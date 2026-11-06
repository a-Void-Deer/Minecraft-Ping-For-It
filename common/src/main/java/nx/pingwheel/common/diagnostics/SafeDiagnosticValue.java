package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;

/**
 * An explicitly audited NORMAL scalar. There is deliberately no Object or
 * arbitrary-text factory. Codes must come from the caller's finite allowlist,
 * never a provider's message, identifier, name or free-form reason.
 */
@NotNullByDefault
public final class SafeDiagnosticValue {
	private final String text;

	private SafeDiagnosticValue(String text) {
		this.text = text;
	}

	public static SafeDiagnosticValue number(long value) {
		return new SafeDiagnosticValue(Long.toString(value));
	}

	public static SafeDiagnosticValue number(double value) {
		return new SafeDiagnosticValue(Double.toString(value));
	}

	public static SafeDiagnosticValue bool(boolean value) {
		return new SafeDiagnosticValue(Boolean.toString(value));
	}

	public static SafeDiagnosticValue code(String allowlistedCode) {
		return new SafeDiagnosticValue(DiagnosticText.controlled(allowlistedCode));
	}

	String text() {
		return text;
	}
}
