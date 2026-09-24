package nx.pingwheel.common.presentation.client;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, namespace-grouped field catalogue for the presentation settings UI.
 *
 * <p>Two sources produce the same read-only shape: the locally registered
 * client manifest (offline preview and local receive/display editors) and the
 * server-advertised fields accepted from an offer (the server policy page and
 * the shared read-only reference). Entries carry the authoritative metadata of
 * their source; the local manifest's defaults are never substituted for the
 * server's advertised metadata. Grouping follows the namespace of each field
 * id, not the owning adapter's mod id, so a bridge or wrapper adapter still
 * groups each field under the namespace a player sees. A field id that occurs
 * more than once is rendered once, keeping the first adapter in registration
 * or manifest order, so a colliding global id cannot produce duplicate rows.
 */
public final class PresentationFieldCatalog {
	/**
	 * One field row: the owning adapter for lookup and tooltips, the namespace
	 * of the field id itself, and the source metadata.
	 */
	public record Entry(String adapterId, String namespace, PresentationField field) {
		/** Stable language-key suffix; the field id never appears as a key itself. */
		public String translationSuffix() {
			return field.id().replace(':', '_').replace('.', '_');
		}
	}

	/** One namespace heading and its fields in stable order. */
	public record Namespace(String id, List<Entry> entries) {
		/**
		 * The bundled heading key for this namespace. Callers check whether the
		 * key exists and fall back to the raw id, so an unknown namespace is
		 * never given an invented mod name.
		 */
		public String translationKey() {
			return "settings.pingforit.presentation.namespace." + id;
		}
	}

	private static final PresentationFieldCatalog EMPTY = new PresentationFieldCatalog(List.of());

	private final List<Entry> entries;
	private final List<Namespace> namespaces;

	private PresentationFieldCatalog(List<Entry> entries) {
		this.entries = List.copyOf(entries);

		Map<String, List<Entry>> grouped = new LinkedHashMap<>();
		for (Entry entry : this.entries) {
			grouped.computeIfAbsent(entry.namespace(), ignored -> new ArrayList<>()).add(entry);
		}

		List<Namespace> groups = new ArrayList<>(grouped.size());
		grouped.forEach((namespace, fields) -> groups.add(new Namespace(namespace, List.copyOf(fields))));
		this.namespaces = List.copyOf(groups);
	}

	public static PresentationFieldCatalog empty() {
		return EMPTY;
	}

	/** The locally registered adapters, grouped by each field id's namespace. */
	public static PresentationFieldCatalog ofAdapters(List<PresentationAdapter> adapters) {
		if (adapters == null || adapters.isEmpty()) {
			return EMPTY;
		}

		List<Entry> entries = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (PresentationAdapter adapter : adapters) {
			if (adapter == null) {
				continue;
			}
			for (PresentationField field : adapter.fields()) {
				if (field == null || !seen.add(field.id())) {
					continue;
				}
				entries.add(new Entry(adapter.adapterId(), namespaceOf(field.id()), field));
			}
		}
		return new PresentationFieldCatalog(sorted(entries));
	}

	/** The server-advertised accepted fields, grouped by each field id's namespace. */
	public static PresentationFieldCatalog ofAccepted(Map<String, ? extends List<PresentationField>> accepted) {
		if (accepted == null || accepted.isEmpty()) {
			return EMPTY;
		}

		List<Entry> entries = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		accepted.forEach((adapterId, fields) -> {
			if (adapterId == null || fields == null) {
				return;
			}
			for (PresentationField field : fields) {
				if (field == null || !seen.add(field.id())) {
					continue;
				}
				entries.add(new Entry(adapterId, namespaceOf(field.id()), field));
			}
		});
		return new PresentationFieldCatalog(sorted(entries));
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public List<Entry> entries() {
		return entries;
	}

	public List<Namespace> namespaces() {
		return namespaces;
	}

	public boolean contains(String fieldId) {
		for (Entry entry : entries) {
			if (entry.field().id().equals(fieldId)) {
				return true;
			}
		}
		return false;
	}

	/** Minecraft first, then the remaining namespaces alphabetically; fields by id. */
	private static List<Entry> sorted(List<Entry> entries) {
		entries.sort(Comparator
			.comparingInt((Entry entry) -> "minecraft".equals(entry.namespace()) ? 0 : 1)
			.thenComparing(Entry::namespace)
			.thenComparing(entry -> entry.field().id()));
		return entries;
	}

	/** PresentationField validates the id, so a valid field always has a namespace. */
	private static String namespaceOf(String fieldId) {
		int colon = fieldId == null ? -1 : fieldId.indexOf(':');
		return colon > 0 ? fieldId.substring(0, colon) : "unknown";
	}
}
