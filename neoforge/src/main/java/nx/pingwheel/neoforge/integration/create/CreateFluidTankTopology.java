package nx.pingwheel.neoforge.integration.create;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Static tank metadata only; boiler mode and inventory read authority are independent. */
@NotNullByDefault
public final class CreateFluidTankTopology {

	public static final String TANK_ID = "create:fluid_tank";
	private static final String TANK = "com.simibubi.create.content.fluids.tank.FluidTankBlockEntity";
	private static final String BLOCK = "com.simibubi.create.content.fluids.tank.FluidTankBlock";
	private static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
	private CreateFluidTankTopology() {}

	@NotNullByDefault
	public record Cell(BlockPos position, BlockState state) {
		public Cell { position = position.immutable(); }
	}

	/** Detached, canonical X/Y/Z order; no live world, entity, capability or contents. */
	@NotNullByDefault
	public record Topology(BlockPos controller, int width, int height, @Unmodifiable List<Cell> cells) {
		public Topology { controller = controller.immutable(); cells = List.copyOf(cells); }
		public TargetKey.CreateTankKey key(String dimension) {
			return new TargetKey.CreateTankKey(dimension, controller.getX(), controller.getY(), controller.getZ(), TANK_ID);
		}
	}

	@NotNullByDefault
	interface World {
		@Nullable Reading loaded(BlockPos position) throws ReflectiveOperationException;
		int minBuildHeight();
		int buildHeight();
	}

	/** Operation-local object identity never escapes into the published topology. */
	@NotNullByDefault
	record Reading(BlockState state, Object identity, BlockPos controller, boolean isController,
		int width, int height, Axis axis) {
		boolean same(Reading other) {
			return identity == other.identity && state == other.state && controller.equals(other.controller)
				&& isController == other.isController && width == other.width && height == other.height && axis == other.axis;
		}
	}

	@FunctionalInterface
	@NotNullByDefault
	interface Chunks { @Nullable LevelChunk loaded(int x, int z); }

	public static TargetKey group(ServerLevel level, Target.BlockTarget target) {
		TargetKey exact = TargetKey.from(target);
		if (!TANK_ID.equals(target.blockRegistryId())
			|| !level.dimension().location().toString().equals(target.dimensionId())) return exact;
		return resolve(level, (x, z) -> level.getChunkSource().getChunkNow(x, z),
			new BlockPos(target.x(), target.y(), target.z())).<TargetKey>map(t -> t.key(target.dimensionId())).orElse(exact);
	}

	static Optional<Topology> resolve(Level level, Chunks chunks, BlockPos original) {
		// Fixed optional classes are discovered only after the existing tested-version gate.
		if (!CreatePresentationAvailability.available()) return Optional.empty();
		try {
			return read(new MinecraftWorld(level, chunks, ApiHolder.API), original);
		} catch (RuntimeException | LinkageError unavailable) {
			failure(original, unavailable); return Optional.empty();
		}
	}

