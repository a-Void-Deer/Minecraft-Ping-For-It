package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure indexed-triangle edge validation and unordered-edge deduplication.
 *
 * <p>It deliberately keeps triangle diagonals. The optional adapter supplies
 * the positions read from Flywheel's native vertex list and uses the returned
 * indices to transform each vertex once before emitting lines.</p>
 */
public final class WireframeEdgeExtractor {
	public static final int MAX_VERTICES = 131_072;
	public static final int MAX_INDICES = 393_216;

	private WireframeEdgeExtractor() {}

	public record Edge(int first, int second) {}

	public record Extraction(boolean valid, List<Edge> edges) {
		public Extraction {
			edges = List.copyOf(edges);
		}

		public static Extraction invalid() {
			return new Extraction(false, List.of());
		}
	}

	/**
	 * Validates one indexed triangle mesh and returns its deduplicated edges.
	 * Invalid input is rejected as a whole; no prefix is returned.
	 */
	public static Extraction extract(float[] positions, int[] indices, int maxEdges) {
		if (positions == null || indices == null || maxEdges <= 0
			|| positions.length == 0 || positions.length % 3 != 0
			|| indices.length == 0 || indices.length % 3 != 0
			|| positions.length / 3 > MAX_VERTICES || indices.length > MAX_INDICES) {
			return Extraction.invalid();
		}

		for (float position : positions) {
			if (!Float.isFinite(position)) {
				return Extraction.invalid();
			}
		}

		int vertexCount = positions.length / 3;
		Set<Long> unique = new HashSet<>();
		List<Edge> edges = new ArrayList<>();

		for (int index = 0; index < indices.length; index += 3) {
			int first = indices[index];
			int second = indices[index + 1];
			int third = indices[index + 2];
			if (first < 0 || second < 0 || third < 0
				|| first >= vertexCount || second >= vertexCount || third >= vertexCount
				|| first == second || second == third || first == third) {
				return Extraction.invalid();
			}

			if (!addEdge(unique, edges, first, second, maxEdges)
				|| !addEdge(unique, edges, second, third, maxEdges)
				|| !addEdge(unique, edges, third, first, maxEdges)) {
				return Extraction.invalid();
			}
		}

		return new Extraction(!edges.isEmpty(), edges);
	}

	private static boolean addEdge(Set<Long> unique, List<Edge> edges,
		int first, int second, int maxEdges) {
		int low = Math.min(first, second);
		int high = Math.max(first, second);
		long key = ((long) low << 32) | (high & 0xFFFF_FFFFL);
		if (!unique.add(key)) {
			return true;
		}
		if (edges.size() >= maxEdges) {
			return false;
		}
		edges.add(new Edge(low, high));
		return true;
	}
}
