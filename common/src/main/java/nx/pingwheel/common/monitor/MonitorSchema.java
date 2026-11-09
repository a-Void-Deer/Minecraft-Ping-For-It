package nx.pingwheel.common.monitor;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Detached, closed monitor vocabulary. No value contains a world, clock reading,
 * provider, transport buffer or arbitrary label. Numeric IDs are explicit wire
 * identities; enum ordinals are not a persistence or wire format.
 */
@NotNullByDefault
public final class MonitorSchema {

	private static final int LOCAL_LANES = 0x03;
	private static final int SERVER_LANES = 0x0c;
	private static final int SHARED_SCOPE = 0x10;
	private static final int PREVIEW_SCOPES = 0x05;
	private static final int TRACKING_SCOPES = 0x0a;

	private MonitorSchema() {}

	/** Monitor workloads, not the independent ITEM/FLUID resource kinds. */
	@NotNullByDefault
	public enum Lane {
		PREVIEW(1, "preview", 1), TRACKING(2, "tracking", 2);

		private final int wireId;
		private final String id;
		private final int mask;

		Lane(int wireId, String id, int mask) { this.wireId = wireId; this.id = id; this.mask = mask; }
		public int wireId() { return wireId; }
		public String id() { return id; }
		public int mask() { return mask; }
		public static @Nullable Lane byWireId(int id) {
			return switch (id) { case 1 -> PREVIEW; case 2 -> TRACKING; default -> null; };
		}
	}

	@NotNullByDefault
	public enum Source {
		LOCAL(1, "local"), SERVER(2, "server"), SHARED(3, "shared");

		private final int wireId;
		private final String id;

		Source(int wireId, String id) { this.wireId = wireId; this.id = id; }
		public int wireId() { return wireId; }
		public String id() { return id; }
		public static @Nullable Source byWireId(int id) {
			return switch (id) { case 1 -> LOCAL; case 2 -> SERVER; case 3 -> SHARED; default -> null; };
		}
	}

	/** Shared has no lane; local and server always have exactly one. */
	@NotNullByDefault
	public record Scope(Source source, @Nullable Lane lane) {
		public static final Scope LOCAL_PREVIEW = new Scope(Source.LOCAL, Lane.PREVIEW);
		public static final Scope LOCAL_TRACKING = new Scope(Source.LOCAL, Lane.TRACKING);
		public static final Scope SERVER_PREVIEW = new Scope(Source.SERVER, Lane.PREVIEW);
		public static final Scope SERVER_TRACKING = new Scope(Source.SERVER, Lane.TRACKING);
		public static final Scope SHARED = new Scope(Source.SHARED, null);
		private static final @Unmodifiable List<Scope> ALL = List.of(
			LOCAL_PREVIEW, LOCAL_TRACKING, SERVER_PREVIEW, SERVER_TRACKING, SHARED);

		public Scope {
			Objects.requireNonNull(source, "source");
			if ((source == Source.SHARED) != (lane == null)) throw new IllegalArgumentException("scope lane");
		}

		public String id() { return source.id() + (lane == null ? "" : "." + lane.id()); }
		public int wireId() { return scopeId(source, lane); }
		public int mask() { return 1 << (wireId() - 1); }
		public static @Unmodifiable List<Scope> all() { return ALL; }
		public static @Nullable Scope byWireId(int id) {
			return switch (id) {
				case 1 -> LOCAL_PREVIEW; case 2 -> LOCAL_TRACKING; case 3 -> SERVER_PREVIEW;
				case 4 -> SERVER_TRACKING; case 5 -> SHARED; default -> null;
			};
		}
	}

	/** Inclusive durations. Ingress and tick work are separate top-level intervals. */
	@NotNullByDefault
	public enum Phase {
		INGRESS(1, "ingress", SERVER_LANES),
		TICK_WORK(2, "tick_work", SERVER_LANES),
		CAPTURE(3, "capture", SERVER_LANES),
		READ(4, "read", SERVER_LANES),
		DETACHED_ENCODE(5, "detached_encode", SERVER_LANES),
		MEASURE(6, "measure", SERVER_LANES),
		PUBLISH(7, "publish", SERVER_LANES),
		APPLY(8, "apply", LOCAL_LANES),
		PREPARE(9, "prepare", LOCAL_LANES),
		DRAW(10, "draw", LOCAL_LANES);

