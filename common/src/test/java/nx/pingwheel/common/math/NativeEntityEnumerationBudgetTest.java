package nx.pingwheel.common.math;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.interaction.candidate.CandidateCollector;
import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import nx.pingwheel.common.interaction.candidate.NativeEntityCandidateScan;

import static org.junit.jupiter.api.Assertions.*;

class NativeEntityEnumerationBudgetTest {
	private static final AABB BOUNDS = new AABB(0, -1, -1, 16, 2, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void productionLookupWalkChargesOffRayEntitiesBeforeAnyNativeAabbFilter() {
		EntityLookup<Entity> lookup = new EntityLookup<>();
		AtomicInteger sectionReads = new AtomicInteger();
		EntitySectionStorage<Entity> sections = new EntitySectionStorage<>(Entity.class, ignored -> Visibility.TRACKED) {
			@Override public void forEachAccessibleNonEmptySection(AABB bounds,
				net.minecraft.util.AbortableIterationConsumer<net.minecraft.world.level.entity.EntitySection<Entity>> consumer) {
				sectionReads.incrementAndGet();
				super.forEachAccessibleNonEmptySection(bounds, consumer);
			}
		};
		for (int i = 0; i < 4096; i++) {
			Entity offRay = entity(5, 5, 8);
			lookup.add(offRay);
			sections.getOrCreateSection(SectionPos.asLong(0, 0, 0)).add(offRay);
		}
		LevelEntityGetterAdapter<Entity> getter = new LevelEntityGetterAdapter<>(lookup, sections);
		AtomicInteger filteredCallbacks = new AtomicInteger();
		getter.get(BOUNDS, ignored -> filteredCallbacks.incrementAndGet());
		assertEquals(0, filteredCallbacks.get(), "native AABB callbacks hide all 4096 inspected nonmatches");
		assertTrue(sectionReads.get() > 0);
		sectionReads.set(0);
		AtomicInteger enumerated = new AtomicInteger();
		var collector = new CandidateCollector();
		Vec3 start = new Vec3(.5, .5, .5), end = new Vec3(15, .5, .5);
		RaycastPolicy policy = RaycastPolicy.from(false, false, false);
		assertFalse(Raycast.collectVisibleEntityCandidates(counted(getter.getAll(), enumerated), BOUNDS, start, end,
			policy, new EntityLocalGeometryRegistry().snapshot(),
			new EntityLocalRaycastRequest(start, end, policy, 1, CollisionContext.empty(), Vec3.ZERO),
			new CandidateWorkBudget(new CandidateWorkLimits(0, 2048, 0)), collector, "minecraft:overworld", null));
		assertEquals(2048, enumerated.get(), "no hidden list materialization or filtered-result cap");
		assertEquals(0, sectionReads.get(), "production uses lazy visible lookup, no entity-section walk");
		assertTrue(collector.evidence().isEmpty());
	}

	@Test
	void zeroBudgetDoesNotInspectEntityAndExactBudgetCanCompleteNativeLookup() {
		EntityLookup<Entity> lookup = new EntityLookup<>();
		lookup.add(entity(3, .5, .5));
		var getter = new LevelEntityGetterAdapter<>(lookup, new EntitySectionStorage<Entity>(Entity.class, ignored -> Visibility.TRACKED));
		AtomicInteger enumerated = new AtomicInteger(), callbacks = new AtomicInteger();
		assertFalse(NativeEntityCandidateScan.scan(counted(getter.getAll(), enumerated), BOUNDS, null,
			new CandidateWorkBudget(new CandidateWorkLimits(0, 0, 0)), ignored -> callbacks.incrementAndGet()));
		assertEquals(0, enumerated.get());
		assertEquals(0, callbacks.get());
		assertTrue(NativeEntityCandidateScan.scan(counted(getter.getAll(), enumerated), BOUNDS, null,
			new CandidateWorkBudget(new CandidateWorkLimits(0, 1, 0)), ignored -> callbacks.incrementAndGet()));
		assertEquals(1, enumerated.get());
		assertEquals(1, callbacks.get());
	}

	private static Iterable<Entity> counted(Iterable<Entity> source, AtomicInteger count) {
		return () -> new Iterator<>() {
			private final Iterator<Entity> delegate = source.iterator();
			@Override public boolean hasNext() { return delegate.hasNext(); }
			@Override public Entity next() { count.incrementAndGet(); return delegate.next(); }
		};
	}
	private static Entity entity(double x, double y, double z) {
		Entity entity = new TestEntity(); entity.setPos(x, y, z); return entity;
	}
	private static final class TestEntity extends Entity {
		TestEntity() { super(EntityType.COW, null); }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
