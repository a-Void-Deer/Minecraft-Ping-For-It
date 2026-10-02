package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.source.SourceKey;

/**
 * Vanilla 1.21.1 container provider for the shared inventory scanner.
 *
 * <p>{@link #resolve} turns an already-validated block or entity target into a
 * detached {@link SourceKey} plus a revalidating {@link InventoryScanner.Source}.
 * It performs no permission decision: the caller validates the target first,
 * and this class only double-checks that the physical source is still loaded
 * and still matches the original target identity.
 *
 * <p>Safety rules: reads never force-load chunks, never generate loot, and
 * never modify the world. A loaded block entity must be a {@link Container} and
 * its loot table (block entities via {@link RandomizableContainer}; minecarts
 * and chest boats via {@link ContainerEntity}) must be absent before any item
 * is read. Locks are checked through
	 * a lock-only accessor so the vanilla
 * {@code canOpen} feedback message and sound are not emitted. Double chests
 * resolve only when both halves are loaded and both block entities pass the
 * same gates; a canonical lower-lexicographic position alias makes either half
 * the same source identity.
 *
 * <p>The source holds no world object between reads: each read re-resolves the
 * container, revalidates type, loaded state, lock and loot, and only then
 * detaches a stack copy for the codec. The display catalog is the only cache
 * and it stores detached variant identity only.
 */
public final class VanillaInventorySource {

	static final String PROVIDER_ID = "minecraft";
	static final String BLOCK_KIND = "block_container";
	static final String ENTITY_KIND = "entity_container";
	static final String READ_SCOPE = "inventory";

	private VanillaInventorySource() {}

	/**
	 * A resolved physical source. {@code catalog} is a live read-only view that
	 * gains one entry for every distinct variant observed by the source.
	 */
	public record Resolved(SourceKey key, Target original, InventoryScanner.Source source,
		Map<InventoryScanner.Key, InventoryItemCodec.Display> catalog) {
		public Resolved {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(original, "original");
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(catalog, "catalog");
		}
	}

	/**
	 * Resolves a validated target against the live server. Unsupported target
	 * kinds (location, external provider), unloaded positions, changed blocks or
	 * unavailable containers yield {@link Optional#empty()} rather than a guess.
	 */
	public static Optional<Resolved> resolve(MinecraftServer server, ServerPlayer player, Target target) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(target, "target");
		ServerLevel level = level(server, target.dimensionId());
		if (level == null) return Optional.empty();

