package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.*;

import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.UnsafeDisplay;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.Validation;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryPatches;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Narrow, non-codec copier for the concrete item graphs retained by name hovers. */
@NotNullByDefault
final class ClientDisplayItemCopy {

	private static final InventoryResidentAccess RAW = InventoryResidentAccess.load();
	private static final @Nullable Constructor<ChargedProjectiles> PROJECTILES = constructor(ChargedProjectiles.class, List.class);
	private ClientDisplayItemCopy() {}

	static ItemStack copy(ItemStack source, int depth, Validation budget) {
		budget.visit(depth);
		if (source.isEmpty()) return ItemStack.EMPTY;
		if (!ClientItemDisplay.supportedComponents(source.getComponents(), depth, budget)) throw new UnsafeDisplay();
		DataComponentPatch.Builder patch = DataComponentPatch.builder();
		ClientItemDisplay.visitPatch(source.getComponents(), budget, (type, addition) -> {
			budget.visit(depth); budget.cells(1); InventoryPatches.put(patch, type, addition.map(child -> value(type, child, depth, budget)));
		});
		PatchedDataComponentMap components = ((PatchedDataComponentMap) source.getComponents()).copy();
		components.restorePatch(patch.build());
		return assemble(source.getItem(), source.getCount(), components, depth, budget);
	}

	static ItemStack copy(Item item, int count, DataComponentPatch patch, int depth, Validation budget) {
		if (!ClientItemDisplay.supportedPatch(patch, depth, budget)) throw new UnsafeDisplay();
		DataComponentPatch.Builder owned = DataComponentPatch.builder();
		ClientItemDisplay.visitPatch(patch, budget, (type, addition) -> {
			budget.visit(depth); budget.cells(1); InventoryPatches.put(owned, type, addition.map(child -> value(type, child, depth, budget)));
		});
		PatchedDataComponentMap components = new PatchedDataComponentMap(item.components());
		components.restorePatch(owned.build());
		return assemble(item, count, components, depth, budget);
	}

	private static ItemStack assemble(Item item, int count, PatchedDataComponentMap components, int depth, Validation budget) {
		budget.visit(depth);
		// Deferred hovers are admitted only for the inherited no-op verifier.
		// Materialized hovers need the same property: copying must not add profile
		// callbacks or retain an item hook's producer-owned mutable return values.
		if (!ClientItemDisplay.HoverStack.NO_LOAD_HOOK.get(item.getClass())) throw new UnsafeDisplay();
		budget.visit(depth);
		ItemStack result = ClientDisplayWorkingCopy.noLoadStack(item, count, components);
		if (!ClientItemDisplay.supportedComponents(result.getComponents(), depth, budget)) throw new UnsafeDisplay();
		return result;
	}

	static Object value(DataComponentType<?> type, Object value, int depth, Validation budget) {
		budget.visit(depth);
		if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) return ClientDisplayText.snapshot((Component) value, depth + 1, budget);
		if (type == DataComponents.LORE) {
			ItemLore lore = (ItemLore) value;
			List<Component> lines = text(lore.lines(), depth, budget);
			// Rebuild the exact native lazy style view over OWNED lines, retaining
			// its independent default gray/italic style rather than substituting
			// raw lines or visiting the resident copying mapper.
			if (ClientItemDisplay.LoreView.matches(lore)) return new ItemLore(lines);
			return new ItemLore(lines, text(lore.styledLines(), depth, budget));
		}
		if (type == DataComponents.CHARGED_PROJECTILES) {
			List<ItemStack> items = items(RAW.projectiles((ChargedProjectiles) value), depth, budget);
			return construct(PROJECTILES, items);
		}
		// Constructors of container/bundle/food perform hashes, weight queries or
		// expose mutable effect state. Only their proven empty immutable leaves
		// qualify here; an unsupported concrete hover fails softly, not all hovers.
		if (value == ItemContainerContents.EMPTY || value == BundleContents.EMPTY) return value;
		if (type == DataComponents.WRITTEN_BOOK_CONTENT) {
			WrittenBookContent book = (WrittenBookContent) value;
			List<Filterable<Component>> pages = new ArrayList<>();
			for (Object page : ClientItemDisplay.admittedList(book.pages(), budget)) {
				budget.visit(depth);
				Filterable<?> filter = (Filterable<?>) page;
				pages.add(new Filterable<>(ClientDisplayText.snapshot((Component) filter.raw(), depth + 1, budget),
					filter.filtered().map(child -> ClientDisplayText.snapshot((Component) child, depth + 1, budget))));
			}
			budget.characters(book.author());
			return new WrittenBookContent(strings(book.title(), budget), book.author(), book.generation(), pages, book.resolved());
		}
		if (type == DataComponents.WRITABLE_BOOK_CONTENT) {
			List<Filterable<String>> pages = new ArrayList<>();
			for (Object page : ClientItemDisplay.admittedList(((WritableBookContent) value).pages(), budget)) pages.add(strings((Filterable<?>) page, budget));
			return new WritableBookContent(pages);
		}
		Class<?> shape = value.getClass();
		if (shape == String.class) { budget.characters((String) value); return value; }
		if (shape == Integer.class || shape == Long.class || shape == Boolean.class || shape == Float.class || shape == Double.class
			|| shape == Byte.class || shape == Short.class || shape == Rarity.class || shape == net.minecraft.util.Unit.class
			|| shape == CustomModelData.class || shape == net.minecraft.world.item.component.Unbreakable.class
			|| shape == net.minecraft.world.item.component.DyedItemColor.class) return value;
		// These exact shared empty native constants have no public mutator or
		// mutable children. Nonempty variants require their own bounded copier.
		if (value == net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY || value == ItemAttributeModifiers.EMPTY) return value;
		throw new UnsafeDisplay();
	}

	private static List<Component> text(List<Component> source, int depth, Validation budget) {
		List<Component> result = new ArrayList<>();
		for (Object line : ClientItemDisplay.admittedList(source, budget)) result.add(ClientDisplayText.snapshot((Component) line, depth + 1, budget));
		return result;
	}
	private static List<ItemStack> items(List<ItemStack> source, int depth, Validation budget) {
		List<ItemStack> result = new ArrayList<>();
		for (Object item : ClientItemDisplay.admittedList(source, budget)) result.add(copy((ItemStack) item, depth + 1, budget));
		return result;
	}
	private static Filterable<String> strings(Filterable<?> filter, Validation budget) {
		budget.visit(0);
		if (!(filter.raw() instanceof String raw)) throw new UnsafeDisplay();
		budget.characters(raw);
		Optional<String> filtered = filter.filtered().map(value -> {
			if (!(value instanceof String text)) throw new UnsafeDisplay();
			budget.characters(text); return text;
		});
		return new Filterable<>(raw, filtered);
	}
	private static <T> @Nullable Constructor<T> constructor(Class<T> owner, Class<?>... parameters) {
		try { Constructor<T> result = owner.getDeclaredConstructor(parameters); return result.trySetAccessible() ? result : null; }
		catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
	}
	private static <T> T construct(@Nullable Constructor<T> constructor, Object argument) {
		if (constructor == null) throw new UnsafeDisplay();
		try { return constructor.newInstance(argument); }
		catch (ReflectiveOperationException unavailable) { throw new UnsafeDisplay(unavailable); }
	}
}
