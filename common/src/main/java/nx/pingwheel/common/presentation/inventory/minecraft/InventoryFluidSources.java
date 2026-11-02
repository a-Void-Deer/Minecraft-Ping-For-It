package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessMatch;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessUse;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Static fluid sources. Only detached metadata/object witnesses survive an external operation. */
@NotNullByDefault
public final class InventoryFluidSources {
	private InventoryFluidSources() {}
	private static final InventoryValidationBudget.Cost BIND = new InventoryValidationBudget.Cost(8192, 8192, 2048);
	private static final InventoryValidationBudget.Cost MEMBER = new InventoryValidationBudget.Cost(1024, 1024, 512);
	private static final InventoryValidationBudget.Cost INDEX_LOOKUP = new InventoryValidationBudget.Cost(0, 0, 16);
	private static OperationProfile owned(InventoryValidationBudget.Cost cost) { return new OperationProfile(cost, 0, 0, 0); }
	/** Outer Runtime bookkeeping only. Actual shape, callbacks and locks get additional genuine grants. */
	public static InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryValidationBudget.NONE; }
	public static Optional<InventorySourceAccess.Source> resolve(MinecraftServer server, InventorySourceInput input, int displayBytes,
		InventoryValidationBudget.Allowance allowance) {
		return resolve(new ServerWorld(server), IPlatformFluidService.INSTANCE, input, displayBytes, allowance);
	}
	@NotNullByDefault
	interface World {
		boolean onServerThread();
		@Nullable Binding bind(InventorySourceInput input, InventoryValidationBudget.Allowance allowance);
		@Nullable BlockEntity existingEntity(ServerLevel level, BlockPos pos);
		BlockState blockState(ServerLevel level, BlockPos pos);
		boolean loaded(ServerLevel level, BlockPos pos);
		@Nullable ItemStack ownerKey(InventorySourceInput input);
		HolderLookup.Provider registries(ServerLevel level);
		default InventoryValidationBudget.Cost bindingCost() { return BIND; }
		default InventoryValidationBudget.Cost membershipCost() { return InventoryValidationBudget.NONE; }
		default boolean contains(Binding binding, BlockPos pos, InventoryValidationBudget.Allowance allowance) { return binding.resolved().containsMember(pos); }
		default void used(IPlatformFluidService.RouteInfo route) {}
	}
	/** Source-owned facade with no owning path to the server or any resolved world/provider context. */
	@NotNullByDefault
	private record ServerWorld(WeakReference<MinecraftServer> server) implements World {
		private ServerWorld(MinecraftServer server) { this(new WeakReference<>(server)); }
		@Override public boolean onServerThread() {
			@Nullable MinecraftServer current = server.get(); return current != null && current.isSameThread();
		}
		@Override public InventoryValidationBudget.Cost bindingCost() { return BIND.plus(ExternalBlockServerProviders.registry().membershipCost()); }
		@Override public InventoryValidationBudget.Cost membershipCost() { return ExternalBlockServerProviders.registry().membershipCost(); }
		@Override public @Nullable Binding bind(InventorySourceInput input, InventoryValidationBudget.Allowance allowance) {
			@Nullable MinecraftServer current = server.get();
			if (current == null) return null;
			var dimension = ResourceLocation.tryParse(input.target().dimensionId());
			@Nullable ServerLevel level = dimension == null ? null : current.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
			if (level == null || !current.isSameThread()) return null;
			var result = input.target() instanceof Target.ExternalBlockTarget external && external.isCommitted()
				? MinecraftBlockReadSources.resolveCommittedReadSource(level, input.target())
				: input.target() instanceof Target.BlockTarget block ? MinecraftBlockReadSources.resolveOrdinary(level, block, allowance)
				: MinecraftBlockReadSources.resolvePreviewReadSource(level, input.target());
			if (result.isEmpty()) return null;
			var block = result.get().descriptor().blockTarget();
			return new Binding(level, result.get(), new BlockPos(block.x(), block.y(), block.z()));
		}
		@Override public boolean contains(Binding binding, BlockPos pos, InventoryValidationBudget.Allowance allowance) {
			if (binding.resolved().descriptor().providerId().equals(BlockReadSource.MINECRAFT_PROVIDER_ID))
				return ExternalBlockServerProviders.registry().membership(binding.level(), pos, allowance).state()
					== nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider.MembershipState.OUTSIDE
					&& !binding.level().isOutsideBuildHeight(pos) && binding.level().isLoaded(pos);
			return binding.resolved().containsMember(pos);
		}
		@Override public @Nullable BlockEntity existingEntity(ServerLevel level, BlockPos pos) { return InventoryFluidSources.existingEntity(level, pos); }
		@Override public BlockState blockState(ServerLevel level, BlockPos pos) { return level.getBlockState(pos); }
		@Override public boolean loaded(ServerLevel level, BlockPos pos) { return level.isLoaded(pos); }
		@Override public @Nullable ItemStack ownerKey(InventorySourceInput input) {
			@Nullable MinecraftServer current = server.get();
			if (current == null || !current.isSameThread()) return null;
			@Nullable var owner = current.getPlayerList().getPlayer(input.readOwner()); return owner == null ? null : owner.getMainHandItem();
		}
		@Override public HolderLookup.Provider registries(ServerLevel level) { return level.registryAccess(); }
		@Override public void used(IPlatformFluidService.RouteInfo route) {
			@Nullable MinecraftServer current = server.get();
			if (current == null || !current.isSameThread()) throw unavailable("fluid server context unavailable");
			InventoryFluidRouteDiagnostics.used(current, route);
		}
	}
	@NotNullByDefault
	record Binding(ServerLevel level, ResolvedBlockReadSource resolved, BlockPos root) {}
	static Optional<InventorySourceAccess.Source> resolve(World world, IPlatformFluidService service, InventorySourceInput input, int displayBytes,
		InventoryValidationBudget.Allowance allowance) {
		if (input.resourceKind() != InventoryResourceKind.FLUID || !world.onServerThread()) return Optional.empty();
		InventoryMinecraftSources.requireDisplayBytes(displayBytes);
		return Optional.of(new Source(world, service, input, displayBytes, allowance));
	}
	/** Pending NBT is not a BlockEntity and must never be promoted by discovery. */
	static @Nullable BlockEntity existingEntity(ServerLevel level, BlockPos pos) {
		var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk == null ? null : chunk.getBlockEntities().get(pos);
	}
	private static boolean registryMatches(String expected, @Nullable ResourceLocation current) {
		return current != null && (long) current.getNamespace().length() + current.getPath().length() + 1 <= 256 && expected.equals(current.toString());
	}
	@NotNullByDefault
	private record Witness(BlockPos position, String blockId, String entityId) {}
	@NotNullByDefault
	private static final class Source implements InventorySourceAccess.Source {
		// Own the stable facade, not its server. Resolved bindings and their member callbacks stay operation-local.
		private final World world;
		private final IPlatformFluidService service;
		private final InventorySourceInput input;
		private final BlockReadSource descriptor;
		private final InventorySnapshotLayout layout;
		private final Optional<String> alias;
		private final Witness @Unmodifiable [] members;
		// External metadata never owns a BE, Level, Access or native-address graph. Ordinary fluid
		// handles keep their existing resident member ownership; ITEM sources are not involved.
		private final BlockEntity @Nullable @Unmodifiable [] ordinaryMembers;
		private final @Nullable FluidAddressWitness memberWitness;
		private final FluidAddressWitness addressWitness;
		private final SourceKey key;
		private final boolean stable, external;
		private final int slots;
		private final InventoryFluidAdmission.Ownership ownership;
		private IPlatformFluidService.@Nullable Access ordinaryAccess;
		private int displayBytes;
		private @Nullable InventoryFluidCodec.Catalog catalog;
		private boolean closed, scopeTransition, frozen;
		Source(World world, IPlatformFluidService service, InventorySourceInput input, int displayBytes, InventoryValidationBudget.Allowance allowance) {
			this.world = world; this.service = service; this.input = input; this.displayBytes = displayBytes;
			external = input.target() instanceof Target.ExternalBlockTarget;
			try (var admission = open(allowance)) {
				Binding binding = bind(admission);
				descriptor = binding.resolved().descriptor();
				IPlatformFluidService.@Nullable Access access = acquire(binding, admission);
				try {
					layout = access.layout(); alias = access.alias(); stable = access.stableCursor(); slots = access.slots();
					if (slots < 0 || slots > InventorySourceAccess.MAX_SLOTS || !stable && slots != 0)
						throw unavailable("fluid address bound");
					long bytes = 2048L + 256L * layout.members().size() + 2L * (layout.layoutData().length() + layout.layoutId().length());
					var cost = new InventoryValidationBudget.Cost(bytes * 2, bytes * 2, 1024L + 64L * layout.members().size() + slots);
					var memberCapture = external ? FluidAddressWitness.profile(WitnessUse.CAPTURE, layout.members().size()) : OperationProfile.NONE;
					try (var operation = admission.enter(new OperationProfile(cost.overlap(memberCapture.ownedCost()).plus(INDEX_LOOKUP),
						layout.members().size(), layout.members().size(), Math.addExact(bytes, memberCapture.retainedBytes())))) {
						operation.allowance().debit(cost);
						var captured = new Witness[layout.members().size()]; int index = 0, addresses = 0;
						for (var member : layout.members()) {
							captured[index++] = new Witness(member.position(), member.blockId(), member.blockEntityId());
							for (var segment : member.segments()) {
								if (!IPlatformFluidService.ADDRESS_SCHEMA.equals(segment.schemaId())) throw unavailable("fluid address schema");
								addresses = Math.addExact(addresses, segment.visibleSlots().size());
							}
						}
						Arrays.sort(captured, Comparator.comparingLong(witness -> witness.position().asLong())); members = captured;
						operation.allowance().requireAvailable(memberCapture.ownedCost());
						Object @Nullable [] current = external ? new Object[members.length] : null;
						ordinaryMembers = external ? null : new BlockEntity[members.length]; index = 0;
						for (Witness member : members) {
							if (!memberGate(binding, member.position(), operation, false) || !authority(binding, operation)) throw unavailable("fluid member gate");
							BlockEntity entity = Objects.requireNonNull(world.existingEntity(binding.level(), member.position()));
							if (!registryMatches(member.blockId(), BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()))
								|| !registryMatches(member.entityId(), BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()))) throw unavailable("fluid registry identity");
							if (current != null) current[index] = entity; else Objects.requireNonNull(ordinaryMembers)[index] = entity;
							index++;
						}
						memberWitness = current == null ? null : FluidAddressWitness.capture(FluidAddressWitness.Schema.COMMON_MEMBERS_V1, current, operation);
						frozen = true;
						if (stable && addresses != slots || find(binding.root(), operation) == null) throw unavailable("fluid root/address layout");
					}
					// Capture the original route once, into the same independent metadata ownership as
					// the detached index. Reacquisition/publication must never capture a replacement proof.
					try (var operation = admission.enter(access.validationProfile())) {
						if (!access.valid(operation)) throw unavailable("fluid source invalid before witness capture");
					}
					try (var operation = admission.enter(access.witnessProfile(WitnessUse.CAPTURE))) {
						addressWitness = access.captureWitness(operation);
					}
					if (!validate(binding, access, admission)) throw unavailable("fluid source invalid after witness capture");
					String identity = descriptor.providerId() + "/" + descriptor.subLevelId() + "/" + descriptor.blockTarget() + "/" + alias.orElse("");
					key = new SourceKey(descriptor.providerId(), "inventory/fluid", digest(identity), input.viewKey());
					// Complete operation-local cleanup before transferring ownership. A failing
					// external close must not orphan already-transferred metadata tickets.
					if (external) { var closing = access; access = null; closing.close(); }
					ownership = admission.retain();
					if (!external) { ordinaryAccess = access; access = null; }
				} finally { if (access != null) access.close(); }
			}
		}
		private InventoryFluidAdmission open(InventoryValidationBudget.Allowance allowance) {
			return allowance.fluidGrants().open(allowance, memberCost(), authorityCost());
		}
		private InventoryValidationBudget.Cost memberCost() { return MEMBER.plus(INDEX_LOOKUP).plus(world.membershipCost()); }
		private InventoryValidationBudget.Cost authorityCost() { return MEMBER.plus(world.bindingCost()); }
		private Binding bind(InventoryFluidAdmission admission) {
			try (var operation = admission.enter(owned(world.bindingCost()))) {
				operation.allowance().debit(BIND);
				@Nullable Binding binding = world.bind(input, operation.allowance());
				if (binding == null) throw unavailable("fluid binding unavailable"); return binding;
			}
		}
		private IPlatformFluidService.Access acquire(Binding binding, InventoryFluidAdmission admission) {
			var context = new IPlatformFluidService.Context(binding.level(), binding.root(), Direction.valueOf(input.face().name()), input.readOwner(),
				(pos, operation) -> memberGate(binding, pos, operation, frozen), operation -> authority(binding, operation));
			var profile = service.probeProfile().plus(new OperationProfile(InventoryValidationBudget.NONE, 2, 2, 0));
			IPlatformFluidService.@Nullable Access access = null;
			try (var operation = admission.enter(profile)) {
				context.require(binding.root(), operation);
				try (var probe = service.probe(context, operation).orElseThrow(() -> unavailable("no fluid capability"))) {
					access = probe.finish(admission).orElseThrow(() -> unavailable("no fluid capability"));
				}
				context.require(binding.root(), operation);
				return IPlatformFluidService.guarded(context, access);
			} catch (RuntimeException | Error failure) {
				if (access != null) try { access.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
				throw failure;
			}
		}
		private boolean authority(Binding expected, Operation operation) {
			operation.allowance().debit(MEMBER.plus(BIND));
			@Nullable Binding current = world.bind(input, operation.allowance());
			if (current == null || current.level() != expected.level() || !MinecraftBlockReadSources.sameReadBinding(expected.resolved().descriptor(), current.resolved().descriptor())) return false;
			@Nullable BlockEntity root = world.existingEntity(current.level(), current.root());
			return root != null && readable(root, operation);
		}
		private boolean memberGate(Binding binding, BlockPos pos, Operation operation, boolean historical) {
			operation.allowance().debit(MEMBER);
			if (!world.onServerThread() || !world.contains(binding, pos, operation.allowance()) || !world.loaded(binding.level(), pos)) return false;
			@Nullable BlockEntity entity = world.existingEntity(binding.level(), pos);
			if (entity == null || entity.getLevel() != binding.level() || !entity.getBlockPos().equals(pos)
				|| entity.getBlockState().getBlock() != world.blockState(binding.level(), pos).getBlock() || !readable(entity, operation)) return false;
			if (!historical) return true;
			operation.allowance().debit(INDEX_LOOKUP); int index = findIndex(pos);
			@Nullable Witness witness = index < 0 ? null : members[index];
			return witness != null && (ordinaryMembers == null || entity == ordinaryMembers[index])
				&& registryMatches(witness.blockId(), BuiltInRegistries.BLOCK.getKey(world.blockState(binding.level(), pos).getBlock()))
				&& registryMatches(witness.entityId(), BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
		}
		private @Nullable Witness find(BlockPos pos, Operation operation) {
			operation.allowance().debit(INDEX_LOOKUP); int index = findIndex(pos); return index < 0 ? null : members[index];
		}
		private int findIndex(BlockPos pos) {
			int low = 0, high = members.length - 1; long key = pos.asLong();
			while (low <= high) { int middle = (low + high) >>> 1; long value = members[middle].position().asLong();
				if (value == key) return middle; if (value < key) low = middle + 1; else high = middle - 1; }
			return -1;
		}
		private boolean readable(BlockEntity entity, Operation operation) {
			@Nullable ItemStack held = world.ownerKey(input);
			if (held == null || entity.isRemoved() || entity instanceof net.minecraft.world.RandomizableContainer loot && loot.getLootTable() != null) return false;
			if (!(entity instanceof BaseContainerBlockEntity)) return true;
			if (!(entity instanceof BaseContainerLockAccessor accessor)) return false;
			@Nullable LockCode code = accessor.pingforit$inventoryLock(); if (code == null) return false;
			if (code.key().isEmpty()) return true;
			var cost = InventoryValidationBudget.locks(1); operation.requireAdditional(cost); operation.allowance().debit(cost);
			return InventoryReadSafety.lockCheck(code, held, InventoryItemBudget.capture(InventoryItemCodec.minimumDisplayBytes(),
				InventoryItemBudget.MAX_NODES, 1, cost.workUnits())) == InventorySharedRead.Check.VALID;
		}
		private <T> T withAccess(InventoryValidationBudget.Allowance allowance, Action<T> action) {
			if (closed) throw unavailable("fluid source closed");
			scopeTransition = false; // Only the latest freshly authorized mismatch can request smooth recompute.
			try (var admission = open(allowance)) {
				Binding binding = bind(admission);
				if (!MinecraftBlockReadSources.sameReadBinding(descriptor, binding.resolved().descriptor())) {
					boolean changedScope = input.target() instanceof Target.ExternalBlockTarget target && target.isCommitted() && target.providerId().equals("sable")
						&& MinecraftBlockReadSources.sameReadBinding(descriptor.target(), binding.resolved().descriptor().target())
						&& descriptor.providerId().equals(binding.resolved().descriptor().providerId())
						&& descriptor.blockTarget().equals(binding.resolved().descriptor().blockTarget())
						&& !descriptor.subLevelId().equals(binding.resolved().descriptor().subLevelId());
					if (changedScope) try (var operation = admission.enter(new OperationProfile(InventoryValidationBudget.NONE, 1, 1, 0))) {
						changedScope = memberGate(binding, binding.root(), operation, false) && authority(binding, operation);
					}
					if (changedScope) try (var current = acquire(binding, admission)) {
						changedScope = validate(binding, current, admission);
					}
					scopeTransition = changedScope;
					throw unavailable("fluid physical scope changed");
				}
				IPlatformFluidService.Access access = external ? acquire(binding, admission) : Objects.requireNonNull(ordinaryAccess);
				try {
					return action.run(binding, access, admission);
				} finally { if (external) access.close(); }
			}
		}
		private boolean sameLayout(IPlatformFluidService.Access access) {
			return layout.equals(access.layout()) && alias.equals(access.alias()) && stable == access.stableCursor() && slots == access.slots();
		}
		private boolean validate(Binding binding, IPlatformFluidService.Access access, InventoryFluidAdmission admission) {
			try (var operation = admission.enter(new OperationProfile(InventoryValidationBudget.NONE, members.length + 1L, members.length + 1L, 0))) {
				if (!memberGate(binding, binding.root(), operation, true) || !authority(binding, operation)) return false;
				for (Witness witness : members) if (!memberGate(binding, witness.position(), operation, true) || !authority(binding, operation)) return false;
			}
			try (var operation = admission.enter(access.validationProfile())) { if (!access.valid(operation)) return false; }
			if (!sameLayout(access)) return false;
			if (memberWitness != null) {
				var profile = FluidAddressWitness.profile(WitnessUse.COMPARE, members.length);
				// One callback-free loaded-map lookup per member, separate from the helper's vector
				// construction/comparison cost. All existing member/authority/native checks above stay.
				var lookups = new InventoryValidationBudget.Cost(256, 256, 16L + 8L * members.length);
				try (var operation = admission.enter(profile.plus(owned(lookups)))) {
					operation.allowance().debit(lookups);
					operation.allowance().requireAvailable(profile.ownedCost());
					Object[] current = new Object[members.length];
					// A later member/authority callback can replace an earlier member. Build the vector
					// only after ALL checks, from the loaded-existing-BE map (no provider callbacks).
					for (int index = 0; index < members.length; index++) {
						@Nullable BlockEntity entity = world.existingEntity(binding.level(), members[index].position());
						if (entity == null) return false; current[index] = entity;
					}
					requireSame(memberWitness.compare(FluidAddressWitness.Schema.COMMON_MEMBERS_V1, current, operation));
				}
			}
			try (var operation = admission.enter(access.witnessProfile(WitnessUse.COMPARE))) {
				requireSame(access.compareWitness(addressWitness, operation));
			}
			return true;
		}
		private static void requireSame(WitnessMatch match) {
			if (match == WitnessMatch.UNVERIFIABLE) throw unavailable("fluid historical identity evidence unverifiable");
			if (match != WitnessMatch.SAME) throw unavailable("fluid historical identity changed");
		}
		@Override public SourceKey key() { return key; }
		@Override public boolean stableCursor() { return stable; }
		@Override public int slots() { return slots; }
		@Override public boolean scopeTransition() { return scopeTransition; }
		@Override public InventorySourceAccess.ReadMode readMode() { return stable ? InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL : InventorySourceAccess.ReadMode.ADAPTER; }
		@Override public long sourceRetainedBytes() { return 0; } // already independently owned by the Runtime admission tickets
		@Override public long nativeResourceRetainedBytes() { return catalog == null ? 0 : catalog.modeledRetainedBytes(); }
		@Override public long nativeCatalogGrowthUpperBound() { return InventoryFluidCodec.Catalog.retainedGrowthUpperBound(); }
		@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return InventoryValidationBudget.NONE; }
		@Override public InventoryValidationBudget.Cost readValidationCost(int count) { return InventoryValidationBudget.NONE; }
		@Override public boolean certifiedResourceSharing() { return false; }
		@Override public int displayBytes() { return displayBytes; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public void tightenDisplay(int ceiling) { displayBytes = Math.min(displayBytes, ceiling); }
		@Override public boolean valid() { throw new IllegalStateException("fluid validity requires Runtime grants"); }
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) {
			try { return withAccess(allowance, this::validate); }
			catch (InventorySourceUnavailableException unavailable) { return false; }
		}
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new UnsupportedOperationException("fluid resource admission required"); }
		@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission catalogAdmission, InventoryValidationBudget.Allowance allowance) {
			Objects.checkIndex(slot, slots); Objects.requireNonNull(catalogAdmission);
			return withAccess(allowance, (binding, access, admission) -> {
				if (!validate(binding, access, admission)) throw unavailable("fluid source invalid before getter");
				var cell = new Cell();
				try (var operation = admission.enter(access.readProfile())) {
					if (!access.consume(slot, entry -> { if (cell.visited) throw new IllegalStateException("multiple fluid visits"); cell.visited = true;
						cell.value = freeze(entry, world.registries(binding.level()), catalogAdmission); return true; }, operation) || !cell.visited) throw unavailable("fluid visitor failed");
				}
				if (!validate(binding, access, admission)) throw unavailable("fluid source invalid after getter");
				world.used(access.routeInfo()); return cell.value;
			});
		}
		private @Nullable InventoryFluidValue freeze(IPlatformFluidService.Entry entry, HolderLookup.Provider registries, InventoryCatalogAdmission admission) {
			if (catalog == null) catalog = new InventoryFluidCodec.Catalog(); return InventoryFluidFreezer.freeze(entry, registries, displayBytes, catalog, admission);
		}
		@Override public InventorySourceAccess.ResourcePage enumerateResources(int limit, @Nullable InventoryCatalogAdmission catalogAdmission, InventoryValidationBudget.Allowance allowance) {
			Objects.requireNonNull(catalogAdmission);
			return withAccess(allowance, (binding, access, admission) -> {
				if (!validate(binding, access, admission)) throw unavailable("fluid source invalid before enumeration");
				var values = new ArrayList<@Nullable InventoryResourceValue>(limit);
				IPlatformFluidService.Visit visit;
				try (var operation = admission.enter(access.observationProfile(limit))) {
					visit = access.observe(limit, entry -> { if (values.size() >= limit) throw new IllegalStateException("fluid visit overflow");
						values.add(freeze(entry, world.registries(binding.level()), catalogAdmission)); return true; }, operation);
				}
				if (visit.visited() != values.size() || !validate(binding, access, admission)) throw unavailable("fluid source invalid after enumeration");
				if (visit.visited() != 0) world.used(access.routeInfo());
				return new InventorySourceAccess.ResourcePage(values, visit.complete());
			});
		}
		@Override public void releaseReadData() { catalog = null; }
		@Override public void close() {
			if (!closed) { closed = true; catalog = null; try { if (ordinaryAccess != null) ordinaryAccess.close(); }
				finally { ordinaryAccess = null; ownership.close(); } }
		}
	}
	@NotNullByDefault @FunctionalInterface
	private interface Action<T> { T run(Binding binding, IPlatformFluidService.Access access, InventoryFluidAdmission admission); }
	@NotNullByDefault private static final class Cell { boolean visited; @Nullable InventoryFluidValue value; }
	private static InventorySourceUnavailableException unavailable(String message) { return new InventorySourceUnavailableException(message); }
	private static String digest(String value) {
		try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
}
