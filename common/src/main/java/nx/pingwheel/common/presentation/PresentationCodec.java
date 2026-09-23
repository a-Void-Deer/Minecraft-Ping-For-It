package nx.pingwheel.common.presentation;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Bounded, independently framed fields. A denied field never enters typed decoding. */
public final class PresentationCodec {
	public static final int MAX_FIELDS = PresentationLimits.MAX_FIELDS;
	public static final int MAX_ENTRIES = PresentationLimits.MAX_ENTRIES;
	public static final int MAX_DEPTH = PresentationLimits.MAX_DEPTH;
	public static final int MAX_TEXT_BYTES = PresentationLimits.MAX_TEXT_BYTES;
	public static final int MAX_FIELD_BYTES = PresentationLimits.MAX_FIELD_BYTES;
	public static final int MAX_SECTION_BYTES = PresentationLimits.MAX_SECTION_BYTES;
	private PresentationCodec() {}

	public static void validate(PresentationValue value) { PresentationLimits.validate(value); }

	public static void write(FriendlyByteBuf buf, PresentationSection section) {
		FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer(256, MAX_SECTION_BYTES));
		try {
			frame.writeUtf(section.adapterId(), 193);
			frame.writeVarInt(section.schema());
			frame.writeBoolean(section.stale());
			frame.writeVarInt(section.fields().size());
			for (var entry : section.fields().entrySet()) {
				FriendlyByteBuf field = new FriendlyByteBuf(Unpooled.buffer(64, MAX_FIELD_BYTES));
				try {
					writeValue(field, entry.getValue());
					if (field.readableBytes() > MAX_FIELD_BYTES) throw new IllegalArgumentException("field byte limit");
					frame.writeUtf(entry.getKey(), 193);
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
		String adapter = frame.readUtf(193);
		int schema = frame.readVarInt();
		boolean stale = frame.readBoolean();
		int count = frame.readVarInt();
		if (count < 0 || count > MAX_FIELDS) throw new IllegalArgumentException("field count");
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 0; i < count; i++) {
			String id = frame.readUtf(193);
			PresentationIds.validate(id);
			if (!seen.add(id)) throw new IllegalArgumentException("duplicate field");
			int bytes = frame.readVarInt();
			if (bytes < 0 || bytes > MAX_FIELD_BYTES || bytes > frame.readableBytes())
				throw new IllegalArgumentException("field frame");
			if (!receive.test(id)) { frame.skipBytes(bytes); continue; }
			FriendlyByteBuf field = new FriendlyByteBuf(frame.readSlice(bytes));
			PresentationValue value = readValue(field, 0);
			if (field.isReadable()) throw new IllegalArgumentException("field trailing bytes");
			fields.put(id, value);
		}
		if (frame.isReadable()) throw new IllegalArgumentException("section trailing bytes");
		return new PresentationSection(adapter, schema, fields, stale);
	}

	private static void writeValue(FriendlyByteBuf buf, PresentationValue value) {
		validate(value);
		switch (value) {
			case PresentationValue.Text text -> { buf.writeByte(1); buf.writeUtf(text.value(), MAX_TEXT_BYTES); }
			case PresentationValue.NumberValue number -> { buf.writeByte(2); buf.writeDouble(number.value()); }
			case PresentationValue.Flag flag -> { buf.writeByte(3); buf.writeBoolean(flag.value()); }
			case PresentationValue.Sequence list -> {
				buf.writeByte(4); buf.writeVarInt(list.values().size());
				list.values().forEach(v -> writeValue(buf, v));
			}
			case PresentationValue.RecordValue record -> {
				buf.writeByte(5); buf.writeVarInt(record.values().size());
				record.values().forEach((key, v) -> { buf.writeUtf(key, 128); writeValue(buf, v); });
			}
		}
	}

	private static PresentationValue readValue(FriendlyByteBuf buf, int depth) {
		if (depth > MAX_DEPTH) throw new IllegalArgumentException("presentation depth");
		return switch (buf.readUnsignedByte()) {
			case 1 -> new PresentationValue.Text(buf.readUtf(MAX_TEXT_BYTES));
			case 2 -> new PresentationValue.NumberValue(buf.readDouble());
			case 3 -> new PresentationValue.Flag(buf.readBoolean());
			case 4 -> {
				int count = count(buf);
				var values = new ArrayList<PresentationValue>(count);
				for (int i = 0; i < count; i++) values.add(readValue(buf, depth + 1));
				yield new PresentationValue.Sequence(values);
			}
			case 5 -> {
				int count = count(buf);
				var values = new LinkedHashMap<String, PresentationValue>();
				for (int i = 0; i < count; i++) {
					String key = buf.readUtf(128);
					if (values.putIfAbsent(key, readValue(buf, depth + 1)) != null)
						throw new IllegalArgumentException("duplicate map key");
				}
				yield new PresentationValue.RecordValue(values);
			}
			default -> throw new IllegalArgumentException("unknown presentation value tag");
		};
	}

	private static int count(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("entry count");
		return count;
	}
}
