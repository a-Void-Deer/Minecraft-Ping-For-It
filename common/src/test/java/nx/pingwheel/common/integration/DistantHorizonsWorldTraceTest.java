package nx.pingwheel.common.integration;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiDimensionTypeWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiRaycastResult;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3i;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real async adapter through the pinned DH API, without loading a DH game. */
@NotNullByDefault
class DistantHorizonsWorldTraceTest {

	private static final Vec3 ORIGIN = new Vec3(2.5, 70, 4.5);
	private static final Vec3 DIRECTION = new Vec3(0, 0, 1);
	// Probe the first whole-second age beyond the production limit.
	private static final Duration FIRST_EXPIRED_AGE =
		Duration.ofSeconds(DistantHorizonsIntegration.CACHE_MAX_AGE_SECONDS).plusSeconds(1);
	private static final IDhApiDimensionTypeWrapper OVERWORLD_TYPE = stub(IDhApiDimensionTypeWrapper.class,
		(ignored, method, args) -> { throw new AssertionError(method); });

	private boolean previousFlag;
	private @Nullable IDhApiWorldProxy previousWorld;
	private @Nullable IDhApiTerrainDataRepo previousRepo;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void installApiFixture() {
		previousFlag = ModContext.HasDistantHorizons;
		previousWorld = DhApi.Delayed.worldProxy;
		previousRepo = DhApi.Delayed.terrainRepo;
		ModContext.HasDistantHorizons = true;
	}

	@AfterEach
	void restoreApi() {
		ModContext.HasDistantHorizons = previousFlag;
		DhApi.Delayed.worldProxy = previousWorld;
		DhApi.Delayed.terrainRepo = previousRepo;
	}

	@Test
	void multiplayerUsesExactClientLevelBehindOtherWorldsOfTheSameType() throws Exception {
		ClientLevel captureLevel = allocateLevel();
		var foreign = wrapper(allocateLevel());
		var selected = wrapper(captureLevel);
		DhApi.Delayed.worldProxy = world(true, () -> List.of(foreign, selected), null);
		DhApi.Delayed.terrainRepo = repo((wrapper, cache) -> {
			assertSame(selected, wrapper);
			return hit(201);
		});

		assertEquals(new Vec3(201, 70, 4), trace(captureLevel).orElseThrow().getLocation());
	}

	@Test
	void multipleDistinctWrappersForTheCapturedLevelDoNotCast() throws Exception {
		ClientLevel captureLevel = allocateLevel();
		DhApi.Delayed.worldProxy = world(true, () -> List.of(wrapper(captureLevel), wrapper(captureLevel)), null);

		assertNoTerrainRead(captureLevel);
	}

	@Test
	void loadedButUnrelatedWorldDoesNotCast() throws Exception {
		ClientLevel captureLevel = allocateLevel();
		var unrelated = wrapper(allocateLevel());
		// Some DH versions also return a client alias remotely; that is not a loaded remote binding.
		DhApi.Delayed.worldProxy = world(true, () -> List.of(unrelated), wrapper(captureLevel));

		assertNoTerrainRead(captureLevel);
	}

	@Test
	void unloadedOrDisappearingDhWorldLeavesFallbackToTheCaller() throws Exception {
		ClientLevel captureLevel = allocateLevel();
		var selected = wrapper(captureLevel);
		DhApi.Delayed.worldProxy = null;
		assertNoTerrainRead(captureLevel);
		DhApi.Delayed.worldProxy = world(false, () -> List.of(selected), null);
		assertNoTerrainRead(captureLevel);
		DhApi.Delayed.worldProxy = world(true, () -> { throw new IllegalStateException("no world loaded"); }, null);
		assertNoTerrainRead(captureLevel);
	}

	@Test
	void singlePlayerStillTracesTheCapturedWorld() throws Exception {
		ClientLevel captureLevel = allocateLevel();
		var selected = wrapper(captureLevel);
		var integratedServerWrapper = wrapper(new Object());
		DhApi.Delayed.worldProxy = world(true, () -> List.of(integratedServerWrapper), selected);
		DhApi.Delayed.terrainRepo = repo((wrapper, cache) -> {
			assertSame(selected, wrapper);
			return hit(257);
		});

		assertEquals(new Vec3(257, 70, 4), startTrace(captureLevel, true, Runnable::run)
			.get(10, TimeUnit.SECONDS).orElseThrow().getLocation());
	}

