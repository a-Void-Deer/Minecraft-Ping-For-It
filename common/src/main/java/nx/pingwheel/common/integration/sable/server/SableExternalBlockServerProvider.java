package nx.pingwheel.common.integration.sable.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import nx.pingwheel.common.integration.sable.SableDiagnostics;
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
	private final SableDiagnostics diagnostics;
	private final ReflectiveApi api;
	private final Map<MinecraftServer, ServerState> servers = new IdentityHashMap<>();

	/** Factory used by the indirect optional bootstrap. */
	public static ExternalBlockServerProvider create() {
		return new SableExternalBlockServerProvider();
	}

	private SableExternalBlockServerProvider() {
		this(SableDiagnostics.global());
	}

	SableExternalBlockServerProvider(SableDiagnostics diagnostics) {
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
		diagnostics.server(
			"provider-init",
			"start",
			"provider", PROVIDER_ID,
			"container_class", SUB_LEVEL_CONTAINER_CLASS,
			"server_sublevel_class", SERVER_SUB_LEVEL_CLASS,
			"tracking_data_class", TRACKING_DATA_CLASS,
			"tracking_point_class", TRACKING_POINT_CLASS);

		ReflectiveApi discovered;

		try {
			discovered = ReflectiveApi.discover(diagnostics);
			diagnostics.server(
				"provider-init",
				"success",
				"provider", PROVIDER_ID,
				"api_methods", discovered.signatures());
		} catch (ReflectiveOperationException | RuntimeException failure) {
			diagnostics.serverException(
				"reflection-discovery",
				"failure",
				failure,
				"provider", PROVIDER_ID,
				"expected_classes", expectedClasses(),
				"expected_methods", expectedMethods());
			discovered = null;
		} catch (LinkageError failure) {
			diagnostics.serverException(
				"reflection-discovery",
				"linkage-error",
				failure,
				"provider", PROVIDER_ID,
				"expected_classes", expectedClasses(),
				"expected_methods", expectedMethods());
			discovered = null;
		}

		this.api = discovered;

		if (discovered == null) {
			linkGuard.disableSilently();
			diagnostics.server(
				"provider-init",
				"disabled",
				"provider", PROVIDER_ID,
				"link_guard_disabled", true);
		}
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	public synchronized ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
		diagnostics.server(
			"provider-selection",
			"selected",
			"provider", PROVIDER_ID,
			"operation", "validate");
		diagnostics.server(
			"candidate-validation",
			"start",
			"candidate", candidate,
			"level", level,
			"provider_usable", usable());

		if (!usable()) {
			return invalidValidation("provider-unavailable", candidate);
		}

		if (level == null || candidate == null) {
			return invalidValidation("missing-level-or-candidate", candidate);
		}

		if (!candidate.isCandidate()) {
			return invalidValidation("not-a-candidate", candidate);
		}

		if (!PROVIDER_ID.equals(candidate.providerId())) {
			return invalidValidation("provider-mismatch", candidate);
		}

		if (!dimensionMatches(level, candidate.dimensionId())) {
			return invalidValidation("dimension-mismatch", candidate);
		}

		Optional<SableExternalBlockLocator> parsed = SableExternalBlockLocator.parse(candidate.providerLocator());
		Optional<ResourceLocation> expectedId = parseBlockId(candidate.expectedBlockRegistryId());

		if (parsed.isEmpty()) {
			diagnostics.server(
				"locator",
				"decode-failure",
				"operation", "validate",
				"provider_locator", candidate.providerLocator(),
				"candidate", candidate);
			return invalidValidation("locator-decode-failure", candidate);
		}

		diagnostics.server(
			"locator",
			"decoded",
			"operation", "validate",
			"provider_locator", candidate.providerLocator(),
			"sublevel_uuid", parsed.get().subLevelId(),
			"local_block_pos", parsed.get().blockPos());

		if (expectedId.isEmpty()) {
			diagnostics.server(
				"candidate-validation",
				"registry-id-decode-failure",
				"operation", "validate",
				"expected_block_registry_id", candidate.expectedBlockRegistryId(),
				"candidate", candidate);
			return invalidValidation("registry-id-decode-failure", candidate);
		}

		LiveResult live = resolveLive(level, parsed.get(), expectedId.get());
		logLiveResult("validate", candidate, live);

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

		ValidationResult.Accepted accepted = new ValidationResult.Accepted(new ValidatedTarget(
			normalized,
			TargetMatchContext.blockEntityBlock(available.hasBlockEntity()),
			available.anchor()));
		diagnostics.server(
			"candidate-validation",
			"accepted",
			"candidate", candidate,
			"normalized_target", normalized,
			"sublevel_uuid", available.locator().subLevelId(),
			"local_block_pos", available.position(),
			"block_registry_id", available.registryId(),
			"block_entity", available.hasBlockEntity(),
			"anchor", available.anchor());
		return accepted;
	}

	@Override
	public synchronized MaterializationResult materialize(
		ServerLevel level, Target.ExternalBlockTarget candidate
	) {
		diagnostics.server(
			"provider-selection",
			"selected",
			"provider", PROVIDER_ID,
			"operation", "materialize");
		diagnostics.server(
			"materialization",
			"start",
			"candidate", candidate,
			"level", level,
			"provider_usable", usable());

		if (!usable()) {
			return invalidMaterialization("provider-unavailable", candidate);
		}

		if (level == null || candidate == null) {
			return invalidMaterialization("missing-level-or-candidate", candidate);
		}

		if (!candidate.isCandidate()) {
			return invalidMaterialization("not-a-candidate", candidate);
		}

		if (!PROVIDER_ID.equals(candidate.providerId())) {
			return invalidMaterialization("provider-mismatch", candidate);
		}

		if (!dimensionMatches(level, candidate.dimensionId())) {
			return invalidMaterialization("dimension-mismatch", candidate);
		}

		Optional<SableExternalBlockLocator> parsed = SableExternalBlockLocator.parse(candidate.providerLocator());
		Optional<ResourceLocation> expectedId = parseBlockId(candidate.expectedBlockRegistryId());

		if (parsed.isEmpty()) {
			diagnostics.server(
				"locator",
				"decode-failure",
				"operation", "materialize",
				"provider_locator", candidate.providerLocator(),
				"candidate", candidate);
			return invalidMaterialization("locator-decode-failure", candidate);
		}

		diagnostics.server(
			"locator",
			"decoded",
			"operation", "materialize",
			"provider_locator", candidate.providerLocator(),
			"sublevel_uuid", parsed.get().subLevelId(),
			"local_block_pos", parsed.get().blockPos());

		if (expectedId.isEmpty()) {
			diagnostics.server(
				"materialization",
				"registry-id-decode-failure",
				"expected_block_registry_id", candidate.expectedBlockRegistryId(),
				"candidate", candidate);
			return invalidMaterialization("registry-id-decode-failure", candidate);
		}

		LiveResult live = resolveLive(level, parsed.get(), expectedId.get());
		logLiveResult("materialize", candidate, live);

		if (live instanceof LiveResult.TemporarilyUnavailable) {
			return new MaterializationResult.TemporarilyUnavailable();
		}

		if (!(live instanceof LiveResult.Available available)) {
			return new MaterializationResult.Invalid();
		}

		MinecraftServer server = level.getServer();
		if (server == null) {
			diagnostics.server(
				"materialization",
				"invalid",
				"reason", "server-unavailable",
				"candidate", candidate);
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
					diagnostics.server(
						"materialization",
						"reused-tracking-point-missing",
						"stable_target_uuid", existingStableId.get(),
						"provider_locator", available.locator().encode(),
						"candidate", candidate);
					return new MaterializationResult.Invalid();
				}

				diagnostics.server(
					"materialization",
					"tracking-point-reused",
					"stable_target_uuid", existingStableId.get(),
					"provider_locator", available.locator().encode(),
					"anchor", available.anchor(),
					"refcount_before", state.references.references(existingStableId.get()));
				ExternalBlockReferenceIndex.Lease lease = state.references.prepare(
					key, existingStableId::orElseThrow);
				diagnostics.server(
					"materialization",
					"lease-prepared",
					"stable_target_uuid", lease.stableId(),
					"lease_new", lease.newlyCreated(),
					"provider_locator", available.locator().encode());
				if (!state.references.commit(lease)) {
					diagnostics.server(
						"materialization",
						"lease-commit-failure",
						"stable_target_uuid", lease.stableId(),
						"provider_locator", available.locator().encode());
					return new MaterializationResult.Invalid();
				}

				state.refreshDiagnostics.available(
					existingStableId.get(), available.locator().encode(), available.anchor());
				diagnostics.server(
					"materialization",
					"lease-committed",
					"stable_target_uuid", lease.stableId(),
					"lease_new", lease.newlyCreated(),
					"refcount_after", state.references.references(lease.stableId()),
					"provider_locator", available.locator().encode(),
					"anchor", available.anchor());
				return materialized(available, entry.trackingId());
			}

			diagnostics.server(
				"materialization",
				"tracking-point-generation-start",
				"provider_locator", available.locator().encode(),
				"sublevel_uuid", available.locator().subLevelId(),
				"local_block_pos", available.position(),
				"anchor", available.anchor());
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

			// Discovery requires the generator's return type to be UUID. A different
			// runtime value is contract drift; fail soft without pretending that an
			// unidentifiable provider resource can be cleaned up by UUID.
			if (!(generated instanceof UUID trackingId)) {
				throw new IllegalStateException("tracking point generator violated its UUID return contract");
			}
			generatedId = trackingId;
			diagnostics.server(
				"materialization",
				"tracking-point-generated",
				"stable_target_uuid", trackingId,
				"provider_locator", available.locator().encode(),
				"sublevel_uuid", available.locator().subLevelId(),
				"local_block_pos", available.position());

			ExternalBlockReferenceIndex.Lease lease = state.references.prepare(key, trackingId::toString);
			diagnostics.server(
				"materialization",
				"lease-prepared",
				"stable_target_uuid", lease.stableId(),
				"lease_new", lease.newlyCreated(),
				"provider_locator", available.locator().encode());
			if (!state.references.commit(lease)) {
				diagnostics.server(
					"materialization",
					"lease-commit-failure",
					"stable_target_uuid", lease.stableId(),
					"provider_locator", available.locator().encode());
				state.references.rollback(lease, retiredId -> removeTrackingPoint(data, trackingId));
				return new MaterializationResult.Invalid();
			}

			Entry created = new Entry(trackingId, data);
			state.entries.put(trackingId.toString(), created);
			state.refreshDiagnostics.available(
				trackingId.toString(), available.locator().encode(), available.anchor());
			diagnostics.server(
				"materialization",
				"lease-committed",
				"stable_target_uuid", lease.stableId(),
				"lease_new", lease.newlyCreated(),
				"refcount_after", state.references.references(lease.stableId()),
				"provider_locator", available.locator().encode(),
				"anchor", available.anchor());

			return materialized(available, trackingId);
		} catch (ReflectiveOperationException | RuntimeException failure) {
			diagnostics.serverException(
				"materialization",
				"exception",
				failure,
				"candidate", candidate,
				"generated_tracking_id", generatedId,
				"provider_locator", available.locator().encode());
			if (generatedId != null && generatedData != null) {
				removeTrackingPoint(generatedData, generatedId);
			}

			return new MaterializationResult.Invalid();
		} catch (LinkageError failure) {
			diagnostics.serverException(
				"materialization",
				"linkage-error",
				failure,
				"candidate", candidate,
				"generated_tracking_id", generatedId,
				"provider_locator", available.locator().encode());
			linkGuard.disableSilently();
			if (generatedId != null && generatedData != null) {
				removeTrackingPoint(generatedData, generatedId);
			}

			return new MaterializationResult.Invalid();
		}
	}

	@Override
	public synchronized RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
		ServerState state = level == null ? null : serverState(level);
		String stableIdValue = committed == null ? null : committed.stableTargetId();

		if (!usable()) {
			return refreshInvalid(state, stableIdValue, committed, "provider-unavailable", null);
		}

		if (level == null || committed == null) {
			return refreshInvalid(state, stableIdValue, committed, "missing-level-or-target", null);
		}

		if (!committed.isCommitted()) {
			return refreshInvalid(state, stableIdValue, committed, "not-committed", null);
		}

		if (!PROVIDER_ID.equals(committed.providerId())) {
			return refreshInvalid(state, stableIdValue, committed, "provider-mismatch", null);
		}

		if (!dimensionMatches(level, committed.dimensionId())) {
			return refreshInvalid(state, stableIdValue, committed, "dimension-mismatch", null);
		}

		Optional<UUID> trackingId = parseUuid(committed.stableTargetId());
		Optional<ResourceLocation> expectedId = parseBlockId(committed.expectedBlockRegistryId());

		if (trackingId.isEmpty()) {
			return refreshInvalid(state, stableIdValue, committed, "tracking-id-decode-failure", null);
		}

		if (expectedId.isEmpty()) {
			return refreshInvalid(state, stableIdValue, committed, "registry-id-decode-failure", null);
		}

		if (state == null) {
			return refreshInvalid(state, stableIdValue, committed, "server-state-missing", null);
		}

		Entry entry = state.entries.get(trackingId.get().toString());
		if (entry == null) {
			return refreshInvalid(state, trackingId.get().toString(), committed, "tracking-point-entry-missing", null);
		}

		try {
			Object trackingPoint = invoke(api.getTrackingPoint, entry.data(), trackingId.get());

			if (trackingPoint == null) {
				return refreshInvalid(state, trackingId.get().toString(), committed,
					"tracking-point-missing", null);
			}

			Object inSubLevel = invoke(api.inSubLevel(), trackingPoint);
			Object subLevelId = invoke(api.subLevelId(), trackingPoint);
			Object point = invoke(api.point(), trackingPoint);

			if (!(inSubLevel instanceof Boolean in) || !in
				|| !(subLevelId instanceof UUID currentSubLevelId)
				|| !(point instanceof Vector3dc currentPoint)
				|| !finite(currentPoint)) {
				return refreshInvalid(state, trackingId.get().toString(), committed,
					"tracking-point-invalid", null);
			}

			Object container = invoke(api.getContainer, null, level);
			if (container == null) {
				return refreshTemporary(state, trackingId.get().toString(), committed,
					"sublevel-container-unavailable");
			}

			Object subLevel = invoke(api.getSubLevel, container, currentSubLevelId);
			if (subLevel == null || isRemoved(subLevel)) {
				return refreshTemporary(state, trackingId.get().toString(), committed,
					"sublevel-unresolved-or-removed");
			}

			BlockPos position = BlockPos.containing(currentPoint.x(), currentPoint.y(), currentPoint.z());
			LiveResult live = resolveLive(
				level, subLevel, currentSubLevelId, position, expectedId.get(), false);

			if (live instanceof LiveResult.TemporarilyUnavailable temporary) {
				return refreshTemporary(
					state, trackingId.get().toString(), committed, temporary.reason());
			}

			if (!(live instanceof LiveResult.Available available)) {
				LiveResult.Invalid invalid = (LiveResult.Invalid) live;
				return refreshInvalid(
					state,
					trackingId.get().toString(),
					committed,
					invalid.reason(),
					invalid.failure());
			}

			// The expected registry identity freezes the target classification for
			// the marker lifetime. A locator migration must not create a new
			// winner/classification merely because a provider observation reports a
			// different Java-side flag.
			ExternalBlockReferenceIndex.LocatorKey newKey = locatorKey(available, committed.hasBlockEntity());
			if (!state.references.migrate(trackingId.get().toString(), newKey)) {
				return refreshInvalid(state, trackingId.get().toString(), committed,
					"locator-collision", null);
			}

			Target.ExternalBlockTarget target = committed(
				level,
				available.locator(),
				trackingId.get(),
				available.registryId(),
				committed.hasBlockEntity());

			logRefreshAvailable(state, trackingId.get().toString(), committed, available);
			return new RefreshResult.Available(
				target,
				TargetMatchContext.blockEntityBlock(committed.hasBlockEntity()),
				available.anchor());
		} catch (ReflectiveOperationException | RuntimeException failure) {
			return refreshInvalid(
				state,
				trackingId.get().toString(),
				committed,
				"refresh-exception",
				failure);
		} catch (LinkageError failure) {
			linkGuard.disableSilently();
			return refreshInvalid(
				state, trackingId.get().toString(), committed, "linkage-error", failure);
		}
	}

	@Override
	public synchronized Optional<ExternalBlockName> resolveName(
		ServerLevel level, Target.ExternalBlockTarget target
	) {
		if (!usable() || level == null || target == null || !PROVIDER_ID.equals(target.providerId())
			|| !dimensionMatches(level, target.dimensionId())) {
			diagnostics.server(
				"name-resolution",
				"rejected",
				"target", target,
				"provider_usable", usable());
			return Optional.empty();
		}

		Optional<SableExternalBlockLocator> parsed = SableExternalBlockLocator.parse(target.providerLocator());
		Optional<ResourceLocation> expectedId = parseBlockId(target.expectedBlockRegistryId());

		if (parsed.isEmpty()) {
			diagnostics.server(
				"locator",
				"decode-failure",
				"operation", "resolve-name",
				"provider_locator", target.providerLocator(),
				"target", target);
			return Optional.empty();
		}

		if (expectedId.isEmpty()) {
			diagnostics.server(
				"name-resolution",
				"registry-id-decode-failure",
				"expected_block_registry_id", target.expectedBlockRegistryId(),
				"target", target);
			return Optional.empty();
		}

		LiveResult live = resolveLive(level, parsed.get(), expectedId.get());
		logLiveResult("resolve-name", target, live);
		if (!(live instanceof LiveResult.Available available)) {
			return Optional.empty();
		}

		Component vanillaName = available.state().getBlock().getName();
		BlockEntity blockEntity = available.level().getBlockEntity(available.position());
		Optional<Component> customName = Optional.empty();

		if (blockEntity instanceof Nameable nameable && nameable.hasCustomName()) {
			Component custom = nameable.getCustomName();
			if (custom != null) {
				customName = Optional.of(custom);
			}
		}

		Optional<ExternalBlockName> result = Optional.of(new ExternalBlockName(vanillaName, customName));
		diagnostics.server(
			"name-resolution",
			"success",
			"target", target,
			"block_registry_id", available.registryId(),
			"block_entity", available.hasBlockEntity(),
			"vanilla_name", vanillaName,
			"custom_name", customName.orElse(null));
		return result;
	}

	@Override
	public synchronized void observeValidationDistance(
		ServerLevel level,
		Target.ExternalBlockTarget target,
		MarkerAnchor anchor,
		double distance,
		boolean withinRange
	) {
		diagnostics.server(
			"distance-validation",
			withinRange ? "within-range" : "out-of-range",
			"provider", PROVIDER_ID,
			"target", target,
			"anchor", anchor,
			"distance", distance,
			"within_range", withinRange);
	}

	@Override
	public synchronized void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
		release(server, committed, null);
	}

	@Override
	public synchronized void release(
		MinecraftServer server, Target.ExternalBlockTarget committed, String markerId
	) {
		if (api == null || server == null || committed == null || !committed.isCommitted()
			|| !PROVIDER_ID.equals(committed.providerId())) {
			diagnostics.server(
				"release",
				"ignored-invalid-target",
				"server", server,
				"target", committed,
				"provider_usable", usable());
			return;
		}

		Optional<UUID> trackingId = parseUuid(committed.stableTargetId());
		ServerState state = servers.get(server);
		if (trackingId.isEmpty() || state == null) {
			diagnostics.server(
				"release",
				"tracking-state-missing",
				"target", committed,
				"tracking_id", committed.stableTargetId());
			return;
		}

		String stableId = trackingId.get().toString();
		Entry entry = state.entries.get(stableId);
		int before = state.references.references(stableId);
		if (entry == null || before <= 0) {
			diagnostics.server(
				"release",
				"ignored-inactive",
				"target", committed,
				"tracking_id", stableId,
				"remaining_refcount", before);
			return;
		}

		diagnostics.server(
			"release",
			"start",
			"target", committed,
			"marker_id", markerId,
			"tracking_id", stableId,
			"provider_locator", committed.providerLocator(),
			"refcount_before", before);
		state.references.release(stableId, retiredId -> {
			try {
				removeTrackingPoint(entry.data(), trackingId.get());
			} finally {
				state.entries.remove(retiredId);
				state.refreshDiagnostics.remove(retiredId);
				diagnostics.server(
					"cleanup",
					"tracking-point-retired",
					"target", committed,
					"marker_id", markerId,
					"tracking_id", retiredId,
					"remaining_refcount", 0);
			}
		});
		diagnostics.server(
			"release",
			"lease-released",
			"target", committed,
			"marker_id", markerId,
			"tracking_id", stableId,
			"remaining_refcount", state.references.references(stableId));
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

		diagnostics.server(
			"cleanup",
			"server-close-start",
			"server", server,
			"tracking_point_count", state.entries.size());

		state.references.close(stableId -> {
			Entry entry = state.entries.remove(stableId);
			if (entry != null) {
				removeTrackingPoint(entry.data(), entry.trackingId());
			}
			state.refreshDiagnostics.remove(stableId);
			diagnostics.server(
				"cleanup",
				"tracking-point-closed",
				"tracking_id", stableId,
				"remaining_refcount", 0);
		});

		state.entries.clear();
		diagnostics.server(
			"cleanup",
			"server-close-complete",
			"server", server,
			"tracking_point_count", 0);
	}

	private boolean usable() {
		return !linkGuard.disabled() && api != null;
	}

	private static boolean dimensionMatches(ServerLevel level, String dimensionId) {
		return dimensionId != null && dimensionId.equals(level.dimension().location().toString());
	}

	private ValidationResult invalidValidation(String reason, Target.ExternalBlockTarget candidate) {
		diagnostics.server(
			"candidate-validation",
			"rejected",
			"rejection_reason", reason,
			"candidate", candidate);
		return new ValidationResult.Invalid();
	}

	private MaterializationResult invalidMaterialization(
		String reason, Target.ExternalBlockTarget candidate
	) {
		diagnostics.server(
			"materialization",
			"rejected",
			"rejection_reason", reason,
			"candidate", candidate);
		return new MaterializationResult.Invalid();
	}

	private void logLiveResult(String operation, Target.ExternalBlockTarget target, LiveResult result) {
		logLiveResult(diagnostics, operation, target, result);
	}

	static void logLiveResult(
		SableDiagnostics diagnostics,
		String operation,
		Target.ExternalBlockTarget target,
		LiveResult result
	) {
		Objects.requireNonNull(diagnostics, "diagnostics");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(result, "result");

		if (result instanceof LiveResult.Available) {
			return;
		}

		if (result instanceof LiveResult.TemporarilyUnavailable temporary) {
			diagnostics.server(
				"live-resolution",
				temporary.reason(),
				"operation", operation,
				"target", target);
			return;
		}

		LiveResult.Invalid invalid = (LiveResult.Invalid) result;
		if (invalid.failure() != null) {
			diagnostics.serverException(
				"live-resolution",
				invalid.reason(),
				invalid.failure(),
				"operation", operation,
				"target", target);
		} else {
			diagnostics.server(
				"live-resolution",
				invalid.reason(),
				"operation", operation,
				"target", target);
		}
	}

	private RefreshResult refreshTemporary(
		ServerState state,
		String stableId,
		Target.ExternalBlockTarget committed,
		String reason
	) {
		boolean emit = state == null || stableId == null
			|| state.refreshDiagnostics.temporarilyUnavailable(stableId, reason);

		if (emit) {
			diagnostics.refresh(
				"refresh",
				"temporary-unavailable",
				"unavailable_reason", reason,
				"stable_target_uuid", stableId,
				"committed_target", committed);
		}

		return new RefreshResult.TemporarilyUnavailable();
	}

	private RefreshResult refreshInvalid(
		ServerState state,
		String stableId,
		Target.ExternalBlockTarget committed,
		String reason,
		Throwable failure
	) {
		boolean emit = state == null || stableId == null
			|| state.refreshDiagnostics.invalid(stableId, reason);

		if (emit) {
			if (failure != null) {
				diagnostics.refreshException(
					"refresh",
					"exception",
					failure,
					"invalidation_reason", reason,
					"stable_target_uuid", stableId,
					"committed_target", committed);
			} else {
				diagnostics.refresh(
					"refresh",
					"invalidation",
					"invalidation_reason", reason,
					"stable_target_uuid", stableId,
					"committed_target", committed);
			}
		}

		return new RefreshResult.Invalid();
	}

	private void logRefreshAvailable(
		ServerState state,
		String stableId,
		Target.ExternalBlockTarget committed,
		LiveResult.Available available
	) {
		boolean changed = state.refreshDiagnostics.available(
			stableId, available.locator().encode(), available.anchor());

		if (changed) {
			diagnostics.refresh(
				"refresh",
				"locator-or-anchor-changed",
				"stable_target_uuid", stableId,
				"previous_target", committed,
				"current_locator", available.locator().encode(),
				"current_anchor", available.anchor(),
				"block_registry_id", available.registryId(),
				"block_entity", available.hasBlockEntity());
		}
	}

	private LiveResult resolveLive(
		ServerLevel level, SableExternalBlockLocator locator, ResourceLocation expectedId
	) {
		return resolveLive(level, locator, expectedId, true);
	}

	private LiveResult resolveLive(
		ServerLevel level,
		SableExternalBlockLocator locator,
		ResourceLocation expectedId,
		boolean logExceptions
	) {
		try {
			Object container = invoke(api.getContainer, null, level);
			if (container == null) {
				return new LiveResult.TemporarilyUnavailable("sublevel-container-unavailable");
			}

			Object subLevel = invoke(api.getSubLevel, container, locator.subLevelId());
			if (subLevel == null || isRemoved(subLevel)) {
				return new LiveResult.TemporarilyUnavailable("sublevel-unresolved-or-removed");
			}

			return resolveLive(level, subLevel, locator.subLevelId(), locator.blockPos(), expectedId, logExceptions);
		} catch (ReflectiveOperationException | RuntimeException failure) {
			return new LiveResult.Invalid("live-resolution-exception", failure);
		} catch (LinkageError failure) {
			linkGuard.disableSilently();
			return new LiveResult.Invalid("linkage-error", failure);
		}
	}

	private LiveResult resolveLive(
		ServerLevel parentLevel,
		Object subLevel,
		UUID subLevelId,
		BlockPos position,
		ResourceLocation expectedId,
		boolean logExceptions
	) throws ReflectiveOperationException {
		if (isRemoved(subLevel)) {
			return new LiveResult.TemporarilyUnavailable("sublevel-removed");
		}

		Object localLevelObject = invoke(api.getLevel, subLevel);
		if (!(localLevelObject instanceof ServerLevel localLevel) || localLevel != parentLevel) {
			return new LiveResult.Invalid("sublevel-level-mismatch", null);
		}

		if (!localLevel.isLoaded(position)) {
			return new LiveResult.TemporarilyUnavailable("unloaded-or-missing-state");
		}

		BlockState state = localLevel.getBlockState(position);
		if (state == null || state.isAir()) {
			return new LiveResult.Invalid("air-or-missing-state", null);
		}

		ResourceLocation actualId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (actualId == null || !expectedId.equals(actualId)) {
			return new LiveResult.Invalid("registry-identity-mismatch", null);
		}

		Object poseObject = invoke(api.logicalPose, subLevel);
		if (!(poseObject instanceof Pose3dc pose)) {
			return new LiveResult.Invalid("logical-pose-missing", null);
		}

		Vector3d globalCenter = pose.transformPosition(
			new Vector3d(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5),
			new Vector3d());

		if (!finite(globalCenter)) {
			return new LiveResult.Invalid("non-finite-anchor", null);
		}

		boolean hasBlockEntity = BlockEntityClassification.hasBlockEntity(state);
		LiveResult.Available available = new LiveResult.Available(
			subLevel,
			localLevel,
			position,
			state,
			actualId,
			hasBlockEntity,
			new MarkerAnchor(globalCenter.x, globalCenter.y, globalCenter.z),
			new SableExternalBlockLocator(subLevelId, position));

		if (logExceptions) {
			diagnostics.server(
				"live-resolution",
				"available",
				"sublevel_uuid", subLevelId,
				"local_block_pos", position,
				"block_registry_id", actualId,
				"block_entity", hasBlockEntity,
				"anchor", available.anchor(),
				"provider_locator", available.locator().encode());
		}

		return available;
	}

	private boolean trackingPointExists(Entry entry) throws ReflectiveOperationException {
		return invoke(api.getTrackingPoint, entry.data(), entry.trackingId()) != null;
	}

	private void removeTrackingPoint(Object data, UUID trackingId) {
		try {
			invoke(api.removeTrackingPoint, data, trackingId);
			diagnostics.server(
				"cleanup",
				"tracking-point-removed",
				"tracking_id", trackingId);
		} catch (ReflectiveOperationException | RuntimeException failure) {
			diagnostics.serverException(
				"cleanup",
				"tracking-point-removal-failure",
				failure,
				"tracking_id", trackingId,
				"data", data);
		} catch (LinkageError failure) {
			diagnostics.serverException(
				"cleanup",
				"tracking-point-removal-linkage-error",
				failure,
				"tracking_id", trackingId,
				"data", data);
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

	private static List<String> expectedClasses() {
		return List.of(
			SUB_LEVEL_CONTAINER_CLASS,
			SERVER_SUB_LEVEL_CLASS,
			TRACKING_DATA_CLASS,
			TRACKING_POINT_CLASS);
	}

	private static List<String> expectedMethods() {
		return List.of(
			"SubLevelContainer#getContainer(ServerLevel|Level)",
			"SubLevelContainer#getSubLevel(UUID)",
			"ServerSubLevel#getLevel()",
			"ServerSubLevel#isRemoved()",
			"ServerSubLevel#logicalPose()",
			"SubLevelTrackingPointSavedData#getOrLoad(ServerLevel)",
			"SubLevelTrackingPointSavedData#generateTrackingPoint(Vec3,ServerSubLevel)",
			"SubLevelTrackingPointSavedData#getTrackingPoint(UUID)",
			"SubLevelTrackingPointSavedData#removeTrackingPoint(UUID)",
			"TrackingPoint#inSubLevel()",
			"TrackingPoint#subLevelID()",
			"TrackingPoint#point()");
	}

	sealed interface LiveResult permits LiveResult.Available, LiveResult.TemporarilyUnavailable,
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

		record TemporarilyUnavailable(String reason) implements LiveResult {
		}

		record Invalid(String reason, Throwable failure) implements LiveResult {
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
		private final SableRefreshLogGate refreshDiagnostics = new SableRefreshLogGate();
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

		private static ReflectiveApi discover(SableDiagnostics diagnostics)
			throws ReflectiveOperationException {
			Class<?> containerClass = loadClass(SUB_LEVEL_CONTAINER_CLASS, diagnostics);
			Class<?> serverSubLevelClass = loadClass(SERVER_SUB_LEVEL_CLASS, diagnostics);
			Class<?> trackingDataClass = loadClass(TRACKING_DATA_CLASS, diagnostics);
			Class<?> trackingPointClass = loadClass(TRACKING_POINT_CLASS, diagnostics);

			return new ReflectiveApi(
				containerLookup(containerClass, diagnostics),
				discovered(containerClass, "getSubLevel", Object.class, false, diagnostics, UUID.class),
				discovered(serverSubLevelClass, "getLevel", Level.class, false, diagnostics),
				discovered(serverSubLevelClass, "isRemoved", boolean.class, false, diagnostics),
				discovered(serverSubLevelClass, "logicalPose", Pose3dc.class, false, diagnostics),
				discovered(trackingDataClass, "getOrLoad", trackingDataClass, true, diagnostics, ServerLevel.class),
				discovered(trackingDataClass, "generateTrackingPoint", UUID.class, false, diagnostics, Vec3.class, serverSubLevelClass),
				discovered(trackingDataClass, "getTrackingPoint", trackingPointClass, false, diagnostics, UUID.class),
				discovered(trackingDataClass, "removeTrackingPoint", void.class, false, diagnostics, UUID.class),
				discovered(trackingPointClass, "inSubLevel", boolean.class, false, diagnostics),
				discovered(trackingPointClass, "subLevelID", UUID.class, false, diagnostics),
				discovered(trackingPointClass, "point", Vector3dc.class, false, diagnostics));
		}

		private static Class<?> loadClass(String name, SableDiagnostics diagnostics)
			throws ClassNotFoundException {
			Class<?> loaded = Class.forName(
				name, false, SableExternalBlockServerProvider.class.getClassLoader());
			diagnostics.server(
				"reflection-discovery",
				"class-found",
				"class_name", loaded.getName());
			return loaded;
		}

		private static Method containerLookup(
			Class<?> containerClass, SableDiagnostics diagnostics
		) throws ReflectiveOperationException {
			try {
				return discovered(
					containerClass, "getContainer", Object.class, true, diagnostics, ServerLevel.class);
			} catch (NoSuchMethodException missingServerLevelOverload) {
				diagnostics.server(
					"reflection-discovery",
					"server-level-overload-missing",
					"class_name", containerClass.getName(),
					"fallback_signature", "getContainer(Level)");
				return discovered(
					containerClass, "getContainer", Object.class, true, diagnostics, Level.class);
			}
		}

		private static Method discovered(
			Class<?> owner,
			String name,
			Class<?> returnType,
			boolean isStatic,
			SableDiagnostics diagnostics,
			Class<?>... parameters
		) throws ReflectiveOperationException {
			Method method = required(owner, name, returnType, isStatic, parameters);
			diagnostics.server(
				"reflection-discovery",
				"method-found",
				"class_name", owner.getName(),
				"method_signature", method.toGenericString());
			return method;
		}

		private List<String> signatures() {
			return List.of(
				getContainer.toGenericString(),
				getSubLevel.toGenericString(),
				getLevel.toGenericString(),
				isRemoved.toGenericString(),
				logicalPose.toGenericString(),
				getOrLoad.toGenericString(),
				generateTrackingPoint.toGenericString(),
				getTrackingPoint.toGenericString(),
				removeTrackingPoint.toGenericString(),
				inSubLevel.toGenericString(),
				subLevelId.toGenericString(),
				point.toGenericString());
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
