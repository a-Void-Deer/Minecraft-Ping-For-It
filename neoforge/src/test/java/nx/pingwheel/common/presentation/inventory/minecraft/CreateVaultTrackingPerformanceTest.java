package nx.pingwheel.common.presentation.inventory.minecraft;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.InventoryStep;
import nx.pingwheel.common.presentation.inventory.client.ClientDisplayFoodTestPorts;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.neoforge.integration.create.presentation.VaultPerformanceFixture;
import nx.pingwheel.neoforge.integration.create.presentation.VaultPerformanceFixture.Contents;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable BEFORE/AFTER workload, not a latency threshold test. Actual common view -> Source.valid ->
 * native capture/freezer/catalogue -> runtime journal; real NeoForge Segmented and ItemStackHandler.
 * The two worlds and block entities are headless stand-ins, not ServerLevel, live Create or Mixins.
 * No backend/marker publisher is timed. Runtime pages are observations, NOT publication counts.
 */
@NotNullByDefault
class CreateVaultTrackingPerformanceTest {
	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new UUID(84, 1), BlockFace.NORTH);
	private static final int WARMUP = 3, SAMPLES = 5;
	private static final Field PHYSICAL = physicalField();
	private static volatile @Nullable Object retainedOutput;
	private static @Nullable RegistryAlias<?> blockAlias, entityAlias;
	private static Field physicalField() {
		try { var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); return field; }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static long physicalUsed(InventoryRuntime runtime) {
		try { return ((CostLedger) PHYSICAL.get(runtime)).used(InventorySourceAccess.PHYSICAL); }
		catch (IllegalAccessException failure) { throw new AssertionError(failure); }
	}

	@BeforeAll static void bootstrap() throws ReflectiveOperationException {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		// Only getKey identity is substituted, for non-Container BE stand-ins. Restore exact entries after this class.
		// Item/component registries, native storage, codecs and all production view checks remain untouched.
		blockAlias = new RegistryAlias<>(BuiltInRegistries.BLOCK, Blocks.BEEHIVE);
		entityAlias = new RegistryAlias<>(BuiltInRegistries.BLOCK_ENTITY_TYPE, BlockEntityType.BEEHIVE);
	}
	@AfterAll static void restoreRegistries() {
		if (entityAlias != null) entityAlias.close();
		if (blockAlias != null) blockAlias.close();
		retainedOutput = null;
	}

	@NotNullByDefault
	private static final class RegistryAlias<T> implements AutoCloseable {
		private final Map<T, Holder.Reference<T>> map;
		private final T value;
		private final Holder.Reference<T> previous;
		@SuppressWarnings("unchecked") RegistryAlias(Registry<T> registry, T value) throws ReflectiveOperationException {
			Field field = MappedRegistry.class.getDeclaredField("byValue"); field.setAccessible(true);
			map = (Map<T, Holder.Reference<T>>) field.get(registry); this.value = value;
			previous = java.util.Objects.requireNonNull(map.get(value));
			map.put(value, Holder.Reference.createStandAlone(registry.asLookup(), ResourceKey.create(registry.key(), ResourceLocation.parse("create:item_vault"))));
		}
		@Override public void close() { map.put(value, previous); }
	}

	static InventorySettings settings() {
		var settings = InventorySettings.serverDefaults();
		settings.setPhysicalSlotsPerTick(IntLimit.finite(32)); settings.setPendingMemoryMiB(256);
		settings.setDisplayByteMultiplier(new BigDecimal("4")); settings.setFrameByteMultiplier(new BigDecimal("4"));
		settings.getTracking().setPeriodTicks(4);
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(256));
		settings.getTracking().setMaxSlotsServer(IntLimit.finite(2048));
		settings.validate(); return settings;
	}

	@NotNullByDefault
	static final class World implements InventoryMinecraftSources.SourceWorld, InventoryRuntime.SourceResolver {
		final VaultPerformanceFixture vault;
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		long views, loaded, states, blockEntities, keys, registryCalls;
		InventorySourceAccess.@Nullable Source lastSource;
		World(Contents contents) {
			vault = new VaultPerformanceFixture(contents);
			for (BlockPos position : vault.members.keySet()) entities.put(position, new BeehiveBlockEntity(position, Blocks.BEEHIVE.defaultBlockState()));
		}
		@Override public boolean isLoaded(BlockPos position) { loaded++; return vault.available && entities.containsKey(position); }
		@Override public BlockState blockState(BlockPos position) { states++; return java.util.Objects.requireNonNull(entities.get(position)).getBlockState(); }
		@Override public BlockEntity blockEntity(BlockPos position) { blockEntities++; return java.util.Objects.requireNonNull(entities.get(position)); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { keys++; return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { registryCalls++; return RegistryAccess.EMPTY; }
		@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling) { throw new AssertionError("missing validation admission"); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling, InventoryValidationBudget.Allowance allowance) {
			allowance.debit(resolutionValidationCost(input));
			var original = view(input);
			var source = new InventoryMinecraftSources.Source(input, original, this::onServerThread,
				() -> reacquire(input, original), entry -> { throw new AssertionError("native path fell back to compatibility codec"); },
				this, null, ceiling, ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY);
			lastSource = source;
			return Optional.of(source);
		}
		private InventoryMinecraftSources.View view(InventorySourceInput input) {
			views++; return InventoryMinecraftSources.view(this, input, vault::find);
		}
		private InventoryMinecraftSources.View reacquire(InventorySourceInput input, InventoryMinecraftSources.View original) {
			views++;
			return InventoryMinecraftSources.view(this, input,
				new InventoryMinecraftSources.PhysicalRead(input.ordinaryTarget().orElseThrow(), input.face(), this::isLoaded),
				vault::find, original.snapshotLayout(), true);
		}
		void resetCounters() { views = loaded = states = blockEntities = keys = registryCalls = 0; vault.resetCounters(); }
	}

	@NotNullByDefault
	static final class Run implements AutoCloseable {
		final World world;
		final InventorySettings policy = settings();
		final InventoryRuntime runtime;
		final InventoryRuntime.Consumer consumer;
		long tick;
		InventoryRuntime.@Nullable Observation last;
		int slots, pages, complete;
		long total, physicalVisits;
		boolean countPhysical;
		Run(Contents contents) {
			world = new World(contents); runtime = new InventoryRuntime(world, policy.pendingMemoryBytes());
			runtime.advance(0, policy);
			consumer = runtime.attach(INPUT, new InventoryRuntime.TrackingSubject(TargetKey.from(INPUT.target()))).orElseThrow();
		}
		void prepare() { assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer)); }
		void step() {
			runtime.advance(++tick, policy);
			last = runtime.step(consumer, 32).orElseThrow();
			if (countPhysical) physicalVisits += physicalUsed(runtime); // deterministic run only, not timed samples
			retainedOutput = last;
			pages++; slots += last.slots().size();
			for (var item : last.slots()) if (item != null) total = Math.addExact(total, item.count());
			if (last.result().completeness() == CaptureResult.Completeness.COMPLETE) complete++;
		}
		void sweep() { while (complete == 0 && tick < 128) step(); }
		boolean probe() { runtime.advance(++tick, policy); return runtime.probe(consumer).orElseThrow(); }
		@Override public void close() { consumer.close(); runtime.close(); }
	}

	@Test void representativeNativeSweepsChargeEveryVisitKeepExactVariantsAndReleaseMemory() {
		for (Contents contents : Contents.values()) {
			try (var run = new Run(contents)) {
				run.prepare();
				assertEquals(0, run.world.vault.getters, "admission performs no resident reads");
				var keys = new HashMap<String, nx.pingwheel.common.presentation.inventory.InventoryScanner.Key>();
				int visited = 0;
				while (run.complete == 0 && run.tick < 128) {
					long before = run.world.vault.getters;
					run.step();
					var page = java.util.Objects.requireNonNull(run.last);
					assertEquals(CaptureResult.Availability.READABLE, page.result().availability());
					assertTrue(page.slots().size() <= 32); assertEquals(page.slots().size(), run.world.vault.getters - before);
					assertEquals(page.slots().size(), physicalUsed(run.runtime), "the physical ledger charges actual native getter visits, even blanks");
					assertEquals(visited + page.slots().size(), page.result().coverage().scanned());
					for (var actual : page.slots()) {
						var expected = VaultPerformanceFixture.stack(contents, visited++);
						if (contents == Contents.EMPTY) assertNull(actual);
						else {
							assertNotNull(actual); assertEquals(expected.getCount(), actual.count()); assertFalse(actual.aggregateRequired());
							// Independent native patch encoding: equal keys iff both item ID and native patch encoding agree.
							int variant = contents == Contents.MIXED ? (visited - 1) % 3 : 0;
							var oracle = nativeIdentity(expected);
							assertEquals(nativeIdentity(VaultPerformanceFixture.stack(contents, variant)), oracle);
							@Nullable var previous = keys.putIfAbsent(oracle, actual.key());
							if (previous != null) assertEquals(previous, actual.key());
							else assertFalse(keys.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(oracle) && entry.getValue().equals(actual.key())), "distinct native patches must not collapse");
							var peer = run.runtime.memory(); assertEquals(0, peer.reserved());
						}
					}
				}
				assertEquals(900, visited); assertEquals(900, run.world.vault.getters);
				assertEquals(contents == Contents.EMPTY ? 0 : contents == Contents.MIXED ? 3 : 1, keys.size());
				assertEquals(contents == Contents.EMPTY ? 0 : 900L * 3_000_003 + 899L * 900 / 2, run.total, "totals exceed signed int range without narrowing");
				assertEquals(29, run.pages); assertEquals(1, run.complete);
				assertEquals(InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, java.util.Objects.requireNonNull(run.last).inventorySweepEvidence());
				run.consumer.retainCompleted();
				long reads = run.world.vault.getters;
				assertTrue(run.probe()); assertEquals(reads, run.world.vault.getters, "idle publication-evidence probe has no getter"); assertEquals(0, physicalUsed(run.runtime));
				run.close(); assertEquals(0, run.runtime.memory().retained()); assertEquals(0, run.runtime.memory().reserved());
			}
		}
	}

	private static String nativeIdentity(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + net.minecraft.core.component.DataComponentPatch.CODEC
			.encodeStart(net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, RegistryAccess.EMPTY), stack.getComponentsPatch()).getOrThrow();
	}

	@Test void frozenCommonEvidenceRejectsRedistributedLocalBoundariesBeforeAnyTailGetter() {
		try (var run = new Run(Contents.REPEATED)) {
			run.prepare(); run.step(); long reads = run.world.vault.getters;
			run.world.vault.members.get(BlockPos.ZERO).storage = new net.neoforged.neoforge.items.ItemStackHandler(19);
			run.world.vault.members.get(new BlockPos(0, 0, 1)).storage = new net.neoforged.neoforge.items.ItemStackHandler(21);
			run.runtime.advance(++run.tick, run.policy);
			var invalid = run.runtime.step(run.consumer, 32).orElseThrow();
			assertEquals(CaptureResult.Availability.INVALID, invalid.result().availability());
			assertTrue(invalid.slots().isEmpty()); assertEquals(reads, run.world.vault.getters); assertEquals(0, physicalUsed(run.runtime));
		}
	}

	@Test void idleProbeRetiresRevokedEvidenceWithoutContentReadsAndReleasesAllOwnership() {
		var run = new Run(Contents.REPEATED);
		try (run) {
			run.prepare(); run.sweep(); run.consumer.retainCompleted();
			long reads = run.world.vault.getters; run.world.vault.available = false;
			assertFalse(run.probe()); assertTrue(run.consumer.invalidated());
			assertEquals(reads, run.world.vault.getters); assertEquals(0, physicalUsed(run.runtime));
		}
		assertEquals(0, run.runtime.memory().retained()); assertEquals(0, run.runtime.memory().reserved());
	}

	@Test void recordRepresentativeBeforeOrAfterCostsWithoutTimingAssertions() {
		var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		assertTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
		System.out.println("VAULT_PERF_META jdk=" + System.getProperty("java.runtime.version") + " vm=" + System.getProperty("java.vm.name")
			+ " members=45 slotsPerMember=20 physical=32 period=4 logicalTarget=256 logicalServer=2048 memoryMiB=256 displayMultiplier=4 frameMultiplier=4 warmup=" + WARMUP + " samples=" + SAMPLES
			+ " getterCounter=prebuilt-forwarding-observer(native-storage-audit-unwrapped) physicalCounter=runtime-ledger fullMatches=controllerChecks-factories(stable-success-only) constructionInference=successfulFactory:Segmented+Layout+snapshotDescriptor publications=not-measured sweep900=prepared-fresh-sweep window32=second-page admission=fresh-runtime-not-JVM-cold");
		for (Contents contents : Contents.values()) {
			// Separate deterministic counters from allocation sampling. Nothing grows across workloads.
			for (String workload : new String[] {"admission", "window32", "sweep900", "idleProbe"}) {
				counterRun(contents, workload);
				for (int warm = 0; warm < WARMUP; warm++) sample(bean, contents, workload);
				long[] allocations = new long[SAMPLES], nanos = new long[SAMPLES];
				for (int sample = 0; sample < SAMPLES; sample++) {
					long[] result = sample(bean, contents, workload); allocations[sample] = result[0]; nanos[sample] = result[1];
				}
				System.out.println("VAULT_RAW contents=" + contents + " workload=" + workload + " bytes=" + Arrays.toString(allocations) + " nanos=" + Arrays.toString(nanos));
				Arrays.sort(allocations); Arrays.sort(nanos);
				System.out.printf(java.util.Locale.ROOT, "VAULT_PERF contents=%s workload=%s unit=operation bytes_min=%d bytes_median=%d bytes_max=%d ns_min=%d ns_median=%d ns_max=%d%n",
					contents, workload, allocations[0], allocations[SAMPLES / 2], allocations[SAMPLES - 1], nanos[0], nanos[SAMPLES / 2], nanos[SAMPLES - 1]);
			}
		}
	}

	private static void setup(Run run, String workload) {
		if (!workload.equals("admission")) run.prepare();
		if (workload.equals("window32")) run.step(); // first page populates the catalogue before the measured 32-slot window
		if (workload.equals("idleProbe")) { run.sweep(); run.consumer.retainCompleted(); }
	}
	private static void operate(Run run, String workload) {
		switch (workload) {
			case "admission" -> run.prepare();
			case "window32" -> run.step();
			case "sweep900" -> run.sweep();
			case "idleProbe" -> { if (!run.probe()) throw new AssertionError("stable idle probe rejected"); }
			default -> throw new AssertionError(workload);
		}
	}
	private static long[] sample(ThreadMXBean bean, Contents contents, String workload) {
		var run = new Run(contents);
		long bytes, nanos;
		try (run) {
			setup(run, workload);
			long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
			operate(run, workload);
			nanos = System.nanoTime() - start; bytes = bean.getThreadAllocatedBytes(thread) - before;
			retainedOutput = run.last == null ? run.world.lastSource : run.last;
		}
		assertEquals(0, run.runtime.memory().retained()); assertEquals(0, run.runtime.memory().reserved());
		retainedOutput = null; // the volatile escape has served its purpose; no prior workload pins a closed world
		return new long[] {bytes, nanos};
	}
	private static void counterRun(Contents contents, String workload) {
		try (var run = new Run(contents)) {
			setup(run, workload); run.world.resetCounters();
			run.countPhysical = true;
			long services = ClientDisplayFoodTestPorts.constructions;
			int pages = run.pages, complete = run.complete;
			operate(run, workload);
			var world = run.world; var vault = world.vault;
			System.out.printf(java.util.Locale.ROOT, "VAULT_COUNTER contents=%s workload=%s operations=1 views=%d factories=%d inferredFullMatches=%d adapterLoaded=%d commonLoaded=%d commonStates=%d commonEntities=%d ownerKeys=%d registries=%d localMetadata=%d getters=%d physicalVisits=%d pages=%d completions=%d serviceConstructions=%d retained=%d reserved=%d tick=%d%n",
				contents, workload, world.views, vault.factories, vault.controllerChecks - vault.factories, vault.loaded, world.loaded, world.states, world.blockEntities,
				world.keys, world.registryCalls, vault.localCalls, vault.getters, run.physicalVisits, run.pages - pages, run.complete - complete,
				ClientDisplayFoodTestPorts.constructions - services, run.runtime.memory().retained(), run.runtime.memory().reserved(), run.tick);
		}
	}
}
