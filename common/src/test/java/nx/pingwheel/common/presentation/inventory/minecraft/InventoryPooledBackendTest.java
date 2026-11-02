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
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.CaptureResult;
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
		Resolver(Block block) { this(block, true); }
		Resolver(Block block, boolean paired) {
			world = paired ? InventoryCertifiedReadTest.pair(block) : new InventoryCertifiedReadTest.World();
			if (!paired) world.put(BlockPos.ZERO, block.defaultBlockState());
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
		// Completed Preview is not a cache. Admit every intended Tracking peer
		// before any of their first physical rounds can finish.
		@SuppressWarnings("unchecked") var leases = (Map<MarkerId, ?>) InventoryPooledRuntimeTest.fieldUnchecked(f.backend, "tracking");
		for (var marker : markers) f.runtime.prepare((InventoryRuntime.Consumer) InventoryPooledRuntimeTest.fieldUnchecked(java.util.Objects.requireNonNull(leases.get(marker)), "consumer"));
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
	static @Nullable Object stateField(Object owner, String name) {
		try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	static Object state(Object owner, String name) { return java.util.Objects.requireNonNull(stateField(owner, name)); }
	static Object previewState(InventoryPublicationBackendFixture fixture, UUID owner, long request) {
		var session = java.util.Objects.requireNonNull(((Map<?, ?>) state(fixture.backend, "sessions")).get(owner));
		return ((Map<?, ?>) state(session, "previews")).values().stream()
			.filter(preview -> Long.valueOf(request).equals(state(preview, "request"))).findFirst().orElseThrow();
	}
	static Object trackingState(InventoryPublicationBackendFixture fixture, MarkerId marker) {
		return java.util.Objects.requireNonNull(((Map<?, ?>) state(fixture.backend, "tracking")).get(marker));
	}
	private static void distinctPreviewVariants(Resolver resolver) {
		for (var entity : resolver.world.entities.values()) ((ChestBlockEntity) entity).clearContent();
		var chest = (ChestBlockEntity) java.util.Objects.requireNonNull(resolver.world.entities.get(BlockPos.ZERO));
		@Unmodifiable List<net.minecraft.world.item.Item> variants = List.of(Items.STONE, Items.DIRT, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
			Items.COBBLESTONE, Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.TUFF, Items.CALCITE,
			Items.SAND, Items.RED_SAND, Items.GRAVEL, Items.CLAY, Items.BRICKS, Items.OBSIDIAN, Items.NETHERRACK);
		for (int slot = 0; slot < variants.size(); slot++) chest.setItem(slot, new ItemStack(variants.get(slot), 1));
	}
	private static InventorySettings projectionLimitSettings() {
		var settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(16); settings.setPhysicalSlotsPerTick(IntLimit.finite(16));
		settings.getPreview().setPeriodTicks(5); settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(16));
		// Request-owned prefixes must reach the client before selection; wire contention is not this boundary.
		settings.getPreview().setClientByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(java.math.BigDecimal.valueOf(4)));
		settings.validate(); return settings;
	}
	private static long openClientPreview(InventoryPublicationBackendFixture fixture, UUID owner, long tick) {
		long request = fixture.clients.get(owner).open(target(Blocks.CHEST), BlockFace.NORTH, "entity_block");
		assertEquals(request, fixture.open(owner, target(Blocks.CHEST), BlockFace.NORTH, tick)); return request;
	}
	private static MarkerId selectStone(InventoryPublicationBackendFixture fixture, UUID owner, long request, long tick) {
		var client = fixture.clients.get(owner);
		String key = client.preview(request).entries().stream().filter(entry -> entry.itemId().equals("minecraft:stone")).findFirst().orElseThrow().key();
		var reference = client.selectable(request).stream().filter(entry -> entry.entryKey().equals(key)).findFirst().orElseThrow();
		int markers = fixture.markers.size();
		assertEquals(ClientInventory.DispatchOutcome.SENT, client.select(reference, "danger", packet -> {
			fixture.backend.handle(owner, packet, tick, fixture.settings); return ClientInventory.DispatchOutcome.SENT;
		}));
		assertEquals(markers + 1, fixture.markers.size()); return fixture.markers.allMarkers().getLast().id();
	}
	private static long previewStone(ClientInventory.Preview preview) {
		return preview.entries().stream().filter(entry -> entry.itemId().equals("minecraft:stone")).findFirst().orElseThrow().count();
	}
	@ParameterizedTest @CsvSource({"false,64,86", "true,1,54"})
	void projectionTerminalPreviewSelectionStartsFreshUnlessAnotherTrackerStillScans(boolean activePeer, long count, long visits) throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.CHEST); distinctPreviewVariants(resolver); var settings = projectionLimitSettings();
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			UUID owner = InventoryPublicationBackendFixture.A; var client = f.clients.get(owner);
			long request = openClientPreview(f, owner, 0);
			long peerRequest = activePeer ? openClientPreview(f, owner, 0) : 0;
			f.backend.tick(1, settings); long reads = physical(f.runtime); assertEquals(16, reads);
			if (activePeer) {
				selectStone(f, owner, peerRequest, 1); f.closePreview(owner, peerRequest, 1); client.close(peerRequest);
			}
			f.backend.tick(2, settings); reads += physical(f.runtime); assertEquals(32, reads);
			var preview = previewState(f, owner, request); Object original = state(state(preview, "consumer"), "round");
			assertEquals(true, state(preview, "terminal"));
			assertEquals(CaptureResult.Completeness.CONTINUE, ((CaptureResult) state(original, "last")).completeness(),
				"only the Backend projection reached its variant limit; the physical journal is unfinished");
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, client.preview(request).status());
			assertFalse(client.preview(request).completeScan()); assertEquals(1, previewStone(client.preview(request)));
			((ChestBlockEntity) java.util.Objects.requireNonNull(resolver.world.entities.get(BlockPos.ZERO))).setItem(0, new ItemStack(Items.STONE, 64));
			long before = physical(f.runtime); MarkerId marker = selectStone(f, owner, request, 2);
			assertEquals(before, physical(f.runtime), "ordinary SELECT revalidates metadata without any item-presence getter");
			Object selected = trackingState(f, marker); assertEquals(0L, state(selected, "count"), "the retained seed is not a tracking accumulator");
			@Nullable Object selectedRound = stateField(state(selected, "consumer"), "round");
			f.closePreview(owner, request, 2); client.close(request);
			for (int tick = 3; tick <= 40; tick++) {
				f.backend.tick(tick, settings); reads += physical(f.runtime);
				if (f.clients.values().stream().allMatch(endpoint -> endpoint.tracking(marker).complete())) break;
			}
			for (var endpoint : f.clients.values()) {
				assertTrue(endpoint.tracking(marker).complete()); assertEquals(count, endpoint.tracking(marker).entries().getFirst().count());
			}
			if (activePeer) assertSame(original, selectedRound, "a real Tracking peer still makes the paid mixed-time prefix eligible");
			else assertNull(selectedRound, "retaining terminal Preview evidence cannot grant a new active association");
			assertEquals(visits, reads, "a fresh 54-slot observation is required only when no real scanning peer survives");
			assertTrue(f.frames.stream().anyMatch(packet -> marker.equals(packet.markerId()) && !packet.completeScan()
				&& packet.entries().stream().anyMatch(entry -> entry.count() == 1)), "the selected retained Preview count remains a provisional seed");
			assertEquals(0, resolver.deniedReads); assertEquals(0, f.runtime.memory().reserved());
		}
	}
	@Test void freshPreviewCannotUseProjectionTerminalEvidenceEvenWhenTheOldRequestStaysOpen() throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.CHEST); distinctPreviewVariants(resolver); var settings = projectionLimitSettings();
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			UUID owner = InventoryPublicationBackendFixture.A; var client = f.clients.get(owner);
			long originalRequest = openClientPreview(f, owner, 0);
			f.backend.tick(1, settings); long reads = physical(f.runtime); f.backend.tick(2, settings); reads += physical(f.runtime);
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, client.preview(originalRequest).status());
			Object original = state(state(previewState(f, owner, originalRequest), "consumer"), "round");
			((ChestBlockEntity) java.util.Objects.requireNonNull(resolver.world.entities.get(BlockPos.ZERO))).setItem(0, new ItemStack(Items.STONE, 64));
			settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(32)); settings.validate();
			long request = openClientPreview(f, owner, 5);
			for (int tick = 6; tick <= 40; tick++) {
				f.backend.tick(tick, settings); reads += physical(f.runtime); if (client.preview(request).completeScan()) break;
			}
			assertTrue(client.preview(request).completeScan()); assertEquals(64, previewStone(client.preview(request)));
			assertNotSame(original, state(state(previewState(f, owner, request), "consumer"), "round"));
			assertEquals(86, reads); assertEquals(0, f.markers.size());
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, client.preview(originalRequest).status());
			assertEquals(1, previewStone(client.preview(originalRequest))); assertFalse(client.selectable(originalRequest).isEmpty(),
				"terminal evidence and request-owned references remain valid without becoming scanning demand");
		}
	}
	@Test void audiencePausedTrackingDoesNotWidenPreviewSharingAndResumesItsOriginalPaidJournal() throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.CHEST); var settings = settings(false, false); settings.setPhysicalSlotsPerTick(IntLimit.finite(16));
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			UUID owner = InventoryPublicationBackendFixture.A, other = InventoryPublicationBackendFixture.B;
			long request = openClientPreview(f, owner, 0), selected = openClientPreview(f, owner, 0);
			f.backend.tick(1, settings); long reads = physical(f.runtime); MarkerId marker = selectStone(f, owner, selected, 1);
			f.closePreview(owner, selected, 1); f.clients.get(owner).close(selected);
			f.known = (player, id) -> false; f.backend.tick(2, settings); reads += physical(f.runtime);
			Object original = state(state(previewState(f, owner, request), "consumer"), "round");
			assertSame(original, state(state(trackingState(f, marker), "consumer"), "round"));
			assertEquals(CaptureResult.Completeness.CONTINUE, ((CaptureResult) state(original, "last")).completeness());
			long otherRequest = openClientPreview(f, other, 2);
			var otherConsumer = (InventoryRuntime.Consumer) state(previewState(f, other, otherRequest), "consumer");
			long before = physical(f.runtime);
			assertEquals(InventoryRuntime.JoinResult.NO_MATCH, f.runtime.tryJoinInProgress(otherConsumer),
				"only the exact Preview participant is scanning; retained audience-paused Tracking cannot widen that pool");
			assertEquals(InventorySourceAccess.Preparation.READY, f.runtime.prepare(otherConsumer));
			assertNotSame(original, state(otherConsumer, "round"));
			assertEquals(before, physical(f.runtime));
			((ChestBlockEntity) java.util.Objects.requireNonNull(resolver.world.entities.get(BlockPos.ZERO))).setItem(0, new ItemStack(Items.STONE, 64));
			f.known = (player, id) -> true;
			for (int tick = 3; tick <= 40; tick++) {
				f.backend.tick(tick, settings); reads += physical(f.runtime);
				if (f.clients.get(other).preview(otherRequest).completeScan() && f.clients.values().stream().allMatch(endpoint -> endpoint.tracking(marker).complete())) break;
			}
			for (var endpoint : f.clients.values()) { assertTrue(endpoint.tracking(marker).complete()); assertEquals(756, endpoint.tracking(marker).entries().getFirst().count()); }
			assertTrue(f.clients.get(other).preview(otherRequest).completeScan()); assertEquals(819, previewStone(f.clients.get(other).preview(otherRequest)));
			assertEquals(756, previewStone(f.clients.get(owner).preview(request))); assertEquals(108, reads);
		}
	}
	private static @Unmodifiable List<MarkerId> ownerOnlyTrackingPair(InventoryPublicationBackendFixture fixture) {
		fixture.audience = owner -> List.of(owner);
		for (UUID owner : List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B)) fixture.open(owner, target(Blocks.CHEST), BlockFace.NORTH, 0);
		for (int tick = 1; tick <= 20; tick++) fixture.backend.tick(tick, fixture.settings);
		var markers = new java.util.ArrayList<MarkerId>();
		for (UUID owner : List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B)) {
			markers.add(fixture.select(owner, 1, 20)); fixture.closePreview(owner, 1, 20);
		}
		for (int tick = 21; tick <= 25; tick++) fixture.backend.tick(tick, fixture.settings);
		for (int index = 0; index < markers.size(); index++) {
			UUID owner = index == 0 ? InventoryPublicationBackendFixture.A : InventoryPublicationBackendFixture.B;
			assertEquals(List.of(owner), fixture.markers.allMarkers().get(index).recipients());
			assertTrue(fixture.clients.get(owner).tracking(markers.get(index)).complete());
			assertEquals(378, fixture.clients.get(owner).tracking(markers.get(index)).entries().getFirst().count());
			assertEquals(32L, state(trackingState(fixture, markers.get(index)), "nextSweep"));
		}
		assertSame(state(state(trackingState(fixture, markers.getFirst()), "consumer"), "completedRound"),
			state(state(trackingState(fixture, markers.getLast()), "consumer"), "completedRound"));
		return List.copyOf(markers);
	}
	@ParameterizedTest @CsvSource({"false", "true"})
	void recipientlessRecurringPeerCannotStrandSuccessorsBeforeOrAfterGrantAndResumesOnReauthorization(boolean revokeAfterGrant) throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.CHEST, false); var settings = settings(false, false);
		settings.setPhysicalSlotsPerTick(IntLimit.finite(16)); settings.validate();
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, TargetKey.from(target(Blocks.CHEST)))) {
			@Unmodifiable List<MarkerId> ids = ownerOnlyTrackingPair(f); MarkerId a = ids.getFirst(), b = ids.getLast();
			UUID owner = InventoryPublicationBackendFixture.A, suspended = InventoryPublicationBackendFixture.B;
			var aClient = java.util.Objects.requireNonNull(f.clients.get(owner)); var bClient = java.util.Objects.requireNonNull(f.clients.get(suspended));
			Object bLease = trackingState(f, b), bConsumer = state(bLease, "consumer");
			Object original = state(bConsumer, "completedRound"), evidence = state(bLease, "completedEvidence");
			long baseline = aClient.tracking(a).baselineId(), revision = (Long) state(trackingState(f, a), "revision");
			boolean[] permitted = {true}; int[] grants = {0};
			// This is inventory-field permission, not source-read authority or marker removal.
			f.currentPolicy = player -> Optional.of(new InventoryBackend.Policy(100, 1,
				player.equals(suspended) && !permitted[0] ? java.util.Set.of() : java.util.Set.of("entity_block")));
			int forbiddenStart = f.frames.size();
			if (!revokeAfterGrant) permitted[0] = false;
			f.backend.tick(26, settings); // suspension is observed while both completed trackers are waiting for cadence
			for (int tick = 27; tick < 32; tick++) f.backend.tick(tick, settings);
			if (revokeAfterGrant) forbiddenStart = f.frames.size();
			((ChestBlockEntity) java.util.Objects.requireNonNull(resolver.world.entities.get(BlockPos.ZERO))).setItem(0, new ItemStack(Items.STONE, 7));
			if (revokeAfterGrant) resolver.duringRead = reader -> {
				if (reader.equals(owner) && permitted[0]) {
					assertNotNull(stateField(bConsumer, "successorEntitlement"), "authorized cadence-wait peer was granted continuity before the callback");
					grants[0]++; permitted[0] = false;
				}
			};
			else resolver.duringRead = reader -> {
				if (reader.equals(owner)) assertNull(stateField(bConsumer, "successorEntitlement"), "already-suspended peer must be excluded before its next Backend turn could release a mistaken grant");
			};
			var order = InventoryBackend.class.getDeclaredField("trackingCursor"); order.setAccessible(true); order.setInt(f.backend, 0);
			f.backend.tick(32, settings); // A grants first; B observes its audience suspension later in this same pass
			resolver.duringRead = reader -> {};
			assertEquals(revokeAfterGrant ? 1 : 0, grants[0]);
			Object replacement = state(state(trackingState(f, a), "consumer"), "round");
			assertNotSame(original, replacement); assertNull(stateField(bConsumer, "successorEntitlement"));
			assertEquals(0, state(replacement, "incomingEntitlements"));
			assertSame(original, state(bConsumer, "completedRound")); assertSame(evidence, state(bLease, "completedEvidence"));
			assertEquals(27, state(bConsumer, "cursor")); assertEquals(false, state(bConsumer, "scanning"));
			assertEquals(27, ((Map<?, ?>) state(original, "paid")).get(state(bConsumer, "subject")), "suspension neither releases nor refunds the accepted prefix");
			assertEquals(2, f.markers.size()); assertTrue(f.backend.tracksInventory(b));
			f.backend.tick(33, settings);
			assertTrue(aClient.tracking(a).complete()); assertEquals(384, aClient.tracking(a).entries().getFirst().count());
			assertTrue(f.runtime.probe((InventoryRuntime.Consumer) bConsumer).orElseThrow(), "the unlocked source remains readable despite the absent inventory audience");
			((ChestBlockEntity) java.util.Objects.requireNonNull(resolver.world.entities.get(BlockPos.ZERO))).setItem(0, new ItemStack(Items.STONE, 11));
			for (int tick = 34; tick <= 75; tick++) f.backend.tick(tick, settings);
			assertTrue((Long) state(trackingState(f, a), "revision") >= revision + 4, "A completes R1 and multiple later replacements while B stays suspended");
			assertEquals(388, aClient.tracking(a).entries().getFirst().count()); assertEquals(baseline, aClient.tracking(a).baselineId());
			assertSame(original, state(bConsumer, "completedRound")); assertSame(evidence, state(bLease, "completedEvidence"));
			for (int index = forbiddenStart; index < f.frames.size(); index++) if (f.recipients.get(index).equals(suspended))
				assertFalse(InventorySyncPublisher.requiresSource(f.frames.get(index)), "no quantity, success control or heartbeat reaches the unauthorized owner-only audience");
			permitted[0] = true;
			for (int tick = 76; tick <= 120; tick++) f.backend.tick(tick, settings);
			assertTrue(bClient.tracking(b).complete()); assertEquals(388, bClient.tracking(b).entries().getFirst().count());
			assertTrue((Long) state(bLease, "revision") > 1); assertEquals(baseline, aClient.tracking(a).baselineId());
			for (int index = 0; index < f.frames.size(); index++) if (f.frames.get(index).markerId() != null)
				assertEquals(f.frames.get(index).markerId().equals(a) ? owner : suspended, f.recipients.get(index), "physical sharing does not widen either frozen audience");
			assertTrue(f.frames.stream().noneMatch(packet -> packet.status() == InventoryS2CPacket.Status.INVALID));
			for (var marker : f.markers.allMarkers()) assertEquals(10000, marker.expiresAtTick());
			int audits = resolver.world.audits; f.backend.tick(10000, settings); f.backend.tick(10001, settings);
			assertFalse(f.backend.tracksInventory(a)); assertFalse(f.backend.tracksInventory(b)); assertEquals(audits, resolver.world.audits);
			assertEquals(0, resolver.deniedReads); assertEquals(0, f.runtime.memory().reserved());
		}
	}
	@Test void authorizedCadenceWaitPeerStillAdoptsCompletedSuccessorWithoutASecondPhysicalSweep() throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.CHEST, false); var settings = settings(false, false);
		settings.setPhysicalSlotsPerTick(IntLimit.finite(16)); settings.validate();
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, TargetKey.from(target(Blocks.CHEST)))) {
			@Unmodifiable List<MarkerId> ids = ownerOnlyTrackingPair(f);
			Object slow = trackingState(f, ids.getLast()), slowConsumer = state(slow, "consumer");
			var due = slow.getClass().getDeclaredField("nextSweep"); due.setAccessible(true); due.setLong(slow, 37);
			for (int tick = 26; tick <= 33; tick++) f.backend.tick(tick, settings);
			Object successor = state(slowConsumer, "successorEntitlement");
			assertEquals(false, state(slowConsumer, "scanning")); assertEquals(1, state(successor, "incomingEntitlements"));
			assertEquals(CaptureResult.Completeness.COMPLETE, ((CaptureResult) state(successor, "last")).completeness());
			int audits = resolver.world.audits;
			for (int tick = 34; tick <= 37; tick++) f.backend.tick(tick, settings);
			assertSame(successor, state(slowConsumer, "completedRound")); assertNull(stateField(slowConsumer, "successorEntitlement"));
			assertEquals(audits, resolver.world.audits); assertEquals(0, physical(f.runtime));
			assertEquals(3L, state(slow, "revision")); assertEquals(378, f.clients.get(InventoryPublicationBackendFixture.B).tracking(ids.getLast()).entries().getFirst().count());
		}
	}
	@ParameterizedTest @CsvSource({"3", "16"})
	void unselectedOpenPreviewsKeepOneObservationWhileTwoPartitionedTrackingCohortsCompleteRepeatedly(int physicalSlots) throws ReflectiveOperationException {
		var resolver = new Resolver(Blocks.CHEST); var settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(16);
		settings.getTracking().setDistinguishFaces(false); settings.getTracking().setDistinguishPlayers(true);
		String configured = settings.toString(); settings.validate(); assertEquals(configured, settings.toString(), "all other quotas and cadences stay at validated defaults");
		// The reported 3-slot runtime seam deliberately constrains work below the
		// persisted minimum;16 separately exercises the smallest validated allowance.
		settings.setPhysicalSlotsPerTick(IntLimit.finite(physicalSlots));
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, group(Blocks.CHEST))) {
			@Unmodifiable List<UUID> owners = List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B);
			for (UUID owner : owners) {
				assertEquals(1, f.clients.get(owner).open(target(Blocks.CHEST), BlockFace.NORTH, "entity_block"));
				assertEquals(1, f.open(owner, target(Blocks.CHEST), BlockFace.NORTH, 0));
			}
			for (int tick = 1; tick <= 60; tick++) f.backend.tick(tick, settings);
			var markers = new java.util.ArrayList<MarkerId>();
			for (UUID owner : owners) {
				assertTrue(f.clients.get(owner).preview(1).completeScan());
				markers.add(f.select(owner, 1, 60)); f.closePreview(owner, 1, 60); f.clients.get(owner).close(1);
			}
			assertEquals(2, markers.size()); f.backend.tick(61, settings);
			for (UUID owner : owners) {
				assertEquals(2, f.clients.get(owner).open(target(Blocks.CHEST), BlockFace.SOUTH, "entity_block"));
				assertEquals(2, f.open(owner, target(Blocks.CHEST), BlockFace.SOUTH, 62));
			}
			f.backend.tick(62, settings);
			var originalRounds = new java.util.ArrayList<Object>();
			for (int index = 0; index < owners.size(); index++) {
				var preview = previewState(f, owners.get(index), 2); var tracker = trackingState(f, markers.get(index));
				Object round = state(state(preview, "consumer"), "round"); originalRounds.add(round);
				assertSame(round, state(state(tracker, "consumer"), "round"), "later unselected Preview joins its independently authorized active owner cohort");
				assertEquals(1, ((Map<?, ?>) state(java.util.Objects.requireNonNull(((Map<?, ?>) state(f.backend, "sessions")).get(owners.get(index))), "previews")).size(),
					"original selected Preview was normally closed; only the later unselected request remains");
			}
			assertNotSame(originalRounds.get(0), originalRounds.get(1), "player partition keeps two physical cohorts");
			for (int tick = 63; tick <= 300; tick++) f.backend.tick(tick, settings);
			var revisions = new java.util.ArrayList<Long>(); var previewRevisions = new java.util.ArrayList<Long>();
			var references = new java.util.ArrayList<nx.pingwheel.common.presentation.inventory.client.ClientInventory.PreviewEntryReference>();
			for (int index = 0; index < owners.size(); index++) {
				UUID owner = owners.get(index); var preview = previewState(f, owner, 2);
				assertEquals(true, state(preview, "terminal")); assertTrue(f.clients.get(owner).preview(2).completeScan());
				assertEquals(756, f.clients.get(owner).preview(2).entries().getFirst().count());
				previewRevisions.add((Long) state(preview, "revision")); references.add(f.clients.get(owner).selectable(2).getFirst());
				revisions.add((Long) state(trackingState(f, markers.get(index)), "revision"));
			}
			// Two stable slot0 amounts change from1 to7. The old Preview must retain756;
			// each recurring Tracking observation must independently converge to756+2*(7-1).
			for (var entity : resolver.world.entities.values()) ((ChestBlockEntity) entity).setItem(0, new ItemStack(Items.STONE, 7));
			for (int tick = 301; tick <= 600; tick++) f.backend.tick(tick, settings);
			for (int index = 0; index < owners.size(); index++) {
				var tracker = trackingState(f, markers.get(index)); var completed = state(state(tracker, "consumer"), "completedRound");
				System.out.println("PREVIEW_LIFETIME physicalSlots=" + physicalSlots + " tick=600 owner=" + index + " count=" + f.clients.get(owners.get(index)).tracking(markers.get(index)).entries().getFirst().count()
					+ " revisionAt300=" + revisions.get(index) + " revision=" + state(tracker, "revision") + " incoming=" + state(completed, "incomingEntitlements")
					+ " retained=" + f.runtime.memory().retained() + " reserved=" + f.runtime.memory().reserved());
			}
			for (int index = 0; index < owners.size(); index++) {
				var tracker = trackingState(f, markers.get(index));
				for (var client : f.clients.values()) {
					var projection = client.tracking(markers.get(index)); assertTrue(projection.complete()); assertFalse(projection.grey());
					assertEquals(768, projection.entries().getFirst().count(), "neither open unselected Preview may strand later Tracking observations");
				}
				assertTrue((Long) state(tracker, "revision") >= revisions.get(index) + 3, "at least three subsequent Tracking completions without closing either Preview");
				var preview = previewState(f, owners.get(index), 2); Object consumer = state(preview, "consumer");
				assertSame(originalRounds.get(index), state(consumer, "round")); assertEquals(54, state(consumer, "cursor"));
				assertEquals(previewRevisions.get(index), state(preview, "revision"), "Preview performs no second sweep");
				assertNull(stateField(consumer, "successorEntitlement"));
				assertEquals(List.of(references.get(index)), f.clients.get(owners.get(index)).selectable(2), "request-scoped selection stays valid");
				assertEquals(756, f.clients.get(owners.get(index)).preview(2).entries().getFirst().count(), "Preview's initial quantity is neither recaptured nor discarded");
			}
			Object oldPreviewRound = originalRounds.getFirst(); long before = physical(f.runtime);
			MarkerId selectedLater = f.select(owners.getFirst(), 2, 600);
			assertEquals(3, f.markers.size()); assertEquals(before, physical(f.runtime), "late SELECT still has no content witness");
			Object selected = trackingState(f, selectedLater); assertEquals(0L, state(selected, "count"));
			@Nullable Object selectedRound = stateField(state(selected, "consumer"), "round");
			assertNotSame(oldPreviewRound, selectedRound, "the completed initial Preview is not a new-selection cache");
			if (selectedRound != null) assertEquals(nx.pingwheel.common.presentation.source.CaptureResult.Completeness.CONTINUE,
				((nx.pingwheel.common.presentation.source.CaptureResult) state(selectedRound, "last")).completeness());
			for (int tick = 601; tick <= 750; tick++) f.backend.tick(tick, settings);
			for (var client : f.clients.values()) { assertTrue(client.tracking(selectedLater).complete()); assertEquals(768, client.tracking(selectedLater).entries().getFirst().count()); }
			for (UUID owner : owners) assertEquals(true, state(previewState(f, owner, 2), "terminal"));
			assertEquals(0, resolver.deniedReads); assertEquals(0, f.runtime.memory().reserved());
		}
	}
}
