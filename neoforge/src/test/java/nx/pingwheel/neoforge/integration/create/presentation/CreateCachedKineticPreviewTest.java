package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Create-free seam tests for the locally received cached kinetic network totals. */
class CreateCachedKineticPreviewTest {
	private static final String STRESS = CreatePresentationAdapter.STRESS;
	private static final String CAPACITY = CreatePresentationAdapter.CAPACITY;
	private static final String AVAILABLE = CreatePresentationAdapter.AVAILABLE_CAPACITY;
	private static final long TICK = 42L;

	@Test
	void absentReceiptNeverReadsNetworkOrRawTotals() {
		FakeSource source = new FakeSource();
		source.received = false;
		source.stress = 3f;
		source.capacity = 8f;

		Map<String, PreviewFieldAccess.Outcome> outcomes = observe(source, STRESS, CAPACITY, AVAILABLE);

		assertEquals(Set.of(STRESS, CAPACITY, AVAILABLE), outcomes.keySet());
		assertUnavailable(outcomes, STRESS);
		assertUnavailable(outcomes, CAPACITY);
		assertUnavailable(outcomes, AVAILABLE);
		assertEquals(0, source.networkReads, "a missing receipt cannot read the network getter");
		assertEquals(0, source.accessorReads);
		assertEquals(0, source.stressReads);
		assertEquals(0, source.capacityReads);
	}

	@Test
	void absentNetworkOrAccessorShapeNeverReadsRawTotals() {
		FakeSource noNetwork = new FakeSource();
		noNetwork.network = false;
		noNetwork.stress = 3f;
		noNetwork.capacity = 8f;
		Map<String, PreviewFieldAccess.Outcome> networkOutcomes = observe(noNetwork, STRESS, CAPACITY, AVAILABLE);
		assertUnavailable(networkOutcomes, STRESS);
		assertUnavailable(networkOutcomes, CAPACITY);
		assertUnavailable(networkOutcomes, AVAILABLE);
		assertEquals(1, noNetwork.networkReads);
		assertEquals(0, noNetwork.accessorReads);
		assertEquals(0, noNetwork.stressReads);
		assertEquals(0, noNetwork.capacityReads);

		FakeSource noAccessor = new FakeSource();
		noAccessor.accessor = false;
		noAccessor.stress = 3f;
		noAccessor.capacity = 8f;
		Map<String, PreviewFieldAccess.Outcome> accessorOutcomes = observe(noAccessor, STRESS, CAPACITY, AVAILABLE);
		assertUnavailable(accessorOutcomes, STRESS);
		assertUnavailable(accessorOutcomes, CAPACITY);
		assertUnavailable(accessorOutcomes, AVAILABLE);
		assertEquals(1, noAccessor.networkReads);
		assertEquals(1, noAccessor.accessorReads);
		assertEquals(0, noAccessor.stressReads);
		assertEquals(0, noAccessor.capacityReads);
	}

	@Test
	void genuinelyReceivedZeroStaysObservedInsteadOfMissing() {
		FakeSource source = new FakeSource();
		source.stress = 0f;
		source.capacity = 0f;

		Map<String, PreviewFieldAccess.Outcome> outcomes = observe(source, STRESS, CAPACITY, AVAILABLE);

		assertObserved(outcomes, STRESS, 0);
		assertObserved(outcomes, CAPACITY, 0);
		assertObserved(outcomes, AVAILABLE, 0);
		assertEquals(1, source.stressReads);
		assertEquals(1, source.capacityReads);
	}

	@Test
	void rawAccessorsAreReadOnceAndAvailableReusesThePair() {
		FakeSource source = new FakeSource();
		source.stress = 3f;
		source.capacity = 8f;

		Map<String, PreviewFieldAccess.Outcome> outcomes = observe(source, STRESS, CAPACITY, AVAILABLE);

		assertObserved(outcomes, STRESS, 3);
		assertObserved(outcomes, CAPACITY, 8);
		assertObserved(outcomes, AVAILABLE, 5);
		assertEquals(1, source.networkReads);
		assertEquals(1, source.accessorReads);
		assertEquals(1, source.stressReads, "the stress accessor is read at most once per observation");
		assertEquals(1, source.capacityReads, "the capacity accessor is read at most once per observation");
	}

	@Test
	void undemandedFamilyFieldsAreNeitherReadNorPublished() {
		FakeSource availableOnly = new FakeSource();
		availableOnly.stress = 3f;
		availableOnly.capacity = 8f;
		Map<String, PreviewFieldAccess.Outcome> available = observe(availableOnly, AVAILABLE);
		assertEquals(Set.of(AVAILABLE), available.keySet(), "the raw pair never leaks into the projection");
		assertObserved(available, AVAILABLE, 5);
		assertEquals(1, availableOnly.stressReads);
		assertEquals(1, availableOnly.capacityReads);

		FakeSource stressOnly = new FakeSource();
		stressOnly.stress = 3f;
		stressOnly.capacity = 8f;
		Map<String, PreviewFieldAccess.Outcome> stress = observe(stressOnly, STRESS);
		assertEquals(Set.of(STRESS), stress.keySet());
		assertObserved(stress, STRESS, 3);
		assertEquals(1, stressOnly.stressReads);
		assertEquals(0, stressOnly.capacityReads, "capacity is not read when only stress is demanded");
	}

