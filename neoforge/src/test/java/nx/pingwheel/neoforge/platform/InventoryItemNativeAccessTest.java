package nx.pingwheel.neoforge.platform;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.food.Foods;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Native reference-identity/supplier provenance boundary; no arbitrary supplier runs on refusal. */
class InventoryItemNativeAccessTest {
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void onlyTheThirteenActualAuditedPossibleEffectObjectsExposeResidents() {
		var port = new InventoryItemNativeAccessImpl();
		int entries = 0;
		for (FoodProperties food : List.of(Foods.CHICKEN, Foods.POISONOUS_POTATO, Foods.PUFFERFISH,
			Foods.ROTTEN_FLESH, Foods.SPIDER_EYE, Foods.GOLDEN_APPLE, Foods.ENCHANTED_GOLDEN_APPLE)) {
			for (var possible : food.effects()) {
				var resident = possible.effectSupplier().get();
				assertSame(resident, port.foodEffect(possible));
				assertNotSame(resident, possible.effect(), "native public getter copies even these approved entries");
				var replacement = new FoodProperties.PossibleEffect(possible.effectSupplier(), possible.probability());
				assertNull(port.foodEffect(replacement), "same supplier/value is not construction provenance");
				entries++;
			}
		}
		assertEquals(13, entries, "independent ordinary-food compatibility floor");
	}

	@Test void unknownSupplierPositiveControlThrowsButResidentPortDoesNotInvokeIt() {
		var calls = new AtomicInteger();
		var unknown = new FoodProperties.PossibleEffect(() -> {
			calls.incrementAndGet();
			throw new IllegalStateException("unadmitted supplier");
		}, 1.0F);
		assertThrows(IllegalStateException.class, unknown::effect);
		assertEquals(1, calls.get(), "positive control proves the trap is reachable");
		calls.set(0);
		assertNull(new InventoryItemNativeAccessImpl().foodEffect(unknown));
		assertEquals(0, calls.get());
	}

	@Test void curePortReturnsNativeOwnedSetAndRejectsUnknownNameHooks() {
		var port = new InventoryItemNativeAccessImpl();
		var resident = Foods.CHICKEN.effects().getFirst().effectSupplier().get();
		assertSame(resident.getCures(), port.effectCures(resident));
		for (var cure : resident.getCures()) assertEquals(cure.name(), port.effectCureName(cure));
		assertNull(port.effectCureName(new Object() {
			@Override public String toString() { fail("unknown cure formatting must not run"); return ""; }
		}));
	}
}
