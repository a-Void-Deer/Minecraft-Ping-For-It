package nx.pingwheel.common.integration;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.SafeDiagnosticValue;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;


/**
 * Dedicated boundary for the optional Distant Horizons integration: every
 * Distant Horizons class reference, the terrain cache, the asynchronous ray
 * trace, and the API version diagnostics live here. Core classes never
 * reference the optional packages and only use the JDK/Minecraft signature
 * {@link #traceDistantAsync(ClientLevel, boolean, Vec3, Vec3, Executor, Consumer)}.
 */
@NotNullByDefault
public class DistantHorizonsIntegration {
	static final long CACHE_MAX_AGE_SECONDS = 10L;
	private static final int RAYCAST_RANGE = 4096;

	private static final IntegrationLinkGuard LINK_GUARD = new IntegrationLinkGuard("distanthorizons", Diagnostics.client());
	private static final DistantHorizonsIntegration INSTANCE = new DistantHorizonsIntegration(InstantSource.system());

	private final InstantSource clock;
	private final Object cacheLock = new Object();
	private @Nullable CachedTerrain cachedTerrain;

	DistantHorizonsIntegration(InstantSource clock) {
		this.clock = clock;
	}

	private record TraceWorld(IDhApiLevelWrapper wrapper, IDhApiTerrainDataRepo repo) {}

	private static final class CachedTerrain {
		private final WeakReference<IDhApiLevelWrapper> wrapper;
		private final WeakReference<IDhApiTerrainDataRepo> repo;
		private final IDhApiTerrainDataCache cache;
		private final Instant loadedAt;
		private int users;

		private CachedTerrain(TraceWorld world, InstantSource clock) {
			wrapper = new WeakReference<>(world.wrapper());
			repo = new WeakReference<>(world.repo());
			cache = world.repo().createSoftCache();
			loadedAt = clock.instant();
		}

		private boolean expired(InstantSource clock) {
			return Duration.between(loadedAt, clock.instant()).getSeconds() > CACHE_MAX_AGE_SECONDS;
		}

		private boolean matches(TraceWorld world) {
			return wrapper.get() == world.wrapper() && repo.get() == world.repo();
		}
	}

	/**
	 * Binds the captured level to its DH wrapper on the client thread and traces
	 * terrain off-thread. The completion executor returns to the client thread
	 * to recheck the binding before delivering a hit.
	 *
	 * @return {@code true} only when exactly one completion will be invoked
	 *         (with a distant hit, or empty on no hit/error); {@code false}
	 *         when the integration or captured DH level is unavailable, or failed to
	 *         schedule, in which case no completion is ever invoked and the
	 *         caller must fall back itself. A scheduling rejection (for
	 *         example a {@code RejectedExecutionException}) does not disable
	 *         the integration; a controlled WARN preserves the failure details.
	 */
	public static boolean traceDistantAsync(ClientLevel level, boolean integratedServer, Vec3 rayStart, Vec3 direction,
		Executor clientExecutor, Consumer<Optional<BlockHitResult>> completion) {
		return INSTANCE.traceAsync(level, integratedServer, rayStart, direction, clientExecutor, completion);
	}

