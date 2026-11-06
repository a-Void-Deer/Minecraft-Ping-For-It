package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;

/** Backend seam. A sink receives exactly one bounded string, never a throwable. */
@NotNullByDefault
public interface DiagnosticBackend {
	default void prepare(DiagnosticSide side) {}

	boolean isEnabled(DiagnosticSide side, Level severity);

	void log(DiagnosticSide side, Level severity, String message);
}
