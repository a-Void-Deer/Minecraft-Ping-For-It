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
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executed capability seam; no Create classes, loader launch, live world or native copying is assumed. */
@NotNullByDefault
class PlatformFluidServiceTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void serviceIsRegisteredAndResolutionIsMetadataOnlyThroughExactlyTheFrozenFace() {
		assertTrue(ServiceLoader.load(IPlatformFluidService.class).stream().anyMatch(provider -> provider.type() == PlatformFluidServiceImpl.class));
		var world = new World();
		var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.WEST).orElseThrow();
		assertTrue(access.stableCursor()); assertEquals(2, access.slots()); assertEquals(0, world.handler().reads);
		assertTrue(access.valid()); assertEquals(0, world.handler().reads, "postvalidation never reads contents");
		assertTrue(access.alias().isEmpty());
		var segment = access.layout().members().getFirst().segments().getFirst();
		assertEquals(IPlatformFluidService.ADDRESS_SCHEMA, segment.schemaId()); assertEquals(List.of(0, 1), segment.visibleSlots());
		assertTrue(world.faces.stream().allMatch(face -> face == Direction.WEST));
		world.handler = null;
		assertFalse(access.valid(), "null current capability is unavailable, not an empty view");
		assertTrue(PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.WEST).isEmpty());
		assertTrue(world.faces.stream().allMatch(face -> face == Direction.WEST), "no null/other-face fallback");
	}

	@Test void deniedResolutionAdmissionInvokesNoAuthorityMembershipWorldOrCapabilityCallback() {
		AtomicInteger gates = new AtomicInteger();
		var context = new IPlatformFluidService.Context(allocate(ServerLevel.class), BlockPos.ZERO, Direction.NORTH, UUID.randomUUID(),
			position -> { gates.incrementAndGet(); return true; }, () -> { gates.incrementAndGet(); return true; });
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> new PlatformFluidServiceImpl().find(context,
			new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertEquals(0, gates.get());
	}

	@Test void deniedReadAndValidationAdmissionReachNoCallbacksAndAttemptedFailureStaysPaid() {
		var world = new World(); var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		world.callbacks = 0;
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.consume(0, entry -> fail("unadmitted visitor"),
			new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.valid(new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertEquals(0, world.callbacks); assertEquals(0, world.handler().reads);
		var paid = new InventoryValidationBudget.Allowance(access.readCost());
		var failure = new IllegalStateException("getter failure"); world.handler().failure = failure;
		assertSame(failure, assertThrows(IllegalStateException.class, () -> access.consume(0, entry -> fail("throw is not empty"), paid)));
		assertEquals(access.readCost().workUnits(), paid.attemptedWorkUnits());
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> access.consume(0, entry -> true, paid));
		assertEquals(1, world.handler().reads);
	}

	@Test void nativeIntMaximumUsesCheckedLongUnitsAndActualExplicitBorrowBeforeAnyLaterCallback() {
		var world = new World(); var handler = world.handler();
		var name = Component.literal("getter-time");
		handler.stack = new FluidStack(Fluids.WATER, Integer.MAX_VALUE); handler.stack.set(DataComponents.CUSTOM_NAME, name);
		var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.UP).orElseThrow();
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
		var world = new World(); var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.DOWN).orElseThrow();
		assertEquals(new IPlatformFluidService.Visit(1, false), access.observe(2, entry -> { assertNull(entry.resident()); assertEquals(0, entry.amount()); return false; }));
		assertEquals(1, world.handler().reads);
		assertEquals(new IPlatformFluidService.Visit(2, true), access.observe(2, entry -> true)); assertEquals(3, world.handler().reads);
		world.handler().nullStack = true;
		assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("null is not empty")));
	}

	@Test void originalRouteObjectsAndPostReadAuthorityAndLayoutAreRequiredEvenWithEqualTankTotals() {
		for (String change : List.of("permission", "member", "handler", "count")) {
			var world = new World(); var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
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
			assertTrue(PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).isEmpty());
			assertTrue(world.faces.isEmpty()); assertEquals(0, world.handler().reads);
		}
	}

	@Test void zeroTankObservationCannotCompleteAfterAuthorityOrRouteLoss() {
		var world = new World(); world.handler().count = 0;
		var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		assertEquals(new IPlatformFluidService.Visit(0, true), access.observe(0, entry -> fail("no address")));
		world.allowed = false;
		assertThrows(InventorySourceUnavailableException.class, () -> access.observe(0, entry -> fail("no address")));
		assertEquals(0, world.handler().reads);
	}

	@Test void orderedPublicCapacitiesDetectSameCountReorderWithoutAnotherContentRead() {
		var world = new World(); var handler = world.handler(); handler.capacities = new int[] {1000, 2000};
		var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
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
			var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
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
			var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			int before = handler.capacityReads;
			if (duringCapacity) handler.onCapacity = index -> { if (index == 1) handler.count = changed; };
			else world.afterBlock = () -> { if (handler.capacityReads > before) handler.count = changed; };
			assertFalse(access.valid(), "last capacity/route callback must not hide changed count");
			assertEquals(0, handler.reads);
		}
		var world = new World(); var handler = world.handler();
		handler.onCapacity = index -> { if (index == 1) handler.count = 3; };
		assertThrows(InventorySourceUnavailableException.class, () -> PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH),
			"initial capacity discovery also checks the final count");
		var changedLayout = new World(); var layoutHandler = changedLayout.handler();
		var access = PlatformFluidServiceImpl.resolve(changedLayout, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		int before = layoutHandler.capacityReads;
		changedLayout.afterBlock = () -> { if (layoutHandler.capacityReads > before) layoutHandler.capacities[0] = 2000; };
		assertFalse(access.valid(), "post-capacity route callback capacity changes are checked again");
		var changedHeld = new World(); var heldHandler = changedHeld.handler();
		var heldAccess = PlatformFluidServiceImpl.resolve(changedHeld, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		int heldBefore = heldHandler.capacityReads;
		changedHeld.afterHeld = () -> { if (heldHandler.capacityReads >= heldBefore + 4) heldHandler.count = 3; };
		assertFalse(heldAccess.valid(), "final count follows held state/entity metadata callbacks too");
	}

	@Test void equalCapacityOpaqueRemappingIsNotClaimedAsDetectableAndEachNativeReadIsStillPaid() {
		var world = new World(); var handler = world.handler(); handler.capacities = new int[] {1000, 1000};
		var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var paid = new InventoryValidationBudget.Allowance(access.readCost());
		access.consume(0, entry -> true, paid);
		assertEquals(access.readCost().workUnits(), paid.attemptedWorkUnits());
		assertEquals(1, handler.reads); assertTrue(access.valid());
		// Public metadata has not changed. This is deliberately not a certificate of hidden provider
		// addresses or contents; same-capacity internal remaps remain external-trusted.
	}

	@Test void capacityWitnessValidationHasLinearMetadataCostWithoutAllocatingANewAddressTable() {
		for (int count : new int[] {1, 16, 4096}) {
			var world = new World(); var handler = world.handler(); handler.count = count;
			handler.capacities = new int[count]; java.util.Arrays.fill(handler.capacities, 1000);
			var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			handler.capacityReads = 0; world.callbacks = 0;
			var paid = new InventoryValidationBudget.Allowance(access.validationCost());
			assertTrue(access.valid(paid)); assertEquals(2 * count, handler.capacityReads); assertEquals(0, handler.reads);
			assertEquals(access.validationCost().workUnits(), paid.attemptedWorkUnits());
			System.out.println("NEOFORGE ordinary tanks=" + count + " requireCalls=" + world.callbacks + " capacityReads="
				+ handler.capacityReads + " validation=" + access.validationCost() + " read=" + access.readCost()
				+ " retainedCapacityBytes=" + (40L + 4L * count) + " retainedLayoutChars=" + access.layout().layoutData().length());
		}
	}

	@Test void ordinarySingleTankAdvancesWithDefaultAndLowHeadroomMetadataAdmission() {
		var world = new World(); world.handler().count = 1; world.handler().capacities = new int[] {1000};
		var access = PlatformFluidServiceImpl.resolve(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var settings = nx.pingwheel.common.config.InventorySettings.serverDefaults();
		var memory = new nx.pingwheel.common.presentation.source.RetainedMemoryLedger(settings.pendingMemoryBytes());
		var held = memory.tryReserve(settings.pendingMemoryBytes() - access.readCost().workspaceBytes()).orElseThrow();
		var scratch = memory.tryReserve(access.readCost().workspaceBytes()).orElseThrow();
		var paid = new InventoryValidationBudget.Allowance(access.readCost());
		assertTrue(access.consume(0, entry -> true, paid)); assertEquals(1, world.handler().reads);
		scratch.close(); held.close(); assertEquals(settings.pendingMemoryBytes(), memory.remaining());
		System.out.println("NEOFORGE ordinary one-tank modeled scratch=" + access.readCost().workspaceBytes()
			+ " defaultMemory=" + settings.pendingMemoryBytes() + " readWork=" + paid.attemptedWorkUnits());
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
			position -> { world.contextRequires++; return position.equals(BlockPos.ZERO); }, () -> true);
		var service = new PlatformFluidServiceImpl();
		var access = service.find(context, new InventoryValidationBudget.Allowance(service.resolutionCost())).orElseThrow();
		assertEquals(0, world.handler.reads); assertTrue(access.valid());
		int retainedChars = access.layout().layoutData().length();
		world.contextRequires = 0; assertTrue(access.valid());
		int validationRequires = world.contextRequires;
		world.contextRequires = 0;
		access.consume(0, entry -> { assertEquals(0, entry.amount()); return true; });
		assertEquals(1, world.handler.reads); assertTrue(world.capabilities > 0); assertTrue(world.cache.chunk.maps > 0);
		System.out.println("NEOFORGE ordinary two-tank consume Context.require=" + world.contextRequires
			+ " validContext.require=" + validationRequires + " validation=" + access.validationCost() + " read=" + access.readCost()
			+ " retainedLayoutChars=" + retainedChars);
		assertTrue(world.contextRequires < 256, "ordinary consume keeps fresh checks within a bounded callback envelope");
		world.handler.count = 1; world.handler.capacities = new int[] {1000};
		var single = service.find(context, new InventoryValidationBudget.Allowance(service.resolutionCost())).orElseThrow();
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
		@Override public void require(BlockPos position) {
			callbacks++;
			if (!allowed || !position.equals(BlockPos.ZERO)) throw new InventorySourceUnavailableException("denied member/owner");
			if (handler != null && handler.reads > 0) afterRead.run();
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position) { require(position); afterBlock.run(); return block; }
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face) { require(position); faces.add(face); return handler; }
		@Override public String dimension() { return "minecraft:overworld"; }
		@Override public boolean createAvailable() { return false; }
		@Override public boolean held(FluidCapabilityAccess.Member member) { require(member.position()); afterHeld.run(); return member.block().same(block); }
	}

	@NotNullByDefault
	private static final class Handler implements IFluidHandler {
		private int count = 2, reads, capacityReads;
		private int[] capacities = {1000, 2000};
		private java.util.function.IntConsumer onCapacity = index -> {};
		private FluidStack stack = FluidStack.EMPTY;
		private @Nullable RuntimeException failure;
		private boolean nullStack;
		@Override public int getTanks() { return count; }
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
