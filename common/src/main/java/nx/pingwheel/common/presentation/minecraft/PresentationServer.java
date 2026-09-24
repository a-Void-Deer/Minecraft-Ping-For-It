package nx.pingwheel.common.presentation.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.name.MinecraftTargetNameResolver;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.*;

import java.util.*;
import java.util.function.Function;

/** Server-thread, world-lifetime presentation leases. Cached values never hold world objects. */
public final class PresentationServer {
	private static final int MAX_LEASES = 1024;
	private static final int MAX_CAPTURE_WORK_PER_TICK = 4096;
	private static final int MAX_CAPTURES_PER_TICK = 32;
	private static final Map<UUID, Session> SESSIONS = new HashMap<>();
	private static final Map<Long, Lease> LEASES = new LinkedHashMap<>();
	private static MinecraftServer activeServer;
	private static PresentationRegistry registry = new PresentationRegistry();
	private static int cursor;
	private static String policyFingerprint = "";

	private PresentationServer() {}

	private static final class Session {
		final long epoch;
		final Map<String, Integer> schemas;
		final Map<String, List<PresentationField>> manifest;
		final Map<Long, Map<String, PresentationSection>> sent = new HashMap<>();
		Set<String> subscriptionFields = Set.of();
		long subscription;
		long view;
		long revision;
		boolean ready;
		Session(long epoch, Map<String, Integer> schemas, Map<String, List<PresentationField>> manifest) {
			this.epoch = epoch; this.schemas = Map.copyOf(schemas); this.manifest = Map.copyOf(manifest);
		}
	}
	private static final class Source {
		PresentationSection value;
		Set<String> demand = Set.of();
		long nextSample;
		Source(String id, int schema) { value = PresentationSection.empty(id, schema); }
	}
	private static final class Lease {
		ServerMarker marker;
		String ownerName;
		final Map<String, Source> sources = new LinkedHashMap<>();
		Lease(ServerMarker marker, String ownerName) { this.marker = marker; this.ownerName = ownerName; }
	}

	public static void activate(MinecraftServer server) {
		if (activeServer == server) return;
		SESSIONS.clear(); LEASES.clear(); cursor = 0;
		activeServer = server;
		registry = new PresentationRegistry();
		registry.register(new BasicManifest());
		try {
			Class<?> factory = Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAdapters");
			Object adapter = factory.getMethod("server", MinecraftServer.class).invoke(null, server);
			if (adapter instanceof PresentationAdapter present) registry.register(present);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
			// Optional integrations must not prevent Basic from negotiating.
		}
		policyFingerprint = settings().fingerprint();
	}

	public static void reset() {
		SESSIONS.clear(); LEASES.clear(); activeServer = null; registry = new PresentationRegistry();
	}

	/** Validate a negotiated intent before entering the existing authority pipeline. */
	public static boolean accepts(ServerPlayer player, PresentationC2SPacket packet) {
		Session session = SESSIONS.get(player.getUUID());
		return !packet.isCorrupt() && session != null && session.ready && session.epoch == packet.epoch();
	}

