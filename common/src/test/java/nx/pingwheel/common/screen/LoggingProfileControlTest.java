package nx.pingwheel.common.screen;

import net.minecraft.network.chat.Component;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class LoggingProfileControlTest {

	@Test
	void ordinaryRotationMovesOnlyBetweenNormalAndDetailed() {
		assertEquals(LoggingProfile.DEEP, LoggingProfileButton.next(LoggingProfile.NORMAL, false));
		assertEquals(LoggingProfile.NORMAL, LoggingProfileButton.next(LoggingProfile.DEEP, false));
		assertEquals(LoggingProfile.NORMAL, LoggingProfileButton.next(LoggingProfile.DEBUG, false));
	}

	@Test
	void heldAltTemporarilyIncludesDebugInTheRotation() {
		assertEquals(LoggingProfile.DEEP, LoggingProfileButton.next(LoggingProfile.NORMAL, true));
		assertEquals(LoggingProfile.DEBUG, LoggingProfileButton.next(LoggingProfile.DEEP, true));
		assertEquals(LoggingProfile.NORMAL, LoggingProfileButton.next(LoggingProfile.DEBUG, true));
	}

	@Test
	void eachPressReadsTheCurrentAltState() {
		var current = new AtomicReference<>(LoggingProfile.NORMAL);
		var selected = new ArrayList<LoggingProfile>();
		var alt = new AtomicBoolean(false);
		var button = button(current, selected, alt);

		button.onPress();
		assertEquals(LoggingProfile.DEEP, current.get());

		alt.set(true);
		button.onPress();
		assertEquals(LoggingProfile.DEBUG, current.get());
		assertEquals("Logging Detail: Debug", button.getMessage().getString());

		alt.set(false);
		button.onPress();
		assertEquals(LoggingProfile.NORMAL, current.get(), "a normal press leaves Debug");

		alt.set(true);
		button.onPress();
		button.onPress();
		assertEquals(LoggingProfile.DEBUG, current.get(),
			"Alt remains a live per-press modifier rather than a one-time unlock");
		assertEquals(List.of(LoggingProfile.DEEP, LoggingProfile.DEBUG, LoggingProfile.NORMAL,
			LoggingProfile.DEEP, LoggingProfile.DEBUG), selected);
	}

	@Test
	void altClickSelectsTheNextValueInsteadOfOnlyRevealingACandidate() {
		var current = new AtomicReference<>(LoggingProfile.NORMAL);
		var selected = new ArrayList<LoggingProfile>();
		var button = button(current, selected, new AtomicBoolean(true));

		button.onClick(0, 0);

		assertEquals(LoggingProfile.DEEP, current.get());
		assertEquals(List.of(LoggingProfile.DEEP), selected, "the same activation must apply the selected value");
		assertEquals("Logging Detail: Detailed", button.getMessage().getString());

		button.onClick(0, 0);

		assertEquals(LoggingProfile.DEBUG, current.get());
		assertEquals(List.of(LoggingProfile.DEEP, LoggingProfile.DEBUG), selected);
	}

	@Test
	void releasingAltRetractsTheCandidateButKeepsTheVisibleDebugValue() {
		var current = new AtomicReference<>(LoggingProfile.DEBUG);
		var selected = new ArrayList<LoggingProfile>();
		var alt = new AtomicBoolean(false);
		var button = button(current, selected, alt);

		alt.set(true);
		alt.set(false);

		assertEquals(LoggingProfile.DEBUG, current.get(), "releasing Alt does not change the selected value");
		assertEquals("Logging Detail: Debug", button.getMessage().getString());

		button.onPress();

		assertEquals(LoggingProfile.NORMAL, current.get(), "the next ordinary press cannot stay in Debug");
		assertEquals(List.of(LoggingProfile.NORMAL), selected);
	}

	@Test
	void wheelInputDoesNotSelectAValue() {
		var current = new AtomicReference<>(LoggingProfile.NORMAL);
		var selected = new ArrayList<LoggingProfile>();
		var button = button(current, selected, new AtomicBoolean(true));

		assertFalse(button.mouseScrolled(0, 0, 0, 1));

		assertEquals(LoggingProfile.NORMAL, current.get());
		assertTrue(selected.isEmpty());
	}

	@Test
	void inactiveServerRowCanNeitherSelectNorUseAlt() {
		var current = new AtomicReference<>(LoggingProfile.DEBUG);
		var selected = new ArrayList<LoggingProfile>();
		var button = button(current, selected, new AtomicBoolean(true));
		button.active = false;

		button.onClick(0, 0);
		button.onPress();

		assertEquals(LoggingProfile.DEBUG, current.get());
		assertTrue(selected.isEmpty());
	}

	@Test
	void serverDraftSelectionUsesTheSameTemporaryAltCandidateWithoutPublishingRuntime() {
		LoggingProfile previousRuntime = Diagnostics.profile(DiagnosticSide.SERVER);
		try {
			var model = new ServerSettingsModel(true);
			long requestId = model.beginExpansion();
			assertTrue(model.applySnapshot(requestId, new ServerConfigSnapshot(
				true, ChannelMode.AUTO, true, 1000, 5, 7, InventoryConfigValues.defaults(), true,
				LoggingProfile.DEEP)));
			var button = new LoggingProfileButton(
				() -> Component.literal(caption(model.loggingProfile())),
				model::loggingProfile,
				model::setLoggingProfile,
				() -> true);

			button.onPress();

			assertEquals(LoggingProfile.DEBUG, model.loggingProfile());
			assertTrue(model.dirty());
			assertEquals(LoggingProfile.DEBUG, model.updatePlan().orElseThrow().loggingProfile());
			assertEquals(previousRuntime, Diagnostics.profile(DiagnosticSide.SERVER),
				"the temporary Debug candidate stays in the draft");
		} finally {
			Diagnostics.setProfile(DiagnosticSide.SERVER, previousRuntime);
		}
	}

	private static LoggingProfileButton button(
		AtomicReference<LoggingProfile> current,
		List<LoggingProfile> selected,
		AtomicBoolean alt
	) {
		return new LoggingProfileButton(
			() -> Component.literal("Logging Detail: " + caption(current.get())),
			current::get,
			profile -> {
				current.set(profile);
				selected.add(profile);
			},
			alt::get);
	}

	private static String caption(LoggingProfile profile) {
		return switch (profile) {
			case NORMAL -> "Normal";
			case DEEP -> "Detailed";
			case DEBUG -> "Debug";
		};
	}
}
