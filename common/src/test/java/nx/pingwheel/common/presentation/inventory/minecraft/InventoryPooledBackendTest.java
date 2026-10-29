package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPooledBackendTest {
	@BeforeAll static void bootstrap() { InventoryCertifiedReadTest.bootstrap(); }
	@NotNullByDefault
	static final class Resolver implements InventoryRuntime.SourceResolver {
		final InventoryCertifiedReadTest.World world;
		final Map<UUID, Boolean> owners = new HashMap<>();
		java.util.function.Consumer<UUID> duringRead = owner -> {};
		Runnable metadataCallback = () -> {};
		int deniedReads;
		Resolver(Block block) {
			world = InventoryCertifiedReadTest.pair(block);
			for (var entity : world.entities.values()) for (int slot = 0; slot < 27; slot++) ((ChestBlockEntity) entity).setItem(slot, new ItemStack(Items.STONE, slot + 1));
		}
		@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
			allowance.debit(resolutionValidationCost(input));
			var scope = new InventoryMinecraftSources.SourceWorld() {
				@Override public Object worldIdentity() { metadataCallback.run(); return world.worldIdentity(); }
				@Override public boolean isLoaded(BlockPos pos) { return world.isLoaded(pos); }
				@Override public BlockState blockState(BlockPos pos) { return world.blockState(pos); }
				@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { return world.blockEntity(pos); }
				@Override public boolean onServerThread() { return true; }
				@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
				@Override public @Nullable ItemStack readOwnerKey() { return owners.getOrDefault(input.readOwner(), true) ? ItemStack.EMPTY : null; }
				@Override public InventorySharedRead.Check readCheck(BlockEntity entity, @Nullable ItemStack key) { return key == null ? InventorySharedRead.Check.AUTHORITY_DENIED : world.readCheck(entity, key); }
				@Override public HolderLookup.Provider registries() { return world.registries(); }
				@Override public boolean audited(Container container) {
					if (!owners.getOrDefault(input.readOwner(), true)) deniedReads++;
					duringRead.accept(input.readOwner()); return world.audited(container);
				}
			};
			var view = InventoryMinecraftSources.view(scope, input, (pos, face) -> Optional.empty());
			return Optional.of(new InventoryMinecraftSources.Source(input, view, () -> true,
				() -> InventoryMinecraftSources.view(scope, input, (pos, face) -> Optional.empty()), entry -> { throw new AssertionError("compatibility"); }, scope, null,
				cap, ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY));
		}
	}
	static InventorySettings settings(boolean faces, boolean players) {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(256); settings.setPhysicalSlotsPerTick(IntLimit.finite(64));
		settings.getPreview().setPeriodTicks(1); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(4096)); settings.getPreview().setMaxSlotsServer(IntLimit.finite(4096));
		settings.getTracking().setPeriodTicks(10); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(4096)); settings.getTracking().setMaxSlotsServer(IntLimit.finite(4096));
		settings.getTracking().setDistinguishFaces(faces); settings.getTracking().setDistinguishPlayers(players); return settings;
	}
	static Target.BlockTarget target(Block block) { return new Target.BlockTarget("minecraft:overworld", 0, 0, 0, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString()); }
	static TargetKey group(Block block) { return new TargetKey.DoubleChestKey("minecraft:overworld", 0, 0, 0, 1, 0, 0, target(block).blockRegistryId()); }
	static @Unmodifiable List<MarkerId> selectFour(InventoryPublicationBackendFixture f, Block block) {
		var markers = new java.util.ArrayList<MarkerId>();
		for (UUID owner : List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B)) {
			f.open(owner, target(block), BlockFace.NORTH, 0); f.open(owner, target(block), BlockFace.SOUTH, 0);
		}
		for (int tick = 0; tick < 20; tick++) f.backend.tick(tick, f.settings);
		for (UUID owner : List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B)) for (long request = 1; request <= 2; request++) {
			markers.add(f.select(owner, request, 20)); f.closePreview(owner, request, 20);
		}
		return List.copyOf(markers);
	}
	static long physical(InventoryRuntime runtime) throws ReflectiveOperationException {
		var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); return ((CostLedger) field.get(runtime)).used(InventorySourceAccess.PHYSICAL);
	}
	@ParameterizedTest @CsvSource({"true,true,216", "true,false,108", "false,true,108", "false,false,54"})
	void actualOwnersAndFourFacesFlowFromBackendIngressThroughNativeChestToEncodedClients(boolean faces, boolean players, int expected) throws ReflectiveOperationException {
		for (Block block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) {
			var resolver = new Resolver(block); var settings = settings(faces, players);
			try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(block))) {
			@Unmodifiable List<MarkerId> ids = selectFour(f, block); long visits = 0;
				for (int tick = 21; tick < 100; tick++) {
					f.backend.tick(tick, settings); visits += physical(f.runtime);
					if (f.clients.values().stream().allMatch(client -> ids.stream().allMatch(id -> client.tracking(id).complete()))) break;
				}
				assertEquals(expected, visits);
				for (var client : f.clients.values()) for (MarkerId id : ids) { assertTrue(client.tracking(id).complete()); assertEquals(756, client.tracking(id).entries().getFirst().count()); assertEquals(InventoryS2CPacket.Status.READY, client.tracking(id).status()); }
				assertEquals(4, f.markers.size()); assertTrue(f.effectiveMeasurements > 0); assertEquals(0, resolver.deniedReads);
			}
		}
	}
	@Test void effectiveMeasurementRevokesOnlyOneOwnerAfterSamplingAndOtherOwnerCompletes() {
		var resolver = new Resolver(Blocks.CHEST); var settings = settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			@Unmodifiable List<MarkerId> ids = selectFour(f, Blocks.CHEST); MarkerId denied = ids.getFirst();
			int[] revoked = {0}; f.measured = packet -> { if (denied.equals(packet.markerId()) && InventorySyncPublisher.requiresSource(packet)) { revoked[0]++; resolver.owners.put(InventoryPublicationBackendFixture.A, false); } };
			int start = f.frames.size();
			for (int tick = 21; tick < 60; tick++) f.backend.tick(tick, settings);
			assertTrue(revoked[0] > 0, "actual effective-budget callback revoked an earlier eligible owner");
			assertTrue(f.frames.subList(start, f.frames.size()).stream().noneMatch(packet -> denied.equals(packet.markerId()) && InventorySyncPublisher.requiresSource(packet)));
			for (var client : f.clients.values()) { assertFalse(client.tracking(denied).complete()); for (MarkerId id : ids.subList(2, 4)) { assertTrue(client.tracking(id).complete()); assertEquals(756, client.tracking(id).entries().getFirst().count()); } }
			assertEquals(0, resolver.deniedReads);
		}
	}
	@Test void laterOwnersReadRevokesEarlierCheckedOwnerWithoutRetiringSharedProgressOrLeakingQueuedSeed() {
		var resolver = new Resolver(Blocks.CHEST); var settings = settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			@Unmodifiable List<MarkerId> ids = selectFour(f, Blocks.CHEST); int start = f.frames.size(); int[] callbacks = {0};
			settings.setPhysicalSlotsPerTick(IntLimit.finite(9)); f.holdData = true;
			resolver.duringRead = owner -> { if (owner.equals(InventoryPublicationBackendFixture.B)) { callbacks[0]++; resolver.owners.put(InventoryPublicationBackendFixture.A, false); f.holdData = false; } };
			for (int tick = 21; tick < 45; tick++) f.backend.tick(tick, settings);
			assertTrue(callbacks[0] > 0);
			assertTrue(f.frames.subList(start, f.frames.size()).stream().noneMatch(packet -> ids.subList(0, 2).contains(packet.markerId()) && InventorySyncPublisher.requiresSource(packet)));
			for (var client : f.clients.values()) for (MarkerId id : ids.subList(2, 4)) { assertTrue(client.tracking(id).complete()); assertEquals(756, client.tracking(id).entries().getFirst().count()); }
			assertEquals(0, resolver.deniedReads);
		}
	}
	@Test void sourceMetadataCallbackCanRemoveOwnerDuringFinalValidationWithoutMutatingPublisherTraversal() {
		var resolver = new Resolver(Blocks.CHEST); var settings = settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			@Unmodifiable List<MarkerId> ids = selectFour(f, Blocks.CHEST); int[] callbacks = {0};
			f.measured = packet -> { if (ids.getFirst().equals(packet.markerId()) && InventorySyncPublisher.requiresSource(packet))
				resolver.metadataCallback = () -> { callbacks[0]++; f.backend.disconnect(InventoryPublicationBackendFixture.A); }; };
			f.backend.tick(21, settings); resolver.metadataCallback = () -> {}; f.measured = packet -> {};
			assertTrue(callbacks[0] > 0); assertFalse(f.backend.tracksInventory(ids.getFirst()));
			for (int tick = 22; tick < 40; tick++) f.backend.tick(tick, settings);
			assertTrue(f.clients.get(InventoryPublicationBackendFixture.B).tracking(ids.get(2)).complete()); assertEquals(0, resolver.deniedReads);
		}
	}
	@Test void bootstrapOwnBindingChecksWithZeroGettersAndPolicyFlipNeverRetiresItsSeedOrChangesBaseline() throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.TRAPPED_CHEST); var settings = settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.TRAPPED_CHEST))) {
			@Unmodifiable List<MarkerId> ids = selectFour(f, Blocks.TRAPPED_CHEST);
			f.runtime.advance(21, settings);
			var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); var ledger = (CostLedger) field.get(f.runtime);
			@Unmodifiable Map<CostLedger.Counter, Long> amount = Map.of(InventorySourceAccess.PHYSICAL, ledger.remaining(InventorySourceAccess.PHYSICAL)); ledger.tryReserve(amount).orElseThrow().commit(amount);
			int audits = resolver.world.audits; f.backend.tick(21, settings); assertEquals(audits, resolver.world.audits);
			var client = f.clients.get(InventoryPublicationBackendFixture.A); var seed = client.tracking(ids.getFirst());
			assertFalse(seed.complete()); assertEquals(756, seed.entries().getFirst().count()); long baseline = seed.baselineId();
			settings.getTracking().setDistinguishFaces(true); settings.getTracking().setDistinguishPlayers(true);
			f.backend.handle(InventoryPublicationBackendFixture.A, nx.pingwheel.common.network.InventoryC2SPacket.close(f.epochs.get(InventoryPublicationBackendFixture.A), 999).stamp(100, 1), 21, settings);
			for (int tick = 22; tick < 35; tick++) { f.backend.tick(tick, settings); if (client.tracking(ids.getFirst()).complete()) break; }
			assertEquals(baseline, client.tracking(ids.getFirst()).baselineId()); assertTrue(client.tracking(ids.getFirst()).complete()); assertEquals(756, client.tracking(ids.getFirst()).entries().getFirst().count());
			assertFalse(f.frames.stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
		}
	}
	@Test void sameObjectPolicyFlipDuringActiveSharedSweepFinishesExistingAssociationWithoutInvalidOrBaselineReset() throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.CHEST); var settings = settings(false, false);
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			@Unmodifiable List<MarkerId> ids = selectFour(f, Blocks.CHEST); settings.setPhysicalSlotsPerTick(IntLimit.finite(9)); f.backend.tick(21, settings);
			assertEquals(9, physical(f.runtime)); var client = f.clients.get(InventoryPublicationBackendFixture.B);
			long baseline = client.tracking(ids.get(2)).baselineId(); assertFalse(client.tracking(ids.get(2)).complete());
			settings.getTracking().setDistinguishPlayers(true); settings.getTracking().setDistinguishFaces(true);
			f.backend.handle(InventoryPublicationBackendFixture.B, nx.pingwheel.common.network.InventoryC2SPacket.close(f.epochs.get(InventoryPublicationBackendFixture.B), 999).stamp(100, 1), 21, settings);
			long visits = 9;
			for (int tick = 22; tick < 45; tick++) {
				f.backend.tick(tick, settings); visits += physical(f.runtime);
				if (f.clients.values().stream().allMatch(endpoint -> ids.stream().allMatch(id -> endpoint.tracking(id).complete()))) break;
			}
			assertEquals(54, visits); assertEquals(baseline, client.tracking(ids.get(2)).baselineId());
			assertFalse(f.frames.stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			for (var endpoint : f.clients.values()) for (MarkerId id : ids) { assertTrue(endpoint.tracking(id).complete()); assertEquals(756, endpoint.tracking(id).entries().getFirst().count()); }
		}
	}
}
