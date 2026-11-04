package nx.pingwheel.common.testing;

import java.util.List;

/**
 * Zero-dependency test runner for the offline harness.
 *
 * <p>Run it through {@code tools/test-check.sh} (or by hand with
 * {@code java -cp "<main classes>;<test classes>" nx.pingwheel.common.testing.TestRunner}):
 * this never runs through Gradle, because JUnit cannot be resolved without network.
 *
 * <p>Usage:
 * <ul>
 *   <li>no arguments — run every case registered in {@link AllTests};</li>
 *   <li>{@code --list} — print the case names and exit 0;</li>
 *   <li>{@code --filter=<substring>} — only run cases whose name contains it.</li>
 * </ul>
 *
 * <p>Exit code: 0 when every case passed, 1 when at least one failed.
 */
public final class TestRunner {

	private TestRunner() {
	}

	public static void main(String[] args) {
		boolean listOnly = false;
		String filter = null;
		for (String arg : args) {
			if ("--list".equals(arg)) {
				listOnly = true;
			} else if (arg.startsWith("--filter=")) {
				filter = arg.substring("--filter=".length());
			} else {
				System.err.println("unknown argument: " + arg);
				System.err.println("usage: TestRunner [--list] [--filter=<substring>]");
				System.exit(2);
			}
		}

		List<TestCase> cases = AllTests.collect();
		if (filter != null) {
			String needle = filter;
			cases = cases.stream().filter(c -> c.name().contains(needle)).toList();
		}

		if (listOnly) {
			for (TestCase testCase : cases) {
				System.out.println(testCase.name());
			}
			System.out.println("tests=" + cases.size());
			return;
		}

		int passed = 0;
		int failed = 0;
		for (TestCase testCase : cases) {
			try {
				testCase.run();
				passed++;
				System.out.println("PASS " + testCase.name());
			} catch (Throwable failure) {
				failed++;
				System.out.println("FAIL " + testCase.name());
				failure.printStackTrace(System.out);
			}
		}

		System.out.println("tests=" + cases.size() + " passed=" + passed + " failed=" + failed);
		if (failed > 0) {
			System.exit(1);
		}
	}
}
