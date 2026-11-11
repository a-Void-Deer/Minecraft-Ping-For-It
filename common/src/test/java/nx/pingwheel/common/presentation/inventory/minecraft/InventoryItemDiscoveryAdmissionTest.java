package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventoryPublicationEvidence;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production discovery over real world/view ports, using the Runtime's real work/memory grants. */
@NotNullByDefault
class InventoryItemDiscoveryAdmissionTest {
	private static final CostLedger.Counter WORK = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
	private static final UUID OWNER = new UUID(404, 1024);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@NotNullByDefault
	private static final class Furnace extends FurnaceBlockEntity implements BaseContainerLockAccessor {
		int reads;
		Furnace() { super(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
		@Override public LockCode pingforit$inventoryLock() { return LockCode.NO_LOCK; }
		@Override public ItemStack getItem(int slot) { reads++; return ItemStack.EMPTY; }
	}
	@NotNullByDefault
	private static final class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		int reads;
		Chest(BlockState state) { super(BlockPos.ZERO, state); }
		@Override public LockCode pingforit$inventoryLock() { return LockCode.NO_LOCK; }
		@Override public ItemStack getItem(int slot) { reads++; return ItemStack.EMPTY; }
	}
	@NotNullByDefault
	private static final class World implements InventoryMinecraftSources.SourceWorld, InventoryRuntime.SourceResolver {
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		final InventorySourceInput input;
		final @Nullable InventorySnapshotLayout layout;
		@Nullable InventorySnapshotLayout returnedLayout;
		final List<String> callbacks = new ArrayList<>();
		final List<Long> paid = new ArrayList<>();
		@Nullable InventoryValidationBudget.Allowance allowance;
		@Nullable InventoryRuntime runtime;
		@Nullable Runnable afterLayout;
		boolean deny, throwing;
		int layouts, lookups, contents;
		World(boolean container, int members) {
			String id = container ? "minecraft:furnace" : "minecraft:beehive";
			input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, id), OWNER, BlockFace.NORTH);
			var entries = new ArrayList<InventorySnapshotLayout.Member>();
			for (int index = 0; index < Math.max(1, members); index++) {
				var pos = new BlockPos(index, 0, 0);
				entities.put(pos, container ? new Furnace() : new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState()));
				if (members != 0) entries.add(new InventorySnapshotLayout.Member(pos, id, id, "member", index == 0
					? List.of(new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 1, List.of(0))) : List.of()));
			}
			layout = members == 0 ? null : new InventorySnapshotLayout("test:explicit", "north", BlockPos.ZERO, entries);
			returnedLayout = layout;
		}
		void callback(String name) {
			var grant = java.util.Objects.requireNonNull(allowance);
			assertTrue(grant.attemptedWorkUnits() > 0, "every world/provider callback follows a paid grant");
			assertTrue(java.util.Objects.requireNonNull(runtime).memory().reserved() > 0);
			callbacks.add(name); paid.add(grant.attemptedWorkUnits());
		}
		@Override public boolean isLoaded(BlockPos pos) { callback("loaded"); return entities.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { callback("state"); return entities.get(pos).getBlockState(); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { callback("entity"); return entities.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { callback("owner"); if (deny) throw new InventorySourceUnavailableException("owner revoked"); return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { callback("registries"); return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
		@Override public nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check readCheck(BlockEntity entity, @Nullable ItemStack key) {
			callback("authority"); return deny ? nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check.AUTHORITY_DENIED
				: InventoryMinecraftSources.SourceWorld.super.readCheck(entity, key);
		}
		@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput request) { return InventoryMinecraftSources.resolutionValidationCost(request); }
		@Override public InventoryValidationBudget.Cost resolutionProbeCost(InventorySourceInput request) { return InventoryMinecraftSources.resolutionProbeCost(request); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput request, int cap) { throw new AssertionError("unadmitted resolve"); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput request, int cap, InventoryValidationBudget.Allowance grant) {
			allowance = grant; return InventoryMinecraftSources.resolve(this, request, this::find, cap, grant);
		}
		Optional<IPlatformInventoryService.Access> find(BlockPos pos, Direction face) {
			callback("lookup"); lookups++; assertEquals(Direction.NORTH, face);
			if (throwing) throw new IllegalStateException("lookup failed");
			return Optional.of(new IPlatformInventoryService.Access() {
				@Override public Optional<InventorySnapshotLayout> snapshotLayout() {
					callback("layout"); layouts++; if (afterLayout != null) afterLayout.run(); return Optional.ofNullable(returnedLayout);
				}
				@Override public Optional<String> alias() { callback("alias"); return layout == null ? Optional.empty() : Optional.of(layout.layoutId()); }
				@Override public boolean valid() { callback("valid"); return !deny; }
				@Override public int slots() { callback("slots"); return 1; }
				@Override public boolean stableCursor() { callback("cursor"); return true; }
				@Override public OptionalLong version() { callback("version"); return OptionalLong.empty(); }
				@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("detached getter"); }
				@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) { callback("content"); contents++; return IPlatformInventoryService.ResidentEntry.empty(); }
				@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed"); }
			});
		}
	}
	private static CostLedger ledger(InventoryRuntime runtime) throws ReflectiveOperationException {
		var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); return (CostLedger) field.get(runtime);
	}
	private static AutoCloseable operation(InventoryRuntime runtime, InventoryValidationBudget.Cost cost, InventoryValidationBudget.Allowance allowance) throws ReflectiveOperationException {
		var method = InventoryRuntime.class.getDeclaredMethod("validation", InventoryValidationBudget.Cost.class, InventoryValidationBudget.Allowance.class);
		method.setAccessible(true); return (AutoCloseable) ((Optional<?>) method.invoke(runtime, cost, allowance)).orElseThrow();
	}
	@Test void everyRealDiscoveryStagePrecedesItsCallbacksAndSmallContainerFitsDualLaneLowMemory() throws Exception {
		for (boolean container : new boolean[] {true, false}) {
			var world = new World(container, 0); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(16); settings.validate();
			try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
				world.runtime = runtime; runtime.advance(0, settings);
				try (var backendOwnership = runtime.memory().tryReserve(3_773_440).orElseThrow()) {
					backendOwnership.commit(3_773_440);
					assertEquals(Optional.of(true), runtime.probe(world.input));
					assertEquals(0, runtime.memory().reserved()); assertEquals(3_773_440, runtime.memory().retained());
					long root = InventoryValidationBudget.itemResolutionProbe().workUnits();
					assertEquals(root, world.paid.get(world.callbacks.indexOf("entity")));
					if (!container) {
						long lookup = root + InventoryValidationBudget.itemProviderLookup(false).workUnits();
						assertEquals(lookup, world.paid.get(world.callbacks.indexOf("lookup")));
						assertEquals(lookup, world.paid.get(world.callbacks.indexOf("layout")));
						assertEquals(lookup + InventoryValidationBudget.itemProviderShape(world.layout == null ? 0 : world.layout.members().size()).workUnits()
							+ InventoryValidationBudget.itemProviderFinish(world.layout).workUnits(), world.paid.get(world.callbacks.indexOf("alias")));
					}
					var consumer = runtime.attach(world.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
					boolean complete = false;
					for (int tick = 1; tick < 8 && (container ? ((Furnace) world.entities.get(BlockPos.ZERO)).reads == 0 : world.contents == 0); tick++) {
						runtime.advance(tick, settings);
						var observed = runtime.step(consumer, 1);
						complete |= observed.isPresent() && observed.get().result().completeness() == nx.pingwheel.common.presentation.source.CaptureResult.Completeness.COMPLETE;
					}
					assertEquals(1, container ? ((Furnace) world.entities.get(BlockPos.ZERO)).reads : world.contents,
						"the frozen NORTH face exposes only the furnace's fuel slot");
					assertTrue(complete, "a paid small source must finish, not merely attempt a content getter");
					consumer.close(); assertEquals(3_773_440 + 1024, runtime.memory().retained(), "period logical-subject accounting survives consumer close"); assertEquals(0, runtime.memory().reserved());
				}
			}
		}
	}
	@Test void containerStageRejectionPrecedesPartnerAndMappingQueriesEvenForAnUnknownRegistryRoute() throws Exception {
		var world = new World(true, 0); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
			world.runtime = runtime; runtime.advance(0, settings); long allowed = InventoryValidationBudget.itemResolutionProbe().workUnits();
			var work = ledger(runtime); long used = work.remaining(WORK) - allowed;
			try (var ticket = work.tryReserve(Map.of(WORK, used)).orElseThrow()) { ticket.commit(Map.of(WORK, used)); }
			assertEquals(Optional.empty(), runtime.probe(world.input)); assertEquals(1, java.util.Collections.frequency(world.callbacks, "entity"));
			assertEquals(1, java.util.Collections.frequency(world.callbacks, "state")); assertEquals(0, world.lookups);
			assertEquals(0, ((Furnace) world.entities.get(BlockPos.ZERO)).reads);
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
		var chest = new World(false, 0); chest.entities.clear();
		var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.LEFT);
		var half = new Chest(state); chest.entities.put(BlockPos.ZERO, half);
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), OWNER, BlockFace.NORTH);
		try (var runtime = new InventoryRuntime(chest, settings.pendingMemoryBytes())) {
			chest.runtime = runtime; runtime.advance(0, settings); long allowed = InventoryValidationBudget.itemResolutionProbe().workUnits();
			var work = ledger(runtime); long used = work.remaining(WORK) - allowed;
			try (var ticket = work.tryReserve(Map.of(WORK, used)).orElseThrow()) { ticket.commit(Map.of(WORK, used)); }
			assertEquals(Optional.empty(), runtime.probe(input)); assertEquals(1, java.util.Collections.frequency(chest.callbacks, "entity"));
			assertEquals(1, java.util.Collections.frequency(chest.callbacks, "state")); assertEquals(0, half.reads);
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void probeLookupAndFinishRejectionNeverReadNextStageOrBecomeInvalidAndAllTicketsDrain() throws Exception {
		for (int stage = 0; stage < 3; stage++) {
			var world = new World(false, 1); var settings = InventorySettings.serverDefaults();
			try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
				world.runtime = runtime; runtime.advance(0, settings);
				var consumer = runtime.attach(world.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
				long allowed = stage == 0 ? InventoryValidationBudget.itemResolutionProbe().workUnits() - 1
					: InventoryValidationBudget.itemResolutionProbe().workUnits() + (stage == 2 ? InventoryValidationBudget.itemProviderLookup(false).workUnits() : 0);
				var ledger = ledger(runtime); long used = ledger.remaining(WORK) - allowed;
				try (var spent = ledger.tryReserve(Map.of(WORK, used)).orElseThrow()) { spent.commit(Map.of(WORK, used)); }
				assertEquals(Optional.empty(), runtime.probe(world.input));
				long attempted = stage == 0 ? 0 : InventoryValidationBudget.itemResolutionProbe().workUnits()
					+ (stage == 2 ? InventoryValidationBudget.itemProviderLookup(false).workUnits() : 0);
				assertEquals(used + attempted, ledger.used(WORK), "rejection never refunds the already attempted prefix");
				assertEquals(stage == 0 ? 0 : stage == 1 ? 0 : 1, world.lookups);
				assertEquals(stage == 2 ? 1 : 0, world.layouts);
				assertFalse(world.callbacks.contains("alias")); assertEquals(0, world.contents);
				assertFalse(consumer.invalidated());
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
				assertTrue(runtime.step(consumer, 1).isEmpty(), "discovery refusal must not manufacture an empty completion");
				assertFalse(consumer.invalidated()); consumer.close();
				assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
			}
		}
	}
	@Test void memoryRefusalAtLookupAndFinishIsAllOrNoneAndPreservesAttemptedWork() throws Exception {
		for (boolean finish : new boolean[] {false, true}) {
			var world = new World(false, 1); var settings = InventorySettings.serverDefaults();
			try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
				world.runtime = runtime; runtime.advance(0, settings);
				if (finish) world.afterLayout = () -> runtime.memory().setCap(runtime.memory().retained() + runtime.memory().reserved());
				if (!finish) {
					// Leave room for the fixed caller/root probe but not the known native lookup envelope.
					long round = 4096 + nx.pingwheel.common.presentation.inventory.InventorySlotJournal.initialRetainedBytes();
					long root = InventoryValidationBudget.itemResolutionProbe().workspaceBytes();
					runtime.memory().setCap(Math.max(root, round + InventoryItemBudget.workspaceUpperBound(4096))
						+ InventoryValidationBudget.itemProviderLookup(false).workspaceBytes() - 1);
				}
				// Runtime.advance normally publishes the configured cap; this operation deliberately
				// models a lower shared remaining balance at an already admitted callback boundary.
				assertEquals(Optional.empty(), runtime.probe(world.input));
				assertEquals(finish ? 1 : 0, world.lookups); assertEquals(finish ? 1 : 0, world.layouts);
				assertFalse(world.callbacks.contains("alias")); assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
				long expected = InventoryValidationBudget.itemResolutionProbe().workUnits() + (finish ? InventoryValidationBudget.itemProviderLookup(false).workUnits() : 0);
				assertEquals(expected, ledger(runtime).used(WORK));
			}
		}
	}
	@Test void retainedMetadataOutlivesConsumerOnlyWhilePublicationEvidenceOwnsIt() {
		var world = new World(false, 1); var settings = InventorySettings.serverDefaults();
		var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes()); world.runtime = runtime; runtime.advance(0, settings);
		try {
			var consumer = runtime.attach(world.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			InventoryPublicationEvidence evidence = runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow();
			long retained = runtime.memory().retained(); assertTrue(retained > InventorySourceRetention.layout(world.layout));
			consumer.close(); runtime.close();
			assertTrue(runtime.memory().retained() >= InventorySourceRetention.layout(world.layout));
			assertTrue(runtime.memory().retained() < retained); assertEquals(0, runtime.memory().reserved());
			evidence.close(); evidence.close(); assertEquals(0, runtime.memory().retained());
		} finally { runtime.close(); }
	}
	@Test void capturedStageCapabilityCannotReserveAfterSynchronousOperationReturns() throws Exception {
		var world = new World(false, 0); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
			world.runtime = runtime; runtime.advance(0, settings); var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
			InventoryValidationBudget.ItemResolutionGrants grants;
			try (var operation = operation(runtime, InventoryValidationBudget.itemResolutionProbe(), allowance)) { grants = java.util.Objects.requireNonNull(allowance.itemResolutionGrants()); }
			assertNull(allowance.itemResolutionGrants()); assertThrows(IllegalStateException.class, () -> grants.open(InventoryValidationBudget.NONE));
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, ledger(runtime).used(WORK));
		}
	}
	@Test void externalBindingUsesIdenticalStagesAndRetainsOnlyDetachedMetadataUntilClose() throws Exception {
		var world = new World(false, 257); var settings = InventorySettings.serverDefaults();
		var target = Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:beehive", "locator", true);
		var input = new InventorySourceInput(target, OWNER, BlockFace.NORTH);
		var descriptor = new nx.pingwheel.common.integration.externalblock.BlockReadSource(target, "sable", "sublevel",
			world.input.ordinaryTarget().orElseThrow(), new nx.pingwheel.common.marker.MarkerAnchor(0, 0, 0));
		var resolver = new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput request) { return InventoryMinecraftSources.resolutionValidationCost(request); }
			@Override public InventoryValidationBudget.Cost resolutionProbeCost(InventorySourceInput request) { return InventoryMinecraftSources.resolutionProbeCost(request); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput request, int cap) { throw new AssertionError("unadmitted external resolve"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput request, int cap, InventoryValidationBudget.Allowance allowance) {
				world.allowance = allowance;
				return InventoryMinecraftSources.resolveExternal(request, () -> {
					world.callback("binding");
					return Optional.of(new InventoryMinecraftSources.ExternalBinding(world, descriptor, world::isLoaded, world::find));
				}, () -> true, entry -> null, cap, ignored -> {}, ignored -> nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary.EMPTY, allowance);
			}
		};
		try (var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes())) {
			world.runtime = runtime; runtime.advance(0, settings); var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
			InventorySourceAccess.Source source;
			try (var operation = operation(runtime, resolver.resolutionProbeCost(input), allowance)) {
				source = resolver.resolve(input, 4096, allowance).orElseThrow(); assertEquals(0, source.sourceRetainedBytes());
				assertEquals(1, world.layouts); assertEquals(1, world.lookups);
			}
			assertEquals(0, runtime.memory().reserved()); long retained = runtime.memory().retained();
			assertTrue(retained >= InventorySourceRetention.layout(world.layout));
			for (var field : InventoryMinecraftSources.ExternalSource.class.getDeclaredFields()) {
				assertNotEquals(InventoryMinecraftSources.View.class, field.getType());
				assertNotEquals(InventoryMinecraftSources.ExternalBinding.class, field.getType());
				assertNotEquals(java.util.function.Predicate.class, field.getType());
			}
			source.close(); assertEquals(0, runtime.memory().retained());
			world.afterLayout = () -> runtime.memory().setCap(runtime.memory().reserved() + runtime.memory().retained());
			assertEquals(Optional.empty(), runtime.probe(input), "external finish rejection must propagate as defer, not unavailable");
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void explicitLayoutsIncluding1024KeepActualShapeChargingAndOneSnapshotCallback() throws Exception {
		for (int members : new int[] {1, 257, 1024}) {
			var world = new World(false, members); var settings = InventorySettings.serverDefaults();
			// A 1024-member finish admits all 16 segment/text envelopes per member.
			// Large explicit layouts remain supported under the unchanged legal cap range.
			if (members == 1024) settings.setPendingMemoryMiB(128);
			try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
				world.runtime = runtime; runtime.advance(0, settings);
				var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
				InventorySourceAccess.Source source;
				try (var operation = operation(runtime, world.resolutionProbeCost(world.input), allowance)) {
					source = world.resolve(world.input, 4096, allowance).orElseThrow();
					assertEquals(0, source.sourceRetainedBytes(), "staged ownership must not be charged again by Runtime");
					assertTrue(runtime.memory().retained() >= InventorySourceRetention.layout(world.layout));
					assertEquals(1, world.layouts); assertEquals(1, world.lookups); assertEquals(0, world.contents);
					assertEquals(members + 1, java.util.Collections.frequency(world.callbacks, "entity"), "root plus every admitted explicit member is checked");
					int root = world.callbacks.indexOf("entity"), member = world.callbacks.subList(root + 1, world.callbacks.size()).indexOf("entity") + root + 1;
					assertEquals(InventoryValidationBudget.itemResolutionProbe().workUnits() + InventoryValidationBudget.itemProviderLookup(false).workUnits()
						+ InventoryValidationBudget.itemProviderShape(members).workUnits() + InventoryValidationBudget.itemProviderFinish(world.layout).workUnits(), world.paid.get(member));
				}
				assertNull(allowance.itemResolutionGrants()); assertEquals(0, runtime.memory().reserved());
				long work = InventoryValidationBudget.itemResolutionProbe().workUnits() + InventoryValidationBudget.itemProviderLookup(false).workUnits()
					+ InventoryValidationBudget.itemProviderShape(members).workUnits() + InventoryValidationBudget.itemProviderFinish(world.layout).workUnits();
				assertEquals(work, allowance.attemptedWorkUnits()); assertEquals(work, ledger(runtime).used(WORK));
				source.close(); source.close(); assertEquals(0, runtime.memory().retained());
			}
		}
	}
	@Test void cancellationDuringDiscoveryDoesNotInstallOrLeakTheNewSource() {
		var world = new World(false, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
			world.runtime = runtime; runtime.advance(0, settings);
			var consumer = runtime.attach(world.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			world.afterLayout = consumer::close;
			assertEquals(InventorySourceAccess.Preparation.UNAVAILABLE, runtime.prepare(consumer));
			assertEquals(1, world.layouts); assertEquals(0, world.contents);
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void changedProviderShapeIsRejectedBeforeExpandedMemberReadsWithoutAnotherLayoutCallback() throws Exception {
		var world = new World(false, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
			world.runtime = runtime; runtime.advance(0, settings); var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
			InventorySourceAccess.Source source;
			try (var operation = operation(runtime, world.resolutionProbeCost(world.input), allowance)) { source = world.resolve(world.input, 4096, allowance).orElseThrow(); }
			try (source) {
				world.returnedLayout = new World(false, 1024).layout; int layouts = world.layouts;
				world.callbacks.clear(); var check = new InventoryValidationBudget.Allowance(source.evidenceValidationCost()); world.allowance = check;
				try (var workspace = runtime.memory().tryReserve(source.evidenceValidationCost().workspaceBytes()).orElseThrow()) {
					assertFalse(source.valid(check));
				}
				assertEquals(layouts + 1, world.layouts); assertEquals(1, java.util.Collections.frequency(world.callbacks, "entity"), "only the root was queried");
				assertEquals(0, world.contents);
			}
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void itemStageWorkAndIndependentFluidWorkSettleOnceOnTheSameAllowance() throws Exception {
		var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
			var root = new InventoryValidationBudget.Cost(64, 64, 5); var extra = new InventoryValidationBudget.Cost(128, 128, 7);
			var fluidCost = new InventoryValidationBudget.Cost(64, 64, 11);
			try (var operation = operation(runtime, root, allowance)) {
				allowance.debit(root);
				try (var stage = java.util.Objects.requireNonNull(allowance.itemResolutionGrants()).open(extra)) { allowance.debit(extra); }
				try (var fluid = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
					var inner = fluid.enter(new nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile(fluidCost, 0, 0, 0))) {
					assertThrows(IllegalStateException.class, () -> java.util.Objects.requireNonNull(allowance.itemResolutionGrants()).open(extra));
					inner.allowance().debit(fluidCost);
				}
				assertEquals(139, ledger(runtime).used(WORK));
			}
			assertEquals(151, allowance.attemptedWorkUnits()); assertEquals(151, ledger(runtime).used(WORK));
			assertEquals(12, allowance.attemptedOuterWorkUnits()); assertEquals(139, allowance.independentlyChargedWork());
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void hugeReturnedMetadataDefersAtSmallMemoryWithoutUnfundedMemberTraversal() throws Exception {
		var world = new World(false, 1024); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(16); settings.validate();
		try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
			world.runtime = runtime; runtime.advance(0, settings); assertEquals(Optional.empty(), runtime.probe(world.input));
			assertEquals(1, world.layouts); assertEquals(1, world.lookups);
			assertEquals(1, java.util.Collections.frequency(world.callbacks, "entity")); assertFalse(world.callbacks.contains("alias"));
			assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void initialProbeAdmissionIsIdenticalForEveryRegistryAndFullProfilesRemainLegacyOnly() {
		System.out.println("ITEM discovery probe=" + InventoryValidationBudget.itemResolutionProbe() + " container=" + InventoryValidationBudget.itemContainerResolution()
			+ " lookup=" + InventoryValidationBudget.itemProviderLookup() + " layoutlessFinish=" + InventoryValidationBudget.itemProviderFinish(0)
			+ " smallFinish=" + InventoryValidationBudget.itemProviderFinish(1) + " finish257=" + InventoryValidationBudget.itemProviderFinish(257)
			+ " finish1024=" + InventoryValidationBudget.itemProviderFinish(1024));
		for (String id : List.of("minecraft:chest", "minecraft:furnace", "minecraft:beehive", "create:item_vault")) {
			var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, id), OWNER, BlockFace.NORTH);
			assertEquals(InventoryValidationBudget.itemResolutionProbe(), InventoryMinecraftSources.resolutionProbeCost(input));
			assertEquals(InventoryValidationBudget.resolution(id), InventoryMinecraftSources.resolutionValidationCost(input));
		}
	}
	@Test void finishEnvelopeDominatesRetainedMetadataAtEveryIndependentTextAndSegmentBound() {
		for (int count : new int[] {1, 257, 1024}) {
			var members = new ArrayList<InventorySnapshotLayout.Member>();
			for (int index = 0; index < count; index++) {
				var segments = new ArrayList<InventorySnapshotLayout.Segment>();
				for (int segment = 0; segment < InventorySnapshotLayout.MAX_SEGMENTS_PER_MEMBER; segment++)
					segments.add(new InventorySnapshotLayout.Segment("p".repeat(1024), "s".repeat(256), 4096,
						index == 0 && segment == 0 ? java.util.stream.IntStream.range(0, 4096).boxed().toList() : List.of()));
				members.add(new InventorySnapshotLayout.Member(new BlockPos(index, 0, 0), "b".repeat(256), "e".repeat(256), "r".repeat(256), segments));
			}
			var layout = new InventorySnapshotLayout("l".repeat(256), "d".repeat(65536), BlockPos.ZERO, members);
			var view = new InventoryMinecraftSources.View(layout.layoutId(), List.of(), List.of(), null, layout, false);
			// A possible certificate conservatively retains a second full canonical
			// layout plus its witnesses/cost directory. No provider callback computes this bound.
			long owned = InventorySourceRetention.source(view) + InventorySourceRetention.layout(layout)
				+ 2048 + 3 * InventorySourceRetention.list(count) + 3L * count * (40 + 8)
				+ InventorySourceRetention.array(count, 8) + InventorySourceRetention.array(65, 8) + 67L * 64 + 18 + 192;
			assertTrue(owned <= InventoryValidationBudget.itemProviderFinish(count).workspaceBytes(), "complete retained graph must fit finish: " + count);
		}
	}
	@Test void throwingLookupAndCallbackRevocationCleanUpAndNeverReadContents() {
		for (boolean throwing : new boolean[] {true, false}) {
			var world = new World(false, 1); world.throwing = throwing; world.afterLayout = () -> world.deny = true;
			var settings = InventorySettings.serverDefaults();
			try (var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes())) {
				world.runtime = runtime; runtime.advance(0, settings); assertEquals(Optional.of(false), runtime.probe(world.input));
				assertEquals(0, world.contents); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
			}
		}
	}
}
