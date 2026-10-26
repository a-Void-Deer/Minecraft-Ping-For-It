package nx.pingwheel.common.presentation.inventory.minecraft;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNullByDefault;

import nx.pingwheel.common.config.InventoryLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure projector tests: Gson parses only small output/oracle fixtures, never production input. */
@NotNullByDefault
class InventoryDisplayWriterTest {

	private static final String ID = "minecraft:stone";

	@Test
	void everyLegalCapFitsWithoutLosingRemovalMetadata() {
		JsonObject input = item();
		JsonObject components = input.getAsJsonObject("components");
		components.addProperty("minecraft:custom_name", "n".repeat(1500));
		components.addProperty("minecraft:damage", 7);
		components.add("!example:absent", new JsonObject());
		String tiny = InventoryDisplayWriter.project(input, ID, 1024);
		for (int cap = 1024; cap <= InventoryLimits.MAX_DISPLAY_BYTES; cap++) {
			String projected = InventoryDisplayWriter.reproject(tiny, ID, cap);
			assertNotNull(projected);
			assertTrue(wire(projected) <= cap);
			// Each cap exercises output; independent Gson semantics at transition
			// boundaries avoid parsing a tree 130,000 times for the same result.
			if (cap == 1024 || cap == 1700 || cap == InventoryLimits.MAX_DISPLAY_BYTES) {
				JsonObject result = object(projected);
				assertEquals(ID, result.get("id").getAsString());
				assertTrue(result.getAsJsonObject("components").has("!example:absent"));
				assertTrue(result.getAsJsonObject("components").has("!minecraft:lore"));
				assertEquals(7, result.getAsJsonObject("components").get("minecraft:damage").getAsInt());
			}
		}
		for (int cap : new int[] { 1024, 1500, 1700, 4096, InventoryLimits.MAX_DISPLAY_BYTES }) {
			String projected = InventoryDisplayWriter.project(input, ID, cap);
			assertNotNull(projected);
			assertTrue(wire(projected) <= cap);
			assertTrue(componentObject(projected).has("!example:absent"));
		}
		assertEquals("n".repeat(1500), components.get("minecraft:custom_name").getAsString(), "projection never mutates the input");
	}

	@Test
	void fullNoNameAndBareHaveIndependentSemanticExpectations() {
		JsonObject input = item();
		JsonObject components = input.getAsJsonObject("components");
		components.addProperty("minecraft:custom_name", "n".repeat(1400));
		components.addProperty("minecraft:item_name", "i".repeat(1400));
		components.addProperty("minecraft:custom_data", "d".repeat(1400));
		components.add("!example:already_removed", new JsonObject());
		JsonObject full = componentObject(InventoryDisplayWriter.project(input, ID, 8192));
		assertTrue(full.has("minecraft:custom_name"));
		JsonObject noNames = componentObject(InventoryDisplayWriter.project(input, ID, 2048));
		assertFalse(noNames.has("minecraft:custom_name"));
		assertFalse(noNames.has("minecraft:item_name"));
		assertTrue(noNames.has("!minecraft:custom_name"));
		assertTrue(noNames.has("!minecraft:item_name"));
		assertTrue(noNames.has("minecraft:custom_data"));
		JsonObject bare = componentObject(InventoryDisplayWriter.project(input, ID, 1024));
		assertFalse(bare.has("minecraft:custom_data"));
		for (String name : new String[] { "custom_name", "item_name", "custom_data", "lore" }) {
			assertEquals(new JsonObject(), bare.get("!minecraft:" + name));
		}
		assertTrue(bare.has("!example:already_removed"));
	}

