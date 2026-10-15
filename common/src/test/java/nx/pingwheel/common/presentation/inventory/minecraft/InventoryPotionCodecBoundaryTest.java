package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.alchemy.PotionContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent native-oracle evidence for the A1 hidden-effect contract conflict.
 * Does not invoke an inventory freeze or claim the item boundary is implemented.
 */
class InventoryPotionCodecBoundaryTest {
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void potionStaticCodecUsesCopyingGetterRatherThanRawHiddenChain() {
		var hidden = new MobEffectInstance(MobEffects.POISON, 500, 0);
		var resident = new MobEffectInstance(MobEffects.POISON, 20, 2, false, true, true, hidden);
		var rawEncoded = (CompoundTag) MobEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE, resident)
			.result().orElseThrow();
		assertTrue(rawEncoded.contains("hidden_effect"), "positive control: raw effect codec observes hidden state");

		var copy = new MobEffectInstance(resident);
		var copyEncoded = (CompoundTag) MobEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE, copy)
			.result().orElseThrow();
		assertFalse(copyEncoded.contains("hidden_effect"), "native copy does not retain hiddenEffect");

		var potion = new PotionContents(Optional.empty(), Optional.empty(), List.of(resident));
		var getter = potion.customEffects().get(0);
		assertNotSame(resident, getter, "PotionContents.customEffects is a lazy copying view");
		var getterEncoded = MobEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE, getter).result().orElseThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(copyEncoded), InventoryItemCodec.canonicalTag(getterEncoded));

		var potionEncoded = (CompoundTag) PotionContents.CODEC.encodeStart(NbtOps.INSTANCE, potion)
			.result().orElseThrow();
		var nested = potionEncoded.getList("custom_effects", 10).getCompound(0);
		assertFalse(nested.contains("hidden_effect"), "original potion codec routes through the copying getter");
		assertArrayEquals(InventoryItemCodec.canonicalTag(copyEncoded), InventoryItemCodec.canonicalTag(nested));
		assertFalse(java.util.Arrays.equals(InventoryItemCodec.canonicalTag(rawEncoded),
			InventoryItemCodec.canonicalTag(nested)), "full raw hidden-chain identity cannot equal this vanilla oracle");
	}
}
