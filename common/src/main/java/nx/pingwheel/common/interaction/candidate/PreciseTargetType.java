package nx.pingwheel.common.interaction.candidate;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.TargetSnapshot;

/** Fixed catalog priority order. A broad slot never relabels a candidate's canonical type. */
public enum PreciseTargetType {
	DROPPED_ITEM("dropped_item"), ENTITY("entity"), ENTITY_BLOCK("entity_block"),
	BLOCK("block"), LOCATION("location");

	private final String targetTypeId;
	PreciseTargetType(String targetTypeId) { this.targetTypeId = targetTypeId; }
	public String targetTypeId() { return targetTypeId; }

	public boolean matches(ResolvedTarget resolved) {
		return switch (this) {
			case DROPPED_ITEM -> resolved.target() instanceof Target.EntityTarget
				&& resolved.targetType().id().equals("dropped_item");
			case ENTITY -> resolved.target() instanceof Target.EntityTarget;
			case ENTITY_BLOCK -> isBlock(resolved.target()) && resolved.targetType().id().equals("entity_block");
			case BLOCK -> isBlock(resolved.target());
			case LOCATION -> resolved.target() instanceof Target.LocationTarget;
		};
	}

	boolean matches(TargetSnapshot snapshot) {
		return switch (this) {
			case DROPPED_ITEM -> snapshot.target() instanceof Target.EntityTarget
				&& snapshot.matchContext().entityTypeId().filter("minecraft:item"::equals).isPresent();
			case ENTITY -> snapshot.target() instanceof Target.EntityTarget;
			case ENTITY_BLOCK -> isBlock(snapshot.target()) && snapshot.matchContext().blockHasBlockEntity()
				.orElseGet(() -> snapshot.target() instanceof Target.ExternalBlockTarget external && external.hasBlockEntity());
			case BLOCK -> isBlock(snapshot.target());
			case LOCATION -> snapshot.target() instanceof Target.LocationTarget;
		};
	}

	private static boolean isBlock(Target target) {
		return target instanceof Target.BlockTarget || target instanceof Target.ExternalBlockTarget;
	}
}
