package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.fabric.platform.PlatformFluidServiceImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real common source, Fabric indexed adapter and Runtime ledgers; only native world/storage are fixtures. */
@NotNullByDefault
class FluidIdentityAdmissionScalingTest {

	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test void twoThousandAddressesProgressAcrossTicksAndCompleteWithDefaultMemory() {
		try (var fixture = new Fixture(2048)) {
			var consumer = fixture.prepare();
			long scanned = 0;
			boolean complete = false;
			// One bounded page per tick. Empty addresses still require every native getter,
			// but avoid unrelated identity codecs/catalogue pressure in this admission regression.
			for (int tick = 1; tick <= fixture.count; tick++) {
				fixture.advance(tick);
				var next = fixture.runtime.step(consumer);
				if (tick <= 3) assertTrue(next.isPresent(), "a stable admitted source must not remain deferred at cursor zero");
				if (next.isEmpty()) continue;
				var result = next.orElseThrow().result();
				assertEquals(CaptureResult.Availability.READABLE, result.availability());
				assertTrue(result.coverage().scanned() > scanned, "each published page must advance the paid cursor");
				scanned = result.coverage().scanned();
				assertEquals(scanned, fixture.getters, "no address is replayed by a new tick");
				assertEquals(0, fixture.runtime.memory().reserved());
				if (result.completeness() == CaptureResult.Completeness.COMPLETE) { complete = true; break; }
				assertEquals(CaptureResult.Completeness.CONTINUE, result.completeness());
			}
			assertTrue(complete, "the stable full sweep must terminate, not merely construct successfully");
			assertEquals(fixture.count, scanned);
			assertEquals(fixture.count, fixture.getters);
			assertTrue(fixture.peakMemory > 0 && fixture.peakMemory <= fixture.settings.pendingMemoryBytes());
			assertEquals(1, fixture.resolutions, "ticks must keep the original source instead of restarting the sweep");
		}
	}

	@Test void maximumLegalAddressCountConstructsAndReadsRepeatedTicksWithoutOwnershipExhaustion() {
		try (var fixture = new Fixture(InventorySourceAccess.MAX_SLOTS)) {
			var consumer = fixture.prepare();
			for (int tick = 1; tick <= 3; tick++) {
				fixture.advance(tick);
				var result = assertDoesNotThrow(() -> fixture.runtime.step(consumer, 1)).orElseThrow().result();
				assertEquals(CaptureResult.Availability.READABLE, result.availability());
				assertEquals(CaptureResult.Completeness.CONTINUE, result.completeness());
				assertEquals(tick, result.coverage().scanned());
				assertEquals(tick, fixture.getters);
				assertEquals(0, fixture.runtime.memory().reserved());
			}
			assertTrue(fixture.peakMemory <= fixture.settings.pendingMemoryBytes());
			assertEquals(1, fixture.resolutions);
		}
	}

	@Test void memoryRefusalBeforeTheReadInvokesNoGetterAndLaterBudgetRecoveryMakesProgress() {
		try (var fixture = new Fixture(8)) {
			var consumer = fixture.prepare(); fixture.resetMetadata();
			fixture.runtime.memory().setCap(fixture.runtime.memory().retained());
			assertTrue(fixture.runtime.step(consumer, 1).isEmpty());
			assertEquals(0, fixture.getters); assertEquals(0, fixture.countCalls); assertEquals(0, fixture.underlyingCalls);
			assertEquals(0, fixture.runtime.memory().reserved());
			fixture.advance(1);
			var result = fixture.runtime.step(consumer, 1).orElseThrow().result();
			assertEquals(CaptureResult.Availability.READABLE, result.availability());
			assertEquals(1, result.coverage().scanned()); assertEquals(1, fixture.getters);
		}
	}

	@Test void sourceProfileRefusalAfterAnAdmittedMetadataCallbackStillStopsBeforeContents() {
		try (var fixture = new Fixture(8)) {
			var consumer = fixture.prepare(); fixture.resetMetadata(); fixture.refuseOnCount = 1;
			assertTrue(fixture.runtime.step(consumer, 1).isEmpty());
			assertEquals(1, fixture.countCalls, "the first profile was admitted and its callback really ran");
			assertEquals(0, fixture.getters, "the next source phase must refuse instead of borrowing unpaid work");
			assertEquals(0, fixture.runtime.memory().reserved());
			fixture.refuseOnCount = -1; fixture.advance(1);
			var result = fixture.runtime.step(consumer, 1).orElseThrow().result();
			assertEquals(CaptureResult.Availability.READABLE, result.availability());
			assertEquals(1, result.coverage().scanned()); assertEquals(1, fixture.getters);
		}
	}

