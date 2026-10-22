package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;

import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.UnsafeDisplay;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.Validation;

/** Client-only exact native FOOD representation, without invoking any supplier. */
final class ClientDisplayFoodAccess {

	private record Shape(Constructor<FoodProperties.PossibleEffect> constructor, Field effect,
		Field supplier, Class<?> nativeSupplier, Field capturedEffect) {}
	private static final Shape SHAPE = shape();
	private ClientDisplayFoodAccess() {}

	private static Shape shape() {
		try {
			var constructor = FoodProperties.PossibleEffect.class.getDeclaredConstructor(MobEffectInstance.class, float.class);
			if (!constructor.trySetAccessible()) return null;
			Field effect = ClientItemDisplay.nativeField(FoodProperties.PossibleEffect.class, MobEffectInstance.class);
			Field supplier = ClientItemDisplay.nativeField(FoodProperties.PossibleEffect.class, Supplier.class);
			if (effect != null && supplier == null) return new Shape(constructor, effect, null, null, null);
			if (effect != null || supplier == null) return null;
			// NeoForge's exact direct constructor captures its argument, even null,
			// without dereferencing it. This one fixed-size anchor establishes the
			// actual native call site's class; no sample/supplier/effect is retained.
			Object anchor = supplier.get(constructor.newInstance(null, 0.0F));
			if (anchor == null) return null;
			Class<?> nativeSupplier = anchor.getClass(); Field captured = null;
			for (Field field : nativeSupplier.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) continue;
				if (captured != null || field.getType() != MobEffectInstance.class || !Modifier.isFinal(field.getModifiers()) || !field.trySetAccessible()) return null;
				captured = field;
			}
			return captured == null ? null : new Shape(constructor, null, supplier, nativeSupplier, captured);
		} catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
	}

	static MobEffectInstance resident(FoodProperties.PossibleEffect source, int depth, Validation budget) {
		budget.visit(depth); budget.cells(3); // Exact owner and up to two fixed-field reads.
		if (SHAPE == null || source == null || source.getClass() != FoodProperties.PossibleEffect.class) throw new UnsafeDisplay();
		try {
			Object effect;
			if (SHAPE.effect != null) effect = SHAPE.effect.get(source);
			else {
				Object supplier = SHAPE.supplier.get(source);
				// Not isHidden/isSynthetic, not an arbitrary single-capture lambda,
				// and never get()/effect()/hashCode()/equals()/toString().
				if (supplier == null || supplier.getClass() != SHAPE.nativeSupplier) throw new UnsafeDisplay();
				effect = SHAPE.capturedEffect.get(supplier);
			}
			if (effect == null || effect.getClass() != MobEffectInstance.class) throw new UnsafeDisplay();
			return (MobEffectInstance) effect;
		} catch (IllegalAccessException unavailable) { throw new UnsafeDisplay(unavailable); }
	}

	/** Caller has already made exactly one bounded recursive effect copy. */
	static FoodProperties.PossibleEffect wrap(MobEffectInstance owned, float probability, int depth, Validation budget) {
		budget.visit(depth);
		// Worst NeoForge shape: reflection's two-slot arguments, float boxing,
		// record, native capture lambda and its references; paid BEFORE newInstance.
		budget.cells(8);
		if (SHAPE == null || owned == null || owned.getClass() != MobEffectInstance.class) throw new UnsafeDisplay();
		try { return SHAPE.constructor.newInstance(owned, probability); }
		catch (ReflectiveOperationException unavailable) { throw new UnsafeDisplay(unavailable); }
	}
}
