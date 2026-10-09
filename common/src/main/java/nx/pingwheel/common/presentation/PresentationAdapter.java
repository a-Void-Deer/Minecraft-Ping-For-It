package nx.pingwheel.common.presentation;

import java.util.List;

import nx.pingwheel.common.domain.Target;

/**
	 * Extension SPI. The version bridge resolves a world object to a detached handle, and
	 * supplies a bounded collector for that handle. The client only needs the manifest;
	 * no UI is required to decode fields. Implementations may return unavailable rather
	 * than force-loading a source.
	 */
public interface PresentationAdapter {
	String adapterId();
	String modId();
	int schema();
	int minUpdateIntervalTicks();
	List<PresentationField> fields();

	/** Only fields in demand may be read. Null means temporarily unavailable. */
	PresentationSection collect(DetachedTarget target, java.util.Set<String> demand, CaptureBudget budget);

	/**
	 * Preview may observe a target through the adapter's safe read-only source.
	 * The default keeps the committed one-shot collect contract; a
	 * candidate-aware adapter overrides this without widening {@link #collect}.
	 */
	default PresentationSection collectPreview(DetachedTarget target, java.util.Set<String> demand, CaptureBudget budget) {
		return collect(target, demand, budget);
	}

	/**
	 * How this adapter's values reach a client. The legacy framed SECTION route
	 * only ever samples, masks, publishes and renders {@link DeliveryMode#SECTION}
	 * adapters; a dedicated adapter owns its own request, collection and
	 * publication path and must never be charged or sent as an empty section.
	 */
	enum DeliveryMode { SECTION, DEDICATED }

	/** Existing adapters stay on the legacy framed SECTION route. */
	default DeliveryMode deliveryMode() { return DeliveryMode.SECTION; }

	record DetachedTarget(String dimension, String kind, String registryId, int x, int y, int z,
		String locator, Target.ExternalBlockTarget externalBlock) {
		public DetachedTarget(String dimension, String kind, String registryId, int x, int y, int z,
			String locator) {
			this(dimension, kind, registryId, x, y, z, locator, null);
		}
	}

	/** A hard upper bound on source scans even if few distinct values result. */
	final class CaptureBudget {
		private int remaining;
		public CaptureBudget(int scans) { remaining = Math.max(0, scans); }
		public boolean scan() { if (remaining == 0) return false; --remaining; return true; }
		public int remaining() { return remaining; }
	}
}