	@Test void replacementAtTheLastCountOrUnderlyingCallbackIsRejectedBeforeTheNativeGetter() {
		int countBeforeGetter, underlyingBeforeGetter;
		try (var calibration = new Fixture(1)) {
			var consumer = calibration.prepare(); calibration.resetMetadata();
			assertEquals(CaptureResult.Completeness.COMPLETE, calibration.runtime.step(consumer, 1).orElseThrow().result().completeness());
			countBeforeGetter = calibration.firstGetterCount;
			underlyingBeforeGetter = calibration.firstGetterUnderlying;
			assertTrue(countBeforeGetter > 0); assertTrue(underlyingBeforeGetter > 0);
		}
		for (boolean atCount : new boolean[] {true, false}) try (var fixture = new Fixture(1)) {
			var consumer = fixture.prepare(); fixture.resetMetadata();
			fixture.replaceOnCount = atCount ? countBeforeGetter : -1;
			fixture.replaceOnUnderlying = atCount ? -1 : underlyingBeforeGetter;
			var original = fixture.entity;
			var result = fixture.runtime.step(consumer, 1).orElseThrow();
			assertTrue(fixture.replaced, "the injected replacement must run after the provider's root lookup");
			assertNotEquals(CaptureResult.Availability.READABLE, result.result().availability());
			assertTrue(result.slots().isEmpty()); assertEquals(0, fixture.getters);
			assertEquals(0, fixture.runtime.memory().reserved());
			java.lang.ref.Reference.reachabilityFence(original);
		}
	}

	@NotNullByDefault
	private static final class Fixture implements InventoryFluidSources.World, AutoCloseable {
		final InventorySettings settings = InventorySettings.serverDefaults();
		final Server server = allocate(Server.class);
		final Level level = allocate(Level.class);
		final BlockState state = Blocks.BEEHIVE.defaultBlockState();
		final int count;
		final @Unmodifiable List<SingleSlotStorage<FluidVariant>> views;
		final InventorySourceInput input;
		final InventoryFluidSources.Binding binding;
		final InventoryRuntime runtime;
		BlockEntity entity;
		int resolutions, getters, countCalls, underlyingCalls;
		int firstGetterCount, firstGetterUnderlying;
		int replaceOnCount = -1, replaceOnUnderlying = -1, refuseOnCount = -1;
		boolean replaced;
		long peakMemory;

