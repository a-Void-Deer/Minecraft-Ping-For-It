package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/** Projected administrator telemetry. This message cannot establish authorization. */
@NotNullByDefault
public record InventoryMonitorSampleS2CPacket(long demandRevision, long permissionGeneration,
	@Nullable MonitorSample sample) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		S2C_NAMESPACE, "inventory-monitor-sample-v1");
	public static final Type<InventoryMonitorSampleS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public InventoryMonitorSampleS2CPacket() { this(0, 0, null); }
	public InventoryMonitorSampleS2CPacket(FriendlyByteBuf buf) { this(InventoryMonitorCodec.readSample(buf)); }
	private InventoryMonitorSampleS2CPacket(InventoryMonitorSampleS2CPacket packet) {
		this(packet.demandRevision, packet.permissionGeneration, packet.sample);
	}
	@Override public void write(FriendlyByteBuf buf) { InventoryMonitorCodec.writeSample(buf, this); }
	@Override public boolean isCorrupt() {
		return demandRevision <= 0 || permissionGeneration <= 0 || sample == null
			|| sample.serverGeneration() <= 0 || sample.collectionGeneration() <= 0 || sample.sampleSequence() <= 0
			|| (sample.preview() != null && sample.preview().source() != Source.SERVER)
			|| (sample.tracking() != null && sample.tracking().source() != Source.SERVER);
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public Type<InventoryMonitorSampleS2CPacket> type() { return PACKET_TYPE; }
	public static InventoryMonitorSampleS2CPacket readSafe(FriendlyByteBuf buf) {
		return InventoryMonitorCodec.readSafe(buf, InventoryMonitorCodec::readSample, InventoryMonitorSampleS2CPacket::new);
	}
}
