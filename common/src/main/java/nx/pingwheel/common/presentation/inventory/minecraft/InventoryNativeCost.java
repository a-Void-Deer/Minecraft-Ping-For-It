package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.effect.MobEffectInstance;

/** Actual selected native family events; does not encode, copy or format values. */
final class InventoryNativeCost {
	private final InventoryItemBudget budget;
	private final InventoryEncodingCost c;
	private final InventoryResidentAccess raw;
	private final InventoryItemNativeAccess nativeAccess;
	private final InventoryTextCost text;
	InventoryNativeCost(InventoryItemBudget budget, InventoryResidentAccess raw, InventoryItemNativeAccess nativeAccess, InventoryEncodingCost cost) {
		this.budget = budget; this.c = cost; this.raw = raw; this.nativeAccess = nativeAccess;
		text = new InventoryTextCost(budget, raw, cost);
	}
	void value(DataComponentType<?> type, Object value) {
		if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) text.flat((Component) value);
		else if (type == DataComponents.LORE) {
			var lines = ((ItemLore) value).lines(); c.xmap(); c.list(lines.size(), 0);
			c.planningList(lines.size()); for (int index = 0; index < lines.size(); index++) text.flat(lines.get(index));
		} else if (type == DataComponents.UNBREAKABLE) {
			boolean show = ((Unbreakable) value).showInTooltip(); c.record(1, show ? 0 : 1); c.defaults(1, show ? 0 : 1);
			if (!show) c.booleanLeaf();
		} else if (type == DataComponents.DYED_COLOR) {
			var dye = (DyedItemColor) value; int n = dye.showInTooltip() ? 1 : 2;
			c.either(); c.record(2, n); c.defaults(1, n - 1); c.box4(); c.numericLeaf(4); if (n == 2) c.booleanLeaf();
		} else if (type == DataComponents.ATTRIBUTE_MODIFIERS) {
			var modifiers = (ItemAttributeModifiers) value; var entries = modifiers.modifiers();
			c.either(); c.record(2, modifiers.showInTooltip() ? 1 : 2); c.defaults(1, modifiers.showInTooltip() ? 0 : 1);
			if (!modifiers.showInTooltip()) c.booleanLeaf(); c.list(entries.size(), 0);
			c.planningList(entries.size()); for (int index = 0; index < entries.size(); index++) { var entry = entries.get(index);
				boolean slot = entry.slot() != net.minecraft.world.entity.EquipmentSlotGroup.ANY;
				c.graph(3); c.graph(3); c.keys(slot ? 5 : 4); c.map(slot ? 5 : 4); c.defaults(1, slot ? 1 : 0);
				holder(entry.attribute(), BuiltInRegistries.ATTRIBUTE); c.resource(entry.modifier().id()); c.box8(); c.numericLeaf(8);
				c.enumLeaf(entry.modifier().operation().getSerializedName()); if (slot) c.enumLeaf(entry.slot().getSerializedName());
			}
		} else if (type == DataComponents.TOOL) {
			var tool = (Tool) value; boolean speed = tool.defaultMiningSpeed() != 1, damage = tool.damagePerBlock() != 1;
			c.record(3, 1 + (speed ? 1 : 0) + (damage ? 1 : 0)); c.defaults(2, (speed ? 1 : 0) + (damage ? 1 : 0)); c.box4(); c.box4();
			if (speed) c.numericLeaf(4); if (damage) { c.range(); c.numericLeaf(4); }
			c.list(tool.rules().size(), 0);
			c.planningList(tool.rules().size()); for (int index = 0; index < tool.rules().size(); index++) { var rule = tool.rules().get(index);
				c.record(3, 1 + (rule.speed().isPresent() ? 1 : 0) + (rule.correctForDrops().isPresent() ? 1 : 0)); c.optionalFields(2);
				holders(rule.blocks()); if (rule.speed().isPresent()) { c.range(); c.numericLeaf(4); }
				if (rule.correctForDrops().isPresent()) c.booleanLeaf();
			}
		} else if (type == DataComponents.POTION_CONTENTS) {
			var potion = (PotionContents) value; var effects = raw.potion(potion);
			c.either(); c.record(3, (potion.potion().isPresent() ? 1 : 0) + (potion.customColor().isPresent() ? 1 : 0) + (effects.isEmpty() ? 0 : 1));
			c.optionalFields(2); c.defaults(1, effects.isEmpty() ? 0 : 1);
			c.v(36, 0, 7, 4); c.w(48); // transform view and its empty-default equality
			if (potion.potion().isPresent()) holder(potion.potion().orElseThrow(), BuiltInRegistries.POTION);
			if (potion.customColor().isPresent()) c.numericLeaf(4);
			if (!effects.isEmpty()) {
				c.v(0, 0, 2, 1); c.w(8 + 6L * effects.size()); c.list(effects.size(), 0);
				c.planningList(effects.size()); for (int index = 0; index < effects.size(); index++) effect(effects.get(index));
			}
		} else if (type == DataComponents.SUSPICIOUS_STEW_EFFECTS) {
			var effects = ((SuspiciousStewEffects) value).effects(); c.xmap(); c.list(effects.size(), 0);
			c.planningList(effects.size()); for (int index = 0; index < effects.size(); index++) { var effect = effects.get(index);
				boolean duration = effect.duration() != 160; c.record(2, duration ? 2 : 1); c.defaults(1, duration ? 1 : 0); c.box4();
				holder(effect.effect(), BuiltInRegistries.MOB_EFFECT); if (duration) c.numericLeaf(4);
			}
		} else if (type == DataComponents.WRITABLE_BOOK_CONTENT) {
			var pages = ((WritableBookContent) value).pages(); c.record(1, pages.isEmpty() ? 0 : 1); c.defaults(1, pages.isEmpty() ? 0 : 1); c.emptyListEquality();
			if (!pages.isEmpty()) { c.list(pages.size(), 0); c.planningList(pages.size()); for (int index = 0; index < pages.size(); index++) filter(pages.get(index), false); }
		} else if (type == DataComponents.WRITTEN_BOOK_CONTENT) {
			// The selected page codec is FLAT32767, not CUSTOM_NAME's unbounded
			// FLAT. Oversized encoded pages remain a paid empty-partial error:
			// text.flat -> Filterable MB -> pages LB -> book MB, all adds paid.
			var book = (WrittenBookContent) value; int present = (book.generation() == 0 ? 0 : 1) + (book.pages().isEmpty() ? 0 : 1) + (book.resolved() ? 1 : 0);
			c.record(5, 2 + present); c.defaults(3, present); c.box4(); c.emptyListEquality(); filter(book.title(), false); c.stringLeaf(book.author());
			if (book.generation() != 0) { c.range(); c.numericLeaf(4); } if (book.resolved()) c.booleanLeaf();
			if (!book.pages().isEmpty()) { c.list(book.pages().size(), 0); c.planningList(book.pages().size()); for (int index = 0; index < book.pages().size(); index++) filter(book.pages().get(index), true); }
		} else if (type == DataComponents.TRIM) {
			// ArmorTrim.CODEC defaults its private tooltip flag to true, with no
			// public getter. G3 always runs; charge the false branch's third key,
			// Optional and bool as a finite overcharge when true omits that field.
			// The default's xmap returns only an Optional, not a DataResult.
			var trim = (ArmorTrim) value; c.record(3, 3); c.defaults(1, 1);
			holder(trim.material(), null); holder(trim.pattern(), null); c.booleanLeaf();
		} else if (type == DataComponents.INSTRUMENT) holder((Holder<?>) value, null);
		else if (type == DataComponents.JUKEBOX_PLAYABLE) {
			var jukebox = (JukeboxPlayable) value; c.record(2, jukebox.showInTooltip() ? 1 : 2); c.defaults(1, jukebox.showInTooltip() ? 0 : 1);
			var song = jukebox.song(); c.v(0, 0, song.holder().isPresent() ? 3 : 2, song.holder().isPresent() ? 3 : 2); c.w(16); c.dispatch();
			if (song.holder().isPresent()) holder(song.holder().orElseThrow(), null); else c.resource(song.key().location());
			if (!jukebox.showInTooltip()) c.booleanLeaf();
		} else if (type == DataComponents.RECIPES) {
			var recipes = (List<?>) value; c.list(recipes.size(), 0); c.planningList(recipes.size());
			for (int index = 0; index < recipes.size(); index++) c.resource((ResourceLocation) recipes.get(index));
		} else if (type == DataComponents.NOTE_BLOCK_SOUND) c.resource((ResourceLocation) value);
		else if (type == DataComponents.LODESTONE_TRACKER) {
			var tracker = (LodestoneTracker) value; c.record(2, (tracker.target().isPresent() ? 1 : 0) + (tracker.tracked() ? 0 : 1));
			c.defaults(1, tracker.tracked() ? 0 : 1); c.optionalFields(1); if (!tracker.tracked()) c.booleanLeaf();
			if (tracker.target().isPresent()) { var pos = tracker.target().orElseThrow(); c.record(2, 2); c.resource(pos.dimension().location()); c.intStream(3); c.lifecycle(); }
		} else if (type == DataComponents.FIREWORK_EXPLOSION) explosion((FireworkExplosion) value);
		else if (type == DataComponents.FIREWORKS) {
			var fireworks = (Fireworks) value; boolean flight = fireworks.flightDuration() != 0, explosions = !fireworks.explosions().isEmpty();
			c.record(2, (flight ? 1 : 0) + (explosions ? 1 : 0)); c.box4(); c.defaults(2, (flight ? 1 : 0) + (explosions ? 1 : 0)); c.emptyListEquality();
			if (flight) { c.checked(); c.numericLeaf(1); }
			if (explosions) { c.list(fireworks.explosions().size(), 0); c.planningList(fireworks.explosions().size());
				for (int index = 0; index < fireworks.explosions().size(); index++) explosion(fireworks.explosions().get(index)); }
		} else if (type == DataComponents.PROFILE) profile((ResolvableProfile) value);
		else if (type == DataComponents.BANNER_PATTERNS) {
			var layers = ((BannerPatternLayers) value).layers(); c.xmap(); c.list(layers.size(), 0);
			c.planningList(layers.size()); for (int index = 0; index < layers.size(); index++) { var layer = layers.get(index);
				c.record(2, 2); holder(layer.pattern(), null); c.enumLeaf(layer.color().getSerializedName()); }
		} else if (type == DataComponents.POT_DECORATIONS) {
			var pot = (PotDecorations) value; c.v(55, 0, 33, 9); c.w(144); c.list(4, 0);
			item(pot.back().orElse(Items.BRICK)); item(pot.left().orElse(Items.BRICK)); item(pot.right().orElse(Items.BRICK)); item(pot.front().orElse(Items.BRICK));
		} else if (type == DataComponents.LOCK) { c.xmap(); c.stringLeaf(((net.minecraft.world.LockCode) value).key()); }
		else if (type == DataComponents.CONTAINER_LOOT) {
			var loot = (SeededContainerLoot) value; boolean seed = loot.seed() != 0; c.record(2, seed ? 2 : 1); c.box8(); c.defaults(1, seed ? 1 : 0);
			c.resource(loot.lootTable().location()); if (seed) c.numericLeaf(8);
		} else if (type == DataComponents.RARITY) c.enumLeaf(((Rarity) value).getSerializedName());
		else if (type == DataComponents.BASE_COLOR) c.enumLeaf(((DyeColor) value).getSerializedName());
		else if (type == DataComponents.ENCHANTMENT_GLINT_OVERRIDE) c.booleanLeaf();
		else if (value instanceof net.minecraft.util.Unit) c.map(0);
		else { // Direct INT and the three one-int xmaps. Original static INT has no range checker.
			if (!(value instanceof Integer)) { c.box4(); c.xmap(); }
			c.numericLeaf(4);
		}
		c.result();
	}
	void decoration(MapDecorations.Entry entry) {
		c.record(4, 4); c.box4(); c.box8(); c.box8(); holder(entry.type(), BuiltInRegistries.MAP_DECORATION_TYPE);
		c.numericLeaf(8); c.numericLeaf(8); c.numericLeaf(4); c.result();
	}
	void effect(MobEffectInstance effect) {
		boolean amp = effect.getAmplifier() != 0, duration = effect.getDuration() != 0, ambient = effect.isAmbient(), particles = !effect.isVisible();
		var cures = nativeAccess.effectCures(effect); int n = cures == null ? 0 : cures.size();
		c.graph(2); c.graph(cures == null ? 6 : 7);
		int fields = 2 + (amp ? 1 : 0) + (duration ? 1 : 0) + (ambient ? 1 : 0) + (particles ? 1 : 0) + (n == 0 ? 0 : 1);
		c.keys(fields); c.map(fields); c.box4(); c.box4(); c.defaults(4, fields - 2 - (n == 0 ? 0 : 1)); c.optionalFields(cures == null ? 2 : 3);
		holder(effect.getEffect(), BuiltInRegistries.MOB_EFFECT); c.booleanLeaf();
		if (amp) { c.checked(); c.numericLeaf(1); } if (duration) c.numericLeaf(4); if (ambient) c.booleanLeaf(); if (particles) c.booleanLeaf();
		if (n != 0) {
			c.list(n, 0);
			// Names were certified once; raw set traversal would be a NEW resident
			// table pass. E1's saved materialization descriptor owns that pass.
			for (int index = 0; index < n; index++) { c.xmap(); c.primitive(); c.outputString(); c.w(2); }
		}
	}
	private void explosion(FireworkExplosion explosion) {
		int colors = explosion.colors().size(), fade = explosion.fadeColors().size();
		int emitted = (colors == 0 ? 0 : 1) + (fade == 0 ? 0 : 1) + (explosion.hasTrail() ? 1 : 0) + (explosion.hasTwinkle() ? 1 : 0);
		c.record(5, 1 + emitted); c.defaults(4, emitted); c.v(16, 0, 4, 4); c.w(40); c.enumLeaf(explosion.shape().getSerializedName());
		colors(colors); colors(fade); if (explosion.hasTrail()) c.booleanLeaf(); if (explosion.hasTwinkle()) c.booleanLeaf();
	}
	private void colors(int n) {
		if (n == 0) return;
		c.v(16 + 4L * n, 0, 2L * n + 2, 4L + n); c.w(24 + 12L * n); c.list(n, 1);
		for (int index = 0; index < n; index++) c.numericLeaf(4);
	}
	private void filter(Filterable<?> filter, boolean component) {
		c.either(); c.record(2, filter.filtered().isPresent() ? 2 : 1); c.optionalFields(1);
		if (component) text.flat((Component) filter.raw()); else { c.checked(); c.stringLeaf((String) filter.raw()); }
		if (filter.filtered().isPresent()) {
			if (component) text.flat((Component) filter.filtered().orElseThrow()); else { c.checked(); c.stringLeaf((String) filter.filtered().orElseThrow()); }
		}
	}
	private void profile(ResolvableProfile profile) {
		// This arithmetic walk performs its own field read, separate from the
		// earlier resident-shape certificate and the later native codec call.
		budget.work(InventoryExternalFields.READ_WORK);
		var properties = raw.profile(profile.properties()); int n = properties.size();
		c.either(); c.record(3, (profile.name().isPresent() ? 1 : 0) + (profile.id().isPresent() ? 1 : 0) + (n == 0 ? 0 : 1));
		c.optionalFields(2); c.defaults(1, n == 0 ? 0 : 1); c.v(0, 0, 14, 2); c.w(36);
		if (profile.name().isPresent()) {
			String name = profile.name().orElseThrow(); c.v(52, 0, 20, 7); c.w(96 + 12L * name.length()); c.checked(); c.checked(); c.stringLeaf(name);
		}
		if (profile.id().isPresent()) c.intStream(4);
		if (n != 0) {
			c.v(36, 0, n + 19L, 10); c.w(96 + 12L * n); c.either(); c.list(n, 0);
			c.planningIterator(n);
			for (var property : properties.values()) {
				c.record(3, property.signature() == null ? 2 : 3); c.optional(property.signature() != null); c.optionalFields(1);
				c.stringLeaf(property.name()); c.stringLeaf(property.value()); if (property.signature() != null) c.stringLeaf(property.signature());
			}
		}
	}
	private void holder(Holder<?> holder, Registry<?> builtin) {
		var id = raw.holderKey((Holder.Reference<?>) holder).location();
		if (builtin == null) c.fileHolder(id);
		else c.builtinHolder(id, InventoryRegistryContext.registrationWork(builtin, raw));
	}
	private void item(Item item) {
		// The arithmetic walker performs its OWN registered-ID lookup now. The
		// selected codec's wrapAsHolder lookup remains a later c.w event.
		budget.work(InventoryRegistryContext.valueWork(BuiltInRegistries.ITEM, raw));
		c.w(InventoryRegistryContext.valueWork(BuiltInRegistries.ITEM, raw));
		var id = BuiltInRegistries.ITEM.getKey(item); c.builtinHolder(id, InventoryRegistryContext.registrationWork(BuiltInRegistries.ITEM, raw));
	}
	private void holders(HolderSet<?> set) {
		c.v(0, 0, 10, 6); c.w(40);
		if (InventoryComponentCertificate.nativeNamed(set)) {
			var id = raw.namedKey((HolderSet.Named<?>) set).location(); int n = id.getNamespace().length() + id.getPath().length() + 2;
			c.v(16 + 3L * n, 0, 1, 3); c.w(12 + 3L * n); c.primitive(); c.outputString(); return;
		}
		var contents = raw.directContents((HolderSet.Direct<?>) set); int n = contents.size();
		c.v(16, 0, 2L * n + 3, 5); c.w(32 + 8L * n); c.either();
		if (n != 1) { c.checked(); c.v(16, 0, 1, 1); c.w(12 + 6L * n); c.list(n, 0); }
		c.planningList(n); for (int index = 0; index < n; index++) holder(contents.get(index), null);
	}
}
