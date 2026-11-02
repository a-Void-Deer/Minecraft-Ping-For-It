package nx.pingwheel.neoforge.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessMatch;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessUse;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Executed capability seam; no Create classes, loader launch, live world or native copying is assumed. */
@NotNullByDefault
public class PlatformFluidServiceTest {
	public static final InventoryValidationBudget.Cost GATE = new InventoryValidationBudget.Cost(16, 16, 1);
	public static final CostLedger.Counter WORK = new CostLedger.Counter("neoforge-fluid-test", CostLedger.Unit.WORK);
	public static InventoryFluidAdmission admission(long work, RetainedMemoryLedger memory) {
		return new InventoryFluidAdmission(new CostLedger(Map.of(WORK, work)), WORK, memory, GATE, GATE);
	}
	public static InventoryFluidAdmission admission() { return admission(10_000_000, new RetainedMemoryLedger(64L << 20)); }
	public static void gate(Operation operation) { operation.allowance().debit(GATE.times(2)); }

	/** Test convenience only: each execution still enters the actual caller-backed admission. */
	@NotNullByDefault
	public static final class PaidAccess implements AutoCloseable {
		public final IPlatformFluidService.Access delegate;
		public PaidAccess(IPlatformFluidService.Access delegate) { this.delegate = delegate; }
		public nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout layout() { return delegate.layout(); }
		public java.util.Optional<String> alias() { return delegate.alias(); }
		public boolean stableCursor() { return delegate.stableCursor(); }
		public int slots() { return delegate.slots(); }
		public OperationProfile witnessProfile(WitnessUse use) { return delegate.witnessProfile(use); }
		public FluidAddressWitness captureWitness() {
			try (var a = admission(); var op = a.enter(witnessProfile(WitnessUse.CAPTURE))) { return delegate.captureWitness(op); }
		}
		public WitnessMatch compareWitness(FluidAddressWitness expected) {
			try (var a = admission(); var op = a.enter(witnessProfile(WitnessUse.COMPARE))) { return delegate.compareWitness(expected, op); }
		}
		public OperationProfile validationProfile() { return delegate.validationProfile(); }
		public OperationProfile readProfile() { return delegate.readProfile(); }
		public InventoryValidationBudget.Cost validationCost() { return validationProfile().cost(GATE, GATE); }
		public InventoryValidationBudget.Cost readCost() { return readProfile().cost(GATE, GATE); }
		public boolean valid() { try (var a = admission(); var op = a.enter(validationProfile())) { return delegate.valid(op); } }
		public boolean valid(InventoryValidationBudget.Allowance allowance) {
			try (var op = operation(validationProfile(), allowance)) { return delegate.valid(op); }
		}
		public boolean consume(int slot, IPlatformFluidService.Visitor visitor) {
			try (var a = admission(); var op = a.enter(readProfile())) { return delegate.consume(slot, visitor, op); }
		}
		public boolean consume(int slot, IPlatformFluidService.Visitor visitor, InventoryValidationBudget.Allowance allowance) {
			try (var op = operation(readProfile(), allowance)) { return delegate.consume(slot, visitor, op); }
		}
		public IPlatformFluidService.Visit observe(int limit, IPlatformFluidService.Visitor visitor) {
			try (var a = admission(); var op = a.enter(delegate.observationProfile(limit))) { return delegate.observe(limit, visitor, op); }
		}
		@Override public void close() { delegate.close(); }
	}
	private static Operation operation(OperationProfile profile, InventoryValidationBudget.Allowance allowance) {
		allowance.requireAvailable(profile.cost(GATE, GATE));
		return new Operation() {
			@Override public InventoryValidationBudget.Allowance allowance() { return allowance; }
			@Override public void requireAdditional(InventoryValidationBudget.Cost cost) { allowance.requireAvailable(cost); }
			@Override public void close() {}
		};
	}
	private static java.util.Optional<PaidAccess> resolve(World world, BlockPos root, Direction face) {
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE)) {
			var result = PlatformFluidServiceImpl.probe(world, root, face, op);
			if (result.isEmpty()) return java.util.Optional.empty();
			try (var probe = result.orElseThrow()) { return probe.finish(a).map(PaidAccess::new); }
		}
	}
	private static PaidAccess nativeAccess(PlatformFluidServiceImpl service, IPlatformFluidService.Context context) {
		try (var a = admission(); var op = a.enter(service.probeProfile())) {
			var world = (NativeWorld) context.level(); int before = world.contextRequires;
			try (var probe = service.probe(context, op).orElseThrow()) {
				assertTrue(world.contextRequires - before <= service.probeProfile().memberGateCalls());
				before = world.contextRequires;
				var access = probe.finish(a).orElseThrow();
				assertTrue(world.contextRequires - before <= probe.finishProfile().memberGateCalls());
				return new PaidAccess(access);
			}
		}
	}
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void serviceIsRegisteredAndResolutionIsMetadataOnlyThroughExactlyTheFrozenFace() {
		assertTrue(ServiceLoader.load(IPlatformFluidService.class).stream().anyMatch(provider -> provider.type() == PlatformFluidServiceImpl.class));
		var world = new World();
		var access = resolve(world, BlockPos.ZERO, Direction.WEST).orElseThrow();
		assertTrue(access.stableCursor()); assertEquals(2, access.slots()); assertEquals(0, world.handler().reads);
		assertTrue(access.valid()); assertEquals(0, world.handler().reads, "postvalidation never reads contents");
		assertTrue(access.alias().isEmpty());
		var segment = access.layout().members().getFirst().segments().getFirst();
		assertEquals(IPlatformFluidService.ADDRESS_SCHEMA, segment.schemaId()); assertEquals(List.of(0, 1), segment.visibleSlots());
		assertTrue(world.faces.stream().allMatch(face -> face == Direction.WEST));
		world.handler = null;
		assertFalse(access.valid(), "null current capability is unavailable, not an empty view");
		assertTrue(resolve(world, BlockPos.ZERO, Direction.WEST).isEmpty());
		assertTrue(world.faces.stream().allMatch(face -> face == Direction.WEST), "no null/other-face fallback");
	}

	@Test void deniedResolutionAdmissionInvokesNoAuthorityMembershipWorldOrCapabilityCallback() {
		AtomicInteger gates = new AtomicInteger();
		var context = new IPlatformFluidService.Context(allocate(ServerLevel.class), BlockPos.ZERO, Direction.NORTH, UUID.randomUUID(),
			(position, op) -> { gates.incrementAndGet(); return true; }, op -> { gates.incrementAndGet(); return true; });
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> {
			try (var a = admission(127, new RetainedMemoryLedger(1_000_000)); var op = a.enter(new PlatformFluidServiceImpl().probeProfile())) {
				new PlatformFluidServiceImpl().probe(context, op);
			}
		});
		assertEquals(0, gates.get());
	}

	@Test void deniedReadAndValidationAdmissionReachNoCallbacksAndAttemptedFailureStaysPaid() {
		var world = new World(); var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		world.callbacks = 0;
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.consume(0, entry -> fail("unadmitted visitor"),
			new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.valid(new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertEquals(0, world.callbacks); assertEquals(0, world.handler().reads);
		var paid = new InventoryValidationBudget.Allowance(access.readCost());
		var failure = new IllegalStateException("getter failure"); world.handler().failure = failure;
		assertSame(failure, assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> fail("throw is not empty"), paid)));
		assertTrue(paid.attemptedWorkUnits() >= access.readProfile().ownedCost().workUnits());
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.consume(0, entry -> true, paid));
		assertEquals(1, world.handler().reads);
	}

	@Test void nativeIntMaximumUsesCheckedLongUnitsAndActualExplicitBorrowBeforeAnyLaterCallback() {
		var world = new World(); var handler = world.handler();
		var name = Component.literal("getter-time");
		handler.stack = new FluidStack(Fluids.WATER, Integer.MAX_VALUE); handler.stack.set(DataComponents.CUSTOM_NAME, name);
		var access = resolve(world, BlockPos.ZERO, Direction.UP).orElseThrow();
		world.afterRead = () -> { name.append("-metadata"); handler.stack.setAmount(0); };
		String[] detached = {""};
		assertTrue(access.consume(0, entry -> {
			assertEquals(173_946_175_407L, entry.amount());
			var resident = assertInstanceOf(IPlatformFluidService.Components.class, entry.resident());
			assertSame(Fluids.WATER, resident.fluid());
			assertSame(name, resident.patch().get(DataComponents.CUSTOM_NAME).orElseThrow(), "native patch values are borrowed, not full-stack copied");
			assertEquals("getter-time", name.getString(), "even scope checks run only after the synchronous visitor");
			detached[0] = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,
				resident.patch()).getOrThrow().toString();
			return true;
		}));
		assertTrue(name.getString().contains("metadata")); assertTrue(handler.stack.isEmpty(), "next metadata callback reuses the native scratch");
		assertTrue(detached[0].contains("getter-time")); assertFalse(detached[0].contains("metadata")); assertEquals(1, handler.reads);
	}

	@Test void blankVisitsAreChargedAndVisitorRefusalCannotClaimCompletion() {
		var world = new World(); var access = resolve(world, BlockPos.ZERO, Direction.DOWN).orElseThrow();
		assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(2, entry -> { assertNull(entry.resident()); assertEquals(0, entry.amount()); return false; }));
		assertEquals(1, world.handler().reads);
		assertEquals(new IPlatformFluidService.Visit(2, true), access.observe(2, entry -> true)); assertEquals(3, world.handler().reads);
		world.handler().nullStack = true;
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("null is not empty")));
	}

	@Test void originalRouteObjectsAndPostReadAuthorityAndLayoutAreRequiredEvenWithEqualTankTotals() {
		for (String change : List.of("permission", "member", "handler", "count")) {
			var world = new World(); var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			var original = world.handler();
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
				switch (change) {
					case "permission" -> world.allowed = false;
					case "member" -> world.block = new FluidCapabilityAccess.Block(new Object(), new Object(), "test:tank", "test:tank");
					case "handler" -> world.handler = new Handler();
					case "count" -> world.handler().count = 3;
					default -> fail(change);
				}
				return true;
			}), change);
			assertEquals(1, original.reads, "postcheck must not perform another content getter");
			assertFalse(access.valid());
		}
	}

	@Test void unavailableCreateAndCreativeClaimsNeverUseTheGenericCapability() {
		for (String id : List.of("create:fluid_tank", "create:creative_fluid_tank")) {
			var world = new World(); world.block = new FluidCapabilityAccess.Block(new Object(), new Object(), id, id);
			assertTrue(resolve(world, BlockPos.ZERO, Direction.NORTH).isEmpty());
			assertTrue(world.faces.isEmpty()); assertEquals(0, world.handler().reads);
		}
	}

	@Test void zeroTankObservationCannotCompleteAfterAuthorityOrRouteLoss() {
		var world = new World(); world.handler().count = 0;
		var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		assertEquals(new IPlatformFluidService.Visit(0, true), access.observe(0, entry -> fail("no address")));
		world.allowed = false;
		assertThrows(InventorySourceUnavailableException.class, () -> access.observe(0, entry -> fail("no address")));
		assertEquals(0, world.handler().reads);
	}

	@Test void orderedPublicCapacitiesDetectSameCountReorderWithoutAnotherContentRead() {
		var world = new World(); var handler = world.handler(); handler.capacities = new int[] {1000, 2000};
		var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		assertTrue(access.layout().layoutData().endsWith("capacities=1000,2000"));
		access.consume(0, entry -> true); assertEquals(1, handler.reads);
		handler.capacities = new int[] {2000, 1000};
		assertFalse(access.valid());
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(1, entry -> fail("remapped A cannot be read twice")));
		assertEquals(1, handler.reads);
	}

	@Test void countGrowthShrinkAndPostGetterAddressChangesInvalidateTheFrozenView() {
		for (String change : List.of("growth", "shrink", "reorder", "capacity")) {
			var world = new World(); var handler = world.handler(); handler.capacities = new int[] {1000, 2000};
			var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
				switch (change) {
					case "growth" -> handler.count = 3;
					case "shrink" -> handler.count = 1;
					case "reorder" -> handler.capacities = new int[] {2000, 1000};
					case "capacity" -> handler.capacities[1] = 3000;
					default -> fail(change);
				}
				return true;
			}), change);
			assertEquals(1, handler.reads); assertFalse(access.valid());
		}
	}

	@Test void finalCountIsCheckedAfterCapacityAndPostCapacityRouteCallbacks() {
		for (boolean duringCapacity : new boolean[] {true, false}) for (int changed : new int[] {1, 3}) {
			var world = new World(); var handler = world.handler();
			var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			int before = handler.capacityReads;
			if (duringCapacity) handler.onCapacity = index -> { if (index == 1) handler.count = changed; };
			else world.afterBlock = () -> { if (handler.capacityReads > before) handler.count = changed; };
			assertFalse(access.valid(), "last capacity/route callback must not hide changed count");
			assertEquals(0, handler.reads);
		}
		var world = new World(); var handler = world.handler();
		handler.onCapacity = index -> { if (index == 1) handler.count = 3; };
		assertThrows(InventorySourceUnavailableException.class, () -> resolve(world, BlockPos.ZERO, Direction.NORTH),
			"initial capacity discovery also checks the final count");
		var changedLayout = new World(); var layoutHandler = changedLayout.handler();
		var access = resolve(changedLayout, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		int before = layoutHandler.capacityReads;
		changedLayout.afterBlock = () -> { if (layoutHandler.capacityReads > before) layoutHandler.capacities[0] = 2000; };
		assertFalse(access.valid(), "post-capacity route callback capacity changes are checked again");
		var changedHeld = new World(); var heldHandler = changedHeld.handler();
		var heldAccess = resolve(changedHeld, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		int heldBefore = heldHandler.capacityReads;
		changedHeld.afterHeld = () -> { if (heldHandler.capacityReads >= heldBefore + 4) heldHandler.count = 3; };
		assertFalse(heldAccess.valid(), "final count follows held state/entity metadata callbacks too");
	}

	@Test void equalCapacityOpaqueRemappingIsNotClaimedAsDetectableAndEachNativeReadIsStillPaid() {
		var world = new World(); var handler = world.handler(); handler.capacities = new int[] {1000, 1000};
		var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var paid = new InventoryValidationBudget.Allowance(access.readCost());
		access.consume(0, entry -> true, paid);
		assertTrue(paid.attemptedWorkUnits() <= access.readCost().workUnits());
		assertEquals(1, handler.reads); assertTrue(access.valid());
		// Public metadata has not changed. This is deliberately not a certificate of hidden provider
		// addresses or contents; same-capacity internal remaps remain external-trusted.
	}

	@Test void capacityWitnessValidationHasLinearMetadataCostWithoutAllocatingANewAddressTable() {
		for (int count : new int[] {1, 16, 4096}) {
			var world = new World(); var handler = world.handler(); handler.count = count;
			handler.capacities = new int[count]; java.util.Arrays.fill(handler.capacities, 1000);
			var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			handler.capacityReads = 0; world.callbacks = 0;
			var paid = new InventoryValidationBudget.Allowance(access.validationCost());
			assertTrue(access.valid(paid)); assertEquals(2 * count, handler.capacityReads); assertEquals(0, handler.reads);
			assertTrue(paid.attemptedWorkUnits() <= access.validationCost().workUnits());
			System.out.println("NEOFORGE ordinary tanks=" + count + " requireCalls=" + world.callbacks + " capacityReads="
				+ handler.capacityReads + " validation=" + access.validationCost() + " read=" + access.readCost()
				+ " retainedCapacityBytes=" + (40L + 4L * count) + " retainedLayoutChars=" + access.layout().layoutData().length());
		}
	}

	@Test void ordinarySingleTankAdvancesWithDefaultAndLowHeadroomMetadataAdmission() {
		var world = new World(); world.handler().count = 1; world.handler().capacities = new int[] {1000};
		var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var settings = nx.pingwheel.common.config.InventorySettings.serverDefaults();
		var memory = new nx.pingwheel.common.presentation.source.RetainedMemoryLedger(settings.pendingMemoryBytes());
		long needed = access.readCost().workspaceBytes() + 2048;
		try (var held = memory.tryReserve(settings.pendingMemoryBytes() - needed).orElseThrow();
			var a = admission(10_000_000, memory); var op = a.enter(access.readProfile())) {
			assertTrue(access.delegate.consume(0, entry -> true, op)); assertEquals(1, world.handler().reads);
		}
		assertEquals(settings.pendingMemoryBytes(), memory.remaining());
		System.out.println("NEOFORGE ordinary one-tank modeled scratch=" + access.readCost().workspaceBytes()
			+ " admissionHeadroom=" + needed + " defaultMemory=" + settings.pendingMemoryBytes());
	}

	@Test void oversizedNativeRegistryIdsAreRejectedBeforeStringCopyWithBoundedFailureAllocation() {
		var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "x".repeat(3_000_000));
		assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.boundedId(id));
		var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		assertTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
		long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread);
		try { PlatformFluidServiceImpl.boundedId(id); fail("oversized native identity"); }
		catch (InventorySourceUnavailableException failure) { assertEquals("fluid registry identity bound", failure.getMessage()); }
		long rejected = bean.getThreadAllocatedBytes(thread) - before;
		assertTrue(rejected < 65536, "guard must not allocate/copy the 3MB input: " + rejected);
		before = bean.getThreadAllocatedBytes(thread); String copied = id.toString();
		assertTrue(bean.getThreadAllocatedBytes(thread) - before >= 3_000_000, "positive control detects native concatenation allocation");
		assertEquals(3_000_005, copied.length());
		assertEquals("test:tank", PlatformFluidServiceImpl.boundedId(net.minecraft.resources.ResourceLocation.parse("test:tank")));
		assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.boundedId(null));
		assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.boundedId(
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("x".repeat(3_000_000), "tank")));
		assertEquals(256, PlatformFluidServiceImpl.boundedId(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "x".repeat(251))).length());
		assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.boundedId(
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "x".repeat(252))));
	}

	@Test void stagedGenericProbeIsConstantSizedAndFinishProfilesTrackActualOneAndEightTanks() throws Exception {
		OperationProfile previous = OperationProfile.NONE;
		for (int count : new int[] {1, 8}) {
			var world = new World(); world.handler().count = count; world.handler().capacities = new int[count];
			java.util.Arrays.fill(world.handler().capacities, 1000);
			var memory = new RetainedMemoryLedger(1_000_000); var ledger = new CostLedger(Map.of(WORK, 1_000_000L));
			InventoryFluidAdmission.Ownership ownership;
			IPlatformFluidService.Access access;
			try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE);
				var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
				var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) {
				assertEquals(0, world.handler().capacityReads); assertEquals(1, world.handler().countReads); assertEquals(0, world.handler().reads);
				int callbacks = world.callbacks;
				assertEquals(new IPlatformFluidService.Shape(1, java.util.OptionalInt.of(count), true), probe.shape());
				var finish = probe.finishProfile(); assertSame(finish, probe.finishProfile()); assertEquals(callbacks, world.callbacks);
				if (count == 8) {
					assertTrue(finish.ownedCost().workUnits() > previous.ownedCost().workUnits());
					assertEquals(128L * 7, finish.retainedBytes() - previous.retainedBytes());
				}
				previous = finish;
				long before = op.allowance().attemptedWorkUnits();
				access = probe.finish(a).orElseThrow();
				assertEquals(finish.ownedCost().workUnits() + 128 + 2L * (world.callbacks - callbacks),
					op.allowance().attemptedWorkUnits() - before, "one owned debit + admission bookkeeping + actual independent callbacks");
				assertTrue(world.callbacks - callbacks <= finish.memberGateCalls());
				assertEquals(2 * count, world.handler().capacityReads);
				assertEquals(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, access.routeInfo().topology());
				assertEquals(Handler.class.getName(), access.routeInfo().providerClass());
				assertReleasedProbe(probe);
				assertThrows(IllegalStateException.class, () -> probe.finish(a));
				callbacks = world.callbacks; access.layout(); access.alias(); access.routeInfo(); access.validationProfile(); access.readProfile();
				assertEquals(callbacks, world.callbacks, "metadata access never authorizes or calls provider");
				try (var read = a.enter(access.readProfile())) {
					assertSame(op.allowance(), read.allowance()); before = read.allowance().attemptedWorkUnits();
					assertTrue(access.consume(0, entry -> true, read));
					assertEquals(access.readProfile().ownedCost().workUnits() + 2L * (world.callbacks - callbacks),
						read.allowance().attemptedWorkUnits() - before);
				}
				ownership = a.retain(); assertEquals(finish.retainedBytes() + 512, ownership.bytes());
				System.out.println("NEOFORGE staged generic N=" + count + " finish=" + finish + " read=" + access.readProfile()
					+ " retained=" + ownership.bytes() + " actualReadRequires=" + (world.callbacks - callbacks));
			}
			assertEquals(0, memory.reserved()); assertEquals(ownership.bytes(), memory.retained());
			int callbacks = world.callbacks; access.close(); access.close(); assertEquals(callbacks, world.callbacks);
			assertReleasedAccess(access); ownership.close(); ownership.close(); assertEquals(0, memory.retained());
		}
	}

	@Test void oneShortGenericFinishAndReadUseRealCallerLedgerAndNeverReachGuardedWork() throws Exception {
		var world = new World(); var memory = new RetainedMemoryLedger(1_000_000);
		OperationProfile finish;
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
			var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) { finish = probe.finishProfile(); }
		var bookkeeping = new InventoryValidationBudget.Cost(2048, 2048, 128);
		long total = FluidCapabilityAccess.PROBE_PROFILE.cost(GATE, GATE).overlap(bookkeeping).workUnits()
			+ finish.cost(GATE, GATE).overlap(bookkeeping).workUnits();
		var ledger = new CostLedger(Map.of(WORK, total - 1));
		try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
			var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) {
			int calls = world.callbacks;
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> probe.finish(a));
			assertEquals(calls, world.callbacks); assertEquals(0, world.handler().capacityReads); assertReleasedProbe(probe);
			assertThrows(IllegalStateException.class, () -> probe.finish(a));
		}
		assertTrue(ledger.used(WORK) > 0); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow(); world.callbacks = 0;
		long work = access.readProfile().cost(GATE, GATE).workUnits() + 128 - 1;
		try (var a = admission(work, memory)) {
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> {
				try (var op = a.enter(access.readProfile())) { access.delegate.consume(0, entry -> fail("unadmitted read"), op); }
			});
		}
		assertEquals(0, world.callbacks); assertEquals(0, world.handler().reads); assertEquals(0, memory.reserved());
	}

	@Test void changedGenericProbeCountOrHandlerIsRejectedBeforeCapacityAllocationAndFailureKeepsPrefixPaid() throws Exception {
		for (String change : List.of("count", "handler", "root", "capacity-throw")) {
			var world = new World(); var memory = new RetainedMemoryLedger(1_000_000); var ledger = new CostLedger(Map.of(WORK, 1_000_000L));
			try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
				var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) {
				long prefix = op.allowance().attemptedWorkUnits();
				switch (change) {
					case "count" -> world.handler().count = 8;
					case "handler" -> world.handler = new Handler();
					case "root" -> world.block = new FluidCapabilityAccess.Block(new Object(), new Object(), "test:tank", "test:tank");
					case "capacity-throw" -> world.handler().onCapacity = index -> { throw new IllegalStateException("capacity failure"); };
					default -> fail(change);
				}
				assertThrows(RuntimeException.class, () -> probe.finish(a));
				assertTrue(op.allowance().attemptedWorkUnits() > prefix); assertReleasedProbe(probe);
				assertEquals(change.equals("capacity-throw") ? 1 : 0, world.handler().capacityReads);
				assertEquals(0, world.handler().reads);
			}
			assertTrue(ledger.used(WORK) > 0); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		}
	}
	@Test void probeCannotEscapeItsOriginOperationOrSwitchCallerAllowanceAndFinishIsSingleUseDuringAdmission() throws Exception {
		var world = new World();
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
			var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) {
			int calls = world.callbacks;
			probe.finish(profile -> {
				assertThrows(IllegalStateException.class, () -> probe.finish(a), "reentrant finish cannot admit twice");
				return a.enter(profile);
			}).orElseThrow().close(); assertTrue(world.callbacks > calls); assertReleasedProbe(probe);
		}
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
			var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow(); var other = admission()) {
			int calls = world.callbacks;
			assertThrows(IllegalStateException.class, () -> probe.finish(other)); assertEquals(calls, world.callbacks); assertReleasedProbe(probe);
		}
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
			var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) {
			op.close(); int calls = world.callbacks;
			assertThrows(IllegalStateException.class, () -> probe.finish(a)); assertEquals(calls, world.callbacks); assertReleasedProbe(probe);
		}
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
			var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) {
			int calls = world.callbacks;
			assertThrows(IllegalStateException.class, () -> probe.finish(profile -> { var next = a.enter(profile); op.close(); return next; }));
			assertEquals(calls, world.callbacks); assertReleasedProbe(probe);
		}
	}

	@Test void zeroEntryObservationChargesActualCallbacksAndClosedAccessCannotReadOrValidate() throws Exception {
		var world = new World(); world.handler().count = 0; var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow().delegate;
		try (var a = admission(); var op = a.enter(access.observationProfile(0))) {
			world.callbacks = 0; long before = op.allowance().attemptedWorkUnits();
			assertEquals(new IPlatformFluidService.Visit(0, true), access.observe(0, entry -> fail("no address"), op));
			assertEquals(access.observationProfile(0).ownedCost().workUnits() + 2L * world.callbacks,
				op.allowance().attemptedWorkUnits() - before);
			assertTrue(world.callbacks <= access.observationProfile(0).memberGateCalls()); assertEquals(0, world.handler().reads);
			access.close(); access.close(); int calls = world.callbacks;
			assertThrows(IllegalStateException.class, () -> access.valid(op));
			assertThrows(IllegalStateException.class, () -> access.observe(0, entry -> true, op));
			assertEquals(calls, world.callbacks); assertReleasedAccess(access);
		}
	}

	@Test void invalidNativeCountIsRejectedInProbeWithoutCapacityOrContentCalls() {
		for (int count : new int[] {-1, 4097, Integer.MAX_VALUE}) {
			var world = new World(); world.handler().count = count;
			try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE)) {
				assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op));
				assertEquals(0, world.handler().capacityReads); assertEquals(0, world.handler().reads);
			}
		}
	}

	@Test void genericShapeMemoryOneShortDefersBeforeCapacityAndExactHeadroomReallyFinishes() throws Exception {
		for (boolean oneShort : new boolean[] {true, false}) {
			var world = new World(); var memory = new RetainedMemoryLedger(1_000_000);
			try (var a = admission(1_000_000, memory); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
				var probe = PlatformFluidServiceImpl.probe(world, BlockPos.ZERO, Direction.NORTH, op).orElseThrow()) {
				var finish = probe.finishProfile();
				long needed = finish.cost(GATE, GATE).workspaceBytes() + 2048 + finish.retainedBytes() + 512;
				try (var withheld = memory.tryReserve(memory.remaining() - needed + (oneShort ? 1 : 0)).orElseThrow()) {
					int calls = world.callbacks;
					if (oneShort) {
						assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> probe.finish(a));
						assertEquals(calls, world.callbacks); assertEquals(0, world.handler().capacityReads);
					} else probe.finish(a).orElseThrow().close();
					assertReleasedProbe(probe);
				}
			}
			assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		}
	}

	@Test void detachedWitnessSurvivesAccessCloseAndFreshSameHandlerWithIndependentRetainedOwnership() throws Exception {
		var world = new World(); var old = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var layout = old.layout(); var memory = new RetainedMemoryLedger(1_000_000);
		var ledger = new CostLedger(Map.of(WORK, 1_000_000L));
		FluidAddressWitness expected; InventoryFluidAdmission.Ownership ownership;
		try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE);
			var op = a.enter(old.witnessProfile(WitnessUse.CAPTURE))) {
			expected = old.delegate.captureWitness(op); ownership = a.retain();
			assertEquals(368 + 512, ownership.bytes(), "witness plus caller's retained-ticket bookkeeping");
		}
		try (ownership) {
			assertEquals(0, memory.reserved()); assertEquals(ownership.bytes(), memory.retained());
			old.close(); old.close(); assertReleasedAccess(old.delegate);
			assertEquals(ownership.bytes(), memory.retained(), "Access does not own the detached witness ticket");
			try (var fresh = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
				var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE);
				var op = a.enter(fresh.witnessProfile(WitnessUse.COMPARE))) {
				assertEquals(layout, fresh.layout()); assertNotSame(old.delegate, fresh.delegate);
				assertEquals(IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY, fresh.delegate.routeInfo().topology());
				assertEquals(WitnessMatch.SAME, fresh.delegate.compareWitness(expected, op));
				try (var schema = a.enter(fresh.witnessProfile(WitnessUse.COMPARE))) {
					assertEquals(WitnessMatch.SAME, expected.compare(FluidAddressWitness.Schema.NEOFORGE_CAPABILITY_V1,
						new Object[] {world.handler()}, schema), "schema is exactly the handler-only NeoForge vector");
				}
				assertEquals(0, world.handler().reads);
			}
			assertEquals(ownership.bytes(), memory.retained(), "comparison adds no retained ownership");
			assertEquals(0, memory.reserved());
		}
		ownership.close(); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		assertEquals(3 * (72 + 128), ledger.used(WORK), "helper work is debited once per operation");
	}

	@Test void sameLayoutFreshHandlerIsChangedBeforeAnyContentRead() {
		var world = new World(); var original = world.handler();
		var old = resolve(world, BlockPos.ZERO, Direction.WEST).orElseThrow();
		var expected = old.captureWitness(); var layout = old.layout(); old.close();
		world.handler = new Handler();
		try (var fresh = resolve(world, BlockPos.ZERO, Direction.WEST).orElseThrow()) {
			assertEquals(layout, fresh.layout()); assertTrue(fresh.valid());
			assertEquals(WitnessMatch.CHANGED, fresh.compareWitness(expected));
			assertEquals(0, original.reads); assertEquals(0, world.handler().reads);
		}
	}

	@Test void orderedCapacityChangeAcrossAcquisitionsAlreadyChangesDetachedLayoutWithoutWideningTheWitness() {
		var world = new World(); var old = resolve(world, BlockPos.ZERO, Direction.UP).orElseThrow();
		var expected = old.captureWitness(); var layout = old.layout(); old.close();
		world.handler().capacities = new int[] {2000, 1000}; // Same count and total, different ordered addresses.
		try (var fresh = resolve(world, BlockPos.ZERO, Direction.UP).orElseThrow()) {
			assertNotEquals(layout.layoutData(), fresh.layout().layoutData()); assertNotEquals(layout, fresh.layout());
			assertEquals(WitnessMatch.SAME, fresh.compareWitness(expected), "capacity evidence stays in layout, not the K=1 identity vector");
			assertEquals(0, world.handler().reads);
		}
	}

	@Test void deterministicallyClearedHistoricalHandlerIsUnverifiableNotSameOrChanged() throws Exception {
		var world = new World(); var old = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var expected = old.captureWitness(); old.close(); assertReleasedAccess(old.delegate);
		try (var fresh = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow()) {
			assertEquals(WitnessMatch.SAME, fresh.compareWitness(expected), "closing Access does not clear historical evidence");
			var clear = FluidAddressWitness.class.getDeclaredMethod("clearForTesting", int.class); clear.setAccessible(true); clear.invoke(expected, 0);
			assertEquals(WitnessMatch.UNVERIFIABLE, fresh.compareWitness(expected));
			assertEquals(0, world.handler().reads);
		}
	}

	@Test void cachedK1WitnessProfilesDebitExactlyOnceAndInvokeNoNativeMemberOrAuthorityCallbacks() {
		for (int count : new int[] {0, 2, 4096}) {
			var world = new World(); var handler = world.handler(); handler.count = count; handler.capacities = new int[count];
			try (var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow(); var a = admission()) {
				var capture = access.witnessProfile(WitnessUse.CAPTURE); var compare = access.witnessProfile(WitnessUse.COMPARE);
				assertSame(capture, access.witnessProfile(WitnessUse.CAPTURE)); assertSame(compare, access.witnessProfile(WitnessUse.COMPARE));
				assertEquals(new OperationProfile(new InventoryValidationBudget.Cost(896, 896, 72), 0, 0, 368), capture);
				assertEquals(new OperationProfile(new InventoryValidationBudget.Cost(264, 264, 72), 0, 0, 0), compare);
				int calls = world.callbacks, queries = world.faces.size(), counts = handler.countReads, capacities = handler.capacityReads;
				world.allowed = false; // Witness identity is not authorization and must not call the denied gates.
				FluidAddressWitness expected;
				try (var op = a.enter(capture)) {
					long allocation = op.allowance().attemptedAllocationBytes(), work = op.allowance().attemptedWorkUnits();
					expected = access.delegate.captureWitness(op);
					assertEquals(896, op.allowance().attemptedAllocationBytes() - allocation);
					assertEquals(72, op.allowance().attemptedWorkUnits() - work);
				}
				try (var op = a.enter(compare)) {
					long allocation = op.allowance().attemptedAllocationBytes(), work = op.allowance().attemptedWorkUnits();
					assertEquals(WitnessMatch.SAME, access.delegate.compareWitness(expected, op));
					assertEquals(264, op.allowance().attemptedAllocationBytes() - allocation);
					assertEquals(72, op.allowance().attemptedWorkUnits() - work);
					assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.delegate.compareWitness(expected, op), "no grant refill");
				}
				assertEquals(calls, world.callbacks); assertEquals(queries, world.faces.size());
				assertEquals(counts, handler.countReads); assertEquals(capacities, handler.capacityReads); assertEquals(0, handler.reads);
				assertFalse(access.valid(), "a matching witness cannot replace current authority validation");
			}
		}
	}

	@Test void oneShortWitnessWorkAndMemoryAdmissionRefuseBeforeExecutionAndExactGrantsReleaseAllTickets() {
		var world = new World();
		try (var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow()) {
			var expected = access.captureWitness();
			for (WitnessUse use : WitnessUse.values()) for (boolean memoryShort : new boolean[] {false, true}) for (boolean oneShort : new boolean[] {true, false}) {
				var profile = access.witnessProfile(use);
				long work = profile.ownedCost().workUnits() + 128;
				long bytes = profile.ownedCost().workspaceBytes() + 2048 + (profile.retainedBytes() == 0 ? 0 : profile.retainedBytes() + 512);
				var ledger = new CostLedger(Map.of(WORK, work - (oneShort && !memoryShort ? 1 : 0)));
				var memory = new RetainedMemoryLedger(bytes - (oneShort && memoryShort ? 1 : 0));
				boolean[] entered = {false}; int calls = world.callbacks, counts = world.handler().countReads, capacities = world.handler().capacityReads;
				try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE)) {
					Runnable execute = () -> {
						try (var op = a.enter(profile)) {
							entered[0] = true;
							if (use == WitnessUse.CAPTURE) assertNotNull(access.delegate.captureWitness(op));
							else assertEquals(WitnessMatch.SAME, access.delegate.compareWitness(expected, op));
						}
					};
					if (oneShort) assertThrows(InventoryItemBudget.CaptureLimitException.class, execute::run); else execute.run();
				}
				assertEquals(!oneShort, entered[0]); assertEquals(oneShort ? 0 : work, ledger.used(WORK));
				assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
				assertEquals(calls, world.callbacks); assertEquals(counts, world.handler().countReads);
				assertEquals(capacities, world.handler().capacityReads); assertEquals(0, world.handler().reads);
			}
		}
	}

	@Test void productionGuardedWitnessDelegatesWithoutMemberOrAuthorityCallsAndHonorsWrapperClosure() {
		var world = new World(); var level = allocate(NativeWorld.class); level.server = allocate(NativeServer.class);
		var calls = new AtomicInteger();
		var context = new IPlatformFluidService.Context(level, BlockPos.ZERO, Direction.NORTH, UUID.randomUUID(),
			(position, op) -> { calls.incrementAndGet(); throw new AssertionError("witness member callback"); },
			op -> { calls.incrementAndGet(); throw new AssertionError("witness authority callback"); });
		try (var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			var guarded = IPlatformFluidService.guarded(context, access.delegate); var a = admission()) {
			assertSame(access.witnessProfile(WitnessUse.CAPTURE), guarded.witnessProfile(WitnessUse.CAPTURE));
			assertSame(access.witnessProfile(WitnessUse.COMPARE), guarded.witnessProfile(WitnessUse.COMPARE));
			int before = world.callbacks; FluidAddressWitness expected;
			try (var op = a.enter(guarded.witnessProfile(WitnessUse.CAPTURE))) { expected = guarded.captureWitness(op); }
			try (var op = a.enter(guarded.witnessProfile(WitnessUse.COMPARE))) {
				assertEquals(WitnessMatch.SAME, guarded.compareWitness(expected, op)); guarded.close();
				assertThrows(IllegalStateException.class, () -> guarded.captureWitness(op));
				assertThrows(IllegalStateException.class, () -> guarded.compareWitness(expected, op));
			}
			assertEquals(0, calls.get()); assertEquals(before, world.callbacks); assertEquals(0, world.handler().reads);
		}
	}

	@Test void underfundedAllocationWorkspaceOrWorkRefusesWitnessWithoutSpendingTheCallerAllowance() {
		var world = new World();
		try (var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow()) {
			var expected = access.captureWitness();
			for (WitnessUse use : WitnessUse.values()) for (String dimension : List.of("allocation", "workspace", "work")) {
				var cost = access.witnessProfile(use).ownedCost();
				var allowance = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(
					cost.allocationBytes() - (dimension.equals("allocation") ? 1 : 0),
					cost.workspaceBytes() - (dimension.equals("workspace") ? 1 : 0), cost.workUnits() - (dimension.equals("work") ? 1 : 0)));
				int calls = world.callbacks;
				try (var op = operation(OperationProfile.NONE, allowance)) {
					if (use == WitnessUse.CAPTURE) assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.delegate.captureWitness(op));
					else assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.delegate.compareWitness(expected, op));
				}
				assertEquals(0, allowance.attemptedAllocationBytes()); assertEquals(0, allowance.attemptedWorkUnits());
				assertEquals(0, allowance.peakWorkspaceBytes()); assertEquals(calls, world.callbacks); assertEquals(0, world.handler().reads);
			}
		}
	}

	@Test void witnessHeadroomCheckPrecedesTheTemporaryStrongArrayInBothAdapterOperations() throws Exception {
		// A refused temporary allocation is invisible to Allowance's counters. Audit this ordering
		// boundary in the executed adapter bytecode as well as exercising the refused operations.
		var node = new ClassNode();
		try (var input = java.util.Objects.requireNonNull(FluidCapabilityAccess.class.getResourceAsStream("FluidCapabilityAccess.class"))) {
			new ClassReader(input).accept(node, 0);
		}
		for (String name : List.of("captureWitness", "compareWitness")) {
			var method = node.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElseThrow();
			boolean checked = false; int arrays = 0;
			for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(InventoryValidationBudget.Allowance.class.getName().replace('.', '/'))
					&& call.name.equals("requireAvailable")) checked = true;
				if (instruction instanceof TypeInsnNode array && array.getOpcode() == Opcodes.ANEWARRAY && array.desc.equals("java/lang/Object")) {
					assertTrue(checked, name + " must preflight before allocating its strong identity vector"); arrays++;
				}
			}
			assertEquals(1, arrays, "only the temporary K=1 handler vector is adapter-owned");
		}
	}

	@Test void closedAccessAndExpiredOperationCannotCaptureOrCompareAndDoNotSpendOrCallAnything() throws Exception {
		for (WitnessUse use : WitnessUse.values()) for (boolean closeAccess : new boolean[] {true, false}) {
			var world = new World();
			try (var access = resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow(); var a = admission();
				var op = a.enter(access.witnessProfile(use))) {
				var expected = access.captureWitness(); var allowance = op.allowance();
				long allocation = allowance.attemptedAllocationBytes(), work = allowance.attemptedWorkUnits(); int calls = world.callbacks;
				if (closeAccess) { access.close(); assertReleasedAccess(access.delegate); } else op.close();
				if (use == WitnessUse.CAPTURE) assertThrows(IllegalStateException.class, () -> access.delegate.captureWitness(op));
				else assertThrows(IllegalStateException.class, () -> access.delegate.compareWitness(expected, op));
				assertEquals(allocation, allowance.attemptedAllocationBytes()); assertEquals(work, allowance.attemptedWorkUnits());
				assertEquals(calls, world.callbacks); assertEquals(0, world.handler().reads);
			}
		}
	}

	public static void assertReleasedProbe(IPlatformFluidService.Probe probe) throws Exception {
		for (var field : probe.getClass().getDeclaredFields()) if (List.of("route", "handler", "discovery", "origin").contains(field.getName())) {
			field.setAccessible(true); assertNull(field.get(probe), "closed probe releases " + field.getName());
		}
	}
	public static void assertReleasedAccess(IPlatformFluidService.Access access) throws Exception {
		for (String name : List.of("route", "handler", "capacities")) {
			var field = access.getClass().getDeclaredField(name); field.setAccessible(true); @Nullable Object value = field.get(access);
			if (name.equals("capacities")) assertEquals(0, ((int[]) value).length); else assertNull(value, "closed access releases " + name);
		}
	}

	@Test void productionWorldUsesExistingChunkMapAndExactBlockCapabilityWithNoLoadingOrNBTSave() throws Exception {
		var world = allocate(NativeWorld.class); world.server = allocate(NativeServer.class);
		world.loaded = true; // constructor-free fixture: field initializers did not run
		world.cache = allocate(NativeCache.class); world.cache.chunk = allocate(NativeChunk.class);
		var entity = new net.minecraft.world.level.block.entity.BeaconBlockEntity(BlockPos.ZERO,
			net.minecraft.world.level.block.Blocks.BEACON.defaultBlockState());
		var levelField = net.minecraft.world.level.block.entity.BlockEntity.class.getDeclaredField("level");
		levelField.setAccessible(true); levelField.set(entity, world); // do not run unrelated beacon level initialization
		world.cache.chunk.entity = entity; world.handler = new Handler();
		var context = new IPlatformFluidService.Context(world, BlockPos.ZERO, Direction.EAST, UUID.randomUUID(),
			(position, op) -> { op.allowance().debit(GATE); world.contextRequires++; return position.equals(BlockPos.ZERO); },
			op -> { op.allowance().debit(GATE); return true; });
		var service = new PlatformFluidServiceImpl();
		var access = nativeAccess(service, context);
		assertEquals(0, world.handler.reads); assertTrue(access.valid());
		int retainedChars = access.layout().layoutData().length();
		world.contextRequires = 0; assertTrue(access.valid());
		int validationRequires = world.contextRequires;
		assertTrue(validationRequires <= access.validationProfile().memberGateCalls());
		world.contextRequires = 0;
		access.consume(0, entry -> { assertEquals(0, entry.amount()); return true; });
		assertEquals(1, world.handler.reads); assertTrue(world.capabilities > 0); assertTrue(world.cache.chunk.maps > 0);
		System.out.println("NEOFORGE ordinary two-tank consume Context.require=" + world.contextRequires
			+ " validContext.require=" + validationRequires + " validation=" + access.validationCost() + " read=" + access.readCost()
			+ " retainedLayoutChars=" + retainedChars);
		assertTrue(world.contextRequires < 256, "ordinary consume keeps fresh checks within a bounded callback envelope");
		assertTrue(world.contextRequires <= access.readProfile().memberGateCalls());
		world.handler.count = 1; world.handler.capacities = new int[] {1000};
		var single = nativeAccess(service, context);
		world.contextRequires = 0; single.consume(0, entry -> true);
		System.out.println("NEOFORGE ordinary one-tank consume Context.require=" + world.contextRequires);
		world.loaded = false; int queries = world.capabilities;
		assertFalse(access.valid()); assertEquals(queries, world.capabilities, "unloaded member cannot reach capability lookup");
		world.loaded = true; world.cache.chunk.entity = null;
		assertFalse(access.valid()); assertEquals(queries, world.capabilities, "pending/uninstantiated BE cannot be promoted by resolution");
	}

	@NotNullByDefault
	private static final class NativeServer extends net.minecraft.server.dedicated.DedicatedServer {
		private NativeServer() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
	}
	@NotNullByDefault
	private static final class NativeWorld extends ServerLevel {
		private NativeServer server;
		private NativeCache cache;
		private Handler handler;
		private boolean loaded = true;
		private int capabilities, contextRequires;
		private NativeWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public net.minecraft.server.MinecraftServer getServer() { return server; }
		@Override public boolean isLoaded(BlockPos position) { return loaded; }
		@Override public net.minecraft.server.level.ServerChunkCache getChunkSource() { return cache; }
		@Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos position) { throw new AssertionError("platform must use loaded chunk"); }
		@Override public @Nullable net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos position) { throw new AssertionError("no BE/NBT promotion"); }
		@Override public <T, C> @Nullable T getCapability(net.neoforged.neoforge.capabilities.BlockCapability<T, C> capability, BlockPos position, C face) {
			assertSame(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, capability);
			assertEquals(BlockPos.ZERO, position); assertSame(Direction.EAST, face); assertTrue(loaded); capabilities++;
			return capability.typeClass().cast(handler);
		}
	}
	@NotNullByDefault
	private static final class NativeCache extends net.minecraft.server.level.ServerChunkCache {
		private NativeChunk chunk;
		private NativeCache() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
		@Override public @Nullable net.minecraft.world.level.chunk.LevelChunk getChunkNow(int x, int z) { return chunk; }
		@Override public @Nullable net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean required) {
			throw new AssertionError("no loading chunk lookup");
		}
	}
	@NotNullByDefault
	private static final class NativeChunk extends net.minecraft.world.level.chunk.LevelChunk {
		private @Nullable net.minecraft.world.level.block.entity.BlockEntity entity;
		private int maps;
		private NativeChunk() { super(null, null); }
		@Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos position) { return net.minecraft.world.level.block.Blocks.BEACON.defaultBlockState(); }
		@Override public @Unmodifiable Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> getBlockEntities() {
			maps++; return entity == null ? java.util.Map.of() : java.util.Map.of(BlockPos.ZERO, entity);
		}
		@Override public @Nullable net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos position) { throw new AssertionError("no BE/NBT promotion"); }
		@Override public @Nullable net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos position, EntityCreationType mode) { throw new AssertionError("even CHECK promotes NBT"); }
	}

	@NotNullByDefault
	private static final class World implements FluidCapabilityAccess.World {
		private FluidCapabilityAccess.Block block = new FluidCapabilityAccess.Block(new Object(), new Object(), "test:tank", "test:tank");
		private @Nullable Handler handler = new Handler();
		private final List<Direction> faces = new ArrayList<>();
		private boolean allowed = true;
		private int callbacks;
		private Runnable afterRead = () -> {};
		private Runnable afterBlock = () -> {};
		private Runnable afterHeld = () -> {};
		private Handler handler() { return java.util.Objects.requireNonNull(handler); }
		@Override public void require(BlockPos position, Operation operation) {
			gate(operation);
			callbacks++;
			if (!allowed || !position.equals(BlockPos.ZERO)) throw new InventorySourceUnavailableException("denied member/owner");
			if (handler != null && handler.reads > 0) afterRead.run();
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position, Operation operation) { require(position, operation); afterBlock.run(); return block; }
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face, Operation operation) { require(position, operation); faces.add(face); return handler; }
		@Override public String dimension(Operation operation) { return "minecraft:overworld"; }
		@Override public boolean createAvailable() { return false; }
		@Override public boolean held(FluidCapabilityAccess.Member member, Operation operation) { require(member.position(), operation); afterHeld.run(); return member.block().same(block); }
	}

	@NotNullByDefault
	private static final class Handler implements IFluidHandler {
		private int count = 2, reads, capacityReads, countReads;
		private int[] capacities = {1000, 2000};
		private java.util.function.IntConsumer onCapacity = index -> {};
		private FluidStack stack = FluidStack.EMPTY;
		private @Nullable RuntimeException failure;
		private boolean nullStack;
		@Override public boolean equals(@Nullable Object other) { throw new AssertionError("native handler equals"); }
		@Override public int hashCode() { throw new AssertionError("native handler hashCode"); }
		@Override public String toString() { throw new AssertionError("native handler formatting"); }
		@Override public int getTanks() { countReads++; return count; }
		@Override public @Nullable FluidStack getFluidInTank(int tank) {
			reads++;
			if (failure != null) throw failure;
			return nullStack ? null : stack;
		}
		@Override public int getTankCapacity(int tank) { capacityReads++; onCapacity.accept(tank); return capacities[tank]; }
		@Override public boolean isFluidValid(int tank, FluidStack stack) { throw new AssertionError("no simulation"); }
		@Override public int fill(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("read only"); }
	}

	private static <T> T allocate(Class<T> type) {
		try {
			var unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