	@Test
	void sameDimensionAfterWorldReplacementCannotReusePreviousTerrain() throws Exception {
		ClientLevel firstLevel = allocateLevel();
		ClientLevel nextLevel = allocateLevel();
		var first = wrapper(firstLevel);
		var next = wrapper(nextLevel);
		DhApi.Delayed.terrainRepo = repo((selected, cache) -> cache.readAtSharedCoordinates(hit(selected == first ? 300 : 400)));
		DhApi.Delayed.worldProxy = world(true, () -> List.of(first), null);
		assertEquals(new Vec3(300, 70, 4), trace(firstLevel).orElseThrow().getLocation());
		DhApi.Delayed.worldProxy = world(true, () -> List.of(next), null);
		assertEquals(new Vec3(400, 70, 4), trace(nextLevel).orElseThrow().getLocation());
	}

	@Test
	void sameBindingReusesCacheUntilExpirationThenReplacesIt() throws Exception {
		var terrainX = new AtomicInteger(1100);
		var trace = new TimedTrace((selected, cache) -> cache.readAtSharedCoordinates(hit(terrainX.get())));
		assertEquals(new Vec3(1100, 70, 4), trace.trace().orElseThrow().getLocation());

		terrainX.set(1200);
		trace.setAge(FIRST_EXPIRED_AGE.minusNanos(1));
		assertEquals(new Vec3(1100, 70, 4), trace.trace().orElseThrow().getLocation(), "the cached hit still applies");
		assertEquals(1, trace.createAttempts.get());
		assertEquals(0, trace.original.clearCalls.get());

		trace.setAge(FIRST_EXPIRED_AGE);
		assertEquals(new Vec3(1200, 70, 4), trace.trace().orElseThrow().getLocation(), "the expired hit is not reused");
		assertEquals(2, trace.createAttempts.get());
		assertEquals(1, trace.original.clearCalls.get());
	}

	@Test
	void failedAllocationClearsIdleExpiredCacheAndLeavesTraceRetryable() throws Exception {
		var terrainX = new AtomicInteger(1100);
		var trace = new TimedTrace((selected, cache) -> cache.readAtSharedCoordinates(hit(terrainX.get())));
		assertEquals(new Vec3(1100, 70, 4), trace.trace().orElseThrow().getLocation());

		trace.setAge(FIRST_EXPIRED_AGE);
		trace.failAllocation = true;
		assertTrue(trace.trace().isEmpty(), "ordinary async fallback on cache allocation failure");
		assertEquals(2, trace.createAttempts.get());
		assertEquals(1, trace.original.clearCalls.get(), "the failed attempt must still clear the idle cache");
		assertNull(trace.original.stored.get());

		trace.failAllocation = false;
		terrainX.set(1200);
		assertEquals(new Vec3(1200, 70, 4), trace.trace().orElseThrow().getLocation());
		assertEquals(3, trace.createAttempts.get());
		assertEquals(1, trace.original.clearCalls.get(), "retry must not retire the old cache again");
	}

	@Test
	void failedAllocationClearsExpiredCacheOnlyWhenItsLastReaderFinishes() throws Exception {
		var firstEntered = new CountDownLatch(1);
		var lastEntered = new CountDownLatch(1);
		var releaseFirst = new CompletableFuture<@Nullable Void>();
		var releaseLast = new CompletableFuture<@Nullable Void>();
		var readers = new AtomicInteger();
		var trace = new TimedTrace((selected, cache) -> {
			assertEquals(1300, cache.readAtSharedCoordinates(hit(1300)).pos.x);
			if (readers.getAndIncrement() == 0) {
				firstEntered.countDown();
				releaseFirst.join();
			} else {
				lastEntered.countDown();
				releaseLast.join();
			}
			return cache.readAtSharedCoordinates(hit(1400));
		});

		var first = trace.start();
		try {
			assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
			var last = trace.start();
			assertTrue(lastEntered.await(10, TimeUnit.SECONDS));
			assertEquals(1, trace.createAttempts.get(), "both readers use the same cache");

			trace.setAge(FIRST_EXPIRED_AGE);
			trace.failAllocation = true;
			assertTrue(trace.trace().isEmpty());
			assertEquals(2, trace.createAttempts.get());
			assertEquals(0, trace.original.clearCalls.get(), "the retired cache still has readers");

			releaseFirst.complete(null);
			assertEquals(new Vec3(1300, 70, 4), first.get(10, TimeUnit.SECONDS).orElseThrow().getLocation());
			assertEquals(0, trace.original.clearCalls.get(), "the last reader still needs the cache");

			releaseLast.complete(null);
			assertEquals(new Vec3(1300, 70, 4), last.get(10, TimeUnit.SECONDS).orElseThrow().getLocation());
			assertEquals(1, trace.original.clearCalls.get(), "release must clear the cache without a successful replacement");
			assertNull(trace.original.stored.get());
		} finally {
			releaseFirst.complete(null);
			releaseLast.complete(null);
		}
	}

