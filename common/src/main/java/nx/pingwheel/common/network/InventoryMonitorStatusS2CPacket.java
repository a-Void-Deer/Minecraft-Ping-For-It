package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/** Public control state with no numeric telemetry; never inferred from a missing sample. */
@NotNullByDefault
public record InventoryMonitorStatusS2CPacket(long demandRevision, long serverGeneration,
	long collectionGeneration, long permissionGeneration, boolean authorized,
	@Nullable SampleCapability sampleCapability, boolean previewEnabled, boolean trackingEnabled) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		S2C_NAMESPACE, "inventory-monitor-status-v1");
	public static final Type<InventoryMonitorStatusS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	@NotNullByDefault
	public enum SampleCapability {
		UNKNOWN(0), AVAILABLE(1), UNAVAILABLE(2);
		private final int wireId;
		SampleCapability(int wireId) { this.wireId = wireId; }
		public int wireId() { return wireId; }
		public static @Nullable SampleCapability byWireId(int id) {
			return switch (id) { case 0 -> UNKNOWN; case 1 -> AVAILABLE; case 2 -> UNAVAILABLE; default -> null; };
		}
	}

	public InventoryMonitorStatusS2CPacket() { this(0, 0, 0, 0, false, null, false, false); }
	public InventoryMonitorStatusS2CPacket(FriendlyByteBuf buf) { this(InventoryMonitorCodec.readStatus(buf)); }
	private InventoryMonitorStatusS2CPacket(InventoryMonitorStatusS2CPacket packet) {
		this(packet.demandRevision, packet.serverGeneration, packet.collectionGeneration, packet.permissionGeneration,
			packet.authorized, packet.sampleCapability, packet.previewEnabled, packet.trackingEnabled);
	}
	@Override public void write(FriendlyByteBuf buf) { InventoryMonitorCodec.writeStatus(buf, this); }
	@Override public boolean isCorrupt() {
		return demandRevision <= 0 || serverGeneration <= 0 || collectionGeneration <= 0 || permissionGeneration <= 0
			|| sampleCapability == null;
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public Type<InventoryMonitorStatusS2CPacket> type() { return PACKET_TYPE; }
	public static InventoryMonitorStatusS2CPacket readSafe(FriendlyByteBuf buf) {
		return InventoryMonitorCodec.readSafe(buf, InventoryMonitorCodec::readStatus, InventoryMonitorStatusS2CPacket::new);
	}
}
