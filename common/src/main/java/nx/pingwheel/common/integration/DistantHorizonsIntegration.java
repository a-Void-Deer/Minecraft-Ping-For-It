package nx.pingwheel.common.integration;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.debugException;
import static nx.pingwheel.common.Global.warnException;

/**
 * Dedicated boundary for the optional Distant Horizons integration: every
 * Distant Horizons class reference, the terrain cache, the asynchronous ray
 * trace, and the API version diagnostics live here. Core classes never
 * reference the optional packages and only use the JDK/Minecraft signature
 * {@link #traceDistantAsync(Vec3, Vec3, Consumer)}.
 */
public class DistantHorizonsIntegration {
	private DistantHorizonsIntegration() {}

	private static final Object CACHE_LOCK = new Object();
	private static final long CACHE_MAX_AGE_SECONDS = 10L;
	private static final int RAYCAST_RANGE = 4096;

	private static final IntegrationLinkGuard LINK_GUARD = new IntegrationLinkGuard("distanthorizons");

	private static IDhApiTerrainDataCache terrainCache = null;
	private static Instant lastCacheLoad = Instant.EPOCH;

	/**
	 * Starts the asynchronous distant-terrain ray trace from {@code rayStart}
	 * along {@code direction}.
	 *
	 * @return {@code true} only when exactly one completion will be invoked
	 *         (with a distant hit, or empty on no hit/error); {@code false}
	 *         when the integration is gated off, disabled, or failed to
	 *         schedule, in which case no completion is ever invoked and the
	 *         caller must fall back itself. A scheduling rejection (for
 *         example a {@code RejectedExecutionException}) does not disable
 *         the integration: a bounded, sanitized exception diagnostic is
 *         debug logged without throwable messages or payload data, and the
 *         caller falls back itself.
	 */
	public static boolean traceDistantAsync(Vec3 rayStart, Vec3 direction, Consumer<Optional<BlockHitResult>> completion) {
		if (!ModContext.HasDistantHorizons || LINK_GUARD.disabled()) {
			return false;
		}

		final CompletableFuture<Optional<BlockHitResult>> future;

		try {
			future = CompletableFuture.supplyAsync(() -> traceDistant(rayStart, direction));
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
			return false;
		} catch (RuntimeException error) {
			// Scheduling was rejected before the trace body ever ran. The integration
			// stays enabled, and the bounded, sanitized exception diagnostic contains
			// no throwable messages or payload data. The caller completes its own
			// synchronous fallback.
			debugException("distanthorizons schedule failed", error);
			return false;
		}

		future.whenComplete((result, throwable) -> {
			if (throwable != null) {
				Throwable root = rootCause(throwable);

				if (root instanceof LinkageError linkError) {
					// The guard emits its once-only integration warning and debug-logs a
					// bounded, sanitized exception diagnostic without messages or payload data.
					LINK_GUARD.disable(linkError);
				} else {
					// The bounded report includes only exception structure and
					// source-frame fields; messages and payload data never reach the log.
					debugException("distanthorizons trace failed", root);
				}

				completion.accept(Optional.empty());
				return;
			}

			completion.accept(result != null ? result : Optional.empty());
		});

		return true;
	}

	/**
	 * Unwraps wrapper exceptions such as
	 * {@link java.util.concurrent.CompletionException} and
	 * {@link java.util.concurrent.ExecutionException} down to the root cause.
	 * The walk is depth-bounded and breaks on self-referential causes, so a
	 * recursive cause cycle can never loop forever.
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

	private static Optional<BlockHitResult> traceDistant(Vec3 rayStart, Vec3 direction) {
		if (LINK_GUARD.disabled()) {
			return Optional.empty();
		}

		try {
			if (DhApi.Delayed.worldProxy == null) {
				return Optional.empty();
			}

			final var levelWrapper = DhApi.Delayed.worldProxy.getSinglePlayerLevel();

			if (levelWrapper == null) {
				return Optional.empty();
			}

			final IDhApiTerrainDataCache cache;

			// Refresh the cache under the lock, then ray cast outside it.
			synchronized (CACHE_LOCK) {
				if (terrainCache == null || Duration.between(lastCacheLoad, Instant.now()).getSeconds() > CACHE_MAX_AGE_SECONDS) {
					terrainCache = DhApi.Delayed.terrainRepo.createSoftCache();
					lastCacheLoad = Instant.now();
				}

				cache = terrainCache;
			}

			final var rayCastResult = DhApi.Delayed.terrainRepo.raycast(
				levelWrapper,
				rayStart.x, rayStart.y, rayStart.z,
				(float) direction.x, (float) direction.y, (float) direction.z,
				RAYCAST_RANGE,
				cache
			);

			if (!rayCastResult.success || rayCastResult.payload == null) {
				return Optional.empty();
			}

			final var pos = new Vec3(rayCastResult.payload.pos.x, rayCastResult.payload.pos.y, rayCastResult.payload.pos.z);

			return Optional.of(new BlockHitResult(pos, Direction.UP, new BlockPos((int) pos.x, (int) pos.y, (int) pos.z), true));
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
			return Optional.empty();
		}
	}

	/**
	 * Logs the Distant Horizons API version when the integration is enabled; a
	 * link failure here disables the integration instead of crashing.
	 */
	public static void logVersionDiagnostics() {
		if (!ModContext.HasDistantHorizons || LINK_GUARD.disabled()) {
			return;
		}

		try {
			LOGGER.info("Distant Horizons API Version: %s.%s.%s".formatted(
				DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), DhApi.getApiPatchVersion()));
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
		}
	}

	/**
	 * Reports a {@link LinkageError} that escaped this boundary before the
	 * guard could handle it (for example one thrown while linking the trace
	 * call itself), without duplicating the guard's own warning. Only the
	 * bounded safe report is logged: the payload of an unexpected link error
	 * may carry environment details that must never reach the log.
	 */
	public static void logUnguardedLinkFailure(LinkageError error) {
		if (LINK_GUARD.disabled()) {
			return;
		}

		warnException("distanthorizons integration disabled: unguarded link failure", error);
	}
}
