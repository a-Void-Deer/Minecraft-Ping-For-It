package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.monitor.MonitorDemand;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/** Latest transmission demand, not a collection/configuration command. */
@NotNullByDefault
public record InventoryMonitorSubscribeC2SPacket(long demandRevision, boolean watching,
	@Unmodifiable List<Integer> metricWireIds) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		C2S_NAMESPACE, "inventory-monitor-subscribe-v1");
	public static final Type<InventoryMonitorSubscribeC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public InventoryMonitorSubscribeC2SPacket {
		metricWireIds = InventoryMonitorCodec.copyMetricIds(metricWireIds);
		if (!watching && !metricWireIds.isEmpty()) throw new IllegalArgumentException("stopped monitor demand");
	}

	/** Corrupt fallback only; zero is never an accepted demand revision. */
	public InventoryMonitorSubscribeC2SPacket() { this(0, false, List.of()); }
	public InventoryMonitorSubscribeC2SPacket(FriendlyByteBuf buf) { this(InventoryMonitorCodec.readSubscribe(buf)); }
	private InventoryMonitorSubscribeC2SPacket(InventoryMonitorSubscribeC2SPacket packet) {
		this(packet.demandRevision, packet.watching, packet.metricWireIds);
	}

	/** Expands local display selectors into only known remote raw transmission selectors. */
	public static InventoryMonitorSubscribeC2SPacket watch(long revision, Collection<Metric> selectors) {
		return new InventoryMonitorSubscribeC2SPacket(revision, true, Metric.expand(selectors).stream()
			.filter(metric -> java.util.Objects.requireNonNull(metric.scope()).source() != Source.LOCAL)
			.sorted(Comparator.comparingInt(Metric::wireId)).map(Metric::wireId).toList());
	}
	public static InventoryMonitorSubscribeC2SPacket stop(long revision) {
		return new InventoryMonitorSubscribeC2SPacket(revision, false, List.of());
	}
	public MonitorDemand demand() {
		return MonitorDemand.fromMetrics(metricWireIds.stream().map(InventoryMonitorCodec::remoteMetric).toList());
	}
	@Override public void write(FriendlyByteBuf buf) { InventoryMonitorCodec.writeSubscribe(buf, this); }
	@Override public boolean isCorrupt() { return demandRevision <= 0; }
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public Type<InventoryMonitorSubscribeC2SPacket> type() { return PACKET_TYPE; }
	public static InventoryMonitorSubscribeC2SPacket readSafe(FriendlyByteBuf buf) {
		return InventoryMonitorCodec.readSafe(buf, InventoryMonitorCodec::readSubscribe, InventoryMonitorSubscribeC2SPacket::new);
	}
}
