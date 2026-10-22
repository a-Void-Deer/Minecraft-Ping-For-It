package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.presentation.preview.PresentationPreviewLimits;

/** Framed bytes remain undecoded until current request authorization is available. */
public record PresentationPreviewS2CPacket(int protocol, long epoch, long view, long requestId,
	String adapterId, int schema, Status status, byte[] sectionBytes) implements IPacket {
	public enum Status { RESULT, UNAVAILABLE, DEFERRED, REJECTED }
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath("pingforit-s2c", "presentation-preview-v1");
	public static final Type<PresentationPreviewS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);
	public PresentationPreviewS2CPacket {
		sectionBytes = sectionBytes.clone();
		if (sectionBytes.length > PresentationCodec.MAX_SECTION_BYTES + 5) throw new IllegalArgumentException("preview section bytes");
	}
	@Override public byte[] sectionBytes() { return sectionBytes.clone(); }
	public PresentationPreviewS2CPacket() { this(0, 0, 0, 0, null, 0, null, new byte[0]); }
	public PresentationPreviewS2CPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private PresentationPreviewS2CPacket(PresentationPreviewS2CPacket packet) {
		this(packet.protocol, packet.epoch, packet.view, packet.requestId, packet.adapterId, packet.schema, packet.status, packet.sectionBytes);
	}
	public static PresentationPreviewS2CPacket control(PresentationPreviewC2SPacket request, int schema, Status status) {
		if (status == Status.RESULT) throw new IllegalArgumentException("result requires values");
		return new PresentationPreviewS2CPacket(1, request.epoch(), request.view(), request.requestId(), request.adapterId(), schema, status, new byte[0]);
	}
	public static PresentationPreviewS2CPacket result(PresentationPreviewC2SPacket request, PresentationSection section) {
		if (!section.annotations().isEmpty() || section.stale()) throw new IllegalArgumentException("preview is not marker state");
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256, PresentationCodec.MAX_SECTION_BYTES + 5));
		try {
			PresentationCodec.write(buf, section); byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes);
			return new PresentationPreviewS2CPacket(1, request.epoch(), request.view(), request.requestId(), section.adapterId(), section.schema(), Status.RESULT, bytes);
		} finally { buf.release(); }
	}
	/**
	 * An unaccepted field is skipped before typed decoding and rejects the whole
	 * response. Any received annotation is invalid.
	 */
	public PresentationSection decodeSection(PresentationPreviewAccess access) {
		if (isCorrupt() || status != Status.RESULT || access.epoch() != epoch || access.view() != view) return null;
		var accepted = access.adapters().get(adapterId);
		if (accepted == null || accepted.schema() != schema) return null;
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(sectionBytes));
		try {
			PresentationSection section = PresentationCodec.readStrict(buf, accepted.fields()::containsKey);
			if (buf.isReadable() || !adapterId.equals(section.adapterId()) || schema != section.schema()
				|| section.stale() || !section.annotations().isEmpty()) return null;
			for (var entry : section.fields().entrySet()) if (!accepted.fields().get(entry.getKey()).accepts(entry.getValue())) return null;
			return section;
		} catch (RuntimeException invalid) { return null; }
		finally { buf.release(); }
	}
	@Override public void write(FriendlyByteBuf buf) {
		if (isCorrupt()) throw new IllegalArgumentException("corrupt preview response");
		buf.writeVarInt(protocol); buf.writeLong(epoch); buf.writeLong(view); buf.writeLong(requestId);
		buf.writeUtf(adapterId, 193); buf.writeVarInt(schema); buf.writeEnum(status); buf.writeByteArray(sectionBytes);
	}
	private static PresentationPreviewS2CPacket decode(FriendlyByteBuf buf) {
		if (buf.readableBytes() > PresentationPreviewLimits.MAX_RESPONSE_BYTES) throw new IllegalArgumentException("preview response frame");
		int version = ServerConfigVarNumbers.readInt(buf);
		if (version != PresentationPreviewC2SPacket.VERSION) throw new IllegalArgumentException("preview protocol");
		long epoch = buf.readLong(), view = buf.readLong(), id = buf.readLong();
		String adapter = StrictPacketCodec.readUtf(buf, 193);
		int schema = ServerConfigVarNumbers.readInt(buf), status = ServerConfigVarNumbers.readInt(buf);
		if (schema < 1 || schema > 255 || status >= Status.values().length) throw new IllegalArgumentException("preview response header");
		int length = ServerConfigVarNumbers.readInt(buf);
		if (length > PresentationCodec.MAX_SECTION_BYTES + 5 || length > buf.readableBytes()) throw new IllegalArgumentException("preview section length");
		byte[] bytes = new byte[length]; buf.readBytes(bytes);
		var packet = new PresentationPreviewS2CPacket(version, epoch, view, id, adapter, schema, Status.values()[status], bytes);
		if (buf.isReadable()) throw new IllegalArgumentException("trailing preview response");
		return packet;
	}
	@Override public boolean isCorrupt() {
		try { PresentationIds.validate(adapterId); } catch (RuntimeException invalid) { return true; }
		return protocol != 1 || epoch == 0 || view < 1 || requestId < 1 || schema < 1 || schema > 255 || status == null
			|| (status == Status.RESULT ? sectionBytes.length == 0 : sectionBytes.length != 0);
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public Type<PresentationPreviewS2CPacket> type() { return PACKET_TYPE; }
	public static PresentationPreviewS2CPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, PresentationPreviewS2CPacket.class); }
}
