package nx.pingwheel.common.integration.sable.client;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.interaction.candidate.CaptureEquivalenceKey;

/** Provider-owned positive-capture token, not a locator parser or a server materialization ID. */
public final class SableCaptureEquivalence {
	private SableCaptureEquivalence() {}

	public static CaptureEquivalenceKey.ExternalKey fromResolved(String dimensionId, UUID subLevelId,
		int x, int y, int z, String expectedBlockRegistryId) {
		Objects.requireNonNull(subLevelId, "subLevelId");
		return new CaptureEquivalenceKey.ExternalKey(dimensionId, "sable",
			subLevelId + ":" + x + ":" + y + ":" + z, expectedBlockRegistryId);
	}
}
