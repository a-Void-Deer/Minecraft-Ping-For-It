package nx.pingwheel.common.integration.externalblock;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.marker.MarkerAnchor;

/**
 * Server-side seam for providers which expose blocks outside the ordinary
 * world-level block storage.  The core only deals in the opaque locator and
 * the canonical target returned by this interface; provider state remains in
 * the provider implementation.
 */
public interface ExternalBlockServerProvider {

	String providerId();

	/**
	 * Checks a candidate against live provider state without allocating
	 * provider-side tracking state.  This is called before target/ping-type
	 * admission and therefore must be safe to call for every untrusted request.
	 */
	ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate);

	/**
	 * Creates or acquires provider-side state for an already validated
	 * candidate.  The returned materialization is owned by the caller until the
	 * marker is stored; callers must release it on any later create failure.
	 */
	MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate);

	/**
	 * Refreshes a committed target without changing its marker identity.  An
	 * unavailable observation is deliberately represented separately from an
	 * invalid target so callers can retain the marker during provider loading.
	 */
	RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed);

	/** Resolves a current server-side name for a candidate or committed target. */
	Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget target);

	/**
	 * Receives the authoritative range observation for a validated external
	 * target. Providers normally ignore this hook; optional adapters may use it
	 * for detailed diagnostics without making the core depend on provider APIs.
	 */
	default void observeValidationDistance(
		ServerLevel level,
		Target.ExternalBlockTarget target,
		MarkerAnchor anchor,
		double distance,
		boolean withinRange
	) {
	}

	/** Releases one marker's reference to the committed provider target. */
	void release(MinecraftServer server, Target.ExternalBlockTarget committed);

	/**
	 * Releases one marker reference while optionally exposing its marker id to a
	 * diagnostics-capable provider. Existing providers only need the two-argument
	 * contract.
	 */
	default void release(
		MinecraftServer server, Target.ExternalBlockTarget committed, String markerId
	) {
		release(server, committed);
	}

	/** Releases any defensive provider state left for a server being replaced. */
	default void close(MinecraftServer server) {
	}

	record ValidatedTarget(
		Target.ExternalBlockTarget target,
		TargetMatchContext matchContext,
		MarkerAnchor anchor
	) {
		public ValidatedTarget {
			java.util.Objects.requireNonNull(target, "target");
			java.util.Objects.requireNonNull(matchContext, "matchContext");
			java.util.Objects.requireNonNull(anchor, "anchor");
		}
	}

	sealed interface ValidationResult permits ValidationResult.Accepted, ValidationResult.TemporarilyUnavailable,
		ValidationResult.Invalid {

		record Accepted(ValidatedTarget target) implements ValidationResult {
		}

		record TemporarilyUnavailable() implements ValidationResult {
		}

		record Invalid() implements ValidationResult {
		}
	}

	record MaterializedTarget(
		Target.ExternalBlockTarget target,
		TargetMatchContext matchContext,
		MarkerAnchor anchor
	) {
		public MaterializedTarget {
			java.util.Objects.requireNonNull(target, "target");
			java.util.Objects.requireNonNull(matchContext, "matchContext");
			java.util.Objects.requireNonNull(anchor, "anchor");
		}
	}

	sealed interface MaterializationResult permits MaterializationResult.Materialized,
		MaterializationResult.TemporarilyUnavailable, MaterializationResult.Invalid {

		record Materialized(MaterializedTarget target) implements MaterializationResult {
		}

		record TemporarilyUnavailable() implements MaterializationResult {
		}

		record Invalid() implements MaterializationResult {
		}
	}

	record ExternalBlockName(Component vanillaName, Optional<Component> customName) {
		public ExternalBlockName {
			java.util.Objects.requireNonNull(vanillaName, "vanillaName");
			java.util.Objects.requireNonNull(customName, "customName");
			customName.ifPresent(name -> java.util.Objects.requireNonNull(name, "customName"));
		}
	}

	sealed interface RefreshResult permits RefreshResult.Available, RefreshResult.TemporarilyUnavailable,
		RefreshResult.Invalid {

		record Available(
			Target.ExternalBlockTarget target,
			TargetMatchContext matchContext,
			MarkerAnchor anchor
		) implements RefreshResult {
			public Available {
				java.util.Objects.requireNonNull(target, "target");
				java.util.Objects.requireNonNull(matchContext, "matchContext");
				java.util.Objects.requireNonNull(anchor, "anchor");
			}
		}

		record TemporarilyUnavailable() implements RefreshResult {
		}

		record Invalid() implements RefreshResult {
		}
	}
}
