package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Port;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.ReadFailure;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.neoforge.integration.create.presentation.CoordinatedVaultFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryCoordinatedVaultReadTest {
	private static final BlockPos SIBLING = new BlockPos(1, 0, 0);
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }
	@NotNullByDefault
	static final class World implements InventoryMinecraftSources.SourceWorld {
		final CoordinatedVaultFixture vault = new CoordinatedVaultFixture();
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		boolean owner = true;
		int callbacks, checks, registries;
		Runnable registry = () -> {}, afterRead = () -> {}, ownerKey = () -> {};
		World() { for (var position : vault.members.keySet()) replace(position); }
		void replace(BlockPos pos) { entities.put(pos, new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState())); }
		@Override public boolean isLoaded(BlockPos pos) { callbacks++; return entities.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { callbacks++; return Objects.requireNonNull(entities.get(pos)).getBlockState(); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { callbacks++; return entities.get(pos); }
		@Override public boolean onServerThread() { callbacks++; return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public @Nullable ItemStack readOwnerKey() { callbacks++; ownerKey.run(); return owner ? ItemStack.EMPTY : null; }
		@Override public Check readCheck(BlockEntity entity, @Nullable ItemStack key) { checks++; afterRead.run(); return key == null ? Check.AUTHORITY_DENIED : Check.VALID; }
		@Override public HolderLookup.Provider registries() { callbacks++; registries++; registry.run(); return RegistryAccess.EMPTY; }
		InventoryMinecraftSources.Source source() {
			var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new UUID(77, 1), BlockFace.NORTH);
			var view = InventoryMinecraftSources.view(this, input, vault::find);
			return new InventoryMinecraftSources.Source(input, view, () -> true, () -> { throw new AssertionError("no reroute"); }, entry -> null, this, null);
		}
	}
	static InventoryValidationBudget.Allowance grant(Port port) { return new InventoryValidationBudget.Allowance(port.readValidationCost(1)); }
	static InventoryCatalogAdmission catalogGrant() { return new InventoryCatalogAdmission(
		InventoryCatalogAdmission.workspaceUpperBound(InventoryItemCodec.MAX_CANONICAL_BYTES, 4096, InventoryItemCodec.DEFAULT_MAX_ENTRIES), InventoryCatalogAdmission.workUpperBound(4096)); }
	static InventoryItemCodec.Catalog catalog(Port port) { return port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost())); }
	static @Nullable Object get(Object value, String name) {
		try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	static InventoryCoordinatedRead operation(Port port) { return (InventoryCoordinatedRead) Objects.requireNonNull(get(port, "activeOperation")); }
	static IPlatformInventoryService.CoordinatedSharedAccess provider(World world) {
		return (IPlatformInventoryService.CoordinatedSharedAccess) world.vault.find(BlockPos.ZERO, net.minecraft.core.Direction.NORTH).orElseThrow();
	}
	@Test void successUsesFourFullChecksEightProviderPassesAndExplicitEmptyHandoffWithCurrentReplacement() {
		var world = new World();
		try (var source = world.source()) {
			var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port);
			var current = new ItemStackHandler(1); current.setStackInSlot(0, new ItemStack(Items.DIAMOND, 47)); world.vault.members.get(BlockPos.ZERO).storage = current;
			world.checks = world.vault.passes = world.vault.audits = 0;
			var item = port.read(0, port.proof(), catalog, catalogGrant(), grant(port)); assertNotNull(item); assertEquals(47, item.count());
			assertEquals(16, world.checks, "four common checks, each two passes over two owners"); assertEquals(8, world.vault.passes); assertEquals(1, world.vault.audits);
			current.setStackInSlot(0, ItemStack.EMPTY); world.checks = world.vault.passes = 0;
			assertNull(port.read(0, port.proof(), catalog, catalogGrant(), grant(port)), "null is an observed empty, not an omitted detach");
			assertEquals(16, world.checks); assertEquals(8, world.vault.passes); assertNull(get(port, "activeOperation"));
			assertEquals(InventoryValidationBudget.coordinatedRead(port.validationCost(), provider(world).coordinatedResidentCost(), 1), port.readValidationCost(1));
		}
	}
	@Test void registryAndLastLocalAuditRevocationRefuseBeforeAnyFreezeWithTypedFailureAndPaidAttempt() {
		for (String boundary : List.of("registry", "metadata", "audit")) for (boolean authority : List.of(true, false)) {
			var world = new World(); try (var source = world.source()) {
				var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port); int locals = world.vault.locals;
				Runnable revoke = () -> { if (authority) world.owner = false; else world.replace(SIBLING); };
				if (boundary.equals("registry")) { world.registries = 0; world.registry = () -> { if (world.registries == 2) revoke.run(); }; }
				if (boundary.equals("metadata")) world.vault.onLocal = ignored -> { if (get(port, "activeOperation") != null) revoke.run(); };
				if (boundary.equals("audit")) world.vault.onAudit = ignored -> revoke.run();
				var paid = grant(port);
				assertEquals(authority ? Check.AUTHORITY_DENIED : Check.STRUCTURE_INVALID,
					assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(), paid), boundary + "/" + authority).check(), boundary);
				assertTrue(catalog.displays().isEmpty()); assertEquals(port.readValidationCost(1).workUnits(), paid.attemptedWorkUnits());
				assertNull(get(port, "activeOperation")); if (boundary.equals("registry")) assertTrue(world.vault.locals >= locals);
			}
		}
	}
	@Test void immediateFreezePrecedesAllPostMetadataMutationAndKeepsNativePatchIdentity() {
		var world = new World(); var resident = new ItemStack(Items.STONE, 71); resident.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("getter-time"));
		((ItemStackHandler) world.vault.members.get(BlockPos.ZERO).storage).setStackInSlot(0, resident);
		try (var source = world.source()) {
			var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port); String expected = CertifiedVault1620BaselineTest.expectedDigest(resident);
			world.vault.onLoad = ignored -> { @Nullable Object op = get(port, "activeOperation"); if (op != null && Integer.valueOf(5).equals(get(op, "state"))) {
				resident.setCount(0); resident.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("post-metadata"));
			} };
			var item = port.read(0, port.proof(), catalog, catalogGrant(), grant(port)); assertNotNull(item);
			assertEquals(71, item.count()); assertEquals(expected, item.key().componentsKey()); assertTrue(resident.isEmpty(), "post metadata really mutated the resident");
			assertFalse(Objects.requireNonNull(item.displayJson()).contains("post-metadata"));
		}
	}
	@Test void finalOwnerAndLastProviderMetadataCallbacksStillCatchVisitedMemberReplacement() {
		for (boolean ownerCallback : List.of(false, true)) {
			var world = new World(); try (var source = world.source()) {
				var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port);
				Runnable replace = () -> { @Nullable Object op = get(port, "activeOperation"); if (op != null && Integer.valueOf(2).equals(get(op, "state")) && !catalog.displays().isEmpty()) world.replace(BlockPos.ZERO); };
				if (ownerCallback) world.ownerKey = replace; else world.vault.onLocal = ignored -> replace.run();
				assertEquals(Check.STRUCTURE_INVALID, assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(), grant(port))).check());
				assertFalse(catalog.displays().isEmpty(), "failure occurs after real freeze/intern, which remains attempted work");
			}
		}
	}
	@Test void invalidReceiptOrderingWrongProviderAddressDuplicatesAndPostClosePoisonTheRead() {
		for (String misuse : List.of("before", "after", "detach", "enter", "wrong-slot", "wrong-provider", "close")) {
			var world = new World(); try (var source = world.source()) {
				var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port); var other = provider(world);
				world.vault.onAudit = ignored -> {
					var op = operation(port); var actual = (IPlatformInventoryService.CoordinatedSharedAccess) Objects.requireNonNull(get(op, "provider"));
					switch (misuse) {
						case "before" -> { op.beforeResident(actual, 0); assertThrows(ReadFailure.class, () -> op.beforeResident(actual, 0)); }
						case "after" -> assertThrows(ReadFailure.class, () -> op.afterResident(actual, 0));
						case "detach" -> assertThrows(ReadFailure.class, () -> op.detach(actual, 0, IPlatformInventoryService.ResidentEntry.empty()));
						case "enter" -> assertThrows(ReadFailure.class, () -> op.enter(actual, 0));
						case "wrong-slot" -> assertThrows(ReadFailure.class, () -> op.beforeResident(actual, 1));
						case "wrong-provider" -> assertThrows(ReadFailure.class, () -> op.beforeResident(other, 0));
						case "close" -> op.close();
						default -> throw new AssertionError(misuse);
					}
					assertThrows(ReadFailure.class, () -> op.enter(actual, 0));
				};
				assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(), grant(port)), misuse);
				assertTrue(catalog.displays().isEmpty()); assertNull(get(port, "activeOperation"));
			}
		}
	}
	@Test void reentrantReadRefusalCannotBeCaughtToAuthorizeOuterReadAndFinallyAllowsFreshRead() {
		var world = new World(); try (var source = world.source()) {
			var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port);
			world.vault.onAudit = ignored -> assertThrows(ReadFailure.class, () -> port.read(1, port.proof(), catalog, catalogGrant(), grant(port)));
			assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(), grant(port))); assertTrue(catalog.displays().isEmpty());
			world.vault.onAudit = ignored -> {}; assertNotNull(port.read(0, port.proof(), catalog, catalogGrant(), grant(port)));
		}
	}
	@Test void oneShortMetadataAndCatalogueAdmissionRefuseBeforeWorldOrLocalCallbacks() {
		var world = new World(); try (var source = world.source()) {
			var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port); var cost = port.readValidationCost(1); int callbacks = world.callbacks + world.vault.loads;
			for (int dimension = 0; dimension < 3; dimension++) {
				var shortCost = new InventoryValidationBudget.Cost(cost.allocationBytes() - (dimension == 0 ? 1 : 0), cost.workspaceBytes() - (dimension == 1 ? 1 : 0), cost.workUnits() - (dimension == 2 ? 1 : 0));
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> port.read(0, port.proof(), catalog, catalogGrant(), new InventoryValidationBudget.Allowance(shortCost)));
				assertEquals(callbacks, world.callbacks + world.vault.loads); assertNull(get(port, "activeOperation"));
			}
			long memory = InventoryCatalogAdmission.workspaceUpperBound(InventoryItemCodec.MAX_CANONICAL_BYTES, 4096, InventoryItemCodec.DEFAULT_MAX_ENTRIES), work = InventoryCatalogAdmission.workUpperBound(4096);
			for (var shortGrant : List.of(new InventoryCatalogAdmission(memory - 1, work), new InventoryCatalogAdmission(memory, work - 1))) {
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> port.read(0, port.proof(), catalog, shortGrant, grant(port)));
				assertEquals(callbacks, world.callbacks + world.vault.loads);
			}
			source.close(); assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(), grant(port))).check());
		}
	}
	@Test void realPersistentCodecRevocationRejectsDetachedValueButKeepsAttemptedInternAndTypedFailure() throws Exception {
		for (String change : List.of("owner", "member", "shape", "unloaded", "capacity")) {
			var world = new World(); try (var component = new CallbackComponent(); var source = world.source()) {
				var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port);
				var resident = new ItemStack(Items.STONE, 83); resident.set(component.type, 1);
				((ItemStackHandler) world.vault.members.get(BlockPos.ZERO).storage).setStackInSlot(0, resident);
				component.callback = () -> {
					switch (change) {
						case "owner" -> world.owner = false;
						case "member" -> world.replace(SIBLING);
						case "shape" -> world.vault.members.get(SIBLING).axis = net.minecraft.core.Direction.Axis.Z;
						case "unloaded" -> world.entities.remove(SIBLING);
						case "capacity" -> throw new InventoryItemBudget.CaptureLimitException("codec capacity");
						default -> throw new AssertionError(change);
					}
				};
				var paid = grant(port); var processing = catalogGrant();
				if (change.equals("capacity")) assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> port.read(0, port.proof(), catalog, processing, paid));
				else {
					Check expected = change.equals("owner") ? Check.AUTHORITY_DENIED : change.equals("unloaded") ? Check.UNAVAILABLE : Check.STRUCTURE_INVALID;
					assertEquals(expected, assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, processing, paid)).check());
					assertFalse(catalog.displays().isEmpty()); assertTrue(processing.attemptedWork() > 0, "failed postgate cannot refund intern work");
				}
				assertTrue(component.encodes > 0); assertEquals(port.readValidationCost(1).workUnits(), paid.attemptedWorkUnits()); assertNull(get(port, "activeOperation"));
			}
		}
	}
	@Test void receiptCannotEscapeTheReadAndSuccessfulEmptyCannotBeFollowedByAnotherStage() {
		var world = new World(); try (var source = world.source()) {
			var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port);
			@Nullable InventoryCoordinatedRead[] retained = new InventoryCoordinatedRead[1];
			IPlatformInventoryService.@Nullable CoordinatedSharedAccess[] providers = new IPlatformInventoryService.CoordinatedSharedAccess[1];
			world.vault.onAudit = ignored -> { retained[0] = operation(port); providers[0] = (IPlatformInventoryService.CoordinatedSharedAccess) get(retained[0], "provider"); };
			assertNotNull(port.read(0, port.proof(), catalog, catalogGrant(), grant(port)));
			var op = Objects.requireNonNull(retained[0]); var provider = Objects.requireNonNull(providers[0]);
			assertNull(get(op, "port")); assertNull(get(op, "catalog")); assertNull(get(op, "registries"));
			assertThrows(ReadFailure.class, () -> op.enter(provider, 0)); assertThrows(ReadFailure.class, () -> op.beforeResident(provider, 0));
			assertThrows(ReadFailure.class, () -> op.detach(provider, 0, IPlatformInventoryService.ResidentEntry.empty()));
			assertThrows(ReadFailure.class, () -> op.afterResident(provider, 0));
		}
	}
	@Test void claimedCoordinatedInterfaceCannotRemoveChecksFromAnUnknownProvider() {
		var world = new World(); var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new UUID(77, 1), BlockFace.NORTH);
		var original = InventoryMinecraftSources.view(world, input, world.vault::find);
		var delegate = (IPlatformInventoryService.CoordinatedSharedAccess) Objects.requireNonNull(original.access());
		int[] calls = {0};
		IPlatformInventoryService.CoordinatedSharedAccess unknown = new IPlatformInventoryService.CoordinatedSharedAccess() {
			@Override public InventoryValidationBudget.Cost coordinatedResidentCost() { fail("unknown provider cannot select cheaper envelope"); return InventoryValidationBudget.NONE; }
			@Override public void consumeCoordinatedResident(int slot, InventoryCoordinatedRead operation) { fail("unknown provider cannot receive operation"); }
			@Override public InventorySharedRead.@Nullable Route sharedReadRoute() { return delegate.sharedReadRoute(); }
			@Override public Check validateSharedRead(InventorySnapshotLayout layout) { calls[0]++; return delegate.validateSharedRead(layout); }
			@Override public InventoryValidationBudget.Cost sharedValidationCost() { return delegate.sharedValidationCost(); }
			@Override public boolean consumeSharedResident(int slot, IPlatformInventoryService.ResidentVisitor visitor, Runnable authority) { return delegate.consumeSharedResident(slot, visitor, authority); }
			@Override public Optional<String> alias() { return delegate.alias(); }
			@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return delegate.snapshotLayout(); }
			@Override public int slots() { return delegate.slots(); }
			@Override public boolean stableCursor() { return true; }
			@Override public java.util.OptionalLong version() { return java.util.OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { return delegate.read(slot); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { return delegate.visit(limit, visitor); }
		};
		var view = new InventoryMinecraftSources.View(original.identity(), original.entities(), original.slots(), unknown, original.snapshotLayout(), original.discoverItems(), original.providerMembers());
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view, entry -> null, world, null)) {
			var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port); calls[0] = 0;
			assertNotNull(port.read(0, port.proof(), catalog, catalogGrant(), grant(port))); assertEquals(5, calls[0], "generic adapter retains five common checks and two standalone provider matches");
			assertEquals(port.validationCost().times(6).plus(InventoryValidationBudget.nativeRead(view.snapshotLayout(), true, true).times(2)), port.readValidationCost(1));
		}
	}
	@Test void postFreezeOwnerFailureAppendsNoneOfThePageAndAnAuthorizedPeerRetriesPaidTail() throws Exception {
		var world = new World();
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new UUID(77, 1), BlockFace.NORTH);
		var settings = nx.pingwheel.common.config.InventorySettings.serverDefaults();
		settings.getTracking().setDistinguishFaces(false); settings.getTracking().setDistinguishPlayers(false);
		var resolver = new nx.pingwheel.common.presentation.inventory.InventoryRuntime.SourceResolver() {
			@Override public Optional<nx.pingwheel.common.presentation.inventory.InventorySourceAccess.Source> resolve(InventorySourceInput ignored, int ceiling) { return Optional.of(world.source()); }
		};
		var runtime = new nx.pingwheel.common.presentation.inventory.InventoryRuntime(resolver, settings.pendingMemoryBytes(), nx.pingwheel.common.presentation.inventory.InventoryRuntime.DisplayReprojector.none(), nx.pingwheel.common.presentation.budget.BudgetDelaySink.NONE, true);
		try (runtime) {
			runtime.advance(0, settings); var subject = new nx.pingwheel.common.presentation.inventory.InventoryRuntime.TrackingSubject(nx.pingwheel.common.marker.TargetKey.from(input.target()));
			var failed = runtime.attach(input, subject).orElseThrow(); var peer = runtime.attach(input, subject).orElseThrow();
			assertEquals(nx.pingwheel.common.presentation.inventory.InventorySourceAccess.Preparation.READY, runtime.prepare(failed));
			assertEquals(nx.pingwheel.common.presentation.inventory.InventorySourceAccess.Preparation.READY, runtime.prepare(peer));
			var round = Objects.requireNonNull(get(failed, "round")); var journal = (nx.pingwheel.common.presentation.inventory.InventorySlotJournal) Objects.requireNonNull(get(round, "journal"));
			var catalog = (InventoryItemCodec.Catalog) Objects.requireNonNull(get(Objects.requireNonNull(get(round, "sweep")), "catalog"));
			world.afterRead = () -> { if (!catalog.displays().isEmpty()) world.owner = false; };
			var result = runtime.step(failed, 2).orElseThrow();
			assertNotEquals(nx.pingwheel.common.presentation.source.CaptureResult.Availability.READABLE, result.result().availability()); assertTrue(failed.invalidated());
			assertEquals(0, journal.endSlot(), "post-freeze failed page commits no address");
			assertFalse(catalog.displays().isEmpty(), "attempted catalogue work remains retained, not refunded");
			assertEquals(1, CertifiedVault1620Fixture.physical(runtime));
			world.afterRead = () -> {}; world.owner = true; runtime.advance(1, settings);
			var accepted = runtime.step(peer, 2).orElseThrow(); assertEquals(2, accepted.slots().size()); assertEquals(2, journal.endSlot());
			assertEquals(2, CertifiedVault1620Fixture.physical(runtime), "retry pays both native addresses after failed attempted getter");
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void unchangedCertified1620PageBoundaryHasTheDerivedReductionPlusTheIndependentAssociationCheck() {
		try (var run = new CertifiedVault1620Fixture.Run(CertifiedVault1620Fixture.Contents.EMPTY, CertifiedVault1620Fixture.Profile.INCIDENT)) {
			run.prepare(); run.step(32); run.world.resetCounters(); run.countPhysical = true; run.step(32);
			long members = 81, slots = 32, checks = 4 * slots + 2 + 1;
			assertEquals(checks * 2 * members, run.world.checks);
			assertEquals(8 * slots + 4 + 2, run.world.vault.widths);
			assertEquals(slots * (18 * members + 17) + 8 * members + 8 + 4 * members + 4, run.world.vault.loadedCalls);
			long methods = run.world.vault.controllerCalls + run.world.vault.controllerChecks + run.world.vault.widths
				+ run.world.vault.lengths + run.world.vault.axes + run.world.vault.locals;
			assertEquals(slots * (24 * members + 49) + 12 * members + 24 + 6 * members + 12, methods);
			assertEquals(32, run.physicalVisits); assertEquals(64, run.slots); assertEquals(2, run.pages); assertEquals(0, run.defers); assertEquals(0, run.invalid);
		}
	}
	@Test void unsafeGetterOrBackingAndUnloadedMemberCannotReachResidentContents() throws Exception {
		for (String change : List.of("getter", "backing", "unloaded")) {
			var world = new World(); try (var source = world.source()) {
				var port = Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port); int[] gets = {0};
				if (change.equals("getter")) world.vault.members.get(BlockPos.ZERO).storage = new ItemStackHandler(1) {
					@Override public ItemStack getStackInSlot(int slot) { gets[0]++; throw new AssertionError("uncertified getter"); }
				};
				if (change.equals("backing")) {
					var field = ItemStackHandler.class.getDeclaredField("stacks"); field.setAccessible(true);
					var stacks = Objects.requireNonNull(field.get(world.vault.members.get(BlockPos.ZERO).storage));
					var list = net.minecraft.core.NonNullList.class.getDeclaredField("list"); list.setAccessible(true);
					list.set(stacks, new java.util.LinkedList<>(List.of(ItemStack.EMPTY)) {
						@Override public ItemStack get(int index) { gets[0]++; throw new AssertionError("unsafe backing getter"); }
					});
				}
				if (change.equals("unloaded")) world.entities.remove(SIBLING);
				assertEquals(Check.UNAVAILABLE, assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(), grant(port))).check());
				assertEquals(0, gets[0]); assertTrue(catalog.displays().isEmpty());
			}
		}
	}
	@Test void receiptRejectsMissingBeforeMissingDetachMissingAfterAndDuplicateDetachAndAfter() {
		var world = new World(); try (var source = world.source()) {
			var port = (InventoryCertifiedRead.NativePort) Objects.requireNonNull(source.sharedRead()); var catalog = catalog(port); var provider = provider(world);
			Field active;
			try { active = port.getClass().getDeclaredField("activeOperation"); active.setAccessible(true); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			for (String violation : List.of("missing-before", "missing-detach", "missing-after", "duplicate-detach", "duplicate-after")) {
				var operation = new InventoryCoordinatedRead(port, provider, 0, port.proof(), catalog, RegistryAccess.EMPTY, catalogGrant());
				try {
					set(port, "reading", true); active.set(port, operation); operation.enter(provider, 0);
					if (violation.equals("missing-before")) assertThrows(ReadFailure.class, () -> operation.detach(provider, 0, IPlatformInventoryService.ResidentEntry.empty()));
					else {
						operation.beforeResident(provider, 0);
						if (violation.equals("missing-detach")) assertThrows(ReadFailure.class, () -> operation.afterResident(provider, 0));
						else {
							operation.detach(provider, 0, IPlatformInventoryService.ResidentEntry.empty());
							if (violation.equals("missing-after")) assertThrows(ReadFailure.class, () -> operation.completed(port));
							if (violation.equals("duplicate-detach")) assertThrows(ReadFailure.class, () -> operation.detach(provider, 0, IPlatformInventoryService.ResidentEntry.empty()));
							if (violation.equals("duplicate-after")) { operation.afterResident(provider, 0); assertThrows(ReadFailure.class, () -> operation.afterResident(provider, 0)); }
						}
					}
				} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
				finally { operation.close(); set(port, "activeOperation", null); set(port, "reading", false); }
			}
		}
	}
	private static void set(Object owner, String name, @Nullable Object value) {
		try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	/** Exact temporary registry restoration, with an uncached native persistent codec callback. */
	@NotNullByDefault
	static final class CallbackComponent implements AutoCloseable {
		final net.minecraft.core.component.DataComponentType<Integer> type;
		Runnable callback = () -> {};
		int encodes;
		private final Map<String, Map<Object, Object>> saved = new HashMap<>();
		private final Object frozen, lifecycle;
		private final List<Object> byId;
		private final int size;
		@SuppressWarnings("unchecked") CallbackComponent() throws Exception {
			for (String name : List.of("byLocation", "byKey", "byValue", "registrationInfos", "toId")) saved.put(name, new HashMap<>((Map<Object, Object>) field(name).get(BuiltInRegistries.DATA_COMPONENT_TYPE)));
			frozen = Objects.requireNonNull(field("frozen").get(BuiltInRegistries.DATA_COMPONENT_TYPE)); lifecycle = Objects.requireNonNull(field("registryLifecycle").get(BuiltInRegistries.DATA_COMPONENT_TYPE));
			byId = (List<Object>) field("byId").get(BuiltInRegistries.DATA_COMPONENT_TYPE); size = byId.size();
			type = net.minecraft.core.component.DataComponentType.<Integer>builder().persistent(com.mojang.serialization.Codec.INT.xmap(v -> v, v -> { encodes++; callback.run(); return v; })).build();
			field("frozen").set(BuiltInRegistries.DATA_COMPONENT_TYPE, false);
			try { net.minecraft.core.Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, "pingforit_test:coordinated_callback", type); }
			finally { field("frozen").set(BuiltInRegistries.DATA_COMPONENT_TYPE, frozen); }
		}
		private static Field field(String name) throws Exception { var field = MappedRegistry.class.getDeclaredField(name); field.setAccessible(true); return field; }
		@SuppressWarnings("unchecked") @Override public void close() throws Exception {
			for (var entry : saved.entrySet()) { var map = (Map<Object, Object>) field(entry.getKey()).get(BuiltInRegistries.DATA_COMPONENT_TYPE); map.clear(); map.putAll(entry.getValue()); }
			while (byId.size() > size) byId.removeLast(); field("frozen").set(BuiltInRegistries.DATA_COMPONENT_TYPE, frozen); field("registryLifecycle").set(BuiltInRegistries.DATA_COMPONENT_TYPE, lifecycle);
		}
	}
}
