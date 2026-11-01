package nx.pingwheel.neoforge.integration.create.presentation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.neoforge.platform.FluidCapabilityAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Create-only static structure/mode gate. Contents still use the ordinary NeoForge fluid capability. */
@NotNullByDefault
public final class CreateFluidTankAccess {
	private static final String TANK = "com.simibubi.create.content.fluids.tank.FluidTankBlockEntity";
	private static final String CREATIVE = "com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity";
	private static final String TANK_BLOCK = "com.simibubi.create.content.fluids.tank.FluidTankBlock";
	private static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
	private static final String BOILER = "com.simibubi.create.content.fluids.tank.BoilerData";
	private static final String ID = "create:fluid_tank";
	private CreateFluidTankAccess() {}

	/** Indexed lookup only: repeated safety checks must not enumerate every installed mod. */
	public static boolean available() {
		try {
			@Nullable net.neoforged.fml.ModList mods = net.neoforged.fml.ModList.get();
			if (mods == null) return false;
			var container = mods.getModContainerById("create");
			return container.isPresent() && CreatePresentationAvailability.testedVersion(container.get().getModInfo().getVersion().toString());
		} catch (RuntimeException | LinkageError unavailable) { return false; }
	}

	/** A claimed Create tank never recovers to the unrestricted generic route, even when unavailable. */
	public static boolean recognizes(FluidCapabilityAccess.Block block) {
		if (ID.equals(block.blockId()) || "create:creative_fluid_tank".equals(block.blockId())
			|| ID.equals(block.entityId()) || "create:creative_fluid_tank".equals(block.entityId())) return true;
		@Nullable Class<?> type = block.entity() == null ? null : block.entity().getClass();
		for (int depth = 0; type != null && depth < 32; depth++, type = type.getSuperclass())
			if (TANK.equals(type.getName()) || CREATIVE.equals(type.getName())) return true;
		return false;
	}

	public static FluidCapabilityAccess.Route resolve(FluidCapabilityAccess.World world, BlockPos root, Direction face,
		FluidCapabilityAccess.Block block) {
		if (!world.createAvailable()) throw unavailable("unsupported Create fluid route");
		return resolve(world, root, face, block, ApiHolder.API);
	}

	/** Fixed metadata seam: no handler, native tank, controller-BE lookup or content method is exposed. */
	@NotNullByDefault
	interface Metadata {
		boolean ordinary(FluidCapabilityAccess.Block block);
		boolean virtual(Object entity);
		@Nullable BlockPos lastPosition(Object entity);
		boolean activeBoiler(Object entity);
		@Nullable BlockPos controller(Object entity);
		boolean isController(Object entity);
		int width(Object entity);
		int height(Object entity);
		Direction.@Nullable Axis axis(Object entity);
		default boolean storedHandler(Object controller, IFluidHandler handler) { return true; }
	}

