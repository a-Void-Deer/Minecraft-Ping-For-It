package nx.pingwheel.common.presentation.inventory;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Mixed-domain regressions exercise the one Backend/Runtime and shared admission, not a second engine. */
@NotNullByDefault
class InventoryResourceLanesTest {
	@NotNullByDefault
	private static final class State {
		final AtomicInteger reads = new AtomicInteger(), resolutions = new AtomicInteger();
		boolean valid = true, fail, aggregate;
		boolean alternateFallback;
		long amount = 81000;
		int slots = 1;
		Runnable afterValidation = () -> {};
	}
	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final InventoryBackendTest.Host host;
		final InventorySettings settings = InventorySettings.serverDefaults();
		final EnumMap<InventoryResourceKind, State> states = new EnumMap<>(InventoryResourceKind.class);
		final InventoryRuntime runtime;
		final InventoryBackend backend;
		Fixture() { this(new InventoryBackendTest.Host()); }
		Fixture(InventoryBackendTest.Host host) {
			this.host = host;
			for (var kind : InventoryResourceKind.values()) states.put(kind, new State());
			host.policies.replaceAll((player, prior) -> new InventoryBackend.Policy(100, 1, Set.of("entity_block"), Set.of("entity_block")));
			runtime = new InventoryRuntime(input -> {
				State state = states.get(input.resourceKind()); state.resolutions.incrementAndGet();
				return Optional.of(new InventorySourceAccess.Source() {
					@Override public SourceKey key() { return new SourceKey("test", "mixed", "same-root", input.viewKey()); }
					@Override public boolean valid() { state.afterValidation.run(); return state.valid; }
					@Override public boolean stableCursor() { return true; }
					@Override public int slots() { return state.slots; }
					@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
					@Override public InventoryDomainCodec.@Nullable Item read(int slot) {
						if (input.resourceKind() != InventoryResourceKind.ITEM) throw new AssertionError("FLUID entered Item read");
						return (InventoryDomainCodec.@Nullable Item) value();
					}
					private @Nullable InventoryResourceValue value() {
						state.reads.incrementAndGet();
						if (state.fail) throw new IllegalStateException("authoritative getter failed");
						if (state.amount == 0) return null;
						String id = input.resourceKind() == InventoryResourceKind.ITEM ? "minecraft:stone" : "minecraft:water";
						boolean aggregate = state.aggregate || state.alternateFallback && state.reads.get() % 2 == 0;
						return InventoryResourceCodec.value(new InventoryScanner.Key(input.resourceKind(), id, aggregate ? "" : "native"),
							state.amount, id, null, aggregate);
					}
					@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
						return value();
					}
				});
			}, settings.pendingMemoryBytes());
			backend = new InventoryBackend(host, runtime, 1);
		}
		void open() {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.B, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
		}
		InventoryS2CPacket preview(InventoryResourceKind kind) {
			return host.packets.stream().filter(p -> p.resourceKind() == kind && p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty())
				.reduce((a, b) -> b).orElseThrow();
		}
		InventoryC2SPacket select(InventoryResourceKind kind, long commit, InventoryS2CPacket preview) {
			return InventoryC2SPacket.select(kind, host.epoch(InventoryBackendTest.A), 100, 1, commit, 1,
				preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "attention");
		}
		@Unmodifiable List<InventoryS2CPacket> tracking() {
			return host.packets.stream().filter(p -> p.markerId() != null && !p.entries().isEmpty()).toList();
		}
		@Override public void close() { backend.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved()); }
	}

	@Test void oneOpenHasIndependentBaselinesCompletionAndOneTargetQuota() {
		try (var f = new Fixture()) {
			f.settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(1)); f.open();
			var item = f.preview(InventoryResourceKind.ITEM); var fluid = f.preview(InventoryResourceKind.FLUID);
			assertNotEquals(item.baselineId(), fluid.baselineId());
			assertNotEquals(item.entries().getFirst().key(), fluid.entries().getFirst().key());
			assertTrue(item.completeScan()); assertTrue(fluid.completeScan());
			assertEquals(1, f.states.get(InventoryResourceKind.ITEM).reads.get());
			assertEquals(1, f.states.get(InventoryResourceKind.FLUID).reads.get());
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(f.host.epoch(InventoryBackendTest.A), 100, 1, 2,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 1, f.settings);
			f.backend.tick(1, f.settings);
			assertTrue(f.host.packets.stream().noneMatch(p -> p.requestId() == 2));
		}
	}
	@Test void siblingPolicyChangesPreserveCompleteAndInProgressTrackingBaselineAndQueuedCutBothDirections() {
		for (var kind : InventoryResourceKind.values()) for (boolean partial : new boolean[] {false, true}) try (var f = new Fixture()) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(kind, 1, f.preview(kind)), 1, f.settings);
			if (partial) { f.states.get(kind).slots = 8; f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); }
			f.backend.tick(2, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			var before = f.host.packets.stream().filter(packet -> marker.id().equals(packet.markerId())).reduce((a, b) -> b).orElseThrow();
			int start = f.host.packets.size();
			var allowed = Set.of("entity_block");
			f.host.policies.replaceAll((player, policy) -> new InventoryBackend.Policy(100, 1,
				kind == InventoryResourceKind.ITEM ? allowed : Set.of(), kind == InventoryResourceKind.FLUID ? allowed : Set.of()));
			InventoryPublicationTicks.withoutScanSlots(f.backend, 3, f.settings);
			for (var packet : f.host.packets.subList(start, f.host.packets.size())) if (marker.id().equals(packet.markerId())) {
				assertEquals(before.baselineId(), packet.baselineId(), "sibling policy cannot reset baseline");
				assertEquals(before.statusRevision(), packet.statusRevision()); assertNotEquals(InventoryS2CPacket.Status.INVALID, packet.status());
			}
			start = f.host.packets.size(); f.host.policies.replaceAll((player, policy) -> new InventoryBackend.Policy(100, 1, allowed, allowed));
			InventoryPublicationTicks.withoutScanSlots(f.backend, 4, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().filter(packet -> marker.id().equals(packet.markerId()))
				.allMatch(packet -> packet.baselineId() == before.baselineId()));
			f.host.policies.replaceAll((player, policy) -> new InventoryBackend.Policy(100, 1,
				kind == InventoryResourceKind.ITEM ? Set.of() : allowed, kind == InventoryResourceKind.FLUID ? Set.of() : allowed));
			start = f.host.packets.size(); InventoryPublicationTicks.withoutScanSlots(f.backend, 5, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(packet -> marker.id().equals(packet.markerId())));
		}
	}
	@Test void deniedFluidNeverResolvesWhileItemCompletes() {
		try (var f = new Fixture()) {
			f.host.policies.replaceAll((player, prior) -> new InventoryBackend.Policy(100, 1, Set.of("entity_block"), Set.of()));
			f.open(); assertTrue(f.preview(InventoryResourceKind.ITEM).completeScan());
			assertEquals(0, f.states.get(InventoryResourceKind.FLUID).resolutions.get());
			assertEquals(0, f.states.get(InventoryResourceKind.FLUID).reads.get());
		}
	}
	@Test void invalidFluidDoesNotRetireItemAndDoesNotRecoverWithoutReopen() {
		try (var f = new Fixture()) {
			f.states.get(InventoryResourceKind.FLUID).valid = false; f.open();
			assertTrue(f.preview(InventoryResourceKind.ITEM).completeScan());
			assertTrue(f.host.packets.stream().anyMatch(p -> p.resourceKind() == InventoryResourceKind.FLUID && p.status() == InventoryS2CPacket.Status.INVALID));
			f.states.get(InventoryResourceKind.FLUID).valid = true; f.backend.tick(5, f.settings);
			assertEquals(0, f.states.get(InventoryResourceKind.FLUID).reads.get());
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.ITEM, 1, f.preview(InventoryResourceKind.ITEM)), 5, f.settings);
			assertEquals(1, f.host.store.size());
		}
	}
	@Test void crossLaneTokenFailsSharedAttemptAndReplayCannotChangeLane() {
		try (var f = new Fixture()) {
			f.open(); var item = f.preview(InventoryResourceKind.ITEM); var fluid = f.preview(InventoryResourceKind.FLUID);
			int before = f.states.values().stream().mapToInt(s -> s.reads.get()).sum();
			var wrong = InventoryC2SPacket.select(InventoryResourceKind.FLUID, f.host.epoch(InventoryBackendTest.A), 100, 1, 1, 1,
				fluid.baselineId(), fluid.statusRevision(), item.entries().getFirst().key(), "attention");
			f.backend.handle(InventoryBackendTest.A, wrong, 1, f.settings);
			assertEquals(0, f.host.store.size());
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.ITEM, 1, item), 1, f.settings);
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.ITEM, 2, item), 1, f.settings);
			assertEquals(0, f.host.store.size(), "one parent owns the failed attempt across both lanes");
			assertEquals(before, f.states.values().stream().mapToInt(s -> s.reads.get()).sum(), "SELECT never performs a presence getter");
		}
	}
	@Test void retainedFluidSeedAndFreshEqualAmountHaveDistinctRevisionsAndFixedKind() {
		try (var f = new Fixture()) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 1, f.preview(InventoryResourceKind.FLUID)), 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			assertEquals(Optional.of(InventoryResourceKind.FLUID), f.backend.trackedKind(marker.id()));
			assertEquals(1, f.states.get(InventoryResourceKind.FLUID).reads.get());
			InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.settings);
			var seed = f.tracking().getFirst(); assertFalse(seed.completeScan()); assertEquals(81000, seed.entries().getFirst().amount());
			f.backend.tick(2, f.settings);
			var fresh = f.tracking().stream().filter(InventoryS2CPacket::completeScan).findFirst().orElseThrow();
			assertEquals(seed.entries().getFirst().amount(), fresh.entries().getFirst().amount());
			assertTrue(fresh.entries().getFirst().resourceRevision() > seed.entries().getFirst().resourceRevision());
			assertEquals(2, f.states.get(InventoryResourceKind.FLUID).reads.get());
			assertEquals(1, f.states.get(InventoryResourceKind.ITEM).reads.get());
			assertTrue(f.tracking().stream().allMatch(p -> p.resourceKind() == InventoryResourceKind.FLUID));
		}
	}
	@Test void sharedPhysicalAndLogicalBudgetsChargeBothDomains() {
		try (var f = new Fixture()) {
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); f.open();
			assertEquals(1, f.states.values().stream().mapToInt(s -> s.reads.get()).sum());
			for (int tick = 1; tick <= f.settings.getPreview().getPeriodTicks(); tick++) f.backend.tick(tick, f.settings);
			assertTrue(f.preview(InventoryResourceKind.ITEM).completeScan()); assertTrue(f.preview(InventoryResourceKind.FLUID).completeScan());
			assertEquals(2, f.states.values().stream().mapToInt(s -> s.reads.get()).sum());
		}
	}
	@Test void getterFailureAndOverflowNeverPublishCompleteZero() {
		for (boolean overflow : new boolean[] {false, true}) try (var f = new Fixture()) {
			State fluid = f.states.get(InventoryResourceKind.FLUID);
			fluid.fail = !overflow; fluid.amount = Long.MAX_VALUE; fluid.slots = overflow ? 2 : 1;
			f.open(); assertTrue(f.preview(InventoryResourceKind.ITEM).completeScan());
			assertTrue(f.host.packets.stream().filter(p -> p.resourceKind() == InventoryResourceKind.FLUID)
				.noneMatch(p -> p.completeScan() && p.entries().stream().anyMatch(e -> e.amount() == 0)));
			assertTrue(f.host.packets.stream().filter(p -> p.resourceKind() == InventoryResourceKind.FLUID)
				.noneMatch(p -> p.completeScan() && p.kind() == InventoryS2CPacket.Kind.PREVIEW));
		}
	}
	@Test void revokedFluidRecipientCannotReceiveQueuedSeedOrFreshData() {
		try (var f = new Fixture()) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 1, f.preview(InventoryResourceKind.FLUID)), 1, f.settings);
			f.host.policies.put(InventoryBackendTest.B, new InventoryBackend.Policy(100, 2, Set.of("entity_block"), Set.of()));
			int start = f.host.packets.size(); f.backend.tick(2, f.settings);
			for (int index = start; index < f.host.packets.size(); index++) if (f.host.recipients.get(index).equals(InventoryBackendTest.B))
				assertTrue(f.host.packets.get(index).entries().isEmpty(), "revoked recipient received fluid contents");
			assertTrue(f.tracking().stream().anyMatch(InventoryS2CPacket::completeScan));
		}
	}
	@Test void liveIdentityRefusalRetiresOnlyFluidExactTokenAndAggregatesTheWholeId() {
		try (var f = new Fixture()) {
			State fluid = f.states.get(InventoryResourceKind.FLUID); fluid.slots = 2; fluid.alternateFallback = true;
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); f.open();
			for (int tick = 1; tick <= 2; tick++) f.backend.tick(tick, f.settings);
			var fluidPackets = f.host.packets.stream().filter(p -> p.resourceKind() == InventoryResourceKind.FLUID && !p.entries().isEmpty()).toList();
			var exact = fluidPackets.getFirst(); assertFalse(exact.entries().getFirst().fallback());
			var folded = f.preview(InventoryResourceKind.FLUID); assertTrue(folded.entries().getFirst().fallback());
			assertEquals(162000, folded.entries().getFirst().amount()); assertNotEquals(exact.entries().getFirst().key(), folded.entries().getFirst().key());
			assertFalse(f.preview(InventoryResourceKind.ITEM).entries().getFirst().fallback());
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 1, exact), 3, f.settings);
			assertEquals(0, f.host.store.size(), "the retired exact token cannot commit");
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 2, folded), 3, f.settings);
			assertEquals(1, f.host.store.size(), "retired-token rejection does not consume the replacement cut's attempt");
		}
	}
	@Test void sameViewFieldRevocationPreservesSiblingAndReallowFreshlyAdmitsOnlyItsLane() {
		try (var f = new Fixture()) {
			f.open(); var item = f.preview(InventoryResourceKind.ITEM);
			f.host.policies.put(InventoryBackendTest.A, new InventoryBackend.Policy(100, 1, Set.of("entity_block"), Set.of()));
			f.backend.tick(1, f.settings);
			f.host.policies.put(InventoryBackendTest.A, new InventoryBackend.Policy(100, 1, Set.of("entity_block"), Set.of("entity_block")));
			f.backend.tick(2, f.settings); assertEquals(2, f.states.get(InventoryResourceKind.FLUID).reads.get());
			assertEquals(1, f.states.get(InventoryResourceKind.ITEM).reads.get());
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.ITEM, 1, item), 2, f.settings);
			assertEquals(1, f.host.store.size());
		}
	}
	@Test void fluidInvalidationPermanentlyRetiresSeedAndRecoveryStopsAtMarkerExpiry() {
		try (var f = new Fixture()) {
			f.host.expiry = 12; f.open(); f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 1, f.preview(InventoryResourceKind.FLUID)), 1, f.settings);
			State fluid = f.states.get(InventoryResourceKind.FLUID); fluid.valid = false; f.backend.tick(2, f.settings);
			assertTrue(f.host.packets.stream().anyMatch(p -> p.resourceKind() == InventoryResourceKind.FLUID && p.markerId() != null && p.status() == InventoryS2CPacket.Status.INVALID));
			fluid.valid = true; fluid.amount = 0; f.backend.tick(6, f.settings);
			assertTrue(f.tracking().stream().anyMatch(p -> p.completeScan() && p.entries().getFirst().amount() == 0));
			assertTrue(f.tracking().stream().noneMatch(p -> !p.completeScan() && p.entries().getFirst().amount() == 81000), "recovery never restores the withdrawn seed");
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(f.host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 7, f.settings);
			int reads = fluid.reads.get(); int resolves = fluid.resolutions.get(); f.backend.tick(12, f.settings); f.backend.tick(30, f.settings);
			assertEquals(reads, fluid.reads.get()); assertEquals(resolves, fluid.resolutions.get());
			assertTrue(f.backend.trackedKind(f.host.store.allMarkers().getFirst().id()).isEmpty());
		}
	}
	@Test void finalFluidProofRechecksRecipientFieldRevocationWithoutRetiringAuthorizedItemSibling() {
		@NotNullByDefault
		class Host extends InventoryBackendTest.Host {
			Runnable duringProof = () -> {};
			@Override public InventorySyncPublisher.SendDecision beforeSend(nx.pingwheel.common.presentation.source.SyncPublisher.Context context,
				InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) {
				if (context.recipient().equals(InventoryBackendTest.B) && packet.resourceKind() == InventoryResourceKind.FLUID
					&& packet.markerId() != null && InventorySyncPublisher.requiresSource(packet)) duringProof.run();
				return InventorySyncPublisher.SendDecision.ALLOW;
			}
		}
		var host = new Host();
		try (var f = new Fixture(host)) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 1, f.preview(InventoryResourceKind.FLUID)), 1, f.settings);
			State fluid = f.states.get(InventoryResourceKind.FLUID);
			host.duringProof = () -> fluid.afterValidation = () -> host.policies.put(InventoryBackendTest.B,
				new InventoryBackend.Policy(100, 1, Set.of("entity_block"), Set.of()));
			int start = host.packets.size(); InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.settings);
			for (int index = start; index < host.packets.size(); index++) if (host.recipients.get(index).equals(InventoryBackendTest.B))
				assertTrue(host.packets.get(index).entries().isEmpty(), "field authority changed inside final source proof");
			assertTrue(f.tracking().stream().anyMatch(packet -> packet.entries().getFirst().amount() == 81000));
			assertEquals(1, fluid.reads.get(), "proof does not acquire another content value");
			assertTrue(f.preview(InventoryResourceKind.ITEM).completeScan());
		}
	}
	@Test void closeReleasesBothLanesAndDelayedLaneAdmissionRetriesOnlyWhileParentIsOpen() {
		try (var f = new Fixture()) {
			f.open(); var firstItem = f.preview(InventoryResourceKind.ITEM); var firstFluid = f.preview(InventoryResourceKind.FLUID);
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(f.host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 1, f.settings);
			int itemReads = f.states.get(InventoryResourceKind.ITEM).reads.get(), fluidReads = f.states.get(InventoryResourceKind.FLUID).reads.get();
			int frames = f.host.packets.size(); f.backend.tick(3, f.settings);
			assertEquals(itemReads, f.states.get(InventoryResourceKind.ITEM).reads.get());
			assertEquals(fluidReads, f.states.get(InventoryResourceKind.FLUID).reads.get());
			assertTrue(f.host.packets.subList(frames, f.host.packets.size()).stream().noneMatch(packet -> packet.requestId() == 1));
			var memory = f.runtime.memory(); long retained = memory.retained(); memory.setCap(retained + 4096);
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(f.host.epoch(InventoryBackendTest.A), 100, 1, 2,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 3, f.settings);
			memory.setCap(f.settings.pendingMemoryBytes()); f.backend.tick(4, f.settings);
			assertTrue(f.host.packets.stream().anyMatch(packet -> packet.requestId() == 2 && packet.resourceKind() == InventoryResourceKind.ITEM && packet.completeScan()));
			assertTrue(f.host.packets.stream().anyMatch(packet -> packet.requestId() == 2 && packet.resourceKind() == InventoryResourceKind.FLUID && packet.completeScan()));
			assertNotEquals(firstItem.baselineId(), f.preview(InventoryResourceKind.ITEM).baselineId());
			assertNotEquals(firstFluid.baselineId(), f.preview(InventoryResourceKind.FLUID).baselineId());
		}
	}
	@Test void wrongKindResyncCannotResetFixedFluidTrackingBaselineOrConsumeItsRepairCooldown() {
		try (var f = new Fixture()) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 1, f.preview(InventoryResourceKind.FLUID)), 1, f.settings);
			InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.settings); f.backend.tick(2, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			long baseline = f.tracking().stream().filter(packet -> f.host.recipients.get(f.host.packets.indexOf(packet)).equals(InventoryBackendTest.A))
				.reduce((left, right) -> right).orElseThrow().baselineId();
			int start = f.host.packets.size();
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(InventoryResourceKind.ITEM, f.host.epoch(InventoryBackendTest.A), 0, marker.id()).stamp(100, 1), 3, f.settings);
			InventoryPublicationTicks.withoutScanSlots(f.backend, 3, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().filter(packet -> marker.id().equals(packet.markerId()))
				.allMatch(packet -> packet.baselineId() == baseline));
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(InventoryResourceKind.FLUID, f.host.epoch(InventoryBackendTest.A), 0, marker.id()).stamp(100, 1), 4, f.settings);
			InventoryPublicationTicks.withoutScanSlots(f.backend, 4, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().anyMatch(packet -> marker.id().equals(packet.markerId())
				&& packet.resourceKind() == InventoryResourceKind.FLUID && packet.baselineId() > baseline));
		}
	}
	@Test void successfulFluidCommitReplayCannotCreateItemOrEmitCrossLaneOutcome() {
		try (var f = new Fixture()) {
			f.open(); var fluid = f.preview(InventoryResourceKind.FLUID); var item = f.preview(InventoryResourceKind.ITEM);
			var selected = f.select(InventoryResourceKind.FLUID, 1, fluid);
			f.backend.handle(InventoryBackendTest.A, selected, 1, f.settings); assertEquals(1, f.host.store.size());
			int start = f.host.packets.size();
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.ITEM, 1, item), 1, f.settings);
			assertEquals(start, f.host.packets.size(), "same commit id cannot replay a different lane's correlated result");
			f.backend.handle(InventoryBackendTest.A, selected, 1, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.SELECTED
				&& packet.resourceKind() == InventoryResourceKind.FLUID && packet.commitId() == 1));
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.ITEM, 2, item), 1, f.settings);
			assertEquals(1, f.host.store.size()); assertEquals(1, f.states.get(InventoryResourceKind.ITEM).reads.get());
			assertEquals(1, f.states.get(InventoryResourceKind.FLUID).reads.get());
		}
	}
	@Test void fluidSidecarIsInstalledBeforeCreatedProjectionWithoutAnUnbudgetedFirstFrame() {
		@NotNullByDefault
		class Host extends InventoryBackendTest.Host {
			InventoryBackend.@Nullable Created projected;
			@Nullable InventoryBackend backend;
			int createdCalls;
			@Override public void publishCreated(InventoryBackend.Created created) {
				createdCalls++; projected = created;
				assertEquals(Optional.of(InventoryResourceKind.FLUID), java.util.Objects.requireNonNull(backend).trackedKind(created.marker().id()));
				assertTrue(packets.stream().noneMatch(packet -> created.marker().id().equals(packet.markerId()) && !packet.entries().isEmpty()),
					"CREATED projection precedes the first budgeted tracking frame");
			}
		}
		var host = new Host();
		try (var f = new Fixture(host)) {
			host.backend = f.backend; f.open();
			f.backend.handle(InventoryBackendTest.A, f.select(InventoryResourceKind.FLUID, 1, f.preview(InventoryResourceKind.FLUID)), 1, f.settings);
			assertEquals(1, host.createdCalls); assertNotNull(host.projected);
			assertTrue(f.tracking().isEmpty(), "SELECT drains only correlated terminal controls, not a free seed send");
			assertEquals(1, f.states.get(InventoryResourceKind.FLUID).reads.get());
			InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.settings);
			assertTrue(f.tracking().stream().allMatch(packet -> packet.resourceKind() == InventoryResourceKind.FLUID && !packet.completeScan()));
			assertFalse(f.tracking().isEmpty());
		}
	}
}
