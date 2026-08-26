package nx.pingwheel.common.integration.sable.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.integration.IntegrationLinkGuard;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ExternalBlockReferenceIndex;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.resolve.BlockEntityClassification;

/**
 * Reflective server adapter for Sable's external block storage.
 *
 * <p>No Sable main class is linked by the common/server path.  The public
 * Sable 2.0.5 API shape is discovered once after the optional mod has been
 * detected; every later operation is guarded and fails soft when the shape is
 * absent or a provider object is temporarily unavailable.
 */
public final class SableExternalBlockServerProvider implements ExternalBlockServerProvider {

	public static final String PROVIDER_ID = "sable";

	private static final String SUB_LEVEL_CONTAINER_CLASS =
		"dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
	private static final String SERVER_SUB_LEVEL_CLASS =
		"dev.ryanhcode.sable.sublevel.ServerSubLevel";
	private static final String TRACKING_DATA_CLASS =
		"dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData";
	private static final String TRACKING_POINT_CLASS =
		"dev.ryanhcode.sable.sublevel.tracking_points.TrackingPoint";

	private final IntegrationLinkGuard linkGuard = new IntegrationLinkGuard(PROVIDER_ID);
	private final ReflectiveApi api;
	private final Map<MinecraftServer, ServerState> servers = new IdentityHashMap<>();

	/** Factory used by the indirect optional bootstrap. */
	public static ExternalBlockServerProvider create() {
		return new SableExternalBlockServerProvider();
	}

	private SableExternalBlockServerProvider() {
		ReflectiveApi discovered;

		try {
			discovered = ReflectiveApi.discover();
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			discovered = null;
		} catch (LinkageError error) {
			linkGuard.disableSilently();
			discovered = null;
		}

		this.api = discovered;

		if (discovered == null) {
			linkGuard.disableSilently();
		}
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	public synchronized ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
		if (!usable() || level == null || candidate == null || !candidate.isCandidate()
			|| !PROVIDER_ID.equals(candidate.providerId())
			|| !dimensionMatches(level, candidate.dimensionId())) {
			return new ValidationResult.Invalid();
		}

		Optional<SableExternalBlockLocator> parsed = SableExternalBlockLocator.parse(candidate.providerLocator());
		Optional<ResourceLocation> expectedId = parseBlockId(candidate.expectedBlockRegistryId());

		if (parsed.isEmpty() || expectedId.isEmpty()) {
			return new ValidationResult.Invalid();
		}

		LiveResult live = resolveLive(level, parsed.get(), expectedId.get());

		if (live instanceof LiveResult.TemporarilyUnavailable) {
			return new ValidationResult.TemporarilyUnavailable();
		}

		if (!(live instanceof LiveResult.Available available)) {
			return new ValidationResult.Invalid();
		}

		Target.ExternalBlockTarget normalized = candidate(
			level,
			available.locator(),
			available.registryId(),
			available.hasBlockEntity());

		return new ValidationResult.Accepted(new ValidatedTarget(
			normalized,
			TargetMatchContext.blockEntityBlock(available.hasBlockEntity()),
			available.anchor()));
	}

