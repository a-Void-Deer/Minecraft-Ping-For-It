package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.SourceKey;

/** Only ordinary blocks. Every view is sided, read-only, loaded and owner-authorized. */
public final class InventoryMinecraftSources {
	private InventoryMinecraftSources() {}
	private record Slot(Container container, int index) {}
	record View(String identity, List<BlockEntity> entities, List<Slot> slots, IPlatformInventoryService.Access access) {}

	public static Optional<InventorySourceAccess.Source> resolve(MinecraftServer server, InventorySourceInput input) {
		if (!server.isSameThread()) return Optional.empty();
		var dimension = ResourceLocation.tryParse(input.target().dimensionId());
		ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		ServerPlayer owner = server.getPlayerList().getPlayer(input.readOwner());
		if (level == null || owner == null) return Optional.empty();
		try {
			View view = view(level, owner, input);
			var catalog = new InventoryItemCodec.Catalog(level.registryAccess());
			return Optional.of(new Source(input, view, server::isSameThread, () -> {
				ServerPlayer currentOwner = server.getPlayerList().getPlayer(input.readOwner());
				if (currentOwner == null) throw new IllegalStateException("inventory owner unavailable");
				return view(level, currentOwner, input);
			}, entry -> item(catalog, entry)));
		} catch (RuntimeException | LinkageError unavailable) { return Optional.empty(); }
	}

