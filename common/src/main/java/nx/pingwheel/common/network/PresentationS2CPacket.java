package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.presentation.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/**
 * One ordered transport for negotiation, atomic initial Basic, updates and
 * terminal barriers. The server owns the field selection entirely: an accepted
 * {@code RESET} carries an authoritative bounded target-type/adapter/field mask,
 * an empty mask denies every field, and {@code CREATED} carries the marker's
 * default property ref beside the canonical snapshot.
 */
public record PresentationS2CPacket(Kind kind, int protocol, long epoch, long view,
	long revision, Map<String, List<PresentationField>> manifest, Map<String, Integer> schemas,
	Map<String, Map<String, Set<String>>> mask,
	MarkerSnapshot snapshot, String ownerName, PresentationPropertyRef defaultRef, byte[] sectionBytes,
	MarkerId markerId, MarkerRemovalReason removalReason, TargetKey targetKey, Optional<MarkerId> winnerId,
	long requestId, MarkerRequestKind requestKind, MarkerRejectReason rejectReason) implements IPacket {
	public enum Kind { OFFER, RESET, CREATED, SECTION, REMOVED, WINNER, REJECT }
	public static final int VERSION = PresentationC2SPacket.VERSION;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "presentation-v3");
	public static final Type<PresentationS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public PresentationS2CPacket {
		var copy = new LinkedHashMap<String, List<PresentationField>>();
		manifest.forEach((id, fields) -> copy.put(id, List.copyOf(fields)));
		manifest = Map.copyOf(copy);
		schemas = Map.copyOf(schemas);
		mask = validateMask(mask, kind);
		if (kind != Kind.CREATED && defaultRef != null) throw new IllegalArgumentException("unexpected default property ref");
		sectionBytes = sectionBytes == null ? new byte[0] : sectionBytes.clone();
		if (sectionBytes.length > PresentationCodec.MAX_SECTION_BYTES + 5) throw new IllegalArgumentException("section bytes");
	}
	@Override public byte[] sectionBytes() { return sectionBytes.clone(); }
	public PresentationS2CPacket() {
		this(null, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), null, null, null, new byte[0], null, null, null,
			Optional.empty(), 0, null, null);
	}
	public PresentationS2CPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private PresentationS2CPacket(PresentationS2CPacket p) {
		this(p.kind, p.protocol, p.epoch, p.view, p.revision, p.manifest, p.schemas, p.mask,
			p.snapshot, p.ownerName, p.defaultRef, p.sectionBytes, p.markerId, p.removalReason, p.targetKey,
			p.winnerId, p.requestId, p.requestKind, p.rejectReason);
	}
	public static PresentationS2CPacket offer(long epoch, Map<String, List<PresentationField>> manifest, Map<String, Integer> schemas) {
		return new PresentationS2CPacket(Kind.OFFER, VERSION, epoch, 0, 0, manifest, schemas, Map.of(),
			null, null, null, new byte[0], null, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket reset(long epoch, long view, Map<String, Map<String, Set<String>>> mask) {
		return control(Kind.RESET, epoch, view, mask, null, null, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket created(long epoch, long view, long revision, MarkerSnapshot snapshot,
		String owner, PresentationPropertyRef defaultRef, PresentationSection basic) {
		return new PresentationS2CPacket(Kind.CREATED, VERSION, epoch, view, revision, Map.of(), Map.of(), Map.of(),
			snapshot, owner, defaultRef, encode(basic), snapshot.id(), null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket section(long epoch, long view, long revision, MarkerId id, PresentationSection section) {
		return new PresentationS2CPacket(Kind.SECTION, VERSION, epoch, view, revision, Map.of(), Map.of(), Map.of(),
			null, null, null, encode(section), id, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket removed(long epoch, long view, MarkerId id, MarkerRemovalReason reason) {
		return control(Kind.REMOVED, epoch, view, Map.of(), id, reason, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket winner(long epoch, long view, TargetKey key, Optional<MarkerId> id) {
		return control(Kind.WINNER, epoch, view, Map.of(), null, null, key, null, id, 0, null, null);
	}
	public static PresentationS2CPacket rejected(long epoch, long view, long request, MarkerRequestKind kind, MarkerRejectReason reason) {
		return control(Kind.REJECT, epoch, view, Map.of(), null, null, null, null, Optional.empty(), request, kind, reason);
	}
	private static PresentationS2CPacket control(Kind kind, long epoch, long view, Map<String, Map<String, Set<String>>> mask,
		MarkerId id, MarkerRemovalReason removal, TargetKey target, PresentationPropertyRef defaultRef,
		Optional<MarkerId> winner, long request, MarkerRequestKind requestKind, MarkerRejectReason reject) {
		return new PresentationS2CPacket(kind, VERSION, epoch, view, 0, Map.of(), Map.of(), mask,
			null, null, defaultRef, new byte[0], id, removal, target, winner, request, requestKind, reject);
	}
	private static byte[] encode(PresentationSection section) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256, PresentationCodec.MAX_SECTION_BYTES + 5));
		try { PresentationCodec.write(buf, section); byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); return bytes; }
		finally { buf.release(); }
	}
	@Override public void write(FriendlyByteBuf buf) {
		buf.writeEnum(kind); buf.writeVarInt(protocol); buf.writeLong(epoch); buf.writeLong(view); buf.writeLong(revision);
		switch (kind) {
			case OFFER -> {
				buf.writeVarInt(manifest.size());
				manifest.forEach((id, fields) -> {
					buf.writeUtf(id, 193); buf.writeVarInt(schemas.get(id)); buf.writeVarInt(fields.size());
					fields.forEach(field -> { buf.writeUtf(field.id(), 193); buf.writeEnum(field.kind()); buf.writeBoolean(field.enabledByDefault()); buf.writeVarInt(field.permissionLevel()); buf.writeUtf(field.label(), 128); });
				});
			}
			case RESET -> writeMask(buf, mask);
			case CREATED -> {
				MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
				MarkerPacketCodec.writeOwnerName(buf, ownerName);
				PresentationCodec.writePropertyRef(buf, defaultRef);
				buf.writeByteArray(sectionBytes);
			}
			case SECTION -> { MarkerPacketCodec.writeMarkerId(buf, markerId); buf.writeByteArray(sectionBytes); }
			case REMOVED -> { MarkerPacketCodec.writeMarkerId(buf, markerId); buf.writeEnum(removalReason); }
			case WINNER -> { MarkerPacketCodec.writeTargetKey(buf, targetKey); buf.writeBoolean(winnerId.isPresent()); winnerId.ifPresent(id -> MarkerPacketCodec.writeMarkerId(buf, id)); }
			case REJECT -> { buf.writeLong(requestId); buf.writeEnum(requestKind); buf.writeEnum(rejectReason); }
		}
	}
	private static PresentationS2CPacket decode(FriendlyByteBuf buf) {
		Kind kind = buf.readEnum(Kind.class); int protocol = buf.readVarInt();
		long epoch = buf.readLong(), view = buf.readLong(), revision = buf.readLong();
		Map<String, List<PresentationField>> manifest = new LinkedHashMap<>(); Map<String, Integer> schemas = new LinkedHashMap<>();
		Map<String, Map<String, Set<String>>> mask = Map.of();
		MarkerSnapshot snapshot = null; String owner = null; PresentationPropertyRef defaultRef = null;
		byte[] section = new byte[0]; MarkerId marker = null;
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
			case RESET -> mask = readMask(buf);
			case CREATED -> {
				snapshot = MarkerPacketCodec.readMarkerSnapshot(buf); marker = snapshot.id();
				owner = MarkerPacketCodec.readOwnerName(buf);
				defaultRef = PresentationCodec.readPropertyRef(buf);
				section = buf.readByteArray(PresentationCodec.MAX_SECTION_BYTES + 5);
			}
			case SECTION -> { marker = MarkerPacketCodec.readMarkerId(buf); section = buf.readByteArray(PresentationCodec.MAX_SECTION_BYTES + 5); }
			case REMOVED -> { marker = MarkerPacketCodec.readMarkerId(buf); removal = buf.readEnum(MarkerRemovalReason.class); }
			case WINNER -> { target = MarkerPacketCodec.readTargetKey(buf); if (buf.readBoolean()) winner = Optional.of(MarkerPacketCodec.readMarkerId(buf)); }
			case REJECT -> { request = buf.readLong(); requestKind = buf.readEnum(MarkerRequestKind.class); reject = buf.readEnum(MarkerRejectReason.class); }
		}
		if (buf.isReadable()) throw new IllegalArgumentException("trailing presentation response");
		return new PresentationS2CPacket(kind, protocol, epoch, view, revision, manifest, schemas, mask, snapshot, owner,
			defaultRef, section, marker, removal, target, winner, request, requestKind, reject);
	}
	@Override public boolean isCorrupt() {
		return kind == null || protocol != VERSION || epoch == 0 || view < 0 || revision < 0
			|| (kind == Kind.CREATED && (snapshot == null || ownerName == null || ownerName.isBlank()
				|| defaultRef == null || sectionBytes.length == 0))
			|| (kind == Kind.SECTION && (markerId == null || sectionBytes.length == 0))
			|| (kind == Kind.REMOVED && (markerId == null || removalReason == null))
			|| (kind == Kind.WINNER && targetKey == null)
			|| (kind == Kind.REJECT && (requestKind == null || rejectReason == null));
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public @NotNull Type<PresentationS2CPacket> type() { return PACKET_TYPE; }
	public static PresentationS2CPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, PresentationS2CPacket.class); }

	/** Empty is a valid deny-all mask; an unknown type or over-capacity entry is rejected. */
	static Map<String, Map<String, Set<String>>> validateMask(Map<String, Map<String, Set<String>>> mask, Kind kind) {
		if (mask == null) throw new IllegalArgumentException("presentation mask");
		if (kind != Kind.RESET && !mask.isEmpty()) throw new IllegalArgumentException("unexpected presentation mask");
		if (mask.size() > PresentationSettings.TARGET_TYPE_IDS.size()) throw new IllegalArgumentException("presentation mask types");
		Map<String, Map<String, Set<String>>> copy = new LinkedHashMap<>();
		for (var type : mask.entrySet()) {
			int typeTotal = 0;
			if (!PresentationSettings.isKnownTargetType(type.getKey())) throw new IllegalArgumentException("unknown mask target type");
			Map<String, Set<String>> adapters = type.getValue();
			if (adapters == null || adapters.size() > PresentationC2SPacket.MAX_ADAPTERS) throw new IllegalArgumentException("presentation mask adapters");
			Map<String, Set<String>> adapterCopy = new LinkedHashMap<>();
			for (var adapter : adapters.entrySet()) {
				PresentationIds.validate(adapter.getKey());
				Set<String> fields = adapter.getValue();
				if (fields == null || fields.size() > PresentationCodec.MAX_FIELDS) throw new IllegalArgumentException("presentation mask fields");
				Set<String> fieldCopy = new LinkedHashSet<>();
				for (String field : fields) {
					PresentationIds.validate(field);
					if (!fieldCopy.add(field)) throw new IllegalArgumentException("duplicate mask field");
					if (++typeTotal > PresentationC2SPacket.MAX_FIELDS) throw new IllegalArgumentException("presentation mask field total per type");
				}
				if (adapterCopy.put(adapter.getKey(), Collections.unmodifiableSet(fieldCopy)) != null)
					throw new IllegalArgumentException("duplicate mask adapter");
			}
			copy.put(type.getKey(), Collections.unmodifiableMap(adapterCopy));
		}
		return Collections.unmodifiableMap(copy);
	}

	private static void writeMask(FriendlyByteBuf buf, Map<String, Map<String, Set<String>>> mask) {
		buf.writeVarInt(mask.size());
		new TreeMap<>(mask).forEach((type, adapters) -> {
			buf.writeUtf(type, 193);
			buf.writeVarInt(adapters.size());
			new TreeMap<>(adapters).forEach((adapter, fields) -> {
				buf.writeUtf(adapter, 193);
				buf.writeVarInt(fields.size());
				fields.stream().sorted().forEach(field -> buf.writeUtf(field, 193));
			});
		});
	}

	private static Map<String, Map<String, Set<String>>> readMask(FriendlyByteBuf buf) {
		int types = PresentationC2SPacket.bounded(buf.readVarInt(), PresentationSettings.TARGET_TYPE_IDS.size());
		Map<String, Map<String, Set<String>>> mask = new LinkedHashMap<>();
		for (int t = 0; t < types; t++) {
			int typeTotal = 0;
			String type = buf.readUtf(193);
			if (!PresentationSettings.isKnownTargetType(type) || mask.containsKey(type))
				throw new IllegalArgumentException("unknown presentation mask target type");
			int adapters = PresentationC2SPacket.bounded(buf.readVarInt(), PresentationC2SPacket.MAX_ADAPTERS);
			Map<String, Set<String>> adapterMap = new LinkedHashMap<>();
			for (int a = 0; a < adapters; a++) {
				String adapter = buf.readUtf(193); PresentationIds.validate(adapter);
				int fields = PresentationC2SPacket.bounded(buf.readVarInt(), PresentationCodec.MAX_FIELDS);
				Set<String> fieldIds = new LinkedHashSet<>();
				for (int f = 0; f < fields; f++) {
					String field = buf.readUtf(193); PresentationIds.validate(field);
					if (!fieldIds.add(field)) throw new IllegalArgumentException("duplicate mask field");
					if (++typeTotal > PresentationC2SPacket.MAX_FIELDS) throw new IllegalArgumentException("presentation mask field total per type");
				}
				if (adapterMap.putIfAbsent(adapter, fieldIds) != null) throw new IllegalArgumentException("duplicate mask adapter");
			}
			mask.put(type, adapterMap);
		}
		return mask;
	}
}
