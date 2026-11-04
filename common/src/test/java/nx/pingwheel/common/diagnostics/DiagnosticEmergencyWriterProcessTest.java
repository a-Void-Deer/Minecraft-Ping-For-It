package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real-descriptor smoke test; process-global stream changes stay in the child JVM. */
@NotNullByDefault
class DiagnosticEmergencyWriterProcessTest {

	private static final String ORDINARY_STDERR = "ordinary-stderr-after-self-fault";
	private static final String CHILD_COMPLETE = "descriptor-smoke-complete";
	private static final String PRIVATE_MESSAGE = "private-emergency-smoke-message";

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void processDescriptorWritesAndFlushesNormalSelfFaultWithoutClosingStandardError(@TempDir Path directory)
		throws Exception {
		Path stdoutFile = directory.resolve("child.stdout").toAbsolutePath();
		Path stderrFile = directory.resolve("child.stderr").toAbsolutePath();
		Path java = Path.of(Objects.requireNonNull(System.getProperty("java.home")), "bin",
			File.separatorChar == '\\' ? "java.exe" : "java");
		// Gradle's worker java.class.path need not contain the test runtime. These
		// two code locations suffice for this JDK-only fixture and production path.
		@Unmodifiable List<String> classpath = List.of(classLocation(DescriptorFixture.class).toString(),
			classLocation(DiagnosticEmergencyWriter.class).toString());
		Process child = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8",
			"-Djava.io.tmpdir=" + directory.toAbsolutePath(), "-cp", String.join(File.pathSeparator, classpath),
			DescriptorFixture.class.getName(), stderrFile.toString())
			.directory(directory.toFile())
			.redirectOutput(stdoutFile.toFile())
			.redirectError(stderrFile.toFile())
			.start();
		try {
			assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Isolated descriptor fixture timed out");
			String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
			String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
			assertEquals(0, child.exitValue(), () -> "Child stdout:\n" + stdout + "\nChild stderr:\n" + stderr);
			assertEquals(List.of(CHILD_COMPLETE), stdout.lines().toList());
			@Unmodifiable List<String> records = stderr.lines().toList();
			assertEquals(2, records.size(), () -> "Expected one complete self-fault and later ordinary stderr:\n" + stderr);
			String report = records.getFirst();
			// logging.md: NORMAL self-fault identity is side/site/stage/kind only;
			// the emergency record is newline-terminated and leaves stderr usable.
			assertAll(
				() -> assertTrue(report.startsWith("[PingForIt diagnostic-fault] ")),
				() -> assertTrue(report.contains("side=BOOTSTRAP")),
				() -> assertTrue(report.contains("site=descriptor-smoke")),
				() -> assertTrue(report.contains("stage=SINK")),
				() -> assertTrue(report.contains("kind=RUNTIME")),
				() -> assertFalse(report.contains("class=")),
				() -> assertFalse(report.contains("message=")),
				() -> assertFalse(stderr.contains(PRIVATE_MESSAGE)),
				() -> assertFalse(stderr.contains(IllegalStateException.class.getName())),
				() -> assertEquals(ORDINARY_STDERR, records.getLast()),
				() -> assertTrue(stderr.endsWith("\n")));
		} finally {
			if (child.isAlive()) {
				child.destroyForcibly();
				assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Timed-out descriptor fixture did not terminate");
			}
		}
	}

	private static Path classLocation(Class<?> type) throws URISyntaxException {
		return Path.of(Objects.requireNonNull(type.getProtectionDomain().getCodeSource(),
			"Fixture class requires a code location").getLocation().toURI()).toAbsolutePath();
	}

	@NotNullByDefault
	public static final class DescriptorFixture {
		public static void main(String @Unmodifiable [] arguments) throws Exception {
			PrintStream ordinaryError = System.err;
			ByteArrayOutputStream intercepted = new ByteArrayOutputStream();
			// Only this isolated JVM replaces System.err. Neither the replacement
			// nor the original standard streams/descriptors are ever closed.
			System.setErr(new PrintStream(intercepted, true, StandardCharsets.UTF_8));
			try {
				DiagnosticEmergencyWriter writer = DiagnosticEmergencyWriter.process();
				DiagnosticFaultReporter reporter = new DiagnosticFaultReporter(writer);
				reporter.report(DiagnosticSide.BOOTSTRAP, "descriptor-smoke", DiagnosticFaultReporter.Stage.SINK,
					LoggingProfile.NORMAL, new IllegalStateException(PRIVATE_MESSAGE));
				if (intercepted.size() != 0) throw new AssertionError("Emergency output used System.err instead of its descriptor");
				if (writer.initializations() != 1 || writer.failures() != 0) {
					throw new AssertionError("Real descriptor initialization/write/flush failed");
				}
				// Check complete publication now, not just bytes found after shutdown.
				String published = Files.readString(Path.of(arguments[0]), StandardCharsets.UTF_8);
				if (!published.endsWith("\n") || published.lines().count() != 1) {
					throw new AssertionError("Complete emergency record was not flushed before exit");
				}
			} finally {
				System.setErr(ordinaryError);
			}
			System.err.println(ORDINARY_STDERR);
			System.err.flush();
			if (System.err.checkError()) throw new AssertionError("Emergency writer closed or broke ordinary stderr");
			System.out.println(CHILD_COMPLETE);
			System.out.flush();
			if (System.out.checkError()) throw new AssertionError("Ordinary stdout is not usable");
		}
	}
}
