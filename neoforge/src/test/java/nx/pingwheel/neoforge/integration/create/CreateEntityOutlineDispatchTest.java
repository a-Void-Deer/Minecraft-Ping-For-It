package nx.pingwheel.neoforge.integration.create;

import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityParentFrame;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class CreateEntityOutlineDispatchTest {
	private static final Vec3 CAMERA = new Vec3(10.0, 20.0, 30.0);
	private static final Vec3 NATIVE_ANCHOR = new Vec3(1.0, 2.0, 3.0);

	@BeforeEach
	void resetMaskScopeBefore() {
		CreateEntityOutlineMaskScope.resetForTests();
	}

	@AfterEach
	void resetMaskScopeAfter() {
		CreateEntityOutlineMaskScope.resetForTests();
	}

	@Test
	void containedRotorTranslatesScaledAnchorAndRotatesLocallyWithoutASecondTranslationOrLocalScale() {
		EntityParentFrame frame = EntityParentFrame.copyOf(
			new Matrix4d()
				.translation(100.0, 200.0, 300.0)
				.rotateY(Math.PI / 2.0)
				.scale(2.0, 3.0, 4.0),
			new Quaterniond().rotationY(Math.PI / 2.0));
		RecordingSink sink = new RecordingSink();

		CreateEntityOutlineDispatch.Result result = CreateEntityOutlineDispatch.dispatch(
			NATIVE_ANCHOR, CAMERA, 37.0F, 0.25F,
			EntityFrameResolution.contained(frame, new UUID(1L, 2L)), sink);

		assertEquals(CreateEntityOutlineDispatch.Result.DISPATCHED, result);
		assertEquals(1, sink.calls);
		assertEquals(0.0, sink.x);
		assertEquals(0.0, sink.y);
		assertEquals(0.0, sink.z);
		assertEquals(37.0F, sink.yaw);
		assertEquals(0.25F, sink.partialTick);

		// Parent T * R * S applies its full scale to the anchor: (112,206,298) - camera.
		Matrix4f expectedPose = new Matrix4f()
			.translation(102.0F, 186.0F, 268.0F)
			.rotateY((float) (Math.PI / 2.0));
		assertTrue(expectedPose.equals(sink.pose, 1.0E-5F), "pose must be T(anchor-camera) * R_parent");

		// The local point is rotated once and never scaled by the parent: (3,2,-1) + translation.
		Vector3f transformed = sink.pose.transformPosition(new Vector3f(1.0F, 2.0F, 3.0F));
		assertEquals(105.0F, transformed.x, 1.0E-4F);
		assertEquals(188.0F, transformed.y, 1.0E-4F);
		assertEquals(267.0F, transformed.z, 1.0E-4F);
	}

	@Test
	void worldEntityKeepsCameraRelativeNativeCoordinatesAndIdentityPose() {
		RecordingSink sink = new RecordingSink();

		CreateEntityOutlineDispatch.Result result = CreateEntityOutlineDispatch.dispatch(
			new Vec3(4.0, 5.0, 6.0), new Vec3(1.0, 2.0, 3.0), 37.0F, 0.25F,
			EntityFrameResolution.world(), sink);

		assertEquals(CreateEntityOutlineDispatch.Result.DISPATCHED, result);
		assertEquals(1, sink.calls);
		assertEquals(3.0, sink.x);
		assertEquals(3.0, sink.y);
		assertEquals(3.0, sink.z);
		assertEquals(37.0F, sink.yaw);
		assertEquals(0.25F, sink.partialTick);
		assertTrue(new Matrix4f().equals(sink.pose, 0.0F), "world dispatch must not add a parent pose");
	}

	@Test
	void unavailableFrameSkipsDispatch() {
		RecordingSink sink = new RecordingSink();

		CreateEntityOutlineDispatch.Result result = CreateEntityOutlineDispatch.dispatch(
			NATIVE_ANCHOR, CAMERA, 37.0F, 0.25F, EntityFrameResolution.unavailable(), sink);

		assertEquals(CreateEntityOutlineDispatch.Result.FRAME_UNAVAILABLE, result);
		assertEquals(0, sink.calls);
	}

	@Test
	void maskScopeRemainsActiveForTheSinkAndRestoresAfterSuccessAndFailure() {
		assertFalse(CreateEntityOutlineMaskScope.active());
		boolean[] activeInSink = {false};

		try (CreateEntityOutlineMaskScope.Scope ignored = CreateEntityOutlineMaskScope.enter()) {
			CreateEntityOutlineDispatch.dispatch(
				NATIVE_ANCHOR, CAMERA, 0.0F, 0.0F, EntityFrameResolution.world(),
				(x, y, z, yaw, partialTick, poseStack) -> activeInSink[0] = CreateEntityOutlineMaskScope.active());
			assertTrue(activeInSink[0]);
			assertTrue(CreateEntityOutlineMaskScope.active());

			assertThrows(IllegalStateException.class, () -> CreateEntityOutlineDispatch.dispatch(
				NATIVE_ANCHOR, CAMERA, 0.0F, 0.0F, EntityFrameResolution.world(),
				(x, y, z, yaw, partialTick, poseStack) -> {
					throw new IllegalStateException("sink failure");
				}));
			assertTrue(CreateEntityOutlineMaskScope.active(), "the helper must not consume the adapter scope");
		}

		assertFalse(CreateEntityOutlineMaskScope.active());
	}

	@NotNullByDefault
	private static final class RecordingSink implements CreateEntityOutlineDispatch.Sink {
		private int calls;
		private double x;
		private double y;
		private double z;
		private float yaw;
		private float partialTick;
		private final Matrix4f pose = new Matrix4f();

		@Override
		public void render(double x, double y, double z, float yaw, float partialTick, PoseStack poseStack) {
			calls++;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.partialTick = partialTick;
			pose.set(poseStack.last().pose());
		}
	}
}