	public static void negotiate(MinecraftServer server, ServerPlayer player, PresentationC2SPacket packet) {
		activate(server);
		if (packet.isCorrupt()) return;
		if (packet.kind() == PresentationC2SPacket.Kind.HELLO) {
			Session existing = SESSIONS.get(player.getUUID());
			if (existing != null) {
				if (!existing.ready) send(player, PresentationS2CPacket.offer(existing.epoch, existing.manifest, existing.schemas));
				return;
			}
			Map<String, Integer> schemas = new LinkedHashMap<>();
			Map<String, List<PresentationField>> manifest = new LinkedHashMap<>();
			for (PresentationAdapter adapter : registry.all()) {
				if (Objects.equals(packet.schemas().get(adapter.adapterId()), adapter.schema())) {
					schemas.put(adapter.adapterId(), adapter.schema()); manifest.put(adapter.adapterId(), adapter.fields());
				}
			}
			if (!schemas.containsKey(PresentationBasic.ID)) return;
			long epoch;
			do { epoch = UUID.randomUUID().getMostSignificantBits(); } while (epoch == 0);
			Session session = new Session(epoch, schemas, manifest);
			SESSIONS.put(player.getUUID(), session);
			send(player, PresentationS2CPacket.offer(epoch, manifest, schemas));
			return;
		}
		Session session = SESSIONS.get(player.getUUID());
		if (packet.kind() != PresentationC2SPacket.Kind.SUBSCRIBE || session == null
			|| packet.epoch() != session.epoch || packet.subscription() <= session.subscription) return;
		Set<String> known = new HashSet<>();
		session.manifest.values().forEach(fields -> fields.forEach(field -> known.add(field.id())));
		Set<String> selected = new HashSet<>(packet.fields()); selected.retainAll(known);
		session.subscriptionFields = Set.copyOf(selected);
		session.subscription = packet.subscription(); session.view++; session.ready = true;
		session.sent.clear();
		send(player, PresentationS2CPacket.reset(session.epoch, session.subscription, session.view));
		// Baseline only retained active markers; no expensive source capture on subscription changes.
		for (Lease lease : LEASES.values()) {
			if (lease.marker.expiresAtTick() > server.getTickCount() && lease.marker.recipients().contains(player.getUUID())) {
				capture(server, lease, new PresentationAdapter.CaptureBudget(settings().scanBudget()), MAX_CAPTURE_WORK_PER_TICK, false);
				sendInitial(player, session, lease);
				publish(player, session, lease);
			}
		}
	}

	public static void created(MinecraftServer server, ServerMarker marker, TargetNameJson name, String ownerName) {
		activate(server);
		Lease lease = LEASES.get(marker.id().value());
		boolean first = lease == null;
		if (first) {
			lease = new Lease(marker, ownerName);
			if (LEASES.size() < MAX_LEASES) LEASES.put(marker.id().value(), lease);
		} else { lease.marker = marker; lease.ownerName = ownerName; }
		if (first) {
			PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(settings().scanBudget());
			capture(server, lease, budget, 1, true);
		}
		for (UUID recipient : marker.recipients()) {
			ServerPlayer player = server.getPlayerList().getPlayer(recipient); Session session = SESSIONS.get(recipient);
			if (player != null && session != null && session.ready) sendInitial(player, session, lease);
		}
	}

	public static void updated(MinecraftServer server, ServerMarker marker, TargetNameJson name, String ownerName) {
		Lease lease = LEASES.get(marker.id().value());
		if (lease == null) {
			created(server, marker, name, ownerName);
			return;
		}
		lease.marker = marker;
		lease.ownerName = ownerName;
		for (UUID recipient : marker.recipients()) {
			Session session = SESSIONS.get(recipient);
			if (session != null && session.ready && session.sent.containsKey(marker.id().value())) {
				Source source = lease.sources.get(PresentationBasic.ID);
				if (source != null && source.nextSample > server.getTickCount()) source.nextSample = server.getTickCount();
			}
		}
	}

	public static void tick(MinecraftServer server, List<ServerMarker> markers) {
		activate(server);
		String nextPolicy = settings().fingerprint();
		if (!nextPolicy.equals(policyFingerprint)) {
			policyFingerprint = nextPolicy;
			for (var entry : SESSIONS.entrySet()) {
				Session session = entry.getValue();
				if (!session.ready || session.view == Long.MAX_VALUE) continue;
				session.view++;
				session.sent.clear();
				ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
				if (player == null) continue;
				send(player, PresentationS2CPacket.reset(session.epoch, session.subscription, session.view));
				for (Lease lease : LEASES.values()) {
					if (lease.marker.expiresAtTick() <= server.getTickCount() || !lease.marker.recipients().contains(player.getUUID())) continue;
					sendInitial(player, session, lease);
					publish(player, session, lease);
				}
			}
		}
		Set<Long> active = new HashSet<>();
		for (ServerMarker marker : markers) {
			active.add(marker.id().value());
			Lease lease = LEASES.get(marker.id().value());
			if (lease != null) lease.marker = marker;
		}
		LEasesWithoutMarkers(active);
		List<Lease> leases = new ArrayList<>(LEASES.values());
		if (leases.isEmpty()) return;
		PresentationAdapter.CaptureBudget work = new PresentationAdapter.CaptureBudget(MAX_CAPTURE_WORK_PER_TICK);
		int captures = MAX_CAPTURES_PER_TICK;
		for (int i = 0; i < leases.size(); i++) {
			Lease lease = leases.get((cursor + i) % leases.size());
			if (lease.marker.expiresAtTick() <= server.getTickCount()) continue;
			captures -= capture(server, lease, work, captures, false);
			for (UUID recipient : lease.marker.recipients()) {
				Session session = SESSIONS.get(recipient); ServerPlayer player = server.getPlayerList().getPlayer(recipient);
				if (player != null && session != null && session.ready) publish(player, session, lease);
			}
		}
		cursor = (cursor + 1) % leases.size();
	}