		private final int wireId;
		private final String id;
		private final int scopes;

		Phase(int wireId, String id, int scopes) { this.wireId = wireId; this.id = id; this.scopes = scopes; }
		public int wireId() { return wireId; }
		public String id() { return id; }
		public long mask() { return 1L << (wireId - 1); }
		public boolean supports(Scope scope) { return supports(scope.source(), scope.lane()); }
		public boolean supports(Source source, @Nullable Lane lane) { return supportsScope(scopes, source, lane); }
		public static @Nullable Phase byWireId(int id) {
			for (Phase phase : values()) if (phase.wireId == id) return phase;
			return null;
		}
	}

	/**
	 * Event totals over the window, never gauges or inferred backlog. Server sync
	 * frame/byte counters describe regular inventory-v2 traffic only, not all
	 * connection traffic or the separate CREATED creation receipt. Handoff bytes
	 * are observed transport-boundary bytes, never receipt or compressed traffic;
	 * unobserved candidate bytes stay separate and cannot be added as sent bytes.
	 */
	@NotNullByDefault
	public enum Counter {
		REQUESTS_RECEIVED(1, "requests.received", SERVER_LANES),
		REQUESTS_ADMITTED(2, "requests.admitted", SERVER_LANES),
		WORK_CONSUMERS(3, "work.consumers", SERVER_LANES),
		DEFER_ATTEMPTS(4, "work.defer_attempts", SERVER_LANES | SHARED_SCOPE),
		LOGICAL_SLOTS(5, "work.logical_slots", SERVER_LANES),
		PUBLICATIONS_INSTALLED(6, "sync.publications_installed", SERVER_LANES),
		CUTS_COMPLETED(7, "sync.cuts_completed", SERVER_LANES),
		FRAMES_PREVIEW(8, "sync.frames_preview", PREVIEW_SCOPES),
		FRAMES_SNAPSHOT(9, "sync.frames_snapshot", TRACKING_SCOPES),
		FRAMES_STREAM(10, "sync.frames_stream", TRACKING_SCOPES),
		BUDGET_CHARGED_BYTES(11, "sync.budget_charged_bytes", SERVER_LANES),
		HANDOFF_BYTES(12, "sync.handoff_bytes", SERVER_LANES),
		HANDOFF_FRAMES(13, "sync.handoff_frames", SERVER_LANES),
		NOT_HANDED_OFF_FRAMES(14, "sync.not_handed_off_frames", SERVER_LANES),
		UNOBSERVED_HANDOFF_FRAMES(15, "sync.unobserved_handoff_frames", SERVER_LANES),
		UNOBSERVED_HANDOFF_CANDIDATE_BYTES(16, "sync.unobserved_handoff_candidate_bytes", SERVER_LANES),
		ACCEPTED(17, "apply.accepted", LOCAL_LANES),
		REJECTED(18, "apply.rejected", LOCAL_LANES),
		REPAIR_REQUESTS(19, "sync.repair_requests", LOCAL_LANES),
		READ_ATTEMPTS(20, "work.read_attempts", SHARED_SCOPE),
		READ_FAILURES(21, "work.read_failures", SHARED_SCOPE);

		private final int wireId;
		private final String id;
		private final int scopes;

		Counter(int wireId, String id, int scopes) { this.wireId = wireId; this.id = id; this.scopes = scopes; }
		public int wireId() { return wireId; }
		public String id() { return id; }
		public long mask() { return 1L << (wireId - 1); }
		public boolean supports(Scope scope) { return supports(scope.source(), scope.lane()); }
		public boolean supports(Source source, @Nullable Lane lane) { return supportsScope(scopes, source, lane); }
		public static @Nullable Counter byWireId(int id) {
			for (Counter counter : values()) if (counter.wireId == id) return counter;
			return null;
		}
	}

