package nx.pingwheel.common.client.outline;

/**
 * One-shot diagnostic for the {@code require = 0} entity-extraction mixin.
 *
 * <p>{@code EntityRendererMixin} is the only place where a ping colour can be written
 * into {@code EntityRenderState.outlineColor}. If its target descriptor ever changes it
 * silently stops applying, which looks exactly like "this mod has no entity outlines".
 * The extraction hook therefore records that it ran and
 * {@link EntityOutlineState} warns once when outlines are requested but no extraction
 * ever arrived.
 */
public final class EntityOutlineDiagnostics {

	private static volatile boolean extractionHookApplied = false;

	private EntityOutlineDiagnostics() {}

	/** Called by {@code EntityRendererMixin} on every successful extraction. */
	public static void markExtractionApplied() {
		extractionHookApplied = true;
	}

	/** True once the entity extraction hook has actually run. */
	public static boolean extractionHookApplied() {
		return extractionHookApplied;
	}
}
