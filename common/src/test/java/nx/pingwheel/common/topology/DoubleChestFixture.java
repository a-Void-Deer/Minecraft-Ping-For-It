package nx.pingwheel.common.topology;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.domain.Target;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Native adapter fixture: constructors are bypassed, never a running world or server. */
@NotNullByDefault
public final class DoubleChestFixture {
	public static final UUID OWNER = new UUID(1, 1), OTHER = new UUID(2, 2);
	public final Map<BlockPos, BlockState> states = new HashMap<>();
	public final Set<BlockPos> unavailable = new HashSet<>();
	public final List<BlockPos> reads = new java.util.ArrayList<>();
	public final ServerWorld world = allocate(ServerWorld.class);
	public final ClientWorld client = allocate(ClientWorld.class);
	public final Server server = allocate(Server.class);
	public final BlockPos left = new BlockPos(15, 64, 15);
	public final BlockPos right;
	public int blockEntities, ordinaryClientReads, chunkLookups;

	public DoubleChestFixture(Block block, Direction facing) {
		BlockState state = block.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT).setValue(ChestBlock.FACING, facing);
		right = left.relative(ChestBlock.getConnectedDirection(state));
		states.put(left, state); states.put(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT).setValue(ChestBlock.WATERLOGGED, true));
		world.fixture = this; client.fixture = this; server.fixture = this;
		world.cache = allocate(ServerCache.class); world.cache.fixture = this;
		client.cache = allocate(ClientCache.class); client.cache.fixture = this;
		server.players = allocate(Players.class); server.players.fixture = this;
		server.players.player = allocate(Player.class); server.players.player.world = world;
		try {
			var position = net.minecraft.world.entity.Entity.class.getDeclaredField("position"); position.setAccessible(true);
			position.set(server.players.player, new Vec3(15.5, 64.5, 15.5));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	public Target.BlockTarget target(BlockPos pos) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(states.get(pos).getBlock()).toString();
		return new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), id);
	}
	private @Nullable Chunk chunk(int x, int z) {
		chunkLookups++;
		if (unavailable.stream().anyMatch(pos -> pos.getX() >> 4 == x && pos.getZ() >> 4 == z)) return null;
		Chunk chunk = allocate(Chunk.class); chunk.fixture = this; return chunk;
	}
	private BlockState read(BlockPos pos) {
		if (unavailable.stream().anyMatch(other -> other.getX() >> 4 == pos.getX() >> 4 && other.getZ() >> 4 == pos.getZ() >> 4))
			throw new AssertionError("read after unavailable chunk: " + pos);
		reads.add(pos); return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}
	public static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	@NotNullByDefault
	public static final class Chunk extends LevelChunk {
		DoubleChestFixture fixture;
		private Chunk() { super(null, null); }
		@Override public BlockState getBlockState(BlockPos pos) { return fixture.read(pos); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("topology may not get BE/inventory/loot"); }
	}
	@NotNullByDefault
	public static final class ServerCache extends ServerChunkCache {
		DoubleChestFixture fixture;
		private ServerCache() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
		@Override public @Nullable LevelChunk getChunkNow(int x, int z) { return fixture.chunk(x, z); }
		@Override public @Nullable ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) { throw new AssertionError("no loading server lookup"); }
	}
	@NotNullByDefault
	public static final class ServerWorld extends ServerLevel {
		DoubleChestFixture fixture; ServerCache cache;
		private ServerWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public ServerChunkCache getChunkSource() { return cache; }
		@Override public boolean isLoaded(BlockPos pos) { return !fixture.unavailable.contains(pos); }
		@Override public BlockState getBlockState(BlockPos pos) { return fixture.read(pos); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { fixture.blockEntities++; return null; }
	}
	@NotNullByDefault
	public static final class ClientCache extends ClientChunkCache {
		DoubleChestFixture fixture;
		private ClientCache() { super(null, 0); }
		@Override public @Nullable LevelChunk getChunk(int x, int z, ChunkStatus status, boolean required) {
			if (required || status != ChunkStatus.FULL) throw new AssertionError("positive nonloading FULL lookup required");
			return fixture.chunk(x, z);
		}
	}
	@NotNullByDefault
	public static final class ClientWorld extends ClientLevel {
		DoubleChestFixture fixture; ClientCache cache;
		private ClientWorld() { super(null, null, null, null, 0, 0, null, null, false, 0); }
		@Override public ClientChunkCache getChunkSource() { return cache; }
		@Override public BlockState getBlockState(BlockPos pos) { fixture.ordinaryClientReads++; throw new AssertionError("client topology must read positively loaded chunk"); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("no client BE lookup"); }
	}
	@NotNullByDefault
	public static final class Player extends ServerPlayer {
		ServerWorld world;
		private Player() { super(null, null, null, null); }
		@Override public ServerLevel serverLevel() { return world; }
	}
	@NotNullByDefault
	public static final class Players extends DedicatedPlayerList {
		DoubleChestFixture fixture; Player player;
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID owner) { return owner.equals(OWNER) || owner.equals(OTHER) ? player : null; }
	}
	@NotNullByDefault
	public static final class Server extends DedicatedServer {
		DoubleChestFixture fixture; Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public DedicatedPlayerList getPlayerList() { return players; }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
	}
}
