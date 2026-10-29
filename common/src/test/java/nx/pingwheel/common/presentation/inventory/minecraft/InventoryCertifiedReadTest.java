package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Port;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.ReadFailure;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact native entities; only loaded world facts and the unapplied lock accessor are injected headlessly. */
@NotNullByDefault
class InventoryCertifiedReadTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@NotNullByDefault
	static final class World implements InventoryMinecraftSources.SourceWorld {
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		final Map<BlockPos, BlockState> states = new HashMap<>();
		final Map<BlockPos, LockCode> locks = new HashMap<>();
		final Map<BlockPos, Boolean> loadedPositions = new HashMap<>();
		Object identity = new Object();
		HolderLookup.Provider registry = RegistryAccess.EMPTY;
		boolean owner = true, loaded = true;
		ItemStack key = ItemStack.EMPTY;
		int metadata, audits, readChecks, unloadedStateAccesses;
		Runnable afterCheck = () -> {}, beforeAudit = () -> {};
		private boolean loaded(BlockPos pos) { return loaded && loadedPositions.getOrDefault(pos, false) && entities.containsKey(pos); }
		@Override public boolean isLoaded(BlockPos pos) { metadata++; return loaded(pos); }
		@Override public BlockState blockState(BlockPos pos) {
			metadata++;
			if (!loaded(pos)) { unloadedStateAccesses++; throw new AssertionError("block state queried in unloaded chunk: " + pos); }
			return java.util.Objects.requireNonNull(states.get(pos));
		}
		@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { metadata++; return entities.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return key; }
		@Override public @Nullable ItemStack readOwnerKey() { metadata++; return owner ? key : null; }
		@Override public Object worldIdentity() { metadata++; return identity; }
		@Override public HolderLookup.Provider registries() { metadata++; return registry; }
		@Override public Check readCheck(BlockEntity entity, @Nullable ItemStack key) {
			metadata++; readChecks++;
			Check check = entity.isRemoved() || entity instanceof ChestBlockEntity chest && chest.getLootTable() != null
				? Check.UNAVAILABLE : InventoryReadSafety.lockCheck(locks.getOrDefault(entity.getBlockPos(), LockCode.NO_LOCK), key);
			afterCheck.run(); return check;
		}
		@Override public boolean audited(net.minecraft.world.Container container) {
			audits++; beforeAudit.run(); return InventoryContainerReads.audited(container, ignored -> true);
		}
		void put(BlockPos pos, BlockState state) {
			loadedPositions.put(pos, true);
			states.put(pos, state);
			entities.put(pos, state.getBlock() == Blocks.TRAPPED_CHEST ? new TrappedChestBlockEntity(pos, state) : new ChestBlockEntity(pos, state));
		}
		InventorySourceInput input(BlockPos pos, BlockFace face, long owner) {
			return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(),
				BuiltInRegistries.BLOCK.getKey(states.get(pos).getBlock()).toString()), new UUID(3, owner), face);
		}
		InventoryMinecraftSources.Source source(InventorySourceInput input) {
			var view = InventoryMinecraftSources.view(this, input, (pos, face) -> Optional.empty());
			return new InventoryMinecraftSources.Source(input, view, () -> true,
				() -> InventoryMinecraftSources.view(this, input, (pos, face) -> Optional.empty()), entry -> { throw new AssertionError("compatibility codec"); }, this, null);
		}
	}
	static World pair(Block block) {
		return pair(block, BlockPos.ZERO);
	}
	static World pair(Block block, BlockPos first) {
		var world = new World();
		var state = block.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
		world.put(first, state);
		world.put(first.relative(ChestBlock.getConnectedDirection(state)), state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		return world;
	}
	static InventoryValidationBudget.Allowance validation(Port port) { return new InventoryValidationBudget.Allowance(port.validationCost()); }
	static InventoryValidationBudget.Allowance readGrant(Port port) { return new InventoryValidationBudget.Allowance(port.readValidationCost(1)); }
	static InventoryCatalogAdmission catalogGrant(int ceiling) {
		return new InventoryCatalogAdmission(InventoryCatalogAdmission.workspaceUpperBound(InventoryItemCodec.MAX_CANONICAL_BYTES, ceiling,
			InventoryItemCodec.DEFAULT_MAX_ENTRIES), InventoryCatalogAdmission.workUpperBound(ceiling));
	}
	@Test void oppositeExactHitsOwnersAndAllFacesHaveTheSameCanonicalOrderedAddressGraph() {
		for (Block block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) {
			var world = pair(block);
			try (var first = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1))) {
				var port = java.util.Objects.requireNonNull(first.sharedRead());
				for (BlockPos hit : world.entities.keySet()) for (BlockFace face : BlockFace.values()) {
					try (var peer = world.source(world.input(hit, face, 2))) {
						var other = java.util.Objects.requireNonNull(peer.sharedRead());
						assertEquals(port.proof(), other.proof());
						assertEquals(Check.VALID, other.validate(port.proof(), validation(other)));
						assertNotEquals(first.key().readScope(), peer.key().readScope());
					}
				}
				assertEquals(54, port.proof().graph().slots());
				var positions = port.proof().graph().layout().members().stream().map(InventorySnapshotLayout.Member::position).toList();
				assertEquals(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), positions);
				for (var member : port.proof().graph().layout().members()) assertEquals(java.util.stream.IntStream.range(0, 27).boxed().toList(), member.segments().getFirst().visibleSlots());
			}
		}
	}
	@Test void metadataCertificateNeverReadsOrAuditsResidentBackingAndProfileQueriesHaveNoCallbacks() throws ReflectiveOperationException {
		var world = pair(Blocks.CHEST);
		// Install a hostile backing before construction: metadata must neither audit nor traverse it.
		for (var entity : world.entities.values()) backing((ChestBlockEntity) entity, new ArrayList<>() {
			@Override public ItemStack get(int index) { throw new AssertionError("metadata read contents"); }
			@Override public int size() { throw new AssertionError("metadata traversed resident backing"); }
		});
		try (var source = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1))) {
			var port = java.util.Objects.requireNonNull(source.sharedRead());
			assertEquals(Check.VALID, port.validate(port.proof(), validation(port))); assertEquals(0, world.audits);
			int callbacks = world.metadata;
			assertSame(port.proof(), port.proof()); assertSame(port.validationCost(), port.validationCost());
			assertSame(port.readValidationCost(1), port.readValidationCost(1)); assertTrue(port.retainedBytes() > 0);
			assertEquals(callbacks, world.metadata);
			var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
			assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class,
				() -> port.read(0, port.proof(), catalog, catalogGrant(4096), readGrant(port))).check());
		}
	}
	private static void backing(ChestBlockEntity chest, List<ItemStack> replacement) throws ReflectiveOperationException {
		Field items = ChestBlockEntity.class.getDeclaredField("items"); items.setAccessible(true);
		Object stacks = items.get(chest);
		Field list = net.minecraft.core.NonNullList.class.getDeclaredField("list"); list.setAccessible(true); list.set(stacks, replacement);
	}
	@Test void authorityDenialIsDistinctFromPhysicalIdentityPairingWorldRegistryAndMappingFailure() {
		for (String change : List.of("owner", "key", "loot", "unload", "member", "registry", "world", "pair")) {
			var world = pair(Blocks.CHEST);
			try (var source = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1))) {
				var port = java.util.Objects.requireNonNull(source.sharedRead());
				switch (change) {
					case "owner" -> world.owner = false;
					case "key" -> world.locks.put(BlockPos.ZERO, new LockCode("denied"));
					case "loot" -> ((ChestBlockEntity) world.entities.get(BlockPos.ZERO)).setLootTable(net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.LOOT_TABLE, net.minecraft.resources.ResourceLocation.parse("minecraft:chests/simple_dungeon")));
					case "unload" -> world.loaded = false;
					case "member" -> world.entities.put(BlockPos.ZERO, new ChestBlockEntity(BlockPos.ZERO, world.states.get(BlockPos.ZERO)));
					case "registry" -> world.registry = HolderLookup.Provider.create(java.util.stream.Stream.empty());
					case "world" -> world.identity = new Object();
					case "pair" -> world.states.put(BlockPos.ZERO, world.states.get(BlockPos.ZERO).setValue(ChestBlock.TYPE, ChestType.SINGLE));
					default -> throw new AssertionError(change);
				}
				Check expected = change.equals("owner") || change.equals("key") ? Check.AUTHORITY_DENIED
					: change.equals("loot") || change.equals("unload") ? Check.UNAVAILABLE : Check.STRUCTURE_INVALID;
				assertEquals(expected, port.validate(port.proof(), validation(port)), change);
			}
		}
	}
	@Test void inheritedChestSubclassDoesNotOptIntoEquivalenceAndWrongOrderedMappingCannotCertify() {
		var world = pair(Blocks.CHEST);
		@NotNullByDefault class ForeignChest extends ChestBlockEntity {
			ForeignChest(BlockState state) { super(BlockPos.ZERO, state); }
		}
		world.entities.put(BlockPos.ZERO, new ForeignChest(world.states.get(BlockPos.ZERO)));
		try (var source = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1))) { assertNull(source.sharedRead()); }
		world = pair(Blocks.CHEST); var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		var view = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
		var wrong = new InventoryMinecraftSources.View(view.identity(), view.entities(), view.slots().reversed(), null, view.snapshotLayout(), true);
		try (var source = new InventoryMinecraftSources.Source(input, wrong, () -> true, () -> wrong, entry -> null, world, null)) {
			assertNull(source.sharedRead());
		}
	}
	@Test void crossChunkPartnerUnloadIsUnavailableBeforeStateAccessCatalogOrItemCallbacks() throws ReflectiveOperationException {
		var first = new BlockPos(15, 64, 0); var second = new BlockPos(16, 64, 0);
		for (Block block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) for (BlockPos hit : List.of(first, second)) {
			var world = pair(block, first); BlockPos partner = hit.equals(first) ? second : first;
			try (var registration = new CallbackComponent(); var source = world.source(world.input(hit, BlockFace.NORTH, 1))) {
				var port = java.util.Objects.requireNonNull(source.sharedRead());
				assertEquals(List.of(first, second), port.proof().graph().layout().members().stream().map(InventorySnapshotLayout.Member::position).toList());
				assertEquals(Check.VALID, port.validate(port.proof(), validation(port)));
				var stack = new ItemStack(Items.STONE, 37); stack.set(registration.type, 1);
				((ChestBlockEntity) world.entities.get(first)).setItem(0, stack);
				var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
				var item = java.util.Objects.requireNonNull(port.read(0, port.proof(), catalog, catalogGrant(4096), readGrant(port)));
				assertEquals(37, item.count());
				registration.callback = () -> fail("unavailable member must prevent item freezing");
				world.loadedPositions.put(partner, false); world.audits = 0; world.readChecks = 0;
				var paid = validation(port);
				assertEquals(Check.UNAVAILABLE, port.validate(port.proof(), paid));
				assertEquals(port.validationCost().workUnits(), paid.attemptedWorkUnits());
				var creation = new InventoryValidationBudget.Allowance(port.catalogCreationCost());
				assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class, () -> port.createCatalog(4096, creation)).check());
				assertEquals(port.catalogCreationCost().workUnits(), creation.attemptedWorkUnits());
				var read = readGrant(port);
				assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class,
					() -> port.read(0, port.proof(), catalog, catalogGrant(4096), read)).check());
				assertEquals(port.readValidationCost(1).workUnits(), read.attemptedWorkUnits());
				assertEquals(0, world.audits); assertEquals(0, world.readChecks); assertEquals(0, world.unloadedStateAccesses);
				world.loadedPositions.put(partner, true);
				assertEquals(Check.VALID, port.validate(port.proof(), validation(port)));
				world.states.put(partner, world.states.get(partner).setValue(ChestBlock.TYPE, ChestType.SINGLE));
				assertEquals(Check.STRUCTURE_INVALID, port.validate(port.proof(), validation(port)));
			}
		}
	}
	@Test void certificateConstructionDeclinesAnUnloadedMemberBeforeItsStateAccess() {
		var first = new BlockPos(15, 64, 0); var second = new BlockPos(16, 64, 0);
		for (BlockPos unloaded : List.of(first, second)) {
			var world = pair(Blocks.CHEST, first); var input = world.input(first, BlockFace.NORTH, 1);
			var view = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
			world.loadedPositions.put(unloaded, false);
			assertNull(InventoryCertifiedRead.create(input, view, world, () -> false));
			assertEquals(0, world.unloadedStateAccesses);
		}
	}
	@Test void partnerUnloadAfterFirstPassIsUnavailableInFinalValidationForEveryOperation() {
		var first = new BlockPos(15, 64, 0); var second = new BlockPos(16, 64, 0);
		for (String operation : List.of("validate", "catalog", "read")) {
			var world = pair(Blocks.CHEST, first);
			try (var source = world.source(world.input(first, BlockFace.NORTH, 1))) {
				var port = java.util.Objects.requireNonNull(source.sharedRead());
				var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
				world.readChecks = 0;
				world.afterCheck = () -> { if (world.readChecks == 2) world.loadedPositions.put(second, false); };
				switch (operation) {
					case "validate" -> assertEquals(Check.UNAVAILABLE, port.validate(port.proof(), validation(port)));
					case "catalog" -> assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class,
						() -> port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()))).check());
					case "read" -> assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class,
						() -> port.read(0, port.proof(), catalog, catalogGrant(4096), readGrant(port))).check());
					default -> throw new AssertionError(operation);
				}
				assertEquals(2, world.readChecks); assertEquals(0, world.audits); assertEquals(0, world.unloadedStateAccesses);
			}
		}
	}
	@Test void partnerUnloadDuringFreezeOrPostGetterValidationNeverReturnsAPartialItem() throws ReflectiveOperationException {
		var first = new BlockPos(15, 64, 0); var second = new BlockPos(16, 64, 0);
		for (boolean finalPass : List.of(false, true)) {
			var world = pair(Blocks.CHEST, first);
			try (var registration = new CallbackComponent(); var source = world.source(world.input(first, BlockFace.NORTH, 1))) {
				var port = java.util.Objects.requireNonNull(source.sharedRead());
				var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
				var stack = new ItemStack(Items.STONE, 37); stack.set(registration.type, 1);
				((ChestBlockEntity) world.entities.get(first)).setItem(0, stack);
				int[] freezes = {0};
				registration.callback = () -> {
					freezes[0]++; world.readChecks = 0;
					if (!finalPass) world.loadedPositions.put(second, false);
				};
				world.afterCheck = () -> { if (finalPass && freezes[0] > 0 && world.readChecks == 2) world.loadedPositions.put(second, false); };
				assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class,
					() -> port.read(0, port.proof(), catalog, catalogGrant(4096), readGrant(port))).check());
				assertTrue(freezes[0] > 0, "real resident getter and component freeze were reached");
				assertTrue(world.audits > 0); assertEquals(0, world.unloadedStateAccesses);
				if (finalPass) assertEquals(2, world.readChecks);
			}
		}
	}
	@Test void singleChestAndTrappedRoutesRemainDistinctAndForeignProofIsNeverReplacedByASelfConsistentWrapper() {
		var chestWorld = new World(); chestWorld.put(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		var trapWorld = new World(); trapWorld.put(BlockPos.ZERO, Blocks.TRAPPED_CHEST.defaultBlockState());
		try (var chest = chestWorld.source(chestWorld.input(BlockPos.ZERO, BlockFace.UP, 1));
			var trap = trapWorld.source(trapWorld.input(BlockPos.ZERO, BlockFace.DOWN, 2))) {
			var a = java.util.Objects.requireNonNull(chest.sharedRead()); var b = java.util.Objects.requireNonNull(trap.sharedRead());
			assertEquals(27, a.proof().graph().slots()); assertNotEquals(a.proof().graph(), b.proof().graph());
			assertEquals(Check.STRUCTURE_INVALID, b.validate(a.proof(), validation(b)));
			chestWorld.entities.put(BlockPos.ZERO, new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState()));
			try (var replacement = chestWorld.source(chestWorld.input(BlockPos.ZERO, BlockFace.UP, 1))) {
				var current = java.util.Objects.requireNonNull(replacement.sharedRead());
				assertEquals(a.proof().graph(), current.proof().graph()); assertNotEquals(a.proof(), current.proof());
				assertEquals(Check.STRUCTURE_INVALID, current.validate(a.proof(), validation(current)));
			}
		}
	}
	@Test void sharedCatalogIsExplicitAcrossPortsKeepsNativeCountsAndStickyCanonicalCollisionAndTightening() throws ReflectiveOperationException {
		var world = pair(Blocks.CHEST);
		for (var entity : world.entities.values()) {
			var stack = new ItemStack(Items.STONE, 2_000_000_001); stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("native-name"));
			((ChestBlockEntity) entity).setItem(0, stack);
			stack.setCount(2_000_000_001); // setItem itself applies vanilla's stack-size clamp.
		}
		try (var one = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1));
			var two = world.source(world.input(new BlockPos(1, 0, 0), BlockFace.SOUTH, 2))) {
			var a = java.util.Objects.requireNonNull(one.sharedRead()); var b = java.util.Objects.requireNonNull(two.sharedRead());
			var catalog = a.createCatalog(4096, new InventoryValidationBudget.Allowance(a.catalogCreationCost()));
			var first = java.util.Objects.requireNonNull(a.read(0, a.proof(), catalog, catalogGrant(4096), readGrant(a)));
			var second = java.util.Objects.requireNonNull(b.read(27, a.proof(), catalog, catalogGrant(4096), readGrant(b)));
			assertEquals(4_000_000_002L, first.count() + second.count()); assertEquals(first.key(), second.key());
			assertEquals(1, catalog.displays().size()); assertNull(one.nativeCatalog()); assertNull(two.nativeCatalog());
			catalog.tightenDisplay(InventoryItemCodec.minimumDisplayBytes());
			var tightened = b.read(0, a.proof(), catalog, catalogGrant(catalog.displayBytes()), readGrant(b));
			assertNotNull(tightened); assertEquals(first.key(), tightened.key()); assertEquals(first.count(), tightened.count());
			Field field = InventoryItemCodec.Catalog.class.getDeclaredField("canonicalByDigest"); field.setAccessible(true);
			@SuppressWarnings("unchecked") var known = (Map<String, byte[]>) field.get(catalog);
			known.put(first.key().componentsKey(), new byte[] {99});
			assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class, () -> b.read(27, a.proof(), catalog, catalogGrant(catalog.displayBytes()), readGrant(b))).check());
			assertThrows(ReadFailure.class, () -> a.read(0, a.proof(), catalog, catalogGrant(catalog.displayBytes()), readGrant(a)));
			var privateCatalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
			assertEquals(Check.STRUCTURE_INVALID, assertThrows(ReadFailure.class,
				() -> a.read(0, a.proof(), privateCatalog, catalogGrant(4096), readGrant(a))).check());
		}
	}
	@Test void oneShortValidationCreationAndReadAdmissionRefuseBeforeCallbacksAndCloseEndsBorrowedPort() {
		var world = pair(Blocks.CHEST); var source = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1));
		var port = java.util.Objects.requireNonNull(source.sharedRead());
		for (int dimension = 0; dimension < 3; dimension++) {
			var cost = port.validationCost(); int callbacks = world.metadata;
			var shortCost = shortCost(cost, dimension);
			assertEquals(Check.DEFERRED, port.validate(port.proof(), new InventoryValidationBudget.Allowance(shortCost)));
			assertEquals(callbacks, world.metadata);
			var creationCost = shortCost(port.catalogCreationCost(), dimension);
			assertThrows(InventoryItemBudget.CaptureLimitException.class,
				() -> port.createCatalog(4096, new InventoryValidationBudget.Allowance(creationCost)));
			assertEquals(callbacks, world.metadata);
		}
		var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
		for (int dimension = 0; dimension < 3; dimension++) {
			var grant = new InventoryValidationBudget.Allowance(shortCost(port.readValidationCost(1), dimension)); int callbacks = world.metadata;
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> port.read(0, port.proof(), catalog, catalogGrant(4096), grant));
			assertEquals(callbacks, world.metadata);
		}
		int callbacks = world.metadata;
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> port.read(0, port.proof(), catalog, new InventoryCatalogAdmission(0, 0), readGrant(port)));
		assertEquals(callbacks, world.metadata);
		long workspace = InventoryCatalogAdmission.workspaceUpperBound(InventoryItemCodec.MAX_CANONICAL_BYTES, 4096, InventoryItemCodec.DEFAULT_MAX_ENTRIES);
		long work = InventoryCatalogAdmission.workUpperBound(4096);
		for (var shortGrant : List.of(new InventoryCatalogAdmission(workspace - 1, work), new InventoryCatalogAdmission(workspace, work - 1))) {
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> port.read(0, port.proof(), catalog, shortGrant, readGrant(port)));
			assertEquals(callbacks, world.metadata);
		}
		world.owner = false; var paid = validation(port);
		assertEquals(Check.AUTHORITY_DENIED, port.validate(port.proof(), paid)); assertEquals(port.validationCost().workUnits(), paid.attemptedWorkUnits());
		source.close(); assertNull(source.sharedRead()); assertEquals(Check.UNAVAILABLE, port.validate(port.proof(), validation(port)));
	}
	@Test void getterFreezeAndPostMetadataRevocationNeverHandOffAValidItem() throws ReflectiveOperationException {
		for (String boundary : List.of("pre", "getter", "freeze", "post")) for (boolean authority : List.of(true, false)) {
			var world = pair(Blocks.CHEST);
			try (var registration = new CallbackComponent(); var source = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1))) {
				var port = java.util.Objects.requireNonNull(source.sharedRead());
				var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
				Runnable revoke = () -> {
					if (authority) world.owner = false;
					else world.entities.put(BlockPos.ZERO, new ChestBlockEntity(BlockPos.ZERO, world.states.get(BlockPos.ZERO)));
				};
				var chest = (ChestBlockEntity) world.entities.get(BlockPos.ZERO);
				var stack = new ItemStack(Items.STONE, 37); stack.set(registration.type, 1); chest.setItem(0, stack);
				int[] gets = {0}, freezes = {0};
				registration.callback = () -> { freezes[0]++; if (boundary.equals("freeze")) revoke.run(); };
				if (boundary.equals("getter")) backing(chest, new ArrayList<>(java.util.Collections.nCopies(27, ItemStack.EMPTY)) {
					@Override public ItemStack get(int slot) { gets[0]++; revoke.run(); return stack; }
				});
				// Exact native backing classes are required. A foreign getter is refused at the complete audit first.
				if (boundary.equals("pre")) world.owner = false;
				if (boundary.equals("post")) world.afterCheck = () -> { if (freezes[0] > 0) revoke.run(); };
				Check result = assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(4096), readGrant(port))).check();
				assertEquals(boundary.equals("getter") ? Check.UNAVAILABLE : boundary.equals("pre") || authority ? Check.AUTHORITY_DENIED : Check.STRUCTURE_INVALID, result, boundary);
				if (boundary.equals("getter")) assertEquals(0, gets[0], "a foreign backing is refused before its getter");
				if (boundary.equals("pre")) assertEquals(0, freezes[0]);
				if (boundary.equals("freeze") || boundary.equals("post")) assertTrue(freezes[0] > 0);
			}
		}
	}
	/** Temporary native component registration with exact registry restoration, used only to provoke real codec callbacks. */
	@NotNullByDefault
	private static final class CallbackComponent implements AutoCloseable {
		final net.minecraft.core.component.DataComponentType<Integer> type;
		Runnable callback = () -> {};
		private final Object registry = BuiltInRegistries.DATA_COMPONENT_TYPE;
		private final Map<String, Object> previous = new HashMap<>();
		private final Map<String, Map<Object, Object>> maps = new HashMap<>();
		private final List<Object> byId;
		private final int size;
		@SuppressWarnings("unchecked") CallbackComponent() throws ReflectiveOperationException {
			for (String name : List.of("byLocation", "byKey", "byValue", "registrationInfos", "toId")) {
				var map = (Map<Object, Object>) get(name); maps.put(name, new HashMap<>(map));
			}
			previous.put("frozen", get("frozen")); previous.put("registryLifecycle", get("registryLifecycle"));
			byId = (List<Object>) get("byId"); size = byId.size();
			type = net.minecraft.core.component.DataComponentType.<Integer>builder().persistent(com.mojang.serialization.Codec.INT.xmap(
				value -> value, value -> { callback.run(); return value; })).build();
			set("frozen", false);
			try { net.minecraft.core.Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, "pingforit_test:certified_read_callback", type); }
			finally { set("frozen", previous.get("frozen")); }
		}
		private Object get(String name) throws ReflectiveOperationException {
			Field field = net.minecraft.core.MappedRegistry.class.getDeclaredField(name); field.setAccessible(true); return java.util.Objects.requireNonNull(field.get(registry));
		}
		private void set(String name, Object value) throws ReflectiveOperationException {
			Field field = net.minecraft.core.MappedRegistry.class.getDeclaredField(name); field.setAccessible(true); field.set(registry, value);
		}
		@SuppressWarnings("unchecked") @Override public void close() throws ReflectiveOperationException {
			for (var entry : maps.entrySet()) { var map = (Map<Object, Object>) get(entry.getKey()); map.clear(); map.putAll(entry.getValue()); }
			while (byId.size() > size) byId.remove(byId.size() - 1);
			for (var entry : previous.entrySet()) set(entry.getKey(), entry.getValue());
		}
	}
	private static InventoryValidationBudget.Cost shortCost(InventoryValidationBudget.Cost cost, int dimension) {
		return new InventoryValidationBudget.Cost(cost.allocationBytes() - (dimension == 0 ? 1 : 0),
			cost.workspaceBytes() - (dimension == 1 ? 1 : 0), cost.workUnits() - (dimension == 2 ? 1 : 0));
	}
}
