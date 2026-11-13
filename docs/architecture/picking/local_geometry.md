# Exact entity-local geometry picking

## Immutable ownership and candidate admission

Each ray takes one immutable entity-local-geometry registry snapshot. Owners
are resolved in explicit numeric-priority and source-ID order. This ordering
is distinct from Target Type declaration order and rendering-source
registration order. The first matching owner claims the candidate; its declared
admission is fixed in that snapshot.

| Admission | Discovery boundary |
| --- | --- |
| `NATIVE_BOUNDS` | The legacy route. Ordinary discovery uses the native entity query, and native broad-box relevance checks may still gate the narrowphase. |
| `LOADED_EXACT` | The exact owner declares that its native entity AABB is not an adequate discovery or exactness gate. It is never the narrowphase, and the candidate may also be discovered from the client's loaded entity view when the native query did not return it. |

Ordinary candidate iteration preserves native query order first. When the
snapshot contains a `LOADED_EXACT` owner, it appends only first-owner exact
entities from the client loaded view; it applies spectator and blacklist
filters, excludes the camera entity, and deduplicates by object identity.
Unowned loaded entities are not added, and a duplicate native object is not
traced twice. With only legacy owners, no loaded supplement walk occurs.

Precise live candidate capture keeps one lazy raw loaded view. Every raw entity
and dragon-part visit consumes work before filtering and identity deduplication;
a zero budget does not consume the iterator. A first-owner `LOADED_EXACT`
candidate bypasses the native AABB relevance gates, while an unowned candidate keeps
the existing broad-bound relevance. An incomplete raw visit prefix cannot
certify a nearest candidate.

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

## Bounded work and incomplete traversal

A query carries one work capability. A bounded exact traversal charges provider
access, local block reads, and native shape boxes; a legacy or ordinary route
can instead use independent unlimited work. Exhaustion is sticky across all
counters and cannot be cleared by catching it. A callback that has exhausted its
work cannot return a certified hit even after observing one, and a scan with
incomplete work cannot certify a nearest prefix or retain a provisional result.
Each provider's own charging points and any legacy unlimited path remain in its
integration contract.

## Distance and native local shapes

Compare exact candidates using their actual world-space surface points and the
existing strict nearest-hit rules. Equal-distance entity candidates retain the
first candidate-iteration selection. A world hit must be strictly nearer to
displace an entity hit. An entity hit exactly at the finite segment's far
endpoint is excluded.

The selection preserves the native hit result separately from its trusted world
comparison point. An ordinary-world hit uses its native location; a
provider-local block uses a proven projection without rewriting its native
position or face. When the block's world point is unknown, retain the native
block result rather than allow an entity to win an unprovable comparison.
Provider provenance is owned by [Sable](../../integrations/sable.md#transformed-entity-frames-and-frozen-block-proof);
acquisition and location-fallback effects are owned by
[capture](capture.md).

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
limitations, and [Aeronautics](../../integrations/aeronautics.md) owns the
separate exact rotor frame and geometry.

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
the whole entity's [authoritative anchor](range.md#server-acceptance); it does
not replay the local ray or validate a constituent.

For Create's exact IDs, lazy owner shell and unavailable-delegate handling see
[Create](../../integrations/create.md). The rejected coarse-bound and alternative
kernel approaches are explained in
[D0006](../../decisions/D0006-exact-owned-geometry.md).
