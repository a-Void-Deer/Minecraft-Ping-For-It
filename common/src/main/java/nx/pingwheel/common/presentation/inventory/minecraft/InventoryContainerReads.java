package nx.pingwheel.common.presentation.inventory.minecraft;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.block.entity.*;
import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentReads;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;

/** Fixed read graphs and bounded wrapper composition, never namespace membership. */
public final class InventoryContainerReads {
	private InventoryContainerReads() {}

	/**
	 * Single-thread reusable finite scratch for repeated classification on one
	 * capture/read pass. It holds only fixed-size arrays plus the already
	 * resolved raw-field port; it is never retained beyond its operation.
	 */
	public static final class Scratch {
		private final Container[] scopeActive = new Container[16];
		private final int[] scopeNodes = new int[1];
		private final Container[] auditActive = new Container[16];
		private final int[] auditNodes = new int[1];
		private final InventoryResidentAccess raw;
		public Scratch() { this(InventoryResidentAccess.load()); }
		public Scratch(InventoryResidentAccess raw) { this.raw = java.util.Objects.requireNonNull(raw, "raw"); }
	}
	private static final List<Class<?>> FIELD_OWNERS = List.of(ChestBlockEntity.class, BarrelBlockEntity.class,
		DispenserBlockEntity.class, HopperBlockEntity.class, AbstractFurnaceBlockEntity.class,
		BrewingStandBlockEntity.class, ShulkerBoxBlockEntity.class);
	// Method references are remapped by each loader, unlike reflective string
	// literals. Only our own serializable lambda's fixed writeReplace is invoked.
	// InnerClassLambdaMetafactory builds SerializedLambda.implMethodName from
	// implInfo.getName() of the linked implementation handle, not javac's
	// deserialization-switch literals. No lambda deserialization is used here.
	@FunctionalInterface private interface ItemRead extends Serializable { ItemStack get(Container value, int slot); }
	@FunctionalInterface private interface SizeRead extends Serializable { int get(Container value); }
	@FunctionalInterface private interface FaceRead extends Serializable { int[] get(WorldlyContainer value, Direction face); }
	@FunctionalInterface private interface LootRead extends Serializable { ResourceKey<LootTable> get(RandomizableContainer value); }
	@FunctionalInterface private interface UnpackRead extends Serializable { void get(RandomizableContainer value, Player player); }
	@FunctionalInterface private interface LevelRead extends Serializable { Level get(BlockEntity value); }
	@FunctionalInterface private interface PositionRead extends Serializable { BlockPos get(BlockEntity value); }
	private static final String ITEM = name((ItemRead) Container::getItem), SIZE = name((SizeRead) Container::getContainerSize),
		FACE = name((FaceRead) WorldlyContainer::getSlotsForFace), LOOT = name((LootRead) RandomizableContainer::getLootTable),
		UNPACK = name((UnpackRead) RandomizableContainer::unpackLootTable), LEVEL = name((LevelRead) BlockEntity::getLevel),
		POSITION = name((PositionRead) BlockEntity::getBlockPos);
	public static boolean audited(Container value, Predicate<Container> admitted) {
		return audited(value, admitted, new Scratch());
	}
	/** Field-equivalent headless port; supplying it does not certify an unknown read graph. */
	public static boolean audited(Container value, Predicate<Container> admitted, InventoryResidentAccess raw) {
		return audited(value, admitted, new Scratch(raw));
	}
	/** Same classification through caller-reused finite scratch, reset on entry. */
	public static boolean audited(Container value, Predicate<Container> admitted, Scratch scratch) {
		scratch.auditNodes[0] = 0;
		scratch.scopeNodes[0] = 0;
		requireScope(value, admitted, scratch.scopeActive, 0, scratch.scopeNodes);
		return audited(value, admitted, scratch.auditActive, 0, scratch.auditNodes, scratch.raw);
	}
	/** Authority walks all known delegates even when an earlier read graph is unknown. */
	public static void requireScope(Container value, Predicate<Container> admitted) {
		requireScope(value, admitted, new Container[16], 0, new int[] {0});
	}
	/** Same scope walk through caller-reused finite scratch, reset on entry. */
	public static void requireScope(Container value, Predicate<Container> admitted, Scratch scratch) {
		scratch.scopeNodes[0] = 0;
		requireScope(value, admitted, scratch.scopeActive, 0, scratch.scopeNodes);
	}
	/** Compose only full native CompoundContainer delegates, never discover members or widen a sided child. */
	public static List<Integer> scopedSlots(Container value, Predicate<Container> admitted, Function<Container, List<Integer>> local) {
		return scopedSlots(value, admitted, local, new Scratch());
	}
	/** Same composition through caller-reused finite scratch. */
	public static List<Integer> scopedSlots(Container value, Predicate<Container> admitted, Function<Container, List<Integer>> local, Scratch scratch) {
		requireScope(value, admitted, scratch);
		List<Integer> known = local.apply(value);
		if (known != null || !(value instanceof CompoundContainer) || !audited(value, admitted, scratch)) return known;
		return fullCompound(value, admitted, local, 0);
	}
	private static List<Integer> fullCompound(Container value, Predicate<Container> admitted, Function<Container, List<Integer>> local, int depth) {
		if (depth >= 16) throw new IllegalStateException("container wrapper bound");
		List<Integer> known = local.apply(value);
		if (known != null) {
			int size = value.getContainerSize();
			if (known.size() != size) return null;
			for (int i = 0; i < size; i++) if (known.get(i) != i) return null;
			return known;
		}
		if (!(value instanceof CompoundContainer)) return null;
		for (Field field : CompoundContainer.class.getDeclaredFields()) if (field.getType() == Container.class) {
			Object child = field(value, field);
			if (!(child instanceof Container container) || fullCompound(container, admitted, local, depth + 1) == null) return null;
		}
		int size = value.getContainerSize();
		if (size < 0 || size > nx.pingwheel.common.presentation.inventory.InventorySourceAccess.MAX_SLOTS)
			throw new IllegalStateException("compound slot bound");
		return java.util.stream.IntStream.range(0, size).boxed().toList();
	}
	private static void requireScope(Container value, Predicate<Container> admitted, Container[] active, int depth, int[] nodes) {
		if (value == null || depth == active.length || ++nodes[0] > 32) throw new IllegalStateException("container wrapper bound");
		for (int i = 0; i < depth; i++) if (active[i] == value) throw new IllegalStateException("container wrapper cycle");
		if (!admitted.test(value)) throw new IllegalStateException("container delegate outside admitted source");
		active[depth] = value;
		if (value instanceof CompoundContainer) for (Field field : CompoundContainer.class.getDeclaredFields())
			if (field.getType() == Container.class) {
				Object child = field(value, field);
				if (!(child instanceof Container container)) throw new IllegalStateException("container delegate unavailable");
				requireScope(container, admitted, active, depth + 1, nodes);
			}
	}
	private static boolean audited(Container value, Predicate<Container> admitted, Container[] active, int depth, int[] nodes, InventoryResidentAccess raw) {
		if (value == null || depth == active.length || ++nodes[0] > 32) throw new IllegalStateException("container wrapper bound");
		for (int i = 0; i < depth; i++) if (active[i] == value) throw new IllegalStateException("container wrapper cycle");
		if (!admitted.test(value)) return false; // before any metadata/getter on this delegate
		active[depth] = value;
		// Every family includes the metadata path used by source discovery. Adding
		// WorldlyContainer with an unknown mapping cannot inherit certification.
		if (value instanceof WorldlyContainer && !nativeMapping(value)) return false;
		if (ResidentReads.inherits(value, CompoundContainer.class, ITEM, int.class)
			&& ResidentReads.inherits(value, CompoundContainer.class, SIZE)) {
			// Both fields must qualify; their reflective order is irrelevant. The
			// real native getter, not these fields, determines read order later.
			int delegates = 0;
			for (Field field : CompoundContainer.class.getDeclaredFields()) if (field.getType() == Container.class) {
				Object child = field(value, field);
				if (!(child instanceof Container container) || !audited(container, admitted, active, depth + 1, nodes, raw)) return false;
				delegates++;
			}
			return delegates == 2;
		}
		if (ResidentReads.inherits(value, SimpleContainer.class, ITEM, int.class)
			&& ResidentReads.inherits(value, SimpleContainer.class, SIZE))
			return (!(value instanceof WorldlyContainer) || nativeMapping(value))
				&& ResidentReads.nativeStacks(uniqueField(value, SimpleContainer.class, NonNullList.class), raw);
		Class<?> read = owner(value, ITEM, int.class);
		if ((read == ChiseledBookShelfBlockEntity.class || read == CrafterBlockEntity.class)
			&& owner(value, SIZE) == read)
			return ResidentReads.nativeStacks(uniqueField(value, read, NonNullList.class), raw);
		if (read != BaseContainerBlockEntity.class && read != RandomizableContainerBlockEntity.class) return false;
		if (read == RandomizableContainerBlockEntity.class) {
			if (owner(value, UNPACK, Player.class) != RandomizableContainer.class
				|| !ResidentReads.inherits(value, RandomizableContainerBlockEntity.class, LOOT)
				|| !ResidentReads.inherits(value, BlockEntity.class, LEVEL)
				|| !ResidentReads.inherits(value, BlockEntity.class, POSITION)) return false;
			if (((RandomizableContainer) value).getLootTable() != null) throw new IllegalStateException("pending container loot");
		}
		String items = itemsMethod();
		if (items == null) return false;
		Class<?> fields = owner(value, items);
		if (fields == null || !FIELD_OWNERS.contains(fields) || owner(value, SIZE) != fields) return false;
		if (value instanceof WorldlyContainer && !nativeMapping(value)) return false;
		return ResidentReads.nativeStacks(uniqueField(value, fields, NonNullList.class), raw);
	}
	public static boolean nativeMapping(Object value) {
		Class<?> mapping = owner(value, FACE, Direction.class);
		return mapping == AbstractFurnaceBlockEntity.class || mapping == BrewingStandBlockEntity.class || mapping == ShulkerBoxBlockEntity.class;
	}
	/** Validate length/range before any owned boxing or clone of external metadata. */
	public static List<Integer> mapping(Container container, Direction face) {
		int size = container.getContainerSize();
		if (size < 0 || size > nx.pingwheel.common.presentation.inventory.InventorySourceAccess.MAX_SLOTS)
			throw new IllegalStateException("container slot bound");
		if (!(container instanceof WorldlyContainer sided)) return java.util.stream.IntStream.range(0, size).boxed().toList();
		int[] slots = sided.getSlotsForFace(face);
		if (slots == null || slots.length > size) throw new IllegalStateException("container face bound");
		boolean[] seen = new boolean[size];
		for (int slot : slots) {
			if (slot < 0 || slot >= size || seen[slot]) throw new IllegalStateException("container face mapping");
			seen[slot] = true;
		}
		return java.util.Arrays.stream(slots).boxed().toList();
	}

