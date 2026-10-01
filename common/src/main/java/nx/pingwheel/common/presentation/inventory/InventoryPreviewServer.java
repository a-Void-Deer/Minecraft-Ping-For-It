package nx.pingwheel.common.presentation.inventory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.InventoryS2CPacket.Entry;
import nx.pingwheel.common.network.InventoryS2CPacket.Status;
import nx.pingwheel.common.presentation.source.SourceKey;

/** Server-thread preview sessions. World access and packet transport are supplied by the host. */
public final class InventoryPreviewServer {

	public record Display(String itemId, String label, String json, boolean componentsStripped) {}

	public record Resolved(SourceKey key, InventoryScanner.Source source,
		Function<InventoryScanner.Key, Display> display) {
		public Resolved {
			Objects.requireNonNull(key);
			Objects.requireNonNull(source);
			Objects.requireNonNull(display);
		}
	}

	public interface Host {
		/** Includes current target, permission, lock, loot and loaded-chunk checks, even for cached sends. */
		Optional<Resolved> resolve(UUID player, Target target);
		int encodedBytes(InventoryS2CPacket packet);
		void send(UUID player, InventoryS2CPacket packet);
	}

	private static final int MAX_SESSIONS = 1024;
	private static final int MAX_TARGETS = 16;
	private static final int MAX_ENTRIES = 256;
	private static final int MAX_SOURCE_SLOTS = 4096;
	private static final int MAX_ROUNDS = 16;
	private static final int MAX_VISITS_PER_TICK = 128;
	private static final long HELLO_INTERVAL = 20;
	// Conservative retained-object admission, separate from the logical encoded queue ceiling.
	private static final long REQUEST_MEMORY = 1024;
	private static final long ENTRY_MEMORY = 2L * InventoryS2CPacket.MAX_ENTRY_BYTES + 1536;
	private static final long SESSION_MEMORY = 1024;
	private static final int FRAME_WORKSPACE = 2 * (InventoryS2CPacket.MAX_FRAME_BYTES + InventoryS2CPacket.MAX_ENTRY_BYTES);
	private static final int ENTRY_UPPER_BYTES = InventoryS2CPacket.MAX_ENTRY_BYTES + 128;

	private final Host host;
	private final Map<UUID, Session> sessions = new LinkedHashMap<>();
	private final Map<SourceKey, Round> rounds = new LinkedHashMap<>();
	private long nextEpoch;
	private long nextPreviewPeriod = Long.MIN_VALUE;
	private long currentTick = Long.MIN_VALUE;
	private long retainedBytes;
	private long queuedBytes;
	private long globalBytes;
	private long globalControls;
	private int globalSlots;
	private int physicalSlots;
	private int rotation;
	private InventorySettings settings;

	public InventoryPreviewServer(Host host, long firstEpoch) {
		this.host = Objects.requireNonNull(host);
		if (firstEpoch <= 0) throw new IllegalArgumentException("nonpositive inventory epoch");
		nextEpoch = firstEpoch;
	}

	public void handle(UUID player, InventoryC2SPacket packet, long tick, InventorySettings settings) {
		Objects.requireNonNull(player);
		advance(tick, settings);
		if (packet == null || packet.isCorrupt()) return;
		Session session = sessions.get(player);
		if (packet.kind() == InventoryC2SPacket.Kind.HELLO) {
			if (session == null) {
				if (sessions.size() >= MAX_SESSIONS || nextEpoch == Long.MAX_VALUE || !room(SESSION_MEMORY + FRAME_WORKSPACE)) return;
				session = new Session(player, nextEpoch++);
				sessions.put(player, session);
				retainedBytes += SESSION_MEMORY;
			}
			if (session.lastHello != Long.MIN_VALUE && tick - session.lastHello < HELLO_INTERVAL) return;
			session.lastHello = tick;
			session.offerPending = true;
			sendOffer(session);
			return;
		}
		if (session == null || !session.offered || packet.epoch() != session.epoch) return;
		switch (packet.kind()) {
			case OPEN -> open(session, packet.requestId(), packet.target());
			case CLOSE -> close(session, packet.requestId());
			case RESYNC -> {
				if (packet.markerId() != null) return;
				Request request = session.requests.get(packet.requestId());
				long cooldown = (long) settings.getTracking().getPeriodTicks() * settings.getTracking().getResyncMinPeriods();
				if (request != null && (request.lastResync == Long.MIN_VALUE || tick - request.lastResync >= cooldown)) {
					request.lastResync = tick;
					// An admitted sweep keeps its progress. A completed request may replay its terminal status.
					request.statusPending = true;
				}
			}
			case SELECT -> { /* Dedicated marker admission is required; this preview service cannot create markers. */ }
			default -> { }
		}
	}

