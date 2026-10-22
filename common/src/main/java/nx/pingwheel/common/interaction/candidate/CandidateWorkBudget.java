package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;

/** Mutable scan-local counter. Never retained by a capture or supplied to release. */
public final class CandidateWorkBudget {
	private final CandidateWorkLimits limits;
	private int blockCells;
	private int entityVisits;
	private int providerCalls;
	private int shapeBoxes;
	public CandidateWorkBudget(CandidateWorkLimits limits) { this.limits = Objects.requireNonNull(limits, "limits"); }
	public boolean visitBlock() {
		if (blockCells >= limits.blockCells()) return false;
		blockCells++;
		return true;
	}
	public boolean visitEntity() {
		if (entityVisits >= limits.entityVisits()) return false;
		entityVisits++;
		return true;
	}
	public boolean callProvider() {
		if (providerCalls >= limits.providerCalls()) return false;
		providerCalls++;
		return true;
	}
	public boolean visitShapeBox() {
		if (shapeBoxes >= limits.shapeBoxes()) return false;
		shapeBoxes++;
		return true;
	}
}
