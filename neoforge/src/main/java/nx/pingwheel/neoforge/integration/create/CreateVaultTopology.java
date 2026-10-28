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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Metadata only: independent of inventory discovery, permissions, handlers and their cost model. */
@NotNullByDefault
public final class CreateVaultTopology {

	public static final String VAULT_ID = "create:item_vault";
	private static final String VAULT = "com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity";
	private static final String BLOCK = "com.simibubi.create.content.logistics.vault.ItemVaultBlock";
	private static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
	private CreateVaultTopology() {}

	@NotNullByDefault
	public record Cell(BlockPos position, BlockState state) {
		public Cell { position = position.immutable(); }
	}

	/** No level, chunk, block entity, handler or live member escapes this operation. */
	@NotNullByDefault
	public record Topology(BlockPos controller, Axis axis, int width, int length,
		@Unmodifiable List<Cell> cells) {
		public Topology { controller = controller.immutable(); cells = List.copyOf(cells); }
		public TargetKey.VaultKey key(String dimension) {
			return new TargetKey.VaultKey(dimension, controller.getX(), controller.getY(), controller.getZ(), VAULT_ID);
		}
	}

	@NotNullByDefault
	interface World { @Nullable Reading loaded(BlockPos position) throws ReflectiveOperationException; }

	/** Local operation evidence; identity is compared by reference, never retained by Topology. */
	@NotNullByDefault
	record Reading(BlockState state, Object identity, BlockPos controller, boolean isController,
		int width, int length, Axis axis) {
		boolean same(Reading other) {
			return identity == other.identity && state == other.state && controller.equals(other.controller)
				&& isController == other.isController && width == other.width && length == other.length && axis == other.axis;
		}
	}

	@FunctionalInterface
	@NotNullByDefault
	interface Chunks { @Nullable LevelChunk loaded(int x, int z); }

	public static TargetKey group(ServerLevel level, Target.BlockTarget target) {
		TargetKey exact = TargetKey.from(target);
		if (!VAULT_ID.equals(target.blockRegistryId())
			|| !level.dimension().location().toString().equals(target.dimensionId())) return exact;
		return resolve(level, (x, z) -> level.getChunkSource().getChunkNow(x, z),
			new BlockPos(target.x(), target.y(), target.z())).<TargetKey>map(t -> t.key(target.dimensionId())).orElse(exact);
	}