	boolean traceAsync(ClientLevel level, boolean integratedServer, Vec3 rayStart, Vec3 direction,
		Executor clientExecutor, Consumer<Optional<BlockHitResult>> completion) {
		if (!ModContext.HasDistantHorizons || LINK_GUARD.disabled()) {
			return false;
		}

		@Nullable TraceWorld world = findTraceWorld(level, integratedServer);
		if (world == null) return false;

		final CompletableFuture<Optional<BlockHitResult>> future;
		try {
			future = CompletableFuture.supplyAsync(() -> {
				try {
					return traceDistant(world, rayStart, direction);
				} catch (LinkageError error) {
					LINK_GUARD.disable(error);
					return Optional.empty();
				}
			});
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
			return false;
		} catch (RuntimeException error) {
			Diagnostics.client().atNormal().rateLimited(DistantHorizonsDiagnosticChannels.SCHEDULE_FAILURES).failure(Level.WARN,
				"distanthorizons/schedule", "async-schedule-rejected",
				() -> "rayStart=" + rayStart + " direction=" + direction, error);
			return false;
		}

		future.whenComplete((result, throwable) -> completeTrace(rayStart, direction, result, throwable,
			hit -> clientExecutor.execute(() -> {
				if (hit.isEmpty()) {
					completion.accept(hit);
					return;
				}
				@Nullable TraceWorld current = findTraceWorld(level, integratedServer);
				// DH can replace a keyed wrapper without replacing Minecraft's ClientLevel.
				completion.accept(current != null && current.wrapper() == world.wrapper() && current.repo() == world.repo()
					? hit : Optional.empty());
			})));

		return true;
	}

	static void completeTrace(Vec3 rayStart, Vec3 direction, @Nullable Optional<BlockHitResult> result,
		@Nullable Throwable throwable, Consumer<Optional<BlockHitResult>> completion) {
		if (throwable != null) {
			Throwable root = rootCause(throwable);
			if (root instanceof LinkageError linkError) {
				LINK_GUARD.disable(linkError);
			} else {
				Diagnostics.client().atNormal().rateLimited(DistantHorizonsDiagnosticChannels.TRACE_FAILURES).failure(Level.WARN,
					"distanthorizons/trace", "async-trace-failed",
					() -> "rayStart=" + rayStart + " direction=" + direction, root);
			}
			completion.accept(Optional.empty());
			return;
		}
		completion.accept(result != null ? result : Optional.empty());
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
			@Nullable Throwable cause = root.getCause();

			if (cause == null || cause == root) {
				break;
			}

			root = cause;
		}