	@Test
	void failedIdleCacheRetirementStillTracesTheReplacementWrapperAndCompletesOnce() throws Exception {
		ClientLevel captureLevel = allocateLevel();
		var first = wrapper(captureLevel);
		var replacement = wrapper(captureLevel);
		AtomicInteger clearAttempts = new AtomicInteger();
		AtomicInteger replacementRaycasts = new AtomicInteger();
		AtomicInteger callbacks = new AtomicInteger();
		DhApi.Delayed.worldProxy = world(true, () -> List.of(first), null);
		DhApi.Delayed.terrainRepo = repo((selected, cache) -> {
			if (selected == first) {
				cache.beforeClear = () -> {
					clearAttempts.incrementAndGet();
					throw new NullPointerException("private retired terrain detail");
				};
				return cache.readAtSharedCoordinates(hit(700));
			}
			assertSame(replacement, selected);
			replacementRaycasts.incrementAndGet();
			return cache.readAtSharedCoordinates(hit(800));
		});

		try (var diagnostics = new IntegrationDiagnosticCapture(Level.WARN, LoggingProfile.NORMAL)) {
			assertEquals(new Vec3(700, 70, 4), trace(captureLevel).orElseThrow().getLocation());
			DhApi.Delayed.worldProxy = world(true, () -> List.of(replacement), null);
			var completion = new CompletableFuture<Optional<BlockHitResult>>();
			assertTrue(DistantHorizonsIntegration.traceDistantAsync(captureLevel, false, ORIGIN, DIRECTION, Runnable::run, hit -> {
				callbacks.incrementAndGet();
				completion.complete(hit);
			}));

			var result = completion.get(10, TimeUnit.SECONDS);
			assertEquals(1, clearAttempts.get());
			assertEquals(1, replacementRaycasts.get(), "clear failure cannot prevent the new ray from running");
			assertEquals(1, callbacks.get());
			assertEquals(new Vec3(800, 70, 4), result.orElseThrow().getLocation());
			assertEquals(1, diagnostics.events().size());
			var event = diagnostics.events().getFirst();
			var message = event.getMessage().getFormattedMessage();
			assertTrue(message.contains("site=distanthorizons/cache-retirement"));
			assertTrue(message.contains("handling=retired-cache-discarded"));
			assertFalse(message.contains("private retired terrain detail"));
			assertNull(event.getThrown());
		}
	}

	@Test
	void failedRetirementAfterLastReaderDoesNotDiscardItsValidHit() throws Exception {
		ClientLevel firstLevel = allocateLevel();
		ClientLevel otherLevel = allocateLevel();
		var first = wrapper(firstLevel);
		var other = wrapper(otherLevel);
		var firstRaycastEntered = new CountDownLatch(1);
		var firstRaycastMayFinish = new CountDownLatch(1);
		var clearAttempts = new AtomicInteger();
		DhApi.Delayed.worldProxy = world(true, () -> List.of(first, other), null);
		DhApi.Delayed.terrainRepo = repo((selected, cache) -> {
			if (selected == first) {
				cache.beforeClear = () -> {
					clearAttempts.incrementAndGet();
					throw new IllegalStateException("retired cache could not release its data");
				};
				var result = cache.readAtSharedCoordinates(hit(900));
				firstRaycastEntered.countDown();
				assertTrue(firstRaycastMayFinish.await(10, TimeUnit.SECONDS));
				return result;
			}
			assertSame(other, selected);
			return cache.readAtSharedCoordinates(hit(1000));
		});

		var pending = startTrace(firstLevel, false, Runnable::run);
		try {
			assertTrue(firstRaycastEntered.await(10, TimeUnit.SECONDS));
			assertEquals(new Vec3(1000, 70, 4), trace(otherLevel).orElseThrow().getLocation());
			assertEquals(0, clearAttempts.get(), "the first ray still holds the retired cache");
		} finally {
			firstRaycastMayFinish.countDown();
		}
		var result = pending.get(10, TimeUnit.SECONDS);
		assertEquals(1, clearAttempts.get());
		// Both wrappers are still loaded, so the first hit must survive cleanup and revalidation.
		assertEquals(new Vec3(900, 70, 4), result.orElseThrow().getLocation());
	}

