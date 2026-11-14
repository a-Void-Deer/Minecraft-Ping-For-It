package nx.pingwheel.common.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * Shared headless Level for entity-focused tests.
 *
 * <p>The 26.3 {@link Entity} constructor requires a non-null Level because it
 * calls {@link Level#getNextEntityId()}. A real server level is not available
 * in a common test, so this fixture supplies the minimum Level surface needed
 * for entity construction: bootstrap the vanilla registries once, then build a
 * Level for entity creation.</p>
 *
 * <p>The Level constructor eagerly calls {@code PalettedContainerFactory.create}
 * (which needs the biome registry and {@code minecraft:plains}) and constructs
 * {@code DamageSources} (which needs every damage-type key). Those two are
 * datapack registries in 26.3 and therefore are not part of
 * {@link BuiltInRegistries#REGISTRY}; the fixture layers minimal registries for
 * exactly those two keys over the real built-in registry access. The holders
 * are unused placeholders: entity construction only asks for their keys.</p>
 *
 * <p>Only entity construction and the pure math seams these tests exercise are
 * supported. The dimension type is {@code null}, and this fixture intentionally
 * returns empty/null for chunk, scoreboard, recipe, clock and environment APIs;
 * tests must not call {@code dimensionType()}, {@code minY()}, chunk access or
 * {@code value()} on the placeholder biome/damage holders.</p>
 */
public final class TestLevel extends Level {

	private static boolean bootstrapped;
	private static TestLevel instance;

	private TestLevel(RegistryAccess registryAccess) {
		super(new TestLevelData(), Level.OVERWORLD, registryAccess, null, false, false, 0L, 0);
	}

	/** Returns the lazily created shared fixture, bootstrapping vanilla first. */
	public static synchronized TestLevel get() {
		ensureBootstrapped();

		if (instance == null) {
			instance = new TestLevel(createRegistryAccess());
		}

		return instance;
	}

	/** Runs the one-time vanilla bootstrap needed before constructing a Level. */
	public static synchronized void ensureBootstrapped() {
		if (bootstrapped) {
			return;
		}

		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		bootstrapped = true;
	}

	private static RegistryAccess createRegistryAccess() {
		RegistryAccess builtIns = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		return new TestRegistryAccess(builtIns);
	}

	@Override
	public ChunkSource getChunkSource() {
		return null;
	}

	@Override
	public LevelTickAccess<Block> getBlockTicks() {
		return null;
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return null;
	}

	@Override
	public WorldBorder getWorldBorder() {
		return null;
	}

	@Override
	public List<VoxelShape> getEntityCollisions(Entity source, AABB testArea) {
		return List.of();
	}

	@Override
	public int getSeaLevel() {
		return 0;
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
		return null;
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return FeatureFlagSet.of();
	}

	@Override
	public List<? extends Player> players() {
		return List.of();
	}

	@Override
	public void levelEvent(Entity source, int type, BlockPos pos, int data) {
	}

	@Override
	public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
	}

	@Override
	public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
	}

	@Override
	public void playSeededSound(Entity source, double x, double y, double z,
		Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
	}

	@Override
	public void playSeededSound(Entity source, Entity except, Holder<SoundEvent> sound,
		SoundSource category, float volume, float pitch, long seed) {
	}

	@Override
	public void explode(Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator,
		double x, double y, double z, float radius, boolean fire, ExplosionInteraction interaction,
		ParticleOptions smallParticles, ParticleOptions largeParticles,
		WeightedList<ExplosionParticleInfo> blockParticles, Holder<SoundEvent> sound) {
	}

	@Override
	public String gatherChunkSourceStats() {
		return "";
	}

	@Override
	public void setRespawnData(LevelData.RespawnData respawnData) {
	}

	@Override
	public LevelData.RespawnData getRespawnData() {
		return null;
	}

	@Override
	public Entity getEntity(int id) {
		return null;
	}

	@Override
	public List<EnderDragonPart> dragonParts() {
		return List.of();
	}

	@Override
	public TickRateManager tickRateManager() {
		return null;
	}

	@Override
	public MapItemSavedData getMapData(MapId id) {
		return null;
	}

	@Override
	public void destroyBlockProgress(int id, BlockPos pos, int progress) {
	}

	@Override
	public Scoreboard getScoreboard() {
		return null;
	}

	@Override
	public RecipeAccess recipeAccess() {
		return null;
	}

	@Override
	protected LevelEntityGetter<Entity> getEntities() {
		return null;
	}

	@Override
	public ClockManager clockManager() {
		return null;
	}

	@Override
	public EnvironmentAttributeSystem environmentAttributes() {
		return null;
	}

	private static final class TestLevelData implements WritableLevelData {
		@Override
		public void setSpawn(LevelData.RespawnData respawnData) {
		}

		@Override
		public LevelData.RespawnData getRespawnData() {
			return null;
		}

		@Override
		public long getGameTime() {
			return 0L;
		}

		@Override
		public boolean isHardcore() {
			return false;
		}

		@Override
		public Difficulty getDifficulty() {
			return Difficulty.NORMAL;
		}

		@Override
		public boolean isDifficultyLocked() {
			return false;
		}
	}

	/**
	 * Real built-in registry access plus the two 26.3 datapack registries that
	 * the Level constructor reads before any datapack loading.
	 */
	private static final class TestRegistryAccess implements RegistryAccess {

		private static final HolderOwner<Biome> BIOME_OWNER = new HolderOwner<>() {
		};
		private static final HolderOwner<DamageType> DAMAGE_OWNER = new HolderOwner<>() {
		};
		private static final Registry<Biome> BIOMES = registry(
			Registries.BIOME,
			key -> Holder.Reference.createStandAlone(BIOME_OWNER, key),
			Biome.CODEC,
			new SingleHolderIdMap<>(Holder.Reference.createStandAlone(BIOME_OWNER, Biomes.PLAINS)));
		private static final Registry<DamageType> DAMAGE_TYPES = registry(
			Registries.DAMAGE_TYPE,
			key -> Holder.Reference.createStandAlone(DAMAGE_OWNER, key),
			DamageType.CODEC,
			new SingleHolderIdMap<>(Holder.Reference.createStandAlone(DAMAGE_OWNER, DamageTypes.GENERIC)));

		private final RegistryAccess builtIns;

		private TestRegistryAccess(RegistryAccess builtIns) {
			this.builtIns = builtIns;
		}

		@Override
		public <E> Optional<Registry<E>> lookup(ResourceKey<? extends Registry<? extends E>> key) {
			if (Registries.BIOME.equals(key)) {
				return Optional.of((Registry<E>) BIOMES);
			}

			if (Registries.DAMAGE_TYPE.equals(key)) {
				return Optional.of((Registry<E>) DAMAGE_TYPES);
			}

			return this.builtIns.lookup(key);
		}

		@Override
		public Stream<RegistryEntry<?>> registries() {
			return this.builtIns.registries();
		}
	}

	private static <T> Registry<T> registry(
		ResourceKey<? extends Registry<T>> key,
		Function<ResourceKey<T>, Holder.Reference<T>> holderFactory,
		Codec<Holder<T>> holderCodec,
		IdMap<Holder<T>> holderIdMap
	) {
		return (Registry<T>) Proxy.newProxyInstance(
			TestLevel.class.getClassLoader(),
			new Class<?>[] {Registry.class},
			new RegistryHandler<>(key, holderFactory, holderCodec, holderIdMap));
	}

	private static final class RegistryHandler<T> implements InvocationHandler {
		private final ResourceKey<? extends Registry<T>> key;
		private final Function<ResourceKey<T>, Holder.Reference<T>> holderFactory;
		private final Codec<Holder<T>> holderCodec;
		private final IdMap<Holder<T>> holderIdMap;

		private RegistryHandler(
			ResourceKey<? extends Registry<T>> key,
			Function<ResourceKey<T>, Holder.Reference<T>> holderFactory,
			Codec<Holder<T>> holderCodec,
			IdMap<Holder<T>> holderIdMap
		) {
			this.key = key;
			this.holderFactory = holderFactory;
			this.holderCodec = holderCodec;
			this.holderIdMap = holderIdMap;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			String name = method.getName();

			if ("key".equals(name)) {
				return this.key;
			}

			if ("getOrThrow".equals(name)) {
				return this.holderFactory.apply((ResourceKey<T>) args[0]);
			}

			if ("get".equals(name) || "getOptional".equals(name)) {
				if (args[0] instanceof Integer) {
					return Optional.ofNullable(this.holderIdMap.byId(((Integer) args[0]).intValue()));
				}

				return Optional.of(this.holderFactory.apply((ResourceKey<T>) args[0]));
			}

			if ("asHolderIdMap".equals(name)) {
				return this.holderIdMap;
			}

			if ("holderByNameCodec".equals(name)) {
				return this.holderCodec;
			}

			if ("getId".equals(name)) {
				return 0;
			}

			if ("byId".equals(name)) {
				return this.holderIdMap.byId(((Integer) args[0]).intValue());
			}

			if ("size".equals(name)) {
				return this.holderIdMap.size();
			}

			if ("iterator".equals(name)) {
				return this.holderIdMap.iterator();
			}

			if ("keySet".equals(name) || "entrySet".equals(name) || "registryKeySet".equals(name)) {
				return Set.of();
			}

			if ("containsKey".equals(name)) {
				return false;
			}

			if ("stream".equals(name) || "getTags".equals(name)) {
				return Stream.empty();
			}

			if ("getAny".equals(name)) {
				return Optional.empty();
			}

			if ("freeze".equals(name)) {
				return proxy;
			}

			if ("registryLifecycle".equals(name)) {
				return Lifecycle.stable();
			}

			if ("equals".equals(name)) {
				return proxy == args[0];
			}

			if ("hashCode".equals(name)) {
				return System.identityHashCode(proxy);
			}

			if ("toString".equals(name)) {
				return "TestLevelRegistry[" + this.key + "]";
			}

			return defaultValue(method.getReturnType());
		}
	}

	private static Object defaultValue(Class<?> type) {
		if (type == boolean.class) {
			return false;
		}

		if (type == int.class) {
			return 0;
		}

		if (type == long.class) {
			return 0L;
		}

		if (type == float.class) {
			return 0.0F;
		}

		if (type == double.class) {
			return 0.0D;
		}

		if (type == short.class) {
			return (short) 0;
		}

		if (type == byte.class) {
			return (byte) 0;
		}

		if (type == char.class) {
			return (char) 0;
		}

		return null;
	}

	private static final class SingleHolderIdMap<T> implements IdMap<Holder<T>> {
		private final Holder<T> holder;

		private SingleHolderIdMap(Holder<T> holder) {
			this.holder = holder;
		}

		@Override
		public int getId(Holder<T> value) {
			return 0;
		}

		@Override
		public Holder<T> byId(int id) {
			return this.holder;
		}

		@Override
		public int size() {
			return 1;
		}

		@Override
		public Iterator<Holder<T>> iterator() {
			return Collections.singletonList(this.holder).iterator();
		}
	}
}
