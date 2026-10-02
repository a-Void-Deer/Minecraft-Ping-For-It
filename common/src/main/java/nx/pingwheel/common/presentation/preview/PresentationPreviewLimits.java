package nx.pingwheel.common.presentation.preview;

import nx.pingwheel.common.presentation.PresentationLimits;

/** Finite engineering guards, independent of inventory and marker lifetime. */
public final class PresentationPreviewLimits {
	public static final int MAX_REQUEST_BYTES = 128 * 1024;
	public static final int MAX_RESPONSE_BYTES = 40 * 1024;
	public static final int MAX_FIELDS = PresentationLimits.MAX_FIELDS;
	public static final int MAX_QUEUED = 64;
	public static final int MAX_CONNECTIONS = 1024;
	public static final int MAX_PROPERTY_ENTRIES = 256;
	public static final long REQUEST_TICKS = 100;
	public static final long MIN_REQUEST_TICKS = 10;
	private PresentationPreviewLimits() {}
}
