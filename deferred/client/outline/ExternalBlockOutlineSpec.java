package nx.pingwheel.common.client.outline;

import java.util.Objects;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;

/** Immutable outline data for a winning provider-owned block marker. */
public record ExternalBlockOutlineSpec(
	MarkerId markerId,
	TargetKey.ExternalBlockKey blockKey,
	Target.ExternalBlockTarget target,
	String targetTypeId,
	String pingTypeId,
	int argbColor
) {
	public ExternalBlockOutlineSpec {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(blockKey, "blockKey");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(targetTypeId, "targetTypeId");
		Objects.requireNonNull(pingTypeId, "pingTypeId");

		if (targetTypeId.isBlank()) {
			throw new IllegalArgumentException("targetTypeId must not be blank");
		}

		if (pingTypeId.isBlank()) {
			throw new IllegalArgumentException("pingTypeId must not be blank");
		}

		if (!target.isCommitted() || !blockKey.equals(TargetKey.from(target))) {
			throw new IllegalArgumentException("external target does not match its key");
		}

		argbColor = 0xFF000000 | (argbColor & 0x00FFFFFF);
	}

	/**
	 * External target equality intentionally ignores its opaque locator because
	 * locator migration does not change marker identity. Render specs must do
	 * the opposite: a migrated locator is a new render payload and must replace
	 * the prior spec in {@link BlockOutlineState}.
	 */
	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}

		if (!(object instanceof ExternalBlockOutlineSpec other)) {
			return false;
		}

		return markerId.equals(other.markerId)
			&& blockKey.equals(other.blockKey)
			&& target.equals(other.target)
			&& target.providerLocator().equals(other.target.providerLocator())
			&& target.hasBlockEntity() == other.target.hasBlockEntity()
			&& targetTypeId.equals(other.targetTypeId)
			&& pingTypeId.equals(other.pingTypeId)
			&& argbColor == other.argbColor;
	}

	@Override
	public int hashCode() {
		return Objects.hash(
			markerId,
			blockKey,
			target,
			target.providerLocator(),
			target.hasBlockEntity(),
			targetTypeId,
			pingTypeId,
			argbColor);
	}
}
