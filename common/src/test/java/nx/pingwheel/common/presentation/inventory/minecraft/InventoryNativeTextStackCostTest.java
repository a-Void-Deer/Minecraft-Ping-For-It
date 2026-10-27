package nx.pingwheel.common.presentation.inventory.minecraft;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native stack-array oracle and pre-call admission, not JVM allocation measurement. */
class InventoryNativeTextStackCostTest {
	private static RegistryAccess registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	private static Component selectors(int count) {
		Component value = Component.literal("x").withStyle(style -> style.withBold(true));
		for (int index = 0; index < count; index++) value = MutableComponent.create(new SelectorContents("@s", Optional.of(value)));
		return value;
	}
	private static Component branches(int longer) {
		// Same nodes, fields, strings and one two-element extra array in every
		// fixture; only the distribution of 31 selectors between branches changes.
		return Component.literal("root").append(selectors(longer)).append(selectors(31 - longer));
	}
	private static void exactNativeIdentity(Component name) {
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, name);
		var frozen = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
			InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
		assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
		var original = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), ((net.minecraft.core.component.PatchedDataComponentMap) stack.getComponents()).asPatch()).result().orElseThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(original), frozen.canonicalForCatalog());
	}
	private static NativeStack nativeStack(Component name) throws IOException {
		var tree = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, name).result().orElseThrow();
		var destination = new StringWriter();
		try (var writer = new StackWriter(destination)) {
			GsonHelper.writeValue(writer, tree, Comparator.naturalOrder());
			assertEquals(ComponentSerialization.FLAT_CODEC.encodeStart(NbtOps.INSTANCE, name).result().orElseThrow().getAsString(), destination.toString());
			return new NativeStack(writer.attempts.stream().map(array -> array.length).toList(), writer.maximumCells,
				writer.attempts.stream().mapToLong(array -> 4L * array.length).sum(), destination.toString().length());
		}
	}
	private record NativeStack(List<Integer> capacities, int maximumCells, long bytes, int jsonLength) {
		long storage() { return bytes + 40L * capacities.size(); }
	}
	private static final class StackWriter extends JsonWriter {
		private final List<int[]> attempts = new ArrayList<>();
		private int maximumCells;
		StackWriter(StringWriter destination) { super(destination); observe(); }
		private void observe() {
			// Read actual Gson state after native push; never predict its growth.
			// Retain every distinct old/new array, not just the final capacity.
			int[] stack = InventoryResidentAccessFields.fieldAt(this, JsonWriter.class, "stack");
			if (attempts.isEmpty() || attempts.get(attempts.size() - 1) != stack) attempts.add(stack);
			int cells = InventoryResidentAccessFields.fieldAt(this, JsonWriter.class, "stackSize");
			maximumCells = Math.max(maximumCells, cells);
		}
		@Override public JsonWriter beginObject() throws IOException { super.beginObject(); observe(); return this; }
		@Override public JsonWriter beginArray() throws IOException { super.beginArray(); observe(); return this; }
	}
	private record Planned(long storage, long operations) {}
	private static Planned planned(Component name, boolean display) {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var cost = new InventoryEncodingCost(budget); cost.reset(display);
			new InventoryNativeCost(budget, new InventoryResidentAccessFields(), new InventoryTestNativeAccess(), cost)
				.value(DataComponents.CUSTOM_NAME, name);
			return new Planned(cost.storage(), cost.operations());
		} finally { budget.end(); }
	}
	private static void admittedFlat(Component name, long allowance, AtomicInteger nativeCalls) {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var raw = new InventoryResidentAccessFields();
			var bounds = new InventoryComponentBounds(budget, raw);
			var certificate = new InventoryComponentCertificate(budget, raw, new InventoryTestNativeAccess(), bounds);
			certificate.text(name, 0); // Keep the real exact-class, list and text guards.
			budget.allocate(2L * 1024 * 1024 - budget.rootAllocatedBytes() - allowance, 0, 0, 0);
			certificate.admitValue(DataComponents.CUSTOM_NAME, name, false);
			nativeCalls.incrementAndGet();
			assertTrue(ComponentSerialization.FLAT_CODEC.encodeStart(NbtOps.INSTANCE, name).result().isPresent());
		} finally { budget.end(); }
	}
	@Test void nativeSelectorSeparatorsGrowAtTheThirtySecondObjectNotTheThirtyThird() throws IOException {
		for (int count : new int[] {30, 31, 32}) {
			var name = selectors(count);
			var actual = nativeStack(name);
			assertEquals(count + 2, actual.maximumCells(), "one document plus selector and bold-literal objects");
			assertEquals(count == 30 ? List.of(32) : List.of(32, 64), actual.capacities());
			assertEquals(count == 30 ? 128 : 384, actual.bytes());
			exactNativeIdentity(name);
		}
	}
	@Test void equalNodeShapesPayOnlyTheNativeStackGrowthBelowAtAndAboveTheBoundary() throws IOException {
		var below = branches(28); var at = branches(29); var above = branches(30);
		var nativeBelow = nativeStack(below); var nativeAt = nativeStack(at); var nativeAbove = nativeStack(above);
		assertEquals(32, nativeBelow.maximumCells()); assertEquals(33, nativeAt.maximumCells()); assertEquals(34, nativeAbove.maximumCells());
		assertEquals(List.of(32), nativeBelow.capacities()); assertEquals(List.of(32, 64), nativeAt.capacities()); assertEquals(nativeAt.capacities(), nativeAbove.capacities());
		assertEquals(nativeBelow.jsonLength(), nativeAt.jsonLength()); assertEquals(nativeAt.jsonLength(), nativeAbove.jsonLength());
		// Extra int[64]: V=(256,0,0,1), M=296, I=257. The existing cumulative
		// stack-copy work upper also rises by 256, hence total operations +513.
		assertEquals(296, nativeAt.storage() - nativeBelow.storage());
		for (boolean display : new boolean[] {false, true}) {
			var low = planned(below, display); var edge = planned(at, display); var high = planned(above, display);
			assertEquals(296, edge.storage() - low.storage()); assertEquals(edge.storage(), high.storage());
			assertEquals(513, edge.operations() - low.operations()); assertEquals(edge.operations(), high.operations());
		}
		for (Component name : List.of(below, at, above)) exactNativeIdentity(name);
	}
	@Test void omittedArrayAllowanceRefusesBeforeNativeFlatCallAndExactNativeMarginFits() throws IOException {
		var below = branches(28); var at = branches(29); var above = branches(30);
		long oldAllowance = planned(below, false).storage();
		// Nonstack events are identical. Derive the margin from actual Gson
		// allocations on the control, not the target planner's own counters.
		long margin = nativeStack(at).storage() - nativeStack(below).storage();
		assertEquals(296, margin);
		var calls = new AtomicInteger();
		assertDoesNotThrow(() -> admittedFlat(below, oldAllowance, calls)); assertEquals(1, calls.get());
		calls.set(0);
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> admittedFlat(at, oldAllowance, calls)); assertEquals(0, calls.get());
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> admittedFlat(at, oldAllowance + margin - 1, calls)); assertEquals(0, calls.get());
		assertDoesNotThrow(() -> admittedFlat(at, oldAllowance + margin, calls)); assertEquals(1, calls.get());
		assertDoesNotThrow(() -> admittedFlat(above, oldAllowance + margin, calls)); assertEquals(2, calls.get());
	}
}