	private static void LEasesWithoutMarkers(Set<Long> active) {
		LEasesRemoveIfAbsent(active);
	}
	private static void LEasesRemoveIfAbsent(Set<Long> active) {
		LEASES.keySet().removeIf(id -> !active.contains(id));
	}

	private static int capture(MinecraftServer server, Lease lease, PresentationAdapter.CaptureBudget work,
		int remainingCaptures, boolean initial) {
		int captures = 0;
		for (PresentationAdapter adapter : registry.all()) {
			if (initial && !adapter.adapterId().equals(PresentationBasic.ID)) continue;
			Set<String> demand = new HashSet<>();
			for (UUID recipient : lease.marker.recipients()) {
				Session session = SESSIONS.get(recipient); ServerPlayer player = server.getPlayerList().getPlayer(recipient);
				if (session != null && session.ready && player != null) demand.addAll(allowed(player, session, adapter));
			}
			Source source = lease.sources.computeIfAbsent(adapter.adapterId(), id -> new Source(id, adapter.schema()));
			if (demand.isEmpty()) { source.value = PresentationSection.empty(adapter.adapterId(), adapter.schema()); source.demand = Set.of(); continue; }
			long tick = server.getTickCount();
			if (captures >= remainingCaptures || tick < source.nextSample || !work.scan()) continue;
			captures++;
			source.nextSample = tick + settings().interval(adapter.adapterId(), adapter.minUpdateIntervalTicks());
			PresentationSection value = null;
			try {
				if (adapter.adapterId().equals(PresentationBasic.ID)) value = basic(server, lease.marker, demand);
				else {
					int allowance = Math.min(settings().scanBudget(), work.remaining());
					PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(allowance);
					value = adapter.collect(detached(lease.marker.target()), Set.copyOf(demand), budget);
					for (int used = allowance - budget.remaining(); used > 0; used--) work.scan();
				}
				if (value != null && (!adapter.adapterId().equals(value.adapterId()) || adapter.schema() != value.schema())) value = null;
			} catch (RuntimeException | LinkageError ignored) { value = null; }
			if (value == null) {
				Map<String, PresentationValue> retained = new LinkedHashMap<>();
				source.value.fields().forEach((id, previous) -> { if (demand.contains(id)) retained.put(id, previous); });
				source.value = new PresentationSection(adapter.adapterId(), adapter.schema(), retained, true);
			} else source.value = value;
			source.demand = Set.copyOf(demand);
		}
		return captures;
	}

	private static Set<String> allowed(ServerPlayer player, Session session, PresentationAdapter adapter) {
		if (!Objects.equals(session.schemas.get(adapter.adapterId()), adapter.schema())) return Set.of();
		PresentationSettings settings = settings(); PresentationPolicy policy = settings.policy();
		int level = 0; for (int candidate = 1; candidate <= 4; candidate++) if (player.hasPermissions(candidate)) level = candidate;
		Set<String> result = new LinkedHashSet<>();
		for (PresentationField field : adapter.fields()) {
			if (session.subscriptionFields.contains(field.id()) && policy.allows(field.id(), field.enabledByDefault())
				&& PresentationAuthorization.canSee(player.getUUID(), field, level, settings.permission(field.id(), field.permissionLevel()))) result.add(field.id());
		}
		return result;
	}

	private static PresentationSection project(ServerPlayer player, Session session, PresentationAdapter adapter, Source source) {
		Set<String> allowed = allowed(player, session, adapter);
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		if (source != null) for (PresentationField descriptor : adapter.fields()) {
			PresentationValue value = source.value.fields().get(descriptor.id());
			if (allowed.contains(descriptor.id()) && value != null && descriptor.accepts(value)) fields.put(descriptor.id(), value);
		}
		return new PresentationSection(adapter.adapterId(), adapter.schema(), fields, source != null && source.value.stale());
	}

