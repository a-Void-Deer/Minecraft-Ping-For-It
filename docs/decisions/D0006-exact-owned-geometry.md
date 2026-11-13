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

A transformed entity's native bounds are also not a sufficient discovery or
exactness boundary. Those bounds can remain at remote native plot coordinates
far from the rendered parent-contained position, or be far larger than the
owned local geometry. Gating exact candidates only through such bounds would
miss valid contained targets and would still expose regions the exact source
did not hit. An explicit loaded-exact admission therefore separates discovery
from the native AABB without making that AABB authoritative.

A bounded exact traversal can observe a nearer hit and still exhaust before
completing an unordered search. Its prefix is not a certified nearest result,
and catching the exhaustion must not turn that prefix into a hit. Sticky work
and whole-attempt rejection preserve the same exactness boundary as the
coarse-AABB rule; the operational charging and ordering rules remain with the
topic contracts.

The finite-segment box kernel preserves actual native block/fluid surfaces,
supports origin containment and boundary cases, and avoids assumptions about a
unit cube or a GPU-rendered mesh. Whole-entity marker identity remains intact;
local hit detail is capture metadata, not a constituent target.

## Alternatives considered

- **Fall back to AABB after an owned non-hit.** Rejected because it selects
  hollow or sparse entities that the exact source explicitly did not hit.
- **Use the native entity AABB as the exact discovery and intersection gate.**
  Rejected because transformed parents can separate the native plot bounds from
  the placed rotor or make them much broader than the owned local shapes.
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
resolved entity identity. Query-local parent frames are detached copies;
later provider pose changes do not move retained placement, and a parent failure
does not become world identity. A bounded traversal that cannot complete
invalidates the candidate rather than certifying a prefix. The server validates
the whole entity and does not replay the client-local ray.

## Related docs

[Entity-local picking](../architecture/picking/local_geometry.md),
[native VoxelShape distinction](../architecture/geometry/voxel_shape.md),
[Create integration](../integrations/create.md),
[Create contraption ray targeting](../integrations/create-contraption-raycast.md), and
[Aeronautics rotor targeting](../integrations/aeronautics.md).