	/** Latest observed scalars. A missing reading is unknown, not zero. */
	@NotNullByDefault
	public enum Gauge {
		REQUESTS_OPEN(1, "requests.open", SERVER_LANES),
		QUEUE_LOGICAL_COST(2, "queue.logical_cost", SERVER_LANES),
		QUEUE_CAP(3, "queue.cap", SERVER_LANES),
		PENDING_ENTRIES(4, "queue.pending_entries", SERVER_LANES),
		ADMITTED_FRAMES(5, "queue.admitted_frames", SERVER_LANES),
		CHANNELS(6, "state.channels", LOCAL_LANES),
		SESSIONS(7, "state.sessions", LOCAL_LANES | SHARED_SCOPE),
		BUFFERED_FRAMES(8, "buffer.frames", LOCAL_LANES),
		BUFFERED_ENTRIES(9, "buffer.entries", LOCAL_LANES),
		BUFFER_ESTIMATED_BYTES(10, "buffer.estimated_bytes", LOCAL_LANES),
		CONSUMERS(11, "state.consumers", SHARED_SCOPE),
		ACTIVE_OBSERVATIONS(12, "state.active_observations", SHARED_SCOPE),
		OUTSTANDING_LEASES(13, "memory.outstanding_leases", SHARED_SCOPE);

		private final int wireId;
		private final String id;
		private final int scopes;

		Gauge(int wireId, String id, int scopes) { this.wireId = wireId; this.id = id; this.scopes = scopes; }
		public int wireId() { return wireId; }
		public String id() { return id; }
		public long mask() { return 1L << (wireId - 1); }
		public boolean supports(Scope scope) { return supports(scope.source(), scope.lane()); }
		public boolean supports(Source source, @Nullable Lane lane) { return supportsScope(scopes, source, lane); }
		public static @Nullable Gauge byWireId(int id) {
			for (Gauge gauge : values()) if (gauge.wireId == id) return gauge;
			return null;
		}
	}

	/** Separate ledger units. No weighting or conversion between these budgets. */
	@NotNullByDefault
	public enum Budget {
		LOGICAL_SLOTS(1, "logical_slots", SERVER_LANES),
		WIRE_BYTES(2, "wire_bytes", SERVER_LANES),
		PHYSICAL_SLOTS(3, "physical_slots", SHARED_SCOPE),
		CAPTURE_READS(4, "capture_reads", SHARED_SCOPE),
		PROBES(5, "probes", SHARED_SCOPE),
		PROVIDER_WORK(6, "provider_work", SHARED_SCOPE),
		PROCESSING_CHARGE(7, "processing_charge", SHARED_SCOPE),
		PUBLICATION_WORK(8, "publication_work", SHARED_SCOPE);

		private final int wireId;
		private final String id;
		private final int scopes;

		Budget(int wireId, String id, int scopes) { this.wireId = wireId; this.id = id; this.scopes = scopes; }
		public int wireId() { return wireId; }
		public String id() { return id; }
		public long mask() { return 1L << (wireId - 1); }
		public boolean supports(Scope scope) { return supports(scope.source(), scope.lane()); }
		public boolean supports(Source source, @Nullable Lane lane) { return supportsScope(scopes, source, lane); }
		public static @Nullable Budget byWireId(int id) {
			for (Budget budget : values()) if (budget.wireId == id) return budget;
			return null;
		}
	}

