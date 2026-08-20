package nx.pingwheel.common.client.outline;

import java.util.List;

/**
 * Pure aggregate budget checks shared by the optional Flywheel adapter and
 * headless tests. The adapter inspects every mesh's counts before it allocates
 * a native or Java mesh array, then applies target/frame mask budgets before
 * touching the vanilla outline buffer.
 */
public final class FlywheelModelBudget {
	/** Maximum number of configured meshes retained by one cached model. */
	public static final int MAX_MESHES_PER_MODEL = 1_024;
	/** Defensive limit for one mesh's vertices. */
	public static final int MAX_VERTICES_PER_MESH = 262_144;
	/** Defensive limit for one mesh's indices. */
	public static final int MAX_INDICES_PER_MESH = 786_432;
	/** Maximum total vertices retained across all meshes in one cached model. */
	public static final int MAX_VERTICES_PER_MODEL = 262_144;
	/** Maximum total float position components retained by one cached model. */
	public static final int MAX_POSITION_COMPONENTS_PER_MODEL = 786_432;
	/** Maximum total float UV components retained by one cached model. */
	public static final int MAX_UV_COMPONENTS_PER_MODEL = 524_288;
	/** Maximum total indices read while extracting one cached model. */
	public static final int MAX_INDICES_PER_MODEL = 786_432;
	/** Maximum indexed triangles retained by one cached model. */
	public static final int MAX_TRIANGLES_PER_MODEL = 65_536;
	/** Maximum triangles emitted for one target in one frame. */
	public static final int MAX_TRIANGLES_PER_TARGET = 8_192;
	/** Maximum triangles emitted by the optional mask across one frame. */
	public static final int MAX_TRIANGLES_PER_FRAME = 65_536;
	/** Four outline-mask vertices are emitted for each source triangle. */
	public static final int MASK_VERTICES_PER_TRIANGLE = 4;

	private FlywheelModelBudget() {}

	/** Counts that can be read from a Flywheel mesh without allocating arrays. */
	public record MeshCounts(int vertexCount, int indexCount) {}

	/** Result of the allocation-free aggregate model preflight. */
	public record Preflight(
		boolean valid,
		int meshCount,
		int vertexCount,
		int positionComponentCount,
		int uvComponentCount,
		int indexCount,
		int triangleCount
	) {}

	/**
	 * Validates all mesh counts and their checked aggregate totals.  The method
	 * rejects the whole model rather than returning a usable prefix.
	 */
	public static Preflight preflight(List<MeshCounts> meshes) {
		if (meshes == null || meshes.isEmpty()) {
			return new Preflight(true, 0, 0, 0, 0, 0, 0);
		}
		if (meshes.size() > MAX_MESHES_PER_MODEL) {
			return invalid();
		}

		long totalVertices = 0;
		long totalPositionComponents = 0;
		long totalUvComponents = 0;
		long totalIndices = 0;
		long totalTriangles = 0;

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
				totalUvComponents = Math.addExact(
					totalUvComponents,
					Math.multiplyExact((long) mesh.vertexCount(), 2L));
				totalIndices = Math.addExact(totalIndices, mesh.indexCount());
				totalTriangles = Math.addExact(totalTriangles, mesh.indexCount() / 3L);
			} catch (ArithmeticException overflow) {
				return invalid();
			}

			if (totalVertices > MAX_VERTICES_PER_MODEL
				|| totalPositionComponents > MAX_POSITION_COMPONENTS_PER_MODEL
				|| totalUvComponents > MAX_UV_COMPONENTS_PER_MODEL
				|| totalIndices > MAX_INDICES_PER_MODEL
				|| totalTriangles > MAX_TRIANGLES_PER_MODEL) {
				return invalid();
			}
		}

		return new Preflight(
			true,
			meshes.size(),
			Math.toIntExact(totalVertices),
			Math.toIntExact(totalPositionComponents),
			Math.toIntExact(totalUvComponents),
			Math.toIntExact(totalIndices),
			Math.toIntExact(totalTriangles));
	}

	/** Checks a complete target/frame triangle reservation without accepting negatives. */
	public static boolean trianglesWithinBudget(long triangleCount, long budget) {
		return triangleCount >= 0 && budget >= 0 && triangleCount <= budget;
	}

	private static Preflight invalid() {
		return new Preflight(false, 0, 0, 0, 0, 0, 0);
	}
}
