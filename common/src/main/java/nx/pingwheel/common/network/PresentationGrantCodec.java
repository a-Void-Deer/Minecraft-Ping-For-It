package nx.pingwheel.common.network;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationGrant;
import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Strict, canonical wire form of one marker's content grant.
 *
 * <p>The canonical server grant is not truncated to wire bounds. A session
 * projection first intersects it with the negotiated catalogue and its
 * accepted schemas/kinds; this codec then enforces the bounded wire grammar.
 * Every read rejects unknown structure, non-canonical ordering, duplicates,
 * malformed UTF-8, invalid enums/booleans, truncation and trailing bytes. A
 * failed read aborts the enclosing packet, whose safe read path drops the
 * whole message.
 */
@NotNullByDefault
public final class PresentationGrantCodec {
	public static final int MAX_ADAPTERS = 32;
	public static final int MAX_FIELDS_PER_ADAPTER = 48;
	public static final int MAX_TOTAL_FIELDS = 256;
	public static final int MAX_CHILD_REFS = 256;
	private static final int MAX_ID_CHARS = 193;
	private static final int MAX_KEY_CHARS = PresentationPropertyRef.MAX_KEY_BYTES;

	private PresentationGrantCodec() {}

	public static void write(FriendlyByteBuf buf, MarkerContentGrant grant) {
		PresentationGrant authorization = grant.authorization();
		validateBounds(authorization);
		buf.writeVarInt(authorization.adapters().size());
		new TreeMap<>(authorization.adapters()).forEach((adapterId, adapter) -> {
			buf.writeUtf(adapterId, MAX_ID_CHARS);
			buf.writeVarInt(adapter.schema());
			buf.writeVarInt(adapter.fields().size());
			new TreeMap<>(adapter.fields()).forEach((fieldId, kind) -> {
				buf.writeUtf(fieldId, MAX_ID_CHARS);
				buf.writeVarInt(kind.ordinal());
			});
		});
		buf.writeVarInt(authorization.childBlack().size());
		new TreeSet<>(authorization.childBlack()).forEach(ref -> writeRef(buf, ref));
		buf.writeBoolean(grant.trackingKind() != null);
		if (grant.trackingKind() != null) buf.writeVarInt(grant.trackingKind().wireId());
	}

	public static MarkerContentGrant read(FriendlyByteBuf buf) {
		int adapters = bounded(StrictPacketCodec.readVarInt(buf), MAX_ADAPTERS);
		Map<String, PresentationGrant.AdapterGrant> grants = new LinkedHashMap<>();
		String previousAdapter = null;
		int totalFields = 0;
		for (int i = 0; i < adapters; i++) {
			String adapterId = readUtf(buf, MAX_ID_CHARS);
			PresentationIds.validate(adapterId);
			if (previousAdapter != null && previousAdapter.compareTo(adapterId) >= 0)
				throw new IllegalArgumentException("non-canonical adapter grant order");
			previousAdapter = adapterId;
			int schema = StrictPacketCodec.readVarInt(buf);
			if (schema < 1 || schema > 255) throw new IllegalArgumentException("adapter grant schema");
			int fields = bounded(StrictPacketCodec.readVarInt(buf), MAX_FIELDS_PER_ADAPTER);
			totalFields += fields;
			if (totalFields > MAX_TOTAL_FIELDS) throw new IllegalArgumentException("total grant fields");
			Map<String, PresentationField.Kind> kinds = new LinkedHashMap<>();
			String previousField = null;
			for (int f = 0; f < fields; f++) {
				String fieldId = readUtf(buf, MAX_ID_CHARS);
				PresentationIds.validate(fieldId);
				if (previousField != null && previousField.compareTo(fieldId) >= 0)
					throw new IllegalArgumentException("non-canonical field grant order");
				previousField = fieldId;
				int kind = StrictPacketCodec.readVarInt(buf);
				if (kind < 0 || kind >= PresentationField.Kind.values().length)
					throw new IllegalArgumentException("field grant kind");
				kinds.put(fieldId, PresentationField.Kind.values()[kind]);
			}
			grants.put(adapterId, new PresentationGrant.AdapterGrant(schema, kinds));
		}
		int children = bounded(StrictPacketCodec.readVarInt(buf), MAX_CHILD_REFS);
		java.util.Set<PresentationPropertyRef> childBlack = new TreeSet<>();
		PresentationPropertyRef previous = null;
		for (int i = 0; i < children; i++) {
			PresentationPropertyRef ref = readRef(buf);
			if (ref.isRoot() || previous != null && previous.compareTo(ref) >= 0)
				throw new IllegalArgumentException("non-canonical child grant reference");
			previous = ref;
			childBlack.add(ref);
		}
		boolean tracked = StrictPacketCodec.readBoolean(buf);
		InventoryResourceKind trackingKind = tracked
			? InventoryResourceKind.fromWireId(StrictPacketCodec.readVarInt(buf)) : null;
		return new MarkerContentGrant(new PresentationGrant(grants, childBlack), trackingKind);
	}

