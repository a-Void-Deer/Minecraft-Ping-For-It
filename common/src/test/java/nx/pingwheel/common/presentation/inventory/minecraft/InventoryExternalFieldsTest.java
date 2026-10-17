package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.Map;

import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Calls the production external ports directly, without ServiceLoader or applied Mixins. */
class InventoryExternalFieldsTest {

	@Test void untransformedReferenceMapsWithEqualSizeKeepDifferentResidentCapacities() {
		var compact = new Reference2ObjectOpenHashMap<Object, Object>(2, 0.75F);
		var sparse = new Reference2ObjectOpenHashMap<Object, Object>(1024, 0.75F);
		Object key = new Object(); compact.put(key, key); sparse.put(key, key);
		assertEquals(compact.size(), sparse.size());
		// nextPowerOfTwo(ceil(expected / 0.75)): 4 and 2048 buckets.
		assertEquals(4, InventoryResidentAccess.MIXIN.referenceMapCapacity(compact));
		assertEquals(2048, InventoryResidentAccess.MIXIN.referenceMapCapacity(sparse));
		assertEquals(2048, InventoryResidentAccess.MIXIN.referenceMapCapacity(sparse));
	}

	@Test void untransformedObjectIntMapsWithEqualSizeKeepDifferentResidentCapacities() {
		var compact = new Object2IntOpenHashMap<Object>(2, 0.75F);
		var sparse = new Object2IntOpenHashMap<Object>(1024, 0.75F);
		Object key = new Object(); compact.put(key, 1); sparse.put(key, 1);
		assertEquals(compact.size(), sparse.size());
		assertEquals(4, InventoryResidentAccess.MIXIN.objectIntCapacity(compact));
		assertEquals(2048, InventoryResidentAccess.MIXIN.objectIntCapacity(sparse));
		assertEquals(2048, InventoryResidentAccess.MIXIN.objectIntCapacity(sparse));
	}

	@Test void productionProfileReadReturnsTheOriginalBackingAndPropertyObjects() {
		var map = new PropertyMap();
		var first = new Property("textures", "first"); map.put("textures", first);
		var backing = InventoryResidentAccess.MIXIN.profile(map);
		assertSame(InventoryResidentAccessFields.field(map, "properties"), backing);
		assertSame(backing, InventoryResidentAccess.MIXIN.profile(map));
		assertSame(first, backing.get("textures").iterator().next());
		var second = new Property("textures", "second", "signature"); map.put("textures", second);
		assertEquals(2, backing.size());
		assertTrue(backing.containsEntry("textures", second));
	}

	@Test void externalPortsRejectUnknownOwnersBeforeOverriddenMethods() {
		Map<?, ?> wrong = new HashMap<>() {
			@Override public int size() { throw new AssertionError("unknown map size"); }
		};
		var reference = new Reference2ObjectOpenHashMap<>() {
			@Override public int size() { throw new AssertionError("subclass size"); }
		};
		var objectInt = new Object2IntOpenHashMap<>() {
			@Override public int size() { throw new AssertionError("subclass size"); }
		};
		assertThrows(IllegalArgumentException.class, () -> InventoryResidentAccess.MIXIN.referenceMapCapacity(wrong));
		assertThrows(IllegalArgumentException.class, () -> InventoryResidentAccess.MIXIN.referenceMapCapacity(reference));
		assertThrows(IllegalArgumentException.class, () -> InventoryResidentAccess.MIXIN.objectIntCapacity(objectInt));
	}

	@Test void sparseReferenceCapacityRefusesBeforeCreatingAnEntryViewOrIterator() {
		var map = new Reference2ObjectOpenHashMap<Object, Object>(1 << 20, 0.75F);
		Object key = new Object(); map.put(key, key);
		assertEquals(1 << 21, InventoryResidentAccess.MIXIN.referenceMapCapacity(map));
		assertTrue((1L << 21) + map.size() > InventoryItemBudget.MAX_NATIVE_TRAVERSAL);
		assertNull(InventoryResidentAccessFields.field(map, "entries"));
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var bounds = new InventoryComponentBounds(budget, InventoryResidentAccess.MIXIN);
			var refusal = assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.mapPass(map));
			assertEquals("native traversal bound", refusal.getMessage());
			assertNull(InventoryResidentAccessFields.field(map, "entries"));
		} finally { budget.end(); }
		assertNotNull(map.entrySet()); // positive control: a view would populate the cache
		assertNotNull(InventoryResidentAccessFields.field(map, "entries"));
	}

	@Test void sparseEnchantmentCapacityUsesProductionReadBeforeAnyEntryTraversal() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		var map = new Object2IntOpenHashMap<Object>(1 << 20, 0.75F); map.put(new Object(), 1);
		assertEquals(1 << 21, InventoryResidentAccess.MIXIN.objectIntCapacity(map));
		assertTrue((1L << 21) + map.size() > InventoryItemBudget.MAX_NATIVE_TRAVERSAL);
		assertNull(InventoryResidentAccessFields.field(map, "entries"));
		var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY).toImmutable();
		var field = ItemEnchantments.class.getDeclaredField("enchantments"); field.setAccessible(true); field.set(enchantments, map);
		// Delegate Minecraft fields only. The capacity read inherits the default
		// production path; no untransformed Minecraft accessor is invoked.
		var raw = new InventoryResidentAccessFields() {
			@Override public boolean enchantmentTooltip(ItemEnchantments value) { throw new AssertionError("rejected map consumed"); }
		};
		assertSame(map, raw.enchantments(enchantments));
		InventoryComponentCertificate.initialize();
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var bounds = new InventoryComponentBounds(budget, raw);
			var certificate = new InventoryComponentCertificate(budget, raw, new InventoryTestNativeAccess(), bounds);
			var refusal = assertThrows(InventoryItemBudget.RootLimitException.class,
				() -> certificate.owned(DataComponents.ENCHANTMENTS, enchantments, 0));
			assertEquals("native traversal bound", refusal.getMessage());
			assertNull(InventoryResidentAccessFields.field(map, "entries"));
		} finally { budget.end(); }
		assertNotNull(map.object2IntEntrySet());
		assertNotNull(InventoryResidentAccessFields.field(map, "entries"));
	}
}