	public void tick(long tick, InventorySettings settings) {
		advance(tick, settings);
		List<Session> active = new ArrayList<>(sessions.values());
		if (active.isEmpty()) return;
		int visits = 0;
		for (int i = 0; i < active.size() && visits < MAX_VISITS_PER_TICK; i++) {
			Session session = active.get((rotation + i) % active.size());
			if (session.offerPending) sendOffer(session);
			for (Request request : session.requests.values()) {
				if (++visits > MAX_VISITS_PER_TICK) break;
				visit(session, request);
			}
		}
		rotation = (rotation + 1) % active.size();
	}

	public void disconnect(UUID player) {
		Session session = sessions.remove(player);
		if (session == null) return;
		for (long request : List.copyOf(session.requests.keySet())) close(session, request);
		retainedBytes -= SESSION_MEMORY;
	}

	public void reset() {
		for (UUID player : List.copyOf(sessions.keySet())) disconnect(player);
		rounds.clear();
		queuedBytes = 0;
		nextPreviewPeriod = Long.MIN_VALUE;
		currentTick = Long.MIN_VALUE;
	}

	private void advance(long tick, InventorySettings settings) {
		if (tick < currentTick) throw new IllegalArgumentException("inventory clock moved backwards");
		this.settings = Objects.requireNonNull(settings);
		if (tick != currentTick) {
			currentTick = tick;
			physicalSlots = 0;
		}
		if (tick >= nextPreviewPeriod) {
			nextPreviewPeriod = Math.addExact(tick, settings.getPreview().getPeriodTicks());
			globalSlots = 0;
			globalBytes = globalControls = 0;
			for (Session session : sessions.values()) {
				session.slots = session.variants = 0;
				session.bytes = session.controls = 0;
			}
		}
	}

	private void open(Session session, long id, Target target) {
		Request existing = session.requests.get(id);
		if (existing != null) return; // A request identity never changes its target.
		if (session.requests.size() >= Math.min(MAX_TARGETS, settings.getPreview().effectiveMaxTargetsPerClient())
			|| !room(REQUEST_MEMORY + FRAME_WORKSPACE)) return;
		Request request = new Request(id, target);
		session.requests.put(id, request);
		retainedBytes += REQUEST_MEMORY;
		// Resolution is scheduled by tick, rather than allowing OPEN spam to perform world reads.
	}

	private void visit(Session session, Request request) {
		if (request.terminal != null && request.round == null) {
			sendStatus(session, request);
			return;
		}
		Optional<Resolved> resolution;
		try {
			resolution = host.resolve(session.player, request.target);
		} catch (RuntimeException | LinkageError unavailable) {
			resolution = Optional.empty();
		}
		if (resolution.isEmpty()) {
			invalidate(request, request.round == null ? Status.UNAVAILABLE : Status.INVALID);
			sendStatus(session, request);
			return;
		}
		Resolved resolved = resolution.get();
		if (request.round != null && !request.round.resolved.key().equals(resolved.key())) {
			invalidate(request, Status.INVALID);
			sendStatus(session, request);
			return;
		}
		if (request.round == null) {
			Round round = rounds.get(resolved.key());
			if (round == null) {
				if (rounds.size() >= MAX_ROUNDS) return;
				round = new Round(resolved);
				rounds.put(resolved.key(), round);
			}
			round.references++;
			request.round = round;
		}
		if (request.terminal == null) scan(session, request);
		flush(session, request);
		sendStatus(session, request);
	}

