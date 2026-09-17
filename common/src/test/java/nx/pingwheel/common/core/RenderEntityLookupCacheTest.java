package nx.pingwheel.common.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RenderEntityLookupCacheTest {

	private static final UUID ENTITY_A = new UUID(0L, 1L);
	private static final UUID ENTITY_B = new UUID(0L, 2L);
	private static final UUID ENTITY_C = new UUID(0L, 3L);
	private static final UUID MISSING = new UUID(0L, 4L);

	@Test
	void idleRenderPassesDoNotScanRenderableEntities() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		cache.beginFrame(world);
		cache.beginFrame(world);

		assertEquals(0, world.renderableScans);
	}

	@Test
	void oneColdIndexServesDistinctRepeatedHitsAndMissesInOnePass() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity a = new FakeEntity(1, ENTITY_A);
		FakeEntity b = new FakeEntity(2, ENTITY_B);
		world.spawn(a);
		world.spawn(b);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);

		assertSame(a, cache.findUuid(world, ENTITY_A));
		assertSame(b, cache.findUuid(world, ENTITY_B));
		assertSame(a, cache.findUuid(world, ENTITY_A));
		assertNull(cache.findUuid(world, MISSING));
		assertNull(cache.findUuid(world, MISSING));
		assertEquals(1, world.renderableScans);
	}

	@Test
	void duplicateUuidsSkipInvalidEntriesAndKeepTheFirstLiveRenderableEntity() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity removed = new FakeEntity(1, ENTITY_A);
		removed.removed = true;
		FakeEntity unregistered = new FakeEntity(2, ENTITY_A);
		FakeEntity firstLive = new FakeEntity(3, ENTITY_A);
		FakeEntity laterLive = new FakeEntity(4, ENTITY_A);
		world.addRenderable(removed);
		world.addRenderable(unregistered);
		world.spawn(firstLive);
		world.spawn(laterLive);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);

		assertSame(firstLive, cache.findUuid(world, ENTITY_A));
		assertEquals(1, world.renderableScans);
	}

	@Test
	void hudAndOutlineLookupsShareWarmEntriesWithoutAnotherScan() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity hudTarget = new FakeEntity(1, ENTITY_A);
		FakeEntity outlineTarget = new FakeEntity(2, ENTITY_B);
		world.spawn(hudTarget);
		world.spawn(outlineTarget);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		assertSame(hudTarget, cache.findUuid(world, ENTITY_A));
		assertSame(outlineTarget, cache.findUuid(world, ENTITY_B));
		assertEquals(1, world.renderableScans);

		cache.beginFrame(world);
		assertSame(outlineTarget, cache.findUuid(world, ENTITY_B));
		assertSame(hudTarget, cache.findUuid(world, ENTITY_A));
		assertSame(hudTarget, cache.findUuid(world, ENTITY_A));
		assertEquals(1, world.renderableScans);
	}

	@Test
	void sameDimensionButDifferentWorldObjectDoesNotReuseEntities() {
		FakeWorld oldWorld = new FakeWorld("minecraft:overworld");
		FakeEntity oldEntity = new FakeEntity(1, ENTITY_A);
		oldWorld.spawn(oldEntity);
		FakeWorld replacementWorld = new FakeWorld("minecraft:overworld");
		FakeEntity replacementEntity = new FakeEntity(1, ENTITY_A);
		replacementWorld.spawn(replacementEntity);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();
		assertEquals(oldWorld.dimensionId, replacementWorld.dimensionId);

		cache.beginFrame(oldWorld);
		assertSame(oldEntity, cache.findUuid(oldWorld, ENTITY_A));

		cache.beginFrame(replacementWorld);
		assertSame(replacementEntity, cache.findUuid(replacementWorld, ENTITY_A));
		assertEquals(1, oldWorld.renderableScans);
		assertEquals(1, replacementWorld.renderableScans);
	}

	@Test
	void removedOrUnloadedEntryIsNeverReturnedAgain() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity entity = new FakeEntity(1, ENTITY_A);
		world.spawn(entity);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		assertSame(entity, cache.findUuid(world, ENTITY_A));

		entity.removed = true;
		world.unload(entity);

		assertNull(cache.findUuid(world, ENTITY_A));
		assertEquals(1, world.renderableScans);
	}

	@Test
	void idReuseAndUuidMutationDoNotReturnStalePositiveEntries() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity original = new FakeEntity(1, ENTITY_A);
		world.spawn(original);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		assertSame(original, cache.findUuid(world, ENTITY_A));

		FakeEntity reusedId = new FakeEntity(1, ENTITY_B);
		world.replace(original, reusedId);

		assertNull(cache.findUuid(world, ENTITY_A));
		assertNull(cache.findUuid(world, ENTITY_B));

		cache.beginFrame(world);
		assertSame(reusedId, cache.findUuid(world, ENTITY_B));

		reusedId.uuid = ENTITY_C;
		assertNull(cache.findUuid(world, ENTITY_B));
		assertNull(cache.findUuid(world, ENTITY_C));

		cache.beginFrame(world);
		assertSame(reusedId, cache.findUuid(world, ENTITY_C));
	}

	@Test
	void sameUuidReplacementAndNegativeLookupBecomeVisibleOnTheNextPass() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity original = new FakeEntity(1, ENTITY_A);
		world.spawn(original);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		assertSame(original, cache.findUuid(world, ENTITY_A));

		FakeEntity replacement = new FakeEntity(2, ENTITY_A);
		world.replace(original, replacement);
		assertNull(cache.findUuid(world, ENTITY_A));

		cache.beginFrame(world);
		assertSame(replacement, cache.findUuid(world, ENTITY_A));

		cache.beginFrame(world);
		assertNull(cache.findUuid(world, MISSING));
		FakeEntity newlyVisible = new FakeEntity(3, MISSING);
		world.spawn(newlyVisible);
		assertNull(cache.findUuid(world, MISSING));

		cache.beginFrame(world);
		assertSame(newlyVisible, cache.findUuid(world, MISSING));
	}

	@Test
	void invalidatedWarmPositiveRebuildsOneIndexThenSharesItForFollowingHitAndMiss() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity original = new FakeEntity(1, ENTITY_A);
		world.spawn(original);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		assertSame(original, cache.findUuid(world, ENTITY_A));
		assertEquals(1, world.renderableScans);

		cache.beginFrame(world);
		original.resetValidationTouches();
		FakeEntity replacement = new FakeEntity(2, ENTITY_A);
		world.replace(original, replacement);

		assertSame(replacement, cache.findUuid(world, ENTITY_A));
		assertEquals(1, original.validationTouches);
		assertEquals(2, world.renderableScans);
		assertSame(replacement, cache.findUuid(world, ENTITY_A));
		assertNull(cache.findUuid(world, MISSING));
		assertEquals(2, world.renderableScans);
	}

	@Test
	void prunesUnusedPositiveEntriesBeforeAReplacementLookup() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity original = new FakeEntity(1, ENTITY_A);
		world.spawn(original);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		assertSame(original, cache.findUuid(world, ENTITY_A));
		assertEquals(1, world.renderableScans);

		cache.beginFrame(world);
		cache.beginFrame(world);
		original.resetValidationTouches();
		FakeEntity replacement = new FakeEntity(1, ENTITY_A);
		world.replace(original, replacement);

		assertSame(replacement, cache.findUuid(world, ENTITY_A));
		assertEquals(0, original.validationTouches);
		assertEquals(2, world.renderableScans);
	}

	@Test
	void clearAndNullFrameDropFilledCacheReferences() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		FakeEntity original = new FakeEntity(1, ENTITY_A);
		world.spawn(original);
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);
		assertSame(original, cache.findUuid(world, ENTITY_A));
		original.resetValidationTouches();
		FakeEntity afterClear = new FakeEntity(2, ENTITY_A);
		cache.clear();
		world.replace(original, afterClear);

		assertSame(afterClear, cache.findUuid(world, ENTITY_A));
		assertEquals(0, original.validationTouches);
		assertEquals(2, world.renderableScans);

		afterClear.resetValidationTouches();
		FakeEntity afterNullFrame = new FakeEntity(3, ENTITY_A);
		cache.beginFrame(null);
		world.replace(afterClear, afterNullFrame);

		assertSame(afterNullFrame, cache.findUuid(world, ENTITY_A));
		assertEquals(0, afterClear.validationTouches);
		assertEquals(3, world.renderableScans);
	}

	@Test
	void nullWorldAndUuidAreSafeWithoutScanning() {
		FakeWorld world = new FakeWorld("minecraft:overworld");
		RenderEntityLookupCache<FakeWorld, FakeEntity> cache = newCache();

		cache.beginFrame(world);

		assertNull(cache.findUuid(world, null));
		assertNull(cache.findUuid(null, ENTITY_A));
		assertEquals(0, world.renderableScans);
	}

	private static RenderEntityLookupCache<FakeWorld, FakeEntity> newCache() {
		return new RenderEntityLookupCache<>(new RenderEntityLookupCache.Access<>() {
			@Override
			public Iterable<FakeEntity> entitiesForRendering(FakeWorld world) {
				world.renderableScans++;
				return world.renderables;
			}

			@Override
			public FakeEntity getById(FakeWorld world, int id) {
				world.liveIdLookups++;
				return world.liveById.get(id);
			}

			@Override
			public UUID uuid(FakeEntity entity) {
				return entity.uuid;
			}

			@Override
			public int id(FakeEntity entity) {
				return entity.id;
			}

			@Override
			public boolean removed(FakeEntity entity) {
				entity.validationTouches++;
				return entity.removed;
			}
		});
	}

	private static final class FakeWorld {
		private final String dimensionId;
		private final List<FakeEntity> renderables = new ArrayList<>();
		private final Map<Integer, FakeEntity> liveById = new HashMap<>();
		private int renderableScans;
		private int liveIdLookups;

		private FakeWorld(String dimensionId) {
			this.dimensionId = dimensionId;
		}

		private void spawn(FakeEntity entity) {
			addRenderable(entity);
			liveById.put(entity.id, entity);
		}

		private void addRenderable(FakeEntity entity) {
			renderables.add(entity);
		}

		private void unload(FakeEntity entity) {
			renderables.remove(entity);
			liveById.remove(entity.id, entity);
		}

		private void replace(FakeEntity oldEntity, FakeEntity replacement) {
			renderables.remove(oldEntity);
			liveById.remove(oldEntity.id, oldEntity);
			spawn(replacement);
		}

		private void resetLookupCounts() {
			renderableScans = 0;
			liveIdLookups = 0;
		}
	}

	private static final class FakeEntity {
		private final int id;
		private UUID uuid;
		private boolean removed;
		private int validationTouches;

		private FakeEntity(int id, UUID uuid) {
			this.id = id;
			this.uuid = uuid;
		}

		private void resetValidationTouches() {
			validationTouches = 0;
		}
	}
}
