package nx.pingwheel.common.presentation;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PresentationCoreTest {
	private static final String BASIC = "pingforit:basic";
	private static final String EXTRA = "create:inventory";

	private static PresentationSection section(String adapter, String key) {
		return new PresentationSection(adapter, 1, Map.of(key, new PresentationValue.Text("value")), false);
	}

	private static final String TARGET_TYPE = "block";

	private static PresentationPropertyRef defaultRef() {
		return PresentationPropertyRef.root(BASIC, "pingforit:name");
	}

	private static void initial(PresentationStore store, long epoch, long view, long markerId, PresentationSection basic) {
		store.initial(epoch, view, markerId, TARGET_TYPE, defaultRef(), basic);
	}

	@Test
	void wildcardMatchesEachIdPartIndependentlyAndWhiteOverridesBlack() {
		var policy = new PresentationPolicy(List.of("c*e:in*ry", "*:always"),
			List.of("create:*", "*:always"), true);
		assertTrue(policy.allows("create:inventory", false));
		assertTrue(policy.allows("forge:always", false));
		assertFalse(policy.allows("create:other", true));
		assertFalse(policy.allows("forge:inventory", true));
		assertTrue(new PresentationPolicy(List.of("create*:inventory"), List.of(), true)
			.allows("create:inventory", false)); // wildcard can match zero characters
		assertFalse(new PresentationPolicy(List.of("create:inventory*"), List.of(), true)
			.allows("create_extra:inventory", false)); // path wildcard cannot cross ':'
		assertTrue(PresentationPolicy.acceptAll().allows(EXTRA, true));
		assertFalse(PresentationPolicy.acceptAll().allows(EXTRA, false));
	}

	@Test
	void selectorsAreValidatedAtConstructionAndRemainImmutable() {
		var input = new ArrayList<>(List.of("create:*") );
		var policy = new PresentationPolicy(input, List.of(), false);
		input.clear();
		assertEquals(List.of("create:*"), policy.white());
		assertThrows(UnsupportedOperationException.class, () -> policy.white().add("forge:*"));
		for (String invalid : List.of("*", ":inventory", "create:", "cre/ate:*",
			"create:in:ventory", "create:Inventory", "create:" + "a".repeat(193))) {
			assertThrows(IllegalArgumentException.class, () -> new PresentationPolicy(List.of(invalid), List.of(), false));
		}
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationPolicy(java.util.Collections.nCopies(129, "create:*"), List.of(), false));
	}

	@Test
	void manyOverlappingStarsCannotCauseRegexBacktracking() {
		String selector = "create:" + "*a".repeat(70) + "*z";
		var policy = new PresentationPolicy(List.of(selector), List.of(), true);
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			for (int i = 0; i < 1000; i++)
				assertFalse(policy.allows("create:" + "a".repeat(128), false));
		});
	}

	@Test
	void sectionValidationIsPureAndLimitsStillApply() {
		assertEquals(section(BASIC, "pingforit:label"), section(BASIC, "pingforit:label"));
		assertThrows(UnsupportedOperationException.class,
			() -> section(BASIC, "pingforit:label").fields().put("new:key", new PresentationValue.Flag(true)));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationSection(BASIC, 1, Map.of("pingforit:label",
				new PresentationValue.Text("界".repeat(PresentationLimits.MAX_TEXT_BYTES))), false));
		PresentationValue deeplyNested = new PresentationValue.Flag(true);
		for (int i = 0; i <= PresentationLimits.MAX_DEPTH; i++)
			deeplyNested = new PresentationValue.Sequence(List.of(deeplyNested));
		PresentationValue tooDeep = deeplyNested;
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationSection(BASIC, 1, Map.of("pingforit:deep", tooDeep), false));
	}

	@Test
	void generationClearsOnlyActiveSectionsAndPreservesKnownMarkersAndFrozenSnapshots() {
		var store = new PresentationStore();
		store.reset(7);
		initial(store, 7, 0, 10, section(BASIC, "pingforit:first"));
		initial(store, 7, 0, 11, section(BASIC, "pingforit:first"));
		assertTrue(store.replace(7, 0, 10, 1, section(EXTRA, "create:contents")));
		store.clear(7, 10, 2, true);
		assertTrue(store.isFrozen(10));
		assertFalse(store.replace(7, 0, 10, 20, section(EXTRA, "create:new")));
		assertFalse(store.clearSection(7, 0, 10, EXTRA, 20));
		store.generation(7, 1);
		assertEquals(2, store.sections(10).size());
		assertEquals(Map.of(), store.sections(11));
		assertTrue(store.isKnown(11));
		initial(store, 7, 1, 11, section(BASIC, "pingforit:late"));
		assertTrue(store.sections(11).isEmpty());
		assertTrue(store.replace(7, 1, 11, 1, section(EXTRA, "create:contents")));
		assertFalse(store.replace(7, 0, 11, 9, section(EXTRA, "create:old")));
		assertEquals(1, store.viewGeneration());
		store.generation(7, 0); // a regressing view cannot roll the store back
		assertEquals(1, store.viewGeneration());
		store.generation(8, 5); // a mismatched epoch is ignored
		assertEquals(1, store.viewGeneration());
	}

	@Test
	void sectionClearsAreRevisionedPerAdapterAndDoNotDeleteMarker() {
		var store = new PresentationStore();
		store.reset(1);
		initial(store, 1, 0, 8, section(BASIC, "pingforit:title"));
		assertTrue(store.replace(1, 0, 8, 3, section(EXTRA, "create:value")));
		assertFalse(store.clearSection(1, 0, 8, EXTRA, 3));
		assertTrue(store.clearSection(1, 0, 8, EXTRA, 4));
		assertTrue(store.sections(8).containsKey(BASIC));
		assertFalse(store.sections(8).containsKey(EXTRA));
		assertFalse(store.replace(1, 0, 8, 4, section(EXTRA, "create:old")));
		assertFalse(store.clearSection(1, 0, 8, EXTRA, 4));
		assertFalse(store.clearSection(1, 1, 8, EXTRA, 100)); // stale view
		assertTrue(store.replace(1, 0, 8, 5, section(EXTRA, "create:fresh")));
		assertEquals(5, store.sections(8).get(EXTRA).revision());
		assertThrows(UnsupportedOperationException.class, () -> store.sections(8).clear());
	}

	@Test
	void hardRemovalAndEvictionPreventResurrectionUntilNewSession() {
		var store = new PresentationStore();
		store.reset(1);
		store.clear(1, 20, 1, false); // removal before initial
		initial(store, 1, 0, 20, section(BASIC, "pingforit:late"));
		assertTrue(store.isKnown(20));
		assertTrue(store.sections(20).isEmpty());
		initial(store, 1, 0, 21, section(BASIC, "pingforit:value"));
		store.clear(1, 21, 2, true);
		store.restrict(Map.of(TARGET_TYPE, Map.of(BASIC, Set.of())));
		assertTrue(store.sections(21).get(BASIC).section().fields().isEmpty());
		assertFalse(store.replace(1, 0, 21, 99, section(EXTRA, "create:more")));
		store.evict(21);
		initial(store, 1, 0, 21, section(BASIC, "pingforit:late"));
		assertTrue(store.isKnown(21));
		assertFalse(store.isFrozen(21));
		assertTrue(store.sections(21).isEmpty());
		assertFalse(store.replace(1, 0, 21, 100, section(EXTRA, "create:more")));
		store.reset(2);
		assertFalse(store.isKnown(21));
		initial(store, 2, 0, 21, section(BASIC, "pingforit:fresh"));
		assertEquals(1, store.sections(21).size());
	}

	@Test
	void serverMaskPrunesToAllowedFieldsAndKeepsMarkerIdentity() {
		var store = new PresentationStore();
		store.reset(3);
		var basic = new PresentationSection(BASIC, 1, Map.of(
			"pingforit:name", new PresentationValue.Text("Chest"),
			"pingforit:extra", new PresentationValue.Flag(true)), false);
		initial(store, 3, 0, 5, basic);

		assertEquals(TARGET_TYPE, store.targetTypeId(5));
		assertEquals(defaultRef(), store.defaultRef(5));

		store.restrict(Map.of(TARGET_TYPE, Map.of(BASIC, Set.of("pingforit:name"))));
		assertEquals(Set.of("pingforit:name"), store.sections(5).get(BASIC).section().fields().keySet());

		store.restrict(Map.of());
		assertTrue(store.sections(5).get(BASIC).section().fields().isEmpty());
		assertTrue(store.isKnown(5));
	}

	@Test
	void boundedStoreFailsClosedInsteadOfDroppingOldRemovalHistory() {
		var store = new PresentationStore();
		store.reset(1);
		initial(store, 1, 0, 1, section(BASIC, "pingforit:saved"));
		for (long i = 2; i < 8200; i++) store.clear(1, i, 1, false);
		initial(store, 1, 0, 8200, section(BASIC, "pingforit:new"));
		assertTrue(store.sections(8200).isEmpty());
		initial(store, 1, 0, 2, section(BASIC, "pingforit:late"));
		assertTrue(store.sections(2).isEmpty());
		assertEquals(1, store.sections(1).size());
	}

	@Test
	void oversizedSemanticRecordIsBoundedToEmptyStaleSectionBeforeInitialDelivery() {
		Map<String, PresentationValue> properties = new LinkedHashMap<>();
		for (int i = 0; i < PresentationLimits.MAX_ENTRIES; i++)
			properties.put("k" + i, new PresentationValue.Text("v".repeat(PresentationLimits.MAX_TEXT_BYTES)));
		var blockState = new PresentationValue.RecordValue(properties);
		var oversized = new PresentationSection(BASIC, 1, Map.of("pingforit:block", blockState), false);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256, PresentationCodec.MAX_SECTION_BYTES + 5));
		try {
			assertThrows(RuntimeException.class, () -> PresentationCodec.write(buf, oversized));
		} finally { buf.release(); }

		assertEquals(new PresentationSection(BASIC, 1, Map.of(), true), PresentationCodec.bounded(oversized));
		var fitting = section(BASIC, "pingforit:label");
		assertSame(fitting, PresentationCodec.bounded(fitting));
	}

	@Test
	void codecRoundTripsAndSkipsDeniedUnknownTagsWithoutDecodingThem() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			var original = section(BASIC, "pingforit:title");
			PresentationCodec.write(buf, original);
			assertEquals(original, PresentationCodec.read(buf, id -> true));
			assertEquals(0, buf.readableBytes());
			writeFrame(buf, new String[] {"pingforit:unknown", "pingforit:known"}, new int[] {127, 3});
			var result = PresentationCodec.read(buf, id -> id.equals("pingforit:known"));
			assertEquals(Map.of("pingforit:known", new PresentationValue.Flag(true)), result.fields());
		} finally { buf.release(); }
	}

	@Test
	void duplicateDeniedFieldsAndOversizedFramesAreRejectedBeforeTypedDecode() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			writeFrame(buf, new String[] {"pingforit:unknown", "pingforit:unknown"}, new int[] {127, 127});
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.read(buf, id -> false));
			buf.clear();
			buf.writeVarInt(PresentationCodec.MAX_SECTION_BYTES + 1);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.read(buf, id -> false));
			buf.clear();
			writeFrame(buf, new String[] {"pingforit:unknown"}, new int[] {127});
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.read(buf, id -> true));
		} finally { buf.release(); }
	}

	private static void writeFrame(FriendlyByteBuf out, String[] ids, int[] tags) {
		FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
		try {
			frame.writeUtf(BASIC, 193);
			frame.writeVarInt(1);
			frame.writeBoolean(false);
			frame.writeVarInt(ids.length);
			for (int i = 0; i < ids.length; i++) {
				frame.writeUtf(ids[i], 193);
				// v3: the typed value is followed by a nullable annotation bit.
				frame.writeVarInt(tags[i] == 3 ? 3 : 1);
				frame.writeByte(tags[i]);
				if (tags[i] == 3) { frame.writeBoolean(true); frame.writeBoolean(false); }
			}
			out.writeVarInt(frame.readableBytes());
			out.writeBytes(frame);
		} finally { frame.release(); }
	}
}
