package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.neoforge.integration.create.presentation.PooledVaultFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPooledVaultRuntimeTest {
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }
	@NotNullByDefault
	static final class World implements InventoryRuntime.SourceResolver {
		final PooledVaultFixture vault = new PooledVaultFixture();
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		int resolutions;
		World() { for (BlockPos pos : vault.members.keySet()) entities.put(pos, new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState())); }
		@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted resolution"); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
			allowance.debit(resolutionValidationCost(input)); resolutions++;
			var world = new InventoryMinecraftSources.SourceWorld() {
				@Override public Object worldIdentity() { return World.this; }
				@Override public boolean isLoaded(BlockPos pos) { return vault.available && entities.containsKey(pos); }
				@Override public BlockState blockState(BlockPos pos) { return java.util.Objects.requireNonNull(entities.get(pos)).getBlockState(); }
				@Override public @Nullable BlockEntity blockEntity(BlockPos pos) { return entities.get(pos); }
				@Override public boolean onServerThread() { return true; }
				@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
				@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
			};
			var view = InventoryMinecraftSources.view(world, input, vault::find);
			var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> InventoryMinecraftSources.view(world, input, vault::find),
				entry -> { throw new AssertionError("compatibility"); }, world, null, cap, ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY);
			assertNotNull(source.sharedRead()); assertEquals(900, source.sharedRead().proof().graph().slots());
			return Optional.of(source);
		}
	}
	static long physical(InventoryRuntime runtime) throws ReflectiveOperationException {
		Field field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); return ((CostLedger) field.get(runtime)).used(InventorySourceAccess.PHYSICAL);
	}
	@ParameterizedTest @CsvSource({"true,true,3600", "true,false,1800", "false,true,1800", "false,false,900"})
	void actualSegmentedToSourceToRuntimeReadsOneOrdered900SlotSweepPerPolicyCohort(boolean faces, boolean players, int expectedReads) throws ReflectiveOperationException {
		var world = new World(); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(256); settings.setPhysicalSlotsPerTick(IntLimit.finite(64));
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(4096)); settings.getTracking().setMaxSlotsServer(IntLimit.finite(4096));
		settings.getTracking().setDistinguishFaces(faces); settings.getTracking().setDistinguishPlayers(players);
		var runtime = new InventoryRuntime(world, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
		long[] totals = new long[4]; boolean[] done = new boolean[4]; int[] counts = new int[4]; long reads = 0;
		try (runtime) {
			runtime.advance(0, settings);
			var target = new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(target));
			var consumers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 4; index++) {
				var pos = index == 0 ? BlockPos.ZERO : new BlockPos(4, 2, 2);
				var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), "create:item_vault"), new UUID(91, index / 2 + 1), index % 2 == 0 ? BlockFace.NORTH : BlockFace.SOUTH);
				consumers.add(runtime.attach(input, subject).orElseThrow());
			}
			for (int tick = 0; tick < 300 && !(done[0] && done[1] && done[2] && done[3]); tick++) {
				if (tick != 0) runtime.advance(tick, settings);
				for (int index = 0; index < 4; index++) if (!done[index]) {
					var observation = runtime.step(consumers.get(index), 32);
					if (observation.isEmpty()) continue;
					assertEquals(CaptureResult.Availability.READABLE, observation.get().result().availability());
					for (var item : observation.get().slots()) { totals[index] += java.util.Objects.requireNonNull(item).count(); counts[index]++; }
					if (observation.get().result().completeness() == CaptureResult.Completeness.COMPLETE) { done[index] = true; consumers.get(index).retainCompleted(); }
				}
				reads += physical(runtime);
			}
			for (int index = 0; index < 4; index++) { assertTrue(done[index]); assertEquals(900, counts[index]); assertEquals(2_700_407_250L, totals[index]); }
			assertEquals(expectedReads, reads); assertEquals(4, world.resolutions);
		} assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
}
