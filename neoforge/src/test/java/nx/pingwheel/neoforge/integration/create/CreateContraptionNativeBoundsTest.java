package nx.pingwheel.neoforge.integration.create;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateContraptionNativeBoundsTest {

	@Test
	void missingAndCheckedNativeBoundsStayUnavailableRatherThanInventingABoundsView() {
		try (CreateDiagnosticCapture capture = new CreateDiagnosticCapture()) {
			assertNull(CreateContraptionNativeBounds.lookup(Object.class, new Object()));
			assertNull(CreateContraptionNativeBounds.lookup(CheckedFailure.class, new CheckedFailure()));
			assertNull(CreateContraptionNativeBounds.lookup(WrongType.class, new WrongType()));
			assertEquals(2, capture.events.size());
			assertTrue(capture.events.stream().anyMatch(event -> {
				String text = event.getMessage().getFormattedMessage();
				return text.contains("native checked original") && text.contains("native checked cause")
					&& text.contains("CreateContraptionNativeBoundsTest");
			}));
			capture.events.forEach(event -> {
				assertNull(event.getThrown());
				assertTrue(event.getMessage().getFormattedMessage().length() <= nx.pingwheel.common.util.SafeExceptionReport.MAX_CHARACTERS);
			});
		}
	}

	@Test
	void runtimeAndFatalInvocationCausesKeepTheirExistingPropagationBoundary() {
		RuntimeFailure runtime = new RuntimeFailure();
		assertSame(runtime.failure, assertThrows(IllegalStateException.class,
			() -> CreateContraptionNativeBounds.lookup(RuntimeFailure.class, runtime)));
		FatalFailure fatal = new FatalFailure();
		assertSame(fatal.failure, assertThrows(OutOfMemoryError.class,
			() -> CreateContraptionNativeBounds.lookup(FatalFailure.class, fatal)));
	}

	@NotNullByDefault
	public static final class CheckedFailure {
		public Object getContraptionWorld() throws ReflectiveOperationException {
			throw new ReflectiveOperationException("native checked original", new IllegalArgumentException("native checked cause"));
		}
	}

	@NotNullByDefault
	public static final class WrongType {
		public Object getContraptionWorld() { return new Object(); }
	}

	@NotNullByDefault
	public static final class RuntimeFailure {
		private final IllegalStateException failure = new IllegalStateException("native runtime");
		public Object getContraptionWorld() { throw failure; }
	}

	@NotNullByDefault
	public static final class FatalFailure {
		private final OutOfMemoryError failure = new OutOfMemoryError("native fatal");
		public Object getContraptionWorld() { throw failure; }
	}
}
