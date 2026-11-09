package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One recipient's immutable, creation-frozen presentation authorization.
 *
 * <p>Only allowed fields are present. Missing adapters or fields deny without
 * being widened by a later manifest or policy change. The child deny set is
 * the exact nested-reference set that was frozen with the marker; a denied
 * child is never independently selectable or annotatable while the complete
 * root record value stays authorized.
 */
@NotNullByDefault
public record PresentationGrant(
	@Unmodifiable Map<String, AdapterGrant> adapters,
	@Unmodifiable Set<PresentationPropertyRef> childBlack) {

	public record AdapterGrant(int schema, @Unmodifiable Map<String, PresentationField.Kind> fields) {
		public AdapterGrant {
			if (schema < 1 || schema > 255) throw new IllegalArgumentException("adapter grant schema");
			Map<String, PresentationField.Kind> copy = new LinkedHashMap<>();
			Objects.requireNonNull(fields, "fields").forEach((id, kind) -> {
				PresentationIds.validate(id);
				copy.put(id, Objects.requireNonNull(kind, "field kind"));
			});
			fields = Map.copyOf(copy);
		}
	}

	public PresentationGrant {
		Map<String, AdapterGrant> adapterCopy = new LinkedHashMap<>();
		Objects.requireNonNull(adapters, "adapters").forEach((id, grant) -> {
			PresentationIds.validate(id);
			adapterCopy.put(id, Objects.requireNonNull(grant, "adapter grant"));
		});
		adapters = Map.copyOf(adapterCopy);

		Set<PresentationPropertyRef> childCopy = new LinkedHashSet<>();
		Objects.requireNonNull(childBlack, "childBlack").forEach(ref -> {
			Objects.requireNonNull(ref, "child reference");
			if (ref.isRoot()) throw new IllegalArgumentException("child grant reference must be nested");
			childCopy.add(ref);
		});
		childBlack = Set.copyOf(childCopy);
	}

	private static final PresentationGrant DENIED = new PresentationGrant(Map.of(), Set.of());

	public static PresentationGrant denied() {
		return DENIED;
	}

	public boolean allowsField(String adapterId, String fieldId) {
		AdapterGrant adapter = adapterId == null ? null : adapters.get(adapterId);
		return adapter != null && fieldId != null && adapter.fields().containsKey(fieldId);
	}

	public boolean allowsRef(PresentationPropertyRef ref) {
		if (ref == null) return false;
		AdapterGrant adapter = adapters.get(ref.adapterId());
		if (adapter == null) return false;
		PresentationField.Kind kind = adapter.fields().get(ref.fieldId());
		if (kind == null) return false;
		if (!ref.isRoot() && kind != PresentationField.Kind.RECORD) return false;
		return !childBlack.contains(ref);
	}
}