	@Test
	void maximumOldInputTightensWithoutParsingAValueTree() {
		String prefix = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":\"";
		String suffix = "\",\"!example:gone\":{}}}";
		String input = prefix + "x".repeat(InventoryLimits.MAX_DISPLAY_BYTES - prefix.length() - suffix.length()) + suffix;
		assertEquals(InventoryLimits.MAX_DISPLAY_BYTES, wire(input));
		String tiny = InventoryDisplayWriter.reproject(input, ID, 1024);
		assertNotNull(tiny);
		assertTrue(wire(tiny) <= 1024);
		JsonObject components = componentObject(tiny);
		assertTrue(components.has("!minecraft:custom_data"));
		assertTrue(components.has("!example:gone"));
		String raised = InventoryDisplayWriter.reproject(tiny, ID, InventoryLimits.MAX_DISPLAY_BYTES);
		assertEquals(components, componentObject(raised), "raising cannot resurrect an omitted value");
		assertNull(InventoryDisplayWriter.reproject(input + " ", ID, 1024));
	}

	@Test
	void escapedRootAndComponentNamesAreRecognizedSemantically() {
		String input = "{\"\\u0069d\":\"minecraft:stone\",\"compo\\u006eents\":{"
			+ "\"minecraft:lo\\u0072e\":[\"secret\"],\"minecraft:custom_\\u006eame\":\"" + "n".repeat(2000)
			+ "\",\"\\u0021example:gone\":{}}}";
		JsonObject components = componentObject(InventoryDisplayWriter.reproject(input, ID, 1024));
		assertTrue(components.has("!minecraft:lore"));
		assertTrue(components.has("!minecraft:custom_name"));
		assertTrue(components.has("!example:gone"));
		assertFalse(components.has("minecraft:lore"));
		assertFalse(components.has("minecraft:custom_name"));
	}

	@Test
	void duplicatesAndAddRemoveConflictsAreUnavailableRegardlessOfSpelling() {
		for (String members : new String[] {
			"\"minecraft:damage\":1,\"minecraft:da\\u006dage\":2",
			"\"damage\":1,\"minecraft:damage\":2",
			"\"minecraft:damage\":1,\"!damage\":{}",
			"\"!example:a\":{},\"\\u0021example:a\":{}",
			"\"!minecraft:lore\":{},\"lore\":[]"
		}) assertNull(InventoryDisplayWriter.reproject(envelope(members), ID, 1024), members);
		assertNull(InventoryDisplayWriter.reproject("{\"id\":\"minecraft:stone\",\"\\u0069d\":\"minecraft:stone\"}", ID, 1024));
		assertNull(InventoryDisplayWriter.reproject("{\"id\":\"minecraft:stone\",\"components\":{},\"compo\\u006eents\":{}}", ID, 1024));
		JsonObject tree = item();
		tree.getAsJsonObject("components").addProperty("damage", 1);
		tree.getAsJsonObject("components").add("!minecraft:damage", new JsonObject());
		assertNull(InventoryDisplayWriter.project(tree, ID, 1024));
	}

	@Test
	void nestedValuesAreOpaqueValidatedSpansAndFlatStringsStayStrings() {
		String nested = "[{\"slot\":2,\"item\":{\"id\":\"minecraft:shulker_box\",\"count\":3,\"components\":{"
			+ "\"!minecraft:lore\":{},\"!minecraft:container\":{}}}},[null,true,false,-1.25e+3]]";
		String flat = "\"{not valid inner JSON [ \\\"\"";
		String input = envelope("\"minecraft:container\":" + nested + ",\"minecraft:custom_name\":" + flat);
		String output = InventoryDisplayWriter.reproject(input, ID, 4096);
		assertNotNull(output);
		assertTrue(output.contains(nested), "retained nested bytes are not reinterpreted or normalized");
		JsonObject components = componentObject(output);
		assertEquals(JsonParser.parseString(nested), components.get("minecraft:container"));
		assertEquals(JsonParser.parseString(flat), components.get("minecraft:custom_name"));
	}