	/** Saturated total/count are lower bounds; their mean is deliberately unavailable. */
	@NotNullByDefault
	public record TimingStats(long totalNs, long count, long maxNs) {
		public TimingStats {
			nonnegative(totalNs, "totalNs"); nonnegative(count, "count"); nonnegative(maxNs, "maxNs");
			if (maxNs > totalNs || (count == 0 && (totalNs != 0 || maxNs != 0))
				|| (totalNs > 0 && maxNs == 0)) throw new IllegalArgumentException("timing stats");
			if (count > 0 && totalNs / count + (totalNs % count == 0 ? 0 : 1) > maxNs)
				throw new IllegalArgumentException("timing total exceeds count times max");
		}
		public boolean saturated() { return totalNs == Long.MAX_VALUE || count == Long.MAX_VALUE; }
		public OptionalDouble meanNs() {
			return count == 0 || saturated() ? OptionalDouble.empty() : OptionalDouble.of((double) totalNs / count);
		}
	}

	/**
	 * One observed ledger period. Unavailable uses canonical zero fields, which
	 * must not be displayed as measured values. Remaining may be negative after a
	 * live cap reduction. Admissible remaining may be lower due to another guard;
	 * smoothing means it is not reconstructed from limit minus used.
	 */
	@NotNullByDefault
	public record BudgetUsage(boolean available, long periodId, long limit, long used,
		long reserved, long remaining, long admissibleRemaining) {
		public BudgetUsage {
			nonnegative(periodId, "periodId"); nonnegative(limit, "limit"); nonnegative(used, "used");
			nonnegative(reserved, "reserved"); nonnegative(admissibleRemaining, "admissibleRemaining");
			if (admissibleRemaining > Math.max(0L, remaining)) throw new IllegalArgumentException("admissible remaining");
			if (!available && (periodId != 0 || limit != 0 || used != 0 || reserved != 0
				|| remaining != 0 || admissibleRemaining != 0)) throw new IllegalArgumentException("unavailable budget");
		}
		public static BudgetUsage unavailable() { return new BudgetUsage(false, 0, 0, 0, 0, 0, 0); }
	}

	/** Unique accounted retained bytes, not heap, allocation rate or transport bytes. */
	@NotNullByDefault
	public record MemoryUsage(long retained, long reserved, long cap, long remaining) {
		public MemoryUsage {
			nonnegative(retained, "retained"); nonnegative(reserved, "reserved"); nonnegative(cap, "cap");
			if (remaining != memoryRemaining(retained, reserved, cap)) throw new IllegalArgumentException("memory remaining");
		}
		public static MemoryUsage accounted(long retained, long reserved, long cap) {
			return new MemoryUsage(retained, reserved, cap, memoryRemaining(retained, reserved, cap));
		}
		public long admissibleRemaining() { return Math.max(0, remaining); }
	}

	@NotNullByDefault
	public record Timing(Phase phase, TimingStats stats) {
		public Timing { Objects.requireNonNull(phase, "phase"); Objects.requireNonNull(stats, "stats"); }
	}

	/** Saturated counters are observed lower bounds, never exact rate inputs. */
	@NotNullByDefault
	public record CounterReading(Counter counter, long value, boolean saturated) {
		public CounterReading {
			Objects.requireNonNull(counter, "counter"); nonnegative(value, "value");
			if (saturated && value != Long.MAX_VALUE) throw new IllegalArgumentException("saturated counter");
		}
	}

	/** Buffer estimated bytes are a client-local estimate, not transport bytes or heap. */
	@NotNullByDefault
	public record GaugeReading(Gauge gauge, long value) {
		public GaugeReading { Objects.requireNonNull(gauge, "gauge"); nonnegative(value, "value"); }
	}

	@NotNullByDefault
	public record BudgetReading(Budget budget, BudgetUsage usage) {
		public BudgetReading { Objects.requireNonNull(budget, "budget"); Objects.requireNonNull(usage, "usage"); }
	}

