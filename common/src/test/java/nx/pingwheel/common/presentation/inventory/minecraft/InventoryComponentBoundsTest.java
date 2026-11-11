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
	@Test void registryMetadata512DoesNotWidenArbitraryMapsAndList513RefusesBeforeCopy() {
		var metadata = new HashMap<Integer, Integer>();
		for (int i = 0; i < 512; i++) metadata.put(i, i);
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
			assertEquals(512, bounds.registryMapPass(metadata));
			assertEquals(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.NATIVE_MAP_LIMIT,
				assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.mapPass(metadata)).reason());
			metadata.put(512, 512);
			assertEquals(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.NATIVE_MAP_LIMIT,
				assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.registryMapPass(metadata)).reason());
			var list = new ArrayList<>(java.util.Collections.nCopies(512, IntTag.valueOf(1)));
			assertSame(list, bounds.list(list));
			list.add(IntTag.valueOf(2)); long allocation = budget.rootAllocatedBytes(), work = budget.attemptedWork();
			assertEquals(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.NATIVE_LIST_LIMIT,
				assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.list(list)).reason());
			assertEquals(allocation, budget.rootAllocatedBytes()); assertTrue(budget.attemptedWork() > work);
		} finally { budget.end(); }
	}

	@Test void exactArrayCeilingCopiesOwnedBytesButOneOverRefusesBeforeAllocation() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
			var source = new ByteArrayTag(new byte[60 * 1024]);
			var owned = (ByteArrayTag) bounds.ownedPersistentTag(source, 0);
			assertArrayEquals(source.getAsByteArray(), owned.getAsByteArray()); assertNotSame(source.getAsByteArray(), owned.getAsByteArray());
			long before = budget.rootAllocatedBytes();
			assertEquals(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.NATIVE_ARRAY_LIMIT,
				assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.ownedPersistentTag(new ByteArrayTag(new byte[1]), 0)).reason());
			assertEquals(before, budget.rootAllocatedBytes());
		} finally { budget.end(); }
	}

	@Test void returnedPersistentTreeAdmits8192ExpandedNodesAndRefuses8193BeforeCopyingThatNode() {
		var root = new ListTag(); int remaining = 8191;
		while (remaining > 0) {
			var child = new ListTag(); int cells = Math.min(512, remaining - 1);
			for (int i = 0; i < cells; i++) child.add(net.minecraft.nbt.ByteTag.ZERO);
			root.add(child); remaining -= cells + 1;
		}
		var exact = InventoryItemBudget.root(4096); exact.begin(4096);
		try {
			Tag owned = new InventoryComponentBounds(exact, new InventoryResidentAccessFields()).ownedPersistentTag(root, 0);
			assertArrayEquals(InventoryItemCodec.canonicalTag(root), InventoryItemCodec.canonicalTag(owned));
		} finally { exact.end(); }
		((ListTag) root.get(root.size() - 1)).add(net.minecraft.nbt.ByteTag.ZERO);
		var refused = InventoryItemBudget.root(4096); refused.begin(4096);
		try {
			assertEquals(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.NATIVE_NODE_LIMIT,
				assertThrows(InventoryItemBudget.RootLimitException.class,
					() -> new InventoryComponentBounds(refused, new InventoryResidentAccessFields()).ownedPersistentTag(root, 0)).reason());
			assertTrue(refused.attemptedWork() >= 8192);
		} finally { refused.end(); }
	}

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

	@Test void persistentOwnedTreePreservesNativeKindsAndEmptyKeyWrappers() throws Exception {
		var root = new CompoundTag();
		var numbers = new ListTag(); numbers.add(IntTag.valueOf(7)); numbers.add(IntTag.valueOf(8));
		var wrapper = new CompoundTag(); wrapper.putString("", "not an ops wrapper");
		var wrappers = new ListTag(); wrappers.add(wrapper);
		var typedEmpty = new ListTag();
		var typeField = ListTag.class.getDeclaredField("type"); typeField.setAccessible(true);
		typeField.setByte(typedEmpty, Tag.TAG_INT);
		root.put("numbers", numbers); root.put("array", new net.minecraft.nbt.IntArrayTag(new int[] {7, 8}));
		root.put("wrappers", wrappers); root.put("empty", typedEmpty);
		byte[] expected = InventoryItemCodec.canonicalTag(root);
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		var owned = (CompoundTag) new InventoryComponentBounds(budget, new InventoryResidentAccessFields()).ownedPersistentTag(root, 0);
		assertArrayEquals(expected, InventoryItemCodec.canonicalTag(owned));
		assertEquals(ListTag.class, owned.get("numbers").getClass());
		assertEquals(net.minecraft.nbt.IntArrayTag.class, owned.get("array").getClass());
		assertEquals(Tag.TAG_INT, ((ListTag) owned.get("empty")).getElementType());
		assertEquals("not an ops wrapper", owned.getList("wrappers", Tag.TAG_COMPOUND).getCompound(0).getString(""));
		numbers.add(IntTag.valueOf(9)); wrapper.putString("", "changed"); root.getIntArray("array")[0] = 9;
		assertArrayEquals(expected, InventoryItemCodec.canonicalTag(owned));
		assertNotSame(typedEmpty, owned.get("empty")); budget.end();
	}

	@Test void persistentOwnedTreeRefusesUnknownBackingAndCyclesWithoutCallbacks() throws Exception {
		var root = new CompoundTag();
		var map = new HashMap<String, Tag>() {
			@Override public int size() { fail("unknown output map size"); return 0; }
			@Override public java.util.Set<Map.Entry<String, Tag>> entrySet() { fail("unknown output map iterator"); return null; }
		};
		var field = CompoundTag.class.getDeclaredField("tags"); field.setAccessible(true); field.set(root, map);
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.ownedPersistentTag(root, 0));
		var cycle = new CompoundTag(); cycle.put("self", cycle);
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.ownedPersistentTag(cycle, 0));
		cycle.remove("self"); assertDoesNotThrow(() -> bounds.ownedPersistentTag(cycle, 0));
		budget.end();
	}

	@Test void persistentCopyAllocationMustFitBeforeOutputConstruction() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		var bounds = new InventoryComponentBounds(budget, new InventoryResidentAccessFields());
		var array = new ByteArrayTag(new byte[] {1, 2, 3});
		budget.allocate(2L * 1024 * 1024 - budget.rootAllocatedBytes() - 1, 0, 0, 0);
		assertThrows(InventoryItemBudget.RootLimitException.class, () -> bounds.ownedPersistentTag(array, 0));
		assertArrayEquals(new byte[] {1, 2, 3}, array.getAsByteArray()); budget.end();
	}
}