	@Test
	void malformedNumbersEscapesAndDepthRefuseBeforeProjection() {
		for (String value : new String[] {
			"01", "+1", "-", "1.", ".1", "1e", "1e+", "NaN", "Infinity", "true false",
			"[1,]", "{\"a\":1,}", "{\"a\" 1}", "\"\\x\"", "\"\\u12xz\"", "\"\\u123\"", "\"raw\nline\""
		}) assertNull(InventoryDisplayWriter.reproject(envelope("\"minecraft:custom_data\":" + value), ID, 1024), value);
		assertNull(InventoryDisplayWriter.reproject("", ID, 1024));
		assertNull(InventoryDisplayWriter.reproject("{} trailing", ID, 1024));
		assertNull(InventoryDisplayWriter.reproject("{\"id\":\"minecraft:dirt\"}", ID, 1024));
		assertNotNull(InventoryDisplayWriter.reproject(envelope("\"minecraft:custom_data\":" + "[".repeat(46) + "0" + "]".repeat(46)), ID, 1024));
		assertNull(InventoryDisplayWriter.reproject(envelope("\"minecraft:custom_data\":" + "[".repeat(47) + "0" + "]".repeat(47)), ID, 1024));
	}

	@Test
	void rawAndEscapedSurrogatesMatchJavaWireReplacementAndNeverOvershoot() {
		for (String value : new String[] { "\ud83d\ude00", "\ud83d", "\ude00", "\ud83dX\ude00", "é漢字" }) {
			assertEquals(wire(value), InventoryDisplayWriter.utf8(value));
			JsonObject input = item();
			input.getAsJsonObject("components").addProperty("minecraft:custom_data", value.repeat(400));
			String result = InventoryDisplayWriter.project(input, ID, 1024);
			assertNotNull(result);
			assertTrue(wire(result) <= 1024);
			assertEquals(wire(result), InventoryDisplayWriter.utf8(result));
		}
		for (String escaped : new String[] { "\\ud83d\\ude00", "\\ud83d", "\\ude00", "\\ud83dX\\ude00" }) {
			String result = InventoryDisplayWriter.reproject(envelope("\"minecraft:custom_data\":\"" + escaped + "\""), ID, 1024);
			assertNotNull(result);
			assertEquals(JsonParser.parseString("\"" + escaped + "\""), componentObject(result).get("minecraft:custom_data"));
		}
		JsonObject exact = item();
		exact.getAsJsonObject("components").addProperty("minecraft:custom_data", "");
		int overhead = wire(InventoryDisplayWriter.project(exact, ID, 1024));
		String payload = "a".repeat(1024 - overhead - 4) + "\ud83d\ude00";
		exact.getAsJsonObject("components").addProperty("minecraft:custom_data", payload);
		String boundary = InventoryDisplayWriter.project(exact, ID, 1024);
		assertEquals(1024, wire(boundary));
		assertEquals(payload, componentObject(boundary).get("minecraft:custom_data").getAsString());
		exact.getAsJsonObject("components").addProperty("minecraft:custom_data", payload + "a");
		assertTrue(componentObject(InventoryDisplayWriter.project(exact, ID, 1024)).has("!minecraft:custom_data"));
	}

	@Test
	void suppressionSizeOverflowUsesCanonicalDefaultIconForFreshAndRetainedInput() {
		JsonObject input = item();
		JsonObject components = input.getAsJsonObject("components");
		components.add("!example:" + "a".repeat(1100), new JsonObject());
		assertEquals("{\"id\":\"minecraft:stone\"}", InventoryDisplayWriter.project(input, ID, 1024));
		String retained = InventoryDisplayWriter.project(input, ID, 4096);
		assertNotNull(retained);
		assertEquals("{\"id\":\"minecraft:stone\"}", InventoryDisplayWriter.reproject(retained, ID, 1024));
		assertTrue(componentObject(InventoryDisplayWriter.bare(ID)).has("!minecraft:lore"));
	}

