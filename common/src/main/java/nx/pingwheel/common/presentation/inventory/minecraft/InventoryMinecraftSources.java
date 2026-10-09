package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
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
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.source.SourceKey;

/** Ordinary and provider-confirmed blocks. Every view is sided, read-only, loaded and owner-authorized. */
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

	/**
	 * Provider-confirmed physical read input used by the shared view and capture
	 * algorithms. The original target identity stays in {@link InventorySourceInput}
	 * and is never replaced by these physical coordinates.
	 */
	record PhysicalRead(Target.BlockTarget position, BlockFace face, Predicate<BlockPos> memberGate) {
		PhysicalRead {
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(face, "face");
			Objects.requireNonNull(memberGate, "memberGate");
		}
		boolean contains(BlockPos pos) { return pos != null && InventoryMinecraftSources.contains(memberGate, pos); }
	}

	/**
	 * One synchronous provider-confirmed physical binding. It holds the fresh
	 * server-thread world port, the detached descriptor, the membership gate and
	 * the loader capability lookup; it is never retained across operations.
	 */
	record ExternalBinding(SourceWorld world, BlockReadSource descriptor, Predicate<BlockPos> memberGate,
		ProviderAccessLookup providers) {
		ExternalBinding {
			Objects.requireNonNull(world, "world");
			Objects.requireNonNull(descriptor, "descriptor");
			Objects.requireNonNull(memberGate, "memberGate");
			Objects.requireNonNull(providers, "providers");
		}
		boolean contains(BlockPos pos) { return pos != null && InventoryMinecraftSources.contains(memberGate, pos); }
	}

	/** Fresh synchronous provider resolution for one external input; empty means the binding is unavailable. */
	@FunctionalInterface
	interface ExternalBindingResolver {
		Optional<ExternalBinding> resolve();
	}

	private static boolean contains(Predicate<BlockPos> gate, BlockPos pos) {
		try { return gate.test(pos); }
		catch (RuntimeException | LinkageError unavailable) { return false; }
	}

	public static Optional<InventorySourceAccess.Source> resolve(MinecraftServer server, InventorySourceInput input) {
		if (!server.isSameThread()) return Optional.empty();
		var dimension = ResourceLocation.tryParse(input.target().dimensionId());
		ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		ServerPlayer owner = server.getPlayerList().getPlayer(input.readOwner());
		if (level == null || owner == null) return Optional.empty();
		try {
			Supplier<ServerPlayer> ownerSupplier = () -> server.getPlayerList().getPlayer(input.readOwner());
			var catalog = new InventoryItemCodec.Catalog(level.registryAccess());
			Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec = entry -> item(catalog, entry);
			if (input.target() instanceof Target.ExternalBlockTarget external) {
				return resolveExternal(input, () -> bindExternal(server, ownerSupplier, external), server::isSameThread, codec);
			}
			ServerWorld world = new ServerWorld(level, ownerSupplier);
			ProviderAccessLookup providers = (pos, face) -> IPlatformInventoryService.INSTANCE.find(level, pos, face);
			View view = view(world, input, providers);
			return Optional.of(new Source(input, view, server::isSameThread, () -> view(world, input, providers), codec, world, null));
		} catch (RuntimeException | LinkageError unavailable) { return Optional.empty(); }
	}

	/** The server level is looked up per operation so no world handle is retained across ticks. */
	private static Optional<ExternalBinding> bindExternal(MinecraftServer server, Supplier<ServerPlayer> ownerSupplier,
		Target.ExternalBlockTarget external) {
		ResourceLocation dimension = ResourceLocation.tryParse(external.dimensionId());
		ServerLevel current = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		if (current == null || current.getServer() == null || !current.getServer().isSameThread()) return Optional.empty();
		var resolved = external.isCandidate()
			? MinecraftBlockReadSources.resolvePreviewReadSource(current, external)
			: MinecraftBlockReadSources.resolveCommittedReadSource(current, external);
		return resolved.map(source -> new ExternalBinding(new ServerWorld(current, ownerSupplier), source.descriptor(),
			source::containsMember, (pos, face) -> IPlatformInventoryService.INSTANCE.find(current, pos, face, source::containsMember)));
	}

	/**
	 * Production/test seam for provider-confirmed external sources. The resolver
	 * runs again before every operation; only the detached descriptor and
	 * evidence are retained.
	 */
	static Optional<InventorySourceAccess.Source> resolveExternal(InventorySourceInput input,
		ExternalBindingResolver bindings, BooleanSupplier onThread,
		Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec) {
		if (!(input.target() instanceof Target.ExternalBlockTarget) || !onThread.getAsBoolean()) return Optional.empty();
		try {
			ExternalBinding first = bindings.resolve().orElse(null);
			if (first == null || !InventorySourceInput.sameBinding(input.target(), first.descriptor().target())) return Optional.empty();
			View view = view(first.world(), input, new PhysicalRead(first.descriptor().blockTarget(), input.face(), first::contains), first.providers());
			return Optional.of(new ExternalSource(input, first, bindings, onThread, codec, view));
		} catch (RuntimeException | LinkageError unavailable) { return Optional.empty(); }
	}

	static View view(SourceWorld world, InventorySourceInput input, ProviderAccessLookup providers) {
		Target.BlockTarget target = input.ordinaryTarget().orElseThrow(() ->
			new IllegalStateException("external input requires a provider-confirmed physical read"));
		return view(world, input, new PhysicalRead(target, input.face(), world::isLoaded), providers);
	}

	static View view(SourceWorld world, InventorySourceInput input, PhysicalRead physical, ProviderAccessLookup providers) {
		var target = physical.position();
		BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
		if (!physical.contains(pos) || !world.isLoaded(pos)) throw new IllegalStateException("unloaded inventory");
		if (!physical.contains(pos)) throw new IllegalStateException("inventory root outside the read scope");
		BlockState state = world.blockState(pos);
		if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(target.blockRegistryId()))
			throw new IllegalStateException("original block unavailable");
		if (!physical.contains(pos)) throw new IllegalStateException("inventory root outside the read scope");
		BlockEntity entity = world.blockEntity(pos);
		check(entity, world.heldKey());
		Direction face = Direction.valueOf(physical.face().name());
		List<BlockEntity> entities = new ArrayList<>();
		List<Slot> slots = new ArrayList<>();
		String identity = VanillaInventorySource.simpleBlockId(target.dimensionId(), pos, target.blockRegistryId());
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
			if (!physical.contains(partner) || !world.isLoaded(partner)) throw new IllegalStateException("unloaded chest partner");
			if (!physical.contains(partner)) throw new IllegalStateException("chest partner outside the read scope");
			BlockState other = world.blockState(partner);
			if (other.getBlock() != state.getBlock() || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
				|| other.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
				|| other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
				|| !partner.relative(ChestBlock.getConnectedDirection(other)).equals(pos))
				throw new IllegalStateException("invalid chest pairing");
			if (!physical.contains(partner)) throw new IllegalStateException("chest partner outside the read scope");
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
				BlockPos memberPos = half.getBlockPos().immutable();
				if (!physical.contains(memberPos)) throw new IllegalStateException("inventory member outside the read scope");
				int[] mapping = sidedSlots(container, face);
				for (int slot : mapping) slots.add(new Slot(container, slot));
				if (slots.size() > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("container view exceeds finite bound");
				if (!physical.contains(memberPos)) throw new IllegalStateException("inventory member outside the read scope");
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
		if (!physical.contains(pos)) throw new IllegalStateException("inventory capability outside the read scope");
		IPlatformInventoryService.Access access = providers.find(pos, face).orElseThrow();
		if (input.target() instanceof Target.ExternalBlockTarget) {
			if (!physical.contains(pos)) throw new IllegalStateException("inventory capability outside the read scope after lookup");
			access = access.guardedBy(() -> physical.contains(pos));
			if (!access.valid()) throw new IllegalStateException("inventory capability unavailable");
		}
		String accessIdentity = access.alias().orElse(identity);
		InventorySnapshotLayout layout = providerLayout(input, pos, access, physical::contains);
		return new View(accessIdentity, List.copyOf(entities), List.of(), access, layout, false);
	}

	/**
	 * Explicit provider layout decision. A structurally inconsistent layout is a
	 * hard refusal: only a genuinely absent or unregistered layout keeps the
	 * existing live fallback, because a live slot count alone cannot prove that
	 * an arbitrary provider's NBT range matches its selected face.
	 */
	static InventorySnapshotLayout providerLayout(InventorySourceInput input, BlockPos pos, IPlatformInventoryService.Access access) {
		return providerLayout(input, pos, access, ignored -> true);
	}
	private static InventorySnapshotLayout providerLayout(InventorySourceInput input, BlockPos pos, IPlatformInventoryService.Access access,
		Predicate<BlockPos> memberGate) {
		InventorySnapshotLayout layout;
		try { layout = access.snapshotLayout().orElse(null); }
		catch (RuntimeException | LinkageError unsupported) {
			InventorySnapshotDiagnostics.unsupported(input, "provider snapshot layout unavailable");
			return null;
		}
		if (layout == null) return null;
		if (layout.members().stream().anyMatch(member -> !contains(memberGate, member.position())))
			throw new IllegalStateException("inventory layout member outside the read scope");
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
					if (world == null) return Optional.empty();
					return captureNbt(input, new PhysicalRead(input.ordinaryTarget().orElseThrow(), input.face(), ignored -> true),
						original, world, Source.this::valid, upperBound);
				}
			});
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

	/**
	 * Shared detached NBT capture over one admitted view. The caller revalidates
	 * the current physical binding before and after the contiguous save; every
	 * member is gated before its state, block entity, capability or content read.
	 */
	static Optional<InventorySourceAccess.InventorySnapshot> captureNbt(InventorySourceInput input, PhysicalRead physical,
		View view, SourceWorld world, BooleanSupplier valid, long upperBound) {
		InventorySnapshotLayout layout = view.snapshotLayout;
		if (world == null || layout == null) return Optional.empty();
		if (!world.onServerThread()) throw new IllegalStateException("snapshot owner or server thread unavailable");
		List<BlockEntity> members = precheckMembers(layout, world, physical.memberGate());
		if (view.access == null && !layout.layoutId().equals("minecraft:container") && !layout.layoutId().equals("minecraft:double_chest"))
			throw new IllegalStateException("unsupported vanilla snapshot layout");
		if (view.access != null && !layout.layoutId().equals(view.access.alias().orElse(view.identity)))
			throw new IllegalStateException("snapshot layout does not match provider alias");
		for (int index = 0; index < layout.members().size(); index++) {
			var member = layout.members().get(index);
			BlockEntity entity = members.get(index);
			if (!physical.contains(member.position())) throw new IllegalStateException("snapshot member outside the read scope");
			if (entity instanceof Container container && member.segments().size() == 1) {
				var segment = member.segments().get(0);
				int[] current = sidedSlots(container, Direction.valueOf(input.face().name()));
				List<Integer> mapping = java.util.Arrays.stream(current).boxed().toList();
				if (container.getContainerSize() != segment.localSlots() || !segment.visibleSlots().equals(mapping))
					throw new IllegalStateException("snapshot container face mapping changed");
			}
		}
		if (!valid.getAsBoolean()) throw new IllegalStateException("snapshot source topology changed before save");

		List<Tag> payloads = new ArrayList<>();
		for (int memberIndex = 0; memberIndex < layout.members().size(); memberIndex++) {
			var member = layout.members().get(memberIndex);
			if (member.segments().isEmpty()) continue;
			BlockEntity entity = members.get(memberIndex);
			if (!physical.contains(member.position()))
				throw new IllegalStateException("snapshot member outside the read scope before save");
			CompoundTag saved = entity.saveCustomOnly(world.registries());
			for (var segment : member.segments()) {
				Tag payload = view.discoverItems ? discoverItems(saved, entity, segment.localSlots())
					: selectPath(saved, segment.fieldPath(), segment.localSlots());
				if (payload == null) {
					InventorySnapshotDiagnostics.unsupported(input, "inventory field unavailable or ambiguous");
					return Optional.empty();
				}
				if (InventorySnapshotSchemas.ITEMS.equals(segment.schemaId())) validateField(payload, segment.localSlots());
				payloads.add(payload);
			}
		}
		List<BlockEntity> afterSave = precheckMembers(layout, world, physical.memberGate());
		if (!members.equals(afterSave) || !valid.getAsBoolean()) throw new IllegalStateException("snapshot source topology changed during save");
		return Optional.of(InventoryNbtSnapshot.create(layout,
			new BlockPos(physical.position().x(), physical.position().y(), physical.position().z()), payloads, world.registries(), upperBound));
	}

	private static List<BlockEntity> precheckMembers(InventorySnapshotLayout layout, SourceWorld world, Predicate<BlockPos> memberGate) {
		List<BlockEntity> members = new ArrayList<>(layout.members().size());
		for (InventorySnapshotLayout.Member member : layout.members()) {
			BlockPos position = member.position();
			if (!contains(memberGate, position) || !world.isLoaded(position)) throw new IllegalStateException("snapshot member is not loaded");
			if (!contains(memberGate, position)) throw new IllegalStateException("snapshot member outside the read scope");
			BlockState state = world.blockState(position);
			if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(member.blockId()))
				throw new IllegalStateException("snapshot member block changed");
			if (!contains(memberGate, position)) throw new IllegalStateException("snapshot member outside the read scope");
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

	/**
	 * Provider-confirmed external source. Every operation re-resolves the current
	 * candidate source or committed lease point, compares the detached descriptor
	 * scope, physical position and topology, and only then reads. No level, block
	 * entity, provider object or membership closure is retained across ticks.
	 */
	static final class ExternalSource implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final BlockReadSource descriptor;
		private final ExternalBindingResolver bindings;
		private final BooleanSupplier onThread;
		private final Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec;
		private final String identity;
		private final InventorySnapshotLayout layout;
		private final boolean discoverItems;
		private final String accessAlias;
		private final int slots;
		private final boolean stableCursor;
		private final boolean capability;
		private boolean closed;

		ExternalSource(InventorySourceInput input, ExternalBinding first, ExternalBindingResolver bindings,
			BooleanSupplier onThread, Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec, View view) {
			this.input = input;
			this.descriptor = first.descriptor();
			this.bindings = bindings;
			this.onThread = onThread;
			this.codec = codec;
			this.identity = view.identity;
			this.layout = view.snapshotLayout;
			this.discoverItems = view.discoverItems;
			this.accessAlias = view.access == null ? null : view.access.alias().orElse(null);
			this.slots = view.access == null ? view.slots.size() : view.access.slots();
			this.stableCursor = view.access == null || view.access.stableCursor();
			this.capability = view.access != null;
			requireMembers(new Current(first, view));
		}

		/** Provider-confirmed sub-level scope plus the canonical alias; never the raw locator. */
		@Override public SourceKey key() {
			return new SourceKey(descriptor.providerId(), "block_inventory",
				scopeIdentity(descriptor.dimensionId(), descriptor.subLevelId(), identity), input.viewKey());
		}
		@Override public boolean valid() {
			if (closed || !onThread.getAsBoolean()) return false;
			try { current(); return true; }
			catch (RuntimeException | LinkageError unavailable) { return false; }
		}
		@Override public boolean stableCursor() { return stableCursor; }
		@Override public int slots() { return slots; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public InventoryDomainCodec.Item read(int slot) {
			Current live = current();
			View current = live.view();
			if (current.access != null) {
				requireMembers(live);
				var entry = current.access.guardedBy(() -> membersAllowed(live)).read(slot);
				requireMembers(live);
				return codec.apply(entry);
			}
			Slot mapping = current.slots.get(slot);
			if (!(mapping.container instanceof BlockEntity member) || !live.binding().contains(member.getBlockPos()))
				throw new IllegalStateException("inventory slot outside the read scope");
			ItemStack observed = mapping.container.getItem(mapping.index);
			requireMembers(live);
			ItemStack stack = observed.copy();
			return stack.isEmpty() ? null : codec.apply(new IPlatformInventoryService.Entry(stack.copyWithCount(1), stack.getCount()));
		}
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			Current live = current();
			View current = live.view();
			if (current.access == null) throw new UnsupportedOperationException("no enumeration");
			requireMembers(live);
			var observed = current.access.guardedBy(() -> membersAllowed(live)).observe(limit);
			requireMembers(live);
			List<InventoryDomainCodec.Item> values = new ArrayList<>();
			for (var entry : observed.entries()) values.add(codec.apply(entry));
			return new InventorySourceAccess.Page(values, observed.complete());
		}
		private boolean membersAllowed(Current live) {
			var position = live.binding().descriptor().blockTarget();
			if (!live.binding().contains(new BlockPos(position.x(), position.y(), position.z()))) return false;
			return live.view().snapshotLayout == null || live.view().snapshotLayout.members().stream()
				.allMatch(member -> live.binding().contains(member.position()));
		}
		private void requireMembers(Current live) {
			if (!membersAllowed(live)) throw new IllegalStateException("inventory handoff outside the read scope");
		}
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
			InventorySnapshotLayout captured = layout;
			if (captured == null) return Optional.empty();
			long upperBound = InventoryNbtSnapshot.memoryUpperBound(visibleSlots(captured), captured.members().size());
			return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return upperBound; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					if (closed || !onThread.getAsBoolean()) return Optional.empty();
					Current current = current();
					BlockReadSource live = current.binding().descriptor();
					return captureNbt(input, new PhysicalRead(live.blockTarget(), input.face(), current.binding()::contains),
						current.view(), current.binding().world(), ExternalSource.this::valid, upperBound);
				}
			});
		}
		@Override public void close() { closed = true; }

		private Current current() {
			if (closed) throw new IllegalStateException("closed inventory source");
			if (!onThread.getAsBoolean()) throw new IllegalStateException("inventory source is not on the server thread");
			ExternalBinding binding = bindings.resolve().orElseThrow(() -> new IllegalStateException("provider read source unavailable"));
			if (!sameDescriptor(binding.descriptor())) throw new IllegalStateException("provider read binding changed");
			View view = view(binding.world(), input, new PhysicalRead(binding.descriptor().blockTarget(), input.face(), binding::contains), binding.providers());
			if (!sameTopology(view)) throw new IllegalStateException("provider read topology changed");
			Current current = new Current(binding, view);
			requireMembers(current);
			return current;
		}
		private boolean sameDescriptor(BlockReadSource current) {
			return descriptor.providerId().equals(current.providerId())
				&& descriptor.subLevelId().equals(current.subLevelId())
				&& descriptor.blockTarget().equals(current.blockTarget())
				&& InventorySourceInput.sameBinding(descriptor.target(), current.target());
		}
		private boolean sameTopology(View current) {
			if (current.access != null && !current.access.valid()) return false;
			if (!identity.equals(current.identity) || !Objects.equals(layout, current.snapshotLayout)
				|| discoverItems != current.discoverItems || capability != (current.access != null)) return false;
			String alias = current.access == null ? null : current.access.alias().orElse(null);
			if (!Objects.equals(accessAlias, alias)) return false;
			int currentSlots = current.access == null ? current.slots.size() : current.access.slots();
			if (currentSlots != slots) return false;
			return (current.access == null || current.access.stableCursor()) == stableCursor;
		}
	}
	/** Length-framed scope hashing keeps aliases bounded without delimiter or cross-world collisions. */
	private static String scopeIdentity(String dimension, String subLevel, String alias) {
		try {
			var digest = java.security.MessageDigest.getInstance("SHA-256");
			for (String part : List.of(dimension, subLevel, alias)) {
				byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
				digest.update(bytes);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
	private record Current(ExternalBinding binding, View view) {}

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
