package nx.pingwheel.fabric;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
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
import net.minecraft.server.Bootstrap;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorPayloadRegistrationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualPayloadRegistryKeepsThreeTypedStrictRoutesInTheirOwnDirections() {
		PresentationPayloadRegistrationTest.registeredCodec();
		var subscribe = PayloadTypeRegistryImpl.PLAY_C2S.get(InventoryMonitorSubscribeC2SPacket.PACKET_TYPE);
		var status = PayloadTypeRegistryImpl.PLAY_S2C.get(InventoryMonitorStatusS2CPacket.PACKET_TYPE);
		var sample = PayloadTypeRegistryImpl.PLAY_S2C.get(InventoryMonitorSampleS2CPacket.PACKET_TYPE);
		assertNotNull(subscribe); assertNotNull(status); assertNotNull(sample);
		assertNull(PayloadTypeRegistryImpl.PLAY_S2C.get(InventoryMonitorSubscribeC2SPacket.PACKET_ID));
		assertNull(PayloadTypeRegistryImpl.PLAY_C2S.get(InventoryMonitorStatusS2CPacket.PACKET_ID));
		assertNull(PayloadTypeRegistryImpl.PLAY_C2S.get(InventoryMonitorSampleS2CPacket.PACKET_ID));
		strict(subscribe.codec(), InventoryMonitorSubscribeC2SPacket.watch(1,
			List.of(Objects.requireNonNull(Metric.byId("server.preview.requests.received")))));
		strict(status.codec(), new InventoryMonitorStatusS2CPacket(1, 1, 1, 1, true,
			SampleCapability.AVAILABLE, true, false));
		var collector = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), () -> 0);
		collector.resetGeneration(1); collector.configure(true, false);
		collector.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 1);
		strict(sample.codec(), new InventoryMonitorSampleS2CPacket(1, 1, Objects.requireNonNull(collector.completeTick(0))));
	}

	private static <T extends IPacket> void strict(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T packet) {
		var body = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		var expected = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			codec.encode(body, packet); packet.write(expected); assertEquals(expected, body);
			assertEquals(packet, codec.decode(body)); assertFalse(body.isReadable());
			body.clear(); codec.encode(body, packet); body.writeByte(0);
			assertTrue(codec.decode(body).isCorrupt()); assertFalse(body.isReadable());
			body.clear(); codec.encode(body, packet); body.writerIndex(body.writerIndex() - 1);
			assertTrue(codec.decode(body).isCorrupt()); assertFalse(body.isReadable());
		} finally { body.release(); expected.release(); }
	}
}