	/** Compare a current face mapping without cloning or boxing it; the metadata getter may allocate. */
	public static boolean mappingEquals(Container container, Direction face, List<Integer> expected) {
		if (expected == null) return false;
		int size = container.getContainerSize();
		if (size < 0 || size > InventorySourceAccess.MAX_SLOTS) return false;
		if (!(container instanceof WorldlyContainer sided)) {
			if (expected.size() != size) return false;
			for (int index = 0; index < size; index++) if (expected.get(index) != index) return false;
			return true;
		}
		int[] slots = sided.getSlotsForFace(face);
		if (slots == null || slots.length != expected.size()) return false;
		for (int index = 0; index < slots.length; index++) if (expected.get(index) != slots[index]) return false;
		return true;
	}

	/**
	 * True when the selected face exposes exactly the container's local slots in
	 * local order: the inheritance witness of an unsided known wrapper. Metadata
	 * only; no slot content is read.
	 */
	public static boolean identityMapping(Container container, Direction face) {
		int size = container.getContainerSize();
		if (size < 0 || size > InventorySourceAccess.MAX_SLOTS) return false;
		if (!(container instanceof WorldlyContainer sided)) return true;
		int[] slots = sided.getSlotsForFace(face);
		if (slots == null || slots.length != size) return false;
		for (int index = 0; index < size; index++) if (slots[index] != index) return false;
		return true;
	}

