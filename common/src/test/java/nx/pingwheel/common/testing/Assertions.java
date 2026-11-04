package nx.pingwheel.common.testing;

import java.util.Arrays;
import java.util.Objects;

/**
 * Dependency-free assertions with JUnit 5's names and overload shapes.
 *
 * <p>Ported upstream tests keep their assertion calls unchanged; only the static
 * import is rewritten. Every failure throws {@link AssertionError} carrying the
 * expected and actual value, so a broken case cannot pass silently.
 */
public final class Assertions {

	private Assertions() {
	}

	private static String withMessage(String message) {
		return message == null || message.isEmpty() ? "" : message + " ==> ";
	}

	public static void fail(String message) {
		throw new AssertionError(message == null ? "assertion failed" : message);
	}

	public static void fail() {
		fail("assertion failed");
	}

	public static void assertTrue(boolean condition) {
		assertTrue(condition, null);
	}

	public static void assertTrue(boolean condition, String message) {
		if (!condition) {
			fail(withMessage(message) + "expected: <true> but was: <false>");
		}
	}

	public static void assertFalse(boolean condition) {
		assertFalse(condition, null);
	}

	public static void assertFalse(boolean condition, String message) {
		if (condition) {
			fail(withMessage(message) + "expected: <false> but was: <true>");
		}
	}

	public static void assertNull(Object actual) {
		assertNull(actual, null);
	}

	public static void assertNull(Object actual, String message) {
		if (actual != null) {
			fail(withMessage(message) + "expected: <null> but was: <" + actual + ">");
		}
	}

	public static void assertNotNull(Object actual) {
		assertNotNull(actual, null);
	}

	public static void assertNotNull(Object actual, String message) {
		if (actual == null) {
			fail(withMessage(message) + "expected: not <null>");
		}
	}

	public static void assertSame(Object expected, Object actual) {
		assertSame(expected, actual, null);
	}

	public static void assertSame(Object expected, Object actual, String message) {
		if (expected != actual) {
			fail(withMessage(message) + "expected: <" + expected + "> to be the same instance as: <" + actual + ">");
		}
	}

	public static void assertNotSame(Object unexpected, Object actual) {
		assertNotSame(unexpected, actual, null);
	}

	public static void assertNotSame(Object unexpected, Object actual, String message) {
		if (unexpected == actual) {
			fail(withMessage(message) + "expected: not the same instance as: <" + actual + ">");
		}
	}

	public static void assertEquals(Object expected, Object actual) {
		assertEquals(expected, actual, null);
	}

