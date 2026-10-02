package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;

/** Press-local deduplication only. Never a marker key or a provider stable ID. */
public sealed interface CaptureEquivalenceKey {

	boolean matches(Target target);

	static CaptureEquivalenceKey nativeTarget(Target target) {
		Objects.requireNonNull(target, "target");
		return switch (target) {
			case Target.EntityTarget entity -> new EntityKey(entity.dimensionId(), entity.locator());
			case Target.BlockTarget block -> new BlockKey(block);
			case Target.LocationTarget location -> new LocationKey(location);
			case Target.ExternalBlockTarget ignored -> throw new IllegalArgumentException(
				"external capture requires positive provider equivalence evidence");
		};
	}

	record EntityKey(String dimensionId, EntityLocator locator) implements CaptureEquivalenceKey {
		public EntityKey {
			new Target.EntityTarget(dimensionId, locator);
		}
		@Override public boolean matches(Target target) {
			return target instanceof Target.EntityTarget entity
				&& dimensionId.equals(entity.dimensionId()) && locator.equals(entity.locator());
		}
	}

	record BlockKey(Target.BlockTarget block) implements CaptureEquivalenceKey {
		public BlockKey { Objects.requireNonNull(block, "block"); }
		@Override public boolean matches(Target target) { return block.equals(target); }
	}

	record LocationKey(Target.LocationTarget location) implements CaptureEquivalenceKey {
		public LocationKey { Objects.requireNonNull(location, "location"); }
		@Override public boolean matches(Target target) { return location.equals(target); }
	}

	/** The adapter supplies this token after positive live containment/type resolution. */
	record ExternalKey(String dimensionId, String providerId, String providerToken,
		String expectedBlockRegistryId) implements CaptureEquivalenceKey {
		public ExternalKey {
			Objects.requireNonNull(dimensionId, "dimensionId");
			Objects.requireNonNull(providerId, "providerId");
			Objects.requireNonNull(providerToken, "providerToken");
			Objects.requireNonNull(expectedBlockRegistryId, "expectedBlockRegistryId");
			if (dimensionId.isBlank() || providerId.isBlank() || providerToken.isBlank()
				|| expectedBlockRegistryId.isBlank() || providerToken.length() > 256) {
				throw new IllegalArgumentException("invalid external capture equivalence token");
			}
		}
		@Override public boolean matches(Target target) {
			return target instanceof Target.ExternalBlockTarget external
				&& dimensionId.equals(external.dimensionId()) && providerId.equals(external.providerId())
				&& expectedBlockRegistryId.equals(external.expectedBlockRegistryId());
		}
	}
}
