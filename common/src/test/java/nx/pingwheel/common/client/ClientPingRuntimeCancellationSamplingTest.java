package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.marker.ClientMarkerFixtures;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.marker.EntityMarkerPoint;
import nx.pingwheel.common.client.marker.MarkerOverlayState;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.cancel.CancelMarkerCandidate;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Capture-local cancellation sampling through the production runtime seam. */
@NotNullByDefault
class ClientPingRuntimeCancellationSamplingTest {

	private static final String DIMENSION = "minecraft:overworld";
	private static final UUID OWNER = new UUID(1, 1);
	private static final UUID UUID_A = new UUID(10, 1);
	private static final UUID UUID_B = new UUID(10, 2);
	private static final UUID UUID_C = new UUID(10, 3);
	private static final UUID UUID_D = new UUID(10, 4);

	private @Nullable Minecraft previousGame;
	private boolean previousSable;
	private boolean previousDistantHorizons;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach void isolateClientState() {
		previousGame = CommonClient.Game;
		previousSable = ModContext.HasSable;
		previousDistantHorizons = ModContext.HasDistantHorizons;
		ModContext.HasSable = false;
		ModContext.HasDistantHorizons = false;
		MarkerOverlayState.INSTANCE.clear();
	}

	@AfterEach void restoreClientState() {
		CommonClient.Game = previousGame;
		ModContext.HasSable = previousSable;
		ModContext.HasDistantHorizons = previousDistantHorizons;
		MarkerOverlayState.INSTANCE.clear();
	}

	@Test
	void oneCaptureResolvesEveryRequestedUuidInOneEarlyExitingPass() {
		var fixture = new Fixture();
		TestEntity first = fixture.entity(UUID_A, 0, 63, 10);
		fixture.entity(UUID_B, 0, 63, 20);
		TestEntity third = fixture.entity(UUID_C, 0, 63, 30);
		fixture.entity(UUID_D, 0, 63, 40);

		MarkerId near = new MarkerId(1);
		MarkerId repeated = new MarkerId(2);
		MarkerId far = new MarkerId(3);
		MarkerId block = new MarkerId(4);
		fixture.marker(near, EntityLocator.uuid(UUID_A), 0, 0, 0);
		fixture.marker(repeated, EntityLocator.uuid(UUID_A), 0, 0, 0);
		fixture.marker(far, EntityLocator.uuid(UUID_C), 0, 0, 0);
		fixture.blockMarker(block, 4, 5, 6);

		CancellationContext context = cancellation(fixture.runtime, ray());

		assertEquals(1, fixture.level.renderingReads);
		assertEquals(3, fixture.level.visited,
			"the scan stops after every requested UUID is found; UUID_D is never visited");
		assertLivePosition(candidate(context, near), first);
		assertLivePosition(candidate(context, repeated), first);
		assertLivePosition(candidate(context, far), third);
		assertEquals(new WorldVector(4, 5, 6), candidate(context, block).position());
	}

	@Test
	void aCaptureWithNoUuidTargetDoesNotTouchTheRenderingCollection() {
		var fixture = new Fixture();
		MarkerId block = new MarkerId(10);
		fixture.blockMarker(block, 1, 2, 3);

		CancellationContext context = cancellation(fixture.runtime, ray());

		assertEquals(0, fixture.level.renderingReads);
		assertEquals(0, fixture.level.visited);
		assertEquals(new WorldVector(1, 2, 3), candidate(context, block).position());
	}

	@Test
	void aCaptureWithoutOwnMarkersReturnsAnEmptySelectionWithoutScanning() {
		var fixture = new Fixture();

		FrozenPingPress press = capture(fixture.runtime);

		assertTrue(press.cancellation().selected().isEmpty());
		assertEquals(0, press.cancellation().candidateCount());
		assertEquals(0, fixture.level.renderingReads);
	}