	/**
	 * Only measured fields are present; omission is unavailable, not zero. Lists
	 * have closed enum-sized bounds, reject duplicates/illegal scopes, and are
	 * defensively copied in stable numeric-ID order. Timings are inclusive and
	 * must not be blindly summed. Gauges/budgets are latest observations, not sums.
	 */
	@NotNullByDefault
	public record LaneSample(Source source, Lane lane, @Unmodifiable List<Timing> timings,
		@Unmodifiable List<CounterReading> counters, @Unmodifiable List<GaugeReading> gauges,
		@Unmodifiable List<BudgetReading> budgets) {
		public LaneSample {
			Scope scope = new Scope(source, Objects.requireNonNull(lane, "lane"));
			timings = copyTimings(timings, scope);
			counters = copyCounters(counters, scope);
			gauges = copyGauges(gauges, scope);
			budgets = copyBudgets(budgets, scope);
		}
		public Scope scope() { return new Scope(source, lane); }
		public @Nullable TimingStats timing(Phase phase) {
			Objects.requireNonNull(phase, "phase");
			for (Timing timing : timings) if (timing.phase() == phase) return timing.stats();
			return null;
		}
		public @Nullable CounterReading counter(Counter counter) { return findCounter(counters, counter); }
		public @Nullable GaugeReading gauge(Gauge gauge) { return findGauge(gauges, gauge); }
		public @Nullable BudgetUsage budget(Budget budget) { return findBudget(budgets, budget); }
	}

	/** A single shared region; it cannot be attached to or copied into a lane. */
	@NotNullByDefault
	public record SharedSample(@Unmodifiable List<CounterReading> counters,
		@Unmodifiable List<GaugeReading> gauges, @Unmodifiable List<BudgetReading> budgets,
		@Nullable MemoryUsage memory) {
		public SharedSample {
			counters = copyCounters(counters, Scope.SHARED);
			gauges = copyGauges(gauges, Scope.SHARED);
			budgets = copyBudgets(budgets, Scope.SHARED);
		}
		public @Nullable CounterReading counter(Counter counter) { return findCounter(counters, counter); }
		public @Nullable GaugeReading gauge(Gauge gauge) { return findGauge(gauges, gauge); }
		public @Nullable BudgetUsage budget(Budget budget) { return findBudget(budgets, budget); }
	}

	/**
	 * One endpoint's monotonic elapsed durations, not an absolute nanoTime stamp.
	 * Server and local samples never merge. A lane may be absent because it is
	 * disabled or not requested; status is separate from numeric data. An absent
	 * field is unavailable. Zero elapsed is valid warm-up with unavailable rates.
	 * The two generations fence lifetime and collection.
	 */
	@NotNullByDefault
	public record MonitorSample(long serverGeneration, long collectionGeneration, long sampleSequence,
		long firstTick, long lastTick, long elapsedNs, @Nullable LaneSample preview,
		@Nullable LaneSample tracking, @Nullable SharedSample shared) {
		public MonitorSample {
			nonnegative(serverGeneration, "serverGeneration"); nonnegative(collectionGeneration, "collectionGeneration");
			nonnegative(sampleSequence, "sampleSequence"); nonnegative(firstTick, "firstTick"); nonnegative(elapsedNs, "elapsedNs");
			if (lastTick < firstTick || (preview == null && tracking == null && shared == null)) throw new IllegalArgumentException("sample interval/regions");
			if (preview != null && preview.lane() != Lane.PREVIEW) throw new IllegalArgumentException("preview lane");
			if (tracking != null && tracking.lane() != Lane.TRACKING) throw new IllegalArgumentException("tracking lane");
			if (preview != null && tracking != null && preview.source() != tracking.source()) throw new IllegalArgumentException("mixed endpoints");
			Source source = preview != null ? preview.source() : tracking != null ? tracking.source() : Source.SERVER;
			if (shared != null && source != Source.SERVER) throw new IllegalArgumentException("local shared sample");
		}
		public OptionalDouble ratePerSecond(CounterReading reading) {
			Objects.requireNonNull(reading, "reading");
			return elapsedNs == 0 || reading.saturated() ? OptionalDouble.empty()
				: OptionalDouble.of(reading.value() * 1_000_000_000.0 / elapsedNs);
		}
	}

