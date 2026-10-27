package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DebugStickState;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Native static codecs with the production RegistryOps conversion semantics. */
class InventoryComponentFidelityTest {
	private static RegistryAccess registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static InventoryDetachedItem freeze(ItemStack stack) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
			InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}

	private static <T> InventoryDetachedItem oracle(DataComponentType<T> type, T value) {
		var stack = new ItemStack(Items.STONE); stack.set(type, value);
		var frozen = freeze(stack);
		assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
		var nbtOps = registries.createSerializationContext(NbtOps.INSTANCE);
		Tag nativeMap = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(nbtOps, ((net.minecraft.core.component.PatchedDataComponentMap) stack.getComponents()).asPatch()).result().orElseThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(nativeMap), frozen.canonicalForCatalog());
		var jsonOps = nbtOps.withParent(JsonOps.INSTANCE);
		var nativeValue = originalCodec(type).encodeStart(jsonOps, value).result().orElseThrow();
		var display = JsonParser.parseString(frozen.display().displayJson()).getAsJsonObject().getAsJsonObject("components");
		assertEquals(nativeValue, display.get(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString()));
		return frozen;
	}

	@SuppressWarnings("unchecked")
	private static <T> com.mojang.serialization.Codec<T> originalCodec(DataComponentType<T> type) {
		if (type == DataComponents.CUSTOM_DATA) return (com.mojang.serialization.Codec<T>) CustomData.CODEC;
		if (type == DataComponents.BEES) return (com.mojang.serialization.Codec<T>) BeehiveBlockEntity.Occupant.LIST_CODEC;
		if (type == DataComponents.MAP_DECORATIONS) return (com.mojang.serialization.Codec<T>) MapDecorations.CODEC;
		if (type == DataComponents.BLOCK_STATE) return (com.mojang.serialization.Codec<T>) BlockItemStateProperties.CODEC;
		if (type == DataComponents.DEBUG_STICK_STATE) return (com.mojang.serialization.Codec<T>) DebugStickState.CODEC;
		throw new AssertionError("missing independent fixture codec");
	}

	@Test void everyNumericListWidthAndPrimitiveArrayMatchesRegistryOpsWithoutJsonRoundtrip() {
		for (Tag scalar : List.of(ByteTag.valueOf((byte) 3), ShortTag.valueOf((short) 3), IntTag.valueOf(3),
			LongTag.valueOf(3), FloatTag.valueOf(3.25F), DoubleTag.valueOf(3.25))) {
			var list = new ListTag(); list.add(scalar); list.add(scalar);
			var root = new CompoundTag(); root.put("values", list);
			oracle(DataComponents.CUSTOM_DATA, CustomData.of(root));
		}
		var arrays = new CompoundTag();
		arrays.putByteArray("bytes", new byte[] { -1, 0, 2 });
		arrays.putIntArray("ints", new int[] { Integer.MIN_VALUE, 3 });
		arrays.putLongArray("longs", new long[] { Long.MIN_VALUE, 9007199254740993L });
		arrays.put("empty", new ListTag());
		oracle(DataComponents.CUSTOM_DATA, CustomData.of(arrays));
	}

	@Test void nativePlainNbtPositiveControlDiffersFromRealRegistryConversion() {
		var list = new ListTag(); list.add(IntTag.valueOf(1));
		var root = new CompoundTag(); root.put("values", list); var custom = CustomData.of(root);
		Tag plain = CustomData.CODEC.encodeStart(NbtOps.INSTANCE, custom).result().orElseThrow();
		Tag contextual = CustomData.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), custom).result().orElseThrow();
		assertInstanceOf(ListTag.class, ((CompoundTag) plain).get("values"));
		assertInstanceOf(IntArrayTag.class, ((CompoundTag) contextual).get("values"));
		assertFalse(java.util.Arrays.equals(InventoryItemCodec.canonicalTag(plain), InventoryItemCodec.canonicalTag(contextual)));
		oracle(DataComponents.CUSTOM_DATA, custom);
	}

	@Test void emptyKeyWrappersUnwrapOnceBeforeNestedAndHeterogeneousCollection() {
		var inner = new ListTag(); inner.add(wrap(IntTag.valueOf(9))); inner.add(wrap(StringTag.valueOf("nine")));
		var outer = new ListTag(); outer.add(wrap(inner)); outer.add(wrap(wrap(ByteTag.valueOf((byte) 4))));
		var root = new CompoundTag(); root.put("nested", outer);
		oracle(DataComponents.CUSTOM_DATA, CustomData.of(root));
		var sameKind = new ListTag(); sameKind.add(wrap(LongTag.valueOf(7))); sameKind.add(wrap(LongTag.valueOf(8)));
		root.put("numeric_wrappers", sameKind);
		oracle(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	@Test void beesUseIdenticalConversionAndNativeEmptyEntityDataOmission() {
		var values = new ListTag(); values.add(wrap(ByteTag.valueOf((byte) 1))); values.add(wrap(StringTag.valueOf("bee")));
		var entity = new CompoundTag(); entity.put("values", values);
		var bees = List.of(new BeehiveBlockEntity.Occupant(CustomData.EMPTY, 0, 600),
			new BeehiveBlockEntity.Occupant(CustomData.of(entity), -1, 37));
		oracle(DataComponents.BEES, bees);
		var json = BeehiveBlockEntity.Occupant.LIST_CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), bees).result().orElseThrow();
		assertFalse(json.getAsJsonArray().get(0).getAsJsonObject().has("entity_data"));
	}

	@Test void ownedGraphsDetachPrimitiveArraysCompoundMapsAndBeeLists() {
		var root = new CompoundTag(); root.putLongArray("array", new long[] { 1, 2 });
		var custom = CustomData.of(root); var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_DATA, custom);
		var frozen = freeze(stack); byte[] before = frozen.canonicalForCatalog().clone(); String display = frozen.display().displayJson();
		custom.getUnsafe().getLongArray("array")[0] = 99; custom.getUnsafe().putString("later", "change");
		assertArrayEquals(before, frozen.canonicalForCatalog()); assertEquals(display, frozen.display().displayJson());
		var bees = new ArrayList<BeehiveBlockEntity.Occupant>(); bees.add(new BeehiveBlockEntity.Occupant(custom, 2, 3));
		stack.set(DataComponents.BEES, bees); var beeFrozen = freeze(stack); byte[] beeBefore = beeFrozen.canonicalForCatalog().clone();
		bees.clear(); custom.getUnsafe().putInt("more", 4);
		assertArrayEquals(beeBefore, beeFrozen.canonicalForCatalog());
	}

	@Test void finiteMapEnvelopesKeepArbitraryKeysAndNativeEmptyGrammar() {
		oracle(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);
		oracle(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of("Aa", "first", "BB", "second", "", "empty")));
		oracle(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of()));
		oracle(DataComponents.DEBUG_STICK_STATE, new DebugStickState(Map.of()));
	}

	@Test void grownEmptyIdentityBackedBeeDataHasOwnedEmptyOutputWithoutNativeCopy() throws Exception {
		var backing = new IdentityHashMap<String, Tag>(16384);
		backing.put("removed", IntTag.valueOf(1)); backing.clear();
		var custom = CustomData.of(new CompoundTag());
		var field = CompoundTag.class.getDeclaredField("tags"); field.setAccessible(true); field.set(custom.getUnsafe(), backing);
		oracle(DataComponents.BEES, List.of(new BeehiveBlockEntity.Occupant(custom, 1, 2)));
	}

	private static CompoundTag wrap(Tag value) { var wrapper = new CompoundTag(); wrapper.put("", value); return wrapper; }
}
