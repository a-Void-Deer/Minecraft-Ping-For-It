package nx.pingwheel.common.util;

import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeExceptionReportTest {

	@Test
	void omitsThrowableMessagesAndIncludesSafeFrameFields() {
		IllegalStateException cause = new IllegalStateException("cause secret");
		cause.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("safe.CauseFrame", "causeMethod", "CauseFile.java", 22)
		});
		IllegalArgumentException top = new IllegalArgumentException("top secret", cause);
		top.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("safe.TopFrame", "topMethod", "TopFile.java", 11)
		});

		String report = SafeExceptionReport.format(top);

		assertFalse(report.contains("top secret"));
		assertFalse(report.contains("cause secret"));
		assertTrue(report.contains("java.lang.IllegalArgumentException"));
		assertTrue(report.contains("safe.TopFrame.topMethod (TopFile.java:11)"));
		assertTrue(report.contains("safe.CauseFrame.causeMethod (CauseFile.java:22)"));
		assertTrue(report.indexOf("java.lang.IllegalArgumentException")
			< report.indexOf("java.lang.IllegalStateException"));
	}

	@Test
	void suppressedEntriesAreBounded() {
		RuntimeException top = new RuntimeException("do not print");

		for (int index = 0; index < SafeExceptionReport.MAX_SUPPRESSED_PER_NODE + 3; index++) {
			IllegalStateException suppressed = new IllegalStateException("suppressed secret " + index);
			suppressed.setStackTrace(new StackTraceElement[0]);
			top.addSuppressed(suppressed);
		}

		String report = SafeExceptionReport.format(top);

		assertTrue(report.contains("suppressed[0]:"));
		assertTrue(report.contains("suppressed: [truncated]"));
		assertFalse(report.contains(
			"suppressed[" + SafeExceptionReport.MAX_SUPPRESSED_PER_NODE + "]:"));
		assertFalse(report.contains("suppressed secret"));
	}

	@Test
	void causeAndSuppressedCyclesTerminate() {
		CycleThrowable causeFirst = new CycleThrowable();
		CycleThrowable causeSecond = new CycleThrowable();
		causeFirst.cause = causeSecond;
		causeSecond.cause = causeFirst;

		CycleThrowable suppressedFirst = new CycleThrowable();
		CycleThrowable suppressedSecond = new CycleThrowable();
		suppressedFirst.addSuppressed(suppressedSecond);
		suppressedSecond.addSuppressed(suppressedFirst);

		String causeReport = SafeExceptionReport.format(causeFirst);
		String suppressedReport = SafeExceptionReport.format(suppressedFirst);

		assertTrue(causeReport.contains("[cycle]"));
		assertTrue(suppressedReport.contains("[cycle]"));
		assertTrue(causeReport.length() < SafeExceptionReport.MAX_CHARACTERS);
		assertTrue(suppressedReport.length() < SafeExceptionReport.MAX_CHARACTERS);
	}

	@Test
	void hostileThrowableAccessorsFailSoftWithoutReadingPayload() {
		HostileThrowable hostile = new HostileThrowable();

		String report = SafeExceptionReport.format(hostile);

		assertTrue(report.contains(HostileThrowable.class.getName()));
		assertTrue(report.contains("[stack unavailable]"));
		assertTrue(report.contains("cause: [unavailable]"));
		assertFalse(report.contains("message secret"));
		assertFalse(report.contains("localized secret"));
		assertFalse(report.contains("toString secret"));
		assertFalse(hostile.messageRead);
		assertFalse(hostile.localizedMessageRead);
		assertFalse(hostile.toStringRead);
	}

	@Test
	void frameAndCharacterCapsUseDeterministicBounds() {
		String longClassName = "frame." + "x".repeat(SafeExceptionReport.MAX_NODE_CHARACTERS * 2);
		RuntimeException top = new RuntimeException();
		StackTraceElement[] frames = new StackTraceElement[SafeExceptionReport.MAX_FRAMES_PER_NODE + 2];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = new StackTraceElement(longClassName, "method", "File.java", index);
		}

		top.setStackTrace(frames);
		String report = SafeExceptionReport.format(top);

		assertTrue(report.length() <= SafeExceptionReport.MAX_CHARACTERS);
		assertTrue(report.contains("[node truncated]"));
		assertFalse(report.contains(longClassName));
	}

	@Test
	void totalFrameCapIsAppliedAcrossCauseNodes() {
		StackTraceElement[] frames = new StackTraceElement[SafeExceptionReport.MAX_FRAMES_PER_NODE];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = new StackTraceElement("safe.Frame", "method", "File.java", index);
		}

		Throwable current = null;

		for (int depth = 0; depth < 8; depth++) {
			RuntimeException next = new RuntimeException();
			next.setStackTrace(frames);

			if (current != null) {
				next.initCause(current);
			}

			current = next;
		}

		String report = SafeExceptionReport.format(current);

		assertTrue(report.contains("[frames truncated: total limit]"));
	}

	@Test
	void loggerSeamReceivesOneCompleteStringWithoutThrowableArgument() {
		List<String> messages = new ArrayList<>();
		List<Object[]> arguments = new ArrayList<>();
		PingInteractionLogger logger = (message, args) -> {
			messages.add(message);
			arguments.add(args);
		};

		logger.debugException("constant diagnostic context", new RuntimeException("payload secret"));

		assertTrue(messages.get(0).contains("constant diagnostic context"));
		assertFalse(messages.get(0).contains("payload secret"));
		assertTrue(arguments.get(0).length == 0);
	}

	private static final class CycleThrowable extends Throwable {
		private Throwable cause;

		@Override
		public Throwable getCause() {
			return cause;
		}
	}

	private static final class HostileThrowable extends Throwable {
		private boolean messageRead;
		private boolean localizedMessageRead;
		private boolean toStringRead;

		private HostileThrowable() {
			super("message secret");
		}

		@Override
		public String getMessage() {
			messageRead = true;
			return "message secret";
		}

		@Override
		public String getLocalizedMessage() {
			localizedMessageRead = true;
			return "localized secret";
		}

		@Override
		public String toString() {
			toStringRead = true;
			return "toString secret";
		}

		@Override
		public Throwable getCause() {
			throw new IllegalStateException("cause secret");
		}

		@Override
		public StackTraceElement[] getStackTrace() {
			throw new IllegalStateException("stack secret");
		}
}
}
