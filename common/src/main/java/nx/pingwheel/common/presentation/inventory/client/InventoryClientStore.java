package nx.pingwheel.common.presentation.inventory.client;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Transport-free client state for streamed inventory consumers.
 *
 * <p>Each consumer owns an independent {@link Channel} (preview request or
 * tracked marker; recipient is the implied local session) with its own
 * baseline identity, status revision, values, grey-invalid flag and fragment
 * assembly. Value maps are bounded by a per-channel entry count, the channel
 * count is bounded, and keys and quality tokens have fixed byte bounds.
 *
 * <p>{@link #snapshot} replaces a whole map under the state-revision fence; a
 * replay of an already installed baseline is a no-operation so it cannot roll
 * back newer streamed item revisions. {@link #apply} requires the exact
 * accepted state fence and an installed baseline, stores only items with a
 * newer per-item revision, leaves missing keys unchanged and keeps an explicit
 * zero. {@link #invalidate} keeps values for grey presentation and blocks
 * later valid updates until {@link #rebase} clears the channel with a strictly
 * newer state fence (or a monotonic resync baseline at the same fence);
 * {@link #expire} removes the channel.
 *
 * <p>{@link #beginSnapshot} and {@link #part} assemble a bounded fragmented
 * baseline that stays hidden until every part arrives. Repeating an identical
 * begin keeps the running assembly and its start tick; a weaker fence never
 * replaces a stronger pending assembly, and a key may appear in only one part.
 * The bounded unknown-baseline stream queue with its five-period expiry and
 * resync outcome is a separate follow-up unit, so an update not matching the
 * committed baseline is reported as {@link Outcome#IGNORED_UNKNOWN_BASELINE}.
 * Expiry tombstones against channel recreation are also a follow-up unit. No
 * world or network call and no UI work happens here.
 */
public final class InventoryClientStore {

	/** Sentinel for "no committed baseline"; baseline identities are non-negative. */
	public static final long NO_BASELINE = -1L;

	/** Engineering channel bound for the two-argument convenience constructor. */
	public static final int DEFAULT_MAX_CHANNELS = 128;

	private static final int MAX_KEY_BYTES = 256;
	private static final int MAX_QUALITY_BYTES = 64;

	/** Client-local consumer identity; the recipient is the local session. */
	public record Channel(long id, boolean tracking) {

		public Channel {
			if (id < 0L)
				throw new IllegalArgumentException("channel id must be non-negative");
		}

		public static Channel preview(long requestId) {
			return new Channel(requestId, false);
		}

		public static Channel tracked(long markerId) {
			return new Channel(markerId, true);
		}
	}

	/** Exact whole count, per-item stream revision and opaque quality token. */
	public record Value(long count, long revision, String quality) {

		public Value {
			if (count < 0L)
				throw new IllegalArgumentException("count must be non-negative");
			if (revision < 0L)
				throw new IllegalArgumentException("revision must be non-negative");
			Objects.requireNonNull(quality, "quality");
			if (quality.isBlank())
				throw new IllegalArgumentException("quality must not be blank");
			if (quality.getBytes(StandardCharsets.UTF_8).length > MAX_QUALITY_BYTES)
				throw new IllegalArgumentException("quality exceeds the quality byte bound");
		}
	}

	/** Outcome of a whole snapshot or an absolute update. */
	public enum Outcome {
		APPLIED,
		IGNORED_STALE,
		IGNORED_INVALID,
		IGNORED_UNKNOWN_BASELINE,
		REJECTED_BOUND
	}

	/** Outcome of one fragment assembly step. */
	public enum PartOutcome {
		STARTED,
		ACCEPTED,
		COMMITTED,
		DUPLICATE_PART,
		CONFLICT,
		CONFLICT_PART,
		NO_ASSEMBLY,
		IGNORED_STALE,
		IGNORED_INVALID,
		REJECTED_BOUND
	}

	private static final class ChannelState {

		long baselineId = NO_BASELINE;
		long statusRevision = -1L;
		boolean invalid;
		boolean baselineInstalled;
		final Map<String, Value> values = new LinkedHashMap<>();
		Assembly assembly;
	}

	private static final class Assembly {

		final long baselineId;
		final long stateRevision;
		final int totalParts;
		final long startTick;
		final Map<Integer, Map<String, Value>> parts = new LinkedHashMap<>();
		final Map<String, Integer> keysByPart = new LinkedHashMap<>();
		long entryCount;

		Assembly(long baselineId, long stateRevision, int totalParts, long startTick) {
			this.baselineId = baselineId;
			this.stateRevision = stateRevision;
			this.totalParts = totalParts;
			this.startTick = startTick;
		}
	}

	private final int maxParts;
	private final long maxEntries;
	private final int maxChannels;
	private final Map<Channel, ChannelState> channels = new LinkedHashMap<>();

	public InventoryClientStore(int maxParts, int maxEntries) {
		this(maxParts, maxEntries, DEFAULT_MAX_CHANNELS);
	}

	/**
	 * @param maxParts    engineering bound for one logical baseline's fragment count
	 * @param maxEntries  engineering bound for one channel's committed entry count
	 * @param maxChannels engineering bound for channels held by this store
	 */
	public InventoryClientStore(int maxParts, long maxEntries, int maxChannels) {
		if (maxParts < 1)
			throw new IllegalArgumentException("maxParts must be positive");
		if (maxEntries < 1L)
			throw new IllegalArgumentException("maxEntries must be positive");
		if (maxChannels < 1)
			throw new IllegalArgumentException("maxChannels must be positive");
		this.maxParts = maxParts;
		this.maxEntries = maxEntries;
		this.maxChannels = maxChannels;
	}

	/**
	 * Replaces the channel's whole value map. The revision must not be older
	 * than the channel fence; on an equal revision a lower baseline is stale
	 * and a forward baseline is an accepted resync. Once a baseline is
	 * installed, an identical replay is a no-operation that preserves newer
	 * streamed values.
	 */
	public Outcome snapshot(Channel channel, long baselineId, long stateRevision, Map<String, Value> entries) {
		Objects.requireNonNull(channel, "channel");
		Map<String, Value> copy = copyValues(entries);
		requireBaseline(baselineId);
		requireRevision(stateRevision);
		ChannelState state = channels.get(channel);

		if (state != null && state.invalid)
			return Outcome.IGNORED_INVALID;

		if (state == null) {
			if (channels.size() >= maxChannels)
				return Outcome.REJECTED_BOUND;
		} else {
			Outcome stale = fenceOutcome(state, baselineId, stateRevision);
			if (stale != null)
				return stale;
			if (state.baselineInstalled && baselineId == state.baselineId
				&& stateRevision == state.statusRevision)
				return Outcome.APPLIED;
		}

		if ((long) copy.size() > maxEntries)
			return Outcome.REJECTED_BOUND;

		if (state == null) {
			state = new ChannelState();
			channels.put(channel, state);
		}
		state.values.clear();
		state.values.putAll(copy);
		state.baselineId = baselineId;
		state.statusRevision = stateRevision;
		state.baselineInstalled = true;
		state.assembly = null;
		return Outcome.APPLIED;
	}

	/**
	 * Applies an absolute per-item update. The state revision must equal the
	 * accepted fence exactly and the baseline must be installed; every listed
	 * key with a newer item revision is stored, missing keys stay unchanged and
	 * an explicit zero stays present. The entry bound is checked before any
	 * value changes.
	 */
	public Outcome apply(Channel channel, long baselineId, long stateRevision, Map<String, Value> updates) {
		Objects.requireNonNull(channel, "channel");
		Map<String, Value> copy = copyValues(updates);
		requireBaseline(baselineId);
		requireRevision(stateRevision);
		ChannelState state = channels.get(channel);

		if (state == null)
			return Outcome.IGNORED_UNKNOWN_BASELINE;
		if (state.invalid)
			return Outcome.IGNORED_INVALID;
		if (stateRevision != state.statusRevision)
			return stateRevision < state.statusRevision ? Outcome.IGNORED_STALE : Outcome.IGNORED_UNKNOWN_BASELINE;
		if (baselineId != state.baselineId || !state.baselineInstalled || state.assembly != null)
			return Outcome.IGNORED_UNKNOWN_BASELINE;

		long newKeys = 0L;
		for (Map.Entry<String, Value> update : copy.entrySet()) {
			if (!state.values.containsKey(update.getKey()))
				newKeys++;
		}
		if ((long) state.values.size() + newKeys > maxEntries)
			return Outcome.REJECTED_BOUND;

		for (Map.Entry<String, Value> update : copy.entrySet()) {
			Value existing = state.values.get(update.getKey());
			if (existing == null || update.getValue().revision() > existing.revision())
				state.values.put(update.getKey(), update.getValue());
		}
		return Outcome.APPLIED;
	}

	/**
	 * Marks the channel invalid under a strictly newer status revision.
	 * Committed values are kept for grey presentation and in-flight assembly is
	 * discarded. A same-fence or older invalidate never transitions a valid
	 * channel; repeating it on an already invalid channel is idempotent.
	 */
	public boolean invalidate(Channel channel, long newStateRevision) {
		Objects.requireNonNull(channel, "channel");
		requireRevision(newStateRevision);
		ChannelState state = channels.get(channel);

		if (state == null) {
			if (channels.size() >= maxChannels)
				return false;
			state = new ChannelState();
			channels.put(channel, state);
			state.invalid = true;
			state.statusRevision = newStateRevision;
			return true;
		}
		if (newStateRevision < state.statusRevision)
			return false;
		if (newStateRevision == state.statusRevision)
			return state.invalid;

		state.invalid = true;
		state.statusRevision = newStateRevision;
		state.assembly = null;
		return true;
	}

	/**
	 * Clears the channel and establishes a new baseline. A strictly newer
	 * state revision may recover an invalid channel. At the same fence a valid
	 * channel accepts only an equal baseline (idempotent no-op preserving
	 * values) or a monotonic forward baseline (resync), never a lower baseline,
	 * and an invalid channel is never revived.
	 */
	public boolean rebase(Channel channel, long newBaselineId, long newStateRevision) {
		Objects.requireNonNull(channel, "channel");
		requireBaseline(newBaselineId);
		requireRevision(newStateRevision);
		ChannelState state = channels.get(channel);

		if (state == null) {
			if (channels.size() >= maxChannels)
				return false;
			state = new ChannelState();
			channels.put(channel, state);
			state.baselineId = newBaselineId;
			state.statusRevision = newStateRevision;
			return true;
		}
		if (newStateRevision < state.statusRevision)
			return false;
		if (newStateRevision == state.statusRevision) {
			if (state.invalid)
				return false;
			if (newBaselineId < state.baselineId)
				return false;
			if (newBaselineId == state.baselineId)
				return true;
		}

		state.values.clear();
		state.assembly = null;
		state.invalid = false;
		state.baselineId = newBaselineId;
		state.statusRevision = newStateRevision;
		state.baselineInstalled = false;
		return true;
	}

	/** Removes the channel and all of its state. */
	public void expire(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		channels.remove(channel);
	}

	/**
	 * Starts a fragmented baseline: at most {@code maxParts} parts and, across
	 * all parts, at most {@code maxEntries} entries. Repeating an identical
	 * begin keeps the running assembly and its start tick; the same baseline
	 * with a different part count conflicts; a weaker fence never replaces a
	 * stronger pending or committed fence.
	 */
	public PartOutcome beginSnapshot(Channel channel, long baselineId, long stateRevision, int totalParts,
			long startTick) {
		Objects.requireNonNull(channel, "channel");
		requireBaseline(baselineId);
		requireRevision(stateRevision);
		if (startTick < 0L)
			throw new IllegalArgumentException("startTick must be non-negative");
		if (totalParts < 1 || totalParts > maxParts)
			return PartOutcome.REJECTED_BOUND;

		ChannelState state = channels.get(channel);
		if (state == null) {
			if (channels.size() >= maxChannels)
				return PartOutcome.REJECTED_BOUND;
		} else {
			if (state.invalid)
				return PartOutcome.IGNORED_INVALID;
			Assembly pending = state.assembly;
			if (pending != null) {
				if (baselineId == pending.baselineId && stateRevision == pending.stateRevision)
					return totalParts == pending.totalParts ? PartOutcome.STARTED : PartOutcome.CONFLICT;
				if (stateRevision < pending.stateRevision
					|| (stateRevision == pending.stateRevision && baselineId < pending.baselineId))
					return PartOutcome.IGNORED_STALE;
			}
			if (stateRevision < state.statusRevision)
				return PartOutcome.IGNORED_STALE;
			if (stateRevision == state.statusRevision) {
				if (state.baselineId != NO_BASELINE && baselineId < state.baselineId)
					return PartOutcome.IGNORED_STALE;
				if (baselineId == state.baselineId && state.baselineInstalled)
					return PartOutcome.IGNORED_STALE;
			}
		}

		if (state == null) {
			state = new ChannelState();
			channels.put(channel, state);
		}
		state.assembly = new Assembly(baselineId, stateRevision, totalParts, startTick);
		return PartOutcome.STARTED;
	}

	/**
	 * Adds one part to the running assembly. A repeated identical part is a
	 * no-operation, a repeated index with different content is rejected without
	 * replacing the stored part, a key repeated across parts is rejected, and
	 * the baseline commits only when every part has arrived.
	 */
	public PartOutcome part(Channel channel, long baselineId, long stateRevision, int partIndex,
			Map<String, Value> entries, long tick) {
		Objects.requireNonNull(channel, "channel");
		Map<String, Value> copy = copyValues(entries);
		if (tick < 0L)
			throw new IllegalArgumentException("tick must be non-negative");

		ChannelState state = channels.get(channel);
		if (state == null)
			return PartOutcome.NO_ASSEMBLY;
		if (state.invalid)
			return PartOutcome.IGNORED_INVALID;

		Assembly assembly = state.assembly;
		if (assembly == null || assembly.baselineId != baselineId || assembly.stateRevision != stateRevision)
			return PartOutcome.NO_ASSEMBLY;
		if (partIndex < 0 || partIndex >= assembly.totalParts)
			return PartOutcome.REJECTED_BOUND;

		Map<String, Value> existing = assembly.parts.get(partIndex);
		if (existing != null)
			return existing.equals(copy) ? PartOutcome.DUPLICATE_PART : PartOutcome.CONFLICT_PART;

		for (String key : copy.keySet()) {
			if (assembly.keysByPart.containsKey(key))
				return PartOutcome.CONFLICT_PART;
		}

		if (assembly.entryCount + copy.size() > maxEntries)
			return PartOutcome.REJECTED_BOUND;

		assembly.parts.put(partIndex, copy);
		for (String key : copy.keySet())
			assembly.keysByPart.put(key, partIndex);
		assembly.entryCount += copy.size();

		if (assembly.parts.size() < assembly.totalParts)
			return PartOutcome.ACCEPTED;

		commit(state, assembly);
		return PartOutcome.COMMITTED;
	}

	public boolean hasChannel(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		return channels.containsKey(channel);
	}

	public boolean isInvalid(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelState state = channels.get(channel);
		return state != null && state.invalid;
	}

	public long baselineId(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelState state = channels.get(channel);
		return state == null ? NO_BASELINE : state.baselineId;
	}

	/** Current status revision, or {@code -1} when the channel is absent. */
	public long statusRevision(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelState state = channels.get(channel);
		return state == null ? -1L : state.statusRevision;
	}

	/** Whether a fragmented baseline is still waiting for parts. */
	public boolean assembling(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelState state = channels.get(channel);
		return state != null && state.assembly != null;
	}

	/** Tick at which the running assembly started, if one is running. */
	public OptionalLong assemblyStartTick(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelState state = channels.get(channel);
		return state != null && state.assembly != null
			? OptionalLong.of(state.assembly.startTick)
			: OptionalLong.empty();
	}

	/** Immutable copy of the committed values; a partial assembly stays hidden. */
	public Map<String, Value> values(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelState state = channels.get(channel);
		return state == null ? Map.of() : Map.copyOf(state.values);
	}

	private static Outcome fenceOutcome(ChannelState state, long baselineId, long stateRevision) {
		long fenceRevision = state.statusRevision;
		long fenceBaseline = state.baselineId;
		Assembly assembly = state.assembly;
		if (assembly != null && (assembly.stateRevision > fenceRevision
			|| (assembly.stateRevision == fenceRevision && assembly.baselineId > fenceBaseline))) {
			fenceRevision = assembly.stateRevision;
			fenceBaseline = assembly.baselineId;
		}
		if (stateRevision < fenceRevision)
			return Outcome.IGNORED_STALE;
		if (stateRevision == fenceRevision && fenceBaseline != NO_BASELINE && baselineId < fenceBaseline)
			return Outcome.IGNORED_STALE;
		return null;
	}

	private static void commit(ChannelState state, Assembly assembly) {
		Map<String, Value> merged = new LinkedHashMap<>();
		for (int i = 0; i < assembly.totalParts; i++) {
			Map<String, Value> part = assembly.parts.get(i);
			if (part != null)
				merged.putAll(part);
		}

		state.values.clear();
		state.values.putAll(merged);
		state.baselineId = assembly.baselineId;
		state.statusRevision = assembly.stateRevision;
		state.baselineInstalled = true;
		state.assembly = null;
	}

	private static Map<String, Value> copyValues(Map<String, Value> entries) {
		Objects.requireNonNull(entries, "entries");
		Map<String, Value> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Value> entry : entries.entrySet()) {
			String key = Objects.requireNonNull(entry.getKey(), "key");
			if (key.isBlank())
				throw new IllegalArgumentException("key must not be blank");
			if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES)
				throw new IllegalArgumentException("key exceeds the key byte bound");
			copy.put(key, Objects.requireNonNull(entry.getValue(), "value"));
		}
		return copy;
	}

	private static void requireBaseline(long baselineId) {
		if (baselineId < 0L)
			throw new IllegalArgumentException("baseline id must be non-negative");
	}

	private static void requireRevision(long stateRevision) {
		if (stateRevision < 0L)
			throw new IllegalArgumentException("state revision must be non-negative");
	}
}