	@Test
	void keyedWrapperReplacementOnTheSameClientLevelRejectsAnInFlightHit() throws Exception {
		ClientLevel captureLevel = allocateLevel();
		var first = wrapper(captureLevel);
		var next = wrapper(captureLevel);
		CountDownLatch firstRaycastEntered = new CountDownLatch(1);
		CountDownLatch firstRaycastMayFinish = new CountDownLatch(1);
		AtomicReference<@Nullable PositionOnlyCache> retiredCache = new AtomicReference<>();
		AtomicReference<@Nullable DhApiRaycastResult> retiredCacheData = new AtomicReference<>();
		var clientTasks = new LinkedBlockingQueue<Runnable>();
		DhApi.Delayed.worldProxy = world(true, () -> List.of(first), null);
		DhApi.Delayed.terrainRepo = repo((selected, cache) -> {
			if (selected == first) {
				retiredCache.set(cache);
				cache.readAtSharedCoordinates(hit(500));
				firstRaycastEntered.countDown();
				assertTrue(firstRaycastMayFinish.await(10, TimeUnit.SECONDS));
				var oldHit = cache.readAtSharedCoordinates(hit(501));
				retiredCacheData.set(oldHit);
				return oldHit;
			}
			assertSame(next, selected);
			return cache.readAtSharedCoordinates(hit(600));
		});

		var pending = startTrace(captureLevel, false, clientTasks::add);
		try {
			assertTrue(firstRaycastEntered.await(10, TimeUnit.SECONDS), "raycast must start before switching worlds");
			assertFalse(pending.isDone());
			DhApi.Delayed.worldProxy = world(true, () -> List.of(next), null);
			assertEquals(new Vec3(600, 70, 4), trace(captureLevel).orElseThrow().getLocation());
		} finally {
			firstRaycastMayFinish.countDown();
		}
		@Nullable Runnable publish = clientTasks.poll(10, TimeUnit.SECONDS);
		assertNotNull(publish);
		assertFalse(pending.isDone(), "the hit must await client-thread validation");
		publish.run();
		assertTrue(pending.get(10, TimeUnit.SECONDS).isEmpty(), "the replaced wrapper must not publish a hit");
		assertEquals(500, Objects.requireNonNull(retiredCacheData.get()).pos.x, "retirement must not clear an in-flight cache");
		assertNull(Objects.requireNonNull(retiredCache.get()).stored.get(), "the retired cache is cleared after its last use");
	}

	private static Optional<BlockHitResult> trace(ClientLevel level) throws Exception {
		return startTrace(level, false, Runnable::run).get(10, TimeUnit.SECONDS);
	}

	private static CompletableFuture<Optional<BlockHitResult>> startTrace(ClientLevel level, boolean integratedServer,
		Executor executor) {
		var completion = new CompletableFuture<Optional<BlockHitResult>>();
		assertTrue(DistantHorizonsIntegration.traceDistantAsync(level, integratedServer, ORIGIN, DIRECTION, executor,
			completion::complete));
		return completion;
	}

	private static IDhApiWorldProxy world(boolean loaded, Supplier<Iterable<IDhApiLevelWrapper>> wrappers,
		@Nullable IDhApiLevelWrapper singlePlayer) {
		return stub(IDhApiWorldProxy.class, (ignored, method, args) -> switch (method.getName()) {
			case "worldLoaded" -> loaded;
			case "getAllLoadedLevelWrappers" -> wrappers.get();
			case "getSinglePlayerLevel" -> singlePlayer;
			default -> throw new AssertionError(method);
		});
	}

	private static IDhApiLevelWrapper wrapper(Object level) {
		return stub(IDhApiLevelWrapper.class, (proxy, method, args) -> switch (method.getName()) {
			case "getWrappedMcObject" -> level;
			case "getDimensionName", "getDhIdentifier" -> "minecraft:overworld";
			case "getDimensionType" -> OVERWORLD_TYPE;
			case "equals" -> proxy == Objects.requireNonNull(args)[0];
			case "hashCode" -> System.identityHashCode(proxy);
			default -> throw new AssertionError(method);
		});
	}

