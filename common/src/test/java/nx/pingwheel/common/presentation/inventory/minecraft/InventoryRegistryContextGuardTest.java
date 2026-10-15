package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryRegistryContextGuardTest {
	private static RegistryAccess builtin;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	private static InventoryDetachedItem freeze(ItemStack stack, HolderLookup.Provider provider) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), provider, 4096,
			InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}
	private static void oracle(ItemStack stack, HolderLookup.Provider provider) {
		var frozen = freeze(stack, provider); assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
		var original = DataComponentMap.CODEC.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), stack.getComponents()).result().orElseThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(original), frozen.canonicalForCatalog());
	}

	@Test void unknownProviderRefusesBeforeEveryProviderCallback() {
		var calls = new AtomicInteger();
		var provider = new HolderLookup.Provider() {
			@Override public Stream<ResourceKey<? extends Registry<?>>> listRegistries() { calls.incrementAndGet(); throw new AssertionError("list"); }
			@Override public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) { calls.incrementAndGet(); throw new AssertionError("lookup"); }
			@Override public <T> RegistryOps<T> createSerializationContext(DynamicOps<T> ops) { calls.incrementAndGet(); throw new AssertionError("context"); }
		};
		assertThrows(AssertionError.class, () -> provider.createSerializationContext(NbtOps.INSTANCE)); calls.set(0);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(new ItemStack(Items.DIAMOND_SWORD), provider).identityMode());
		assertEquals(0, calls.get());
	}

	@Test void actualRootAndFrozenNativeContainersKeepDirectAndNamedSwordIdentity() {
		oracle(new ItemStack(Items.DIAMOND_SWORD), builtin);
		RegistryAccess frozen = new RegistryAccess.ImmutableRegistryAccess(builtin.registries()).freeze();
		oracle(new ItemStack(Items.DIAMOND_SWORD), frozen);
	}

	@Test void missingOwnerRefusesBeforeFileInlineTraversal() {
		Holder.Reference<Instrument> holder = BuiltInRegistries.INSTRUMENT.getHolder(0).orElseThrow();
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.INSTRUMENT, holder);
		// A real compatible owner produces the native key; the missing-owner
		// branch must never expand Instrument's element codec in guarded freeze.
		oracle(stack, builtin);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, RegistryAccess.EMPTY).identityMode());
	}

	@Test void missingOwnerCannotInvokeEvenBoundInlineInstrumentSoundHolder() throws Exception {
		var calls = new AtomicInteger();
		Holder<net.minecraft.sounds.SoundEvent> sound = new Holder<>() {
			private AssertionError trap() { calls.incrementAndGet(); return new AssertionError("inline sound holder"); }
			@Override public net.minecraft.sounds.SoundEvent value() { throw trap(); }
			@Override public boolean isBound() { throw trap(); }
			@Override public boolean is(ResourceLocation value) { throw trap(); }
			@Override public boolean is(ResourceKey<net.minecraft.sounds.SoundEvent> value) { throw trap(); }
			@Override public boolean is(java.util.function.Predicate<ResourceKey<net.minecraft.sounds.SoundEvent>> value) { throw trap(); }
			@Override public boolean is(TagKey<net.minecraft.sounds.SoundEvent> value) { throw trap(); }
			@Override public boolean is(Holder<net.minecraft.sounds.SoundEvent> value) { throw trap(); }
			@Override public Stream<TagKey<net.minecraft.sounds.SoundEvent>> tags() { throw trap(); }
			@Override public com.mojang.datafixers.util.Either<ResourceKey<net.minecraft.sounds.SoundEvent>, net.minecraft.sounds.SoundEvent> unwrap() { throw trap(); }
			@Override public Optional<ResourceKey<net.minecraft.sounds.SoundEvent>> unwrapKey() { throw trap(); }
			@Override public Kind kind() { throw trap(); }
			@Override public boolean canSerializeIn(HolderOwner<net.minecraft.sounds.SoundEvent> owner) { throw trap(); }
		};
		var key = ResourceKey.create(Registries.INSTRUMENT, ResourceLocation.parse("test:inline"));
		Holder.Reference<Instrument> holder = Holder.Reference.createStandAlone(BuiltInRegistries.INSTRUMENT.asLookup(), key);
		var field = Holder.Reference.class.getDeclaredField("value"); field.setAccessible(true); field.set(holder, new Instrument(sound, 1, 1));
		assertThrows(AssertionError.class, () -> Instrument.CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(NbtOps.INSTANCE), holder));
		assertTrue(calls.get() > 0); calls.set(0);
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.INSTRUMENT, holder);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, RegistryAccess.EMPTY).identityMode()); assertEquals(0, calls.get());
	}

	@Test void boundForeignOwnerNeverRunsCompatibilityOrFormattingHooks() throws Exception {
		var calls = new AtomicInteger();
		HolderOwner<Instrument> trap = new HolderOwner<>() {
			@Override public boolean canSerializeIn(HolderOwner<Instrument> owner) { calls.incrementAndGet(); throw new AssertionError("compatibility"); }
			@Override public String toString() { calls.incrementAndGet(); throw new AssertionError("format"); }
		};
		Holder.Reference<Instrument> nativeHolder = BuiltInRegistries.INSTRUMENT.getHolder(0).orElseThrow();
		Holder.Reference<Instrument> foreign = Holder.Reference.createStandAlone(trap, nativeHolder.key());
		var field = Holder.Reference.class.getDeclaredField("value"); field.setAccessible(true); field.set(foreign, nativeHolder.value());
		assertThrows(AssertionError.class, () -> Instrument.CODEC.encodeStart(builtin.createSerializationContext(NbtOps.INSTANCE), foreign));
		assertEquals(1, calls.get()); calls.set(0);
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.INSTRUMENT, foreign);
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, freeze(stack, builtin).identityMode()); assertEquals(0, calls.get());
	}

	@Test void namedHolderSetEncodesOnlyAdmittedKeyWithoutMemberEnumeration() throws Exception {
		var named = HolderSet.emptyNamed(BuiltInRegistries.BLOCK.asLookup(), TagKey.create(Registries.BLOCK, ResourceLocation.parse("test:bounded")));
		var field = HolderSet.Named.class.getDeclaredField("contents"); field.setAccessible(true);
		Object previous = field.get(named);
		field.set(named, new java.util.AbstractList<>() {
			@Override public Object get(int index) { throw new AssertionError("named member read"); }
			@Override public int size() { throw new AssertionError("named size read"); }
		});
		try {
			var stack = new ItemStack(Items.STONE);
			stack.set(DataComponents.TOOL, new Tool(List.of(new Tool.Rule(named, Optional.empty(), Optional.empty())), 1, 1));
			oracle(stack, builtin);
		} finally { field.set(named, previous); }
	}

	@Test void builderUniversalOwnerAndNonemptyEnchantmentsKeepOriginalGrammar() {
		var key = ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse("test:finite"));
		// Encoding only needs a bound reference; the registered element is not
		// traversed by the fixed holder codec.
		HolderLookup.Provider provider = new RegistrySetBuilder().add(Registries.ENCHANTMENT,
			context -> context.register(key, Enchantment.enchantment(
				Enchantment.definition(HolderSet.direct(), 1, 1, Enchantment.constantCost(1), Enchantment.constantCost(1), 1))
				.build(ResourceLocation.parse("test:finite")))).build(builtin);
		Holder<Enchantment> holder = provider.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
		var mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY); mutable.set(holder, 3);
		for (boolean tooltip : new boolean[] {true, false}) {
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable().withTooltip(tooltip));
			oracle(stack, provider);
		}
		var empty = new ItemStack(Items.STONE); empty.set(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY.withTooltip(false)); oracle(empty, builtin);
	}
}
