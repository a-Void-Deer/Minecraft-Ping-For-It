package nx.pingwheel.forge.platform;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryResourceValue;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidValue;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real Forge stacks and production extraction, on the native-first integration classpath. */
@NotNullByDefault
class NativeFluidAmountTest {

	private static final CostLedger.Counter WORK = new CostLedger.Counter("forge-native-amount", CostLedger.Unit.WORK);
	private static boolean previousBootstrap;
	private static boolean bootstrapChanged;

	@BeforeAll static void minimalBootstrap() throws ReflectiveOperationException {
		boolean patched;
		try { Fluid.class.getMethod("getFluidType"); patched = true; }
		catch (NoSuchMethodException vanillaFirst) { patched = false; }
		org.junit.jupiter.api.Assumptions.assumeTrue(patched,
			"requires the existing patched Forge jar first; the ordinary test task is vanilla-first");
		SharedConstants.tryDetectVersion();
		// GameData installs the native fluid/BE registries. Avoid unrelated item-component mod events:
		// no FML launch or forged FluidStack is needed, and entity constructors are not exercised here.
		var field = Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true);
		previousBootstrap = field.getBoolean(null); field.setBoolean(null, true); bootstrapChanged = true;
		assertNotNull(net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(Fluids.WATER));
	}
	@AfterAll static void restoreBootstrap() throws ReflectiveOperationException {
		if (!bootstrapChanged) return;
		var field = Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true);
		field.setBoolean(null, previousBootstrap);
	}

	@Test void negativeNativeNbtConstructorAndSetterNeverBecomeEmptyVisits() throws ReflectiveOperationException {
		for (int amount : new int[] {-1, Integer.MIN_VALUE}) {
			var nbt = new CompoundTag(); nbt.putString("FluidName", "minecraft:water"); nbt.putInt("Amount", amount);
			var set = new FluidStack(Fluids.WATER, 7); set.setAmount(amount);
			for (FluidStack stack : List.of(FluidStack.loadFluidStackFromNBT(nbt), new FluidStack(Fluids.WATER, amount), set)) {
				assertEquals(amount, retained(stack)); assertEquals(0, stack.getAmount()); assertTrue(stack.isEmpty());
				assertRejected(stack);
			}
		}
	}

	@Test void legitimateEmptyPositiveAndPublicOverrideSemanticsArePreserved() throws ReflectiveOperationException {
		for (FluidStack stack : List.of(FluidStack.EMPTY, new FluidStack(Fluids.WATER, 0), new FluidStack(Fluids.EMPTY, 7),
			new PublicAmountStack(0))) {
			try (var fixture = new Fixture(stack); var access = fixture.open(); var admission = fixture.admission();
				var operation = admission.enter(access.observationProfile(1))) {
				assertEquals(new IPlatformFluidService.Visit(1, true), access.observe(1, entry -> {
					assertNull(entry.resident()); assertEquals(0, entry.amount()); return true;
				}, operation));
				assertEquals(1, fixture.reads);
			}
		}
		for (FluidStack stack : List.of(new FluidStack(Fluids.WATER, 1), new FluidStack(Fluids.WATER, Integer.MAX_VALUE),
			new PublicAmountStack(3))) {
			try (var fixture = new Fixture(stack); var access = fixture.open(); var admission = fixture.admission();
				var operation = admission.enter(access.readProfile())) {
				long expected = stack.getAmount() == Integer.MAX_VALUE ? 173_946_175_407L : stack.getAmount() * 81L;
				assertTrue(access.consume(0, entry -> {
					assertEquals(expected, entry.amount());
					assertSame(Fluids.WATER, assertInstanceOf(IPlatformFluidService.Nbt.class, entry.resident()).fluid()); return true;
				}, operation));
				assertEquals(1, fixture.reads);
			}
		}
		assertRejected(new PublicAmountStack(-1));
	}

	@Test void nullAndThrowingNativeGettersRemainPaidSingleAttempts() throws ReflectiveOperationException {
		for (boolean throwing : new boolean[] {false, true}) try (var fixture = new Fixture(null); var access = fixture.open()) {
			var failure = new IllegalStateException("native getter failed"); if (throwing) fixture.failure = failure;
			try (var admission = fixture.admission(); var operation = admission.enter(access.readProfile())) {
				var caught = assertThrows(IllegalStateException.class,
					() -> access.consume(0, entry -> fail("failed getter must not visit"), operation));
				if (throwing) assertSame(failure, caught);
				assertEquals(1, fixture.reads); assertTrue(operation.allowance().attemptedWorkUnits() > 128);
				assertThrows(InventoryItemBudget.CaptureLimitException.class,
					() -> access.consume(0, entry -> true, operation)); assertEquals(1, fixture.reads);
			}
		}
	}

	@Test void inaccessibleCachedPrimitiveFieldFailsClosedWithoutPoisoningTheNextRead() throws ReflectiveOperationException {
		var stack = new FluidStack(Fluids.WATER, 7);
		try (var fixture = new Fixture(stack); var access = fixture.open()) {
			try (var admission = fixture.admission(); var operation = admission.enter(access.readProfile())) {
				assertTrue(access.consume(0, entry -> true, operation)); // initialize the real lazy metadata
			}
			var holder = Class.forName(PlatformFluidServiceImpl.class.getName() + "$RetainedAmount");
			var metadataField = holder.getDeclaredField("METADATA"); metadataField.setAccessible(true);
			Object metadata = Objects.requireNonNull(metadataField.get(null));
			var fieldField = holder.getDeclaredField("field"); fieldField.setAccessible(true);
			var field = (java.lang.reflect.Field) Objects.requireNonNull(fieldField.get(metadata));
			field.setAccessible(false);
			try (var admission = fixture.admission(); var operation = admission.enter(access.readProfile())) {
				var caught = assertThrows(IllegalStateException.class,
					() -> access.consume(0, entry -> fail("unreadable raw amount cannot become empty"), operation));
				assertInstanceOf(IllegalAccessException.class, caught.getCause()); assertEquals(2, fixture.reads);
				assertTrue(operation.allowance().attemptedWorkUnits() > 128);
				assertThrows(InventoryItemBudget.CaptureLimitException.class,
					() -> access.consume(0, entry -> true, operation)); assertEquals(2, fixture.reads);
			} finally { field.setAccessible(true); }
			try (var admission = fixture.admission(); var operation = admission.enter(access.readProfile())) {
				assertTrue(access.consume(0, entry -> { assertEquals(567, entry.amount()); return true; }, operation));
				assertSame(metadata, metadataField.get(null)); assertSame(field, fieldField.get(metadata)); assertEquals(3, fixture.reads);
			}
		}
	}

	@Test void nativeReadFailureCannotReplaceThePreviousCompleteRuntimeResultWithZero() throws ReflectiveOperationException {
		try (var fixture = new Fixture(new FluidStack(Fluids.WATER, 7)); var runtime = fixture.runtime()) {
			runtime.advance(0, fixture.settings);
			var consumer = runtime.attach(fixture.input, new InventoryRuntime.TrackingSubject(TargetKey.from(fixture.target))).orElseThrow();
			var complete = runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, complete.result().completeness());
			assertEquals(567, Objects.requireNonNull(complete.slots().getFirst()).count()); assertEquals(1, fixture.reads);
			consumer.retainCompleted();
			try (var previous = runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
				var malformed = new CompoundTag(); malformed.putString("FluidName", "minecraft:water"); malformed.putInt("Amount", -1);
				fixture.stack = FluidStack.loadFluidStackFromNBT(malformed); assertEquals(-1, retained(Objects.requireNonNull(fixture.stack)));
				consumer.restart(); runtime.advance(1, fixture.settings);
				var failed = runtime.step(consumer).orElseThrow();
				assertEquals(CaptureResult.Availability.UNAVAILABLE, failed.result().availability(),
					() -> "replacement=" + failed.result().completeness() + " slots=" + failed.slots());
				assertNotEquals(CaptureResult.Completeness.COMPLETE, failed.result().completeness());
				assertTrue(failed.slots().isEmpty()); assertThrows(IllegalStateException.class, consumer::retainCompleted);
				assertEquals(567, Objects.requireNonNull(complete.slots().getFirst()).count());
				assertEquals(2, fixture.reads, "the invalid attempt is one actual native address read");
				assertEquals(1, physical(runtime).used(InventorySourceAccess.PHYSICAL));
				try (var grant = runtime.reservePublication(previous).orElseThrow()) {
					assertEquals(InventorySharedRead.Check.VALID, grant.validate(previous), "old complete evidence is not replaced with absence-zero");
				}
			}
			runtime.close(); assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}

	private static void assertRejected(FluidStack stack) throws ReflectiveOperationException {
		try (var fixture = new Fixture(stack); var access = fixture.open(); var admission = fixture.admission();
			var operation = admission.enter(access.readProfile())) {
			var visits = new AtomicInteger();
			assertThrows(IllegalArgumentException.class, () -> access.consume(0, entry -> {
				visits.incrementAndGet(); assertEquals(0, entry.amount()); return true;
			}, operation), () -> "native malformed amount reached " + visits.get() + " empty visitor(s)");
			assertEquals(0, visits.get()); assertEquals(1, fixture.reads);
			assertTrue(operation.allowance().attemptedWorkUnits() > 128);
			assertThrows(InventoryItemBudget.CaptureLimitException.class,
				() -> access.consume(0, entry -> true, operation)); assertEquals(1, fixture.reads);
		}
	}
	private static int retained(FluidStack stack) throws ReflectiveOperationException {
		var field = FluidStack.class.getDeclaredField("amount"); field.setAccessible(true); return field.getInt(stack);
	}
	private static CostLedger physical(InventoryRuntime runtime) throws ReflectiveOperationException {
		var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true);
		return (CostLedger) Objects.requireNonNull(field.get(runtime));
	}

	@NotNullByDefault
	private static final class PublicAmountStack extends FluidStack {
		private final int amount;
		private PublicAmountStack(int amount) { super(Fluids.WATER, 7); this.amount = amount; }
		@Override public int getAmount() { return amount; }
	}

	@NotNullByDefault
	private static final class Fixture implements PlatformFluidServiceImpl.World, IFluidHandler, AutoCloseable {
		private final InventorySettings settings = InventorySettings.serverDefaults();
		private final ServerLevel level = allocate(ServerLevel.class);
		private final DedicatedServer server = allocate(DedicatedServer.class);
		private final BlockState state = Blocks.BEEHIVE.defaultBlockState();
		private final BlockEntity entity = allocate(BeehiveBlockEntity.class);
		private final LazyOptional<IFluidHandler> capability = LazyOptional.of(() -> this);
		private final Target target = Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "lease", "minecraft:beehive", "locator", true);
		private final InventorySourceInput input = new InventorySourceInput(target, new UUID(1, 2), BlockFace.WEST, InventoryResourceKind.FLUID);
		private final IPlatformFluidService.Context context;
		private @Nullable FluidStack stack;
		private @Nullable RuntimeException failure;
		private int reads;
		private final IPlatformFluidService service = new IPlatformFluidService() {
			@Override public OperationProfile probeProfile() { return new PlatformFluidServiceImpl().probeProfile(); }
			@Override public Optional<Probe> probe(Context current, Operation operation) { return PlatformFluidServiceImpl.probe(current, Fixture.this, operation); }
		};
		private Fixture(@Nullable FluidStack stack) throws ReflectiveOperationException {
			this.stack = stack;
			set(ServerLevel.class, "server", level, server); set(MinecraftServer.class, "serverThread", server, Thread.currentThread());
			set(BlockEntity.class, "type", entity, BlockEntityType.BEEHIVE); set(BlockEntity.class, "worldPosition", entity, BlockPos.ZERO);
			set(BlockEntity.class, "blockState", entity, state); set(BlockEntity.class, "level", entity, level);
			context = new IPlatformFluidService.Context(level, BlockPos.ZERO, Direction.WEST, input.readOwner(),
				(position, operation) -> position.equals(BlockPos.ZERO), operation -> true);
		}
		private InventoryFluidAdmission admission() {
			return new InventoryFluidAdmission(new CostLedger(Map.of(WORK, 10_000_000L)), WORK,
				new RetainedMemoryLedger(64L << 20), InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
		}
		private IPlatformFluidService.Access open() {
			try (var admission = admission(); var operation = admission.enter(service.probeProfile());
				var probe = service.probe(context, operation).orElseThrow()) { return probe.finish(admission).orElseThrow(); }
		}
		private InventoryRuntime runtime() {
			// Only the native amount/empty decision is under test, not the independent identity codec.
			// The real Runtime/NativeHandle owns physical attempts, completion and replacement fences.
			return new InventoryRuntime(value -> Optional.of(new NativeSource(this, open())), settings.pendingMemoryBytes());
		}
		@Override public boolean loaded(BlockPos position) { return true; }
		@Override public BlockState state(BlockPos position) { return state; }
		@Override public @Nullable BlockEntity blockEntity(BlockPos position) { return entity; }
		@Override public LazyOptional<IFluidHandler> capability(BlockEntity current, Direction face) { assertSame(entity, current); assertEquals(Direction.WEST, face); return capability; }
		@Override public int getTanks() { return 1; }
		@Override public int getTankCapacity(int tank) { return Integer.MAX_VALUE; }
		@Override public @Nullable FluidStack getFluidInTank(int tank) { reads++; if (failure != null) throw failure; return stack; }
		@Override public boolean isFluidValid(int tank, FluidStack value) { throw new AssertionError("read only"); }
		@Override public int fill(FluidStack value, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(FluidStack value, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("read only"); }
		@Override public void close() { capability.invalidate(); }
	}
	@NotNullByDefault
	private record NativeSource(Fixture fixture, IPlatformFluidService.Access access, InventoryFluidCodec.Catalog catalog) implements InventorySourceAccess.Source {
		private NativeSource(Fixture fixture, IPlatformFluidService.Access access) { this(fixture, access, new InventoryFluidCodec.Catalog()); }
		@Override public SourceKey key() { return new SourceKey("test", "forge-native", "amount", fixture.input.viewKey()); }
		@Override public boolean valid() { try (var admission = fixture.admission(); var operation = admission.enter(access.validationProfile())) { return access.valid(operation); } }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 1; }
		@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
		@Override public boolean certifiedResourceSharing() { return false; }
		@Override public long nativeResourceRetainedBytes() { return catalog.modeledRetainedBytes(); }
		@Override public long nativeCatalogGrowthUpperBound() { return InventoryFluidCodec.Catalog.retainedGrowthUpperBound(); }
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new AssertionError("fluid must not use item reads"); }
		@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission catalog, InventoryValidationBudget.Allowance allowance) {
			@Nullable InventoryResourceValue[] result = new InventoryResourceValue[1];
			try (var admission = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
				var operation = admission.enter(access.readProfile())) {
				access.consume(slot, entry -> {
					if (entry.amount() != 0) result[0] = this.catalog.intern("minecraft:water", entry.amount(), InventoryFluidCodec.Identity.exact(new byte[] {0}),
						new InventoryFluidValue.Display("minecraft:water", "water", null), Objects.requireNonNull(catalog));
					return true;
				}, operation);
			}
			return result[0];
		}
		@Override public void close() { access.close(); }
	}
	private static <T> T allocate(Class<T> type) {
		try {
			var unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static void set(Class<?> owner, String name, Object target, Object value) throws ReflectiveOperationException {
		var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}
}