		Fixture(int count) {
			this.count = count; level.fixture = this;
			entity = new BeehiveBlockEntity(BlockPos.ZERO, state); entity.setLevel(level);
			var target = new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive");
			input = new InventorySourceInput(target, new UUID(1, 2), BlockFace.WEST, InventoryResourceKind.FLUID);
			var descriptor = new BlockReadSource(target, "minecraft", "", target, new MarkerAnchor(0, 0, 0));
			binding = new InventoryFluidSources.Binding(level,
				new ResolvedBlockReadSource(level, descriptor, BlockPos.ZERO::equals), BlockPos.ZERO);
			var addresses = new ArrayList<SingleSlotStorage<FluidVariant>>(count);
			for (int index = 0; index < count; index++) addresses.add(new View(this, index));
			views = List.copyOf(addresses);
			var service = service(storage());
			runtime = new InventoryRuntime(new InventoryRuntime.SourceResolver() {
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput value, int cap) {
					throw new AssertionError("unadmitted source resolution");
				}
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput value, int cap, InventoryValidationBudget.Allowance allowance) {
					resolutions++; return InventoryFluidSources.resolve(Fixture.this, service, value, cap, allowance);
				}
			}, settings.pendingMemoryBytes());
		}
		InventoryRuntime.Consumer prepare() {
			advance(0);
			var consumer = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(0, getters, "source construction is metadata-only");
			assertEquals(0, runtime.memory().reserved());
			return consumer;
		}
		void advance(int tick) { server.tick = tick; runtime.advance(tick, settings); }
		void resetMetadata() { countCalls = 0; underlyingCalls = 0; }
		void sampleMemory() { peakMemory = Math.max(peakMemory, runtime.memory().reserved() + runtime.memory().retained()); }
		void replaceRoot() {
			entity = new BeehiveBlockEntity(BlockPos.ZERO, state); entity.setLevel(level); replaced = true;
		}
		@SuppressWarnings("unchecked")
		private SlottedStorage<FluidVariant> storage() {
			return (SlottedStorage<FluidVariant>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SlottedStorage.class},
				(Object proxy, java.lang.reflect.Method method, @Nullable Object[] arguments) -> switch (method.getName()) {
					case "getSlotCount" -> {
						countCalls++; sampleMemory(); if (countCalls == replaceOnCount) replaceRoot();
						if (countCalls == refuseOnCount) runtime.memory().setCap(runtime.memory().retained());
						yield count;
					}
					case "getSlot" -> views.get((Integer) Objects.requireNonNull(arguments)[0]);
					default -> throw new AssertionError("unexpected native storage call: " + method.getName());
				});
		}
		private IPlatformFluidService service(SlottedStorage<FluidVariant> storage) {
			try {
				Class<?> lookup = Class.forName(PlatformFluidServiceImpl.class.getName() + "$Lookup");
				var constructor = PlatformFluidServiceImpl.class.getDeclaredConstructor(lookup); constructor.setAccessible(true);
				Object port = Proxy.newProxyInstance(lookup.getClassLoader(), new Class<?>[] {lookup},
					(Object proxy, java.lang.reflect.Method method, @Nullable Object[] arguments) -> {
						Object[] args = Objects.requireNonNull(arguments);
						assertEquals("find", method.getName()); assertSame(level, args[0]);
						assertEquals(BlockPos.ZERO, args[1]); assertSame(Direction.WEST, args[2]); return storage;
					});
				return constructor.newInstance(port);
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		@Override public boolean onServerThread() { return true; }
		@Override public InventoryFluidSources.Binding bind(InventorySourceInput value, InventoryValidationBudget.Allowance allowance) { return binding; }
		@Override public @Nullable BlockEntity existingEntity(ServerLevel world, BlockPos position) { return position.equals(BlockPos.ZERO) ? entity : null; }
		@Override public BlockState blockState(ServerLevel world, BlockPos position) { return state; }
		@Override public boolean loaded(ServerLevel world, BlockPos position) { return true; }
		@Override public ItemStack ownerKey(InventorySourceInput value) { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries(ServerLevel world) { return RegistryAccess.EMPTY; }
		@Override public void close() {
			runtime.close(); assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}

	@NotNullByDefault
	private static final class View implements SingleSlotStorage<FluidVariant> {
		private final Fixture fixture;
		private final int index;
		private View(Fixture fixture, int index) { this.fixture = fixture; this.index = index; }
		@Override public StorageView<FluidVariant> getUnderlyingView() {
			fixture.underlyingCalls++;
			if (fixture.underlyingCalls == fixture.replaceOnUnderlying) fixture.replaceRoot();
			return this;
		}
		@Override public long getAmount() {
			assertEquals(fixture.getters, index, "only the next current address may be read");
			if (fixture.getters++ == 0) {
				fixture.firstGetterCount = fixture.countCalls; fixture.firstGetterUnderlying = fixture.underlyingCalls;
			}
			fixture.sampleMemory(); return 0;
		}
		@Override public boolean isResourceBlank() { throw new AssertionError("empty address does not read a resource"); }
		@Override public FluidVariant getResource() { throw new AssertionError("empty address does not read a resource"); }
		@Override public long getCapacity() { throw new AssertionError("unexpected capacity query"); }
		@Override public long insert(FluidVariant resource, long maximum, TransactionContext transaction) { throw new AssertionError("read-only source"); }
		@Override public long extract(FluidVariant resource, long maximum, TransactionContext transaction) { throw new AssertionError("read-only source"); }
		@Override public Iterator<StorageView<FluidVariant>> iterator() { throw new AssertionError("indexed source must not enumerate"); }
	}

	@NotNullByDefault
	private static final class Level extends ServerLevel {
		private @Nullable Fixture fixture;
		private Level() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		private Fixture fixture() { return Objects.requireNonNull(fixture); }
		@Override public MinecraftServer getServer() { return fixture().server; }
		@Override public boolean isLoaded(BlockPos pos) { return true; }
		@Override public BlockState getBlockState(BlockPos pos) { return fixture().state; }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return fixture().entity; }
	}
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private int tick;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public int getTickCount() { return tick; }
	}
	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