	/**
	 * Closed selector directory. Summary entries expand existing raw selectors;
	 * they do not name additional measurement sites. A phase selector exposes its
	 * total/count/mean; its separate .max selector opts into the peak. Construction
	 * is private, so neither config nor the wire can invent metrics or labels.
	 */
	@NotNullByDefault
	public static final class Metric {
		@NotNullByDefault
		public enum Kind { SUMMARY, TIMING, TIMING_MAX, COUNTER, GAUGE, BUDGET, MEMORY }

		public static final Metric SUMMARY_PROCESSING;
		public static final Metric SUMMARY_REQUESTS_QUEUE;
		public static final Metric SUMMARY_BUDGET;
		public static final Metric SUMMARY_MEMORY;
		public static final Metric SUMMARY_SYNC_RATE;
		private static final @Unmodifiable List<Metric> ALL;
		private static final @Unmodifiable List<Metric> RAW;
		private static final @Unmodifiable List<Metric> DEFAULTS;
		private static final @Unmodifiable List<String> DEFAULT_IDS;
		private static final @Unmodifiable Map<String, Metric> BY_ID;
		private static final @Unmodifiable Map<Integer, Metric> BY_WIRE_ID;

		static {
			List<Metric> raw = new ArrayList<>();
			for (Scope scope : Scope.all()) {
				for (Phase phase : Phase.values()) if (phase.supports(scope)) {
					raw.add(new Metric(scope.id() + ".timing." + phase.id(), 1000 + scope.wireId() * 100 + phase.wireId() * 2,
						Kind.TIMING, scope, phase, null, null, null, List.of()));
					raw.add(new Metric(scope.id() + ".timing." + phase.id() + ".max", 1001 + scope.wireId() * 100 + phase.wireId() * 2,
						Kind.TIMING_MAX, scope, phase, null, null, null, List.of()));
				}
				for (Counter counter : Counter.values()) if (counter.supports(scope)) raw.add(new Metric(
					scope.id() + "." + counter.id(), 2000 + scope.wireId() * 100 + counter.wireId(),
					Kind.COUNTER, scope, null, counter, null, null, List.of()));
				for (Gauge gauge : Gauge.values()) if (gauge.supports(scope)) raw.add(new Metric(
					scope.id() + "." + gauge.id(), 3000 + scope.wireId() * 100 + gauge.wireId(),
					Kind.GAUGE, scope, null, null, gauge, null, List.of()));
				for (Budget budget : Budget.values()) if (budget.supports(scope)) raw.add(new Metric(
					scope.id() + ".budget." + budget.id(), 4000 + scope.wireId() * 100 + budget.wireId(),
					Kind.BUDGET, scope, null, null, null, budget, List.of()));
			}
			raw.add(new Metric("shared.memory", 5001, Kind.MEMORY, Scope.SHARED, null, null, null, null, List.of()));
			RAW = List.copyOf(raw);
			SUMMARY_PROCESSING = summary("summary.processing", 1, raw.stream().filter(metric -> metric.kind == Kind.TIMING
				&& (metric.phase == Phase.INGRESS || metric.phase == Phase.TICK_WORK || metric.phase == Phase.APPLY
				|| metric.phase == Phase.PREPARE || metric.phase == Phase.DRAW)).toList());
			SUMMARY_REQUESTS_QUEUE = summary("summary.requests_queue", 2, raw.stream().filter(metric ->
				metric.counter == Counter.REQUESTS_RECEIVED || metric.counter == Counter.REQUESTS_ADMITTED
				|| metric.counter == Counter.ACCEPTED || metric.counter == Counter.REJECTED
				|| metric.counter == Counter.REPAIR_REQUESTS || (metric.kind == Kind.GAUGE
				&& metric.gauge != Gauge.BUFFER_ESTIMATED_BYTES && metric.gauge != Gauge.OUTSTANDING_LEASES)).toList());
			SUMMARY_BUDGET = summary("summary.budget", 3, raw.stream().filter(metric -> metric.kind == Kind.BUDGET).toList());
			SUMMARY_MEMORY = summary("summary.memory", 4, raw.stream().filter(metric -> metric.kind == Kind.MEMORY
				|| metric.gauge == Gauge.BUFFER_ESTIMATED_BYTES).toList());
			SUMMARY_SYNC_RATE = summary("summary.sync_rate", 5, raw.stream().filter(metric -> metric.kind == Kind.COUNTER
				&& metric.counter != null && metric.counter.id().startsWith("sync.")
				&& metric.counter != Counter.REPAIR_REQUESTS).toList());
			DEFAULTS = List.of(SUMMARY_PROCESSING, SUMMARY_REQUESTS_QUEUE, SUMMARY_BUDGET, SUMMARY_MEMORY, SUMMARY_SYNC_RATE);
			DEFAULT_IDS = DEFAULTS.stream().map(Metric::id).toList();
			List<Metric> all = new ArrayList<>(DEFAULTS);
			all.addAll(raw);
			ALL = List.copyOf(all);
			Map<String, Metric> ids = new LinkedHashMap<>();
			Map<Integer, Metric> wireIds = new LinkedHashMap<>();
			for (Metric metric : all) {
				if (ids.put(metric.id, metric) != null || wireIds.put(metric.wireId, metric) != null)
					throw new IllegalStateException("duplicate monitor metric");
			}
			BY_ID = Map.copyOf(ids);
			BY_WIRE_ID = Map.copyOf(wireIds);
		}