	@Override
	public synchronized MaterializationResult materialize(
		ServerLevel level, Target.ExternalBlockTarget candidate
	) {
		if (!usable() || level == null || candidate == null || !candidate.isCandidate()
			|| !PROVIDER_ID.equals(candidate.providerId())
			|| !dimensionMatches(level, candidate.dimensionId())) {
			return new MaterializationResult.Invalid();
		}

		Optional<SableExternalBlockLocator> parsed = SableExternalBlockLocator.parse(candidate.providerLocator());
		Optional<ResourceLocation> expectedId = parseBlockId(candidate.expectedBlockRegistryId());

		if (parsed.isEmpty() || expectedId.isEmpty()) {
			return new MaterializationResult.Invalid();
		}

		LiveResult live = resolveLive(level, parsed.get(), expectedId.get());

		if (live instanceof LiveResult.TemporarilyUnavailable) {
			return new MaterializationResult.TemporarilyUnavailable();
		}

		if (!(live instanceof LiveResult.Available available)) {
			return new MaterializationResult.Invalid();
		}

		MinecraftServer server = level.getServer();
		if (server == null) {
			return new MaterializationResult.Invalid();
		}

		ServerState state = servers.computeIfAbsent(server, ignored -> new ServerState());
		ExternalBlockReferenceIndex.LocatorKey key = locatorKey(available);
		Optional<String> existingStableId = state.references.stableFor(key);
		Entry entry = existingStableId.map(state.entries::get).orElse(null);

		UUID generatedId = null;
		Object generatedData = null;
		try {
			if (existingStableId.isPresent()) {
				if (entry == null || !trackingPointExists(entry)) {
					return new MaterializationResult.Invalid();
				}

				ExternalBlockReferenceIndex.Lease lease = state.references.prepare(
					key, existingStableId::orElseThrow);
				if (!state.references.commit(lease)) {
					return new MaterializationResult.Invalid();
				}

				return materialized(available, entry.trackingId());
			}

			Object data = invoke(api.getOrLoad, null, level);
			generatedData = data;
			Object generated = invoke(
				api.generateTrackingPoint,
				data,
				new Vec3(
					available.position().getX() + 0.5,
					available.position().getY() + 0.5,
					available.position().getZ() + 0.5),
				available.subLevel());

			if (!(generated instanceof UUID trackingId)) {
				return new MaterializationResult.Invalid();
			}
			generatedId = trackingId;

			ExternalBlockReferenceIndex.Lease lease = state.references.prepare(key, trackingId::toString);
			if (!state.references.commit(lease)) {
				state.references.rollback(lease, retiredId -> removeTrackingPoint(data, trackingId));
				return new MaterializationResult.Invalid();
			}

			Entry created = new Entry(trackingId, data);
			state.entries.put(trackingId.toString(), created);

			return materialized(available, trackingId);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			if (generatedId != null && generatedData != null) {
				removeTrackingPoint(generatedData, generatedId);
			}

			return new MaterializationResult.Invalid();
		} catch (LinkageError error) {
			linkGuard.disableSilently();
			if (generatedId != null && generatedData != null) {
				removeTrackingPoint(generatedData, generatedId);
			}

			return new MaterializationResult.Invalid();
		}
	}

	@Override
	public synchronized RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
		if (!usable() || level == null || committed == null || !committed.isCommitted()
			|| !PROVIDER_ID.equals(committed.providerId())
			|| !dimensionMatches(level, committed.dimensionId())) {
			return new RefreshResult.Invalid();
		}

		Optional<UUID> trackingId = parseUuid(committed.stableTargetId());
		Optional<ResourceLocation> expectedId = parseBlockId(committed.expectedBlockRegistryId());
		ServerState state = serverState(level);

		if (trackingId.isEmpty() || expectedId.isEmpty() || state == null) {
			return new RefreshResult.Invalid();
		}

		Entry entry = state.entries.get(trackingId.get().toString());
		if (entry == null) {
			return new RefreshResult.Invalid();
		}

