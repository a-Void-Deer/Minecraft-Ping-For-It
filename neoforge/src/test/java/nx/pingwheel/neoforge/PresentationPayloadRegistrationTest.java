package nx.pingwheel.neoforge;

import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
public final class PresentationPayloadRegistrationTest {

	public static StreamCodec<FriendlyByteBuf, CustomPacketPayload> registeredCodec() {
		if (NetworkRegistry.getCodec(PresentationS2CPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND) == null)
			registerProductionPayloads();
		var codec = NetworkRegistry.getCodec(PresentationS2CPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND);
		assertNotNull(codec);
		assertNull(NetworkRegistry.getCodec(PresentationS2CPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND));
		@SuppressWarnings("unchecked")
		StreamCodec<FriendlyByteBuf, CustomPacketPayload> result = (StreamCodec<FriendlyByteBuf, CustomPacketPayload>) codec;
		return result;
	}

	private static void registerProductionPayloads() {
		try {
			// IModBusEvent is runtime-only on the ordinary test classpath. Cross that
			// boundary reflectively, but execute the real event, registrar and production registration.
			Class<?> eventType = Class.forName("net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent");
			Object event = eventType.getConstructor().newInstance();
			PayloadRegistrar registrar = (PayloadRegistrar) eventType.getMethod("registrar", String.class).invoke(event, "pingforit");
			NeoMain.registerPackets(registrar.optional());
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("real NeoForge payload registration failed", failure);
		}
	}

	@Test void actualRegistryReaderReturnsOrdinaryRecordAndNormalRecordUsesSameWire() {
		var codec = registeredCodec();
		FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
		FriendlyByteBuf reference = new FriendlyByteBuf(Unpooled.buffer());
		try {
			PresentationS2CPacket original = PresentationS2CPacket.offer(7, java.util.Map.of(), java.util.Map.of());
			codec.encode(body, original); original.write(reference); assertEquals(reference, body);
			assertInstanceOf(PresentationS2CPacket.class, codec.decode(body)); assertFalse(body.isReadable());
		} finally { body.release(); reference.release(); }
	}

	@Test void actualRegisteredC2SStaysTypedStrictAndOtherS2CRemainsUntouched() {
		registeredCodec();
		@SuppressWarnings("unchecked")
		var c2s = (StreamCodec<FriendlyByteBuf, CustomPacketPayload>) NetworkRegistry.getCodec(
			PresentationC2SPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND);
		@SuppressWarnings("unchecked")
		var other = (StreamCodec<FriendlyByteBuf, CustomPacketPayload>) NetworkRegistry.getCodec(
			RateLimitPolicyS2CPacket.PACKET_ID, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND);
		assertNotNull(c2s); assertNotNull(other); FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
		try {
			var hello = PresentationC2SPacket.hello(java.util.Map.of("minecraft:basic", 1));
			c2s.encode(body, hello); assertEquals(hello, c2s.decode(body));
			body.clear(); c2s.encode(body, hello); body.writeByte(0);
			assertTrue(((PresentationC2SPacket) c2s.decode(body)).isCorrupt()); assertFalse(body.isReadable());
			body.clear(); var policy = new RateLimitPolicyS2CPacket(3, 1000);
			other.encode(body, policy); assertEquals(policy, other.decode(body));
		} finally { body.release(); }
	}
}
