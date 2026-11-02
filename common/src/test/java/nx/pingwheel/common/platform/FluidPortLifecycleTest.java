package nx.pingwheel.common.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class FluidPortLifecycleTest {
	private static final CostLedger.Counter WORK = new CostLedger.Counter("fluid-lifecycle", CostLedger.Unit.WORK);
	private static final InventoryValidationBudget.Cost GATE = new InventoryValidationBudget.Cost(16, 16, 1);
	private static final BlockPos ROOT = new BlockPos(1, 2, 3);
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static IPlatformFluidService.Context context(List<String> events) {
		var level = DoubleChestFixture.allocate(World.class); level.server = DoubleChestFixture.allocate(Server.class);
		return new IPlatformFluidService.Context(level, ROOT, Direction.NORTH, new UUID(1, 2),
			(pos, op) -> { op.allowance().debit(GATE); events.add("member"); return true; },
			op -> { op.allowance().debit(GATE); events.add("authority"); return true; });
	}
	private static InventoryFluidAdmission admission(long work) {
		return new InventoryFluidAdmission(new CostLedger(Map.of(WORK, work)), WORK, new RetainedMemoryLedger(1_000_000), GATE, GATE);
	}
	@Test void metadataIsPureAndGuardDebitsOnlyItsOwnBoundaryNotTheDelegateProfileTwice() {
		var events = new ArrayList<String>(); var context = context(events); var delegate = new Access(events);
		var guarded = IPlatformFluidService.guarded(context, delegate);
		guarded.layout(); guarded.alias(); guarded.stableCursor(); guarded.slots(); guarded.routeInfo();
		guarded.validationProfile(); guarded.readProfile(); guarded.observationProfile(1); assertTrue(events.isEmpty());
		try (var admission = admission(10_000); var op = admission.enter(guarded.readProfile())) {
			long before = op.allowance().attemptedWorkUnits();
			assertTrue(guarded.consume(0, entry -> { events.add("detach"); return true; }, op));
			assertEquals(List.of("member", "authority", "getter", "detach", "member", "authority"), events);
			assertEquals(guarded.readProfile().cost(GATE, GATE).workUnits(), op.allowance().attemptedWorkUnits() - before);
		}
		guarded.close(); guarded.close(); assertEquals(1, delegate.closes);
	}
	@Test void scopeAndFaceAreFrozenButAuthorityMustBeCheckedAgainAfterVisitor() {
		var events = new ArrayList<String>(); var original = context(events); var mutable = new BlockPos.MutableBlockPos(1, 2, 3);
		boolean[] authorized = {true};
		var context = new IPlatformFluidService.Context(original.level(), mutable, Direction.SOUTH, original.readOwner(), original.memberGate(),
			op -> { op.allowance().debit(GATE); return authorized[0]; });
		mutable.set(99, 99, 99); assertEquals(ROOT, context.root()); assertEquals(Direction.SOUTH, context.face());
		var guarded = IPlatformFluidService.guarded(context, new Access(events));
		try (var admission = admission(10_000); var op = admission.enter(guarded.readProfile())) {
			assertThrows(InventorySourceUnavailableException.class, () -> guarded.consume(0, entry -> { authorized[0] = false; return true; }, op));
			assertEquals(1, events.stream().filter("getter"::equals).count());
		}
	}
	@Test void shapeFinishAndSlotGrowthShareOriginalAllowanceAndRefusalRunsNoLargerAllocation() {
		var events = new ArrayList<String>();
		try (var admission = admission(10_000); var probeOp = admission.enter(IPlatformFluidService.OperationProfile.NONE)) {
			try (var probe = new Probe(events, probeOp.allowance())) {
				assertEquals(new IPlatformFluidService.Shape(2, OptionalInt.empty(), true), probe.shape());
				probe.finishProfile(); assertTrue(events.isEmpty());
				probe.finish(admission).orElseThrow().close();
				assertEquals(List.of("members-gated", "controller-count", "slot-allocation"), events);
				assertThrows(IllegalStateException.class, () -> probe.finish(admission));
			}
		}
		events.clear();
		try (var admission = admission(300); var probeOp = admission.enter(IPlatformFluidService.OperationProfile.NONE);
			var probe = new Probe(events, probeOp.allowance())) {
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> probe.finish(admission));
			assertEquals(List.of("members-gated", "controller-count"), events);
		}
	}
	@Test void membershipEvidenceCannotPromoteUnknownOrOutsideIntoInside() {
		assertEquals(InventoryValidationBudget.NONE, new UnsupportedProvider().membershipCost());
		assertEquals(ExternalBlockServerProvider.Membership.UNKNOWN, new UnsupportedProvider().membership(context(new ArrayList<>()).level(), ROOT,
			new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertEquals(Optional.of("scope"), ExternalBlockServerProvider.Membership.inside("scope").subLevelId());
		assertThrows(IllegalArgumentException.class, () -> new ExternalBlockServerProvider.Membership(
			ExternalBlockServerProvider.MembershipState.INSIDE, Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new ExternalBlockServerProvider.Membership(
			ExternalBlockServerProvider.MembershipState.OUTSIDE, Optional.of("scope")));
	}
	@Test void guardedWitnessUsesOriginalGrantWithoutProviderCallbacksAndSurvivesAccessClose() {
		var events = new ArrayList<String>(); var context = context(events); var delegate = new Access(events);
		var guarded = IPlatformFluidService.guarded(context, delegate);
		var capture = guarded.witnessProfile(IPlatformFluidService.WitnessUse.CAPTURE);
		var compare = guarded.witnessProfile(IPlatformFluidService.WitnessUse.COMPARE);
		assertTrue(events.isEmpty());
		FluidAddressWitness witness;
		try (var admission = admission(10_000)) {
			try (var operation = admission.enter(capture)) {
				long before = operation.allowance().attemptedWorkUnits(); witness = guarded.captureWitness(operation);
				assertEquals(capture.ownedCost().workUnits(), operation.allowance().attemptedWorkUnits() - before);
			}
			try (var operation = admission.enter(compare)) {
				assertEquals(IPlatformFluidService.WitnessMatch.SAME, guarded.compareWitness(witness, operation));
			}
			assertTrue(events.isEmpty(), "witness is metadata, not a second validation operation");
			guarded.close();
			try (var operation = admission.enter(compare)) {
				assertThrows(IllegalStateException.class, () -> guarded.compareWitness(witness, operation));
				assertEquals(IPlatformFluidService.WitnessMatch.SAME,
					witness.compare(FluidAddressWitness.Schema.NEOFORGE_CAPABILITY_V1, new Object[] {delegate.identity}, operation));
			}
		}
	}
	@NotNullByDefault
	private static final class Probe implements IPlatformFluidService.Probe {
		private final List<String> events;
		private final InventoryValidationBudget.Allowance original;
		private boolean done;
		private Probe(List<String> events, InventoryValidationBudget.Allowance original) { this.events = events; this.original = original; }
		@Override public IPlatformFluidService.Shape shape() { return new IPlatformFluidService.Shape(2, OptionalInt.empty(), true); }
		@Override public IPlatformFluidService.OperationProfile finishProfile() { return IPlatformFluidService.OperationProfile.NONE; }
		@Override public Optional<IPlatformFluidService.Access> finish(IPlatformFluidService.Admission admission) {
			if (done) throw new IllegalStateException("probe consumed"); done = true;
			try (var members = admission.enter(finishProfile())) {
				assertSame(original, members.allowance()); events.add("members-gated"); events.add("controller-count");
				try (var slots = admission.enter(new IPlatformFluidService.OperationProfile(new InventoryValidationBudget.Cost(100, 100, 100), 0, 0, 100))) {
					assertSame(original, slots.allowance()); slots.allowance().debit(new InventoryValidationBudget.Cost(100, 100, 100));
					events.add("slot-allocation"); return Optional.of(new Access(new ArrayList<>()));
				}
			}
		}
		@Override public void close() { done = true; }
	}
	@NotNullByDefault
	private static final class Access implements IPlatformFluidService.Access {
		private final List<String> events;
		private final Object identity = new Object();
		private int closes;
		private Access(List<String> events) { this.events = events; }
		@Override public InventorySnapshotLayout layout() { return new InventorySnapshotLayout("test", "", ROOT,
			List.of(new InventorySnapshotLayout.Member(ROOT, "test:block", "test:entity", "root", List.of()))); }
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 1; }
		@Override public IPlatformFluidService.RouteInfo routeInfo() { return new IPlatformFluidService.RouteInfo(IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE, "fixture"); }
		@Override public IPlatformFluidService.OperationProfile witnessProfile(IPlatformFluidService.WitnessUse use) { return FluidAddressWitness.profile(use, 1); }
		@Override public FluidAddressWitness captureWitness(IPlatformFluidService.Operation operation) {
			operation.allowance().requireAvailable(witnessProfile(IPlatformFluidService.WitnessUse.CAPTURE).ownedCost());
			return FluidAddressWitness.capture(FluidAddressWitness.Schema.NEOFORGE_CAPABILITY_V1, new Object[] {identity}, operation);
		}
		@Override public IPlatformFluidService.WitnessMatch compareWitness(FluidAddressWitness expected, IPlatformFluidService.Operation operation) {
			operation.allowance().requireAvailable(witnessProfile(IPlatformFluidService.WitnessUse.COMPARE).ownedCost());
			return expected.compare(FluidAddressWitness.Schema.NEOFORGE_CAPABILITY_V1, new Object[] {identity}, operation);
		}
		@Override public IPlatformFluidService.OperationProfile validationProfile() { return IPlatformFluidService.OperationProfile.NONE; }
		@Override public IPlatformFluidService.OperationProfile readProfile() { return new IPlatformFluidService.OperationProfile(new InventoryValidationBudget.Cost(32, 32, 7), 0, 0, 0); }
		@Override public boolean valid(IPlatformFluidService.Operation operation) { operation.allowance(); return true; }
		@Override public boolean consume(int slot, IPlatformFluidService.Visitor visitor, IPlatformFluidService.Operation operation) {
			operation.allowance().debit(readProfile().ownedCost()); events.add("getter"); return visitor.visit(IPlatformFluidService.Entry.empty());
		}
		@Override public void close() { closes++; }
	}
	@NotNullByDefault
	private static final class UnsupportedProvider implements ExternalBlockServerProvider {
		@Override public String providerId() { return "unsupported"; }
		@Override public ValidationResult validate(ServerLevel level, nx.pingwheel.common.domain.Target.ExternalBlockTarget target) { return new ValidationResult.Invalid(); }
		@Override public MaterializationResult materialize(ServerLevel level, nx.pingwheel.common.domain.Target.ExternalBlockTarget target) { return new MaterializationResult.Invalid(); }
		@Override public RefreshResult refresh(ServerLevel level, nx.pingwheel.common.domain.Target.ExternalBlockTarget target) { return new RefreshResult.Invalid(); }
		@Override public Optional<ExternalBlockName> resolveName(ServerLevel level, nx.pingwheel.common.domain.Target.ExternalBlockTarget target) { return Optional.empty(); }
		@Override public void release(net.minecraft.server.MinecraftServer server, nx.pingwheel.common.domain.Target.ExternalBlockTarget target) {}
	}
	@NotNullByDefault
	private static final class World extends ServerLevel {
		private @Nullable Server server;
		private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public net.minecraft.server.MinecraftServer getServer() { return Objects.requireNonNull(server); }
	}
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
	}
}
