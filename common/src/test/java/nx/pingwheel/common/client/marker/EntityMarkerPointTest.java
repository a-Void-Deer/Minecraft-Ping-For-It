package nx.pingwheel.common.client.marker;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityParentFrame;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@NotNullByDefault
class EntityMarkerPointTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void topCenterLiftsBaseByBoundingBoxYSize() {
		assertEquals(new Vec3(10.5, 66.0, -3.0),
			EntityMarkerPoint.topCenter(new Vec3(10.5, 64.25, -3.0), 1.75));
	}

	@Test
	void topCenterKeepsXAndZAndAddsY() {
		assertEquals(new Vec3(-7.0, 4.0, 21.5),
			EntityMarkerPoint.topCenter(new Vec3(-7.0, 0.0, 21.5), 4.0));
	}

	@Test
	void zeroHeightReturnsBasePosition() {
		Vec3 base = new Vec3(3.0, 8.0, 9.0);

		assertEquals(base, EntityMarkerPoint.topCenter(base, 0.0));
	}

	@Test
	void movingInterpolatedBaseStillComputesTopCenter() {
		// An interpolated base between ticks (a moving entity) is lifted by the
		// full bounding box Y size, not scaled by the partial tick.
		assertEquals(new Vec3(0.5, 66.5, 1.5),
			EntityMarkerPoint.topCenter(new Vec3(0.5, 64.0, 1.5), 2.5));
	}

	@Test
	void rejectsNullBasePosition() {
		assertThrows(NullPointerException.class, () -> EntityMarkerPoint.topCenter(null, 1.0));
	}

	@Test
	void worldFrameUsesTheInterpolatedNativeBaseAndCurrentHeight() {
		TestEntity entity = new TestEntity();
		entity.setPos(1.0, 2.0, 3.0);
		entity.setOldPosAndRot();
		entity.setPos(3.0, 6.0, 9.0);

		Vec3 point = Objects.requireNonNull(EntityMarkerPoint.forLiveEntity(entity, 0.5F,
			(value, partialTick) -> EntityFrameResolution.world()));

		assertEquals(EntityMarkerPoint.topCenter(new Vec3(2.0, 4.0, 6.0),
			entity.getBoundingBox().getYsize()), point);
	}

	@Test
	void containedFrameProjectsOnlyTheBaseWhileHeightRemainsWorldUp() {
		TestEntity entity = new TestEntity();
		entity.setPos(1.0, 2.0, 3.0);
		entity.setOldPosAndRot();
		Matrix4d source = new Matrix4d()
			.translation(4.0, 5.0, 6.0)
			.rotateZ(Math.PI / 2.0)
			.scale(2.0);
		Quaterniond orientation = new Quaterniond().rotateZ(Math.PI / 2.0);
		EntityParentFrame frame = EntityParentFrame.copyOf(source, orientation);
		Vec3 expectedBase = frame.toWorld(entity.position());
		// Mutating the provider-owned values after capture must not move the
		// already copied frame.
		source.identity();
		orientation.identity();

		Vec3 point = Objects.requireNonNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F,
			(value, partialTick) -> EntityFrameResolution.contained(frame, UUID.randomUUID())));

		assertEquals(expectedBase.add(0.0, entity.getBoundingBox().getYsize(), 0.0), point);
	}

	@Test
	void unavailableRenderFrameProducesNoTrustedWorldPoint() {
		TestEntity entity = new TestEntity();
		entity.setPos(1.0, 2.0, 3.0);

		assertNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F,
			(value, partialTick) -> EntityFrameResolution.unavailable()));
	}

	@Test
	void failedRenderFrameProducesNoTrustedWorldPoint() {
		TestEntity entity = new TestEntity();
		entity.setPos(1.0, 2.0, 3.0);

		assertNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F,
			(value, partialTick) -> {
				throw new NoClassDefFoundError("optional render pose unavailable");
			}));
	}

	@Test
	void successfulMarkerPointResumesFollowingAfterUnavailableLiveFrames() {
		TestEntity entity = new TestEntity();
		EntityMarkerPositionTracker tracker = new EntityMarkerPositionTracker();
		EntityParentFrame frame = EntityParentFrame.copyOf(
			new Matrix4d().translation(10.0, 20.0, 30.0), new Quaterniond());
		Vec3 anchor = new Vec3(1.0, 2.0, 3.0);
		EntityMarkerPoint.RenderFrame contained =
			(value, partialTick) -> EntityFrameResolution.contained(frame, UUID.randomUUID());
		EntityMarkerPoint.RenderFrame unavailable =
			(value, partialTick) -> EntityFrameResolution.unavailable();

		// The first unavailable frame leaves the authoritative anchor visible.
		assertNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F, unavailable));
		assertEquals(position(anchor), tracker.resolve(position(anchor), null));

		// A successful frame becomes the shared render/cancel point.
		entity.setPos(1.0, 0.0, 2.0);
		Vec3 live = Objects.requireNonNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F, contained));
		assertEquals(position(live), tracker.resolve(position(anchor), position(live)));

		// Losing the frame retains that last live point until it succeeds again.
		assertNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F, unavailable));
		assertEquals(position(live), tracker.resolve(position(anchor), null));

		entity.setPos(2.0, 0.0, 4.0);
		Vec3 recovered = Objects.requireNonNull(EntityMarkerPoint.forLiveEntity(entity, 1.0F, contained));
		assertEquals(position(recovered), tracker.resolve(position(anchor), position(recovered)));
		assertEquals(position(recovered), tracker.resolve(position(anchor), null));
	}

	private static EntityMarkerPositionTracker.Position position(Vec3 point) {
		return new EntityMarkerPositionTracker.Position(point.x, point.y, point.z);
	}

	@NotNullByDefault
	private static final class TestEntity extends Entity {
		private TestEntity() {
			super(EntityType.COW, null);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {}

		@Override
		protected void readAdditionalSaveData(CompoundTag tag) {}

		@Override
		protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
