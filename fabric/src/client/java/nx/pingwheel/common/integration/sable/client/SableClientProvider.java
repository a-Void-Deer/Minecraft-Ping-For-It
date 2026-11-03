package nx.pingwheel.common.integration.sable.client;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.TargetSnapshot;

/**
 * Dependency-free stand-in for the Sable (sub-level) client integration.
 *
 * <p>The real bridge lives in {@code deferred/client/integration/sable/} because
 * the Sable companion API is an optional runtime dependency that is absent from
 * the build classpath (M9 restores the integration together with its
 * dependency). Every entry point reports "integration unavailable", which is
 * exactly the semantics the callers already implement:
 * <ul>
 *   <li>{@link #capture} - no provider-owned snapshot, so the normal vanilla /
 *       projected-location capture keeps running (ClientPingRuntime);</li>
 *   <li>{@link #projectOutOfSubLevel} - nothing to project, so the vanilla hit
 *       position stays authoritative (SableIntegration);</li>
 *   <li>{@link #resolvePosition} / {@link #resolveName} - no provider-owned
 *       position or name, so MarkerView keeps the authoritative anchor and the
 *       target name falls back to the decoded/unknown name.</li>
 * </ul>
 */
public final class SableClientProvider {

	/** The provider id the deferred implementation reported. */
	public static final String PROVIDER_ID = "sable";

	private SableClientProvider() {}

	/** No provider-owned snapshot: the caller keeps its vanilla capture path. */
	public static Optional<TargetSnapshot> capture(
		ClientLevel level, BlockHitResult hit, Vec3 rayStart, Vec3 rayEnd
	) {
		return Optional.empty();
	}

	/** Nothing to project: the vanilla hit position stays authoritative. */
	public static Optional<Vec3> projectOutOfSubLevel(ClientLevel level, Vec3 hitPosition) {
		return Optional.empty();
	}

	/** No provider-owned live position: the caller falls back to the anchor. */
	public static Optional<Vec3> resolvePosition(
		ClientLevel level, Target.ExternalBlockTarget target, float partialTick
	) {
		return Optional.empty();
	}

	/** No provider-owned display name: the caller uses its own composition. */
	public static Optional<Component> resolveName(
		ClientLevel level, Target.ExternalBlockTarget target
	) {
		return Optional.empty();
	}

	/** Keeps the call sites intact; the stub deliberately logs nothing. */
	public static void logCaptureFallback(
		String fallback, String detailReason, String stage, Object... fields
	) {
		// Intentionally empty: the deferred provider owns these diagnostics.
	}
}
