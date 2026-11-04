package nx.pingwheel.fabric;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import nx.pingwheel.common.network.EncodedPresentationPayload;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
public final class PresentationPayloadRegistrationTest {

	public static StreamCodec<FriendlyByteBuf, CustomPacketPayload> registeredCodec() {
		if (PayloadTypeRegistryImpl.PLAY_S2C.get(PresentationS2CPacket.PACKET_ID) == null) FabricMain.registerPayloadTypes();
		var registered = PayloadTypeRegistryImpl.PLAY_S2C.get(EncodedPresentationPayload.PACKET_TYPE);
		assertNotNull(registered);
		assertNull(PayloadTypeRegistryImpl.PLAY_C2S.get(PresentationS2CPacket.PACKET_ID));
		// Registration accepts FriendlyByteBuf; Fabric stores it under its registry-aware play supertype.
		@SuppressWarnings("unchecked")
		StreamCodec<FriendlyByteBuf, CustomPacketPayload> codec =
			(StreamCodec<FriendlyByteBuf, CustomPacketPayload>) (StreamCodec<?, ?>) registered.codec();
		return codec;
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
		var c2s = PayloadTypeRegistryImpl.PLAY_C2S.get(PresentationC2SPacket.PACKET_TYPE);
		var other = PayloadTypeRegistryImpl.PLAY_S2C.get(RateLimitPolicyS2CPacket.PACKET_TYPE);
		assertNotNull(c2s); assertNotNull(other);
		var body = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
		try {
			var hello = PresentationC2SPacket.hello(java.util.Map.of("minecraft:basic", 1));
			c2s.codec().encode(body, hello); assertEquals(hello, c2s.codec().decode(body));
			body.clear(); c2s.codec().encode(body, hello); body.writeByte(0);
			assertTrue(c2s.codec().decode(body).isCorrupt()); assertFalse(body.isReadable());
			body.clear(); var policy = new RateLimitPolicyS2CPacket(3, 1000);
			other.codec().encode(body, policy); assertEquals(policy, other.codec().decode(body));
		} finally { body.release(); }
	}
}
