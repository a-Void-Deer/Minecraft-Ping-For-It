package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessMatch;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessUse;
import nx.pingwheel.neoforge.platform.PlatformFluidServiceTest.PaidAccess;
import static nx.pingwheel.neoforge.platform.PlatformFluidServiceTest.*;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.neoforge.platform.FluidCapabilityAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real safety-route algorithm with detached metadata ports; not a live Create/capability launch. */
@NotNullByDefault
class CreateFluidTankAccessTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void everyStaticHitHasOneCanonicalControllerTankAndKeepsTheActualFrozenFace() {
		var world = new World(2, 2);
		for (BlockPos hit : world.blocks.keySet()) for (Direction face : Direction.values()) {
			var access = access(world, hit, face);
			assertEquals(1, access.slots()); assertEquals(8, access.layout().members().size());
			assertEquals(BlockPos.ZERO, access.layout().controller());
			assertEquals(1, access.layout().members().stream().flatMap(member -> member.segments().stream()).mapToInt(segment -> segment.visibleSlots().size()).sum());
			assertEquals("minecraft:overworld|fluid-tank|0, 0, 0|create:fluid_tank", access.alias().orElseThrow());
			assertTrue(access.observe(1, entry -> { assertEquals(81_000, entry.amount()); return true; }).complete());
			assertTrue(world.queryFaces.stream().allMatch(side -> side == face));
			assertTrue(world.queryPositions.stream().allMatch(position -> position.equals(BlockPos.ZERO)));
			world.queryFaces.clear(); world.queryPositions.clear();
		}
		assertEquals(8 * 6, world.handler.reads, "one controller total per sweep, never once per structure member");
	}

	@Test void deniedUnloadedMissingControllerOrMemberIsNeverLookedUpAndCannotReachCapability() {
		for (String fault : List.of("root-denied", "controller-denied", "member-denied", "controller-unloaded", "member-unloaded", "controller-missing", "member-missing")) {
			var world = new World(2, 1); BlockPos hit = new BlockPos(1, 0, 1), other = new BlockPos(1, 0, 0);
			BlockPos denied = fault.startsWith("root") ? hit : fault.startsWith("controller") ? BlockPos.ZERO : other;
			if (fault.endsWith("denied")) world.allowed.remove(denied);
			if (fault.endsWith("unloaded")) world.loaded.remove(denied);
			if (fault.endsWith("missing")) world.blocks.remove(denied);
			assertThrows(InventorySourceUnavailableException.class, () -> access(world, hit, Direction.NORTH), fault);
			if (!fault.endsWith("missing")) assertFalse(world.blockReads.contains(denied), "gate must precede denied/unloaded lookup: " + fault);
			assertTrue(world.queryPositions.isEmpty()); assertEquals(0, world.handler.reads);
		}
	}

	@Test void creativeCurrentBoilerVirtualAndMovedStructuresFailBeforeCapabilityWithoutAContentFallback() {
		for (String fault : List.of("creative", "boiler-controller", "virtual", "moved", "wrong-axis", "wrong-controller")) {
			var world = new World(2, 1); BlockPos hit = new BlockPos(1, 0, 1);
			var member = world.entities.get(fault.equals("boiler-controller") ? BlockPos.ZERO : hit);
			switch (fault) {
				case "creative" -> member.ordinary = false;
				case "boiler-controller" -> member.active = true;
				case "virtual" -> member.virtual = true;
				case "moved" -> member.last = hit.above();
				case "wrong-axis" -> member.axis = Direction.Axis.X;
				case "wrong-controller" -> member.controller = new BlockPos(20, 0, 0);
				default -> fail(fault);
			}
			assertThrows(InventorySourceUnavailableException.class, () -> access(world, hit, Direction.NORTH), fault);
			assertTrue(world.queryPositions.isEmpty(), fault); assertEquals(0, world.handler.reads);
		}
	}

	@ParameterizedTest @ValueSource(ints = {0, 1, 2})
	void inactiveCurrentControllerAcceptsStaleFormerControllerBoilerWithoutProbingMemberBoilers(int hitY) {
		var world = new World(1, 3); BlockPos former = BlockPos.ZERO.above();
		world.entities.get(former).active = true;
		try (var access = access(world, BlockPos.ZERO.above(hitY), Direction.NORTH)) {
			assertTrue(access.valid()); assertEquals(3, access.layout().members().size());
			var paid = new InventoryValidationBudget.Allowance(access.readCost());
			assertTrue(access.consume(0, entry -> { assertEquals(81_000, entry.amount()); return true; }, paid));
			assertTrue(paid.attemptedWorkUnits() > 0); assertTrue(paid.attemptedWorkUnits() <= access.readCost().workUnits());
			assertFalse(world.boilerReads.isEmpty());
			assertTrue(world.boilerReads.stream().allMatch(entity -> entity == world.entities.get(BlockPos.ZERO)),
				"local boiler data is meaningful only on the verified current controller");
			assertTrue(world.queryPositions.stream().allMatch(BlockPos.ZERO::equals));
			assertTrue(world.queryFaces.stream().allMatch(face -> face == Direction.NORTH));
		}
		assertTrue(world.entities.get(former).active, "read validation must not clear Create's stale member state");
		assertEquals(1, world.handler.reads, "one owned controller quantity per hit, not a member sum");
	}

	@Test void activeCurrentControllerRejectsEveryHitEvenWithAnOrdinaryStoredHandlerAndStaleActiveFormerController() {
		for (BlockPos hit : List.of(BlockPos.ZERO, BlockPos.ZERO.above(), BlockPos.ZERO.above(2))) {
			var world = new World(1, 3);
			world.entities.get(BlockPos.ZERO).active = true; world.entities.get(BlockPos.ZERO.above()).active = true;
			assertThrows(InventorySourceUnavailableException.class, () -> access(world, hit, Direction.NORTH));
			assertTrue(world.queryPositions.isEmpty()); assertEquals(0, world.handler.reads);
		}
	}

	@Test void controllerChangeDuringModeValidationCannotBorrowTheFormerMastersCapability() {
		var world = new World(1, 3); var master = world.entities.get(BlockPos.ZERO);
		world.entities.get(BlockPos.ZERO.above()).active = true;
		world.afterBoiler = () -> { master.master = false; master.controller = BlockPos.ZERO.above(); };
		assertThrows(InventorySourceUnavailableException.class, () -> access(world, BlockPos.ZERO.above(2), Direction.NORTH));
		assertTrue(world.queryPositions.isEmpty()); assertEquals(0, world.handler.reads);
	}

	@Test void absentOrUntestedRouteIsRejectedBeforeMetadataAndNoCapabilityCanBeBorrowed() {
		var world = new World(1, 1); world.available = false;
		assertThrows(InventorySourceUnavailableException.class, () -> access(world, BlockPos.ZERO, Direction.UP));
		assertEquals(0, world.metadataCalls); assertTrue(world.queryPositions.isEmpty());
		assertTrue(CreatePresentationAvailability.testedVersion("6.0.10")); assertTrue(CreatePresentationAvailability.testedVersion("6.0.10-281"));
		assertFalse(CreatePresentationAvailability.testedVersion("6.0.11")); assertFalse(CreatePresentationAvailability.testedVersion("6.0.10-282"));
	}

	@Test void enormousConfiguredStructureFailsPreflightBeforePrismAllocationOrMemberPrescan() {
		var world = new World(1, 1); var controller = world.entities.get(BlockPos.ZERO);
		controller.width = 3; controller.height = Integer.MAX_VALUE;
		assertThrows(InventorySourceUnavailableException.class, () -> access(world, BlockPos.ZERO, Direction.UP));
		assertTrue(world.blockReads.stream().allMatch(BlockPos.ZERO::equals), "only root/controller before finite shape preflight");
		assertTrue(world.queryPositions.isEmpty());
	}

	@Test void sameTankTotalCannotAuthorizeChangedTopologyMemberObjectControllerOrCurrentMode() {
		for (String fault : List.of("dimensions", "controller", "entity", "boiler", "version")) {
			var world = new World(2, 1); var access = access(world, new BlockPos(1, 0, 1), Direction.EAST);
			change(world, fault); assertFalse(access.valid(), fault);
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("changed route read contents")), fault);
			assertEquals(0, world.handler.reads);
		}
	}

	@Test void splitAndMergeRetireOldAddressEvidenceAndFreshStaticSourcesResolveTheirCurrentMembers() {
		var world = new World(1, 2); BlockPos upper = BlockPos.ZERO.above();
		var joined = access(world, upper, Direction.UP);
		world.entities.get(BlockPos.ZERO).height = 1;
		world.entities.get(upper).height = 1; world.entities.get(upper).master = true; world.entities.get(upper).controller = upper;
		assertFalse(joined.valid());
		assertThrows(InventorySourceUnavailableException.class, () -> joined.consume(0, entry -> fail("old joined view survived split")));
		var lowerSingle = access(world, BlockPos.ZERO, Direction.UP); var upperSingle = access(world, upper, Direction.UP);
		assertEquals(1, lowerSingle.layout().members().size()); assertEquals(1, upperSingle.layout().members().size());
		assertNotEquals(lowerSingle.alias(), upperSingle.alias());
		world.entities.get(BlockPos.ZERO).height = 2;
		world.entities.get(upper).master = false; world.entities.get(upper).controller = BlockPos.ZERO;
		assertFalse(lowerSingle.valid()); assertFalse(upperSingle.valid());
		var rejoined = access(world, upper, Direction.UP); assertEquals(2, rejoined.layout().members().size());
		assertEquals(joined.alias(), rejoined.alias()); assertEquals(1, rejoined.slots());
		assertEquals(new IPlatformFluidService.Visit(1, true), rejoined.observe(1, entry -> { assertEquals(81_000, entry.amount()); return true; }));
		assertEquals(1, world.handler.reads, "fresh joined structure observes one controller total, not both cells");
	}

	@Test void heldContentIsProvisionalUntilCurrentModeTopologyAndAllMemberAuthorityPostchecksPass() {
		for (String fault : List.of("dimensions", "controller", "entity", "boiler", "version", "permission", "unloaded")) {
			var world = new World(2, 1); var access = access(world, new BlockPos(1, 0, 1), Direction.SOUTH);
			var visits = new AtomicInteger();
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
				assertEquals(81_000, entry.amount()); visits.incrementAndGet(); change(world, fault); return true;
			}), fault);
			assertEquals(1, visits.get()); assertEquals(1, world.handler.reads, "postcheck is metadata only");
		}
	}

	@Test void capabilityOrLastTankMetadataCannotRevokeASiblingThenReachAContentGetter() {
		for (boolean atCapability : new boolean[] {true, false}) {
			var world = new World(2, 1); var access = access(world, BlockPos.ZERO, Direction.NORTH);
			Runnable revoke = () -> world.allowed.remove(new BlockPos(1, 0, 1));
			if (atCapability) world.afterCapability = revoke; else world.handler.afterMetadata = revoke;
			assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("revoked sibling")));
			assertEquals(0, world.handler.reads);
		}
	}

	@Test void staleBoilerCapabilityDoesNotBecomeEmptyStockAfterModeTurnsInactive() {
		var world = new World(1, 1); world.handlerStored = false;
		assertThrows(InventorySourceUnavailableException.class, () -> access(world, BlockPos.ZERO, Direction.NORTH));
		assertEquals(1, world.queryPositions.size(), "mode is inactive, but the returned capability is still rejected");
		assertEquals(0, world.handler.reads);
		world.handlerStored = true; var access = access(world, BlockPos.ZERO, Direction.NORTH);
		world.handlerStored = false; assertFalse(access.valid());
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("cached fake empty")));
		assertEquals(0, world.handler.reads);
	}

	@Test void refusalChargesNoMembersAndFailureKeepsTheAdmittedAttemptWithoutInventingEmpty() {
		var world = new World(2, 1); var access = access(world, BlockPos.ZERO, Direction.DOWN);
		world.metadataCalls = 0; world.blockReads.clear();
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.consume(0, entry -> true,
			new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertEquals(0, world.metadataCalls); assertTrue(world.blockReads.isEmpty());
		var paid = new InventoryValidationBudget.Allowance(access.readCost());
		world.handler.failure = new IllegalStateException("Create capability failed");
		assertSame(world.handler.failure, assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> fail("exception is not empty"), paid)));
		assertTrue(paid.attemptedWorkUnits() >= access.readProfile().ownedCost().workUnits()); assertEquals(1, world.handler.reads);
	}

	@Test void oneTankMetadataAndFreshReadCostsAreBoundedWithNoMemberTimesTankRescan() {
		for (int width : new int[] {1, 2, 3}) {
			var world = new World(width, 1); var access = access(world, BlockPos.ZERO, Direction.NORTH);
			world.requireCalls = 0; world.metadataCalls = 0; world.handler.capacityReads = 0;
			var paid = new InventoryValidationBudget.Allowance(access.readCost());
			assertTrue(access.consume(0, entry -> true, paid));
			assertEquals(4, world.handler.capacityReads, "two ordered capacity checks per pre/post validation, independent of member count");
			assertEquals(1, world.handler.reads); assertTrue(paid.attemptedWorkUnits() <= access.readCost().workUnits());
			assertTrue(world.requireCalls <= 128 + 128 * width * width);
			System.out.println("NEOFORGE Create members=" + width * width + " tanks=1 requireCalls=" + world.requireCalls
				+ " metadataCalls=" + world.metadataCalls + " capacityReads=" + world.handler.capacityReads
				+ " read=" + access.readCost() + " validation=" + access.validationCost()
				+ " retainedLayoutChars=" + access.layout().layoutData().length());
		}
	}

	@Test void createSingleTankAdvancesWithOnlyItsAdmittedMetadataScratchRemaining() {
		var world = new World(1, 1); var access = access(world, BlockPos.ZERO, Direction.NORTH);
		var settings = nx.pingwheel.common.config.InventorySettings.serverDefaults();
		var memory = new nx.pingwheel.common.presentation.source.RetainedMemoryLedger(settings.pendingMemoryBytes());
		long needed = access.readCost().workspaceBytes() + 2048;
		try (var held = memory.tryReserve(settings.pendingMemoryBytes() - needed).orElseThrow();
			var a = admission(10_000_000, memory); var op = a.enter(access.readProfile())) {
			assertTrue(access.delegate.consume(0, entry -> true, op)); assertEquals(1, world.handler.reads);
		}
		assertEquals(settings.pendingMemoryBytes(), memory.remaining());
		System.out.println("NEOFORGE Create one-tank modeled scratch=" + access.readCost().workspaceBytes()
			+ " admissionHeadroom=" + needed + " defaultMemory=" + settings.pendingMemoryBytes());
	}

	@Test void staged108MemberControllerTraversalReallyFinishesReadsAndReleasesCallerOwnership() throws Exception {
		var world = new World(3, 12); BlockPos hit = new BlockPos(2, 11, 2);
		world.requireAllBeforeCapability = true;
		// Ordinary source callback envelopes (before optional external membership work), with
		// independent execution debits rather than charging the provider's composed profile twice.
		world.memberCost = new InventoryValidationBudget.Cost(1024, 1024, 528);
		world.authorityCost = new InventoryValidationBudget.Cost(9216, 9216, 2560);
		var profiles = new ArrayList<IPlatformFluidService.OperationProfile>();
		var memory = new RetainedMemoryLedger(64L << 20);
		// Same engineering work ledger used by Runtime, not the unrelated provider-call counter.
		long work = 4096L * InventoryItemBudget.workUpperBound(4096);
		var ledger = new CostLedger(Map.of(WORK, work));
		InventoryFluidAdmission.Ownership ownership; IPlatformFluidService.Access access;
		try (var a = new InventoryFluidAdmission(ledger, WORK, memory, world.memberCost, world.authorityCost);
			var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE); var probe = probe(world, hit, Direction.SOUTH, op)) {
			assertEquals(108, probe.shape().members()); assertTrue(probe.shape().indexedSlots().isEmpty());
			assertTrue(world.blockReads.stream().allMatch(pos -> pos.equals(hit) || pos.equals(BlockPos.ZERO)));
			assertEquals(0, world.handler.capacityReads); assertEquals(0, world.handler.countReads); assertTrue(world.queryPositions.isEmpty());
			int calls = world.requireCalls; probe.shape(); probe.finishProfile(); assertEquals(calls, world.requireCalls);
			access = probe.finish(profile -> {
				profiles.add(profile);
				if (profiles.size() == 1) { assertTrue(world.queryPositions.isEmpty()); assertEquals(0, world.handler.countReads); }
				else { assertEquals(1, world.handler.countReads); assertEquals(0, world.handler.capacityReads); assertEquals(108, new HashSet<>(world.blockReads).size()); }
				return a.enter(profile);
			}).orElseThrow();
			assertEquals(2, profiles.size()); assertEquals(probe.finishProfile(), profiles.getFirst()); assertReleasedProbe(probe);
			assertThrows(IllegalStateException.class, () -> probe.finish(a));
			assertEquals(108, access.layout().members().size()); assertEquals(1, access.slots());
			assertEquals(IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE, access.routeInfo().topology());
			assertTrue(world.queryPositions.stream().allMatch(BlockPos.ZERO::equals)); assertEquals(0, world.handler.reads);
			world.requireCalls = 0; world.metadataCalls = 0; int capacities = world.handler.capacityReads;
			try (var read = a.enter(access.readProfile())) {
				assertSame(op.allowance(), read.allowance()); long before = read.allowance().attemptedWorkUnits();
				assertTrue(access.consume(0, entry -> { assertEquals(81_000, entry.amount()); return true; }, read));
				assertEquals(access.readProfile().ownedCost().workUnits()
					+ (world.memberCost.workUnits() + world.authorityCost.workUnits()) * world.requireCalls,
					read.allowance().attemptedWorkUnits() - before);
			}
			assertEquals(1, world.handler.reads); assertEquals(4, world.handler.capacityReads - capacities);
			assertTrue(world.requireCalls <= access.readProfile().memberGateCalls());
			ownership = a.retain();
			assertEquals(profiles.stream().mapToLong(IPlatformFluidService.OperationProfile::retainedBytes).sum() + 768, ownership.bytes());
			System.out.println("NEOFORGE staged Create M=108 N=1 structure=" + profiles.getFirst() + " address=" + profiles.getLast()
				+ " read=" + access.readProfile() + " retained=" + ownership.bytes() + " actualRequires=" + world.requireCalls
				+ " actualMetadata=" + world.metadataCalls + " composedRead=" + access.readProfile().cost(world.memberCost, world.authorityCost)
				+ " attemptedWork=" + op.allowance().attemptedWorkUnits() + " reservedScratch=" + memory.reserved() + " workLimit=" + work);
		}
		assertTrue(ledger.used(WORK) > 0); assertEquals(0, memory.reserved()); assertEquals(ownership.bytes(), memory.retained());
		int calls = world.requireCalls; access.close(); access.close(); assertEquals(calls, world.requireCalls); assertReleasedAccess(access);
		ownership.close(); ownership.close(); assertEquals(0, memory.retained());
	}

	@Test void oneShortStructureAndAddressAdmissionNeverRunTheNextStagesCallbacks() throws Exception {
		for (boolean refuseStructure : new boolean[] {true, false}) {
			var world = new World(2, 1); var memory = new RetainedMemoryLedger(1_000_000);
			var ledger = new CostLedger(Map.of(WORK, 1_000_000L)); var profiles = new ArrayList<IPlatformFluidService.OperationProfile>();
			try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
				var probe = probe(world, BlockPos.ZERO, Direction.UP, op)) {
				int blockReads = world.blockReads.size(), calls = world.requireCalls;
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> probe.finish(profile -> {
					profiles.add(profile);
					if (refuseStructure || profiles.size() == 2) {
						long oneShort = profile.cost(GATE, GATE).workUnits() + 128 - 1;
						// Withhold the remaining headroom on THIS ledger: no new allowance or refund.
						var withheld = ledger.tryReserve(Map.of(WORK, ledger.remaining(WORK) - oneShort)).orElseThrow();
						try { return a.enter(profile); } finally { withheld.close(); }
					}
					return a.enter(profile);
				}));
				if (refuseStructure) { assertEquals(blockReads, world.blockReads.size()); assertEquals(calls, world.requireCalls); assertTrue(world.queryPositions.isEmpty()); }
				else { assertEquals(1, world.handler.countReads); assertEquals(4, new HashSet<>(world.blockReads).size()); }
				assertEquals(0, world.handler.capacityReads); assertEquals(0, world.handler.reads); assertReleasedProbe(probe);
				assertThrows(IllegalStateException.class, () -> probe.finish(a));
			}
			assertTrue(ledger.used(WORK) > 0); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		}
	}

	@Test void changedProbeShapeFailsBeforeNewPrismReadsOrControllerCapabilityAndFailureRetainsPaidPrefix() throws Exception {
		for (String change : List.of("dimensions", "controller", "entity", "boiler", "version", "denied-member", "throw-member")) {
			var world = new World(2, 1); BlockPos hit = new BlockPos(1, 0, 1);
			var memory = new RetainedMemoryLedger(1_000_000); var ledger = new CostLedger(Map.of(WORK, 1_000_000L));
			try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
				var probe = probe(world, hit, Direction.NORTH, op)) {
				long prefix = op.allowance().attemptedWorkUnits(); world.blockReads.clear();
				if (change.equals("denied-member")) world.allowed.remove(new BlockPos(1, 0, 0));
				else if (change.equals("throw-member")) world.throwAt = new BlockPos(1, 0, 0);
				else change(world, change);
				assertThrows(RuntimeException.class, () -> probe.finish(a), change);
				assertTrue(op.allowance().attemptedWorkUnits() > prefix); assertTrue(world.queryPositions.isEmpty());
				assertEquals(0, world.handler.countReads); assertEquals(0, world.handler.capacityReads); assertReleasedProbe(probe);
				if (!change.endsWith("member")) assertTrue(world.blockReads.stream().allMatch(pos -> pos.equals(hit) || pos.equals(BlockPos.ZERO)));
			}
			assertTrue(ledger.used(WORK) > 0); assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		}
	}
	@Test void createProbeCannotFinishAfterOriginClosesOrReenterOrSwitchAnIncrementalAllowance() throws Exception {
		for (String fault : List.of("closed", "first-caller", "address-caller", "expire-first", "expire-address", "reenter")) {
			var world = new World(1, 1);
			try (var a = admission(); var other = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
				var probe = probe(world, BlockPos.ZERO, Direction.NORTH, op)) {
				int calls = world.requireCalls;
				if (fault.equals("closed")) {
					op.close(); assertThrows(IllegalStateException.class, () -> probe.finish(a)); assertEquals(calls, world.requireCalls);
				} else {
					var stages = new AtomicInteger();
					IPlatformFluidService.Admission admission = profile -> {
						int stage = stages.incrementAndGet();
						assertThrows(IllegalStateException.class, () -> probe.finish(a), "reentrant finish is consumed before Admission callback");
						var next = fault.equals("first-caller") || fault.equals("address-caller") && stage == 2 ? other.enter(profile) : a.enter(profile);
						if (fault.equals("expire-first") || fault.equals("expire-address") && stage == 2) op.close();
						return next;
					};
					if (fault.equals("reenter")) probe.finish(admission).orElseThrow().close();
					else assertThrows(IllegalStateException.class, () -> probe.finish(admission));
					if (fault.equals("first-caller")) assertEquals(calls, world.requireCalls);
					if (fault.equals("expire-first")) assertEquals(calls, world.requireCalls);
					if (fault.equals("address-caller")) { assertEquals(1, world.handler.countReads); assertEquals(0, world.handler.capacityReads); }
				}
				assertReleasedProbe(probe);
			}
		}
	}

	@Test void countCallbackClosingOriginRejectsBeforeAddressAdmissionAndReleasesFailedMetadata() throws Exception {
		var world = new World(1, 1); var memory = new RetainedMemoryLedger(1_000_000);
		var ledger = new CostLedger(Map.of(WORK, 1_000_000L)); var stages = new AtomicInteger();
		long attempted;
		try (var a = new InventoryFluidAdmission(ledger, WORK, memory, GATE, GATE); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
			var probe = probe(world, BlockPos.ZERO, Direction.NORTH, op)) {
			var allowance = op.allowance(); long prefix = allowance.attemptedWorkUnits();
			world.handler.afterMetadata = op::close;
			assertThrows(IllegalStateException.class, () -> probe.finish(profile -> {
				stages.incrementAndGet(); return a.enter(profile);
			}));
			assertEquals(1, stages.get(), "expired origin must not invoke address admission");
			assertEquals(1, world.handler.countReads); assertEquals(0, world.handler.capacityReads); assertEquals(0, world.handler.reads);
			assertThrows(IllegalStateException.class, op::allowance); assertReleasedProbe(probe);
			assertThrows(IllegalStateException.class, () -> probe.finish(a), "failed finish remains single-use");
			attempted = allowance.attemptedWorkUnits(); assertTrue(attempted > prefix); assertTrue(memory.reserved() > 0);
		}
		assertEquals(attempted, ledger.used(WORK), "failed metadata prefix stays paid");
		assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
	}

	@Test void addressStageShapeMutationIsRejectedBeforeCapacityAndNullCapabilityReleasesStructureOwnership() throws Exception {
		for (String fault : List.of("dimensions", "controller", "boiler", "version", "null-capability")) {
			var world = new World(2, 1); var memory = new RetainedMemoryLedger(1_000_000);
			try (var a = admission(1_000_000, memory); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE);
				var probe = probe(world, new BlockPos(1, 0, 1), Direction.NORTH, op)) {
				if (fault.equals("null-capability")) {
					world.missingCapability = true; assertTrue(probe.finish(a).isEmpty());
				} else {
					var stages = new AtomicInteger();
					assertThrows(InventorySourceUnavailableException.class, () -> probe.finish(profile -> {
						var next = a.enter(profile); if (stages.incrementAndGet() == 2) change(world, fault); return next;
					}));
				}
				assertEquals(0, world.handler.capacityReads); assertEquals(0, world.handler.reads); assertReleasedProbe(probe);
			}
			assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		}
	}

	@Test void sameStructureAndHandlerAcrossFreshScopeContextsKeepWitnessContinuityButStillRequireCurrentCreateGates() throws Exception {
		var previous = new World(2, 1); BlockPos hit = new BlockPos(1, 0, 1);
		var old = access(previous, hit, Direction.NORTH); var layout = old.layout(); var alias = old.alias();
		var expected = old.captureWitness(); old.close(); assertReleasedAccess(old.delegate);
		// Reacquire a different scope-bound World with the SAME physical members/handler. No external
		// scope classification is implemented here; common owns that decision independently of identity.
		var current = new World(previous); previous.allowed.clear(); previous.available = false;
		try (var fresh = access(current, hit, Direction.NORTH); var a = admission();
			var op = a.enter(fresh.witnessProfile(WitnessUse.COMPARE))) {
			assertEquals(layout, fresh.layout()); assertEquals(alias, fresh.alias()); assertTrue(fresh.valid());
			assertEquals(IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE, fresh.delegate.routeInfo().topology());
			int gates = current.requireCalls, metadata = current.metadataCalls, queries = current.queryPositions.size();
			int counts = current.handler.countReads, capacities = current.handler.capacityReads;
			assertEquals(WitnessMatch.SAME, fresh.delegate.compareWitness(expected, op));
			assertEquals(gates, current.requireCalls); assertEquals(metadata, current.metadataCalls);
			assertEquals(queries, current.queryPositions.size()); assertEquals(counts, current.handler.countReads);
			assertEquals(capacities, current.handler.capacityReads); assertEquals(0, current.handler.reads);
			current.entities.get(BlockPos.ZERO).active = true;
			assertFalse(fresh.valid(), "SAME does not cache or replace the current boiler/mode check");
			assertThrows(InventorySourceUnavailableException.class, () -> fresh.consume(0, entry -> fail("mode change cannot read")));
			assertEquals(0, current.handler.reads);
			assertTrue(current.queryFaces.stream().allMatch(face -> face == Direction.NORTH));
			assertTrue(current.queryPositions.stream().allMatch(BlockPos.ZERO::equals));
		}
	}

	@Test void actualHandlerReplacementAcrossFreshScopeContextsIsChangedIndependentlyOfScopeClassification() {
		var previous = new World(2, 1); BlockPos hit = new BlockPos(1, 0, 1);
		var old = access(previous, hit, Direction.EAST); var layout = old.layout(); var alias = old.alias();
		var expected = old.captureWitness(); old.close();
		var current = new World(previous); current.handler = new Handler();
		try (var fresh = access(current, hit, Direction.EAST)) {
			assertEquals(layout, fresh.layout()); assertEquals(alias, fresh.alias()); assertTrue(fresh.valid());
			assertEquals(IPlatformFluidService.TopologyEvidence.EXPLICIT_STRUCTURE, fresh.delegate.routeInfo().topology());
			assertEquals(WitnessMatch.CHANGED, fresh.compareWitness(expected), "moving scope cannot hide actual address replacement");
			var held = current.handler; current.handler = new Handler();
			assertFalse(fresh.valid(), "operation-local replacement checks also remain intact");
			assertThrows(InventorySourceUnavailableException.class, () -> fresh.consume(0, entry -> fail("replaced handler cannot read")));
			assertEquals(0, held.reads); assertEquals(0, current.handler.reads); assertEquals(0, previous.handler.reads);
		}
	}

	private static IPlatformFluidService.Probe probe(World world, BlockPos root, Direction face, Operation op) {
		op.allowance().debit(FluidCapabilityAccess.PROBE_PROFILE.ownedCost()); world.require(root, op);
		return CreateFluidTankAccess.probe(world, root, face, world.block(root, op), world, op);
	}

	private static void change(World world, String fault) {
		BlockPos other = new BlockPos(1, 0, 1);
		switch (fault) {
			case "dimensions" -> { world.entities.get(BlockPos.ZERO).width = 1; world.entities.get(BlockPos.ZERO).height = 4; }
			case "controller" -> world.entities.get(other).controller = other;
			case "entity" -> world.blocks.put(other, new FluidCapabilityAccess.Block(world.blockType, new Member(), "create:fluid_tank", "create:fluid_tank"));
			case "boiler" -> world.entities.get(BlockPos.ZERO).active = true;
			case "version" -> world.available = false;
			case "permission" -> world.allowed.remove(other);
			case "unloaded" -> world.loaded.remove(other);
			default -> fail(fault);
		}
	}
	private static PaidAccess access(World world, BlockPos root, Direction face) {
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE)) {
			op.allowance().debit(FluidCapabilityAccess.PROBE_PROFILE.ownedCost());
			world.require(root, op);
			try (var probe = CreateFluidTankAccess.probe(world, root, face, world.block(root, op), world, op)) {
				return new PaidAccess(probe.finish(a).orElseThrow());
			}
		}
	}

	@NotNullByDefault
	private static final class Member {
		private @Nullable BlockPos controller = BlockPos.ZERO, last;
		private int width = 2, height = 1;
		private boolean ordinary = true, active, virtual, master;
		private Direction.Axis axis = Direction.Axis.Y;
	}

	@NotNullByDefault
	private static final class World implements FluidCapabilityAccess.World, CreateFluidTankAccess.Metadata {
		private final Map<BlockPos, FluidCapabilityAccess.Block> blocks = new HashMap<>();
		private final Map<BlockPos, Member> entities = new HashMap<>();
		private final Set<BlockPos> allowed = new HashSet<>(), loaded = new HashSet<>();
		private final List<BlockPos> blockReads = new ArrayList<>(), queryPositions = new ArrayList<>();
		private final List<Direction> queryFaces = new ArrayList<>();
		private final List<Object> boilerReads = new ArrayList<>();
		private final Object blockType;
		private Handler handler = new Handler();
		private boolean available = true, handlerStored = true, missingCapability, requireAllBeforeCapability;
		private int metadataCalls, requireCalls;
		private Runnable afterCapability = () -> {};
		private Runnable afterBoiler = () -> {};
		private @Nullable BlockPos throwAt;
		private InventoryValidationBudget.Cost memberCost = GATE, authorityCost = GATE;
		private World(int width, int height) {
			blockType = new Object();
			for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
				BlockPos pos = new BlockPos(x, y, z); var member = new Member(); member.width = width; member.height = height; member.master = pos.equals(BlockPos.ZERO);
				entities.put(pos, member); blocks.put(pos, new FluidCapabilityAccess.Block(blockType, member, "create:fluid_tank", "create:fluid_tank"));
				allowed.add(pos); loaded.add(pos);
			}
		}
		private World(World previous) {
			blockType = previous.blockType; handler = previous.handler;
			blocks.putAll(previous.blocks); entities.putAll(previous.entities);
			allowed.addAll(previous.allowed); loaded.addAll(previous.loaded);
		}
		@Override public void require(BlockPos position, Operation operation) {
			operation.allowance().debit(memberCost); operation.allowance().debit(authorityCost);
			requireCalls++;
			if (!allowed.contains(position) || !loaded.contains(position)) throw new InventorySourceUnavailableException("denied/unloaded tank member");
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position, Operation operation) {
			require(position, operation); blockReads.add(position); @Nullable var block = blocks.get(position);
			if (position.equals(throwAt)) throw new IllegalStateException("member metadata failure");
			if (block == null) throw new InventorySourceUnavailableException("missing tank member"); return block;
		}
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face, Operation operation) {
			require(position, operation);
			if (requireAllBeforeCapability) assertEquals(blocks.keySet(), new HashSet<>(blockReads), "ALL members precede the controller capability callback");
			queryPositions.add(position); queryFaces.add(face); afterCapability.run(); return missingCapability ? null : handler;
		}
		@Override public String dimension(Operation operation) { return "minecraft:overworld"; }
		@Override public boolean createAvailable() { return available; }
		@Override public boolean held(FluidCapabilityAccess.Member member, Operation operation) {
			require(member.position(), operation); @Nullable FluidCapabilityAccess.Block current = blocks.get(member.position());
			return current != null && member.block().same(current);
		}
		@Override public boolean ordinary(FluidCapabilityAccess.Block block) { metadataCalls++; return block.entity() instanceof Member member && member.ordinary; }
		private Member member(Object object) { metadataCalls++; return (Member) object; }
		@Override public boolean virtual(Object entity) { return member(entity).virtual; }
		@Override public @Nullable BlockPos lastPosition(Object entity) { return member(entity).last; }
		@Override public boolean activeBoiler(Object entity) { boilerReads.add(entity); boolean active = member(entity).active; afterBoiler.run(); return active; }
		@Override public @Nullable BlockPos controller(Object entity) { return member(entity).controller; }
		@Override public boolean isController(Object entity) { return member(entity).master; }
		@Override public int width(Object entity) { return member(entity).width; }
		@Override public int height(Object entity) { return member(entity).height; }
		@Override public Direction.Axis axis(Object entity) { return member(entity).axis; }
		@Override public boolean storedHandler(Object controller, IFluidHandler handler) { return handlerStored; }
	}

	@NotNullByDefault
	private static final class Handler implements IFluidHandler {
		private int reads, capacityReads, countReads;
		private Runnable afterMetadata = () -> {};
		private @Nullable RuntimeException failure;
		@Override public int getTanks() { countReads++; afterMetadata.run(); return 1; }
		@Override public FluidStack getFluidInTank(int index) { assertEquals(0, index); reads++; if (failure != null) throw failure; return new FluidStack(Fluids.WATER, 1000); }
		@Override public int getTankCapacity(int index) { assertEquals(0, index); capacityReads++; return 8000; }
		@Override public boolean isFluidValid(int index, FluidStack stack) { throw new AssertionError("no simulation"); }
		@Override public int fill(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("read only"); }
	}
}
