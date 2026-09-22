# D0006: Exact geometry for owned entity candidates

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

Treat a geometry owner's exact result as the selection boundary rather than
recovering an owned non-hit through a coarse bound. Unowned entities retain
their existing selection path. The executable owner/result, competition and
native-shape kernel rules are owned by
[entity-local picking](../architecture/picking/local_geometry.md).

Keep exact picking separate from outline presentation. Their native geometry
contracts are linked from that picking owner and
[VoxelShape presentation](../architecture/geometry/voxel_shape.md).

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
  behavior and has a fixed traversal limit unsuitable for this
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
