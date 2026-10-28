package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Logical quota sharing does not grant physical read authority or share protocol baselines. */
@NotNullByDefault
class CompositeTargetQuotaTest {
	private static final UUID A = InventoryBackendTest.A, B = InventoryBackendTest.B;
	private static final String DIM = "minecraft:overworld";
	private static final Target.BlockTarget LEFT = new Target.BlockTarget(DIM, 1, 2, 3, "minecraft:chest");
	private static final Target.BlockTarget RIGHT = new Target.BlockTarget(DIM, 2, 2, 3, "minecraft:chest");
	private static final TargetKey.DoubleChestKey KEY = new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 2, 2, 3, "minecraft:chest");
	@NotNullByDefault
	private static final class Host extends InventoryBackendTest.Host {
		TargetGroupingResolver grouping = (owner, target) -> target instanceof Target.BlockTarget block
			&& KEY.contains(block.x(), block.y(), block.z()) ? KEY : TargetKey.from(target);
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) {
			return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention"));
		}
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
			TargetKey key = grouping.resolve(player, frozen.target());
			@Nullable MarkerRejectReason rejection = admission.prepare(frozen.target(), key, type.id(), List.of(A, B));
			if (rejection != null) return new InventoryBackend.Created(null, rejection);
			return new InventoryBackend.Created(store.create(player, frozen.target(), key, type, type.defaultPingType(),
				new MarkerAnchor(2.5, 2.5, 3.5), 1, 1000, List.of(A, B)).marker(), null);
		}
	}
	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final Host host = new Host();
		final InventorySettings settings = InventorySettings.serverDefaults();
		final AtomicInteger reads = new AtomicInteger();
		final List<InventorySourceInput> readInputs = new ArrayList<>();
		String item = "minecraft:stone";
		long amount = 7, now;
		final InventoryBackend backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
			@Override public SourceKey key() { return new SourceKey("test", "inventory", "both-halves", input.viewKey()); }
			@Override public InventoryDomainCodec.@Nullable Item read(int slot) {
				reads.incrementAndGet(); readInputs.add(input);
				return slot == 0 ? new InventoryDomainCodec.Item(new InventoryScanner.Key(item, "plain"), amount, "item", null, false) : null;
			}
		}), 64_000_000), 1);
		Fixture() { settings.setPendingMemoryMiB(64); hello(A); hello(B); }
		void hello(UUID owner) { backend.handle(owner, InventoryC2SPacket.hello(), now, settings); }
		void tick() { backend.tick(now, settings); }
		InventoryS2CPacket open(UUID owner, long request, Target target, BlockFace face) {
			backend.handle(owner, InventoryC2SPacket.open(host.epoch(owner), 100, 1, request, target, face), now, settings); tick();
			return host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.epoch() == host.epoch(owner) && p.requestId() == request && !p.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
		}
		InventoryS2CPacket select(UUID owner, long request, long commit, InventoryS2CPacket preview) {
			backend.handle(owner, InventoryC2SPacket.select(host.epoch(owner), 100, 1, commit, request,
				preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), now, settings);
			return host.packets.stream().filter(p -> p.epoch() == host.epoch(owner) && p.commitId() == commit
				&& (p.kind() == InventoryS2CPacket.Kind.SELECTED || p.kind() == InventoryS2CPacket.Kind.REJECT)).reduce((a, b) -> b).orElseThrow();
		}
		void close(UUID owner, long request) { backend.handle(owner, InventoryC2SPacket.close(host.epoch(owner), request).stamp(100, 1), now, settings); }
		@Override public void close() { backend.close(); }
	}

	@Test void activeVariantCategoriesShareGroupAcrossHitsButRepeatedCategoryIsNotAnotherClaim() {
		try (var f = new Fixture()) {
			f.settings.getTracking().setMaxVariantsPerTarget(IntLimit.finite(1));
			var first = f.open(A, 1, LEFT, BlockFace.NORTH);
			assertEquals(InventoryS2CPacket.Kind.SELECTED, f.select(A, 1, 1, first).kind()); f.close(A, 1);
			f.now = 3; var repeat = f.open(A, 2, RIGHT, BlockFace.NORTH);
			assertEquals(InventoryS2CPacket.Kind.SELECTED, f.select(A, 2, 2, repeat).kind()); f.close(A, 2);
			f.item = "minecraft:dirt"; f.now = 6; var different = f.open(A, 3, RIGHT, BlockFace.NORTH);
			assertEquals(InventoryS2CPacket.Kind.REJECT, f.select(A, 3, 3, different).kind()); f.close(A, 3);
			assertEquals(2, f.host.store.size());
			assertTrue(f.host.store.allMarkers().stream().allMatch(m -> m.targetKey().equals(KEY)));
			f.now = 9;
			var outside = f.open(A, 4, new Target.BlockTarget(DIM, 3, 2, 3, "minecraft:chest"), BlockFace.NORTH);
			assertEquals(InventoryS2CPacket.Kind.SELECTED, f.select(A, 4, 4, outside).kind(), "a genuinely different target has its own capacity");
		}
	}

	@Test void vaultControllerQuotaCarrierDoesNotReplaceOriginalMemberReadBinding() throws Exception {
		try (var f = new Fixture()) {
			var key = new TargetKey.VaultKey(DIM, -4, 2, 3, "create:item_vault");
			f.host.grouping = (owner, target) -> key;
			f.settings.getTracking().setMaxVariantsPerTarget(IntLimit.finite(1));
			var member = new Target.BlockTarget(DIM, 2, 2, 3, "create:item_vault");
			var first = f.open(A, 1, member, BlockFace.NORTH); f.select(A, 1, 1, first); f.close(A, 1);
			f.now = 3; f.item = "minecraft:dirt";
			var second = f.open(B, 1, new Target.BlockTarget(DIM, -3, 2, 3, "create:item_vault"), BlockFace.SOUTH);
			assertEquals(InventoryS2CPacket.Kind.REJECT, f.select(B, 1, 1, second).kind());
			var marker = f.host.store.allMarkers().getFirst(); assertEquals(key, marker.targetKey()); assertEquals(member, marker.target());
			Object lease = ((Map<?, ?>) InventoryBackendTest.field(f.backend, "tracking")).values().iterator().next();
			Object consumer = InventoryBackendTest.field(lease, "consumer");
			assertEquals(new InventoryRuntime.TrackingSubject(key), InventoryBackendTest.field(consumer, "subject"));
			assertEquals(new InventorySourceInput(member, A, BlockFace.NORTH), InventoryBackendTest.field(lease, "input"));
		}
	}

	@Test void backendCarriesGroupIntoTrackingSubjectRoutesAndRebaselineWithoutChangingOwnerFaceOrHit() throws Exception {
		try (var f = new Fixture()) {
			var first = f.open(A, 1, LEFT, BlockFace.NORTH); f.select(A, 1, 1, first); f.close(A, 1);
			f.now = 3; var second = f.open(B, 1, RIGHT, BlockFace.SOUTH); f.select(B, 1, 1, second); f.close(B, 1);
			f.now = 4; f.tick();
			assertEquals(2, f.host.store.size());
			var tracking = (Map<?, ?>) InventoryBackendTest.field(f.backend, "tracking");
			for (Object lease : tracking.values()) {
				var marker = (ServerMarker) InventoryBackendTest.field(lease, "marker");
				var input = (InventorySourceInput) InventoryBackendTest.field(lease, "input");
				Object consumer = InventoryBackendTest.field(lease, "consumer");
				assertEquals(new InventoryRuntime.TrackingSubject(KEY), InventoryBackendTest.field(consumer, "subject"));
				assertEquals(marker.target(), input.target()); assertEquals(marker.owner(), input.readOwner());
				assertEquals(marker.owner().equals(A) ? BlockFace.NORTH : BlockFace.SOUTH, input.face());
			}
			assertTrue(f.readInputs.stream().anyMatch(i -> i.target().equals(LEFT) && i.readOwner().equals(A) && i.face() == BlockFace.NORTH));
			assertTrue(f.readInputs.stream().anyMatch(i -> i.target().equals(RIGHT) && i.readOwner().equals(B) && i.face() == BlockFace.SOUTH));
			var publisher = InventoryBackendTest.field(f.backend, "publisher");
			assertRoutes(publisher);
			var id = f.host.store.allMarkers().getFirst().id();
			long oldBaseline = f.host.packets.stream().filter(p -> id.equals(p.markerId()) && p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).findFirst().orElseThrow().baselineId();
			f.now = 50; f.backend.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 0, id).stamp(100, 1), f.now, f.settings); f.tick();
			assertRoutes(publisher);
			assertTrue(f.host.packets.stream().anyMatch(p -> id.equals(p.markerId()) && p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && p.baselineId() != oldBaseline));
			f.amount = 10; f.now = 55; f.tick(); f.now = 58; f.tick();
			var bytes = (Map<?, ?>) InventoryBackendTest.field(publisher, "streamBytes");
			assertFalse(bytes.isEmpty(), "real stream sends must exercise target accounting");
			assertEquals(Set.of(A + "/" + KEY, B + "/" + KEY), bytes.keySet(), "different marker IDs/hits share each recipient's group byte ledger");
			assertTrue(bytes.values().stream().allMatch(value -> (long) value > 0));
		}
	}
	private static void assertRoutes(Object publisher) throws Exception {
		var states = (Map<?, ?>) InventoryBackendTest.field(publisher, "states");
		var baselines = new java.util.HashSet<Long>(); int tracking = 0;
		for (Object state : states.values()) {
			var route = (InventorySyncPublisher.Route) InventoryBackendTest.field(state, "route");
			if (route.markerId() == null) continue;
			tracking++; assertEquals(KEY.toString(), route.targetQuota());
			var context = (nx.pingwheel.common.presentation.source.SyncPublisher.Context) InventoryBackendTest.field(state, "context");
			assertTrue(baselines.add(context.baseline()), "shared quota must not share Ping+recipient baselines");
		}
		assertEquals(4, tracking);
	}

	@Test void groupedLogicalSlotQuotaSpansDifferentPhysicalReadViews() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		var reads = new AtomicInteger(); var inputs = new ArrayList<InventorySourceInput>();
		try (var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 3) {
			@Override public InventoryDomainCodec.@Nullable Item read(int slot) { inputs.add(input); return super.read(slot); }
		}), 16_000_000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(new InventorySourceInput(LEFT, A, BlockFace.NORTH), new InventoryRuntime.TrackingSubject(KEY)).orElseThrow();
			var b = runtime.attach(new InventorySourceInput(RIGHT, B, BlockFace.SOUTH), new InventoryRuntime.TrackingSubject(KEY)).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size());
			assertTrue(runtime.step(b).isEmpty(), "new owner/face/hit is not another logical target allowance");
			assertEquals(1, reads.get());
			runtime.advance(settings.getTracking().getPeriodTicks(), settings);
			assertEquals(1, runtime.step(b).orElseThrow().slots().size());
			assertEquals(new InventorySourceInput(RIGHT, B, BlockFace.SOUTH), inputs.getLast());
		}
	}
}
