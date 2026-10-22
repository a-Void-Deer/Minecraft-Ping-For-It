package nx.pingwheel.common.presentation.inventory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import nx.pingwheel.common.network.InventoryS2CPacket;

/**
 * Canonical, transport-free inventory digest shared by the server producer and
 * the client verifier.
 *
 * <p>The digest is an FNV-1a 64-bit hash over a version tag followed by every
 * sample in canonical order. A sample is the entry key plus its identity
 * metadata (item id) and the count/quality/fallback components. Every text
 * component is length-delimited as a four-byte big-endian byte length followed
 * by its UTF-8 bytes, and the count is a plain eight-byte long; no
 * {@code Map.hashCode}, iteration order or platform default charset is
 * involved. Samples are sorted by the unsigned UTF-8 byte order of their keys,
 * so identical content always produces the identical digest on both sides.
 *
 * <p>A duplicate key is a programming error and rejected instead of silently
 * folding two entries into one, matching the packet decoder's duplicate-key
 * rejection. Server code can hash an entry list directly with
 * {@link #checksum(Collection)}; client code builds samples with
 * {@link #builder()} from committed values and their bounded metadata.
 */
public final class InventoryChecksums {

	/** FNV-1a 64-bit offset basis. */
	public static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

	/** FNV-1a 64-bit prime. */
	public static final long FNV_PRIME = 0x100000001b3L;

	private static final byte[] DOMAIN = "pingwheel.inventory.checksum.v1".getBytes(StandardCharsets.UTF_8);

	private static final Comparator<Sample> BY_KEY = (left, right) ->
		compareUnsigned(left.key().getBytes(StandardCharsets.UTF_8), right.key().getBytes(StandardCharsets.UTF_8));

	private InventoryChecksums() {}

	/**
	 * One canonical digest component: the stable entry key, its identity
	 * metadata, and the count/quality/fallback state.
	 *
	 * @param quality opaque grey/quality token, {@code null} when absent
	 */
	public record Sample(String key, String itemId, long count, String quality, boolean fallback) {
		public Sample {
			Objects.requireNonNull(key, "key");
			if (key.isBlank())
				throw new IllegalArgumentException("key must not be blank");
			Objects.requireNonNull(itemId, "itemId");
			if (itemId.isBlank())
				throw new IllegalArgumentException("itemId must not be blank");
			if (count < 0L)
				throw new IllegalArgumentException("count must be non-negative");
			if (quality != null && quality.isBlank())
				throw new IllegalArgumentException("quality must not be blank when present");
		}
	}

	/** Checksum of one already-built sample list. */
	public static long checksum(Collection<Sample> samples) {
		Objects.requireNonNull(samples, "samples");
		List<Sample> canonical = new ArrayList<>(samples);
		canonical.sort(BY_KEY);
		Hasher hasher = new Hasher();
		String previousKey = null;
		for (Sample sample : canonical) {
			Sample checked = Objects.requireNonNull(sample, "sample");
			if (checked.key().equals(previousKey))
				throw new IllegalArgumentException("duplicate inventory checksum key");
			previousKey = checked.key();
			hasher.add(checked);
		}
		return hasher.checksum();
	}

	/** Convenience digest of wire entries; display-only label/JSON is not hashed. */
	public static long checksumEntries(Collection<InventoryS2CPacket.Entry> entries) {
		Objects.requireNonNull(entries, "entries");
		List<Sample> samples = new ArrayList<>(entries.size());
		for (InventoryS2CPacket.Entry entry : entries) {
			InventoryS2CPacket.Entry checked = Objects.requireNonNull(entry, "entry");
			samples.add(new Sample(checked.key(), checked.itemId(), checked.count(),
				checked.quality() == null ? null : checked.quality().name(), checked.fallback()));
		}
		return checksum(samples);
	}

	/** Incremental builder; {@link Builder#checksum()} re-sorts by key like the list form. */
	public static Builder builder() {
		return new Builder();
	}

	/** Single convenience digest for a key, identity, count, quality and fallback. */
	public static long checksum(String key, String itemId, long count, String quality, boolean fallback) {
		return checksum(List.of(new Sample(key, itemId, count, quality, fallback)));
	}

	public static final class Builder {
		private final List<Sample> samples = new ArrayList<>();

		private Builder() {}

		public Builder add(String key, String itemId, long count, String quality, boolean fallback) {
			samples.add(new Sample(key, itemId, count, quality, fallback));
			return this;
		}

		public Builder add(Sample sample) {
			samples.add(Objects.requireNonNull(sample, "sample"));
			return this;
		}

		public long checksum() {
			return InventoryChecksums.checksum(samples);
		}
	}

	/** Streaming FNV-1a state; samples are length-delimited and domain-tagged. */
	private static final class Hasher {
		private long hash = FNV_OFFSET_BASIS;

		private Hasher() {
			update(DOMAIN);
		}

		private long checksum() {
			return hash;
		}

		private void add(Sample sample) {
			update(sample.key().getBytes(StandardCharsets.UTF_8));
			update(sample.itemId().getBytes(StandardCharsets.UTF_8));
			updateLong(sample.count());
			updateBoolean(sample.fallback());
			boolean hasQuality = sample.quality() != null;
			updateBoolean(hasQuality);
			if (hasQuality) {
				update(sample.quality().getBytes(StandardCharsets.UTF_8));
			}
		}

		private void update(byte[] bytes) {
			updateInt(bytes.length);
			for (byte value : bytes) {
				hash ^= (value & 0xffL);
				hash *= FNV_PRIME;
			}
		}

		private void updateInt(int value) {
			updateLong(value & 0xffffffffL);
		}

		private void updateLong(long value) {
			for (int shift = 56; shift >= 0; shift -= 8) {
				hash ^= ((value >>> shift) & 0xffL);
				hash *= FNV_PRIME;
			}
		}

		private void updateBoolean(boolean value) {
			hash ^= value ? 1L : 0L;
			hash *= FNV_PRIME;
		}
	}

	private static int compareUnsigned(byte[] left, byte[] right) {
		int length = Math.min(left.length, right.length);
		for (int i = 0; i < length; i++) {
			int comparison = Integer.compare(left[i] & 0xff, right[i] & 0xff);
			if (comparison != 0)
				return comparison;
		}
		return Integer.compare(left.length, right.length);
	}
}
