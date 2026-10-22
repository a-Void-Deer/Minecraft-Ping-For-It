package nx.pingwheel.common.integration.externalblock;

import java.util.Objects;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;

/** Detached read binding. Physical storage coordinates never replace the original target identity. */
public record BlockReadSource(Target target, String providerId, String subLevelId,
	Target.BlockTarget blockTarget, MarkerAnchor validationAnchor) {
	public static final String MINECRAFT_PROVIDER_ID = "minecraft";

	public BlockReadSource {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(providerId, "providerId");
		Objects.requireNonNull(subLevelId, "subLevelId");
		Objects.requireNonNull(blockTarget, "blockTarget");
		Objects.requireNonNull(validationAnchor, "validationAnchor");
		if (!target.dimensionId().equals(blockTarget.dimensionId())) {
			throw new IllegalArgumentException("read source dimension mismatch");
		}
		if (target instanceof Target.ExternalBlockTarget external) {
			if (!external.providerId().equals(providerId) || subLevelId.isBlank()
				|| subLevelId.length() > Target.ExternalBlockTarget.MAX_IDENTIFIER_LENGTH
				|| !external.expectedBlockRegistryId().equals(blockTarget.blockRegistryId())) {
				throw new IllegalArgumentException("external read source scope or identity mismatch");
			}
		} else if (!(target instanceof Target.BlockTarget block) || !block.equals(blockTarget)
			|| !MINECRAFT_PROVIDER_ID.equals(providerId) || !subLevelId.isEmpty()) {
			throw new IllegalArgumentException("ordinary read source identity mismatch");
		}
	}

	public String dimensionId() { return target.dimensionId(); }

	/** Capture/read-context comparison, deliberately stricter than external Target equality. */
	public static boolean sameTargetBinding(Target first, Target second) {
		return first != null && first.equals(second)
			&& (!(first instanceof Target.ExternalBlockTarget external)
				|| second instanceof Target.ExternalBlockTarget other
					&& external.providerLocator().equals(other.providerLocator()));
	}
}
