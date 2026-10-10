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
import java.util.HashSet;
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
 * {@code RESET} carries an authoritative bounded target-type/adapter/field mask
 * and the complete per-target-type child deny map, an empty mask denies every
 * field, and {@code CREATED} carries the marker's explicit receipt content
 * descriptor and default property ref beside the canonical snapshot.
 */
public record PresentationS2CPacket(Kind kind, int protocol, long epoch, long view,
	long revision, Map<String, List<PresentationField>> manifest, Map<String, Integer> schemas,
	Map<String, Map<String, Set<String>>> mask,
	Map<String, List<PresentationPropertyRef>> childBlack,
	MarkerSnapshot snapshot, String ownerName, PresentationPropertyRef defaultRef,
	PresentationReceiptContent content, byte[] sectionBytes,
	MarkerId markerId, MarkerRemovalReason removalReason, TargetKey targetKey, Optional<MarkerId> winnerId,
	long requestId, MarkerRequestKind requestKind, MarkerRejectReason rejectReason) implements IPacket {
	public enum Kind { OFFER, RESET, CREATED, SECTION, REMOVED, WINNER, REJECT }
	public static final int VERSION = PresentationC2SPacket.VERSION;
	/** The shared clientbound custom-payload body bound every loader route observes. */
	public static final int MAX_S2C_BODY_BYTES = 1048576;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "presentation-v5");
	public static final Type<PresentationS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public PresentationS2CPacket {
		var copy = new LinkedHashMap<String, List<PresentationField>>();
		manifest.forEach((id, fields) -> copy.put(id, List.copyOf(fields)));
		manifest = Map.copyOf(copy);
		schemas = Map.copyOf(schemas);
		mask = validateMask(mask, kind);
		childBlack = validateChildBlack(childBlack, kind);
		if (kind != Kind.CREATED && defaultRef != null) throw new IllegalArgumentException("unexpected default property ref");
		if (kind != Kind.CREATED && content != null) throw new IllegalArgumentException("unexpected receipt content");
		if (kind == Kind.CREATED && content == null) throw new IllegalArgumentException("missing receipt content");
		sectionBytes = sectionBytes == null ? new byte[0] : sectionBytes.clone();
		if (sectionBytes.length > PresentationCodec.MAX_SECTION_BYTES + 5) throw new IllegalArgumentException("section bytes");
	}
	@Override public byte[] sectionBytes() { return sectionBytes.clone(); }
	public PresentationS2CPacket() {
		this(null, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, new byte[0], null, null, null,
			Optional.empty(), 0, null, null);
	}
	public PresentationS2CPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private PresentationS2CPacket(PresentationS2CPacket p) {
		this(p.kind, p.protocol, p.epoch, p.view, p.revision, p.manifest, p.schemas, p.mask, p.childBlack,
			p.snapshot, p.ownerName, p.defaultRef, p.content, p.sectionBytes, p.markerId, p.removalReason,
			p.targetKey, p.winnerId, p.requestId, p.requestKind, p.rejectReason);
	}
	/**
	 * Source-compatible constructor for callers that predate the receipt
	 * descriptor: a {@code CREATED} packet defaults to the ordinary
	 * {@link PresentationReceiptContent.Kind#WHOLE} descriptor, and every other
	 * kind carries none. The wire form always writes the descriptor explicitly.
	 */
	public PresentationS2CPacket(Kind kind, int protocol, long epoch, long view, long revision,
		Map<String, List<PresentationField>> manifest, Map<String, Integer> schemas,
		Map<String, Map<String, Set<String>>> mask, Map<String, List<PresentationPropertyRef>> childBlack,
		MarkerSnapshot snapshot, String ownerName,
		PresentationPropertyRef defaultRef, byte[] sectionBytes, MarkerId markerId, MarkerRemovalReason removalReason,
		TargetKey targetKey, Optional<MarkerId> winnerId, long requestId, MarkerRequestKind requestKind,
		MarkerRejectReason rejectReason) {
		this(kind, protocol, epoch, view, revision, manifest, schemas, mask, childBlack, snapshot, ownerName, defaultRef,
			kind == Kind.CREATED ? PresentationReceiptContent.whole() : null, sectionBytes, markerId,
			removalReason, targetKey, winnerId, requestId, requestKind, rejectReason);
	}
	public static PresentationS2CPacket offer(long epoch, Map<String, List<PresentationField>> manifest, Map<String, Integer> schemas) {
		return new PresentationS2CPacket(Kind.OFFER, VERSION, epoch, 0, 0, manifest, schemas, Map.of(), Map.of(),
			null, null, null, new byte[0], null, null, null, Optional.empty(), 0, null, null);
	}
	/** Every existing target type carries no child deny reference. */
	public static Map<String, List<PresentationPropertyRef>> emptyChildBlack() {
		Map<String, List<PresentationPropertyRef>> childBlack = new LinkedHashMap<>();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) childBlack.put(id, List.of());
		return Collections.unmodifiableMap(childBlack);
	}
	/** Compatibility form: a reset without child deny references. */
	public static PresentationS2CPacket reset(long epoch, long view, Map<String, Map<String, Set<String>>> mask) {
		return reset(epoch, view, mask, emptyChildBlack());
	}
	public static PresentationS2CPacket reset(long epoch, long view, Map<String, Map<String, Set<String>>> mask,
		Map<String, List<PresentationPropertyRef>> childBlack) {
		return control(Kind.RESET, epoch, view, mask, childBlack, null, null, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket created(long epoch, long view, long revision, MarkerSnapshot snapshot,
		String owner, PresentationPropertyRef defaultRef, PresentationSection basic) {
		return created(epoch, view, revision, snapshot, owner, defaultRef, PresentationReceiptContent.whole(), basic);
	}
	public static PresentationS2CPacket created(long epoch, long view, long revision, MarkerSnapshot snapshot,
		String owner, PresentationPropertyRef defaultRef, PresentationReceiptContent content, PresentationSection basic) {
		return new PresentationS2CPacket(Kind.CREATED, VERSION, epoch, view, revision, Map.of(), Map.of(), Map.of(), Map.of(),
			snapshot, owner, defaultRef, content, encode(basic), snapshot.id(), null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket section(long epoch, long view, long revision, MarkerId id, PresentationSection section) {
		return new PresentationS2CPacket(Kind.SECTION, VERSION, epoch, view, revision, Map.of(), Map.of(), Map.of(), Map.of(),
			null, null, null, encode(section), id, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket removed(long epoch, long view, MarkerId id, MarkerRemovalReason reason) {
		return control(Kind.REMOVED, epoch, view, Map.of(), Map.of(), id, reason, null, null, Optional.empty(), 0, null, null);
	}
	public static PresentationS2CPacket winner(long epoch, long view, TargetKey key, Optional<MarkerId> id) {
		return control(Kind.WINNER, epoch, view, Map.of(), Map.of(), null, null, key, null, id, 0, null, null);
	}
	public static PresentationS2CPacket rejected(long epoch, long view, long request, MarkerRequestKind kind, MarkerRejectReason reason) {
		return control(Kind.REJECT, epoch, view, Map.of(), Map.of(), null, null, null, null, Optional.empty(), request, kind, reason);
	}
	private static PresentationS2CPacket control(Kind kind, long epoch, long view, Map<String, Map<String, Set<String>>> mask,
		Map<String, List<PresentationPropertyRef>> childBlack,
		MarkerId id, MarkerRemovalReason removal, TargetKey target, PresentationPropertyRef defaultRef,
		Optional<MarkerId> winner, long request, MarkerRequestKind requestKind, MarkerRejectReason reject) {
		return new PresentationS2CPacket(kind, VERSION, epoch, view, 0, Map.of(), Map.of(), mask, childBlack,
			null, null, defaultRef, new byte[0], id, removal, target, winner, request, requestKind, reject);
	}
	private static byte[] encode(PresentationSection section) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256, PresentationCodec.MAX_SECTION_BYTES + 5));
		try { PresentationCodec.write(buf, section); byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); return bytes; }
		finally { buf.release(); }
	}
	@Override public void write(FriendlyByteBuf buf) {
		if (isCorrupt()) throw new IllegalArgumentException("invalid presentation response");
		buf.writeEnum(kind); buf.writeVarInt(protocol); buf.writeLong(epoch); buf.writeLong(view); buf.writeLong(revision);
		switch (kind) {
			case OFFER -> {
				buf.writeVarInt(manifest.size());
				manifest.forEach((id, fields) -> {
					buf.writeUtf(id, 193); buf.writeVarInt(schemas.get(id)); buf.writeVarInt(fields.size());
					fields.forEach(field -> { buf.writeUtf(field.id(), 193); buf.writeEnum(field.kind()); buf.writeBoolean(field.enabledByDefault()); buf.writeVarInt(field.permissionLevel()); buf.writeUtf(field.label(), 128); });
				});
			}
			case RESET -> { writeMask(buf, mask); writeChildBlack(buf, childBlack); }
			case CREATED -> {
				MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
				MarkerPacketCodec.writeOwnerName(buf, ownerName);
				PresentationCodec.writePropertyRef(buf, defaultRef);
				PresentationCodec.writeReceiptContent(buf, content);
				buf.writeByteArray(sectionBytes);
			}
			case SECTION -> { MarkerPacketCodec.writeMarkerId(buf, markerId); buf.writeByteArray(sectionBytes); }
			case REMOVED -> { MarkerPacketCodec.writeMarkerId(buf, markerId); buf.writeEnum(removalReason); }
			case WINNER -> { MarkerPacketCodec.writeTargetKey(buf, targetKey); buf.writeBoolean(winnerId.isPresent()); winnerId.ifPresent(id -> MarkerPacketCodec.writeMarkerId(buf, id)); }
			case REJECT -> { buf.writeLong(requestId); buf.writeEnum(requestKind); buf.writeEnum(rejectReason); }
		}
	}
	private static PresentationS2CPacket decode(FriendlyByteBuf buf) {
		if (buf.readableBytes() > MAX_S2C_BODY_BYTES) throw new IllegalArgumentException("presentation body bytes");
		int ordinal = StrictPacketCodec.readVarInt(buf);
		if (ordinal >= Kind.values().length) throw new IllegalArgumentException("presentation kind");
		Kind kind = Kind.values()[ordinal]; int protocol = StrictPacketCodec.readVarInt(buf);
		if (protocol != VERSION) throw new IllegalArgumentException("presentation version");
		long epoch = buf.readLong(), view = buf.readLong(), revision = buf.readLong();
		Map<String, List<PresentationField>> manifest = new LinkedHashMap<>(); Map<String, Integer> schemas = new LinkedHashMap<>();
		Map<String, Map<String, Set<String>>> mask = Map.of();
		Map<String, List<PresentationPropertyRef>> childBlack = Map.of();
		MarkerSnapshot snapshot = null; String owner = null; PresentationPropertyRef defaultRef = null;
		PresentationReceiptContent content = null;
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
			case RESET -> { mask = readMask(buf); childBlack = readChildBlack(buf); }
			case CREATED -> {
				snapshot = MarkerPacketCodec.readMarkerSnapshot(buf); marker = snapshot.id();
				owner = MarkerPacketCodec.readOwnerName(buf);
				defaultRef = PresentationCodec.readPropertyRef(buf);
				content = PresentationCodec.readReceiptContent(buf);
				section = buf.readByteArray(PresentationCodec.MAX_SECTION_BYTES + 5);
			}
			case SECTION -> { marker = MarkerPacketCodec.readMarkerId(buf); section = buf.readByteArray(PresentationCodec.MAX_SECTION_BYTES + 5); }
			case REMOVED -> { marker = MarkerPacketCodec.readMarkerId(buf); removal = buf.readEnum(MarkerRemovalReason.class); }
			case WINNER -> { target = MarkerPacketCodec.readTargetKey(buf); if (buf.readBoolean()) winner = Optional.of(MarkerPacketCodec.readMarkerId(buf)); }
			case REJECT -> { request = buf.readLong(); requestKind = buf.readEnum(MarkerRequestKind.class); reject = buf.readEnum(MarkerRejectReason.class); }
		}
		if (buf.isReadable()) throw new IllegalArgumentException("trailing presentation response");
		return new PresentationS2CPacket(kind, protocol, epoch, view, revision, manifest, schemas, mask, childBlack, snapshot, owner,
			defaultRef, content, section, marker, removal, target, winner, request, requestKind, reject);
	}
	@Override public boolean isCorrupt() {
		return kind == null || protocol != VERSION || epoch == 0 || view < 0 || revision < 0
			|| (kind == Kind.RESET && view < 1)
			|| (kind == Kind.RESET && encodedResetBodyBytes() > MAX_S2C_BODY_BYTES)
			|| (kind == Kind.CREATED && (snapshot == null || ownerName == null || ownerName.isBlank()
				|| defaultRef == null || content == null || sectionBytes.length == 0))
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
		int types = PresentationC2SPacket.bounded(StrictPacketCodec.readVarInt(buf), PresentationSettings.TARGET_TYPE_IDS.size());
		Map<String, Map<String, Set<String>>> mask = new LinkedHashMap<>();
		for (int t = 0; t < types; t++) {
			int typeTotal = 0;
			String type = StrictPacketCodec.readUtf(buf, 193);
			if (!PresentationSettings.isKnownTargetType(type) || mask.containsKey(type))
				throw new IllegalArgumentException("unknown presentation mask target type");
			int adapters = PresentationC2SPacket.bounded(StrictPacketCodec.readVarInt(buf), PresentationC2SPacket.MAX_ADAPTERS);
			Map<String, Set<String>> adapterMap = new LinkedHashMap<>();
			for (int a = 0; a < adapters; a++) {
				String adapter = StrictPacketCodec.readUtf(buf, 193); PresentationIds.validate(adapter);
				int fields = PresentationC2SPacket.bounded(StrictPacketCodec.readVarInt(buf), PresentationCodec.MAX_FIELDS);
				Set<String> fieldIds = new LinkedHashSet<>();
				for (int f = 0; f < fields; f++) {
					String field = StrictPacketCodec.readUtf(buf, 193); PresentationIds.validate(field);
					if (!fieldIds.add(field)) throw new IllegalArgumentException("duplicate mask field");
					if (++typeTotal > PresentationC2SPacket.MAX_FIELDS) throw new IllegalArgumentException("presentation mask field total per type");
				}
				if (adapterMap.putIfAbsent(adapter, fieldIds) != null) throw new IllegalArgumentException("duplicate mask adapter");
			}
			mask.put(type, adapterMap);
		}
		return mask;
	}

	/**
	 * The RESET child deny map is complete and strict: exactly every existing
	 * target type appears once, each list is at most the per-type child-deny
	 * capacity, and every entry is a nested, unique, grammar-valid reference. A
	 * non-reset message carries no child deny map, and a missing target type can
	 * never decode as an empty deny list.
	 */
	static Map<String, List<PresentationPropertyRef>> validateChildBlack(
		Map<String, List<PresentationPropertyRef>> childBlack, Kind kind) {
		if (childBlack == null) throw new IllegalArgumentException("presentation child deny map");
		if (kind != Kind.RESET && !childBlack.isEmpty())
			throw new IllegalArgumentException("unexpected presentation child deny map");
		if (kind == Kind.RESET && childBlack.size() != PresentationSettings.TARGET_TYPE_IDS.size())
			throw new IllegalArgumentException("presentation child deny types");
		Map<String, List<PresentationPropertyRef>> copy = new LinkedHashMap<>();
		for (var type : childBlack.entrySet()) {
			if (!PresentationSettings.isKnownTargetType(type.getKey()))
				throw new IllegalArgumentException("unknown presentation child deny target type");
			List<PresentationPropertyRef> refs = type.getValue();
			if (refs == null || refs.size() > PresentationSettings.MAX_CHILD_BLACK_REFS)
				throw new IllegalArgumentException("presentation child deny capacity");
			Set<PresentationPropertyRef> unique = new HashSet<>();
			List<PresentationPropertyRef> refCopy = new ArrayList<>(refs.size());
			for (PresentationPropertyRef ref : refs) {
				if (ref == null || ref.isRoot() || !unique.add(ref))
					throw new IllegalArgumentException("invalid presentation child deny reference");
				refCopy.add(ref);
			}
			copy.put(type.getKey(), Collections.unmodifiableList(refCopy));
		}
		return Collections.unmodifiableMap(copy);
	}

	private static void writeChildBlack(FriendlyByteBuf buf, Map<String, List<PresentationPropertyRef>> childBlack) {
		buf.writeVarInt(childBlack.size());
		new TreeMap<>(childBlack).forEach((type, refs) -> {
			buf.writeUtf(type, 193);
			buf.writeVarInt(refs.size());
			refs.forEach(ref -> PresentationCodec.writePropertyRef(buf, ref));
		});
	}

	private static Map<String, List<PresentationPropertyRef>> readChildBlack(FriendlyByteBuf buf) {
		int types = PresentationC2SPacket.bounded(StrictPacketCodec.readVarInt(buf), PresentationSettings.TARGET_TYPE_IDS.size());
		Map<String, List<PresentationPropertyRef>> childBlack = new LinkedHashMap<>();
		for (int t = 0; t < types; t++) {
			String type = StrictPacketCodec.readUtf(buf, 193);
			if (!PresentationSettings.isKnownTargetType(type) || childBlack.containsKey(type))
				throw new IllegalArgumentException("unknown presentation child deny target type");
			int count = PresentationC2SPacket.bounded(StrictPacketCodec.readVarInt(buf), PresentationSettings.MAX_CHILD_BLACK_REFS);
			Set<PresentationPropertyRef> unique = new HashSet<>();
			List<PresentationPropertyRef> refs = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				PresentationPropertyRef ref = PresentationCodec.readPropertyRefStrict(buf);
				if (ref.isRoot() || !unique.add(ref))
					throw new IllegalArgumentException("invalid presentation child deny reference");
				refs.add(ref);
			}
			childBlack.put(type, refs);
		}
		if (childBlack.size() != PresentationSettings.TARGET_TYPE_IDS.size())
			throw new IllegalArgumentException("incomplete presentation child deny map");
		return childBlack;
	}

	/**
	 * Canonical encoded size of a RESET body: the header, the mask, and
	 * the child deny map, measured with the same UTF-8 and varint accounting the
	 * writer uses. The shared loader clientbound custom-payload bound limits it.
	 */
	int encodedResetBodyBytes() {
		if (kind != Kind.RESET) return 0;
		int size = 1 + varIntSize(protocol) + 8 + 8 + 8;
		size += varIntSize(mask.size());
		for (var type : mask.entrySet()) {
			size += encodedUtfBytes(type.getKey()) + varIntSize(type.getValue().size());
			for (var adapter : type.getValue().entrySet()) {
				size += encodedUtfBytes(adapter.getKey()) + varIntSize(adapter.getValue().size());
				for (String field : adapter.getValue()) size += encodedUtfBytes(field);
			}
		}
		size += varIntSize(childBlack.size());
		for (var type : childBlack.entrySet()) {
			size += encodedUtfBytes(type.getKey()) + varIntSize(type.getValue().size());
			for (PresentationPropertyRef ref : type.getValue()) {
				size += encodedUtfBytes(ref.adapterId()) + encodedUtfBytes(ref.fieldId());
				size += varIntSize(ref.recordPath().size());
				for (String key : ref.recordPath()) size += encodedUtfBytes(key);
			}
		}
		return size;
	}

	/** UTF length prefix plus bytes; {@code writeUtf} bounds characters, not bytes. */
	private static int encodedUtfBytes(String value) {
		int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		return varIntSize(bytes) + bytes;
	}

	/** Encoded size of a non-negative varint; mirrors {@code FriendlyByteBuf#writeVarInt}. */
	private static int varIntSize(int value) {
		return (value & -128) == 0 ? 1
			: (value & -16384) == 0 ? 2
			: (value & -2097152) == 0 ? 3
			: (value & -268435456) == 0 ? 4
			: 5;
	}
}
