package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
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
	record View(String identity, List<BlockEntity> entities, List<Slot> slots, IPlatformInventoryService.Access access,
		InventorySnapshotLayout snapshotLayout, boolean discoverItems) {
		View(String identity, List<BlockEntity> entities, List<Slot> slots, IPlatformInventoryService.Access access) {
			this(identity, entities, slots, access, null, false);
		}
	}

	/** Server-thread world facts shared by view discovery and detached capture; production delegates to ServerLevel. */
	interface SourceWorld {
		boolean isLoaded(BlockPos pos);
		BlockState blockState(BlockPos pos);
		BlockEntity blockEntity(BlockPos pos);
		boolean onServerThread();
		ItemStack heldKey();
		HolderLookup.Provider registries();
	}

	/** Loader capability lookup over one exact position and frozen face; production delegates to the platform service. */
	@FunctionalInterface
	interface ProviderAccessLookup {
		Optional<IPlatformInventoryService.Access> find(BlockPos pos, Direction face);
	}

	public static Optional<InventorySourceAccess.Source> resolve(MinecraftServer server, InventorySourceInput input) {
		if (!server.isSameThread()) return Optional.empty();
		var dimension = ResourceLocation.tryParse(input.target().dimensionId());
		ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		ServerPlayer owner = server.getPlayerList().getPlayer(input.readOwner());
		if (level == null || owner == null) return Optional.empty();
		try {
			Supplier<ServerPlayer> ownerSupplier = () -> server.getPlayerList().getPlayer(input.readOwner());
			ServerWorld world = new ServerWorld(level, ownerSupplier);
			ProviderAccessLookup providers = (pos, face) -> IPlatformInventoryService.INSTANCE.find(level, pos, face);
			View view = view(world, input, providers);
			var catalog = new InventoryItemCodec.Catalog(level.registryAccess());
			return Optional.of(new Source(input, view, server::isSameThread, () -> view(world, input, providers),
				entry -> item(catalog, entry), world, null));
		} catch (RuntimeException | LinkageError unavailable) { return Optional.empty(); }
	}

	static View view(SourceWorld world, InventorySourceInput input, ProviderAccessLookup providers) {
		var target = input.target();
		BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
		if (!world.isLoaded(pos)) throw new IllegalStateException("unloaded inventory");
		BlockState state = world.blockState(pos);
		if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(target.blockRegistryId()))
			throw new IllegalStateException("original block unavailable");
		BlockEntity entity = world.blockEntity(pos);
		check(entity, world.heldKey());
		Direction face = Direction.valueOf(input.face().name());
		List<BlockEntity> entities = new ArrayList<>();
		List<Slot> slots = new ArrayList<>();
		String identity = VanillaInventorySource.simpleBlockId(target.dimensionId(), pos, target.blockRegistryId());
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
			if (!world.isLoaded(partner)) throw new IllegalStateException("unloaded chest partner");
			BlockState other = world.blockState(partner);
			if (other.getBlock() != state.getBlock() || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
				|| other.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
				|| other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
				|| !partner.relative(ChestBlock.getConnectedDirection(other)).equals(pos))
				throw new IllegalStateException("invalid chest pairing");
			BlockEntity otherEntity = world.blockEntity(partner);
			check(otherEntity, world.heldKey());
			if (!(entity instanceof ChestBlockEntity) || !(otherEntity instanceof ChestBlockEntity))
				throw new IllegalStateException("invalid chest halves");
			boolean first = compare(pos, partner) < 0;
			entities.add(first ? entity : otherEntity);
			entities.add(first ? otherEntity : entity);
			identity = VanillaInventorySource.chestAlias(target.dimensionId(), pos, partner, target.blockRegistryId());
		} else entities.add(entity);
		if (entity instanceof Container) {
			List<InventorySnapshotLayout.Member> members = new ArrayList<>();
			for (BlockEntity half : entities) {
				if (!(half instanceof Container container)) throw new IllegalStateException("missing container half");
				int[] mapping = sidedSlots(container, face);
				for (int slot : mapping) slots.add(new Slot(container, slot));
				if (slots.size() > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("container view exceeds finite bound");
				BlockPos memberPos = half.getBlockPos().immutable();
				BlockState memberState = world.blockState(memberPos);
				String memberBlockId = BuiltInRegistries.BLOCK.getKey(memberState.getBlock()).toString();
				String memberEntityId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(half.getType()).toString();
				String role = memberPos.equals(pos) ? "target" : "member";
				List<InventorySnapshotLayout.Segment> segments = InventorySnapshotSchemas.registered(InventorySnapshotSchemas.ITEMS)
					? List.of(new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS,
						container.getContainerSize(), java.util.Arrays.stream(mapping).boxed().toList())) : List.of();
				members.add(new InventorySnapshotLayout.Member(memberPos, memberBlockId, memberEntityId, role, segments));
			}
			BlockPos controller = entities.size() == 1 ? pos.immutable() : null;
			String layoutId = entities.size() == 1 ? "minecraft:container" : "minecraft:double_chest";
			String layoutData = "face=" + face.name().toLowerCase(java.util.Locale.ROOT);
			var layout = new InventorySnapshotLayout(layoutId, layoutData, controller, members);
			return new View(identity, List.copyOf(entities), List.copyOf(slots), null, layout, true);
		}
		// Only genuinely non-Container sources reach the native capability bridge.
		IPlatformInventoryService.Access access = providers.find(pos, face).orElseThrow();
		String accessIdentity = access.alias().orElse(identity);
		InventorySnapshotLayout layout = providerLayout(input, pos, access);
		return new View(accessIdentity, List.copyOf(entities), List.of(), access, layout, false);
	}

	/**
	 * Explicit provider layout decision. A structurally inconsistent layout is a
	 * hard refusal: only a genuinely absent or unregistered layout keeps the
	 * existing live fallback, because a live slot count alone cannot prove that
	 * an arbitrary provider's NBT range matches its selected face.
	 */
	static InventorySnapshotLayout providerLayout(InventorySourceInput input, BlockPos pos, IPlatformInventoryService.Access access) {
		InventorySnapshotLayout layout;
		try { layout = access.snapshotLayout().orElse(null); }
		catch (RuntimeException | LinkageError unsupported) {
			InventorySnapshotDiagnostics.unsupported(input, "provider snapshot layout unavailable");
			return null;
		}
		if (layout == null) return null;
		if (!layout.members().stream().anyMatch(member -> member.position().equals(pos))
			|| (!access.alias().isEmpty() && !layout.layoutId().equals(access.alias().orElseThrow()))
			|| visibleSlots(layout) != access.slots())
			throw new IllegalStateException("provider snapshot layout does not match live slot witness");
		if (!layout.members().stream().flatMap(member -> member.segments().stream())
			.allMatch(segment -> InventorySnapshotSchemas.registered(segment.schemaId()))) {
			InventorySnapshotDiagnostics.unsupported(input, "snapshot schema is not registered");
			return null;
		}
		return layout;
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
	private static void check(BlockEntity entity, ItemStack heldKey) {
		if (!InventoryReadSafety.readable(entity, heldKey)) throw new IllegalStateException("source read denied");
	}
	private static int visibleSlots(InventorySnapshotLayout layout) {
		int result = 0;
		for (var member : layout.members()) for (var segment : member.segments()) result = Math.addExact(result, segment.visibleSlots().size());
		return result;
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
		private final SourceWorld world;
		private final Supplier<Optional<InventorySourceAccess.InventorySnapshot>> testSnapshotCapture;
		private final int originalSlots;
		private final boolean originalStableCursor;
		private IPlatformInventoryService.Access liveAccess;
		private boolean closed;
		Source(InventorySourceInput input, View original, java.util.function.BooleanSupplier onThread,
			java.util.function.Supplier<View> liveView, java.util.function.Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec) {
			this(input, original, onThread, liveView, codec, (SourceWorld) null, null);
		}
		Source(InventorySourceInput input, View original, java.util.function.BooleanSupplier onThread,
			java.util.function.Supplier<View> liveView, java.util.function.Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec,
			Supplier<Optional<InventorySourceAccess.InventorySnapshot>> testSnapshotCapture) {
			this(input, original, onThread, liveView, codec, (SourceWorld) null, testSnapshotCapture);
		}
		/** Test seam: the production view and capture algorithms over injected server-thread world facts. */
		Source(InventorySourceInput input, View original, java.util.function.BooleanSupplier onThread,
			java.util.function.Supplier<View> liveView, java.util.function.Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec,
			SourceWorld world, Supplier<Optional<InventorySourceAccess.InventorySnapshot>> testSnapshotCapture) {
			this.input = input; this.original = original; this.onThread = onThread; this.liveView = liveView; this.codec = codec;
			this.world = world; this.testSnapshotCapture = testSnapshotCapture;
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
					|| !current.slots.equals(original.slots) || !java.util.Objects.equals(current.snapshotLayout, original.snapshotLayout)
					|| current.discoverItems != original.discoverItems) return false;
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
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
			InventorySnapshotLayout layout = original.snapshotLayout;
			if (layout == null) return Optional.empty();
			int visible = visibleSlots(layout);
			long upperBound = InventoryNbtSnapshot.memoryUpperBound(visible, layout.members().size());
			return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return upperBound; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					if (closed || !onThread.getAsBoolean() || !valid()) return Optional.empty();
					if (testSnapshotCapture != null) return testSnapshotCapture.get();
					return captureNbt(layout, upperBound);
				}
			});
		}
		private Optional<InventorySourceAccess.InventorySnapshot> captureNbt(InventorySnapshotLayout layout, long upperBound) {
			if (world == null) return Optional.empty();
			if (!world.onServerThread()) throw new IllegalStateException("snapshot owner or server thread unavailable");
			List<BlockEntity> members = precheckMembers(layout, world);
			if (original.access == null && !layout.layoutId().equals("minecraft:container") && !layout.layoutId().equals("minecraft:double_chest"))
				throw new IllegalStateException("unsupported vanilla snapshot layout");
			if (original.access != null && !layout.layoutId().equals(original.access.alias().orElse(original.identity)))
				throw new IllegalStateException("snapshot layout does not match provider alias");
			for (int index = 0; index < layout.members().size(); index++) {
				var member = layout.members().get(index);
				BlockEntity entity = members.get(index);
				if (entity instanceof Container container && member.segments().size() == 1) {
					var segment = member.segments().get(0);
					int[] current = sidedSlots(container, Direction.valueOf(input.face().name()));
					List<Integer> mapping = java.util.Arrays.stream(current).boxed().toList();
					if (container.getContainerSize() != segment.localSlots() || !segment.visibleSlots().equals(mapping))
						throw new IllegalStateException("snapshot container face mapping changed");
				}
			}
			if (!valid()) throw new IllegalStateException("snapshot source topology changed before save");

			List<Tag> payloads = new ArrayList<>();
			for (int memberIndex = 0; memberIndex < layout.members().size(); memberIndex++) {
				var member = layout.members().get(memberIndex);
				if (member.segments().isEmpty()) continue;
				BlockEntity entity = members.get(memberIndex);
				CompoundTag saved = entity.saveCustomOnly(world.registries());
				for (var segment : member.segments()) {
					Tag payload = original.discoverItems ? discoverItems(saved, entity, segment.localSlots())
						: selectPath(saved, segment.fieldPath(), segment.localSlots());
					if (payload == null) {
						InventorySnapshotDiagnostics.unsupported(input, "inventory field unavailable or ambiguous");
						return Optional.empty();
					}
					if (InventorySnapshotSchemas.ITEMS.equals(segment.schemaId())) validateField(payload, segment.localSlots());
					payloads.add(payload);
				}
			}
			List<BlockEntity> afterSave = precheckMembers(layout, world);
			if (!members.equals(afterSave) || !valid()) throw new IllegalStateException("snapshot source topology changed during save");
			return Optional.of(InventoryNbtSnapshot.create(layout,
				new BlockPos(input.target().x(), input.target().y(), input.target().z()), payloads, world.registries(), upperBound));
		}
		private List<BlockEntity> precheckMembers(InventorySnapshotLayout layout, SourceWorld world) {
			List<BlockEntity> members = new ArrayList<>(layout.members().size());
			for (InventorySnapshotLayout.Member member : layout.members()) {
				BlockPos position = member.position();
				if (!world.isLoaded(position)) throw new IllegalStateException("snapshot member is not loaded");
				BlockState state = world.blockState(position);
				if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(member.blockId()))
					throw new IllegalStateException("snapshot member block changed");
				BlockEntity entity = world.blockEntity(position);
				if (entity == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(member.blockEntityId()))
					throw new IllegalStateException("snapshot member block entity changed");
				check(entity, world.heldKey());
				members.add(entity);
			}
			return members;
		}
		private static Tag discoverItems(CompoundTag saved, BlockEntity entity, int localSlots) {
			boolean root = saved.contains("Items");
			Tag inventory = saved.get("Inventory");
			boolean wrapped = inventory instanceof CompoundTag compound && compound.contains("Items");
			if (root && wrapped) return null;
			if (root) return saved.get("Items");
			if (wrapped) {
				CompoundTag wrapper = (CompoundTag) inventory;
				validateSize(wrapper, localSlots);
				return wrapper.get("Items");
			}
			// Vanilla ShulkerBoxBlockEntity intentionally omits its Items list when
			// empty; no other missing field is evidence of an empty inventory.
			if (entity.getClass() == ShulkerBoxBlockEntity.class
				&& "minecraft:shulker_box".equals(BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString()))
				return new net.minecraft.nbt.ListTag();
			return null;
		}
		private static Tag selectPath(CompoundTag saved, String fieldPath, int localSlots) {
			String[] path = fieldPath.split("\\.");
			Tag current = saved;
			for (String part : path) {
				if (!(current instanceof CompoundTag compound) || !compound.contains(part)) return null;
				current = compound.get(part);
			}
			if (path.length == 2 && "Inventory".equals(path[0]) && current != null
				&& saved.get("Inventory") instanceof CompoundTag wrapper) validateSize(wrapper, localSlots);
			return current;
		}
		private static void validateSize(CompoundTag wrapper, int localSlots) {
			if (!wrapper.contains("Size")) return;
			Tag size = wrapper.get("Size");
			if (!(size instanceof IntTag value) || value.getAsInt() != localSlots)
				throw new IllegalStateException("inventory wrapper size does not match snapshot layout");
		}
		private static void validateField(Tag payload, int localSlots) {
			if (!(payload instanceof net.minecraft.nbt.ListTag list) || (list.size() != 0 && list.getElementType() != Tag.TAG_COMPOUND))
				throw new IllegalStateException("recognized inventory field is malformed");
			if (list.size() > localSlots) throw new IllegalStateException("recognized inventory field exceeds local slots");
		}
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
	/** Production world port: the same server-thread facts read from ServerLevel. */
	private static final class ServerWorld implements SourceWorld {
		private final ServerLevel level;
		private final Supplier<ServerPlayer> ownerSupplier;
		ServerWorld(ServerLevel level, Supplier<ServerPlayer> ownerSupplier) { this.level = level; this.ownerSupplier = ownerSupplier; }
		@Override public boolean isLoaded(BlockPos pos) { return level.isLoaded(pos); }
		@Override public BlockState blockState(BlockPos pos) { return level.getBlockState(pos); }
		@Override public BlockEntity blockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
		@Override public boolean onServerThread() { return level.getServer().isSameThread(); }
		@Override public ItemStack heldKey() {
			ServerPlayer owner = ownerSupplier.get();
			if (owner == null) throw new IllegalStateException("snapshot owner unavailable");
			return owner.getMainHandItem();
		}
		@Override public HolderLookup.Provider registries() { return level.registryAccess(); }
	}
	private static InventoryDomainCodec.Item item(InventoryItemCodec.Catalog catalog, IPlatformInventoryService.Entry entry) {
		if (entry.isEmpty()) return null;
		var encoded = catalog.encode(entry.exemplar());
		var display = encoded.display();
		return new InventoryDomainCodec.Item(encoded.key(), entry.amount(), display.label(), display.displayJson(), display.componentsStripped());
	}
}
