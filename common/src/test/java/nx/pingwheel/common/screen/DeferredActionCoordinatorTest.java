package nx.pingwheel.common.screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeferredActionCoordinatorTest {
	@Test
	void runsStructuralActionAfterNativeDispatchReturns() {
		final var coordinator = new DeferredActionCoordinator();
		final List<String> events = new ArrayList<>();

		final boolean handled = coordinator.dispatch(() -> {
			events.add("callback");
			coordinator.runAfterDispatch(() -> events.add("transition"));
			events.add("native-focus-update");
			return true;
		});

		assertEquals(true, handled);
		assertEquals(List.of("callback", "native-focus-update", "transition"), events);
	}

	@Test
	void waitsForOutermostNestedDispatchAndKeepsLatestTransition() {
		final var coordinator = new DeferredActionCoordinator();
		final List<String> events = new ArrayList<>();

		coordinator.dispatch(() -> {
			coordinator.runAfterDispatch(() -> events.add("obsolete"));
			coordinator.dispatch(() -> {
				coordinator.runAfterDispatch(() -> events.add("final"));
				events.add("inner-return");
				return null;
			});
			events.add("outer-return");
			return null;
		});

		assertEquals(List.of("inner-return", "outer-return", "final"), events);
	}

	@Test
	void dropsTransitionWhenDispatchFails() {
		final var coordinator = new DeferredActionCoordinator();
		final List<String> events = new ArrayList<>();

		assertThrows(IllegalStateException.class, () -> coordinator.dispatch(() -> {
			coordinator.runAfterDispatch(() -> events.add("must-not-run"));
			throw new IllegalStateException("failure");
		}));
		coordinator.runAfterDispatch(() -> events.add("later"));

		assertEquals(List.of("later"), events);
	}

	@Test
	void dropsTransitionWhenNestedFailureIsCaughtByOuterDispatch() {
		final var coordinator = new DeferredActionCoordinator();
		final List<String> events = new ArrayList<>();

		coordinator.dispatch(() -> {
			try {
				coordinator.dispatch(() -> {
					coordinator.runAfterDispatch(() -> events.add("must-not-run"));
					throw new IllegalStateException("nested failure");
				});
			} catch (IllegalStateException expected) {
				events.add("caught");
			}
			return null;
		});

		assertEquals(List.of("caught"), events);
	}
}
