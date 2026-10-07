package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;

/** Code-registered parsers of inventory-only payloads, not arbitrary block NBT. */
public final class InventorySnapshotSchemas {

	public static final String ITEMS = "minecraft:items";
	private static final ConcurrentHashMap<String, Counter> COUNTERS = new ConcurrentHashMap<>();
	private static final Counter ITEMS_COUNTER = (payload, localSlot, localSlots, registries) ->
		index(payload, localSlots, IntStream.range(0, checkedSlots(localSlots)).boxed().toList()).read(localSlot, registries);

	static { COUNTERS.put(ITEMS, ITEMS_COUNTER); }

	private InventorySnapshotSchemas() {}

	/** Custom counters receive their own deep copy, never the snapshot's private tag. */
	@FunctionalInterface
	public interface Counter {
		IPlatformInventoryService.Entry read(Tag payload, int localSlot, int localSlots, HolderLookup.Provider registries);
	}

	/** Registration cannot replace the builtin or an earlier registration. */
	public static boolean register(String schemaId, Counter counter) {
		Objects.requireNonNull(schemaId, "schemaId");
		Objects.requireNonNull(counter, "counter");
		if (schemaId.length() > 256 || ResourceLocation.tryParse(schemaId) == null) throw new IllegalArgumentException("invalid snapshot schema id");
		return COUNTERS.putIfAbsent(schemaId, counter) == null;
	}

	static Counter counter(String schemaId) {
		Counter counter = COUNTERS.get(schemaId);
		if (counter == null) throw new ReadFailureException("unregistered inventory snapshot schema");
		return counter;
	}

	static boolean registered(String schemaId) { return COUNTERS.containsKey(schemaId); }

	/** Malformed observed data is a read failure, never an empty-slot observation. */
	public static final class ReadFailureException extends IllegalStateException {
		public ReadFailureException(String reason) { super(reason); }
	}

	/**
	 * Built on the first consuming read, not during capture. This validates the
	 * sparse list once, retains only visible entry references, and decodes just
	 * the requested entry. The one-time header pass is O(localSlots); it never
	 * decodes hidden stacks or aggregates their counts.
	 */
	static ItemsIndex index(Tag payload, int localSlots, List<Integer> visibleSlots) {
		checkedSlots(localSlots);
		if (!(payload instanceof ListTag list) || (list.size() != 0 && list.getElementType() != Tag.TAG_COMPOUND)) {
			throw new ReadFailureException("items payload is not a compound list");
		}
		if (list.size() > localSlots) throw new ReadFailureException("items list exceeds local slots");
		int[] visibleByLocal = new int[localSlots];
		Arrays.fill(visibleByLocal, -1);
		for (int i = 0; i < visibleSlots.size(); i++) visibleByLocal[visibleSlots.get(i)] = i;
		CompoundTag[] entries = new CompoundTag[visibleSlots.size()];
		boolean[] seen = new boolean[localSlots];
		for (Tag tag : list) {
			if (!(tag instanceof CompoundTag entry)) throw new ReadFailureException("items entry is not a compound");
			int slot = slot(entry.get("Slot"));
			if (slot < 0 || slot >= localSlots || seen[slot]) throw new ReadFailureException("duplicate or out-of-range items slot");
			seen[slot] = true;
			validateEntry(entry);
			int visible = visibleByLocal[slot];
			if (visible >= 0) entries[visible] = entry;
		}
		return new ItemsIndex(entries);
	}

	private static int checkedSlots(int localSlots) {
		if (localSlots < 0 || localSlots > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("snapshot local slot bound");
		return localSlots;
	}

	private static int slot(Tag slot) {
		if (slot instanceof ByteTag value) return Byte.toUnsignedInt(value.getAsByte());
		if (slot instanceof IntTag value) return value.getAsInt();
		throw new ReadFailureException("items Slot must be byte or int");
	}

	private static void validateEntry(CompoundTag entry) {
		// The 1.21.1 codec ignores these legacy keys, and its count field has an
		// orElse(1) fallback even for malformed present data. Do not let either
		// behavior manufacture a one-item observation.
		if (entry.contains("Count") || entry.contains("tag")) throw new ReadFailureException("legacy items entry is unsupported");
		if (!(entry.get("id") instanceof StringTag id)) throw new ReadFailureException("items id must be a string");
		if (ResourceLocation.tryParse(id.getAsString()) == null) throw new ReadFailureException("items id has invalid registry syntax");
		if (entry.contains("components")) {
			if (!(entry.get("components") instanceof CompoundTag components)) throw new ReadFailureException("items components must be a compound");
			validateComponents(components);
		}
		if (entry.contains("count")) {
			long count = switch (entry.get("count")) {
				case ByteTag value -> value.getAsByte();
				case ShortTag value -> value.getAsShort();
				case IntTag value -> value.getAsInt();
				case LongTag value -> value.getAsLong();
				default -> throw new ReadFailureException("items count must be an integer");
			};
			if (count < 1 || count > 99) throw new ReadFailureException("items count is outside the Minecraft codec range");
		}
	}

	private static void validateComponents(CompoundTag components) {
		for (String key : components.getAllKeys()) {
			boolean removed = key.startsWith("!");
			ResourceLocation id = ResourceLocation.tryParse(removed ? key.substring(1) : key);
			if (id == null) throw new ReadFailureException("items component key has invalid registry syntax");
			// Vanilla CustomData encodes its primary CompoundTag.CODEC form in
			// ItemStack.save(NbtOps). Its alternative string form invokes the
			// recursive SNBT parser and can expand a shallow, guarded StringTag
			// into an unguarded tree. Require the native saved form before any
			// ItemStack decode, including aliases and the other builtin users of
			// this same CustomData codec. Removal patches do not decode a value.
			if (!removed && nativeCustomData(id) && !(components.get(key) instanceof CompoundTag)) {
				throw new ReadFailureException("items custom-data component must use native compound NBT");
			}
		}
	}

	private static boolean nativeCustomData(ResourceLocation id) {
		if (!id.getNamespace().equals("minecraft")) return false;
		return switch (id.getPath()) {
			case "custom_data", "entity_data", "bucket_entity_data", "block_entity_data" -> true;
			default -> false;
		};
	}

	static final class ItemsIndex {
		private final CompoundTag[] entries;
		private ItemsIndex(CompoundTag[] entries) { this.entries = entries; }

		IPlatformInventoryService.Entry read(int visibleIndex, HolderLookup.Provider registries) {
			CompoundTag entry = entries[visibleIndex];
			if (entry == null) return IPlatformInventoryService.Entry.empty();
			// Registered component codecs also see a detached input, so even a
			// mutable component decoder cannot rewrite this frozen sparse index.
			var decoded = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), entry.copy());
			if (decoded.error().isPresent()) throw new ReadFailureException("Minecraft items codec rejected the entry");
			ItemStack stack = decoded.result().orElseThrow(() -> new ReadFailureException("Minecraft items codec did not produce a complete stack"));
			if (stack.isEmpty()) throw new ReadFailureException("Minecraft items codec produced an empty stack");
			return new IPlatformInventoryService.Entry(stack.copyWithCount(1), stack.getCount());
		}
	}
}
