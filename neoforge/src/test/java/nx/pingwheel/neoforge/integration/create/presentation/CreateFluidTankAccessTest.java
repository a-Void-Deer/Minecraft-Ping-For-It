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
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.neoforge.platform.FluidCapabilityAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

	@Test void creativeBoilerVirtualAndMovedStructuresFailBeforeCapabilityWithoutAContentFallback() {
		for (String fault : List.of("creative", "boiler-root", "boiler-controller", "boiler-member", "virtual", "moved", "wrong-axis", "wrong-controller")) {
			var world = new World(2, 1); BlockPos hit = new BlockPos(1, 0, 1), other = new BlockPos(1, 0, 0);
			var member = world.entities.get(fault.equals("boiler-controller") ? BlockPos.ZERO : fault.equals("boiler-member") ? other : hit);
			switch (fault) {
				case "creative" -> member.ordinary = false;
				case "boiler-root", "boiler-controller", "boiler-member" -> member.active = true;
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
		assertEquals(List.of(BlockPos.ZERO), world.blockReads, "only already-known controller is acquired before finite shape preflight");
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
		assertEquals(access.readCost().workUnits(), paid.attemptedWorkUnits()); assertEquals(1, world.handler.reads);
	}

	@Test void oneTankMetadataAndFreshReadCostsAreBoundedWithNoMemberTimesTankRescan() {
		for (int width : new int[] {1, 2, 3}) {
			var world = new World(width, 1); var access = access(world, BlockPos.ZERO, Direction.NORTH);
			world.requireCalls = 0; world.metadataCalls = 0; world.handler.capacityReads = 0;
			var paid = new InventoryValidationBudget.Allowance(access.readCost());
			assertTrue(access.consume(0, entry -> true, paid));
			assertEquals(4, world.handler.capacityReads, "two ordered capacity checks per pre/post validation, independent of member count");
			assertEquals(1, world.handler.reads); assertEquals(access.readCost().workUnits(), paid.attemptedWorkUnits());
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
		var held = memory.tryReserve(settings.pendingMemoryBytes() - access.readCost().workspaceBytes()).orElseThrow();
		var scratch = memory.tryReserve(access.readCost().workspaceBytes()).orElseThrow();
		assertTrue(access.consume(0, entry -> true, new InventoryValidationBudget.Allowance(access.readCost())));
		assertEquals(1, world.handler.reads); scratch.close(); held.close();
		assertEquals(settings.pendingMemoryBytes(), memory.remaining());
		System.out.println("NEOFORGE Create one-tank modeled scratch=" + access.readCost().workspaceBytes()
			+ " defaultMemory=" + settings.pendingMemoryBytes());
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
	private static IPlatformFluidService.Access access(World world, BlockPos root, Direction face) {
		world.require(root);
		var route = CreateFluidTankAccess.resolve(world, root, face, world.blocks.get(root), world);
		return FluidCapabilityAccess.find(route).orElseThrow();
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
		private final Object blockType = new Object();
		private final Handler handler = new Handler();
		private boolean available = true, handlerStored = true;
		private int metadataCalls, requireCalls;
		private Runnable afterCapability = () -> {};
		private World(int width, int height) {
			for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
				BlockPos pos = new BlockPos(x, y, z); var member = new Member(); member.width = width; member.height = height; member.master = pos.equals(BlockPos.ZERO);
				entities.put(pos, member); blocks.put(pos, new FluidCapabilityAccess.Block(blockType, member, "create:fluid_tank", "create:fluid_tank"));
				allowed.add(pos); loaded.add(pos);
			}
		}
		@Override public void require(BlockPos position) {
			requireCalls++;
			if (!allowed.contains(position) || !loaded.contains(position)) throw new InventorySourceUnavailableException("denied/unloaded tank member");
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position) {
			require(position); blockReads.add(position); var block = blocks.get(position);
			if (block == null) throw new InventorySourceUnavailableException("missing tank member"); return block;
		}
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face) {
			require(position); queryPositions.add(position); queryFaces.add(face); afterCapability.run(); return handler;
		}
		@Override public String dimension() { return "minecraft:overworld"; }
		@Override public boolean createAvailable() { return available; }
		@Override public boolean held(FluidCapabilityAccess.Member member) {
			require(member.position()); @Nullable FluidCapabilityAccess.Block current = blocks.get(member.position());
			return current != null && member.block().same(current);
		}
		@Override public boolean ordinary(FluidCapabilityAccess.Block block) { metadataCalls++; return block.entity() instanceof Member member && member.ordinary; }
		private Member member(Object object) { metadataCalls++; return (Member) object; }
		@Override public boolean virtual(Object entity) { return member(entity).virtual; }
		@Override public @Nullable BlockPos lastPosition(Object entity) { return member(entity).last; }
		@Override public boolean activeBoiler(Object entity) { return member(entity).active; }
		@Override public @Nullable BlockPos controller(Object entity) { return member(entity).controller; }
		@Override public boolean isController(Object entity) { return member(entity).master; }
		@Override public int width(Object entity) { return member(entity).width; }
		@Override public int height(Object entity) { return member(entity).height; }
		@Override public Direction.Axis axis(Object entity) { return member(entity).axis; }
		@Override public boolean storedHandler(Object controller, IFluidHandler handler) { return handlerStored; }
	}

	@NotNullByDefault
	private static final class Handler implements IFluidHandler {
		private int reads, capacityReads;
		private Runnable afterMetadata = () -> {};
		private @Nullable RuntimeException failure;
		@Override public int getTanks() { afterMetadata.run(); return 1; }
		@Override public FluidStack getFluidInTank(int index) { assertEquals(0, index); reads++; if (failure != null) throw failure; return new FluidStack(Fluids.WATER, 1000); }
		@Override public int getTankCapacity(int index) { assertEquals(0, index); capacityReads++; return 8000; }
		@Override public boolean isFluidValid(int index, FluidStack stack) { throw new AssertionError("no simulation"); }
		@Override public int fill(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("read only"); }
	}
}
