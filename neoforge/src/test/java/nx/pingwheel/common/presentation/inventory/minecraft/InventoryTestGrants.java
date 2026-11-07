package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.marker.PingAuthorization;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationGrant;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Explicit test-only content grants for NeoForge inventory fixtures; never used in production. */
@NotNullByDefault
public final class InventoryTestGrants {
	private InventoryTestGrants() {}

	public static PingAuthorization lanes(List<UUID> audience, Map<UUID, Set<InventoryResourceKind>> allowed) {
		Map<UUID, PresentationGrant> grants = new LinkedHashMap<>();
		for (UUID recipient : audience) {
			Set<InventoryResourceKind> kinds = allowed.getOrDefault(recipient, Set.of());
			Map<String, PresentationField.Kind> fields = new LinkedHashMap<>();
			for (InventoryResourceKind kind : kinds) fields.put(kind.fieldId(), PresentationField.Kind.RECORD);
			PresentationGrant grant = fields.isEmpty() ? PresentationGrant.denied()
				: new PresentationGrant(Map.of(InventoryPresentation.ADAPTER_ID,
					new PresentationGrant.AdapterGrant(InventoryPresentation.SCHEMA, fields)), Set.of());
			grants.put(recipient, grant);
		}
		return new PingAuthorization(grants);
	}

	public static PingAuthorization allowAll(List<UUID> audience) {
		Map<UUID, Set<InventoryResourceKind>> allowed = new LinkedHashMap<>();
		for (UUID recipient : audience) allowed.put(recipient, Set.of(InventoryResourceKind.values()));
		return lanes(audience, allowed);
	}

	public static @Unmodifiable Set<InventoryResourceKind> both() {
		return Set.of(InventoryResourceKind.values());
	}

	/** The exact immutable wire content for one canonical marker recipient. */
	public static MarkerContentGrant content(ServerMarker marker, UUID recipient,
		@Nullable InventoryResourceKind trackingKind) {
		return content(marker.authorization(), recipient, trackingKind);
	}

	public static MarkerContentGrant content(PingAuthorization authorization, UUID recipient,
		@Nullable InventoryResourceKind trackingKind) {
		return new MarkerContentGrant(authorization.forRecipient(recipient), trackingKind);
	}
}
