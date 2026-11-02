package nx.pingwheel.common.integration.externalblock;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Server-side seam for providers which expose blocks outside the ordinary
 * world-level block storage.  The core only deals in the opaque locator and
 * the canonical target returned by this interface; provider state remains in
 * the provider implementation.
 */
@NotNullByDefault
public interface ExternalBlockServerProvider {

	String providerId();
	/** Pure upper bound, including cold/failed containing-query discovery; no world/provider callback. */
	default InventoryValidationBudget.Cost membershipCost() { return InventoryValidationBudget.NONE; }
	/** Positive physical membership, independent of target classification or lease materialization. */
	default Membership membership(ServerLevel level, BlockPos position, InventoryValidationBudget.Allowance allowance) {
		java.util.Objects.requireNonNull(level); java.util.Objects.requireNonNull(position);
		java.util.Objects.requireNonNull(allowance).debit(membershipCost()); return Membership.UNKNOWN;
	}
	@NotNullByDefault
	enum MembershipState { OUTSIDE, INSIDE, UNKNOWN }
	@NotNullByDefault
	record Membership(MembershipState state, Optional<String> subLevelId) {
		public static final Membership OUTSIDE = new Membership(MembershipState.OUTSIDE, Optional.empty());
		public static final Membership UNKNOWN = new Membership(MembershipState.UNKNOWN, Optional.empty());
		public Membership {
			java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(subLevelId);
			if ((state == MembershipState.INSIDE) != subLevelId.isPresent()) throw new IllegalArgumentException("physical membership evidence");
			subLevelId.ifPresent(id -> {
				if (id.isBlank() || id.length() > 256 || id.chars().anyMatch(Character::isISOControl))
					throw new IllegalArgumentException("physical membership id bound");
			});
		}
		public static Membership inside(String id) { return new Membership(MembershipState.INSIDE, Optional.of(id)); }
	}

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

	/**
	 * Observes the current authoritative local block for a committed target
	 * without acquiring, releasing, or migrating provider state.
	 */
	default ObservationResult observeBlock(ServerLevel level, Target.ExternalBlockTarget committed) {
		return new ObservationResult.TemporarilyUnavailable();
	}

	/** Candidate-only synchronous read. Must never materialize, acquire, or mutate provider state. */
	default Optional<ResolvedBlockReadSource> resolvePreviewReadSource(
		ServerLevel level, Target.ExternalBlockTarget candidate
	) {
		return Optional.empty();
	}

	/** Read an existing active stable reference's current physical binding without changing its lease. */
	default Optional<ResolvedBlockReadSource> resolveCommittedReadSource(
		ServerLevel level, Target.ExternalBlockTarget committed
	) {
		return Optional.empty();
	}

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

	record BlockObservation(Object level, BlockPos position, BlockState state) {
		public BlockObservation {
			java.util.Objects.requireNonNull(level, "level");
			java.util.Objects.requireNonNull(position, "position");
			java.util.Objects.requireNonNull(state, "state");
		}
	}

	sealed interface ObservationResult permits ObservationResult.Available,
		ObservationResult.TemporarilyUnavailable, ObservationResult.Invalid {
		record Available(BlockObservation observation) implements ObservationResult {
			public Available {
				java.util.Objects.requireNonNull(observation, "observation");
			}
		}

		record TemporarilyUnavailable() implements ObservationResult {
		}

		record Invalid() implements ObservationResult {
		}
	}
}
