package nx.pingwheel.common.client.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GestureSkipHoldTest {
	private static final NativeSelectorInput.Frame GRABBED = new NativeSelectorInput.Frame(1, 2000, 1000, 1000, 500, true, true, false);

	@Test void netGuiDisplacementAndInclusiveTimeQualifyWithoutAnotherCallback() {
		var gesture = new GestureSkipHold(); gesture.begin(100, 75, 24);
		gesture.onMove(1, 100, 100, GRABBED, 100);
		gesture.onMove(1, 148, 100, GRABBED, 110);
		assertFalse(gesture.eligible(175), "exact deadzone radius is inside");
		gesture.onMove(1, 150, 100, GRABBED, 120);
		assertFalse(gesture.eligible(174)); assertTrue(gesture.eligible(175));
		gesture.onMove(1, 100, 100, GRABBED, 176);
		assertFalse(gesture.eligible(200), "historical travel cannot replace net displacement");
	}

	@Test void trajectoryIsOrderedAndSingleUseAndResetDiscardsAllPriorState() {
		var gesture = new GestureSkipHold(); gesture.begin(0, 75, 24);
		gesture.onMove(1, 100, 100, GRABBED, 0);
		gesture.onMove(1, 80, 100, GRABBED, 10); gesture.onMove(1, 80, 60, GRABBED, 20);
		assertEquals(List.of(new GestureSkipHold.Sample(-10, 0, 10), new GestureSkipHold.Sample(0, -20, 20)), gesture.take().samples());
		assertFalse(gesture.active()); assertTrue(gesture.take().samples().isEmpty());
		gesture.begin(200, 75, 24); assertFalse(gesture.eligible(1000));
		gesture.onMove(1, 10000, -10000, GRABBED, 210); assertTrue(gesture.take().samples().isEmpty());
	}

	@Test void foreignInvalidScaleAndOwnershipHandoffsNeverInventDisplacement() {
		var gesture = new GestureSkipHold(); gesture.begin(0, 75, 24);
		gesture.onMove(1, 100, 100, GRABBED, 0);
		gesture.onMove(2, 10000, 10000, GRABBED, 10);
		gesture.onMove(1, 120, 100, GRABBED, 20);
		gesture.onMove(1, Double.NaN, 0, GRABBED, 30);
		gesture.onMove(1, 10000, 10000, GRABBED, 40);
		var resized = new NativeSelectorInput.Frame(1, 1000, 500, 1000, 500, true, true, false);
		gesture.synchronize(resized); gesture.onMove(1, 20000, 20000, resized, 50);
		var ungrabbed = new NativeSelectorInput.Frame(1, 1000, 500, 1000, 500, true, false, false);
		gesture.synchronize(ungrabbed); gesture.onMove(1, 30000, 30000, ungrabbed, 60);
		assertFalse(gesture.eligible(75));
		assertEquals(List.of(new GestureSkipHold.Sample(10, 0, 20)), gesture.take().samples());
	}

	@Test void exactly4096ValidNonzeroSamplesRemainEligibleAndTransferOnceWithoutLosingTheirOrder() {
		var gesture = new GestureSkipHold(); gesture.begin(100, 75, 24);
		gesture.onMove(1, 0, 0, GRABBED, 100);
		for (int i = 1; i <= 4096; i++) gesture.onMove(1, i * 2, 0, GRABBED, 100 + i);
		// Neither zero travel nor rejected input spends another history slot.
		gesture.onMove(1, 8192, 0, GRABBED, 4200);
		gesture.onMove(2, 10000, 0, GRABBED, 4200);
		assertTrue(gesture.active()); assertTrue(gesture.eligible(4200));
		var trajectory = gesture.take();
		assertEquals(100, trajectory.pressTimeMillis()); assertEquals(4096, trajectory.samples().size());
		for (int i = 0; i < 4096; i++)
			assertEquals(new GestureSkipHold.Sample(1, 0, 101 + i), trajectory.samples().get(i));
		assertFalse(gesture.active()); assertTrue(gesture.take().samples().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> trajectory.samples().clear());
		gesture.begin(5000, 75, 24); gesture.onMove(1, 0, 0, GRABBED, 5000);
		gesture.onMove(1, 60, 0, GRABBED, 5010); gesture.reset();
		assertEquals(4096, trajectory.samples().size(), "a new hold/reset cannot mutate transferred storage");
	}

	@Test void sample4097DiscardsEverySampleAndCannotResumeUntilANewHold() {
		var gesture = new GestureSkipHold(); gesture.begin(0, 75, 24);
		gesture.onMove(1, 0, 0, GRABBED, 0);
		for (int i = 1; i <= 4096; i++) gesture.onMove(1, i * 2, 0, GRABBED, i);
		assertTrue(gesture.active()); assertTrue(gesture.eligible(4096));
		gesture.onMove(1, 8194, 0, GRABBED, 4097);
		assertFalse(gesture.active()); assertFalse(gesture.eligible(5000));
		gesture.rePrime(); gesture.synchronize(GRABBED);
		for (int i = 0; i < 10000; i++) gesture.onMove(1, i * 2, 0, GRABBED, 5000 + i);
		assertFalse(gesture.active()); assertTrue(gesture.take().samples().isEmpty(), "neither a prefix nor a tail is retained");
		gesture.reset(); assertFalse(gesture.eligible(20000));
		gesture.begin(20000, 75, 24); gesture.onMove(1, 0, 0, GRABBED, 20000);
		gesture.onMove(1, 60, 0, GRABBED, 20010);
		assertTrue(gesture.eligible(20075));
		assertEquals(List.of(new GestureSkipHold.Sample(30, 0, 20010)), gesture.take().samples());
	}
}
