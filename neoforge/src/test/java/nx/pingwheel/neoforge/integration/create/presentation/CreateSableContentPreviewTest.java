package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adapter-level preview/committed separation; no Create classes are loaded here. */
class CreateSableContentPreviewTest {
	private static final Target.ExternalBlockTarget CANDIDATE = Target.ExternalBlockTarget.candidate(
		"minecraft:overworld", "sable", "minecraft:chest", "opaque-locator", true);

	private static PresentationAdapter.DetachedTarget detached(Target.ExternalBlockTarget target) {
		return new PresentationAdapter.DetachedTarget(
			target.dimensionId(), "block", target.expectedBlockRegistryId(), 0, 0, 0, "", target);
	}

	@Test
	void previewCollectUsesCandidateAwareSourceWhileOrdinaryCollectStaysCommitted() {
		List<String> calls = new ArrayList<>();
		CreatePresentationAdapter.Source source = new CreatePresentationAdapter.Source() {
			@Override public CreatePresentationAdapter.Observation observe(
				PresentationAdapter.DetachedTarget target, Set<String> demand,
				PresentationAdapter.CaptureBudget budget) {
				calls.add("collect");
				return null;
			}

			@Override public CreatePresentationAdapter.Observation observePreview(
				PresentationAdapter.DetachedTarget target, Set<String> demand,
				PresentationAdapter.CaptureBudget budget) {
				calls.add("preview");
				return new CreatePresentationAdapter.Observation(
					new CreatePresentationAdapter.Speed(0, 0, false), false, false, null, null, null, null);
			}
		};
		CreatePresentationAdapter adapter = new CreatePresentationAdapter(source);
		PresentationAdapter.DetachedTarget target = detached(CANDIDATE);

		assertNull(adapter.collect(target, Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(4)));
		assertEquals(List.of("collect"), calls, "ordinary collect never resolves a candidate source");
		PresentationSection section = adapter.collectPreview(target, Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(4));
		assertEquals(List.of("collect", "preview"), calls);
		assertTrue(section.fields().get(CreatePresentationAdapter.SPEED) instanceof PresentationValue.RecordValue);
		assertNull(adapter.collectPreview(target, Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(0)), "a zero preview budget never reaches the source");
		assertEquals(List.of("collect", "preview"), calls);
	}

	@Test
	void defaultPreviewSourceDelegatesToTheCommittedObservation() {
		List<String> calls = new ArrayList<>();
		CreatePresentationAdapter.Source source = (target, demand, budget) -> {
			calls.add("observe");
			return null;
		};
		CreatePresentationAdapter adapter = new CreatePresentationAdapter(source);

		assertNull(adapter.collectPreview(detached(CANDIDATE), Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(4)));
		assertEquals(List.of("observe"), calls, "an adapter without a candidate source keeps its committed path");
	}
}