	@Test
	void manyCommonPrefixRemovalsKeepTheirValuesAndDetectALateDuplicate() {
		JsonObject input = item();
		JsonObject components = input.getAsJsonObject("components");
		for (int i = 30; i >= 0; i--) components.add("!example:common_prefix_" + i, new JsonObject());
		String retained = InventoryDisplayWriter.project(input, ID, 4096);
		assertNotNull(retained);
		JsonObject projected = componentObject(InventoryDisplayWriter.reproject(retained, ID, 4096));
		for (int i = 0; i <= 30; i++) assertEquals(new JsonObject(), projected.get("!example:common_prefix_" + i));
		String duplicate = retained.substring(0, retained.length() - 2) + ",\"!example:common_prefix_\\u0030\":{}}}";
		assertNull(InventoryDisplayWriter.reproject(duplicate, ID, 4096));
	}

	@Test
	void maximumItemIdAndAllShulkerBareMarkersFitTheMinimum() {
		String maximum = "example:" + "a".repeat(248);
		assertEquals(256, wire(maximum));
		assertTrue(wire(InventoryDisplayWriter.bare(maximum)) <= 1024);
		for (String color : new String[] { "", "white_", "orange_", "magenta_", "light_blue_", "yellow_", "lime_", "pink_",
			"gray_", "light_gray_", "cyan_", "purple_", "blue_", "brown_", "green_", "red_", "black_" }) {
			String id = "minecraft:" + color + "shulker_box";
			String output = InventoryDisplayWriter.bare(id);
			assertTrue(wire(output) <= 1024);
			assertTrue(componentObject(output).has("!minecraft:lore"));
			assertTrue(componentObject(output).has("!minecraft:container"));
		}
	}

	@Test
	void unknownAdditionsNeverGainCodecAuthorityAndUnknownRemovalsSurvive() {
		assertNull(InventoryDisplayWriter.reproject(envelope("\"example:unknown\":{}"), ID, 1024));
		assertNotNull(InventoryDisplayWriter.reproject(envelope("\"!example:unknown\":{}"), ID, 1024));
		assertNull(InventoryDisplayWriter.reproject(envelope("\"!example:unknown\":null"), ID, 1024));
		assertNull(InventoryDisplayWriter.reproject(envelope("\"!example:unknown\":{\"x\":0}"), ID, 1024));
	}

	@Test
	void savedCountAndNativePrimitiveSpellingsSurviveTheWriter() {
		JsonObject input = item(); input.addProperty("count", 99);
		JsonObject data = new JsonObject();
		data.addProperty("minimum", Long.MIN_VALUE); data.addProperty("maximum", Long.MAX_VALUE);
		data.addProperty("float", -0.0F); data.addProperty("double", 1.25e100);
		input.getAsJsonObject("components").add("minecraft:custom_data", data);
		String output = InventoryDisplayWriter.project(input, ID, 1024);
		assertEquals(99, object(output).get("count").getAsInt());
		assertEquals(data, componentObject(output).get("minecraft:custom_data"));
		assertEquals(99, object(InventoryDisplayWriter.reproject(output, ID, 1024)).get("count").getAsInt());
		assertTrue(output.contains("-0.0"));
		data.addProperty("nonfinite", Double.NaN);
		assertNull(InventoryDisplayWriter.project(input, ID, 1024), "nonfinite data is invalid, not a size terminal");
	}

