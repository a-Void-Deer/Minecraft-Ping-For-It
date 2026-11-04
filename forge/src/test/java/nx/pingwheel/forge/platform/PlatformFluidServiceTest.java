package nx.pingwheel.forge.platform;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.presentation.inventory.InventoryQuantities;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
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
 * the ordinary task's vanilla-first Minecraft cannot run Forge FluidStack's Holder.Reference.get extension.
 * The real content dispatch is separately exercised for throwing/null getters without booting registries.
 * The native source task borrows the same recording route with constructor-free block-entity setup.
 */
@NotNullByDefault
class PlatformFluidServiceTest {
	private static final CostLedger.Counter WORK = new CostLedger.Counter("forge-fluid-test", CostLedger.Unit.WORK);
	private static final InventoryValidationBudget.Cost MEMBER = new InventoryValidationBudget.Cost(32, 16, 3);
	private static final InventoryValidationBudget.Cost AUTHORITY = new InventoryValidationBudget.Cost(48, 32, 5);
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
			assertNull(layout.controller()); assertEquals(face.getName() + ";capacities=1000,2000,", layout.layoutData());
			assertEquals(1, layout.members().size()); assertEquals(BlockPos.ZERO, layout.members().getFirst().position());
			assertEquals("minecraft:beehive", layout.members().getFirst().blockId());
			var segment = layout.members().getFirst().segments().getFirst();
			assertEquals(IPlatformFluidService.ADDRESS_SCHEMA, segment.schemaId());
			assertEquals(2, segment.localSlots()); assertEquals(List.of(0, 1), segment.visibleSlots());
			assertThrows(UnsupportedOperationException.class, () -> segment.visibleSlots().add(2));
			assertEquals(access.delegate.validationProfile(), access.delegate.validationProfile()); assertEquals(access.delegate.readProfile(), access.delegate.readProfile());
			assertEquals(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, access.delegate.routeInfo().topology());
			assertEquals(fixture.handler.getClass().getName(), access.delegate.routeInfo().providerClass());
			assertTrue(fixture.calls.isEmpty(), "frozen descriptors/profiles must not reacquire native metadata");
			assertTrue(access.valid()); assertEquals(0, fixture.reads, "valid must never inspect fluid contents");
		}
	}

	@Test void refusedAllowanceMakesNoNativeCallAndSpentAttemptCannotBeReused() {
		var fixture = new Fixture(Direction.NORTH);
		try (var denied = fixture.admission(0)) {
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> denied.enter(fixture.service.probeProfile()));
		}
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.authorityChecks);
		var access = fixture.open(); fixture.calls.clear();
		try (var denied = fixture.admission(0)) {
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> denied.enter(access.delegate.validationProfile()));
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> denied.enter(access.delegate.readProfile()));
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> denied.enter(access.delegate.observationProfile(2)));
		}
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.reads);
		try (var admission = fixture.admission(1_000_000); var paid = admission.enter(access.delegate.readProfile())) {
			assertThrows(IllegalStateException.class, () -> access.delegate.consume(0, entry -> { throw new IllegalStateException("freezer refused"); }, paid));
			assertEquals(1, fixture.reads);
			fixture.calls.clear();
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.delegate.consume(0, entry -> true, paid));
			assertTrue(fixture.calls.isEmpty()); assertEquals(1, fixture.reads);
		}
	}

	@Test void emptyAddressesArePaidAndVisitorRefusalEvenOnLastAddressIsNotCompletion() {
		var fixture = new Fixture(Direction.EAST); fixture.amount = 0;
		var access = fixture.open(); AtomicInteger visits = new AtomicInteger();
		assertEquals(new IPlatformFluidService.Visit(2, true), access.observe(2, entry -> {
			assertNull(entry.resident()); assertEquals(0, entry.amount()); visits.incrementAndGet(); return true;
		}));
		assertEquals(2, fixture.reads); assertEquals(2, visits.get());
		assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(1, entry -> true));
		visits.set(0);
		assertEquals(new IPlatformFluidService.Visit(2, false), access.observe(2, entry -> visits.incrementAndGet() < 2));
		assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(2, entry -> false));
		assertEquals(6, fixture.reads, "every call begins at tank zero; no retained synthetic cursor");
	}

	@Test void zeroTankObservationStillValidatesAndChangedEmptyRouteCannotComplete() {
		var fixture = new Fixture(Direction.UP); fixture.capacities = new int[0];
		var access = fixture.open(); fixture.calls.clear();
		assertEquals(new IPlatformFluidService.Visit(0, true), access.observe(0, entry -> fail("no addresses")));
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
			assertEquals(35 + 4 * tanks, fixture.authorityChecks, "probe and admitted finish requirements");
			fixture.authorityChecks = 0; fixture.calls.clear(); assertTrue(access.valid());
			assertEquals(18 + 2 * tanks, fixture.authorityChecks);
			assertEquals(tanks, fixture.calls.stream().filter("capacity"::equals).count());
			assertEquals(2, fixture.calls.stream().filter("count"::equals).count());
			fixture.authorityChecks = 0; access.layout(); access.alias(); access.slots(); access.stableCursor();
			assertEquals(0, fixture.authorityChecks, "all metadata accessors are pure, not cached permission grants");
			assertEquals(new InventoryValidationBudget.Cost(2304, 2048, 288L + 16L * tanks), access.delegate.validationProfile().ownedCost());
			assertEquals(new InventoryValidationBudget.Cost(5376, 2048, 688L + 32L * tanks), access.delegate.readProfile().ownedCost());
			assertEquals(18 + 2L * tanks, access.delegate.validationProfile().memberGateCalls());
			assertEquals(35 + 4L * tanks, access.delegate.readProfile().currentAuthorityCalls());
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
		IPlatformFluidService.Access access;
		try (var admission = new InventoryFluidAdmission(work, workCounter, memory, MEMBER, AUTHORITY)) {
			try (var operation = admission.enter(fixture.service.probeProfile()); var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
				access = IPlatformFluidService.guarded(fixture.context, probe.finish(admission).orElseThrow());
			}
			try (var operation = admission.enter(access.observationProfile(2));
				var slots = work.tryReserve(Map.of(InventorySourceAccess.PHYSICAL, 2L)).orElseThrow()) {
				assertEquals(new IPlatformFluidService.Visit(2, true), access.observe(2, entry -> true, operation));
				slots.commit(Map.of(InventorySourceAccess.PHYSICAL, 2L));
			}
			access.close();
		}
		assertEquals(2, fixture.reads); assertEquals(2, work.used(InventorySourceAccess.PHYSICAL));
		assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		// Real callback composition/ledgers, not the independently owned common world source/freezer.
	}

	@Test void probeIsConstantWithoutCapacityScanAndFinishScalesOnlyWithDiscoveredShape() {
		@Nullable IPlatformFluidService.OperationProfile first = null;
		for (int tanks : new int[] {1, 8}) {
			var fixture = new Fixture(Direction.NORTH); fixture.capacities = new int[tanks];
			try (var admission = fixture.admission(1_000_000); var operation = admission.enter(fixture.service.probeProfile());
				var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
				assertEquals(8, fixture.memberChecks); assertEquals(8, fixture.authorityChecks);
				assertEquals(1, fixture.calls.stream().filter("count"::equals).count());
				assertFalse(fixture.calls.contains("capacity")); assertEquals(0, fixture.reads);
				fixture.calls.clear(); int gates = fixture.authorityChecks;
				assertEquals(new IPlatformFluidService.Shape(1, OptionalInt.of(tanks), true), probe.shape());
				var finish = probe.finishProfile(); assertSame(finish, probe.finishProfile());
				assertTrue(fixture.calls.isEmpty()); assertEquals(gates, fixture.authorityChecks);
				assertEquals(8896 + 322L * tanks, finish.ownedCost().allocationBytes());
				assertEquals(8896 + 322L * tanks, finish.ownedCost().workspaceBytes());
				assertEquals(4480 + 152L * tanks, finish.ownedCost().workUnits());
				assertEquals(8384 + 86L * tanks, finish.retainedBytes());
				assertEquals(27 + 4L * tanks, finish.memberGateCalls());
				if (first != null) {
					assertEquals(7 * 322L, finish.ownedCost().allocationBytes() - first.ownedCost().allocationBytes());
					assertEquals(7 * 86L, finish.retainedBytes() - first.retainedBytes());
				}
				first = finish;
				var access = probe.finish(admission).orElseThrow();
				assertEquals(27 + 4 * tanks, fixture.authorityChecks - gates);
				assertEquals(2 * tanks, fixture.calls.stream().filter("capacity"::equals).count(),
					"finish constructs one witness and retains the established closing validation");
				assertEquals(0, fixture.reads); access.close();
				assertThrows(IllegalStateException.class, () -> probe.finish(admission));
			}
		}
	}

	@Test void maximumLegalShapeRemainsPreciselyAdmittedAndCallbackCountsDoNotExceedProfile() {
		var fixture = new Fixture(Direction.NORTH); int tanks = InventorySourceAccess.MAX_SLOTS; fixture.capacities = new int[tanks];
		try (var admission = fixture.admission(10_000_000); var operation = admission.enter(fixture.service.probeProfile());
			var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
			int before = fixture.authorityChecks; var profile = probe.finishProfile();
			assertEquals(1_327_808, profile.ownedCost().allocationBytes()); assertEquals(360_640, profile.retainedBytes());
			var access = probe.finish(admission).orElseThrow();
			assertEquals(profile.currentAuthorityCalls(), fixture.authorityChecks - before);
			assertEquals(2L * tanks, fixture.calls.stream().filter("capacity"::equals).count());
			try (var read = admission.enter(access.readProfile())) {
				before = fixture.authorityChecks; assertTrue(access.consume(tanks - 1, entry -> true, read));
				assertEquals(access.readProfile().currentAuthorityCalls(), fixture.authorityChecks - before);
			}
			access.close();
		}
		assertEquals(1, fixture.reads);
	}

	@Test void oneShortFinishAdmissionRejectsBeforeNativeCallbacksAndKeepsPaidProbePrefix() {
		var fixture = new Fixture(Direction.NORTH); fixture.capacities = new int[8];
		var probeProfile = fixture.service.probeProfile();
		long probeWork = probeProfile.cost(MEMBER, AUTHORITY).workUnits() + 128;
		// Obtain the immutable finish profile once; a later fixture uses precisely the same shape.
		IPlatformFluidService.OperationProfile finishProfile;
		try (var admission = fixture.admission(1_000_000); var operation = admission.enter(probeProfile);
			var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) { finishProfile = probe.finishProfile(); }
		long finishWork = finishProfile.cost(MEMBER, AUTHORITY).workUnits() + 128;
		fixture = new Fixture(Direction.NORTH); fixture.capacities = new int[8];
		var ledger = new CostLedger(Map.of(WORK, probeWork + finishWork - 1));
		var memory = new RetainedMemoryLedger(1_000_000);
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY);
			var operation = admission.enter(probeProfile); var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
			fixture.calls.clear(); long reserved = memory.reserved(); long spent = operation.allowance().attemptedWorkUnits();
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> probe.finish(admission));
			assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.reads);
			assertEquals(reserved, memory.reserved()); assertEquals(spent, operation.allowance().attemptedWorkUnits());
			assertThrows(IllegalStateException.class, () -> probe.finish(admission));
		}
		assertEquals(probeWork, ledger.used(WORK)); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
	}

	@Test void changedRouteOrCountBetweenProbeAndFinishNeverScansOrAdoptsNewMapping() {
		for (boolean countChange : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.NORTH);
			try (var admission = fixture.admission(1_000_000); var operation = admission.enter(fixture.service.probeProfile());
				var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
				if (countChange) fixture.capacities = new int[] {1000, 2000, 3000};
				else fixture.capability = LazyOptional.of(() -> fixture.handler);
				fixture.calls.clear();
				assertThrows(InventorySourceUnavailableException.class, () -> probe.finish(admission));
				assertFalse(fixture.calls.contains("capacity")); assertEquals(0, fixture.reads);
			}
		}
	}

	@Test void probeCannotEscapeItsOperationChangeCallerAllowanceOrReopenAfterClose() {
		var fixture = new Fixture(Direction.NORTH);
		try (var admission = fixture.admission(1_000_000)) {
			IPlatformFluidService.Probe expired;
			try (var operation = admission.enter(fixture.service.probeProfile())) {
				expired = fixture.service.probe(fixture.context, operation).orElseThrow();
			}
			fixture.calls.clear();
			assertThrows(IllegalStateException.class, () -> expired.finish(admission)); assertTrue(fixture.calls.isEmpty());
			expired.close(); expired.close();
			try (var operation = admission.enter(fixture.service.probeProfile());
				var probe = fixture.service.probe(fixture.context, operation).orElseThrow(); var foreign = fixture.admission(1_000_000)) {
				fixture.calls.clear();
				assertThrows(IllegalStateException.class, () -> probe.finish(foreign)); assertTrue(fixture.calls.isEmpty());
				assertThrows(IllegalStateException.class, () -> probe.finish(admission));
			}
			try (var operation = admission.enter(fixture.service.probeProfile());
				var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
				probe.close(); probe.close(); fixture.calls.clear();
				assertThrows(IllegalStateException.class, () -> probe.finish(admission)); assertTrue(fixture.calls.isEmpty());
			}
		}
	}

	@Test void failedProbeCallbackDebitsOnlyActualCallbackPrefixAndClosesGenuineGrants() {
		@Unmodifiable List<String> failures = List.of("loaded", "state", "entity", "capability", "resolve", "count");
		for (String at : failures) {
			var fixture = new Fixture(Direction.NORTH); var failure = new IllegalStateException("native lookup failed");
			fixture.afterNative = operation -> { if (operation.equals(at)) throw failure; };
			var ledger = new CostLedger(Map.of(WORK, 1_000_000L)); var memory = new RetainedMemoryLedger(1_000_000);
			long spent;
			try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY);
				var operation = admission.enter(fixture.service.probeProfile())) {
				assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.service.probe(fixture.context, operation)));
				spent = operation.allowance().attemptedWorkUnits();
				assertEquals(128 + fixture.service.probeProfile().ownedCost().workUnits()
					+ fixture.memberChecks * MEMBER.workUnits() + fixture.authorityChecks * AUTHORITY.workUnits(), spent);
				assertTrue(fixture.memberChecks <= 8); assertTrue(fixture.authorityChecks <= 8);
				assertEquals(at, fixture.calls.getLast()); assertEquals(0, fixture.reads);
			}
			assertEquals(spent, ledger.used(WORK)); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		}
	}

	@Test void failedFinishAndReadKeepCallbackPrefixesWithinProfileAndReleaseTemporaryOwnership() {
		for (boolean finishFailure : new boolean[] {false, true}) {
			var fixture = new Fixture(Direction.NORTH); var failure = new IllegalStateException("native metadata failure");
			var ledger = new CostLedger(Map.of(WORK, 1_000_000L)); var memory = new RetainedMemoryLedger(1_000_000);
			try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
				@Nullable IPlatformFluidService.Access access;
				try (var operation = admission.enter(fixture.service.probeProfile());
					var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
					if (finishFailure) {
						int members = fixture.memberChecks, authorities = fixture.authorityChecks;
						long before = operation.allowance().attemptedWorkUnits(); var profile = probe.finishProfile();
						fixture.afterNative = event -> { if (event.equals("capacity")) throw failure; };
						assertSame(failure, assertThrows(IllegalStateException.class, () -> probe.finish(admission)));
						assertTrue(fixture.memberChecks - members < profile.memberGateCalls());
						assertTrue(fixture.authorityChecks - authorities < profile.currentAuthorityCalls());
						assertEquals(before + 128 + profile.ownedCost().workUnits() + (fixture.memberChecks - members) * MEMBER.workUnits()
							+ (fixture.authorityChecks - authorities) * AUTHORITY.workUnits(), operation.allowance().attemptedWorkUnits());
						access = null;
					} else access = probe.finish(admission).orElseThrow();
				}
				if (access != null) {
					var profile = access.readProfile();
					try (var operation = admission.enter(profile)) {
						int members = fixture.memberChecks, authorities = fixture.authorityChecks;
						long before = operation.allowance().attemptedWorkUnits();
						fixture.afterNative = event -> { if (event.equals("capacity")) throw failure; };
						assertSame(failure, assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> fail("failed precheck"), operation)));
						assertTrue(fixture.memberChecks - members < profile.memberGateCalls());
						assertTrue(fixture.authorityChecks - authorities < profile.currentAuthorityCalls());
						assertEquals(before + profile.ownedCost().workUnits() + (fixture.memberChecks - members) * MEMBER.workUnits()
							+ (fixture.authorityChecks - authorities) * AUTHORITY.workUnits(), operation.allowance().attemptedWorkUnits());
					}
					access.close();
				}
			}
			assertTrue(ledger.used(WORK) > 0); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
			assertEquals(0, fixture.reads);
		}
	}

	@Test void singleAdmissionComposesOwnedAndIndependentGatesOnceAndTransfersMetadataOnly() {
		var fixture = new Fixture(Direction.NORTH); var ledger = new CostLedger(Map.of(WORK, 1_000_000L));
		var memory = new RetainedMemoryLedger(1_000_000); InventoryFluidAdmission.Ownership ownership;
		IPlatformFluidService.Access access; IPlatformFluidService.Operation saved;
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
			var probeProfile = fixture.service.probeProfile();
			InventoryValidationBudget.Allowance cumulative;
			IPlatformFluidService.OperationProfile finishProfile;
			try (var operation = admission.enter(probeProfile); var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
				cumulative = operation.allowance(); finishProfile = probe.finishProfile();
				access = IPlatformFluidService.guarded(fixture.context, probe.finish(admission).orElseThrow()); saved = operation;
			}
			assertThrows(IllegalStateException.class, saved::allowance);
			var read = access.readProfile();
			try (var operation = admission.enter(read)) {
				assertSame(cumulative, operation.allowance());
				assertTrue(access.consume(0, entry -> true, operation));
			}
			var composed = probeProfile.plus(finishProfile).plus(read).cost(MEMBER, AUTHORITY);
			assertEquals(composed.workUnits() + 3 * 128, cumulative.attemptedWorkUnits());
			assertEquals(composed.allocationBytes() + 3 * 2048, cumulative.attemptedAllocationBytes());
			ownership = admission.retain(); assertEquals(finishProfile.retainedBytes() + 512, ownership.bytes());
		}
		assertEquals(0, memory.reserved()); assertEquals(ownership.bytes(), memory.retained());
		access.close(); access.close(); ownership.close(); ownership.close();
		assertEquals(0, memory.retained());
		try (var admission = fixture.admission(1_000_000); var operation = admission.enter(access.readProfile())) {
			assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> true, operation));
			assertEquals(1, fixture.reads);
		}
	}

	@Test void historicalWeakWitnessSurvivesOldAccessCloseAndMatchesReacquiredSameRoute() {
		var fixture = new Fixture(Direction.NORTH); var old = fixture.open();
		var expected = old.capture(); var layout = old.layout(); old.delegate.close(); old.delegate.close();
		var current = fixture.open(); assertEquals(layout, current.layout()); fixture.calls.clear();
		int members = fixture.memberChecks, authorities = fixture.authorityChecks;
		assertEquals(IPlatformFluidService.WitnessMatch.SAME, current.compare(expected));
		assertTrue(fixture.calls.isEmpty()); assertEquals(members, fixture.memberChecks); assertEquals(authorities, fixture.authorityChecks);
		assertEquals(0, fixture.reads);
		try (var admission = fixture.admission(1_000_000); var operation = admission.enter(old.delegate.witnessProfile(IPlatformFluidService.WitnessUse.CAPTURE))) {
			assertThrows(IllegalStateException.class, () -> old.delegate.captureWitness(operation));
		}
		current.delegate.close();
	}

	@Test void freshLayoutCarriesCompleteOrderedCapacityEvidenceWithoutAContentGetter() {
		var fixture = new Fixture(Direction.EAST); var old = fixture.open(); var expected = old.capture();
		var layout = old.layout(); old.delegate.close(); fixture.capacities = new int[] {2000, 1000};
		var current = fixture.open();
		assertEquals("east;capacities=1000,2000,", layout.layoutData());
		assertEquals("east;capacities=2000,1000,", current.layout().layoutData());
		assertNotEquals(layout, current.layout()); assertEquals(0, fixture.reads);
		// Identity and detached public metadata are independent evidence, neither replaces the other.
		assertEquals(IPlatformFluidService.WitnessMatch.SAME, current.compare(expected));
		current.delegate.close();
	}

	@Test void sameDetachedLayoutWithChangedCapabilityHandlerOrStateHasChangedWeakIdentity() {
		for (int change = 0; change < 3; change++) {
			var fixture = new Fixture(Direction.NORTH); var old = fixture.open(); var expected = old.capture(); var layout = old.layout();
			Object[] liveOriginalIdentities = {fixture.state, fixture.capability, fixture.handler};
			old.delegate.close();
			if (change == 0) fixture.capability = LazyOptional.of(() -> fixture.handler);
			else if (change == 1) { fixture.handler = fixture.newHandler(); fixture.capability = LazyOptional.of(() -> fixture.handler); }
			else fixture.state = fixture.state.setValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL, 1);
			var current = fixture.open(); assertEquals(layout, current.layout());
			assertEquals(IPlatformFluidService.WitnessMatch.CHANGED, current.compare(expected));
			assertEquals(0, fixture.reads); current.delegate.close();
			java.lang.ref.Reference.reachabilityFence(liveOriginalIdentities);
		}
	}

	@Test void deterministicallyClearedWeakIdentityIsUnverifiableNeverSameOrInventedRemapping() throws ReflectiveOperationException {
		var fixture = new Fixture(Direction.NORTH); var old = fixture.open(); var expected = old.capture(); old.delegate.close();
		var clear = FluidAddressWitness.class.getDeclaredMethod("clearForTesting", int.class); clear.setAccessible(true); clear.invoke(expected, 1);
		var current = fixture.open(); fixture.calls.clear();
		assertEquals(IPlatformFluidService.WitnessMatch.UNVERIFIABLE, current.compare(expected));
		assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.reads); current.delegate.close();
	}

	@Test void witnessProfilesHaveZeroCallbacksDebitHelperOnceAndRefuseOneShortBeforeOwnedAllocation() {
		var fixture = new Fixture(Direction.NORTH); var access = fixture.open(); fixture.calls.clear();
		int members = fixture.memberChecks, authorities = fixture.authorityChecks;
		for (var use : IPlatformFluidService.WitnessUse.values()) {
			var profile = access.delegate.witnessProfile(use);
			assertEquals(FluidAddressWitness.profile(use, 3), profile);
			assertEquals(0, profile.memberGateCalls()); assertEquals(0, profile.currentAuthorityCalls());
			assertEquals(use == IPlatformFluidService.WitnessUse.CAPTURE ? 592 : 0, profile.retainedBytes());
		}
		FluidAddressWitness expected;
		try (var admission = fixture.admission(1_000_000); var operation = admission.enter(access.delegate.witnessProfile(IPlatformFluidService.WitnessUse.CAPTURE))) {
			long before = operation.allowance().attemptedWorkUnits();
			expected = access.delegate.captureWitness(operation);
			assertEquals(88, operation.allowance().attemptedWorkUnits() - before);
		}
		for (var use : IPlatformFluidService.WitnessUse.values()) {
			var profile = access.delegate.witnessProfile(use);
			try (var admission = fixture.admission(1_000_000); var operation = admission.enter(profile)) {
				// Consume exactly one unit of the real grant, retaining all allocation/work headroom otherwise.
				operation.allowance().debit(new InventoryValidationBudget.Cost(0, 0, 1));
				long before = operation.allowance().attemptedAllocationBytes();
				if (use == IPlatformFluidService.WitnessUse.CAPTURE)
					assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.delegate.captureWitness(operation));
				else assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.delegate.compareWitness(expected, operation));
				assertEquals(before, operation.allowance().attemptedAllocationBytes());
				assertEquals(129, operation.allowance().attemptedWorkUnits(), "only admission bookkeeping and the consumed unit remain paid");
			}
		}
		try (var admission = fixture.admission(1_000_000); var operation = admission.enter(access.delegate.witnessProfile(IPlatformFluidService.WitnessUse.COMPARE))) {
			long before = operation.allowance().attemptedWorkUnits();
			assertEquals(IPlatformFluidService.WitnessMatch.SAME, access.delegate.compareWitness(expected, operation));
			assertEquals(88, operation.allowance().attemptedWorkUnits() - before);
		}
		assertTrue(fixture.calls.isEmpty()); assertEquals(members, fixture.memberChecks); assertEquals(authorities, fixture.authorityChecks);
		access.delegate.close();
	}

	@Test void maximumCapacityDescriptionIsBoundedAndTransferredStringAndWitnessOwnershipReleaseTogether() {
		var fixture = new Fixture(Direction.NORTH); fixture.capacities = new int[InventorySourceAccess.MAX_SLOTS];
		java.util.Arrays.fill(fixture.capacities, Integer.MAX_VALUE);
		var ledger = new CostLedger(Map.of(WORK, 10_000_000L)); var memory = new RetainedMemoryLedger(8_000_000);
		InventoryFluidAdmission.Ownership ownership; FluidAddressWitness expected;
		IPlatformFluidService.Access access;
		try (var admission = new InventoryFluidAdmission(ledger, WORK, memory, MEMBER, AUTHORITY)) {
			IPlatformFluidService.OperationProfile finish;
			try (var operation = admission.enter(fixture.service.probeProfile()); var probe = fixture.service.probe(fixture.context, operation).orElseThrow()) {
				finish = probe.finishProfile(); access = probe.finish(admission).orElseThrow();
			}
			String description = access.layout().layoutData();
			assertEquals("north;capacities=".length() + 11 * InventorySourceAccess.MAX_SLOTS, description.length());
			assertTrue(description.length() < 65536); assertTrue(description.startsWith("north;capacities=2147483647,"));
			assertTrue(description.endsWith("2147483647,"));
			assertEquals(2L * InventorySourceAccess.MAX_SLOTS, fixture.calls.stream().filter("capacity"::equals).count());
			assertEquals(1_327_808, finish.ownedCost().allocationBytes()); assertEquals(627_072, finish.ownedCost().workUnits());
			try (var operation = admission.enter(access.witnessProfile(IPlatformFluidService.WitnessUse.CAPTURE))) { expected = access.captureWitness(operation); }
			ownership = admission.retain(); assertEquals(finish.retainedBytes() + 592 + 512 + 256, ownership.bytes());
		}
		assertEquals(0, memory.reserved()); assertEquals(ownership.bytes(), memory.retained());
		access.close(); var current = fixture.open(); assertEquals(IPlatformFluidService.WitnessMatch.SAME, current.compare(expected));
		ownership.close(); ownership.close(); current.delegate.close(); assertEquals(0, memory.retained()); assertEquals(0, fixture.reads);
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
		private BlockEntity entity;
		private IFluidHandler handler = newHandler();
		private LazyOptional<IFluidHandler> capability = LazyOptional.of(() -> { nativeCall("resolve"); return handler; });
		private int[] capacities = {1000, 2000};
		private int amount = 7, reads, authorityChecks, memberChecks;
		private @Nullable Integer reportedCount;
		private @Nullable RuntimeException contentFailure;
		private boolean loaded = true, member = true, authority = true, readReturned, nativeContent;
		private Consumer<String> afterNative = operation -> {};
		private Runnable afterReadGate = () -> {};

		private Fixture(Direction face) { this(face, false); }
		/** Native source integration supplies real registries, but does not launch FML capability events. */
		private Fixture(Direction face, boolean nativeRegistries) {
			this.face = face;
			if (nativeRegistries) {
				entity = allocate(BeehiveBlockEntity.class);
				field(BlockEntity.class, "type", entity, BlockEntityType.BEEHIVE);
				field(BlockEntity.class, "worldPosition", entity, BlockPos.ZERO);
				field(BlockEntity.class, "blockState", entity, state);
			} else entity = new BeehiveBlockEntity(BlockPos.ZERO, state);
			field(ServerLevel.class, "server", level, server); serverThread(Thread.currentThread());
			context = new IPlatformFluidService.Context(level, BlockPos.ZERO, face, new UUID(1, 2), (position, operation) -> {
				operation.allowance().debit(MEMBER); memberChecks++;
				assertEquals(BlockPos.ZERO, position, "generic capability must never discover other members"); return member;
			}, operation -> {
				operation.allowance().debit(AUTHORITY);
				authorityChecks++;
				if (readReturned) afterReadGate.run();
				return authority;
			});
			service = new TestService(this);
		}
		private void serverThread(Thread thread) { field(MinecraftServer.class, "serverThread", server, thread); }
		private InventoryFluidAdmission admission(long work) {
			return new InventoryFluidAdmission(new CostLedger(Map.of(WORK, work)), WORK,
				new RetainedMemoryLedger(64L * 1024 * 1024), MEMBER, AUTHORITY);
		}
		private TestAccess open() {
			try (var admission = admission(10_000_000); var operation = admission.enter(service.probeProfile());
				var probe = service.probe(context, operation).orElseThrow()) {
				return new TestAccess(this, IPlatformFluidService.guarded(context, probe.finish(admission).orElseThrow()));
			}
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
		@Override public OperationProfile probeProfile() { return new PlatformFluidServiceImpl().probeProfile(); }
		@Override public Optional<Probe> probe(Context context, Operation operation) { return PlatformFluidServiceImpl.probe(context, fixture, operation); }
	}

	/** Test convenience only: every call still obtains a genuine caller grant; no legacy port overload. */
	@NotNullByDefault
	private record TestAccess(Fixture fixture, IPlatformFluidService.Access delegate) {
		private <T> T run(IPlatformFluidService.OperationProfile profile, Function<IPlatformFluidService.Operation, T> action) {
			try (var admission = fixture.admission(10_000_000); var operation = admission.enter(profile)) { return action.apply(operation); }
		}
		private boolean valid() { return run(delegate.validationProfile(), delegate::valid); }
		private boolean consume(int slot, IPlatformFluidService.Visitor visitor) { return run(delegate.readProfile(), operation -> delegate.consume(slot, visitor, operation)); }
		private IPlatformFluidService.Visit observe(int limit, IPlatformFluidService.Visitor visitor) { return run(delegate.observationProfile(limit), operation -> delegate.observe(limit, visitor, operation)); }
		private nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout layout() { return delegate.layout(); }
		private Optional<String> alias() { return delegate.alias(); }
		private boolean stableCursor() { return delegate.stableCursor(); }
		private int slots() { return delegate.slots(); }
		private FluidAddressWitness capture() { return run(delegate.witnessProfile(IPlatformFluidService.WitnessUse.CAPTURE), delegate::captureWitness); }
		private IPlatformFluidService.WitnessMatch compare(FluidAddressWitness expected) {
			return run(delegate.witnessProfile(IPlatformFluidService.WitnessUse.COMPARE), operation -> delegate.compareWitness(expected, operation));
		}
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