	private void scan(Session session, Request request) {
		int logical = Math.min(limit(settings.getPreview().effectiveMaxSlotsPerClient()) - session.slots,
			limit(settings.getPreview().effectiveMaxSlotsServer()) - globalSlots);
		long queueRoom = settings.previewQueueBytes() - queuedBytes;
		// Reserve a whole-entry upper bound for every potentially discovered/changed slot before reading.
		int grant = (int) Math.min(Math.min(Math.max(0, logical), 128), Math.max(0, queueRoom / ENTRY_UPPER_BYTES));
		int freeEntries = request.entryCapacity - request.observed.size();
		long affordableEntries = Math.max(0, (settings.pendingMemoryBytes() - retainedBytes - FRAME_WORKSPACE) / ENTRY_MEMORY);
		if (request.entryCapacity < MAX_ENTRIES) grant = (int) Math.min(grant, freeEntries + affordableEntries);
		if (grant == 0) return;
		int capacity = Math.min(MAX_ENTRIES, request.observed.size() + grant);
		if (capacity > request.entryCapacity) {
			retainedBytes += (capacity - request.entryCapacity) * ENTRY_MEMORY;
			request.entryCapacity = capacity;
		}
		Round round = request.round;
		int cursor = request.scanner == null ? 0 : request.scanner.snapshot().scanned();
		int needed = Math.max(0, cursor + grant - round.broker.scanned());
		int physical = Math.max(0, limit(settings.effectivePhysicalSlotsPerTick()) - physicalSlots);
		long before = round.attempts;
		if (round.broker.total() < 0 || needed > 0) round.broker.physicalStep(Math.min(grant, Math.min(needed, physical)));
		physicalSlots += (int) (round.attempts - before);
		if (round.broker.state() == InventoryScanner.State.UNAVAILABLE) {
			invalidate(request, Status.UNAVAILABLE);
			return;
		}
		if (round.broker.total() < 0) return;
		if (request.scanner == null) request.scanner = new InventoryScanner(round.broker.capturedSource(), Set.of(), Set.of(), true, MAX_ENTRIES);
		int available = Math.min(grant, Math.max(0, round.broker.scanned() - cursor));
		if (available == 0 && !(round.broker.complete() && round.broker.total() == 0)) {
			if (round.broker.state() == InventoryScanner.State.INCOMPLETE) finish(request, Status.INCOMPLETE);
			return;
		}
		InventoryScanner.Result result = request.scanner.step(Math.max(1, available));
		int used = result.scanned() - cursor;
		session.slots += used;
		globalSlots += used;
		request.watermark++;
		for (Map.Entry<InventoryScanner.Key, Long> value : result.counts().entrySet()) {
			if (Objects.equals(request.observed.get(value.getKey()), value.getValue())) continue;
			Display display = round.resolved.display().apply(value.getKey());
			if (display == null || display.componentsStripped()) {
				// Exact-only preview cannot publish a false all-ID aggregate. Group folding is a separate operation.
				invalidate(request, Status.INCOMPLETE);
				return;
			}
			String token = request.tokens.computeIfAbsent(value.getKey(), key -> Integer.toString(request.tokens.size() + 1, 36));
			Entry entry;
			try {
				entry = new Entry(token, display.itemId(), display.label(), display.json(), value.getValue(), request.watermark,
					false, result.uncertain() ? Status.UNCERTAIN : Status.READY);
			} catch (IllegalArgumentException oversized) {
				invalidate(request, Status.INCOMPLETE);
				return;
			}
			Entry previous = request.pending.put(token, entry);
			queuedBytes += queueCost(entry) - (previous == null ? 0 : queueCost(previous));
			request.observed.put(value.getKey(), value.getValue());
		}
		if (result.state() == InventoryScanner.State.UNAVAILABLE) invalidate(request, Status.UNAVAILABLE);
		else if (result.complete() && round.broker.complete()) finish(request, result.uncertain() ? Status.UNCERTAIN : Status.READY);
		else if (result.state() == InventoryScanner.State.INCOMPLETE
			|| (round.broker.state() == InventoryScanner.State.INCOMPLETE && result.scanned() == round.broker.scanned())) finish(request, Status.INCOMPLETE);
	}

	private void flush(Session session, Request request) {
		while (!request.pending.isEmpty() && session.variants < limit(settings.getPreview().effectiveMaxVariantsPerClientPeriod())) {
			if (!room(FRAME_WORKSPACE)) return;
			Entry entry = request.pending.values().iterator().next();
			InventoryS2CPacket packet = preview(session, request, Status.UPDATING, false, List.of(entry));
			int bytes = host.encodedBytes(packet);
			if (bytes > dataCapacity(settings.previewClientPeriodBytes()) || bytes > dataCapacity(settings.previewGlobalPeriodBytes())) {
				invalidate(request, Status.INCOMPLETE);
				return;
			}
			if (!sendMeasured(session, packet, bytes, false)) return;
			session.variants++;
			request.pending.remove(entry.key());
			queuedBytes -= queueCost(entry);
		}
	}

	private void finish(Request request, Status status) {
		request.terminal = status;
		request.statusPending = true;
	}

	private void invalidate(Request request, Status status) {
		for (Entry entry : request.pending.values()) queuedBytes -= queueCost(entry);
		request.pending.clear();
		request.fence++;
		request.watermark++;
		releaseRound(request);
		finish(request, status);
	}

	private void sendStatus(Session session, Request request) {
		if (!request.statusPending || !request.pending.isEmpty()) return;
		Status status = request.terminal == null ? Status.UPDATING : request.terminal;
		boolean complete = status == Status.READY || status == Status.UNCERTAIN;
		if (send(session, preview(session, request, status, complete, List.of()), true)) request.statusPending = false;
	}

