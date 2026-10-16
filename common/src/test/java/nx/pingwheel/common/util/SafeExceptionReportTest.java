package nx.pingwheel.common.util;

import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeExceptionReportTest {

	@Test
	void includesOriginalMessagesTypesFramesAndRelationshipsWithoutRedaction() {
		IllegalStateException cause = new IllegalStateException("cause registry=othermod:machine");
		cause.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("safe.CauseFrame", "causeMethod", "CauseFile.java", 22)
		});
		IllegalArgumentException top = new IllegalArgumentException("top position=(12,64,-33) payload={name:Alice}", cause);
		top.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("safe.TopFrame", "topMethod", "TopFile.java", 11)
		});
		RuntimeException suppressed = new RuntimeException("suppressed component=custom:data");
		suppressed.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("safe.SuppressedFrame", "close", "CloseFile.java", 33)
		});
		top.addSuppressed(suppressed);

		String report = SafeExceptionReport.format(top);

		assertTrue(report.contains("top position=(12,64,-33) payload={name:Alice}"));
		assertTrue(report.contains("cause registry=othermod:machine"));
		assertTrue(report.contains("suppressed component=custom:data"));
		assertTrue(report.contains("java.lang.IllegalArgumentException"));
		assertTrue(report.contains("safe.TopFrame.topMethod (TopFile.java:11)"));
		assertTrue(report.contains("safe.CauseFrame.causeMethod (CauseFile.java:22)"));
		assertTrue(report.contains("safe.SuppressedFrame.close (CloseFile.java:33)"));
		assertTrue(report.contains("\n    at "));
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
		assertTrue(report.contains("suppressed secret 0"));
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
	void readsMessageButNeverUsesThrowableStringConversionAndSurvivesFailingAccessors() {
		HostileThrowable hostile = new HostileThrowable();

		String report = SafeExceptionReport.format(hostile);

		assertTrue(report.contains(HostileThrowable.class.getName()));
		assertTrue(report.contains("[stack unavailable]"));
		assertTrue(report.contains("cause: [unavailable]"));
		assertTrue(report.contains("message secret"));
		assertFalse(report.contains("localized secret"));
		assertFalse(report.contains("toString secret"));
		assertTrue(hostile.messageRead);
		assertFalse(hostile.localizedMessageRead);
		assertFalse(hostile.toStringRead);
	}

	@Test
	void failingMessageAccessorHasExplicitMarkerAndDoesNotHideSourceFrames() {
		Throwable failure = new RuntimeException() {
			@Override
			public String getMessage() {
				throw new IllegalStateException("accessor failed");
			}
		};
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Broken", "read", "Broken.java", 7)
		});

		String report = SafeExceptionReport.format(failure);

		assertTrue(report.contains("[message unavailable]"));
		assertTrue(report.contains("provider.Broken.read (Broken.java:7)"));
	}

	@Test
	void fatalMessageAccessorFailuresPropagateUnchangedFromBothFormattingEntrypoints() {
		for (Error fatal : List.of(new OutOfMemoryError("message accessor failed"), new ThreadDeath())) {
			Throwable failure = new RuntimeException() {
				@Override
				public String getMessage() {
					throw fatal;
				}
			};

			assertSame(fatal, assertThrows(fatal.getClass(), () -> SafeExceptionReport.format(failure)));
			assertSame(fatal, assertThrows(fatal.getClass(), () ->
				SafeExceptionReport.formatWithContext("provider-read", failure)));
		}
	}

	@Test
	void longMessageAndContextHaveSeparateBudgetsSoTopFrameSurvives() {
		RuntimeException failure = new RuntimeException("message " + "x".repeat(SafeExceptionReport.MAX_CHARACTERS));
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Inventory", "extract", "Inventory.java", 57)
		});

		String report = SafeExceptionReport.formatWithContext(
			"context " + "y".repeat(SafeExceptionReport.MAX_CHARACTERS), failure);

		assertTrue(report.contains("[context truncated]"));
		assertTrue(report.contains("[message truncated]"));
		assertTrue(report.contains("provider.Inventory.extract (Inventory.java:57)"));
		assertTrue(report.length() <= SafeExceptionReport.MAX_CHARACTERS);
	}

	@Test
	void messageControlsAreRecognizableEscapesWhileStructureKeepsLineBreaks() {
		RuntimeException failure = new RuntimeException("first\nsecond\tend" + (char) 0 + " literal=\\n");
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Reader", "read", "Reader.java", 4)
		});

		String report = SafeExceptionReport.format(failure);

		assertTrue(report.contains("first\\nsecond\\tend\\u0000 literal=\\\\n"));
		assertFalse(report.contains("first\nsecond"));
		assertTrue(report.contains("\n    at provider.Reader"));
	}

	@Test
	void causeDepthIsBoundedAndMarked() {
		Throwable failure = null;
		for (int depth = 0; depth < SafeExceptionReport.MAX_CAUSE_DEPTH + 3; depth++) {
			RuntimeException next = new RuntimeException("depth " + depth, failure);
			next.setStackTrace(new StackTraceElement[0]);
			failure = next;
		}

		String report = SafeExceptionReport.format(failure);

		assertTrue(report.contains("[depth limit]"));
		assertTrue(report.length() <= SafeExceptionReport.MAX_CHARACTERS);
	}

	@Test
	void wideThrowableGraphHasBoundedNodeAccess() {
		int[] messageReads = {0};
		Throwable failure = wideFailure(3, messageReads);

		String report = SafeExceptionReport.format(failure);

		assertEquals(SafeExceptionReport.MAX_THROWABLE_NODES, messageReads[0]);
		assertTrue(report.contains("[node limit]"));
		assertTrue(report.length() <= SafeExceptionReport.MAX_CHARACTERS);
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
	void totalCharacterBudgetIsMarkedWithoutLosingTheTopSourceFrame() {
		Throwable failure = null;
		for (int depth = 0; depth < 12; depth++) {
			RuntimeException next = new RuntimeException("payload=" + "x".repeat(1000), failure);
			next.setStackTrace(new StackTraceElement[] {
				new StackTraceElement("provider.Inventory", "read", "Inventory.java", 17),
				new StackTraceElement("provider." + "y".repeat(1400), "caller", "Caller.java", 18)
			});
			failure = next;
		}

		String report = SafeExceptionReport.format(failure);

		assertTrue(report.length() <= SafeExceptionReport.MAX_CHARACTERS);
		assertTrue(report.contains("[truncated]"));
		assertTrue(report.contains("provider.Inventory.read (Inventory.java:17)"));
	}

	@Test
	void perThrowableFrameBudgetKeepsAdmittedFramesAndMarksTheRemainder() {
		RuntimeException failure = new RuntimeException("detail");
		StackTraceElement[] frames = new StackTraceElement[SafeExceptionReport.MAX_FRAMES_PER_NODE + 2];
		for (int index = 0; index < frames.length; index++) {
			frames[index] = new StackTraceElement("provider.Reader", "read" + index, "Reader.java", index);
		}
		failure.setStackTrace(frames);

		String report = SafeExceptionReport.format(failure);

		assertTrue(report.contains("provider.Reader.read0"));
		assertFalse(report.contains("provider.Reader.read" + SafeExceptionReport.MAX_FRAMES_PER_NODE));
		assertTrue(report.contains("[frames truncated: node limit]"));
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
		assertTrue(messages.get(0).contains("payload secret"));
		assertTrue(arguments.get(0).length == 0);
	}

	private static Throwable wideFailure(int depth, int[] messageReads) {
		RuntimeException failure = new RuntimeException() {
			@Override
			public String getMessage() {
				messageReads[0]++;
				return "detail";
			}
		};
		failure.setStackTrace(new StackTraceElement[0]);
		if (depth > 0) {
			for (int index = 0; index < SafeExceptionReport.MAX_SUPPRESSED_PER_NODE; index++) {
				failure.addSuppressed(wideFailure(depth - 1, messageReads));
			}
		}
		return failure;
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
