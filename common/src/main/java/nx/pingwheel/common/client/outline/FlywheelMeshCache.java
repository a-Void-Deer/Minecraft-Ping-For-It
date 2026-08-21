package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, loader-neutral captured mesh data used by the optional
 * Create/Flywheel mask adapter.
 *
 * <p>The adapter reads Flywheel's native vertex/index streams once and stores
 * the resulting positions, UVs, triangle indices, and material texture. This
 * class performs the second, allocation-free-at-the-call-site validation pass
 * over that captured data and returns defensive immutable mesh values. It has
 * no dependency on Flywheel or Create, so malformed-data and budget coverage
 * can run in the common test source set.</p>
 */
public final class FlywheelMeshCache {

	private FlywheelMeshCache() {}

	/** Raw arrays read from one Flywheel configured mesh. */
	public record Input<T>(float[] positions, float[] uvs, int[] indices, T texture) {}

	/** One validated immutable mesh retained by a model cache. */
	public record Mesh<T>(float[] positions, float[] uvs, int[] indices, T texture) {
		public Mesh {
			positions = positions.clone();
			uvs = uvs.clone();
			indices = indices.clone();
			Objects.requireNonNull(texture, "texture");
		}

		@Override
		public float[] positions() {
			return positions.clone();
		}

		@Override
		public float[] uvs() {
			return uvs.clone();
		}

		@Override
		public int[] indices() {
			return indices.clone();
		}

		public int vertexCount() {
			return positions.length / 3;
		}

		public int indexCount() {
			return indices.length;
		}
	}

	/** The validated immutable mesh list and its indexed-triangle total. */
	public record Model<T>(List<Mesh<T>> meshes, int triangleCount) {
		public Model {
			meshes = List.copyOf(meshes);
			if (triangleCount < 0) {
				throw new IllegalArgumentException("triangleCount must not be negative");
			}
		}

		public boolean isEmpty() {
			return meshes.isEmpty() || triangleCount == 0;
		}
	}

	/**
	 * Validates and captures a complete model. The whole model is rejected when
	 * any mesh is malformed or an aggregate budget is exceeded; no usable prefix
	 * is returned.
	 */
	public static <T> Model<T> capture(List<Input<T>> inputs) {
		if (inputs == null || inputs.isEmpty()) {
			return new Model<>(List.of(), 0);
		}

		List<FlywheelModelBudget.MeshCounts> counts = new ArrayList<>(inputs.size());
		for (int index = 0; index < inputs.size(); index++) {
			Input<T> input = requireInput(inputs.get(index), index);
			int vertexCount = checkedVertexCount(input.positions(), index);
			int indexCount = checkedIndexCount(input.indices(), index);
			checkedUvCount(input.uvs(), vertexCount, index);
			counts.add(new FlywheelModelBudget.MeshCounts(vertexCount, indexCount));
		}

		FlywheelModelBudget.Preflight preflight = FlywheelModelBudget.preflight(counts);
		if (!preflight.valid()) {
			throw new IllegalArgumentException("captured mesh model exceeds its budget");
		}

		List<Mesh<T>> meshes = new ArrayList<>(inputs.size());
		for (int index = 0; index < inputs.size(); index++) {
			Input<T> input = inputs.get(index);
			validateValues(input, index);
			meshes.add(new Mesh<>(input.positions(), input.uvs(), input.indices(), input.texture()));
		}

		return new Model<>(meshes, preflight.triangleCount());
	}

	private static <T> Input<T> requireInput(Input<T> input, int index) {
		if (input == null) {
			throw new IllegalArgumentException("mesh " + index + " is null");
		}
		if (input.texture() == null) {
			throw new IllegalArgumentException("mesh " + index + " has no material texture");
		}
		return input;
	}

	private static int checkedVertexCount(float[] positions, int index) {
		if (positions == null || positions.length == 0 || positions.length % 3 != 0) {
			throw new IllegalArgumentException("mesh " + index + " has malformed positions");
		}
		return positions.length / 3;
	}

	private static int checkedIndexCount(int[] indices, int index) {
		if (indices == null || indices.length == 0 || indices.length % 3 != 0) {
			throw new IllegalArgumentException("mesh " + index + " has malformed triangle indices");
		}
		return indices.length;
	}

	private static void checkedUvCount(float[] uvs, int vertexCount, int index) {
		long expected;
		try {
			expected = Math.multiplyExact((long) vertexCount, 2L);
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("mesh " + index + " UV count overflow", overflow);
		}
		if (uvs == null || uvs.length != expected) {
			throw new IllegalArgumentException("mesh " + index + " has malformed UVs");
		}
	}

	private static <T> void validateValues(Input<T> input, int meshIndex) {
		float[] positions = input.positions();
		float[] uvs = input.uvs();
		int[] indices = input.indices();
		int vertexCount = positions.length / 3;

		for (int vertex = 0; vertex < vertexCount; vertex++) {
			int positionOffset = vertex * 3;
			if (!Float.isFinite(positions[positionOffset])
				|| !Float.isFinite(positions[positionOffset + 1])
				|| !Float.isFinite(positions[positionOffset + 2])) {
				throw new IllegalArgumentException(
					"mesh " + meshIndex + " has non-finite position at vertex " + vertex);
			}

			int uvOffset = vertex * 2;
			if (!Float.isFinite(uvs[uvOffset]) || !Float.isFinite(uvs[uvOffset + 1])) {
				throw new IllegalArgumentException(
					"mesh " + meshIndex + " has non-finite UV at vertex " + vertex);
			}
		}

		for (int offset = 0; offset < indices.length; offset += 3) {
			int first = indices[offset];
			int second = indices[offset + 1];
			int third = indices[offset + 2];
			if (first < 0 || second < 0 || third < 0
				|| first >= vertexCount || second >= vertexCount || third >= vertexCount) {
				throw new IllegalArgumentException(
					"mesh " + meshIndex + " has an out-of-range triangle index at " + offset);
			}
			if (first == second || first == third || second == third) {
				throw new IllegalArgumentException(
					"mesh " + meshIndex + " has a degenerate triangle at " + offset);
			}
		}
	}
}
