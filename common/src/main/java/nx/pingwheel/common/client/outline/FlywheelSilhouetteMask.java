package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable indexed-triangle mask plan and its tiny, headless emission core.
 *
 * <p>The optional adapter supplies texture batches containing already
 * transformed camera-relative vertices. Emission deliberately expands each
 * indexed triangle to the vanilla outline post-chain's quad input as
 * {@code (v0,v1,v2,v2)}. The generic texture type keeps this class free of
 * optional Flywheel classes while the production caller uses
 * {@code ResourceLocation}.</p>
 */
public final class FlywheelSilhouetteMask {
	private static final double TRIANGLE_CROSS_SQUARED_EPSILON = 1.0E-12D;
	private static final int VERTICES_PER_TRIANGLE = 4;

	private FlywheelSilhouetteMask() {}

	public record Vertex(float x, float y, float z, float u, float v) {
		public Vertex {
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
				|| !Float.isFinite(u) || !Float.isFinite(v)) {
				throw new IllegalArgumentException("mask vertex values must be finite");
			}
		}
	}

	public record Triangle(Vertex first, Vertex second, Vertex third) {
		public Triangle {
			Objects.requireNonNull(first, "first");
			Objects.requireNonNull(second, "second");
			Objects.requireNonNull(third, "third");
		}

		/**
		 * Returns whether this triangle has enough transformed area to produce a
		 * visible silhouette. The comparison is scale-relative and uses the
		 * squared cross product, so it does not take a square root or become
		 * unstable for large camera-relative coordinates.
		 */
		public boolean hasVisibleArea() {
			double abX = (double) second.x() - first.x();
			double abY = (double) second.y() - first.y();
			double abZ = (double) second.z() - first.z();
			double acX = (double) third.x() - first.x();
			double acY = (double) third.y() - first.y();
			double acZ = (double) third.z() - first.z();
			double bcX = (double) third.x() - second.x();
			double bcY = (double) third.y() - second.y();
			double bcZ = (double) third.z() - second.z();

			double largestEdgeSquared = Math.max(
				squaredLength(abX, abY, abZ),
				Math.max(squaredLength(acX, acY, acZ), squaredLength(bcX, bcY, bcZ)));
			if (!Double.isFinite(largestEdgeSquared) || largestEdgeSquared == 0.0D) {
				return false;
			}

			double crossX = Math.fma(abY, acZ, -abZ * acY);
			double crossY = Math.fma(abZ, acX, -abX * acZ);
			double crossZ = Math.fma(abX, acY, -abY * acX);
			double crossSquared = squaredLength(crossX, crossY, crossZ);
			if (!Double.isFinite(crossSquared)) {
				return false;
			}

			return crossSquared > TRIANGLE_CROSS_SQUARED_EPSILON
				* largestEdgeSquared * largestEdgeSquared;
		}

		private static double squaredLength(double x, double y, double z) {
			return Math.fma(x, x, Math.fma(y, y, z * z));
		}
	}

	public record TextureBatch<T>(T texture, List<Triangle> triangles) {
		public TextureBatch {
			Objects.requireNonNull(texture, "texture");
			triangles = List.copyOf(Objects.requireNonNull(triangles, "triangles"));
		}
	}

	public record RenderPlan<T>(List<TextureBatch<T>> batches) {
		public RenderPlan {
			batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
		}

		public int triangleCount() {
			int count = 0;
			for (TextureBatch<T> batch : batches) {
				count = Math.addExact(count, batch.triangles().size());
			}
			return count;
		}

		public boolean isEmpty() {
			return triangleCount() == 0;
		}
	}

	/** Returns a plan containing only finite, non-degenerate visible triangles. */
	public static <T> RenderPlan<T> filterVisibleTriangles(RenderPlan<T> plan) {
		Objects.requireNonNull(plan, "plan");
		List<TextureBatch<T>> filtered = new java.util.ArrayList<>(plan.batches().size());
		for (TextureBatch<T> batch : plan.batches()) {
			List<Triangle> triangles = new java.util.ArrayList<>(batch.triangles().size());
			for (Triangle triangle : batch.triangles()) {
				if (triangle.hasVisibleArea()) {
					triangles.add(triangle);
				}
			}
			filtered.add(new TextureBatch<>(batch.texture(), triangles));
		}
		return new RenderPlan<>(filtered);
	}

	/** One staged vertex. It is not submitted to a game buffer until commit. */
	public record EncodedVertex(Vertex vertex, int argbColor) {
		public EncodedVertex {
			Objects.requireNonNull(vertex, "vertex");
		}
	}

	/** One attempt-local, fixed-texture staging batch. */
	public record EncodedBatch<T>(T texture, List<EncodedVertex> vertices) {
		public EncodedBatch {
			Objects.requireNonNull(texture, "texture");
			vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
		}
	}

	/**
	 * Complete immutable mask data. The production adapter commits this only
	 * after every planned vertex has been encoded, so a pre-commit failure never
	 * touches the shared vanilla outline buffer. The later multi-texture commit
	 * cannot roll back vertices already accepted by that shared source; its
	 * recoverable exception reports the written count so the caller can suppress
	 * the VoxelShape overlay for that frame. Fatal JVM/resource errors still
	 * propagate.
	 */
	public record EncodedPlan<T>(List<EncodedBatch<T>> batches, int triangleCount) {
		public EncodedPlan {
			batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
			if (triangleCount < 0) {
				throw new IllegalArgumentException("triangleCount must not be negative");
			}
		}

		public boolean isEmpty() {
			return triangleCount == 0;
		}
	}

	/** Receives one complete vertex, including its original/current-frame UV. */
	@FunctionalInterface
	public interface VertexEmitter {
		void emit(Vertex vertex, int argbColor);

		/**
		 * Emits with a progress callback. The default preserves the simple
		 * two-argument emitter contract and marks the vertex after it returns;
		 * production sinks can mark immediately after their underlying
		 * {@code addVertex} accepts the vertex, before a later setter fails.
		 */
		default void emitTracked(
			Vertex vertex, int argbColor, VertexWriteObserver observer
		) {
			Objects.requireNonNull(observer, "observer");
			emit(vertex, argbColor);
			observer.vertexWritten();
		}
	}

	@FunctionalInterface
	public interface VertexWriteObserver {
		void vertexWritten();
	}

	/** Opens the output sink for one immutable texture batch. */
	@FunctionalInterface
	public interface BatchEmitter<T> {
		VertexEmitter open(T texture);
	}

	/**
	 * Optional validation seam for an attempt-local builder. Implementations
	 * must stage/validate only; committing to a shared render buffer belongs to
	 * {@link #commit(EncodedPlan, Function)}.
	 */
	@FunctionalInterface
	public interface AttemptBuilder<T> {
		void append(T texture, Vertex vertex, int argbColor);
	}

	/**
	 * Encodes every triangle into fixed per-texture attempt-local batches. No
	 * {@link BatchEmitter} is opened here. A throwing builder therefore aborts
	 * before the commit phase and cannot leave a partial shared-buffer mask.
	 */
	public static <T> EncodedPlan<T> encode(RenderPlan<T> plan, int argbColor) {
		return encode(plan, argbColor, (texture, vertex, color) -> { });
	}

	/** Testable overload for a throwing pre-commit builder/validator seam. */
	public static <T> EncodedPlan<T> encode(
		RenderPlan<T> plan,
		int argbColor,
		AttemptBuilder<T> attemptBuilder
	) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(attemptBuilder, "attemptBuilder");

		List<EncodedBatch<T>> encodedBatches = new java.util.ArrayList<>(plan.batches().size());
		int triangles = 0;
		for (TextureBatch<T> batch : plan.batches()) {
			int vertexCapacity = Math.multiplyExact(batch.triangles().size(), VERTICES_PER_TRIANGLE);
			List<EncodedVertex> vertices = new java.util.ArrayList<>(vertexCapacity);
			for (Triangle triangle : batch.triangles()) {
				triangles = Math.addExact(triangles, 1);
				appendEncoded(batch.texture(), triangle.first(), argbColor, attemptBuilder, vertices);
				appendEncoded(batch.texture(), triangle.second(), argbColor, attemptBuilder, vertices);
				appendEncoded(batch.texture(), triangle.third(), argbColor, attemptBuilder, vertices);
				// The outline post-chain consumes quads; v2 is the required
				// winding-preserving fourth vertex for the source triangle.
				appendEncoded(batch.texture(), triangle.third(), argbColor, attemptBuilder, vertices);
			}
			encodedBatches.add(new EncodedBatch<>(batch.texture(), vertices));
		}

		return new EncodedPlan<>(encodedBatches, triangles);
	}

	private static <T> void appendEncoded(
		T texture,
		Vertex vertex,
		int argbColor,
		AttemptBuilder<T> attemptBuilder,
		List<EncodedVertex> destination
	) {
		attemptBuilder.append(texture, vertex, argbColor);
		destination.add(new EncodedVertex(vertex, argbColor));
	}

	/**
	 * Recoverable failure during the shared-buffer commit phase. The count is
	 * the number of vertices whose sink write was marked complete before the
	 * failure. A caller must not pretend that a shared buffer can roll back
	 * those writes.
	 */
	public static final class CommitFailure extends RuntimeException {
		private final int verticesWritten;

		private CommitFailure(int verticesWritten, Throwable cause) {
			super("mask commit failed after " + verticesWritten + " vertices", cause);
			this.verticesWritten = verticesWritten;
		}

		public int verticesWritten() {
			return verticesWritten;
		}
	}

	/**
	 * Commits a fully encoded plan to its caller-owned output sinks. Recoverable
	 * commit failures are wrapped with the number of vertices already emitted;
	 * fatal JVM/resource errors are deliberately not caught.
	 */
	public static <T> int commit(EncodedPlan<T> encodedPlan, Function<T, VertexEmitter> batchEmitter) {
		Objects.requireNonNull(encodedPlan, "encodedPlan");
		Objects.requireNonNull(batchEmitter, "batchEmitter");

		// Resolve every fixed RenderType sink before writing the first vertex.
		// A recoverable buffer lookup failure therefore reports zero written; the
		// subsequent vertex/GL commit window can leave an irreversible partial mask.
		List<VertexEmitter> emitters = new java.util.ArrayList<>(encodedPlan.batches().size());
		for (EncodedBatch<T> batch : encodedPlan.batches()) {
			try {
				emitters.add(Objects.requireNonNull(
					batchEmitter.apply(batch.texture()), "batch emitter"));
			} catch (Exception | LinkageError | AssertionError failure) {
				throw new CommitFailure(0, failure);
			}
		}

		int[] verticesWritten = {0};
		for (int batchIndex = 0; batchIndex < encodedPlan.batches().size(); batchIndex++) {
			EncodedBatch<T> batch = encodedPlan.batches().get(batchIndex);
			VertexEmitter emitter = emitters.get(batchIndex);
			for (EncodedVertex vertex : batch.vertices()) {
				try {
					emitter.emitTracked(
					vertex.vertex(), vertex.argbColor(), () -> verticesWritten[0]++);
				} catch (Exception | LinkageError | AssertionError failure) {
					throw new CommitFailure(verticesWritten[0], failure);
				}
			}
		}
		return encodedPlan.triangleCount();
	}

	/**
	 * Emits every plan triangle in deterministic batch/triangle order. The
	 * returned count is the number of source triangles, and every source
	 * triangle results in exactly four emitted vertices.
	 */
	public static <T> int emit(
		RenderPlan<T> plan,
		int argbColor,
		Function<T, VertexEmitter> batchEmitter
	) {
		return commit(encode(plan, argbColor), batchEmitter);
	}
}
