package nx.pingwheel.common.client;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.math.RaycastPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the client-side toggle label resolver: each of the three
 * target-selection toggles reads only its own live raycast concern, both
 * states render, only the state word is colored, and non-toggle choices stay
 * with the caller's own resolver.
 */
class SelectorToggleLabelsTest {
	/** Code-defined keys shared with the target-selection notice. */
	private static final String ON_KEY = "notice.pingforit.on";
	private static final String OFF_KEY = "notice.pingforit.off";
	/** The shipped target-selection notice status colors. */
	private static final int ON_COLOR = 0x55FF55;
	private static final int OFF_COLOR = 0xFF5555;

	@Test
	void eachToggleReadsOnlyItsOwnLivePolicyConcern() {
		AtomicReference<RaycastPolicy> policy = new AtomicReference<>(RaycastPolicy.from(false, false, false));
		SelectorToggleLabels labels = new SelectorToggleLabels(policy::get);

		for (SelectorIntent.CaptureToggle toggle : SelectorIntent.CaptureToggle.values())
			assertEquals(OFF_KEY, stateKey(labels.label(choice(toggle))), () -> toggle + " must show Off while every concern is off");

		for (SelectorIntent.CaptureToggle enabled : SelectorIntent.CaptureToggle.values()) {
			policy.set(policyWithOnly(enabled));
			for (SelectorIntent.CaptureToggle toggle : SelectorIntent.CaptureToggle.values())
				assertEquals(toggle == enabled ? ON_KEY : OFF_KEY, stateKey(labels.label(choice(toggle))),
					() -> toggle + " with only " + enabled + " enabled");
		}

		policy.set(RaycastPolicy.from(true, true, true));
		for (SelectorIntent.CaptureToggle toggle : SelectorIntent.CaptureToggle.values())
			assertEquals(ON_KEY, stateKey(labels.label(choice(toggle))), () -> toggle + " must show On while every concern is on");
	}

	@Test
	void onlyTheStateWordIsColoredAndTheNameAndSeparatorStayPlain() {
		SelectorToggleLabels labels = new SelectorToggleLabels(() -> RaycastPolicy.from(false, false, true));
		Component on = labels.label(choice(SelectorIntent.CaptureToggle.FLUIDS));

		TranslatableContents name = assertInstanceOf(TranslatableContents.class, on.getContents());
		assertEquals("pingforit.spatial.toggle.fluids", name.getKey());
		assertNull(on.getStyle().getColor());
		assertEquals(2, on.getSiblings().size());
		Component separator = on.getSiblings().get(0);
		assertInstanceOf(PlainTextContents.LiteralContents.class, separator.getContents());
		assertEquals(": ", ((PlainTextContents.LiteralContents) separator.getContents()).text());
		assertNull(separator.getStyle().getColor());
		assertStateWord(ON_KEY, ON_COLOR, on.getSiblings().get(1));

		SelectorToggleLabels offLabels = new SelectorToggleLabels(() -> RaycastPolicy.from(false, false, false));
		Component off = offLabels.label(choice(SelectorIntent.CaptureToggle.FLUIDS));
		assertStateWord(OFF_KEY, OFF_COLOR, off.getSiblings().get(1));
		assertNull(off.getStyle().getColor());
		assertNull(off.getSiblings().get(0).getStyle().getColor());
	}

	@Test
	void everyResolutionSamplesTheLivePolicyAgain() {
		AtomicReference<RaycastPolicy> policy = new AtomicReference<>(RaycastPolicy.from(false, false, false));
		int[] samples = {0};
		SelectorToggleLabels labels = new SelectorToggleLabels(() -> {
			samples[0]++;
			return policy.get();
		});

		SelectorIntent.CaptureToggle toggle = SelectorIntent.CaptureToggle.ENTITY_BLACKLIST;
		assertEquals(OFF_KEY, stateKey(labels.label(choice(toggle))));
		policy.set(RaycastPolicy.from(false, true, false));
		assertEquals(ON_KEY, stateKey(labels.label(choice(toggle))));
		policy.set(RaycastPolicy.from(false, false, false));
		assertEquals(OFF_KEY, stateKey(labels.label(choice(toggle))));
		assertEquals(3, samples[0], "every draw resolution must sample the live policy");
	}

	@Test
	void nonToggleChoicesKeepTheCallersResolver() {
		SelectorToggleLabels labels = new SelectorToggleLabels(() -> RaycastPolicy.from(false, false, false));

		assertNull(labels.label(choice("pingforit.spatial.content")));
		assertNull(labels.label(choice("pingforit.spatial.toggle.unknown")));
		assertNull(labels.label(null));
		assertNull(labels.label(raw("settings:FLUIDS", null)));
	}

	private static void assertStateWord(String expectedKey, int expectedColor, Component state) {
		TranslatableContents contents = assertInstanceOf(TranslatableContents.class, state.getContents());
		assertEquals(expectedKey, contents.getKey());
		TextColor color = state.getStyle().getColor();
		assertNotNull(color, "the state word must carry the status color");
		assertEquals(expectedColor, color.getValue());
	}

	private static String stateKey(Component label) {
		Component state = label.getSiblings().get(1);
		assertInstanceOf(TranslatableContents.class, state.getContents(), "the state must be the second span");
		return ((TranslatableContents) state.getContents()).getKey();
	}

	private static RaycastPolicy policyWithOnly(SelectorIntent.CaptureToggle toggle) {
		return switch (toggle) {
			case FLUIDS -> RaycastPolicy.from(false, false, true);
			case ENTITY_BLACKLIST -> RaycastPolicy.from(false, true, false);
			case TRANSPARENT_BLOCKS -> RaycastPolicy.from(true, false, false);
		};
	}

	private static SpatialController.ChoiceView choice(SelectorIntent.CaptureToggle toggle) {
		String key = "pingforit.spatial.toggle." + toggle.name().toLowerCase(Locale.ROOT);
		return raw("settings:" + toggle.name(), key);
	}

	private static SpatialController.ChoiceView choice(String label) {
		return raw("choice:" + label, label);
	}

	private static SpatialController.ChoiceView raw(String id, String label) {
		return new SpatialController.ChoiceView(id, label, id, false, false, false, false, false, 0.0, 0.0, null);
	}
}
