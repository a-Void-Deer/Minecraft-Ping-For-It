package nx.pingwheel.common.presentation.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationPropertyFormatterTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static final String BASIC = "minecraft:basic";
	private static final String CREATE = "create:presentation";
	private static final PresentationPropertyRef HEALTH = PresentationPropertyRef.root(BASIC, "minecraft:entity.health");
	private static final PresentationPropertyRef COUNT = new PresentationPropertyRef(CREATE,
		"create:inventory.summary", List.of("counts", "minecraft:cobblestone"));
	private static final PresentationPropertyRef RPM = new PresentationPropertyRef(CREATE,
		"create:kinetic.speed", List.of("effective_rpm"));

	@Test void distinctNestedAnnotationsFollowTheDefaultWithoutInheritingOuterPingType() {
		var basic = new PresentationSection(BASIC, 1,
			Map.of("minecraft:entity.health", new PresentationValue.NumberValue(5),
				"minecraft:entity.max_health", new PresentationValue.NumberValue(20),
				"minecraft:target.name", new PresentationValue.Text("{\"text\":\"Named\"}")), false);
		var create = new PresentationSection(CREATE, 1, Map.of(
			"create:inventory.summary", new PresentationValue.RecordValue(Map.of("counts",
				new PresentationValue.RecordValue(Map.of("minecraft:cobblestone", new PresentationValue.NumberValue(64))))),
			"create:kinetic.speed", new PresentationValue.RecordValue(Map.of("effective_rpm",
				new PresentationValue.NumberValue(128)))), false,
			Map.of(COUNT, "request", RPM, "danger"));
		var view = new PresentationView("entity", HEALTH, Map.of(BASIC, basic, CREATE, create));
		assertEquals(List.of(
			new PresentationPropertyFormatter.LabelProperty(HEALTH, null),
			new PresentationPropertyFormatter.LabelProperty(COUNT, "request"),
			new PresentationPropertyFormatter.LabelProperty(RPM, "danger")),
			PresentationPropertyFormatter.plan(view));
		assertNotNull(view.field(BASIC, "minecraft:target.name"),
			"the name remains available independently of the health default");
	}

	@Test void annotatingTheDefaultDoesNotDuplicateItAndAbsentNestedEntriesAreSkipped() {
		var basic = new PresentationSection(BASIC, 1,
			Map.of("minecraft:entity.health", new PresentationValue.NumberValue(5),
				"minecraft:entity.max_health", new PresentationValue.NumberValue(20)), false,
			Map.of(HEALTH, "attention"));
		assertEquals(List.of(new PresentationPropertyFormatter.LabelProperty(HEALTH, "attention")),
			PresentationPropertyFormatter.plan(new PresentationView("entity", HEALTH, Map.of(BASIC, basic))));
		var missingDefault = new PresentationView("entity", COUNT, Map.of(BASIC, basic));
		assertNull(missingDefault.property(COUNT));
		assertEquals(List.of(new PresentationPropertyFormatter.LabelProperty(HEALTH, "attention")),
			PresentationPropertyFormatter.plan(missingDefault),
			"the absent default is not fabricated, but an unrelated explicit health ping remains visible");
		var unannotated = new PresentationSection(BASIC, 1, basic.fields(), false);
		assertTrue(PresentationPropertyFormatter.plan(
			new PresentationView("entity", COUNT, Map.of(BASIC, unannotated))).isEmpty(),
			"without a present default or explicit annotation, no property line is shown");
	}

	@Test void scalarFallbacksAreBoundedAndNeverDumpRecordObjects() {
		var one = new PresentationPropertyRef(CREATE, "create:kinetic.speed", List.of("effective_rpm"));
		var two = PresentationPropertyRef.root(CREATE, "create:kinetic.overstressed");
		var three = PresentationPropertyRef.root(CREATE, "create:fluid.summary");
		var section = new PresentationSection(CREATE, 1, Map.of(
			"create:kinetic.speed", new PresentationValue.RecordValue(Map.of("effective_rpm", new PresentationValue.NumberValue(128))),
			"create:kinetic.overstressed", new PresentationValue.Flag(true),
			"create:fluid.summary", new PresentationValue.RecordValue(Map.of("counts",
				new PresentationValue.RecordValue(Map.of("minecraft:water", new PresentationValue.NumberValue(1000)))))),
			false, Map.of(one, "danger", two, "attention", three, "request"));
		var view = new PresentationView("block", one, Map.of(CREATE, section));
		assertEquals(3, PresentationPropertyFormatter.plan(view).size());
		List<String> labels = ClientPresentation.defaultLabels(view);
		assertEquals(3, labels.size());
		assertTrue(labels.stream().noneMatch(line -> line.contains("RecordValue") || line.contains("NumberValue")
			|| line.contains("{counts") || line.contains("{effective_rpm")));
	}

	@Test void healthRequiresAnAuthorizedMaximumButDoesNotHideAnIndependentName() {
		var name = PresentationPropertyRef.root(BASIC, "minecraft:target.name");
		var withoutMax = new PresentationSection(BASIC, 1, Map.of(
			"minecraft:entity.health", new PresentationValue.NumberValue(5),
			"minecraft:target.name", new PresentationValue.Text("{\"text\":\"Named\"}")), false);
		var view = new PresentationView("entity", HEALTH, Map.of(BASIC, withoutMax));
		assertTrue(PresentationPropertyFormatter.plan(view).isEmpty(),
			"a health default alone cannot produce a partial health label");
		assertTrue(ClientPresentation.defaultLabels(view).isEmpty());
		assertNotNull(view.property(name), "the name remains available to the separate chat/HUD name path");
		var annotatedWithoutMax = new PresentationSection(BASIC, 1, withoutMax.fields(), false,
			Map.of(HEALTH, "attention"));
		assertTrue(PresentationPropertyFormatter.plan(
			new PresentationView("entity", HEALTH, Map.of(BASIC, annotatedWithoutMax))).isEmpty(),
			"a property annotation cannot bypass the authorized health-pair requirement");

		var withMax = new PresentationSection(BASIC, 1, Map.of(
			"minecraft:entity.health", new PresentationValue.NumberValue(5),
			"minecraft:entity.max_health", new PresentationValue.NumberValue(20),
			"minecraft:target.name", new PresentationValue.Text("{\"text\":\"Named\"}")), false);
		var paired = new PresentationView("entity", HEALTH, Map.of(BASIC, withMax));
		assertEquals(1, ClientPresentation.defaultLabels(paired).size());
		assertNotNull(paired.property(name));
	}

	@Test void unannotatedNondefaultValueIsRetainedButNotAnExplicitPing() {
		var other = PresentationPropertyRef.root(BASIC, "minecraft:entity.type");
		var section = new PresentationSection(BASIC, 1, Map.of(
			"minecraft:entity.health", new PresentationValue.NumberValue(5),
			"minecraft:entity.max_health", new PresentationValue.NumberValue(20),
			"minecraft:entity.type", new PresentationValue.Text("minecraft:zombie")), false);
		var view = new PresentationView("entity", HEALTH, Map.of(BASIC, section));
		assertNotNull(view.property(other), "a received context field remains available to consumers");
		assertEquals(List.of(new PresentationPropertyFormatter.LabelProperty(HEALTH, null)),
			PresentationPropertyFormatter.plan(view), "only the default and explicitly annotated refs produce lines");
	}

	@Test void targetNameDefaultIsExcludedFromThePropertyPlan() {
		var name = PresentationPropertyRef.root(BASIC, "minecraft:target.name");
		var section = new PresentationSection(BASIC, 1,
			Map.of("minecraft:target.name", new PresentationValue.Text("{\"text\":\"Named\"}")), false);
		for (String targetType : List.of("block", "entity_block", "location")) {
			var view = new PresentationView(targetType, name, Map.of(BASIC, section));
			assertTrue(PresentationPropertyFormatter.plan(view).isEmpty(),
				targetType + ": the name default must not add a second line beside the authoritative name");
			assertTrue(ClientPresentation.defaultLabels(view).isEmpty());
			assertNotNull(view.property(name), "the name value stays available to the separate name path");
		}
	}

	@Test void explicitTargetNameAnnotationCannotDuplicateTheAuthoritativeName() {
		var name = PresentationPropertyRef.root(BASIC, "minecraft:target.name");
		var section = new PresentationSection(BASIC, 1, Map.of(
			"minecraft:entity.health", new PresentationValue.NumberValue(5),
			"minecraft:entity.max_health", new PresentationValue.NumberValue(20),
			"minecraft:target.name", new PresentationValue.Text("{\"text\":\"Named\"}")), false,
			Map.of(name, "attention"));
		var view = new PresentationView("entity", HEALTH, Map.of(BASIC, section));
		assertEquals(List.of(new PresentationPropertyFormatter.LabelProperty(HEALTH, null)),
			PresentationPropertyFormatter.plan(view),
			"the annotated name is excluded while the non-name default still produces its line");
		List<String> labels = ClientPresentation.defaultLabels(view);
		assertEquals(1, labels.size());
		assertTrue(labels.stream().noneMatch(line -> line.contains("Named")),
			"no property line repeats the authoritative target name");
	}

	@Test void droppedItemDefaultAndCountContextAreUnaffectedByTheNameExclusion() {
		var item = PresentationPropertyRef.root(BASIC, "minecraft:item.id");
		var section = new PresentationSection(BASIC, 1, Map.of(
			"minecraft:item.id", new PresentationValue.Text("minecraft:cobblestone"),
			"minecraft:item.count", new PresentationValue.NumberValue(64)), false);
		var view = new PresentationView("dropped_item", item, Map.of(BASIC, section));
		assertEquals(List.of(new PresentationPropertyFormatter.LabelProperty(item, null)),
			PresentationPropertyFormatter.plan(view),
			"the dropped-item default is not the name and stays displayable");
		assertEquals(1, ClientPresentation.defaultLabels(view).size());
	}

	@Test void kineticStressCapacityAndAvailableFormatAsSuFromTheSameProjection() throws IOException {
		withEnglishTranslations(() -> {
			var stress = PresentationPropertyRef.root(CREATE, "create:kinetic.stress");
			var capacity = PresentationPropertyRef.root(CREATE, "create:kinetic.capacity");
			var available = PresentationPropertyRef.root(CREATE, "create:kinetic.available_capacity");
			var section = new PresentationSection(CREATE, 1, Map.of(
				"create:kinetic.stress", new PresentationValue.NumberValue(12),
				"create:kinetic.capacity", new PresentationValue.NumberValue(10),
				"create:kinetic.available_capacity", new PresentationValue.NumberValue(-2)), false,
				Map.of(available, "attention"));
			assertEquals(List.of(
				"Used stress: 12 SU (120%)",
				"Attention: Available stress: -2 SU"),
				ClientPresentation.defaultLabels(new PresentationView("block", stress, Map.of(CREATE, section))));
			assertEquals(List.of(
				"Total stress capacity: 10 SU",
				"Attention: Available stress: -2 SU"),
				ClientPresentation.defaultLabels(new PresentationView("block", capacity, Map.of(CREATE, section))));
		});
	}

	@Test void kineticStressOmitsThePercentageWithoutAnAuthorizedPositiveCapacity() throws IOException {
		withEnglishTranslations(() -> {
			var stress = PresentationPropertyRef.root(CREATE, "create:kinetic.stress");
			var section = new PresentationSection(CREATE, 1,
				Map.of("create:kinetic.stress", new PresentationValue.NumberValue(12)), false);
			assertEquals(List.of("Used stress: 12 SU"),
				ClientPresentation.defaultLabels(new PresentationView("block", stress, Map.of(CREATE, section))));
			var zero = new PresentationSection(CREATE, 1, Map.of(
				"create:kinetic.stress", new PresentationValue.NumberValue(12),
				"create:kinetic.capacity", new PresentationValue.NumberValue(0)), false);
			assertEquals(List.of("Used stress: 12 SU"),
				ClientPresentation.defaultLabels(new PresentationView("block", stress, Map.of(CREATE, zero))));
		});
	}

	@Test void unrelatedPropertyFormatsStayUnchanged() throws IOException {
		withEnglishTranslations(() -> {
			var health = PresentationPropertyRef.root(BASIC, "minecraft:entity.health");
			var type = PresentationPropertyRef.root(BASIC, "minecraft:entity.type");
			var section = new PresentationSection(BASIC, 1, Map.of(
				"minecraft:entity.health", new PresentationValue.NumberValue(5),
				"minecraft:entity.max_health", new PresentationValue.NumberValue(20),
				"minecraft:entity.type", new PresentationValue.Text("minecraft:zombie")), false,
				Map.of(type, "attention"));
			assertEquals(List.of("Health: 5 / 20", "Attention: Entity type: minecraft:zombie"),
				ClientPresentation.defaultLabels(new PresentationView("entity", health, Map.of(BASIC, section))));
		});
	}

	@FunctionalInterface private interface ThrowingRunnable { void run(); }

	private static void withEnglishTranslations(ThrowingRunnable body) throws IOException {
		Map<String, String> english = new LinkedHashMap<>();
		try (InputStream stream = PresentationPropertyFormatterTest.class.getClassLoader()
			.getResourceAsStream("assets/pingforit/lang/en_us.json")) {
			assertNotNull(stream);
			Language.loadFromJson(stream, english::put);
		}
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(english));
		try {
			body.run();
		} finally {
			Language.inject(previous);
		}
	}

	private static final class MapLanguage extends Language {
		private final Map<String, String> translations;
		private MapLanguage(Map<String, String> translations) { this.translations = translations; }
		@Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
		@Override public boolean has(String key) { return translations.containsKey(key); }
		@Override public boolean isDefaultRightToLeft() { return false; }
		@Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}
}
