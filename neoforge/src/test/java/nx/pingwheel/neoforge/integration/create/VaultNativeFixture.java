package nx.pingwheel.neoforge.integration.create;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.domain.Target;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import static nx.pingwheel.neoforge.integration.create.VaultOptionalHarness.*;

/** Constructor-free native levels/caches; production reads must never use a loading/BE lookup. */
@NotNullByDefault
public final class VaultNativeFixture implements AutoCloseable {
	static final UUID OWNER = new UUID(1, 1), OTHER = new UUID(2, 2);
	final VaultOptionalHarness loader = new VaultOptionalHarness();
	final Map<BlockPos, BlockState> states = new HashMap<>();
	final Map<BlockPos, BlockEntity> entities = new HashMap<>();
	final Set<Long> unloaded = new HashSet<>();
	final ServerWorld serverWorld = allocate(ServerWorld.class);
	final ClientWorld client = allocate(ClientWorld.class);
	final Server server = allocate(Server.class);
	final Chunk chunk = allocate(Chunk.class);
	final Block block;
	final Alias<Block> blockAlias;
	final Alias<BlockEntityType<?>> entityAlias;
	final BlockPos root;
	int stateReads, mapReads, chunkReads;
	VaultNativeFixture(Axis axis, int width, int length, BlockPos root) throws Exception {
		this.root = root;
		// Construct one synthetic optional block without changing the registry's frozen lifetime.
		// The temporary intrusive-holder workspace is removed and the exact flags/map restored.
		var frozen = field(MappedRegistry.class, "frozen"); var intrusive = field(MappedRegistry.class, "unregisteredIntrusiveHolders");
		boolean wasFrozen = frozen.getBoolean(BuiltInRegistries.BLOCK); @Nullable Object oldIntrusive = intrusive.get(BuiltInRegistries.BLOCK);
		try {
			frozen.setBoolean(BuiltInRegistries.BLOCK, false); intrusive.set(BuiltInRegistries.BLOCK, new java.util.IdentityHashMap<>());
			block = (Block) loader.loadClass(BLOCK).getConstructor().newInstance();
		} finally { frozen.setBoolean(BuiltInRegistries.BLOCK, wasFrozen); intrusive.set(BuiltInRegistries.BLOCK, oldIntrusive); }
		blockAlias = new Alias<>(BuiltInRegistries.BLOCK, block);
		entityAlias = new Alias<>(BuiltInRegistries.BLOCK_ENTITY_TYPE, BlockEntityType.BEEHIVE);
		serverWorld.fixture = this; client.fixture = this; chunk.fixture = this;
		serverWorld.cache = allocate(ServerCache.class); serverWorld.cache.fixture = this;
		client.cache = allocate(ClientCache.class); client.cache.fixture = this;
		server.players = allocate(Players.class); server.players.player = allocate(Player.class); server.players.player.world = serverWorld;
		field(net.minecraft.world.entity.Entity.class, "position").set(server.players.player, new Vec3(root.getX() + .5, root.getY() + .5, root.getZ() + .5));
		for (int x = 0; x < (axis == Axis.X ? length : width); x++) for (int y = 0; y < width; y++) for (int z = 0; z < (axis == Axis.Z ? length : width); z++) {
			var pos = root.offset(x, y, z); var state = block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
			states.put(pos, state);
			// Members keep default/stale extents. Only the root carries synchronized dimensions.
			var entity = loader.member(pos, state, pos.equals(root) ? null : root, pos.equals(root) ? width : 1, pos.equals(root) ? length : 1);
			entity.setLevel(serverWorld); entities.put(pos, entity);
		}
	}
	void clientEntities() { entities.values().forEach(e -> e.setLevel(client)); }
	Target.BlockTarget target(BlockPos pos) { return new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), "create:item_vault"); }
	@Nullable LevelChunk loaded(int x, int z) { chunkReads++; return unloaded.contains(net.minecraft.world.level.ChunkPos.asLong(x, z)) ? null : chunk; }
	@Override public void close() { entityAlias.close(); blockAlias.close(); }

	@NotNullByDefault
	public static class VaultBlock extends Block implements net.minecraft.world.level.block.EntityBlock {
		public VaultBlock() { super(BlockBehaviour.Properties.of()); }
		@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(BlockStateProperties.HORIZONTAL_AXIS); }
		@Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { throw new AssertionError("no inventory/BE construction"); }
	}
	@NotNullByDefault
	static final class Alias<T> implements AutoCloseable {
		final Map<T, Holder.Reference<T>> map;
		final T value;
		final Holder.@Nullable Reference<T> previous;
		@SuppressWarnings("unchecked") Alias(Registry<T> registry, T value) throws Exception {
			map = (Map<T, Holder.Reference<T>>) field(MappedRegistry.class, "byValue").get(registry); this.value = value; previous = map.get(value);
			map.put(value, Holder.Reference.createStandAlone(registry.asLookup(), ResourceKey.create(registry.key(), ResourceLocation.parse("create:item_vault"))));
		}
		@Override public void close() { if (previous == null) map.remove(value); else map.put(value, previous); }
	}
	@NotNullByDefault
	public static final class Chunk extends LevelChunk {
		VaultNativeFixture fixture;
		private Chunk() { super(null, null); }
		@Override public BlockState getBlockState(BlockPos pos) { fixture.stateReads++; return fixture.states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
		@Override public Map<BlockPos, BlockEntity> getBlockEntities() { fixture.mapReads++; return fixture.entities; }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("no BE promotion"); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos, EntityCreationType creation) { throw new AssertionError("even CHECK promotes NBT"); }
	}
	@NotNullByDefault
	public static final class ServerCache extends ServerChunkCache {
		VaultNativeFixture fixture;
		private ServerCache() { super(null, null, null, null, null, null, 0, 0, false, null, null, null); }
		@Override public @Nullable LevelChunk getChunkNow(int x, int z) { return fixture.loaded(x, z); }
		@Override public @Nullable ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) { throw new AssertionError("no loading server lookup"); }
	}
	@NotNullByDefault
	public static final class ServerWorld extends ServerLevel {
		VaultNativeFixture fixture; ServerCache cache;
		private ServerWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public ServerChunkCache getChunkSource() { return cache; }
		@Override public boolean isLoaded(BlockPos pos) { return !fixture.unloaded.contains(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)); }
		@Override public BlockState getBlockState(BlockPos pos) { return fixture.states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return fixture.entities.get(pos); }
	}
	@NotNullByDefault
	public static final class ClientCache extends ClientChunkCache {
		VaultNativeFixture fixture;
		private ClientCache() { super(null, 0); }
		@Override public @Nullable LevelChunk getChunk(int x, int z, ChunkStatus status, boolean required) {
			if (required || status != ChunkStatus.FULL) throw new AssertionError("nonloading FULL only"); return fixture.loaded(x, z);
		}
	}
	@NotNullByDefault
	public static final class ClientWorld extends ClientLevel {
		VaultNativeFixture fixture; ClientCache cache;
		private ClientWorld() { super(null, null, null, null, 0, 0, null, null, false, 0); }
		@Override public ClientChunkCache getChunkSource() { return cache; }
		@Override public BlockState getBlockState(BlockPos pos) { throw new AssertionError("no unguarded level read"); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("no IMMEDIATE BE read"); }
	}
	@NotNullByDefault
	public static final class Player extends ServerPlayer {
		ServerWorld world;
		private Player() { super(null, null, null, null); }
		@Override public ServerLevel serverLevel() { return world; }
	}
	@NotNullByDefault
	public static final class Players extends DedicatedPlayerList {
		Player player;
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID owner) { return owner.equals(OWNER) || owner.equals(OTHER) ? player : null; }
	}
	@NotNullByDefault
	public static final class Server extends DedicatedServer {
		Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public DedicatedPlayerList getPlayerList() { return players; }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
	}
}