	@Test
	void aRemovedFirstUuidMatchFallsBackPerMarkerWithoutReadingALaterDuplicate() {
		var fixture = new Fixture();
		TestEntity removed = fixture.entity(UUID_A, 0, 63, 10);
		TestEntity duplicate = fixture.entity(UUID_A, 0, 63, 30);
		Vec3 duplicatePoint = Objects.requireNonNull(EntityMarkerPoint.forLiveEntity(duplicate, 1.0F));
		removed.setRemoved(Entity.RemovalReason.KILLED);

		MarkerId first = new MarkerId(20);
		MarkerId second = new MarkerId(21);
		fixture.marker(first, EntityLocator.uuid(UUID_A), 1, 64, 5);
		fixture.marker(second, EntityLocator.uuid(UUID_A), 2, 64, 6);

		CancellationContext context = cancellation(fixture.runtime, ray());

		assertEquals(1, fixture.level.visited,
			"the first raw UUID match is authoritative even when that entity is removed");
		assertEquals(new WorldVector(1, 64, 5), candidate(context, first).position());
		assertEquals(new WorldVector(2, 64, 6), candidate(context, second).position(),
			"each marker applies its own fallback for the shared unavailable UUID");
		assertNotEquals(new WorldVector(duplicatePoint.x, duplicatePoint.y, duplicatePoint.z),
			candidate(context, first).position(), "the later duplicate is never used");
	}

	@Test
	void runtimeIdTargetsKeepTheirPerCandidateDirectLookupAndTypeCheck() {
		var fixture = new Fixture();
		TestEntity cow = new TestEntity(fixture.level, UUID_B);
		cow.setPos(0, 63, 10);
		fixture.level.byId.put(7, cow);
		MarkerId rejected = new MarkerId(30);
		MarkerId rejectedAgain = new MarkerId(31);
		fixture.marker(rejected, EntityLocator.runtimeId(7), 3, 64, 8);
		fixture.marker(rejectedAgain, EntityLocator.runtimeId(7), 4, 64, 9);

		CancellationContext rejectedContext = cancellation(fixture.runtime, ray());

		assertEquals(2, fixture.level.idLookups,
			"each runtime-id candidate keeps its own direct lookup instead of sharing a cache");
		assertEquals(0, fixture.level.renderingReads);
		assertEquals(new WorldVector(3, 64, 8), candidate(rejectedContext, rejected).position());
		assertEquals(new WorldVector(4, 64, 9), candidate(rejectedContext, rejectedAgain).position());
	}

	@Test
	void runtimeIdExperienceOrbUsesTheDirectLookupAndLivePoint() {
		var fixture = new Fixture();
		ExperienceOrb orb = new ExperienceOrb(fixture.level, 0, 63, 30, 1);
		orb.setId(8);
		fixture.level.byId.put(8, orb);
		MarkerId live = new MarkerId(32);
		fixture.marker(live, EntityLocator.runtimeId(8), 0, 64, 30);

		CancellationContext liveContext = cancellation(fixture.runtime, ray());

		assertEquals(1, fixture.level.idLookups);
		assertEquals(0, fixture.level.renderingReads);
		assertLivePosition(candidate(liveContext, live), orb);
	}

	@Test
	void successivePressesInOneTickObserveSpawnMovementAndRemoval() {
		var fixture = new Fixture();
		TestEntity first = fixture.entity(UUID_A, 0, 63, 10);
		MarkerId firstMarker = new MarkerId(40);
		MarkerId secondMarker = new MarkerId(41);
		fixture.marker(firstMarker, EntityLocator.uuid(UUID_A), 0, 64, 30);

		FrozenPingPress initial = capture(fixture.runtime);

		assertSelected(initial, firstMarker);
		assertEquals(1, initial.cancellation().candidateCount());

		fixture.entity(UUID_B, 0, 63, 20);
		fixture.marker(secondMarker, EntityLocator.uuid(UUID_B), 0, 64, 20);

		FrozenPingPress spawned = capture(fixture.runtime);

		assertSelected(spawned, firstMarker);
		assertEquals(2, spawned.cancellation().candidateCount(),
			"the spawned entity and its marker are sampled by the same tick's next press");

		first.setPos(0, 63, 40);

		FrozenPingPress moved = capture(fixture.runtime);

		assertSelected(moved, secondMarker);

		first.setPos(0, 63, 10);

		FrozenPingPress returned = capture(fixture.runtime);

		assertSelected(returned, firstMarker);

		fixture.level.entities.remove(first);
		first.setRemoved(Entity.RemovalReason.KILLED);

		FrozenPingPress removed = capture(fixture.runtime);

		assertSelected(removed, secondMarker);
		assertEquals(5, fixture.level.renderingReads,
			"each physical press owns exactly one rendering-entity pass");
	}

