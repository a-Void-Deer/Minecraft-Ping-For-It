package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Explicit test-only content grants; production never synthesizes authority. */
@NotNullByDefault
public final class TestContentGrants {
	private TestContentGrants() {}

	public static PresentationGrant basic(@Unmodifiable Set<String> fields) {
		Map<String, PresentationField.Kind> kinds = new LinkedHashMap<>();
		for (PresentationField field : PresentationBasic.fields())
			if (fields.contains(field.id())) kinds.put(field.id(), field.kind());
		return new PresentationGrant(Map.of(PresentationBasic.ID,
			new PresentationGrant.AdapterGrant(1, kinds)), Set.of());
	}

	public static PresentationGrant basicAll() {
		Map<String, PresentationField.Kind> kinds = new LinkedHashMap<>();
		for (PresentationField field : PresentationBasic.fields()) kinds.put(field.id(), field.kind());
		return new PresentationGrant(Map.of(PresentationBasic.ID,
			new PresentationGrant.AdapterGrant(1, kinds)), Set.of());
	}

	public static PresentationGrant basic(@Nullable PresentationSection section) {
		if (section == null) return PresentationGrant.denied();
		return adapters(section.adapterId(), section.schema(), section.fields().keySet());
	}

	public static PresentationGrant adapters(String adapterId, int schema, @Unmodifiable Set<String> fields) {
		return new PresentationGrant(Map.of(adapterId,
			new PresentationGrant.AdapterGrant(schema, kinds(adapterId, fields))), Set.of());
	}

	/** Explicit fixture grants for the legacy focused tests' advertised field maps. */
	public static PresentationGrant fromAllowed(@Unmodifiable Map<String, ? extends Set<String>> allowed) {
		return fromAllowed(allowed, Map.of());
	}

	public static PresentationGrant fromAllowed(@Unmodifiable Map<String, ? extends Set<String>> allowed,
		@Unmodifiable Map<String, ? extends List<PresentationField>> descriptors) {
		Map<String, PresentationGrant.AdapterGrant> adapters = new LinkedHashMap<>();
		allowed.forEach((adapterId, fields) -> {
			int schema = adapterId.equals(InventoryPresentation.ADAPTER_ID) ? InventoryPresentation.SCHEMA : 1;
			Map<String, PresentationField.Kind> kinds = new LinkedHashMap<>();
			List<PresentationField> local = descriptors.get(adapterId);
			if (local != null) {
				for (PresentationField field : local)
					if (fields.contains(field.id())) kinds.put(field.id(), field.kind());
			} else {
				kinds.putAll(kinds(adapterId, Set.copyOf(fields)));
			}
			if (!kinds.isEmpty()) adapters.put(adapterId,
				new PresentationGrant.AdapterGrant(schema, kinds));
		});
		return new PresentationGrant(adapters, Set.of());
	}

	public static MarkerContentGrant content(PresentationGrant grant) {
		return new MarkerContentGrant(grant, null);
	}

	public static MarkerContentGrant content(PresentationGrant grant, @Nullable InventoryResourceKind kind) {
		return new MarkerContentGrant(grant, kind);
	}

	private static Map<String, PresentationField.Kind> kinds(String adapterId, Set<String> fields) {
		Map<String, PresentationField.Kind> kinds = new LinkedHashMap<>();
		if (PresentationBasic.ID.equals(adapterId)) {
			for (PresentationField field : PresentationBasic.fields())
				if (fields.contains(field.id())) kinds.put(field.id(), field.kind());
			return kinds;
		}
		if (InventoryPresentation.ADAPTER_ID.equals(adapterId)) {
			for (PresentationField field : InventoryPresentation.INSTANCE.fields())
				if (fields.contains(field.id())) kinds.put(field.id(), field.kind());
			return kinds;
		}
		for (String field : fields) kinds.put(field,
			"create:kinetic.speed".equals(field) ? PresentationField.Kind.RECORD : PresentationField.Kind.TEXT);
		return kinds;
	}
}