	/** True when the selected face exposes the given local slot at the given visible position. */
	public static boolean faceSlot(Container container, Direction face, int visible, int local) {
		if (visible < 0 || local < 0) return false;
		int size = container.getContainerSize();
		if (size < 0 || size > InventorySourceAccess.MAX_SLOTS || local >= size) return false;
		if (!(container instanceof WorldlyContainer sided)) return visible == local;
		int[] slots = sided.getSlotsForFace(face);
		return slots != null && visible < slots.length && slots[visible] == local;
	}

	/** True when the selected face exposes the given local slot at any visible position. */
	public static boolean faceContains(Container container, Direction face, int local) {
		if (local < 0) return false;
		int size = container.getContainerSize();
		if (size < 0 || size > InventorySourceAccess.MAX_SLOTS || local >= size) return false;
		if (!(container instanceof WorldlyContainer sided)) return true;
		int[] slots = sided.getSlotsForFace(face);
		if (slots == null) return false;
		for (int slot : slots) if (slot == local) return true;
		return false;
	}
	private static String itemsMethod() {
		String name = null;
		for (Method method : BaseContainerBlockEntity.class.getDeclaredMethods())
			if (method.getReturnType() == NonNullList.class && method.getParameterCount() == 0) {
				if (name != null) return null; name = method.getName();
			}
		return name;
	}
	private static Object uniqueField(Object value, Class<?> owner, Class<?> type) {
		Field found = null;
		for (Field field : owner.getDeclaredFields()) if (field.getType() == type) {
			if (found != null) return null; found = field;
		}
		return found == null ? null : field(value, found);
	}
	private static Object field(Object value, Field field) {
		try { return field.trySetAccessible() ? field.get(value) : null; }
		catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
	}
	private static String name(Serializable reference) {
		try {
			Method replace = reference.getClass().getDeclaredMethod("writeReplace");
			if (!replace.trySetAccessible()) throw new IllegalStateException("native read name unavailable");
			return ((SerializedLambda) replace.invoke(reference)).getImplMethodName();
		} catch (ReflectiveOperationException failure) { throw new IllegalStateException("native read name unavailable", failure); }
	}
	private static Class<?> owner(Object value, String name, Class<?>... args) {
		Class<?> current = value.getClass();
		for (int depth = 0; current != null && depth < 32; depth++, current = current.getSuperclass()) {
			if (!ResidentReads.reflectionShape(current, name, args)) return null;
			try { return current.getDeclaredMethod(name, args).getDeclaringClass(); }
			catch (NoSuchMethodException absent) { /* bounded inherited search */ }
			catch (SecurityException unavailable) { return null; }
		}
		if (!ResidentReads.reflectionShape(value.getClass(), name, args)) return null;
		try { Method method = value.getClass().getMethod(name, args); return method.getDeclaringClass(); }
		catch (ReflectiveOperationException | SecurityException unavailable) { return null; }
	}
}
