package nx.pingwheel.fabric.platform;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.impl.transfer.fluid.FluidVariantImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Access;
import nx.pingwheel.common.platform.IPlatformFluidService.Components;
import nx.pingwheel.common.platform.IPlatformFluidService.Context;
import nx.pingwheel.common.platform.IPlatformFluidService.Visit;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Headless production lookup/gating/layout/visitor seams; no transaction, mixin or game launch. */
@NotNullByDefault
class PlatformFluidServiceTest {

	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
	}

	@NotNullByDefault
	private static final class Fixture {
		final List<String> calls = new ArrayList<>();
		final Server server = allocate(Server.class);
		final Level level = allocate(Level.class);
		final List<View> views = new ArrayList<>();
		final PlatformFluidServiceImpl service;
		final Context context;
		boolean authority = true, membership = true, loaded = true, peek, nullSlot, nullIterator, nullView, freshWrappers;
		BlockState state = Blocks.BEEHIVE.defaultBlockState();
		@Nullable BlockEntity entity = new BeehiveBlockEntity(BlockPos.ZERO, state);
		@Nullable Storage<FluidVariant> route;
		int count = 2;
		int memberChecks, authorityChecks;
		int wrapperGeneration;
		@Nullable View lastWrapper;
		Consumer<String> after = ignored -> {};

		Fixture(boolean indexed) { this(indexed, Direction.WEST); }
		Fixture(boolean indexed, Direction face) {
			server.sameThread = true; level.fixture = this;
			views.add(new View(this)); views.add(new View(this));
			route = storage(indexed);
			service = new PlatformFluidServiceImpl((world, position, side) -> {
				assertSame(level, world); assertEquals(BlockPos.ZERO, position); assertSame(face, side);
				nativeCall("lookup"); return route;
			});
			context = new Context(level, BlockPos.ZERO, face, new UUID(11, 17), position -> {
				assertEquals(BlockPos.ZERO, position); memberChecks++; return membership;
			}, () -> { authorityChecks++; return authority; });
		}

		void nativeCall(String operation) {
			assertTrue(authority && membership && server.sameThread, "native callback after current scope denial: " + operation);
			calls.add(operation); after.accept(operation);
		}

		Access open() {
			return service.find(context, allowance(service.resolutionCost())).orElseThrow();
		}

		@SuppressWarnings("unchecked")
		Storage<FluidVariant> storage(boolean indexed) {
			return (Storage<FluidVariant>) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { indexed ? SlottedStorage.class : Storage.class }, (Object proxy, java.lang.reflect.Method method, @Nullable Object[] arguments) -> {
					return switch (method.getName()) {
						case "getSlotCount" -> { nativeCall("count"); yield count; }
						case "getSlot" -> {
							nativeCall("slot");
							if (nullSlot) yield null;
							View view = views.get(((Number) Objects.requireNonNull(arguments)[0]).intValue());
							if (freshWrappers) {
								var wrapper = new View(this); wrapper.delegateTo(view); wrapper.generation = ++wrapperGeneration;
								lastWrapper = wrapper;
								yield wrapper.nativeView;
							}
							yield view.nativeView;
						}
						case "iterator" -> {
							nativeCall("iterator");
							if (nullIterator) yield null;
							yield new Iterator<StorageView<FluidVariant>>() {
								int cursor;
								@Override public boolean hasNext() {
									nativeCall("hasNext");
									if (peek && cursor < count) views.get(cursor).nativeView.getAmount();
									return cursor < count;
								}
								@Override public @Nullable StorageView<FluidVariant> next() {
									nativeCall("next");
									if (cursor >= count) throw new NoSuchElementException();
									return nullView ? null : views.get(cursor++).nativeView;
								}
							};
						}
						default -> throw new AssertionError("unexpected native storage operation: " + method.getName());
					};
				});
		}

		long called(String operation) { return calls.stream().filter(operation::equals).count(); }
		void clear() { calls.clear(); memberChecks = 0; authorityChecks = 0; }
	}

	@NotNullByDefault
	private static final class View {
		final SingleSlotStorage<FluidVariant> nativeView;
		@Nullable StorageView<FluidVariant> underlying;
		@Nullable View contentDelegate;
		int generation;
		long amount = 81_001L;
		boolean blank;
		@Nullable FluidVariant variant = new FluidVariantImpl(Fluids.WATER, DataComponentPatch.EMPTY);

		@SuppressWarnings("unchecked")
		View(Fixture fixture) {
			nativeView = (SingleSlotStorage<FluidVariant>) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { SingleSlotStorage.class }, (Object proxy, java.lang.reflect.Method method, @Nullable Object[] arguments) -> switch (method.getName()) {
					case "getUnderlyingView" -> { fixture.nativeCall("underlying"); yield underlying; }
					case "getAmount" -> { currentWrapper(fixture); fixture.nativeCall("amount"); yield contentDelegate == null ? amount : contentDelegate.nativeView.getAmount(); }
					case "isResourceBlank" -> { currentWrapper(fixture); fixture.nativeCall("blank"); yield contentDelegate == null ? blank : contentDelegate.nativeView.isResourceBlank(); }
					case "getResource" -> { currentWrapper(fixture); fixture.nativeCall("resource"); yield contentDelegate == null ? variant : contentDelegate.nativeView.getResource(); }
					default -> throw new AssertionError("unexpected native view operation: " + method.getName());
				});
			underlying = nativeView;
		}

		void delegateTo(View view) { contentDelegate = view; underlying = view.nativeView; }
		private void currentWrapper(Fixture fixture) {
			if (generation != 0) {
				assertEquals(fixture.wrapperGeneration, generation, "content must use the current selected wrapper acquired last");
				fixture.calls.add("wrapperContent");
			}
		}
	}

	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		boolean sameThread;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return sameThread; }
	}

	@NotNullByDefault
	private static final class Level extends ServerLevel {
		@Nullable Fixture fixture;
		private Level() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		private Fixture fixture() { return Objects.requireNonNull(fixture); }
		@Override public MinecraftServer getServer() { return fixture().server; }
		@Override public boolean isLoaded(BlockPos pos) { assertEquals(BlockPos.ZERO, pos); fixture().nativeCall("loaded"); return fixture().loaded; }
		@Override public BlockState getBlockState(BlockPos pos) { assertEquals(BlockPos.ZERO, pos); fixture().nativeCall("state"); return fixture().state; }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { assertEquals(BlockPos.ZERO, pos); fixture().nativeCall("entity"); return fixture().entity; }
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	private static InventoryValidationBudget.Allowance allowance(InventoryValidationBudget.Cost cost) {
		return new InventoryValidationBudget.Allowance(cost);
	}

	@Test void serviceRegistrationSelectsTheRealFabricPort() {
		assertInstanceOf(PlatformFluidServiceImpl.class, ServiceLoader.load(IPlatformFluidService.class).findFirst().orElseThrow());
	}

	@Test void resolutionReadsOnlyBoundedMetadataAndKeepsEveryFrozenFace() {
		for (Direction face : Direction.values()) {
			var fixture = new Fixture(true, face); Access access = fixture.open();
			assertTrue(access.stableCursor()); assertEquals(2, access.slots()); assertTrue(access.alias().isEmpty());
			var layout = access.layout(); var segment = layout.members().getFirst().segments().getFirst();
			assertEquals(IPlatformFluidService.ADDRESS_SCHEMA, segment.schemaId());
			assertEquals(List.of(0, 1), segment.visibleSlots()); assertEquals(2, segment.localSlots());
			assertEquals("minecraft:beehive", layout.members().getFirst().blockId());
			assertEquals("minecraft:beehive", layout.members().getFirst().blockEntityId());
			assertEquals(face.getSerializedName(), layout.layoutData());
			assertEquals(0, fixture.called("amount")); assertEquals(0, fixture.called("blank"));
			assertEquals(0, fixture.called("resource")); assertEquals(0, fixture.called("iterator"));
			assertThrows(UnsupportedOperationException.class, () -> segment.visibleSlots().add(2));
		}
	}

	@Test void deniedAllowancesReachNoNativeOrAuthorityCallback() {
		var fixture = new Fixture(true);
		assertThrows(CaptureLimitException.class, () -> fixture.service.find(fixture.context, allowance(InventoryValidationBudget.NONE)));
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.memberChecks); assertEquals(0, fixture.authorityChecks);
		Access access = fixture.open(); fixture.clear();
		var required = access.readCost();
		for (var insufficient : List.of(
			new InventoryValidationBudget.Cost(required.allocationBytes() - 1, required.workspaceBytes(), required.workUnits()),
			new InventoryValidationBudget.Cost(required.allocationBytes(), required.workspaceBytes() - 1, required.workUnits()),
			new InventoryValidationBudget.Cost(required.allocationBytes(), required.workspaceBytes(), required.workUnits() - 1))) {
			assertThrows(CaptureLimitException.class, () -> access.consume(0, entry -> fail("visitor"), allowance(insufficient)));
		}
		assertThrows(CaptureLimitException.class, () -> access.valid(allowance(InventoryValidationBudget.NONE)));
		assertThrows(CaptureLimitException.class, () -> access.consume(0, entry -> fail("visitor"), allowance(InventoryValidationBudget.NONE)));
		assertThrows(CaptureLimitException.class, () -> access.observe(2, entry -> fail("visitor"), allowance(InventoryValidationBudget.NONE)));
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.memberChecks); assertEquals(0, fixture.authorityChecks);
		var cursorless = new Fixture(false); Access enumerated = cursorless.open(); cursorless.clear();
		assertThrows(CaptureLimitException.class, () -> enumerated.observe(1, entry -> fail("visitor"), allowance(InventoryValidationBudget.NONE)));
		assertTrue(cursorless.calls.isEmpty()); assertEquals(0, cursorless.memberChecks); assertEquals(0, cursorless.authorityChecks);
	}

	@Test void unavailableRootsNeverFallThroughToCapabilityLookup() {
		var unloaded = new Fixture(true); unloaded.loaded = false;
		assertTrue(unloaded.service.find(unloaded.context, allowance(unloaded.service.resolutionCost())).isEmpty());
		assertEquals(List.of("loaded"), unloaded.calls);
		var missing = new Fixture(true); missing.entity = null;
		assertTrue(missing.service.find(missing.context, allowance(missing.service.resolutionCost())).isEmpty());
		assertEquals(0, missing.called("lookup"));
		var cauldron = new Fixture(true); cauldron.state = Blocks.WATER_CAULDRON.defaultBlockState();
		assertTrue(cauldron.service.find(cauldron.context, allowance(cauldron.service.resolutionCost())).isEmpty());
		assertEquals(0, cauldron.called("lookup")); assertEquals(0, cauldron.called("entity"));
		var loot = new Fixture(true); var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		chest.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON); loot.entity = chest;
		assertTrue(loot.service.find(loot.context, allowance(loot.service.resolutionCost())).isEmpty());
		assertEquals(0, loot.called("lookup"));
		var absent = new Fixture(true); absent.route = null;
		assertTrue(absent.service.find(absent.context, allowance(absent.service.resolutionCost())).isEmpty());
		assertEquals(1, absent.called("lookup"));
	}

	@Test void currentAuthorityMembershipAndServerThreadAreCheckedBeforeAnyWorldRead() {
		for (String denied : List.of("owner", "member", "thread")) {
			var fixture = new Fixture(true);
			if (denied.equals("owner")) fixture.authority = false;
			if (denied.equals("member")) fixture.membership = false;
			if (denied.equals("thread")) fixture.server.sameThread = false;
			assertThrows(InventorySourceUnavailableException.class, fixture::open); assertTrue(fixture.calls.isEmpty());
		}
	}

	@Test void lookupAndEveryIndexedMetadataBoundaryRecheckCurrentAuthority() {
		for (String revokeAt : List.of("loaded", "state", "entity", "lookup", "count", "slot", "underlying")) {
			var fixture = new Fixture(true);
			fixture.after = operation -> { if (operation.equals(revokeAt)) fixture.authority = false; };
			assertThrows(InventorySourceUnavailableException.class, fixture::open);
			assertEquals(revokeAt, fixture.calls.getLast()); assertEquals(0, fixture.called("amount"));
		}
	}

	@Test void indexedResidentHasNativeFluidPatchAndExactUnconvertedLongU() {
		var fixture = new Fixture(true); var first = fixture.views.getFirst();
		var patch = DataComponentPatch.builder().set(DataComponents.DAMAGE, 73).build();
		first.variant = new FluidVariantImpl(Fluids.LAVA, patch); first.amount = 9_007_199_254_741_011L;
		Access access = fixture.open(); fixture.clear();
		assertTrue(access.consume(0, entry -> {
			assertEquals(9_007_199_254_741_011L, entry.amount());
			var resident = assertInstanceOf(Components.class, entry.resident());
			assertSame(Fluids.LAVA, resident.fluid()); assertSame(patch, resident.patch()); return true;
		}, allowance(access.readCost())));
		assertEquals(1, fixture.called("amount")); assertEquals(1, fixture.called("resource"));
		assertTrue(access.valid(allowance(access.validationCost())));
		assertEquals(1, fixture.called("amount"), "metadata validation must not read contents");
	}

	@Test void immediateVisitorPrecedesEveryProviderPostcheckAndDoesNotRetainResidentValues() {
		var fixture = new Fixture(true); var first = fixture.views.getFirst(); Access access = fixture.open(); fixture.clear();
		var before = DataComponentPatch.builder().set(DataComponents.DAMAGE, 91).build();
		first.variant = new FluidVariantImpl(Fluids.LAVA, before);
		boolean[] handedOff = { false };
		fixture.after = operation -> {
			if (operation.equals("loaded") && fixture.called("resource") > 0) {
				assertTrue(handedOff[0], "provider postcheck before source freeze");
				first.variant = new FluidVariantImpl(Fluids.WATER, DataComponentPatch.EMPTY);
			}
		};
		access.consume(0, entry -> {
			var resident = assertInstanceOf(Components.class, entry.resident());
			assertSame(Fluids.LAVA, resident.fluid()); assertSame(before, resident.patch()); handedOff[0] = true; return true;
		});
		fixture.after = ignored -> {};
		access.consume(0, entry -> { assertSame(Fluids.WATER, Objects.requireNonNull(entry.resident()).fluid()); return true; });
	}

	@Test void zeroAddressesAndEmptyIndexedAddressesAreVisitsNotSourceAbsence() {
		var empty = new Fixture(true); empty.count = 0; Access none = empty.open(); empty.clear();
		assertEquals(new Visit(0, true), none.observe(0, entry -> fail("no address"), allowance(none.observationCost(0))));
		assertEquals(0, empty.called("amount"));
		var fixture = new Fixture(true); fixture.views.forEach(view -> view.amount = 0); Access access = fixture.open(); fixture.clear();
		assertEquals(new Visit(1, false), access.observe(1, entry -> { assertNull(entry.resident()); assertEquals(0, entry.amount()); return true; }));
		assertEquals(1, fixture.called("amount")); assertEquals(0, fixture.called("resource"));
		assertEquals(new Visit(2, true), access.observe(2, entry -> { assertNull(entry.resident()); return true; }));
		int[] visits = { 0 };
		assertEquals(new Visit(2, false), access.observe(2, entry -> ++visits[0] < 2));
	}

	@Test void visitorRefusalIsNeverCompletionEvenAtTheLastIndexedAddress() {
		var fixture = new Fixture(true); fixture.count = 1; Access access = fixture.open(); fixture.clear();
		assertEquals(new Visit(1, false), access.observe(1, entry -> false)); assertEquals(1, fixture.called("amount"));
	}

	@Test void cursorlessLimitsPayForEmptyViewsAndDoNotProbeBeyondTheLastAdmittedAttempt() {
		var fixture = new Fixture(false); fixture.peek = true; fixture.views.forEach(view -> view.amount = 0);
		Access access = fixture.open(); assertFalse(access.stableCursor()); assertEquals(0, access.slots());
		assertTrue(access.layout().members().getFirst().segments().getFirst().visibleSlots().isEmpty()); fixture.clear();
		assertEquals(new Visit(0, false), access.observe(0, entry -> fail("zero visit"), allowance(access.observationCost(0))));
		assertEquals(0, fixture.called("iterator")); assertEquals(0, fixture.called("hasNext"));
		assertEquals(new Visit(1, false), access.observe(1, entry -> { assertNull(entry.resident()); return true; }, allowance(access.observationCost(1))));
		assertEquals(1, fixture.called("hasNext")); assertEquals(1, fixture.called("next")); assertEquals(2, fixture.called("amount"));
		fixture.clear();
		assertEquals(new Visit(2, false), access.observe(2, entry -> true));
		assertEquals(2, fixture.called("hasNext")); assertEquals(2, fixture.called("next"));
		fixture.clear();
		assertEquals(new Visit(2, true), access.observe(3, entry -> true));
		assertEquals(3, fixture.called("hasNext")); assertEquals(2, fixture.called("next"));
		assertThrows(UnsupportedOperationException.class, () -> access.consume(0, entry -> true));
	}

	@Test void cursorlessRestartsAtZeroAndVisitorRefusalDoesNotProbeTheTail() {
		var fixture = new Fixture(false); fixture.views.get(0).amount = 17; fixture.views.get(1).amount = 23;
		Access access = fixture.open(); fixture.clear();
		for (int attempt = 0; attempt < 2; attempt++) {
			assertEquals(new Visit(1, false), access.observe(2, entry -> { assertEquals(17, entry.amount()); return false; }));
		}
		assertEquals(2, fixture.called("iterator")); assertEquals(2, fixture.called("next")); assertEquals(2, fixture.called("hasNext"));
	}

	@Test void cursorlessEmptyPassCompletesOnlyWithAnAdmittedHasNext() {
		var fixture = new Fixture(false); fixture.count = 0; Access access = fixture.open(); fixture.clear();
		assertEquals(new Visit(0, false), access.observe(0, entry -> fail("no views")));
		assertEquals(new Visit(0, true), access.observe(1, entry -> fail("no views"), allowance(access.observationCost(1))));
		assertEquals(1, fixture.called("hasNext")); assertEquals(0, fixture.called("next"));
	}

	@Test void revokedContentIteratorOrVariantGetterCannotReachTheNextCallbackOrVisitor() {
		for (boolean indexed : List.of(true, false)) {
			for (String revokeAt : indexed ? List.of("amount", "blank", "resource", "fluid")
				: List.of("iterator", "hasNext", "next", "amount", "blank", "resource", "fluid")) {
				var fixture = new Fixture(indexed); Access access = fixture.open(); fixture.clear();
				fixture.views.getFirst().variant = variant(fixture, Fluids.WATER, DataComponentPatch.EMPTY);
				fixture.after = operation -> { if (operation.equals(revokeAt)) fixture.authority = false; };
				assertThrows(InventorySourceUnavailableException.class, () -> access.observe(1, entry -> fail("revoked read handed off")));
				assertEquals(revokeAt, fixture.calls.getLast());
			}
		}
	}

	@Test void finalGetterHandsOffBeforeAnyAuthorityCallbackAndRevocationStillRejectsTheResult() {
		for (boolean indexed : List.of(true, false)) {
			var fixture = new Fixture(indexed); Access access = fixture.open(); fixture.clear();
			fixture.views.getFirst().variant = variant(fixture, Fluids.WATER, DataComponentPatch.EMPTY);
			fixture.after = operation -> { if (operation.equals("patch")) fixture.authority = false; };
			int[] handedOff = { 0 };
			assertThrows(InventorySourceUnavailableException.class, () -> access.observe(1, entry -> {
				assertEquals("patch", fixture.calls.getLast()); handedOff[0]++; return true;
			}));
			assertEquals(1, handedOff[0]); assertEquals("patch", fixture.calls.getLast());
		}
	}

	private static FluidVariant variant(Fixture fixture, @Nullable net.minecraft.world.level.material.Fluid fluid, @Nullable DataComponentPatch patch) {
		return (FluidVariant) Proxy.newProxyInstance(PlatformFluidServiceTest.class.getClassLoader(), new Class<?>[] { FluidVariant.class },
			(Object proxy, java.lang.reflect.Method method, @Nullable Object[] arguments) -> switch (method.getName()) {
				case "getFluid" -> { fixture.nativeCall("fluid"); yield fluid; }
				case "getComponents" -> { fixture.nativeCall("patch"); yield patch; }
				default -> throw new AssertionError("unexpected fluid variant callback: " + method.getName());
			});
	}

	@Test void routeSlotMappingStateAndEntityChangesInvalidateWithoutReadingContent() {
		for (String change : List.of("route", "mapping", "count", "state", "entity", "unloaded", "removed")) {
			var fixture = new Fixture(true); Access access = fixture.open(); fixture.clear();
			switch (change) {
				case "route" -> fixture.route = fixture.storage(true);
				case "mapping" -> java.util.Collections.swap(fixture.views, 0, 1);
				case "count" -> fixture.count = 1;
				case "state" -> fixture.state = Blocks.STONE.defaultBlockState();
				case "entity" -> fixture.entity = new BeehiveBlockEntity(BlockPos.ZERO, fixture.state);
				case "unloaded" -> fixture.loaded = false;
				case "removed" -> Objects.requireNonNull(fixture.entity).setRemoved();
			}
			assertFalse(access.valid(allowance(access.validationCost())), change);
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("invalid route")));
			assertEquals(0, fixture.called("amount"));
		}
	}

	@Test void fixedWrappersCannotHideAnUnderlyingSlotSwapBetweenIncrementalReads() {
		var fixture = new Fixture(true); var a = new View(fixture); var b = new View(fixture);
		a.amount = 17; b.amount = 23;
		var first = fixture.views.get(0); var second = fixture.views.get(1);
		first.delegateTo(a); second.delegateTo(b);
		Access access = fixture.open();
		long[] total = { 0 };
		access.consume(0, entry -> { total[0] += entry.amount(); return true; }, allowance(access.readCost()));
		assertEquals(17, total[0]);
		first.delegateTo(b); second.delegateTo(a); fixture.clear();
		assertFalse(access.valid(allowance(access.validationCost())), "same wrappers/count with changed physical addresses is not continuous");
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(1, entry -> {
			total[0] += entry.amount(); return true;
		}, allowance(access.readCost())));
		assertEquals(17, total[0], "must not append another 17 and accept the incorrect 34 instead of 40");
		assertEquals(0, fixture.called("amount")); assertEquals(0, fixture.called("resource"));
	}

	@Test void newWrappersForStableUnderlyingSlotsRemainValidAndReadsUseTheCurrentWrapper() {
		var fixture = new Fixture(true); fixture.freshWrappers = true;
		fixture.views.get(0).amount = 17; fixture.views.get(1).amount = 23;
		Access access = fixture.open(); fixture.clear();
		assertTrue(access.valid(allowance(access.validationCost()))); assertEquals(0, fixture.called("amount"));
		var amounts = new ArrayList<Long>();
		assertEquals(new Visit(2, true), access.observe(2, entry -> { amounts.add(entry.amount()); return true; }, allowance(access.observationCost(2))));
		assertEquals(List.of(17L, 23L), amounts);
		assertEquals(6, fixture.called("wrapperContent"), "amount/blank/resource must go through each current wrapper");
		assertEquals(10, fixture.called("slot"), "one validation and two pre/post read checks, without extra observe walks");
	}

	@Test void newWrappersCannotRebindASlotAfterDetachOrCountPreservingRemap() {
		for (String change : List.of("detach", "remap", "route")) {
			var fixture = new Fixture(true); fixture.freshWrappers = true;
			fixture.views.get(0).amount = 17; fixture.views.get(1).amount = 23;
			Access access = fixture.open();
			access.consume(0, entry -> { assertEquals(17, entry.amount()); return true; });
			if (change.equals("detach")) fixture.count = 1;
			else if (change.equals("route")) fixture.route = fixture.storage(true);
			else java.util.Collections.swap(fixture.views, 0, 1);
			fixture.clear();
			assertFalse(access.valid(allowance(access.validationCost())));
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(1, entry -> fail("changed graph cannot rebind"), allowance(access.readCost())));
			assertEquals(0, fixture.called("amount")); assertEquals(0, fixture.called("wrapperContent"));
		}
	}

	@Test void freshWrapperAtTheMaximumUnderlyingDepthFitsTheReadProfileAndStillChecksRevocation() {
		var fixture = new Fixture(true); fixture.count = 1; fixture.freshWrappers = true;
		var tail = fixture.views.getFirst();
		for (int depth = 2; depth < PlatformFluidServiceImpl.MAX_UNDERLYING_VIEWS; depth++) {
			var next = new View(fixture); tail.underlying = next.nativeView; tail = next;
		}
		Access access = fixture.open(); fixture.clear();
		assertTrue(access.consume(0, entry -> true, allowance(access.readCost())));
		assertEquals(3L * PlatformFluidServiceImpl.MAX_UNDERLYING_VIEWS, fixture.called("underlying"));
		fixture.clear();
		fixture.after = operation -> { if (operation.equals("underlying")) fixture.authority = false; };
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("revoked wrapper"), allowance(access.readCost())));
		assertEquals(1, fixture.called("underlying")); assertEquals(0, fixture.called("wrapperContent"));
	}

	@Test void freshReplacementCannotHideTheConsumedWrappersOwnAddressRemap() {
		var fixture = new Fixture(true); fixture.freshWrappers = true;
		Access access = fixture.open(); var foreign = new View(fixture);
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
			Objects.requireNonNull(fixture.lastWrapper).delegateTo(foreign); return true;
		}, allowance(access.readCost())));
	}

	@Test void newWrapperAdmissionRefusalCannotAcquireMetadataOrAuthorityAndExactGrantsWork() {
		var fixture = new Fixture(true); fixture.freshWrappers = true; Access access = fixture.open(); fixture.clear();
		for (var required : List.of(access.validationCost(), access.readCost(), access.observationCost(2))) {
			var denied = allowance(new InventoryValidationBudget.Cost(required.allocationBytes(), required.workspaceBytes(), required.workUnits() - 1));
			if (required.equals(access.validationCost())) assertThrows(CaptureLimitException.class, () -> access.valid(denied));
			else if (required.equals(access.readCost())) assertThrows(CaptureLimitException.class, () -> access.consume(0, entry -> fail("denied visit"), denied));
			else assertThrows(CaptureLimitException.class, () -> access.observe(2, entry -> fail("denied pass"), denied));
			assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.memberChecks); assertEquals(0, fixture.authorityChecks);
		}
		assertTrue(access.consume(1, entry -> true, allowance(access.readCost())));
		assertEquals(3, fixture.called("wrapperContent"));
	}

	@Test void multiSlotNewWrapperPassDetachesBeforeTheNextProviderMetadataCallback() {
		var fixture = new Fixture(true); fixture.freshWrappers = true;
		var firstPatch = DataComponentPatch.builder().set(DataComponents.DAMAGE, 17).build();
		fixture.views.get(0).variant = new FluidVariantImpl(Fluids.WATER, firstPatch);
		fixture.views.get(0).amount = 17; fixture.views.get(1).amount = 23;
		Access access = fixture.open(); fixture.clear();
		var amounts = new ArrayList<Long>();
		fixture.after = operation -> {
			if (operation.equals("loaded") && fixture.called("resource") > 0) {
				assertFalse(amounts.isEmpty(), "source must detach before provider postchecks");
				fixture.views.get(0).variant = new FluidVariantImpl(Fluids.LAVA, DataComponentPatch.EMPTY);
			}
		};
		assertEquals(new Visit(2, true), access.observe(2, entry -> {
			if (amounts.isEmpty()) {
				var resident = assertInstanceOf(Components.class, entry.resident());
				assertSame(firstPatch, resident.patch()); assertSame(Fluids.WATER, resident.fluid());
			}
			amounts.add(entry.amount()); return true;
		}, allowance(access.observationCost(2))));
		assertEquals(List.of(17L, 23L), amounts);
	}

	@Test void registryIdLengthIsCheckedWithoutFormattingAnOversizedId() {
		var maximum = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "x".repeat(251));
		assertEquals(256, PlatformFluidServiceImpl.registryId(maximum).length());
		var oversized = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "x".repeat(65536));
		var failure = assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.registryId(oversized));
		assertEquals("fluid source registry identity unavailable", failure.getMessage());
		assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.registryId(null));
	}

	@Test void metadataCallbackCountsStayLinearAndIndexedObserveAddsNoDuplicateWholePass() {
		for (boolean fresh : List.of(false, true)) {
			var fixture = new Fixture(true); fixture.freshWrappers = fresh; Access access = fixture.open(); fixture.clear();
			assertTrue(access.valid(allowance(access.validationCost())));
			int depth = fresh ? 2 : 1;
			assertEquals(2, fixture.called("slot")); assertEquals(2L * depth, fixture.called("underlying"));
			assertEquals(13 + 4 + 4 * depth, fixture.memberChecks);
			assertEquals(fixture.memberChecks, fixture.authorityChecks);
			fixture.clear();
			assertTrue(access.consume(0, entry -> true, allowance(access.readCost())));
			assertEquals(4, fixture.called("slot")); assertEquals((fresh ? 5L : 4L) * depth, fixture.called("underlying"));
			assertEquals(fresh ? 37 + 10 * depth : 37 + 8 * depth, fixture.memberChecks);
			fixture.clear();
			assertEquals(new Visit(2, true), access.observe(2, entry -> true, allowance(access.observationCost(2))));
			assertEquals(8, fixture.called("slot")); assertEquals((fresh ? 10L : 8L) * depth, fixture.called("underlying"));
			assertEquals(fresh ? 72 + 20 * depth : 72 + 16 * depth, fixture.memberChecks);
		}
	}

	@Test void defaultMemoryAndWorkCanAdmitThePlatformPortWithoutInventingASecondLedger() {
		var fixture = new Fixture(true); fixture.freshWrappers = true;
		fixture.count = InventorySourceAccess.MAX_SLOTS;
		while (fixture.views.size() < fixture.count) fixture.views.add(new View(fixture));
		Access access = fixture.open();
		long defaultMemory = 1024L * 1024 * nx.pingwheel.common.config.InventoryLimits.DEFAULT_PENDING_MEMORY_MIB;
		long tickWork = InventorySourceAccess.MAX_SLOTS * nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.workUpperBound(
			nx.pingwheel.common.config.InventoryLimits.DISPLAY_BASE_BYTES);
		assertTrue(fixture.service.resolutionCost().workspaceBytes() < defaultMemory);
		assertTrue(fixture.service.resolutionCost().workUnits() < tickWork);
		assertTrue(access.readCost().workspaceBytes() < defaultMemory);
		assertTrue(access.readCost().workUnits() < tickWork);
		fixture.clear();
		assertTrue(access.valid(allowance(access.validationCost())));
		assertEquals(2L + 3L * fixture.count, fixture.called("lookup") + fixture.called("count")
			+ fixture.called("slot") + fixture.called("underlying"));
		assertTrue(access.consume(fixture.count - 1, entry -> true, allowance(access.readCost())));
		// This is the platform envelope only. Common source authority/lock/freezer reservations
		// must independently fit the SAME Runtime ledgers before end-to-end progress is established.
	}

	@Test void underlyingRemapDuringVisitorRejectsTheProvisionalReadAtThePostcheck() {
		var fixture = new Fixture(true); fixture.count = 1;
		var a = new View(fixture); var b = new View(fixture); var wrapper = fixture.views.getFirst();
		wrapper.delegateTo(a); Access access = fixture.open(); fixture.clear();
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
			wrapper.delegateTo(b); return true;
		}, allowance(access.readCost())));
		assertEquals(2, fixture.called("amount"), "only the authorized wrapper and its delegate may be read");
		assertFalse(access.valid(allowance(access.validationCost())));
	}

	@Test void nestedUnderlyingAddressChangesInvalidateButContentsAlwaysUseTheReadWrapper() {
		var fixture = new Fixture(true); fixture.count = 1;
		var root = fixture.views.getFirst(); var middle = new View(fixture); var a = new View(fixture); var b = new View(fixture);
		root.underlying = middle.nativeView; middle.underlying = a.nativeView;
		root.amount = 17; a.amount = -1;
		Access access = fixture.open(); fixture.clear();
		access.consume(0, entry -> { assertEquals(17, entry.amount()); return true; }, allowance(access.readCost()));
		assertEquals(1, fixture.called("amount"), "underlying address metadata must not bypass the wrapper's content getters");
		middle.underlying = b.nativeView; fixture.clear();
		assertFalse(access.valid(allowance(access.validationCost()))); assertEquals(0, fixture.called("amount"));
	}

	@Test void underlyingMetadataRevocationStopsImmediatelyDuringResolutionValidationAndReadPostcheck() {
		for (String phase : List.of("resolve", "validate", "read", "postcheck")) {
			var fixture = new Fixture(true); fixture.count = 1;
			var middle = new View(fixture); fixture.views.getFirst().underlying = middle.nativeView;
			if (phase.equals("resolve")) {
				fixture.after = operation -> { if (operation.equals("underlying")) fixture.authority = false; };
				assertThrows(InventorySourceUnavailableException.class, fixture::open);
			} else {
				Access access = fixture.open(); fixture.clear();
				fixture.after = operation -> {
					if (operation.equals("underlying") && (!phase.equals("postcheck") || fixture.called("amount") > 0)) fixture.membership = false;
				};
				if (phase.equals("validate")) assertThrows(InventorySourceUnavailableException.class, () -> access.valid(allowance(access.validationCost())));
				else assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
					assertEquals("postcheck", phase, "pre-read revocation must not hand off content"); return true;
				}, allowance(access.readCost())));
			}
			assertEquals("underlying", fixture.calls.getLast());
			if (!phase.equals("postcheck")) assertEquals(0, fixture.called("amount"));
		}
	}

	@Test void underlyingMetadataChainsAreFiniteAndMalformedOrThrowingMetadataNeverBecomesEmpty() {
		for (String shape : List.of("null", "cycle", "tooDeep", "throws")) {
			var fixture = new Fixture(true); fixture.count = 1; var root = fixture.views.getFirst();
			switch (shape) {
				case "null" -> root.underlying = null;
				case "cycle" -> { var middle = new View(fixture); root.underlying = middle.nativeView; middle.underlying = root.nativeView; }
				case "tooDeep" -> {
					for (int depth = 0; depth < PlatformFluidServiceImpl.MAX_UNDERLYING_VIEWS; depth++) {
						var next = new View(fixture); root.underlying = next.nativeView; root = next;
					}
				}
				case "throws" -> fixture.after = operation -> { if (operation.equals("underlying")) throw new IllegalArgumentException("native address failure"); };
			}
			if (shape.equals("throws")) assertThrows(IllegalArgumentException.class, fixture::open);
			else assertThrows(InventorySourceUnavailableException.class, fixture::open);
			assertTrue(fixture.called("underlying") <= PlatformFluidServiceImpl.MAX_UNDERLYING_VIEWS);
			assertEquals(0, fixture.called("amount")); assertEquals(0, fixture.called("resource"));
		}
	}

	@Test void metadataProfilesPrepayTheBoundedUnderlyingWalkAndRefusalHasNoCallbacks() {
		var fixture = new Fixture(true); fixture.count = 1; var tail = fixture.views.getFirst();
		for (int depth = 1; depth < PlatformFluidServiceImpl.MAX_UNDERLYING_VIEWS; depth++) {
			var next = new View(fixture); tail.underlying = next.nativeView; tail = next;
		}
		Access access = fixture.open(); fixture.clear();
		access.validationCost(); access.readCost(); access.observationCost(1); fixture.service.resolutionCost();
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.authorityChecks);
		var required = access.validationCost();
		var denied = allowance(new InventoryValidationBudget.Cost(required.allocationBytes(), required.workspaceBytes(), required.workUnits() - 1));
		assertThrows(CaptureLimitException.class, () -> access.valid(denied));
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.authorityChecks);
		var paid = allowance(required);
		assertTrue(access.valid(paid));
		assertEquals(PlatformFluidServiceImpl.MAX_UNDERLYING_VIEWS, fixture.called("underlying"));
		assertEquals(required.workUnits(), paid.attemptedWorkUnits()); assertEquals(0, fixture.called("amount"));
		fixture.clear();
		assertTrue(access.consume(0, entry -> true, allowance(access.readCost())));
		assertEquals(2L * PlatformFluidServiceImpl.MAX_UNDERLYING_VIEWS, fixture.called("underlying"));
		assertEquals(1, fixture.called("amount"));
	}

	@Test void sameBlockPropertyAndContentsChangesDoNotInventASourceGeneration() {
		var fixture = new Fixture(true); Access access = fixture.open(); fixture.clear();
		fixture.state = fixture.state.cycle(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL);
		fixture.views.getFirst().amount = 1;
		assertTrue(access.valid(allowance(access.validationCost()))); assertEquals(0, fixture.called("amount"));
		access.consume(0, entry -> { assertEquals(1, entry.amount()); return true; });
	}

	@Test void callbackDrivenMappingOrAuthorityChangeRejectsProvisionalVisitorOutput() {
		for (boolean authority : List.of(true, false)) {
			var fixture = new Fixture(true); Access access = fixture.open();
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
				if (authority) fixture.authority = false; else java.util.Collections.swap(fixture.views, 0, 1);
				return true;
			}));
		}
	}

	@Test void malformedNativeResultsAndThrowsAreFailuresNeverEmptyVisits() {
		var nullSlot = new Fixture(true); nullSlot.nullSlot = true;
		assertThrows(InventorySourceUnavailableException.class, nullSlot::open);
		for (boolean nullIterator : List.of(true, false)) {
			var fixture = new Fixture(false); Access access = fixture.open();
			if (nullIterator) fixture.nullIterator = true; else fixture.nullView = true;
			assertThrows(InventorySourceUnavailableException.class, () -> access.observe(1, entry -> fail("null iterator/view became empty")));
		}
		for (String invalid : List.of("negative", "blank", "nullVariant", "emptyFluid", "nullFluid", "nullPatch")) {
			var fixture = new Fixture(true); Access access = fixture.open(); var view = fixture.views.getFirst();
			switch (invalid) {
				case "negative" -> view.amount = -1;
				case "blank" -> view.blank = true;
				case "nullVariant" -> view.variant = null;
				case "emptyFluid" -> view.variant = variant(fixture, Fluids.EMPTY, DataComponentPatch.EMPTY);
				case "nullFluid" -> view.variant = variant(fixture, null, DataComponentPatch.EMPTY);
				case "nullPatch" -> view.variant = variant(fixture, Fluids.WATER, null);
			}
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("invalid native value became empty")), invalid);
		}
		for (String failAt : List.of("lookup", "count", "slot", "amount", "blank", "resource", "fluid", "patch")) {
			var fixture = new Fixture(true); Access access = fixture.open(); var failure = new IllegalArgumentException("native failure");
			fixture.views.getFirst().variant = variant(fixture, Fluids.WATER, DataComponentPatch.EMPTY);
			fixture.after = operation -> { if (operation.equals(failAt)) throw failure; };
			assertSame(failure, assertThrows(IllegalArgumentException.class, () -> access.consume(0, entry -> fail("throw became empty"))));
		}
		for (String failAt : List.of("iterator", "hasNext", "next")) {
			var fixture = new Fixture(false); Access access = fixture.open(); var failure = new IllegalArgumentException("iterator failure");
			fixture.after = operation -> { if (operation.equals(failAt)) throw failure; };
			assertSame(failure, assertThrows(IllegalArgumentException.class, () -> access.observe(1, entry -> fail("throw became empty"))));
		}
	}

	@Test void invalidCountsIndicesAndLimitsFailBeforeContentAndProfilesArePure() {
		for (int count : List.of(-1, InventorySourceAccess.MAX_SLOTS + 1)) {
			var fixture = new Fixture(true); fixture.count = count;
			assertThrows(InventorySourceUnavailableException.class, fixture::open); assertEquals(0, fixture.called("slot"));
		}
		var fixture = new Fixture(true); Access access = fixture.open(); fixture.clear();
		assertThrows(IndexOutOfBoundsException.class, () -> access.consume(-1, entry -> true));
		assertThrows(IndexOutOfBoundsException.class, () -> access.consume(2, entry -> true));
		assertThrows(IllegalArgumentException.class, () -> access.observe(-1, entry -> true));
		assertThrows(IllegalArgumentException.class, () -> access.observationCost(InventorySourceAccess.MAX_SLOTS + 1));
		access.validationCost(); access.readCost(); access.observationCost(2); fixture.service.resolutionCost();
		assertTrue(fixture.calls.isEmpty());
	}
}
