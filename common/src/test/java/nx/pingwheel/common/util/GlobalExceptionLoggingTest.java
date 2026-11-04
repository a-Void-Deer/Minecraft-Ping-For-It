package nx.pingwheel.common.util;

import nx.pingwheel.common.Global;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.presentation.PresentationDiagnosticCapture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Global convenience route selects a side, but owns no implicit admission window. */
@NotNullByDefault
class GlobalExceptionLoggingTest {

	@Test
	void explicitSideHelperUsesNormalDisclosureEvenAtErrorSeverity() {
		try (var capture = new PresentationDiagnosticCapture(Level.DEBUG)) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			Global.exception(DiagnosticSide.SERVER, Level.ERROR, "global-side", "fallback", new UnreadableFailure());
			assertEquals(1, capture.events().size());
			var event = capture.events().getFirst();
			assertEquals(Level.ERROR, event.getLevel());
			assertTrue(event.getMessage().getFormattedMessage().contains("side=SERVER site=global-side handling=fallback"));
			assertFalse(event.getMessage().getFormattedMessage().contains("original-private"));
			assertNull(event.getThrown());
		}
	}

	@Test
	void disabledHelperNeverReadsThrowableAccessors() {
		try (var capture = new PresentationDiagnosticCapture(Level.OFF)) {
			for (Level severity : List.of(Level.DEBUG, Level.WARN, Level.ERROR)) {
				Global.exception(DiagnosticSide.CLIENT, severity, "disabled-global", "fallback", new UnreadableFailure());
			}
			assertTrue(capture.events().isEmpty());
		}
	}

	@Test
	void deepHelpersPreserveSideSeverityOriginalGraphAndOneBoundedStringPerCall() {
		try (var capture = new PresentationDiagnosticCapture(Level.DEBUG)) {
			RuntimeException failure = new RuntimeException("original-private", new IllegalArgumentException("root-private"));
			failure.setStackTrace(new StackTraceElement @Unmodifiable [] {
				new StackTraceElement("provider.Inventory", "read", "Inventory.java", 18)
			});
			failure.addSuppressed(new IllegalStateException("suppressed-private"));
			for (DiagnosticSide side : DiagnosticSide.values()) {
				for (Level severity : List.of(Level.DEBUG, Level.WARN, Level.ERROR)) {
					Global.exception(side, severity, "global-graph", "fallback", failure);
				}
			}
			assertEquals(9, capture.events().size());
			assertEquals(List.of(Level.DEBUG, Level.WARN, Level.ERROR, Level.DEBUG, Level.WARN, Level.ERROR,
				Level.DEBUG, Level.WARN, Level.ERROR), capture.events().stream().map(event -> event.getLevel()).toList());
			for (var event : capture.events()) {
				String report = event.getMessage().getFormattedMessage();
				assertTrue(report.contains("original-private"));
				assertTrue(report.contains("root-private"));
				assertTrue(report.contains("suppressed-private"));
				assertTrue(report.contains("provider.Inventory.read (Inventory.java:18)"));
				assertTrue(report.length() <= SafeExceptionReport.MAX_CHARACTERS);
				assertNull(event.getThrown());
			}
		}
	}

	@Test
	void repeatedHelperFailuresHaveNoImplicitWindow() {
		try (var capture = new PresentationDiagnosticCapture()) {
			for (int attempt = 0; attempt < 3; attempt++) {
				Global.exception(DiagnosticSide.SERVER, Level.WARN, "global-repeat", "fallback", new IllegalStateException("original"));
			}
			assertEquals(3, capture.events().size());
			assertTrue(capture.messages().stream().noneMatch(text -> text.contains("channel=")));
		}
	}

	@NotNullByDefault
	private static final class UnreadableFailure extends RuntimeException {
		@Override public String getMessage() { throw new AssertionError("original-private message accessor"); }
		@Override public StackTraceElement @Unmodifiable [] getStackTrace() { throw new AssertionError("stack accessor"); }
		@Override public @Nullable Throwable getCause() { throw new AssertionError("cause accessor"); }
	}
}