		private final String id;
		private final int wireId;
		private final Kind kind;
		private final @Nullable Scope scope;
		private final @Nullable Phase phase;
		private final @Nullable Counter counter;
		private final @Nullable Gauge gauge;
		private final @Nullable Budget budget;
		private final @Unmodifiable List<Metric> components;

		private Metric(String id, int wireId, Kind kind, @Nullable Scope scope, @Nullable Phase phase,
			@Nullable Counter counter, @Nullable Gauge gauge, @Nullable Budget budget, List<Metric> components) {
			this.id = id; this.wireId = wireId; this.kind = kind; this.scope = scope; this.phase = phase;
			this.counter = counter; this.gauge = gauge; this.budget = budget; this.components = List.copyOf(components);
		}
		private static Metric summary(String id, int wireId, List<Metric> components) {
			return new Metric(id, wireId, Kind.SUMMARY, null, null, null, null, null, components);
		}
		public String id() { return id; }
		public int wireId() { return wireId; }
		public Kind kind() { return kind; }
		public @Nullable Scope scope() { return scope; }
		public @Nullable Phase phase() { return phase; }
		public @Nullable Counter counter() { return counter; }
		public @Nullable Gauge gauge() { return gauge; }
		public @Nullable Budget budget() { return budget; }
		public @Unmodifiable List<Metric> expanded() { return kind == Kind.SUMMARY ? components : List.of(this); }
		public static @Nullable Metric byId(String id) { return BY_ID.get(Objects.requireNonNull(id, "id")); }
		public static @Nullable Metric byWireId(int id) { return BY_WIRE_ID.get(id); }
		public static @Unmodifiable List<Metric> all() { return ALL; }
		public static @Unmodifiable List<Metric> displayMetrics() { return ALL; }
		public static @Unmodifiable List<Metric> rawMetrics() { return RAW; }
		public static @Unmodifiable List<Metric> defaults() { return DEFAULTS; }
		public static @Unmodifiable List<String> defaultIds() { return DEFAULT_IDS; }

		/** Display/demand union: selected summaries and raw details never duplicate a raw field. */
		public static @Unmodifiable List<Metric> expand(Collection<Metric> selected) {
			Objects.requireNonNull(selected, "selected");
			Set<Metric> expanded = new HashSet<>();
			for (Metric metric : selected) expanded.addAll(Objects.requireNonNull(metric, "metric").expanded());
			return RAW.stream().filter(expanded::contains).toList();
		}
		@Override public String toString() { return id; }
	}

