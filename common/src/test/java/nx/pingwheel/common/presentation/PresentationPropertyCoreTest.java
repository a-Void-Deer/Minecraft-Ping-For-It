package nx.pingwheel.common.presentation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationPropertyCoreTest {
	private static final String BASIC = PresentationBasic.ID;

	@Test
	void resolvesRootAndNestedLiteralRecordKeys() {
		Map<String, PresentationValue> state = new LinkedHashMap<>();
		state.put("#counts", new PresentationValue.NumberValue(3));
		state.put("minecraft:cobblestone", new PresentationValue.RecordValue(Map.of("x", new PresentationValue.Text("1"))));
		Map<String, PresentationValue> fields = Map.of(PresentationBasic.BLOCK_STATE,
			new PresentationValue.RecordValue(state));
		var section = new PresentationSection(BASIC, 1, fields, false);

		var root = PresentationPropertyRef.root(BASIC, PresentationBasic.BLOCK_STATE);
		var counts = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
		var cobble = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE,
			List.of("minecraft:cobblestone", "x"));
		var absent = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("minecraft:stone"));

		assertTrue(root.resolve(section) instanceof PresentationValue.RecordValue);
		assertEquals(new PresentationValue.NumberValue(3), counts.resolve(section));
		assertNull(counts.resolveText(section)); // a number is never a pretended text value
		assertEquals("1", cobble.resolveText(section));
		assertNull(absent.resolve(section));
		assertNull(new PresentationPropertyRef("create:other", PresentationBasic.BLOCK_STATE, List.of("#counts"))
			.resolve(section)); // a ref from another adapter never resolves in this section
	}

	@Test
	void propertyRefsRejectInvalidIdsDepthAndOversizedKeys() {
		assertThrows(IllegalArgumentException.class, () -> new PresentationPropertyRef(BASIC, "Bad Id", List.of()));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("a", "b", "c", "d", "e")));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("k".repeat(129))));
	}

	@Test
	void deterministicOrderingGroupsByAdapterFieldAndPath() {
		var root = PresentationPropertyRef.root(BASIC, PresentationBasic.BLOCK_STATE);
		var counts = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
		var cobble = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE,
			List.of("minecraft:cobblestone", "x"));
		var otherAdapter = PresentationPropertyRef.root("create:other", PresentationBasic.NAME);

		// Independent lexical fact: the comparator contract has no preferred-adapter
		// priority, so "create:other" sorts before "minecraft:basic" by natural order.
		assertTrue("create:other".compareTo(BASIC) < 0);

		var refs = new ArrayList<>(List.of(cobble, otherAdapter, counts, root));
		refs.sort(PresentationPropertyRef.DETERMINISTIC_ORDER);

		assertEquals(List.of(otherAdapter, root, counts, cobble), refs);
		assertEquals(List.of(root, counts, cobble),
			refs.stream().filter(ref -> BASIC.equals(ref.adapterId())).toList());
	}

	@Test
	void annotationsRequireTheSectionAdapterAnExistingAddressAndAKnownType() {
		Map<String, PresentationValue> fields = Map.of(PresentationBasic.BLOCK_STATE,
			new PresentationValue.RecordValue(Map.of("#counts", new PresentationValue.Text("3"))));
		var counts = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
		var section = new PresentationSection(BASIC, 1, fields, false, Map.of(counts, "danger"));

		assertEquals("danger", section.annotations().get(counts));

		assertThrows(IllegalArgumentException.class, () -> new PresentationSection(BASIC, 1, fields, false,
			Map.of(new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("missing")), "danger")));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationSection(BASIC, 1, fields, false, Map.of(counts, "not_a_ping_type")));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationSection("create:other", 1, fields, false, Map.of(counts, "danger")));
	}

	@Test
	void sequenceContentsAreNeverAnnotatable() {
		Map<String, PresentationValue> fields = Map.of("pingforit:seq", new PresentationValue.Sequence(
			List.of(new PresentationValue.RecordValue(Map.of("k", new PresentationValue.Text("v"))))));
		var inside = new PresentationPropertyRef(BASIC, "pingforit:seq", List.of("0", "k"));

		assertNull(inside.resolve(new PresentationSection(BASIC, 1, fields, false)));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationSection(BASIC, 1, fields, false, Map.of(inside, "danger")));
	}

	@Test
	void defaultsFollowTheTargetTypeAndFailClosedWhenUnknown() {
		assertEquals(Optional.of(PresentationPropertyRef.root(BASIC, PresentationBasic.ITEM_ID)),
			PresentationDefaults.forTargetType("dropped_item"));
		assertEquals(Optional.of(PresentationPropertyRef.root(BASIC, PresentationBasic.HEALTH)),
			PresentationDefaults.forTargetType("entity"));
		for (String id : List.of("entity_block", "block", "location")) {
			assertEquals(Optional.of(PresentationPropertyRef.root(BASIC, PresentationBasic.NAME)),
				PresentationDefaults.forTargetType(id));
		}
		assertEquals(Optional.empty(), PresentationDefaults.forTargetType("unknown"));
		assertEquals(Optional.of(PresentationPropertyRef.root(BASIC, PresentationBasic.ITEM_COUNT)),
			PresentationDefaults.displayCountContextRef("dropped_item"));
		assertEquals(Optional.empty(), PresentationDefaults.displayCountContextRef("block"));
	}

	@Test
	void targetSelectorsKeepTagAndRegistryIdLookupsIndependent() {
		var plainWildcard = PresentationTargetSelector.of("create:*");
		assertTrue(plainWildcard.matches("create:gear", Set.of()));
		assertFalse(plainWildcard.matches(null, Set.of("create:gear")));

		var tag = PresentationTargetSelector.of("#c:chests");
		assertTrue(tag.matches("minecraft:barrel", Set.of("c:chests")));
		assertFalse(tag.matches("c:chests", Set.of())); // no absent-tag-as-registry-id fallback
		assertTrue(PresentationTargetSelector.of("#*:iron_ingot")
			.matches("minecraft:barrel", Set.of("create:iron_ingot")));
		assertTrue(PresentationTargetSelector.of("#create:*").matches("minecraft:barrel", Set.of("create:item_vault")));
		assertTrue(PresentationTargetSelector.of("cyclic:*").matches("cyclic:shaft", Set.of()));
		assertTrue(PresentationTargetSelector.of("*:*").matches("create:gear", Set.of()));
		assertFalse(PresentationTargetSelector.of("*:*").matches(null, Set.of("c:chests")));

		for (String invalid : List.of("#", "missing_colon", "create:", ":x", "Create:x", "a:b:c")) {
			assertFalse(PresentationTargetSelector.isValid(invalid));
		}
		assertThrows(IllegalArgumentException.class, () -> PresentationTargetSelector.of("nope"));
	}

	@Test
	void targetSelectorLengthBoundCountsTheWholeSelectorIncludingTagPrefix() {
		String plain = "create:" + "a".repeat(PresentationTargetSelector.MAX_LENGTH - "create:".length());
		String tag = "#c:" + "a".repeat(PresentationTargetSelector.MAX_LENGTH - "#c:".length());

		assertEquals(PresentationTargetSelector.MAX_LENGTH, plain.length());
		assertEquals(PresentationTargetSelector.MAX_LENGTH, tag.length());
		assertTrue(PresentationTargetSelector.isValid(plain));
		assertTrue(PresentationTargetSelector.isValid(tag));
		assertFalse(PresentationTargetSelector.isValid(plain + "a"));
		assertFalse(PresentationTargetSelector.isValid("#" + plain)); // the prefix must fit inside the cap too
	}

	@Test
	void propertyPingTypesApplyOrderedOverridesAndCanReAdd() {
		var builtIn = PresentationPropertyPingTypes.builtIn();
		assertEquals(List.of("attention", "danger"), builtIn.effective("create:gear", Set.of()));
		assertEquals(List.of("attention", "request"), builtIn.effective("minecraft:barrel", Set.of("c:chests")));
		// A registry id that spells the tag name never feeds the tag selector.
		assertEquals(List.of("attention", "danger"), builtIn.effective("c:chests", Set.of()));
		assertFalse(builtIn.allows("danger", "minecraft:barrel", Set.of("c:chests")));
		assertTrue(builtIn.allows("request", "minecraft:barrel", Set.of("c:chests")));

		var ordered = new PresentationPropertyPingTypes(List.of(
			new PresentationPropertyPingTypes.Override(PresentationTargetSelector.of("create:*"),
				List.of("danger"), List.of()),
			new PresentationPropertyPingTypes.Override(PresentationTargetSelector.of("create:gear"),
				List.of(), List.of("danger"))));
		assertEquals(List.of("attention", "danger"), ordered.effective("create:gear", Set.of()));
		assertEquals(List.of("attention"), ordered.effective("create:other", Set.of()));
	}

	@Test
	void intentsAndSelectionsValidateTheValueAndKnownPingTypes() {
		var ref = PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
		var nested = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
		var intent = PresentationPropertyIntent.observed(ref, new PresentationValue.Text("Chest"));
		var number = PresentationPropertyIntent.of(nested, new PresentationValue.NumberValue(3), "danger");
		var record = PresentationPropertyIntent.observed(ref,
			new PresentationValue.RecordValue(Map.of("speed", new PresentationValue.NumberValue(1.5))));

		assertNull(intent.pingTypeId());
		assertTrue(intent.observedValue() instanceof PresentationValue.Text);
		assertTrue(number.observedValue() instanceof PresentationValue.NumberValue);
		assertTrue(record.observedValue() instanceof PresentationValue.RecordValue);
		assertEquals("danger", number.pingTypeId());
		assertEquals("go_to", PresentationPropertySelection.of(ref, "go_to").pingTypeId());
		assertNull(PresentationPropertySelection.of(ref).pingTypeId());

		assertThrows(NullPointerException.class, () -> PresentationPropertyIntent.observed(ref, null));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationPropertyIntent(ref, new PresentationValue.Flag(true), "not_a_type"));
		assertThrows(IllegalArgumentException.class, () -> new PresentationPropertyIntent(ref,
			new PresentationValue.Text("x".repeat(PresentationLimits.MAX_TEXT_BYTES + 1)), null));

		PresentationValue deep = new PresentationValue.Flag(true);
		for (int i = 0; i <= PresentationLimits.MAX_DEPTH; i++)
			deep = new PresentationValue.RecordValue(Map.of("k", deep));
		PresentationValue tooDeep = deep;
		assertThrows(IllegalArgumentException.class, () -> PresentationPropertyIntent.observed(ref, tooDeep));
	}
}
