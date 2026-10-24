package nx.pingwheel.common.presentation.inventory.minecraft;

import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryPrimitiveArgumentCostTest {
	private static RegistryAccess registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	@Test void everyClosedPrimitiveSubtypeKeepsItsNativeCanonicalEncoding() {
		for (Object argument : new Object[] {"quoted\"\\\n", true, (byte) -100, (short) 30000, Integer.MIN_VALUE,
			Long.MIN_VALUE, Float.MIN_VALUE, Float.MAX_VALUE, Double.MIN_VALUE, -Double.MAX_VALUE, -0.0f, -0.0d}) {
			var component = Component.translatable("test.argument", argument);
			var nativeJson = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).result().orElseThrow().getAsJsonObject();
			var encoded = nativeJson.getAsJsonArray("with").get(0).getAsJsonPrimitive();
			if (argument instanceof String) assertTrue(encoded.isString());
			else if (argument instanceof Boolean) assertTrue(encoded.isBoolean());
			else assertTrue(encoded.isNumber());
			assertEquals(String.valueOf(argument), encoded.getAsString());
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, component);
			var actual = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
				InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
			assertEquals(InventoryIdentityMode.EXACT, actual.identityMode(), argument.getClass().getName());
			var nativeTag = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), ((net.minecraft.core.component.PatchedDataComponentMap) stack.getComponents()).asPatch()).result().orElseThrow();
			assertArrayEquals(InventoryItemCodec.canonicalTag(nativeTag), actual.canonicalForCatalog());
		}
	}
	@Test void converterVectorContainsBothDynamicsPassthroughAndTargetBoxButNotASecondPrimitiveResult() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var cost = new InventoryEncodingCost(budget); cost.reset(true); cost.javaPrimitive(123456);
			// validator/continuation/lifecycle7R3O + Dynamic pair4R2O +
			// PASSTHROUGH Success2R1O + target Integer/JsonPrimitive4B1R2O.
			assertEquals(4 + 8L * 14 + 40L * 8, cost.storage());
			cost.reset(true); cost.javaPrimitive("text");
			assertEquals(8L * 14 + 40L * 7, cost.storage());
			cost.reset(true); cost.flatResult();
			assertEquals(12 + 8L * 17 + 40L * 8, cost.storage());
		} finally { budget.end(); }
	}
	@Test void unknownNumberAndNonfiniteValuesAreRefusedWithoutAnyFormattingOrNumericCallback() {
		var trap = new Number() {
			@Override public int intValue() { throw new AssertionError("int callback"); }
			@Override public long longValue() { throw new AssertionError("long callback"); }
			@Override public float floatValue() { throw new AssertionError("float callback"); }
			@Override public double doubleValue() { throw new AssertionError("double callback"); }
			@Override public String toString() { throw new AssertionError("format callback"); }
		};
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.translatable("trap", trap));
		var actual = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
			InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, actual.identityMode());
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var cost = new InventoryEncodingCost(budget);
			for (Object rejected : new Object[] {trap, 'x', Float.NaN, Double.POSITIVE_INFINITY})
				assertThrows(InventoryItemBudget.RootLimitException.class, () -> cost.javaPrimitive(rejected));
		} finally { budget.end(); }
		assertFalse(TranslatableContents.isAllowedPrimitiveArgument('x'), "native primitive grammar has no Character branch");
	}
}
