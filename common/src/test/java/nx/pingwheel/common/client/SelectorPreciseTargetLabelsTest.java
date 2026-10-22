package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.name.ClientTargetNameResolver;
import nx.pingwheel.common.render.SpatialInventoryView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Precise detail-label resolver headlessly: every enabled Precise
 * leaf names its own captured candidate from the exact snapshot it is bound
 * to, a broad slot never synthesizes its slot type, choices without a captured
 * candidate never reach the naming function, a newer publication cannot
 * retarget an older snapshot, and literal or localized name components pass
 * through unchanged. It is a label-resolution helper seam: it does not
 * exercise the real selector draw, renderer wiring, or GPU submission.
 */
class SelectorPreciseTargetLabelsTest {
	private static final String DIMENSION = "minecraft:overworld";

	@Test
	void everyEnabledPreciseLeafNamesItsOwnCapturedCandidate() {
		Map<String, SpatialSelectorSession.CapturedTarget> choices = new LinkedHashMap<>();
		choices.put("precise:dropped_item", candidate("c-item", new Target.EntityTarget(DIMENSION, new UUID(1, 1)), "dropped_item"));
		choices.put("precise:entity", candidate("c-entity", new Target.EntityTarget(DIMENSION, new UUID(2, 2)), "entity"));
		choices.put("precise:entity_block", candidate("c-entity-block", new Target.BlockTarget(DIMENSION, 1, 2, 3, "minecraft:chest"), "entity_block"));
		choices.put("precise:block", candidate("c-block", new Target.BlockTarget(DIMENSION, 4, 5, 6, "minecraft:stone"), "block"));
		choices.put("precise:location", candidate("c-location", new Target.LocationTarget(DIMENSION, 7.0, 8.0, 9.0), "location"));
		List<Target> named = new ArrayList<>();
		SelectorPreciseTargetLabels labels = new SelectorPreciseTargetLabels(snapshot(1, choices),
			target -> {
				named.add(target);
				return Component.literal("named-" + named.size());
			});

		for (Map.Entry<String, SpatialSelectorSession.CapturedTarget> entry : choices.entrySet()) {
			Component detail = labels.detail(choice(entry.getKey()));
			assertEquals("named-" + named.size(), detail.getString(), entry.getKey());
			assertSame(entry.getValue().resolvedTarget().target(), named.getLast(),
				"the candidate's own resolved target is named");
		}

		assertEquals(5, named.size(), "each of the five slots resolves exactly once");
	}

	@Test
	void broadSlotNamesTheCapturedTargetInsteadOfSynthesizingItsSlotType() {
		Target droppedItem = new Target.EntityTarget(DIMENSION, new UUID(3, 3));
		var captured = candidate("c-item", droppedItem, "dropped_item");
		List<Target> named = new ArrayList<>();
		SelectorPreciseTargetLabels labels = new SelectorPreciseTargetLabels(
			snapshot(1, Map.of("precise:entity", captured)),
			target -> {
				named.add(target);
				return Component.literal("Dropped Item");
			});

		assertEquals("Dropped Item", labels.detail(choice("precise:entity")).getString());
		assertEquals(List.of(droppedItem), named,
			"the broad entity slot keeps the candidate's canonical dropped-item identity");
		assertEquals("dropped_item", captured.resolvedTarget().targetType().id());
	}

	@Test
	void choicesWithoutACapturedCandidateNeverInvokeTheNamingFunction() {
		AtomicInteger lookups = new AtomicInteger();
		SelectorPreciseTargetLabels labels = new SelectorPreciseTargetLabels(
			snapshot(1, Map.of("precise:block", candidate("c-block",
				new Target.BlockTarget(DIMENSION, 1, 2, 3, "minecraft:stone"), "block"))),
			target -> {
				lookups.incrementAndGet();
				return Component.literal("resolved");
			});

		assertNull(labels.detail(choice("precise:entity")), "missing candidate stays without a detail");
		assertNull(labels.detail(choice("precise:location")), "missing candidate stays without a detail");
		assertNull(labels.detail(disabledChoice("precise:entity_block")), "disabled leaf stays without a detail");
		assertNull(labels.detail(choice("precise:unknown_type")), "unknown slot stays without a detail");
		assertNull(labels.detail(choice("intent:attention")), "non-precise choice stays without a detail");
		assertNull(labels.detail(backChoice()), "Back stays without a detail");
		assertNull(labels.detail(null), "no choice stays without a detail");
		assertEquals(0, lookups.get(), "no detail resolution may sample the naming function");

		SelectorPreciseTargetLabels noFrame = new SelectorPreciseTargetLabels(
			snapshot(1, null), target -> {
				lookups.incrementAndGet();
				return Component.literal("resolved");
			});
		assertNull(noFrame.detail(choice("precise:block")));
		assertEquals(0, lookups.get(), "a snapshot without a precise frame performs no lookup");
	}

