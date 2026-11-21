package nx.pingwheel.common.math;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import nx.pingwheel.common.diagnostics.DiagnosticBackend;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.MinecraftTargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.*;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.apache.logging.log4j.Level;

import static org.junit.jupiter.api.Assertions.*;

class RaycastSupplementalCandidateTest {
	private static final Vec3 START = new Vec3(0, 0.5, 0);
	private static final Vec3 END = new Vec3(20, 0.5, 0);
	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, false);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void supplementalFlowRetainsFartherEntitiesInsteadOfCollapsingToOrdinaryNearest() {
		var collector = new CandidateCollector();
		var registry = new EntityLocalGeometryRegistry();
		TestEntity near = entity(EntityType.COW, 2);
		TestEntity behind = entity(EntityType.COW, 8);
		collect(List.of(near, behind), registry.snapshot(), 20, collector);
		assertEquals(2, collector.evidence().size());
		assertEquals(new Target.EntityTarget("minecraft:overworld", behind.getUUID()), collector.evidence().get(1).snapshot().target());
		assertTrue(collector.evidence().get(1).distance() > 7);
	}

	@Test
	void allOwnedNonHitsNeverReenterViaTheirCoarseBoxesWhileSeparateTargetsRemain() {
		for (EntityLocalGeometryResult result : List.of(EntityLocalGeometryResult.miss(),
			EntityLocalGeometryResult.unavailable(), EntityLocalGeometryResult.failed())) {
			var registry = new EntityLocalGeometryRegistry();
			registry.register(id("owned"), 1, List.of(vanilla("pig")), (entity, request) -> result);
			var collector = new CandidateCollector();
			TestEntity cow = entity(EntityType.COW, 6);
			collect(List.of(entity(EntityType.PIG, 2), cow), registry.snapshot(), 20, collector);
			assertEquals(1, collector.evidence().size());
			assertEquals(new Target.EntityTarget("minecraft:overworld", cow.getUUID()), collector.evidence().getFirst().snapshot().target());
		}
	}

	@Test
	void sameOwnerSnapshotAndPreciseMetadataSurviveRegistryMutation() {
		var registry = new EntityLocalGeometryRegistry();
		AtomicInteger exactCalls = new AtomicInteger();
		registry.register(id("precise"), 1, List.of(vanilla("pig")), (entity, request) -> {
			exactCalls.incrementAndGet();
			return EntityLocalGeometryResult.hit(new LocalGeometryHit(0.3, BlockPos.ZERO, LocalGeometryKind.BLOCK,
				Blocks.STONE.defaultBlockState(), "minecraft:stone", Optional.empty(), new Vec3(1, 2, 3)));
		});
		var snapshot = registry.snapshot();
		registry.register(id("later"), 0, List.of(vanilla("pig")), (entity, request) -> EntityLocalGeometryResult.miss());
		var collector = new CandidateCollector();
		collect(List.of(entity(EntityType.PIG, 2)), snapshot, 20, collector);
		assertEquals(1, exactCalls.get());
		var hit = collector.evidence().getFirst();
		assertEquals(6, hit.distance());
		assertEquals("test:precise", hit.snapshot().entityLocalGeometryMetadata().orElseThrow().sourceId());
		assertTrue(hit.snapshot().blockHitFace().isEmpty());
	}

	@Test
	void supplementalScanDoesNotAdmitAnEntityBeyondTheFiniteSegment() {
		var registry = new EntityLocalGeometryRegistry();
		var collector = new CandidateCollector();
		collect(List.of(entity(EntityType.COW, 12)), registry.snapshot(), 10, collector);
		assertTrue(collector.evidence().isEmpty());
	}

	@Test
	void laterNativeReadFailureLeavesBlockTypesUncertifiedAndPreservesTheOrdinarySelection() {
		var world = DoubleChestFixture.allocate(PartialBlockWorld.class);
		var camera = entity(EntityType.COW, .5);
		camera.attach(world);
		double range = 2048;
		var start = new Vec3(.5, .5, .5);
		var ray = new CapturedRay(new WorldVector(.5, .5, .5), new WorldVector(1, 0, 0));
		BlockHitResult expectedNative = world.clip(PingClipContext.create(start, start.add(range, 0, 0),
			POLICY, CollisionContext.of(camera)));
		var nativeCapture = CandidateBlockCapture.nativeBlocks();
		List<BlockHitResult> provisional = new ArrayList<>();
		var trace = Raycast.traceDirectionalCandidates(camera, new ActiveInteraction().begin(), ray,
			range, range, POLICY, (level, hit, from, to) -> {
				provisional.add(hit);
				return nativeCapture.capture(level, hit, from, to);
			}).orElseThrow();

		assertTrue(world.failedRead, "the real state read must fail after the first block");
		assertEquals(1, provisional.size());
		BlockHitResult exactContact = provisional.getFirst();
		Vec3 surface = exactContact.getLocation();
		assertEquals(expectedNative.getBlockPos(), exactContact.getBlockPos());
		assertEquals(new Vec3(2, .5, .5), surface, "Precise found the same stone's front face");
		assertTrue(surface.x < expectedNative.getLocation().x, "the recontact is nearer than the native hit");
		var ordinary = trace.ordinarySelection();
		var ordinaryHit = assertInstanceOf(BlockHitResult.class, ordinary.hitResult());
		assertEquals(expectedNative.getType(), ordinaryHit.getType());
		assertEquals(expectedNative.getBlockPos(), ordinaryHit.getBlockPos());
		assertEquals(expectedNative.getDirection(), ordinaryHit.getDirection());
		assertEquals(expectedNative.isInside(), ordinaryHit.isInside());
		assertEquals(expectedNative.getLocation(), ordinaryHit.getLocation());
		assertEquals(expectedNative.getLocation(), ordinary.worldPoint().orElseThrow());
		var reference = MinecraftTargetSnapshotFactory.fromCandidateSelection(world, ordinary);
		var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
		var set = trace.candidates().orElseThrow().finish(reference, resolver.resolve(reference.target(), reference.matchContext()), resolver);
		for (var type : List.of(PreciseTargetType.BLOCK, PreciseTargetType.ENTITY_BLOCK)) {
			assertEquals(PreciseSlot.Availability.INCOMPLETE, set.slot(type).availability(), type.name());
			assertTrue(set.slot(type).candidateId().isEmpty(), type.name());
		}
		assertEquals(PreciseSlot.Availability.MISSING, set.slot(PreciseTargetType.ENTITY).availability(),
			"the independent entity view completed with no candidates");
		var locationSlot = set.slot(PreciseTargetType.LOCATION);
		assertEquals(PreciseSlot.Availability.AVAILABLE, locationSlot.availability());
		var location = set.candidate(locationSlot.candidateId().orElseThrow()).orElseThrow();
		// The earlier exact contact can refine the audit and derived location for this
		// same identity without certifying either scanned block type.
		var expectedRecontact = new WorldVector(surface.x, surface.y, surface.z);
		assertEquals(expectedRecontact, set.ordinary().worldHit());
		assertEquals(expectedRecontact, location.worldHit());
	}

	@Test
	void blacklistToggleAndSpectatorFilterApplyBeforeSupplementalNarrowphase() {
		var registry = new EntityLocalGeometryRegistry();
		TestEntity spectator = new TestEntity(EntityType.PIG, true);
		spectator.setPos(2, 0, 0);
		var collector = new CandidateCollector();
		collect(List.of(spectator), registry.snapshot(), 20, collector);
		assertTrue(collector.evidence().isEmpty());
		TestEntity ignored = entity(EntityType.COW, 4);
		try (var registration = EntitySelectionBlacklist.INSTANCE.register(entity -> entity == ignored)) {
			collect(List.of(ignored), registry.snapshot(), 20, collector);
			assertTrue(collector.evidence().isEmpty());
			RaycastPolicy include = RaycastPolicy.from(false, true, false);
			Raycast.collectEntityCandidates(List.of(ignored, spectator), START, END, include, registry.snapshot(),
				request(END, include), collector, "minecraft:overworld", null);
			assertEquals(1, collector.evidence().size());
		}
	}

	@Test
	void candidateFactoryUsesCanonicalDragonOwnerAndRuntimeXpLocatorNotPartUuid() throws Exception {
		// Mob construction requires a live level. This boundary only reads owner/type/UUID,
		// so initialize those explicitly without pretending to simulate a dragon world.
		Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		Object unsafe = unsafeField.get(null);
		EnderDragon dragon = (EnderDragon) unsafeClass.getMethod("allocateInstance", Class.class)
			.invoke(unsafe, EnderDragon.class);
		var typeField = Entity.class.getDeclaredField("type");
		typeField.setAccessible(true);
		typeField.set(dragon, EntityType.ENDER_DRAGON);
		dragon.setUUID(java.util.UUID.randomUUID());
		EnderDragonPart part = (EnderDragonPart) unsafeClass.getMethod("allocateInstance", Class.class)
			.invoke(unsafe, EnderDragonPart.class);
		var parentField = EnderDragonPart.class.getDeclaredField("parentMob");
		parentField.setAccessible(true);
		parentField.set(part, dragon);
		var dragonSnapshot = MinecraftTargetSnapshotFactory.fromEntityCandidateSelection("minecraft:overworld",
			RaycastSelection.withoutLocalGeometry(new EntityHitResult(part, new Vec3(3, 0.5, 0))));
		assertEquals(new Target.EntityTarget("minecraft:overworld", dragon.getUUID()), dragonSnapshot.target());
		assertEquals(CaptureEquivalenceKey.nativeTarget(dragonSnapshot.target()), dragonSnapshot.candidateHit().orElseThrow().equivalenceKey());
		assertTrue(dragonSnapshot.entityCaptureMetadata().orElseThrow().canonicalizedMultipart());
		ExperienceOrb xp = new ExperienceOrb(EntityType.EXPERIENCE_ORB, null);
		xp.setId(314);
		var xpSnapshot = MinecraftTargetSnapshotFactory.fromEntityCandidateSelection("minecraft:overworld",
			RaycastSelection.withoutLocalGeometry(new EntityHitResult(xp, new Vec3(2, 0.5, 0))));
		assertEquals(EntityLocator.runtimeId(314), ((Target.EntityTarget) xpSnapshot.target()).locator());
	}

	@Test
	void supplementalScanFailuresReportSafeNormalSummariesAndLazyDecisionContext() {
		try (var logs = new DiagnosticLogCapture()) {
			var token = new ActiveInteraction().begin();
			Raycast.reportEntityScanIncomplete(token, () -> "minecraft:overworld", 10, 100, POLICY,
				new IllegalStateException("entity-scan-sentinel"));
			Raycast.reportBlockScanIncomplete(token, () -> "minecraft:overworld", 10, 100, POLICY,
				new IllegalStateException("block-scan-sentinel"));

			var entity = logs.operation("precise/entity-scan").getFirst();
			assertEquals(Level.WARN, entity.getLevel());
			assertNull(entity.getThrown());
			String entityReport = entity.getMessage().getFormattedMessage();
			assertTrue(entityReport.contains("handling=entity-slots-incomplete"), entityReport);
			assertTrue(entityReport.contains("failure=invalid-state"), entityReport);
			assertFalse(entityReport.contains("entity-scan-sentinel"));

			var block = logs.operation("precise/block-scan").getFirst();
			assertEquals(Level.WARN, block.getLevel());
			assertNull(block.getThrown());
			String blockReport = block.getMessage().getFormattedMessage();
			assertTrue(blockReport.contains("handling=block-slots-incomplete"), blockReport);
			assertTrue(blockReport.contains("failure=invalid-state"), blockReport);
			assertFalse(blockReport.contains("block-scan-sentinel"));
		}

		try (var logs = new DiagnosticLogCapture(nx.pingwheel.common.diagnostics.LoggingProfile.DEEP)) {
			var token = new ActiveInteraction().begin();
			Raycast.reportEntityScanIncomplete(token, () -> "minecraft:overworld", 10, 100, POLICY,
				new IllegalStateException("deep-entity-scan-sentinel"));
			String report = logs.operation("precise/entity-scan").getFirst().getMessage().getFormattedMessage();
			assertTrue(report.contains("token=" + token.sequence()), report);
			assertTrue(report.contains("dimension=minecraft:overworld"), report);
			assertTrue(report.contains("nativeRange=10.0"), report);
			assertTrue(report.contains("pingRange=100.0"), report);
			assertTrue(report.contains("policy="), report);
			assertTrue(report.contains("deep-entity-scan-sentinel"), report);
		}
	}

	@Test
	void scanFailureDimensionSupplierStaysLazyUntilDeepAdmissionAndSuppression() {
		AtomicInteger entityReads = new AtomicInteger();
		AtomicInteger blockReads = new AtomicInteger();
		try (var logs = new DiagnosticLogCapture()) {
			var token = new ActiveInteraction().begin();
			Raycast.reportEntityScanIncomplete(token, () -> {
				entityReads.incrementAndGet();
				return "minecraft:overworld";
			}, 10, 100, POLICY, new IllegalStateException("normal-entity-sentinel"));
			Raycast.reportBlockScanIncomplete(token, () -> {
				blockReads.incrementAndGet();
				return "minecraft:overworld";
			}, 10, 100, POLICY, new IllegalStateException("normal-block-sentinel"));
			assertEquals(0, entityReads.get(), "NORMAL must not read the dimension context");
			assertEquals(0, blockReads.get(), "NORMAL must not read the dimension context");
			assertEquals(1, logs.operation("precise/entity-scan").size());
			assertEquals(1, logs.operation("precise/block-scan").size());
		}

		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEEP)) {
			var token = new ActiveInteraction().begin();
			Raycast.reportEntityScanIncomplete(token, () -> {
				entityReads.incrementAndGet();
				return "minecraft:overworld";
			}, 10, 100, POLICY, new IllegalStateException("deep-entity-sentinel"));
			Raycast.reportEntityScanIncomplete(token, () -> {
				entityReads.incrementAndGet();
				return "minecraft:overworld";
			}, 10, 100, POLICY, new IllegalStateException("suppressed-entity-sentinel"));
			Raycast.reportBlockScanIncomplete(token, () -> {
				blockReads.incrementAndGet();
				return "minecraft:overworld";
			}, 10, 100, POLICY, new IllegalStateException("deep-block-sentinel"));
			assertEquals(1, entityReads.get(), "only the admitted DEEP report reads the dimension context");
			assertEquals(1, blockReads.get());
			assertEquals(1, logs.operation("precise/entity-scan").size());
			assertTrue(logs.operation("precise/entity-scan").getFirst().getMessage().getFormattedMessage()
				.contains("dimension=minecraft:overworld"));
		}
	}

	@Test
	void scanFailureDimensionSupplierIsNotReadWhenBackendDisabled() {
		AtomicInteger reads = new AtomicInteger();
		LoggingProfile previousProfile = Diagnostics.profile(DiagnosticSide.CLIENT);
		DiagnosticBackend previous = swapBackend(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return false; }
			@Override public void log(DiagnosticSide side, Level severity, String message) {
				throw new AssertionError("disabled backend must receive no report");
			}
		});
		Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
		try {
			var token = new ActiveInteraction().begin();
			Raycast.reportEntityScanIncomplete(token, () -> {
				reads.incrementAndGet();
				return "minecraft:overworld";
			}, 10, 100, POLICY, new IllegalStateException("disabled-entity-sentinel"));
			Raycast.reportBlockScanIncomplete(token, () -> {
				reads.incrementAndGet();
				return "minecraft:overworld";
			}, 10, 100, POLICY, new IllegalStateException("disabled-block-sentinel"));
			assertEquals(0, reads.get());
		} finally {
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

	private static void collect(List<Entity> entities, EntityLocalGeometryRegistry.Snapshot owners,
		double distance, CandidateCollector collector) {
		Vec3 end = new Vec3(distance, 0.5, 0);
		Raycast.collectEntityCandidates(entities, START, end, POLICY, owners, request(end, POLICY),
			collector, "minecraft:overworld", null);
	}
	private static EntityLocalRaycastRequest request(Vec3 end, RaycastPolicy policy) {
		return new EntityLocalRaycastRequest(START, end, policy, 1, CollisionContext.empty(), Vec3.ZERO);
	}
	private static TestEntity entity(EntityType<?> type, double x) {
		TestEntity entity = new TestEntity(type, false); entity.setPos(x, 0, 0); return entity;
	}
	private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("test", name); }
	private static ResourceLocation vanilla(String name) { return ResourceLocation.withDefaultNamespace(name); }

	@NotNullByDefault
	private static final class PartialBlockWorld extends ClientLevel {
		private static final BlockPos FIRST = new BlockPos(2, 0, 0);
		private boolean failedRead;
		private PartialBlockWorld() { super(null, null, null, null, 0, 0, null, null, false, 0); }
		@Override public ResourceKey<net.minecraft.world.level.Level> dimension() { return OVERWORLD; }
		@Override public @Unmodifiable List<Entity> getEntities(@Nullable Entity except, AABB area, Predicate<? super Entity> filter) { return List.of(); }
		@Override public @Unmodifiable Iterable<Entity> entitiesForRendering() { return List.of(); }
		@Override public boolean isLoaded(BlockPos pos) { return true; }
		@Override public BlockState getBlockState(BlockPos pos) {
			if (pos.getX() == 4) {
				failedRead = true;
				throw new IllegalStateException("later loaded block state is unavailable");
			}
			return (pos.equals(FIRST) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
		}
		@Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
		@Override public BlockHitResult clip(ClipContext context) {
			return Objects.requireNonNull(context.getBlockShape(getBlockState(FIRST), this, FIRST)
				.clip(context.getFrom(), context.getTo(), FIRST));
		}
	}

	private static final class TestEntity extends Entity {
		private final boolean spectator;
		TestEntity(EntityType<?> type, boolean spectator) { super(type, null); this.spectator = spectator; }
		void attach(net.minecraft.world.level.Level level) { setLevel(level); }
		@Override public boolean isSpectator() { return spectator; }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
