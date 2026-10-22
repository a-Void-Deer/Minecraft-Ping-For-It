package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;

/**
 * Collected once at capture start, then carried into the same token's ordinary completion.
 * Finalization performs no world/provider access. DH may supply the final ordinary hit/location.
 */
public record FrozenCandidateAcquisition(InteractionToken token, CapturedRay ray, double pingDistance,
	WorldVector nativeOrdinaryPoint, List<CandidateEvidence> evidence, Set<PreciseTargetType> certifiedTypes) {
	public FrozenCandidateAcquisition {
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(ray, "ray");
		Objects.requireNonNull(nativeOrdinaryPoint, "nativeOrdinaryPoint");
		evidence = List.copyOf(evidence);
		certifiedTypes = Set.copyOf(certifiedTypes);
		if (!Double.isFinite(pingDistance) || pingDistance < 0 || evidence.size() > 8) {
			throw new IllegalArgumentException("invalid bounded acquisition");
		}
		for (CandidateEvidence value : evidence) {
			double actualDistance = distance(ray.origin(), value.hit().worldHit());
			if (actualDistance > pingDistance || Math.abs(actualDistance - value.distance()) > 1.0E-9) {
				throw new IllegalArgumentException("supplement outside Ping range or inconsistent surface distance");
			}
		}
	}

	public boolean belongsTo(InteractionToken expectedToken, CapturedRay expectedRay) {
		return token == expectedToken && ray.equals(expectedRay);
	}

	public FrozenCandidateSet finish(TargetSnapshot ordinarySnapshot, ResolvedTarget ordinaryResolved,
		TargetResolver resolver) {
		CandidateHit ordinaryHit = ordinarySnapshot.candidateHit().orElseGet(() -> new CandidateHit(
			ordinaryResolved.target() instanceof Target.LocationTarget location
				? new WorldVector(location.x(), location.y(), location.z()) : nativeOrdinaryPoint,
			CaptureEquivalenceKey.nativeTarget(ordinaryResolved.target())));
		if (!sameIdentity(ordinarySnapshot.target(), ordinaryResolved.target())) {
			throw new IllegalArgumentException("ordinary resolution changed captured identity");
		}
		TargetSnapshot ordinaryContact = ordinarySnapshot;
		double ordinaryDistance = distance(ray.origin(), ordinaryHit.worldHit());
		List<Candidate> supplements = new ArrayList<>();
		int id = 1;
		for (CandidateEvidence value : evidence) {
			if (value.hit().equivalenceKey().equals(ordinaryHit.equivalenceKey())) {
				// The supplemental scan may re-contact the ordinary identity at a nearer
				// actual point. The installed candidate then takes that contact's own
				// geometry/face provenance, while the canonical owner type and resolution
				// stay the ordinary ones; an equal or farther re-contact is not installed.
				double actualDistance = distance(ray.origin(), value.hit().worldHit());
				if (actualDistance < ordinaryDistance) {
					ordinaryDistance = actualDistance;
					ordinaryHit = value.hit();
					ordinaryContact = value.snapshot();
				}
				continue;
			}
			if (!value.snapshot().target().dimensionId().equals(ordinaryResolved.target().dimensionId())) {
				throw new IllegalArgumentException("supplement belongs to a different dimension");
			}
			ResolvedTarget resolved = resolver.resolve(value.snapshot().target(), value.snapshot().matchContext());
			if (!sameIdentity(value.snapshot().target(), resolved.target())) {
				throw new IllegalArgumentException("supplement resolution changed captured identity");
			}
			supplements.add(candidate(id++, value.snapshot(), resolved, value.hit()));
		}
		Candidate ordinary = candidate(0, ordinaryContact, ordinaryResolved, ordinaryHit);
		WorldVector point = ordinaryHit.worldHit();
		TargetSnapshot locationSnapshot = TargetSnapshotFactory.location(ordinaryResolved.target().dimensionId(),
			point.x(), point.y(), point.z());
		ResolvedTarget locationResolved = ordinaryResolved.target() instanceof Target.LocationTarget
			? ordinaryResolved : resolver.resolve(locationSnapshot.target(), locationSnapshot.matchContext());
		Candidate location = candidate(id, locationSnapshot, locationResolved,
			new CandidateHit(point, CaptureEquivalenceKey.nativeTarget(locationResolved.target())));
		return CandidateAllocator.allocate(ordinary, supplements, certifiedTypes, location);
	}

	private Candidate candidate(int id, TargetSnapshot snapshot, ResolvedTarget resolved, CandidateHit hit) {
		return new Candidate(id, resolved, hit.worldHit(), distance(ray.origin(), hit.worldHit()),
			snapshot.entityLocalGeometryMetadata(), snapshot.blockHitFace(), hit.equivalenceKey());
	}

	public static double distance(WorldVector from, WorldVector to) {
		return Math.hypot(Math.hypot(to.x() - from.x(), to.y() - from.y()), to.z() - from.z());
	}

	private static boolean sameIdentity(Target captured, Target resolved) {
		return captured.equals(resolved) && (!(captured instanceof Target.ExternalBlockTarget external)
			|| resolved instanceof Target.ExternalBlockTarget other && external.providerLocator().equals(other.providerLocator()));
	}
}
