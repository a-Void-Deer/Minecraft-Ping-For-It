package nx.pingwheel.common.presentation.preview.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.MinecraftEntityTargetAdapter;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;

/** Explicit vanilla synchronized fields only. Each getter is behind authorized demand. */
public final class MinecraftPreviewFieldAccess implements PreviewFieldAccess {
	/** Transient client-thread world access; no world objects enter the resulting projection. */
	public interface WorldContext extends ReadContext {
		Entity entity(EntityLocator locator);
		/** Real received chunk, expected registry identity, and no pending prediction, or null. */
		BlockState blockState(Target.BlockTarget target);
		BlockEntity blockEntity(Target.BlockTarget target);
		String encodeName(Component name);
	}
	@Override public String adapterId() { return PresentationBasic.ID; }
	@Override public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) {
		Map<String, Outcome> result = new LinkedHashMap<>();
		for (String field : demand) result.put(field, Missing.UNAVAILABLE);
		if (!(context instanceof WorldContext world) || !target.dimensionId().equals(context.dimensionId())) return Map.copyOf(result);
		if (demand.isEmpty()) return Map.of();
		if (target instanceof Target.EntityTarget entityTarget) {
			Set<String> entityFields = Set.of(PresentationBasic.NAME, PresentationBasic.ENTITY_TYPE,
				PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH, PresentationBasic.ITEM_ID,
				PresentationBasic.ITEM_COUNT, PresentationBasic.ITEM_ICON);
			boolean applicable = false;
			for (String field : demand) {
				if (entityFields.contains(field)) applicable = true;
				else result.put(field, Missing.NOT_APPLICABLE);
			}
			if (!applicable) return Map.copyOf(result);
			Entity entity = world.entity(entityTarget.locator());
			if (entity == null || entity.isRemoved()) return Map.copyOf(result);
			entity = MinecraftEntityTargetAdapter.canonicalEntity(entity);
			if (!MinecraftEntityTargetAdapter.locatorFor(entity).equals(entityTarget.locator())) return Map.copyOf(result);
			result.putAll(observeEntity(entity, demand, context.tick(), world::encodeName));
		} else if (target instanceof Target.BlockTarget block) {
			for (String field : demand) if (!PresentationBasic.NAME.equals(field) && !PresentationBasic.BLOCK_STATE.equals(field))
				result.put(field, Missing.NOT_APPLICABLE);
			if (!demand.contains(PresentationBasic.NAME) && !demand.contains(PresentationBasic.BLOCK_STATE)) return Map.copyOf(result);
			BlockState state = world.blockState(block);
			if (state == null || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block.blockRegistryId())) return Map.copyOf(result);
			if (demand.contains(PresentationBasic.BLOCK_STATE)) {
				Map<String, PresentationValue> values = new LinkedHashMap<>();
				state.getValues().forEach((property, value) -> values.put(property.getName(), new PresentationValue.Text(value.toString())));
				put(result, PresentationBasic.BLOCK_STATE, () -> new PresentationValue.RecordValue(values), context.tick());
			}
			// Generic block-entity existence/null name proves neither receipt nor absence of a custom name.
			if (demand.contains(PresentationBasic.NAME) && !state.hasBlockEntity())
				put(result, PresentationBasic.NAME, () -> new PresentationValue.Text(world.encodeName(state.getBlock().getName())), context.tick());
		} else if (target instanceof Target.LocationTarget) {
			for (String field : demand) result.put(field, Missing.NOT_APPLICABLE);
			if (demand.contains(PresentationBasic.NAME))
				put(result, PresentationBasic.NAME, () -> new PresentationValue.Text(world.encodeName(TargetNameComposer.here())), context.tick());
		}
		return Map.copyOf(result);
	}
	/** Real entity seam shared by the live reader and headless tests, not a synthetic preview fixture. */
	public static Map<String, Outcome> observeEntity(Entity entity, Set<String> demand, long tick, Function<Component, String> encode) {
		Map<String, Outcome> result = new LinkedHashMap<>();
		for (String field : demand) result.put(field, Missing.NOT_APPLICABLE);
		if (demand.contains(PresentationBasic.ENTITY_TYPE))
			put(result, PresentationBasic.ENTITY_TYPE, () -> new PresentationValue.Text(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()), tick);
		if (entity instanceof LivingEntity living) {
			if (demand.contains(PresentationBasic.HEALTH)) put(result, PresentationBasic.HEALTH, () -> new PresentationValue.NumberValue(living.getHealth()), tick);
			if (demand.contains(PresentationBasic.MAX_HEALTH)) put(result, PresentationBasic.MAX_HEALTH, () -> new PresentationValue.NumberValue(living.getMaxHealth()), tick);
		}
		if (entity instanceof ItemEntity item) {
			if (demand.contains(PresentationBasic.ITEM_ID) || demand.contains(PresentationBasic.ITEM_COUNT)
				|| demand.contains(PresentationBasic.ITEM_ICON) || demand.contains(PresentationBasic.NAME)) {
				for (String field : Set.of(PresentationBasic.ITEM_ID, PresentationBasic.ITEM_COUNT, PresentationBasic.ITEM_ICON, PresentationBasic.NAME))
					if (demand.contains(field)) result.put(field, Missing.UNAVAILABLE);
				var stack = item.getItem();
				if (!stack.isEmpty()) {
					if (demand.contains(PresentationBasic.ITEM_ID)) put(result, PresentationBasic.ITEM_ID,
						() -> new PresentationValue.Text(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()), tick);
					if (demand.contains(PresentationBasic.ITEM_COUNT)) put(result, PresentationBasic.ITEM_COUNT, () -> new PresentationValue.NumberValue(stack.getCount()), tick);
					if (demand.contains(PresentationBasic.ITEM_ICON)) put(result, PresentationBasic.ITEM_ICON, () -> new PresentationValue.Flag(true), tick);
					if (demand.contains(PresentationBasic.NAME)) put(result, PresentationBasic.NAME, () -> {
						Component base = Component.translatable(stack.getDescriptionId()), custom = stack.get(DataComponents.CUSTOM_NAME);
						return new PresentationValue.Text(encode.apply(custom == null ? base : TargetNameComposer.compose(custom, base)));
					}, tick);
				}
			}
		} else if (demand.contains(PresentationBasic.NAME)) put(result, PresentationBasic.NAME, () -> {
			if (entity instanceof Player player) return new PresentationValue.Text(encode.apply(Component.literal(player.getGameProfile().getName())));
			Component base = entity.getType().getDescription(), custom = entity.getCustomName();
			return new PresentationValue.Text(encode.apply(custom == null ? base : TargetNameComposer.compose(custom, base)));
		}, tick);
		return Map.copyOf(result);
	}
	private static void put(Map<String, Outcome> result, String id, java.util.function.Supplier<PresentationValue> getter, long tick) {
		try { result.put(id, new Observed(new PreviewObservation(getter.get(), PreviewObservation.Origin.CLIENT_SYNCED, tick, false))); }
		catch (RuntimeException | LinkageError unavailable) { result.put(id, Missing.UNAVAILABLE); }
	}
}
