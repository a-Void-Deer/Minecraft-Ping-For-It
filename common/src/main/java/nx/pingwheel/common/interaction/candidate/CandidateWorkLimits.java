package nx.pingwheel.common.interaction.candidate;

/**
 * Engineering guardrails, independent of settings and ordinary single-hit work.
 * blockCells charges discovered DDA cells and guarded
 * world/neighbor reads. shapeBoxes independently bounds native decomposition work.
 */
public record CandidateWorkLimits(int blockCells, int entityVisits, int providerCalls, int shapeBoxes) {
	private static final int DEFAULT_SHAPE_BOXES = 131_072;
	/** Compatibility with the original independent work counters. */
	public CandidateWorkLimits(int blockCells, int entityVisits, int providerCalls) {
		this(blockCells, entityVisits, providerCalls, DEFAULT_SHAPE_BOXES);
	}
	public CandidateWorkLimits {
		if (blockCells < 0 || entityVisits < 0 || providerCalls < 0 || shapeBoxes < 0
			|| blockCells > 1_000_000 || entityVisits > 100_000 || providerCalls > 100_000 || shapeBoxes > 1_000_000) {
			throw new IllegalArgumentException("invalid candidate work limits");
		}
	}
	public static CandidateWorkLimits defaults() { return new CandidateWorkLimits(16_384, 2_048, 256); }
}
