package nx.pingwheel.forge.platform;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.platform.IPlatformFluidRegistryService;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidValue;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidFreezer;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Requires the real patched Forge artifact first, rather than a wrapper-shaped stand-in. */
@NotNullByDefault
class PlatformFluidRegistryServiceTest {

	private static boolean previousBootstrap;
	private static boolean changed;
	@BeforeAll static void bootstrap() throws ReflectiveOperationException {
		try { Fluid.class.getMethod("getFluidType"); }
		catch (NoSuchMethodException vanillaFirst) {
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "requires patched-first Forge integration classpath");
		}
		SharedConstants.tryDetectVersion();
		var field = Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true);
		previousBootstrap = field.getBoolean(null); field.setBoolean(null, true); changed = true;
		assertNotNull(net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(Fluids.WATER));
		assertSame(Class.forName("net.minecraftforge.registries.NamespacedDefaultedWrapper"), BuiltInRegistries.FLUID.getClass());
	}
	@AfterAll static void restore() throws ReflectiveOperationException {
		if (!changed) return;
		var field = Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true); field.setBoolean(null, previousBootstrap);
	}
	private static InventoryFluidValue freeze(@Nullable CompoundTag tag, long amount) {
		return java.util.Objects.requireNonNull(InventoryFluidFreezer.freeze(
			new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, tag), amount), RegistryAccess.EMPTY,
			1024, new InventoryFluidCodec.Catalog(), new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(1024),
			InventoryCatalogAdmission.workUpperBound(1024))));
	}
	@Test void realCurrentWrapperWaterFreezesExactlyWithAuthoritativeLongAmount() {
		assertInstanceOf(PlatformFluidRegistryServiceImpl.class,
			java.util.ServiceLoader.load(IPlatformFluidRegistryService.class).findFirst().orElseThrow());
		var frozen = freeze(null, 9_007_199_254_740_993L);
		assertEquals("minecraft:water", frozen.resourceId()); assertFalse(frozen.aggregateRequired());
		assertEquals(9_007_199_254_740_993L, frozen.amount());
	}
	@Test void realCurrentWrapperKeepsDistinctNativeNbtVariants() {
		var first = new CompoundTag(); first.putInt("variant", 1);
		var second = new CompoundTag(); second.putInt("variant", 2);
		var a = freeze(first, 81); var b = freeze(second, Long.MAX_VALUE);
		assertFalse(a.aggregateRequired()); assertFalse(b.aggregateRequired()); assertNotEquals(a.key(), b.key());
		assertEquals(Long.MAX_VALUE, b.amount());
	}
	@Test void retainedHolderIsNotProofOfActiveRegistrationAndSameObjectMapsAreReadAgain() throws ReflectiveOperationException {
		var service = new PlatformFluidRegistryServiceImpl();
		IPlatformFluidRegistryService.Admission admission = (work, bytes) -> {};
		ResourceLocation water = ResourceLocation.withDefaultNamespace("water");
		assertEquals(water, service.registeredKey(Fluids.WATER, admission));
		Object wrapper = BuiltInRegistries.FLUID;
		var delegateField = wrapper.getClass().getDeclaredField("delegate"); delegateField.setAccessible(true);
		Object delegate = java.util.Objects.requireNonNull(delegateField.get(wrapper));
		var namesField = delegate.getClass().getDeclaredField("names"); namesField.setAccessible(true);
		@SuppressWarnings("unchecked") var names = (java.util.Map<ResourceLocation, Fluid>) java.util.Objects.requireNonNull(namesField.get(delegate));
		assertSame(Fluids.WATER, names.remove(water));
		try {
			assertTrue(BuiltInRegistries.FLUID.getHolder(water).isPresent(), "the old holder deliberately survives");
			assertNull(service.registeredKey(Fluids.WATER, admission), "current forward registration, not historical holder, is required");
			assertThrows(IllegalArgumentException.class, () -> freeze(null, 81));
		} finally { names.put(water, Fluids.WATER); }
		assertEquals(water, service.registeredKey(Fluids.WATER, admission));
		ResourceLocation lava = ResourceLocation.withDefaultNamespace("lava");
		assertSame(Fluids.LAVA, names.remove(lava)); names.put(water, Fluids.LAVA);
		try {
			assertNull(service.registeredKey(Fluids.WATER, admission), "a retained water holder cannot authorize a replacement fluid under its key");
		} finally { names.put(water, Fluids.WATER); names.put(lava, Fluids.LAVA); }
		try {
			namesField.set(delegate, com.google.common.collect.HashBiMap.create());
			assertNull(service.registeredKey(Fluids.WATER, admission), "same wrapper/delegate cannot cache the old map");
		} finally { namesField.set(delegate, names); }
		assertEquals(water, service.registeredKey(Fluids.WATER, admission));
	}
	@Test void emptyAndUnregisteredHostileFluidDoNotCallHashOrEquality() {
		var service = new PlatformFluidRegistryServiceImpl();
		var hostile = new HostileFluid(); hostile.calls = 0;
		assertNull(service.registeredKey(hostile, (work, bytes) -> {}));
		assertNull(service.registeredKey(Fluids.EMPTY, (work, bytes) -> {}));
		var catalog = new InventoryFluidCodec.Catalog();
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidFreezer.freeze(
			new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(hostile, null), Long.MAX_VALUE), RegistryAccess.EMPTY,
			1024, catalog, new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(1024), InventoryCatalogAdmission.workUpperBound(1024))));
		assertEquals(0, catalog.retainedBytes()); assertEquals(0, hostile.calls);
	}
	@Test void refusalBeforeMetadataAndCurrentMapReadsIsPropagatedUnchanged() throws ReflectiveOperationException {
		var failure = new IllegalStateException("admission sentinel");
		var service = new PlatformFluidRegistryServiceImpl();
		assertSame(failure, assertThrows(IllegalStateException.class,
			() -> service.registeredKey(Fluids.WATER, (work, bytes) -> { throw failure; })));
		// Warm fixed metadata first, then make the actual holder field unreadable. A refused
		// current-read grant must escape before that read can turn into a null result.
		assertNotNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
		var metadataField = service.getClass().getDeclaredField("metadata"); metadataField.setAccessible(true);
		Object metadata = java.util.Objects.requireNonNull(metadataField.get(null));
		var holderField = metadata.getClass().getDeclaredField("holders"); holderField.setAccessible(true);
		var field = (java.lang.reflect.Field) java.util.Objects.requireNonNull(holderField.get(metadata));
		field.setAccessible(false);
		try {
			int[] grants = {0};
			assertSame(failure, assertThrows(IllegalStateException.class, () -> service.registeredKey(Fluids.WATER, (work, bytes) -> {
				if (++grants[0] == 3) throw failure;
			})));
			assertEquals(3, grants[0]);
			assertNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
			assertNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
			assertSame(metadata, metadataField.get(null), "fixed metadata failure cannot cause repeated reflection discovery");
		} finally { field.setAccessible(true); }
		assertNotNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
	}
	@Test void foreignHolderMapAndMismatchedDelegateOrStageFailClosed() throws ReflectiveOperationException {
		var service = new PlatformFluidRegistryServiceImpl();
		Object wrapper = BuiltInRegistries.FLUID;
		Class<?> parent = wrapper.getClass().getSuperclass();
		var holdersField = parent.getDeclaredField("holders"); holdersField.setAccessible(true);
		Object holders = java.util.Objects.requireNonNull(holdersField.get(wrapper));
		var foreign = new ForeignMap();
		try {
			holdersField.set(wrapper, foreign);
			assertNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {})); assertEquals(0, foreign.calls);
		} finally { holdersField.set(wrapper, holders); }
		var stageField = parent.getDeclaredField("stage"); stageField.setAccessible(true);
		Object stage = java.util.Objects.requireNonNull(stageField.get(wrapper));
		try {
			stageField.set(wrapper, net.minecraftforge.registries.RegistryManager.VANILLA);
			assertNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
		} finally { stageField.set(wrapper, stage); }
		var childDelegate = wrapper.getClass().getDeclaredField("delegate"); childDelegate.setAccessible(true);
		Object delegate = java.util.Objects.requireNonNull(childDelegate.get(wrapper));
		try {
			childDelegate.set(wrapper, null);
			assertNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
		} finally { childDelegate.set(wrapper, delegate); }
		assertNotNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
	}
	@Test void coldMetadataFailureIsCachedAndColdAdmissionPrecedesDiscovery() throws ReflectiveOperationException {
		var loader = new MetadataUnavailableLoader();
		var service = (IPlatformFluidRegistryService) loader.loadClass(PlatformFluidRegistryServiceImpl.class.getName()).getConstructor().newInstance();
		var failure = new IllegalStateException("cold admission");
		int[] grants = {0};
		assertSame(failure, assertThrows(IllegalStateException.class, () -> service.registeredKey(Fluids.WATER, (work, bytes) -> {
			if (++grants[0] == 2) throw failure;
		})));
		assertEquals(0, loader.attempts);
		assertNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
		assertNull(service.registeredKey(Fluids.WATER, (work, bytes) -> {}));
		assertEquals(1, loader.attempts, "a fixed descriptor failure cannot retry discovery for every fluid");
	}
	@NotNullByDefault
	private static final class MetadataUnavailableLoader extends ClassLoader {
		private int attempts;
		private MetadataUnavailableLoader() { super(PlatformFluidRegistryServiceTest.class.getClassLoader()); }
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals("net.minecraftforge.registries.NamespacedDefaultedWrapper")) { attempts++; throw new ClassNotFoundException("fixed metadata sentinel"); }
			if (!name.startsWith(PlatformFluidRegistryServiceImpl.class.getName())) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> found = findLoadedClass(name);
				if (found == null) try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
					if (input == null) throw new ClassNotFoundException(name);
					byte[] bytes = input.readAllBytes(); found = defineClass(name, bytes, 0, bytes.length);
				} catch (java.io.IOException unavailable) { throw new ClassNotFoundException(name, unavailable); }
				if (resolve) resolveClass(found); return found;
			}
		}
	}
	@NotNullByDefault
	private static final class HostileFluid extends EmptyFluid {
		private int calls;
		@Override public int hashCode() { calls++; throw new AssertionError("fluid hashCode"); }
		@Override public boolean equals(@Nullable Object other) { calls++; throw new AssertionError("fluid equals"); }
	}
	@NotNullByDefault
	private static final class ForeignMap extends java.util.IdentityHashMap<Object, Object> {
		private static final long serialVersionUID = 1L;
		private int calls;
		@Override public int size() { calls++; throw new AssertionError("foreign size"); }
		@Override public @Nullable Object get(@Nullable Object key) { calls++; throw new AssertionError("foreign get"); }
	}
}
