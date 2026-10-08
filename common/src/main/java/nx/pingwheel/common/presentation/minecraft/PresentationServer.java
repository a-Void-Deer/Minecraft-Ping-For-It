package nx.pingwheel.common.presentation.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.name.MinecraftTargetNameResolver;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.*;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.presentation.preview.PresentationPreviewServer;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

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
	private static PresentationPreviewServer previews;

	private PresentationServer() {}

	static final class Session {
		final long epoch;
		final Map<String, Integer> schemas;
		final Map<String, List<PresentationField>> manifest;
		final Map<Long, SentMarker> sent = new HashMap<>();
		Map<String, Map<String, Set<String>>> mask = Map.of();
		Set<String> inventoryTypes = Set.of();
		long view;
		long revision;
		boolean ready;
		Session(long epoch, Map<String, Integer> schemas, Map<String, List<PresentationField>> manifest) {
			this.epoch = epoch; this.schemas = Map.copyOf(schemas); this.manifest = Map.copyOf(manifest);
		}
	}
	/** Per-recipient, already-delivered state; no world objects or source-reading lease. */
	static final class SentMarker {
		final String ownerName;
		final Map<String, PresentationSection> sections = new HashMap<>();
		SentMarker(String ownerName) { this.ownerName = ownerName; }
	}
	static final class Source {
		PresentationSection value;
		Set<String> demand = Set.of();
		long nextSample;
		Source(String id, int schema) { value = PresentationSection.empty(id, schema); }
	}
	static final class Lease {
		ServerMarker marker;
		String ownerName;
		final Map<String, Source> sources = new LinkedHashMap<>();
		Lease(ServerMarker marker, String ownerName) { this.marker = marker; this.ownerName = ownerName; }
	}

	public static void activate(MinecraftServer server) {
		if (activeServer == server) return;
		SESSIONS.clear(); LEASES.clear(); cursor = 0;
		if (previews != null) previews.reset();
		activeServer = server;
		previews = new PresentationPreviewServer(new MinecraftPresentationPreview(server));
		registry = new PresentationRegistry();
		registry.register(new BasicManifest());
		registry.register(InventoryPresentation.INSTANCE);
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
		if (previews != null) previews.reset();
		previews = null;
		SESSIONS.clear(); LEASES.clear(); activeServer = null; registry = new PresentationRegistry();
	}

	/** Authenticated server-thread ingress only queues; it performs no target/source observation. */
	public static void preview(MinecraftServer server, ServerPlayer player, PresentationPreviewC2SPacket packet) {
		if (server == null || player == null || packet == null || packet.isCorrupt() || !server.isSameThread()
			|| player.serverLevel().getServer() != server) return;
		activate(server);
		previews.handle(player.getUUID(), packet, server.getTickCount());
	}

	public static void stopped(MinecraftServer server) {
		if (activeServer == server) reset();
	}

	/** Validate a negotiated intent before entering the existing authority pipeline. */
	public static boolean accepts(ServerPlayer player, PresentationC2SPacket packet) {
		Session session = SESSIONS.get(player.getUUID());
		return !packet.isCorrupt() && session != null && session.ready && session.epoch == packet.epoch();
	}

	public static void negotiate(MinecraftServer server, ServerPlayer player, PresentationC2SPacket packet,
		ServerMarkerStore store) {
		activate(server);
		if (packet.isCorrupt()) return;
		if (packet.kind() == PresentationC2SPacket.Kind.HELLO) {
			Session existing = SESSIONS.get(player.getUUID());
			if (existing != null) {
				if (!existing.ready) {
					send(player, PresentationS2CPacket.offer(existing.epoch, existing.manifest, existing.schemas));
					prepare(player, existing, store);
				}
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
			prepare(player, session, store);
			return;
		}
	}

	private static void prepare(ServerPlayer player, Session session, ServerMarkerStore store) {
		session.mask = mask(player, session);
		session.inventoryTypes = inventoryTypes(player, session);
		session.view++;
		session.ready = true;
		session.sent.clear();
		send(player, PresentationS2CPacket.reset(session.epoch, session.view, session.mask));
		long tick = activeServer.getTickCount();
		cachedBaseline(LEASES.values(), tick, player.getUUID(), lease -> {
			sendInitial(player, session, lease);
			publish(player, session, lease);
		});
		sendWinnerBaseline(session, player.getUUID(), tick, store, packet -> send(player, packet));
	}

	/** The negotiated baseline replays retained values; it never enters the source sampler. */
	static void cachedBaseline(Collection<Lease> leases, long tick, UUID recipient,
		java.util.function.Consumer<Lease> delivery) {
		for (Lease lease : leases) {
			if (lease.marker.expiresAtTick() > tick && lease.marker.recipients().contains(recipient))
				delivery.accept(lease);
		}
	}

	/**
	 * Replays the authoritative recipient-scoped winner for every target key that
	 * has an active marker visible to {@code recipient}, after the record/value
	 * baseline. It reads only the current marker store: no source observation, no
	 * lease allocation, and no fabricated marker record.
	 */
	static void sendWinnerBaseline(Session session, UUID recipient, long tick, ServerMarkerStore store,
		java.util.function.Consumer<PresentationS2CPacket> delivery) {
		if (!session.ready) return;

		for (ServerMarker winner : store.winnersFor(recipient, tick)) {
			delivery.accept(PresentationS2CPacket.winner(session.epoch, session.view,
				winner.targetKey(), Optional.of(winner.id())));
		}
	}

	public static void created(MinecraftServer server, ServerMarker marker, TargetNameJson name, String ownerName) {
		created(server, marker, name, ownerName, Map.of());
	}

	public static void created(MinecraftServer server, ServerMarker marker, TargetNameJson name, String ownerName,
		Map<String, PresentationSection> sourceSeeds) {
		activate(server);
		Lease lease = LEASES.get(marker.id().value());
		boolean first = lease == null;
		if (first) {
			lease = new Lease(marker, ownerName);
			rememberLease(LEASES, lease);
		} else { lease.marker = marker; lease.ownerName = ownerName; }
		if (first) {
			long tick = server.getTickCount();
			for (var seed : sourceSeeds.entrySet()) {
				PresentationAdapter adapter = registry.get(seed.getKey());
				if (adapter == null || adapter.deliveryMode() != PresentationAdapter.DeliveryMode.SECTION
					|| seed.getValue() == null) continue;
				PresentationSection neutral = sanitize(adapter, seed.getValue(), seed.getValue().fields().keySet());
				if (neutral == null || neutral.stale()) continue;
				Source source = lease.sources.computeIfAbsent(adapter.adapterId(), id -> new Source(id, adapter.schema()));
				source.value = neutral;
				source.demand = Set.copyOf(neutral.fields().keySet());
				source.nextSample = tick + settings().interval(adapter.adapterId(), adapter.minUpdateIntervalTicks());
			}
			PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(settings().scanBudget());
			if (!lease.sources.containsKey(PresentationBasic.ID)) capture(server, lease, budget, 1, true);
		}
		for (UUID recipient : marker.recipients()) {
			ServerPlayer player = server.getPlayerList().getPlayer(recipient); Session session = SESSIONS.get(recipient);
			if (player != null && session != null && session.ready) {
				sendInitial(player, session, lease);
				if (!sourceSeeds.isEmpty()) publish(player, session, lease);
			}
		}
	}

	/** The sampling cache is bounded; initial delivery does not require admission to it. */
	static boolean rememberLease(Map<Long, Lease> leases, Lease lease) {
		if (leases.size() >= MAX_LEASES) return false;
		leases.put(lease.marker.id().value(), lease);
		return true;
	}

	public static void updated(MinecraftServer server, ServerMarker marker) {
		PresentationAdapter basic = registry.get(PresentationBasic.ID);
		refreshMarker(marker, LEASES, basic, server.getTickCount(), SESSIONS,
			recipient -> server.getPlayerList().getPlayer(recipient) != null,
			recipient -> allowed(server.getPlayerList().getPlayer(recipient), SESSIONS.get(recipient),
				basic, marker.targetType().id()),
			(recipient, packet) -> send(server.getPlayerList().getPlayer(recipient), packet));
	}

	/** Metadata-only refresh, including markers that exceeded the bounded sampling cache. */
	static void refreshMarker(ServerMarker marker, Map<Long, Lease> leases, PresentationAdapter basic, long tick,
		Map<UUID, Session> sessions, java.util.function.Predicate<UUID> online,
		Function<UUID, Set<String>> allowed,
		java.util.function.BiConsumer<UUID, PresentationS2CPacket> delivery) {
		Lease lease = leases.get(marker.id().value());
		if (lease != null) {
			updateLease(lease, marker, tick, sessions, online, recipient ->
				sendInitial(sessions.get(recipient), lease, basic, allowed.apply(recipient),
					packet -> delivery.accept(recipient, packet)));
		} else {
			deliverKnownRefresh(marker, tick, sessions, online, (recipient, sent) ->
				sendCachedInitial(sessions.get(recipient), marker, sent.ownerName, basic,
					sent.sections.get(PresentationBasic.ID), allowed.apply(recipient),
					packet -> delivery.accept(recipient, packet)));
		}
	}

	/** Refreshes only recipients that have this marker's initial snapshot in the current view. */
	static void updateLease(Lease lease, ServerMarker marker, long tick, Map<UUID, Session> sessions,
		java.util.function.Predicate<UUID> online, java.util.function.Consumer<UUID> delivery) {
		if (marker.expiresAtTick() <= tick) return;
		lease.marker = marker;
		deliverKnownRefresh(marker, tick, sessions, online, (recipient, sent) -> delivery.accept(recipient));
	}

	private static void deliverKnownRefresh(ServerMarker marker, long tick, Map<UUID, Session> sessions,
		java.util.function.Predicate<UUID> online, java.util.function.BiConsumer<UUID, SentMarker> delivery) {
		if (marker.expiresAtTick() <= tick) return;
		for (UUID recipient : marker.recipients()) {
			Session session = sessions.get(recipient);
			if (session != null && session.ready && online.test(recipient)) {
				SentMarker sent = session.sent.get(marker.id().value());
				if (sent != null) delivery.accept(recipient, sent);
			}
		}
	}

	public static void tick(MinecraftServer server, ServerMarkerStore store) {
		activate(server);
		List<ServerMarker> markers = store.allMarkers();
		String nextPolicy = settings().fingerprint();
		boolean policyChanged = !nextPolicy.equals(policyFingerprint);
		policyFingerprint = nextPolicy;
		for (var entry : SESSIONS.entrySet()) {
			Session session = entry.getValue();
			if (!session.ready || session.view == Long.MAX_VALUE) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) continue;
			Map<String, Map<String, Set<String>>> effective = mask(player, session);
			Set<String> dedicated = inventoryTypes(player, session);
			if (!policyChanged && effective.equals(session.mask) && dedicated.equals(session.inventoryTypes)) continue;
			session.mask = effective;
			session.inventoryTypes = dedicated;
			session.view++;
			Map<Long, SentMarker> previouslySent = new HashMap<>(session.sent);
			session.sent.clear();
			send(player, PresentationS2CPacket.reset(session.epoch, session.view, effective));
			cachedBaseline(LEASES.values(), server.getTickCount(), player.getUUID(), lease -> {
				sendInitial(player, session, lease);
				publish(player, session, lease);
			});
			// The sampling cap does not remove an already-delivered marker from the
			// authoritative audience: re-baseline its own cached projection after reset.
			for (ServerMarker marker : markers) {
				SentMarker old = previouslySent.get(marker.id().value());
				if (old == null || LEASES.containsKey(marker.id().value())
					|| marker.expiresAtTick() <= server.getTickCount()
					|| !marker.recipients().contains(player.getUUID())) continue;
				PresentationAdapter basic = registry.get(PresentationBasic.ID);
				sendCachedInitial(session, marker, old.ownerName, basic, old.sections.get(PresentationBasic.ID),
					allowed(player, session, basic, marker.targetType().id()), packet -> send(player, packet));
				for (PresentationAdapter adapter : registry.sectionAdapters()) {
					if (adapter.adapterId().equals(PresentationBasic.ID) || !session.schemas.containsKey(adapter.adapterId())) continue;
					PresentationSection cached = old.sections.get(adapter.adapterId());
					if (cached != null) publishCached(player, session, marker, adapter, cached);
				}
			}
			sendWinnerBaseline(session, player.getUUID(), server.getTickCount(), store, packet -> send(player, packet));
		}
		Set<Long> active = new HashSet<>();
		for (ServerMarker marker : markers) {
			active.add(marker.id().value());
			Lease lease = LEASES.get(marker.id().value());
			if (lease != null) lease.marker = marker;
		}
		LEasesWithoutMarkers(active);
		List<Lease> leases = new ArrayList<>(LEASES.values());
		PresentationAdapter.CaptureBudget work = new PresentationAdapter.CaptureBudget(MAX_CAPTURE_WORK_PER_TICK);
		sampleTick(leases, cursor, server.getTickCount(), work, MAX_CAPTURES_PER_TICK,
			(lease, sharedWork, remainingCaptures) -> capture(server, lease, sharedWork, remainingCaptures, false), lease -> {
			for (UUID recipient : lease.marker.recipients()) {
				Session session = SESSIONS.get(recipient); ServerPlayer player = server.getPlayerList().getPlayer(recipient);
				if (player != null && session != null && session.ready) publish(player, session, lease);
			}
		}, previews);
		if (!leases.isEmpty()) cursor = (cursor + 1) % leases.size();
	}

	@FunctionalInterface interface LeaseCapture {
		int capture(Lease lease, PresentationAdapter.CaptureBudget work, int remainingCaptures);
	}
	/** Production tick sequencing: previews receive exactly the residual lease allowance, even without leases. */
	static void sampleTick(List<Lease> leases, int cursor, long tick, PresentationAdapter.CaptureBudget work,
		int captures, LeaseCapture capture, java.util.function.Consumer<Lease> publish, PresentationPreviewServer previews) {
		for (int i = 0; i < leases.size(); i++) {
			Lease lease = leases.get((cursor + i) % leases.size());
			if (lease.marker.expiresAtTick() <= tick) continue;
			captures -= capture.capture(lease, work, captures);
			publish.accept(lease);
		}
		if (previews != null) previews.drain(tick, work, captures);
	}

	private static void LEasesWithoutMarkers(Set<Long> active) {
		LEasesRemoveIfAbsent(active);
	}
	private static void LEasesRemoveIfAbsent(Set<Long> active) {
		LEASES.keySet().removeIf(id -> !active.contains(id));
	}

	private static int capture(MinecraftServer server, Lease lease, PresentationAdapter.CaptureBudget work,
		int remainingCaptures, boolean initial) {
		return captureSources(lease, registry, settings(), server.getTickCount(), work, remainingCaptures, initial,
			adapter -> {
				Set<String> demand = new HashSet<>();
				for (UUID recipient : lease.marker.recipients()) {
					Session session = SESSIONS.get(recipient);
					ServerPlayer player = server.getPlayerList().getPlayer(recipient);
					if (session != null && session.ready && player != null)
						demand.addAll(allowed(player, session, adapter, lease.marker.targetType().id()));
				}
				return demand;
			}, demand -> basic(server, lease.marker, demand));
	}

	/** Production sampler with detached demand and Basic observation ports for headless regression checks. */
	static int captureSources(Lease lease, PresentationRegistry adapters, PresentationSettings policy, long tick,
		PresentationAdapter.CaptureBudget work, int remainingCaptures, boolean initial,
		Function<PresentationAdapter, Set<String>> demands,
		Function<Set<String>, PresentationSection> basicCapture) {
		int captures = 0;
		for (PresentationAdapter adapter : adapters.sectionAdapters()) {
			if (initial && !adapter.adapterId().equals(PresentationBasic.ID)) continue;
			Set<String> demand = demands.apply(adapter);
			Source source = lease.sources.computeIfAbsent(adapter.adapterId(), id -> new Source(id, adapter.schema()));
			if (demand.isEmpty()) { source.value = PresentationSection.empty(adapter.adapterId(), adapter.schema()); source.demand = Set.of(); continue; }
			// Insufficient work or disabled capture is a deferral, not an unavailable observation.
			if (captures >= remainingCaptures || tick < source.nextSample || policy.scanBudget() == 0
				|| work.remaining() == 0 || (!adapter.adapterId().equals(PresentationBasic.ID) && work.remaining() < 2)) continue;
			work.scan();
			captures++;
			source.nextSample = tick + policy.interval(adapter.adapterId(), adapter.minUpdateIntervalTicks());
			PresentationSection value = null;
			try {
				if (adapter.adapterId().equals(PresentationBasic.ID)) value = basicCapture.apply(demand);
				else {
					int allowance = Math.min(policy.scanBudget(), work.remaining());
					PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(allowance);
					try { value = adapter.collect(detached(lease.marker.target()), Set.copyOf(demand), budget); }
					finally { for (int used = allowance - budget.remaining(); used > 0; used--) work.scan(); }
				}
			} catch (RuntimeException | LinkageError ignored) { value = null; }
			try { value = sanitize(adapter, value, demand); }
			catch (RuntimeException | LinkageError ignored) { value = null; }
			source.value = transition(adapter.adapterId(), adapter.schema(), source.value, demand, value);
			source.demand = Set.copyOf(demand);
		}
		return captures;
	}

	static PresentationSection retainStale(String adapterId, int schema,
		PresentationSection previous, Set<String> demand) {
		Map<String, PresentationValue> retained = new LinkedHashMap<>();
		previous.fields().forEach((id, value) -> { if (demand.contains(id)) retained.put(id, value); });
		return new PresentationSection(adapterId, schema, retained, true);
	}

	static PresentationSection transition(String adapterId, int schema,
		PresentationSection previous, Set<String> demand, PresentationSection captured) {
		if (captured != null && adapterId.equals(captured.adapterId()) && schema == captured.schema()) {
			return captured;
		}
		return retainStale(adapterId, schema, previous, demand);
	}

	private static Map<String, Map<String, Set<String>>> mask(ServerPlayer player, Session session) {
		int level = 0;
		for (int candidate = 1; candidate <= 4; candidate++) if (player.hasPermissions(candidate)) level = candidate;
		return maskFor(registry, settings(), player.getUUID(), level, session.schemas);
	}

	static Map<String, Map<String, Set<String>>> maskFor(PresentationRegistry adapters,
		PresentationSettings settings, UUID recipient, int level, Map<String, Integer> schemas) {
		Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			Map<String, Set<String>> perAdapter = new LinkedHashMap<>();
			for (PresentationAdapter adapter : adapters.sectionAdapters()) {
				if (!Objects.equals(schemas.get(adapter.adapterId()), adapter.schema())) continue;
				Set<String> fields = allowedFields(settings, recipient, level, adapter, type);
				if (!fields.isEmpty()) perAdapter.put(adapter.adapterId(), fields);
			}
			result.put(type, Map.copyOf(perAdapter));
		}
		return Map.copyOf(result);
	}

	private static Set<String> allowed(ServerPlayer player, Session session, PresentationAdapter adapter,
		String targetTypeId) {
		if (!Objects.equals(session.schemas.get(adapter.adapterId()), adapter.schema())) return Set.of();
		int level = 0; for (int candidate = 1; candidate <= 4; candidate++) if (player.hasPermissions(candidate)) level = candidate;
		return allowedFields(settings(), player.getUUID(), level, adapter, targetTypeId);
	}

	static Set<String> allowedFields(PresentationSettings settings, UUID recipient, int level,
		PresentationAdapter adapter, String targetTypeId) {
		Set<String> result = new LinkedHashSet<>();
		for (PresentationField field : adapter.fields()) {
			if (PresentationAuthorization.fieldAllowed(settings, recipient, level, field, targetTypeId))
				result.add(field.id());
		}
		return Set.copyOf(result);
	}

	static PresentationSection sanitize(PresentationAdapter adapter, PresentationSection value, Set<String> demand) {
		if (value == null || !adapter.adapterId().equals(value.adapterId()) || adapter.schema() != value.schema()) return null;
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		for (PresentationField field : adapter.fields()) {
			PresentationValue observed = value.fields().get(field.id());
			if (demand.contains(field.id()) && observed != null && field.accepts(observed)) fields.put(field.id(), observed);
		}
		return new PresentationSection(adapter.adapterId(), adapter.schema(), fields, value.stale());
	}

	static PresentationSection project(PresentationAdapter adapter, PresentationSection source,
		Set<String> allowed, List<PresentationPropertySelection> selections) {
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		if (source != null) for (PresentationField descriptor : adapter.fields()) {
			PresentationValue value = source.fields().get(descriptor.id());
			if (allowed.contains(descriptor.id()) && value != null && descriptor.accepts(value)) fields.put(descriptor.id(), value);
		}
		Map<PresentationPropertyRef, String> annotations = new LinkedHashMap<>();
		PresentationSection neutral = new PresentationSection(adapter.adapterId(), adapter.schema(), fields,
			source != null && source.stale());
		for (PresentationPropertySelection selection : selections) {
			if (selection.pingTypeId() != null && selection.ref().adapterId().equals(adapter.adapterId())
				&& allowed.contains(selection.ref().fieldId()) && selection.ref().resolve(neutral) != null)
				annotations.put(selection.ref(), selection.pingTypeId());
		}
		return new PresentationSection(adapter.adapterId(), adapter.schema(), fields, neutral.stale(), annotations);
	}

	private static PresentationSection project(ServerPlayer player, Session session, PresentationAdapter adapter, Lease lease) {
		Source source = lease.sources.get(adapter.adapterId());
		return project(adapter, source == null ? null : source.value,
			allowed(player, session, adapter, lease.marker.targetType().id()), lease.marker.properties());
	}

	private static void sendInitial(ServerPlayer player, Session session, Lease lease) {
		PresentationAdapter basic = registry.get(PresentationBasic.ID);
		sendInitial(session, lease, basic,
			allowed(player, session, basic, lease.marker.targetType().id()), packet -> send(player, packet));
	}

	/** Project and deliver cached Basic atomically with the marker, without a source observation. */
	static void sendInitial(Session session, Lease lease, PresentationAdapter basic, Set<String> allowed,
		java.util.function.Consumer<PresentationS2CPacket> delivery) {
		Source source = lease.sources.get(basic.adapterId());
		sendCachedInitial(session, lease.marker, lease.ownerName, basic,
			source == null ? null : source.value, allowed, delivery);
	}

	private static void sendCachedInitial(Session session, ServerMarker marker, String ownerName,
		PresentationAdapter basic, PresentationSection cached, Set<String> allowed,
		java.util.function.Consumer<PresentationS2CPacket> delivery) {
		PresentationPropertyRef defaultRef = PresentationDefaults.forTargetType(marker.targetType().id())
			.orElse(null);
		if (defaultRef == null) return;
		// A semantically valid Basic capture may exceed the encoded field bounds;
		// degrade to the established empty stale section instead of throwing here.
		PresentationSection projected = PresentationCodec.bounded(project(basic,
			cached, allowed, marker.properties()));
		delivery.accept(PresentationS2CPacket.created(session.epoch, session.view,
			++session.revision, MarkerSnapshot.from(marker), ownerName, defaultRef, projected));
		session.sent.computeIfAbsent(marker.id().value(), id -> new SentMarker(ownerName))
			.sections.put(PresentationBasic.ID, projected);
	}

	private static void publish(ServerPlayer player, Session session, Lease lease) {
		SentMarker marker = session.sent.get(lease.marker.id().value());
		Map<String, PresentationSection> sent = marker == null ? null : marker.sections;
		if (sent == null) return; // Never backfill another connection's initial marker.
		for (PresentationAdapter adapter : registry.sectionAdapters()) {
			if (!session.schemas.containsKey(adapter.adapterId())) continue;
			PresentationSection projected = project(player, session, adapter, lease);
			if (projected.equals(sent.get(adapter.adapterId()))) continue;
			sendProjectedSection(player, session, lease.marker.id(), adapter, projected, sent);
		}
	}

	private static void publishCached(ServerPlayer player, Session session, ServerMarker marker,
		PresentationAdapter adapter, PresentationSection cached) {
		SentMarker known = session.sent.get(marker.id().value());
		if (known == null) return;
		PresentationSection projected = project(adapter, cached,
			allowed(player, session, adapter, marker.targetType().id()), marker.properties());
		sendProjectedSection(player, session, marker.id(), adapter, projected, known.sections);
	}

	private static void sendProjectedSection(ServerPlayer player, Session session, MarkerId markerId,
		PresentationAdapter adapter, PresentationSection projected, Map<String, PresentationSection> sent) {
		try {
			send(player, PresentationS2CPacket.section(session.epoch, session.view,
				++session.revision, markerId, projected));
			sent.put(adapter.adapterId(), projected);
		} catch (RuntimeException oversized) {
			PresentationSection empty = new PresentationSection(adapter.adapterId(), adapter.schema(), Map.of(), true);
			send(player, PresentationS2CPacket.section(session.epoch, session.view,
				++session.revision, markerId, empty));
			sent.put(adapter.adapterId(), empty);
		}
	}

	public static void removed(ServerPlayer player, MarkerId id, MarkerRemovalReason reason) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null || !session.ready) return;
		send(player, PresentationS2CPacket.removed(session.epoch, session.view, id, reason));
		session.sent.remove(id.value());
	}
	public static void forget(MarkerId id) { LEASES.remove(id.value()); }
	public static void disconnect(UUID player) {
		SESSIONS.remove(player);
		if (previews != null) previews.disconnect(player);
	}

	/** Fresh server permission/policy intersected with the already-advertised SECTION view. */
	public static Optional<PresentationPreviewAccess> previewAccess(UUID playerId, String targetTypeId) {
		Session session = SESSIONS.get(playerId);
		ServerPlayer player = activeServer == null ? null : activeServer.getPlayerList().getPlayer(playerId);
		if (player == null) return Optional.empty();
		return previewAccess(session, registry, targetTypeId, adapter -> allowed(player, session, adapter, targetTypeId));
	}
	static Optional<PresentationPreviewAccess> previewAccess(Session session, PresentationRegistry adapters,
		String targetTypeId, Function<PresentationAdapter, Set<String>> freshAuthorization) {
		if (session == null || !session.ready || !PresentationSettings.isKnownTargetType(targetTypeId)) return Optional.empty();
		Map<String, PresentationPreviewAccess.Adapter> result = new LinkedHashMap<>();
		for (PresentationAdapter adapter : adapters.sectionAdapters()) {
			if (!Objects.equals(session.schemas.get(adapter.adapterId()), adapter.schema())) continue;
			Set<String> advertised = session.mask.getOrDefault(targetTypeId, Map.of()).getOrDefault(adapter.adapterId(), Set.of());
			if (advertised.isEmpty()) continue;
			Map<String, PresentationField> fields = new LinkedHashMap<>();
			Set<String> fresh = freshAuthorization.apply(adapter);
			for (PresentationField field : session.manifest.getOrDefault(adapter.adapterId(), List.of())) {
				if (advertised.contains(field.id()) && fresh.contains(field.id()) && adapter.fields().stream().anyMatch(local ->
					local.id().equals(field.id()) && local.kind() == field.kind())) fields.put(field.id(), field);
			}
			if (!fields.isEmpty()) result.put(adapter.adapterId(), new PresentationPreviewAccess.Adapter(adapter.schema(), fields));
		}
		return Optional.of(new PresentationPreviewAccess(session.epoch, session.view, targetTypeId, result));
	}
	static PresentationAdapter previewAdapter(String id) { return registry.get(id); }
	static PresentationSettings previewSettings() { return settings(); }

	/** Dedicated delivery shares the presentation fence, but never widens the SECTION mask. */
	public record InventoryPolicy(long epoch, long view, Set<String> types) {
		public InventoryPolicy { types = Set.copyOf(types); }
	}
	public static Optional<InventoryPolicy> inventoryPolicy(UUID playerId) {
		Session session = SESSIONS.get(playerId);
		ServerPlayer player = activeServer == null ? null : activeServer.getPlayerList().getPlayer(playerId);
		if (session == null || !session.ready || player == null) return Optional.empty();
		// Fresh authorization is intersected with the already-advertised view; promotion waits for RESET.
		Set<String> fresh = new HashSet<>(inventoryTypes(player, session));
		fresh.retainAll(session.inventoryTypes);
		return Optional.of(new InventoryPolicy(session.epoch, session.view, fresh));
	}
	public static boolean inventoryKnows(UUID player, MarkerId marker) {
		Session session = SESSIONS.get(player);
		return session != null && session.ready && session.sent.containsKey(marker.value());
	}
	private static Set<String> inventoryTypes(ServerPlayer player, Session session) {
		if (!Objects.equals(session.schemas.get(InventoryPresentation.ADAPTER_ID), InventoryPresentation.SCHEMA)) return Set.of();
		Set<String> result = new HashSet<>();
		for (String type : PresentationSettings.TARGET_TYPE_IDS)
			if (allowed(player, session, InventoryPresentation.INSTANCE, type).contains(InventoryPresentation.ITEMS)) result.add(type);
		return Set.copyOf(result);
	}
	public static void winner(ServerPlayer player, TargetKey key, Optional<MarkerId> winner) {
		Session session = SESSIONS.get(player.getUUID());
		if (session != null && session.ready) send(player, PresentationS2CPacket.winner(session.epoch, session.view, key, winner));
	}
	public static void rejected(ServerPlayer player, long request, MarkerRequestKind kind, MarkerRejectReason reason) {
		Session session = SESSIONS.get(player.getUUID());
		if (session != null && session.ready) send(player, PresentationS2CPacket.rejected(session.epoch, session.view, request, kind, reason));
	}
	private static void send(ServerPlayer player, PresentationS2CPacket packet) { IPlatformNetworkService.INSTANCE.sendToClient(packet, player); }
	private static PresentationSettings settings() { return ServerConfig.HANDLER.getConfig().getPresentation(); }

	public static MarkerCreationService.AdmissionResult admit(MinecraftServer server, ServerPlayer owner,
		Target committed, String targetTypeId, List<UUID> audience, List<PresentationPropertyIntent> intents) {
		activate(server);
		Session session = SESSIONS.get(owner.getUUID());
		if (session == null || !session.ready || settings().scanBudget() == 0)
			return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
		Map<String, Set<String>> allowed = mask(owner, session).getOrDefault(targetTypeId, Map.of());
		Set<String> initialBasic = new LinkedHashSet<>();
		PresentationAdapter basicAdapter = registry.get(PresentationBasic.ID);
		for (UUID id : audience) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			Session viewer = SESSIONS.get(id);
			if (player != null && viewer != null && viewer.ready)
				initialBasic.addAll(allowed(player, viewer, basicAdapter, targetTypeId));
		}
		ServerLevel level = level(server, committed);
		if (level == null) return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
		ServerPropertyAdmission.Context context;
		BlockState state = null;
		Entity entity = null;
		boolean externalNameOnly = false;
		if (committed instanceof Target.EntityTarget target) {
			var lookup = MinecraftServerEntityLookup.find(level, target.locator());
			if (!lookup.accepted()) return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			entity = lookup.entity();
			if (entity instanceof ItemEntity item) {
				ItemStack stack = item.getItem();
				context = new ServerPropertyAdmission.Context(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
					BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem()).tags().map(tag -> tag.location().toString())
						.collect(java.util.stream.Collectors.toSet()));
			} else context = new ServerPropertyAdmission.Context(
				BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
				BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).tags()
					.map(tag -> tag.location().toString()).collect(java.util.stream.Collectors.toSet()));
		} else if (committed instanceof Target.BlockTarget block) {
			BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
			if (!level.hasChunkAt(pos)) return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			state = level.getBlockState(pos);
			if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block.blockRegistryId()))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			context = blockContext(state);
		} else if (committed instanceof Target.ExternalBlockTarget external) {
			ExternalAdmissionObservation observation = observeExternalAdmission(intents,
				() -> ExternalBlockServerProviders.registry().observeBlock(server, level, external));
			if (observation == null)
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			state = observation.state();
			externalNameOnly = observation.nameOnly();
			context = state == null ? null : blockContext(state);
		} else context = new ServerPropertyAdmission.Context("", Set.of());
		BlockState observedState = state;
		Entity observedEntity = entity;
		Set<String> basicDemand = admissionBasicDemand(initialBasic, externalNameOnly);
		return ServerPropertyAdmission.admit(intents, registry, allowed,
			Map.of(PresentationBasic.ID, basicDemand), settings().scanBudget(), context,
			committed instanceof Target.LocationTarget || externalNameOnly ? null : PresentationBasic.ID,
			committed instanceof Target.LocationTarget || externalNameOnly ? 0 : 1,
			(adapter, demand, budget) -> {
				if (adapter.adapterId().equals(PresentationBasic.ID)) {
					if (observedEntity != null) return basicEntity(demand, observedEntity,
						component -> TargetNameJsonCodec.encode(component, server.registryAccess()).value());
					if (committed instanceof Target.ExternalBlockTarget external)
						return assembleExternalBasic(demand, observedState,
							demand.contains(PresentationBasic.NAME)
								? availableExternalName(ExternalBlockServerProviders.registry().resolveName(level, external),
									server.registryAccess()) : null);
					if (committed instanceof Target.BlockTarget block && observedState != null)
						return basicBlock(server, level, block, observedState, demand);
					return committed instanceof Target.LocationTarget && demand.contains(PresentationBasic.NAME)
						? new PresentationSection(PresentationBasic.ID, adapter.schema(), Map.of(PresentationBasic.NAME,
							new PresentationValue.Text(TargetNameJsonCodec.encode(TargetNameComposer.here(), server.registryAccess()).value())), false)
						: null;
				}
				int allowance = budget.remaining();
				PresentationAdapter.CaptureBudget limited = new PresentationAdapter.CaptureBudget(allowance);
				PresentationSection section = adapter.collect(detached(committed), demand, limited);
				for (int i = allowance - limited.remaining(); i > 0; i--) budget.scan();
				return section;
			});
	}

	record ExternalAdmissionObservation(BlockState state, boolean nameOnly) {}

	static Set<String> admissionBasicDemand(Set<String> allowed, boolean externalNameOnly) {
		if (!externalNameOnly) return Set.copyOf(allowed);
		return allowed.contains(PresentationBasic.NAME) ? Set.of(PresentationBasic.NAME) : Set.of();
	}

	private static boolean externalNameOnly(List<PresentationPropertyIntent> intents) {
		return intents != null && !intents.isEmpty() && intents.stream().allMatch(intent ->
			intent != null && intent.pingTypeId() == null && intent.ref().isRoot()
				&& intent.ref().adapterId().equals(PresentationBasic.ID)
				&& intent.ref().fieldId().equals(PresentationBasic.NAME));
	}

	static ExternalAdmissionObservation observeExternalAdmission(List<PresentationPropertyIntent> intents,
		Supplier<ExternalBlockServerProvider.ObservationResult> observe) {
		if (externalNameOnly(intents)) return new ExternalAdmissionObservation(null, true);
		ExternalBlockServerProvider.ObservationResult result = observe.get();
		return result instanceof ExternalBlockServerProvider.ObservationResult.Available available
			? new ExternalAdmissionObservation(available.observation().state(), false) : null;
	}

	public static TargetNameJson availableExternalName(
		Optional<ExternalBlockServerProvider.ExternalBlockName> name, HolderLookup.Provider registries) {
		return MinecraftTargetNameResolver.availableExternalComponent(name)
			.map(component -> TargetNameJsonCodec.encode(component, registries))
			.orElse(null);
	}

	private static ServerLevel level(MinecraftServer server, Target target) {
		ResourceLocation dimension = ResourceLocation.tryParse(target.dimensionId());
		return dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
	}

	private static ServerPropertyAdmission.Context blockContext(BlockState state) {
		return new ServerPropertyAdmission.Context(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
			BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()).tags().map(tag -> tag.location().toString())
				.collect(java.util.stream.Collectors.toSet()));
	}

	private static PresentationSection basicBlock(MinecraftServer server, ServerLevel level,
		Target.BlockTarget block, BlockState state, Set<String> demand) {
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		if (demand.contains(PresentationBasic.BLOCK_STATE))
			put(fields, demand, PresentationBasic.BLOCK_STATE, blockState(state));
		if (demand.contains(PresentationBasic.NAME)) {
			Component name = state.getBlock().getName();
			var blockEntity = level.getBlockEntity(new BlockPos(block.x(), block.y(), block.z()));
			if (blockEntity instanceof Nameable named && named.hasCustomName())
				name = TargetNameComposer.compose(named.getCustomName(), name);
			put(fields, demand, PresentationBasic.NAME,
				new PresentationValue.Text(TargetNameJsonCodec.encode(name, server.registryAccess()).value()));
		}
		return new PresentationSection(PresentationBasic.ID, 1, fields, false);
	}

	private static PresentationSection basic(MinecraftServer server, ServerMarker marker, Set<String> demand) {
		return basic(server, marker.owner(), marker.target(), demand);
	}
	/** Shared one-shot Basic reader; preview never needs or allocates a marker/lease. */
	static PresentationSection basic(MinecraftServer server, UUID owner, Target target, Set<String> demand) {
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
			return basicBlock(server, level, block, state, demand);
		} else if (target instanceof Target.ExternalBlockTarget external) {
			return basicExternal(demand,
				() -> ExternalBlockServerProviders.registry().observeBlock(server, level, external),
				() -> new MinecraftTargetNameResolver(server).resolveName(owner, external));
		} else if (target instanceof Target.LocationTarget) {
			if (demand.contains(PresentationBasic.NAME)) name = TargetNameComposer.here();
		} else if (demand.contains(PresentationBasic.NAME)) {
			TargetNameJson resolved = new MinecraftTargetNameResolver(server).resolveName(owner, target);
			put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(resolved.value()));
		}
		if (name != null) {
			TargetNameJson json = TargetNameJsonCodec.encode(name, server.registryAccess());
			put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(json.value()));
		}
		return new PresentationSection(PresentationBasic.ID, 1, fields, false);
	}

	static PresentationSection basicExternal(Set<String> demand,
		java.util.function.Supplier<ExternalBlockServerProvider.ObservationResult> observe,
		java.util.function.Supplier<TargetNameJson> resolveName) {
		BlockState state = null;
		if (demand.contains(PresentationBasic.BLOCK_STATE)) {
			ExternalBlockServerProvider.ObservationResult result = observe.get();
			if (!(result instanceof ExternalBlockServerProvider.ObservationResult.Available available)) return null;
			state = available.observation().state();
		}
		TargetNameJson name = demand.contains(PresentationBasic.NAME) ? resolveName.get() : null;
		return assembleExternalBasic(demand, state, name);
	}

	static PresentationSection assembleExternalBasic(Set<String> demand, BlockState state,
		TargetNameJson resolved) {
		if (demand.contains(PresentationBasic.BLOCK_STATE) && state == null) return null;
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		if (state != null && demand.contains(PresentationBasic.BLOCK_STATE))
			put(fields, demand, PresentationBasic.BLOCK_STATE, blockState(state));
		if (demand.contains(PresentationBasic.NAME) && resolved != null)
			put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(resolved.value()));
		return new PresentationSection(PresentationBasic.ID, 1, fields, false);
	}

	private static PresentationValue.RecordValue blockState(net.minecraft.world.level.block.state.BlockState state) {
		Map<String, PresentationValue> properties = new LinkedHashMap<>();
		state.getValues().forEach((property, value) -> properties.put(property.getName(), new PresentationValue.Text(value.toString())));
		return new PresentationValue.RecordValue(properties);
	}

	/**
	 * World-free assembly of the Basic fields for one live entity target. The
	 * caller resolves the level, looks the entity up, and supplies the
	 * registry-bound name encoder; keeping this routine detached makes the
	 * name and value rules regression-tested without a running server.
	 */
	static PresentationSection basicEntity(Set<String> demand, Entity entity, Function<Component, String> encodeName) {
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		if (demand.contains(PresentationBasic.ENTITY_TYPE))
			put(fields, demand, PresentationBasic.ENTITY_TYPE, new PresentationValue.Text(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
		if (entity instanceof LivingEntity living) {
			if (demand.contains(PresentationBasic.HEALTH)) put(fields, demand, PresentationBasic.HEALTH, new PresentationValue.NumberValue(living.getHealth()));
			if (demand.contains(PresentationBasic.MAX_HEALTH)) put(fields, demand, PresentationBasic.MAX_HEALTH, new PresentationValue.NumberValue(living.getMaxHealth()));
		}
		Component name = null;
		if (entity instanceof ItemEntity item) {
			if (demand.contains(PresentationBasic.ITEM_ICON)) put(fields, demand, PresentationBasic.ITEM_ICON, new PresentationValue.Flag(true));
			if (demand.contains(PresentationBasic.ITEM_ID) || demand.contains(PresentationBasic.ITEM_COUNT) || demand.contains(PresentationBasic.NAME)) {
				var stack = item.getItem();
				if (demand.contains(PresentationBasic.ITEM_ID)) put(fields, demand, PresentationBasic.ITEM_ID, new PresentationValue.Text(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
				if (demand.contains(PresentationBasic.ITEM_COUNT)) put(fields, demand, PresentationBasic.ITEM_COUNT, new PresentationValue.NumberValue(stack.getCount()));
				if (demand.contains(PresentationBasic.NAME)) name = optionalName(stack.get(DataComponents.CUSTOM_NAME), Component.translatable(stack.getDescriptionId()));
			}
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
	static PresentationAdapter.DetachedTarget detached(Target target) {
		if (target instanceof Target.BlockTarget block) return new PresentationAdapter.DetachedTarget(block.dimensionId(), "block", block.blockRegistryId(), block.x(), block.y(), block.z(), "");
		if (target instanceof Target.ExternalBlockTarget external) return new PresentationAdapter.DetachedTarget(
			external.dimensionId(), "block", external.expectedBlockRegistryId(), 0, 0, 0, "", external);
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
