package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generic registered-persistent-component compatibility through the public
 * freeze entry point.
 *
 * <p>The expected identity is always the full effective component map encoded
 * by the native persistent map codec and then passed through the established
 * canonical-tag oracle. The tests do not call the external-component helper,
 * registry-audit internals, or the owned-tag copier directly.
 *
 * <p>The component registration fixture is test-only. Registries are frozen by
 * {@link Bootstrap#bootStrap()}, so the fixture temporarily clears the frozen
 * flag, registers its own uniquely named types, and restores every touched
 * registry field in {@link #close()}. It is not a pattern for production code.
 */
class InventoryGenericComponentCompatibilityTest {

	private static final int DISPLAY = 4096;

	private static final AtomicInteger CACHED_INT_CALLS = new AtomicInteger();
	private static final AtomicInteger RECORD_CALLS = new AtomicInteger();
	private static final AtomicInteger GUARDED_TRAP_CALLS = new AtomicInteger();
	private static final AtomicInteger UNREGISTERED_CALLS = new AtomicInteger();
	private static final AtomicInteger PARTIAL_CALLS = new AtomicInteger();
	private static final AtomicInteger OVERSIZED_CALLS = new AtomicInteger();
	private static final AtomicInteger PROVIDER_CALLS = new AtomicInteger();

	private static final Object GUARDED_TRAP = new Object();
	private static final Object GUARDED_SAFE = new Object();

	private static final ResourceKey<Registry<Integer>> CUSTOM_REGISTRY = ResourceKey.createRegistryKey(
		ResourceLocation.fromNamespaceAndPath("pingforit_test", "numbers"));
	private static final ResourceKey<Integer> CUSTOM_LOOKUP_KEY = ResourceKey.create(
		CUSTOM_REGISTRY, ResourceLocation.fromNamespaceAndPath("pingforit_test", "value"));

	private static final Codec<Integer> CACHED_INT_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(Integer input, DynamicOps<T> ops, T prefix) {
			CACHED_INT_CALLS.incrementAndGet();
			return DataResult.success(ops.createInt(input));
		}
		@Override public <T> DataResult<Pair<Integer, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.error(() -> "decode unused");
		}
	};

	private static final Codec<TestRecord> RECORD_BASE = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("label").forGetter(TestRecord::label),
		Codec.INT.fieldOf("amount").forGetter(TestRecord::amount)
	).apply(instance, TestRecord::new));

	/** Non-cache-wrapped, so tightening must not be able to hide a repeat call behind a cache hit. */
	private static final Codec<TestRecord> RECORD_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(TestRecord input, DynamicOps<T> ops, T prefix) {
			RECORD_CALLS.incrementAndGet();
			return RECORD_BASE.encode(input, ops, prefix);
		}
		@Override public <T> DataResult<Pair<TestRecord, T>> decode(DynamicOps<T> ops, T input) {
			return RECORD_BASE.decode(ops, input);
		}
	};

	private static final Codec<Map<String, Integer>> MAP_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT);

	private static final Codec<Object> GUARDED_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(Object input, DynamicOps<T> ops, T prefix) {
			if (input == GUARDED_TRAP) {
				GUARDED_TRAP_CALLS.incrementAndGet();
				throw new IllegalStateException("shadowed prototype value was encoded");
			}
			return DataResult.success(ops.createInt(1));
		}
		@Override public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.error(() -> "decode unused");
		}
	};

	private static final Codec<Object> UNREGISTERED_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(Object input, DynamicOps<T> ops, T prefix) {
			UNREGISTERED_CALLS.incrementAndGet();
			return DataResult.success(ops.createInt(1));
		}
		@Override public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.error(() -> "decode unused");
		}
	};

	private static final Codec<Object> PARTIAL_ERROR_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(Object input, DynamicOps<T> ops, T prefix) {
			PARTIAL_CALLS.incrementAndGet();
			return DataResult.error(() -> "foreign codec error", ops.createString("partial-secret"));
		}
		@Override public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.error(() -> "decode unused");
		}
	};

	private static final Codec<Object> THROWING_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(Object input, DynamicOps<T> ops, T prefix) {
			throw new IllegalStateException("foreign codec failure");
		}
		@Override public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
			throw new IllegalStateException("foreign codec failure");
		}
	};

	private static final Codec<Object> OVERSIZED_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(Object input, DynamicOps<T> ops, T prefix) {
			OVERSIZED_CALLS.incrementAndGet();
			return DataResult.success(ops.createString("x".repeat(InventoryItemCodec.MAX_CANONICAL_BYTES + 1)));
		}
		@Override public <T> DataResult<Pair<Object, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.error(() -> "decode unused");
		}
	};

	private static final Codec<MutableTree> MUTABLE_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(MutableTree input, DynamicOps<T> ops, T prefix) {
			if (!(ops.empty() instanceof Tag)) return DataResult.error(() -> "nbt only");
			@SuppressWarnings("unchecked")
			T result = (T) input.tag();
			return DataResult.success(result);
		}
		@Override public <T> DataResult<Pair<MutableTree, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.error(() -> "decode unused");
		}
	};

	private static final Codec<ResourceKey<Integer>> PROVIDER_CODEC = new Codec<>() {
		@Override public <T> DataResult<T> encode(ResourceKey<Integer> input, DynamicOps<T> ops, T prefix) {
			PROVIDER_CALLS.incrementAndGet();
			if (!(ops instanceof RegistryOps<?>)) return DataResult.error(() -> "registry ops required");
			@SuppressWarnings("unchecked")
			RegistryOps<T> registryOps = (RegistryOps<T>) ops;
			var getter = registryOps.getter(CUSTOM_REGISTRY);
			if (getter.isEmpty()) return DataResult.error(() -> "custom registry unavailable");
			var holder = getter.get().get(input);
			if (holder.isEmpty()) return DataResult.error(() -> "custom registry key unavailable");
			return DataResult.success(ops.createInt(holder.get().value()));
		}
		@Override public <T> DataResult<Pair<ResourceKey<Integer>, T>> decode(DynamicOps<T> ops, T input) {
			return DataResult.error(() -> "decode unused");
		}
	};

	private static RegistryAccess registries;
	private static TestComponentRegistration registration;

	private static DataComponentType<Integer> cachedInt;
	private static DataComponentType<TestRecord> recordComponent;
	private static DataComponentType<Map<String, Integer>> mapComponent;
	private static DataComponentType<Object> guardedComponent;
	private static DataComponentType<Object> unregisteredComponent;
	private static DataComponentType<Object> partialErrorComponent;
	private static DataComponentType<Object> throwingComponent;
	private static DataComponentType<Object> oversizedComponent;
	private static DataComponentType<MutableTree> mutableComponent;
	private static DataComponentType<ResourceKey<Integer>> providerComponent;
	private static DataComponentType<Object> transientComponent;

	@BeforeAll static void bootstrap() throws ReflectiveOperationException {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		initializeComponents();
		registration = TestComponentRegistration.install();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@AfterAll static void restoreRegistries() throws ReflectiveOperationException {
		if (registration != null) registration.close();
	}

	private static void initializeComponents() {
		cachedInt = DataComponentType.<Integer>builder().persistent(CACHED_INT_CODEC).cacheEncoding().build();
		recordComponent = DataComponentType.<TestRecord>builder().persistent(RECORD_CODEC).build();
		mapComponent = DataComponentType.<Map<String, Integer>>builder().persistent(MAP_CODEC).build();
		guardedComponent = DataComponentType.<Object>builder().persistent(GUARDED_CODEC).build();
		unregisteredComponent = DataComponentType.<Object>builder().persistent(UNREGISTERED_CODEC).build();
		partialErrorComponent = DataComponentType.<Object>builder().persistent(PARTIAL_ERROR_CODEC).build();
		throwingComponent = DataComponentType.<Object>builder().persistent(THROWING_CODEC).build();
		oversizedComponent = DataComponentType.<Object>builder().persistent(OVERSIZED_CODEC).build();
		mutableComponent = DataComponentType.<MutableTree>builder().persistent(MUTABLE_CODEC).build();
		providerComponent = DataComponentType.<ResourceKey<Integer>>builder().persistent(PROVIDER_CODEC).build();
		transientComponent = DataComponentType.<Object>builder()
			.networkSynchronized(StreamCodec.<RegistryFriendlyByteBuf, Object>unit(new Object())).build();
	}

	@Test void registeredUnknownIntegerRecordAndMapUseExactNativeIdentity() {
		var stack = new ItemStack(Items.STONE);
		stack.set(cachedInt, 810001);
		stack.set(recordComponent, new TestRecord("alpha", 3));
		stack.set(mapComponent, Map.of("first", 1, "second", 2));

		CACHED_INT_CALLS.set(0);
		var frozen = freeze(stack);
		assertTrue(CACHED_INT_CALLS.get() > 0, "the cache-wrapped registered codec actually ran");
		assertExact(frozen, stack.getComponents());
		assertFalse(frozen.display().aggregateRequired());
	}

	@Test void sameRegisteredComponentWithDifferentValuesKeepsDifferentKeys() {
		var firstStack = new ItemStack(Items.STONE);
		firstStack.set(cachedInt, 810002);
		var secondStack = new ItemStack(Items.STONE);
		secondStack.set(cachedInt, 810003);

		var first = freeze(firstStack);
		var second = freeze(secondStack);
		assertExact(first, firstStack.getComponents());
		assertExact(second, secondStack.getComponents());
		assertFalse(Arrays.equals(first.canonicalForCatalog(), second.canonicalForCatalog()));

		var catalog = new InventoryItemCodec.Catalog(registries);
		var firstKey = catalog.encode(firstStack).key();
		var secondKey = catalog.encode(secondStack).key();
		assertNotEquals(firstKey, secondKey);
		assertFalse(firstKey.componentsKey().isEmpty());
		assertFalse(secondKey.componentsKey().isEmpty());
	}

	@Test void unknownDisplaySuppressesOnlyOpaqueValueAndKeepsNativeFields() {
		var stack = new ItemStack(Items.STONE);
		stack.set(cachedInt, 424242);
		stack.set(DataComponents.DAMAGE, 5);

		var frozen = freeze(stack);
		assertExact(frozen, stack.getComponents());
		assertFalse(frozen.display().componentsStripped());
		var components = displayComponents(frozen.display());
		assertTrue(components.has("!pingforit_test:cached_int"));
		assertEquals(0, components.getAsJsonObject("!pingforit_test:cached_int").size());
		assertEquals(5, components.get("minecraft:damage").getAsInt());
		assertFalse(frozen.display().displayJson().contains("424242"));
	}

	@Test void prototypeDefaultAndExplicitEqualValueHaveSameExactIdentity() {
		var prototype = DataComponentMap.builder().addAll(Items.STONE.components())
			.set(guardedComponent, GUARDED_SAFE).set(cachedInt, 7).build();
		var raw = prototypeRaw(prototype);
		var implicit = freezePatch(DataComponentPatch.EMPTY, raw);
		var explicitPatch = DataComponentPatch.builder()
			.set(guardedComponent, GUARDED_SAFE).set(cachedInt, 7).build();
		var explicit = freezePatch(explicitPatch, raw);

		assertExact(implicit, PatchedDataComponentMap.fromPatch(prototype, DataComponentPatch.EMPTY));
		assertExact(explicit, PatchedDataComponentMap.fromPatch(prototype, explicitPatch));
		assertArrayEquals(implicit.canonicalForCatalog(), explicit.canonicalForCatalog());
		assertEquals(0, GUARDED_TRAP_CALLS.get());
	}

	@Test void overrideAndRemovalNeverEncodeShadowedPrototypeValue() {
		var prototype = DataComponentMap.builder().addAll(Items.STONE.components())
			.set(guardedComponent, GUARDED_TRAP).build();
		var raw = prototypeRaw(prototype);

		GUARDED_TRAP_CALLS.set(0);
		var overridePatch = DataComponentPatch.builder().set(guardedComponent, GUARDED_SAFE).build();
		var overridden = freezePatch(overridePatch, raw);
		assertExact(overridden, PatchedDataComponentMap.fromPatch(prototype, overridePatch));
		assertEquals(0, GUARDED_TRAP_CALLS.get());

		GUARDED_TRAP_CALLS.set(0);
		var removalPatch = DataComponentPatch.builder().remove(guardedComponent).build();
		var removed = freezePatch(removalPatch, raw);
		assertExact(removed, PatchedDataComponentMap.fromPatch(prototype, removalPatch));
		assertEquals(0, GUARDED_TRAP_CALLS.get());
	}

	@Test void registeredTransientComponentIsExcludedWithoutValueEncoding() {
		var transientValue = new Object();
		var stack = new ItemStack(Items.STONE);
		stack.set(transientComponent, transientValue);

		var frozen = freeze(stack);
		assertExact(frozen, stack.getComponents());
		assertEquals(freeze(new ItemStack(Items.STONE)).display().displayJson(), frozen.display().displayJson());
	}

	@Test void unregisteredPersistentComponentNeverInvokesItsCodec() {
		var stack = new ItemStack(Items.STONE);
		stack.set(unregisteredComponent, new Object());

		UNREGISTERED_CALLS.set(0);
		var frozen = freeze(stack);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, frozen.identityMode());
		assertNull(frozen.canonicalForCatalog());
		assertTrue(frozen.display().aggregateRequired());
		assertEquals(0, UNREGISTERED_CALLS.get());

		var encoded = new InventoryItemCodec.Catalog(registries).encode(stack);
		assertTrue(encoded.key().componentsKey().isEmpty());
		assertEquals(0, UNREGISTERED_CALLS.get());
	}

	@Test void codecErrorWithPartialResultCannotSupplyPartialIdentity() {
		var stack = new ItemStack(Items.STONE);
		stack.set(partialErrorComponent, new Object());

		PARTIAL_CALLS.set(0);
		var frozen = freeze(stack);
		assertTrue(PARTIAL_CALLS.get() > 0, "the registered codec was actually invoked");
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, frozen.identityMode());
		assertNull(frozen.canonicalForCatalog());
		assertTrue(frozen.display().aggregateRequired());
		assertFalse(frozen.display().displayJson().contains("partial-secret"));
	}

	@Test void throwingCodecPropagatesAndLeavesTheCaptureBudgetReusable() {
		var stack = new ItemStack(Items.STONE);
		stack.set(throwingComponent, new Object());
		var budget = InventoryItemBudget.capture(DISPLAY, 2, Long.MAX_VALUE, Long.MAX_VALUE);

		var failure = assertThrows(IllegalStateException.class, () -> freeze(
			new InventoryResidentItem.Stack(stack), registries, DISPLAY, budget, new InventoryResidentAccessFields()));
		assertEquals("foreign codec failure", failure.getMessage());

		var clean = freeze(new InventoryResidentItem.Stack(new ItemStack(Items.STONE)),
			registries, DISPLAY, budget, new InventoryResidentAccessFields());
		assertEquals(InventoryIdentityMode.EXACT, clean.identityMode());
	}

	@Test void foreignCodecOwnedTreeIsDetachedAndPreservesNbtKinds() {
		var value = new MutableTree("before", 7);
		var stack = new ItemStack(Items.STONE);
		stack.set(mutableComponent, value);

		var frozen = freeze(stack);
		Tag expected = assertExact(frozen, stack.getComponents());
		var tree = ((CompoundTag) expected).getCompound("pingforit_test:mutable_tree");
		assertInstanceOf(ListTag.class, tree.get("list"));
		assertInstanceOf(ByteArrayTag.class, tree.get("bytes"));
		assertInstanceOf(IntArrayTag.class, tree.get("ints"));
		assertInstanceOf(LongArrayTag.class, tree.get("longs"));
		assertInstanceOf(ListTag.class, tree.get("empty_list"));
		assertTrue(((ListTag) tree.get("empty_list")).isEmpty());
		assertInstanceOf(ListTag.class, tree.get("empty_typed_list"));
		var typedEmpty = (ListTag) tree.get("empty_typed_list");
		assertTrue(typedEmpty.isEmpty());
		assertEquals(Tag.TAG_INT, typedEmpty.getElementType());
		assertInstanceOf(CompoundTag.class, tree.get("wrapper"));
		assertTrue(((CompoundTag) tree.get("wrapper")).contains(""));

		byte[] canonical = frozen.canonicalForCatalog().clone();
		String display = frozen.display().displayJson();
		assertNotNull(value.returned);
		value.returned.putString("text", "after");
		value.returned.getList("list", Tag.TAG_INT).add(IntTag.valueOf(99));
		value.returned.getByteArray("bytes")[0] = 42;
		value.returned.getIntArray("ints")[0] = 42;
		value.returned.getLongArray("longs")[0] = 42L;
		value.text = "changed";
		value.number = 42;

		assertArrayEquals(canonical, frozen.canonicalForCatalog());
		assertEquals(display, frozen.display().displayJson());

		var catalog = new InventoryItemCodec.Catalog(registries);
		var key = catalog.intern(frozen).key();
		assertEquals(key, catalog.intern(frozen).key());
	}

	@Test void suppliedProviderIsUsedAndNeverSharedAcrossRegistries() {
		var stack = new ItemStack(Items.STONE);
		stack.set(providerComponent, CUSTOM_LOOKUP_KEY);
		var firstProvider = provider(1);
		var secondProvider = provider(7);

		PROVIDER_CALLS.set(0);
		var first = freeze(stack, firstProvider, DISPLAY);
		assertTrue(PROVIDER_CALLS.get() > 0, "the actual registered codec ran with the supplied provider");
		assertExact(first, stack.getComponents(), firstProvider);
		byte[] firstBytes = first.canonicalForCatalog().clone();

		PROVIDER_CALLS.set(0);
		var second = freeze(stack, secondProvider, DISPLAY);
		assertTrue(PROVIDER_CALLS.get() > 0, "a second provider must not reuse the first encoding");
		assertExact(second, stack.getComponents(), secondProvider);
		assertFalse(Arrays.equals(firstBytes, second.canonicalForCatalog()));

		PROVIDER_CALLS.set(0);
		var repeated = freeze(stack, firstProvider, DISPLAY);
		assertTrue(PROVIDER_CALLS.get() > 0);
		assertArrayEquals(firstBytes, repeated.canonicalForCatalog());
	}

	@Test void displayTighteningKeepsExactKeyAndDoesNotReencodeComponent() {
		var stack = new ItemStack(Items.STONE);
		stack.set(recordComponent, new TestRecord("tighten", 19));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("n".repeat(3000)));

		RECORD_CALLS.set(0);
		var detached = freeze(stack, registries, 8192);
		assertTrue(RECORD_CALLS.get() > 0, "the non-cache record codec actually ran before tightening");
		assertExact(detached, stack.getComponents());
		var catalog = new InventoryItemCodec.Catalog(registries, 8192);
		var key = catalog.intern(detached).key();
		var before = catalog.displays().get(key).displayJson();
		assertNotNull(before);

		RECORD_CALLS.set(0);
		catalog.tightenDisplay(InventoryItemCodec.minimumDisplayBytes());
		assertEquals(0, RECORD_CALLS.get(), "display tightening must not re-run the resident codec");
		assertEquals(InventoryItemCodec.minimumDisplayBytes(), catalog.displayBytes());
		assertTrue(catalog.displays().containsKey(key));
		String tightened = catalog.displays().get(key).displayJson();
		assertNotNull(tightened);
		assertTrue(tightened.getBytes(StandardCharsets.UTF_8).length <= InventoryItemCodec.minimumDisplayBytes());
		assertTrue(tightened.contains("!pingforit_test:record"));
		assertEquals(key, catalog.intern(detached).key());
		assertEquals(0, RECORD_CALLS.get());
	}

	@Test void oversizedForeignCodecResultFallsBackWithoutPartialIdentity() {
		var stack = new ItemStack(Items.STONE);
		stack.set(oversizedComponent, new Object());

		OVERSIZED_CALLS.set(0);
		var frozen = freeze(stack);
		assertTrue(OVERSIZED_CALLS.get() > 0, "the oversized codec result was actually produced");
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, frozen.identityMode());
		assertNull(frozen.canonicalForCatalog());
		assertTrue(frozen.display().aggregateRequired());
	}

	private static InventoryDetachedItem freeze(ItemStack stack) {
		return freeze(new InventoryResidentItem.Stack(stack), registries, DISPLAY);
	}

	private static InventoryDetachedItem freeze(ItemStack stack, HolderLookup.Provider provider, int displayBytes) {
		return freeze(new InventoryResidentItem.Stack(stack), provider, displayBytes);
	}

	private static InventoryDetachedItem freeze(InventoryResidentItem input) {
		return freeze(input, registries, DISPLAY);
	}

	private static InventoryDetachedItem freeze(InventoryResidentItem input, HolderLookup.Provider provider, int displayBytes) {
		return freeze(input, provider, displayBytes, InventoryItemBudget.root(displayBytes), new InventoryResidentAccessFields());
	}

	private static InventoryDetachedItem freeze(InventoryResidentItem input, HolderLookup.Provider provider,
		int displayBytes, InventoryItemBudget budget, InventoryResidentAccess raw) {
		return InventoryItemCodec.freeze(input, provider, displayBytes, budget, raw, new InventoryTestNativeAccess());
	}

	private static InventoryDetachedItem freezePatch(DataComponentPatch patch, InventoryResidentAccess raw) {
		return freeze(new InventoryResidentItem.Patch(Items.STONE, patch), registries, DISPLAY,
			InventoryItemBudget.root(DISPLAY), raw);
	}

	private static InventoryResidentAccess prototypeRaw(DataComponentMap prototype) {
		return new InventoryResidentAccessFields() {
			@Override public DataComponentMap itemComponents(Item item) { return prototype; }
		};
	}

	private static Tag assertExact(InventoryDetachedItem actual, DataComponentMap effective) {
		return assertExact(actual, effective, registries);
	}

	private static Tag assertExact(InventoryDetachedItem actual, DataComponentMap effective, HolderLookup.Provider provider) {
		assertEquals(InventoryIdentityMode.EXACT, actual.identityMode());
		assertFalse(actual.display().aggregateRequired());
		Tag expected = nativePersistentMap(effective, provider);
		assertArrayEquals(InventoryItemCodec.canonicalTag(expected), actual.canonicalForCatalog());
		return expected;
	}

	private static Tag nativePersistentMap(DataComponentMap map, HolderLookup.Provider provider) {
		DataComponentMap persistent = map.filter(type -> !type.isTransient());
		return DataComponentMap.makeCodec(DataComponentType.PERSISTENT_CODEC)
			.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), persistent)
			.result().orElseThrow();
	}

	private static JsonObject displayComponents(InventoryItemCodec.Display display) {
		assertNotNull(display.displayJson());
		return JsonParser.parseString(display.displayJson()).getAsJsonObject().getAsJsonObject("components");
	}

	private static HolderLookup.Provider provider(int value) {
		return new RegistrySetBuilder()
			.add(CUSTOM_REGISTRY, context -> context.register(CUSTOM_LOOKUP_KEY, value))
			.build(registries);
	}

	private record TestRecord(String label, int amount) {}

	/** NBT cannot create an empty list with a non-zero element type via its public API. */
	private static ListTag emptyTypedList(byte elementType) {
		try {
			var constructor = ListTag.class.getDeclaredConstructor(List.class, byte.class);
			constructor.setAccessible(true);
			return constructor.newInstance(List.of(), elementType);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("empty typed list fixture", failure);
		}
	}

	private static final class MutableTree {
		private String text;
		private int number;
		private CompoundTag tag;
		private CompoundTag returned;

		private MutableTree(String text, int number) { this.text = text; this.number = number; }

		private CompoundTag tag() {
			if (tag == null) {
				var root = new CompoundTag();
				var list = new ListTag(); list.add(IntTag.valueOf(number)); root.put("list", list);
				root.putByteArray("bytes", new byte[] {1, 2});
				root.putIntArray("ints", new int[] {3, 4});
				root.putLongArray("longs", new long[] {5, 6});
				root.put("empty_list", new ListTag());
				root.put("empty_typed_list", emptyTypedList(Tag.TAG_INT));
				var wrapper = new CompoundTag(); wrapper.put("", IntTag.valueOf(9)); root.put("wrapper", wrapper);
				root.putString("text", text);
				tag = root;
			}
			returned = tag;
			return tag;
		}
	}

	private record Registration(String id, DataComponentType<?> type) {}

	/**
	 * Adds uniquely named test components to the frozen built-in data-component
	 * registry and removes them again. Every map, the by-id list, the registry
	 * lifecycle and the frozen flag are restored; no production file is touched.
	 */
	private static final class TestComponentRegistration implements AutoCloseable {
		private final MappedRegistry<?> registry;
		private final List<Object> byId;
		private final Map<Object, Object> byLocation;
		private final Map<Object, Object> byKey;
		private final Map<Object, Object> byValue;
		private final Map<Object, Object> registrationInfos;
		private final Map<Object, Object> toId;
		private final boolean wasFrozen;
		private final Lifecycle wasLifecycle;
		private final int originalSize;
		private final List<Installed> installed = new ArrayList<>();
		private boolean closed;

		private TestComponentRegistration(MappedRegistry<?> registry, List<Object> byId,
			Map<Object, Object> byLocation, Map<Object, Object> byKey, Map<Object, Object> byValue,
			Map<Object, Object> registrationInfos, Map<Object, Object> toId,
			boolean wasFrozen, Lifecycle wasLifecycle) {
			this.registry = registry; this.byId = byId; this.byLocation = byLocation; this.byKey = byKey;
			this.byValue = byValue; this.registrationInfos = registrationInfos; this.toId = toId;
			this.wasFrozen = wasFrozen; this.wasLifecycle = wasLifecycle; this.originalSize = byId.size();
		}

		private static TestComponentRegistration install() throws ReflectiveOperationException {
			if (!(BuiltInRegistries.DATA_COMPONENT_TYPE instanceof MappedRegistry<?> registry))
				throw new IllegalStateException("data component registry is not a MappedRegistry");
			@SuppressWarnings("unchecked")
			List<Object> byId = (List<Object>) field(registry, "byId");
			@SuppressWarnings("unchecked")
			Map<Object, Object> byLocation = (Map<Object, Object>) field(registry, "byLocation");
			@SuppressWarnings("unchecked")
			Map<Object, Object> byKey = (Map<Object, Object>) field(registry, "byKey");
			@SuppressWarnings("unchecked")
			Map<Object, Object> byValue = (Map<Object, Object>) field(registry, "byValue");
			@SuppressWarnings("unchecked")
			Map<Object, Object> registrationInfos = (Map<Object, Object>) field(registry, "registrationInfos");
			@SuppressWarnings("unchecked")
			Map<Object, Object> toId = (Map<Object, Object>) field(registry, "toId");
			boolean wasFrozen = (Boolean) field(registry, "frozen");
			Lifecycle wasLifecycle = (Lifecycle) field(registry, "registryLifecycle");
			var result = new TestComponentRegistration(registry, byId, byLocation, byKey, byValue,
				registrationInfos, toId, wasFrozen, wasLifecycle);
			set(registry, "frozen", false);
			try {
				for (Registration registration : registrations()) result.add(registration);
			} catch (Throwable failure) {
				result.close();
				throw failure;
			}
			set(registry, "frozen", wasFrozen);
			return result;
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		private void add(Registration registration) {
			ResourceKey key = ResourceKey.create(Registries.DATA_COMPONENT_TYPE,
				ResourceLocation.fromNamespaceAndPath("pingforit_test", registration.id()));
			Registry.register((Registry) BuiltInRegistries.DATA_COMPONENT_TYPE, key, registration.type());
			installed.add(new Installed(key, registration.type()));
		}

		@Override public void close() throws ReflectiveOperationException {
			if (closed) return;
			try {
				set(registry, "frozen", false);
				for (Installed entry : installed) {
					byLocation.remove(entry.key().location());
					byKey.remove(entry.key());
					registrationInfos.remove(entry.key());
					toId.remove(entry.type());
					byValue.remove(entry.type());
				}
				while (byId.size() > originalSize) byId.remove(byId.size() - 1);
				set(registry, "registryLifecycle", wasLifecycle);
			} finally {
				set(registry, "frozen", wasFrozen);
				closed = true;
			}
		}

		private static Object field(Object owner, String name) throws ReflectiveOperationException {
			Field field = MappedRegistry.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(owner);
		}

		private static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
			Field field = MappedRegistry.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(owner, value);
		}

		private record Installed(ResourceKey<?> key, DataComponentType<?> type) {}
	}

	private static List<Registration> registrations() {
		return List.of(
			new Registration("cached_int", cachedInt),
			new Registration("record", recordComponent),
			new Registration("map", mapComponent),
			new Registration("guarded", guardedComponent),
			new Registration("partial_error", partialErrorComponent),
			new Registration("throwing", throwingComponent),
			new Registration("oversized", oversizedComponent),
			new Registration("mutable_tree", mutableComponent),
			new Registration("provider", providerComponent),
			new Registration("transient", transientComponent));
	}
}
