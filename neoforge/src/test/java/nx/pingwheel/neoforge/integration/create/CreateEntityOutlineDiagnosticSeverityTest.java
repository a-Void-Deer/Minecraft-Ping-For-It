package nx.pingwheel.neoforge.integration.create;

import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@NotNullByDefault
class CreateEntityOutlineDiagnosticSeverityTest {
	@Test void onlyActualFailedRenderingUsesErrorWhileAvailabilityAndPreflightKeepTheirLevels() {
		Throwable failure = new LinkageError("original");
		for (String reason : new String[] {"renderer-failed-before-vertices", "glue-render-failed-before-vertices"}) {
			assertEquals(Level.ERROR, CreateEntityOutlineDiagnosticSeverity.level(reason, "on", failure, EntityBlockGeometryOutcome.FAILED));
		}
		for (String reason : new String[] {"backend-check-failed", "outline-buffer-unavailable", "nonfinite-input",
			"renderer-emitted-zero-vertices", "render-setup-failed", "glue-render-setup-failed"}) {
			assertEquals(Level.WARN, CreateEntityOutlineDiagnosticSeverity.level(reason, "unknown", failure, EntityBlockGeometryOutcome.FAILED));
		}
		assertEquals(Level.DEBUG, CreateEntityOutlineDiagnosticSeverity.level("backend-unavailable", "class-missing", null, EntityBlockGeometryOutcome.EMPTY));
		assertEquals(Level.DEBUG, CreateEntityOutlineDiagnosticSeverity.level("backend-off", "off", null, EntityBlockGeometryOutcome.EMPTY));
		assertEquals(Level.DEBUG, CreateEntityOutlineDiagnosticSeverity.level("rendered", "on", null, EntityBlockGeometryOutcome.RENDERED));
		assertEquals(Level.WARN, CreateEntityOutlineDiagnosticSeverity.level("partial-render-exception", "on", failure, EntityBlockGeometryOutcome.RENDERED));
	}
}