	private static CancellationContext cancellation(ClientPingRuntime runtime, CapturedRay ray) {
		try {
			var method = ClientPingRuntime.class.getDeclaredMethod("buildCancellationContext", CapturedRay.class);
			method.setAccessible(true);
			return (CancellationContext) Objects.requireNonNull(method.invoke(runtime, ray));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static FrozenPingPress capture(ClientPingRuntime runtime) {
		try {
			var method = ClientPingRuntime.class.getDeclaredMethod("captureWheelPress");
			method.setAccessible(true);
			return (FrozenPingPress) ((Optional<?>) Objects.requireNonNull(method.invoke(runtime))).orElseThrow();
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static void assertSelected(FrozenPingPress press, MarkerId markerId) {
		assertEquals(Optional.of(markerId),
			press.cancellation().selected().map(action -> action.markerId()));
	}

	private static CancelMarkerCandidate candidate(CancellationContext context, MarkerId markerId) {
		return context.candidates().stream()
			.filter(candidate -> candidate.markerId().equals(markerId))
			.findFirst()
			.orElseThrow();
	}

	private static void assertLivePosition(CancelMarkerCandidate candidate, Entity entity) {
		Vec3 expected = Objects.requireNonNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F));
		assertEquals(new WorldVector(expected.x, expected.y, expected.z), candidate.position());
	}

	private static CapturedRay ray() {
		return new CapturedRay(new WorldVector(0, 0, 0), new WorldVector(0, 0, 1));
	}

	@NotNullByDefault
	private static final class Fixture {
		final TestLevel level = TestLevel.create();
		final TestGame game = DoubleChestFixture.allocate(TestGame.class);
		final LocalPlayer player = DoubleChestFixture.allocate(LocalPlayer.class);
		final Camera camera = new Camera(level);
		final ClientMarkerStore store = ClientMarkerFixtures.localStore(20, 10_000);
		final ClientPingRuntime runtime;

		Fixture() {
			player.setUUID(OWNER);
			camera.setPos(0, 63, 0);
			camera.setYRot(0.0F);
			camera.setXRot(0.0F);
			game.level = level;
			game.player = player;
			game.cameraEntity = camera;
			CommonClient.Game = game;
			runtime = ClientPingRuntime.create((key, color) -> {}, packet -> {}, ClientRateLimitPolicy.DEFAULT,
				InteractionTimeSource.system(), store, null);
		}

		TestEntity entity(UUID uuid, double x, double y, double z) {
			TestEntity entity = new TestEntity(level, uuid);
			entity.setPos(x, y, z);
			level.entities.add(entity);
			return entity;
		}

		void marker(MarkerId id, EntityLocator locator, double x, double y, double z) {
			store.onCreated(new MarkerSnapshot(id, OWNER, new Target.EntityTarget(DIMENSION, locator),
				"entity", "attention", new MarkerAnchor(x, y, z), 1L, 20_000L), 1L);
		}

		void blockMarker(MarkerId id, int x, int y, int z) {
			store.onCreated(new MarkerSnapshot(id, OWNER, new Target.BlockTarget(DIMENSION, x, y, z, "minecraft:stone"),
				"block", "attention", new MarkerAnchor(x, y, z), 1L, 20_000L), 1L);
		}
	}

	@NotNullByDefault
	private static final class TestGame extends Minecraft {
		private TestGame() {
			super(null);
		}
	}

	@NotNullByDefault
	private static final class TestLevel extends ClientLevel {
		List<Entity> entities = new ArrayList<>();
		Map<Integer, Entity> byId = new HashMap<>();
		int renderingReads;
		int visited;
		int idLookups;

		private TestLevel() {
			super(null, null, null, null, 0, 0, null, null, false, 0);
		}

		static TestLevel create() {
			TestLevel level = DoubleChestFixture.allocate(TestLevel.class);
			level.entities = new ArrayList<>();
			level.byId = new HashMap<>();
			return level;
		}

		@Override public ResourceKey<Level> dimension() {
			return Level.OVERWORLD;
		}

		@Override public Iterable<Entity> entitiesForRendering() {
			renderingReads++;
			return () -> new Iterator<Entity>() {
				private int index;

				@Override public boolean hasNext() {
					return index < entities.size();
				}

				@Override public Entity next() {
					visited++;
					return entities.get(index++);
				}
			};
		}

		@Override public @Nullable Entity getEntity(int id) {
			idLookups++;
			return byId.get(id);
		}
	}

	@NotNullByDefault
	private static final class TestEntity extends Entity {
		private TestEntity(Level level, UUID uuid) {
			super(EntityType.COW, level);
			setUUID(uuid);
		}

		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}

		@Override protected void readAdditionalSaveData(CompoundTag tag) {}

		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}

	@NotNullByDefault
	private static final class Camera extends Entity {
		private Camera(Level level) {
			super(EntityType.COW, level);
		}

		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}

		@Override protected void readAdditionalSaveData(CompoundTag tag) {}

		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
