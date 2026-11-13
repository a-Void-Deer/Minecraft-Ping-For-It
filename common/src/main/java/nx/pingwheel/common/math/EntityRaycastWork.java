package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** One query's work capability. Exhaustion is sticky even when a provider catches the exception. */
@NotNullByDefault
public final class EntityRaycastWork {
	private final @Nullable CandidateWorkBudget budget;
	private boolean complete = true;
	private EntityRaycastWork(@Nullable CandidateWorkBudget budget) { this.budget = budget; }
	public static EntityRaycastWork unlimited() { return new EntityRaycastWork(null); }
	public static EntityRaycastWork bounded(CandidateWorkBudget budget) {
		return new EntityRaycastWork(Objects.requireNonNull(budget));
	}
	public void readLocalBlock() { charge(budget == null ? () -> true : budget::visitBlock); }
	public void callProvider() { charge(budget == null ? () -> true : budget::callProvider); }
	public void visitShapeBox() { charge(budget == null ? () -> true : budget::visitShapeBox); }
	public boolean complete() { return complete; }
	boolean isUnlimited() { return budget == null; }
	private void charge(BooleanSupplier admission) {
		if (!complete || !admission.getAsBoolean()) {
			complete = false;
			throw new Exhausted();
		}
	}
	@NotNullByDefault
	public static final class Exhausted extends RuntimeException {
		public Exhausted() { super("entity raycast work exhausted", null, false, false); }
	}
}
