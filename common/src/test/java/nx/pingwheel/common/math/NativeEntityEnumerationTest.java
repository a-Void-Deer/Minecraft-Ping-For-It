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
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.candidate.CandidateCollector;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class NativeEntityEnumerationTest {
	private static final AABB BOUNDS = new AABB(0, -1, -1, 16, 2, 1);
	private static final Vec3 START = new Vec3(.5, .5, .5), END = new Vec3(15, .5, .5);
	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, false);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void visibleLookupInspectsOffRayEntriesWithoutWalkingNativeEntitySections() {
		EntityLookup<Entity> lookup = new EntityLookup<>();
		AtomicInteger sectionReads = new AtomicInteger();
		EntitySectionStorage<Entity> sections = new EntitySectionStorage<>(Entity.class, ignored -> Visibility.TRACKED) {
			@Override public void forEachAccessibleNonEmptySection(AABB bounds,
				net.minecraft.util.AbortableIterationConsumer<net.minecraft.world.level.entity.EntitySection<Entity>> consumer) {
				sectionReads.incrementAndGet();
				super.forEachAccessibleNonEmptySection(bounds, consumer);
			}
		};
		for (int i = 0; i < 3; i++) {
			Entity offRay = entity(5 + i, 5, 8);
			lookup.add(offRay);
			sections.getOrCreateSection(SectionPos.asLong(0, 0, 0)).add(offRay);
		}
		LevelEntityGetterAdapter<Entity> getter = new LevelEntityGetterAdapter<>(lookup, sections);
		AtomicInteger filteredCallbacks = new AtomicInteger();
		getter.get(BOUNDS, ignored -> filteredCallbacks.incrementAndGet());
		assertEquals(0, filteredCallbacks.get(), "the native AABB query hides the off-ray entries");
		assertTrue(sectionReads.get() > 0);
		sectionReads.set(0);
		AtomicInteger enumerated = new AtomicInteger();
		var collector = new CandidateCollector();
		collect(counted(getter.getAll(), enumerated), collector);
		assertEquals(3, enumerated.get(), "the raw visible view includes off-ray entries");
		assertEquals(0, sectionReads.get(), "production uses lazy visible lookup, no entity-section walk");
		assertTrue(collector.evidence().isEmpty());
	}

	@Test
	void visibleLookupPropagatesSourceFailureAfterAProvisionalHit() {
		Entity first = entity(3, 0, .5);
		var failure = new IllegalStateException("loaded entity view failed after first entity");
		Iterable<Entity> raw = () -> new Iterator<>() {
			private int nextIndex;
			@Override public boolean hasNext() { return nextIndex < 2; }
			@Override public Entity next() {
				if (nextIndex++ == 0) return first;
				throw failure;
			}
		};
		var collector = new CandidateCollector();
		assertSame(failure, assertThrows(IllegalStateException.class, () -> collect(raw, collector)),
			"the caller must not mistake the partial iteration for completion");
		assertEquals(1, collector.evidence().size(), "the failure occurs after usable provisional evidence");
		assertEquals(new Target.EntityTarget("minecraft:overworld", first.getUUID()),
			collector.evidence().getFirst().snapshot().target());
	}

	private static void collect(Iterable<Entity> visible, CandidateCollector collector) {
		Raycast.collectVisibleEntityCandidates(visible, BOUNDS, START, END, POLICY,
			new EntityLocalGeometryRegistry().snapshot(),
			new EntityLocalRaycastRequest(START, END, POLICY, 1, CollisionContext.empty(), Vec3.ZERO),
			collector, "minecraft:overworld", null);
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
	@NotNullByDefault
	private static final class TestEntity extends Entity {
		TestEntity() { super(EntityType.COW, null); }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
