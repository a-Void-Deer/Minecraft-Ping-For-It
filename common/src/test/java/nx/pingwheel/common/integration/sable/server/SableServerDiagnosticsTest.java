package nx.pingwheel.common.integration.sable.server;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.sable.SableDiagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SableServerDiagnosticsTest {

	@Test
	void liveLinkageErrorIsEmittedOnceAtTheOperationBoundary() {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		LinkageError failure = new NoClassDefFoundError("sable-provider-payload");

		SableExternalBlockServerProvider.logLiveResult(
			diagnostics,
			"validate",
			null,
			new SableExternalBlockServerProvider.LiveResult.Invalid("linkage-error", failure));

		assertEquals(1, diagnostics.events().size());
		assertEquals(SableDiagnostics.SERVER_PREFIX, diagnostics.events().get(0).prefix());
		assertEquals("live-resolution", diagnostics.events().get(0).stage());
		assertEquals("linkage-error", diagnostics.events().get(0).reason());
		assertSame(failure, diagnostics.events().get(0).throwable());
	}
}
