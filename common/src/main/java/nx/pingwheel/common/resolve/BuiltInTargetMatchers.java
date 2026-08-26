package nx.pingwheel.common.resolve;

import java.util.Optional;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;

/**
 * The built-in matcher bindings for the confirmed target type catalog.
 *
 * <p>Binding is by target type id, in line with the fixed declaration order in
 * {@link nx.pingwheel.common.domain.TargetTypeCatalog#builtIn()}:
 * <ul>
 *   <li>{@code dropped_item} matches an entity target whose entity type id is
 *       {@code minecraft:item};</li>
 *   <li>{@code entity} matches any entity target;</li>
 *   <li>{@code entity_block} matches a block target whose captured block
 *       actually owns a Minecraft {@code BlockEntity}; an unknown/absent
 *       classification fails soft and resolves as the generic
 *       {@code block};</li>
 *   <li>{@code block} matches any block target;</li>
 *   <li>{@code location} matches any location target.</li>
 * </ul>
 *
 * <p>Because {@code dropped_item} has priority 100 and generic {@code entity}
 * has priority 200, a dropped item resolves as {@code dropped_item} rather than
 * the generic entity type; because {@code entity_block} has priority 250 and
 * generic {@code block} has priority 300, a BlockEntity-owning block resolves
 * as {@code entity_block} rather than the generic block type. The
 * {@code entity_block} classification is derived by the client capture and by
 * the authoritative server from their own game state through
 * {@link BlockEntityClassification}; it is never supplied by the client over
 * the wire.
 */
public final class BuiltInTargetMatchers {

	private static final String MINECRAFT_ITEM = "minecraft:item";

	private BuiltInTargetMatchers() {}

	/**
	 * The matcher registry for the confirmed built-in catalog.
	 */
	public static TargetMatcherRegistry registry() {
		return TargetMatcherRegistry.builder()
			.bind("dropped_item",
				(Target target, TargetMatchContext context) ->
					target instanceof Target.EntityTarget
						&& context.entityTypeId().equals(Optional.of(MINECRAFT_ITEM)))
			.bind("entity",
				(Target target, TargetMatchContext context) -> target instanceof Target.EntityTarget)
			.bind("entity_block",
				(Target target, TargetMatchContext context) ->
					isBlockTarget(target)
						&& hasBlockEntityClassification(target, context))
			.bind("block",
				(Target target, TargetMatchContext context) -> isBlockTarget(target))
			.bind("location",
				(Target target, TargetMatchContext context) -> target instanceof Target.LocationTarget)
			.build();
	}

	private static boolean isBlockTarget(Target target) {
		return target instanceof Target.BlockTarget || target instanceof Target.ExternalBlockTarget;
	}

	private static boolean hasBlockEntityClassification(Target target, TargetMatchContext context) {
		return context.blockHasBlockEntity().orElseGet(() ->
			target instanceof Target.ExternalBlockTarget external && external.hasBlockEntity());
	}
}
