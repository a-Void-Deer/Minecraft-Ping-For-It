package nx.pingwheel.common.testing;

/**
 * One runnable test case: a stable display name plus the body to execute.
 *
 * <p>This is the offline stand-in for a JUnit {@code @Test} method: the name is
 * the upstream test identity ({@code "<dir>/<Class>.<method>"}) so a failing case
 * can be traced straight back to the 1.21.1 source it was ported from.
 */
public interface TestCase {

	/** Stable display name, e.g. {@code "marker/MarkerWinnerTest.latestArrivalWins"}. */
	String name();

	/** Runs the case; any {@link Throwable} counts as a failure. */
	void run() throws Exception;
}