	@Test
	void throwingRawAccessorOnlyLosesItsOwnField() {
		FakeSource brokenStress = new FakeSource();
		brokenStress.capacity = 4f;
		brokenStress.stressFailure = new IllegalStateException("stress accessor");
		Map<String, PreviewFieldAccess.Outcome> stressOutcomes = observe(brokenStress, STRESS, CAPACITY, AVAILABLE);
		assertUnavailable(stressOutcomes, STRESS);
		assertObserved(stressOutcomes, CAPACITY, 4);
		assertUnavailable(stressOutcomes, AVAILABLE);
		assertEquals(1, brokenStress.capacityReads);

		FakeSource brokenCapacity = new FakeSource();
		brokenCapacity.stress = 3f;
		brokenCapacity.capacityFailure = new IllegalStateException("capacity accessor");
		Map<String, PreviewFieldAccess.Outcome> capacityOutcomes = observe(brokenCapacity, STRESS, CAPACITY, AVAILABLE);
		assertObserved(capacityOutcomes, STRESS, 3);
		assertUnavailable(capacityOutcomes, CAPACITY);
		assertUnavailable(capacityOutcomes, AVAILABLE);
		assertEquals(1, brokenCapacity.stressReads);
	}

	@Test
	void linkageFailureFromOneRawAccessorStaysPerField() {
		FakeSource source = new FakeSource() {
			@Override public float cachedCapacity() { throw new NoClassDefFoundError("capacity accessor"); }
		};
		source.stress = 3f;

		Map<String, PreviewFieldAccess.Outcome> outcomes = observe(source, STRESS, CAPACITY, AVAILABLE);

		assertObserved(outcomes, STRESS, 3);
		assertUnavailable(outcomes, CAPACITY);
		assertUnavailable(outcomes, AVAILABLE);
	}

	@Test
	void availableUsesDoublePromotionAndAcceptsExtremeFiniteFloats() {
		FakeSource precise = new FakeSource();
		precise.stress = 1f;
		precise.capacity = 1.0e9f;
		assertObserved(observe(precise, AVAILABLE), AVAILABLE, 999_999_999d);

		FakeSource maximal = new FakeSource();
		maximal.stress = Float.MAX_VALUE;
		maximal.capacity = Float.MAX_VALUE;
		assertObserved(observe(maximal, AVAILABLE), AVAILABLE, 0);

		FakeSource emptyStress = new FakeSource();
		emptyStress.stress = 0f;
		emptyStress.capacity = Float.MAX_VALUE;
		assertObserved(observe(emptyStress, AVAILABLE), AVAILABLE, Float.MAX_VALUE);

		FakeSource overused = new FakeSource();
		overused.stress = Float.MAX_VALUE;
		overused.capacity = 0f;
		assertObserved(observe(overused, AVAILABLE), AVAILABLE, -((double) Float.MAX_VALUE));
	}

	@Test
	void nonFiniteOrNegativeRawValueOnlyLosesItsOwnField() {
		FakeSource nanStress = new FakeSource();
		nanStress.stress = Float.NaN;
		nanStress.capacity = 4f;
		Map<String, PreviewFieldAccess.Outcome> nanOutcomes = observe(nanStress, STRESS, CAPACITY, AVAILABLE);
		assertUnavailable(nanOutcomes, STRESS);
		assertObserved(nanOutcomes, CAPACITY, 4);
		assertUnavailable(nanOutcomes, AVAILABLE);

		FakeSource negativeCapacity = new FakeSource();
		negativeCapacity.stress = 3f;
		negativeCapacity.capacity = -1f;
		Map<String, PreviewFieldAccess.Outcome> negativeOutcomes = observe(negativeCapacity, STRESS, CAPACITY, AVAILABLE);
		assertObserved(negativeOutcomes, STRESS, 3);
		assertUnavailable(negativeOutcomes, CAPACITY);
		assertUnavailable(negativeOutcomes, AVAILABLE);

		FakeSource infiniteStress = new FakeSource();
		infiniteStress.stress = Float.POSITIVE_INFINITY;
		infiniteStress.capacity = 8f;
		Map<String, PreviewFieldAccess.Outcome> infiniteOutcomes = observe(infiniteStress, STRESS, CAPACITY, AVAILABLE);
		assertUnavailable(infiniteOutcomes, STRESS);
		assertObserved(infiniteOutcomes, CAPACITY, 8);
		assertUnavailable(infiniteOutcomes, AVAILABLE);
	}

	private static Map<String, PreviewFieldAccess.Outcome> observe(FakeSource source, String... demand) {
		return CreateCachedKineticPreview.observe(source, Set.of(demand), TICK);
	}

	private static void assertObserved(Map<String, PreviewFieldAccess.Outcome> outcomes,
		String field, double value) {
		assertEquals(new PreviewFieldAccess.Observed(new PreviewObservation(
			new PresentationValue.NumberValue(value), PreviewObservation.Origin.CLIENT_SYNCED, TICK, false)),
			outcomes.get(field));
	}

	private static void assertUnavailable(Map<String, PreviewFieldAccess.Outcome> outcomes, String field) {
		assertEquals(PreviewFieldAccess.Missing.UNAVAILABLE, outcomes.get(field));
	}

	private static class FakeSource implements CreateCachedKineticPreview.Source {
		private boolean received = true;
		private boolean network = true;
		private boolean accessor = true;
		private float stress;
		private float capacity;
		private RuntimeException stressFailure;
		private RuntimeException capacityFailure;
		private int networkReads;
		private int accessorReads;
		private int stressReads;
		private int capacityReads;

		@Override public boolean receivedKinetics() { return received; }
		@Override public boolean hasNetwork() { networkReads++; return network; }
		@Override public boolean hasCachedTotals() { accessorReads++; return accessor; }

		@Override public float cachedStress() {
			stressReads++;
			if (stressFailure != null) throw stressFailure;
			return stress;
		}

		@Override public float cachedCapacity() {
			capacityReads++;
			if (capacityFailure != null) throw capacityFailure;
			return capacity;
		}
	}
}
