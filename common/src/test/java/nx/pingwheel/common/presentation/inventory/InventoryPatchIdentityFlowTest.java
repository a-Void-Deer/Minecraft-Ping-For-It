package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-state backend proof that preview rows, the live select witness and the
 * first tracking capture all use the same opaque exact-key identity. The
 * fixture supplies already-frozen exact keys; it does not exercise the native
 * item freezer that produces those keys.
 */
@NotNullByDefault
class InventoryPatchIdentityFlowTest {

	private static final UUID A = new UUID(1, 1), B = new UUID(2, 2);
	private static final Target.BlockTarget TARGET =
		new Target.BlockTarget("minecraft:overworld", 4, 5, 6, "minecraft:chest");
	private static final String STONE = "minecraft:stone";
	/**
	 * Opaque stand-ins for two different canonical exact identities of one item:
	 * an explicit empty/default-only patch and an explicit component removal.
	 * The backend never parses either value; it only carries it as a key.
	 */
	private static final String EMPTY_EXPLICIT_PATCH = "0".repeat(64);
	private static final String EXPLICIT_REMOVAL_PATCH = "f".repeat(64);
	private static final String EMPTY_DISPLAY = "{\"id\":\"minecraft:stone\"}";
	private static final String REMOVAL_DISPLAY =
		"{\"id\":\"minecraft:stone\",\"components\":{\"!minecraft:custom_name\":{}}}";
	private static final String DECORATED_DISPLAY =
		"{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":\"decorated\"}}";

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void distinctPatchKeysOfOneItemStaySeparateThroughLiveWitnessAndTracking(int selectedIndex) {
		try (Fixture f = new Fixture()) {
			f.state.items.add(item(STONE, EMPTY_EXPLICIT_PATCH, 2, EMPTY_DISPLAY));
			f.state.items.add(item(STONE, EXPLICIT_REMOVAL_PATCH, 3, REMOVAL_DISPLAY));
			f.open();

			List<InventoryS2CPacket.Entry> previewEntries = f.previewEntries();
			assertEquals(2, previewEntries.size(), "two exact identities of one item ID are two rows");
			InventoryS2CPacket.Entry emptyPatch = f.entry(2);
			InventoryS2CPacket.Entry removal = f.entry(3);
			assertNotEquals(emptyPatch.key(), removal.key(), "distinct exact keys get distinct opaque references");
			assertTrue(previewEntries.stream().allMatch(entry -> entry.itemId().equals(STONE)));
			assertTrue(previewEntries.stream().noneMatch(InventoryS2CPacket.Entry::fallback),
				"explicit patch identities stay exact instead of folding to one aggregate");

			InventoryS2CPacket.Entry selected = selectedIndex == 0 ? emptyPatch : removal;
			f.state.reads.clear();
			f.backend.handle(A, f.select(selected.key(), 1), 1, f.settings);
			assertEquals(InventoryS2CPacket.Kind.SELECTED, f.selected(1).kind());
			assertEquals(1, f.host.store.size());
			assertEquals(List.of(selectedIndex), f.state.reads,
				"the live witness reads the selected variant's own slot, never another row of the same item ID");

			ServerMarker marker = f.marker();
			f.state.items.set(0, item(STONE, EMPTY_EXPLICIT_PATCH, 4, EMPTY_DISPLAY));
			f.state.items.set(1, item(STONE, EXPLICIT_REMOVAL_PATCH, 9, REMOVAL_DISPLAY));
			f.state.reads.clear();
			List<InventoryS2CPacket.Entry> entries = f.tickUntilSnapshot(marker, A, 2, f.state.items.size() + 1);
			assertEquals(1, entries.size(), "tracking publishes only the selected exact identity");
			InventoryS2CPacket.Entry tracked = entries.getFirst();
			assertEquals(selectedIndex == 0 ? 4 : 9, tracked.count(),
				"a fresh tracking capture counts only the selected key, not the other same-item variant");
			assertEquals("selected", tracked.key());
			assertFalse(tracked.fallback());
			assertEquals(List.of(0, 1), f.state.reads, "fresh tracking capture traverses the source once");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void onePatchKeyWithVaryingDisplayFormsStaysOneSelectableRow(boolean displayUnavailable) {
		@Nullable String finalDisplay = displayUnavailable ? null : EMPTY_DISPLAY;
		try (Fixture f = new Fixture()) {
			f.state.items.add(item(STONE, EMPTY_EXPLICIT_PATCH, 2, DECORATED_DISPLAY));
			f.state.items.add(item(STONE, EMPTY_EXPLICIT_PATCH, 3, EMPTY_DISPLAY));
			f.state.items.add(item(STONE, EMPTY_EXPLICIT_PATCH, 4, finalDisplay));
			f.open();

			List<InventoryS2CPacket.Entry> previewEntries = f.previewEntries();
			assertEquals(1, previewEntries.size(),
				"display decoration, ID-only display and an unavailable display are not part of the exact key");
			InventoryS2CPacket.Entry row = previewEntries.getFirst();
			assertEquals(9, row.count());
			assertEquals(finalDisplay, row.displayJson(),
				"the last display form changes the display but not the exact row");
			assertFalse(row.fallback());

			// The live witness sees a display form different from the retained preview row.
			f.state.items.set(0, item(STONE, EMPTY_EXPLICIT_PATCH, 5, DECORATED_DISPLAY));
			f.state.items.set(1, item(STONE, EMPTY_EXPLICIT_PATCH, 6, EMPTY_DISPLAY));
			f.state.items.set(2, item(STONE, EMPTY_EXPLICIT_PATCH, 7, null));
			f.state.reads.clear();
			f.backend.handle(A, f.select(row.key(), 1), 1, f.settings);
			InventoryS2CPacket result = f.selected(1);
			assertEquals(InventoryS2CPacket.Kind.SELECTED, result.kind(),
				() -> "display-only variation changed selection: " + result.rejection());
			assertEquals(List.of(0), f.state.reads, "the live witness still uses the retained exact key's slot");
			assertEquals(1, f.host.store.size());

			ServerMarker marker = f.marker();
			List<InventoryS2CPacket.Entry> entries = f.tickUntilSnapshot(marker, A, 2, f.state.items.size() + 1);
			assertEquals(1, entries.size(), "tracking publishes one selected exact row");
			InventoryS2CPacket.Entry tracked = entries.getFirst();
			assertEquals(18, tracked.count(), "authoritative counts survive a display-only failure");
			assertEquals("selected", tracked.key());
			assertFalse(tracked.fallback());
			assertEquals(finalDisplay, tracked.displayJson(),
				"display unavailable and the ID-only terminal form neither discard nor rewrite the selected count");
		}
	}

	@Test
	void rawPatchKeyIsNotARequestScopedPreviewReference() {
		try (Fixture f = new Fixture()) {
			f.state.items.add(item(STONE, EMPTY_EXPLICIT_PATCH, 2, EMPTY_DISPLAY));
			f.open();
			assertEquals(1, f.previewEntries().size());

			// The raw exact-key bytes are not the server-assigned opaque reference.
			f.backend.handle(A, f.select(EMPTY_EXPLICIT_PATCH, 1), 1, f.settings);
			InventoryS2CPacket result = f.selected(1);
			assertEquals(InventoryS2CPacket.Kind.REJECT, result.kind(),
				"a raw patch key that is not the row's request-scoped opaque reference must not select the row");
			assertEquals(0, f.host.store.size());
		}
	}

	private static InventoryDomainCodec.Item item(String itemId, String componentsKey, long count,
		@Nullable String displayJson) {
		return new InventoryDomainCodec.Item(new InventoryScanner.Key(itemId, componentsKey), count, "stone",
			displayJson, false);
	}

	@NotNullByDefault
	private static final class Host implements InventoryBackend.Host {
		private final List<InventoryS2CPacket> packets = new ArrayList<>();
		private final List<UUID> recipients = new ArrayList<>();
		private final ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());
		private final Map<UUID, InventoryBackend.Policy> policies = new HashMap<>();

		private Host() {
			InventoryBackend.Policy policy = new InventoryBackend.Policy(100, 1, Set.of("entity_block"));
			policies.put(A, policy);
			policies.put(B, policy);
		}

		@Override public Optional<InventoryBackend.Policy> policy(UUID player) {
			return Optional.ofNullable(policies.get(player));
		}

		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) {
			return Optional.of(new InventoryBackend.Opened(TARGET, "entity_block", "attention"));
		}

		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) {
			return "attention".equals(pingType) || "danger".equals(pingType);
		}

