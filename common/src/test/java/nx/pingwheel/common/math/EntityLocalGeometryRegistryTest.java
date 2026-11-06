package nx.pingwheel.common.math;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.diagnostics.DiagnosticBackend;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.testing.DiagnosticLogCapture;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityLocalGeometryRegistryTest {

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void lowerPriorityWinsIndependentOfRegistrationOrderAndDuplicateCannotReplaceIt() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		AtomicInteger winnerCalls = new AtomicInteger();
		ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");

		EntityLocalGeometryRegistry.Registration later = registry.register(
			ResourceLocation.fromNamespaceAndPath("test", "later"), 200, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.2)));
		EntityLocalGeometryRegistry.Registration earlier = registry.register(
			ResourceLocation.fromNamespaceAndPath("test", "earlier"), 100, List.of(pig),
			(entity, request) -> {
				winnerCalls.incrementAndGet();
				return EntityLocalGeometryResult.hit(hit(0.1));
			});
		EntityLocalGeometryRegistry.Registration duplicate = registry.register(
			ResourceLocation.fromNamespaceAndPath("test", "earlier"), 0, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.0)));

		TestEntity pigEntity = new TestEntity(EntityType.PIG, null);
		EntityLocalGeometryRegistry.Claim claim = registry.snapshot().firstOwner(pigEntity);
		EntityLocalGeometryResult result = claim.trace(pigEntity, request());

		assertTrue(later.isActive());
		assertTrue(earlier.isActive());
		assertFalse(duplicate.isActive());
		assertEquals("test:earlier", claim.sourceId());
		assertEquals(EntityLocalGeometryResult.Outcome.HIT, result.outcome());
		assertEquals(1, winnerCalls.get());

		duplicate.close();
		earlier.close();
		earlier.close();
		assertEquals("test:later", registry.snapshot().firstOwner(pigEntity).sourceId());
	}

	@Test
	void recoverableCallbackFailureBecomesFailedWithoutTryingAnotherOwner() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");
		AtomicInteger fallbackCalls = new AtomicInteger();

		registry.register(ResourceLocation.fromNamespaceAndPath("test", "broken"), 1, List.of(pig),
			(entity, request) -> {
				throw new AssertionError("recoverable test failure");
			});
		registry.register(ResourceLocation.fromNamespaceAndPath("test", "fallback"), 2, List.of(pig),
			(entity, request) -> {
				fallbackCalls.incrementAndGet();
				return EntityLocalGeometryResult.hit(hit(0.1));
			});

		TestEntity pigEntity = new TestEntity(EntityType.PIG, null);
		EntityLocalGeometryResult result = registry.snapshot().firstOwner(pigEntity).trace(pigEntity, request());

		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, result.outcome());
		assertEquals(0, fallbackCalls.get());
	}

	@Test
	void invalidFluidHitIsContainedAsFailedAtTheOwnerBoundary() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");
		TestEntity pigEntity = new TestEntity(EntityType.PIG, null);

		registry.register(ResourceLocation.fromNamespaceAndPath("test", "invalid-fluid"), 1, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(new LocalGeometryHit(
				0.2, BlockPos.ZERO, LocalGeometryKind.FLUID, Blocks.STONE.defaultBlockState(),
				"minecraft:stone", Optional.empty(), new Vec3(2, 0, 0))));

		EntityLocalGeometryResult result = registry.snapshot().firstOwner(pigEntity).trace(pigEntity, request());

		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, result.outcome());
		assertTrue(result.localHit().isEmpty());
	}

	@Test
	void duplicateAndRejectedOutcomesKeepSafeNormalHandlingAndLazyRawDetail() {
		ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");
		ResourceLocation source = ResourceLocation.fromNamespaceAndPath("test", "source");

		try (var logs = new DiagnosticLogCapture()) {
			EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
			registry.register(source, 120, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.1)));
			registry.register(source, 999, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.2)));
			registry.register(source, 1000, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.3)));
			assertEquals(1, logs.operation("entity-local-geometry").size(), "the duplicate key reports once");
			String report = logs.operation("entity-local-geometry").getFirst().getMessage().getFormattedMessage();
			assertTrue(report.contains("handling=duplicate-first-kept"), report);
			assertFalse(report.contains("test:source"), "NORMAL must not disclose the source id");
			assertFalse(report.contains("priority="), "NORMAL must not disclose the priority");

			EntityLocalGeometryRegistry nullRegistry = new EntityLocalGeometryRegistry();
			nullRegistry.register(ResourceLocation.fromNamespaceAndPath("test", "null-normal"), 5, List.of(pig),
				(entity, request) -> null);
			TestEntity nullEntity = new TestEntity(EntityType.PIG, null);
			nullRegistry.snapshot().firstOwner(nullEntity).trace(nullEntity, request());

			EntityLocalGeometryRegistry callbackRegistry = new EntityLocalGeometryRegistry();
			callbackRegistry.register(ResourceLocation.fromNamespaceAndPath("test", "callback-normal"), 6, List.of(pig),
				(entity, request) -> { throw new IllegalStateException("callback-normal-sentinel"); });
			TestEntity callbackEntity = new TestEntity(EntityType.PIG, null);
			callbackRegistry.snapshot().firstOwner(callbackEntity).trace(callbackEntity, request());

			assertEquals(3, logs.operation("entity-local-geometry").size());
			assertTrue(logs.operation("entity-local-geometry").stream().anyMatch(event ->
				event.getMessage().getFormattedMessage().contains("handling=null-result-candidate-rejected")));
			assertTrue(logs.operation("entity-local-geometry").stream().anyMatch(event ->
				event.getMessage().getFormattedMessage().contains("handling=callback-failed-candidate-rejected")));
			for (var event : logs.operation("entity-local-geometry")) {
				String text = event.getMessage().getFormattedMessage();
				assertFalse(text.contains("test:"), text);
				assertFalse(text.contains("priority="), text);
				assertFalse(text.contains("callback-normal-sentinel"), text);
			}
		}

		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
			EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
			registry.register(source, 120, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.1)));
			registry.register(source, 999, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.2)));
			TestEntity pigEntity = new TestEntity(EntityType.PIG, null);
			registry.register(ResourceLocation.fromNamespaceAndPath("test", "null"), 7, List.of(pig),
				(entity, request) -> null);
			registry.snapshot().firstOwner(pigEntity).trace(pigEntity, request());
			assertEquals(1, logs.operation("entity-local-geometry").stream()
				.filter(event -> event.getMessage().getFormattedMessage().contains("handling=duplicate-first-kept")).count());
			String detail = logs.operation("entity-local-geometry").stream()
				.filter(event -> event.getMessage().getFormattedMessage().contains("handling=null-result-candidate-rejected"))
				.findFirst().orElseThrow().getMessage().getFormattedMessage();
			assertTrue(detail.contains("test:null"));
			assertTrue(detail.contains("priority=7"));
		}
	}

	@Test
	void disabledBackendDoesNotCollectOrSubmitTheWarning() {
		AtomicInteger reports = new AtomicInteger();
		LoggingProfile previousProfile = Diagnostics.profile(DiagnosticSide.CLIENT);
		DiagnosticBackend previous = swapBackend(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, org.apache.logging.log4j.Level severity) {
				return false;
			}

			@Override public void log(DiagnosticSide side, org.apache.logging.log4j.Level severity, String message) {
				reports.incrementAndGet();
			}
		});
		Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
		try {
			ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");
			ResourceLocation source = ResourceLocation.fromNamespaceAndPath("test", "disabled");
			EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
			registry.register(source, 1, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.1)));
			registry.register(source, 2, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.2)));
			assertEquals(0, reports.get());
		} finally {
			swapBackend(previous);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousProfile);
		}
	}

	@Test
	void blockedBackendDoesNotHoldTheRegistryLock() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		LoggingProfile previousProfile = Diagnostics.profile(DiagnosticSide.CLIENT);
		DiagnosticBackend previous = swapBackend(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, org.apache.logging.log4j.Level severity) {
				return true;
			}

			@Override public void log(DiagnosticSide side, org.apache.logging.log4j.Level severity, String message) {
				entered.countDown();
				try {
					release.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
		});
		Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
		var pool = Executors.newFixedThreadPool(2);
		try {
			ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");
			ResourceLocation source = ResourceLocation.fromNamespaceAndPath("test", "blocked");
			EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
			registry.register(source, 1, List.of(pig), (entity, request) -> EntityLocalGeometryResult.hit(hit(0.1)));

			var reporting = pool.submit(() -> registry.register(source, 2, List.of(pig),
				(entity, request) -> EntityLocalGeometryResult.hit(hit(0.2))));
			assertTrue(entered.await(5, TimeUnit.SECONDS), "the duplicate report must reach the blocking backend");

			var unrelated = pool.submit(() -> {
				registry.register(ResourceLocation.fromNamespaceAndPath("test", "other"), 3, List.of(pig),
					(entity, request) -> EntityLocalGeometryResult.hit(hit(0.3)));
				registry.snapshot();
				return true;
			});
			assertTrue(unrelated.get(2, TimeUnit.SECONDS), "registry work must not wait for the logging sink");
			release.countDown();
			assertFalse(reporting.get(2, TimeUnit.SECONDS).isActive());
		} finally {
			release.countDown();
			pool.shutdownNow();
			swapBackend(previous);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousProfile);
		}
	}

	private static DiagnosticBackend swapBackend(DiagnosticBackend replacement) {
		try {
			Method swap = Diagnostics.class.getDeclaredMethod("swapBackendForTesting", DiagnosticBackend.class);
			swap.setAccessible(true);
			return (DiagnosticBackend) swap.invoke(null, replacement);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("diagnostic backend test seam unavailable", failure);
		}
	}

	@Test
	void localHitRequiresFluidIdentityExactlyForFluidKind() {
		assertThrows(IllegalArgumentException.class, () -> new LocalGeometryHit(
			0.2, BlockPos.ZERO, LocalGeometryKind.FLUID, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.empty(), new Vec3(2, 0, 0)));
		assertThrows(IllegalArgumentException.class, () -> new LocalGeometryHit(
			0.2, BlockPos.ZERO, LocalGeometryKind.BLOCK, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.of("minecraft:water"), new Vec3(2, 0, 0)));
	}

	private static EntityLocalRaycastRequest request() {
		return new EntityLocalRaycastRequest(
			new Vec3(0, 0, 0), new Vec3(10, 0, 0),
			RaycastPolicy.from(false, false, false), 1.0f,
			CollisionContext.empty(), Vec3.ZERO);
	}

	private static LocalGeometryHit hit(double t) {
		return new LocalGeometryHit(
			t, BlockPos.ZERO, LocalGeometryKind.BLOCK, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.empty(), new Vec3(t * 10.0, 0, 0));
	}

	private static final class TestEntity extends Entity {

		private TestEntity(EntityType<?> type, Level level) {
			super(type, level);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {
			// no entity data is needed by these ownership tests
		}

		@Override
		protected void readAdditionalSaveData(CompoundTag tag) {
			// no persisted state is needed by these ownership tests
		}

		@Override
		protected void addAdditionalSaveData(CompoundTag tag) {
			// no persisted state is needed by these ownership tests
		}
	}
}
