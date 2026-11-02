package nx.pingwheel.common.integration.externalblock;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidWorldTest;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ExternalBlockMembershipTest {
	private static final InventoryValidationBudget.Cost QUERY = new InventoryValidationBudget.Cost(64, 64, 4);
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void absentProvidersAreOutsideButUnsupportedFailureAndConflictingPositiveEvidenceStayUnknown() {
		var world = world(); var registry = new ExternalBlockServerProviderRegistry();
		assertEquals(ExternalBlockServerProvider.Membership.OUTSIDE, query(registry, world));
		var provider = new Provider("first"); registry.register(provider);
		assertEquals(ExternalBlockServerProvider.Membership.UNKNOWN, query(registry, world));
		provider.value = ExternalBlockServerProvider.Membership.OUTSIDE;
		assertEquals(ExternalBlockServerProvider.Membership.OUTSIDE, query(registry, world));
		provider.failure = new IllegalStateException("containing query failed");
		assertEquals(ExternalBlockServerProvider.Membership.UNKNOWN, query(registry, world)); provider.failure = null;
		provider.value = ExternalBlockServerProvider.Membership.inside("scope-one");
		assertEquals(provider.value, query(registry, world));
		var second = new Provider("second"); second.value = ExternalBlockServerProvider.Membership.inside("scope-two"); registry.register(second);
		assertEquals(ExternalBlockServerProvider.Membership.UNKNOWN, query(registry, world));
		assertThrows(InventoryItemBudget.CaptureLimitException.class,
			() -> registry.membership(world, BlockPos.ZERO, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)));
		assertEquals(0, world.stateReads);
	}
	@Test void actualOrdinaryBindingRejectsInsideAndUnknownBeforeStateButMissingIntegrationDoesNotDisableVanilla() {
		var registry = ExternalBlockServerProviders.registry(); var previous = registry.providers(); registry.clear();
		try {
			var world = world(); var target = new Target.BlockTarget("minecraft:overworld", 0, 64, 0, "minecraft:chest");
			assertTrue(MinecraftBlockReadSources.resolveOrdinary(world, target, new InventoryValidationBudget.Allowance(registry.membershipCost())).isPresent());
			assertEquals(1, world.stateReads); world.stateReads = 0;
			var provider = new Provider("membership-fixture"); registry.register(provider);
			for (var evidence : List.of(ExternalBlockServerProvider.Membership.UNKNOWN, ExternalBlockServerProvider.Membership.inside("scope"))) {
				provider.value = evidence;
				assertTrue(MinecraftBlockReadSources.resolveOrdinary(world, target, new InventoryValidationBudget.Allowance(registry.membershipCost())).isEmpty());
				assertEquals(0, world.stateReads);
			}
			provider.value = ExternalBlockServerProvider.Membership.OUTSIDE;
			assertTrue(MinecraftBlockReadSources.resolveOrdinary(world, target, new InventoryValidationBudget.Allowance(registry.membershipCost())).isPresent());
			assertEquals(1, world.stateReads);
		} finally { registry.clear(); previous.forEach(registry::register); }
	}
	private static ExternalBlockServerProvider.Membership query(ExternalBlockServerProviderRegistry registry, World world) {
		var allowance = new InventoryValidationBudget.Allowance(registry.membershipCost());
		var result = registry.membership(world, BlockPos.ZERO, allowance);
		assertEquals(registry.membershipCost().workUnits(), allowance.attemptedWorkUnits()); return result;
	}
	private static World world() {
		var world = DoubleChestFixture.allocate(World.class); world.server = DoubleChestFixture.allocate(InventoryFluidWorldTest.Server.class); return world;
	}
	@NotNullByDefault
	private static final class World extends ServerLevel {
		private @Nullable MinecraftServer server;
		private int stateReads;
		private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public MinecraftServer getServer() { return java.util.Objects.requireNonNull(server); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public boolean isOutsideBuildHeight(BlockPos pos) { return false; }
		@Override public boolean isLoaded(BlockPos pos) { return true; }
		@Override public BlockState getBlockState(BlockPos pos) { stateReads++; return Blocks.CHEST.defaultBlockState(); }
	}
	@NotNullByDefault
	private static final class Provider implements ExternalBlockServerProvider {
		private final String id;
		private Membership value = Membership.UNKNOWN;
		private @Nullable RuntimeException failure;
		private Provider(String id) { this.id = id; }
		@Override public String providerId() { return id; }
		@Override public InventoryValidationBudget.Cost membershipCost() { return QUERY; }
		@Override public Membership membership(ServerLevel level, BlockPos pos, InventoryValidationBudget.Allowance allowance) {
			allowance.debit(QUERY); if (failure != null) throw failure; return value;
		}
		@Override public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget target) { return new ValidationResult.Invalid(); }
		@Override public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget target) { throw new AssertionError("membership materialized"); }
		@Override public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget target) { return new RefreshResult.Invalid(); }
		@Override public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget target) { return Optional.empty(); }
		@Override public void release(MinecraftServer server, Target.ExternalBlockTarget target) { throw new AssertionError("membership released"); }
	}
}
