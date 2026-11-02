package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real common-world FLUID source through server publication; client data barrier is B-owned. */
@NotNullByDefault
class InventoryFluidScopeFramesTest {
	@org.junit.jupiter.api.BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void scopeOnlyChangeFencesOldCutWithoutInvalidOrPartialAndPublishesFreshComplete() {
		try (var world = new InventoryFluidWorldTest.Fixture(2, 1, true)) {
			@NotNullByDefault
			class Host extends InventoryBackendTest.Host {
				@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) {
					return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention"));
				}
				@Override public Optional<InventorySourceInput> bindCommitted(InventorySourceInput input, Target committed) {
					return Optional.of(new InventorySourceInput(committed, input.readOwner(), input.face(), input.resourceKind()));
				}
				@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
					var type = nx.pingwheel.common.domain.TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
					var reason = admission.prepare(world.target, nx.pingwheel.common.marker.TargetKey.from(world.target), "entity_block", List.of(player));
					if (reason != null) return new InventoryBackend.Created(null, reason);
					return new InventoryBackend.Created(store.create(player, world.target, type, type.defaultPingType(),
						new nx.pingwheel.common.marker.MarkerAnchor(1, 64, 3), 1, 80, List.of(player)).marker(), null);
				}
			}
			var host = new Host(); host.policies.replaceAll((player, policy) -> new InventoryBackend.Policy(100, 1, Set.of(), Set.of("entity_block")));
			try (var backend = new InventoryBackend(host, world.runtime, 1)) {
				Target candidate = Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:chest", "locator", true);
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, world.settings);
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, candidate, BlockFace.NORTH), 0, world.settings);
				backend.tick(0, world.settings);
				var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).reduce((a,b) -> b).orElseThrow();
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(InventoryResourceKind.FLUID, host.epoch(InventoryBackendTest.A), 100, 1, 1, 1,
					preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "attention"), 1, world.settings);
				assertEquals(1, host.store.size()); var marker = host.store.allMarkers().getFirst();
				backend.tick(2, world.settings);
				var prior = host.packets.stream().filter(packet -> marker.id().equals(packet.markerId()) && packet.completeScan() && !packet.entries().isEmpty()).reduce((a,b)->b).orElseThrow();
				assertEquals(162, prior.entries().getFirst().amount());
				int start = host.packets.size(); world.scope = "two"; world.amount = 243; world.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
				backend.tick(3, world.settings);
				var transition = host.packets.subList(start, host.packets.size()).stream().filter(packet -> marker.id().equals(packet.markerId())).toList();
				assertTrue(transition.stream().noneMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID || !packet.entries().isEmpty()));
				var fence = transition.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.STATUS).findFirst().orElseThrow();
				assertEquals(InventoryS2CPacket.Status.UPDATING, fence.status()); assertEquals(0, fence.watermark());
				assertTrue(fence.baselineId() > prior.baselineId()); assertTrue(fence.statusRevision() > prior.statusRevision());
				backend.tick(4, world.settings); // first fresh slot; no partial subtotal
				assertTrue(host.packets.subList(start, host.packets.size()).stream().noneMatch(packet -> marker.id().equals(packet.markerId()) && !packet.entries().isEmpty()));
				backend.tick(5, world.settings);
				var fresh = host.packets.subList(start, host.packets.size()).stream().filter(packet -> marker.id().equals(packet.markerId()) && !packet.entries().isEmpty()).findFirst().orElseThrow();
				assertEquals(InventoryS2CPacket.Kind.SNAPSHOT, fresh.kind()); assertTrue(fresh.completeScan()); assertEquals(486, fresh.entries().getFirst().amount());
				assertEquals(fence.baselineId(), fresh.baselineId()); assertEquals(fence.statusRevision(), fresh.statusRevision()); assertTrue(fresh.watermark() > 0);
				assertEquals(80, host.store.allMarkers().getFirst().expiresAtTick());
			}
		}
	}
	@NotNullByDefault
	private static final class Scenario implements AutoCloseable {
		final InventoryFluidWorldTest.Fixture world = new InventoryFluidWorldTest.Fixture(2, 1, true);
		final ScopeHost host = new ScopeHost(world);
		final InventoryBackend backend = new InventoryBackend(host, world.runtime, 1);
		final nx.pingwheel.common.domain.MarkerId marker;
		Scenario() {
			host.policies.replaceAll((player, policy) -> new InventoryBackend.Policy(100, 1, Set.of(), Set.of("entity_block")));
			Target candidate = Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:chest", "locator", true);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, world.settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, candidate, BlockFace.NORTH), 0, world.settings);
			backend.tick(0, world.settings);
			var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).reduce((a,b) -> b).orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(InventoryResourceKind.FLUID, host.epoch(InventoryBackendTest.A), 100, 1, 1, 1,
				preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "attention"), 1, world.settings);
			marker = host.store.allMarkers().getFirst().id();
		}
		@Unmodifiable List<InventoryS2CPacket> since(int index) { return host.packets.subList(index, host.packets.size()).stream().filter(packet -> marker.equals(packet.markerId())).toList(); }
		@Override public void close() { try { backend.close(); } finally { world.close(); } }
	}
	@NotNullByDefault
	private static final class ScopeHost extends InventoryBackendTest.Host {
		final InventoryFluidWorldTest.Fixture world;
		@Nullable Runnable beforeMeasurement;
		ScopeHost(InventoryFluidWorldTest.Fixture world) { this.world = world; }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
		@Override public Optional<InventorySourceInput> bindCommitted(InventorySourceInput input, Target committed) {
			return Optional.of(new InventorySourceInput(committed, input.readOwner(), input.face(), input.resourceKind()));
		}
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = nx.pingwheel.common.domain.TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
			@Nullable var reason = admission.prepare(world.target, nx.pingwheel.common.marker.TargetKey.from(world.target), "entity_block", List.of(player));
			if (reason != null) return new InventoryBackend.Created(null, reason);
			return new InventoryBackend.Created(store.create(player, world.target, type, type.defaultPingType(),
				new nx.pingwheel.common.marker.MarkerAnchor(1, 64, 3), 1, expiry, List.of(player)).marker(), null);
		}
		@Override public int encodedBytes(InventoryS2CPacket packet, int displayBytes, int frameBytes) {
			if (packet.markerId() != null && !packet.entries().isEmpty() && beforeMeasurement != null) {
				var callback = beforeMeasurement; beforeMeasurement = null; callback.run();
			}
			return packet.encodedSize(displayBytes, frameBytes);
		}
	}
	@Test void retainedBootstrapIsHistoryOnlyAndCannotCompleteOrZeroNewScope() {
		try (var f = new Scenario()) {
			InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.world.settings);
			var seed = f.since(0).stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow();
			assertFalse(seed.completeScan()); assertEquals(162, seed.entries().getFirst().amount());
			int start = f.host.packets.size(); f.world.scope = "two"; f.world.amount = 0;
			f.world.settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); f.backend.tick(2, f.world.settings);
			assertTrue(f.since(start).stream().noneMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID || !packet.entries().isEmpty()));
			f.backend.tick(3, f.world.settings);
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
			f.backend.tick(4, f.world.settings);
			var fresh = f.since(start).stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow();
			assertTrue(fresh.completeScan()); assertEquals(0, fresh.entries().getFirst().amount()); assertTrue(fresh.baselineId() > seed.baselineId());
		}
	}
	@Test void revokeUnloadAndExpiryDuringRecomputeKeepOrdinaryFailureAndHardStop() {
		for (boolean unload : new boolean[] {false, true}) try (var f = new Scenario()) {
			f.backend.tick(2, f.world.settings); f.world.scope = "two"; f.backend.tick(3, f.world.settings);
			int start = f.host.packets.size(); f.world.authorized = unload; f.world.loaded = !unload;
			f.backend.tick(4, f.world.settings);
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty())); assertEquals(80, f.host.store.allMarkers().getFirst().expiresAtTick());
		}
		try (var f = new Scenario()) {
			f.backend.tick(2, f.world.settings); f.world.scope = "two"; f.backend.tick(3, f.world.settings);
			int reads = f.world.reads, binds = f.world.binds, start = f.host.packets.size();
			f.backend.tick(80, f.world.settings); f.backend.tick(81, f.world.settings);
			assertEquals(reads, f.world.reads); assertEquals(binds, f.world.binds); assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
		}
	}
	@Test void sendTimeScopeChangeWithdrawsOldQuantityAndLayoutChangeStillInvalidates() {
		try (var f = new Scenario()) {
			f.host.beforeMeasurement = () -> f.world.scope = "two";
			int start = f.host.packets.size(); InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.world.settings);
			assertNull(f.host.beforeMeasurement); assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
			f.backend.tick(2, f.world.settings);
			assertTrue(f.since(start).stream().noneMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.status() == InventoryS2CPacket.Status.UPDATING && packet.watermark() == 0));
		}
		try (var f = new Scenario()) {
			f.backend.tick(2, f.world.settings); f.world.layoutToken = "changed-members";
			int start = f.host.packets.size(); f.backend.tick(3, f.world.settings);
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
		}
		try (var f = new Scenario()) {
			f.backend.tick(2, f.world.settings); f.world.scope = "two"; f.world.layoutToken = "changed-members";
			int start = f.host.packets.size(); f.backend.tick(3, f.world.settings);
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID),
				"a simultaneous topology change is not a scope-only transition");
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
		}
	}
	@Test void sendTimeRouteOrOrderedAddressChangeRejectsHistoricalSeedDespiteIdenticalLayoutAndTotals() {
		for (boolean reorder : new boolean[] {false, true}) try (var f = new Scenario()) {
			Object oldRoute = f.world.routeIdentity;
			f.host.beforeMeasurement = () -> {
				if (reorder) java.util.Collections.swap(f.world.addresses, 0, 1); else f.world.routeIdentity = new Object();
			};
			int start = f.host.packets.size(); InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.world.settings);
			assertNull(f.host.beforeMeasurement); assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
			f.backend.tick(2, f.world.settings);
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
			java.lang.ref.Reference.reachabilityFence(oldRoute);
		}
	}
	@Test void scopeAndWeakTopologyChangeCannotTakeScopeOnlyUpdatingException() {
		for (int change = 0; change < 3; change++) try (var f = new Scenario()) {
			f.backend.tick(2, f.world.settings); int start = f.host.packets.size(); f.world.scope = "two";
			Object oldRoute = f.world.routeIdentity; Object oldMember = f.world.entities.get(f.world.root);
			if (change == 0) f.world.routeIdentity = new Object();
			else if (change == 1) f.world.replaceMember(f.world.root);
			else f.world.clearMemberWitness();
			f.backend.tick(3, f.world.settings);
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
			assertTrue(f.since(start).stream().noneMatch(packet -> packet.status() == InventoryS2CPacket.Status.UPDATING && packet.watermark() == 0));
			java.lang.ref.Reference.reachabilityFence(oldRoute); java.lang.ref.Reference.reachabilityFence(oldMember);
		}
	}
	@Test void clearedHistoricalProofRecoversByFreshScanAndNeverPublishesSyntheticZeroOrOldCompletion() {
		try (var f = new Scenario()) {
			f.backend.tick(2, f.world.settings); int start = f.host.packets.size(); f.world.clearMemberWitness();
			f.backend.tick(3, f.world.settings);
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()));
			for (int tick = 4; tick < 80 && f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty()); tick++)
				f.backend.tick(tick, f.world.settings);
			var fresh = f.since(start).stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow();
			assertTrue(fresh.completeScan()); assertEquals(162, fresh.entries().getFirst().amount());
			assertTrue(f.world.witnessCaptures >= 3, "preview, committed source and fresh recovery each own their original proof");
		}
	}
	@Test void lateGetterRebindCannotPublishAProvisionalCurrentFrame() {
		for (boolean member : new boolean[] {false, true}) try (var f = new Scenario()) {
			int start = f.host.packets.size();
			f.world.afterRead = () -> { if (member) f.world.replaceMember(f.world.root); else f.world.routeIdentity = new Object(); };
			f.backend.tick(2, f.world.settings);
			assertTrue(f.since(start).stream().noneMatch(packet -> !packet.entries().isEmpty() && packet.completeScan()));
			assertTrue(f.since(start).stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
		}
	}
}
