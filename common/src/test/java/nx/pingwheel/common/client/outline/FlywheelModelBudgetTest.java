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
	void validAggregateCountsStayWithinCheckedPositionAndIndexBudgets() {
		FlywheelModelBudget.Preflight result = FlywheelModelBudget.preflight(List.of(
			new FlywheelModelBudget.MeshCounts(2, 3),
			new FlywheelModelBudget.MeshCounts(4, 6)));

		assertTrue(result.valid());
		assertTrue(result.positionComponentCount() <=
			FlywheelModelBudget.MAX_POSITION_COMPONENTS_PER_MODEL);
		assertTrue(result.indexCount() <= FlywheelModelBudget.MAX_INDICES_PER_MODEL);
	}

	@Test
	void exactCachedEdgeBudgetRejectsAnOverBudgetModel() {
		assertTrue(FlywheelModelBudget.edgesWithinModelBudget(
			FlywheelModelBudget.MAX_EDGES_PER_MODEL));
		assertFalse(FlywheelModelBudget.edgesWithinModelBudget(
			(long) FlywheelModelBudget.MAX_EDGES_PER_MODEL + 1));
	}
}
