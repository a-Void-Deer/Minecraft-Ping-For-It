package nx.pingwheel.common.client.outline;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlywheelMeshCacheTest {
	@Test
	void capturesPositionsUvsIndicesAndTextureWithoutSharingInputArrays() {
		float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
		float[] uvs = {0, 0, 1, 0, 0, 1};
		int[] indices = {0, 1, 2};

		FlywheelMeshCache.Model<String> model = FlywheelMeshCache.capture(List.of(
			new FlywheelMeshCache.Input<>(positions, uvs, indices, "minecraft:texture")));
		FlywheelMeshCache.Mesh<String> mesh = model.meshes().get(0);

		positions[0] = 99;
		uvs[0] = 99;
		indices[0] = 2;

		assertEquals(1, model.triangleCount());
		assertEquals("minecraft:texture", mesh.texture());
		assertArrayEquals(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, mesh.positions());
		assertArrayEquals(new float[] {0, 0, 1, 0, 0, 1}, mesh.uvs());
		assertArrayEquals(new int[] {0, 1, 2}, mesh.indices());
	}

	@Test
	void malformedUvIndexAndTriangleDataRejectTheWholeMesh() {
		assertThrows(IllegalArgumentException.class, () -> FlywheelMeshCache.capture(List.of(
			new FlywheelMeshCache.Input<>(
				new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
				new float[] {0, 0, 1, 0},
				new int[] {0, 1, 2},
				"texture"))));

		assertThrows(IllegalArgumentException.class, () -> FlywheelMeshCache.capture(List.of(
			new FlywheelMeshCache.Input<>(
				new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
				new float[] {0, 0, 1, 0, 0, 1},
				new int[] {0, 1, 3},
				"texture"))));

		assertThrows(IllegalArgumentException.class, () -> FlywheelMeshCache.capture(List.of(
			new FlywheelMeshCache.Input<>(
				new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
				new float[] {0, 0, 1, 0, 0, 1},
				new int[] {0, 1, 1},
				"texture"))));
	}

	@Test
	void aggregateTriangleBudgetRejectsBeforeValueReplay() {
		int triangleCount = FlywheelModelBudget.MAX_TRIANGLES_PER_MODEL + 1;
		FlywheelMeshCache.Input<String> overBudget = new FlywheelMeshCache.Input<>(
			new float[triangleCount * 9],
			new float[triangleCount * 6],
			new int[triangleCount * 3],
			"texture");

		assertThrows(IllegalArgumentException.class,
			() -> FlywheelMeshCache.capture(List.of(overBudget)));
	}
}