	static Optional<Topology> resolve(Level level, Chunks chunks, BlockPos original) {
		// Do not resolve or link any optional class before the tested metadata gate.
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
			int width = root.width(), length = root.length(); Axis axis = root.axis();
			if (width < 1 || width > 3 || length < 1 || length > 3 * width || (axis != Axis.X && axis != Axis.Z)) return Optional.empty();
			int xs = axis == Axis.X ? length : width, zs = axis == Axis.Z ? length : width;
			// Reject overflow before offset(), whose int arithmetic would wrap.
			Math.addExact(controller.getX(), xs - 1); Math.addExact(controller.getY(), width - 1); Math.addExact(controller.getZ(), zs - 1);
			long dx = (long) original.getX() - controller.getX(), dy = (long) original.getY() - controller.getY(), dz = (long) original.getZ() - controller.getZ();
			if (dx < 0 || dx >= xs || dy < 0 || dy >= width || dz < 0 || dz >= zs) return Optional.empty();
			var cells = new ArrayList<Cell>(width * width * length);
			var evidence = new ArrayList<Reading>(width * width * length);
			for (int x = 0; x < xs; x++) for (int y = 0; y < width; y++) for (int z = 0; z < zs; z++) {
				BlockPos pos = controller.offset(x, y, z);
				@Nullable Reading member = world.loaded(pos);
				if (member == null || !controller.equals(member.controller()) || member.axis() != axis
					|| member.isController() != pos.equals(controller)
					|| pos.equals(controller) && !root.same(member)
					|| pos.equals(original) && !source.same(member)) return Optional.empty();
				cells.add(new Cell(pos, member.state())); evidence.add(member);
			}
			// Optional callbacks cannot publish a removed/replaced prefix as a complete structure.
			for (int i = 0; i < cells.size(); i++) {
				@Nullable Reading current = world.loaded(cells.get(i).position());
				if (current == null || !evidence.get(i).same(current)) return Optional.empty();
			}
			return Optional.of(new Topology(controller, axis, width, length, cells));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			failure(original, unavailable); return Optional.empty();
		}
	}

	private static void failure(BlockPos original, Throwable failure) {
		// Reflection must not turn a fatal VM failure into an optional unavailable result.
		if (failure instanceof java.lang.reflect.InvocationTargetException invocation) {
			@Nullable Throwable cause = invocation.getCause();
			if (cause instanceof Error fatal && !(fatal instanceof LinkageError) && !(fatal instanceof AssertionError)) throw fatal;
		}
		Diagnostics.warn("create/vault-topology", VAULT_ID, () -> "position=" + original + "; using physical target", failure);
	}

	@NotNullByDefault
	static final class MinecraftWorld implements World {
		private final Level level;
		private final Chunks chunks;
		private final Api api;
		MinecraftWorld(Level level, Chunks chunks, Api api) { this.level = level; this.chunks = chunks; this.api = api; }
		@Override public @Nullable Reading loaded(BlockPos pos) throws ReflectiveOperationException {
			@Nullable LevelChunk chunk = chunks.loaded(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk == null) return null;
			BlockState state = chunk.getBlockState(pos);
			if (state.getBlock().getClass() != api.block || !VAULT_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
				|| !state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) return null;
			// Even CHECK can promote pending NBT. Only already-instantiated members qualify.
			@Nullable BlockEntity entity = chunk.getBlockEntities().get(pos);
			if (entity == null || entity.getClass() != api.vault || entity.isRemoved() || entity.getLevel() != level
				|| !entity.getBlockPos().equals(pos) || entity.getBlockState() != state
				|| !VAULT_ID.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString())) return null;
			if ((Boolean) api.virtual.invoke(entity)) return null;
			@Nullable BlockPos last = (BlockPos) api.last.invoke(entity);
			if (last != null && !last.equals(pos)) return null;
			@Nullable BlockPos controller = (BlockPos) api.controller.invoke(entity);
			boolean root = (Boolean) api.isController.invoke(entity);
			@Nullable Axis axis = (Axis) api.axis.invoke(entity);
			if (controller == null || axis != state.getValue(BlockStateProperties.HORIZONTAL_AXIS)) return null;
			int width = root ? (Integer) api.width.invoke(entity) : 0;
			int length = root ? (Integer) api.length.invoke(entity) : 0;
			if (entity.isRemoved() || entity.getLevel() != level || chunk.getBlockEntities().get(pos) != entity
				|| chunk.getBlockState(pos) != state) return null;
			return new Reading(state, entity, controller.immutable(), root, width, length, axis);
		}
	}

	/** Fixed metadata API only; declaration ownership also gates nested isController dispatch. */
	@NotNullByDefault
	static final class Api {
		final Class<?> vault, block;
		final Method controller, isController, width, length, axis, last, virtual;
		Api(Class<?> vault, Class<?> block, Class<?> smart) throws ReflectiveOperationException {
			this.vault = vault; this.block = block;
			controller = method(vault, "getController", BlockPos.class);
			isController = method(vault, "isController", boolean.class);
			width = method(vault, "getWidth", int.class); length = method(vault, "getHeight", int.class);
			axis = method(vault, "getMainConnectionAxis", Axis.class); last = method(vault, "getLastKnownPos", BlockPos.class);
			virtual = method(smart, "isVirtual", boolean.class);
			if (vault.getMethod("isVirtual").getDeclaringClass() != smart) throw new NoSuchMethodException("Vault virtual ownership drift");
		}
		private static Method method(Class<?> owner, String name, Class<?> returns) throws ReflectiveOperationException {
			Method method = owner.getDeclaredMethod(name);
			if (method.getReturnType() != returns || !Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()))
				throw new NoSuchMethodException("Vault metadata shape: " + name);
			return method;
		}
	}

	@NotNullByDefault
	private static final class ApiHolder {
		static final Api API = load();
		private static Api load() {
			try {
				ClassLoader loader = CreateVaultTopology.class.getClassLoader();
				return new Api(Class.forName(VAULT, false, loader), Class.forName(BLOCK, false, loader), Class.forName(SMART, false, loader));
			} catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
		}
	}
}
