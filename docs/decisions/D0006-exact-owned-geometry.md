# D0006: Exact geometry for owned entity candidates

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

When an immutable entity-local geometry registry snapshot identifies an owner
for an entity candidate, admit that candidate into nearest-hit selection only
on an exact local-geometry `HIT`. Owned `MISS`, `UNAVAILABLE`, and `FAILED`
results reject the candidate and never fall back to its coarse entity AABB.

An entity with no registered geometry owner retains the existing inflated-AABB
candidate behavior. Rejecting one owned candidate does not reject the ray: other
entities and the world hit remain eligible under the existing strict nearest
surface rules.

For native local block/fluid shapes, intersect a finite transformed segment with
the exact boxes produced by the selected shape's `VoxelShape#forAllBoxes`.
Preserve deterministic local-position ordering and the documented block/fluid
tie rule. This targeting decomposition is distinct from outline presentation,
which uses live `VoxelShape#forAllEdges`.

## Rationale

Once a source claims exact ownership, coarse AABB recovery would create false
positives through holes, sparse structures, or empty portions of a contraption's
broad bounds. `UNAVAILABLE` and `FAILED` cannot safely mean a hit, and treating
them as one would make optional failure alter target semantics.

The finite-segment box kernel preserves actual native block/fluid surfaces,
supports origin containment and boundary cases, and avoids assumptions about a
unit cube or a GPU-rendered mesh. Whole-entity marker identity remains intact;
local hit detail is capture metadata, not a constituent target.

## Alternatives considered

- **Fall back to AABB after an owned non-hit.** Rejected because it selects
  hollow or sparse entities that the exact source explicitly did not hit.
- **Use Create's interaction picker.** Rejected because it fixes outline-shape
  behavior and has a roughly 201-step traversal limit unsuitable for this
  captured-ray contract.
- **Use `VoxelShape.clip` as the precise kernel.** Rejected because its interior
  probe scales with the complete segment length; the finite kernel handles
  containment and boundaries without that length-scaled probe.
- **Create a constituent block marker.** Rejected for the current feature: the
  exact hit refines selection of the whole entity and does not change packet or
  server-authority shape.
- **Reuse outline edge extraction for picking.** Rejected because edge drawing
  and finite volume/surface intersection solve different problems.

## Consequences

Geometry ownership survives temporary delegate unavailability so an owned
candidate cannot regress to coarse selection. Each ray uses one immutable owner
snapshot. Exact local metadata may be retained only while it matches the
resolved entity identity. The server validates the whole entity and does not
replay the client-local ray.

## Related docs

[Entity-local picking](../architecture/picking/local_geometry.md),
[native VoxelShape distinction](../architecture/geometry/voxel_shape.md),
[Create integration](../integrations/create.md), and
[Create contraption ray targeting](../integrations/create-contraption-raycast.md).
