package nx.pingwheel.common.integration.sable.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounds and rate-limits diagnostics for presentation failures.
 *
 * <p>Presentation is attempted every frame, so a transient failure must not
 * turn into a per-frame log storm. Keys are supplied by the caller and must
 * contain only stable operation/failure/target identity values.</p>
 */
final class SablePresentationLogGate {

	static final long DEFAULT_INTERVAL_NANOS = 5_000_000_000L;
	static final int DEFAULT_MAX_ENTRIES = 256;

	private final LongSupplier clock;
	private final long intervalNanos;
	private final int maxEntries;
	private final Map<String, Long> lastLoggedNanos = new LinkedHashMap<>(16, 0.75f, true);

	SablePresentationLogGate() {
		this(System::nanoTime, DEFAULT_INTERVAL_NANOS, DEFAULT_MAX_ENTRIES);
	}

	SablePresentationLogGate(LongSupplier clock, long intervalNanos, int maxEntries) {
		this.clock = Objects.requireNonNull(clock, "clock");
		if (intervalNanos <= 0) {
			throw new IllegalArgumentException("intervalNanos must be positive");
		}
		if (maxEntries <= 0) {
			throw new IllegalArgumentException("maxEntries must be positive");
		}

		this.intervalNanos = intervalNanos;
		this.maxEntries = maxEntries;
	}

	synchronized boolean shouldLog(String key) {
		Objects.requireNonNull(key, "key");
		return shouldLog(key, clock.getAsLong());
	}

	private boolean shouldLog(String key, long nowNanos) {
		evictExpired(nowNanos);
		Long previous = lastLoggedNanos.get(key);

		if (previous != null && nowNanos - previous < intervalNanos) {
			return false;
		}

		lastLoggedNanos.put(key, nowNanos);
		while (lastLoggedNanos.size() > maxEntries) {
			Iterator<String> entries = lastLoggedNanos.keySet().iterator();
			entries.next();
			entries.remove();
		}
		return true;
	}

	synchronized void clear() {
		lastLoggedNanos.clear();
	}

	synchronized int size() {
		return lastLoggedNanos.size();
	}

	private void evictExpired(long nowNanos) {
		Iterator<Map.Entry<String, Long>> entries = lastLoggedNanos.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<String, Long> entry = entries.next();
			if (nowNanos - entry.getValue() >= intervalNanos) {
				entries.remove();
			}
		}
	}
}
