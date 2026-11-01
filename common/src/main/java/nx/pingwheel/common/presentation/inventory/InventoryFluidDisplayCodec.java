package nx.pingwheel.common.presentation.inventory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Registry-only fluid icon and optional plain custom-name projection, independent of native identity.
 * The closed JSON grammar is {@code {"version":1,"id":"namespace:fluid","name":"plain text"}};
 * {@code name} is optional. There are no native components, NBT, text-component JSON, texture locations
 * or variant tint fields. The client resolves the base fluid through its own registry/render facilities;
 * this projection does not promise native variant-specific names or icons.
 */
@NotNullByDefault
public final class InventoryFluidDisplayCodec {

	public static final int VERSION = 1;
	public static final int MAX_BYTES = 4096;
	public static final int MAX_NAME_BYTES = 1024;

	private InventoryFluidDisplayCodec() {}

	/**
	 * Immutable display data only. A custom name is already reduced to bounded literal text by the
	 * sender, never a translation key or a component/NBT document for the client to interpret. Unsupported
	 * native naming data may be omitted while its complete native identity remains unchanged.
	 */
	@NotNullByDefault
	public record Projection(String fluidId, @Nullable String customName) {
		public Projection(String fluidId) { this(fluidId, null); }
		public Projection {
			InventoryFluidCodec.requireId(fluidId);
			if (customName != null) {
				InventoryFluidCodec.requireText(customName, MAX_NAME_BYTES);
				if (customName.isBlank()) throw new IllegalArgumentException("blank fluid display name");
				for (int i = 0; i < customName.length(); i++) {
					char c = customName.charAt(i);
					if (Character.isISOControl(c) || c == '\u00a7' || c == '\u2028' || c == '\u2029')
						throw new IllegalArgumentException("non-plain fluid display name");
				}
			}
		}
	}

	/** Canonical version-one JSON, still subject to the sender's effective per-entry display budget. */
	public static String encode(Projection projection) {
		Objects.requireNonNull(projection);
		var text = new StringWriter();
		try (var writer = new JsonWriter(text)) {
			writer.beginObject().name("version").value(VERSION).name("id").value(projection.fluidId());
			if (projection.customName() != null) writer.name("name").value(projection.customName());
			writer.endObject();
		} catch (IOException impossible) { throw new IllegalStateException(impossible); }
		String json = text.toString();
		InventoryFluidCodec.requireText(json, MAX_BYTES);
		return json;
	}

	/**
	 * Strict bounded decoding without registry lookup or native codecs. Unknown/duplicate fields, a
	 * non-integer version token, wrong types, malformed UTF, nested values and trailing input are refused.
	 * A fixed-depth streaming reader rejects unwanted trees without materializing or walking them.
	 */
	public static Projection decodeStrict(String json) {
		InventoryFluidCodec.requireText(json, MAX_BYTES);
		try (var reader = new JsonReader(new StringReader(json))) {
			reader.setLenient(false);
			requireToken(reader, JsonToken.BEGIN_OBJECT);
			reader.beginObject();
			int seen = 0;
			@Nullable String id = null;
			@Nullable String name = null;
			while (reader.hasNext()) {
				String field = reader.nextName();
				int bit = switch (field) {
					case "version" -> 1;
					case "id" -> 2;
					case "name" -> 4;
					default -> throw new IllegalArgumentException("unknown fluid display field");
				};
				if ((seen & bit) != 0) throw new IllegalArgumentException("duplicate fluid display field");
				seen |= bit;
				if (bit == 1) {
					requireToken(reader, JsonToken.NUMBER);
					if (!reader.nextString().equals("1")) throw new IllegalArgumentException("fluid display version");
				} else {
					requireToken(reader, JsonToken.STRING);
					String value = reader.nextString();
					if (bit == 2) id = value;
					else name = value;
				}
			}
			reader.endObject();
			requireToken(reader, JsonToken.END_DOCUMENT);
			if ((seen & 3) != 3 || id == null) throw new IllegalArgumentException("missing fluid display field");
			return new Projection(id, name);
		} catch (IOException | IllegalStateException malformed) {
			throw new IllegalArgumentException("malformed fluid display", malformed);
		}
	}

	/**
	 * Client entry point, fenced to the containing FLUID entry's resource ID. Empty means safe base-name
	 * fallback and no transmitted icon/name; it never changes the opaque key, amount, identity mode or
	 * selection. A successful result permits a local base-fluid icon, not an ItemStack or a native variant.
	 */
	public static Optional<Projection> decode(String expectedFluidId, @Nullable String json) {
		if (json == null) return Optional.empty();
		try {
			Projection projection = decodeStrict(json);
			return projection.fluidId().equals(expectedFluidId) ? Optional.of(projection) : Optional.empty();
		} catch (IllegalArgumentException malformed) { return Optional.empty(); }
	}

	private static void requireToken(JsonReader reader, JsonToken token) throws IOException {
		if (reader.peek() != token) throw new IllegalArgumentException("fluid display token");
	}
}
