package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Front-guard primitives only. The production codec has not yet been routed through them. */
class InventoryComponentBoundsTest {
	@Test void oversizedActualResidentCustomDataArrayRefusesWithoutCopying() {
		// Construct the ordinary wrapper first, then mutate its actual resident
		// payload. Setup must not discover size by copying the oversized value.
		var custom = net.minecraft.world.item.component.CustomData.of(new CompoundTag());
		custom.getUnsafe().put("bytes", new ByteArrayTag(new byte[65536]));
		var budget = InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES);
		budget.begin(InventoryItemCodec.MAX_DISPLAY_BYTES);
		var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.nbt(custom.getUnsafe()));
		assertSame(custom.getUnsafe(), custom.getUnsafe(), "guard did not replace resident payload");
		budget.end();
	}

	@Test void sparseActualHashMapRefusesBeforeIteratorOrNativeCopy() throws Exception {
		var map = new HashMap<String, Tag>(1 << 20, 1.0F);
		map.put("a", IntTag.valueOf(1));
		var root = new CompoundTag();
		var field = CompoundTag.class.getDeclaredField("tags");
		field.setAccessible(true);
		field.set(root, map);
		var budget = InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES);
		budget.begin(InventoryItemCodec.MAX_DISPLAY_BYTES);
		assertThrows(InventoryItemBudget.RootLimitException.class,
			() -> new InventoryComponentBounds(budget, new InventoryResidentAccessFields()).nbt(root));
		budget.end();
	}

	@Test void unknownMapAndListImplementationsRefuseBeforeTheirMethodsRun() {
		var map = new HashMap<String, Tag>() {
			@Override public int size() { fail("unknown size must not run"); return 0; }
			@Override public java.util.Set<Map.Entry<String, Tag>> entrySet() { fail("unknown iterator must not run"); return null; }
		};
		var list = new ArrayList<Tag>() {
			@Override public int size() { fail("unknown list size must not run"); return 0; }
			@Override public Tag get(int index) { fail("unknown list get must not run"); return null; }
		};
		var budget = InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES);
		budget.begin(InventoryItemCodec.MAX_DISPLAY_BYTES);
		var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
		assertThrows(AssertionError.class, map::size, "positive control: unknown native method is armed");
		assertThrows(AssertionError.class, list::size, "positive control: unknown list method is armed");
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.mapPass(map));
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.list(list));
		budget.end();
	}

	@Test void cyclesAndRepeatedExpansionAreBoundedWithoutValueHashing() {
		var root = new CompoundTag();
		root.put("self", root);
		var budget = InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES);
		budget.begin(InventoryItemCodec.MAX_DISPLAY_BYTES);
		var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.nbt(root));
		budget.end();

		Tag repeated = IntTag.valueOf(1);
		for (int depth = 0; depth < 14; depth++) {
			var next = new ListTag();
			next.add(repeated);
			next.add(repeated);
			repeated = next;
		}
		var expanded = InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES);
		expanded.begin(InventoryItemCodec.MAX_DISPLAY_BYTES);
		var second = new InventoryComponentBounds(expanded, new InventoryResidentAccessFields());
		Tag finalRepeated = repeated;
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> second.nbt(finalRepeated));
		expanded.end();
	}

	@Test void ordinaryCompactTagAndNativeListsRemainAdmitted() {
		var root = new CompoundTag();
		root.putString("name", "ordinary");
		root.putIntArray("numbers", new int[] {1, 2, 3});
		var budget = InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES);
		budget.begin(InventoryItemCodec.MAX_DISPLAY_BYTES);
		var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
		assertDoesNotThrow(() -> bounds.nbt(root));
		assertSame(List.of(), bounds.list(List.of()));
		assertEquals(2, bounds.list(List.of("a", "b")).size());
		budget.end();
	}
}
