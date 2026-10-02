package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;

/** The original target and frozen view; the owner, not a viewer, authorizes reads. */
public record InventorySourceInput(Target.BlockTarget target, UUID readOwner, BlockFace face) {

	public InventorySourceInput {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(readOwner, "readOwner");
		Objects.requireNonNull(face, "face");
	}

	public String viewKey() {
		return "inventory/" + face.name().toLowerCase(java.util.Locale.ROOT) + "/" + readOwner;
	}
}
