package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.EntityLocator;

/**
 * Pure policy used by the Minecraft server entity lookup adapter.
 *
 * <p>Runtime ids are accepted only for experience orbs. The state classifier
 * is intentionally Minecraft-free so missing, wrong-type, and gone lookup
 * behavior can be tested without constructing a server world.
 */
public final class ServerEntityLocatorPolicy {

	private ServerEntityLocatorPolicy() {}

	public enum Outcome {
		ACCEPTED("accepted"),
		MISSING("missing"),
		DISALLOWED_TYPE("disallowed_type"),
		GONE("gone");

		private final String tag;

		Outcome(String tag) {
			this.tag = tag;
		}

		public String tag() {
			return tag;
		}
	}

	/**
	 * Classifies one already-performed server lookup without exposing any
	 * Minecraft class in the policy seam.
	 */
	public static Outcome classify(
		EntityLocator requested,
		boolean present,
		boolean alive,
		boolean removed,
		boolean experienceOrb
	) {
		Objects.requireNonNull(requested, "requested");

		if (!present) {
			return Outcome.MISSING;
		}

		if (requested instanceof EntityLocator.RuntimeId && !experienceOrb) {
			return Outcome.DISALLOWED_TYPE;
		}

		if (!alive || removed) {
			return Outcome.GONE;
		}

		return Outcome.ACCEPTED;
	}

	/**
	 * Normalizes an accepted entity from authoritative state. Orbs retain their
	 * synchronized runtime id; all ordinary entities use the actual UUID.
	 */
	public static EntityLocator normalize(
		boolean experienceOrb,
		UUID actualUuid,
		int actualRuntimeId
	) {
		Objects.requireNonNull(actualUuid, "actualUuid");

		return experienceOrb
			? EntityLocator.runtimeId(actualRuntimeId)
			: EntityLocator.uuid(actualUuid);
	}
}
