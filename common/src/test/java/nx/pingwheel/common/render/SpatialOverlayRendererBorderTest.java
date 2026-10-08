package nx.pingwheel.common.render;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Border-colour projection from the real selector facade: a choice carrying a
 * detached Ping Type outline keeps that catalog colour in both focus states,
 * while a choice without one keeps the established fallbacks, including the
 * legacy {@code ping:} action form. The exit transition retains the detached
 * paint data, so a removed typed entry keeps its colour for its exit interval.
 */
class SpatialOverlayRendererBorderTest {

	private static final SpatialSelectorSession.ListGeometry GEOMETRY =
		new SpatialSelectorSession.ListGeometry(4, 180, 17, 23, 19, 85);

	private static SpatialController.ChoiceView rootChoice(String id, long now) {
		var classification = TargetTypeCatalog.builtIn().findById("entity").orElseThrow();
		Target value = new Target.EntityTarget("minecraft:overworld",
			UUID.nameUUIDFromBytes("ordinary".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		var target = new SpatialSelectorSession.CapturedTarget("ordinary", new ResolvedTarget(value, classification),
			Optional.empty(), Optional.empty());
		var fence = new SpatialSelectorSession.ContentFence(31, 42, 53, target.candidateId());
		var settings = new SpatialSelectorSettings.Snapshot(30, 90, 120, 140, new BigDecimal("0.5"),
			false, 250, true, false);
		var session = new SpatialSelectorSession<String>(target, Map.of(), settings, fence,
			(captured, contentFence) -> null, GEOMETRY);
		session.open(now);
		return session.snapshot().radial().menus().getFirst().choices().stream()
			.filter(choice -> choice.id().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void realFacadePingTypeChoiceKeepsItsCatalogOutlineColourInBothFocusStates() {
		var danger = rootChoice("danger", 0);
		PingType type = PingTypeCatalog.builtIn().findById("danger").orElseThrow();

		assertEquals(type.outlineColor(), danger.outlineColor());
		assertEquals(0xFF000000 | type.outlineColor(), SpatialOverlayRenderer.nodeBorderColor(danger, true));
		assertEquals(0xFF000000 | type.outlineColor(), SpatialOverlayRenderer.nodeBorderColor(danger, false));
	}

	@Test
	void untypedAndLegacyChoicesKeepTheEstablishedFallbacks() {
		var cancel = rootChoice("cancel-marker", 0);
		assertNull(cancel.outlineColor());
		assertEquals(SpatialOverlayRenderer.BORDER_SELECTED_FALLBACK,
			SpatialOverlayRenderer.nodeBorderColor(cancel, true));
		assertEquals(SpatialOverlayRenderer.BORDER_COLOR,
			SpatialOverlayRenderer.nodeBorderColor(cancel, false));

		var legacy = new SpatialController.ChoiceView("legacy", "label", "ping:danger", false, false, false,
			false, true, 0.0, 55.0, null);
		PingType danger = PingTypeCatalog.builtIn().findById("danger").orElseThrow();
		assertEquals(0xFF000000 | danger.outlineColor(), SpatialOverlayRenderer.nodeBorderColor(legacy, true));
		assertEquals(SpatialOverlayRenderer.BORDER_COLOR, SpatialOverlayRenderer.nodeBorderColor(legacy, false));
	}

	@Test
	void exitTransitionRetainsTheDetachedTypedBorderColour() {
		var danger = rootChoice("danger", 0);
		var transitions = new SpatialOverlayTransitions<String, SpatialOverlayRenderer.Paint>(160_000_000L, 8, 0.92);
		var paint = new SpatialOverlayRenderer.NodePaint(danger, Component.literal("danger"), true, 0.0);
		var target = new SpatialOverlayTransitions.Target<String, SpatialOverlayRenderer.Paint>(
			"danger", 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, paint);
		transitions.update(List.of(target), 0L, false);
		var exiting = transitions.update(List.of(), 1_000_000L, false);

		assertEquals(1, exiting.size());
		var retained = assertInstanceOf(SpatialOverlayRenderer.NodePaint.class, exiting.getFirst().data());
		assertEquals(danger.outlineColor(), retained.choice().outlineColor());
		assertEquals(0xFF000000 | danger.outlineColor(),
			SpatialOverlayRenderer.nodeBorderColor(retained.choice(), retained.selected()));
	}
}
