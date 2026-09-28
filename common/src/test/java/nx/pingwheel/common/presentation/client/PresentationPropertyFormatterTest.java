package nx.pingwheel.common.presentation.client;

import java.util.List;
import java.util.Map;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationPropertyFormatterTest {
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
}