	private static int scopeId(Source source, @Nullable Lane lane) {
		return switch (source) {
			case LOCAL -> Objects.requireNonNull(lane, "lane") == Lane.PREVIEW ? 1 : 2;
			case SERVER -> Objects.requireNonNull(lane, "lane") == Lane.PREVIEW ? 3 : 4;
			case SHARED -> { if (lane != null) throw new IllegalArgumentException("shared lane"); yield 5; }
		};
	}

	private static boolean supportsScope(int scopes, Source source, @Nullable Lane lane) {
		return (scopes & (1 << (scopeId(source, lane) - 1))) != 0;
	}

	private static @Unmodifiable List<Timing> copyTimings(List<Timing> readings, Scope scope) {
		if (readings.size() > Phase.values().length) throw new IllegalArgumentException("timing bound");
		long seen = 0;
		for (Timing reading : readings) {
			if (!reading.phase().supports(scope) || (seen & reading.phase().mask()) != 0) throw new IllegalArgumentException("timing scope/duplicate");
			seen |= reading.phase().mask();
		}
		return readings.stream().sorted(Comparator.comparingInt(reading -> reading.phase().wireId())).toList();
	}

	private static @Unmodifiable List<CounterReading> copyCounters(List<CounterReading> readings, Scope scope) {
		if (readings.size() > Counter.values().length) throw new IllegalArgumentException("counter bound");
		long seen = 0;
		for (CounterReading reading : readings) {
			if (!reading.counter().supports(scope) || (seen & reading.counter().mask()) != 0) throw new IllegalArgumentException("counter scope/duplicate");
			seen |= reading.counter().mask();
		}
		return readings.stream().sorted(Comparator.comparingInt(reading -> reading.counter().wireId())).toList();
	}

	private static @Unmodifiable List<GaugeReading> copyGauges(List<GaugeReading> readings, Scope scope) {
		if (readings.size() > Gauge.values().length) throw new IllegalArgumentException("gauge bound");
		long seen = 0;
		for (GaugeReading reading : readings) {
			if (!reading.gauge().supports(scope) || (seen & reading.gauge().mask()) != 0) throw new IllegalArgumentException("gauge scope/duplicate");
			seen |= reading.gauge().mask();
		}
		return readings.stream().sorted(Comparator.comparingInt(reading -> reading.gauge().wireId())).toList();
	}

	private static @Unmodifiable List<BudgetReading> copyBudgets(List<BudgetReading> readings, Scope scope) {
		if (readings.size() > Budget.values().length) throw new IllegalArgumentException("budget bound");
		long seen = 0;
		for (BudgetReading reading : readings) {
			if (!reading.budget().supports(scope) || (seen & reading.budget().mask()) != 0) throw new IllegalArgumentException("budget scope/duplicate");
			seen |= reading.budget().mask();
		}
		return readings.stream().sorted(Comparator.comparingInt(reading -> reading.budget().wireId())).toList();
	}

	private static @Nullable CounterReading findCounter(List<CounterReading> readings, Counter counter) {
		Objects.requireNonNull(counter, "counter");
		for (CounterReading reading : readings) if (reading.counter() == counter) return reading;
		return null;
	}

	private static @Nullable GaugeReading findGauge(List<GaugeReading> readings, Gauge gauge) {
		Objects.requireNonNull(gauge, "gauge");
		for (GaugeReading reading : readings) if (reading.gauge() == gauge) return reading;
		return null;
	}

	private static @Nullable BudgetUsage findBudget(List<BudgetReading> readings, Budget budget) {
		Objects.requireNonNull(budget, "budget");
		for (BudgetReading reading : readings) if (reading.budget() == budget) return reading.usage();
		return null;
	}

	private static long memoryRemaining(long retained, long reserved, long cap) {
		try { return Math.subtractExact(Math.subtractExact(cap, retained), reserved); }
		catch (ArithmeticException overflow) { throw new IllegalArgumentException("memory remaining overflows", overflow); }
	}

	private static void nonnegative(long value, String name) {
		if (value < 0) throw new IllegalArgumentException(name + " must be nonnegative");
	}
}
