package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/** Internal outbound body ownership; the registered reader still produces the ordinary response record. */
@ApiStatus.Internal
@NotNullByDefault
public final class EncodedPresentationPayload implements CustomPacketPayload {

	public static final Type<CustomPacketPayload> PACKET_TYPE = new Type<>(PresentationS2CPacket.PACKET_ID);
	public static final StreamCodec<FriendlyByteBuf, CustomPacketPayload> STREAM_CODEC = StreamCodec.of(
		(buffer, payload) -> {
			if (payload instanceof EncodedPresentationPayload encoded) encoded.writeTo(buffer);
			else if (payload instanceof PresentationS2CPacket packet) packet.write(buffer);
			else throw new IllegalArgumentException("unexpected presentation payload");
		}, PresentationS2CPacket::readSafe);

	private final byte @Unmodifiable [] body;

	private EncodedPresentationPayload(FriendlyByteBuf encoded) {
		int bytes = encoded.readableBytes();
		if (bytes < 1 || bytes > PresentationS2CPacket.MAX_S2C_BODY_BYTES)
			throw new IllegalArgumentException("presentation body bytes");
		body = new byte[bytes];
		encoded.getBytes(encoded.readerIndex(), body);
	}

	/** Copies exactly the readable body without consuming, retaining or asynchronously borrowing the source. */
	public static EncodedPresentationPayload copyCreated(PresentationS2CPacket packet, FriendlyByteBuf encoded) {
		if (packet.kind() != PresentationS2CPacket.Kind.CREATED)
			throw new IllegalArgumentException("expected CREATED");
		return new EncodedPresentationPayload(encoded);
	}

	/** Writes only the frozen body, with no route, length prefix, or record serialization. */
	public void writeTo(FriendlyByteBuf buffer) {
		buffer.writeBytes(body);
	}

	@Override
	public Type<CustomPacketPayload> type() {
		return PACKET_TYPE;
	}
}
