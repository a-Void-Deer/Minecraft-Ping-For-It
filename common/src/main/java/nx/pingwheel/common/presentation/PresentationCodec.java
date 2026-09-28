package nx.pingwheel.common.presentation;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Bounded, independently framed fields. A denied field never enters typed
 * decoding. Every top-level field value and every addressable record entry is
 * followed by a nullable property annotation; entries inside a sequence carry
 * no annotation because a sequence item has no addressable path.
 *
 * <p>Encoding uses deterministic field-ID and record-key order, never map
 * iteration order. Field, section, metadata, trailing-byte, duplicate and
 * value-type validation all remain part of this single codec.
 */
public final class PresentationCodec {
	public static final int MAX_FIELDS = PresentationLimits.MAX_FIELDS;
	public static final int MAX_ENTRIES = PresentationLimits.MAX_ENTRIES;
	public static final int MAX_DEPTH = PresentationLimits.MAX_DEPTH;
	public static final int MAX_TEXT_BYTES = PresentationLimits.MAX_TEXT_BYTES;
	public static final int MAX_FIELD_BYTES = PresentationLimits.MAX_FIELD_BYTES;
	public static final int MAX_SECTION_BYTES = PresentationLimits.MAX_SECTION_BYTES;
	/** At most one bounded property intent per property reference. */
	public static final int MAX_PROPERTIES = PresentationLimits.MAX_FIELDS;
	public static final int MAX_PING_TYPE_LENGTH = PresentationPropertyPingTypes.MAX_PING_TYPE_LENGTH;
	private static final int MAX_ID_LENGTH = 193;

	private PresentationCodec() {}

	public static void validate(PresentationValue value) { PresentationLimits.validate(value); }