	@Test
	void terminalGrammarIsExactAndNeverRehydratesAfterCapChanges() {
		String maximum = "example:" + "a".repeat(248);
		JsonObject input = new JsonObject(); input.addProperty("id", maximum);
		JsonObject values = new JsonObject();
		for (int i = 0; i < 140; i++) values.add("!example:component_" + i, new JsonObject());
		input.add("components", values);
		String expected = "{\"id\":\"" + maximum + "\"}";
		String terminal = InventoryDisplayWriter.project(input, maximum, 1024);
		assertEquals(expected, terminal);
		assertEquals(265, wire(terminal), "maximum legal ID-only JSON fits below every cap");
		assertTrue(InventoryDisplayWriter.isDefaultIcon(maximum, terminal));
		assertEquals(terminal, InventoryDisplayWriter.reproject(terminal, maximum, 1024));
		assertEquals(terminal, InventoryDisplayWriter.reproject(terminal, maximum, InventoryLimits.MAX_DISPLAY_BYTES));
		assertFalse(InventoryDisplayWriter.isDefaultIcon(ID, terminal));
		assertFalse(InventoryDisplayWriter.isDefaultIcon(maximum, null));
		assertFalse(InventoryDisplayWriter.isDefaultIcon(ID, " {\"id\":\"minecraft:stone\"}"));
		assertFalse(InventoryDisplayWriter.isDefaultIcon(ID, "{\"id\":\"minecraft:stone\",\"components\":{}}"));
		assertFalse(InventoryDisplayWriter.isDefaultIcon(ID, "{\"\\u0069d\":\"minecraft:stone\"}"));
		assertFalse(InventoryDisplayWriter.isDefaultIcon("invalid", "{\"id\":\"invalid\"}"));
	}

	@Test
	void oversizeMembersNeverHideAnInvalidLateEntryOrValue() {
		JsonObject input = item(); JsonObject values = input.getAsJsonObject("components");
		for (int i = 0; i < 140; i++) values.add("!example:component_" + i, new JsonObject());
		values.add("example:unknown", new JsonObject());
		assertNull(InventoryDisplayWriter.project(input, ID, 1024));
		values.remove("example:unknown");
		values.addProperty("minecraft:custom_data", Double.NaN);
		assertNull(InventoryDisplayWriter.project(input, ID, 1024));
		assertNull(InventoryDisplayWriter.reproject(envelope("\"!example:" + "a".repeat(1100) + "\":{},\"example:unknown\":{}"), ID, 1024));
	}

	@Test
	void displayWorkRefusalNeverBecomesASizeTerminal() {
		JsonObject input = item();
		input.getAsJsonObject("components").add("!example:" + "a".repeat(1100), new JsonObject());
		var budget = InventoryItemBudget.root(1024); budget.begin(1024); budget.beginDisplay();
		try {
			budget.work(12L * 1024 * 1024);
			org.junit.jupiter.api.Assertions.assertThrows(InventoryItemBudget.RootLimitException.class,
				() -> InventoryDisplayWriter.project(input, ID, 1024, budget));
		} finally { budget.end(); }
	}

	@Test
	void freshAndRetainedLaddersPreservePreRedactedNestedItems() {
		JsonObject input = item();
		JsonObject components = input.getAsJsonObject("components");
		components.add("minecraft:bundle_contents", JsonParser.parseString("[{\"id\":\"minecraft:shulker_box\",\"components\":{\"!minecraft:container\":{},\"!minecraft:lore\":{}}}]"));
		components.add("minecraft:lore", JsonParser.parseString("[\"secret\"]"));
		String fresh = InventoryDisplayWriter.project(input, ID, 4096);
		String retained = InventoryDisplayWriter.reproject(fresh, ID, 1024);
		assertEquals(componentObject(fresh), componentObject(retained));
		assertFalse(retained.contains("secret"));
		JsonObject shulker = item(); shulker.addProperty("id", "minecraft:shulker_box");
		shulker.getAsJsonObject("components").add("minecraft:container", JsonParser.parseString("[{\"secret\":true}]"));
		JsonObject projected = componentObject(InventoryDisplayWriter.project(shulker, "minecraft:shulker_box", 1024));
		assertTrue(projected.has("!minecraft:container"));
		assertFalse(projected.has("minecraft:container"));
	}