	private static void assertNoTerrainRead(ClientLevel level) {
		AtomicInteger calls = new AtomicInteger();
		DhApi.Delayed.terrainRepo = stub(IDhApiTerrainDataRepo.class, (ignored, method, args) -> {
			calls.incrementAndGet();
			throw new AssertionError("unexpected terrain call: " + method);
		});
		var completion = new CompletableFuture<Optional<BlockHitResult>>();
		assertFalse(DistantHorizonsIntegration.traceDistantAsync(level, false, ORIGIN, DIRECTION, Runnable::run,
			completion::complete));
		assertFalse(completion.isDone(), "the caller owns fallback when no trace starts");
		assertEquals(0, calls.get());
	}

	private static IDhApiTerrainDataRepo repo(Terrain terrain) {
		return repo(PositionOnlyCache::new, terrain);
	}

	private static IDhApiTerrainDataRepo repo(Supplier<PositionOnlyCache> cacheFactory, Terrain terrain) {
		return stub(IDhApiTerrainDataRepo.class, (ignored, method, args) -> switch (method.getName()) {
			case "createSoftCache" -> cacheFactory.get();
			case "raycast" -> {
				Object[] parameters = Objects.requireNonNull(args);
				assertEquals(ORIGIN.x, parameters[1]);
				assertEquals(ORIGIN.y, parameters[2]);
				assertEquals(ORIGIN.z, parameters[3]);
				assertEquals((float) DIRECTION.x, parameters[4]);
				assertEquals((float) DIRECTION.y, parameters[5]);
				assertEquals((float) DIRECTION.z, parameters[6]);
				yield DhApiResult.createSuccess(terrain.raycast((IDhApiLevelWrapper) parameters[0], (PositionOnlyCache) parameters[8]));
			}
			default -> throw new AssertionError(method);
		});
	}

	private static DhApiRaycastResult hit(int x) {
		return new DhApiRaycastResult(null, new DhApiVec3i(x, 70, 4));
	}

	private static <T> T stub(Class<T> type, InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
	}

	private static ClientLevel allocateLevel() throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return ClientLevel.class.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), ClientLevel.class));
	}

	@NotNullByDefault
	private interface Terrain {
		DhApiRaycastResult raycast(IDhApiLevelWrapper wrapper, PositionOnlyCache cache) throws InterruptedException;
	}

	@NotNullByDefault
	private static final class TimedTrace {
		private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.EPOCH);
		private final DistantHorizonsIntegration adapter = new DistantHorizonsIntegration(clock::get);
		private final ClientLevel level;
		private final PositionOnlyCache original = new PositionOnlyCache();
		private final AtomicInteger createAttempts = new AtomicInteger();
		private volatile boolean failAllocation;

		private TimedTrace(Terrain terrain) throws ReflectiveOperationException {
			level = allocateLevel();
			var selected = wrapper(level);
			DhApi.Delayed.worldProxy = world(true, () -> List.of(selected), null);
			DhApi.Delayed.terrainRepo = repo(() -> {
				int attempt = createAttempts.incrementAndGet();
				if (failAllocation) throw new IllegalStateException("terrain cache allocation failed");
				return attempt == 1 ? original : new PositionOnlyCache();
			}, (actual, cache) -> {
				assertSame(selected, actual);
				return terrain.raycast(actual, cache);
			});
		}

		private void setAge(Duration age) {
			clock.set(Instant.EPOCH.plus(age));
		}

		private Optional<BlockHitResult> trace() throws Exception {
			return start().get(10, TimeUnit.SECONDS);
		}

		private CompletableFuture<Optional<BlockHitResult>> start() {
			var completion = new CompletableFuture<Optional<BlockHitResult>>();
			assertTrue(adapter.traceAsync(level, false, ORIGIN, DIRECTION, Runnable::run, completion::complete));
			return completion;
		}
	}

	/** Models a coordinate cache with no implicit world partition. */
	@NotNullByDefault
	private static final class PositionOnlyCache implements IDhApiTerrainDataCache {
		private final AtomicReference<@Nullable DhApiRaycastResult> stored = new AtomicReference<>();
		private final AtomicInteger clearCalls = new AtomicInteger();
		private volatile Runnable beforeClear = () -> {};
		DhApiRaycastResult readAtSharedCoordinates(DhApiRaycastResult fresh) {
			return stored.updateAndGet(previous -> previous != null ? previous : fresh);
		}
		@Override public void clear() {
			clearCalls.incrementAndGet();
			beforeClear.run();
			stored.set(null);
		}
	}
}