		if (target instanceof Target.BlockTarget block) return resolveBlock(level, player, block);
		if (target instanceof Target.EntityTarget entity) return resolveEntity(level, player, entity);
		return Optional.empty();
	}

	private static Optional<Resolved> resolveBlock(ServerLevel level, ServerPlayer player,
		Target.BlockTarget target) {
		BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
		if (!level.isLoaded(pos)) return Optional.empty();
		BlockState state = level.getBlockState(pos);
		if (!blockId(state.getBlock()).equals(target.blockRegistryId())) return Optional.empty();

		Source source = new Source(level, player, target);
		try {
			source.container();
		} catch (SourceUnavailableException unavailable) {
			return Optional.empty();
		}
		String stableId = blockStableId(level.dimension().location().toString(), pos, state);
		return resolved(target, BLOCK_KIND, stableId, source);
	}

	private static Optional<Resolved> resolveEntity(ServerLevel level, ServerPlayer player,
		Target.EntityTarget target) {
		if (!(target.locator() instanceof EntityLocator.UUID uuid)) return Optional.empty();
		Entity entity = level.getEntity(uuid.value());
		if (entity == null || entity.isRemoved()) return Optional.empty();
		if (!(entity instanceof ContainerEntity containerEntity)) return Optional.empty();
		if (containerEntity.getLootTable() != null) return Optional.empty();
		if (entity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
			return Optional.empty();
		}

		String stableId = entityStableId(level.dimension().location().toString(), entity.getUUID(),
			BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		Source source = new Source(level, player, target);
		return resolved(target, ENTITY_KIND, stableId, source);
	}

	private static Optional<Resolved> resolved(Target target, String kind, String stableId, Source source) {
		try {
			SourceKey key = new SourceKey(PROVIDER_ID, kind, stableId, READ_SCOPE);
			return Optional.of(new Resolved(key, target, source, source.catalog()));
		} catch (IllegalArgumentException invalidIdentity) {
			return Optional.empty();
		}
	}

	private static ServerLevel level(MinecraftServer server, String dimensionId) {
		ResourceLocation dimension = ResourceLocation.tryParse(dimensionId);
		return dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
	}

	static String blockStableId(String dimensionId, BlockPos pos, BlockState state) {
		String registryId = blockId(state.getBlock());
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
			return chestAlias(dimensionId, pos, partner, registryId);
		}
		return simpleBlockId(dimensionId, pos, registryId);
	}

	/** Pure identity for one non-double block container. */
	static String simpleBlockId(String dimensionId, BlockPos pos, String registryId) {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(registryId, "registryId");
		return dimensionId + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "|" + registryId;
	}

	/**
	 * Pure canonical alias for a double container: dimension, registry id, and
	 * the position pair in lower-lexicographic order, so either half resolves to
	 * the same source key.
	 */
	static String chestAlias(String dimensionId, BlockPos first, BlockPos second, String registryId) {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(first, "first");
		Objects.requireNonNull(second, "second");
		Objects.requireNonNull(registryId, "registryId");
		BlockPos low = comparePositions(first, second) <= 0 ? first : second;
		BlockPos high = low == first ? second : first;
		return dimensionId + "|" + low.getX() + "," + low.getY() + "," + low.getZ()
			+ "|" + high.getX() + "," + high.getY() + "," + high.getZ() + "|" + registryId;
	}

	/** Pure identity for one entity container, stable across movement. */
	static String entityStableId(String dimensionId, UUID uuid, String entityTypeId) {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(uuid, "uuid");
		Objects.requireNonNull(entityTypeId, "entityTypeId");
		return dimensionId + "|entity|" + uuid + "|" + entityTypeId;
	}

	static List<BlockPos> chestHalves(BlockState state, BlockPos pos) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(pos, "pos");
		if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return List.of(pos);
		return List.of(pos, pos.relative(ChestBlock.getConnectedDirection(state)));
	}

	private static int comparePositions(BlockPos first, BlockPos second) {
		int x = Integer.compare(first.getX(), second.getX());
		if (x != 0) return x;
		int y = Integer.compare(first.getY(), second.getY());
		if (y != 0) return y;
		return Integer.compare(first.getZ(), second.getZ());
	}

	private static String blockId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	/** Ordinary unavailability of one physical read; never an exception escape. */
	static final class SourceUnavailableException extends IllegalStateException {
		SourceUnavailableException(String message) {
			super(message);
		}
	}

	/**
	 * Server-thread-confined source that re-resolves and revalidates the
	 * container on every read. Slot indices are stable; the snapshot is not
	 * atomic, so the scanner keeps its uncertain consistency fact.
	 */
	private static final class Source implements InventoryScanner.Source {

		private final ServerLevel level;
		private final ServerPlayer player;
		private final Target original;
		private final InventoryItemCodec.Catalog catalog;

		Source(ServerLevel level, ServerPlayer player, Target original) {
			this.level = level;
			this.player = player;
			this.original = original;
			this.catalog = new InventoryItemCodec.Catalog(level.registryAccess());
		}

		Map<InventoryScanner.Key, InventoryItemCodec.Display> catalog() {
			return catalog.displays();
		}

		@Override
		public int slots() {
			return container().getContainerSize();
		}

		@Override
		public boolean stableCursor() {
			return true;
		}

		@Override
		public boolean stableSnapshot() {
			return false;
		}

		@Override
		public InventoryScanner.Stack read(int slot) {
			if (slot < 0) throw new SourceUnavailableException("negative slot");
			Container container = container();
			if (slot >= container.getContainerSize()) {
				throw new SourceUnavailableException("slot index is out of range");
			}
			ItemStack stack = container.getItem(slot).copy();
			if (stack.isEmpty()) return null;
			InventoryItemCodec.Encoded encoded = catalog.encode(stack);
			return new InventoryScanner.Stack(encoded.key(), stack.getCount());
		}

		private Container container() {
			if (original instanceof Target.BlockTarget block) return blockContainer(block);
			if (original instanceof Target.EntityTarget entity) return entityContainer(entity);
			throw new SourceUnavailableException("unsupported source target");
		}

		private Container blockContainer(Target.BlockTarget target) {
			BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
			if (!level.isLoaded(pos)) throw new SourceUnavailableException("source chunk is not loaded");
			BlockState state = level.getBlockState(pos);
			if (!blockId(state.getBlock()).equals(target.blockRegistryId())) {
				throw new SourceUnavailableException("source block changed");
			}
			if (state.getBlock() instanceof ChestBlock chest) return chestContainer(chest, state, pos);

			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof Container container)) {
				throw new SourceUnavailableException("source is not a container");
			}
			if (blockEntity instanceof RandomizableContainer randomizable
				&& randomizable.getLootTable() != null) {
				throw new SourceUnavailableException("loot generation is pending");
			}
			if (blockEntity instanceof BaseContainerBlockEntity base && !unlocked(base)) {
				throw new SourceUnavailableException("source is locked");
			}
			return container;
		}

		private Container chestContainer(ChestBlock chest, BlockState state, BlockPos pos) {
			for (BlockPos half : chestHalves(state, pos)) {
				if (!level.isLoaded(half)) throw new SourceUnavailableException("container half is not loaded");
				BlockEntity blockEntity = level.getBlockEntity(half);
				if (!(blockEntity instanceof ChestBlockEntity chestEntity)) {
					throw new SourceUnavailableException("container half changed");
				}
				if (chestEntity.getLootTable() != null) {
					throw new SourceUnavailableException("loot generation is pending");
				}
				if (!unlocked(chestEntity)) throw new SourceUnavailableException("container half is locked");
			}

			Container container = ChestBlock.getContainer(chest, state, level, pos, true);
			if (container == null) throw new SourceUnavailableException("container is unavailable");
			return container;
		}

		private boolean unlocked(BaseContainerBlockEntity blockEntity) {
			return InventoryReadSafety.readable(blockEntity, player.getMainHandItem());
		}

		private Container entityContainer(Target.EntityTarget target) {
			if (!(target.locator() instanceof EntityLocator.UUID uuid)) {
				throw new SourceUnavailableException("runtime entity ids are not supported");
			}
			Entity entity = level.getEntity(uuid.value());
			if (entity == null || entity.isRemoved()) throw new SourceUnavailableException("entity is not loaded");
			if (entity.level() != level) throw new SourceUnavailableException("entity changed dimension");
			if (!(entity instanceof ContainerEntity containerEntity)) {
				throw new SourceUnavailableException("entity is not a container entity");
			}
			if (containerEntity.getLootTable() != null) {
				throw new SourceUnavailableException("loot generation is pending");
			}
			if (entity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
				throw new SourceUnavailableException("loot generation is pending");
			}
			return containerEntity;
		}
	}
}
