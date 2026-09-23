package nx.pingwheel.common.presentation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/** Session-scoped ordered whole-section store, including marker and section tombstones. */
public final class PresentationStore {
	// Fail closed on history exhaustion: discarding a tombstone would allow a late packet to resurrect data.
	private static final int MAX_MARKER_HISTORY = 8192;
	private static final int MAX_SECTION_HISTORY = 128;
	private final Map<Long, MarkerState> markers = new HashMap<>();
	private final Map<Long, Long> tombstones = new HashMap<>();
	private boolean markerHistoryFull;
	private long epoch;
	private long subscriptionGeneration;
	private long viewGeneration;

	public record Entry(long revision, PresentationSection section) {}

	private static final class MarkerState {
		final Map<String, Entry> sections = new HashMap<>();
		final Map<String, Long> clearedSections = new HashMap<>();
		boolean frozen;
		boolean sectionHistoryFull;
	}

	public long epoch() { return epoch; }
	public long subscriptionGeneration() { return subscriptionGeneration; }
	public long viewGeneration() { return viewGeneration; }
	public boolean isKnown(long markerId) { return markers.containsKey(markerId) || tombstones.containsKey(markerId); }
	public boolean isFrozen(long markerId) {
		MarkerState marker = markers.get(markerId);
		return marker != null && marker.frozen;
	}

	public void reset(long epoch) {
		markers.clear();
		tombstones.clear();
		markerHistoryFull = false;
		this.epoch = epoch;
		subscriptionGeneration = 0;
		viewGeneration = 0;
	}

	public void generation(long epoch, long subscription, long view) {
		if (this.epoch != epoch || subscription < subscriptionGeneration || view < viewGeneration) return;
		if (subscription != subscriptionGeneration || view != viewGeneration) {
			for (MarkerState marker : markers.values()) {
				if (marker.frozen) continue;
				marker.sections.clear();
				marker.clearedSections.clear();
				marker.sectionHistoryFull = false;
			}
		}
		subscriptionGeneration = subscription;
		viewGeneration = view;
	}

	public void initial(long epoch, long subscription, long view, long markerId, PresentationSection basic) {
		if (!current(epoch, subscription, view) || isKnown(markerId) || markerHistoryFull) return;
		if (markers.size() + tombstones.size() >= MAX_MARKER_HISTORY) {
			markerHistoryFull = true;
			return;
		}
		MarkerState marker = new MarkerState();
		marker.sections.put(basic.adapterId(), new Entry(0, basic));
		markers.put(markerId, marker);
	}

	/** A replacement must be newer than either the current section or its clear tombstone. */
	public boolean replace(long epoch, long subscription, long view, long markerId, long revision,
		PresentationSection section) {
		if (!current(epoch, subscription, view) || revision < 1) return false;
		MarkerState marker = markers.get(markerId);
		if (marker == null || marker.frozen) return false;
		String id = section.adapterId();
		Entry old = marker.sections.get(id);
		if (old != null && old.revision() >= revision) return false;
		Long cleared = marker.clearedSections.get(id);
		if (cleared != null && cleared >= revision) return false;
		if (old == null && cleared == null && !newSectionAllowed(marker)) return false;
		marker.clearedSections.remove(id);
		marker.sections.put(id, new Entry(revision, section));
		return true;
	}

	/** A section clear is revisioned and never deletes the marker or another adapter's section. */
	public boolean clearSection(long epoch, long subscription, long view, long markerId, String adapterId,
		long revision) {
		if (!current(epoch, subscription, view) || revision < 1) return false;
		PresentationIds.validate(adapterId);
		MarkerState marker = markers.get(markerId);
		if (marker == null || marker.frozen) return false;
		Entry old = marker.sections.get(adapterId);
		if (old != null && old.revision() >= revision) return false;
		Long cleared = marker.clearedSections.get(adapterId);
		if (cleared != null && cleared >= revision) return false;
		if (old == null && cleared == null && !newSectionAllowed(marker)) return false;
		marker.sections.remove(adapterId);
		marker.clearedSections.put(adapterId, revision);
		return true;
	}

	private static boolean newSectionAllowed(MarkerState marker) {
		if (marker.sectionHistoryFull) return false;
		if (marker.sections.size() + marker.clearedSections.size() < MAX_SECTION_HISTORY) return true;
		marker.sectionHistoryFull = true;
		return false;
	}

	/** Expiry retains and freezes existing sections; hard removal tombstones even unseen IDs. */
	public void clear(long epoch, long markerId, long revision, boolean expired) {
		if (epoch != this.epoch) return;
		if (expired) {
			MarkerState marker = markers.get(markerId);
			if (marker != null) { marker.frozen = true; return; }
		}
		forget(markerId, revision);
	}

	public Map<String, Entry> sections(long markerId) {
		MarkerState marker = markers.get(markerId);
		return marker == null ? Map.of() : Map.copyOf(marker.sections);
	}

	/** Local eviction still remembers the ID: a delayed initial cannot resurrect it. */
	public void evict(long markerId) { forget(markerId, 0); }

	private void forget(long markerId, long revision) {
		MarkerState removed = markers.remove(markerId);
		if (removed == null && !tombstones.containsKey(markerId)
			&& markers.size() + tombstones.size() >= MAX_MARKER_HISTORY) {
			markerHistoryFull = true;
			return;
		}
		tombstones.merge(markerId, revision, Math::max);
	}

	private boolean current(long epoch, long subscription, long view) {
		return this.epoch == epoch && subscriptionGeneration == subscription && viewGeneration == view;
	}

	/** Tightening receive policy deletes retained fields, including expired/frozen values. */
	public void restrict(BiPredicate<String, String> allowed) {
		for (MarkerState marker : markers.values()) {
			marker.sections.replaceAll((adapter, entry) -> {
				var fields = new HashMap<String, PresentationValue>();
				entry.section().fields().forEach((id, value) -> {
					if (allowed.test(adapter, id)) fields.put(id, value);
				});
				return new Entry(entry.revision(),
					new PresentationSection(adapter, entry.section().schema(), fields, entry.section().stale()));
			});
		}
	}
}
