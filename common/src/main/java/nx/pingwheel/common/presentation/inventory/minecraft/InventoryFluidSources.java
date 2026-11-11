package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
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
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidService.CoordinatedAccess;
import nx.pingwheel.common.platform.IPlatformFluidService.Operation;
import nx.pingwheel.common.platform.IPlatformFluidService.OperationProfile;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessMatch;
import nx.pingwheel.common.platform.IPlatformFluidService.WitnessUse;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.Level;

/** Static fluid sources. Only detached metadata/object witnesses survive an external operation. */
@NotNullByDefault
public final class InventoryFluidSources {
	private InventoryFluidSources() {}
	private static final InventoryValidationBudget.Cost BIND = new InventoryValidationBudget.Cost(8192, 8192, 2048);
	private static final InventoryValidationBudget.Cost MEMBER = new InventoryValidationBudget.Cost(1024, 1024, 512);
	private static final InventoryValidationBudget.Cost INDEX_LOOKUP = new InventoryValidationBudget.Cost(0, 0, 16);
	private static final InventoryValidationBudget.Cost TARGET_IDENTITY = FluidAddressWitness.profile(WitnessUse.COMPARE, 1).ownedCost();
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
		if (current == null) return false;
		long length = (long) current.getNamespace().length() + current.getPath().length() + 1;
		if (length > 256) throw InventoryProviderLimits.exceeded(ProviderLimitSite.FLUID_SOURCE_REGISTRY_ID, length, 256);
		return expected.equals(current.toString());
	}
	/** Frozen target-level identities for the root and, when declared, the source's necessary controller. */
	@NotNullByDefault
	private record Unit(@Nullable BlockPos controller, @Nullable String rootEntityId,
		@Nullable String controllerBlockId, @Nullable String controllerEntityId) {}
	@NotNullByDefault
	private static final class Source implements InventorySourceAccess.Source {
		// Own the stable facade, not its server. Resolved bindings and their target callbacks stay operation-local.
		private final World world;
		private final IPlatformFluidService service;
		private final InventorySourceInput input;
		private final BlockReadSource descriptor;
		private final InventorySnapshotLayout layout;
		private final Optional<String> alias;
		private final String expectedBlockId;
		private @Nullable Unit unit;
		private @Nullable BlockPos declaredController;
		private final @Nullable FluidAddressWitness rootWitness;
		private final @Nullable FluidAddressWitness controllerWitness;
		private final FluidAddressWitness addressWitness;
		private final SourceKey key;
		private final boolean stable, external;
		private final int slots;
		private final InventoryFluidAdmission.Ownership ownership;
		private IPlatformFluidService.@Nullable Access ordinaryAccess;
		private int displayBytes;
		private @Nullable InventoryFluidCodec.Catalog catalog;
		private boolean closed, scopeTransition;
		private boolean validationRejectionReported;
		Source(World world, IPlatformFluidService service, InventorySourceInput input, int displayBytes, InventoryValidationBudget.Allowance allowance) {
			this.world = world; this.service = service; this.input = input; this.displayBytes = displayBytes;
			external = input.target() instanceof Target.ExternalBlockTarget;
			expectedBlockId = expectedRegistryId(input.target());
			try (var admission = open(allowance)) {
				Binding binding = bind(admission);
				descriptor = binding.resolved().descriptor();
				if (InventoryFluidStrictValidation.requiresStrictValidation(descriptor))
					throw unavailable("fluid target requires specialized validation");
				IPlatformFluidService.@Nullable Access access = acquire(binding, admission);
				try {
					layout = access.layout(); alias = access.alias(); stable = access.stableCursor(); slots = access.slots();
					if (slots > InventorySourceAccess.MAX_SLOTS) throw InventoryProviderLimits.exceeded(ProviderLimitSite.FLUID_SHAPE_ADDRESSES, slots, InventorySourceAccess.MAX_SLOTS);
					if (slots < 0 || slots > InventorySourceAccess.MAX_SLOTS || !stable && slots != 0)
						throw unavailable("fluid address bound");
					long bytes = 2048L + 256L * layout.members().size() + 2L * (layout.layoutData().length() + layout.layoutId().length());
					var cost = new InventoryValidationBudget.Cost(bytes * 2, bytes * 2, 1024L + 64L * layout.members().size() + slots);
					try (var operation = admission.enter(new OperationProfile(cost.plus(INDEX_LOOKUP), 0, 0, 0))) {
						operation.allowance().debit(cost);
						int addresses = 0;
						@Nullable InventorySnapshotLayout.Member rootMember = null, controllerMember = null;
						for (var member : layout.members()) {
							if (member.position().equals(binding.root())) rootMember = member;
							if (layout.controller() != null && member.position().equals(layout.controller())) controllerMember = member;
							for (var segment : member.segments()) {
								if (!IPlatformFluidService.ADDRESS_SCHEMA.equals(segment.schemaId())) throw unavailable("fluid address schema");
								addresses = Math.addExact(addresses, segment.visibleSlots().size());
							}
						}
						if (stable && addresses != slots || stable && rootMember == null
							|| layout.controller() != null && controllerMember == null)
							throw unavailable("fluid root/address layout");
						unit = new Unit(layout.controller(), rootMember == null ? null : rootMember.blockEntityId(),
							controllerMember == null ? null : controllerMember.blockId(),
							controllerMember == null ? null : controllerMember.blockEntityId());
						@Nullable BlockPos declared = declaredController;
						if (declared != null && !declared.equals(layout.controller()))
							throw unavailable("fluid controller declaration mismatch");
						if (layout.controller() != null && !layout.controller().equals(binding.root()) && declared == null)
							throw unavailable("fluid controller target gate unavailable");
					}
					// Target-level strong objects (root and necessary controller) are frozen once.
					// Internal provider members are not copied or re-gated here.
					@Nullable BlockEntity rootEntity = world.existingEntity(binding.level(), binding.root());
					if (rootEntity == null) throw unavailable("fluid target identity");
					rootWitness = captureTarget(rootEntity, admission);
					@Nullable Unit target = unit;
					@Nullable BlockEntity controllerEntity = target == null || target.controller() == null
						|| target.controller().equals(binding.root()) ? null : world.existingEntity(binding.level(), target.controller());
					if (target != null && target.controller() != null && !target.controller().equals(binding.root()) && controllerEntity == null)
						throw unavailable("fluid controller identity");
					controllerWitness = controllerEntity == null ? null : captureTarget(controllerEntity, admission);
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
		private static String expectedRegistryId(Target target) {
			if (target instanceof Target.BlockTarget block) return block.blockRegistryId();
			if (target instanceof Target.ExternalBlockTarget external) return external.expectedBlockRegistryId();
			throw unavailable("fluid target kind");
		}
		private InventoryFluidAdmission open(InventoryValidationBudget.Allowance allowance) {
			return allowance.fluidGrants().open(allowance, memberCost(), authorityCost());
		}
		private InventoryValidationBudget.Cost memberCost() {
			// Each profiled member callback may gate the root or controller. Prepay its
			// current identity comparison with that callback, not another retained grant.
			return MEMBER.plus(INDEX_LOOKUP).plus(world.membershipCost()).plus(TARGET_IDENTITY);
		}
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
				(pos, operation) -> memberGate(binding, pos, operation), this::unitAuthority,
				(controller, operation) -> controllerGate(binding, controller, operation));
			var profile = service.probeProfile().plus(new OperationProfile(InventoryValidationBudget.NONE, 2, 2, 0));
			IPlatformFluidService.@Nullable Access access = null;
			try (var operation = admission.enter(profile)) {
				context.require(binding.root(), operation);
				var decision = service.probeDecision(context, operation);
				if (decision.unsupported()) {
					if (acquiredOnce) throw unavailable("fluid capability disappeared");
					throw new InventorySourceUnsupportedException();
				}
				if (!decision.accepted()) throw unavailable("fluid provider probe unavailable");
				try (var probe = Objects.requireNonNull(decision.value())) {
					access = probe.finish(admission).orElseThrow(() -> unavailable("no fluid capability"));
				}
				context.require(binding.root(), operation);
				acquiredOnce = true;
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
		/**
		 * Cheap source callback used by provider internals. The root and a declared controller keep
		 * their full target-level gate. Every other physical member is provider-owned: no Ping root
		 * binding, owner-lock/loot or membership traversal is repeated for it.
		 */
		private boolean memberGate(Binding binding, BlockPos pos, Operation operation) {
			if (!world.onServerThread()) return denied("server-thread", "member", pos, operation);
			if (pos.equals(binding.root())) {
				if (!targetGate(binding, pos, expectedBlockId, unit == null ? null : unit.rootEntityId(), true, "root", operation))
					return false;
				// The guarded wrapper invokes this root gate before and after each real provider
				// call, so the current binding/owner check encloses those calls without a
				// per-member root bind.
				if (!authority(binding, operation)) return denied("authority", "root", pos, operation);
				return true;
			}
			@Nullable Unit current = unit;
			@Nullable BlockPos controller = current == null ? declaredController : current.controller();
			if (controller != null && pos.equals(controller))
				return targetGate(binding, pos, current == null ? null : current.controllerBlockId(),
					current == null ? null : current.controllerEntityId(), true, "controller", operation);
			operation.allowance().debit(INDEX_LOOKUP);
			return true;
		}
		/** Provider-declared non-root controller; scope applies here exactly as it does to the root. */
		private boolean controllerGate(Binding binding, BlockPos controller, Operation operation) {
			if (!world.onServerThread()) return denied("server-thread", "controller", controller, operation);
			@Nullable Unit current = unit;
			if (current != null && (current.controller() == null || !controller.equals(current.controller())))
				return denied("controller-mismatch", "controller", controller, operation);
			declaredController = controller.immutable();
			return targetGate(binding, controller, current == null ? null : current.controllerBlockId(),
				current == null ? null : current.controllerEntityId(), true, "controller", operation);
		}
		/** Root/controller applicability only: scope, loaded object, expected registry and source-owner read authority. */
		private boolean targetGate(Binding binding, BlockPos pos, @Nullable String blockId, @Nullable String entityId,
			boolean rootScope, String scope, Operation operation) {
			operation.allowance().debit(MEMBER);
			if (!world.onServerThread()) return denied("server-thread", scope, pos, operation);
			if (rootScope && !world.contains(binding, pos, operation.allowance())) return denied("membership", scope, pos, operation);
			if (!world.loaded(binding.level(), pos)) return denied("unloaded", scope, pos, operation);
			@Nullable BlockEntity entity = world.existingEntity(binding.level(), pos);
			if (entity == null) return denied("entity-missing", scope, pos, operation);
			if (entity.getLevel() != binding.level()) return denied("level-changed", scope, pos, operation);
			if (!entity.getBlockPos().equals(pos)) return denied("position-changed", scope, pos, operation);
			if (entity.isRemoved()) return denied("entity-removed", scope, pos, operation);
			BlockState currentState = world.blockState(binding.level(), pos);
			if (entity.getBlockState().getBlock() != currentState.getBlock()) return denied("block-changed", scope, pos, operation);
			if (blockId != null && !registryMatches(blockId, BuiltInRegistries.BLOCK.getKey(currentState.getBlock())))
				return denied("block-registry-changed", scope, pos, operation);
			if (entityId != null && !registryMatches(entityId, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType())))
				return denied("entity-registry-changed", scope, pos, operation);
			if (!readable(entity, operation)) return denied("read-authority", scope, pos, operation);
			@Nullable Unit currentUnit = unit;
			@Nullable FluidAddressWitness witness = pos.equals(binding.root()) ? rootWitness
				: currentUnit != null && pos.equals(currentUnit.controller()) ? controllerWitness : null;
			if (witness != null) {
				operation.allowance().requireAvailable(TARGET_IDENTITY);
				requireSame(witness.compare(FluidAddressWitness.Schema.COMMON_MEMBERS_V1, new Object[] {entity}, operation));
			}
			return true;
		}
		/** One frozen target-level object witness; provider-internal members are not included. */
		private static FluidAddressWitness captureTarget(BlockEntity entity, InventoryFluidAdmission admission) {
			try (var operation = admission.enter(FluidAddressWitness.profile(WitnessUse.CAPTURE, 1))) {
				return FluidAddressWitness.capture(FluidAddressWitness.Schema.COMMON_MEMBERS_V1, new Object[] {entity}, operation);
			}
		}
		/** Root authorization is applied once around the provider operation by the source and guarded wrapper. */
		private boolean unitAuthority(Operation operation) {
			Objects.requireNonNull(operation).allowance();
			return world.onServerThread();
		}
		private boolean denied(String reason, String scope, BlockPos position, Operation operation) {
			operation.allowance().debit(INDEX_LOOKUP);
			throw new InventorySourceUnavailableException("fluid access outside current target scope",
				() -> "stage=member-gate reason=" + reason + " scope=" + scope + " member=" + position.toShortString()
					+ " owner=" + input.readOwner() + " face=" + input.face());
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
						changedScope = memberGate(binding, binding.root(), operation);
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
			try (var operation = admission.enter(new OperationProfile(InventoryValidationBudget.NONE, 2, 1, 0))) {
				if (!memberGate(binding, binding.root(), operation)) return validationRejected("root-member-gate", binding.root());
				@Nullable Unit current = unit;
				if (current != null && current.controller() != null && !current.controller().equals(binding.root())
					&& !memberGate(binding, current.controller(), operation))
					return validationRejected("controller-member-gate", current.controller());
			}
			// A provider that consumes an exception into false owns that failure's detailed report.
			try (var operation = admission.enter(access.validationProfile())) { if (!access.valid(operation)) return false; }
			if (!sameLayout(access)) return validationRejected("layout-changed", binding.root());
			if (rootWitness != null) compareTarget(rootWitness, binding.root(), binding, admission);
			@Nullable Unit currentUnit = unit;
			if (controllerWitness != null && currentUnit != null && currentUnit.controller() != null)
				compareTarget(controllerWitness, currentUnit.controller(), binding, admission);
			try (var operation = admission.enter(access.witnessProfile(WitnessUse.COMPARE))) {
				requireSame(access.compareWitness(addressWitness, operation));
			}
			validationRejectionReported = false;
			return true;
		}
		private void compareTarget(FluidAddressWitness witness, BlockPos position, Binding binding, InventoryFluidAdmission admission) {
			@Nullable BlockEntity entity = world.existingEntity(binding.level(), position);
			if (entity == null) throw unavailable("fluid target identity changed");
			try (var operation = admission.enter(FluidAddressWitness.profile(WitnessUse.COMPARE, 1))) {
				requireSame(witness.compare(FluidAddressWitness.Schema.COMMON_MEMBERS_V1, new Object[] {entity}, operation));
			}
		}
		private boolean validationRejected(String reason, BlockPos position) {
			if (!validationRejectionReported) {
				validationRejectionReported = true;
				Diagnostics.server().atDebug().event(Level.DEBUG,
					"inventory/fluid/valid", reason,
					() -> "operation=fluid/valid stage=validate member=" + position.toShortString()
						+ " members=" + layout.members().size() + " layout=" + layout.layoutId() + " slots=" + slots
						+ " owner=" + input.readOwner() + " face=" + input.face() + " target=" + input.target());
			}
			return false;
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
		@Override public long nativeCatalogGrowthUpperBound(int attempts) { return InventoryFluidCodec.Catalog.retainedGrowthUpperBound(attempts); }
		@Override public int nativeCatalogEntriesUpperBound(int attempts) { return catalog == null ? Math.min(1024, Math.max(0, attempts - 1)) : catalog.entriesUpperBound(Math.max(1, attempts)); }
		@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return InventoryValidationBudget.NONE; }
		@Override public InventoryValidationBudget.Cost readValidationCost(int count) { return InventoryValidationBudget.NONE; }
		@Override public boolean certifiedResourceSharing() { return false; }
		@Override public int displayBytes() { return displayBytes; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public void tightenDisplay(int ceiling) { displayBytes = Math.min(displayBytes, ceiling); }
		@Override public boolean valid() { throw new IllegalStateException("fluid validity requires Runtime grants"); }
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) {
			try { return withAccess(allowance, this::validate); }
			catch (InventorySourceUnavailableException unavailable) {
				if (!validationRejectionReported) {
					validationRejectionReported = true;
					Diagnostics.server().atDebug().failure(Level.DEBUG,
						"inventory/fluid/valid", "source-unavailable", () -> diagnosticDetail(unavailable), unavailable);
				}
				return false;
			}
		}
		private String diagnosticDetail(InventorySourceUnavailableException unavailable) {
			@Nullable String context = unavailable.diagnosticContext();
			return "operation=fluid/valid stage=validate source=" + key + " owner=" + input.readOwner()
				+ " face=" + input.face() + " target=" + input.target()
				+ (context == null ? "" : " context=" + context);
		}
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new UnsupportedOperationException("fluid resource admission required"); }
		private boolean reading, readPoisoned;
		private boolean acquiredOnce;
		private @Nullable InventoryFluidReadOperation activeRead;
		@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission catalogAdmission, InventoryValidationBudget.Allowance allowance) {
			if (reading) {
				readPoisoned = true;
				if (activeRead != null) activeRead.close();
				throw unavailable("fluid source read reentered");
			}
			Objects.checkIndex(slot, slots); Objects.requireNonNull(catalogAdmission);
			catalogAdmission.requireNativeRead(displayBytes, catalog == null ? 0 : catalog.entriesUpperBound());
			reading = true;
			try { return withAccess(allowance, (binding, access, admission) -> {
				if (!external) {
					var coordinated = IPlatformFluidService.coordinated(access);
					if (coordinated != null) return coordinatedRead(binding, access, admission, coordinated, slot, catalogAdmission);
				}
				if (!validate(binding, access, admission)) throw unavailable("fluid source invalid before getter");
				var cell = new Cell();
				try (var operation = admission.enter(access.readProfile())) {
					if (!access.consume(slot, entry -> { if (cell.visited) throw new IllegalStateException("multiple fluid visits"); cell.visited = true;
						cell.value = freeze(entry, world.registries(binding.level()), catalogAdmission); return true; }, operation) || !cell.visited) throw unavailable("fluid visitor failed");
				}
				if (!validate(binding, access, admission)) throw unavailable("fluid source invalid after getter");
				world.used(access.routeInfo()); return cell.value;
			}); } finally {
				if (activeRead != null) activeRead.close();
				activeRead = null; reading = false; readPoisoned = false;
			}
		}
		/**
		 * One coordinated ordinary read. The operation owns the two complete common validations and
		 * the synchronous freeze; the provider retains its full pre-getter current fence and one native getter.
		 * No external/Sable binding, Fabric/Forge access or custom/decorated access reaches here.
		 */
		private @Nullable InventoryResourceValue coordinatedRead(Binding binding, IPlatformFluidService.Access access,
			InventoryFluidAdmission admission, CoordinatedAccess coordinated, int slot, InventoryCatalogAdmission catalogAdmission) {
			var profile = coordinated.coordinatedResidentProfile().plus(InventoryFluidReadOperation.profile());
			try (var operation = admission.enter(profile)) {
				// Pay the state/handoff body before any provider, world or catalogue callback.
				operation.allowance().debit(profile.ownedCost());
				var currentCatalog = catalogForRead();
				var registries = world.registries(binding.level());
				if (readPoisoned || closed) throw unavailable("fluid source read invalidated before operation");
				var port = new InventoryFluidReadOperation.Port() {
					@Override public Object proof() { return binding; }
					@Override public IPlatformFluidService.Access wrapper() { return access; }
					@Override public InventoryValidationBudget.Allowance allowance() { return operation.allowance(); }
					@Override public boolean active(InventoryFluidReadOperation read) { return activeRead == read && reading && !readPoisoned && !closed; }
					@Override public boolean validate() { return Source.this.validate(binding, access, admission); }
				};
				var read = new InventoryFluidReadOperation(port, binding, access, coordinated, slot, Thread.currentThread(),
					currentCatalog, registries, catalogAdmission, displayBytes, operation.allowance());
				activeRead = read;
				try {
					coordinated.consumeCoordinatedResident(slot, read, operation);
					world.used(access.routeInfo()); return read.completed(port);
				} finally { read.close(); activeRead = null; }
			}
		}
		private InventoryFluidCodec.Catalog catalogForRead() {
			if (catalog == null) catalog = new InventoryFluidCodec.Catalog(); return catalog;
		}
		private @Nullable InventoryFluidValue freeze(IPlatformFluidService.Entry entry, HolderLookup.Provider registries, InventoryCatalogAdmission admission) {
			return InventoryFluidFreezer.freeze(entry, registries, displayBytes, catalogForRead(), admission);
		}
		@Override public InventorySourceAccess.ResourcePage enumerateResources(int limit, @Nullable InventoryCatalogAdmission catalogAdmission, InventoryValidationBudget.Allowance allowance) {
			Objects.requireNonNull(catalogAdmission);
			catalogAdmission.requireNativeRead(displayBytes, nativeCatalogEntriesUpperBound(limit));
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
			if (reading) readPoisoned = true;
			if (activeRead != null) activeRead.close();
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
