package nx.pingwheel.common.integration;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Stub for the optional Distant Horizons integration.
 *
 * <p>TODO(M9): restore the real adapter (async distant ray trace + API version
 * diagnostics). Stubbed so the build does not require the DH API artifact.
 *
 * <p>Public surface unchanged: {@link #traceDistantAsync} returns {@code false},
 * which is the documented "gated off, no completion will ever be invoked"
 * contract, so callers fall back to their own synchronous ray trace.
 *
 * <p>The helper members below are restored from the 10/26 build so the
 * scheduling/failure contract (and its log wording) survives the stub:
 * {@link #rootCause} is pure and is exactly what the original asynchronous
 * completion used to classify a failure, and {@link #traceDistant} keeps the
 * private trace seam the real adapter will fill in at M9.
 */
public class DistantHorizonsIntegration {

	private DistantHorizonsIntegration() {}

	public static boolean traceDistantAsync(Vec3 rayStart, Vec3 direction, Consumer<Optional<BlockHitResult>> completion) {
		// TODO(M9): schedule CompletableFuture.supplyAsync(() -> traceDistant(...)),
		// disable the link guard on LinkageError, debug-log "distanthorizons
		// schedule failed" / "distanthorizons trace failed", and complete on the
		// client thread. Gated off until the DH API is on the classpath.
		return false;
	}

	/**
	 * The root cause of a (possibly deeply chained) failure, bounded to 16 hops
	 * so a cyclic cause chain can never spin. Restored verbatim from the 10/26
	 * build (decompiled).
	 */
	static Throwable rootCause(Throwable throwable) {
		Throwable root = throwable;

		for (int depth = 0; depth < 16; depth++) {
			Throwable cause = root.getCause();

			if (cause == null || cause == root) {
				break;
			}

			root = cause;
		}

		return root;
	}

	/**
	 * TODO(M9): the synchronous distant ray trace (cached terrain repo, 4096
	 * block range, {@code DhApiRaycastResult} -> {@link BlockHitResult}).
	 */
	private static Optional<BlockHitResult> traceDistant(Vec3 rayStart, Vec3 direction) {
		// Intentionally empty: the DH API artifact is not on the classpath yet.
		return Optional.empty();
	}

	public static void logVersionDiagnostics() {
		// TODO(M9): log "Distant Horizons API Version: <major>.<minor>.<patch>"
		// once the DH API is available.
	}

	public static void logUnguardedLinkFailure(LinkageError error) {
		// TODO(M9): warn "distanthorizons integration disabled: unguarded link
		// failure" once the link guard is restored.
	}
}
