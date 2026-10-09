package nx.pingwheel.common.presentation;

import java.util.List;

/** Stable Basic field manifest shared by the server and client. */
public final class PresentationBasic {
	public static final String ID = "minecraft:basic";
	public static final String NAME = "minecraft:target.name";
	public static final String CUSTOM_NAME = "minecraft:target.custom_name";
	public static final String ENTITY_TYPE = "minecraft:entity.type";
	public static final String HEALTH = "minecraft:entity.health";
	public static final String MAX_HEALTH = "minecraft:entity.max_health";
	public static final String ITEM_ID = "minecraft:item.id";
	public static final String ITEM_COUNT = "minecraft:item.count";
	public static final String ITEM_ICON = "minecraft:item.icon";
	public static final String BLOCK_STATE = "minecraft:block.state";

	private PresentationBasic() {}

	public static List<PresentationField> fields() {
		return List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "name"),
			new PresentationField(CUSTOM_NAME, PresentationField.Kind.TEXT, true, 0, "custom name"),
			new PresentationField(ENTITY_TYPE, PresentationField.Kind.TEXT, true, 0, "entity type"),
			new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "health"),
			new PresentationField(MAX_HEALTH, PresentationField.Kind.NUMBER, true, 0, "maximum health"),
			new PresentationField(ITEM_ID, PresentationField.Kind.TEXT, true, 0, "item"),
			new PresentationField(ITEM_COUNT, PresentationField.Kind.NUMBER, true, 0, "item count"),
			new PresentationField(ITEM_ICON, PresentationField.Kind.FLAG, true, 0, "item icon"),
			new PresentationField(BLOCK_STATE, PresentationField.Kind.RECORD, true, 0, "block state")
		);
	}
}
