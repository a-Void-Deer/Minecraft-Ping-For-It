package nx.pingwheel.common.presentation.inventory;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationAdapter;

/**
 * The original target and frozen view; the owner, not a viewer, authorizes
 * reads. An ordinary block keeps its captured block identity and an external
 * candidate or committed target keeps its provider identity: the
 * provider-confirmed physical read binding is resolved separately and never
 * replaces the original target.
 */
public record InventorySourceInput(Target target, UUID readOwner, BlockFace face) {

	public InventorySourceInput {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(readOwner, "readOwner");
		Objects.requireNonNull(face, "face");
		if (!(target instanceof Target.BlockTarget) && !(target instanceof Target.ExternalBlockTarget)) {
			throw new IllegalArgumentException("inventory source requires a block or external block target");
		}
	}

	/** Existing ordinary block call sites keep their exact constructor. */
	public InventorySourceInput(Target.BlockTarget target, UUID readOwner, BlockFace face) {
		this((Target) target, readOwner, face);
	}

	public String viewKey() {
		return "inventory/" + face.name().toLowerCase(Locale.ROOT) + "/" + readOwner;
	}

	/** The original ordinary block target; empty for an external target. */
	public Optional<Target.BlockTarget> ordinaryTarget() {
		return target instanceof Target.BlockTarget block ? Optional.of(block) : Optional.empty();
	}

	/**
	 * Binding comparison for runtime input shortcuts and invalid retirement.
	 * Owner and frozen face remain read-sharing boundaries. Ordinary blocks
	 * compare their full captured identity. External candidates
	 * compare their locator too: two candidates with the same identity quartet
	 * but different locators are different physical objects, so an equals-only
	 * comparison would collide. A committed external target compares stable
	 * identity only, because a provider locator refresh does not create a new
	 * binding and the read resolver follows the current tracking point.
	 */
	public boolean sameBinding(InventorySourceInput other) {
		return other != null && readOwner.equals(other.readOwner) && face == other.face && sameBinding(target, other.target);
	}

	public static boolean sameBinding(Target first, Target second) {
		if (first == null || second == null || !first.equals(second)) return false;
		if (first instanceof Target.ExternalBlockTarget external
			&& second instanceof Target.ExternalBlockTarget other) {
			return external.isCommitted() && other.isCommitted()
				|| external.providerLocator().equals(other.providerLocator());
		}
		return true;
	}

	/**
	 * True when a detached request describes this input's original target
	 * binding. External coordinates are placeholders and never physical
	 * identity, so the detached external identity is compared instead.
	 */
	public boolean matchesDetached(PresentationAdapter.DetachedTarget detached) {
		if (detached == null) return false;
		if (target instanceof Target.ExternalBlockTarget) {
			Target.ExternalBlockTarget external = detached.externalBlock();
			return external != null && sameBinding(target, external);
		}
		if (!(target instanceof Target.BlockTarget block)) return false;
		return block.dimensionId().equals(detached.dimension())
			&& block.blockRegistryId().equals(detached.registryId())
			&& block.x() == detached.x() && block.y() == detached.y() && block.z() == detached.z();
	}
}