	private static void sendInitial(ServerPlayer player, Session session, Lease lease) {
		PresentationAdapter basic = registry.get(PresentationBasic.ID);
		// A semantically valid Basic capture may still exceed the encoded field bounds;
		// degrade to the established empty stale section instead of throwing here.
		PresentationSection projected = PresentationCodec.bounded(
			project(player, session, basic, lease.sources.get(PresentationBasic.ID)));
		send(player, PresentationS2CPacket.created(session.epoch, session.subscription, session.view,
			++session.revision, MarkerSnapshot.from(lease.marker), lease.ownerName, projected));
		session.sent.computeIfAbsent(lease.marker.id().value(), id -> new HashMap<>()).put(PresentationBasic.ID, projected);
	}

	private static void publish(ServerPlayer player, Session session, Lease lease) {
		Map<String, PresentationSection> sent = session.sent.get(lease.marker.id().value());
		if (sent == null) return; // Never backfill another connection's initial marker.
		for (PresentationAdapter adapter : registry.all()) {
			if (!session.schemas.containsKey(adapter.adapterId())) continue;
			PresentationSection projected = project(player, session, adapter, lease.sources.get(adapter.adapterId()));
			if (projected.equals(sent.get(adapter.adapterId()))) continue;
			try {
				send(player, PresentationS2CPacket.section(session.epoch, session.subscription, session.view,
					++session.revision, lease.marker.id(), projected));
				sent.put(adapter.adapterId(), projected);
			} catch (RuntimeException oversized) {
				PresentationSection empty = new PresentationSection(adapter.adapterId(), adapter.schema(), Map.of(), true);
				send(player, PresentationS2CPacket.section(session.epoch, session.subscription, session.view,
					++session.revision, lease.marker.id(), empty));
				sent.put(adapter.adapterId(), empty);
			}
		}
	}

