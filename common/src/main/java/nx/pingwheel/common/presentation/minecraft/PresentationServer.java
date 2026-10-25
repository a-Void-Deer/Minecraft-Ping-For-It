package nx.pingwheel.common.presentation.minecraft;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.jetbrains.annotations.Nullable;

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
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
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
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryServer;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.presentation.preview.PresentationPreviewServer;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
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
	private static PresentationFairSampler sampler = new PresentationFairSampler();
	private static UUID baselineRecipient;
	private static long observationSerial;
	private static BudgetDelaySink delaySink = BudgetDelaySink.NONE;
	private static String policyFingerprint = "";
	private static PresentationPreviewServer previews;

	private PresentationServer() {}

	static final class Session {
		final long epoch;
		final Map<String, Integer> schemas;
		final Map<String, List<PresentationField>> manifest;
		final Map<Long, SentMarker> sent = new HashMap<>();
		Map<String, Map<String, Set<String>>> mask = Map.of();
		Map<String, List<PresentationPropertyRef>> childBlack = Map.of();
		Set<String> inventoryTypes = Set.of();
		long view;
		long revision;
		long authorizationCut = -1;
		long baselineAfter = -1, baselineHighWater = -1;
		boolean baselinePending;
		boolean ready;
		Session(long epoch, Map<String, Integer> schemas, Map<String, List<PresentationField>> manifest) {
			this.epoch = epoch; this.schemas = Map.copyOf(schemas); this.manifest = Map.copyOf(manifest);
		}
	}
	/** Per-recipient, already-delivered state; no world objects or source-reading lease. */
	static final class SentMarker {
		final String ownerName;
		final Map<String, PresentationSection> sections = new HashMap<>();
		long deliveredView;
		SentMarker(String ownerName) { this.ownerName = ownerName; }
	}
	static final class Source {
		enum Observation { NEVER, SUCCESS, FAILED, CLEARED }
		PresentationSection value;
		Set<String> demand = Set.of();
		long nextSample;
		long successfulSerial;
		Observation observation = Observation.NEVER;
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
		SESSIONS.clear(); LEASES.clear(); sampler = new PresentationFairSampler(); baselineRecipient = null;
		observationSerial = 0;
		if (previews != null) previews.reset();
		activeServer = server;
		delaySink = BudgetDelayMonitoring.sink(server);
		previews = new PresentationPreviewServer(new MinecraftPresentationPreview(server), delaySink);
		registry = new PresentationRegistry();
		registry.register(new BasicManifest());
		registry.register(InventoryPresentation.INSTANCE);
		try {
			Class<?> factory = Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAdapters");
			Object adapter = factory.getMethod("server", MinecraftServer.class).invoke(null, server);
			if (adapter instanceof PresentationAdapter present) registry.register(present);
		} catch (ClassNotFoundException absent) {
			// The NeoForge factory is normally absent on the other loaders.
		} catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
			Diagnostics.warn("presentation/optional-registration", "create", () -> "server adapter registration", ignored);
			// Optional integrations must not prevent Basic from negotiating.
		}
		policyFingerprint = settings().fingerprint();
	}

	public static void reset() {
		if (previews != null) previews.reset();
		previews = null;
		SESSIONS.clear(); LEASES.clear(); activeServer = null; registry = new PresentationRegistry();
		sampler = new PresentationFairSampler(); baselineRecipient = null; observationSerial = 0;
		delaySink = BudgetDelaySink.NONE;
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
		session.childBlack = childBlackFor(settings());
		session.inventoryTypes = inventoryTypes(player, session);
		session.view++;
		session.ready = true;
		send(player, PresentationS2CPacket.reset(session.epoch, session.view, session.mask, session.childBlack));
		long tick = activeServer.getTickCount();
		startBaseline(session, store);
		sendWinnerBaseline(session, player.getUUID(), tick, store, packet -> send(player, packet));
	}

	/** The negotiated baseline replays retained values; it never enters the source sampler. */
	// Compatibility seam for the existing focused cached-replay fixtures. Live
	// negotiation/reset uses bounded canonical baselineTick discovery instead.
	static void cachedBaseline(Collection<Lease> leases, long tick, UUID recipient,
		java.util.function.Consumer<Lease> delivery) {
		for (Lease lease : leases) {
			if (lease.marker.expiresAtTick() > tick && lease.marker.recipients().contains(recipient))
				delivery.accept(lease);
		}
	}

	static void startBaseline(Session session, ServerMarkerStore store) {
		session.baselineAfter = -1;
		session.baselineHighWater = store.highWaterId();
		session.baselinePending = true;
	}

	/** One shared discovery allowance, rotating recipients after each canonical inspection. */
	static UUID baselineTick(ServerMarkerStore store, Map<UUID, Session> sessions, UUID lastRecipient,
		long tick, int allowance, Predicate<UUID> online,
		java.util.function.BiConsumer<UUID, ServerMarker> deliver) {
		if (allowance < 0) throw new IllegalArgumentException("baseline allowance");
		NavigableMap<UUID, Session> pending = new TreeMap<>();
		sessions.forEach((recipient, session) -> {
			if (session.ready && session.baselinePending && online.test(recipient)) pending.put(recipient, session);
		});
		while (allowance > 0 && !pending.isEmpty()) {
			UUID recipient = lastRecipient == null ? pending.firstKey() : pending.higherKey(lastRecipient);
			if (recipient == null) recipient = pending.firstKey();
			Session session = pending.get(recipient);
			var page = store.pageAfter(session.baselineAfter, session.baselineHighWater, 1);
			if (!page.markers().isEmpty()) {
				allowance--;
				ServerMarker marker = page.markers().get(0);
				if (marker.expiresAtTick() > tick && marker.recipients().contains(recipient)) deliver.accept(recipient, marker);
			}
			session.baselineAfter = page.afterId();
			if (page.complete()) { session.baselinePending = false; pending.remove(recipient); }
			lastRecipient = recipient;
		}
		return lastRecipient;
	}

	static void pruneSession(Session session, ServerMarkerStore store, Function<String, PresentationAdapter> adapters,
		java.util.function.BiFunction<ServerMarker, PresentationAdapter, Set<String>> fresh) {
		var iterator = session.sent.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			ServerMarker marker = store.find(new MarkerId(entry.getKey())).orElse(null);
			if (marker == null) { iterator.remove(); continue; }
			pruneKnown(session, marker, adapters, adapter -> fresh.apply(marker, adapter));
		}
	}

	/** Winner-before-record converges as bounded canonical record pages arrive. */
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
				source.observation = Source.Observation.SUCCESS;
				source.successfulSerial = nextObservationSerial();
				source.demand = Set.copyOf(neutral.fields().keySet());
				source.nextSample = addTick(tick, settings().interval(adapter.adapterId(), adapter.minUpdateIntervalTicks()));
			}
			PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(settings().scanBudget());
			if (!lease.sources.containsKey(PresentationBasic.ID)) capture(server, lease, budget, 1, true);
			// Creation is independent paid initial work, not a second eviction owner.
			sampler.rememberInitial(LEASES, lease);
		}
		for (UUID recipient : marker.recipients()) {
			ServerPlayer player = server.getPlayerList().getPlayer(recipient); Session session = SESSIONS.get(recipient);
			if (player != null && session != null && session.ready) {
				if (sourceSeeds.isEmpty()) sendInitial(player, session, lease);
				else sendBaseline(session, lease, registry,
					adapter -> allowed(player, session, adapter, marker.targetType().id()),
					packet -> send(player, packet), InventoryServer.tracksInventory(marker.id()), effectiveInventoryTypes(player, session));
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
		String targetType = marker.targetType().id();
		refreshMarker(marker, LEASES, basic, server.getTickCount(), SESSIONS,
			recipient -> server.getPlayerList().getPlayer(recipient) != null,
			recipient -> allowed(server.getPlayerList().getPlayer(recipient), SESSIONS.get(recipient),
				basic, targetType),
			recipient -> adapter -> allowed(server.getPlayerList().getPlayer(recipient),
				SESSIONS.get(recipient), adapter, targetType),
			recipient -> effectiveInventoryTypes(server.getPlayerList().getPlayer(recipient),
				SESSIONS.get(recipient)),
			(recipient, packet) -> send(server.getPlayerList().getPlayer(recipient), packet));
	}

	/** Metadata-only refresh, including markers that exceeded the bounded sampling cache. */
	static void refreshMarker(ServerMarker marker, Map<Long, Lease> leases, PresentationAdapter basic, long tick,
		Map<UUID, Session> sessions, java.util.function.Predicate<UUID> online,
		Function<UUID, Set<String>> allowed,
		java.util.function.BiConsumer<UUID, PresentationS2CPacket> delivery) {
		refreshMarker(marker, leases, basic, tick, sessions, online, allowed, recipient -> adapter -> Set.of(), delivery);
	}

	/**
	 * Metadata-only refresh with the recipient's fresh per-adapter authorization
	 * port, so a replayed initial computes the same receipt descriptor as the
	 * first delivery. No fresh dedicated inventory gate is supplied here, so an
	 * inventory receipt stays conservatively suppressed.
	 */
	static void refreshMarker(ServerMarker marker, Map<Long, Lease> leases, PresentationAdapter basic, long tick,
		Map<UUID, Session> sessions, java.util.function.Predicate<UUID> online,
		Function<UUID, Set<String>> allowed,
		Function<UUID, Function<PresentationAdapter, Set<String>>> freshAuthorization,
		java.util.function.BiConsumer<UUID, PresentationS2CPacket> delivery) {
		refreshMarker(marker, leases, basic, tick, sessions, online, allowed, freshAuthorization,
			recipient -> Set.of(), delivery);
	}

	/**
	 * Metadata-only refresh with the recipient's fresh per-adapter authorization
	 * and fresh dedicated inventory gate, so a replayed initial computes the
	 * same receipt descriptor as the first delivery.
	 */
	static void refreshMarker(ServerMarker marker, Map<Long, Lease> leases, PresentationAdapter basic, long tick,
		Map<UUID, Session> sessions, java.util.function.Predicate<UUID> online,
		Function<UUID, Set<String>> allowed,
		Function<UUID, Function<PresentationAdapter, Set<String>>> freshAuthorization,
		Function<UUID, Set<String>> freshInventoryTypes,
		java.util.function.BiConsumer<UUID, PresentationS2CPacket> delivery) {
		Lease lease = leases.get(marker.id().value());
		boolean inventoryTracked = InventoryServer.tracksInventory(marker.id());
		if (lease != null) {
			updateLease(lease, marker, tick, sessions, online, recipient ->
				sendInitial(sessions.get(recipient), lease, basic, allowed.apply(recipient),
					packet -> delivery.accept(recipient, packet), freshAuthorization.apply(recipient),
					inventoryTracked, freshInventoryTypes.apply(recipient)));
		} else {
			deliverKnownRefresh(marker, tick, sessions, online, (recipient, sent) ->
				sendCachedInitial(sessions.get(recipient), marker, sent.ownerName, basic,
					sent.sections.get(PresentationBasic.ID), allowed.apply(recipient),
					packet -> delivery.accept(recipient, packet), freshAuthorization.apply(recipient),
					inventoryTracked, freshInventoryTypes.apply(recipient)));
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
				if (sent != null && sent.deliveredView == session.view) delivery.accept(recipient, sent);
			}
		}
	}

	public static void tick(MinecraftServer server, ServerMarkerStore store) {
		activate(server);
		long tick = server.getTickCount();
		String nextPolicy = settings().fingerprint();
		boolean policyChanged = !nextPolicy.equals(policyFingerprint);
		policyFingerprint = nextPolicy;
		for (var entry : SESSIONS.entrySet()) {
			Session session = entry.getValue();
			if (!session.ready || session.view == Long.MAX_VALUE) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) continue;
			Map<String, Map<String, Set<String>>> effective = mask(player, session);
			Map<String, List<PresentationPropertyRef>> effectiveChildBlack = childBlackFor(settings());
			Set<String> dedicated = inventoryTypes(player, session);
			if (!policyChanged && effective.equals(session.mask)
				&& effectiveChildBlack.equals(session.childBlack) && dedicated.equals(session.inventoryTypes)) continue;
			session.mask = effective;
			session.childBlack = effectiveChildBlack;
			session.inventoryTypes = dedicated;
			session.view++;
			session.authorizationCut = observationSerial;
			pruneSession(session, store, registry::get, (marker, adapter) ->
				allowed(player, session, adapter, marker.targetType().id()));
			send(player, PresentationS2CPacket.reset(session.epoch, session.view, effective, effectiveChildBlack));
			startBaseline(session, store);
			sendWinnerBaseline(session, player.getUUID(), server.getTickCount(), store, packet -> send(player, packet));
		}
		baselineRecipient = baselineTick(store, SESSIONS, baselineRecipient, tick, 256,
			recipient -> server.getPlayerList().getPlayer(recipient) != null, (recipient, marker) -> {
				ServerPlayer player = server.getPlayerList().getPlayer(recipient);
				Session session = SESSIONS.get(recipient);
				Lease lease = LEASES.get(marker.id().value());
				if (lease == null) lease = new Lease(marker, ownerName(server, session, marker));
				else lease.marker = marker;
				sendBaseline(session, lease, registry,
					adapter -> allowed(player, session, adapter, marker.targetType().id()),
					packet -> send(player, packet), InventoryServer.tracksInventory(marker.id()), effectiveInventoryTypes(player, session));
			});
		PresentationAdapter.CaptureBudget work = new PresentationAdapter.CaptureBudget(MAX_CAPTURE_WORK_PER_TICK);
		PresentationFairSampler.Result sampled = sampler.tick(store, LEASES, registry, settings(), tick, work,
			MAX_CAPTURES_PER_TICK, (marker, adapter) -> demanded(server, marker, adapter),
			(marker, demand) -> basic(server, marker, demand), marker -> ownerName(server, null, marker), lease -> {
			for (UUID recipient : lease.marker.recipients()) {
				Session session = SESSIONS.get(recipient); ServerPlayer player = server.getPlayerList().getPlayer(recipient);
				if (player != null && session != null && session.ready) publish(player, session, lease);
			}
		}, delaySink);
		if (previews != null) previews.drain(tick, work, MAX_CAPTURES_PER_TICK - sampled.captures());
		finishPresentationCycle(delaySink, tick, tick, sampled.state(), previews);
	}

	@FunctionalInterface interface LeaseCapture {
		int capture(Lease lease, PresentationAdapter.CaptureBudget work, int remainingCaptures);
	}
	/** Compatibility sequencing seam; the live tick uses the resumable canonical sampler. */
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

	static BudgetDelaySink.DelayState combineState(BudgetDelaySink.DelayState first, BudgetDelaySink.DelayState second) {
		if (first == BudgetDelaySink.DelayState.DELAYED || second == BudgetDelaySink.DelayState.DELAYED)
			return BudgetDelaySink.DelayState.DELAYED;
		if (first == BudgetDelaySink.DelayState.NEUTRAL) return first;
		if (first == BudgetDelaySink.DelayState.HEALTHY || second == BudgetDelaySink.DelayState.HEALTHY)
			return BudgetDelaySink.DelayState.HEALTHY;
		return second;
	}
	static void finishPresentationCycle(@org.jetbrains.annotations.Nullable BudgetDelaySink sink, long cycle, long serverTick,
		BudgetDelaySink.DelayState sampled, @org.jetbrains.annotations.Nullable PresentationPreviewServer previews) {
		if (sink == null) sink = BudgetDelaySink.NONE;
		BudgetDelaySink.DelayState state = previews == null ? sampled : combineState(sampled, previews.delayState());
		try { sink.state(BudgetDelaySink.Module.PRESENTATION, cycle, serverTick, state); }
		catch (RuntimeException | LinkageError ignored) {}
		try { sink.endCycle(BudgetDelaySink.Module.PRESENTATION, cycle, serverTick); }
		catch (RuntimeException | LinkageError ignored) {}
	}

	private static String ownerName(MinecraftServer server, Session session, ServerMarker marker) {
		SentMarker sent = session == null ? null : session.sent.get(marker.id().value());
		if (sent != null) return sent.ownerName;
		ServerPlayer owner = server.getPlayerList().getPlayer(marker.owner());
		return owner == null ? "" : owner.getGameProfile().getName();
	}

	private static Set<String> demanded(MinecraftServer server, ServerMarker marker, PresentationAdapter adapter) {
		Set<String> demand = new LinkedHashSet<>();
		for (UUID recipient : marker.recipients()) {
			Session session = SESSIONS.get(recipient);
			ServerPlayer player = server.getPlayerList().getPlayer(recipient);
			if (session != null && session.ready && player != null)
				demand.addAll(projectionAllowed(session, marker, adapter,
					allowed(player, session, adapter, marker.targetType().id())));
		}
		return Set.copyOf(demand);
	}

	private static int capture(MinecraftServer server, Lease lease, PresentationAdapter.CaptureBudget work,
		int remainingCaptures, boolean initial) {
		return captureSources(lease, registry, settings(), server.getTickCount(), work, remainingCaptures, initial,
			adapter -> demanded(server, lease.marker, adapter), demand -> basic(server, lease.marker, demand));
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
			Source source = sourceFor(lease, adapter, policy, false);
			if (demand.isEmpty()) { clearDemand(source, adapter); continue; }
			if (tick < source.nextSample || captureRefusal(adapter, policy, work, remainingCaptures - captures) != 0) continue;
			captureSource(lease, adapter, policy, tick, work, demand, basicCapture);
			captures++;
		}
		return captures;
	}

	static Source sourceFor(Lease lease, PresentationAdapter adapter, PresentationSettings policy, boolean seedFloor) {
		return lease.sources.computeIfAbsent(adapter.adapterId(), id -> {
			Source source = new Source(id, adapter.schema());
			if (seedFloor && (id.equals(PresentationBasic.ID) || lease.marker.properties().stream()
				.anyMatch(selection -> selection.ref().adapterId().equals(id))))
				source.nextSample = addTick(lease.marker.arrivalTick(), policy.interval(id, adapter.minUpdateIntervalTicks()));
			return source;
		});
	}

	static void clearDemand(Source source, PresentationAdapter adapter) {
		// An unobserved, undemanded adapter needs neither a replacement nor residency.
		if (source.observation == Source.Observation.NEVER && source.value.fields().isEmpty()) return;
		source.value = PresentationSection.empty(adapter.adapterId(), adapter.schema());
		source.demand = Set.of(); source.observation = Source.Observation.CLEARED;
	}

	static int captureRefusal(PresentationAdapter adapter, PresentationSettings policy,
		PresentationAdapter.CaptureBudget work, int remainingCaptures) {
		int reasons = 0;
		if (remainingCaptures <= 0) reasons |= BudgetDelaySink.Reason.CAPTURE_COUNT.bit();
		if (policy.scanBudget() == 0) reasons |= BudgetDelaySink.Reason.CAPTURE_DISABLED.bit();
		if (work.remaining() < (adapter.adapterId().equals(PresentationBasic.ID) ? 1 : 2))
			reasons |= BudgetDelaySink.Reason.SOURCE_WORK.bit();
		return reasons;
	}

	static void captureSource(Lease lease, PresentationAdapter adapter, PresentationSettings policy, long tick,
		PresentationAdapter.CaptureBudget work, Set<String> demand,
		Function<Set<String>, PresentationSection> basicCapture) {
		Source source = sourceFor(lease, adapter, policy, false);
		work.scan();
		source.nextSample = addTick(tick, policy.interval(adapter.adapterId(), adapter.minUpdateIntervalTicks()));
		@Nullable PresentationSection value = null;
		try {
			if (adapter.adapterId().equals(PresentationBasic.ID)) value = basicCapture.apply(demand);
			else {
				int allowance = Math.min(policy.scanBudget(), work.remaining());
				PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(allowance);
				try { value = adapter.collect(detached(lease.marker.target()), Set.copyOf(demand), budget); }
				finally { for (int used = allowance - budget.remaining(); used > 0; used--) work.scan(); }
			}
		} catch (RuntimeException | LinkageError ignored) {
			Diagnostics.warn("presentation/capture", adapter.adapterId(),
				() -> "target=" + lease.marker.target() + " demand=" + demand, ignored);
			value = null;
		}
		try { value = sanitize(adapter, value, demand); }
		catch (RuntimeException | LinkageError ignored) {
			Diagnostics.warn("presentation/sanitize", adapter.adapterId(),
				() -> "target=" + lease.marker.target() + " demand=" + demand, ignored);
			value = null;
		}
		source.value = transition(adapter.adapterId(), adapter.schema(), source.value, demand, value);
		source.observation = value == null || value.stale() ? Source.Observation.FAILED : Source.Observation.SUCCESS;
		if (source.observation == Source.Observation.SUCCESS) source.successfulSerial = nextObservationSerial();
		source.demand = Set.copyOf(demand);
	}

	static long addTick(long tick, long delay) { return tick > Long.MAX_VALUE - delay ? Long.MAX_VALUE : tick + delay; }
	private static long nextObservationSerial() {
		if (observationSerial != Long.MAX_VALUE) observationSerial++;
		return observationSerial;
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

	/** The fresh, complete per-target-type child deny map of the current policy. */
	static Map<String, List<PresentationPropertyRef>> childBlackFor(PresentationSettings settings) {
		Map<String, List<PresentationPropertyRef>> result = new LinkedHashMap<>();
		for (String type : PresentationSettings.TARGET_TYPE_IDS)
			result.put(type, settings == null ? List.of() : settings.policyFor(type).childBlack());
		return Map.copyOf(result);
	}

	/**
	 * The target type's effective child deny set: the advertised session map
	 * (its RESET view fence) unioned with the fresh policy, so a newly denied
	 * reference is denied immediately and a newly allowed reference is not
	 * promoted before a RESET re-baselines the view.
	 */
	static Set<PresentationPropertyRef> effectiveChildBlack(Session session, PresentationSettings settings,
		String targetTypeId) {
		if (!PresentationSettings.isKnownTargetType(targetTypeId)) return Set.of();
		Set<PresentationPropertyRef> result = new LinkedHashSet<>();
		if (session != null && session.childBlack != null)
			result.addAll(session.childBlack.getOrDefault(targetTypeId, List.of()));
		if (settings != null) result.addAll(settings.policyFor(targetTypeId).childBlack());
		return Set.copyOf(result);
	}

	/** True when the exact reference is denied by the advertised or fresh child deny list. */
	static boolean childBlackDenied(Session session, PresentationSettings settings, String targetTypeId,
		PresentationPropertyRef ref) {
		return ref != null && effectiveChildBlack(session, settings, targetTypeId).contains(ref);
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
		return project(adapter, source, allowed, selections, ref -> true);
	}

	/**
	 * The exact child predicate suppresses a selection annotation while the root
	 * value stays complete: a denied nested reference is never annotated, and no
	 * allow selector or admitted selection can restore it.
	 */
	static PresentationSection project(PresentationAdapter adapter, PresentationSection source,
		Set<String> allowed, List<PresentationPropertySelection> selections,
		Predicate<PresentationPropertyRef> childAllowed) {
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
				&& allowed.contains(selection.ref().fieldId()) && childAllowed.test(selection.ref())
				&& selection.ref().resolve(neutral) != null)
				annotations.put(selection.ref(), selection.pingTypeId());
		}
		return new PresentationSection(adapter.adapterId(), adapter.schema(), fields, neutral.stale(), annotations);
	}

	private static Predicate<PresentationPropertyRef> childAllowed(Session session, String targetTypeId) {
		Set<PresentationPropertyRef> denied = effectiveChildBlack(session, settings(), targetTypeId);
		return ref -> !denied.contains(ref);
	}

	private static PresentationSection project(ServerPlayer player, Session session, PresentationAdapter adapter, Lease lease) {
		Source source = lease.sources.get(adapter.adapterId());
		String targetType = lease.marker.targetType().id();
		return recipientProjection(session, lease.marker, adapter, source,
			allowed(player, session, adapter, targetType));
	}

	static Set<String> projectionAllowed(Session session, ServerMarker marker, PresentationAdapter adapter, Set<String> fresh) {
		if (!Objects.equals(session.schemas.get(adapter.adapterId()), adapter.schema())) return Set.of();
		Set<String> advertised = session.mask.getOrDefault(marker.targetType().id(), Map.of())
			.getOrDefault(adapter.adapterId(), Set.of());
		Set<String> result = new LinkedHashSet<>();
		for (PresentationField field : adapter.fields()) {
			if (fresh.contains(field.id()) && advertised.contains(field.id())
				&& session.manifest.getOrDefault(adapter.adapterId(), List.of()).stream()
					.anyMatch(accepted -> accepted.id().equals(field.id()) && accepted.kind() == field.kind())) result.add(field.id());
		}
		return Set.copyOf(result);
	}

	/** Deletes revoked recipient state in place before any neutral cache replay. */
	static void pruneKnown(Session session, ServerMarker marker, Function<String, PresentationAdapter> adapters,
		Function<PresentationAdapter, Set<String>> fresh) {
		SentMarker known = session.sent.get(marker.id().value());
		if (known == null) return;
		for (var entry : known.sections.entrySet()) {
			PresentationAdapter adapter = adapters.apply(entry.getKey());
			PresentationSection prior = entry.getValue();
			PresentationSection pruned = adapter == null ? PresentationSection.empty(prior.adapterId(), prior.schema())
				: pruneProjection(adapter, prior, projectionAllowed(session, marker, adapter, fresh.apply(adapter)),
					childAllowed(session, marker.targetType().id()));
			if (!pruned.fields().keySet().equals(prior.fields().keySet()) || !pruned.annotations().equals(prior.annotations()))
				session.authorizationCut = Math.max(session.authorizationCut, observationSerial);
			entry.setValue(pruned);
		}
	}

	private static PresentationSection pruneProjection(PresentationAdapter adapter, PresentationSection prior,
		Set<String> allowed, Predicate<PresentationPropertyRef> childAllowed) {
		PresentationSection fields = project(adapter, prior, allowed, List.of(), childAllowed);
		Map<PresentationPropertyRef, String> annotations = new LinkedHashMap<>();
		prior.annotations().forEach((ref, ping) -> {
			if (allowed.contains(ref.fieldId()) && childAllowed.test(ref) && ref.resolve(fields) != null) annotations.put(ref, ping);
		});
		return new PresentationSection(adapter.adapterId(), adapter.schema(), fields.fields(), prior.stale(), annotations);
	}

	static PresentationSection recipientProjection(Session session, ServerMarker marker, PresentationAdapter adapter,
		Source source, Set<String> fresh) {
		Set<String> allowed = projectionAllowed(session, marker, adapter, fresh);
		SentMarker known = session.sent.get(marker.id().value());
		PresentationSection prior = known == null ? null : known.sections.get(adapter.adapterId());
		Predicate<PresentationPropertyRef> child = childAllowed(session, marker.targetType().id());
		if (source == null || source.observation == Source.Observation.NEVER) {
			if (prior != null) return pruneProjection(adapter, prior, allowed, child);
			return new PresentationSection(adapter.adapterId(), adapter.schema(), Map.of(), true);
		}
		if (source.observation == Source.Observation.CLEARED) return PresentationSection.empty(adapter.adapterId(), adapter.schema());
		if (source.observation == Source.Observation.FAILED && prior != null) {
			PresentationSection retained = pruneProjection(adapter, prior, allowed, child);
			return new PresentationSection(adapter.adapterId(), adapter.schema(), retained.fields(), true, retained.annotations());
		}
		boolean old = known != null && session.authorizationCut >= 0 && source.successfulSerial <= session.authorizationCut;
		if (old) {
			Set<String> retainedKeys = prior == null ? Set.of() : prior.fields().keySet();
			Set<String> narrowed = new LinkedHashSet<>(allowed); narrowed.retainAll(retainedKeys);
			Set<PresentationPropertyRef> retainedRefs = prior == null ? Set.of() : prior.annotations().keySet();
			return project(adapter, source.value, narrowed, marker.properties(), ref -> child.test(ref) && retainedRefs.contains(ref));
		}
		return project(adapter, source.value, allowed, marker.properties(), child);
	}

	private static void sendInitial(ServerPlayer player, Session session, Lease lease) {
		PresentationAdapter basic = registry.get(PresentationBasic.ID);
		String targetType = lease.marker.targetType().id();
		sendInitial(session, lease, basic,
			allowed(player, session, basic, targetType), packet -> send(player, packet),
			adapter -> allowed(player, session, adapter, targetType),
			InventoryServer.tracksInventory(lease.marker.id()), effectiveInventoryTypes(player, session));
	}

	/** Test seam: no fresh non-Basic policy port, so content refs project conservatively. */
	static void sendInitial(Session session, Lease lease, PresentationAdapter basic, Set<String> allowed,
		java.util.function.Consumer<PresentationS2CPacket> delivery) {
		sendInitial(session, lease, basic, allowed, delivery, adapter -> Set.of());
	}

	/**
	 * Test seam: no fresh dedicated inventory gate is supplied, so an inventory
	 * receipt stays conservatively suppressed.
	 */
	static void sendInitial(Session session, Lease lease, PresentationAdapter basic, Set<String> allowed,
		java.util.function.Consumer<PresentationS2CPacket> delivery,
		Function<PresentationAdapter, Set<String>> freshAuthorization) {
		sendInitial(session, lease, basic, allowed, delivery, freshAuthorization, false, Set.of());
	}

	/**
	 * Project and deliver cached Basic atomically with the marker, without a
	 * source observation. Every initial exit computes the receipt descriptor
	 * from the marker's explicit selections, the tracked inventory sidecar and
	 * this recipient's fresh per-adapter and inventory authorization against
	 * the accepted manifest/mask and the advertised route/session view.
	 */
	static void sendInitial(Session session, Lease lease, PresentationAdapter basic, Set<String> allowed,
		java.util.function.Consumer<PresentationS2CPacket> delivery,
		Function<PresentationAdapter, Set<String>> freshAuthorization,
		boolean inventoryTracked, Set<String> freshInventoryTypes) {
		Source source = lease.sources.get(basic.adapterId());
		pruneKnown(session, lease.marker, id -> id.equals(basic.adapterId()) ? basic : registry.get(id),
			adapter -> adapter.adapterId().equals(basic.adapterId()) ? allowed : freshAuthorization.apply(adapter));
		sendProjectedInitial(session, lease.marker, lease.ownerName, basic,
			recipientProjection(session, lease.marker, basic, source, allowed), allowed, delivery, freshAuthorization,
			inventoryTracked, freshInventoryTypes);
	}

	static void sendCachedInitial(Session session, ServerMarker marker, String ownerName,
		PresentationAdapter basic, PresentationSection cached, Set<String> allowed,
		java.util.function.Consumer<PresentationS2CPacket> delivery,
		Function<PresentationAdapter, Set<String>> freshAuthorization,
		boolean inventoryTracked, Set<String> freshInventoryTypes) {
		pruneKnown(session, marker, id -> id.equals(basic.adapterId()) ? basic : registry.get(id),
			adapter -> adapter.adapterId().equals(basic.adapterId()) ? allowed : freshAuthorization.apply(adapter));
		// This path owns only the recipient's delivered projection, never a
		// successful neutral observation. Keep its retained annotations verbatim.
		SentMarker known = session.sent.get(marker.id().value());
		PresentationSection retained = known == null ? cached : known.sections.get(basic.adapterId());
		PresentationSection projected = retained == null
			? new PresentationSection(basic.adapterId(), basic.schema(), Map.of(), true)
			: pruneProjection(basic, retained, projectionAllowed(session, marker, basic, allowed),
				childAllowed(session, marker.targetType().id()));
		sendProjectedInitial(session, marker, ownerName, basic, projected,
			allowed, delivery, freshAuthorization, inventoryTracked, freshInventoryTypes);
	}

	/** Read-free, Basic-first replay. A first baseline's optional projections precede sent-state insertion. */
	static void sendBaseline(Session session, Lease lease, PresentationRegistry adapters,
		Function<PresentationAdapter, Set<String>> fresh, java.util.function.Consumer<PresentationS2CPacket> delivery,
		boolean inventoryTracked, Set<String> freshInventoryTypes) {
		pruneKnown(session, lease.marker, adapters::get, fresh);
		SentMarker prior = session.sent.get(lease.marker.id().value());
		Map<String, PresentationSection> optional = new LinkedHashMap<>();
		for (PresentationAdapter adapter : adapters.sectionAdapters()) {
			if (adapter.adapterId().equals(PresentationBasic.ID) || !session.schemas.containsKey(adapter.adapterId())) continue;
			Source source = lease.sources.get(adapter.adapterId());
			if ((source == null || source.observation == Source.Observation.NEVER)
				&& (prior == null || !prior.sections.containsKey(adapter.adapterId()))) continue;
			optional.put(adapter.adapterId(), recipientProjection(session, lease.marker, adapter, source, fresh.apply(adapter)));
		}
		PresentationAdapter basic = adapters.get(PresentationBasic.ID);
		sendProjectedInitial(session, lease.marker, lease.ownerName, basic,
			recipientProjection(session, lease.marker, basic, lease.sources.get(basic.adapterId()), fresh.apply(basic)),
			fresh.apply(basic), delivery, fresh, inventoryTracked, freshInventoryTypes);
		SentMarker sent = session.sent.get(lease.marker.id().value());
		if (sent == null || sent.deliveredView != session.view) return;
		optional.forEach((id, projected) -> sendProjectedSection(session, lease.marker.id(), adapters.get(id), projected,
			sent.sections, delivery));
	}

	private static void sendProjectedInitial(Session session, ServerMarker marker, String ownerName,
		PresentationAdapter basic, PresentationSection cached, Set<String> allowed,
		java.util.function.Consumer<PresentationS2CPacket> delivery,
		Function<PresentationAdapter, Set<String>> freshAuthorization,
		boolean inventoryTracked, Set<String> freshInventoryTypes) {
		PresentationPropertyRef defaultRef = PresentationDefaults.forTargetType(marker.targetType().id())
			.orElse(null);
		if (defaultRef == null) return;
		// A semantically valid Basic capture may exceed the encoded field bounds;
		// degrade to the established empty stale section instead of throwing here.
		PresentationSection projected = PresentationCodec.bounded(cached);
		PresentationReceiptContent content = receipt(session, marker, basic, allowed, freshAuthorization,
			inventoryTracked, freshInventoryTypes);
		// Old-cache replay may retain a selected Basic value after its annotation
		// was pruned. Do not promise that selection in the atomic initial until a
		// fresh capture restores the annotation; genuinely absent values still wait.
		if (content.kind() == PresentationReceiptContent.Kind.PROPERTIES) {
			for (PresentationPropertyRef ref : content.selectedRefs()) {
				if (basic.adapterId().equals(ref.adapterId()) && ref.resolve(projected) != null
					&& projected.annotations().get(ref) == null) {
					content = PresentationReceiptContent.suppressed();
					break;
				}
			}
		}
		SentMarker prior = session.sent.get(marker.id().value());
		String deliveredOwner = prior == null ? ownerName : prior.ownerName;
		delivery.accept(PresentationS2CPacket.created(session.epoch, session.view,
			++session.revision, MarkerSnapshot.from(marker), deliveredOwner, defaultRef, content, projected));
		SentMarker sent = session.sent.computeIfAbsent(marker.id().value(), id -> new SentMarker(deliveredOwner));
		sent.sections.put(PresentationBasic.ID, projected);
		sent.deliveredView = session.view;
	}

	/**
	 * Per-recipient receipt projection: the accepted manifest/schema, the
	 * advertised field mask and fresh policy authorization gate every explicit
	 * selection, while the tracked inventory sidecar and the recipient's fresh
	 * inventory authorization (already intersected with the advertised
	 * route/session view) gate the dedicated inventory kind. A denied or
	 * incompatible input suppresses the whole content message without
	 * disclosing references.
	 */
	private static PresentationReceiptContent receipt(Session session, ServerMarker marker,
		PresentationAdapter basic, Set<String> basicAllowed,
		Function<PresentationAdapter, Set<String>> freshAuthorization,
		boolean inventoryTracked, Set<String> freshInventoryTypes) {
		String targetType = marker.targetType().id();
		boolean nameAuthorized = receiptFieldAuthorized(session, targetType, basic,
			PresentationPropertyRef.root(basic.adapterId(), PresentationBasic.NAME), basicAllowed);
		boolean inventoryAuthorized = inventoryTracked
			&& Objects.equals(session.schemas.get(InventoryPresentation.ADAPTER_ID), InventoryPresentation.SCHEMA)
			&& freshInventoryTypes.contains(targetType);
		return PresentationReceiptProjector.project(marker.properties(), inventoryTracked, inventoryAuthorized,
			nameAuthorized,
			ref -> {
				PresentationAdapter adapter = ref.adapterId().equals(basic.adapterId())
					? basic : registry.get(ref.adapterId());
				if (adapter == null) return false;
				Set<String> fresh = adapter == basic ? basicAllowed : freshAuthorization.apply(adapter);
				return receiptFieldAuthorized(session, targetType, adapter, ref, fresh);
			});
	}

	/**
	 * Accepted schema/kind, advertised mask, fresh permission, and the exact
	 * child deny predicate must all admit the reference; an allow selector never
	 * overrides a child deny.
	 */
	private static boolean receiptFieldAuthorized(Session session, String targetTypeId, PresentationAdapter adapter,
		PresentationPropertyRef ref, Set<String> freshAllowed) {
		if (ref == null || !Objects.equals(session.schemas.get(adapter.adapterId()), adapter.schema())) return false;
		List<PresentationField> advertised = session.manifest.get(adapter.adapterId());
		PresentationField local = null;
		for (PresentationField field : adapter.fields()) if (field.id().equals(ref.fieldId())) { local = field; break; }
		if (local == null || advertised == null) return false;
		PresentationField.Kind requiredKind = local.kind();
		boolean compatible = advertised.stream().anyMatch(field ->
			field.id().equals(ref.fieldId()) && field.kind() == requiredKind);
		if (!compatible) return false;
		if (!session.mask.getOrDefault(targetTypeId, Map.of())
			.getOrDefault(adapter.adapterId(), Set.of()).contains(ref.fieldId())) return false;
		if (childBlackDenied(session, settings(), targetTypeId, ref)) return false;
		return freshAllowed.contains(ref.fieldId());
	}

	private static void publish(ServerPlayer player, Session session, Lease lease) {
		SentMarker marker = session.sent.get(lease.marker.id().value());
		Map<String, PresentationSection> sent = marker == null ? null : marker.sections;
		if (sent == null || marker.deliveredView != session.view) return;
		Map<String, PresentationSection> beforePruning = new HashMap<>(sent);
		pruneKnown(session, lease.marker, registry::get,
			adapter -> allowed(player, session, adapter, lease.marker.targetType().id()));
		for (PresentationAdapter adapter : registry.sectionAdapters()) {
			if (!session.schemas.containsKey(adapter.adapterId())) continue;
			PresentationSection projected = project(player, session, adapter, lease);
			Source source = lease.sources.get(adapter.adapterId());
			if (!sent.containsKey(adapter.adapterId()) && (source == null || source.observation == Source.Observation.NEVER)) continue;
			if (projected.equals(beforePruning.get(adapter.adapterId()))) continue;
			sendProjectedSection(player, session, lease.marker.id(), adapter, projected, sent);
		}
	}

	private static void sendProjectedSection(ServerPlayer player, Session session, MarkerId markerId,
		PresentationAdapter adapter, PresentationSection projected, Map<String, PresentationSection> sent) {
		sendProjectedSection(session, markerId, adapter, projected, sent, packet -> send(player, packet));
	}
	static void sendProjectedSection(Session session, MarkerId markerId, PresentationAdapter adapter,
		PresentationSection projected, Map<String, PresentationSection> sent,
		java.util.function.Consumer<PresentationS2CPacket> delivery) {
		String stage = "section-construction";
		try {
			PresentationS2CPacket packet = PresentationS2CPacket.section(session.epoch, session.view,
				++session.revision, markerId, projected);
			stage = "transport";
			delivery.accept(packet);
			stage = "sent-state";
			sent.put(adapter.adapterId(), projected);
		} catch (RuntimeException oversized) {
			String failedStage = stage;
			Diagnostics.warn("presentation/send-section", adapter.adapterId(),
				() -> "stage=" + failedStage + " marker=" + markerId + " epoch=" + session.epoch
					+ " view=" + session.view + " revision=" + session.revision, oversized);
			PresentationSection empty = new PresentationSection(adapter.adapterId(), adapter.schema(), Map.of(), true);
			delivery.accept(PresentationS2CPacket.section(session.epoch, session.view,
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

	/**
	 * Fresh server permission/policy and the exact child deny fence intersected
	 * with the already-advertised SECTION view.
	 */
	public static Optional<PresentationPreviewAccess> previewAccess(UUID playerId, String targetTypeId) {
		Session session = SESSIONS.get(playerId);
		ServerPlayer player = activeServer == null ? null : activeServer.getPlayerList().getPlayer(playerId);
		if (player == null) return Optional.empty();
		return previewAccess(session, registry, targetTypeId,
			adapter -> allowed(player, session, adapter, targetTypeId),
			effectiveChildBlack(session, settings(), targetTypeId));
	}
	static Optional<PresentationPreviewAccess> previewAccess(Session session, PresentationRegistry adapters,
		String targetTypeId, Function<PresentationAdapter, Set<String>> freshAuthorization) {
		return previewAccess(session, adapters, targetTypeId, freshAuthorization,
			session == null ? Set.of() : Set.copyOf(session.childBlack.getOrDefault(targetTypeId, List.of())));
	}
	static Optional<PresentationPreviewAccess> previewAccess(Session session, PresentationRegistry adapters,
		String targetTypeId, Function<PresentationAdapter, Set<String>> freshAuthorization,
		Set<PresentationPropertyRef> childBlack) {
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
		return Optional.of(new PresentationPreviewAccess(session.epoch, session.view, targetTypeId, result,
			childBlack == null ? Set.of() : childBlack));
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
		return Optional.of(new InventoryPolicy(session.epoch, session.view,
			effectiveInventoryTypes(player, session)));
	}

	/**
	 * The recipient's single fresh dedicated-inventory authorization: current
	 * permission/policy intersected with the already-advertised route/session
	 * view, so a newly allowed type is not promoted before RESET and a revoked
	 * type is denied immediately. The dedicated policy and every initial
	 * receipt share this one gate.
	 */
	private static Set<String> effectiveInventoryTypes(ServerPlayer player, Session session) {
		Set<String> fresh = new HashSet<>(inventoryTypes(player, session));
		fresh.retainAll(session.inventoryTypes);
		return Set.copyOf(fresh);
	}
	public static boolean inventoryKnows(UUID player, MarkerId marker) {
		Session session = SESSIONS.get(player);
		return inventoryKnows(session, marker);
	}
	static boolean inventoryKnows(Session session, MarkerId marker) {
		SentMarker sent = session == null ? null : session.sent.get(marker.value());
		return session != null && session.ready && sent != null && sent.deliveredView == session.view;
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
		Set<PresentationPropertyRef> deniedChildren = effectiveChildBlack(session, settings(), targetTypeId);
		// Reject forged/revoked references before world lookup or external source observation.
		if (intents == null || intents.stream().anyMatch(intent -> intent == null
			|| deniedChildren.contains(intent.ref())
			|| !allowed.getOrDefault(intent.ref().adapterId(), Set.of()).contains(intent.ref().fieldId())
			|| !session.mask.getOrDefault(targetTypeId, Map.of()).getOrDefault(intent.ref().adapterId(), Set.of()).contains(intent.ref().fieldId())))
			return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
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
					if (committed instanceof Target.ExternalBlockTarget external) {
						ExternalNameObservation names = null;
						if (demand.contains(PresentationBasic.NAME) || demand.contains(PresentationBasic.CUSTOM_NAME))
							names = externalName(ExternalBlockServerProviders.registry().resolveName(level, external),
								server.registryAccess(), null, demand);
						return assembleExternalBasic(demand, observedState, names);
					}
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
			},
			ref -> !deniedChildren.contains(ref));
	}

	record ExternalAdmissionObservation(BlockState state, boolean nameOnly) {}

	/**
	 * Name-only external admission skips every non-name Basic field. A custom
	 * name is a name observation too: it is admitted without forcing the
	 * composed {@code minecraft:target.name} into the same demand.
	 */
	static Set<String> admissionBasicDemand(Set<String> allowed, boolean externalNameOnly) {
		if (!externalNameOnly) return Set.copyOf(allowed);
		Set<String> names = new LinkedHashSet<>();
		if (allowed.contains(PresentationBasic.NAME)) names.add(PresentationBasic.NAME);
		if (allowed.contains(PresentationBasic.CUSTOM_NAME)) names.add(PresentationBasic.CUSTOM_NAME);
		return Set.copyOf(names);
	}

	private static boolean externalNameOnly(List<PresentationPropertyIntent> intents) {
		return intents != null && !intents.isEmpty() && intents.stream().allMatch(intent ->
			intent != null && intent.pingTypeId() == null && intent.ref().isRoot()
				&& intent.ref().adapterId().equals(PresentationBasic.ID)
				&& (intent.ref().fieldId().equals(PresentationBasic.NAME)
					|| intent.ref().fieldId().equals(PresentationBasic.CUSTOM_NAME)));
	}

	static ExternalAdmissionObservation observeExternalAdmission(List<PresentationPropertyIntent> intents,
		Supplier<ExternalBlockServerProvider.ObservationResult> observe) {
		if (externalNameOnly(intents)) return new ExternalAdmissionObservation(null, true);
		ExternalBlockServerProvider.ObservationResult result = observe.get();
		return result instanceof ExternalBlockServerProvider.ObservationResult.Available available
			? new ExternalAdmissionObservation(available.observation().state(), false) : null;
	}

	/**
	 * One provider name observation: the composed display name and the plain
	 * custom-name text, both derived from the same provider resolution so a
	 * custom-name demand never triggers a second provider read. Either part may
	 * be absent.
	 */
	record ExternalNameObservation(TargetNameJson composedName, String customName) {}

	/**
	 * Derives both Basic name fields from one provider resolution.
	 * {@code nameFallback} is used only when the provider has no name: the
	 * committed sampling route keeps its established UNKNOWN fallback, while
	 * admission and candidate preview stay absent. A missing or empty custom
	 * name stays absent and is never replaced by the vanilla or base name.
	 */
	static ExternalNameObservation externalName(
		Optional<ExternalBlockServerProvider.ExternalBlockName> observed,
		HolderLookup.Provider registries, TargetNameJson nameFallback, Set<String> demand) {
		TargetNameJson composed = demand.contains(PresentationBasic.NAME) ? nameFallback : null;
		if (observed.isEmpty()) return new ExternalNameObservation(composed, null);
		ExternalBlockServerProvider.ExternalBlockName name = observed.orElseThrow();
		String custom = demand.contains(PresentationBasic.CUSTOM_NAME) ? name.customName()
			.map(component -> TargetNameComposer.plainText(component, PresentationLimits.MAX_TEXT_BYTES))
			.filter(text -> !text.isEmpty())
			.orElse(null) : null;
		if (demand.contains(PresentationBasic.NAME)) composed = availableExternalName(observed, registries);
		return new ExternalNameObservation(composed, custom);
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
		return basicBlock(demand, state,
			() -> level.getBlockEntity(new BlockPos(block.x(), block.y(), block.z())),
			component -> TargetNameJsonCodec.encode(component, server.registryAccess()).value());
	}

	/** World-free block assembly; the block-entity getter is reached only for demanded names. */
	static PresentationSection basicBlock(Set<String> demand, BlockState state, Supplier<?> blockEntity,
		Function<Component, String> encodeName) {
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		if (demand.contains(PresentationBasic.BLOCK_STATE))
			put(fields, demand, PresentationBasic.BLOCK_STATE, blockState(state));
		if (demand.contains(PresentationBasic.NAME) || demand.contains(PresentationBasic.CUSTOM_NAME)) {
			// The block entity's custom name is read once and feeds both the composed
			// name and the separate plain custom-name field. A recoverable read failure
			// omits only the dependent name fields.
			@Nullable Component custom = null;
			boolean customNameReadable = true;
			try {
				Object observed = blockEntity.get();
				custom = observed instanceof Nameable named && named.hasCustomName() ? named.getCustomName() : null;
			} catch (RuntimeException | LinkageError unavailable) {
				Diagnostics.warn("presentation/basic-block-name-read", "minecraft", () -> "demand=" + demand, unavailable);
				customNameReadable = false;
			}
			if (customNameReadable) {
				if (demand.contains(PresentationBasic.NAME)) {
					@Nullable Component name = composeName(custom, state.getBlock().getName());
					if (name != null) {
						try {
							put(fields, demand, PresentationBasic.NAME,
								new PresentationValue.Text(encodeName.apply(name)));
						} catch (RuntimeException | LinkageError unavailable) {
							Diagnostics.warn("presentation/basic-name-encode", "block", () -> "demand=" + demand, unavailable);
							// NAME only; an independently captured plain custom name stays fresh.
						}
					}
				}
				if (demand.contains(PresentationBasic.CUSTOM_NAME)) putCustomName(fields, demand, custom);
			}
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
			// One provider resolution feeds both name fields; the committed sampling
			// route keeps the established UNKNOWN fallback for an unavailable name.
			return basicExternal(demand,
				() -> ExternalBlockServerProviders.registry().observeBlock(server, level, external),
				() -> committedExternalName(server, owner, external, demand));
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

	/** Preserve the existing composed-name requester/dimension authority and fail-safe fallback. */
	private static ExternalNameObservation committedExternalName(MinecraftServer server, UUID owner,
		Target.ExternalBlockTarget target, Set<String> demand) {
		try {
			ServerPlayer player = server.getPlayerList().getPlayer(owner);
			if (player != null && target.dimensionId().equals(player.serverLevel().dimension().location().toString()))
				return externalName(ExternalBlockServerProviders.registry().resolveName(player.serverLevel(), target),
					server.registryAccess(), TargetNameJsonCodec.UNKNOWN, demand);
		} catch (RuntimeException unavailable) {
			Diagnostics.warn("presentation/basic-external-name", "provider", () -> "target=" + target, unavailable);
			// A failed source must not synthesize custom text or change the existing NAME fallback.
		}
		return new ExternalNameObservation(demand.contains(PresentationBasic.NAME) ? TargetNameJsonCodec.UNKNOWN : null, null);
	}

	static PresentationSection basicExternal(Set<String> demand,
		java.util.function.Supplier<ExternalBlockServerProvider.ObservationResult> observe,
		java.util.function.Supplier<ExternalNameObservation> resolveName) {
		BlockState state = null;
		if (demand.contains(PresentationBasic.BLOCK_STATE)) {
			ExternalBlockServerProvider.ObservationResult result = observe.get();
			if (!(result instanceof ExternalBlockServerProvider.ObservationResult.Available available)) return null;
			state = available.observation().state();
		}
		ExternalNameObservation names = (demand.contains(PresentationBasic.NAME)
			|| demand.contains(PresentationBasic.CUSTOM_NAME)) && resolveName != null ? resolveName.get() : null;
		return assembleExternalBasic(demand, state, names);
	}

	/**
	 * Preview-only Basic assembly for an uncommitted external candidate observed
	 * through its resolved safe read source. The committed observe route above is
	 * deliberately not reused because it requires an active provider reference.
	 * The caller supplies the already-admitted physical state and the lazy
	 * provider name resolution; both remain detached from marker state. Both name
	 * fields use the same observation, independently gated by authorized demand.
	 */
	static PresentationSection basicPreview(BlockReadSource source, Target target, BlockState state,
		Set<String> demand, Supplier<ExternalNameObservation> resolveName) {
		if (source == null || !(target instanceof Target.ExternalBlockTarget external) || !external.isCandidate()
			|| !(source.target() instanceof Target.ExternalBlockTarget bound)
			|| !MinecraftBlockReadSources.sameReadBinding(bound, external)) return null;
		if (demand.contains(PresentationBasic.BLOCK_STATE) && state == null) return null;
		ExternalNameObservation names = (demand.contains(PresentationBasic.NAME)
			|| demand.contains(PresentationBasic.CUSTOM_NAME)) && resolveName != null ? resolveName.get() : null;
		return assembleExternalBasic(demand, state, names);
	}

	static PresentationSection assembleExternalBasic(Set<String> demand, BlockState state,
		ExternalNameObservation names) {
		if (demand.contains(PresentationBasic.BLOCK_STATE) && state == null) return null;
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		if (state != null && demand.contains(PresentationBasic.BLOCK_STATE))
			put(fields, demand, PresentationBasic.BLOCK_STATE, blockState(state));
		if (names != null) {
			if (demand.contains(PresentationBasic.NAME) && names.composedName() != null)
				put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(names.composedName().value()));
			if (demand.contains(PresentationBasic.CUSTOM_NAME) && names.customName() != null && !names.customName().isEmpty())
				put(fields, demand, PresentationBasic.CUSTOM_NAME, new PresentationValue.Text(names.customName()));
		}
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
	 * name and value rules regression-tested without a running server. A
	 * recoverable name-acquisition, composition, reduction or encoding failure
	 * stays local to the name field it feeds; independently captured values
	 * (health, type, item data) stay fresh.
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
			if (demand.contains(PresentationBasic.ITEM_ID) || demand.contains(PresentationBasic.ITEM_COUNT)
				|| demand.contains(PresentationBasic.NAME) || demand.contains(PresentationBasic.CUSTOM_NAME)) {
				var stack = item.getItem();
				if (demand.contains(PresentationBasic.ITEM_ID)) put(fields, demand, PresentationBasic.ITEM_ID, new PresentationValue.Text(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
				if (demand.contains(PresentationBasic.ITEM_COUNT)) put(fields, demand, PresentationBasic.ITEM_COUNT, new PresentationValue.NumberValue(stack.getCount()));
				if (demand.contains(PresentationBasic.NAME) || demand.contains(PresentationBasic.CUSTOM_NAME)) {
					// A dropped item's custom name comes from the stack only; the entity's
					// own custom name is never a fallback for this separate field.
					Component stackCustom = stack.get(DataComponents.CUSTOM_NAME);
					if (demand.contains(PresentationBasic.NAME))
						name = composeName(stackCustom, Component.translatable(stack.getDescriptionId()));
					if (demand.contains(PresentationBasic.CUSTOM_NAME)) putCustomName(fields, demand, stackCustom);
				}
			}
		} else if (demand.contains(PresentationBasic.NAME) || demand.contains(PresentationBasic.CUSTOM_NAME)) {
			if (entity instanceof ServerPlayer player) {
				if (demand.contains(PresentationBasic.NAME)) name = Component.literal(player.getGameProfile().getName());
				// A player target has no custom-name field; the profile name is never reused here.
			} else {
				@Nullable Component custom = null;
				boolean customNameReadable = true;
				try {
					custom = entity.getCustomName();
				} catch (RuntimeException | LinkageError unavailable) {
					Diagnostics.warn("presentation/basic-entity-name-read", "minecraft", () -> "entityClass=" + entity.getClass().getName(), unavailable);
					customNameReadable = false;
				}
				if (customNameReadable) {
					if (demand.contains(PresentationBasic.NAME)) name = composeName(custom, entity.getType().getDescription());
					if (demand.contains(PresentationBasic.CUSTOM_NAME)) putCustomName(fields, demand, custom);
				}
			}
		}
		if (name != null) {
			try {
				put(fields, demand, PresentationBasic.NAME, new PresentationValue.Text(encodeName.apply(name)));
			} catch (RuntimeException | LinkageError unavailable) {
				Diagnostics.warn("presentation/basic-name-encode", "entity", () -> "entityClass=" + entity.getClass().getName(), unavailable);
				// NAME only; every independently captured value stays fresh.
			}
		}
		return new PresentationSection(PresentationBasic.ID, 1, fields, false);
	}

	/**
	 * The separate plain custom-name field: absent for a missing or empty custom
	 * name, never replaced by a base, type, or profile name. The bounded plain
	 * reduction strips styles and events while preserving literal text. A
	 * recoverable reduction failure leaves only this field absent.
	 */
	private static void putCustomName(Map<String, PresentationValue> fields, Set<String> demand, @Nullable Component custom) {
		if (!demand.contains(PresentationBasic.CUSTOM_NAME) || custom == null) return;
		try {
			String text = TargetNameComposer.plainText(custom, PresentationLimits.MAX_TEXT_BYTES);
			if (!text.isEmpty()) put(fields, demand, PresentationBasic.CUSTOM_NAME, new PresentationValue.Text(text));
		} catch (RuntimeException | LinkageError unavailable) {
			Diagnostics.warn("presentation/basic-custom-name", "minecraft", () -> "plain custom-name reduction", unavailable);
			// An unreducible custom component stays absent; the composed name and every
			// unrelated Basic field remain independently fresh.
		}
	}

	/**
	 * The optional-custom-name rule shared by every Basic name source: an
	 * absent custom name means the target is unnamed and keeps the trusted
	 * localized base component; only a present custom name is composed as
	 * {@code Custom (Base)}. Mirrors {@link MinecraftTargetNameResolver} and
	 * must never apply the strict composition to a possibly-absent name.
	 */
	static Component optionalName(@Nullable Component customName, Component baseName) {
		return customName != null ? TargetNameComposer.compose(customName, baseName) : baseName;
	}

	/** Composed-name build: a malformed custom component yields no composed name instead of failing the section. */
	private static @Nullable Component composeName(@Nullable Component customName, Component baseName) {
		try {
			return optionalName(customName, baseName);
		} catch (RuntimeException | LinkageError unavailable) {
			Diagnostics.warn("presentation/basic-name-compose", "minecraft", () -> "optional custom/base composition", unavailable);
			return null;
		}
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
