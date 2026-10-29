package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Port;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.ReadFailure;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.neoforge.integration.create.presentation.CertifiedVaultFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryCertifiedVaultTest {
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }
	@NotNullByDefault
	static final class World implements InventoryMinecraftSources.SourceWorld {
		final CertifiedVaultFixture vault = new CertifiedVaultFixture();
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		boolean owner = true;
		int callbacks;
		World() { for (BlockPos pos : vault.members.keySet()) entities.put(pos, new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState())); }
		@Override public boolean isLoaded(BlockPos pos) { callbacks++; return entities.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { callbacks++; return java.util.Objects.requireNonNull(entities.get(pos)).getBlockState(); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { callbacks++; return entities.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public @Nullable ItemStack readOwnerKey() { callbacks++; return owner ? ItemStack.EMPTY : null; }
		@Override public HolderLookup.Provider registries() { callbacks++; return RegistryAccess.EMPTY; }
		InventoryMinecraftSources.Source source(BlockPos pos, BlockFace face, long owner) {
			var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), "create:item_vault"), new UUID(6, owner), face);
			var view = InventoryMinecraftSources.view(this, input, vault::find);
			return new InventoryMinecraftSources.Source(input, view, () -> true, () -> { throw new AssertionError("rebuild"); }, entry -> null, this, null);
		}
	}
	static InventoryValidationBudget.Allowance validation(Port port) { return new InventoryValidationBudget.Allowance(port.validationCost()); }
	static InventoryCatalogAdmission catalogGrant() { return new InventoryCatalogAdmission(
		InventoryCatalogAdmission.workspaceUpperBound(InventoryItemCodec.MAX_CANONICAL_BYTES, 4096, InventoryItemCodec.DEFAULT_MAX_ENTRIES),
		InventoryCatalogAdmission.workUpperBound(4096)); }
	@Test void typedCommonSourcesCertifyDifferentOwnerFaceAndHitWithNoResidentAuditAndReadCurrentReplacement() {
		var world = new World();
		try (var source = world.source(BlockPos.ZERO, BlockFace.NORTH, 1); var peer = world.source(new BlockPos(1, 0, 0), BlockFace.SOUTH, 2)) {
			var a = java.util.Objects.requireNonNull(source.sharedRead()); var b = java.util.Objects.requireNonNull(peer.sharedRead());
			var access = world.vault.find(BlockPos.ZERO, net.minecraft.core.Direction.NORTH).orElseThrow();
			assertInstanceOf(nx.pingwheel.common.platform.IPlatformInventoryService.CertifiedSharedAccess.class, access);
			assertFalse(access.guardedBy(() -> true) instanceof nx.pingwheel.common.platform.IPlatformInventoryService.CertifiedSharedAccess);
			assertEquals(a.proof(), b.proof()); assertEquals(0, world.vault.audits);
			assertEquals(Check.VALID, b.validate(a.proof(), validation(b))); assertEquals(0, world.vault.audits);
			assertEquals(2, a.proof().graph().slots()); assertNotEquals(source.key().readScope(), peer.key().readScope());
			var catalog = a.createCatalog(4096, new InventoryValidationBudget.Allowance(a.catalogCreationCost()));
			var first = a.read(0, a.proof(), catalog, catalogGrant(), new InventoryValidationBudget.Allowance(a.readValidationCost(1)));
			var current = new ItemStackHandler(1); current.setStackInSlot(0, new ItemStack(Items.STONE, 2_000_000_002));
			world.vault.members.get(new BlockPos(1, 0, 0)).storage = current;
			assertEquals(Check.VALID, b.validate(a.proof(), validation(b)));
			var second = b.read(1, a.proof(), catalog, catalogGrant(), new InventoryValidationBudget.Allowance(b.readValidationCost(1)));
			assertNotNull(first); assertNotNull(second); assertEquals(4_000_000_003L, first.count() + second.count());
			assertEquals(first.key(), second.key()); assertEquals(1, catalog.displays().size()); assertNull(source.nativeCatalog()); assertNull(peer.nativeCatalog());
		}
	}
	@Test void sameTotalsCannotHideMemberReplacementLocalBoundaryRedistributionOrUnsafeLocalDeclaration() {
		for (String change : List.of("member", "boundaries", "unsafe", "owner", "route")) {
			var world = new World();
			try (var source = world.source(BlockPos.ZERO, BlockFace.NORTH, 1)) {
				var port = java.util.Objects.requireNonNull(source.sharedRead());
				switch (change) {
					case "member" -> world.entities.put(BlockPos.ZERO, new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState()));
					case "boundaries" -> {
						world.vault.members.get(BlockPos.ZERO).storage = new ItemStackHandler(0);
						world.vault.members.get(new BlockPos(1, 0, 0)).storage = new ItemStackHandler(2);
					}
					case "unsafe" -> world.vault.members.get(BlockPos.ZERO).storage = new ItemStackHandler(1) {
						@Override public ItemStack getStackInSlot(int slot) { throw new AssertionError("uncertified getter"); }
					};
					case "owner" -> world.owner = false;
					case "route" -> world.vault.route = false;
					default -> throw new AssertionError(change);
				}
				assertEquals(change.equals("owner") ? Check.AUTHORITY_DENIED : change.equals("unsafe") || change.equals("route") ? Check.UNAVAILABLE
					: Check.STRUCTURE_INVALID, port.validate(port.proof(), validation(port)), change);
				assertEquals(0, world.vault.audits);
			}
		}
	}
	@Test void metadataAndOwnershipCallbacksCannotAuthorizeRevokedOwnerOrPhysicalMemberBeforeGetter() {
		for (String boundary : List.of("metadata", "audit")) for (boolean authority : List.of(true, false)) {
			var world = new World();
			try (var source = world.source(BlockPos.ZERO, BlockFace.NORTH, 1)) {
				var port = java.util.Objects.requireNonNull(source.sharedRead());
				var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
				Runnable revoke = () -> { if (authority) world.owner = false; else world.entities.put(BlockPos.ZERO, new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState())); };
				if (boundary.equals("metadata")) world.vault.metadata = revoke; else world.vault.afterAudit = revoke;
				assertEquals(authority ? Check.AUTHORITY_DENIED : Check.STRUCTURE_INVALID,
					assertThrows(ReadFailure.class, () -> port.read(0, port.proof(), catalog, catalogGrant(),
						new InventoryValidationBudget.Allowance(port.readValidationCost(1)))).check());
				assertTrue(catalog.displays().isEmpty());
			}
		}
	}
	@Test void admissionAndBorrowedLifetimeRemainSeparateFromSharedCatalogOwnership() {
		var world = new World(); var a = world.source(BlockPos.ZERO, BlockFace.NORTH, 1); var b = world.source(new BlockPos(1, 0, 0), BlockFace.SOUTH, 2);
		var port = java.util.Objects.requireNonNull(a.sharedRead()); var peer = java.util.Objects.requireNonNull(b.sharedRead());
		var catalog = port.createCatalog(4096, new InventoryValidationBudget.Allowance(port.catalogCreationCost()));
		int calls = world.callbacks + world.vault.locals;
		assertEquals(Check.DEFERRED, port.validate(port.proof(), new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertEquals(calls, world.callbacks + world.vault.locals);
		a.close(); assertEquals(Check.UNAVAILABLE, port.validate(port.proof(), validation(port)));
		assertNotNull(peer.read(1, port.proof(), catalog, catalogGrant(), new InventoryValidationBudget.Allowance(peer.readValidationCost(1))));
		b.close(); assertNull(b.sharedRead());
	}
	@Test void externalWrapperNeverPromotesNestedCertifiedVaultAccess() {
		var world = new World();
		var target = Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "create:item_vault", "certified-vault", true);
		var input = new InventorySourceInput(target, new UUID(6, 1), BlockFace.NORTH);
		var descriptor = new nx.pingwheel.common.integration.externalblock.BlockReadSource(target, "sable", "sublevel",
			new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new nx.pingwheel.common.marker.MarkerAnchor(0, 0, 0));
		var binding = new InventoryMinecraftSources.ExternalBinding(world, descriptor, world.entities::containsKey, world.vault::find);
		try (var external = InventoryMinecraftSources.resolveExternal(input, () -> Optional.of(binding), () -> true, entry -> null).orElseThrow()) {
			assertNull(external.sharedRead()); assertTrue(external.valid()); assertEquals(0, world.vault.audits);
		}
	}
}
