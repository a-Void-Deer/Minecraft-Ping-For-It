package nx.pingwheel.common.presentation.preview;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationPropertyRef;

/** Already-intersected accepted SECTION schemas and the current server RESET mask. */
public record PresentationPreviewAccess(long epoch, long view, String targetTypeId,
	Map<String, Adapter> adapters, Set<PresentationPropertyRef> childBlack) {
	public PresentationPreviewAccess {
		if (epoch == 0 || view < 1 || !PresentationSettings.isKnownTargetType(targetTypeId))
			throw new IllegalArgumentException("unready preview access");
		adapters = Map.copyOf(Objects.requireNonNull(adapters));
		if (adapters.size() > 32) throw new IllegalArgumentException("preview adapter count");
		adapters.keySet().forEach(PresentationIds::validate);
		if (adapters.containsKey("pingforit:inventory")) throw new IllegalArgumentException("inventory owns its dedicated route");
		childBlack = Set.copyOf(Objects.requireNonNull(childBlack));
		if (childBlack.size() > 2 * PresentationSettings.MAX_CHILD_BLACK_REFS || childBlack.stream().anyMatch(PresentationPropertyRef::isRoot))
			throw new IllegalArgumentException("preview child deny references");
	}
	public PresentationPreviewAccess(long epoch, long view, String targetTypeId, Map<String, Adapter> adapters) {
		this(epoch, view, targetTypeId, adapters, Set.of());
	}
	public record Adapter(int schema, Map<String, PresentationField> fields) {
		public Adapter {
			if (schema < 1 || schema > 255 || fields.size() > PresentationPreviewLimits.MAX_FIELDS)
				throw new IllegalArgumentException("preview adapter bounds");
			fields.forEach((id, descriptor) -> {
				PresentationIds.validate(id);
				if (!id.equals(descriptor.id())) throw new IllegalArgumentException("descriptor identity");
			});
			fields = Map.copyOf(fields);
		}
	}
	public boolean allows(String adapter, String field) {
		Adapter accepted = adapters.get(adapter);
		return accepted != null && accepted.fields().containsKey(field);
	}
	/** A child deny is exact: neither its root nor its descendants are denied. */
	public boolean allows(PresentationPropertyRef ref) {
		return ref != null && allows(ref.adapterId(), ref.fieldId()) && !childBlack.contains(ref);
	}
	public Set<String> fields(String adapter) {
		Adapter accepted = adapters.get(adapter);
		return accepted == null ? Set.of() : accepted.fields().keySet();
	}
	/** Select a subset without widening authorization; useful for a correlated response. */
	public PresentationPreviewAccess narrow(String adapter, Set<String> requested) {
		Adapter accepted = adapters.get(adapter);
		if (accepted == null) return new PresentationPreviewAccess(epoch, view, targetTypeId, Map.of(), childBlack);
		Map<String, PresentationField> fields = new LinkedHashMap<>();
		accepted.fields().forEach((id, field) -> { if (requested.contains(id)) fields.put(id, field); });
		return new PresentationPreviewAccess(epoch, view, targetTypeId,
			Map.of(adapter, new Adapter(accepted.schema(), fields)), childBlack);
	}
}
