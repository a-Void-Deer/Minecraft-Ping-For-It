package nx.pingwheel.forge.platform;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.presentation.inventory.InventoryQuantities;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production route/gates with recording native metadata and borrowed-value seams. Not a Forge launch:
 * the task's vanilla-first Minecraft cannot run Forge FluidStack's Holder.Reference.get extension.
 * The real content dispatch is separately exercised for throwing/null getters without booting registries.
 */
@NotNullByDefault
class PlatformFluidServiceTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void serviceRegistrationAndForgeNbtAbiArePresentWithoutResolvingAWorld() throws ReflectiveOperationException {
		assertInstanceOf(PlatformFluidServiceImpl.class, IPlatformFluidService.INSTANCE);
		assertEquals(CompoundTag.class, FluidStack.class.getMethod("getTag").getReturnType());
		assertEquals(int.class, FluidStack.class.getMethod("getAmount").getReturnType());
		assertEquals(FluidStack.class, IFluidHandler.class.getMethod("getFluidInTank", int.class).getReturnType());
	}

	@Test void resolutionIsMetadataOnlyAndLayoutKeepsExactNativeTankAddressesAndFace() {
		for (Direction face : Direction.values()) {
			var fixture = new Fixture(face);
			var access = fixture.open();
			assertEquals(0, fixture.reads);
			assertTrue(fixture.calls.contains("capability")); assertTrue(fixture.calls.contains("capacity"));
			fixture.calls.clear();
			assertTrue(access.stableCursor()); assertEquals(2, access.slots()); assertTrue(access.alias().isEmpty());
			var layout = access.layout();
			assertNull(layout.controller()); assertEquals(face.getName(), layout.layoutData());
			assertEquals(1, layout.members().size()); assertEquals(BlockPos.ZERO, layout.members().getFirst().position());
			assertEquals("minecraft:beehive", layout.members().getFirst().blockId());
			var segment = layout.members().getFirst().segments().getFirst();
			assertEquals(IPlatformFluidService.ADDRESS_SCHEMA, segment.schemaId());
			assertEquals(2, segment.localSlots()); assertEquals(List.of(0, 1), segment.visibleSlots());
			assertThrows(UnsupportedOperationException.class, () -> segment.visibleSlots().add(2));
			assertSame(access.validationCost(), access.validationCost()); assertSame(access.readCost(), access.readCost());
			assertTrue(fixture.calls.isEmpty(), "frozen descriptors/profiles must not reacquire native metadata");
			assertTrue(access.valid()); assertEquals(0, fixture.reads, "valid must never inspect fluid contents");
		}
	}

	@Test void refusedAllowanceMakesNoNativeCallAndSpentAttemptCannotBeReused() {
		var fixture = new Fixture(Direction.NORTH);
		var zero = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> fixture.service.find(fixture.context, zero));
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.authorityChecks);
		var access = fixture.open(); fixture.calls.clear();
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.valid(zero));
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.consume(0, entry -> fail("denied visitor"), zero));
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.observe(2, entry -> fail("denied observer"), zero));
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.reads);
		var paid = new InventoryValidationBudget.Allowance(access.readCost());
		assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> { throw new IllegalStateException("freezer refused"); }, paid));
		assertEquals(1, fixture.reads); assertEquals(access.readCost().workUnits(), paid.attemptedWorkUnits());
		assertEquals(access.readCost().allocationBytes(), paid.attemptedAllocationBytes());
		fixture.calls.clear();
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.consume(0, entry -> true, paid));
		assertTrue(fixture.calls.isEmpty()); assertEquals(1, fixture.reads);
	}

	@Test void emptyAddressesArePaidAndVisitorRefusalEvenOnLastAddressIsNotCompletion() {
		var fixture = new Fixture(Direction.EAST); fixture.amount = 0;
		var access = fixture.open(); AtomicInteger visits = new AtomicInteger();
		var paid = new InventoryValidationBudget.Allowance(access.observationCost(2));
		assertEquals(new IPlatformFluidService.Visit(2, true), access.observe(2, entry -> {
			assertNull(entry.resident()); assertEquals(0, entry.amount()); visits.incrementAndGet(); return true;
		}, paid));
		assertEquals(2, fixture.reads); assertEquals(2, visits.get());
		assertEquals(access.observationCost(2).workUnits(), paid.attemptedWorkUnits());
		assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(1, entry -> true));
		visits.set(0);
		assertEquals(new IPlatformFluidService.Visit(2, false), access.observe(2, entry -> visits.incrementAndGet() < 2));
		assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(2, entry -> false));
		assertEquals(6, fixture.reads, "every call begins at tank zero; no retained synthetic cursor");
	}

	@Test void zeroTankObservationStillValidatesAndChangedEmptyRouteCannotComplete() {
		var fixture = new Fixture(Direction.UP); fixture.capacities = new int[0];
		var access = fixture.open(); fixture.calls.clear();
		assertEquals(new IPlatformFluidService.Visit(0, true), access.observe(0, entry -> fail("no addresses"),
			new InventoryValidationBudget.Allowance(access.observationCost(0))));
		assertTrue(fixture.calls.contains("count")); assertEquals(0, fixture.reads);
		fixture.capability.invalidate();
		assertThrows(InventorySourceUnavailableException.class, () -> access.observe(0, entry -> true));
	}

	@Test void completeNativeNbtIsBorrowedBeforeNextProviderOrAuthorityCallbackAndScalarIsLongU() {
		var fixture = new Fixture(Direction.SOUTH); fixture.amount = Integer.MAX_VALUE;
		var nested = new CompoundTag(); nested.putLongArray("precise", new long[] {Long.MAX_VALUE, 81});
		fixture.tag.put("unknown", nested); fixture.tag.putString("FluidName", "variant-owned-field");
		fixture.tag.putInt("Amount", 77); fixture.tag.putString("name", "getter-time");
		var access = fixture.open();
		AtomicReference<@Nullable CompoundTag> frozen = new AtomicReference<>();
		fixture.afterReadGate = () -> fixture.tag.putString("name", "later-authority");
		assertTrue(access.consume(0, entry -> {
			var resident = assertInstanceOf(IPlatformFluidService.Nbt.class, entry.resident());
			assertSame(Fluids.WATER, resident.fluid()); assertSame(fixture.tag, resident.tag());
			assertEquals("getter-time", fixture.tag.getString("name"));
			assertEquals(173_946_175_407L, entry.amount(), "mB conversion must not multiply in int arithmetic");
			frozen.set(Objects.requireNonNull(resident.tag()).copy()); return true;
		}));
		assertEquals("later-authority", fixture.tag.getString("name"));
		CompoundTag detached = Objects.requireNonNull(frozen.get());
		assertEquals("getter-time", detached.getString("name")); assertEquals(77, detached.getInt("Amount"));
		assertEquals("variant-owned-field", detached.getString("FluidName"));
		assertArrayEquals(new long[] {Long.MAX_VALUE, 81}, detached.getCompound("unknown").getLongArray("precise"));
		var absent = assertInstanceOf(IPlatformFluidService.Nbt.class, PlatformFluidServiceImpl.borrowed(Fluids.WATER, null, 1).resident());
		assertNull(absent.tag()); assertEquals(81, PlatformFluidServiceImpl.borrowed(Fluids.WATER, null, 1).amount());
	}

	@Test void invalidAmountsAndOverflowAreFailuresNotEmptyOrClampedValues() {
		var fixture = new Fixture(Direction.DOWN); fixture.amount = -1; var access = fixture.open();
		assertThrows(IllegalArgumentException.class, () -> access.consume(0, entry -> fail("invalid amount must not visit")));
		assertEquals(1, fixture.reads);
		assertThrows(IllegalArgumentException.class, () -> PlatformFluidServiceImpl.borrowed(Fluids.WATER, null, Integer.MIN_VALUE));
		assertThrows(IllegalStateException.class, () -> PlatformFluidServiceImpl.borrowed(Fluids.EMPTY, null, 1));
		assertThrows(ArithmeticException.class, () -> InventoryQuantities.fromMillibuckets(Long.MAX_VALUE));
		assertEquals(0, PlatformFluidServiceImpl.borrowed(Fluids.EMPTY, null, 0).amount());
	}

	@Test void realNativeContentDispatchPropagatesThrowAndRefusesNullInsteadOfEmpty() {
		for (boolean throwing : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.WEST); fixture.nativeContent = true;
			var failure = new IllegalStateException("native fluid getter failed");
			if (throwing) fixture.contentFailure = failure;
			var access = fixture.open();
			var caught = assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> fail("failed native getter cannot visit")));
			if (throwing) assertSame(failure, caught);
			else assertEquals("null native fluid stack", caught.getMessage());
			assertEquals(1, fixture.reads);
		}
	}

	@Test void everyLookupAndMetadataCallbackIsFollowedByCurrentScopeBeforeNextNativeCall() {
		@Unmodifiable List<String> callbacks = List.of("loaded", "state", "entity", "capability", "resolve", "count", "capacity");
		for (String at : callbacks) {
			var fixture = new Fixture(Direction.NORTH);
			fixture.afterNative = operation -> { if (operation.equals(at)) fixture.authority = false; };
			assertThrows(InventorySourceUnavailableException.class, fixture::open);
			assertEquals(at, fixture.calls.getLast()); assertEquals(0, fixture.reads);
		}
		var denied = new Fixture(Direction.NORTH); denied.member = false;
		assertThrows(InventorySourceUnavailableException.class, denied::open); assertTrue(denied.calls.isEmpty());
		var wrongThread = new Fixture(Direction.NORTH); wrongThread.serverThread(new Thread());
		assertThrows(InventorySourceUnavailableException.class, wrongThread::open); assertTrue(wrongThread.calls.isEmpty());
	}

	@Test void frozenRouteRequiresOriginalCapabilityHandlerEntityStateAndOrderedTankMetadata() {
		assertInvalidAfter(fixture -> fixture.loaded = false);
		assertInvalidAfter(fixture -> fixture.state = Blocks.STONE.defaultBlockState());
		assertInvalidAfter(fixture -> fixture.entity = new BeehiveBlockEntity(BlockPos.ZERO, fixture.state));
		assertInvalidAfter(fixture -> fixture.entity.setRemoved());
		assertInvalidAfter(fixture -> fixture.capability = LazyOptional.of(() -> fixture.handler));
		assertInvalidAfter(fixture -> { fixture.handler = fixture.newHandler(); fixture.capability = LazyOptional.of(() -> fixture.handler); });
		assertInvalidAfter(fixture -> fixture.capability.invalidate());
		assertInvalidAfter(fixture -> fixture.capacities = new int[] {2000, 1000});
		assertInvalidAfter(fixture -> fixture.capacities = new int[] {1000});
		var fixture = new Fixture(Direction.NORTH); var access = fixture.open(); fixture.authority = false; fixture.calls.clear();
		assertThrows(InventorySourceUnavailableException.class, access::valid);
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> true));
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.reads);
	}

	@Test void borrowedResidentIsVisitedBeforeRevocationButNeverCommitsAndNoSecondReadCanFollow() {
		for (boolean revokeInVisitor : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.EAST); var access = fixture.open(); AtomicInteger visits = new AtomicInteger();
			if (!revokeInVisitor) fixture.afterReadGate = () -> fixture.authority = false;
			assertThrows(InventorySourceUnavailableException.class, () -> access.observe(2, entry -> {
				visits.incrementAndGet(); assertEquals(7 * 81L, entry.amount());
				if (revokeInVisitor) fixture.member = false;
				return true;
			}));
			assertEquals(1, visits.get()); assertEquals(1, fixture.reads);
			fixture.calls.clear();
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(1, entry -> true));
			assertTrue(fixture.calls.isEmpty()); assertEquals(1, fixture.reads);
		}
	}

	@Test void metadataCallbackCannotReplaceTheRouteBeforeAnOldHandlerReadsContent() {
		var fixture = new Fixture(Direction.NORTH); var access = fixture.open();
		fixture.afterNative = operation -> {
			if (operation.equals("capacity")) fixture.capability = LazyOptional.of(() -> fixture.handler);
		};
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("replaced route must not reach content")));
		assertEquals(0, fixture.reads);
	}

	@Test void capacityCallbackExpansionAndShrinkageCannotPassValidationOrReachContent() {
		for (boolean expand : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.NORTH); var access = fixture.open(); fixture.calls.clear();
			AtomicInteger capacities = new AtomicInteger();
			fixture.afterNative = operation -> {
				if (operation.equals("capacity") && capacities.incrementAndGet() == 2)
					fixture.capacities = expand ? new int[] {1000, 2000, 3000} : new int[] {1000};
			};
			assertFalse(access.valid(), "count changed after the last original capacity was read");
			assertEquals(2, capacities.get(), "closing count must not add another capacity traversal");
			assertEquals(2, fixture.calls.stream().filter("count"::equals).count());
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("changed count must not visit")));
			assertEquals(0, fixture.reads);
		}
	}

	@Test void closingRouteCallbackCountChangeIsDetectedAfterTheCapacityWalk() {
		for (boolean expand : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.NORTH); var access = fixture.open(); fixture.calls.clear();
			AtomicInteger routeChecks = new AtomicInteger();
			fixture.afterNative = operation -> {
				if (operation.equals("capability") && routeChecks.incrementAndGet() == 2)
					fixture.capacities = expand ? new int[] {1000, 2000, 3000} : new int[] {1000};
			};
			assertFalse(access.valid()); assertEquals(0, fixture.reads);
			assertEquals(2, fixture.calls.stream().filter("capacity"::equals).count());
			assertEquals("count", fixture.calls.getLast(), "count must follow closing route callbacks");
		}
	}

	@Test void countChangeOnlyDuringPostcheckCannotCommitTheBorrowedResidentOrContinue() {
		for (boolean expand : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.NORTH); var access = fixture.open();
			AtomicInteger capacities = new AtomicInteger(), visits = new AtomicInteger();
			fixture.afterNative = operation -> {
				if (fixture.readReturned && operation.equals("capacity") && capacities.incrementAndGet() == 2)
					fixture.capacities = expand ? new int[] {1000, 2000, 3000} : new int[] {1000};
			};
			assertThrows(InventorySourceUnavailableException.class, () -> access.observe(2, entry -> {
				visits.incrementAndGet(); assertEquals(567, entry.amount()); return true;
			}));
			assertEquals(1, visits.get()); assertEquals(1, fixture.reads); assertEquals(2, capacities.get());
		}
	}

	@Test void registryIdentityLengthChecksBothPartsBeforeAllocatingTheCombinedString() {
		for (ResourceLocation id : List.of(ResourceLocation.fromNamespaceAndPath("n", "p".repeat(254)),
			ResourceLocation.fromNamespaceAndPath("n".repeat(254), "p"))) {
			assertEquals(256, PlatformFluidServiceImpl.boundedRegistryId(id).length());
			assertEquals(id.toString(), PlatformFluidServiceImpl.boundedRegistryId(id));
		}
		for (ResourceLocation id : List.of(ResourceLocation.fromNamespaceAndPath("n", "p".repeat(255)),
			ResourceLocation.fromNamespaceAndPath("n".repeat(255), "p"),
			ResourceLocation.fromNamespaceAndPath("n".repeat(256), "p"))) {
			assertEquals("fluid registry identity length bound", assertThrows(InventorySourceUnavailableException.class,
				() -> PlatformFluidServiceImpl.boundedRegistryId(id)).getMessage());
		}
		var bean = java.lang.management.ManagementFactory.getThreadMXBean();
		org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
		var allocations = (com.sun.management.ThreadMXBean) bean;
		org.junit.jupiter.api.Assumptions.assumeTrue(allocations.isThreadAllocatedMemorySupported());
		allocations.setThreadAllocatedMemoryEnabled(true);
		long thread = Thread.currentThread().threadId();
		for (boolean oversizedNamespace : new boolean[] {false, true}) {
			String huge = "a".repeat(2_000_000);
			var id = ResourceLocation.fromNamespaceAndPath(oversizedNamespace ? huge : "n", oversizedNamespace ? "p" : huge);
			long before = allocations.getThreadAllocatedBytes(thread);
			assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.boundedRegistryId(id));
			assertTrue(allocations.getThreadAllocatedBytes(thread) - before < 65536,
				"rejection allocated a combined ID or copied resident namespace/path");
			before = allocations.getThreadAllocatedBytes(thread);
			String combined = id.toString();
			assertTrue(allocations.getThreadAllocatedBytes(thread) - before >= 2_000_000,
				"positive control: native conversion really allocates the oversized combined ID");
			assertEquals(2_000_002, combined.length());
		}
	}

	@Test void successfulCallbackProfilesIncludeClosingCountsWithoutAnotherCapacityTraversal() {
		for (int tanks : new int[] {0, 1, 2, 8}) {
			var fixture = new Fixture(Direction.NORTH); fixture.capacities = new int[tanks];
			var access = fixture.open();
			assertEquals(29 + 4 * tanks, fixture.authorityChecks, "successful find currentContext.require count");
			fixture.authorityChecks = 0; fixture.calls.clear(); assertTrue(access.valid());
			assertEquals(18 + 2 * tanks, fixture.authorityChecks);
			assertEquals(tanks, fixture.calls.stream().filter("capacity"::equals).count());
			assertEquals(2, fixture.calls.stream().filter("count"::equals).count());
			fixture.authorityChecks = 0; access.layout(); access.alias(); access.slots(); access.stableCursor();
			assertEquals(12, fixture.authorityChecks, "three fresh requirements per frozen metadata accessor");
			assertEquals(new InventoryValidationBudget.Cost(2048, 2048, 272L + 16L * tanks), access.validationCost());
			assertEquals(new InventoryValidationBudget.Cost(5120, 2048, 672L + 32L * tanks), access.readCost());
			if (tanks > 0) {
				fixture.authorityChecks = 0; fixture.calls.clear(); assertTrue(access.consume(0, entry -> true));
				assertEquals(35 + 4 * tanks, fixture.authorityChecks);
				assertEquals(2 * tanks, fixture.calls.stream().filter("capacity"::equals).count());
				assertEquals(4, fixture.calls.stream().filter("count"::equals).count());
			}
		}
	}

	@Test void ordinaryTankProviderResolutionAndCompleteReadFitUnchangedDefaultLedgers() {
		var settings = InventorySettings.serverDefaults(); settings.validate();
		var memory = new RetainedMemoryLedger(settings.pendingMemoryBytes());
		var workCounter = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
		var work = new CostLedger(Map.of(workCounter,
			InventorySourceAccess.MAX_SLOTS * InventoryItemBudget.workUpperBound(Math.toIntExact(settings.effectiveDisplayBytes())),
			InventorySourceAccess.PHYSICAL, (long) settings.effectivePhysicalSlotsPerTick()));
		var fixture = new Fixture(Direction.NORTH);
		var resolution = fixture.service.resolutionCost();
		IPlatformFluidService.Access access;
		try (var workspace = memory.tryReserve(resolution.workspaceBytes()).orElseThrow();
			var ticket = work.tryReserve(Map.of(workCounter, resolution.workUnits())).orElseThrow()) {
			var allowance = new InventoryValidationBudget.Allowance(resolution);
			access = fixture.service.find(fixture.context, allowance).orElseThrow();
			ticket.commit(Map.of(workCounter, allowance.attemptedWorkUnits()));
		}
		var observation = access.observationCost(2);
		try (var workspace = memory.tryReserve(observation.workspaceBytes()).orElseThrow();
			var ticket = work.tryReserve(Map.of(workCounter, observation.workUnits(), InventorySourceAccess.PHYSICAL, 2L)).orElseThrow()) {
			var allowance = new InventoryValidationBudget.Allowance(observation);
			assertEquals(new IPlatformFluidService.Visit(2, true), access.observe(2, entry -> true, allowance));
			ticket.commit(Map.of(workCounter, allowance.attemptedWorkUnits(), InventorySourceAccess.PHYSICAL, 2L));
		}
		assertEquals(2, fixture.reads); assertEquals(2, work.used(InventorySourceAccess.PHYSICAL));
		assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		// This exercises the provider envelope, not the independently owned common source gates/freezer.
	}

	@Test void postReadRouteChangeAndProviderMutationAreOnlySeenAfterResidentHasDetached() {
		for (boolean changeRoute : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.NORTH); fixture.tag.putString("name", "original"); var access = fixture.open();
			AtomicReference<@Nullable CompoundTag> frozen = new AtomicReference<>();
			fixture.afterNative = operation -> {
				if (fixture.readReturned && operation.equals("loaded")) {
					assertNotNull(frozen.get(), "provider metadata ran before the synchronous consumer detached");
					fixture.tag.putString("name", "provider-reused");
					if (changeRoute) fixture.capability = LazyOptional.of(() -> fixture.handler);
				}
			};
			IPlatformFluidService.Visitor visitor = entry -> {
				var resident = assertInstanceOf(IPlatformFluidService.Nbt.class, entry.resident());
				frozen.set(Objects.requireNonNull(resident.tag()).copy()); return true;
			};
			if (changeRoute) assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, visitor));
			else assertTrue(access.consume(0, visitor));
			assertEquals("original", Objects.requireNonNull(frozen.get()).getString("name"));
			assertEquals("provider-reused", fixture.tag.getString("name")); assertEquals(1, fixture.reads);
		}
	}

	@Test void malformedAddressMetadataAndGetterFailuresPropagateBeforeContent() {
		var fixture = new Fixture(Direction.NORTH); fixture.capacities = new int[] {-1};
		assertThrows(IllegalStateException.class, fixture::open); assertEquals(0, fixture.reads);
		var excessive = new Fixture(Direction.NORTH); excessive.reportedCount = InventorySourceAccess.MAX_SLOTS + 1;
		assertThrows(IllegalStateException.class, excessive::open); assertFalse(excessive.calls.contains("capacity"));
		var negative = new Fixture(Direction.NORTH); negative.reportedCount = -1;
		assertThrows(IllegalStateException.class, negative::open);
		var failing = new Fixture(Direction.NORTH); var access = failing.open(); var failure = new IllegalStateException("metadata failed");
		failing.afterNative = operation -> { if (operation.equals("count")) throw failure; };
		assertSame(failure, assertThrows(IllegalStateException.class, access::valid)); assertEquals(0, failing.reads);
	}

	private static void assertInvalidAfter(Consumer<Fixture> mutation) {
		var fixture = new Fixture(Direction.NORTH); var access = fixture.open(); mutation.accept(fixture);
		assertFalse(access.valid()); assertEquals(0, fixture.reads);
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("invalid route must not visit")));
		assertEquals(0, fixture.reads);
	}

	@NotNullByDefault
	private static final class Fixture implements PlatformFluidServiceImpl.World {
		private final ServerLevel level = allocate(ServerLevel.class);
		private final DedicatedServer server = allocate(DedicatedServer.class);
		private final Direction face;
		private final IPlatformFluidService.Context context;
		private final IPlatformFluidService service;
		private final List<String> calls = new ArrayList<>();
		private final CompoundTag tag = new CompoundTag();
		private BlockState state = Blocks.BEEHIVE.defaultBlockState();
		private BlockEntity entity = new BeehiveBlockEntity(BlockPos.ZERO, state);
		private IFluidHandler handler = newHandler();
		private LazyOptional<IFluidHandler> capability = LazyOptional.of(() -> { nativeCall("resolve"); return handler; });
		private int[] capacities = {1000, 2000};
		private int amount = 7, reads, authorityChecks;
		private @Nullable Integer reportedCount;
		private @Nullable RuntimeException contentFailure;
		private boolean loaded = true, member = true, authority = true, readReturned, nativeContent;
		private Consumer<String> afterNative = operation -> {};
		private Runnable afterReadGate = () -> {};

		private Fixture(Direction face) {
			this.face = face;
			field(ServerLevel.class, "server", level, server); serverThread(Thread.currentThread());
			context = new IPlatformFluidService.Context(level, BlockPos.ZERO, face, new UUID(1, 2), position -> {
				assertEquals(BlockPos.ZERO, position, "generic capability must never discover other members"); return member;
			}, () -> {
				authorityChecks++;
				if (readReturned) afterReadGate.run();
				return authority;
			});
			service = new TestService(this);
		}
		private void serverThread(Thread thread) { field(MinecraftServer.class, "serverThread", server, thread); }
		private IPlatformFluidService.Access open() {
			return service.find(context, new InventoryValidationBudget.Allowance(service.resolutionCost())).orElseThrow();
		}
		private void nativeCall(String operation) {
			assertTrue(member && authority, "native callback after current scope revoked: " + operation);
			calls.add(operation); afterNative.accept(operation);
		}
		private IFluidHandler newHandler() {
			return (IFluidHandler) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IFluidHandler.class},
				(Object proxy, java.lang.reflect.Method method, Object @Nullable [] arguments) -> {
				return switch (method.getName()) {
					case "getTanks" -> { nativeCall("count"); yield reportedCount == null ? capacities.length : reportedCount; }
					case "getTankCapacity" -> { int capacity = capacities[(int) Objects.requireNonNull(arguments)[0]]; nativeCall("capacity"); yield capacity; }
					case "getFluidInTank" -> { nativeCall("content"); reads++; if (contentFailure != null) throw contentFailure; yield null; }
					default -> throw new AssertionError("forbidden/unexpected fluid operation: " + method.getName());
				};
			});
		}
		@Override public boolean loaded(BlockPos position) { nativeCall("loaded"); return loaded; }
		@Override public BlockState state(BlockPos position) { nativeCall("state"); return state; }
		@Override public @Nullable BlockEntity blockEntity(BlockPos position) { nativeCall("entity"); return entity; }
		@Override public LazyOptional<IFluidHandler> capability(BlockEntity current, Direction side) {
			assertSame(entity, current); assertSame(face, side); nativeCall("capability"); return capability;
		}
		@Override public IPlatformFluidService.Entry content(IFluidHandler current, int tank) {
			assertSame(handler, current);
			if (nativeContent) return PlatformFluidServiceImpl.World.super.content(current, tank);
			nativeCall("content"); reads++; readReturned = true;
			return PlatformFluidServiceImpl.borrowed(Fluids.WATER, tag, amount);
		}
	}

	@NotNullByDefault
	private record TestService(Fixture fixture) implements IPlatformFluidService {
		@Override public InventoryValidationBudget.Cost resolutionCost() { return new PlatformFluidServiceImpl().resolutionCost(); }
		@Override public Optional<Access> resolve(Context context) { return PlatformFluidServiceImpl.resolve(context, fixture); }
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static void field(Class<?> owner, String name, Object target, Object value) {
		try { var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