	@Test
	void resolverStaysBoundToTheSnapshotItWasBuiltWith() {
		var first = candidate("c-first", new Target.BlockTarget(DIMENSION, 1, 2, 3, "minecraft:stone"), "block");
		var second = candidate("c-second", new Target.BlockTarget(DIMENSION, 4, 5, 6, "minecraft:chest"), "block");
		SelectorPreciseTargetLabels older = new SelectorPreciseTargetLabels(
			snapshot(1, Map.of("precise:block", first)), target -> Component.literal("first"));
		SelectorPreciseTargetLabels newer = new SelectorPreciseTargetLabels(
			snapshot(2, Map.of("precise:block", second)), target -> Component.literal("second"));

		assertEquals("second", newer.detail(choice("precise:block")).getString());
		assertEquals("first", older.detail(choice("precise:block")).getString(),
			"a newer publication never retargets an older bound snapshot");
	}

	@Test
	void literalAndLocalizedNamesPassThroughTheExistingNameResolver() {
		ClientTargetNameResolver.Lookup lookup = new ClientTargetNameResolver.Lookup() {
			@Override
			public Optional<Component> entity(Target.EntityTarget target) {
				return Optional.of(Component.literal("Custom.Name (Chest)"));
			}

			@Override
			public Optional<Component> block(Target.BlockTarget target) {
				return Optional.empty();
			}
		};
		SelectorPreciseTargetLabels labels = new SelectorPreciseTargetLabels(
			snapshot(1, Map.of(
				"precise:block", candidate("c-block", new Target.BlockTarget(DIMENSION, 1, 2, 3, "minecraft:stone"), "block"),
				"precise:entity", candidate("c-entity", new Target.EntityTarget(DIMENSION, new UUID(4, 4)), "entity"),
				"precise:location", candidate("c-location", new Target.LocationTarget(DIMENSION, 1.0, 2.0, 3.0), "location"))),
			target -> ClientTargetNameResolver.resolve(target, lookup));

		Component literal = labels.detail(choice("precise:entity"));
		assertEquals("Custom.Name (Chest)", literal.getString());
		assertInstanceOf(PlainTextContents.LiteralContents.class, literal.getContents(),
			"a literal name with dots and spaces is never re-read as a translation key");

		TranslatableContents unknown = assertInstanceOf(TranslatableContents.class,
			labels.detail(choice("precise:block")).getContents());
		assertEquals("pingforit.target.unknown", unknown.getKey());

		TranslatableContents here = assertInstanceOf(TranslatableContents.class,
			labels.detail(choice("precise:location")).getContents());
		assertEquals("pingforit.target.here", here.getKey());
	}

	@Test
	void detailIsDetachedFromTheResolvedComponent() {
		MutableComponent source = Component.literal("Chest");
		SelectorPreciseTargetLabels labels = new SelectorPreciseTargetLabels(
			snapshot(1, Map.of("precise:block", candidate("c-block",
				new Target.BlockTarget(DIMENSION, 1, 2, 3, "minecraft:chest"), "block"))),
			target -> source);
		Component detail = labels.detail(choice("precise:block"));

		assertNotSame(source, detail, "the paint payload must not alias a live component");
		assertEquals("Chest", detail.getString());
		source.append(" mutated");
		assertEquals("Chest", detail.getString(), "later mutation of the resolved component leaves the paint unchanged");
	}

	private static SpatialSelectorSession.CapturedTarget candidate(String id, Target target, String typeId) {
		var classification = TargetTypeCatalog.builtIn().findById(typeId).orElseThrow();
		return new SpatialSelectorSession.CapturedTarget(id, new ResolvedTarget(target, classification),
			Optional.empty(), Optional.empty());
	}

	private static SpatialSelectorSession.Snapshot snapshot(long revision,
		Map<String, SpatialSelectorSession.CapturedTarget> choices) {
		SpatialSelectorSession.PreciseFrame frame = choices == null ? null
			: new SpatialSelectorSession.PreciseFrame(revision, choices, Map.of());
		return new SpatialSelectorSession.Snapshot(true, "selector:1", null, null,
			SpatialInventoryView.Status.UNKNOWN, null, null, null, frame);
	}

	private static SpatialController.ChoiceView choice(String id) {
		return new SpatialController.ChoiceView(id, "pingforit.spatial.target_type.x", id,
			false, false, false, false, false, 0.0, 60.0, null);
	}

	private static SpatialController.ChoiceView disabledChoice(String id) {
		return new SpatialController.ChoiceView(id, "pingforit.spatial.target_type.x", null,
			false, true, false, false, false, 0.0, 60.0, null);
	}

	private static SpatialController.ChoiceView backChoice() {
		return new SpatialController.ChoiceView("selector:1:precise:back", "pingforit.spatial.back", null,
			true, false, false, false, false, 0.0, 60.0, null);
	}
}
