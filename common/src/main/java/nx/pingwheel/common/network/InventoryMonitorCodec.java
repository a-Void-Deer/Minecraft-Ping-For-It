package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetReading;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.CounterReading;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.GaugeReading;
import nx.pingwheel.common.monitor.MonitorSchema.LaneSample;
import nx.pingwheel.common.monitor.MonitorSchema.MemoryUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorSchema.Timing;
import nx.pingwheel.common.monitor.MonitorSchema.TimingStats;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Closed v1 grammar. Every read is whole-body bounded before lists are allocated. */
@NotNullByDefault
public final class InventoryMonitorCodec {
	public static final int VERSION = 1;
	public static final int MAX_SUBSCRIBE_BYTES = 4096;
	public static final int MAX_STATUS_BYTES = 128;
	public static final int MAX_SAMPLE_BYTES = 16384;
	public static final int MAX_METRICS = (int) Metric.rawMetrics().stream()
		.filter(metric -> Objects.requireNonNull(metric.scope()).source() != Source.LOCAL).count();

	private InventoryMonitorCodec() {}

	static Metric remoteMetric(int id) {
		@Nullable Metric metric = Metric.byWireId(id);
		if (metric == null || metric.kind() == Metric.Kind.SUMMARY
			|| Objects.requireNonNull(metric.scope()).source() == Source.LOCAL)
			throw new IllegalArgumentException("unknown/nonremote raw monitor metric");
		return metric;
	}
	static @Unmodifiable List<Integer> copyMetricIds(List<Integer> ids) {
		if (ids.size() > MAX_METRICS) throw new IllegalArgumentException("monitor metric bound");
		int previous = 0;
		for (int id : ids) { remoteMetric(id); previous = ascending(id, previous); }
		return List.copyOf(ids);
	}

	public static InventoryMonitorSubscribeC2SPacket readSubscribe(FriendlyByteBuf buf) {
		begin(buf, MAX_SUBSCRIBE_BYTES);
		long revision = positive(buf);
		boolean watching = StrictPacketCodec.readBoolean(buf);
		int count = count(buf, MAX_METRICS);
		List<Integer> ids = new ArrayList<>(count);
		for (int i = 0; i < count; i++) ids.add(StrictPacketCodec.readVarInt(buf));
		InventoryMonitorSubscribeC2SPacket packet = new InventoryMonitorSubscribeC2SPacket(revision, watching, ids);
		end(buf);
		return packet;
	}
	public static void writeSubscribe(FriendlyByteBuf buf, InventoryMonitorSubscribeC2SPacket packet) {
		requireValid(packet);
		buf.writeVarInt(VERSION).writeVarLong(packet.demandRevision()).writeBoolean(packet.watching());
		buf.writeVarInt(packet.metricWireIds().size());
		for (int id : packet.metricWireIds()) buf.writeVarInt(id);
	}
	public static InventoryMonitorStatusS2CPacket readStatus(FriendlyByteBuf buf) {
		begin(buf, MAX_STATUS_BYTES);
		long revision = positive(buf), server = positive(buf), collection = positive(buf), permission = positive(buf);
		boolean authorized = StrictPacketCodec.readBoolean(buf);
		SampleCapability capability = Objects.requireNonNull(SampleCapability.byWireId(StrictPacketCodec.readVarInt(buf)), "capability");
		InventoryMonitorStatusS2CPacket packet = new InventoryMonitorStatusS2CPacket(revision, server, collection,
			permission, authorized, capability, StrictPacketCodec.readBoolean(buf), StrictPacketCodec.readBoolean(buf));
		end(buf);
		return packet;
	}
	public static void writeStatus(FriendlyByteBuf buf, InventoryMonitorStatusS2CPacket packet) {
		requireValid(packet);
		buf.writeVarInt(VERSION).writeVarLong(packet.demandRevision()).writeVarLong(packet.serverGeneration())
			.writeVarLong(packet.collectionGeneration()).writeVarLong(packet.permissionGeneration())
			.writeBoolean(packet.authorized()).writeVarInt(Objects.requireNonNull(packet.sampleCapability()).wireId())
			.writeBoolean(packet.previewEnabled()).writeBoolean(packet.trackingEnabled());
	}