	public static void write(FriendlyByteBuf buf, PresentationSection section) {
		FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer(256, MAX_SECTION_BYTES));
		try {
			frame.writeUtf(section.adapterId(), MAX_ID_LENGTH);
			frame.writeVarInt(section.schema());
			frame.writeBoolean(section.stale());
			frame.writeVarInt(section.fields().size());
			for (var entry : new TreeMap<>(section.fields()).entrySet()) {
				FriendlyByteBuf field = new FriendlyByteBuf(Unpooled.buffer(64, MAX_FIELD_BYTES));
				try {
					writeAnnotatedValue(field, entry.getValue(), section.adapterId(), entry.getKey(), List.of(),
						section.annotations(), false, true);
					if (field.readableBytes() > MAX_FIELD_BYTES) throw new IllegalArgumentException("field byte limit");
					frame.writeUtf(entry.getKey(), MAX_ID_LENGTH);
					frame.writeVarInt(field.readableBytes());
					frame.writeBytes(field);
				} finally { field.release(); }
			}
			if (frame.readableBytes() > MAX_SECTION_BYTES) throw new IllegalArgumentException("section byte limit");
			buf.writeVarInt(frame.readableBytes());
			buf.writeBytes(frame);
		} finally { frame.release(); }
	}

	/**
	 * Returns a section guaranteed to encode within the existing field and section byte
	 * bounds: the section itself when {@link #write} accepts it, otherwise an empty stale
	 * section for the same adapter and schema. Semantic validation bounds text, entries
	 * and depth but not the encoded field size, so a bounded publication path must
	 * degrade instead of letting the codec exception escape.
	 */
	public static PresentationSection bounded(PresentationSection section) {
		FriendlyByteBuf probe = new FriendlyByteBuf(Unpooled.buffer(256, MAX_SECTION_BYTES + 5));
		try {
			write(probe, section);
			return section;
		} catch (RuntimeException notEncodable) {
			return new PresentationSection(section.adapterId(), section.schema(), Map.of(), true);
		} finally { probe.release(); }
	}

	public static PresentationSection read(FriendlyByteBuf buf, Predicate<String> receive) {
		int size = buf.readVarInt();
		if (size < 0 || size > MAX_SECTION_BYTES || size > buf.readableBytes()) throw new IllegalArgumentException("section frame");
		FriendlyByteBuf frame = new FriendlyByteBuf(buf.readSlice(size));
		String adapter = frame.readUtf(MAX_ID_LENGTH);
		int schema = frame.readVarInt();
		boolean stale = frame.readBoolean();
		int count = frame.readVarInt();
		if (count < 0 || count > MAX_FIELDS) throw new IllegalArgumentException("field count");
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		Map<PresentationPropertyRef, String> annotations = new LinkedHashMap<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 0; i < count; i++) {
			String id = frame.readUtf(MAX_ID_LENGTH);
			PresentationIds.validate(id);
			if (!seen.add(id)) throw new IllegalArgumentException("duplicate field");
			int bytes = frame.readVarInt();
			if (bytes < 0 || bytes > MAX_FIELD_BYTES || bytes > frame.readableBytes())
				throw new IllegalArgumentException("field frame");
			if (!receive.test(id)) { frame.skipBytes(bytes); continue; }
			FriendlyByteBuf field = new FriendlyByteBuf(frame.readSlice(bytes));
			PresentationValue value = readAnnotatedValue(field, 0, adapter, id, List.of(), annotations, false, true);
			if (field.isReadable()) throw new IllegalArgumentException("field trailing bytes");
			fields.put(id, value);
		}
		if (frame.isReadable()) throw new IllegalArgumentException("section trailing bytes");
		return new PresentationSection(adapter, schema, fields, stale, annotations);
	}

	/** Bounded value encoding without property annotations, shared with intents. */
	public static void writeValue(FriendlyByteBuf buf, PresentationValue value) {
		writeAnnotatedValue(buf, value, null, null, List.of(), null, true, true);
	}

	/** Bounded value decoding without property annotations, shared with intents. */
	public static PresentationValue readValue(FriendlyByteBuf buf) {
		return readAnnotatedValue(buf, 0, null, null, List.of(), null, true, true);
	}

	public static void writePropertyRef(FriendlyByteBuf buf, PresentationPropertyRef ref) {
		buf.writeUtf(ref.adapterId(), MAX_ID_LENGTH);
		buf.writeUtf(ref.fieldId(), MAX_ID_LENGTH);
		buf.writeVarInt(ref.recordPath().size());
		for (String key : ref.recordPath()) buf.writeUtf(key, PresentationPropertyRef.MAX_KEY_BYTES);
	}

	public static PresentationPropertyRef readPropertyRef(FriendlyByteBuf buf) {
		String adapter = buf.readUtf(MAX_ID_LENGTH);
		String field = buf.readUtf(MAX_ID_LENGTH);
		int depth = buf.readVarInt();
		if (depth < 0 || depth > MAX_DEPTH) throw new IllegalArgumentException("property record path depth");
		List<String> path = new ArrayList<>(depth);
		for (int i = 0; i < depth; i++) path.add(buf.readUtf(PresentationPropertyRef.MAX_KEY_BYTES));
		return new PresentationPropertyRef(adapter, field, path);
	}

	public static void writePropertyIntent(FriendlyByteBuf buf, PresentationPropertyIntent intent) {
		FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer(64, MAX_FIELD_BYTES));
		try {
			writePropertyRef(frame, intent.ref());
			writeValue(frame, intent.observedValue());
			frame.writeBoolean(intent.pingTypeId() != null);
			if (intent.pingTypeId() != null) frame.writeUtf(intent.pingTypeId(), MAX_PING_TYPE_LENGTH);
			if (frame.readableBytes() > MAX_FIELD_BYTES) throw new IllegalArgumentException("property intent byte limit");
			buf.writeVarInt(frame.readableBytes());
			buf.writeBytes(frame);
		} finally { frame.release(); }
	}

	public static PresentationPropertyIntent readPropertyIntent(FriendlyByteBuf buf) {
		int bytes = buf.readVarInt();
		if (bytes < 0 || bytes > MAX_FIELD_BYTES || bytes > buf.readableBytes())
			throw new IllegalArgumentException("property intent frame");
		FriendlyByteBuf frame = new FriendlyByteBuf(buf.readSlice(bytes));
		PresentationPropertyRef ref = readPropertyRef(frame);
		PresentationValue value = readValue(frame);
		if (!frame.isReadable()) throw new IllegalArgumentException("property intent missing annotation flag");
		String pingType = frame.readBoolean() ? frame.readUtf(MAX_PING_TYPE_LENGTH) : null;
		if (frame.isReadable()) throw new IllegalArgumentException("property intent trailing bytes");
		return new PresentationPropertyIntent(ref, value, pingType);
	}

	private static void writeAnnotatedValue(FriendlyByteBuf buf, PresentationValue value, String adapterId,
		String fieldId, List<String> path, Map<PresentationPropertyRef, String> annotations, boolean insideSequence,
		boolean addressable) {
		validate(value);
		switch (value) {
			case PresentationValue.Text text -> { buf.writeByte(1); buf.writeUtf(text.value(), MAX_TEXT_BYTES); }
			case PresentationValue.NumberValue number -> { buf.writeByte(2); buf.writeDouble(number.value()); }
			case PresentationValue.Flag flag -> { buf.writeByte(3); buf.writeBoolean(flag.value()); }
			case PresentationValue.Sequence list -> {
				buf.writeByte(4); buf.writeVarInt(list.values().size());
				for (PresentationValue child : list.values())
					writeAnnotatedValue(buf, child, adapterId, fieldId, path, annotations, true, addressable);
			}
			case PresentationValue.RecordValue record -> {
				buf.writeByte(5); buf.writeVarInt(record.values().size());
				for (var entry : new TreeMap<>(record.values()).entrySet()) {
					buf.writeUtf(entry.getKey(), PresentationPropertyRef.MAX_KEY_BYTES);
					boolean childAddressable = addressable && PresentationPropertyRef.isAddressableKey(entry.getKey());
					writeAnnotatedValue(buf, entry.getValue(), adapterId, fieldId, appended(path, entry.getKey()),
						annotations, insideSequence, childAddressable);
				}
			}
		}
		if (annotations != null && !insideSequence) {
			// A blank, oversized, or sequence-only path is never addressable: the
			// nullable annotation stays false and no property ref is constructed.
			String annotation = addressable
				? annotations.get(new PresentationPropertyRef(adapterId, fieldId, path))
				: null;
			buf.writeBoolean(annotation != null);
			if (annotation != null) buf.writeUtf(annotation, MAX_PING_TYPE_LENGTH);
		}
	}

	private static PresentationValue readAnnotatedValue(FriendlyByteBuf buf, int depth, String adapterId,
		String fieldId, List<String> path, Map<PresentationPropertyRef, String> annotations, boolean insideSequence,
		boolean addressable) {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("presentation depth");
		PresentationValue value = switch (buf.readUnsignedByte()) {
			case 1 -> new PresentationValue.Text(buf.readUtf(MAX_TEXT_BYTES));
			case 2 -> new PresentationValue.NumberValue(buf.readDouble());
			case 3 -> new PresentationValue.Flag(buf.readBoolean());
			case 4 -> {
				int count = count(buf);
				var values = new ArrayList<PresentationValue>(count);
				for (int i = 0; i < count; i++)
					values.add(readAnnotatedValue(buf, depth + 1, adapterId, fieldId, path, annotations, true, addressable));
				yield new PresentationValue.Sequence(values);
			}
			case 5 -> {
				int count = count(buf);
				var values = new LinkedHashMap<String, PresentationValue>();
				for (int i = 0; i < count; i++) {
					String key = buf.readUtf(PresentationPropertyRef.MAX_KEY_BYTES);
					boolean childAddressable = addressable && PresentationPropertyRef.isAddressableKey(key);
					if (values.putIfAbsent(key, readAnnotatedValue(buf, depth + 1, adapterId, fieldId,
						appended(path, key), annotations, insideSequence, childAddressable)) != null)
						throw new IllegalArgumentException("duplicate map key");
				}
				yield new PresentationValue.RecordValue(values);
			}
			default -> throw new IllegalArgumentException("unknown presentation value tag");
		};
		if (annotations != null && !insideSequence && buf.readBoolean()) {
			if (!addressable) throw new IllegalArgumentException("annotation on an unaddressable path");
			annotations.put(new PresentationPropertyRef(adapterId, fieldId, path),
				buf.readUtf(MAX_PING_TYPE_LENGTH));
		}
		return value;
	}

	private static List<String> appended(List<String> path, String key) {
		List<String> next = new ArrayList<>(path.size() + 1);
		next.addAll(path);
		next.add(key);
		return next;
	}

	private static int count(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("entry count");
		return count;
	}
}
