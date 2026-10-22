package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Detached, registry-ID-only aggregation. A scanned empty slot still consumes
 * work; output cardinality is not a substitute for a scanning budget.
 */
public final class BoundedCreateSummary {
	// The common PresentationValue.NumberValue transports IEEE-754 doubles.
	// Capping here avoids silently losing integer precision on the wire.
	private static final long MAX_EXACT_COUNT = (1L << 53) - 1;
	private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	private final int maxScans;
	private final int maxEntries;
	private final int maxWork;
	private final int maxOutputBytes;
	private final Map<String, Long> counts = new LinkedHashMap<>();
	private final Set<String> omitted = new HashSet<>();
	private int scanned;
	private int work;
	private int outputBytes;
	private boolean partial;

	public BoundedCreateSummary(int maxScans, int maxEntries, int maxWork, int maxOutputBytes) {
		if (maxScans <= 0 || maxEntries <= 0 || maxWork <= 0 || maxOutputBytes <= 0) {
			throw new IllegalArgumentException("summary limits must be positive");
		}
		this.maxScans = maxScans;
		this.maxEntries = maxEntries;
		this.maxWork = maxWork;
		this.maxOutputBytes = maxOutputBytes;
	}

	public boolean beginEntry() {
		if (scanned >= maxScans || work >= maxWork) {
			partial = true;
			return false;
		}
		scanned++;
		work++;
		return true;
	}

	/** Invalid data is incomplete, not an empty container. Zero means empty. */
	public void add(String registryId, long amount) {
		if (amount == 0) {
			return;
		}
		if (amount < 0 || registryId == null || registryId.length() > 128
			|| !REGISTRY_ID.matcher(registryId).matches()) {
			partial = true;
			return;
		}
		if (omitted.contains(registryId)) {
			partial = true;
			return;
		}
		if (!counts.containsKey(registryId)) {
			// ASCII-only registry IDs: characters equal UTF-8 bytes. The
			// allowance includes a maximum varlong and per-entry framing; the
			// enclosing section is additionally bounded by the common codec.
			int entryBytes = registryId.length() + 16;
			if (counts.size() >= maxEntries || entryBytes > maxOutputBytes - outputBytes) {
				partial = true;
				return;
			}
			outputBytes += entryBytes;
		}
		long previous = counts.getOrDefault(registryId, 0L);
		if (amount > MAX_EXACT_COUNT - previous) {
			// An unrepresentable amount must omit the ID entirely: keeping a
			// smaller later total would report a misleadingly low count.
			counts.remove(registryId);
			omitted.add(registryId);
			partial = true;
		} else {
			counts.put(registryId, previous + amount);
		}
	}

	public void markPartial() {
		partial = true;
	}

	public Summary snapshot() {
		return new Summary(counts, scanned, partial, true);
	}

	/** No Minecraft, Create, handler, component or NBT objects escape sampling. */
	public record Summary(Map<String, Long> counts, int scanned, boolean partial, boolean available) {
		public Summary {
			counts = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(counts, "counts")));
			if (scanned < 0) {
				throw new IllegalArgumentException("negative scan count");
			}
			if (!available && !counts.isEmpty()) {
				throw new IllegalArgumentException("unavailable summary cannot contain asserted values");
			}
		}

		public static Summary unavailable() {
			return new Summary(Map.of(), 0, false, false);
		}

		/** Budget exhausted before completeness could be established. */
		public static Summary incomplete() {
			return new Summary(Map.of(), 0, true, false);
		}
	}
}
