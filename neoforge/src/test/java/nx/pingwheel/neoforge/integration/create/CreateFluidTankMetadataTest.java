package nx.pingwheel.neoforge.integration.create;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.neoforge.platform.FluidCapabilityAccess;
import nx.pingwheel.neoforge.platform.PlatformFluidServiceTest.PaidAccess;
import static nx.pingwheel.neoforge.platform.PlatformFluidServiceTest.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real fixed reflective metadata port against archive getter bodies, with inert ancestors. */
@NotNullByDefault
class CreateFluidTankMetadataTest {
	private static final String ACCESS = "nx.pingwheel.neoforge.integration.create.presentation.CreateFluidTankAccess";
	private static final String TANK = "com.simibubi.create.content.fluids.tank.FluidTankBlockEntity";
	private static final String BLOCK = "com.simibubi.create.content.fluids.tank.FluidTankBlock";
	private static final String BOILER = "com.simibubi.create.content.fluids.tank.BoilerData";
	private static final String STORED = "com.simibubi.create.foundation.fluid.SmartFluidTank";
	private static final @Unmodifiable List<String> METHODS = List.of("getController", "isController", "getWidth", "getHeight", "getMainConnectionAxis", "getLastKnownPos", "getTankInventory");
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void archiveGettersExecuteThroughTheActualReflectionPortAndRejectStaleControllerCaches() throws Exception {
		var loader = new Loader(""); var world = new World(loader);
		var access = access(loader, world);
		assertEquals(2, access.layout().members().size()); assertEquals(1, access.slots()); assertTrue(access.valid());
		var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		bean.setThreadAllocatedMemoryEnabled(true);
		for (int warm = 0; warm < 8; warm++) assertTrue(access.consume(0, entry -> true));
		world.requireCalls = 0; long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
		assertTrue(access.consume(0, entry -> true));
		long allocation = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
		assertTrue(allocation <= access.readCost().allocationBytes(), "actual warmed fixed-reflection allocation fits its prepaid bound");
		System.out.println("NEOFORGE archive Create members=2 actualAllocation=" + allocation + " read=" + access.readCost()
			+ " requireCalls=" + world.requireCalls);
		var stale = (IFluidHandler) loader.loadClass(STORED).getConstructor(int.class, java.util.function.Consumer.class).newInstance(1000, (java.util.function.Consumer<Object>) ignored -> {});
		world.handler = stale;
		assertFalse(access.valid());
		// Fresh find must also refuse a same-class capability retained from an old controller.
		assertThrows(InventorySourceUnavailableException.class, () -> access(loader, world));
		world.handler = world.ownTank; field(world.controller.getClass(), "height").setInt(world.controller, 3);
		assertFalse(access.valid(), "controller extent is read currently, not retained as a permission result");
	}

