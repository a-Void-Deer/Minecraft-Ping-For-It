# Exact entity-local geometry picking

## Immutable ownership and candidate admission

Each ray takes one immutable entity-local-geometry registry snapshot. Owners
are resolved in explicit numeric-priority and source-ID order. This ordering
is distinct from Target Type declaration order and rendering-source
registration order.

| Ownership/result | Candidate handling |
| --- | --- |
| No registered owner | Preserve existing inflated-AABB candidate selection |
| Owned `HIT` | Admit the precise world-space surface hit |
| Owned `MISS` | Reject this candidate |
| Owned `UNAVAILABLE` | Reject this candidate |
| Owned `FAILED` | Reject this candidate |

An owned non-hit never falls back to its coarse AABB. Recoverable capture/scan
failure invalidates the whole candidate attempt, including provisional hits.
Other entities and the world hit remain eligible; a hollow contraption must
not hide a real target behind the hole merely because its broad bounds intersect.

## Distance and native local shapes

Compare exact candidates using their actual world-space surface points and the
existing strict nearest-hit rules. Equal-distance entity candidates retain the
first candidate-iteration selection. A world hit must be strictly nearer to
displace an entity hit. Existing entity endpoint exclusion is preserved.

The common scanner obtains the selected native block and fluid shapes through
`ClipContext.getBlockShape` and `getFluidShape` under the frozen
[selection policy](selection_policy.md). It intersects the finite segment with
exact boxes emitted by native `VoxelShape.forAllBoxes`, considering all candidate
positions, including shapes extending outside their owning cells. These are
native shape decompositions, not unit-cube approximations. The finite-segment
kernel handles origin containment and boundaries without a segment-length-scaled
interior probe.

Native block/fluid shapes compete by surface distance; a block wins an exact
block/fluid tie. Equal-distance local child hits of the same kind use
deterministic local-position ordering by x, then y, then z. The
[Create supplement](../../integrations/create-contraption-raycast.md) owns its
provider-specific transform, captured view, collision context and cache/cost
limitations.

This forAllBoxes route is for picking only; the presentation
`VoxelShape#forAllEdges` outline route and its render state are owned by the
[native outline route](../geometry/voxel_shape.md) and its
[GPU contract](../rendering/outline.md).

## Frozen metadata and whole-entity identity

Entity-local capture metadata carries copied source ID, block/fluid kind, local
block position, expected block/fluid registry identities and local/world hit
points into the frozen capture context. No live entity, world, block state, fluid
state or shape is retained in locked domain metadata.

Snapshot construction rejects detail belonging to a different entity; target
resolution retains detail only if it preserves the captured entity identity.
The resulting Target and packets still identify the whole
entity. The server validates identity, liveness, classification and range from
the entity anchor; it does not replay the local ray or validate a constituent.

For Create's exact IDs, lazy owner shell and unavailable-delegate handling see
[Create](../../integrations/create.md). The rejected coarse-bound and alternative
kernel approaches are explained in
[D0006](../../decisions/D0006-exact-owned-geometry.md).
