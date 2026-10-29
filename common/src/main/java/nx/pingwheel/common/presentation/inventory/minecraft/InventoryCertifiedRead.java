package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Graph;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Proof;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.ReadFailure;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Route;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.RouteEvidence;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Witness;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Narrow native address certificates. Construction is inside the source's admitted resolution workspace. */
@NotNullByDefault
final class InventoryCertifiedRead {
	private InventoryCertifiedRead() {}
	static @Nullable NativePort create(InventorySourceInput input, InventoryMinecraftSources.View view,
		InventoryMinecraftSources.SourceWorld world, BooleanSupplier closed) {
		try { return createSupported(input, view, world, closed); }
		catch (InventoryItemBudget.CaptureLimitException exhausted) { throw exhausted; }
		catch (RuntimeException | LinkageError unsupported) { return null; }
	}
	private static @Nullable NativePort createSupported(InventorySourceInput input, InventoryMinecraftSources.View view,
		InventoryMinecraftSources.SourceWorld world, BooleanSupplier closed) {
		if (!(input.target() instanceof Target.BlockTarget) || view.snapshotLayout() == null) return null;
		@Nullable Route route = null;
		if (view.access() instanceof IPlatformInventoryService.CertifiedSharedAccess provider) {
			if (view.providerMembers().size() != view.snapshotLayout().members().size() || view.providerMembers().isEmpty()) return null;
			route = provider.sharedReadRoute();
		}
		else if (view.access() == null && !view.entities().isEmpty()) {
			boolean trapped = view.entities().getFirst().getClass() == TrappedChestBlockEntity.class;
			if (view.entities().size() > 2 || view.entities().size() != view.snapshotLayout().members().size()) return null;
			@Nullable BlockPos previous = null;
			for (var entity : view.entities()) {
				if (!supportedChest(entity, entity.getBlockState(), trapped)) return null;
				BlockPos position = entity.getBlockPos();
				if (previous != null && compare(previous, position) >= 0) return null;
				previous = position;
			}
			String block = trapped ? "minecraft:trapped_chest" : "minecraft:chest";
			BlockPos first = view.snapshotLayout().members().getFirst().position();
			String alias = view.entities().size() == 1 ? VanillaInventorySource.simpleBlockId(input.target().dimensionId(), first, block)
				: VanillaInventorySource.chestAlias(input.target().dimensionId(), first, view.snapshotLayout().members().getLast().position(), block);
			if (!view.identity().equals(alias)) return null;
			route = trapped ? Route.VANILLA_TRAPPED_CHEST : Route.VANILLA_CHEST;
		}
		if (route == null) return null;
		var port = new NativePort(input, view, world, closed, route);
		return port.checked(port.proof) == Check.VALID ? port : null;
	}
	private static int compare(BlockPos first, BlockPos second) {
		int x = Integer.compare(first.getX(), second.getX()), y = Integer.compare(first.getY(), second.getY());
		return x != 0 ? x : y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
	}
	private static boolean supportedChest(BlockEntity entity, BlockState state, boolean trapped) {
		return entity.getClass() == (trapped ? TrappedChestBlockEntity.class : ChestBlockEntity.class)
			&& entity.getType() == (trapped ? BlockEntityType.TRAPPED_CHEST : BlockEntityType.CHEST)
			&& state.getBlock() == (trapped ? Blocks.TRAPPED_CHEST : Blocks.CHEST)
			&& state.getBlock().getClass() == (trapped ? TrappedChestBlock.class : ChestBlock.class)
			&& BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(trapped ? "minecraft:trapped_chest" : "minecraft:chest")
			&& BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(trapped ? "minecraft:trapped_chest" : "minecraft:chest");
	}
	@NotNullByDefault
	static final class NativePort implements InventorySharedRead.Port {
		private final InventorySourceInput input;
		private final InventoryMinecraftSources.View view;
		private final InventoryMinecraftSources.SourceWorld world;
		private final BooleanSupplier closed;
		private final Proof proof;
		private final @Unmodifiable List<BlockEntity> members;
		private final BlockState @Unmodifiable [] states;
		private final InventoryValidationBudget.Cost validationCost, creationCost;
		private final InventoryValidationBudget.Cost @Unmodifiable [] readCosts;
		private final long retainedBytes;
		NativePort(InventorySourceInput input, InventoryMinecraftSources.View view, InventoryMinecraftSources.SourceWorld world,
			BooleanSupplier closed, Route route) {
			this.input = input; this.view = view; this.world = world; this.closed = closed;
			members = view.access() == null ? view.entities() : view.providerMembers();
			var layout = Objects.requireNonNull(view.snapshotLayout());
			var canonical = new ArrayList<InventorySnapshotLayout.Member>(members.size());
			var identities = new ArrayList<Witness>(members.size());
			var routes = new ArrayList<RouteEvidence>(members.size());
			states = new BlockState[members.size()];
			for (int index = 0; index < members.size(); index++) {
				var member = layout.members().get(index);
				canonical.add(new InventorySnapshotLayout.Member(member.position(), member.blockId(), member.blockEntityId(),
					view.access() == null ? "member" : member.role(), member.segments()));
				identities.add(new Witness(members.get(index)));
				states[index] = world.blockState(member.position());
				routes.add(new RouteEvidence(new Witness(states[index].getBlock()), view.access() == null ? states[index].getValue(ChestBlock.TYPE) : null,
					view.access() == null && members.size() == 2 ? states[index].getValue(ChestBlock.FACING) : null));
			}
			// Only these audited routes establish a face-independent ordered physical address sequence.
			var canonicalLayout = new InventorySnapshotLayout(route.name(), view.access() == null ? "" : layout.layoutData(),
				view.access() == null ? layout.members().getFirst().position() : layout.controller(), canonical);
			var graph = new Graph(view.access() == null ? "minecraft" : "create", input.target().dimensionId(), view.identity(), route, canonicalLayout);
			proof = new Proof(graph, new Witness(world.worldIdentity()), new Witness(world.registries()), identities, routes);
			var memberCost = InventoryValidationBudget.currentMembers(members).times(2).plus(new InventoryValidationBudget.Cost(512, 512,
				131072 + 8L * graph.slots() + 1024L * members.size()));
			validationCost = view.access() instanceof IPlatformInventoryService.CertifiedSharedAccess provider
				? memberCost.plus(provider.sharedValidationCost()) : memberCost;
			creationCost = validationCost.times(2).plus(new InventoryValidationBudget.Cost(InventoryItemCodec.Catalog.initialModeledBytes(),
				InventoryItemCodec.Catalog.initialModeledBytes(), InventoryItemCodec.Catalog.initialModeledBytes()));
			var nativeCost = InventoryValidationBudget.nativeRead(layout, view.access() != null, view.access() != null);
			readCosts = new InventoryValidationBudget.Cost[InventorySourceAccess.MAX_STEP + 1];
			// Final typed recheck also covers a provider/read failure before normal postchecks finish.
			for (int slots = 0; slots < readCosts.length; slots++) readCosts[slots] = validationCost.times(6L * slots).plus(nativeCost.times(2L * slots));
			retainedBytes = InventorySourceRetention.layout(canonicalLayout) + 2048 + 3 * InventorySourceRetention.list(members.size())
				+ members.size() * 3L * (40 + 8) + InventorySourceRetention.array(states.length, 8)
				+ InventorySourceRetention.array(readCosts.length, 8) + (readCosts.length + 2L) * 64;
		}
		@Override public Proof proof() { return proof; }
		@Override public long retainedBytes() { return retainedBytes; }
		@Override public InventoryValidationBudget.Cost validationCost() { return validationCost; }
		@Override public InventoryValidationBudget.Cost catalogCreationCost() { return creationCost; }
		@Override public InventoryValidationBudget.Cost readValidationCost(int slots) {
			if (slots < 0 || slots >= readCosts.length) throw new IllegalArgumentException("shared read page bound");
			return readCosts[slots];
		}
		@Override public Check validate(Proof expected, InventoryValidationBudget.Allowance allowance) {
			try { allowance.debit(validationCost); }
			catch (InventoryItemBudget.CaptureLimitException deferred) { return Check.DEFERRED; }
			return checked(expected);
		}
		private Check checked(Proof expected) {
			try { return current(expected); }
			catch (InventoryItemBudget.CaptureLimitException exhausted) { throw exhausted; }
			catch (RuntimeException | LinkageError unavailable) { return Check.UNAVAILABLE; }
		}
		private Check current(Proof expected) {
			if (closed.getAsBoolean() || !world.onServerThread()) return Check.UNAVAILABLE;
			if (!proof.equals(expected) || !proof.world().is(world.worldIdentity()) || !proof.registries().is(world.registries())) return Check.STRUCTURE_INVALID;
			var target = input.ordinaryTarget().orElseThrow();
			var root = new BlockPos(target.x(), target.y(), target.z());
			if (!world.isLoaded(root)) return Check.UNAVAILABLE;
			if (!BuiltInRegistries.BLOCK.getKey(world.blockState(root).getBlock()).toString().equals(target.blockRegistryId())) return Check.STRUCTURE_INVALID;
			boolean originalFound = false;
			for (int index = 0; index < members.size(); index++) {
				var member = proof.graph().layout().members().get(index);
				BlockPos position = member.position();
				if (!world.isLoaded(position)) return Check.UNAVAILABLE;
				@Nullable BlockEntity entity = world.blockEntity(position);
				if (entity != members.get(index) || entity == null) return Check.STRUCTURE_INVALID;
				if (!entity.getBlockPos().equals(position)) return Check.STRUCTURE_INVALID;
				BlockState state = world.blockState(position);
				if (state.getBlock() != states[index].getBlock()
					|| !BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(member.blockId())
					|| !BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(member.blockEntityId())) return Check.STRUCTURE_INVALID;
				if (position.equals(root)) originalFound = true;
				if (view.access() == null && (!supportedChest(entity, state, proof.graph().route() == Route.VANILLA_TRAPPED_CHEST)
					|| !chestMapping(index, state))) return Check.STRUCTURE_INVALID;
				Check readable = world.readCheck(entity, world.readOwnerKey());
				if (readable != Check.VALID) return readable;
			}
			if (!originalFound) return Check.STRUCTURE_INVALID;
			// Source owner/member gates precede provider callbacks, just as on the legacy route.
			if (view.access() instanceof IPlatformInventoryService.CertifiedSharedAccess provider) {
				Check check = provider.validateSharedRead(Objects.requireNonNull(view.snapshotLayout()));
				if (check != Check.VALID) return check;
				for (var entity : members) {
					Check readable = world.readCheck(entity, world.readOwnerKey());
					if (readable != Check.VALID) return readable;
				}
			}
			// Lock/component validation can invoke an admitted codec. Do not let its
			// callback return a now-revoked owner or replaced physical member as valid.
			if (world.readOwnerKey() == null) return Check.AUTHORITY_DENIED;
			for (int index = 0; index < members.size(); index++) {
				BlockPos position = proof.graph().layout().members().get(index).position();
				if (!world.isLoaded(position)) return Check.UNAVAILABLE;
				if (world.blockEntity(position) != members.get(index) || world.blockState(position).getBlock() != states[index].getBlock()
					|| view.access() == null && !chestMapping(index, world.blockState(position))) return Check.STRUCTURE_INVALID;
				if (members.get(index).isRemoved()) return Check.UNAVAILABLE;
			}
			return Check.VALID;
		}
		private boolean chestMapping(int index, BlockState state) {
			ChestType type = states[index].getValue(ChestBlock.TYPE);
			if (state.getValue(ChestBlock.TYPE) != type) return false;
			if ((type == ChestType.SINGLE) != (members.size() == 1)) return false;
			if (members.size() == 2) {
				if (state.getValue(ChestBlock.FACING) != states[index].getValue(ChestBlock.FACING)) return false;
				var position = proof.graph().layout().members().get(index).position();
				var partner = proof.graph().layout().members().get(1 - index).position();
				if (!position.relative(ChestBlock.getConnectedDirection(state)).equals(partner)) return false;
				BlockState other = world.blockState(partner);
				if (other.getBlock() != state.getBlock() || other.getValue(ChestBlock.TYPE) == type || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
					|| other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
					|| !partner.relative(ChestBlock.getConnectedDirection(other)).equals(position)) return false;
			}
			var member = proof.graph().layout().members().get(index);
			if (member.segments().size() != 1) return false;
			var segment = member.segments().getFirst();
			var container = (Container) members.get(index);
			if (segment.localSlots() != 27 || segment.visibleSlots().size() != 27 || container.getContainerSize() != 27) return false;
			for (int slot = 0; slot < 27; slot++) if (segment.visibleSlots().get(slot) != slot) return false;
			int first = index * 27;
			if (view.slots().size() != members.size() * 27) return false;
			for (int slot = 0; slot < 27; slot++) if (view.slots().get(first + slot).container() != container || view.slots().get(first + slot).index() != slot) return false;
			return true;
		}
		@Override public InventoryItemCodec.Catalog createCatalog(int ceiling, InventoryValidationBudget.Allowance allowance) {
			InventoryMinecraftSources.requireDisplayBytes(ceiling);
			allowance.debit(creationCost); // before registry access, validation or allocation
			require(checked(proof));
			var catalog = new InventoryItemCodec.Catalog(world.registries(), ceiling);
			catalog.certifySharedRead(proof);
			require(checked(proof));
			return catalog;
		}
		@Override public InventoryDomainCodec.@Nullable Item read(int slot, Proof expected, InventoryItemCodec.Catalog catalog,
			InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
			Objects.checkIndex(slot, proof.graph().slots());
			allowance.debit(readCosts[1]); // the entire operation is admitted before any callback/getter
			admission.requireNativeRead(catalog.displayBytes());
			require(checked(expected));
			try {
				var registries = world.registries();
				if (!catalog.acceptsSharedRead(expected, registries)) throw new ReadFailure(Check.STRUCTURE_INVALID);
				if (view.access() == null) for (var member : members)
					if (!world.audited((Container) member)) throw new ReadFailure(Check.UNAVAILABLE);
				// Classification/registry callbacks cannot authorize a revoked owner or member.
				require(checked(expected));
				@Nullable var item = InventoryMinecraftSources.readFrozen(view, view.access(), slot, catalog, registries, catalog.displayBytes(),
					InventoryItemBudget.root(catalog.displayBytes()), admission, () -> require(checked(expected)));
				if (view.access() == null) for (var member : members)
					if (!world.audited((Container) member)) throw new ReadFailure(Check.UNAVAILABLE);
				require(checked(expected));
				return item;
			} catch (InventoryItemBudget.CaptureLimitException | InventoryItemCodec.CapacityExceededException exhausted) {
				Check after = checked(expected);
				if (after != Check.VALID) throw new ReadFailure(after, exhausted);
				throw exhausted;
			}
			catch (RuntimeException | LinkageError failure) {
				Check after = checked(expected);
				if (after != Check.VALID) throw new ReadFailure(after, failure);
				if (failure instanceof ReadFailure typed) throw typed;
				throw new ReadFailure(Check.UNAVAILABLE, failure);
			}
		}
		private static void require(Check check) { if (check != Check.VALID) throw new ReadFailure(check); }
	}
}
