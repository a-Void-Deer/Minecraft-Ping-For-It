package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import com.google.common.collect.Multimap;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

/** Fixed external-library fields; their owners need no transformation by the mod's loader. */
final class InventoryExternalFields {

	// Exact-owner dispatch, cached-port checks and the warmed Field.get/getInt
	// path on audited JDK21/25: <=32 caller/owner/cache events +32 Field
	// override/accessor events +32 typed MethodHandle getter events, with32
	// headroom. The sample read warms the lazy accessor before root admission.
	// No lookup, accessibility change, boxing, iterator, copy or allocation
	// occurs on a successful resident read.
	static final int READ_WORK = 128;
	private static final Port REFERENCE_CAPACITY = resolve(Reference2ObjectOpenHashMap.class, "n", int.class,
		new Reference2ObjectOpenHashMap<>());
	private static final Port OBJECT_INT_CAPACITY = resolve(Object2IntOpenHashMap.class, "n", int.class,
		new Object2IntOpenHashMap<>());
	private static final Port PROFILE_PROPERTIES = resolve(PropertyMap.class, "properties", Multimap.class,
		new PropertyMap());

	private InventoryExternalFields() {}

	/** Resolve and warm only fixed empty samples, before resident-root admission. */
	static void initialize() { /* class initialization owns the three cached ports */ }

	static int referenceMapCapacity(Map<?, ?> map) {
		exact(map, Reference2ObjectOpenHashMap.class);
		return integer(REFERENCE_CAPACITY, map);
	}

	static int objectIntCapacity(Object2IntOpenHashMap<?> map) {
		exact(map, Object2IntOpenHashMap.class);
		return integer(OBJECT_INT_CAPACITY, map);
	}

	@SuppressWarnings("unchecked")
	static Multimap<String, Property> profile(PropertyMap map) {
		exact(map, PropertyMap.class);
		try {
			return (Multimap<String, Property>) PROFILE_PROPERTIES.readable().get(map);
		} catch (IllegalAccessException failure) {
			throw new IllegalStateException("inventory external profile field became inaccessible", failure);
		}
	}

	private static int integer(Port port, Object map) {
		try {
			return port.readable().getInt(map);
		} catch (IllegalAccessException failure) {
			throw new IllegalStateException("inventory external capacity field became inaccessible", failure);
		}
	}

	private static void exact(Object value, Class<?> owner) {
		if (value == null || value.getClass() != owner)
			throw new IllegalArgumentException("unsupported inventory external field owner");
	}

	private static Port resolve(Class<?> owner, String name, Class<?> type, Object sample) {
		try {
			Field field = owner.getDeclaredField(name);
			if (field.getType() != type || Modifier.isStatic(field.getModifiers()))
				throw new NoSuchFieldException("unexpected inventory field descriptor: " + owner.getName() + "." + name);
			field.setAccessible(true);
			// Field accessors may initialize lazily. Pay that process-fixed work
			// here rather than on the first admitted resident value.
			if (type == int.class) field.getInt(sample); else field.get(sample);
			return new Port(field, null);
		} catch (ReflectiveOperationException | RuntimeException failure) {
			// Do not retry, invent a capacity, or poison class initialization.
			// Every later read reports the same original cause to source diagnostics.
			return new Port(null, new IllegalStateException("inventory external field unavailable: " + owner.getName() + "." + name, failure));
		}
	}

	private record Port(Field field, IllegalStateException failure) {
		Field readable() {
			if (failure != null) throw failure;
			return field;
		}
	}
}