		return root;
	}

	private static @Nullable TraceWorld findTraceWorld(ClientLevel level, boolean integratedServer) {
		if (LINK_GUARD.disabled()) return null;
		try {
			@Nullable IDhApiWorldProxy worldProxy = DhApi.Delayed.worldProxy;
			@Nullable IDhApiTerrainDataRepo terrainRepo = DhApi.Delayed.terrainRepo;
			if (worldProxy == null || terrainRepo == null || !worldProxy.worldLoaded()) return null;

			final @Nullable IDhApiLevelWrapper integratedAlias;
			final Iterable<@Nullable IDhApiLevelWrapper> loaded;
			try {
				loaded = worldProxy.getAllLoadedLevelWrappers();
				// An integrated DH level is enumerated by its server wrapper; this supplies its client alias.
				integratedAlias = integratedServer ? worldProxy.getSinglePlayerLevel() : null;
			} catch (IllegalStateException unloaded) {
				// The world can unload between the probe and enumeration.
				return null;
			}

			// The same dimension type or name can describe multiple DH worlds.
			@Nullable IDhApiLevelWrapper levelWrapper = integratedAlias != null && integratedAlias.getWrappedMcObject() == level
				? integratedAlias : null;
			for (@Nullable IDhApiLevelWrapper wrapper : loaded) {
				if (wrapper == null || wrapper.getWrappedMcObject() != level) continue;
				if (levelWrapper != null && levelWrapper != wrapper) return null;
				levelWrapper = wrapper;
			}
			return levelWrapper != null ? new TraceWorld(levelWrapper, terrainRepo) : null;
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
		} catch (RuntimeException error) {
			Diagnostics.client().atNormal().rateLimited(DistantHorizonsDiagnosticChannels.TRACE_FAILURES).failure(Level.WARN,
				"distanthorizons/world-selection", "distant-world-unavailable", null, error);
		}
		return null;
	}

	private Optional<BlockHitResult> traceDistant(TraceWorld world, Vec3 rayStart, Vec3 direction) {
		if (LINK_GUARD.disabled()) return Optional.empty();

		@Nullable CachedTerrain acquired = null;
		@Nullable CachedTerrain idleRetired = null;
		try {
			try {
				// DH keys cached data by position alone. Keep one reusable cache per active binding.
				synchronized (cacheLock) {
					@Nullable CachedTerrain previous = cachedTerrain;
					if (previous != null && previous.expired(clock)) {
						// The expired slot is retired even if allocating its replacement fails.
						cachedTerrain = null;
						if (previous.users == 0) idleRetired = previous;
						previous = null;
					}
					if (previous != null && previous.matches(world)) {
						acquired = previous;
					} else {
						acquired = new CachedTerrain(world, clock);
						cachedTerrain = acquired;
						if (previous != null && previous.users == 0) idleRetired = previous;
					}
					acquired.users++;
				}
			} finally {
				if (idleRetired != null) clearRetiredCache(idleRetired);
			}

			final var rayCastResult = world.repo().raycast(
				world.wrapper(),
				rayStart.x, rayStart.y, rayStart.z,
				(float) direction.x, (float) direction.y, (float) direction.z,
				RAYCAST_RANGE,
				acquired.cache
			);

			if (!rayCastResult.success || rayCastResult.payload == null) {
				return Optional.empty();
			}

			final var pos = new Vec3(rayCastResult.payload.pos.x, rayCastResult.payload.pos.y, rayCastResult.payload.pos.z);

			return Optional.of(terrainHit(pos));
		} finally {
			if (acquired != null) releaseTerrain(acquired);
		}
	}

	private void releaseTerrain(CachedTerrain acquired) {
		final boolean clear;
		synchronized (cacheLock) {
			clear = --acquired.users == 0 && cachedTerrain != acquired;
		}
		if (clear) clearRetiredCache(acquired);
	}

	/** Runs outside the lock after retirement and the final reader. A discarded cache cannot affect picking. */
	private static void clearRetiredCache(CachedTerrain retired) {
		try {
			retired.cache.clear();
		} catch (RuntimeException | LinkageError failure) {
			Diagnostics.client().atNormal().rateLimited(DistantHorizonsDiagnosticChannels.CACHE_RETIREMENT_FAILURES)
				.warn("distanthorizons/cache-retirement", "retired-cache-discarded", failure);
		}
	}

	/** DH observes a terrain point but does not report a native block face. */
	static BlockHitResult terrainHit(Vec3 point) {
		return new nx.pingwheel.common.interaction.candidate.UnobservedFaceBlockHitResult(
			point, Direction.UP, new BlockPos((int) point.x, (int) point.y, (int) point.z), true);
	}

	/**
	 * Logs the Distant Horizons API version when the integration is enabled; a
	 * link failure here disables the integration instead of crashing.
	 */
	public static void logVersionDiagnostics() {
		if (!ModContext.HasDistantHorizons || LINK_GUARD.disabled()) {
			return;
		}
		DiagnosticLogger diagnostics = Diagnostics.bootstrap().atNormal().site("distanthorizons/version");
		if (!diagnostics.isEnabled(Level.INFO)) return;

		try {
			int major = DhApi.getApiMajorVersion();
			int minor = DhApi.getApiMinorVersion();
			int patch = DhApi.getApiPatchVersion();
			diagnostics.info("Distant Horizons API Version: {}.{}.{}",
				SafeDiagnosticValue.number(major), SafeDiagnosticValue.number(minor), SafeDiagnosticValue.number(patch));
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
		}
	}

	/**
	 * Reports a {@link LinkageError} that escaped this boundary before the
	 * guard could handle it (for example one thrown while linking the trace
	 * call itself), without duplicating the guard's own warning.
	 */
	public static void logUnguardedLinkFailure(LinkageError error) {
		if (LINK_GUARD.disabled()) {
			return;
		}

		LINK_GUARD.disable(error);
	}
}