	public static void removed(ServerPlayer player, MarkerId id, MarkerRemovalReason reason) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null || !session.ready) return;
		send(player, PresentationS2CPacket.removed(session.epoch, session.subscription, session.view, id, reason));
		session.sent.remove(id.value());
	}
	public static void forget(MarkerId id) { LEASES.remove(id.value()); }
	public static void disconnect(UUID player) { SESSIONS.remove(player); }
	public static void winner(ServerPlayer player, TargetKey key, Optional<MarkerId> winner) {
		Session session = SESSIONS.get(player.getUUID());
		if (session != null && session.ready) send(player, PresentationS2CPacket.winner(session.epoch, session.subscription, session.view, key, winner));
	}
	public static void rejected(ServerPlayer player, long request, MarkerRequestKind kind, MarkerRejectReason reason) {
		Session session = SESSIONS.get(player.getUUID());
		if (session != null && session.ready) send(player, PresentationS2CPacket.rejected(session.epoch, session.subscription, session.view, request, kind, reason));
	}
	private static void send(ServerPlayer player, PresentationS2CPacket packet) { IPlatformNetworkService.INSTANCE.sendToClient(packet, player); }
	private static PresentationSettings settings() { return ServerConfig.HANDLER.getConfig().getPresentation(); }

	private static PresentationSection basic(MinecraftServer server, ServerMarker marker, Set<String> demand) {
		Target target = marker.target();
		ResourceLocation dimension = ResourceLocation.tryParse(target.dimensionId());
		if (dimension == null) return null;
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		if (level == null) return null;
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		Component name = null;
		if (target instanceof Target.EntityTarget entityTarget) {
			var lookup = MinecraftServerEntityLookup.find(level, entityTarget.locator());
			if (!lookup.accepted()) return null;
			return basicEntity(demand, lookup.entity(),
				component -> TargetNameJsonCodec.encode(component, server.registryAccess()).value());
		} else if (target instanceof Target.BlockTarget block) {
			BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
			if (!level.hasChunkAt(pos)) return null;
			var state = level.getBlockState(pos);
			if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block.blockRegistryId())) return null;
			if (demand.contains(PresentationBasic.BLOCK_STATE)) {
				Map<String, PresentationValue> properties = new LinkedHashMap<>();
				state.getValues().forEach((property, value) -> properties.put(property.getName(), new PresentationValue.Text(value.toString())));
				fields.put(PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(properties));
			}
			if (demand.contains(PresentationBasic.NAME)) {
				name = state.getBlock().getName();
				var blockEntity = level.getBlockEntity(pos);
				if (blockEntity instanceof Nameable named && named.hasCustomName()) name = TargetNameComposer.compose(named.getCustomName(), name);
			}
		} else if (target instanceof Target.LocationTarget) {
			if (demand.contains(PresentationBasic.NAME)) name = TargetNameComposer.here();
		} else if (demand.contains(PresentationBasic.NAME)) {
			TargetNameJson resolved = new MinecraftTargetNameResolver(server).resolveName(marker.owner(), target);
			put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(resolved.value()));
		}
		if (name != null) {
			TargetNameJson json = TargetNameJsonCodec.encode(name, server.registryAccess());
			put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(json.value()));
		}
		return new PresentationSection(PresentationBasic.ID, 1, fields, false);
	}

	/**
	 * World-free assembly of the Basic fields for one live entity target. The
	 * caller resolves the level, looks the entity up, and supplies the
	 * registry-bound name encoder; keeping this routine detached makes the
	 * name and value rules regression-tested without a running server.
	 */
	static PresentationSection basicEntity(Set<String> demand, Entity entity, Function<Component, String> encodeName) {
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		put(fields, demand, PresentationBasic.ENTITY_TYPE, new PresentationValue.Text(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
		if (entity instanceof LivingEntity living) {
			put(fields, demand, PresentationBasic.HEALTH, new PresentationValue.NumberValue(living.getHealth()));
			put(fields, demand, PresentationBasic.MAX_HEALTH, new PresentationValue.NumberValue(living.getMaxHealth()));
		}
		Component name = null;
		if (entity instanceof ItemEntity item) {
			var stack = item.getItem();
			put(fields, demand, PresentationBasic.ITEM_ID, new PresentationValue.Text(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
			put(fields, demand, PresentationBasic.ITEM_COUNT, new PresentationValue.NumberValue(stack.getCount()));
			put(fields, demand, PresentationBasic.ITEM_ICON, new PresentationValue.Flag(true));
			if (demand.contains(PresentationBasic.NAME)) name = optionalName(stack.get(DataComponents.CUSTOM_NAME), Component.translatable(stack.getDescriptionId()));
		} else if (demand.contains(PresentationBasic.NAME)) {
			name = entity instanceof ServerPlayer player ? Component.literal(player.getGameProfile().getName())
				: optionalName(entity.getCustomName(), entity.getType().getDescription());
		}
		if (name != null) put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(encodeName.apply(name)));
		return new PresentationSection(PresentationBasic.ID, 1, fields, false);
	}

	/**
	 * The optional-custom-name rule shared by every Basic name source: an
	 * absent custom name means the target is unnamed and keeps the trusted
	 * localized base component; only a present custom name is composed as
	 * {@code Custom (Base)}. Mirrors {@link MinecraftTargetNameResolver} and
	 * must never apply the strict composition to a possibly-absent name.
	 */
	static Component optionalName(Component customName, Component baseName) {
		return customName != null ? TargetNameComposer.compose(customName, baseName) : baseName;
	}

	private static void put(Map<String, PresentationValue> fields, Set<String> demand, String id, PresentationValue value) {
		if (!demand.contains(id)) return;
		try { PresentationLimits.validate(value); fields.put(id, value); } catch (IllegalArgumentException ignored) { }
	}
	private static PresentationAdapter.DetachedTarget detached(Target target) {
		if (target instanceof Target.BlockTarget block) return new PresentationAdapter.DetachedTarget(block.dimensionId(), "block", block.blockRegistryId(), block.x(), block.y(), block.z(), "");
		return new PresentationAdapter.DetachedTarget(target.dimensionId(), target.kind().name().toLowerCase(Locale.ROOT), "", 0, 0, 0, "");
	}
	private static final class BasicManifest implements PresentationAdapter {
		public String adapterId() { return PresentationBasic.ID; }
		public String modId() { return "minecraft"; }
		public int schema() { return 1; }
		public int minUpdateIntervalTicks() { return 5; }
		public List<PresentationField> fields() { return PresentationBasic.fields(); }
		public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) { return null; }
	}
}
