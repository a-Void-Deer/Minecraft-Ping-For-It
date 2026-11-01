package nx.pingwheel.common.presentation.inventory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryFluidDisplayCodecTest {

	private static final String BASE = "{\"version\":1,\"id\":\"minecraft:water\"}";

	@Test void independentVersionedLiteralAndCanonicalWriterAgreeWithoutAnItemPayload() {
		var base = new InventoryFluidDisplayCodec.Projection("minecraft:water");
		assertEquals(BASE, InventoryFluidDisplayCodec.encode(base));
		assertEquals(base, InventoryFluidDisplayCodec.decodeStrict(BASE));
		var named = new InventoryFluidDisplayCodec.Projection("test:fluid/variant", "\"泉水\" \\ spring 🌊");
		String literal = "{\"name\":\"\\\"泉水\\\" \\\\ spring 🌊\",\"id\":\"test:fluid/variant\",\"version\":1}";
		assertEquals(named, InventoryFluidDisplayCodec.decodeStrict(literal));
		assertEquals(named, InventoryFluidDisplayCodec.decodeStrict(InventoryFluidDisplayCodec.encode(named)));
		assertEquals(named, InventoryFluidDisplayCodec.decode(named.fluidId(), literal).orElseThrow());
	}

	@Test void clientDecodeFencesTheExpectedFluidAndKeepsUnavailableDisplayUnavailable() {
		assertTrue(InventoryFluidDisplayCodec.decode("minecraft:water", null).isEmpty());
		assertTrue(InventoryFluidDisplayCodec.decode("minecraft:water", "").isEmpty());
		assertTrue(InventoryFluidDisplayCodec.decode("minecraft:lava", BASE).isEmpty());
		assertTrue(InventoryFluidDisplayCodec.decode("not an id", BASE).isEmpty());
		assertEquals("minecraft:water", InventoryFluidDisplayCodec.decode("minecraft:water", BASE).orElseThrow().fluidId());
		// Registry existence belongs to the client resolver, not this common native-free grammar.
		assertTrue(InventoryFluidDisplayCodec.decode("absent:fluid", "{\"version\":1,\"id\":\"absent:fluid\"}").isPresent());
	}

	@Test void unknownVersionsWrongTypesMissingFieldsAndDuplicateFieldsRefuse() {
		for (String json : List.of("{}", "null", "[]", "\"text\"", "{\"id\":\"minecraft:water\"}", "{\"version\":1}",
			BASE.replace(":1", ":2"), BASE.replace(":1", ":\"1\""), BASE.replace(":1", ":1.0"),
			BASE.replace(":1", ":1e0"), BASE.replace(":1", ":true"), BASE.replace(":1", ":null"),
			"{\"version\":1,\"id\":5}", "{\"version\":1,\"id\":null}", "{\"version\":1,\"id\":[]}",
			append("\"name\":null"), append("\"name\":7"), append("\"name\":{}"), append("\"name\":[]"),
			append("\"version\":1"), append("\"id\":\"minecraft:water\""), append("\"\\u0069d\":\"minecraft:water\""),
			append("\"name\":\"a\",\"name\":\"b\""))) refused(json);
	}

	@Test void nativeComponentsNbtRichTextAndIconInstructionsAreNotAClientDisplayCapability() {
		for (String field : List.of("components", "tag", "nbt", "texture", "tint", "amount", "label"))
			refused(append("\"" + field + "\":{}"));
		refused(append("\"name\":{\"text\":\"click\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op x\"}}"));
		String literalDocument = "{\"text\":\"not a component\"}";
		var projection = new InventoryFluidDisplayCodec.Projection("minecraft:water", literalDocument);
		assertEquals(literalDocument, InventoryFluidDisplayCodec.decodeStrict(InventoryFluidDisplayCodec.encode(projection)).customName());
		// A string that resembles JSON remains literal name text, never a second parse entrance.
		refused("{\"id\":\"minecraft:water\",\"components\":{}}");
	}

	@Test void malformedLenientTrailingAndDeepInputRefusesWithoutMaterializingATree() {
		for (int i = 0; i < BASE.length(); i++) refused(BASE.substring(0, i));
		for (String json : List.of(BASE + "{}", BASE + " true", BASE + "x", BASE.substring(0, BASE.length() - 1),
			BASE.replace("\"version\"", "version"), BASE.replace('"', '\''), "/*comment*/" + BASE,
			BASE.replace(":1", ":01"), append(""), append("\"name\":\"\\q\""), "[".repeat(1500) + "0" + "]".repeat(1500),
			append("\"components\":" + "[".repeat(1000) + "0" + "]".repeat(1000)))) refused(json);
		assertEquals(new InventoryFluidDisplayCodec.Projection("minecraft:water"), InventoryFluidDisplayCodec.decodeStrict(" \n" + BASE + "\t "));
	}

	@Test void registryIdsAreCompleteBoundedAndSyntacticallyFluidIds() {
		for (String id : List.of("water", ":water", "minecraft:", "Minecraft:water", "minecraft:water:other", "test:bad space", "te/st:water")) {
			assertThrows(IllegalArgumentException.class, () -> new InventoryFluidDisplayCodec.Projection(id));
			refused(BASE.replace("minecraft:water", id));
		}
		String largest = "t:" + "x".repeat(254);
		assertEquals(largest, InventoryFluidDisplayCodec.decodeStrict(InventoryFluidDisplayCodec.encode(new InventoryFluidDisplayCodec.Projection(largest))).fluidId());
		assertThrows(IllegalArgumentException.class, () -> new InventoryFluidDisplayCodec.Projection(largest + "x"));
	}

	@Test void nameLimitsAreUtf8ByteLimitsAndEscapedMalformedUtfDoesNotSlipThrough() {
		String maximum = "é".repeat(InventoryFluidDisplayCodec.MAX_NAME_BYTES / 2);
		var projection = new InventoryFluidDisplayCodec.Projection("minecraft:water", maximum);
		assertEquals(projection, InventoryFluidDisplayCodec.decodeStrict(InventoryFluidDisplayCodec.encode(projection)));
		assertThrows(IllegalArgumentException.class, () -> new InventoryFluidDisplayCodec.Projection("minecraft:water", maximum + "a"));
		refused(append("\"name\":\"" + maximum + "a\""));
		for (String name : List.of("", "   ", "a\n", "a\r", "a\t", "a\u0000", "a\u007f", "a\u00a7b", "a\u2028b", "a\u2029b", "\ud800", "\udc00"))
			assertThrows(IllegalArgumentException.class, () -> new InventoryFluidDisplayCodec.Projection("minecraft:water", name));
		for (String escaped : List.of("\\ud800", "\\udc00", "\\ud800x", "\\n", "\\u0000", "\\u00a7")) refused(append("\"name\":\"" + escaped + "\""));
		refused(append("\"name\":\"\ud800\""));
		assertEquals("🌊", InventoryFluidDisplayCodec.decodeStrict(append("\"name\":\"\\ud83c\\udf0a\"")).customName());
	}

	@Test void completeJsonIsByteBoundedBeforeStreamingIncludingWhitespaceAndEscapes() {
		String json = InventoryFluidDisplayCodec.encode(new InventoryFluidDisplayCodec.Projection("minecraft:water", "é".repeat(512)));
		String maximum = json + " ".repeat(InventoryFluidDisplayCodec.MAX_BYTES - json.getBytes(StandardCharsets.UTF_8).length);
		assertTrue(maximum.length() < InventoryFluidDisplayCodec.MAX_BYTES);
		assertEquals(InventoryFluidDisplayCodec.MAX_BYTES, maximum.getBytes(StandardCharsets.UTF_8).length);
		assertEquals(InventoryFluidDisplayCodec.decodeStrict(json), InventoryFluidDisplayCodec.decodeStrict(maximum));
		refused(maximum + " ");
		refused(append("\"name\":\"" + "\\u0061".repeat(1024) + "\""));
	}

	@Test void displayLossOrCoincidentDisplayNeverChangesNativeIdentityExactAmountOrSelection() {
		var catalog = new InventoryFluidCodec.Catalog();
		var identity = InventoryFluidCodec.Identity.exact(new byte[] {10, 0, 1});
		var base = catalog.intern("minecraft:water", 9007199254740993L, identity,
			new InventoryFluidValue.Display("minecraft:water", "block.minecraft.water", BASE));
		var other = catalog.intern("minecraft:water", base.amount(), InventoryFluidCodec.Identity.exact(new byte[] {10, 0, 2}), base.display());
		assertNotEquals(base.key(), other.key());
		for (var display : List.of(base.display(), new InventoryFluidValue.Display("minecraft:water", "block.minecraft.water", null),
			new InventoryFluidValue.Display("minecraft:water", "block.minecraft.water", "{\"id\":\"minecraft:water\"}"),
			new InventoryFluidValue.Display("minecraft:water", "block.minecraft.water", BASE.replace("water", "lava")))) {
			var value = catalog.intern("minecraft:water", base.amount(), identity, display);
			@Nullable InventoryFluidValue decoded = InventoryFluidCodec.decode(InventoryFluidCodec.encode(value));
			assertNotNull(decoded); assertEquals(base.key(), decoded.key()); assertEquals(base.amount(), decoded.amount()); assertFalse(decoded.aggregate());
			var selection = new InventorySelection(InventoryResourceKind.FLUID, decoded.key(), decoded.resourceId(), decoded.label(), decoded.displayJson(), false, "attention");
			assertEquals(base.key(), selection.exact()); assertFalse(selection.aggregate());
			assertEquals(BASE.equals(display.displayJson()), InventoryFluidDisplayCodec.decode(decoded.resourceId(), decoded.displayJson()).isPresent());
		}
	}

	private static String append(String fields) { return BASE.substring(0, BASE.length() - 1) + "," + fields + "}"; }
	private static void refused(String json) {
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidDisplayCodec.decodeStrict(json), json);
		assertTrue(InventoryFluidDisplayCodec.decode("minecraft:water", json).isEmpty(), json);
	}
}
