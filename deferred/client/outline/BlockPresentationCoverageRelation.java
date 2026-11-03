package nx.pingwheel.common.client.outline;

import java.util.Objects;

/**
 * A source-conditioned, presentation-local declaration that one rendered
 * subject covers a later subject.
 *
 * <p>The relation is activated only when {@code ownerSubjectId} finishes the
 * exact {@code requiredRenderedSourceId} attempt with {@link
 * EntityBlockGeometryOutcome#RENDERED}. It never describes aggregate geometry
 * success, persists across frames, or implies transitive coverage.</p>
 */
public record BlockPresentationCoverageRelation(
    String ownerSubjectId,
    String requiredRenderedSourceId,
    String coveredSubjectId
) {

    public BlockPresentationCoverageRelation {
        Objects.requireNonNull(ownerSubjectId, "ownerSubjectId");
        Objects.requireNonNull(requiredRenderedSourceId, "requiredRenderedSourceId");
        Objects.requireNonNull(coveredSubjectId, "coveredSubjectId");

        if (ownerSubjectId.isBlank()) {
            throw new IllegalArgumentException("ownerSubjectId must not be blank");
        }

        if (coveredSubjectId.isBlank()) {
            throw new IllegalArgumentException("coveredSubjectId must not be blank");
        }

        String validatedSourceId = EntityBlockGeometrySourceIds.validate(requiredRenderedSourceId);
        if (validatedSourceId == null) {
            throw new IllegalArgumentException("requiredRenderedSourceId must be a valid namespaced id");
        }
        requiredRenderedSourceId = validatedSourceId;
    }
}
