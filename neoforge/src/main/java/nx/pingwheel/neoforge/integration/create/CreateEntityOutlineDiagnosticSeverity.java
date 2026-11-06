package nx.pingwheel.neoforge.integration.create;

import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Availability is not a failed target render; only actual render emission failures use ERROR. */
@NotNullByDefault
final class CreateEntityOutlineDiagnosticSeverity {
	private CreateEntityOutlineDiagnosticSeverity() {}

	static Level level(String reason, String backend, @Nullable Throwable failure, @Nullable EntityBlockGeometryOutcome outcome) {
		if (failure != null && outcome == EntityBlockGeometryOutcome.FAILED
			&& (reason.equals("renderer-failed-before-vertices") || reason.equals("glue-render-failed-before-vertices"))) return Level.ERROR;
		return failure != null || outcome == EntityBlockGeometryOutcome.FAILED
			|| (reason.equals("backend-unavailable") && !backend.equals("class-missing"))
			|| reason.startsWith("nonfinite") || reason.contains("mismatch") ? Level.WARN : Level.DEBUG;
	}
}
