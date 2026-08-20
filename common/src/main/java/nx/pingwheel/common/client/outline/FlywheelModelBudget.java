package nx.pingwheel.common.client.outline;

import java.util.List;

/**
 * Pure aggregate budget checks shared by the optional Flywheel adapter and
 * headless tests.  The adapter can inspect every mesh's counts before it
 * allocates a native or Java mesh array; edge counts are checked separately
 * after indexed extraction has produced the exact deduplicated edge list.
 */
public final class FlywheelModelBudget {
	/** Maximum number of configured meshes retained by one cached model. */
	public static final int MAX_MESHES_PER_MODEL = 1_024;
	/** Existing defensive limit for one mesh's vertex positions. */
	public static final int MAX_VERTICES_PER_MESH = WireframeEdgeExtractor.MAX_VERTICES;
	/** Existing defensive limit for one mesh's indices. */
	public static final int MAX_INDICES_PER_MESH = WireframeEdgeExtractor.MAX_INDICES;
	/** Maximum total vertices retained across all meshes in one cached model. */
	public static final int MAX_VERTICES_PER_MODEL = 262_144;
	/** Maximum total float position components retained by one cached model. */
	public static final int MAX_POSITION_COMPONENTS_PER_MODEL = 786_432;
	/** Maximum total indices read while extracting one cached model. */
	public static final int MAX_INDICES_PER_MODEL = 786_432;
	/** Maximum exact deduplicated edges retained by one cached model. */
	public static final int MAX_EDGES_PER_MODEL = 65_536;

	private FlywheelModelBudget() {}

	/** Counts that can be read from a Flywheel mesh without allocating arrays. */
	public record MeshCounts(int vertexCount, int indexCount) {}

	/** Result of the allocation-free aggregate model preflight. */
	public record Preflight(
		boolean valid,
		int meshCount,
		int vertexCount,
		int positionComponentCount,
		int indexCount
	) {}

	/**
	 * Validates all mesh counts and their checked aggregate totals.  The method
	 * rejects the whole model rather than returning a usable prefix.
	 */
	public static Preflight preflight(List<MeshCounts> meshes) {
		if (meshes == null || meshes.isEmpty()) {
			return new Preflight(true, 0, 0, 0, 0);
		}
		if (meshes.size() > MAX_MESHES_PER_MODEL) {
			return invalid();
		}

		long totalVertices = 0;
		long totalPositionComponents = 0;
		long totalIndices = 0;

		for (MeshCounts mesh : meshes) {
			if (mesh == null || mesh.vertexCount() <= 0
				|| mesh.vertexCount() > MAX_VERTICES_PER_MESH
				|| mesh.indexCount() <= 0
				|| mesh.indexCount() > MAX_INDICES_PER_MESH
				|| mesh.indexCount() % 3 != 0) {
				return invalid();
			}

			try {
				totalVertices = Math.addExact(totalVertices, mesh.vertexCount());
				totalPositionComponents = Math.addExact(
					totalPositionComponents,
					Math.multiplyExact((long) mesh.vertexCount(), 3L));
				totalIndices = Math.addExact(totalIndices, mesh.indexCount());
			} catch (ArithmeticException overflow) {
				return invalid();
			}

			if (totalVertices > MAX_VERTICES_PER_MODEL
				|| totalPositionComponents > MAX_POSITION_COMPONENTS_PER_MODEL
				|| totalIndices > MAX_INDICES_PER_MODEL) {
				return invalid();
			}
		}

		return new Preflight(
			true,
			meshes.size(),
			Math.toIntExact(totalVertices),
			Math.toIntExact(totalPositionComponents),
			Math.toIntExact(totalIndices));
	}

	/** Checks an exact post-extraction edge total without accepting negatives. */
	public static boolean edgesWithinModelBudget(long edgeCount) {
		return edgeCount >= 0 && edgeCount <= MAX_EDGES_PER_MODEL;
	}

	private static Preflight invalid() {
		return new Preflight(false, 0, 0, 0, 0);
	}
}