		@Override public boolean knows(UUID player, MarkerId marker) {
			return store.allMarkers().stream()
				.anyMatch(stored -> stored.id().equals(marker) && stored.recipients().contains(player));
		}

		@Override public boolean authorized(SyncPublisher.Context context) {
			return true;
		}

		@Override public int encodedBytes(InventoryS2CPacket packet) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				packet.write(buffer);
				return buffer.readableBytes();
			} finally {
				buffer.release();
			}
		}

		@Override public void send(UUID player, InventoryS2CPacket packet) {
			assertFalse(packet.isCorrupt(), packet.toString());
			packets.add(packet);
			recipients.add(player);
		}

		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen,
			InventoryBackend.Admission admission) {
			TargetType type = TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
			var rejection = admission.prepare(frozen.target(), type.id(), List.of(A, B));
			if (rejection != null) return new InventoryBackend.Created(null, rejection);
			ServerMarker marker = store.create(player, frozen.target(), type, type.defaultPingType(),
				new MarkerAnchor(1, 2, 3), 1, 80, List.of(A, B)).marker();
			return new InventoryBackend.Created(marker, null);
		}

		private long epoch(UUID player) {
			for (int index = packets.size() - 1; index >= 0; index--) {
				InventoryS2CPacket packet = packets.get(index);
				if (recipients.get(index).equals(player) && packet.kind() == InventoryS2CPacket.Kind.OFFER) {
					return packet.epoch();
				}
			}
			throw new AssertionError("no OFFER for " + player);
		}
	}

	@NotNullByDefault
	private static final class State {
		private final List<InventoryDomainCodec.Item> items = new ArrayList<>();
		private final List<Integer> reads = new ArrayList<>();
	}

	@NotNullByDefault
	private static final class PatchSource implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final State state;
		private final int displayBytes;

		private PatchSource(InventorySourceInput input, State state, int displayBytes) {
			this.input = input;
			this.state = state;
			this.displayBytes = displayBytes;
		}

		@Override public SourceKey key() {
			return new SourceKey("test", "inventory", "patch-identity-flow", input.viewKey());
		}

		@Override public boolean valid() {
			return true;
		}

		@Override public boolean stableCursor() {
			return true;
		}

		@Override public int slots() {
			return state.items.size();
		}

		/** The resolver supplies the effective display ceiling this source was admitted under. */
		@Override public int displayBytes() {
			return displayBytes;
		}

		/** Synthetic reads retain no source-owned catalogue or parser inputs to tighten. */
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) {
			return InventoryReprojectionSummary.EMPTY;
		}

		@Override public InventoryDomainCodec.Item read(int slot) {
			state.reads.add(slot);
			return state.items.get(slot);
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final Host host = new Host();
		private final InventorySettings settings = InventorySettings.serverDefaults();
		private final State state = new State();
		private final InventoryBackend backend = new InventoryBackend(host, new InventoryRuntime(
			(input, displayBytes) -> Optional.of(new PatchSource(input, state, displayBytes)),
			settings.pendingMemoryBytes()), 1);

		private void hello(UUID player) {
			backend.handle(player, InventoryC2SPacket.hello(), 0, settings);
		}

		private void open() {
			hello(A);
			hello(B);
			backend.handle(A, InventoryC2SPacket.open(host.epoch(A), 100, 1, 1, TARGET, BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
		}

		/**
		 * One fully published preview cut. The publisher sends one entry per part;
		 * a later delta carries only changed keys, so cuts are never mixed.
		 */
		private List<InventoryS2CPacket> previewParts() {
			List<InventoryS2CPacket> data = host.packets.stream()
				.filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.requestId() == 1
					&& !packet.entries().isEmpty())
				.toList();
			if (data.isEmpty()) throw new AssertionError("no preview data");
			long[] watermarks = data.stream().mapToLong(InventoryS2CPacket::watermark).distinct().sorted().toArray();
			for (long watermark : watermarks) {
				List<InventoryS2CPacket> parts = data.stream()
					.filter(packet -> packet.watermark() == watermark)
					.sorted(Comparator.comparingInt(InventoryS2CPacket::partIndex))
					.toList();
				InventoryS2CPacket first = parts.getFirst();
				boolean oneCut = parts.stream().allMatch(packet -> packet.baselineId() == first.baselineId()
					&& packet.statusRevision() == first.statusRevision() && packet.partCount() == first.partCount());
				boolean allParts = parts.size() == first.partCount()
					&& IntStream.range(0, parts.size()).allMatch(index -> parts.get(index).partIndex() == index);
				if (oneCut && allParts && first.completeScan()) return parts;
			}
			throw new AssertionError("no complete preview cut: " + data.stream()
				.map(packet -> "baseline=" + packet.baselineId() + " revision=" + packet.statusRevision()
					+ " watermark=" + packet.watermark() + " part=" + packet.partIndex() + "/" + packet.partCount()
					+ " complete=" + packet.completeScan())
				.toList());
		}

		private List<InventoryS2CPacket.Entry> previewEntries() {
			return previewParts().stream().flatMap(packet -> packet.entries().stream()).toList();
		}

		private InventoryS2CPacket previewFrame() {
			return previewParts().getLast();
		}

		private InventoryS2CPacket.Entry entry(long count) {
			return previewEntries().stream()
				.filter(entry -> entry.count() == count)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no preview entry with count " + count));
		}

		private InventoryC2SPacket select(String entryKey, long commitId) {
			InventoryS2CPacket preview = previewFrame();
			return InventoryC2SPacket.select(host.epoch(A), 100, 1, commitId, 1, preview.baselineId(),
				preview.statusRevision(), entryKey, "danger");
		}

		private InventoryS2CPacket selected(long commitId) {
			return host.packets.stream()
				.filter(packet -> packet.commitId() == commitId
					&& (packet.kind() == InventoryS2CPacket.Kind.SELECTED
						|| packet.kind() == InventoryS2CPacket.Kind.REJECT))
				.reduce((first, second) -> second)
				.orElseThrow(() -> new AssertionError("no selection result for commit " + commitId));
		}

		private ServerMarker marker() {
			return host.store.allMarkers().getFirst();
		}

		private List<InventoryS2CPacket.Entry> snapshotEntries(ServerMarker marker, UUID recipient) {
			List<InventoryS2CPacket.Entry> entries = new ArrayList<>();
			for (int index = 0; index < host.packets.size(); index++) {
				InventoryS2CPacket packet = host.packets.get(index);
				if (host.recipients.get(index).equals(recipient) && packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT
					&& marker.id().equals(packet.markerId())) {
					assertTrue(packet.completeScan(), "tracking snapshot cut must be complete");
					entries.addAll(packet.entries());
				}
			}
			return entries;
		}

		/**
		 * Drives the headless backend until the first tracking snapshot for the
		 * marker is published. The bound allows one productive source slot per
		 * tick plus completion; failure reports the observed reads, never polls.
		 */
		private List<InventoryS2CPacket.Entry> tickUntilSnapshot(ServerMarker marker, UUID recipient, int firstTick, int maxTicks) {
			for (int offset = 0; offset < maxTicks; offset++) {
				backend.tick(firstTick + offset, settings);
				List<InventoryS2CPacket.Entry> entries = snapshotEntries(marker, recipient);
				if (!entries.isEmpty()) return entries;
			}
			throw new AssertionError("no tracking snapshot for " + marker.id() + " within " + maxTicks
				+ " ticks; reads=" + state.reads);
		}

		@Override public void close() {
			backend.close();
		}
	}
}
