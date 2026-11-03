package nx.pingwheel.common.client.marker;

/**
 * Synchronization state of a client marker.  This is intentionally separate
 * from the marker's visual deadline: a stale record can remain visible for a
 * short time, and a synchronized record can remain hidden while its late
 * packets are still authoritative.
 */
public enum ClientMarkerState {
	SYNCHRONIZED,
	STALE
}