	public static int encodedSize(MarkerContentGrant grant) {
		return encodedSize(grant.authorization(), grant.trackingKind());
	}

	public static int encodedSize(PresentationGrant authorization,
		@Nullable InventoryResourceKind trackingKind) {
		validateBounds(authorization);
		return encodedSizeUnchecked(authorization, trackingKind);
	}

	@FunctionalInterface
	public interface FieldFilter {
		boolean includes(String adapterId, String fieldId, PresentationField.Kind kind);
	}

	/**
	 * Exact size of the subset a caller would keep under the supplied projection
	 * predicates. It traverses the existing maps and sets without copying or
	 * sorting them, so staging can reserve before constructing the wire grant.
	 */
	public static int projectedEncodedSize(PresentationGrant authorization,
		@Nullable InventoryResourceKind trackingKind,
		Predicate<String> adapterFilter,
		FieldFilter fieldFilter,
		Predicate<PresentationPropertyRef> childFilter) {
		int adapterCount = 0;
		int adapterBytes = 0;
		for (var adapter : authorization.adapters().entrySet()) {
			if (!adapterFilter.test(adapter.getKey())) continue;
			int fieldCount = 0;
			int fieldBytes = 0;
			for (var field : adapter.getValue().fields().entrySet()) {
				if (!fieldFilter.includes(adapter.getKey(), field.getKey(), field.getValue())) continue;
				fieldCount++;
				fieldBytes += utfBytes(field.getKey(), MAX_ID_CHARS) + varIntBytes(field.getValue().ordinal());
			}
			if (fieldCount == 0) continue;
			adapterCount++;
			adapterBytes += utfBytes(adapter.getKey(), MAX_ID_CHARS)
				+ varIntBytes(adapter.getValue().schema()) + varIntBytes(fieldCount) + fieldBytes;
		}
		int size = varIntBytes(adapterCount) + adapterBytes;
		int childCount = 0;
		int childBytes = 0;
		for (PresentationPropertyRef ref : authorization.childBlack()) {
			if (!childFilter.test(ref)) continue;
			childCount++;
			childBytes += refBytes(ref);
		}
		size += varIntBytes(childCount) + childBytes + 1;
		if (trackingKind != null) size += varIntBytes(trackingKind.wireId());
		return size;
	}

	private static int encodedSizeUnchecked(PresentationGrant authorization,
		@Nullable InventoryResourceKind trackingKind) {
		int size = varIntBytes(authorization.adapters().size());
		for (var adapter : authorization.adapters().entrySet()) {
			size += utfBytes(adapter.getKey(), MAX_ID_CHARS) + varIntBytes(adapter.getValue().schema())
				+ varIntBytes(adapter.getValue().fields().size());
			for (var field : adapter.getValue().fields().entrySet())
				size += utfBytes(field.getKey(), MAX_ID_CHARS) + varIntBytes(field.getValue().ordinal());
		}
		size += varIntBytes(authorization.childBlack().size());
		for (PresentationPropertyRef ref : authorization.childBlack()) size += refBytes(ref);
		size += 1;
		if (trackingKind != null) size += varIntBytes(trackingKind.wireId());
		return size;
	}

