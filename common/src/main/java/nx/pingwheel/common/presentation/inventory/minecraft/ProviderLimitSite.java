package nx.pingwheel.common.presentation.inventory.minecraft;

import java.time.Duration;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.jetbrains.annotations.NotNullByDefault;

/** Finite native guard sites; each owns independent diagnostic admission. */
@NotNullByDefault
public enum ProviderLimitSite {
	FABRIC_FLUID_ADDRESSES("fabric-fluid-addresses", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	FABRIC_FLUID_CLASS("fabric-fluid-class", InventoryFailureReason.ENTRY_LIMIT),
	FABRIC_FLUID_REGISTRY_ID("fabric-fluid-registry-id", InventoryFailureReason.ENTRY_LIMIT),
	FABRIC_FLUID_UNDERLYING_DEPTH("fabric-fluid-underlying-depth", InventoryFailureReason.NATIVE_DEPTH_LIMIT),
	FORGE_FLUID_ADDRESSES("forge-fluid-addresses", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	FORGE_FLUID_CLASS("forge-fluid-class", InventoryFailureReason.ENTRY_LIMIT),
	FORGE_FLUID_REGISTRY_ID("forge-fluid-registry-id", InventoryFailureReason.ENTRY_LIMIT),
	NEOFORGE_FLUID_ADDRESSES("neoforge-fluid-addresses", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	NEOFORGE_FLUID_REGISTRY_ID("neoforge-fluid-registry-id", InventoryFailureReason.ENTRY_LIMIT),
	FLUID_PROVIDER_CLASS("fluid-provider-class", InventoryFailureReason.ENTRY_LIMIT),
	FLUID_SOURCE_REGISTRY_ID("fluid-source-registry-id", InventoryFailureReason.ENTRY_LIMIT),
	FLUID_ROUTE_DESCRIPTION("fluid-route-description", InventoryFailureReason.SNAPSHOT_LIMIT),
	FLUID_CAPACITY_DESCRIPTION("fluid-capacity-description", InventoryFailureReason.SNAPSHOT_LIMIT),
	FLUID_SHAPE_MEMBERS("fluid-shape-members", InventoryFailureReason.SNAPSHOT_LIMIT),
	FLUID_SHAPE_ADDRESSES("fluid-shape-addresses", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	CONTAINER_LOCAL_SLOTS("container-local-slots", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	CONTAINER_SIDED_MAPPING("container-sided-mapping", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	CONTAINER_VISIBLE_SLOTS("container-visible-slots", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	SNAPSHOT_MEMBERS("snapshot-members", InventoryFailureReason.SNAPSHOT_LIMIT),
	SNAPSHOT_VISIBLE_SLOTS("snapshot-visible-slots", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	SNAPSHOT_SEGMENTS("snapshot-segments", InventoryFailureReason.SNAPSHOT_LIMIT),
	SNAPSHOT_LOCAL_SLOTS("snapshot-local-slots", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	SNAPSHOT_MAPPING("snapshot-mapping", InventoryFailureReason.SNAPSHOT_LIMIT),
	SNAPSHOT_LAYOUT_ID("snapshot-layout-id", InventoryFailureReason.ENTRY_LIMIT),
	SNAPSHOT_LAYOUT_DATA("snapshot-layout-data", InventoryFailureReason.SNAPSHOT_LIMIT),
	SNAPSHOT_BLOCK_ID("snapshot-block-id", InventoryFailureReason.ENTRY_LIMIT),
	SNAPSHOT_ENTITY_ID("snapshot-entity-id", InventoryFailureReason.ENTRY_LIMIT),
	SNAPSHOT_ROLE("snapshot-role", InventoryFailureReason.ENTRY_LIMIT),
	SNAPSHOT_FIELD_PATH("snapshot-field-path", InventoryFailureReason.ENTRY_LIMIT),
	SNAPSHOT_SCHEMA_ID("snapshot-schema-id", InventoryFailureReason.ENTRY_LIMIT),
	REFLECTION_TYPE_NAME("reflection-type-name", InventoryFailureReason.ENTRY_LIMIT),
	REFLECTION_MEMBER_NAME("reflection-member-name", InventoryFailureReason.ENTRY_LIMIT),
	REFLECTION_PARAMETERS("reflection-parameters", InventoryFailureReason.NATIVE_ARRAY_LIMIT),
	REFLECTION_INHERITANCE("reflection-inheritance", InventoryFailureReason.NATIVE_DEPTH_LIMIT),
	VAULT_OWNERSHIP_DEPTH("vault-ownership-depth", InventoryFailureReason.NATIVE_DEPTH_LIMIT),
	VAULT_WIDTH("vault-width", InventoryFailureReason.SNAPSHOT_LIMIT),
	VAULT_LENGTH("vault-length", InventoryFailureReason.SNAPSHOT_LIMIT),
	VAULT_SLOTS("vault-slots", InventoryFailureReason.PHYSICAL_SLOT_LIMIT),
	TANK_WIDTH("tank-width", InventoryFailureReason.SNAPSHOT_LIMIT),
	TANK_MEMBERS("tank-members", InventoryFailureReason.SNAPSHOT_LIMIT),
	FLUID_ADMISSION_GRANTS("fluid-admission-grants", InventoryFailureReason.ENTRY_LIMIT);

	private final String code;
	private final InventoryFailureReason reason;
	private final DiagnosticChannel channel;
	ProviderLimitSite(String code, InventoryFailureReason reason) {
		this.code = code; this.reason = reason;
		channel = DiagnosticChannel.interval("inventory-provider/" + code, Duration.ofSeconds(10));
	}
	public String code() { return code; }
	public InventoryFailureReason reason() { return reason; }
	DiagnosticChannel channel() { return channel; }
}
