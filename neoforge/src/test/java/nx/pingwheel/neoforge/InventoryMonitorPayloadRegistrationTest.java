package nx.pingwheel.neoforge;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import nx.pingwheel.common.monitor.MonitorAccumulator;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorPayloadRegistrationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualPayloadRegistryKeepsThreeTypedStrictRoutesInTheirOwnDirections() {
		PresentationPayloadRegistrationTest.registeredCodec();
		if (NetworkRegistry.getCodec(InventoryMonitorSubscribeC2SPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND) == null) {
			NeoMain.registerMonitorPackets(new PayloadRegistrar("pingforit").optional(),
				(server, player, packet) -> fail("codec probe must not dispatch"),
				(token, packet) -> fail("codec probe must not dispatch"), (token, packet) -> fail("codec probe must not dispatch"));
		}
		assertNull(NetworkRegistry.getCodec(InventoryMonitorSubscribeC2SPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND));
		assertNull(NetworkRegistry.getCodec(InventoryMonitorStatusS2CPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND));
		assertNull(NetworkRegistry.getCodec(InventoryMonitorSampleS2CPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND));
		strict(codec(InventoryMonitorSubscribeC2SPacket.PACKET_ID, PacketFlow.SERVERBOUND),
			InventoryMonitorSubscribeC2SPacket.watch(1,
				List.of(Objects.requireNonNull(Metric.byId("server.preview.requests.received")))));
		strict(codec(InventoryMonitorStatusS2CPacket.PACKET_ID, PacketFlow.CLIENTBOUND),
			new InventoryMonitorStatusS2CPacket(1, 1, 1, 1, true, SampleCapability.AVAILABLE, true, false));
		var collector = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), () -> 0);
		collector.resetGeneration(1); collector.configure(true, false);
		collector.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 1);
		strict(codec(InventoryMonitorSampleS2CPacket.PACKET_ID, PacketFlow.CLIENTBOUND),
			new InventoryMonitorSampleS2CPacket(1, 1, Objects.requireNonNull(collector.completeTick(0))));
	}

	@SuppressWarnings("unchecked")
	private static StreamCodec<FriendlyByteBuf, CustomPacketPayload> codec(ResourceLocation id, PacketFlow direction) {
		return (StreamCodec<FriendlyByteBuf, CustomPacketPayload>) Objects.requireNonNull(
			NetworkRegistry.getCodec(id, ConnectionProtocol.PLAY, direction));
	}

	private static void strict(StreamCodec<FriendlyByteBuf, CustomPacketPayload> codec, IPacket packet) {
		var body = new FriendlyByteBuf(Unpooled.buffer()); var expected = new FriendlyByteBuf(Unpooled.buffer());
		try {
			codec.encode(body, packet); packet.write(expected); assertEquals(expected, body);
			assertEquals(packet, codec.decode(body)); assertFalse(body.isReadable());
			body.clear(); codec.encode(body, packet); body.writeByte(0);
			assertTrue(((IPacket) codec.decode(body)).isCorrupt()); assertFalse(body.isReadable());
			body.clear(); codec.encode(body, packet); body.writerIndex(body.writerIndex() - 1);
			assertTrue(((IPacket) codec.decode(body)).isCorrupt()); assertFalse(body.isReadable());
		} finally { body.release(); expected.release(); }
	}
}
