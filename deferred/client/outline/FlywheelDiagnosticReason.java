package nx.pingwheel.common.client.outline;

/**
 * Stable reason taxonomy for the optional Create/Flywheel silhouette source.
 *
 * <p>The values are deliberately independent of Flywheel classes so the
 * diagnostics state machine can be tested on every loader without loading the
 * optional integration.</p>
 */
public enum FlywheelDiagnosticReason {
	ADAPTER_NOT_REGISTERED("adapter-not-registered"),
	MODE_OR_POLICY("mode-or-policy"),
	CONTEXT_UNAVAILABLE("context-unavailable"),
	MANAGER_NULL("manager-null"),
	MANAGER_STORAGE_UNAVAILABLE("manager-storage-unavailable"),
	VISUAL_NULL("visual-null"),
	NO_INSTANCES("no-instances"),
	UNSUPPORTED_TYPE("unsupported"),
	MODEL_UNAVAILABLE("model"),
	MESH_EXTRACTION_FAILED("mesh"),
	MATERIAL_INCOMPATIBLE("material"),
	TRANSFORM_UNAVAILABLE("transform"),
	BUDGET_EXCEEDED("budget"),
	MASK_EMISSION_FAILED("emission"),
	MASK_PARTIAL_EMISSION("partial-emission"),
	RENDERED("rendered");

	private final String diagnosticId;

	FlywheelDiagnosticReason(String diagnosticId) {
		this.diagnosticId = diagnosticId;
	}

	/** Stable lower-case taxonomy value used in human-readable diagnostics. */
	public String diagnosticId() {
		return diagnosticId;
	}
}
