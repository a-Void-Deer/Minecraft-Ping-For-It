package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;

import static org.junit.jupiter.api.Assertions.*;

class InventoryNbtSnapshotTest {

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void singleAndDoubleChestKeepTheirTopologyAndLocalSlotZeroIsPerMember() {
		var left = member(BlockPos.ZERO, segment(27, range(27)));
		var singleLayout = layout(List.of(left));
		try (var single = create(singleLayout, BlockPos.ZERO, list(entry(ByteTag.valueOf((byte) 0), "minecraft:stone", 3)))) {
			assertEquals(27, single.slots());
			assertEquals(singleLayout, single.layout());
			assertEquals(3L, single.read(0).count());
			assertNull(single.read(1));
		}

		BlockPos rightPos = new BlockPos(1, 0, 0);
		var pairLayout = new InventorySnapshotLayout("minecraft:double_chest", "face=north", BlockPos.ZERO,
			List.of(left, member(rightPos, segment(27, range(27)))));
		try (var pair = create(pairLayout, rightPos,
			list(entry(ByteTag.valueOf((byte) 0), "minecraft:stone", 3)),
			list(entry(ByteTag.valueOf((byte) 0), "minecraft:dirt", 7)))) {
			assertEquals(54, pair.slots());
			assertEquals(pairLayout, pair.layout());
			assertEquals(rightPos, pair.originalTarget(), "the hit half is not replaced by the controller");
			assertEquals(2, pair.layout().members().size());
			assertEquals("minecraft:stone", pair.read(0).key().itemId());
			assertEquals("minecraft:dirt", pair.read(27).key().itemId());
			assertEquals(7L, pair.read(27).count());
			assertEquals(InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED, pair.evidence());
		}
	}

