package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetType;

/**
 * An immutable, server-authoritative active ping.
 *
 * <p>Ownership ({@link #owner()}), target identity ({@link #target()}), resolved
 * classification ({@link #targetType()}), and selected presentation
 * ({@link #pingType()}) are all carried explicitly. The marker derives its
 * {@link TargetKey} from {@link #target()} rather than accepting one, so the
 * same-target winner comparison always operates on the authoritative target
 * identity.
 */
public record ServerMarker(
	MarkerId id,
	UUID owner,
	Target target,
	TargetType targetType,
	PingType pingType,
	MarkerAnchor anchor,
	long arrivalTick,
	long expiresAtTick,
	List<UUID> recipients
) {

	public ServerMarker {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(targetType, "targetType");
		Objects.requireNonNull(pingType, "pingType");
		Objects.requireNonNull(anchor, "anchor");
		// Server markers are committed state; an external C2S candidate has no
		// stable key until its provider has authoritatively resolved it.
		TargetKey.from(target);

		if (arrivalTick < 0L) {
			throw new IllegalArgumentException("arrivalTick must be non-negative: " + arrivalTick);
		}

		if (expiresAtTick <= arrivalTick) {
			throw new IllegalArgumentException(
				"expiresAtTick must be greater than arrivalTick: " + expiresAtTick + " <= " + arrivalTick);
		}

		if (!targetType.pingTypes().contains(pingType)) {
			throw new IllegalArgumentException(
				"pingType must be a member of targetType.pingTypes(): " + pingType.id());
		}

		// The target's broad category must match the resolved target type's
		// category. The dropped_item target type is an ENTITY target type, so
		// dropped-item markers (also ENTITY) satisfy this naturally; no special
		// case is required.
		if (target.kind() != targetType.kind()) {
			throw new IllegalArgumentException(
				"target kind " + target.kind() + " must match targetType kind " + targetType.kind());
		}

		recipients = normalizeRecipients(Objects.requireNonNull(recipients, "recipients"));
	}

	/**
	 * The stable, complete target identity this marker refers to.
	 */
	public TargetKey targetKey() {
		return TargetKey.from(target);
	}

	/**
	 * Sorts recipients into natural {@link UUID} order and removes duplicates,
	 * producing an immutable, non-empty list.
	 */
	private static List<UUID> normalizeRecipients(List<UUID> recipients) {
		List<UUID> normalized = recipients.stream()
			.map(uuid -> Objects.requireNonNull(uuid, "recipient"))
			.sorted()
			.distinct()
			.toList();

		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("recipients must not be empty");
		}

		return normalized;
	}
}