		try {
			Object trackingPoint = invoke(api.getTrackingPoint, entry.data(), trackingId.get());

			if (trackingPoint == null) {
				return new RefreshResult.Invalid();
			}

			Object inSubLevel = invoke(api.inSubLevel(), trackingPoint);
			Object subLevelId = invoke(api.subLevelId(), trackingPoint);
			Object point = invoke(api.point(), trackingPoint);

			if (!(inSubLevel instanceof Boolean in) || !in
				|| !(subLevelId instanceof UUID currentSubLevelId)
				|| !(point instanceof Vector3dc currentPoint)
				|| !finite(currentPoint)) {
				return new RefreshResult.Invalid();
			}

			Object container = invoke(api.getContainer, null, level);
			if (container == null) {
				return new RefreshResult.TemporarilyUnavailable();
			}

			Object subLevel = invoke(api.getSubLevel, container, currentSubLevelId);
			if (subLevel == null || isRemoved(subLevel)) {
				return new RefreshResult.TemporarilyUnavailable();
			}

			BlockPos position = BlockPos.containing(currentPoint.x(), currentPoint.y(), currentPoint.z());
			LiveResult live = resolveLive(level, subLevel, currentSubLevelId, position, expectedId.get());

			if (live instanceof LiveResult.TemporarilyUnavailable) {
				return new RefreshResult.TemporarilyUnavailable();
			}

			if (!(live instanceof LiveResult.Available available)) {
				return new RefreshResult.Invalid();
			}

			// The expected registry identity freezes the target classification for
			// the marker lifetime. A locator migration must not create a new
			// winner/classification merely because a provider observation reports a
			// different Java-side flag.
			ExternalBlockReferenceIndex.LocatorKey newKey = locatorKey(available, committed.hasBlockEntity());
			if (!state.references.migrate(trackingId.get().toString(), newKey)) {
				return new RefreshResult.Invalid();
			}

			Target.ExternalBlockTarget target = committed(
				level,
				available.locator(),
				trackingId.get(),
				available.registryId(),
				committed.hasBlockEntity());

			return new RefreshResult.Available(
				target,
				TargetMatchContext.blockEntityBlock(committed.hasBlockEntity()),
				available.anchor());
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return new RefreshResult.Invalid();
		} catch (LinkageError error) {
			linkGuard.disableSilently();
			return new RefreshResult.Invalid();
		}
	}

	@Override
	public synchronized Optional<ExternalBlockName> resolveName(
		ServerLevel level, Target.ExternalBlockTarget target
	) {
		if (!usable() || level == null || target == null || !PROVIDER_ID.equals(target.providerId())
			|| !dimensionMatches(level, target.dimensionId())) {
			return Optional.empty();
		}

		Optional<SableExternalBlockLocator> parsed = SableExternalBlockLocator.parse(target.providerLocator());
		Optional<ResourceLocation> expectedId = parseBlockId(target.expectedBlockRegistryId());

		if (parsed.isEmpty() || expectedId.isEmpty()) {
			return Optional.empty();
		}

		LiveResult live = resolveLive(level, parsed.get(), expectedId.get());
		if (!(live instanceof LiveResult.Available available)) {
			return Optional.empty();
		}

		BlockEntity blockEntity = available.level().getBlockEntity(available.position());
		Optional<Component> customName = Optional.empty();

		if (blockEntity instanceof Nameable nameable && nameable.hasCustomName()) {
			Component custom = nameable.getCustomName();
			if (custom != null) {
				customName = Optional.of(custom);
			}
		}

		return Optional.of(new ExternalBlockName(available.state().getBlock().getName(), customName));
	}

	@Override
	public synchronized void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
		if (api == null || server == null || committed == null || !committed.isCommitted()
			|| !PROVIDER_ID.equals(committed.providerId())) {
			return;
		}

		Optional<UUID> trackingId = parseUuid(committed.stableTargetId());
		ServerState state = servers.get(server);
		if (trackingId.isEmpty() || state == null) {
			return;
		}

		Entry entry = state.entries.get(trackingId.get().toString());
		if (entry == null || state.references.references(trackingId.get().toString()) <= 0) {
			return;
		}

		state.references.release(trackingId.get().toString(), retiredId -> {
			try {
				removeTrackingPoint(entry.data(), trackingId.get());
			} finally {
				state.entries.remove(retiredId);
			}
		});
	}

	@Override
	public synchronized void close(MinecraftServer server) {
		if (server == null) {
			return;
		}

		ServerState state = servers.remove(server);
		if (state == null) {
			return;
		}

		state.references.close(stableId -> {
			Entry entry = state.entries.remove(stableId);
			if (entry != null) {
				removeTrackingPoint(entry.data(), entry.trackingId());
			}
		});

		state.entries.clear();
	}

	private boolean usable() {
		return !linkGuard.disabled() && api != null;
	}

	private static boolean dimensionMatches(ServerLevel level, String dimensionId) {
		return dimensionId != null && dimensionId.equals(level.dimension().location().toString());
	}

	private LiveResult resolveLive(
		ServerLevel level, SableExternalBlockLocator locator, ResourceLocation expectedId
	) {
		try {
			Object container = invoke(api.getContainer, null, level);
			if (container == null) {
				return new LiveResult.TemporarilyUnavailable();
			}

			Object subLevel = invoke(api.getSubLevel, container, locator.subLevelId());
			if (subLevel == null || isRemoved(subLevel)) {
				return new LiveResult.TemporarilyUnavailable();
			}

			return resolveLive(level, subLevel, locator.subLevelId(), locator.blockPos(), expectedId);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return new LiveResult.Invalid();
		} catch (LinkageError error) {
			linkGuard.disableSilently();
			return new LiveResult.Invalid();
		}
	}

	private LiveResult resolveLive(
		ServerLevel parentLevel,
		Object subLevel,
		UUID subLevelId,
		BlockPos position,
		ResourceLocation expectedId
	) throws ReflectiveOperationException {
		if (isRemoved(subLevel)) {
			return new LiveResult.TemporarilyUnavailable();
		}

		Object localLevelObject = invoke(api.getLevel, subLevel);
		if (!(localLevelObject instanceof ServerLevel localLevel) || localLevel != parentLevel) {
			return new LiveResult.Invalid();
		}

		if (!localLevel.isLoaded(position)) {
			return new LiveResult.TemporarilyUnavailable();
		}

		BlockState state = localLevel.getBlockState(position);
		if (state == null || state.isAir()) {
			return new LiveResult.Invalid();
		}

		ResourceLocation actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (actualId == null || !expectedId.equals(actualId)) {
			return new LiveResult.Invalid();
		}

		Object poseObject = invoke(api.logicalPose, subLevel);
		if (!(poseObject instanceof Pose3dc pose)) {
			return new LiveResult.Invalid();
		}

		Vector3d globalCenter = pose.transformPosition(
			new Vector3d(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5),
			new Vector3d());

		if (!finite(globalCenter)) {
			return new LiveResult.Invalid();
		}

		boolean hasBlockEntity = BlockEntityClassification.hasBlockEntity(state);
		return new LiveResult.Available(
			subLevel,
			localLevel,
			position,
			state,
			actualId,
			hasBlockEntity,
			new MarkerAnchor(globalCenter.x, globalCenter.y, globalCenter.z),
			new SableExternalBlockLocator(subLevelId, position));
	}

	private boolean trackingPointExists(Entry entry) throws ReflectiveOperationException {
		return invoke(api.getTrackingPoint, entry.data(), entry.trackingId()) != null;
	}

	private void removeTrackingPoint(Object data, UUID trackingId) {
		try {
			invoke(api.removeTrackingPoint, data, trackingId);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Provider cleanup is intentionally silent and idempotent at the index.
		} catch (LinkageError error) {
			linkGuard.disableSilently();
		}
	}

	private static ExternalBlockReferenceIndex.LocatorKey locatorKey(LiveResult.Available available) {
		return locatorKey(available, available.hasBlockEntity());
	}

	private static ExternalBlockReferenceIndex.LocatorKey locatorKey(
		LiveResult.Available available, boolean hasBlockEntity
	) {
		return new ExternalBlockReferenceIndex.LocatorKey(
			PROVIDER_ID,
			available.locator().encode(),
			available.registryId().toString(),
			hasBlockEntity);
	}

	private boolean isRemoved(Object subLevel) throws ReflectiveOperationException {
		Object removed = invoke(api.isRemoved, subLevel);
		return removed instanceof Boolean value && value;
	}

	private ServerState serverState(ServerLevel level) {
		MinecraftServer server = level.getServer();
		return server == null ? null : servers.get(server);
	}

	private static MaterializationResult materialized(LiveResult.Available available, UUID trackingId) {
		return new MaterializationResult.Materialized(new MaterializedTarget(
			Target.ExternalBlockTarget.committed(
				available.level().dimension().location().toString(),
				PROVIDER_ID,
				trackingId.toString(),
				available.registryId().toString(),
				available.locator().encode(),
				available.hasBlockEntity()),
			TargetMatchContext.blockEntityBlock(available.hasBlockEntity()),
			available.anchor()));
	}

	private static Target.ExternalBlockTarget candidate(
		ServerLevel level, SableExternalBlockLocator locator, ResourceLocation registryId, boolean hasBlockEntity
	) {
		return Target.ExternalBlockTarget.candidate(
			level.dimension().location().toString(),
			PROVIDER_ID,
			registryId.toString(),
			locator.encode(),
			hasBlockEntity);
	}

	private static Target.ExternalBlockTarget committed(
		ServerLevel level,
		SableExternalBlockLocator locator,
		UUID trackingId,
		ResourceLocation registryId,
		boolean hasBlockEntity
	) {
		return Target.ExternalBlockTarget.committed(
			level.dimension().location().toString(),
			PROVIDER_ID,
			trackingId.toString(),
			registryId.toString(),
			locator.encode(),
			hasBlockEntity);
	}

	private static Optional<ResourceLocation> parseBlockId(String value) {
		if (value == null || value.length() > Target.ExternalBlockTarget.MAX_IDENTIFIER_LENGTH) {
			return Optional.empty();
		}

		ResourceLocation parsed = ResourceLocation.tryParse(value);
		return parsed == null ? Optional.empty() : Optional.of(parsed);
	}

	private static Optional<UUID> parseUuid(String value) {
		if (value == null || value.length() != 36) {
			return Optional.empty();
		}

		try {
			UUID parsed = UUID.fromString(value);
			return parsed.toString().equals(value) ? Optional.of(parsed) : Optional.empty();
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}

	private static boolean finite(Vector3dc vector) {
		return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
	}

	private static Object invoke(Method method, Object receiver, Object... arguments)
		throws ReflectiveOperationException {
		try {
			return method.invoke(receiver, arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();

			if (cause instanceof LinkageError linkageError) {
				throw linkageError;
			}

			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}

			if (cause instanceof Error error) {
				throw error;
			}

			throw new ReflectiveOperationException(cause);
		}
	}

	private sealed interface LiveResult permits LiveResult.Available, LiveResult.TemporarilyUnavailable,
		LiveResult.Invalid {

		record Available(
			Object subLevel,
			ServerLevel level,
			BlockPos position,
			BlockState state,
			ResourceLocation registryId,
			boolean hasBlockEntity,
			MarkerAnchor anchor,
			SableExternalBlockLocator locator
		) implements LiveResult {
		}

		record TemporarilyUnavailable() implements LiveResult {
		}

		record Invalid() implements LiveResult {
		}
	}

	private static final class Entry {
		private final UUID trackingId;
		private final Object data;

		private Entry(UUID trackingId, Object data) {
			this.trackingId = trackingId;
			this.data = data;
		}

		private UUID trackingId() {
			return trackingId;
		}

		private Object data() {
			return data;
		}
	}

	private static final class ServerState {
		private final ExternalBlockReferenceIndex references = new ExternalBlockReferenceIndex();
		private final Map<String, Entry> entries = new LinkedHashMap<>();
	}

	private record ReflectiveApi(
		Method getContainer,
		Method getSubLevel,
		Method getLevel,
		Method isRemoved,
		Method logicalPose,
		Method getOrLoad,
		Method generateTrackingPoint,
		Method getTrackingPoint,
		Method removeTrackingPoint,
		Method inSubLevel,
		Method subLevelId,
		Method point
	) {

		private static ReflectiveApi discover() throws ReflectiveOperationException {
			Class<?> containerClass = Class.forName(SUB_LEVEL_CONTAINER_CLASS, false,
				SableExternalBlockServerProvider.class.getClassLoader());
			Class<?> serverSubLevelClass = Class.forName(SERVER_SUB_LEVEL_CLASS, false,
				SableExternalBlockServerProvider.class.getClassLoader());
			Class<?> trackingDataClass = Class.forName(TRACKING_DATA_CLASS, false,
				SableExternalBlockServerProvider.class.getClassLoader());
			Class<?> trackingPointClass = Class.forName(TRACKING_POINT_CLASS, false,
				SableExternalBlockServerProvider.class.getClassLoader());

			return new ReflectiveApi(
				containerLookup(containerClass),
				required(containerClass, "getSubLevel", Object.class, UUID.class, false),
				required(serverSubLevelClass, "getLevel", Level.class, false),
				required(serverSubLevelClass, "isRemoved", boolean.class, false),
				required(serverSubLevelClass, "logicalPose", Pose3dc.class, false),
				required(trackingDataClass, "getOrLoad", trackingDataClass, ServerLevel.class, true),
				required(trackingDataClass, "generateTrackingPoint", UUID.class, false, Vec3.class, serverSubLevelClass),
				required(trackingDataClass, "getTrackingPoint", trackingPointClass, UUID.class, false),
				required(trackingDataClass, "removeTrackingPoint", void.class, UUID.class, false),
				required(trackingPointClass, "inSubLevel", boolean.class, false),
				required(trackingPointClass, "subLevelID", UUID.class, false),
				required(trackingPointClass, "point", Vector3dc.class, false));
		}

		private static Method containerLookup(Class<?> containerClass) throws ReflectiveOperationException {
			try {
				return required(containerClass, "getContainer", Object.class, ServerLevel.class, true);
			} catch (NoSuchMethodException missingServerLevelOverload) {
				return required(containerClass, "getContainer", Object.class, Level.class, true);
			}
		}

		private static Method required(
			Class<?> owner,
			String name,
			Class<?> returnType,
			Class<?> firstParameter,
			boolean isStatic,
			Class<?>... remainingParameters
		) throws ReflectiveOperationException {
			Class<?>[] parameters = new Class<?>[remainingParameters.length + 1];
			parameters[0] = firstParameter;
			System.arraycopy(remainingParameters, 0, parameters, 1, remainingParameters.length);
			return required(owner, name, returnType, isStatic, parameters);
		}

		private static Method required(
			Class<?> owner,
			String name,
			Class<?> returnType,
			boolean isStatic,
			Class<?>... parameters
		) throws ReflectiveOperationException {
			Method method = owner.getMethod(name, parameters);
			boolean staticMethod = Modifier.isStatic(method.getModifiers());

			if (staticMethod != isStatic || !returnTypeMatches(returnType, method.getReturnType())) {
				throw new NoSuchMethodException(name);
			}

			return method;
		}

		private static boolean returnTypeMatches(Class<?> expected, Class<?> actual) {
			if (expected == void.class) {
				return actual == void.class;
			}

			return expected.isAssignableFrom(actual);
		}
	}
}
