package nx.pingwheel.common.math;

import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class EntityRaycastWorkTest {
	@Test void eachCounterIsChargedAndAnyExhaustionIsStickyAcrossCounters() {
		CandidateWorkBudget budget = new CandidateWorkBudget(new CandidateWorkLimits(1, 0, 1, 1));
		EntityRaycastWork work = EntityRaycastWork.bounded(budget);
		work.readLocalBlock(); work.callProvider(); work.visitShapeBox();
		assertTrue(work.complete());
		assertThrows(EntityRaycastWork.Exhausted.class, work::readLocalBlock);
		assertFalse(work.complete());
		assertThrows(EntityRaycastWork.Exhausted.class, work::callProvider);
		assertFalse(budget.visitShapeBox()); assertFalse(budget.callProvider());
	}
	@Test void independentOrdinaryWorkCannotInheritAnotherQueriesExhaustion() {
		EntityRaycastWork first = EntityRaycastWork.unlimited(), second = EntityRaycastWork.unlimited();
		assertNotSame(first, second);
		EntityRaycastWork exhausted = EntityRaycastWork.bounded(new CandidateWorkBudget(new CandidateWorkLimits(0, 0, 0, 0)));
		assertThrows(EntityRaycastWork.Exhausted.class, exhausted::callProvider);
		for (int index = 0; index < 1000; index++) { first.callProvider(); second.readLocalBlock(); second.visitShapeBox(); }
		assertTrue(first.complete()); assertTrue(second.complete());
	}
}
