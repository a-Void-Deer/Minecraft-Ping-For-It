package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywheelModelBudgetTest {
	@Test
	void aggregateManyMeshCountsAreRejectedBeforeMeshExtraction() {
		List<FlywheelModelBudget.MeshCounts> meshes = new ArrayList<>();
		meshes.add(new FlywheelModelBudget.MeshCounts(
			FlywheelModelBudget.MAX_VERTICES_PER_MODEL, 3));
		meshes.add(new FlywheelModelBudget.MeshCounts(1, 3));

		FlywheelModelBudget.Preflight result = FlywheelModelBudget.preflight(meshes);

		assertFalse(result.valid());
	}

	@Test
	void validAggregateCountsStayWithinCheckedPositionUvIndexAndTriangleBudgets() {
		FlywheelModelBudget.Preflight result = FlywheelModelBudget.preflight(List.of(
			new FlywheelModelBudget.MeshCounts(2, 3),
			new FlywheelModelBudget.MeshCounts(4, 6)));

		assertTrue(result.valid());
		assertTrue(result.positionComponentCount() <=
			FlywheelModelBudget.MAX_POSITION_COMPONENTS_PER_MODEL);
		assertTrue(result.uvComponentCount() <=
			FlywheelModelBudget.MAX_UV_COMPONENTS_PER_MODEL);
		assertTrue(result.indexCount() <= FlywheelModelBudget.MAX_INDICES_PER_MODEL);
		assertTrue(result.triangleCount() <= FlywheelModelBudget.MAX_TRIANGLES_PER_MODEL);
	}

	@Test
	void targetAndFrameTriangleBudgetsRejectOverBudgetPlans() {
		assertTrue(FlywheelModelBudget.trianglesWithinBudget(
			FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET,
			FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET));
		assertFalse(FlywheelModelBudget.trianglesWithinBudget(
			(long) FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET + 1,
			FlywheelModelBudget.MAX_TRIANGLES_PER_TARGET));
		assertTrue(FlywheelModelBudget.trianglesWithinBudget(
			FlywheelModelBudget.MAX_TRIANGLES_PER_FRAME,
			FlywheelModelBudget.MAX_TRIANGLES_PER_FRAME));
	}
}