	static FluidCapabilityAccess.Route resolve(FluidCapabilityAccess.World world, BlockPos root, Direction face,
		FluidCapabilityAccess.Block block, Metadata metadata) {
		Objects.requireNonNull(face); world.require(root);
		if (!world.createAvailable()) throw unavailable("unsupported Create fluid route");
		var original = new FluidCapabilityAccess.Member(root, block);
		requireStatic(world, metadata, original);
		@Nullable BlockPos controller = controller(world, metadata, original);
		if (controller == null) throw unavailable("Create fluid controller missing");
		world.require(controller);
		var master = new FluidCapabilityAccess.Member(controller, world.block(controller));
		requireStatic(world, metadata, master);
		world.require(controller);
		if (!metadata.isController(entity(master)) || !controller.equals(controller(world, metadata, master)))
			throw unavailable("Create fluid controller relation");
		world.require(controller); int width = metadata.width(entity(master));
		world.require(controller); int height = metadata.height(entity(master));
		world.require(controller); Direction.@Nullable Axis axis = metadata.axis(entity(master));
		// Preflight the WHOLE member table before allocating positions or probing any prism member.
		// The common layout guard is finite even when Create's configurable max height is very large.
		if (width < 1 || width > 3 || height < 1 || axis != Direction.Axis.Y)
			throw unavailable("Create fluid bounded static topology");
		long size = (long) width * width * height;
		if (size > InventorySnapshotLayout.MAX_MEMBERS || !contains(controller, root, width, height))
			throw unavailable("Create fluid bounded static topology");
		List<FluidCapabilityAccess.Member> members = new ArrayList<>((int) size);
		for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
			BlockPos position = controller.offset(x, y, z);
			world.require(position);
			var member = new FluidCapabilityAccess.Member(position, world.block(position));
			requireStatic(world, metadata, member);
			if (!controller.equals(controller(world, metadata, member))) throw unavailable("Create fluid member controller");
			world.require(position);
			if (metadata.isController(entity(member)) != position.equals(controller)) throw unavailable("Create fluid member role");
			world.require(position);
			if (metadata.axis(entity(member)) != Direction.Axis.Y) throw unavailable("Create fluid member axis");
			members.add(member);
		}
		// Query the controller, not the member: the tested registration may initialize a member cache
		// through getControllerBE (which has no loaded gate). On the verified controller isController is
		// true, so refreshCapability stays local. The exact original non-null face is unchanged. Every
		// member still has its own current scope/authority gate; the physical hit/root is not rewritten.
		String alias = world.dimension() + "|fluid-tank|" + controller.toShortString() + "|" + ID;
		return new TankRoute(world, face, controller, List.copyOf(members), metadata, width, height, alias);
	}

	private static boolean contains(BlockPos controller, BlockPos root, int width, int height) {
		long x = (long) root.getX() - controller.getX(), y = (long) root.getY() - controller.getY(), z = (long) root.getZ() - controller.getZ();
		return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < width;
	}
	private static Object entity(FluidCapabilityAccess.Member member) {
		return Objects.requireNonNull(member.block().entity(), "Create fluid entity");
	}
	private static @Nullable BlockPos controller(FluidCapabilityAccess.World world, Metadata metadata, FluidCapabilityAccess.Member member) {
		world.require(member.position()); return metadata.controller(entity(member));
	}
	private static void requireStatic(FluidCapabilityAccess.World world, Metadata metadata, FluidCapabilityAccess.Member member) {
		world.require(member.position());
		if (!ID.equals(member.block().blockId()) || !ID.equals(member.block().entityId()) || !metadata.ordinary(member.block()))
			throw unavailable("Create creative/foreign fluid tank");
		world.require(member.position());
		if (metadata.virtual(entity(member))) throw unavailable("Create virtual fluid tank");
		world.require(member.position());
		@Nullable BlockPos last = metadata.lastPosition(entity(member));
		if (last != null && !last.equals(member.position())) throw unavailable("Create moved fluid tank");
		world.require(member.position());
		if (metadata.activeBoiler(entity(member))) throw unavailable("Create active boiler is not stored fluid");
	}
	private static InventorySourceUnavailableException unavailable(String message) { return new InventorySourceUnavailableException(message); }
	private static InventorySourceUnavailableException unavailable(String message, Throwable cause) {
		var failure = unavailable(message); failure.initCause(cause); return failure;
	}

	@NotNullByDefault
	private record TankRoute(FluidCapabilityAccess.World world, Direction face, BlockPos capabilityPosition,
		@Unmodifiable List<FluidCapabilityAccess.Member> members, Metadata metadata, int width, int height,
		String physicalAlias) implements FluidCapabilityAccess.Route {
		@Override public Optional<String> alias() { return Optional.of(physicalAlias); }
		@Override public boolean accepts(IFluidHandler handler) {
			world.require(capabilityPosition);
			return metadata.storedHandler(entity(members.getFirst()), handler);
		}
		@Override public String description() { return "create:static-fluid;axis=Y;width=" + width + ";height=" + height + ";face=" + face.getName(); }
		@Override public InventoryValidationBudget.Cost checkCost() {
			// Per member: one world identity witness, nine fixed zero-arg Method/Field calls, bounded
			// native strings and wrapper objects. All member visits and failed attempts stay paid.
			long allocation = 2048L + 2048L * members.size();
			return new InventoryValidationBudget.Cost(allocation, allocation, 128L + 256L * members.size());
		}
		@Override public boolean matches() {
			if (!world.createAvailable()) return false;
			for (FluidCapabilityAccess.Member member : members) {
				world.require(member.position());
				if (!member.block().same(world.block(member.position()))) return false;
				requireStatic(world, metadata, member);
				if (!capabilityPosition.equals(controller(world, metadata, member))) return false;
				world.require(member.position());
				if (metadata.isController(entity(member)) != member.position().equals(capabilityPosition)) return false;
				world.require(member.position());
				if (metadata.axis(entity(member)) != Direction.Axis.Y) return false;
				if (member.position().equals(capabilityPosition)) {
					world.require(member.position()); if (metadata.width(entity(member)) != width) return false;
					world.require(member.position()); if (metadata.height(entity(member)) != height) return false;
				}
			}
			return true;
		}
	}

	/** Only reached after mod/version admission; uses this defining loader, never the context loader. */
	@NotNullByDefault
	private static final class ApiHolder {
		private static final Metadata API = load();
		private static Metadata load() {
			try { return new Api(CreateFluidTankAccess.class.getClassLoader()); }
			catch (ReflectiveOperationException | LinkageError failure) { throw unavailable("Create fluid metadata unavailable", failure); }
		}
	}

	@NotNullByDefault
	private static final class Api implements Metadata {
		private final Class<?> tank, tankBlock, boilerClass, storedTank;
		private final Method virtual, last, active, controller, isController, width, height, axis, tankIdentity;
		private final Field boiler;
		private Api(ClassLoader loader) throws ReflectiveOperationException {
			tank = Class.forName(TANK, false, loader); tankBlock = Class.forName(TANK_BLOCK, false, loader);
			Class<?> smart = Class.forName(SMART, false, loader); boilerClass = Class.forName(BOILER, false, loader);
			storedTank = Class.forName("com.simibubi.create.foundation.fluid.SmartFluidTank", false, loader);
			if (!BlockEntity.class.isAssignableFrom(tank) || !smart.isAssignableFrom(tank)
				|| !Modifier.isPublic(tank.getModifiers()) || !Modifier.isPublic(tankBlock.getModifiers())
				|| !Modifier.isPublic(smart.getModifiers()) || !Modifier.isPublic(boilerClass.getModifiers())
				|| !IFluidHandler.class.isAssignableFrom(storedTank) || !Modifier.isPublic(storedTank.getModifiers()))
				throw new NoSuchMethodException("Create fluid hierarchy");
			virtual = method(smart, "isVirtual", boolean.class); last = method(tank, "getLastKnownPos", BlockPos.class);
			active = method(boilerClass, "isActive", boolean.class); controller = method(tank, "getController", BlockPos.class);
			isController = method(tank, "isController", boolean.class); width = method(tank, "getWidth", int.class);
			height = method(tank, "getHeight", int.class); axis = method(tank, "getMainConnectionAxis", Direction.Axis.class);
			tankIdentity = method(tank, "getTankInventory", net.neoforged.neoforge.fluids.capability.templates.FluidTank.class);
			boiler = tank.getDeclaredField("boiler");
			if (boiler.getDeclaringClass() != tank || boiler.getType() != boilerClass || boiler.getModifiers() != Modifier.PUBLIC)
				throw new NoSuchFieldException("Create fluid boiler metadata shape");
		}
		private static Method method(Class<?> owner, String name, Class<?> returns) throws ReflectiveOperationException {
			Method method = owner.getDeclaredMethod(name);
			if (method.getDeclaringClass() != owner || method.getReturnType() != returns || method.getModifiers() != Modifier.PUBLIC)
				throw new NoSuchMethodException("Create fluid metadata shape: " + name);
			return method;
		}
		private static @Nullable Object invoke(Method method, Object owner) {
			try { return method.invoke(owner); }
			catch (InvocationTargetException failure) {
				@Nullable Throwable cause = failure.getCause();
				if (cause instanceof RuntimeException runtime) throw runtime;
				if (cause instanceof Error error) throw error;
				throw unavailable("Create fluid metadata callback failed", failure);
			} catch (ReflectiveOperationException failure) { throw unavailable("Create fluid metadata inaccessible", failure); }
		}
		@Override public boolean ordinary(FluidCapabilityAccess.Block block) {
			return block.entity() != null && block.entity().getClass() == tank && block.block().getClass() == tankBlock;
		}
		@Override public boolean virtual(Object entity) { return (Boolean) Objects.requireNonNull(invoke(virtual, entity)); }
		@Override public @Nullable BlockPos lastPosition(Object entity) { return (BlockPos) invoke(last, entity); }
		@Override public boolean activeBoiler(Object entity) {
			try {
				@Nullable Object value = boiler.get(entity);
				if (value == null || value.getClass() != boilerClass) throw unavailable("Create fluid boiler ownership changed");
				return (Boolean) Objects.requireNonNull(invoke(active, value));
			} catch (IllegalAccessException failure) { throw unavailable("Create fluid boiler inaccessible", failure); }
		}
		@Override public @Nullable BlockPos controller(Object entity) { return (BlockPos) invoke(controller, entity); }
		@Override public boolean isController(Object entity) { return (Boolean) Objects.requireNonNull(invoke(isController, entity)); }
		@Override public int width(Object entity) { return (Integer) Objects.requireNonNull(invoke(width, entity)); }
		@Override public int height(Object entity) { return (Integer) Objects.requireNonNull(invoke(height, entity)); }
		@Override public Direction.@Nullable Axis axis(Object entity) { return (Direction.Axis) invoke(axis, entity); }
		@Override public boolean storedHandler(Object controller, IFluidHandler handler) {
			// An inactive mode can temporarily retain the old boiler capability until Create refreshes.
			// A newly promoted controller can likewise retain its old controller's SmartFluidTank. The
			// public getter is an identity-only route witness: its contents are NEVER queried. Fluid still
			// comes exclusively through IFluidHandler, not an internal native tank read or refresh call.
			return handler.getClass() == storedTank && invoke(tankIdentity, controller) == handler;
		}
	}
}