	private InventoryS2CPacket preview(Session session, Request request, Status status, boolean complete, List<Entry> entries) {
		return InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, session.epoch, request.id, null,
			1, request.fence, request.watermark, 0, 1, complete, status, 0, entries);
	}

	private void sendOffer(Session session) {
		InventoryS2CPacket.Offer offer = new InventoryS2CPacket.Offer(settings.getPreview().getPeriodTicks(),
			settings.getTracking().getPeriodTicks(), Math.min(InventoryLimits.MAX_PERIOD_TICKS, settings.getTracking().getResyncMinPeriods()),
			settings.getTracking().getHeartbeatPeriods());
		if (send(session, InventoryS2CPacket.offer(session.epoch, offer), true)) {
			session.offerPending = false;
			session.offered = true;
		}
	}

	private boolean send(Session session, InventoryS2CPacket packet, boolean control) {
		if (!room(FRAME_WORKSPACE)) return false;
		int bytes = host.encodedBytes(packet);
		return sendMeasured(session, packet, bytes, control);
	}

	private boolean sendMeasured(Session session, InventoryS2CPacket packet, int bytes, boolean control) {
		if (bytes < 0 || bytes > InventoryS2CPacket.MAX_FRAME_BYTES) throw new IllegalArgumentException("inventory frame size");
		long clientReserve = control ? 0 : Math.max(0, reserve(settings.previewClientPeriodBytes()) - session.controls);
		long serverReserve = control ? 0 : Math.max(0, reserve(settings.previewGlobalPeriodBytes()) - globalControls);
		if (bytes > settings.previewClientPeriodBytes() - session.bytes - clientReserve
			|| bytes > settings.previewGlobalPeriodBytes() - globalBytes - serverReserve) return false;
		session.bytes += bytes;
		globalBytes += bytes;
		if (control) { session.controls += bytes; globalControls += bytes; }
		// A throwing send may have handed off data; charge conservatively and retry the same immutable value.
		try { host.send(session.player, packet); return true; }
		catch (RuntimeException transportFailure) { return false; }
	}

	private boolean room(long bytes) { return bytes <= settings.pendingMemoryBytes() - retainedBytes; }
	private static int limit(int value) { return Math.min(value, InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD); }
	private static long reserve(long base) { return Math.min(base, 128 + base / 16); }
	private static long dataCapacity(long base) { return base - reserve(base); }
	private static long queueCost(Entry entry) {
		return 128L + utf8(entry.key()) + utf8(entry.itemId()) + utf8(entry.label()) + utf8(entry.displayJson());
	}
	private static int utf8(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }

	private void close(Session session, long id) {
		Request request = session.requests.remove(id);
		if (request == null) return;
		for (Entry entry : request.pending.values()) queuedBytes -= queueCost(entry);
		releaseRound(request);
		retainedBytes -= REQUEST_MEMORY + request.entryCapacity * ENTRY_MEMORY;
	}

	private void releaseRound(Request request) {
		if (request.scanner != null) { request.scanner.close(); request.scanner = null; }
		Round round = request.round;
		request.round = null;
		if (round != null && --round.references == 0) {
			round.broker.close();
			rounds.remove(round.resolved.key(), round);
		}
	}

	private static final class Session {
		final UUID player;
		final long epoch;
		final Map<Long, Request> requests = new LinkedHashMap<>();
		long lastHello = Long.MIN_VALUE;
		long bytes, controls;
		int slots, variants;
		boolean offered, offerPending;
		Session(UUID player, long epoch) { this.player = player; this.epoch = epoch; }
	}

	private static final class Request {
		final long id;
		final Target target;
		final Map<InventoryScanner.Key, Long> observed = new LinkedHashMap<>();
		final Map<InventoryScanner.Key, String> tokens = new LinkedHashMap<>();
		final Map<String, Entry> pending = new LinkedHashMap<>();
		Round round;
		InventoryScanner scanner;
		Status terminal;
		long fence = 1, watermark;
		long lastResync = Long.MIN_VALUE;
		boolean statusPending = true;
		int entryCapacity;
		Request(long id, Target target) { this.id = id; this.target = target; }
	}

	private static final class Round {
		final Resolved resolved;
		final InventoryScanBroker broker;
		long attempts;
		int references;
		Round(Resolved resolved) {
			this.resolved = resolved;
			InventoryScanner.Source raw = resolved.source();
			broker = new InventoryScanBroker(new InventoryScanner.Source() {
				@Override public int slots() { return raw.slots(); }
				@Override public boolean stableCursor() { return raw.stableCursor(); }
				@Override public boolean stableSnapshot() { return raw.stableSnapshot(); }
				@Override public InventoryScanner.Stack read(int slot) { attempts++; return raw.read(slot); }
			}, MAX_SOURCE_SLOTS);
		}
	}
}