	private static View view(ServerLevel level, ServerPlayer owner, InventorySourceInput input) {
		var target = input.target();
		BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
		if (!level.isLoaded(pos)) throw new IllegalStateException("unloaded inventory");
		BlockState state = level.getBlockState(pos);
		if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(target.blockRegistryId()))
			throw new IllegalStateException("original block unavailable");
		BlockEntity entity = level.getBlockEntity(pos);
		check(entity, owner);
		Direction face = Direction.valueOf(input.face().name());
		List<BlockEntity> entities = new ArrayList<>();
		List<Slot> slots = new ArrayList<>();
		String identity = VanillaInventorySource.simpleBlockId(target.dimensionId(), pos, target.blockRegistryId());
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
			if (!level.isLoaded(partner)) throw new IllegalStateException("unloaded chest partner");
			BlockState other = level.getBlockState(partner);
			if (other.getBlock() != state.getBlock() || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
				|| other.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
				|| other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
				|| !partner.relative(ChestBlock.getConnectedDirection(other)).equals(pos))
				throw new IllegalStateException("invalid chest pairing");
			BlockEntity otherEntity = level.getBlockEntity(partner);
			check(otherEntity, owner);
			if (!(entity instanceof ChestBlockEntity) || !(otherEntity instanceof ChestBlockEntity))
				throw new IllegalStateException("invalid chest halves");
			boolean first = compare(pos, partner) < 0;
			entities.add(first ? entity : otherEntity);
			entities.add(first ? otherEntity : entity);
			identity = VanillaInventorySource.chestAlias(target.dimensionId(), pos, partner, target.blockRegistryId());
		} else entities.add(entity);
		if (entity instanceof Container) {
			for (BlockEntity half : entities) {
				if (!(half instanceof Container container)) throw new IllegalStateException("missing container half");
				for (int slot : sidedSlots(container, face)) slots.add(new Slot(container, slot));
				if (slots.size() > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("container view exceeds finite bound");
			}
			return new View(identity, List.copyOf(entities), List.copyOf(slots), null);
		}
		// Only genuinely non-Container sources reach the native capability bridge.
		IPlatformInventoryService.Access access = IPlatformInventoryService.INSTANCE.find(level, pos, face).orElseThrow();
		return new View(access.alias().orElse(identity), List.copyOf(entities), List.of(), access);
	}

	static int[] sidedSlots(Container container, Direction face) {
		int size = container.getContainerSize();
		if (size < 0 || size > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("container slot bound");
		int[] source = container instanceof WorldlyContainer sided ? sided.getSlotsForFace(face) : null;
		if (source != null && source.length > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("sided slot bound");
		int[] mapping = source == null && !(container instanceof WorldlyContainer) ? java.util.stream.IntStream.range(0, size).toArray()
			: java.util.Objects.requireNonNull(source, "missing sided mapping").clone();
		boolean[] seen = new boolean[size];
		for (int slot : mapping) {
			if (slot < 0 || slot >= size || seen[slot]) throw new IllegalStateException("invalid sided slot mapping");
			seen[slot] = true;
		}
		return mapping;
	}
	private static void check(BlockEntity entity, ServerPlayer owner) {
		if (!InventoryReadSafety.readable(entity, owner.getMainHandItem())) throw new IllegalStateException("source read denied");
	}
	private static int compare(BlockPos a, BlockPos b) {
		int x = Integer.compare(a.getX(), b.getX());
		int y = Integer.compare(a.getY(), b.getY());
		return x != 0 ? x : y != 0 ? y : Integer.compare(a.getZ(), b.getZ());
	}
	/** Live view/codec ports are also used by the Minecraft path above. */
	static final class Source implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final View original;
		private final java.util.function.BooleanSupplier onThread;
		private final java.util.function.Supplier<View> liveView;
		private final java.util.function.Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec;
		private final int originalSlots;
		private final boolean originalStableCursor;
		private IPlatformInventoryService.Access liveAccess;
		private boolean closed;
		Source(InventorySourceInput input, View original, java.util.function.BooleanSupplier onThread,
			java.util.function.Supplier<View> liveView, java.util.function.Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec) {
			this.input = input; this.original = original; this.onThread = onThread; this.liveView = liveView; this.codec = codec;
			originalSlots = original.access == null ? original.slots.size() : original.access.slots();
			originalStableCursor = original.access == null || original.access.stableCursor();
			liveAccess = original.access;
		}
		@Override public SourceKey key() { return new SourceKey(original.access == null ? "minecraft" : "pingforit", "block_inventory", original.identity, input.viewKey()); }
		@Override public boolean valid() {
			if (closed || !onThread.getAsBoolean()) return false;
			try {
				// A freshly reacquired wrapper validates its *new* topology. Validate
				// the admitted access's frozen evidence first, without object-identity equality.
				if (original.access != null && (!original.access.valid() || original.access.slots() != originalSlots
					|| original.access.stableCursor() != originalStableCursor)) return false;
				View current = liveView.get();
				if (!current.identity.equals(original.identity) || !current.entities.equals(original.entities)
					|| !current.slots.equals(original.slots)) return false;
				if ((current.access == null) != (original.access == null) || current.access != null && (!current.access.valid()
					|| current.access.slots() != originalSlots || current.access.stableCursor() != originalStableCursor)) return false;
				liveAccess = current.access;
				return true;
			} catch (RuntimeException | LinkageError unavailable) { return false; }
		}
		@Override public boolean stableCursor() { return originalStableCursor; }
		@Override public int slots() { return originalSlots; }
		// Reacquired capabilities do not prove stable-version ownership; conservatively eventual.
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public InventoryDomainCodec.Item read(int slot) {
			if (!valid()) throw new IllegalStateException("inventory view changed");
			if (original.access != null) return codec.apply(liveAccess.read(slot));
			Slot mapping = original.slots.get(slot);
			ItemStack stack = mapping.container.getItem(mapping.index).copy();
			return stack.isEmpty() ? null : codec.apply(new IPlatformInventoryService.Entry(stack.copyWithCount(1), stack.getCount()));
		}
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			if (!valid()) throw new IllegalStateException("inventory view changed");
			var observed = liveAccess.observe(limit);
			List<InventoryDomainCodec.Item> values = new ArrayList<>();
			for (var entry : observed.entries()) values.add(codec.apply(entry));
			return new InventorySourceAccess.Page(values, observed.complete());
		}
		@Override public void close() { closed = true; }
	}
	private static InventoryDomainCodec.Item item(InventoryItemCodec.Catalog catalog, IPlatformInventoryService.Entry entry) {
		if (entry.isEmpty()) return null;
		var encoded = catalog.encode(entry.exemplar());
		var display = encoded.display();
		return new InventoryDomainCodec.Item(encoded.key(), entry.amount(), display.label(), display.displayJson(), display.componentsStripped());
	}
}
