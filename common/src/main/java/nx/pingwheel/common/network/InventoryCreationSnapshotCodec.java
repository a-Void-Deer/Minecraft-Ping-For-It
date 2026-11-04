package nx.pingwheel.common.network;

import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.PresentationPropertyPingTypes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** One bounded creation payload; no native identity/component codec or Tracking state. */
@NotNullByDefault
public final class InventoryCreationSnapshotCodec {
	public static final int MAX_PAYLOAD_BYTES = InventoryS2CPacket.MAX_ENTRY_BYTES;
	private InventoryCreationSnapshotCodec() {}

	/** Exact length including the canonical VarInt payload-length prefix; allocation free. */
	public static int encodedSize(InventoryCreationSnapshot snapshot) {
		int payload = payloadSize(snapshot);
		return varIntSize(payload) + payload;
	}

	private static int payloadSize(InventoryCreationSnapshot snapshot) {
		int size = 13 + textSize(snapshot.opaqueKey()) + textSize(snapshot.resourceId())
			+ textSize(snapshot.label()) + textSize(snapshot.annotationPingType());
		if (snapshot.displayJson() != null) size += textSize(snapshot.displayJson());
		if (size > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("creation snapshot payload bytes");
		return size;
	}

	/** Validated immutable input is measured before any destination write. */
	public static void write(FriendlyByteBuf buf, InventoryCreationSnapshot snapshot) {
		buf.writeVarInt(payloadSize(snapshot));
		buf.writeByte(snapshot.resourceKind().wireId());
		writeText(buf, snapshot.opaqueKey()); writeText(buf, snapshot.resourceId()); writeText(buf, snapshot.label());
		buf.writeBoolean(snapshot.displayJson() != null);
		if (snapshot.displayJson() != null) writeText(buf, snapshot.displayJson());
		buf.writeLong(snapshot.amount()); buf.writeBoolean(snapshot.aggregate());
		writeText(buf, snapshot.annotationPingType());
		buf.writeBoolean(snapshot.previewComplete()); buf.writeBoolean(snapshot.quantityUncertain());
	}

	public static InventoryCreationSnapshot read(FriendlyByteBuf buf) {
		int size = StrictPacketCodec.readVarInt(buf);
		if (size > MAX_PAYLOAD_BYTES || size > buf.readableBytes())
			throw new IllegalArgumentException("creation snapshot payload length");
		FriendlyByteBuf frame = new FriendlyByteBuf(buf.readSlice(size));
		InventoryResourceKind kind = InventoryResourceKind.fromWireId(frame.readUnsignedByte());
		String key = InventoryWireCodec.readUtf(frame, InventoryS2CPacket.MAX_KEY_BYTES, InventoryS2CPacket.MAX_KEY_BYTES);
		String id = InventoryWireCodec.readUtf(frame, InventoryS2CPacket.MAX_ITEM_ID_BYTES, InventoryS2CPacket.MAX_ITEM_ID_BYTES);
		String label = InventoryWireCodec.readUtf(frame, InventoryS2CPacket.MAX_LABEL_BYTES, InventoryS2CPacket.MAX_LABEL_BYTES);
		@Nullable String display = StrictPacketCodec.readBoolean(frame)
			? InventoryWireCodec.readUtf(frame, InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_DISPLAY_BYTES) : null;
		long amount = frame.readLong();
		boolean aggregate = StrictPacketCodec.readBoolean(frame);
		String annotation = InventoryWireCodec.readUtf(frame, PresentationPropertyPingTypes.MAX_PING_TYPE_LENGTH,
			PresentationPropertyPingTypes.MAX_PING_TYPE_LENGTH);
		boolean complete = StrictPacketCodec.readBoolean(frame), uncertain = StrictPacketCodec.readBoolean(frame);
		if (frame.isReadable()) throw new IllegalArgumentException("trailing creation snapshot");
		return new InventoryCreationSnapshot(kind, key, id, label, display, amount, aggregate, annotation, complete, uncertain);
	}

	private static int textSize(String text) {
		int bytes = ByteBufUtil.utf8Bytes(text);
		return varIntSize(bytes) + bytes;
	}
	private static void writeText(FriendlyByteBuf buf, String text) {
		int bytes = ByteBufUtil.utf8Bytes(text);
		buf.writeVarInt(bytes);
		ByteBufUtil.reserveAndWriteUtf8(buf.unwrap(), text, bytes);
	}
	private static int varIntSize(int value) {
		int size = 1;
		while ((value & ~0x7f) != 0) { value >>>= 7; size++; }
		return size;
	}
}
