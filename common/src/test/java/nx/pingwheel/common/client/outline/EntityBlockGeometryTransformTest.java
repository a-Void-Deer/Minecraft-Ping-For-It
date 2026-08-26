package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class EntityBlockGeometryTransformTest {
	private static final float EPSILON = 0.00001F;

	@Test
	void identityMatchesTheMainWorldCameraRelativeFormula() {
		EntityBlockGeometryTransform transform =
			new EntityBlockGeometryTransform(new Matrix4d());
		BlockPos position = new BlockPos(12, -4, 7);
		Vec3 camera = new Vec3(10.25D, -5.5D, 9.75D);

		PoseStack pose = transform.createPoseStack(position, camera);
		Matrix4f matrix = pose.last().pose();

		assertEquals(position.getX() - camera.x, matrix.m30(), EPSILON);
		assertEquals(position.getY() - camera.y, matrix.m31(), EPSILON);
		assertEquals(position.getZ() - camera.z, matrix.m32(), EPSILON);
		assertEquals(1.0F, matrix.m00(), EPSILON);
		assertEquals(1.0F, matrix.m11(), EPSILON);
		assertEquals(1.0F, matrix.m22(), EPSILON);
		assertEquals(1.0F, pose.last().normal().m00(), EPSILON);
		assertEquals(1.0F, pose.last().normal().m11(), EPSILON);
		assertEquals(1.0F, pose.last().normal().m22(), EPSILON);
	}

	@Test
	void largeCoordinatesAreSubtractedBeforeFloatPoseTranslation() {
		Matrix4d localToWorld = new Matrix4d().translation(20_000_000.125D, 0.0D, 0.0D);
		EntityBlockGeometryTransform transform = new EntityBlockGeometryTransform(localToWorld);
		PoseStack pose = transform.createPoseStack(
			new BlockPos(20_000_000, 0, 0), new Vec3(40_000_000.0D, 0.0D, 0.0D));

		assertEquals(0.125F, pose.last().pose().m30(), EPSILON);

		Vector3f vertex = transform.cameraRelativeVertex(
			new Vec3(0.25D, 0.0D, 0.0D),
			new BlockPos(20_000_000, 0, 0),
			new Vec3(40_000_000.0D, 0.0D, 0.0D));
		assertEquals(0.375F, vertex.x(), EPSILON);
	}

	@Test
	void pivotRotationScaleAndOffsetUseTheDoubleWorldOriginAndFloatLinearPart() {
		// x' = -2y + 100, y' = 3x + 200, z' = 4z + 300.
		Matrix4d localToWorld = new Matrix4d()
			.m00(0.0D).m10(-2.0D).m20(0.0D).m30(100.0D)
			.m01(3.0D).m11(0.0D).m21(0.0D).m31(200.0D)
			.m02(0.0D).m12(0.0D).m22(4.0D).m32(300.0D);
		EntityBlockGeometryTransform transform = new EntityBlockGeometryTransform(localToWorld);
		PoseStack pose = transform.createPoseStack(
			new BlockPos(2, 3, 4),
			new Vec3(90.0D, 200.0D, 315.0D),
			new Vec3(0.25D, 0.5D, 0.75D));

		Matrix4f matrix = pose.last().pose();
		assertEquals(3.0F, matrix.m30(), EPSILON);
		assertEquals(6.75F, matrix.m31(), EPSILON);
		assertEquals(4.0F, matrix.m32(), EPSILON);
		assertEquals(0.0F, matrix.m00(), EPSILON);
		assertEquals(-2.0F, matrix.m10(), EPSILON);
		assertEquals(3.0F, matrix.m01(), EPSILON);
		assertEquals(4.0F, matrix.m22(), EPSILON);

		Matrix3f normal = pose.last().normal();
		assertEquals(-0.5F, normal.m10(), EPSILON);
		assertEquals(1.0F / 3.0F, normal.m01(), EPSILON);
		assertEquals(0.25F, normal.m22(), EPSILON);
	}

	@Test
	void vertexConversionAddsIntegerOriginBeforeApplyingTheTransform() {
		Matrix4d localToWorld = new Matrix4d()
			.m00(2.0D).m11(3.0D).m22(4.0D)
			.m30(10.0D).m31(-20.0D).m32(30.0D);
		EntityBlockGeometryTransform transform = new EntityBlockGeometryTransform(localToWorld);

		Vector3f result = transform.cameraRelativeVertex(
			new Vec3(0.25D, 0.5D, 0.75D),
			new BlockPos(4, 5, 6),
			new Vec3(1.0D, 2.0D, 3.0D));

		assertEquals(17.5F, result.x(), EPSILON);
		assertEquals(-5.5F, result.y(), EPSILON);
		assertEquals(54.0F, result.z(), EPSILON);
	}

	@Test
	void sourceMatrixAndReturnedMatrixAreDefensivelyCopied() {
		Matrix4d source = new Matrix4d().translation(5.0D, 6.0D, 7.0D);
		EntityBlockGeometryTransform transform = new EntityBlockGeometryTransform(source);
		source.m30(500.0D);
		Matrix4d returned = transform.localToWorld();
		returned.m31(600.0D);

		PoseStack pose = transform.createPoseStack(new BlockPos(1, 2, 3), Vec3.ZERO);
		assertEquals(6.0F, pose.last().pose().m30(), EPSILON);
		assertEquals(8.0F, pose.last().pose().m31(), EPSILON);
		assertEquals(10.0F, pose.last().pose().m32(), EPSILON);
	}

	@Test
	void contextKeepsLegacyNullTransformAndAcceptsEveryTargetKey() {
		EntityBlockGeometryContext empty = EntityBlockGeometryContext.empty();
		assertNull(empty.transform());
		assertNull(empty.targetKey());

		TargetKey.ExternalBlockKey external = new TargetKey.ExternalBlockKey(
			"minecraft:overworld", "test", "plot-1", "minecraft:stone");
		EntityBlockGeometryContext context = new EntityBlockGeometryContext(
			null, null, null, null, 0xFFFFFFFF, Vec3.ZERO, 0.0F, 0.0F, 0,
			null, null, null, external, 1L, null);
		assertNotSame(empty, context);
		assertEquals(external, context.targetKey());
		assertNull(context.transform());
	}
}