	@Test
	void sparseLadderKeepsTombstonesAndOmitsUncarriedDefaultsAcrossConsecutiveTightening() {
		JsonObject input = item();
		JsonObject components = input.getAsJsonObject("components");
		components.addProperty("minecraft:custom_name", "n".repeat(1500));
		components.addProperty("minecraft:custom_data", "d".repeat(1500));
		components.addProperty("minecraft:damage", 7);
		components.add("!minecraft:max_damage", new JsonObject());

		String retained = InventoryDisplayWriter.project(input, ID, 4096);
		JsonObject full = componentObject(retained);
		assertEquals(7, full.get("minecraft:damage").getAsInt());
		assertTrue(full.has("minecraft:custom_name"));
		assertTrue(full.has("!minecraft:max_damage"));

		String middle = InventoryDisplayWriter.reproject(retained, ID, 2048);
		JsonObject middleComponents = componentObject(middle);
		assertTrue(middleComponents.has("minecraft:custom_data"));
		assertTrue(middleComponents.has("!minecraft:custom_name"));
		assertTrue(middleComponents.has("!minecraft:max_damage"));

		String tight = InventoryDisplayWriter.reproject(middle, ID, 1024);
		JsonObject tightComponents = componentObject(tight);
		for (String removal : new String[] { "custom_name", "custom_data", "damage", "max_damage", "lore" }) {
			assertTrue(tightComponents.has("!minecraft:" + removal), removal);
		}
		assertFalse(tightComponents.has("minecraft:custom_data"));
		assertFalse(tightComponents.has("minecraft:damage"));
		assertFalse(tightComponents.has("!minecraft:max_stack_size"),
			"an omitted ordinary default never gains an invented tombstone");
		assertTrue(wire(tight) <= wire(retained), "tightening never grows retained output");

		assertEquals(tight, InventoryDisplayWriter.reproject(tight, ID, 1024),
			"repeated tightening is stable");
		assertEquals(tight, InventoryDisplayWriter.reproject(tight, ID, 4096),
			"raising the ceiling never expands retained output");
	}

	@Test
	void tightCapBoundaryIsDerivedFromTheActualEncodedPayload() {
		String value = "x".repeat(1500);
		JsonObject input = item();
		input.getAsJsonObject("components").addProperty("minecraft:custom_data", value);
		String expected = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":\""
			+ value + "\",\"!minecraft:lore\":{}}}";
		int cap = wire(expected);
		assertTrue(cap > 1024 && cap <= InventoryLimits.MAX_DISPLAY_BYTES);

		JsonObject atCap = componentObject(InventoryDisplayWriter.project(input, ID, cap));
		assertEquals(value, atCap.get("minecraft:custom_data").getAsString());

		JsonObject below = componentObject(InventoryDisplayWriter.project(input, ID, cap - 1));
		assertFalse(below.has("minecraft:custom_data"));
		assertTrue(below.has("!minecraft:custom_data"));
		assertTrue(below.has("!minecraft:lore"));
	}

	@Test
	void nameSuppressionAlsoCoversUncarriedLocalDefaultNames() {
		JsonObject input = item();
		input.getAsJsonObject("components").addProperty("minecraft:custom_data", "d".repeat(1500));
		JsonObject components = componentObject(InventoryDisplayWriter.project(input, ID, 1024));
		assertFalse(components.has("minecraft:custom_name"));
		assertFalse(components.has("minecraft:item_name"));
		assertTrue(components.has("!minecraft:custom_name"));
		assertTrue(components.has("!minecraft:item_name"));
		assertTrue(components.has("!minecraft:custom_data"));
		assertTrue(components.has("!minecraft:lore"));
	}

