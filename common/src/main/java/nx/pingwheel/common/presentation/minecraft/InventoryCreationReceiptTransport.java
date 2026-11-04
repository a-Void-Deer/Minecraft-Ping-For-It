package nx.pingwheel.common.presentation.minecraft;

import io.netty.buffer.Unpooled;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryBackend.InitialDecision;
import org.jetbrains.annotations.NotNullByDefault;

/** Server-thread atomic creation handoff. Platform adapters consume bytes without serializing the packet again. */
@NotNullByDefault
public final class InventoryCreationReceiptTransport {
	private InventoryCreationReceiptTransport() {}
	@NotNullByDefault
	public interface EncodedTransport {
		/** Synchronously accepts or copies the encoded body; the caller releases it on return. False means no handoff. */
		boolean sendEncodedCreated(PresentationS2CPacket packet, ServerPlayer recipient, FriendlyByteBuf encoded);
	}
	public static InitialDecision send(EncodedTransport transport, PresentationS2CPacket packet,
		ServerPlayer recipient, Supplier<InitialDecision> beforeHandoff) {
		int bytes = packet.encodedCreatedBodyBytes();
		FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer(bytes, bytes));
		try {
			packet.write(encoded);
			if (encoded.readableBytes() != bytes) throw new IllegalStateException("created measurement mismatch");
			InitialDecision decision = beforeHandoff.get();
			if (decision != InitialDecision.READY) return decision;
			return transport.sendEncodedCreated(packet, recipient, encoded) ? InitialDecision.READY : InitialDecision.DEFERRED;
		} finally { encoded.release(); }
	}
}
