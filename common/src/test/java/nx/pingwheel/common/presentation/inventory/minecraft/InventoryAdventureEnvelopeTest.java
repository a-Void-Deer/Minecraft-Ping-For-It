package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.critereon.*;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAdventureEnvelopeTest {
	private static RegistryAccess registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	@Test void directNamedStateRangeAndSnbtMatchOriginalCodecInBothDirections() {
		var state = new StatePropertiesPredicate(List.of(
			new StatePropertiesPredicate.PropertyMatcher("Aa", new StatePropertiesPredicate.ExactMatcher("value")),
			new StatePropertiesPredicate.PropertyMatcher("BB", new StatePropertiesPredicate.RangedMatcher(Optional.of("1"), Optional.of("4"))),
			new StatePropertiesPredicate.PropertyMatcher("", new StatePropertiesPredicate.RangedMatcher(Optional.empty(), Optional.empty()))));
		var nbt = new CompoundTag(); nbt.putString("quoted:key", "both '\" and \\"); nbt.putLongArray("longs", new long[] {Long.MIN_VALUE, 1});
		var predicate = new BlockPredicate(Optional.of(HolderSet.direct(Blocks.STONE.builtInRegistryHolder())), Optional.of(state), Optional.of(new NbtPredicate(nbt)));
		var tag = HolderSet.emptyNamed(BuiltInRegistries.BLOCK.asLookup(), TagKey.create(Registries.BLOCK, ResourceLocation.parse("test:key")));
		var named = new BlockPredicate(Optional.of(tag), Optional.empty(), Optional.empty());
		for (boolean tooltip : new boolean[] {true, false}) {
			// First predicate has no blocks, preventing constructor tooltip world/name work.
			var adventure = new AdventureModePredicate(List.of(new BlockPredicate(Optional.empty(), Optional.empty(), Optional.empty()), predicate, named), tooltip);
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CAN_BREAK, adventure);
			var frozen = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
				InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
			assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
			var ops = registries.createSerializationContext(NbtOps.INSTANCE);
			var original = DataComponentMap.CODEC.encodeStart(ops, stack.getComponents()).result().orElseThrow();
			assertArrayEquals(InventoryItemCodec.canonicalTag(original), frozen.canonicalForCatalog());
			var nativeJson = AdventureModePredicate.CODEC.encodeStart(ops.withParent(JsonOps.INSTANCE), adventure).result().orElseThrow();
			assertEquals(nativeJson, JsonParser.parseString(frozen.display().displayJson()).getAsJsonObject().getAsJsonObject("components").get("minecraft:can_break"));
		}
	}

	@Test void ownedSnbtKeepsNumericWidthsWrapperListsAndNativeQuoteChoice() {
		var root = new CompoundTag(); root.putByte("b", (byte) -1); root.putShort("s", (short) -2); root.putInt("i", -3); root.putLong("l", Long.MIN_VALUE);
		root.putFloat("f", Float.MIN_VALUE); root.putDouble("d", Double.MAX_VALUE);
		root.putByteArray("bytes", new byte[] {-128, 0, 127}); root.putIntArray("ints", new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE});
		root.putLongArray("longs", new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
		root.putString("", ""); root.putString("simple_+-", "single' then double\""); root.putString("has space", "double\" then single'\\");
		var wrapper = new CompoundTag(); wrapper.putString("", "wrapped"); var list = new ListTag(); list.add(wrapper); root.put("wrappers", list);
		String original = root.toString();
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var owned = new InventoryComponentBounds(budget, new InventoryResidentAccessFields()).snapshotNbt(root);
			assertEquals(original, InventorySnbtWriter.write(owned, budget));
		} finally { budget.end(); }
	}
}
