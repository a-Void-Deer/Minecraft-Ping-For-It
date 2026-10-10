package nx.pingwheel.common.presentation.minecraft;

import nx.pingwheel.common.marker.MarkerCreationService;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.presentation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** One bounded, authoritative capture transaction before a marker enters the store. */
final class ServerPropertyAdmission {
	private ServerPropertyAdmission() {}

	record Context(String registryId, Set<String> tagIds) {
		Context { tagIds = Set.copyOf(tagIds); }
	}

	@FunctionalInterface
	interface Capture {
		PresentationSection capture(PresentationAdapter adapter, Set<String> roots,
			PresentationAdapter.CaptureBudget budget);
	}

	static MarkerCreationService.AdmissionResult admit(List<PresentationPropertyIntent> intents,
		PresentationRegistry registry, Map<String, Set<String>> ownerAllowed,
		int scanBudget, Context context, Capture capture) {
		return admit(intents, registry, ownerAllowed, Map.of(), scanBudget, context, capture);
	}

	static MarkerCreationService.AdmissionResult admit(List<PresentationPropertyIntent> intents,
		PresentationRegistry registry, Map<String, Set<String>> ownerAllowed, Map<String, Set<String>> extraDemand,
		int scanBudget, Context context, Capture capture) {
		return admit(intents, registry, ownerAllowed, extraDemand, scanBudget, context, null, 0, capture);
	}

	static MarkerCreationService.AdmissionResult admit(List<PresentationPropertyIntent> intents,
		PresentationRegistry registry, Map<String, Set<String>> ownerAllowed, Map<String, Set<String>> extraDemand,
		int scanBudget, Context context, String prepaidAdapter, int contextScans, Capture capture) {
		return admit(intents, registry, ownerAllowed, extraDemand, scanBudget, context, prepaidAdapter, contextScans,
			capture, ref -> true);
	}

	/**
	 * The exact child predicate is checked in the pre-capture intent loop: a
	 * denied reference rejects the whole create before any source is sampled, so
	 * a forged denied intent can never reach a collector, and a field allow
	 * never overrides the child deny.
	 */
	static MarkerCreationService.AdmissionResult admit(List<PresentationPropertyIntent> intents,
		PresentationRegistry registry, Map<String, Set<String>> ownerAllowed, Map<String, Set<String>> extraDemand,
		int scanBudget, Context context, String prepaidAdapter, int contextScans, Capture capture,
		Predicate<PresentationPropertyRef> childAllowed) {
		Objects.requireNonNull(childAllowed, "childAllowed");
		if (scanBudget <= 0 || intents == null || intents.isEmpty() || intents.size() > PresentationCodec.MAX_PROPERTIES)
			return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
		if (contextScans < 0 || contextScans > scanBudget)
			return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
		Map<String, Set<String>> roots = new LinkedHashMap<>();
		Set<PresentationPropertyRef> unique = new LinkedHashSet<>();
		for (PresentationPropertyIntent intent : intents) {
			if (intent == null || !unique.add(intent.ref()))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			PresentationPropertyRef ref = intent.ref();
			PresentationAdapter adapter = registry.get(ref.adapterId());
			if (adapter == null || !ownerAllowed.getOrDefault(ref.adapterId(), Set.of()).contains(ref.fieldId()))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			if (!childAllowed.test(ref))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			PresentationField field = adapter.fields().stream().filter(f -> f.id().equals(ref.fieldId())).findFirst().orElse(null);
			if (field == null || (ref.isRoot() && !field.accepts(intent.observedValue()))
				|| (!ref.isRoot() && field.kind() != PresentationField.Kind.RECORD))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			if (intent.pingTypeId() != null && (context == null || !PresentationPropertyPingTypes.builtIn()
				.allows(intent.pingTypeId(), context.registryId(), context.tagIds())))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_PING_TYPE);
			roots.computeIfAbsent(ref.adapterId(), id -> new LinkedHashSet<>()).add(ref.fieldId());
		}
		PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(scanBudget - contextScans);
		Map<String, PresentationSection> seeds = new LinkedHashMap<>();
		for (var entry : roots.entrySet()) {
			PresentationAdapter adapter = registry.get(entry.getKey());
			if (!entry.getKey().equals(prepaidAdapter) && !budget.scan())
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			Set<String> demand = new LinkedHashSet<>(entry.getValue());
			demand.addAll(extraDemand.getOrDefault(entry.getKey(), Set.of()));
			PresentationSection sampled;
			try { sampled = capture.capture(adapter, Set.copyOf(demand), budget); }
			catch (RuntimeException | LinkageError failure) {
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			}
			PresentationSection neutral = PresentationServer.sanitize(adapter, sampled, demand);
			if (neutral == null || neutral.stale())
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			if (!neutral.fields().keySet().containsAll(entry.getValue()))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			seeds.put(entry.getKey(), neutral);
		}
		List<PresentationPropertySelection> selected = new ArrayList<>();
		for (PresentationPropertyIntent intent : intents) {
			PresentationValue actual = intent.ref().resolve(seeds.get(intent.ref().adapterId()));
			if (actual == null || !actual.getClass().equals(intent.observedValue().getClass()))
				return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
			selected.add(PresentationPropertySelection.of(intent.ref(), intent.pingTypeId()));
		}
		return MarkerCreationService.AdmissionResult.accepted(selected, seeds);
	}
}
