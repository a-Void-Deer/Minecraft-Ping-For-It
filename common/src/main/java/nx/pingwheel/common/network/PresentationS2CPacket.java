package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.presentation.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/** One ordered transport for negotiation, atomic initial Basic, updates and terminal barriers. */
public record PresentationS2CPacket(Kind kind, int protocol, long epoch, long subscription, long view,
	long revision, Map<String, List<PresentationField>> manifest, Map<String, Integer> schemas,
	MarkerSnapshot snapshot, String ownerName, byte[] sectionBytes, MarkerId markerId,
	MarkerRemovalReason removalReason, TargetKey targetKey, Optional<MarkerId> winnerId,
	long requestId, MarkerRequestKind requestKind, MarkerRejectReason rejectReason) implements IPacket {
	public enum Kind { OFFER, RESET, CREATED, SECTION, REMOVED, WINNER, REJECT }
	public static final int VERSION = PresentationC2SPacket.VERSION;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "presentation-v2");
	public static final Type<PresentationS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);
	public PresentationS2CPacket {
		var copy = new LinkedHashMap<String, List<PresentationField>>();
		manifest.forEach((id, fields) -> copy.put(id, List.copyOf(fields)));
		manifest = Map.copyOf(copy); schemas = Map.copyOf(schemas);
		sectionBytes = sectionBytes.clone();
		if (sectionBytes.length > PresentationCodec.MAX_SECTION_BYTES + 5) throw new IllegalArgumentException("section bytes");
	}
	@Override public byte[] sectionBytes() { return sectionBytes.clone(); }
	public PresentationS2CPacket() { this(null, 0, 0, 0, 0, 0, Map.of(), Map.of(), null, null, new byte[0], null, null, null, Optional.empty(), 0, null, null); }
	public PresentationS2CPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private PresentationS2CPacket(PresentationS2CPacket p) {
		this(p.kind, p.protocol, p.epoch, p.subscription, p.view, p.revision, p.manifest, p.schemas,
			p.snapshot, p.ownerName, p.sectionBytes, p.markerId, p.removalReason, p.targetKey, p.winnerId,
			p.requestId, p.requestKind, p.rejectReason);
	}
	public static PresentationS2CPacket offer(long epoch, Map<String, List<PresentationField>> manifest, Map<String, Integer> schemas) {
		return new PresentationS2CPacket(Kind.OFFER, VERSION, epoch, 0, 0, 0, manifest, schemas, null, null, new byte[0], null, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket reset(long epoch, long sub, long view) { return control(Kind.RESET, epoch, sub, view, null, null, null, Optional.empty(), 0, null, null); }
	public static PresentationS2CPacket created(long epoch, long sub, long view, long revision, MarkerSnapshot snapshot, String owner, PresentationSection basic) {
		return new PresentationS2CPacket(Kind.CREATED, VERSION, epoch, sub, view, revision, Map.of(), Map.of(), snapshot, owner, encode(basic), snapshot.id(), null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket section(long epoch, long sub, long view, long revision, MarkerId id, PresentationSection section) {
		return new PresentationS2CPacket(Kind.SECTION, VERSION, epoch, sub, view, revision, Map.of(), Map.of(), null, null, encode(section), id, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket removed(long epoch, long sub, long view, MarkerId id, MarkerRemovalReason reason) { return control(Kind.REMOVED, epoch, sub, view, id, reason, null, Optional.empty(), 0, null, null); }
	public static PresentationS2CPacket winner(long epoch, long sub, long view, TargetKey key, Optional<MarkerId> id) { return control(Kind.WINNER, epoch, sub, view, null, null, key, id, 0, null, null); }
	public static PresentationS2CPacket rejected(long epoch, long sub, long view, long request, MarkerRequestKind kind, MarkerRejectReason reason) { return control(Kind.REJECT, epoch, sub, view, null, null, null, Optional.empty(), request, kind, reason); }
	private static PresentationS2CPacket control(Kind kind, long epoch, long sub, long view, MarkerId id,
		MarkerRemovalReason removal, TargetKey target, Optional<MarkerId> winner, long request, MarkerRequestKind requestKind, MarkerRejectReason reject) {
		return new PresentationS2CPacket(kind, VERSION, epoch, sub, view, 0, Map.of(), Map.of(), null, null, new byte[0], id, removal, target, winner, request, requestKind, reject);
	}
	private static byte[] encode(PresentationSection section) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256, PresentationCodec.MAX_SECTION_BYTES + 5));
		try { PresentationCodec.write(buf, section); byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); return bytes; }
		finally { buf.release(); }
	}
	@Override public void write(FriendlyByteBuf buf) {
		buf.writeEnum(kind); buf.writeVarInt(protocol); buf.writeLong(epoch); buf.writeLong(subscription); buf.writeLong(view); buf.writeLong(revision);
		switch (kind) {
			case OFFER -> {
				buf.writeVarInt(manifest.size());
				manifest.forEach((id, fields) -> {
					buf.writeUtf(id, 193); buf.writeVarInt(schemas.get(id)); buf.writeVarInt(fields.size());
					fields.forEach(field -> { buf.writeUtf(field.id(), 193); buf.writeEnum(field.kind()); buf.writeBoolean(field.enabledByDefault()); buf.writeVarInt(field.permissionLevel()); buf.writeUtf(field.label(), 128); });
				});
			}
			case RESET -> { }
			case CREATED -> { MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot); MarkerPacketCodec.writeOwnerName(buf, ownerName); buf.writeByteArray(sectionBytes); }
			case SECTION -> { MarkerPacketCodec.writeMarkerId(buf, markerId); buf.writeByteArray(sectionBytes); }
			case REMOVED -> { MarkerPacketCodec.writeMarkerId(buf, markerId); buf.writeEnum(removalReason); }
			case WINNER -> { MarkerPacketCodec.writeTargetKey(buf, targetKey); buf.writeBoolean(winnerId.isPresent()); winnerId.ifPresent(id -> MarkerPacketCodec.writeMarkerId(buf, id)); }
			case REJECT -> { buf.writeLong(requestId); buf.writeEnum(requestKind); buf.writeEnum(rejectReason); }
		}
	}
	private static PresentationS2CPacket decode(FriendlyByteBuf buf) {
		Kind kind = buf.readEnum(Kind.class); int protocol = buf.readVarInt();
		long epoch = buf.readLong(), sub = buf.readLong(), view = buf.readLong(), revision = buf.readLong();
		Map<String, List<PresentationField>> manifest = new LinkedHashMap<>(); Map<String, Integer> schemas = new LinkedHashMap<>();
		MarkerSnapshot snapshot = null; String owner = null; byte[] section = new byte[0]; MarkerId marker = null;
		MarkerRemovalReason removal = null; TargetKey target = null; Optional<MarkerId> winner = Optional.empty();
		long request = 0; MarkerRequestKind requestKind = null; MarkerRejectReason reject = null;
		switch (kind) {
			case OFFER -> {
				int count = PresentationC2SPacket.bounded(buf.readVarInt(), PresentationC2SPacket.MAX_ADAPTERS), total = 0;
				for (int i = 0; i < count; i++) {
					String id = buf.readUtf(193); PresentationIds.validate(id);
					int schema = PresentationC2SPacket.bounded(buf.readVarInt(), 255);
					int size = PresentationC2SPacket.bounded(buf.readVarInt(), PresentationCodec.MAX_FIELDS);
					total += size; if (total > PresentationC2SPacket.MAX_FIELDS || schema < 1 || schemas.putIfAbsent(id, schema) != null) throw new IllegalArgumentException("manifest size/schema");
					List<PresentationField> fields = new ArrayList<>();
					for (int f = 0; f < size; f++) fields.add(new PresentationField(buf.readUtf(193), buf.readEnum(PresentationField.Kind.class), buf.readBoolean(), buf.readVarInt(), buf.readUtf(128)));
					manifest.put(id, fields);
				}
			}
			case RESET -> { }
			case CREATED -> { snapshot = MarkerPacketCodec.readMarkerSnapshot(buf); marker = snapshot.id(); owner = MarkerPacketCodec.readOwnerName(buf); section = buf.readByteArray(PresentationCodec.MAX_SECTION_BYTES + 5); }
			case SECTION -> { marker = MarkerPacketCodec.readMarkerId(buf); section = buf.readByteArray(PresentationCodec.MAX_SECTION_BYTES + 5); }
			case REMOVED -> { marker = MarkerPacketCodec.readMarkerId(buf); removal = buf.readEnum(MarkerRemovalReason.class); }
			case WINNER -> { target = MarkerPacketCodec.readTargetKey(buf); if (buf.readBoolean()) winner = Optional.of(MarkerPacketCodec.readMarkerId(buf)); }
			case REJECT -> { request = buf.readLong(); requestKind = buf.readEnum(MarkerRequestKind.class); reject = buf.readEnum(MarkerRejectReason.class); }
		}
		if (buf.isReadable()) throw new IllegalArgumentException("trailing presentation response");
		return new PresentationS2CPacket(kind, protocol, epoch, sub, view, revision, manifest, schemas, snapshot, owner, section, marker, removal, target, winner, request, requestKind, reject);
	}
	@Override public boolean isCorrupt() {
		return kind == null || protocol != VERSION || epoch == 0 || subscription < 0 || view < 0 || revision < 0
			|| (kind == Kind.CREATED && (snapshot == null || ownerName == null || ownerName.isBlank() || sectionBytes.length == 0))
			|| (kind == Kind.SECTION && (markerId == null || sectionBytes.length == 0))
			|| (kind == Kind.REMOVED && (markerId == null || removalReason == null))
			|| (kind == Kind.WINNER && targetKey == null)
			|| (kind == Kind.REJECT && (requestKind == null || rejectReason == null));
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public @NotNull Type<PresentationS2CPacket> type() { return PACKET_TYPE; }
	public static PresentationS2CPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, PresentationS2CPacket.class); }
}
