package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
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
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.InventorySelection;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.util.RateLimitedDiagnostics;

/** Ordinary and provider-confirmed blocks. Every view is sided, read-only, loaded and owner-authorized. */
public final class InventoryMinecraftSources {
	private InventoryMinecraftSources() {}
	/** Production compatibility conversion also forwards the consuming read grant. */
	private interface AdmittedEntryCodec extends Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> {
		InventoryDomainCodec.Item apply(IPlatformInventoryService.Entry entry, InventoryCatalogAdmission admission);
	}
	private static InventoryDomainCodec.Item decode(Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec,
		IPlatformInventoryService.Entry entry, InventoryCatalogAdmission admission) {
		return admission != null && codec instanceof AdmittedEntryCodec admitted ? admitted.apply(entry, admission) : codec.apply(entry);
	}
	/**
	 * Operation-local prefix, never a provider-internals estimate. Before entering
	 * observeResident and after its return the prefix is exact. While inside it,
	 * only a failure in our synchronous visitor proves that no later view ran.
	 * Model: object40 + int4 + booleans2 =46B; the visitor adds at most one8B
	 * capture, and one failed operation may add CursorlessReadFailure88B. These
	 * <=142B belong to the held fixed PAGE/SELECT buffer, not Catalog's72B grant.
	 */
	private static final class CursorlessProgress {
		private int attempted;
		private boolean exact = true, present;
		void enteringProvider() { exact = false; }
		void attemptingVisit() { attempted++; }
		void returned(int visited) { attempted = visited; exact = true; }
		InventorySourceAccess.CursorlessReadFailure visitorFailed(Throwable failure) {
			return new InventorySourceAccess.CursorlessReadFailure(attempted, failure);
		}
		void failed(Throwable failure) {
			if (exact && !(failure instanceof InventorySourceAccess.CursorlessReadFailure))
				throw new InventorySourceAccess.CursorlessReadFailure(attempted, failure);
		}
	}
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
		/** Native graph certificate by default; headless ports may audit their own fixture graph. */
		default boolean audited(Container container) { return InventoryContainerReads.audited(container, ignored -> true); }
		/** Same certificate through caller-reused finite scratch; ports without one keep the single-shot path. */
		default boolean audited(Container container, InventoryContainerReads.Scratch scratch) { return audited(container); }
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
		boolean contains(BlockPos pos) { return pos != null && InventoryMinecraftSources.contains(memberGate, pos, position, face); }
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
		boolean contains(BlockPos pos) { return pos != null && InventoryMinecraftSources.contains(memberGate, pos, descriptor, null); }
	}

	/** Fresh synchronous provider resolution for one external input; empty means the binding is unavailable. */
	@FunctionalInterface
	interface ExternalBindingResolver {
		Optional<ExternalBinding> resolve();
	}

	private static boolean contains(Predicate<BlockPos> gate, BlockPos pos) {
		return contains(gate, pos, null, null);
	}
	private static boolean contains(Predicate<BlockPos> gate, BlockPos pos, Object binding, BlockFace face) {
		try { return gate.test(pos); }
		catch (RuntimeException | LinkageError unavailable) {
			if (!(unavailable instanceof InventorySourceUnavailableException) && !(unavailable instanceof InventoryItemBudget.CaptureLimitException))
				RateLimitedDiagnostics.warn("inventory-member-scope", gate.getClass().getName(),
					() -> "position=" + pos + " binding=" + binding + " face=" + face + " gateClass=" + gate.getClass().getName(), unavailable);
			return false;
		}
	}

	/** The one validated effective display cap range; every catalogue and snapshot ceiling passes here. */
	static int requireDisplayBytes(int displayBytes) {
		if (displayBytes < InventoryItemCodec.minimumDisplayBytes() || displayBytes > InventoryLimits.MAX_DISPLAY_BYTES)
			throw new IllegalArgumentException("display byte cap is out of range");
		return displayBytes;
	}

	/** Existing callers keep the backward-compatible default 4 KiB display projection. */
	public static Optional<InventorySourceAccess.Source> resolve(MinecraftServer server, InventorySourceInput input) {
		return resolve(server, input, InventoryItemCodec.MAX_DISPLAY_BYTES);
	}

	/**
	 * New live and snapshot observations build their variant catalogue at the
	 * authoritative effective display ceiling. The ceiling is a validated
	 * caller-supplied value, never a global static or client preference.
	 */
	public static Optional<InventorySourceAccess.Source> resolve(MinecraftServer server, InventorySourceInput input, int displayBytes) {
		if (!server.isSameThread()) return Optional.empty();
		requireDisplayBytes(displayBytes);
		var dimension = ResourceLocation.tryParse(input.target().dimensionId());
		ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		ServerPlayer owner = server.getPlayerList().getPlayer(input.readOwner());
		if (level == null || owner == null) return Optional.empty();
		try {
			Supplier<ServerPlayer> ownerSupplier = () -> server.getPlayerList().getPlayer(input.readOwner());
			var catalog = new InventoryItemCodec.Catalog(level.registryAccess(), displayBytes);
			Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec = new AdmittedEntryCodec() {
				@Override public InventoryDomainCodec.Item apply(IPlatformInventoryService.Entry entry) { return item(catalog, entry, null); }
				@Override public InventoryDomainCodec.Item apply(IPlatformInventoryService.Entry entry, InventoryCatalogAdmission admission) { return item(catalog, entry, admission); }
			};
			if (input.target() instanceof Target.ExternalBlockTarget external) {
				return resolveExternal(input, () -> bindExternal(server, ownerSupplier, external), server::isSameThread, codec,
					displayBytes, catalog::tightenDisplay, catalog::reprojectionSummary);
			}
			ServerWorld world = new ServerWorld(level, ownerSupplier);
			ProviderAccessLookup providers = (pos, face) -> IPlatformInventoryService.INSTANCE.find(level, pos, face);
			View view = view(world, input, providers);
			return Optional.of(new Source(input, view, server::isSameThread, () -> view(world, input,
				new PhysicalRead(input.ordinaryTarget().orElseThrow(), input.face(), world::isLoaded), providers, view.snapshotLayout, true), codec, world, null,
				displayBytes, catalog::tightenDisplay, catalog::reprojectionSummary));
		} catch (RuntimeException | LinkageError unavailable) {
			InventorySnapshotDiagnostics.failure("inventory-source-resolve", input, null, () -> "displayBytes=" + displayBytes, unavailable);
			return Optional.empty();
		}
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
		return resolveExternal(input, bindings, onThread, codec, InventoryItemCodec.MAX_DISPLAY_BYTES, ignored -> {},
			ignored -> InventoryReprojectionSummary.EMPTY);
	}

	static Optional<InventorySourceAccess.Source> resolveExternal(InventorySourceInput input,
		ExternalBindingResolver bindings, BooleanSupplier onThread,
		Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec,
		int displayBytes, java.util.function.IntConsumer tightenDisplay) {
		return resolveExternal(input, bindings, onThread, codec, displayBytes, tightenDisplay,
			ignored -> InventoryReprojectionSummary.UNKNOWN);
	}

	/** The summary describes only the supplied compatibility hook, not the separately owned resident catalogue. */
	static Optional<InventorySourceAccess.Source> resolveExternal(InventorySourceInput input,
		ExternalBindingResolver bindings, BooleanSupplier onThread,
		Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec,
		int displayBytes, java.util.function.IntConsumer tightenDisplay, IntFunction<InventoryReprojectionSummary> reprojectionSummary) {
		if (!(input.target() instanceof Target.ExternalBlockTarget) || !onThread.getAsBoolean()) return Optional.empty();
		requireDisplayBytes(displayBytes);
		try {
			ExternalBinding first = bindings.resolve().orElse(null);
			if (first == null || !InventorySourceInput.sameBinding(input.target(), first.descriptor().target())) return Optional.empty();
			View view = view(first.world(), input, new PhysicalRead(first.descriptor().blockTarget(), input.face(), first::contains), first.providers());
			return Optional.of(new ExternalSource(input, first, bindings, onThread, codec, view, displayBytes, tightenDisplay, reprojectionSummary));
		} catch (RuntimeException | LinkageError unavailable) {
			InventorySnapshotDiagnostics.failure("inventory-external-resolve", input, bindings, () -> "displayBytes=" + displayBytes, unavailable);
			return Optional.empty();
		}
	}

	static View view(SourceWorld world, InventorySourceInput input, ProviderAccessLookup providers) {
		Target.BlockTarget target = input.ordinaryTarget().orElseThrow(() ->
			new IllegalStateException("external input requires a provider-confirmed physical read"));
		return view(world, input, new PhysicalRead(target, input.face(), world::isLoaded), providers);
	}

	static View view(SourceWorld world, InventorySourceInput input, PhysicalRead physical, ProviderAccessLookup providers) {
		return view(world, input, physical, providers, null, false);
	}
	/** Reacquisition knows the admitted shape before any owned expanded metadata processing. */
	static View view(SourceWorld world, InventorySourceInput input, PhysicalRead physical, ProviderAccessLookup providers,
		InventorySnapshotLayout expected, boolean enforceShape) {
		var target = physical.position();
		BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
		if (!physical.contains(pos) || !world.isLoaded(pos)) throw new InventorySourceUnavailableException("unloaded inventory");
		if (!physical.contains(pos)) throw new InventorySourceUnavailableException("inventory root outside the read scope");
		BlockState state = world.blockState(pos);
		if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(target.blockRegistryId()))
			throw new InventorySourceUnavailableException("original block unavailable");
		if (!physical.contains(pos)) throw new InventorySourceUnavailableException("inventory root outside the read scope");
		BlockEntity entity = world.blockEntity(pos);
		if (enforceShape && !ordinaryLayout(expected) && entity instanceof Container)
			throw new InventorySourceUnavailableException("inventory provider kind changed");
		if (enforceShape && ordinaryLayout(expected) && !(entity instanceof Container))
			throw new InventorySourceUnavailableException("inventory container kind changed");
		if (enforceShape && (expected == null || vault(expected)) && entity instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity)
			throw new InventorySourceUnavailableException("inventory member lock kind changed");
		check(entity, world.heldKey());
		Direction face = Direction.valueOf(physical.face().name());
		List<BlockEntity> entities = new ArrayList<>();
		List<Slot> slots = new ArrayList<>();
		String identity = VanillaInventorySource.simpleBlockId(target.dimensionId(), pos, target.blockRegistryId());
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
			if (!physical.contains(partner) || !world.isLoaded(partner)) throw new InventorySourceUnavailableException("unloaded chest partner");
			if (!physical.contains(partner)) throw new InventorySourceUnavailableException("chest partner outside the read scope");
			BlockState other = world.blockState(partner);
			if (other.getBlock() != state.getBlock() || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
				|| other.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
				|| other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
				|| !partner.relative(ChestBlock.getConnectedDirection(other)).equals(pos))
				throw new InventorySourceUnavailableException("invalid chest pairing");
			if (!physical.contains(partner)) throw new InventorySourceUnavailableException("chest partner outside the read scope");
			BlockEntity otherEntity = world.blockEntity(partner);
			check(otherEntity, world.heldKey());
			if (!(entity instanceof ChestBlockEntity) || !(otherEntity instanceof ChestBlockEntity))
				throw new InventorySourceUnavailableException("invalid chest halves");
			boolean first = compare(pos, partner) < 0;
			entities.add(first ? entity : otherEntity);
			entities.add(first ? otherEntity : entity);
			identity = VanillaInventorySource.chestAlias(target.dimensionId(), pos, partner, target.blockRegistryId());
		} else entities.add(entity);
		if (entity instanceof Container) {
			List<InventorySnapshotLayout.Member> members = new ArrayList<>();
			for (BlockEntity half : entities) {
				if (!(half instanceof Container container)) throw new InventorySourceUnavailableException("missing container half");
				BlockPos memberPos = half.getBlockPos().immutable();
				if (!physical.contains(memberPos)) throw new InventorySourceUnavailableException("inventory member outside the read scope");
				int[] mapping = sidedSlots(container, face);
				for (int slot : mapping) slots.add(new Slot(container, slot));
				if (slots.size() > InventorySourceAccess.MAX_SLOTS) throw new IllegalStateException("container view exceeds finite bound");
				if (!physical.contains(memberPos)) throw new InventorySourceUnavailableException("inventory member outside the read scope");
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
		if (!physical.contains(pos)) throw new InventorySourceUnavailableException("inventory capability outside the read scope");
		IPlatformInventoryService.Access access = providers.find(pos, face).orElseThrow(() -> new InventorySourceUnavailableException("inventory capability absent"));
		if (input.target() instanceof Target.ExternalBlockTarget) {
			if (!physical.contains(pos)) throw new InventorySourceUnavailableException("inventory capability outside the read scope after lookup");
			access = access.guardedBy(() -> physical.contains(pos));
			if (!access.valid()) throw new InventorySourceUnavailableException("inventory capability unavailable");
		}
		String accessIdentity = access.alias().orElse(identity);
		InventorySnapshotLayout layout = providerLayout(input, pos, access, physical::contains, false, expected, enforceShape);
		InventorySnapshotLayout admittedLayout = layout;
		List<BlockEntity> admittedMembers = layout == null ? List.copyOf(entities) : precheckMembers(layout, world, physical.memberGate());
		var admittedBlocks = new net.minecraft.world.level.block.Block[admittedMembers.size()];
		for (int index = 0; index < admittedBlocks.length; index++) admittedBlocks[index] = admittedMembers.get(index).getBlockState().getBlock();
		access = access.scopedResidents(new IPlatformInventoryService.ResidentScope() {
			private final InventoryContainerReads.Scratch scopeScratch = new InventoryContainerReads.Scratch();
			private final Container[] mappedContainers = new Container[32];
			private final List<?>[] mappedSlots = new List<?>[32];
			private int mappedCount;
			@Override public Direction face() { return face; }
			@Override public boolean valid() {
				try {
					if (admittedLayout != null) {
						for (int i = 0; i < admittedMembers.size(); i++) {
							BlockEntity member = admittedMembers.get(i);
							BlockPos memberPos = admittedLayout.members().get(i).position();
							if (!physical.contains(memberPos) || !world.isLoaded(memberPos) || world.blockEntity(memberPos) != member
								|| world.blockState(memberPos).getBlock() != admittedBlocks[i]
								|| !InventoryReadSafety.readable(member, world.heldKey())) return false;
							if (member instanceof Container container) {
								var segments = admittedLayout.members().get(i).segments();
								if (segments.size() != 1 || segments.getFirst().localSlots() != container.getContainerSize()
									|| !InventoryContainerReads.mappingEquals(container, face, segments.getFirst().visibleSlots())) return false;
							}
						}
						return true;
					}
					return physical.contains(pos) && world.isLoaded(pos) && world.blockEntity(pos) == entity
						&& InventoryReadSafety.readable(entity, world.heldKey());
				} catch (InventoryItemBudget.CaptureLimitException exhausted) { throw exhausted; }
				catch (RuntimeException | LinkageError unavailable) {
					InventorySnapshotDiagnostics.failure("inventory-resident-scope-valid", input, entity,
						() -> "position=" + pos + " layout=" + (admittedLayout == null ? "live" : admittedLayout.layoutId()), unavailable);
					return false;
				}
			}
			@Override public boolean allows(Container container) {
				if (!valid()) return false;
				if (container instanceof net.minecraft.world.CompoundContainer) return true; // children independently checked
				// A layout-less capability can own non-world backing storage. Keep
				// that established LIVE route under the root's authority; it cannot
				// admit a new position-bearing BE or create a detached layout.
				if (admittedLayout == null && !(container instanceof BlockEntity)) return true;
				for (int index = 0; index < admittedMembers.size(); index++) if (admittedMembers.get(index) == container) return true;
				return false;
			}
			@Override public boolean audited(Container container) {
				return InventoryContainerReads.audited(container, this::allows, scopeScratch);
			}
			@Override public List<Integer> slots(Container container) {
				if (!valid() || admittedLayout == null) return null;
				InventoryContainerReads.requireScope(container, this::allows, scopeScratch);
				for (int index = 0; index < mappedCount; index++) if (mappedContainers[index] == container) {
					@SuppressWarnings("unchecked") var mapping = (List<Integer>) mappedSlots[index];
					return mapping; // immutable frozen witness, never rebuild/box all slots per read
				}
				var mapping = InventoryContainerReads.scopedSlots(container, this::allows, this::localSlots, scopeScratch);
				if (mappedCount == mappedContainers.length) throw new IllegalStateException("inventory wrapper mapping bound");
				mappedContainers[mappedCount] = container; mappedSlots[mappedCount++] = mapping;
				return mapping;
			}
			private List<Integer> localSlots(Container container) {
				for (int i = 0; i < admittedMembers.size(); i++) if (admittedMembers.get(i) == container) {
					var segments = admittedLayout.members().get(i).segments();
					return segments.size() == 1 ? segments.getFirst().visibleSlots() : null;
				}
				return null;
			}
		});
		if (layout != null && visibleSlots(layout) != access.slots()) throw new InventorySourceUnavailableException("provider layout changed scoped slot witness");
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
		return providerLayout(input, pos, access, memberGate, true);
	}
	private static InventorySnapshotLayout providerLayout(InventorySourceInput input, BlockPos pos, IPlatformInventoryService.Access access,
		Predicate<BlockPos> memberGate, boolean checkSlots) {
		return providerLayout(input, pos, access, memberGate, checkSlots, null, false);
	}
	private static InventorySnapshotLayout providerLayout(InventorySourceInput input, BlockPos pos, IPlatformInventoryService.Access access,
		Predicate<BlockPos> memberGate, boolean checkSlots, InventorySnapshotLayout expected, boolean enforceShape) {
		InventorySnapshotLayout layout;
		try { layout = access.snapshotLayout().orElse(null); }
		catch (InventoryItemBudget.CaptureLimitException exhausted) { throw exhausted; }
		catch (RuntimeException | LinkageError unsupported) {
			InventorySnapshotDiagnostics.failure("inventory-provider-snapshot-layout", input, access, () -> "position=" + pos, unsupported);
			return null;
		}
		// The provider callback is the explicit trust boundary. Its immutable DTO
		// is not permission to allocate/precheck an expanded member scope. Record
		// equality short-circuits list sizes before visiting an enlarged shape.
		boolean unsupportedLayout = false;
		if (enforceShape && expected == null && layout != null) {
			// An explicitly unsupported schema was already normalized to the LIVE
			// route on initial discovery. It must stay LIVE, not turn into an
			// invalidity merely because the raw optional descriptor is present.
			boolean registered = true;
			for (int memberIndex = 0; memberIndex < layout.members().size(); memberIndex++) {
				var member = layout.members().get(memberIndex);
				if (!contains(memberGate, member.position())) throw new InventorySourceUnavailableException("inventory layout member outside the read scope");
				for (int segmentIndex = 0; segmentIndex < member.segments().size(); segmentIndex++)
					registered &= InventorySnapshotSchemas.registered(member.segments().get(segmentIndex).schemaId());
			}
			unsupportedLayout = !registered;
		}
		if (enforceShape && !unsupportedLayout) InventoryValidationBudget.requireLayout(expected, layout);
		if (layout == null) return null;
		if (layout.members().stream().anyMatch(member -> !contains(memberGate, member.position())))
			throw new InventorySourceUnavailableException("inventory layout member outside the read scope");
		if (!layout.members().stream().anyMatch(member -> member.position().equals(pos))
			|| (!access.alias().isEmpty() && !layout.layoutId().equals(access.alias().orElseThrow()))
			|| checkSlots && visibleSlots(layout) != access.slots())
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
		if (source == null && container instanceof WorldlyContainer) throw new InventorySourceUnavailableException("missing sided mapping");
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
		if (!InventoryReadSafety.readable(entity, heldKey)) throw new InventorySourceUnavailableException("source read denied");
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
		private final java.util.function.IntConsumer tightenDisplay;
		private final IntFunction<InventoryReprojectionSummary> compatibilityReprojectionSummary;
		private int displayBytes;
		private IPlatformInventoryService.Access liveAccess;
		private InventoryItemCodec.Catalog residentCatalog;
		private InventoryValidationBudget.Allowance validation;
		private final InventoryValidationBudget.Cost validationViewCost;
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
			this(input, original, onThread, liveView, codec, world, testSnapshotCapture,
				InventoryItemCodec.MAX_DISPLAY_BYTES, ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY);
		}
		/** Legacy trusted hook: opaque mutating callbacks need an explicit summary before Runtime can tighten them. */
		Source(InventorySourceInput input, View original, java.util.function.BooleanSupplier onThread,
			java.util.function.Supplier<View> liveView, java.util.function.Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec,
			SourceWorld world, Supplier<Optional<InventorySourceAccess.InventorySnapshot>> testSnapshotCapture,
			int displayBytes, java.util.function.IntConsumer tightenDisplay) {
			this(input, original, onThread, liveView, codec, world, testSnapshotCapture, displayBytes, tightenDisplay,
				ignored -> InventoryReprojectionSummary.UNKNOWN);
		}
		/** Production seam: a pure cached summary accompanies the caller-owned compatibility catalogue hook. */
		Source(InventorySourceInput input, View original, java.util.function.BooleanSupplier onThread,
			java.util.function.Supplier<View> liveView, java.util.function.Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec,
			SourceWorld world, Supplier<Optional<InventorySourceAccess.InventorySnapshot>> testSnapshotCapture,
			int displayBytes, java.util.function.IntConsumer tightenDisplay, IntFunction<InventoryReprojectionSummary> reprojectionSummary) {
			this.input = input; this.original = original; this.onThread = onThread; this.liveView = liveView; this.codec = codec;
			this.world = world; this.testSnapshotCapture = testSnapshotCapture;
			this.validationViewCost = InventoryValidationBudget.viewValidation(original.snapshotLayout, vault(original.snapshotLayout), lockMembers(original.snapshotLayout));
			this.displayBytes = requireDisplayBytes(displayBytes);
			this.tightenDisplay = Objects.requireNonNull(tightenDisplay, "tightenDisplay");
			this.compatibilityReprojectionSummary = Objects.requireNonNull(reprojectionSummary, "reprojectionSummary");
			if (world != null) residentCatalog = new InventoryItemCodec.Catalog(world.registries(), displayBytes);
			originalSlots = original.access == null ? original.slots.size() : original.access.slots();
			originalStableCursor = original.access == null || original.access.stableCursor();
			liveAccess = original.access;
		}
		@Override public int displayBytes() { return displayBytes; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int displayBytes) {
			int ceiling = requireDisplayBytes(displayBytes);
			if (ceiling >= this.displayBytes) return InventoryReprojectionSummary.EMPTY;
			// At most one combination record; snapshot ownership is combined by CaptureHandle, never here.
			return compatibilityReprojectionSummary.apply(ceiling).plus(residentCatalog == null
				? InventoryReprojectionSummary.EMPTY : residentCatalog.reprojectionSummary(ceiling));
		}
		@Override public void tightenDisplay(int displayBytes) {
			int ceiling = requireDisplayBytes(displayBytes);
			if (ceiling >= this.displayBytes) return;
			this.displayBytes = ceiling;
			tightenDisplay.accept(ceiling);
			if (residentCatalog != null) residentCatalog.tightenDisplay(ceiling);
		}
		@Override public SourceKey key() { return new SourceKey(original.access == null ? "minecraft" : "pingforit", "block_inventory", original.identity, input.viewKey()); }
		@Override public boolean valid() {
			if (closed || !onThread.getAsBoolean()) return false;
			if (validationViewCost != null)
				(validation == null ? new InventoryValidationBudget.Allowance(validationViewCost) : validation).debit(validationViewCost);
			try {
				// A freshly reacquired wrapper validates its *new* topology. Validate
				// the admitted access's frozen evidence first, without object-identity equality.
				if (original.access != null && (!original.access.valid() || original.access.slots() != originalSlots
					|| original.access.stableCursor() != originalStableCursor)) return false;
				View current = liveView.get();
				InventoryValidationBudget.requireLayout(original.snapshotLayout, current.snapshotLayout);
				if (!current.identity.equals(original.identity) || !current.entities.equals(original.entities)
					|| !current.slots.equals(original.slots) || !java.util.Objects.equals(current.snapshotLayout, original.snapshotLayout)
					|| current.discoverItems != original.discoverItems) return false;
				if ((current.access == null) != (original.access == null) || current.access != null && (!current.access.valid()
					|| current.access.slots() != originalSlots || current.access.stableCursor() != originalStableCursor)) return false;
				liveAccess = current.access;
				return true;
			} catch (InventoryItemBudget.CaptureLimitException exhausted) { throw exhausted; }
			catch (RuntimeException | LinkageError unavailable) {
				InventorySnapshotDiagnostics.failure("inventory-source-valid", input, original.access, () -> "source=" + original.identity, unavailable);
				return false;
			}
		}
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) {
			var previous = validation; validation = Objects.requireNonNull(allowance);
			try { return valid(); } finally { validation = previous; }
		}
		@Override public boolean stableCursor() { return originalStableCursor; }
		@Override public int slots() { return originalSlots; }
		// Reacquired capabilities do not prove stable-version ownership; conservatively eventual.
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
			InventorySnapshotLayout layout = original.snapshotLayout;
			if (layout == null) {
				var grant = validation == null ? new InventoryValidationBudget.Allowance(discoveryValidationCost()) : validation;
				grant.debit(InventoryValidationBudget.bookkeeping()); grant.debit(InventoryValidationBudget.fixed(null));
				return Optional.empty();
			}
			boolean provider = original.access != null, vault = vault(layout);
			long lockMembers = lockMembers(layout);
			var discovery = validation == null ? new InventoryValidationBudget.Allowance(InventoryValidationBudget.discovery(layout, provider, vault, lockMembers)) : validation;
			discovery.debit(InventoryValidationBudget.bookkeeping());
			discovery.debit(InventoryValidationBudget.fixed(layout));
			validation = discovery;
			try {
				if (!valid()) throw new InventorySourceUnavailableException("snapshot source invalid");
				discovery.debit(InventoryValidationBudget.supported(layout, provider, vault));
				if (testSnapshotCapture == null && (world == null || !captureSupported(currentView(), world))) return Optional.empty();
				long upperBound = InventoryObservationSnapshot.memoryUpperBound(layout, displayBytes, provider, vault, lockMembers);
				var cost = InventoryValidationBudget.capture(layout, provider, vault, lockMembers);
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				private int attempts;
				private InventoryValidationBudget.Allowance allowance;
				@Override public long memoryUpperBoundBytes() { return upperBound; }
				@Override public InventoryValidationBudget.Cost validationCost() { return cost; }
				@Override public long validationAllocationUpperBoundBytes() { return cost.allocationBytes(); }
				@Override public long attemptedValidationAllocationBytes() { return allowance == null ? 0 : allowance.attemptedAllocationBytes(); }
				@Override public long attemptedValidationWorkUnits() { return allowance == null ? 0 : allowance.attemptedWorkUnits(); }
				@Override public int physicalReadUpperBound() { return visibleSlots(layout); }
				@Override public long itemWorkUpperBoundUnits() {
					return Math.addExact(
						Math.multiplyExact(Math.max(1, physicalReadUpperBound()), InventoryItemBudget.freezeWorkUpperBound(displayBytes)),
						cost.workUnits());
				}
				@Override public int attemptedPhysicalReads() { return attempts; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					return capture(new InventoryValidationBudget.Allowance(cost));
				}
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture(InventoryValidationBudget.Allowance grant) {
					allowance = Objects.requireNonNull(grant);
					allowance.debit(InventoryValidationBudget.bookkeeping());
					allowance.debit(InventoryValidationBudget.fixed(layout));
					validation = allowance;
					try {
						if (closed || !onThread.getAsBoolean() || !valid()) throw new InventorySourceUnavailableException("capture source invalid");
						if (testSnapshotCapture != null) return testSnapshotCapture.get();
						if (world == null) return Optional.empty();
						return captureObservations(input, new PhysicalRead(input.ordinaryTarget().orElseThrow(), input.face(), world::isLoaded),
							currentView(), world, Source.this::valid, upperBound, displayBytes, () -> attempts++, allowance, lockMembers);
					} finally { validation = null; }
				}
			});
			} finally { validation = null; }
		}
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(InventoryValidationBudget.Allowance allowance) {
			var previous = validation; validation = Objects.requireNonNull(allowance);
			try { return snapshotPlan(); } finally { validation = previous; }
		}
		@Override public InventoryValidationBudget.Cost discoveryValidationCost() {
			var layout = original.snapshotLayout;
			if (layout == null) return InventoryValidationBudget.layoutlessDiscovery();
			return InventoryValidationBudget.discovery(layout, original.access != null, vault(layout), lockMembers(layout))
				.plus(validationViewCost.times(2));
		}
		private View currentView() {
			// valid() has just reacquired this operation's capability. The original
			// wrapper remains topology evidence, never a stale capture content port.
			return new View(original.identity, original.entities, original.slots, liveAccess, original.snapshotLayout, original.discoverItems);
		}
		@Override public InventoryDomainCodec.Item read(int slot) {
			return readOwned(slot, null);
		}
		@Override public InventoryDomainCodec.Item read(int slot, InventoryCatalogAdmission admission) {
			return readOwned(slot, Objects.requireNonNull(admission));
		}
		private InventoryDomainCodec.Item readOwned(int slot, InventoryCatalogAdmission admission) {
			if (!valid()) throw new InventorySourceUnavailableException("inventory view changed");
			if (world == null) {
				if (liveAccess != null) return decode(codec, liveAccess.read(slot), admission);
				var value = readResident(original, null, slot);
				return value.amount() == 0 ? null : decode(codec, new IPlatformInventoryService.Entry(((InventoryResidentItem.Stack) value.item()).resident(), value.amount()), admission);
			}
			var resident = readResident(original, liveAccess, slot);
			var item = freezeItem(residentCatalog, world.registries(), displayBytes, resident, InventoryItemBudget.root(displayBytes), admission);
			if (!valid()) throw new InventorySourceUnavailableException("inventory authority revoked during read");
			return item;
		}
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			return enumerateOwned(limit, null);
		}
		@Override public InventorySourceAccess.Page enumerate(int limit, InventoryCatalogAdmission admission) {
			return enumerateOwned(limit, Objects.requireNonNull(admission));
		}
		private InventorySourceAccess.Page enumerateOwned(int limit, InventoryCatalogAdmission admission) {
			var progress = world == null ? null : new CursorlessProgress();
			try {
				if (!valid()) throw new InventorySourceUnavailableException("inventory view changed");
				if (world == null) {
					var observed = liveAccess.observe(limit);
					List<InventoryDomainCodec.Item> values = new ArrayList<>();
					for (var entry : observed.entries()) values.add(decode(codec, entry, admission));
					return new InventorySourceAccess.Page(values, observed.complete());
				}
				return enumerateResident(liveAccess, limit, residentCatalog, world.registries(), displayBytes, this::valid, admission, progress);
			} catch (RuntimeException | LinkageError failure) {
				if (progress != null && admission != null) progress.failed(failure);
				throw failure;
			}
		}
		@Override public boolean matchesSelection(int slot, InventorySelection selection) {
			return matchesSelectionOwned(slot, selection, null);
		}
		@Override public boolean matchesSelection(int slot, InventorySelection selection, InventoryCatalogAdmission admission) {
			return matchesSelectionOwned(slot, selection, Objects.requireNonNull(admission));
		}
		private boolean matchesSelectionOwned(int slot, InventorySelection selection, InventoryCatalogAdmission admission) {
			if (world == null) return matches(readOwned(slot, admission), selection);
			if (!valid()) return false;
			var resident = readResident(original, liveAccess, slot);
			boolean present = witness(resident, selection, residentCatalog, world.registries(), displayBytes, InventoryItemBudget.root(displayBytes), admission);
			return valid() && present;
		}
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection) {
			return observeSelectionOwned(limit, selection, null);
		}
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection, InventoryCatalogAdmission admission) {
			return observeSelectionOwned(limit, selection, Objects.requireNonNull(admission));
		}
		private InventorySourceAccess.SelectionWitness observeSelectionOwned(int limit, InventorySelection selection, InventoryCatalogAdmission admission) {
			if (world == null) {
				if (limit < 0) throw new IllegalArgumentException("negative observation limit");
				var page = enumerateOwned(limit, admission);
				if (page.slots().size() > limit) throw new IllegalStateException("provider exceeded selection allowance");
				return new InventorySourceAccess.SelectionWitness(page.slots().stream().anyMatch(item -> matches(item, selection)), page.slots().size());
			}
			var progress = new CursorlessProgress();
			try {
				if (!valid()) throw new InventorySourceUnavailableException("inventory view changed");
				return witnessResident(liveAccess, limit, selection, residentCatalog, world.registries(), displayBytes, this::valid, admission, progress);
			} catch (RuntimeException | LinkageError failure) {
				if (admission != null) progress.failed(failure);
				throw failure;
			}
		}
		@Override public void close() { closed = true; }
	}

	/**
	 * Shared detached resident capture over one admitted view. The caller revalidates
	 * the current physical binding before and after the contiguous freeze; every
	 * member is gated before its state, block entity, capability or content read.
	 */
	static Optional<InventorySourceAccess.InventorySnapshot> captureObservations(InventorySourceInput input, PhysicalRead physical,
		View view, SourceWorld world, BooleanSupplier valid, long upperBound, int displayBytes, Runnable attemptedRead) {
		var layout = view.snapshotLayout;
		if (layout == null) return Optional.empty();
		var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.capture(layout, view.access != null, vault(layout), lockMembers(layout)));
		allowance.debit(InventoryValidationBudget.bookkeeping());
		allowance.debit(InventoryValidationBudget.fixed(layout));
		return captureObservations(input, physical, view, world, valid, upperBound, displayBytes, attemptedRead, allowance, lockMembers(layout));
	}
	private static boolean vault(InventorySnapshotLayout layout) {
		if (layout == null) return false;
		for (int index = 0; index < layout.members().size(); index++)
			if (!layout.members().get(index).blockEntityId().equals("create:item_vault")) return false;
		return true;
	}
	private static boolean ordinaryLayout(InventorySnapshotLayout layout) {
		return layout != null && (layout.layoutId().equals("minecraft:container") || layout.layoutId().equals("minecraft:double_chest"));
	}
	private static long lockMembers(InventorySnapshotLayout layout) { return layout == null || vault(layout) ? 0 : layout.members().size(); }
	static Optional<InventorySourceAccess.InventorySnapshot> captureObservations(InventorySourceInput input, PhysicalRead physical,
		View view, SourceWorld world, BooleanSupplier valid, long upperBound, int displayBytes, Runnable attemptedRead,
		InventoryValidationBudget.Allowance validation, long lockMembers) {
		InventorySnapshotLayout layout = view.snapshotLayout;
		if (world == null || layout == null) return Optional.empty();
		if (upperBound < InventoryObservationSnapshot.memoryUpperBound(layout, displayBytes, view.access != null, vault(layout), lockMembers))
			throw new InventoryItemBudget.CaptureLimitException("inventory validation workspace was not admitted");
		if (!world.onServerThread()) throw new InventorySourceUnavailableException("snapshot owner or server thread unavailable");
		var memberCost = InventoryValidationBudget.members(layout, lockMembers);
		var supportedCost = InventoryValidationBudget.supported(layout, view.access != null, vault(layout));
		var readCost = InventoryValidationBudget.read(layout, view.access != null, vault(layout));
		var authorityCost = InventoryValidationBudget.authority(layout, lockMembers);
		validation.debit(memberCost);
		List<BlockEntity> members = precheckMembers(layout, world, physical.memberGate());
		if (view.access == null && !layout.layoutId().equals("minecraft:container") && !layout.layoutId().equals("minecraft:double_chest"))
			throw new IllegalStateException("unsupported vanilla snapshot layout");
		if (view.access != null && !layout.layoutId().equals(view.access.alias().orElse(view.identity)))
			throw new IllegalStateException("snapshot layout does not match provider alias");
		validation.debit(InventoryValidationBudget.authority(layout, 0));
		for (int index = 0; index < layout.members().size(); index++) {
			var member = layout.members().get(index);
			BlockEntity entity = members.get(index);
			if (!physical.contains(member.position())) throw new InventorySourceUnavailableException("snapshot member outside the read scope");
			if (entity instanceof Container container && member.segments().size() == 1) {
				var segment = member.segments().get(0);
				if (container.getContainerSize() != segment.localSlots() || !InventoryContainerReads.mappingEquals(container, Direction.valueOf(input.face().name()), segment.visibleSlots()))
					throw new InventorySourceUnavailableException("snapshot container face mapping changed");
			}
		}
		if (!valid.getAsBoolean()) throw new InventorySourceUnavailableException("snapshot source topology changed before contents");

		validation.debit(supportedCost);
		InventoryContainerReads.Scratch scratch = new InventoryContainerReads.Scratch();
		if (!captureSupported(view, world, scratch)) throw new InventorySourceUnavailableException("resident capture graph changed");
		BooleanSupplier authority = frozenAuthority(input, view, members, physical, world);
		InventoryObservationSnapshot captured = InventoryObservationSnapshot.capture(layout,
			new BlockPos(physical.position().x(), physical.position().y(), physical.position().z()), world.registries(), displayBytes, upperBound,
			index -> {
				validation.debit(readCost);
				if (!captureSupported(view, world, scratch)) throw new InventorySourceUnavailableException("resident capture graph changed before read");
				attemptedRead.run(); return readResident(view, view.access, index);
			}, () -> {
				validation.debit(authorityCost);
				if (!authority.getAsBoolean()) throw new InventorySourceUnavailableException("capture authority or topology revoked");
				return true;
			}, validation);
		try {
			validation.debit(memberCost);
			List<BlockEntity> after = precheckMembers(layout, world, physical.memberGate());
			if (!members.equals(after) || !valid.getAsBoolean()) throw new InventorySourceUnavailableException("capture source topology changed during resident reads");
			validation.debit(supportedCost);
			if (!captureSupported(view, world, scratch)) throw new InventorySourceUnavailableException("capture resident graph changed during resident reads");
			return Optional.of(captured);
		} catch (RuntimeException | Error failure) { captured.close(); throw failure; }
	}

	private static boolean captureSupported(View view, SourceWorld world) {
		return captureSupported(view, world, new InventoryContainerReads.Scratch());
	}
	private static boolean captureSupported(View view, SourceWorld world, InventoryContainerReads.Scratch scratch) {
		if (view.snapshotLayout == null) return false;
		for (int member = 0; member < view.snapshotLayout.members().size(); member++) {
			var segments = view.snapshotLayout.members().get(member).segments();
			for (int segment = 0; segment < segments.size(); segment++)
				if (!InventorySnapshotSchemas.ITEMS.equals(segments.get(segment).schemaId())) return false;
		}
		if (view.access != null) return view.access.supportsDetachedSlotCapture();
		for (int index = 0; index < view.entities.size(); index++) {
			var entity = view.entities.get(index);
			if (!(entity instanceof Container container) || !world.audited(container, scratch)) return false;
		}
		return true;
	}
	/**
	 * Frozen-topology authority for one resident capture: the already resolved
	 * member set, the reciprocal chest pairing/facing and the complete ordered
	 * selected-face witness are rechecked before every content read without
	 * reacquiring the view or rebuilding its slot lists. Nonempty lock checks
	 * keep A1's single-call certificate; their bounded multiplicity is charged
	 * once by the snapshot plan's validation work term. The full binding/mapping
	 * revalidation before and after the capture stays with the caller; this
	 * cannot widen the admitted scope. Classification and provider metadata
	 * callbacks are admitted by that same symbolic validation work term.
	 */
	private static BooleanSupplier frozenAuthority(InventorySourceInput input, View view, List<BlockEntity> members, PhysicalRead physical, SourceWorld world) {
		InventorySnapshotLayout layout = view.snapshotLayout;
		Direction face = Direction.valueOf(physical.face().name());
		var blocks = new net.minecraft.world.level.block.Block[members.size()];
		var chestTypes = new ChestType[members.size()];
		var chestFacings = new Direction[members.size()];
		var chestPartners = new BlockPos[members.size()];
		for (int index = 0; index < blocks.length; index++) {
			BlockPos position = layout.members().get(index).position();
			BlockState state = world.blockState(position);
			blocks[index] = state.getBlock();
			if (state.getBlock() instanceof ChestBlock) {
				chestTypes[index] = state.getValue(ChestBlock.TYPE);
				if (chestTypes[index] != ChestType.SINGLE) {
					chestFacings[index] = state.getValue(ChestBlock.FACING);
					chestPartners[index] = position.relative(ChestBlock.getConnectedDirection(state));
				}
			}
		}
		return new BooleanSupplier() {
			private int memberIndex, segmentIndex, visibleIndex;
			@Override public boolean getAsBoolean() {
				try {
					if (!world.onServerThread()) return false;
					while (true) {
						if (memberIndex >= layout.members().size()) return membersHeld();
						var member = layout.members().get(memberIndex);
						if (segmentIndex >= member.segments().size()) { memberIndex++; segmentIndex = 0; visibleIndex = 0; continue; }
						var segment = member.segments().get(segmentIndex);
						if (visibleIndex >= segment.visibleSlots().size()) { segmentIndex++; visibleIndex = 0; continue; }
						if (!membersHeld()) return false;
						BlockEntity entity = members.get(memberIndex);
						// The complete ordered selected-face witness is rechecked
						// before every permitted callback, not only the next position.
						if (entity instanceof Container container && (container.getContainerSize() != segment.localSlots()
							|| !InventoryContainerReads.mappingEquals(container, face, segment.visibleSlots()))) return false;
						visibleIndex++;
						return true;
					}
				} catch (RuntimeException | LinkageError unavailable) {
					InventorySnapshotDiagnostics.failure("inventory-snapshot-authority", input, view.access,
						() -> "source=" + view.identity + " member=" + memberIndex + " segment=" + segmentIndex + " visibleSlot=" + visibleIndex, unavailable);
					return false;
				}
			}
			/** Every frozen member is still loaded, the same BlockEntity, block, readable and, for a chest half, still reciprocally paired. */
			private boolean membersHeld() {
				ItemStack key = world.heldKey();
				for (int index = 0; index < members.size(); index++) {
					BlockEntity entity = members.get(index);
					BlockPos pos = layout.members().get(index).position();
					if (!physical.contains(pos) || !world.isLoaded(pos) || world.blockEntity(pos) != entity) return false;
					BlockState state = world.blockState(pos);
					if (state == null || state.getBlock() != blocks[index]
						|| !InventoryReadSafety.readable(entity, key)
						|| !chestPairHeld(index, pos, state)) return false;
				}
				return true;
			}
			/** The frozen reciprocal chest relationship and facing, compared without cloning the view. */
			private boolean chestPairHeld(int index, BlockPos pos, BlockState state) {
				ChestType frozenType = chestTypes[index];
				if (frozenType == null) return true;
				if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) != frozenType) return false;
				if (frozenType == ChestType.SINGLE) return true;
				if (state.getValue(ChestBlock.FACING) != chestFacings[index]) return false;
				BlockPos partner = chestPartners[index];
				if (!physical.contains(partner) || !world.isLoaded(partner)) return false;
				BlockState other = world.blockState(partner);
				if (other == null || other.getBlock() != blocks[index] || !(other.getBlock() instanceof ChestBlock)
					|| other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
					|| other.getValue(ChestBlock.TYPE) == frozenType
					|| other.getValue(ChestBlock.FACING) != chestFacings[index]) return false;
				Direction back = ChestBlock.getConnectedDirection(other);
				return partner.getX() + back.getStepX() == pos.getX()
					&& partner.getY() + back.getStepY() == pos.getY()
					&& partner.getZ() + back.getStepZ() == pos.getZ();
			}
		};
	}
	private static IPlatformInventoryService.ResidentEntry readResident(View view, IPlatformInventoryService.Access access, int index) {
		if (access != null) return access.readResident(index);
		Slot slot = view.slots.get(index);
		return IPlatformInventoryService.ResidentReads.stack(slot.container.getItem(slot.index));
	}
	private static InventoryDomainCodec.Item freezeItem(InventoryItemCodec.Catalog catalog, HolderLookup.Provider registries,
		int displayBytes, IPlatformInventoryService.ResidentEntry resident, InventoryItemBudget budget, InventoryCatalogAdmission admission) {
		if (resident.amount() == 0) return null;
		var detached = InventoryItemCodec.freeze(resident.item(), registries, displayBytes, budget);
		var encoded = admission == null ? catalog.intern(detached) : catalog.intern(detached, admission);
		var display = encoded.display();
		return new InventoryDomainCodec.Item(encoded.key(), resident.amount(), display.label(), display.displayJson(), display.aggregateRequired());
	}
	private static InventorySourceAccess.Page enumerateResident(IPlatformInventoryService.Access access, int limit,
		InventoryItemCodec.Catalog catalog, HolderLookup.Provider registries, int displayBytes, BooleanSupplier valid, InventoryCatalogAdmission admission,
		CursorlessProgress progress) {
		if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("resident enumeration limit");
		var budget = InventoryItemBudget.capture(displayBytes, Math.max(1, limit),
			Math.max(1, Math.multiplyExact(limit, InventoryItemBudget.retainedUpperBound(displayBytes))),
			Math.multiplyExact(Math.max(1, limit), InventoryItemBudget.freezeWorkUpperBound(displayBytes)));
		List<InventoryDomainCodec.Item> values = new ArrayList<>(limit);
		progress.enteringProvider();
		var visited = access.observeResident(limit, resident -> {
			progress.attemptingVisit(); // Every delivered view, including blank/failing freezes.
			try {
				values.add(freezeItem(catalog, registries, displayBytes, resident, budget, admission));
				if (!valid.getAsBoolean()) throw new InventorySourceUnavailableException("enumeration authority revoked");
				return true;
			} catch (RuntimeException | LinkageError failure) {
				if (admission != null) throw progress.visitorFailed(failure);
				throw failure;
			}
		});
		progress.returned(visited.visited());
		if (visited.visited() != values.size()) throw new IllegalStateException("invalid resident enumeration");
		if (!valid.getAsBoolean()) throw new InventorySourceUnavailableException("invalid resident enumeration");
		return new InventorySourceAccess.Page(values, visited.complete());
	}
	private static boolean witness(IPlatformInventoryService.ResidentEntry resident, InventorySelection selection,
		InventoryItemCodec.Catalog catalog, HolderLookup.Provider registries, int displayBytes, InventoryItemBudget budget, InventoryCatalogAdmission admission) {
		if (resident.amount() == 0) return false;
		if (selection.aggregate()) {
			var item = switch (resident.item()) {
				case InventoryResidentItem.Stack stack -> stack.resident().getItem();
				case InventoryResidentItem.Patch patch -> patch.item();
			};
			var id = BuiltInRegistries.ITEM.getKey(item);
			return id != null && id.toString().equals(selection.itemId());
		}
		var item = freezeItem(catalog, registries, displayBytes, resident, budget, admission);
		return item != null && !item.aggregateRequired() && item.key().equals(selection.exact());
	}
	private static InventorySourceAccess.SelectionWitness witnessResident(IPlatformInventoryService.Access access, int limit,
		InventorySelection selection, InventoryItemCodec.Catalog catalog, HolderLookup.Provider registries, int displayBytes, BooleanSupplier valid,
		InventoryCatalogAdmission admission, CursorlessProgress progress) {
		if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("selection observation limit");
		var budget = InventoryItemBudget.capture(displayBytes, Math.max(1, limit),
			Math.max(1, Math.multiplyExact(limit, InventoryItemBudget.retainedUpperBound(displayBytes))),
			Math.multiplyExact(Math.max(1, limit), InventoryItemBudget.freezeWorkUpperBound(displayBytes)));
		progress.enteringProvider();
		var visited = access.observeResident(limit, resident -> {
			progress.attemptingVisit();
			try {
				progress.present = witness(resident, selection, catalog, registries, displayBytes, budget, admission);
				if (!valid.getAsBoolean()) throw new InventorySourceUnavailableException("selection authority revoked");
				return !progress.present;
			} catch (RuntimeException | LinkageError failure) {
				if (admission != null) throw progress.visitorFailed(failure);
				throw failure;
			}
		});
		progress.returned(visited.visited());
		if (!valid.getAsBoolean()) throw new InventorySourceUnavailableException("selection authority revoked");
		return new InventorySourceAccess.SelectionWitness(progress.present, visited.visited());
	}
	private static boolean matches(InventoryDomainCodec.Item item, InventorySelection selection) {
		return item != null && item.count() > 0 && (selection.aggregate() ? item.key().itemId().equals(selection.itemId())
			: !item.aggregateRequired() && item.key().equals(selection.exact()));
	}

	private static List<BlockEntity> precheckMembers(InventorySnapshotLayout layout, SourceWorld world, Predicate<BlockPos> memberGate) {
		List<BlockEntity> members = new ArrayList<>(layout.members().size());
		for (InventorySnapshotLayout.Member member : layout.members()) {
			BlockPos position = member.position();
			if (!contains(memberGate, position) || !world.isLoaded(position)) throw new InventorySourceUnavailableException("snapshot member is not loaded");
			if (!contains(memberGate, position)) throw new InventorySourceUnavailableException("snapshot member outside the read scope");
			BlockState state = world.blockState(position);
			if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(member.blockId()))
				throw new InventorySourceUnavailableException("snapshot member block changed");
			if (!contains(memberGate, position)) throw new InventorySourceUnavailableException("snapshot member outside the read scope");
			BlockEntity entity = world.blockEntity(position);
			if (entity == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(member.blockEntityId()))
				throw new InventorySourceUnavailableException("snapshot member block entity changed");
			check(entity, world.heldKey());
			members.add(entity);
		}
		return members;
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
		private final java.util.function.IntConsumer tightenDisplay;
		private final IntFunction<InventoryReprojectionSummary> compatibilityReprojectionSummary;
		private int displayBytes;
		private boolean closed;
		private InventoryItemCodec.Catalog residentCatalog;
		private InventoryValidationBudget.Allowance validation;
		private final InventoryValidationBudget.Cost validationViewCost;

		ExternalSource(InventorySourceInput input, ExternalBinding first, ExternalBindingResolver bindings,
			BooleanSupplier onThread, Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec, View view,
			int displayBytes, java.util.function.IntConsumer tightenDisplay) {
			this(input, first, bindings, onThread, codec, view, displayBytes, tightenDisplay,
				ignored -> InventoryReprojectionSummary.UNKNOWN);
		}
		ExternalSource(InventorySourceInput input, ExternalBinding first, ExternalBindingResolver bindings,
			BooleanSupplier onThread, Function<IPlatformInventoryService.Entry, InventoryDomainCodec.Item> codec, View view,
			int displayBytes, java.util.function.IntConsumer tightenDisplay, IntFunction<InventoryReprojectionSummary> reprojectionSummary) {
			this.input = input;
			this.descriptor = first.descriptor();
			this.bindings = bindings;
			this.onThread = onThread;
			this.codec = codec;
			this.identity = view.identity;
			this.layout = view.snapshotLayout;
			this.validationViewCost = InventoryValidationBudget.viewValidation(layout, vault(layout), lockMembers(layout));
			this.discoverItems = view.discoverItems;
			this.accessAlias = view.access == null ? null : view.access.alias().orElse(null);
			this.slots = view.access == null ? view.slots.size() : view.access.slots();
			this.stableCursor = view.access == null || view.access.stableCursor();
			this.capability = view.access != null;
			this.displayBytes = requireDisplayBytes(displayBytes);
			this.tightenDisplay = Objects.requireNonNull(tightenDisplay, "tightenDisplay");
			this.compatibilityReprojectionSummary = Objects.requireNonNull(reprojectionSummary, "reprojectionSummary");
			requireMembers(new Current(first, view));
		}
		@Override public int displayBytes() { return displayBytes; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int displayBytes) {
			int ceiling = requireDisplayBytes(displayBytes);
			if (ceiling >= this.displayBytes) return InventoryReprojectionSummary.EMPTY;
			// No binding resolution, member checks or lazy catalogue creation; at most one combination record.
			return compatibilityReprojectionSummary.apply(ceiling).plus(residentCatalog == null
				? InventoryReprojectionSummary.EMPTY : residentCatalog.reprojectionSummary(ceiling));
		}
		@Override public void tightenDisplay(int displayBytes) {
			int ceiling = requireDisplayBytes(displayBytes);
			if (ceiling >= this.displayBytes) return;
			this.displayBytes = ceiling;
			tightenDisplay.accept(ceiling);
			if (residentCatalog != null) residentCatalog.tightenDisplay(ceiling);
		}

		/** Provider-confirmed sub-level scope plus the canonical alias; never the raw locator. */
		@Override public SourceKey key() {
			return new SourceKey(descriptor.providerId(), "block_inventory",
				scopeIdentity(descriptor.dimensionId(), descriptor.subLevelId(), identity), input.viewKey());
		}
		@Override public boolean valid() {
			if (closed || !onThread.getAsBoolean()) return false;
			try { current(); return true; }
			catch (InventoryItemBudget.CaptureLimitException exhausted) { throw exhausted; }
			catch (RuntimeException | LinkageError unavailable) {
				InventorySnapshotDiagnostics.failure("inventory-external-valid", input, bindings,
					() -> "source=" + identity + " descriptor=" + descriptor, unavailable);
				return false;
			}
		}
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) {
			var previous = validation; validation = Objects.requireNonNull(allowance);
			try { return valid(); } finally { validation = previous; }
		}
		@Override public boolean stableCursor() { return stableCursor; }
		@Override public int slots() { return slots; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public InventoryDomainCodec.Item read(int slot) {
			return readOwned(slot, null);
		}
		@Override public InventoryDomainCodec.Item read(int slot, InventoryCatalogAdmission admission) {
			return readOwned(slot, Objects.requireNonNull(admission));
		}
		private InventoryDomainCodec.Item readOwned(int slot, InventoryCatalogAdmission admission) {
			Current live = current();
			View current = live.view();
			requireMembers(live);
			var resident = readResident(current, current.access, slot);
			var item = freezeItem(catalog(live), live.binding().world().registries(), displayBytes, resident, InventoryItemBudget.root(displayBytes), admission);
			requireMembers(live); current();
			return item;
		}
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			return enumerateOwned(limit, null);
		}
		@Override public InventorySourceAccess.Page enumerate(int limit, InventoryCatalogAdmission admission) {
			return enumerateOwned(limit, Objects.requireNonNull(admission));
		}
		private InventorySourceAccess.Page enumerateOwned(int limit, InventoryCatalogAdmission admission) {
			var progress = new CursorlessProgress();
			try {
				Current live = current();
				View current = live.view();
				if (current.access == null) throw new UnsupportedOperationException("no enumeration");
				requireMembers(live);
				return enumerateResident(current.access.guardedBy(() -> membersAllowed(live)), limit, catalog(live),
					live.binding().world().registries(), displayBytes, ExternalSource.this::valid, admission, progress);
			} catch (RuntimeException | LinkageError failure) {
				if (admission != null) progress.failed(failure);
				throw failure;
			}
		}
		private InventoryItemCodec.Catalog catalog(Current live) {
			if (residentCatalog == null) residentCatalog = new InventoryItemCodec.Catalog(live.binding().world().registries(), displayBytes);
			return residentCatalog;
		}
		@Override public boolean matchesSelection(int slot, InventorySelection selection) {
			return matchesSelectionOwned(slot, selection, null);
		}
		@Override public boolean matchesSelection(int slot, InventorySelection selection, InventoryCatalogAdmission admission) {
			return matchesSelectionOwned(slot, selection, Objects.requireNonNull(admission));
		}
		private boolean matchesSelectionOwned(int slot, InventorySelection selection, InventoryCatalogAdmission admission) {
			Current live = current(); requireMembers(live);
			var resident = readResident(live.view(), live.view().access, slot);
			boolean present = witness(resident, selection, catalog(live), live.binding().world().registries(), displayBytes, InventoryItemBudget.root(displayBytes), admission);
			requireMembers(live); current(); return present;
		}
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection) {
			return observeSelectionOwned(limit, selection, null);
		}
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection, InventoryCatalogAdmission admission) {
			return observeSelectionOwned(limit, selection, Objects.requireNonNull(admission));
		}
		private InventorySourceAccess.SelectionWitness observeSelectionOwned(int limit, InventorySelection selection, InventoryCatalogAdmission admission) {
			var progress = new CursorlessProgress();
			try {
				Current live = current(); requireMembers(live);
				return witnessResident(live.view().access.guardedBy(() -> membersAllowed(live)), limit, selection,
					catalog(live), live.binding().world().registries(), displayBytes, ExternalSource.this::valid, admission, progress);
			} catch (RuntimeException | LinkageError failure) {
				if (admission != null) progress.failed(failure);
				throw failure;
			}
		}
		private boolean membersAllowed(Current live) {
			var position = live.binding().descriptor().blockTarget();
			if (!live.binding().contains(new BlockPos(position.x(), position.y(), position.z()))) return false;
			return live.view().snapshotLayout == null || live.view().snapshotLayout.members().stream()
				.allMatch(member -> live.binding().contains(member.position()));
		}
		private void requireMembers(Current live) {
			if (!membersAllowed(live)) throw new InventorySourceUnavailableException("inventory handoff outside the read scope");
		}
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
			InventorySnapshotLayout captured = layout;
			if (captured == null) {
				var grant = validation == null ? new InventoryValidationBudget.Allowance(discoveryValidationCost()) : validation;
				grant.debit(InventoryValidationBudget.bookkeeping()); grant.debit(InventoryValidationBudget.fixed(null));
				return Optional.empty();
			}
			long lockMembers = lockMembers(captured);
			boolean vault = vault(captured);
			var discovery = validation == null ? new InventoryValidationBudget.Allowance(InventoryValidationBudget.discovery(captured, capability, vault, lockMembers)) : validation;
			discovery.debit(InventoryValidationBudget.bookkeeping());
			discovery.debit(InventoryValidationBudget.fixed(captured));
			validation = discovery;
			try {
				Current candidate = current();
				discovery.debit(InventoryValidationBudget.supported(captured, capability, vault));
				if (!captureSupported(candidate.view(), candidate.binding().world())) return Optional.empty();
				long upperBound = InventoryObservationSnapshot.memoryUpperBound(captured, displayBytes, capability, vault, lockMembers);
				var cost = InventoryValidationBudget.capture(captured, capability, vault, lockMembers);
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				private int attempts;
				private InventoryValidationBudget.Allowance allowance;
				@Override public long memoryUpperBoundBytes() { return upperBound; }
				@Override public InventoryValidationBudget.Cost validationCost() { return cost; }
				@Override public long validationAllocationUpperBoundBytes() { return cost.allocationBytes(); }
				@Override public long attemptedValidationAllocationBytes() { return allowance == null ? 0 : allowance.attemptedAllocationBytes(); }
				@Override public long attemptedValidationWorkUnits() { return allowance == null ? 0 : allowance.attemptedWorkUnits(); }
				@Override public int physicalReadUpperBound() { return visibleSlots(captured); }
				@Override public long itemWorkUpperBoundUnits() {
					return Math.addExact(
						Math.multiplyExact(Math.max(1, physicalReadUpperBound()), InventoryItemBudget.freezeWorkUpperBound(displayBytes)),
						cost.workUnits());
				}
				@Override public int attemptedPhysicalReads() { return attempts; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					return capture(new InventoryValidationBudget.Allowance(cost));
				}
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture(InventoryValidationBudget.Allowance grant) {
					allowance = Objects.requireNonNull(grant);
					allowance.debit(InventoryValidationBudget.bookkeeping());
					allowance.debit(InventoryValidationBudget.fixed(captured));
					validation = allowance;
					try {
						if (closed || !onThread.getAsBoolean()) throw new InventorySourceUnavailableException("capture source invalid");
						Current current = current();
						BlockReadSource live = current.binding().descriptor();
						return captureObservations(input, new PhysicalRead(live.blockTarget(), input.face(), current.binding()::contains),
							current.view(), current.binding().world(), ExternalSource.this::valid, upperBound, displayBytes, () -> attempts++, allowance, lockMembers);
					} finally { validation = null; }
				}
			});
			} finally { validation = null; }
		}
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(InventoryValidationBudget.Allowance allowance) {
			var previous = validation; validation = Objects.requireNonNull(allowance);
			try { return snapshotPlan(); } finally { validation = previous; }
		}
		@Override public InventoryValidationBudget.Cost discoveryValidationCost() {
			if (layout == null) return InventoryValidationBudget.layoutlessDiscovery();
			return InventoryValidationBudget.discovery(layout, capability, vault(layout), lockMembers(layout))
				.plus(validationViewCost.times(2));
		}
		@Override public void close() { closed = true; }

		private Current current() {
			if (closed) throw new InventorySourceUnavailableException("closed inventory source");
			if (!onThread.getAsBoolean()) throw new InventorySourceUnavailableException("inventory source is not on the server thread");
			if (validationViewCost != null)
				(validation == null ? new InventoryValidationBudget.Allowance(validationViewCost) : validation).debit(validationViewCost);
			ExternalBinding binding = bindings.resolve().orElseThrow(() -> new InventorySourceUnavailableException("provider read source unavailable"));
			if (!sameDescriptor(binding.descriptor())) throw new InventorySourceUnavailableException("provider read binding changed");
			View view = view(binding.world(), input, new PhysicalRead(binding.descriptor().blockTarget(), input.face(), binding::contains), binding.providers(), layout, true);
			if (!sameTopology(view)) throw new InventorySourceUnavailableException("provider read topology changed");
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
			if (owner == null) throw new InventorySourceUnavailableException("snapshot owner unavailable");
			return owner.getMainHandItem();
		}
		@Override public HolderLookup.Provider registries() { return level.registryAccess(); }
		@Override public boolean audited(Container container, InventoryContainerReads.Scratch scratch) {
			return InventoryContainerReads.audited(container, ignored -> true, scratch);
		}
	}
	private static InventoryDomainCodec.Item item(InventoryItemCodec.Catalog catalog, IPlatformInventoryService.Entry entry, InventoryCatalogAdmission admission) {
		if (entry.isEmpty()) return null;
		var encoded = admission == null ? catalog.encode(entry.exemplar())
			: catalog.encode(entry.exemplar(), InventoryItemBudget.root(catalog.displayBytes()), admission);
		var display = encoded.display();
		return new InventoryDomainCodec.Item(encoded.key(), entry.amount(), display.label(), display.displayJson(), display.componentsStripped());
	}
}
