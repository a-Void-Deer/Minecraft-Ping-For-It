package nx.pingwheel.common.math;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.MinecraftTargetSnapshotFactory;
import nx.pingwheel.common.interaction.candidate.*;

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
		assertTrue(collect(List.of(near, behind), registry.snapshot(), 20, 20, collector));
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
			assertTrue(collect(List.of(entity(EntityType.PIG, 2), cow), registry.snapshot(), 20, 20, collector));
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
		assertTrue(collect(List.of(entity(EntityType.PIG, 2)), snapshot, 20, 20, collector));
		assertEquals(1, exactCalls.get());
		var hit = collector.evidence().getFirst();
		assertEquals(6, hit.distance());
		assertEquals("test:precise", hit.snapshot().entityLocalGeometryMetadata().orElseThrow().sourceId());
		assertTrue(hit.snapshot().blockHitFace().isEmpty());
	}

	@Test
	void finiteDistanceAndExhaustedEntityEnumerationDoNotCertifyArbitraryPrefix() {
		var registry = new EntityLocalGeometryRegistry();
		var collector = new CandidateCollector();
		assertTrue(collect(List.of(entity(EntityType.COW, 12)), registry.snapshot(), 10, 10, collector));
		assertTrue(collector.evidence().isEmpty());
		assertFalse(collect(List.of(entity(EntityType.COW, 8), entity(EntityType.COW, 2)), registry.snapshot(), 1, 20, collector));
		assertEquals(1, collector.evidence().size(), "caller must not certify this incomplete prefix");
	}

	@Test
	void blacklistToggleAndSpectatorFilterApplyBeforeSupplementalNarrowphase() {
		var registry = new EntityLocalGeometryRegistry();
		TestEntity spectator = new TestEntity(EntityType.PIG, true);
		spectator.setPos(2, 0, 0);
		var collector = new CandidateCollector();
		assertTrue(collect(List.of(spectator), registry.snapshot(), 10, 20, collector));
		assertTrue(collector.evidence().isEmpty());
		TestEntity ignored = entity(EntityType.COW, 4);
		try (var registration = EntitySelectionBlacklist.INSTANCE.register(entity -> entity == ignored)) {
			assertTrue(collect(List.of(ignored), registry.snapshot(), 10, 20, collector));
			assertTrue(collector.evidence().isEmpty());
			RaycastPolicy include = RaycastPolicy.from(false, true, false);
			assertTrue(Raycast.collectEntityCandidates(List.of(ignored, spectator), START, END, include, registry.snapshot(),
				request(END, include), new CandidateWorkBudget(new CandidateWorkLimits(0, 10, 0)), collector, "minecraft:overworld", null));
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

	private static boolean collect(List<Entity> entities, EntityLocalGeometryRegistry.Snapshot owners,
		int visits, double distance, CandidateCollector collector) {
		Vec3 end = new Vec3(distance, 0.5, 0);
		return Raycast.collectEntityCandidates(entities, START, end, POLICY, owners, request(end, POLICY),
			new CandidateWorkBudget(new CandidateWorkLimits(0, visits, 0)), collector, "minecraft:overworld", null);
	}
	private static EntityLocalRaycastRequest request(Vec3 end, RaycastPolicy policy) {
		return new EntityLocalRaycastRequest(START, end, policy, 1, CollisionContext.empty(), Vec3.ZERO);
	}
	private static TestEntity entity(EntityType<?> type, double x) {
		TestEntity entity = new TestEntity(type, false); entity.setPos(x, 0, 0); return entity;
	}
	private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("test", name); }
	private static ResourceLocation vanilla(String name) { return ResourceLocation.withDefaultNamespace(name); }
	private static final class TestEntity extends Entity {
		private final boolean spectator;
		TestEntity(EntityType<?> type, boolean spectator) { super(type, null); this.spectator = spectator; }
		@Override public boolean isSpectator() { return spectator; }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