	public static void validateBounds(PresentationGrant authorization) {
		if (authorization.adapters().size() > MAX_ADAPTERS)
			throw new IllegalArgumentException("grant adapter capacity");
		int total = 0;
		for (var adapter : authorization.adapters().values()) {
			if (adapter.schema() < 1 || adapter.schema() > 255)
				throw new IllegalArgumentException("grant adapter schema");
			if (adapter.fields().size() > MAX_FIELDS_PER_ADAPTER)
				throw new IllegalArgumentException("grant field capacity");
			total += adapter.fields().size();
		}
		if (total > MAX_TOTAL_FIELDS) throw new IllegalArgumentException("total grant field capacity");
		if (authorization.childBlack().size() > MAX_CHILD_REFS)
			throw new IllegalArgumentException("grant child capacity");
	}

	private static void writeRef(FriendlyByteBuf buf, PresentationPropertyRef ref) {
		buf.writeUtf(ref.adapterId(), MAX_ID_CHARS);
		buf.writeUtf(ref.fieldId(), MAX_ID_CHARS);
		buf.writeVarInt(ref.recordPath().size());
		for (String key : ref.recordPath()) buf.writeUtf(key, MAX_KEY_CHARS);
	}

	private static PresentationPropertyRef readRef(FriendlyByteBuf buf) {
		String adapter = readUtf(buf, MAX_ID_CHARS);
		String field = readUtf(buf, MAX_ID_CHARS);
		int depth = StrictPacketCodec.readVarInt(buf);
		if (depth < 0 || depth > nx.pingwheel.common.presentation.PresentationLimits.MAX_DEPTH)
			throw new IllegalArgumentException("grant reference depth");
		List<String> path = new ArrayList<>(depth);
		for (int i = 0; i < depth; i++) path.add(readUtf(buf, MAX_KEY_CHARS));
		return new PresentationPropertyRef(adapter, field, path);
	}

	/**
	 * Canonical UTF-8, matching the bounded character grammar while rejecting
	 * malformed bytes instead of allowing replacement-character substitution.
	 */
	private static String readUtf(FriendlyByteBuf buf, int maxChars) {
		int length = StrictPacketCodec.readVarInt(buf);
		if (length < 0 || length > maxChars * 3 || length > buf.readableBytes())
			throw new IllegalArgumentException("grant UTF length");
		byte[] bytes = new byte[length];
		buf.readBytes(bytes);
		final String value;
		try {
			value = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException malformed) {
			throw new IllegalArgumentException("grant UTF bytes", malformed);
		}
		if (value.length() > maxChars) throw new IllegalArgumentException("grant UTF size");
		return value;
	}

	private static int refBytes(PresentationPropertyRef ref) {
		int size = utfBytes(ref.adapterId(), MAX_ID_CHARS) + utfBytes(ref.fieldId(), MAX_ID_CHARS)
			+ varIntBytes(ref.recordPath().size());
		for (String key : ref.recordPath()) size += utfBytes(key, MAX_KEY_CHARS);
		return size;
	}

	private static int utfBytes(String value, int maxChars) {
		if (value == null || value.length() > maxChars) throw new IllegalArgumentException("grant UTF size");
		int bytes = io.netty.buffer.ByteBufUtil.utf8Bytes(value);
		return varIntBytes(bytes) + bytes;
	}

	private static int bounded(int value, int max) {
		if (value < 0 || value > max) throw new IllegalArgumentException("grant count");
		return value;
	}

	private static int varIntBytes(int value) {
		int size = 1;
		while ((value & ~0x7f) != 0) { value >>>= 7; size++; }
		return size;
	}
}
