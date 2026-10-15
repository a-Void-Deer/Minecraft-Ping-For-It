package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryUnboundHolderGuardTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void unboundIntrusiveReferenceRefusesBeforeNativeErrorFormatsResidentOwner() {
		var calls = new AtomicInteger();
		var owner = new HolderOwner<Instrument>() {
			@Override public boolean canSerializeIn(HolderOwner<Instrument> other) {
				calls.incrementAndGet(); throw new AssertionError("owner callback");
			}
			@Override public String toString() { calls.incrementAndGet(); throw new AssertionError("owner formatting"); }
		};
		Holder.Reference<Instrument> unbound = Holder.Reference.createIntrusive(owner, null);
		// The original file codec's missing-owner branch reaches value() and its
		// error formatting. The guarded route must reject before that call graph.
		assertThrows(AssertionError.class, () -> Instrument.CODEC.encodeStart(NbtOps.INSTANCE, unbound));
		assertTrue(calls.get() > 0); calls.set(0);
		var patch = DataComponentPatch.builder().set(DataComponents.INSTRUMENT, unbound).build();
		var frozen = InventoryItemCodec.freeze(new InventoryResidentItem.Patch(Items.STONE, patch), RegistryAccess.EMPTY,
			4096, InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, frozen.identityMode()); assertEquals(0, calls.get());
	}
}