	/** version, revision, server, collection, permission, sequence, firstTick, lastTick, elapsedNs, regions. */
	public static InventoryMonitorSampleS2CPacket readSample(FriendlyByteBuf buf) {
		begin(buf, MAX_SAMPLE_BYTES);
		long revision = positive(buf), server = positive(buf), collection = positive(buf), permission = positive(buf);
		long sequence = positive(buf), first = StrictPacketCodec.readVarLong(buf), last = StrictPacketCodec.readVarLong(buf);
		long elapsed = StrictPacketCodec.readVarLong(buf);
		int regions = count(buf, 3);
		@Nullable LaneSample preview = null, tracking = null;
		@Nullable SharedSample shared = null;
		int previous = 0;
		for (int i = 0; i < regions; i++) {
			int id = StrictPacketCodec.readVarInt(buf);
			previous = ascending(id, previous);
			Scope scope = Objects.requireNonNull(Scope.byWireId(id), "scope");
			if (scope.source() == Source.LOCAL) throw new IllegalArgumentException("remote local scope");
			List<Timing> timings = readTimings(buf, scope);
			List<CounterReading> counters = readCounters(buf, scope);
			List<GaugeReading> gauges = readGauges(buf, scope);
			List<BudgetReading> budgets = readBudgets(buf, scope);
			boolean memoryPresent = StrictPacketCodec.readBoolean(buf);
			if (memoryPresent && scope.source() != Source.SHARED) throw new IllegalArgumentException("lane memory");
			@Nullable MemoryUsage memory = memoryPresent ? new MemoryUsage(StrictPacketCodec.readVarLong(buf),
				StrictPacketCodec.readVarLong(buf), StrictPacketCodec.readVarLong(buf), buf.readLong()) : null;
			if (scope == Scope.SHARED) shared = new SharedSample(counters, gauges, budgets, memory);
			else {
				LaneSample lane = new LaneSample(Source.SERVER, Objects.requireNonNull(scope.lane()), timings, counters, gauges, budgets);
				if (scope == Scope.SERVER_PREVIEW) preview = lane; else tracking = lane;
			}
		}
		MonitorSample sample = new MonitorSample(server, collection, sequence, first, last, elapsed, preview, tracking, shared);
		end(buf);
		return new InventoryMonitorSampleS2CPacket(revision, permission, sample);
	}
	public static void writeSample(FriendlyByteBuf buf, InventoryMonitorSampleS2CPacket packet) {
		requireValid(packet);
		MonitorSample sample = Objects.requireNonNull(packet.sample());
		int start = buf.writerIndex();
		buf.writeVarInt(VERSION).writeVarLong(packet.demandRevision()).writeVarLong(sample.serverGeneration())
			.writeVarLong(sample.collectionGeneration()).writeVarLong(packet.permissionGeneration())
			.writeVarLong(sample.sampleSequence()).writeVarLong(sample.firstTick()).writeVarLong(sample.lastTick())
			.writeVarLong(sample.elapsedNs());
		buf.writeVarInt((sample.preview() == null ? 0 : 1) + (sample.tracking() == null ? 0 : 1) + (sample.shared() == null ? 0 : 1));
		if (sample.preview() != null) writeLane(buf, sample.preview());
		if (sample.tracking() != null) writeLane(buf, sample.tracking());
		if (sample.shared() != null) {
			SharedSample shared = sample.shared();
			buf.writeVarInt(Scope.SHARED.wireId()).writeVarInt(0);
			writeReadings(buf, shared.counters(), shared.gauges(), shared.budgets(), shared.memory());
		}
		if (buf.writerIndex() - start > MAX_SAMPLE_BYTES) throw new IllegalArgumentException("monitor sample body bound");
	}
	private static void writeLane(FriendlyByteBuf buf, LaneSample lane) {
		buf.writeVarInt(lane.scope().wireId()).writeVarInt(lane.timings().size());
		for (Timing timing : lane.timings()) buf.writeVarInt(timing.phase().wireId())
			.writeVarLong(timing.stats().totalNs()).writeVarLong(timing.stats().count()).writeVarLong(timing.stats().maxNs());
		writeReadings(buf, lane.counters(), lane.gauges(), lane.budgets(), null);
	}
	private static void writeReadings(FriendlyByteBuf buf, List<CounterReading> counters, List<GaugeReading> gauges,
		List<BudgetReading> budgets, @Nullable MemoryUsage memory) {
		buf.writeVarInt(counters.size());
		for (CounterReading reading : counters) buf.writeVarInt(reading.counter().wireId()).writeVarLong(reading.value()).writeBoolean(reading.saturated());
		buf.writeVarInt(gauges.size());
		for (GaugeReading reading : gauges) buf.writeVarInt(reading.gauge().wireId()).writeVarLong(reading.value());
		buf.writeVarInt(budgets.size());
		for (BudgetReading reading : budgets) {
			BudgetUsage usage = reading.usage();
			buf.writeVarInt(reading.budget().wireId()).writeBoolean(usage.available()).writeVarLong(usage.periodId())
				.writeVarLong(usage.limit()).writeVarLong(usage.used()).writeVarLong(usage.reserved()).writeLong(usage.remaining())
				.writeVarLong(usage.admissibleRemaining());
		}
		buf.writeBoolean(memory != null);
		if (memory != null) buf.writeVarLong(memory.retained()).writeVarLong(memory.reserved()).writeVarLong(memory.cap()).writeLong(memory.remaining());
	}
	private static List<Timing> readTimings(FriendlyByteBuf buf, Scope scope) {
		int count = count(buf, Phase.values().length), previous = 0;
		List<Timing> readings = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int id = StrictPacketCodec.readVarInt(buf); previous = ascending(id, previous);
			Phase phase = Objects.requireNonNull(Phase.byWireId(id), "phase");
			if (!phase.supports(scope)) throw new IllegalArgumentException("timing scope");
			readings.add(new Timing(phase, new TimingStats(StrictPacketCodec.readVarLong(buf), StrictPacketCodec.readVarLong(buf), StrictPacketCodec.readVarLong(buf))));
		}
		return readings;
	}
	private static List<CounterReading> readCounters(FriendlyByteBuf buf, Scope scope) {
		int count = count(buf, Counter.values().length), previous = 0;
		List<CounterReading> readings = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int id = StrictPacketCodec.readVarInt(buf); previous = ascending(id, previous);
			Counter counter = Objects.requireNonNull(Counter.byWireId(id), "counter");
			if (!counter.supports(scope)) throw new IllegalArgumentException("counter scope");
			readings.add(new CounterReading(counter, StrictPacketCodec.readVarLong(buf), StrictPacketCodec.readBoolean(buf)));
		}
		return readings;
	}
	private static List<GaugeReading> readGauges(FriendlyByteBuf buf, Scope scope) {
		int count = count(buf, Gauge.values().length), previous = 0;
		List<GaugeReading> readings = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int id = StrictPacketCodec.readVarInt(buf); previous = ascending(id, previous);
			Gauge gauge = Objects.requireNonNull(Gauge.byWireId(id), "gauge");
			if (!gauge.supports(scope)) throw new IllegalArgumentException("gauge scope");
			readings.add(new GaugeReading(gauge, StrictPacketCodec.readVarLong(buf)));
		}
		return readings;
	}
	private static List<BudgetReading> readBudgets(FriendlyByteBuf buf, Scope scope) {
		int count = count(buf, Budget.values().length), previous = 0;
		List<BudgetReading> readings = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int id = StrictPacketCodec.readVarInt(buf); previous = ascending(id, previous);
			Budget budget = Objects.requireNonNull(Budget.byWireId(id), "budget");
			if (!budget.supports(scope)) throw new IllegalArgumentException("budget scope");
			readings.add(new BudgetReading(budget, new BudgetUsage(StrictPacketCodec.readBoolean(buf), StrictPacketCodec.readVarLong(buf),
				StrictPacketCodec.readVarLong(buf), StrictPacketCodec.readVarLong(buf), StrictPacketCodec.readVarLong(buf),
				buf.readLong(), StrictPacketCodec.readVarLong(buf))));
		}
		return readings;
	}
	private static void begin(FriendlyByteBuf buf, int maximum) {
		if (buf.readableBytes() > maximum) throw new IllegalArgumentException("monitor whole body bound");
		if (StrictPacketCodec.readVarInt(buf) != VERSION) throw new IllegalArgumentException("monitor version");
	}
	private static int count(FriendlyByteBuf buf, int maximum) {
		int count = StrictPacketCodec.readVarInt(buf);
		if (count > maximum) throw new IllegalArgumentException("monitor count bound");
		return count;
	}
	private static long positive(FriendlyByteBuf buf) {
		long value = StrictPacketCodec.readVarLong(buf);
		if (value == 0) throw new IllegalArgumentException("monitor positive fence");
		return value;
	}
	private static int ascending(int id, int previous) {
		if (id <= previous) throw new IllegalArgumentException("duplicate/out-of-order monitor id");
		return id;
	}
	private static void end(FriendlyByteBuf buf) {
		if (buf.isReadable()) throw new IllegalArgumentException("trailing monitor bytes");
	}
	private static void requireValid(IPacket packet) {
		if (packet.isCorrupt()) throw new IllegalArgumentException("corrupt monitor packet");
	}
	/** Malformed monitor input is drained and dropped without adding a logging surface. */
	static <T> T readSafe(FriendlyByteBuf buf, Function<FriendlyByteBuf, T> reader, Supplier<T> fallback) {
		try { return reader.apply(buf); }
		catch (RuntimeException invalid) { return fallback.get(); }
		finally { buf.skipBytes(buf.readableBytes()); }
	}
}
