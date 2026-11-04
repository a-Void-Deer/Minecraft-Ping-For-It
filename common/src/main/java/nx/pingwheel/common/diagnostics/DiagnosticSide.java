package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;

/** Explicit logical ownership; an integrated server still uses SERVER. */
@NotNullByDefault
public enum DiagnosticSide {
	CLIENT, SERVER, BOOTSTRAP
}
