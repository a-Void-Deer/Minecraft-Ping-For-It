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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.candidate.CandidateCollector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class LoadedExactCandidateTest {
	private static final Vec3 START = new Vec3(0, .5, 0), END = new Vec3(10, .5, 0);
	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, false);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void productionOrdinaryAdmissionKeepsNativeFirstAndFindsRemoteAndProtrudingLoadedOwners() {
		var registry = new EntityLocalGeometryRegistry(); AtomicInteger calls = new AtomicInteger();
		registry.register(id("rotor"), 0, List.of(ResourceLocation.withDefaultNamespace("pig")),
			EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT,
			(entity, request) -> { calls.incrementAndGet(); return EntityLocalGeometryResult.hit(hit(.3)); });
		Entity nativeFirst = entity(EntityType.PIG, 30_000_000, 30_000_000);
		Entity protruding = entity(EntityType.PIG, 4, 20);
		Entity unrelated = entity(EntityType.COW, 2, 0);
		Entity excluded = entity(EntityType.COW, 0, 0);
		var candidates = Raycast.ordinaryCandidates(List.of(nativeFirst), List.of(nativeFirst, protruding, unrelated),
			excluded, registry.snapshot(), POLICY);
		var selected = Raycast.traceEntityCandidates(candidates, START, END, POLICY, registry.snapshot(), request()).orElseThrow();
		assertSame(nativeFirst, ((EntityHitResult) selected.hitResult()).getEntity(), "native first wins equal exact distance");
		assertEquals(2, calls.get(), "native/loaded duplicate is only traced once; unowned loaded supplement is not admitted");
		assertEquals(new Vec3(3, .5, 0), selected.worldPoint().orElseThrow());
	}

	@Test void admissionIsImmutableAndOwnedFailureAndFiltersNeverReviveRawBounds() {
		var registry = new EntityLocalGeometryRegistry(); AtomicInteger calls = new AtomicInteger();
		var registration = registry.register(id("rotor"), 0, List.of(ResourceLocation.withDefaultNamespace("pig")),
			EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT,
			(entity, request) -> { calls.incrementAndGet(); return EntityLocalGeometryResult.unavailable(); });
		var snapshot = registry.snapshot(); registration.close();
		Entity near = entity(EntityType.PIG, 2, 0), far = entity(EntityType.PIG, 30_000_000, 0);
		try (var ignored = EntitySelectionBlacklist.INSTANCE.register(value -> value == far)) {
			assertTrue(Raycast.traceEntityCandidates(List.of(near, far), START, END, POLICY, snapshot,
				request()).isEmpty());
			assertEquals(1, calls.get());
		}
		assertEquals(EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT, snapshot.firstOwner(far).admission());
	}

	@Test void preciseBypassesRawBoundsOnlyForDeclaredExactOwnersAndDeduplicatesByIdentity() {
		var registry = new EntityLocalGeometryRegistry(); AtomicInteger calls = new AtomicInteger();
		registry.register(id("rotor"), 0, List.of(ResourceLocation.withDefaultNamespace("pig")),
			EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT,
			(entity, request) -> { calls.incrementAndGet(); return EntityLocalGeometryResult.hit(hit(.4)); });
		Entity rotor = entity(EntityType.PIG, 30_000_000, 30_000_000), offRay = entity(EntityType.COW, 4, 10);
		@Unmodifiable List<Entity> raw = List.of(offRay, rotor, rotor);
		CandidateCollector collector = new CandidateCollector();
		Raycast.collectVisibleEntityCandidates(raw, new AABB(START, END).inflate(1), START, END, POLICY,
			registry.snapshot(), request(), collector, "minecraft:overworld", null);
		assertEquals(1, calls.get(), "the duplicate object must not invoke the exact owner twice");
		assertEquals(1, collector.evidence().size());
		var contact = collector.evidence().getFirst();
		assertEquals(new Target.EntityTarget("minecraft:overworld", rotor.getUUID()), contact.snapshot().target());
		assertEquals(4, contact.distance());
	}

	@Test void legacyOwnersDoNotStartLoadedSupplementAndExactEndpointIsStillExcluded() {
		var legacy = new EntityLocalGeometryRegistry();
		Iterable<Entity> forbiddenLoaded = () -> { throw new AssertionError("legacy query must not enumerate loaded supplement"); };
		Entity camera = entity(EntityType.COW, 0, 0);
		assertFalse(Raycast.ordinaryCandidates(List.of(), forbiddenLoaded, camera, legacy.snapshot(), POLICY).iterator().hasNext());
		var registry = new EntityLocalGeometryRegistry();
		registry.register(id("rotor"), 0, List.of(ResourceLocation.withDefaultNamespace("pig")),
			EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT, (entity, request) -> EntityLocalGeometryResult.hit(hit(1)));
		assertTrue(Raycast.traceEntityCandidates(List.of(entity(EntityType.PIG, 30_000_000, 0)), START, END, POLICY,
			registry.snapshot(), request()).isEmpty());
	}

	private static EntityLocalRaycastRequest request() {
		return new EntityLocalRaycastRequest(START, END, POLICY, 1, CollisionContext.empty(), Vec3.ZERO);
	}
	private static LocalGeometryHit hit(double t) {
		return new LocalGeometryHit(t, BlockPos.ZERO, LocalGeometryKind.BLOCK, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.empty(), new Vec3(t * 10, .5, 0));
	}
	private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("test", path); }
	private static Entity entity(EntityType<?> type, double x, double z) {
		Entity entity = new TestEntity(type); entity.setPos(x, 0, z); return entity;
	}
	@NotNullByDefault
	private static final class TestEntity extends Entity {
		TestEntity(EntityType<?> type) { super(type, null); }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
