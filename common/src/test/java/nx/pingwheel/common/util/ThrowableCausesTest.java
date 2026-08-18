package nx.pingwheel.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the bounded/cycle-safe root-cause walk: no Minecraft
 * classes, no bootstrap, no reflection — the cycle case uses an overridden
 * {@code getCause()} because Java's own {@code initCause} rejects cause
 * cycles by design, and the hostile case overrides {@code getCause()} to
 * throw, proving the class-name helper can never let a corrupt chain escape
 * the logging call.
 */
class ThrowableCausesTest {

	@Test
	void noCauseReturnsTheThrowableItself() {
		IllegalStateException error = new IllegalStateException("boom");

		assertSame(error, ThrowableCauses.rootCause(error));
		assertEquals(IllegalStateException.class.getName(), ThrowableCauses.rootCauseClassName(error));
	}

	@Test
	void unwrapsWrapperExceptionsDownToTheBottomCause() {
		RuntimeException leaf = new RuntimeException("leaf");
		IllegalStateException middle = new IllegalStateException("middle", leaf);
		Exception top = new Exception("top", middle);

		assertSame(leaf, ThrowableCauses.rootCause(top));
		assertEquals(RuntimeException.class.getName(), ThrowableCauses.rootCauseClassName(top));
	}

	@Test
	void deepChainWalkIsBounded() {
		// 200 causes: far deeper than MAX_DEPTH, so the walk must stop early
		// and can never return the actual leaf.
		Throwable leaf = new RuntimeException("leaf");

		Throwable current = leaf;

		for (int i = 0; i < 200; i++) {
			current = new IllegalStateException("wrap " + i, current);
		}

		Throwable root = ThrowableCauses.rootCause(current);

		assertNotNull(root);
		assertNotSame(leaf, root);
		assertEquals(IllegalStateException.class.getName(), root.getClass().getName());
	}

	@Test
	void selfReferentialCauseChainTerminates() {
		// Java's initCause refuses self-causation, so build the loop through
		// an overridden getCause(): a -> b -> a. The identity-visited walk
		// must stop on the revisit instead of looping forever.
		LoopingThrowable a = new LoopingThrowable(null);
		LoopingThrowable b = new LoopingThrowable(a);
		a.target = b;

		Throwable root = ThrowableCauses.rootCause(a);

		assertTrue(root == a || root == b);
		assertEquals(LoopingThrowable.class.getName(), root.getClass().getName());
	}

	@Test
	void hostileGetCauseFallbackReturnsTopLevelClassName() {
		// A hostile or corrupt getCause() override can throw instead of
		// returning a cause; rootCauseClassName must contain that failure
		// inside the diagnostic helper and fall back to the top-level
		// throwable's class name — for RuntimeException and AssertionError
		// alike.
		ThrowingCauseThrowable runtimeThrower = new ThrowingCauseThrowable(() -> {
			throw new RuntimeException("from getCause");
		});
		assertEquals(ThrowingCauseThrowable.class.getName(), ThrowableCauses.rootCauseClassName(runtimeThrower));

		ThrowingCauseThrowable assertionThrower = new ThrowingCauseThrowable(() -> {
			throw new AssertionError("from getCause");
		});
		assertEquals(ThrowingCauseThrowable.class.getName(), ThrowableCauses.rootCauseClassName(assertionThrower));
	}

	/**
	 * A throwable whose cause comes from an overridable target field, letting
	 * the test build cause chains Java's own API would refuse.
	 */
	private static final class LoopingThrowable extends Throwable {

		private Throwable target;

		LoopingThrowable(Throwable target) {
			this.target = target;
		}

		@Override
		public Throwable getCause() {
			return target;
		}
	}

	/**
	 * A throwable whose {@code getCause()} always throws instead of
	 * returning a cause, simulating a hostile or corrupt override in the
	 * wild. The thrown throwable is supplied through a {@link Runnable} so
	 * the override never has to declare or sneaky-rethrow a checked type.
	 */
	private static final class ThrowingCauseThrowable extends Throwable {

		private final Runnable getCauseAction;

		ThrowingCauseThrowable(Runnable getCauseAction) {
			this.getCauseAction = getCauseAction;
		}

		@Override
		public Throwable getCause() {
			getCauseAction.run();
			return null;
		}
	}
}