	@Test
	void payloadOrderKeepsMultipleSegmentsAndStructuralMembers() {
		var root = segment(1, List.of(0));
		var wrapped = new InventorySnapshotLayout.Segment("Inventory.Items", InventorySnapshotSchemas.ITEMS, 1, List.of(0));
		var topology = layout(List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "controller", List.of(root, wrapped)),
			new InventorySnapshotLayout.Member(new BlockPos(1, 0, 0), "minecraft:chest", "minecraft:chest", "structure", List.of()),
			member(new BlockPos(2, 0, 0), root)));
		try (var snapshot = create(topology, BlockPos.ZERO,
			list(entry(IntTag.valueOf(0), "minecraft:stone", 2)),
			list(entry(IntTag.valueOf(0), "minecraft:dirt", 3)),
			list(entry(IntTag.valueOf(0), "minecraft:diamond", 4)))) {
			assertEquals(topology, snapshot.layout());
			assertEquals(3, snapshot.slots());
			assertEquals("minecraft:stone", snapshot.read(0).key().itemId());
			assertEquals("minecraft:dirt", snapshot.read(1).key().itemId());
			assertEquals("minecraft:diamond", snapshot.read(2).key().itemId());
			assertEquals("Inventory.Items", snapshot.layout().members().getFirst().segments().get(1).fieldPath());
			assertTrue(snapshot.layout().members().get(1).segments().isEmpty());
		}
	}

	@Test
	void inputAndExportedTagsCannotMutateCapturedEntriesEvenAfterIndexing() {
		ListTag input = list(entry(IntTag.valueOf(0), "minecraft:stone", 3));
		var topology = layout(List.of(member(BlockPos.ZERO, segment(2, range(2)))));
		try (var snapshot = create(topology, BlockPos.ZERO, input)) {
			input.getCompound(0).putInt("count", 77);
			input.clear();
			assertEquals(3L, snapshot.read(0).count());
			ListTag exported = (ListTag) snapshot.payload(0);
			exported.getCompound(0).putString("id", "minecraft:dirt");
			exported.getCompound(0).putInt("count", 8);
			exported.clear();
			assertEquals("minecraft:stone", snapshot.read(0).key().itemId());
			assertEquals(3L, snapshot.read(0).count());
			assertEquals(1, ((ListTag) snapshot.payload(0)).size());
		}
	}

	@Test
	void freezingArrayPayloadsAlsoDetachesTheirMutableBackingStorage() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(1, List.of(0)))));
		byte[] data = {1, 2, 3};
		try (var snapshot = create(topology, BlockPos.ZERO, new ByteArrayTag(data))) {
			data[0] = 7;
			assertArrayEquals(new byte[] {1, 2, 3}, ((ByteArrayTag) snapshot.payload(0)).getAsByteArray());
			((ByteArrayTag) snapshot.payload(0)).getAsByteArray()[1] = 9;
			assertArrayEquals(new byte[] {1, 2, 3}, ((ByteArrayTag) snapshot.payload(0)).getAsByteArray());
		}
	}

	@Test
	void frozenFaceMappingKeepsOrderAndNeverWidensToHiddenSlots() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(4, List.of(3, 1)))));
		try (var snapshot = create(topology, BlockPos.ZERO,
			list(entry(IntTag.valueOf(0), "minecraft:diamond", 9),
				entry(IntTag.valueOf(1), "minecraft:stone", 2), entry(IntTag.valueOf(3), "minecraft:dirt", 5)))) {
			assertEquals(2, snapshot.slots());
			assertEquals("minecraft:dirt", snapshot.read(0).key().itemId());
			assertEquals("minecraft:stone", snapshot.read(1).key().itemId());
			assertThrows(IndexOutOfBoundsException.class, () -> snapshot.read(2));
		}
	}

	@Test
	void byteSlotsAreUnsignedAndIntSlotsDoNotTruncateAt255() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(512, List.of(200, 300, 44)))));
		try (var snapshot = create(topology, BlockPos.ZERO,
			list(entry(ByteTag.valueOf((byte) 200), "minecraft:stone", 2), entry(IntTag.valueOf(300), "minecraft:dirt", 5)))) {
			assertEquals("minecraft:stone", snapshot.read(0).key().itemId());
			assertEquals("minecraft:dirt", snapshot.read(1).key().itemId());
			assertNull(snapshot.read(2), "int slot 300 must not alias byte slot 44");
		}
	}

	@Test
	void explicitEmptyListSparseHolesAndExplicitEmptyFaceAreValidObservations() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(3, range(3)))));
		try (var empty = create(topology, BlockPos.ZERO, new ListTag())) {
			for (int i = 0; i < 3; i++) assertNull(empty.read(i));
		}
		try (var sparse = create(topology, BlockPos.ZERO, list(entry(IntTag.valueOf(1), "minecraft:stone", 2)))) {
			assertNull(sparse.read(0));
			assertEquals(2L, sparse.read(1).count());
			assertNull(sparse.read(2));
		}
		var emptyFace = layout(List.of(member(BlockPos.ZERO, segment(3, List.of()))));
		try (var snapshot = create(emptyFace, BlockPos.ZERO, new ListTag())) {
			assertEquals(0, snapshot.slots());
			assertEquals(InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED, snapshot.evidence());
		}
		var structural = layout(List.of(new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "controller", List.of())));
		try (var snapshot = create(structural, BlockPos.ZERO)) { assertEquals(0, snapshot.slots()); }
	}

	@Test
	void duplicateOutOfRangeAndWrongSlotTagsFailInsteadOfInventingEmpty() {
		assertReadFailure(list(entry(IntTag.valueOf(0), "minecraft:stone", 2), entry(ByteTag.valueOf((byte) 0), "minecraft:dirt", 4)), 2);
		assertReadFailure(list(entry(IntTag.valueOf(2), "minecraft:stone", 2)), 2);
		assertReadFailure(list(entry(IntTag.valueOf(-1), "minecraft:stone", 2)), 2);
		assertReadFailure(list(entry(ByteTag.valueOf((byte) -1), "minecraft:stone", 2)), 2);
		assertReadFailure(list(entry(ShortTag.valueOf((short) 0), "minecraft:stone", 2)), 2);
		assertReadFailure(list(entry(LongTag.valueOf(0L), "minecraft:stone", 2)), 2);
		CompoundTag missingSlot = entry(IntTag.valueOf(0), "minecraft:stone", 2);
		missingSlot.remove("Slot");
		assertReadFailure(list(missingSlot), 2);
		assertReadFailure(list(StringTag.valueOf("not an item")), 2);
		assertReadFailure(new CompoundTag(), 2);
	}

	@Test
	void malformedCountsCannotTakeMinecraftCodecDefaultOne() {
		for (Tag count : List.of(IntTag.valueOf(0), IntTag.valueOf(-1), IntTag.valueOf(100),
			LongTag.valueOf(4294967297L), DoubleTag.valueOf(1.5), StringTag.valueOf("3"))) {
			CompoundTag entry = entry(IntTag.valueOf(0), "minecraft:stone", 2);
			entry.put("count", count);
			assertReadFailure(list(entry), 1);
		}
		CompoundTag missingCount = entry(IntTag.valueOf(0), "minecraft:stone", 2);
		missingCount.remove("count");
		try (var snapshot = create(layout(List.of(member(BlockPos.ZERO, segment(1, List.of(0))))), BlockPos.ZERO, list(missingCount))) {
			assertEquals(1L, snapshot.read(0).count(), "a truly absent modern count follows the Minecraft codec");
		}
	}

	@Test
	void invalidMinecraftIdsComponentsAndLegacyFieldsAreReadFailures() {
		assertReadFailure(list(entry(IntTag.valueOf(0), "missing:unknown_item", 3)), 1);
		assertReadFailure(list(entry(IntTag.valueOf(0), "minecraft:air", 3)), 1);
		CompoundTag wrongId = entry(IntTag.valueOf(0), "minecraft:stone", 3);
		wrongId.putInt("id", 4);
		assertReadFailure(list(wrongId), 1);
		CompoundTag wrongComponents = entry(IntTag.valueOf(0), "minecraft:stone", 3);
		wrongComponents.put("components", StringTag.valueOf("not a compound"));
		assertReadFailure(list(wrongComponents), 1);
		CompoundTag badComponentValue = entry(IntTag.valueOf(0), "minecraft:stone", 3);
		CompoundTag components = new CompoundTag();
		components.putString("minecraft:damage", "not an integer");
		badComponentValue.put("components", components);
		assertReadFailure(list(badComponentValue), 1);
		CompoundTag legacyCount = entry(IntTag.valueOf(0), "minecraft:stone", 3);
		legacyCount.remove("count");
		legacyCount.putByte("Count", (byte) 42);
		assertReadFailure(list(legacyCount), 1);
		CompoundTag legacyTag = entry(IntTag.valueOf(0), "minecraft:stone", 3);
		legacyTag.put("tag", new CompoundTag());
		assertReadFailure(list(legacyTag), 1);
	}

	@Test
	void hiddenMalformedEntryFailsInsteadOfBecomingVerifiedEmpty() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(2, List.of(1)))));
		CompoundTag invalidId = entry(IntTag.valueOf(0), "not a valid item id", 2);
		CompoundTag invalidCount = entry(IntTag.valueOf(0), "minecraft:stone", 2);
		invalidCount.putString("count", "two");
		CompoundTag invalidComponents = entry(IntTag.valueOf(0), "minecraft:stone", 2);
		invalidComponents.putString("components", "not a compound");
		CompoundTag invalidComponentKey = entry(IntTag.valueOf(0), "minecraft:stone", 2);
		CompoundTag components = new CompoundTag();
		components.put("not a valid component id", new CompoundTag());
		invalidComponentKey.put("components", components);
		for (CompoundTag malformed : List.of(invalidId, invalidCount, invalidComponents, invalidComponentKey)) {
			try (var snapshot = create(topology, BlockPos.ZERO, list(malformed))) {
				assertThrows(InventorySnapshotSchemas.ReadFailureException.class, () -> snapshot.read(0),
					"structural failure of a recognized candidate must not certify an empty visible slot");
			}
		}
		try (var validHidden = create(topology, BlockPos.ZERO, list(entry(IntTag.valueOf(0), "minecraft:stone", 2)))) {
			assertEquals(1, validHidden.slots());
			assertNull(validHidden.read(0), "format validation never exposes the hidden item");
		}
	}

	@Test
	void deepEmbeddedSnbtIsRejectedBeforeMinecraftCustomDataDecoding() {
		String deeplyNestedSnbt = "{a:".repeat(15000) + "{}" + "}".repeat(15000);
		var topology = layout(List.of(member(BlockPos.ZERO, segment(2, List.of(1)))));
		for (String component : List.of("minecraft:custom_data", "custom_data", "minecraft:entity_data",
			"minecraft:bucket_entity_data", "minecraft:block_entity_data")) {
			for (int localSlot : List.of(0, 1)) {
				CompoundTag item = entry(IntTag.valueOf(localSlot), "minecraft:stone", 2);
				CompoundTag components = new CompoundTag();
				components.putString(component, deeplyNestedSnbt);
				item.put("components", components);
				try (var snapshot = create(topology, BlockPos.ZERO, list(item))) {
					assertThrows(InventorySnapshotSchemas.ReadFailureException.class, () -> snapshot.read(0),
						"shallow string must not expand through the SNBT alternative, visible or hidden");
				}
			}
		}
		CompoundTag ordinarySnbt = entry(IntTag.valueOf(0), "minecraft:stone", 2);
		CompoundTag components = new CompoundTag();
		components.putString("minecraft:custom_data", "{value:1}");
		ordinarySnbt.put("components", components);
		assertReadFailure(list(ordinarySnbt), 1);
	}

	@Test
	void vanillaCustomDataSaveRoundTripsUsingGuardedNativeCompoundNbt() {
		CompoundTag customData = new CompoundTag();
		customData.putString("label", "saved custom data");
		CompoundTag nested = new CompoundTag();
		nested.putInt("value", 42);
		customData.put("nested", nested);
		ItemStack stack = new ItemStack(Items.STONE, 7);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));
		CompoundTag saved = (CompoundTag) stack.save(RegistryAccess.EMPTY);
		Tag savedCustomData = saved.getCompound("components").get("minecraft:custom_data");
		assertInstanceOf(CompoundTag.class, savedCustomData, "native vanilla save must not use the SNBT string alternative");
		assertEquals(customData, savedCustomData);
		saved.putInt("Slot", 0);
		var expected = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack);
		try (var snapshot = create(layout(List.of(member(BlockPos.ZERO, segment(1, List.of(0))))), BlockPos.ZERO, list(saved))) {
			var observed = snapshot.read(0);
			assertEquals(7L, observed.count());
			assertEquals(expected.key(), observed.key(), "native saved custom data retains full variant identity");
			assertEquals(expected.display().displayJson(), observed.displayJson());
			assertFalse(observed.stripped());
		}
	}

	@Test
	void consumingReadPreservesFullVariantIdentityAndCatalogFallback() {
		ItemStack plain = new ItemStack(Items.STONE, 2);
		ItemStack custom = new ItemStack(Items.STONE, 5);
		CompoundTag data = new CompoundTag();
		data.putString("payload", "x".repeat(InventoryItemCodec.MAX_DISPLAY_BYTES + 512));
		custom.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		var topology = layout(List.of(member(BlockPos.ZERO, segment(2, range(2)))));
		try (var snapshot = create(topology, BlockPos.ZERO, list(encodedEntry(0, plain), encodedEntry(1, custom)))) {
			var plainItem = snapshot.read(0);
			var customItem = snapshot.read(1);
			assertEquals(2L, plainItem.count());
			assertEquals(5L, customItem.count());
			assertNotEquals(plainItem.key(), customItem.key());
			assertFalse(plainItem.stripped());
			assertTrue(customItem.stripped());
			assertTrue(customItem.displayJson().contains("minecraft:stone"));
		}
	}

	@Test
	void customCounterRunsOnlyOnConsumptionAndItsMutationCannotCorruptTheSnapshot() {
		String schema = "test:counter_" + UUID.randomUUID().toString().replace("-", "");
		AtomicInteger calls = new AtomicInteger();
		assertFalse(InventorySnapshotSchemas.registered(schema));
		assertTrue(InventorySnapshotSchemas.registered(InventorySnapshotSchemas.ITEMS));
		assertTrue(InventorySnapshotSchemas.register(schema, (payload, localSlot, localSlots, registries) -> {
			calls.incrementAndGet();
			assertEquals(3, localSlots);
			CompoundTag data = (CompoundTag) payload;
			long amount = data.getLong("amount") + localSlot;
			data.putLong("amount", 0L);
			return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), amount);
		}));
		assertTrue(InventorySnapshotSchemas.registered(schema));
		assertFalse(InventorySnapshotSchemas.register(schema, (payload, slot, slots, registries) -> IPlatformInventoryService.Entry.empty()));
		assertFalse(InventorySnapshotSchemas.register(InventorySnapshotSchemas.ITEMS, (payload, slot, slots, registries) -> IPlatformInventoryService.Entry.empty()));
		var segment = new InventorySnapshotLayout.Segment("CustomInventory", schema, 3, List.of(2, 0));
		CompoundTag input = new CompoundTag();
		input.putLong("amount", 9007199254740993L);
		try (var snapshot = create(layout(List.of(member(BlockPos.ZERO, segment))), BlockPos.ZERO, input)) {
			assertEquals(0, calls.get(), "capture must not count or decode");
			assertEquals(9007199254740995L, snapshot.read(0).count());
			assertEquals(9007199254740993L, snapshot.read(1).count());
			assertEquals(9007199254740995L, snapshot.read(0).count());
			assertEquals(9007199254740993L, ((CompoundTag) snapshot.payload(0)).getLong("amount"));
			assertEquals(3, calls.get());
		}
	}

	@Test
	void partialSegmentsWrongOriginalTargetAndUnknownSchemaCannotCreateASnapshot() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(1, List.of(0))), member(new BlockPos(1, 0, 0), segment(1, List.of(0)))));
		assertThrows(IllegalArgumentException.class, () -> create(topology, BlockPos.ZERO, new ListTag()));
		assertThrows(IllegalArgumentException.class, () -> create(topology, new BlockPos(2, 0, 0), new ListTag(), new ListTag()));
		var unknown = new InventorySnapshotLayout.Segment("Other", "test:not_registered", 1, List.of(0));
		assertThrows(InventorySnapshotSchemas.ReadFailureException.class, () -> create(layout(List.of(member(BlockPos.ZERO, unknown))), BlockPos.ZERO, new ListTag()));
	}

	@Test
	void memoryLimitIncludesPayloadAndDecodeWorkspaceAndDoesNotGrowAfterReads() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(1, List.of(0)))));
		ListTag payload = list(entry(IntTag.valueOf(0), "minecraft:stone", 3));
		long accounted;
		try (var snapshot = create(topology, BlockPos.ZERO, payload)) {
			accounted = snapshot.retainedBytes();
			assertTrue(accounted > payload.sizeInBytes());
			assertTrue(accounted <= InventoryNbtSnapshot.memoryUpperBound(1, 1));
			snapshot.read(0);
			snapshot.payload(0);
			assertEquals(accounted, snapshot.retainedBytes(), "future parse and copy allocations were reserved at capture");
		}
		try (var exact = InventoryNbtSnapshot.create(topology, BlockPos.ZERO, List.of(payload), RegistryAccess.EMPTY, accounted)) {
			assertEquals(accounted, exact.retainedBytes());
		}
		assertThrows(InventoryNbtSnapshot.LimitException.class,
			() -> InventoryNbtSnapshot.create(topology, BlockPos.ZERO, List.of(payload), RegistryAccess.EMPTY, accounted - 1));
		assertThrows(InventoryNbtSnapshot.LimitException.class,
			() -> InventoryNbtSnapshot.create(topology, BlockPos.ZERO, List.of(payload), RegistryAccess.EMPTY, 1));
	}

	@Test
	void excessiveDepthCyclesNodesAndArraysFailBeforeDeepCopy() {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(1, List.of(0)))));
		CompoundTag root = new CompoundTag();
		CompoundTag cursor = root;
		for (int i = 0; i < 100; i++) {
			CompoundTag next = new CompoundTag();
			cursor.put("nested", next);
			cursor = next;
		}
		assertThrows(InventoryNbtSnapshot.LimitException.class, () -> create(topology, BlockPos.ZERO, root));
		CompoundTag cyclic = new CompoundTag();
		cyclic.put("self", cyclic);
		assertThrows(InventoryNbtSnapshot.LimitException.class, () -> create(topology, BlockPos.ZERO, cyclic));
		ListTag hugeList = new ListTag();
		for (int i = 0; i < 262145; i++) hugeList.add(IntTag.valueOf(1));
		assertThrows(InventoryNbtSnapshot.LimitException.class, () -> create(topology, BlockPos.ZERO, hugeList));
		assertThrows(InventoryNbtSnapshot.LimitException.class, () -> create(topology, BlockPos.ZERO, new ByteArrayTag(new byte[2097152])));
	}

	@Test
	void closeReleasesPrivateStateAndRejectsLaterReadsOrExports() {
		var snapshot = create(layout(List.of(member(BlockPos.ZERO, segment(1, List.of(0))))), BlockPos.ZERO, new ListTag());
		assertTrue(snapshot.retainedBytes() > 0);
		snapshot.close();
		snapshot.close();
		assertEquals(0L, snapshot.retainedBytes());
		assertThrows(IllegalStateException.class, () -> snapshot.read(0));
		assertThrows(IllegalStateException.class, () -> snapshot.payload(0));
		assertThrows(IllegalStateException.class, snapshot::layout);
	}

	private static void assertReadFailure(Tag payload, int localSlots) {
		var topology = layout(List.of(member(BlockPos.ZERO, segment(localSlots, range(localSlots)))));
		try (var snapshot = create(topology, BlockPos.ZERO, payload)) {
			assertThrows(InventorySnapshotSchemas.ReadFailureException.class, () -> snapshot.read(0));
		}
	}

	private static InventoryNbtSnapshot create(InventorySnapshotLayout topology, BlockPos originalTarget, Tag... payloads) {
		int visible = topology.members().stream().flatMap(member -> member.segments().stream()).mapToInt(segment -> segment.visibleSlots().size()).sum();
		return InventoryNbtSnapshot.create(topology, originalTarget, List.of(payloads), RegistryAccess.EMPTY,
			InventoryNbtSnapshot.memoryUpperBound(visible, topology.members().size()));
	}

	private static InventorySnapshotLayout layout(List<InventorySnapshotLayout.Member> members) {
		return new InventorySnapshotLayout("test:layout", "face=north", null, members);
	}

	private static InventorySnapshotLayout.Member member(BlockPos position, InventorySnapshotLayout.Segment segment) {
		return new InventorySnapshotLayout.Member(position, "minecraft:chest", "minecraft:chest", "member", List.of(segment));
	}

	private static InventorySnapshotLayout.Segment segment(int localSlots, List<Integer> visible) {
		return new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, localSlots, visible);
	}

	private static List<Integer> range(int slots) { return IntStream.range(0, slots).boxed().toList(); }

	private static CompoundTag entry(Tag slot, String id, int count) {
		CompoundTag entry = new CompoundTag();
		entry.put("Slot", slot);
		entry.putString("id", id);
		entry.putInt("count", count);
		return entry;
	}

	private static CompoundTag encodedEntry(int slot, ItemStack stack) {
		CompoundTag entry = (CompoundTag) ItemStack.CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(NbtOps.INSTANCE), stack).result().orElseThrow();
		entry.putInt("Slot", slot);
		return entry;
	}

	private static ListTag list(Tag... entries) {
		ListTag list = new ListTag();
		for (Tag entry : entries) list.add(entry);
		return list;
	}
}