	public static void assertEquals(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) {
			fail(withMessage(message) + "expected: <" + expected + "> but was: <" + actual + ">");
		}
	}

	public static void assertEquals(int expected, int actual) {
		assertEquals((Object) expected, (Object) actual, null);
	}

	public static void assertEquals(int expected, int actual, String message) {
		assertEquals((Object) expected, (Object) actual, message);
	}

	public static void assertEquals(long expected, long actual) {
		assertEquals((Object) expected, (Object) actual, null);
	}

	public static void assertEquals(long expected, long actual, String message) {
		assertEquals((Object) expected, (Object) actual, message);
	}

	public static void assertEquals(double expected, double actual) {
		assertEquals(expected, actual, 0.0d, null);
	}

	public static void assertEquals(double expected, double actual, String message) {
		assertEquals(expected, actual, 0.0d, message);
	}

	public static void assertEquals(double expected, double actual, double delta) {
		assertEquals(expected, actual, delta, null);
	}

	public static void assertEquals(double expected, double actual, double delta, String message) {
		if (Double.compare(expected, actual) != 0 && !(Math.abs(expected - actual) <= Math.abs(delta))) {
			fail(withMessage(message) + "expected: <" + expected + "> but was: <" + actual + "> (delta <" + delta + ">)");
		}
	}

	public static void assertEquals(float expected, float actual, float delta) {
		if (Float.compare(expected, actual) != 0 && !(Math.abs(expected - actual) <= Math.abs(delta))) {
			fail("expected: <" + expected + "> but was: <" + actual + "> (delta <" + delta + ">)");
		}
	}

	public static void assertNotEquals(Object unexpected, Object actual) {
		assertNotEquals(unexpected, actual, null);
	}

	public static void assertNotEquals(Object unexpected, Object actual, String message) {
		if (Objects.equals(unexpected, actual)) {
			fail(withMessage(message) + "expected: not equal but was: <" + actual + ">");
		}
	}

	public static void assertNotEquals(int unexpected, int actual) {
		assertNotEquals((Object) unexpected, (Object) actual, null);
	}

	public static void assertNotEquals(int unexpected, int actual, String message) {
		assertNotEquals((Object) unexpected, (Object) actual, message);
	}

	public static void assertNotEquals(long unexpected, long actual) {
		assertNotEquals((Object) unexpected, (Object) actual, null);
	}

	public static void assertNotEquals(long unexpected, long actual, String message) {
		assertNotEquals((Object) unexpected, (Object) actual, message);
	}

	public static void assertNotEquals(double unexpected, double actual, double delta) {
		if (Double.compare(unexpected, actual) == 0 || Math.abs(unexpected - actual) <= Math.abs(delta)) {
			fail("expected: not equal but was: <" + actual + "> (delta <" + delta + ">)");
		}
	}

	public static void assertArrayEquals(int[] expected, int[] actual) {
		if (!Arrays.equals(expected, actual)) {
			fail("expected: <" + Arrays.toString(expected) + "> but was: <" + Arrays.toString(actual) + ">");
		}
	}

	public static void assertArrayEquals(long[] expected, long[] actual) {
		if (!Arrays.equals(expected, actual)) {
			fail("expected: <" + Arrays.toString(expected) + "> but was: <" + Arrays.toString(actual) + ">");
		}
	}

	public static void assertArrayEquals(byte[] expected, byte[] actual) {
		if (!Arrays.equals(expected, actual)) {
			fail("expected: <" + Arrays.toString(expected) + "> but was: <" + Arrays.toString(actual) + ">");
		}
	}

	public static void assertArrayEquals(char[] expected, char[] actual) {
		if (!Arrays.equals(expected, actual)) {
			fail("expected: <" + Arrays.toString(expected) + "> but was: <" + Arrays.toString(actual) + ">");
		}
	}

	public static void assertArrayEquals(boolean[] expected, boolean[] actual) {
		if (!Arrays.equals(expected, actual)) {
			fail("expected: <" + Arrays.toString(expected) + "> but was: <" + Arrays.toString(actual) + ">");
		}
	}

	public static void assertArrayEquals(Object[] expected, Object[] actual) {
		if (!Arrays.equals(expected, actual)) {
			fail("expected: <" + Arrays.toString(expected) + "> but was: <" + Arrays.toString(actual) + ">");
		}
	}

	public static void assertArrayEquals(double[] expected, double[] actual, double delta) {
		if (expected == null || actual == null) {
			if (expected != actual) {
				fail("expected: <" + Arrays.toString(expected) + "> but was: <" + Arrays.toString(actual) + ">");
			}
			return;
		}
		if (expected.length != actual.length) {
			fail("array lengths differ: expected <" + expected.length + "> but was <" + actual.length + ">");
		}
		for (int i = 0; i < expected.length; i++) {
			if (Double.compare(expected[i], actual[i]) != 0 && Math.abs(expected[i] - actual[i]) > Math.abs(delta)) {
				fail("arrays differ at index " + i + ": expected <" + expected[i] + "> but was <" + actual[i] + ">");
			}
		}
	}

	public static <T> T assertInstanceOf(Class<T> expectedType, Object actual) {
		if (!expectedType.isInstance(actual)) {
			fail("expected instance of: <" + expectedType.getName() + "> but was: <"
				+ (actual == null ? "null" : actual.getClass().getName()) + ">");
		}
		return expectedType.cast(actual);
	}

	public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
		return assertThrows(expectedType, executable, null);
	}

	public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable, String message) {
		try {
			executable.execute();
		} catch (Throwable thrown) {
			if (expectedType.isInstance(thrown)) {
				return expectedType.cast(thrown);
			}
			AssertionError error = new AssertionError(withMessage(message) + "expected: <"
				+ expectedType.getName() + "> but was: <" + thrown.getClass().getName() + ">: " + thrown.getMessage());
			error.initCause(thrown);
			throw error;
		}
		throw new AssertionError(withMessage(message) + "expected: <" + expectedType.getName()
			+ "> but nothing was thrown");
	}

	public static void assertDoesNotThrow(Executable executable) {
		assertDoesNotThrow(executable, null);
	}

	public static void assertDoesNotThrow(Executable executable, String message) {
		try {
			executable.execute();
		} catch (Throwable thrown) {
			AssertionError error = new AssertionError(withMessage(message) + "unexpected exception: <"
				+ thrown.getClass().getName() + ">: " + thrown.getMessage());
			error.initCause(thrown);
			throw error;
		}
	}
}