	static Optional<Topology> read(World world, BlockPos original) {
		try {
			@Nullable Reading source = world.loaded(original);
			if (source == null) return Optional.empty();
			BlockPos controller = source.controller().immutable();
			@Nullable Reading root = world.loaded(controller);
			if (root == null || !root.isController() || !controller.equals(root.controller())) return Optional.empty();
			int width = root.width(), height = root.height(), buildHeight = world.buildHeight();
			// Create's width is fixed; height is configurable. The current dimension bounds
			// the physical prism without depending on inventory limits or Create config reads.
			if (width < 1 || width > 3 || height < 1 || root.axis() != Axis.Y
				|| buildHeight < 1 || buildHeight > DimensionType.Y_SIZE || height > buildHeight) return Optional.empty();
			int maxX = Math.addExact(controller.getX(), width - 1);
			int maxY = Math.addExact(controller.getY(), height - 1);
			int maxZ = Math.addExact(controller.getZ(), width - 1);
			long minY = world.minBuildHeight(), top = minY + buildHeight;
			if (controller.getY() < minY || maxY >= top
				|| original.getX() < controller.getX() || original.getX() > maxX
				|| original.getY() < controller.getY() || original.getY() > maxY
				|| original.getZ() < controller.getZ() || original.getZ() > maxZ) return Optional.empty();
			int count = Math.multiplyExact(Math.multiplyExact(width, width), height);
			var cells = new ArrayList<Cell>(count);
			var evidence = new ArrayList<Reading>(count);
			for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
				BlockPos position = controller.offset(x, y, z);
				@Nullable Reading member = world.loaded(position);
				if (member == null || !controller.equals(member.controller()) || member.axis() != Axis.Y
					|| member.isController() != position.equals(controller)
					|| position.equals(controller) && !root.same(member)
					|| position.equals(original) && !source.same(member)) return Optional.empty();
				cells.add(new Cell(position, member.state())); evidence.add(member);
			}
			// A removed/replaced member during metadata callbacks invalidates the whole batch.
			for (int i = 0; i < cells.size(); i++) {
				@Nullable Reading current = world.loaded(cells.get(i).position());
				if (current == null || !evidence.get(i).same(current)) return Optional.empty();
			}
			return Optional.of(new Topology(controller, width, height, cells));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			failure(original, unavailable); return Optional.empty();
		}
	}

	private static void failure(BlockPos original, Throwable failure) {
		if (failure instanceof java.lang.reflect.InvocationTargetException invocation) {
			@Nullable Throwable cause = invocation.getCause();
			if (cause instanceof Error fatal && !(fatal instanceof LinkageError) && !(fatal instanceof AssertionError)) throw fatal;
		}
		Diagnostics.warn("create/tank-topology", TANK_ID, () -> "position=" + original + "; using physical target", failure);
	}

	@NotNullByDefault
	static final class MinecraftWorld implements World {
		private final Level level;
		private final Chunks chunks;
		private final Api api;
		MinecraftWorld(Level level, Chunks chunks, Api api) { this.level = level; this.chunks = chunks; this.api = api; }
		@Override public int minBuildHeight() { return level.getMinBuildHeight(); }
		@Override public int buildHeight() { return level.getHeight(); }
		@Override public @Nullable Reading loaded(BlockPos position) throws ReflectiveOperationException {
			@Nullable LevelChunk chunk = chunks.loaded(position.getX() >> 4, position.getZ() >> 4);
			if (chunk == null) return null;
			BlockState state = chunk.getBlockState(position);
			if (state.getBlock().getClass() != api.block || !TANK_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) return null;
			// CHECK can promote pending NBT as well. Only existing map entries are allowed.
			@Nullable BlockEntity entity = chunk.getBlockEntities().get(position);
			if (entity == null || entity.getClass() != api.tank || entity.isRemoved() || entity.getLevel() != level
				|| !entity.getBlockPos().equals(position) || entity.getBlockState() != state
				|| !TANK_ID.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString())) return null;
			if ((Boolean) api.virtual.invoke(entity)) return null;
			@Nullable BlockPos last = (BlockPos) api.last.invoke(entity);
			if (last != null && !last.equals(position)) return null;
			@Nullable BlockPos controller = (BlockPos) api.controller.invoke(entity);
			boolean root = (Boolean) api.isController.invoke(entity);
			@Nullable Axis axis = (Axis) api.axis.invoke(entity);
			if (controller == null || axis != Axis.Y) return null;
			int width = root ? (Integer) api.width.invoke(entity) : 0;
			int height = root ? (Integer) api.height.invoke(entity) : 0;
			if (entity.isRemoved() || entity.getLevel() != level || !entity.getBlockPos().equals(position)
				|| entity.getBlockState() != state || chunk.getBlockEntities().get(position) != entity
				|| chunk.getBlockState(position) != state) return null;
			return new Reading(state, entity, controller.immutable(), root, width, height, axis);
		}
	}

	/** Exact tested declaration owners, including getController's virtual isController call. */
	@NotNullByDefault
	static final class Api {
		final Class<?> tank, block;
		final Method controller, isController, width, height, axis, last, virtual;
		Api(Class<?> tank, Class<?> block, Class<?> smart) throws ReflectiveOperationException {
			this.tank = tank; this.block = block;
			controller = method(tank, "getController", BlockPos.class);
			isController = method(tank, "isController", boolean.class);
			width = method(tank, "getWidth", int.class); height = method(tank, "getHeight", int.class);
			axis = method(tank, "getMainConnectionAxis", Axis.class); last = method(tank, "getLastKnownPos", BlockPos.class);
			virtual = method(smart, "isVirtual", boolean.class);
			if (tank.getMethod("isVirtual").getDeclaringClass() != smart) throw new NoSuchMethodException("Tank virtual ownership drift");
		}
		private static Method method(Class<?> owner, String name, Class<?> returns) throws ReflectiveOperationException {
			Method method = owner.getDeclaredMethod(name);
			if (method.getReturnType() != returns || method.getModifiers() != Modifier.PUBLIC)
				throw new NoSuchMethodException("Tank metadata shape: " + name);
			return method;
		}
	}

	@NotNullByDefault
	private static final class ApiHolder {
		static final Api API = load();
		private static Api load() {
			try {
				ClassLoader loader = CreateFluidTankTopology.class.getClassLoader();
				return new Api(Class.forName(TANK, false, loader), Class.forName(BLOCK, false, loader), Class.forName(SMART, false, loader));
			} catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
		}
	}
}