	@Test void growthPreservesSurrogateStateEscapesAndExactUtf8Ceilings() {
		String prefix = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":\"";
		String suffix = "\",\"!minecraft:lore\":{}}}";
		for (int boundary : new int[] {256, 512, 1024, 2048}) {
			for (String tail : new String[] {"\ud83d\ude00", "\ud83dX\ude00", "é漢\n\"\\\u2028"}) {
				String value = "a".repeat(boundary - 1 - prefix.length()) + tail + "z".repeat(1200);
				JsonObject input = item(); input.getAsJsonObject("components").addProperty("minecraft:custom_data", value);
				String full = java.util.Objects.requireNonNull(InventoryDisplayWriter.project(input, ID, 8192));
				assertNotNull(full); assertEquals(value, componentObject(full).get("minecraft:custom_data").getAsString());
				int exact = wire(full);
				assertEquals(full, InventoryDisplayWriter.project(input, ID, exact));
				assertTrue(componentObject(InventoryDisplayWriter.project(input, ID, exact - 1)).has("!minecraft:custom_data"));
				assertEquals(wire(full), InventoryDisplayWriter.utf8(full));
			}
		}
		assertEquals(prefix + "ordinary" + suffix, InventoryDisplayWriter.project(
			JsonParser.parseString(prefix + "ordinary" + suffix), ID, 4096));
	}

	@Test void failedLargeAttemptResetsBeforeSmallerProjectionAndRetainedOutputCannotRehydrate() {
		JsonObject input = item(); var components = input.getAsJsonObject("components");
		components.addProperty("minecraft:custom_data", "漢".repeat(350));
		components.addProperty("minecraft:custom_name", "secret".repeat(1000));
		String result = java.util.Objects.requireNonNull(InventoryDisplayWriter.project(input, ID, 1501));
		assertNotNull(result);
		assertEquals("漢".repeat(350), componentObject(result).get("minecraft:custom_data").getAsString());
		assertTrue(componentObject(result).has("!minecraft:custom_name"));
		assertFalse(result.contains("secret")); assertTrue(wire(result) <= 1501);
		String tightened = java.util.Objects.requireNonNull(InventoryDisplayWriter.reproject(result, ID, 1024));
		assertTrue(componentObject(tightened).has("!minecraft:custom_data"));
		assertEquals(tightened, InventoryDisplayWriter.reproject(tightened, ID, 8192));
	}

	@Test void allocationAndWorkExhaustionDuringGrowthAreNotSizeTerminalsAndAttemptsStayPaid() {
		JsonObject input = item(); input.getAsJsonObject("components").addProperty("minecraft:custom_data", "x".repeat(3000));
		var full = InventoryItemBudget.root(4096); full.begin(4096); full.beginDisplay(); long work;
		try { assertNotNull(InventoryDisplayWriter.project(input, ID, 4096, full)); work = full.attemptedWork(); }
		finally { full.end(); }
		var tight = InventoryItemBudget.capture(4096, 2, 1_000_000, work - 1); tight.begin(4096); tight.beginDisplay();
		org.junit.jupiter.api.Assertions.assertThrows(InventoryItemBudget.CaptureLimitException.class,
			() -> InventoryDisplayWriter.project(input, ID, 4096, tight));
		long paid = tight.attemptedWork(); assertTrue(paid > 3000); tight.end();
		tight.begin(4096); assertEquals(paid, tight.attemptedWork()); tight.end();
		var allocation = InventoryItemBudget.root(4096); allocation.begin(4096); allocation.beginDisplay();
		try {
			allocation.allocate(1024L * 1024 - 6000, 0, 0, 0);
			org.junit.jupiter.api.Assertions.assertThrows(InventoryItemBudget.RootLimitException.class,
				() -> InventoryDisplayWriter.project(input, ID, 4096, allocation));
			assertTrue(allocation.rootAllocatedBytes() <= 1024L * 1024);
		} finally { allocation.end(); }
	}

	private static JsonObject item() {
		JsonObject item = new JsonObject(); item.addProperty("id", ID); item.add("components", new JsonObject()); return item;
	}
	private static String envelope(String members) { return "{\"id\":\"minecraft:stone\",\"components\":{" + members + "}}"; }
	private static int wire(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
	private static JsonObject object(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
	private static JsonObject componentObject(String value) { return object(value).getAsJsonObject("components"); }
}
