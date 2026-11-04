package nx.pingwheel.common.screen;

import net.minecraft.network.chat.Component;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.ServerConfigSnapshot;
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
	void lockedCycleMovesOnlyBetweenNormalAndDetailed() {
		assertEquals(LoggingProfile.DEEP, LoggingProfileSession.next(LoggingProfile.NORMAL, false));
		assertEquals(LoggingProfile.NORMAL, LoggingProfileSession.next(LoggingProfile.DEEP, false));
		assertEquals(LoggingProfile.NORMAL, LoggingProfileSession.next(LoggingProfile.DEBUG, false));
	}

	@Test
	void unlockedCycleUsesAllThreeCandidates() {
		assertEquals(LoggingProfile.DEEP, LoggingProfileSession.next(LoggingProfile.NORMAL, true));
		assertEquals(LoggingProfile.DEBUG, LoggingProfileSession.next(LoggingProfile.DEEP, true));
		assertEquals(LoggingProfile.NORMAL, LoggingProfileSession.next(LoggingProfile.DEBUG, true));
	}

	@Test
	void shiftClickOnlyUnlocksThisRowsCandidateWithoutSelectingOrRepeatingTheCycle() {
		var session = new LoggingProfileSession();
		var current = new AtomicReference<>(LoggingProfile.NORMAL);
		var selected = new ArrayList<LoggingProfile>();
		var shift = new AtomicBoolean(true);
		var button = button(session, LoggingProfileSession.Side.CLIENT, current, selected, shift);
		assertEquals("Normal", button.getMessage().getString());

		button.onClick(0, 0);

		assertTrue(session.isUnlocked(LoggingProfileSession.Side.CLIENT));
		assertFalse(session.isUnlocked(LoggingProfileSession.Side.SERVER), "the other row stays locked");
		assertEquals(LoggingProfile.NORMAL, current.get());
		assertTrue(selected.isEmpty(), "unlocking must not apply a value");
		assertEquals("Normal", button.getMessage().getString());

		button.onClick(0, 0);

		assertEquals(LoggingProfile.NORMAL, current.get());
		assertTrue(selected.isEmpty(), "an already-unlocked Shift click still does not cycle");

		shift.set(false);
		button.onClick(0, 0);

		assertEquals(LoggingProfile.DEEP, current.get());
		assertEquals(List.of(LoggingProfile.DEEP), selected);
	}

	@Test
	void keyboardActivationCyclesWithoutEverUnlockingDebugByItself() {
		var session = new LoggingProfileSession();
		var current = new AtomicReference<>(LoggingProfile.NORMAL);
		var selected = new ArrayList<LoggingProfile>();
		var button = button(session, LoggingProfileSession.Side.CLIENT, current, selected, new AtomicBoolean(false));

		button.onPress();
		button.onPress();

		assertEquals(LoggingProfile.NORMAL, current.get());
		assertEquals(List.of(LoggingProfile.DEEP, LoggingProfile.NORMAL), selected);
		assertFalse(session.isUnlocked(LoggingProfileSession.Side.CLIENT));
		assertFalse(session.isUnlocked(LoggingProfileSession.Side.SERVER));

		session.unlock(LoggingProfileSession.Side.CLIENT);
		button.onPress();
		button.onPress();
		button.onPress();

		assertEquals(LoggingProfile.NORMAL, current.get());
		assertEquals(List.of(LoggingProfile.DEEP, LoggingProfile.NORMAL, LoggingProfile.DEEP,
			LoggingProfile.DEBUG, LoggingProfile.NORMAL), selected,
			"an existing session unlock enables Debug for keyboard cycling but the keyboard never grants it");
	}

	@Test
	void wheelInputDoesNotUnlockOrSelect() {
		var session = new LoggingProfileSession();
		var current = new AtomicReference<>(LoggingProfile.NORMAL);
		var selected = new ArrayList<LoggingProfile>();
		var button = button(session, LoggingProfileSession.Side.CLIENT, current, selected, new AtomicBoolean(false));

		assertFalse(button.mouseScrolled(0, 0, 0, 1));

		assertFalse(session.isUnlocked(LoggingProfileSession.Side.CLIENT));
		assertEquals(LoggingProfile.NORMAL, current.get());
		assertTrue(selected.isEmpty());
	}

	@Test
	void serverRevealGestureDoesNotDirtyOrPlanTheDraft() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, new ServerConfigSnapshot(
			true, ChannelMode.AUTO, true, 1000, 5, 7, InventoryConfigValues.defaults(), true,
			LoggingProfile.DEEP)));
		var session = new LoggingProfileSession();
		var button = new LoggingProfileButton(
			() -> Component.literal(caption(model.loggingProfile())),
			model::loggingProfile,
			() -> session.isUnlocked(LoggingProfileSession.Side.SERVER),
			() -> {
				if (model.canEdit()) session.unlock(LoggingProfileSession.Side.SERVER);
			},
			model::setLoggingProfile,
			() -> true);

		button.onClick(0, 0);

		assertTrue(session.isUnlocked(LoggingProfileSession.Side.SERVER));
		assertEquals(LoggingProfile.DEEP, model.loggingProfile());
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void inactiveServerRowCanNeitherSelectNorUnlock() {
		var session = new LoggingProfileSession();
		var current = new AtomicReference<>(LoggingProfile.DEBUG);
		var selected = new ArrayList<LoggingProfile>();
		var button = button(session, LoggingProfileSession.Side.SERVER, current, selected, new AtomicBoolean(true));
		button.active = false;

		button.onClick(0, 0);
		button.onPress();

		assertFalse(session.isUnlocked(LoggingProfileSession.Side.SERVER));
		assertEquals(LoggingProfile.DEBUG, current.get());
		assertTrue(selected.isEmpty());
	}

	private static LoggingProfileButton button(
		LoggingProfileSession session,
		LoggingProfileSession.Side side,
		AtomicReference<LoggingProfile> current,
		List<LoggingProfile> selected,
		AtomicBoolean shift
	) {
		return new LoggingProfileButton(
			() -> Component.literal(caption(current.get())),
			current::get,
			() -> session.isUnlocked(side),
			() -> session.unlock(side),
			profile -> {
				current.set(profile);
				selected.add(profile);
			},
			shift::get);
	}

	private static String caption(LoggingProfile profile) {
		return switch (profile) {
			case NORMAL -> "Normal";
			case DEEP -> "Detailed";
			case DEBUG -> "Debug";
		};
	}
}