	@Test void exactArchiveMethodOwnershipFailsClosedOnDriftAndNoContextLoaderCanSupplyAFallback() throws Exception {
		@Nullable ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(new ClassLoader(null) {
			@Override public Class<?> loadClass(String name) { throw new AssertionError("fixed metadata must not use TCCL"); }
		});
		try {
			for (String method : METHODS) {
				var loader = new Loader(method); var world = new World(loader);
				var failure = assertThrows(InvocationTargetException.class, () -> access(loader, world), method);
				assertInstanceOf(ExceptionInInitializerError.class, failure.getCause());
			}
			var good = new Loader(""); assertNotNull(access(good, new World(good)));
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}

	@ParameterizedTest @ValueSource(ints = {0, 1, 2})
	void formerControllerRetainsRealArchiveActiveBoilerButInactiveMasterOwnsTheOnlyReadableTank(int hitY) throws Exception {
		var loader = new Loader(""); var world = new World(loader, 3);
		Object former = java.util.Objects.requireNonNull(world.blocks.get(BlockPos.ZERO.above()).entity());
		Object staleBoiler = java.util.Objects.requireNonNull(field(former.getClass(), "boiler").get(former));
		field(staleBoiler.getClass(), "attachedEngines").setInt(staleBoiler, 1);
		assertEquals(true, staleBoiler.getClass().getMethod("isActive").invoke(staleBoiler));
		// This is the actual archive early-return body, not a fixture boolean. A normal member
		// update does not evaluate or clear the boiler that belonged to its previous controller role.
		former.getClass().getMethod("updateBoilerState").invoke(former);
		assertEquals(1, field(staleBoiler.getClass(), "attachedEngines").getInt(staleBoiler));
		((net.neoforged.neoforge.fluids.capability.templates.FluidTank) world.ownTank).setFluid(new FluidStack(Fluids.WATER, 1000));
		try (var access = access(loader, world, BlockPos.ZERO.above(hitY))) {
			assertTrue(access.valid()); assertEquals(3, access.layout().members().size());
			assertTrue(access.consume(0, entry -> { assertEquals(81_000, entry.amount()); return true; }));
		}
		assertEquals(1, field(staleBoiler.getClass(), "attachedEngines").getInt(staleBoiler), "access is read-only even for ignored local state");
		assertEquals(true, staleBoiler.getClass().getMethod("isActive").invoke(staleBoiler));
	}

	@Test void actualActiveControllerRejectsAllHitsEvenWhenCapabilityStillNamesItsOrdinarySmartTank() throws Exception {
		var loader = new Loader(""); var world = new World(loader, 3);
		for (BlockPos position : List.of(BlockPos.ZERO, BlockPos.ZERO.above())) {
			Object entity = java.util.Objects.requireNonNull(world.blocks.get(position).entity());
			Object boiler = java.util.Objects.requireNonNull(field(entity.getClass(), "boiler").get(entity));
			field(boiler.getClass(), "attachedEngines").setInt(boiler, 1);
			assertEquals(true, boiler.getClass().getMethod("isActive").invoke(boiler));
		}
		for (BlockPos hit : List.of(BlockPos.ZERO, BlockPos.ZERO.above(), BlockPos.ZERO.above(2))) {
			var failure = assertThrows(InvocationTargetException.class, () -> access(loader, world, hit));
			assertInstanceOf(InventorySourceUnavailableException.class, failure.getCause());
		}
		assertEquals(0, world.capabilityCalls, "mode rejection must not depend on a returned SmartFluidTank type");
	}

	@Test void staleMemberBoilerDoesNotAuthorizeForeignHandlerOrMismatchedMemberController() throws Exception {
		for (boolean foreignHandler : new boolean[] {true, false}) {
			var loader = new Loader(""); var world = new World(loader, 3);
			Object former = java.util.Objects.requireNonNull(world.blocks.get(BlockPos.ZERO.above()).entity());
			Object boiler = java.util.Objects.requireNonNull(field(former.getClass(), "boiler").get(former));
			field(boiler.getClass(), "attachedEngines").setInt(boiler, 1);
			if (foreignHandler) world.handler = (IFluidHandler) loader.loadClass(STORED).getConstructor(int.class, java.util.function.Consumer.class)
				.newInstance(1000, (java.util.function.Consumer<Object>) ignored -> {});
			else field(former.getClass(), "controller").set(former, BlockPos.ZERO.above(2));
			assertThrows(InventorySourceUnavailableException.class, () -> access(loader, world, BlockPos.ZERO.above(2)));
			assertEquals(foreignHandler ? 1 : 0, world.capabilityCalls);
		}
	}

	@Test void realControllerModeOrRelationChangesRetireStaleMemberRoutesBeforeReadingOrAcceptingBorrowedContents() throws Exception {
		for (String change : List.of("before-read-mode", "during-read-mode", "during-read-controller")) {
			var loader = new Loader(""); var world = new World(loader, 3);
			Object former = java.util.Objects.requireNonNull(world.blocks.get(BlockPos.ZERO.above()).entity());
			Object oldBoiler = java.util.Objects.requireNonNull(field(former.getClass(), "boiler").get(former));
			field(oldBoiler.getClass(), "attachedEngines").setInt(oldBoiler, 1);
			Object currentBoiler = java.util.Objects.requireNonNull(field(world.controller.getClass(), "boiler").get(world.controller));
			try (var access = access(loader, world, BlockPos.ZERO.above())) {
				if (change.equals("before-read-mode")) {
					field(currentBoiler.getClass(), "attachedWhistles").setInt(currentBoiler, 1);
					assertFalse(access.valid());
					assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> fail("active controller cannot read")));
				} else {
					assertThrows(InventorySourceUnavailableException.class, () -> access.consume(0, entry -> {
						try {
							if (change.equals("during-read-mode")) field(currentBoiler.getClass(), "attachedEngines").setInt(currentBoiler, 1);
							else field(world.controller.getClass(), "controller").set(world.controller, BlockPos.ZERO.above());
						} catch (Exception failure) { throw new AssertionError(failure); }
						return true;
					}));
				}
			}
			assertEquals(1, field(oldBoiler.getClass(), "attachedEngines").getInt(oldBoiler));
		}
	}

	private static PaidAccess access(Loader loader, World world) throws Exception {
		return access(loader, world, BlockPos.ZERO.above());
	}
	private static PaidAccess access(Loader loader, World world, BlockPos hit) throws Exception {
		try (var a = admission(); var op = a.enter(FluidCapabilityAccess.PROBE_PROFILE)) {
			op.allowance().debit(FluidCapabilityAccess.PROBE_PROFILE.ownedCost());
			try (var probe = (IPlatformFluidService.Probe) loader.loadClass(ACCESS).getMethod("probe", FluidCapabilityAccess.World.class,
				BlockPos.class, Direction.class, FluidCapabilityAccess.Block.class, Operation.class)
				.invoke(null, world, hit, Direction.WEST, java.util.Objects.requireNonNull(world.blocks.get(hit)), op)) {
				return new PaidAccess(probe.finish(a).orElseThrow());
			}
		}
	}

	@NotNullByDefault
	private static final class World implements FluidCapabilityAccess.World {
		private final Map<BlockPos, FluidCapabilityAccess.Block> blocks = new HashMap<>();
		private final Object controller;
		private final IFluidHandler ownTank;
		private IFluidHandler handler;
		private int requireCalls, capabilityCalls;
		private final int height;
		private World(Loader loader) throws Exception { this(loader, 2); }
		private World(Loader loader, int height) throws Exception {
			this.height = height;
			Object block = allocate(loader.loadClass(BLOCK));
			ownTank = (IFluidHandler) loader.loadClass(STORED).getConstructor(int.class, java.util.function.Consumer.class).newInstance(1000, (java.util.function.Consumer<Object>) ignored -> {});
			handler = ownTank;
			controller = entity(loader, BlockPos.ZERO, null);
			blocks.put(BlockPos.ZERO, new FluidCapabilityAccess.Block(block, controller, "create:fluid_tank", "create:fluid_tank"));
			for (int y = 1; y < height; y++) blocks.put(BlockPos.ZERO.above(y),
				new FluidCapabilityAccess.Block(block, entity(loader, BlockPos.ZERO.above(y), BlockPos.ZERO), "create:fluid_tank", "create:fluid_tank"));
		}
		private Object entity(Loader loader, BlockPos position, @Nullable BlockPos parent) throws Exception {
			Object entity = allocate(loader.loadClass(TANK));
			field(net.minecraft.world.level.block.entity.BlockEntity.class, "worldPosition").set(entity, position);
			field(entity.getClass(), "controller").set(entity, parent);
			field(entity.getClass(), "width").setInt(entity, 1); field(entity.getClass(), "height").setInt(entity, height);
			field(entity.getClass(), "boiler").set(entity, allocate(loader.loadClass(BOILER)));
			field(entity.getClass(), "tankInventory").set(entity, ownTank); return entity;
		}
		@Override public void require(BlockPos position, Operation operation) { gate(operation); requireCalls++; if (!blocks.containsKey(position)) throw new InventorySourceUnavailableException("unknown member"); }
		@Override public FluidCapabilityAccess.Block block(BlockPos position, Operation operation) { require(position, operation); return java.util.Objects.requireNonNull(blocks.get(position)); }
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face, Operation operation) { require(position, operation); capabilityCalls++; assertEquals(BlockPos.ZERO, position); assertEquals(Direction.WEST, face); return handler; }
		@Override public String dimension(Operation operation) { return "minecraft:overworld"; }
		@Override public boolean createAvailable() { return true; }
	}

	@NotNullByDefault
	private static final class Loader extends ClassLoader {
		private final String drift;
		private Loader(String drift) { super(CreateFluidTankMetadataTest.class.getClassLoader()); this.drift = drift; }
		@Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			boolean optional = List.of(TANK, BLOCK, BOILER, SMART, STORED).contains(name);
			if (!optional && !name.equals(ACCESS) && !name.startsWith(ACCESS + "$")) return super.loadClass(name, resolve);
			@Nullable Class<?> loaded = findLoadedClass(name);
			if (loaded == null) {
				try {
					byte[] bytes;
					if (optional) bytes = optional(name);
					else try (@Nullable InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
						if (input == null) throw new ClassNotFoundException(name); bytes = input.readAllBytes();
					}
					loaded = defineClass(name, bytes, 0, bytes.length);
				} catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
			}
			if (resolve) resolveClass(loaded); return loaded;
		}
		private byte[] optional(String name) throws Exception {
			var original = archive(name);
			if (name.equals(STORED)) { var writer = new ClassWriter(0); original.accept(writer); return writer.toByteArray(); }
			var copy = new ClassNode(); copy.version = original.version; copy.access = original.access; copy.name = original.name;
			copy.superName = name.equals(TANK) ? SMART.replace('.', '/') : name.equals(SMART)
				? "net/minecraft/world/level/block/entity/BlockEntity" : name.equals(BLOCK) ? "net/minecraft/world/level/block/Block" : "java/lang/Object";
			if (name.equals(TANK)) {
				for (var field : original.fields) if (List.of("controller", "lastKnownPos", "width", "height", "boiler", "tankInventory").contains(field.name)) copy.fields.add(field);
				for (var method : original.methods) if ((METHODS.contains(method.name) || method.name.equals("updateBoilerState")) && !method.name.equals(drift)) copy.methods.add(method);
			} else if (name.equals(SMART)) {
				copy.fields.add(original.fields.stream().filter(field -> field.name.equals("virtualMode")).findFirst().orElseThrow());
				copy.methods.add(original.methods.stream().filter(method -> method.name.equals("isVirtual")).findFirst().orElseThrow());
			} else if (name.equals(BOILER)) {
				for (var field : original.fields) if (List.of("attachedEngines", "attachedWhistles").contains(field.name)) copy.fields.add(field);
				copy.methods.add(original.methods.stream().filter(method -> method.name.equals("isActive")).findFirst().orElseThrow());
			}
			var writer = new ClassWriter(0); copy.accept(writer); return writer.toByteArray();
		}
	}
}
