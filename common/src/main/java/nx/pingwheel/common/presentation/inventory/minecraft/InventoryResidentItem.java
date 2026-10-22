package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Objects;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Ephemeral server-thread input. Never store, hash, compare, or format these values. */
public sealed interface InventoryResidentItem {

	record Stack(ItemStack resident) implements InventoryResidentItem {
		public Stack { Objects.requireNonNull(resident, "resident"); }
	}

	record Patch(Item item, DataComponentPatch residentPatch) implements InventoryResidentItem {
		public Patch {
			Objects.requireNonNull(item, "item");
			Objects.requireNonNull(residentPatch, "residentPatch");
		}
	}
}
