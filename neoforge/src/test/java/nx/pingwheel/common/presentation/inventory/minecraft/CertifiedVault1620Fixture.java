package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.ByteMultiplier;
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
import static org.junit.jupiter.api.Assertions.*;

/** Reusable native-certified baseline. No resident getter, handler wrapper or production instrumentation. */
@NotNullByDefault
final class CertifiedVault1620Fixture {
	static final int MEMBERS = 81, SLOTS = 1620;
	static final UUID OWNER = new UUID(1620, 1);
	static final Field PHYSICAL = field(InventoryRuntime.class, "physical");
	private CertifiedVault1620Fixture() {}
	@NotNullByDefault enum Contents { EMPTY, REPEATED, MIXED }
	@NotNullByDefault enum Profile {
		INCIDENT, DEFAULTS;
		InventorySettings settings() {
			var settings = InventorySettings.serverDefaults();
			if (this == INCIDENT) {
				// Representative copy of the directly inspected 2026-10-31 feedback file, NOT a runtime file dependency.
				settings.setPhysicalSlotsPerTick(IntLimit.finite(1024)); settings.setPendingMemoryMiB(16);
				settings.setDisplayByteMultiplier(BigDecimal.ONE); settings.setFrameByteMultiplier(BigDecimal.ONE);
				var p = settings.getPreview(); p.setPeriodTicks(5);
				p.setMaxVariantsPerClientPeriod(IntLimit.finite(32)); p.setMaxSlotsPerClient(IntLimit.finite(128));
				p.setMaxSlotsServer(IntLimit.finite(1024)); p.setMaxTargetsPerClient(IntLimit.finite(2));
				p.setClientByteMultiplier(ByteMultiplier.finite(BigDecimal.ONE)); p.setGlobalByteMultiplier(ByteMultiplier.finite(BigDecimal.ONE));
				var t = settings.getTracking(); t.setPeriodTicks(3); t.setDistinguishFaces(false); t.setDistinguishPlayers(false);
				t.setMaxVariantsPerTarget(IntLimit.finite(8)); t.setMaxSlotsPerTarget(IntLimit.finite(512)); t.setMaxSlotsServer(IntLimit.finite(4096));
				t.setStreamByteMultiplier(ByteMultiplier.finite(BigDecimal.ONE)); t.setSnapshotByteMultiplier(ByteMultiplier.finite(BigDecimal.ONE));
				t.setGlobalByteMultiplier(ByteMultiplier.finite(BigDecimal.ONE)); t.setResyncMinPeriods(5); t.setHeartbeatPeriods(16); t.setGracePeriods(4);
			}
			String configured = settings.toString(); settings.validate();
			assertEquals(configured, settings.toString(), "benchmark input must already be legal; never measure silently clamped incident settings");
			return settings;
		}
	}
	static Target.BlockTarget target(BlockPos pos) { return new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), "create:item_vault"); }
	static InventorySourceInput input(BlockPos pos, BlockFace face, UUID owner) { return new InventorySourceInput(target(pos), owner, face); }
	static ItemStack stack(Contents contents, int address) {
		if (contents == Contents.EMPTY) return ItemStack.EMPTY;
		var stack = new ItemStack(contents == Contents.MIXED && address % 3 == 2 ? Items.DIRT : Items.STONE, 3_000_003 + address);
		if (contents == Contents.MIXED && address % 3 == 1) stack.set(DataComponents.CUSTOM_NAME, Component.literal("explicit vault sample"));
		if (contents == Contents.MIXED && address % 3 == 2) stack.set(DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(17));
		return stack;
	}
	static Field field(Class<?> type, String name) {
		try { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	static @Nullable Object get(Object owner, String name) {
		try { return field(owner.getClass(), name).get(owner); }
		catch (IllegalAccessException failure) { throw new AssertionError(failure); }
	}
	static long physical(InventoryRuntime runtime) {
		try { return ((CostLedger) PHYSICAL.get(runtime)).used(InventorySourceAccess.PHYSICAL); }
		catch (IllegalAccessException failure) { throw new AssertionError(failure); }
	}
	@NotNullByDefault
	static final class World implements InventoryMinecraftSources.SourceWorld, InventoryRuntime.SourceResolver {
		final PooledVaultFixture vault;
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		long resolutions, views, loaded, states, blockEntities, heldKeys, checks, registries, threadChecks;
		InventoryMinecraftSources.@Nullable Source lastSource;
		World(Contents contents) {
			vault = new PooledVaultFixture(9, address -> stack(contents, address));
			for (BlockPos pos : vault.members.keySet()) entities.put(pos, new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState()));
		}
		@Override public boolean isLoaded(BlockPos pos) { loaded++; return vault.available && entities.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { states++; return java.util.Objects.requireNonNull(entities.get(pos)).getBlockState(); }
		@Override public BlockEntity blockEntity(BlockPos pos) { blockEntities++; return java.util.Objects.requireNonNull(entities.get(pos)); }
		@Override public boolean onServerThread() { threadChecks++; return true; }
		@Override public ItemStack heldKey() { heldKeys++; return ItemStack.EMPTY; }
		@Override public InventorySharedRead.Check readCheck(BlockEntity entity, @Nullable ItemStack key) { checks++; return InventoryMinecraftSources.SourceWorld.super.readCheck(entity, key); }
		@Override public HolderLookup.Provider registries() { registries++; return RegistryAccess.EMPTY; }
		@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted resolution"); }
		InventoryMinecraftSources.Source source(InventorySourceInput input, int cap) {
			views++; var original = InventoryMinecraftSources.view(this, input, vault::find);
			var source = new InventoryMinecraftSources.Source(input, original, this::onServerThread,
				() -> { views++; return InventoryMinecraftSources.view(this, input, vault::find); },
				entry -> { throw new AssertionError("compatibility path is not certified"); }, this, null, cap, ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY);
			var port = java.util.Objects.requireNonNull(source.sharedRead(), "native certification must be active");
			assertEquals(InventorySharedRead.Route.CREATE_VAULT_LOCAL, port.proof().graph().route());
			assertEquals(SLOTS, port.proof().graph().slots()); assertEquals(MEMBERS, port.proof().members().size());
			lastSource = source; return source;
		}
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
			allowance.debit(resolutionValidationCost(input)); resolutions++; return Optional.of(source(input, cap));
		}
		void resetCounters() { resolutions = views = loaded = states = blockEntities = heldKeys = checks = registries = threadChecks = 0; vault.resetCounters(); }
	}
	@NotNullByDefault
	static final class Run implements AutoCloseable {
		final World world;
		final InventorySettings settings;
		final InventoryRuntime runtime;
		final InventoryRuntime.Consumer consumer;
		InventoryRuntime.@Nullable Observation last;
		long tick, physicalVisits, total;
		int slots, pages, completions, defers, invalid;
		boolean countPhysical;
		Run(Contents contents, Profile profile) {
			settings = profile.settings(); world = new World(contents);
			runtime = new InventoryRuntime(world, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
			runtime.advance(0, settings);
			consumer = runtime.attach(input(BlockPos.ZERO, BlockFace.NORTH, OWNER), new InventoryRuntime.TrackingSubject(TargetKey.from(target(BlockPos.ZERO)))).orElseThrow();
		}
		void prepare() {
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer), "exact validated profile must admit metadata without raising caps");
			assertEquals(0, physical(runtime), "preparation is metadata only"); assertPooled();
		}
		void assertPooled() {
			var round = java.util.Objects.requireNonNull(get(consumer, "round"));
			assertNotNull(get(round, "sweep"), "must execute certified pooled path, not legacy native path");
		}
		void step(int bound) {
			runtime.advance(++tick, settings);
			last = runtime.step(consumer, bound).orElse(null);
			if (countPhysical) physicalVisits += physical(runtime);
			if (last == null) { defers++; return; }
			pages++; slots += last.slots().size();
			for (var item : last.slots()) if (item != null) total = Math.addExact(total, item.count());
			if (last.result().availability() != CaptureResult.Availability.READABLE) invalid++;
			if (last.result().completeness() == CaptureResult.Completeness.COMPLETE) completions++;
		}
		void sweep() {
			while (completions == 0 && invalid == 0 && tick < 256) step(32);
			assertEquals(1, completions, "stable native source did not progress under unchanged validated settings: slots=" + slots + " defers=" + defers);
		}
		boolean probe() { runtime.advance(++tick, settings); return runtime.probe(consumer).orElseThrow(); }
		@Override public void close() { runtime.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved()); }
	}
}
