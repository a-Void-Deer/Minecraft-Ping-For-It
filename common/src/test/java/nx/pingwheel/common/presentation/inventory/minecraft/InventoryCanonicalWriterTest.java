package nx.pingwheel.common.presentation.inventory.minecraft;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNullByDefault;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryCanonicalWriterTest {
	@Test void fixedWriterMatchesIndependentDataOutputForAllTagKindsAndUtfReplacement() throws Exception {
		var root = new CompoundTag();
		root.putByte("byte", (byte) -128); root.putShort("short", (short) -32768); root.putInt("int", Integer.MIN_VALUE);
		root.putLong("long", Long.MIN_VALUE); root.putFloat("float", Float.NaN); root.putDouble("double", -0.0);
		root.putByteArray("bytes", new byte[] { -1, 0, 1 }); root.putIntArray("ints", new int[] {1, -1});
		root.putLongArray("longs", new long[] {1, Long.MAX_VALUE}); root.putString("utf", "\u0000\u007f\u0080\u07ff\u0800\ud83d\ude00\ud800x\udc00");
		var list = new ListTag(); list.add(StringTag.valueOf("first")); list.add(StringTag.valueOf("second")); root.put("list", list);
		root.put("empty", new CompoundTag());
		var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes); original(root, output); output.flush();
		assertArrayEquals(bytes.toByteArray(), InventoryItemCodec.canonicalTag(root));
	}

	@Test void capRefusesBeforeAnyOutputGrowthAndAttemptedWorkStaysCharged() {
		var budget = InventoryItemBudget.root(1024); budget.begin(1024);
		try {
			assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
				() -> InventoryItemCodec.canonicalTag(StringTag.valueOf("a".repeat(65536)), budget));
			assertTrue(budget.attemptedWork() >= 65536);
			assertTrue(budget.rootAllocatedBytes() < 66000);
		} finally { budget.end(); }
	}

	@Test void geometricBoundariesAndExactLogicalMaximumMatchTheIndependentStream() throws Exception {
		for (int size : new int[] {0, 250, 251, 252, 506, 507, 508, 1018, 1019, 1020, 65530, 65531}) {
			var tag = StringTag.valueOf("a".repeat(size));
			var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
			original(tag, output); output.flush();
			var budget = InventoryItemBudget.root(1024); budget.begin(1024);
			try {
				assertArrayEquals(bytes.toByteArray(), InventoryCanonicalWriter.write(tag, budget));
				assertTrue(budget.rootAllocatedBytes() <= InventoryItemBudget.workspaceUpperBound(1024));
			} finally { budget.end(); }
		}
		var budget = InventoryItemBudget.root(1024); budget.begin(1024);
		try {
			assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
				() -> InventoryCanonicalWriter.write(StringTag.valueOf("a".repeat(65532)), budget));
			assertTrue(budget.rootAllocatedBytes() < 1024, "one byte over the logical limit refuses before a large allocation");
		} finally { budget.end(); }
	}

	@Test void cumulativeGrowthAndCopyWorkMustBeAdmittedAndNeverRefundOnFailure() {
		// A list of short strings forces incremental prefix growth, unlike a
		// single large reservation which can skip intermediate capacities.
		var list = new ListTag();
		for (int i = 0; i < 200; i++) list.add(StringTag.valueOf("x".repeat(100)));
		var full = InventoryItemBudget.root(1024); full.begin(1024);
		long work, allocated;
		try {
			assertEquals(21006, InventoryCanonicalWriter.write(list, full).length);
			work = full.attemptedWork(); allocated = full.rootAllocatedBytes();
			assertTrue(allocated > 2L * 21006, "discarded growth arrays and exact final output all remain charged");
		} finally { full.end(); }
		var tight = InventoryItemBudget.capture(1024, 2, 1_000_000, work - 1); tight.begin(1024);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> InventoryCanonicalWriter.write(list, tight));
		long paid = tight.attemptedWork();
		assertTrue(paid > 21006); assertEquals(allocated, tight.rootAllocatedBytes(), "even the final allocation before copy refusal stays paid");
		tight.end(); tight.begin(1024); assertEquals(paid, tight.attemptedWork()); tight.end();
		var exact = InventoryItemBudget.capture(1024, 1, 1_000_000, work); exact.begin(1024);
		try { assertEquals(21006, InventoryCanonicalWriter.write(list, exact).length); assertEquals(work, exact.attemptedWork()); }
		finally { exact.end(); }
	}

	@Test void growthArrayAllocationIsAdmittedInFullBeforePublishingIt() {
		var budget = InventoryItemBudget.root(1024); budget.begin(1024);
		try {
			budget.allocate(2L * 1024 * 1024 - 1024, 0, 0, 0);
			long before = budget.rootAllocatedBytes();
			assertThrows(InventoryItemBudget.RootLimitException.class,
				() -> InventoryCanonicalWriter.write(StringTag.valueOf("a".repeat(1000)), budget));
			assertTrue(budget.rootAllocatedBytes() - before < 1024,
				"an over-budget full growth array cannot be replaced by a delta debit");
		} finally { budget.end(); }
	}

	private static void original(Tag tag, DataOutputStream out) throws Exception {
		out.writeByte(tag.getId());
		switch (tag) {
			case ByteTag value -> out.writeByte(value.getAsByte());
			case ShortTag value -> out.writeShort(value.getAsShort());
			case IntTag value -> out.writeInt(value.getAsInt());
			case LongTag value -> out.writeLong(value.getAsLong());
			case FloatTag value -> out.writeFloat(value.getAsFloat());
			case DoubleTag value -> out.writeDouble(value.getAsDouble());
			case StringTag value -> text(value.getAsString(), out);
			case ByteArrayTag value -> { byte[] data = value.getAsByteArray(); out.writeInt(data.length); out.write(data); }
			case IntArrayTag value -> { int[] data = value.getAsIntArray(); out.writeInt(data.length); for (int element : data) out.writeInt(element); }
			case LongArrayTag value -> { long[] data = value.getAsLongArray(); out.writeInt(data.length); for (long element : data) out.writeLong(element); }
			case ListTag value -> { out.writeByte(value.getElementType()); out.writeInt(value.size()); for (Tag child : value) original(child, out); }
			case CompoundTag value -> {
				var keys = new ArrayList<>(value.getAllKeys()); Collections.sort(keys); out.writeInt(keys.size());
				for (String key : keys) { text(key, out); original(value.get(key), out); }
			}
			default -> throw new AssertionError();
		}
	}
	private static void text(String value, DataOutputStream out) throws Exception {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
	}
}
