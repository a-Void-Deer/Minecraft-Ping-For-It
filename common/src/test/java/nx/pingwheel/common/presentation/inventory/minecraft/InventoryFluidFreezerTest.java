package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidDisplayCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native resident mutations, complete identity and refusal stay independent of amount/display. */
@NotNullByDefault
class InventoryFluidFreezerTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static InventoryCatalogAdmission admission() {
		return new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(1024), InventoryCatalogAdmission.workUpperBound(1024));
	}
	private static InventoryFluidValue nbt(@Nullable CompoundTag tag, long amount) {
		return java.util.Objects.requireNonNull(InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, tag), amount),
			RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission()));
	}
	@Test void unavailableNativeDescriptionUsesLiteralRegistryIdentityNotAnInventedBlockKey() {
		var frozen = freezeName(fluid -> null, 9007199254740993L);
		assertEquals("minecraft:water", frozen.display().label());
		assertEquals(9007199254740993L, frozen.amount()); assertFalse(frozen.aggregateRequired());
	}
	private static InventoryFluidValue freezeName(IPlatformFluidDescriptionService descriptions, long amount) {
		return java.util.Objects.requireNonNull(InventoryFluidFreezer.freeze(
			new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), amount),
			RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission(), descriptions));
	}
	@Test void nativeTranslationLabelIsDiscoveredAfterIdentityWithoutBecomingCustomName() {
		int[] calls = {0};
		var exact = freezeName(fluid -> { calls[0]++; assertSame(Fluids.WATER, fluid);
			return new IPlatformFluidDescriptionService.Translation("fluid.create.tea", "Builder's Tea"); }, Long.MAX_VALUE);
		assertEquals(1, calls[0]); assertEquals("fluid.create.tea", exact.display().label());
		assertNull(InventoryFluidDisplayCodec.decodeStrict(exact.displayJson()).customName());
		var literal = freezeName(fluid -> new IPlatformFluidDescriptionService.Literal("Base literal"), 81);
		assertEquals("minecraft:water", literal.display().label()); assertNull(InventoryFluidDisplayCodec.decodeStrict(literal.displayJson()).customName());
		assertEquals(exact.key(), literal.key()); assertFalse(exact.aggregateRequired()); assertFalse(literal.aggregateRequired());
		assertEquals(Long.MAX_VALUE, exact.amount()); assertEquals(81, literal.amount());
	}
	@Test void nameHookFailuresCannotFoldOrChangeAmountAndActualCaptureRefusalStillEscapes() {
		var before = freezeName(fluid -> null, 9007199254740993L);
		for (Throwable failure : new Throwable[] {new IllegalStateException("name"), new NoClassDefFoundError("optional"), new java.util.ServiceConfigurationError("service")}) {
			var after = freezeName(fluid -> { if (failure instanceof RuntimeException runtime) throw runtime; throw (Error) failure; }, before.amount());
			assertEquals(before.key(), after.key()); assertEquals(before.amount(), after.amount());
			assertEquals(before.aggregateRequired(), after.aggregateRequired()); assertEquals(before.display(), after.display());
		}
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> freezeName(fluid -> { throw new InventoryItemBudget.CaptureLimitException("grant"); }, 81));
	}
	@Test void nativeFirstInitializationCaptureFailureEscapesFreezerWithoutInstallingSuccessfulObservation() throws Exception {
		var initializer = new ClassLoader(getClass().getClassLoader()) {
			@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (!name.equals(InitializingNameProvider.class.getName()) && !name.equals(LazyNativeName.class.getName())) return super.loadClass(name, resolve);
				synchronized (getClassLoadingLock(name)) {
					@Nullable Class<?> found = findLoadedClass(name);
					if (found == null) try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
						if (input == null) throw new ClassNotFoundException(name);
						byte[] bytes = input.readAllBytes(); found = defineClass(name, bytes, 0, bytes.length);
					} catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
					if (resolve) resolveClass(found); return found;
				}
			}
		};
		var provider = (IPlatformFluidDescriptionService) initializer.loadClass(InitializingNameProvider.class.getName()).getConstructor().newInstance();
		var catalog = new InventoryFluidCodec.Catalog();
		var entry = new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), 9007199254740993L);
		NameInitialization.calls = 0;
		assertSame(NameInitialization.FAILURE, assertThrows(NameAdmissionFailure.class,
			() -> InventoryFluidFreezer.freeze(entry, RegistryAccess.EMPTY, 1024, catalog, admission(), provider)));
		assertEquals(1, NameInitialization.calls); assertEquals(0, catalog.retainedBytes(), "refused name initialization cannot install an observation");
		var retry = InventoryFluidFreezer.freeze(entry, RegistryAccess.EMPTY, 1024, catalog, admission(), fluid -> null);
		assertNotNull(retry); assertEquals(entry.amount(), retry.amount()); assertFalse(retry.aggregateRequired());
		assertEquals(freezeName(fluid -> null, entry.amount()).key(), retry.key(), "normal upper-layer retry keeps exact identity and quantity");
	}
	@NotNullByDefault
	public static final class NameAdmissionFailure extends InventoryItemBudget.CaptureLimitException {
		public NameAdmissionFailure() { super("native name admission refused"); }
	}
	@NotNullByDefault
	public static final class NameInitialization {
		public static final NameAdmissionFailure FAILURE = new NameAdmissionFailure();
		public static int calls;
		public static IPlatformFluidDescriptionService.Description initialize() { calls++; throw FAILURE; }
	}
	@NotNullByDefault
	public static final class LazyNativeName {
		public static final IPlatformFluidDescriptionService.Description NAME = NameInitialization.initialize();
	}
	@NotNullByDefault
	public static final class InitializingNameProvider implements IPlatformFluidDescriptionService {
		@Override public Description describeBase(net.minecraft.world.level.material.Fluid fluid) { return LazyNativeName.NAME; }
	}
	@Test void independentlyRefusedIdentityKeepsTheSameAggregateWhenNameFails() {
		var tag = new CompoundTag(); tag.putString("native", "x".repeat(InventoryFluidCodec.MAX_CANONICAL_BYTES + 1));
		var entry = new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, tag), Long.MAX_VALUE);
		var before = InventoryFluidFreezer.freeze(entry, RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission(),
			fluid -> new IPlatformFluidDescriptionService.Translation("fluid.test.water", null));
		var after = InventoryFluidFreezer.freeze(entry, RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission(), fluid -> { throw new IllegalStateException("name"); });
		assertNotNull(before); assertNotNull(after); assertTrue(before.aggregateRequired()); assertTrue(after.aggregateRequired());
		assertEquals(before.key(), after.key()); assertEquals(before.amount(), after.amount());
	}
	@Test void nbtOrderNormalizesButNativeTypeListOrderAndAbsentTagRemainDistinct() {
		var first = new CompoundTag(); first.putInt("a", 1); first.putString("b", "water");
		var reordered = new CompoundTag(); reordered.putString("b", "water"); reordered.putInt("a", 1);
		assertEquals(nbt(first, 81).key(), nbt(reordered, Long.MAX_VALUE).key(), "amount is excluded from native identity");
		reordered.put("a", ByteTag.valueOf((byte) 1)); assertNotEquals(nbt(first, 81).key(), nbt(reordered, 81).key());
		assertNotEquals(nbt(null, 81).key(), nbt(new CompoundTag(), 81).key());
		var list = new ListTag(); list.add(IntTag.valueOf(1)); list.add(IntTag.valueOf(2)); first.put("list", list);
		var reverse = new ListTag(); reverse.add(IntTag.valueOf(2)); reverse.add(IntTag.valueOf(1)); reordered = first.copy(); reordered.put("list", reverse);
		assertNotEquals(nbt(first, 81).key(), nbt(reordered, 81).key());
	}
	@Test void borrowedNbtIsDetachedBeforeLaterProviderMutation() {
		var resident = new CompoundTag(); resident.putIntArray("payload", new int[] {1, 2, 3});
		var frozen = nbt(resident, 9007199254740993L); var original = frozen.key();
		resident.getIntArray("payload")[0] = 9; resident.putString("later", "mutation");
		assertEquals(original, frozen.key()); assertEquals(9007199254740993L, frozen.amount());
		assertNotEquals(original, nbt(resident, 9007199254740993L).key());
		assertEquals("minecraft:water", InventoryFluidDisplayCodec.decodeStrict(frozen.displayJson()).fluidId());
	}
	@Test void canonicalByteAndMalformedUtfRefusalsPreserveReliableIdAmountAndBoundedDisplay() {
		for (String value : new String[] {"x".repeat(InventoryFluidCodec.MAX_CANONICAL_BYTES + 1), "\ud800"}) {
			var tag = new CompoundTag(); tag.putString("native", value);
			var refused = nbt(tag, Long.MAX_VALUE);
			assertTrue(refused.aggregateRequired()); assertEquals("", refused.key().componentsKey());
			assertEquals(Long.MAX_VALUE, refused.amount()); assertEquals("minecraft:water", refused.resourceId());
			assertTrue(InventoryFluidDisplayCodec.decode("minecraft:water", refused.displayJson()).isPresent());
		}
	}
	@Test void persistentPatchRemovalIsExactAndNoDefaultItemGraphIsRead() {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var empty = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, DataComponentPatch.EMPTY), 81),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		var removed = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER,
			DataComponentPatch.builder().remove(DataComponents.CUSTOM_NAME).build()), 81), registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(empty); assertNotNull(removed); assertFalse(empty.aggregateRequired()); assertFalse(removed.aggregateRequired());
		assertNotEquals(empty.key(), removed.key()); assertEquals(empty.display(), removed.display());
	}
	@Test void zeroIsObservedEmptyAndUnregisteredOrNegativeInputCannotBecomeAggregate() {
		assertNull(InventoryFluidFreezer.freeze(IPlatformFluidService.Entry.empty(), RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission()));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), -1));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.EMPTY, null), 81),
			RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission()));
	}
	@Test void deniedCatalogAdmissionDoesNotInstallIdentityOrDisplay() {
		var catalog = new InventoryFluidCodec.Catalog();
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> InventoryFluidFreezer.freeze(
			new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), 81), RegistryAccess.EMPTY, 1024, catalog,
			new InventoryCatalogAdmission(0, 0)));
		assertEquals(0, catalog.retainedBytes());
	}
	@Test void componentIdentityMatchesCompleteNativePersistentPatchAndDetachesBorrowedCustomData() throws Exception {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var tag = new CompoundTag(); tag.putString("name", "native-variant"); tag.putIntArray("cells", new int[] {2, 4, 6});
		var data = CustomData.of(tag);
		var patch = DataComponentPatch.builder().set(DataComponents.DAMAGE, 17).set(DataComponents.CUSTOM_DATA, data).build();
		var nativeTag = DataComponentPatch.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), patch).result().orElseThrow();
		var expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
			.digest(InventoryCanonicalWriter.write(nativeTag, null)));
		var frozen = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, patch), Long.MAX_VALUE),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(frozen); assertFalse(frozen.aggregateRequired()); assertEquals(expected, frozen.key().componentsKey());
		var reordered = DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, data).set(DataComponents.DAMAGE, 17).build();
		var equal = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, reordered), 81),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(equal); assertEquals(frozen.key(), equal.key());
		data.getUnsafe().getIntArray("cells")[0] = 99;
		var changed = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, patch), 81),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(changed); assertNotEquals(frozen.key(), changed.key()); assertEquals(expected, frozen.key().componentsKey());
		assertEquals(Long.MAX_VALUE, frozen.amount()); assertEquals(frozen.display(), changed.display());
	}
	@Test void depthRefusalPreservesReliableAmountAndFlowingFluidIdIsNeverNormalized() {
		var root = new CompoundTag(); var next = root;
		for (int depth = 0; depth <= InventoryItemBudget.MAX_DEPTH; depth++) { var child = new CompoundTag(); next.put("nested", child); next = child; }
		var refused = nbt(root, 81); assertTrue(refused.aggregateRequired()); assertEquals(81, refused.amount());
		var flowing = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.FLOWING_WATER, null), 81),
			RegistryAccess.EMPTY, InventoryItemCodec.minimumDisplayBytes(), new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(flowing); assertFalse(flowing.aggregateRequired()); assertEquals("minecraft:flowing_water", flowing.resourceId());
		assertNotEquals(nbt(null, 81).key(), flowing.key());
		assertTrue(flowing.displayJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= InventoryItemCodec.minimumDisplayBytes());
	}
}
