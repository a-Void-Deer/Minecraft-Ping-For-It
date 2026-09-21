# Multipart presentation subjects and ownership

## Canonical identity and presentation are orthogonal

The marker retains the original captured target identity, Target Type, owner,
and lifecycle. A presentation resolver may expand that target into several
subjects or redirect presentation to a verified real owner/master, but it must
not rewrite the network target or canonical marker identity.

A presentation subject is the current-frame work item consumed by geometry
sources. It carries its own position, live state snapshot, expected registry
identity, relationship to the source, and `renderTargetTypeId`. This is distinct
from both the canonical target and the question of whether any source actually
renders the subject. The model/source versus rendered-outcome distinction is
owned by [D0001](../../decisions/D0001-separate-model-from-renderable.md); the
identity and multipart-type decision is owned by
[D0003](../../decisions/D0003-multipart-presentation-types.md).

## Required contract and current external conformance

This document states a required presentation contract; it is not a claim that
every optional or external integration has completed every route. D0003 remains
uniformly applicable: canonical marker identity and lifecycle stay separate
from presentation, and normal sources plus VoxelShape fallback use the same
resolved subject list and subject-level type decisions. There is no external
integration exemption from that contract.

For the current Sable external path, the external model route and the external
fallback independently resolve provider presentation. The current
implementation does not guarantee that provider-local multipart or subject
type decisions for those routes come from one shared immutable frame snapshot.
That is an open conformance and verification gap, not a product exception; see
[Sable coverage and pending scenarios](../../testing/verification.md#sable-integration-coverage).

## Render-target type rules

Normal composite resolvers preserve the source target type on every subject.
Spanning multiple positions is never a reason to coerce a subject to ordinary
`block`. A proxy resolver may select a different subject-level
`renderTargetTypeId` only when it has verified and selected the real
owner/master's established rendering form. That choice controls presentation
routing only; it does not reclassify the marker.

Every permitted source--BER, loader-aware baked model, optional geometry, and
VoxelShape fallback--must consume the same resolved subject list and the same
subject-level type decision. Resolve subjects once for the presentation, then
carry those decisions through both normal sources and fallback.

## Beds and doors

- A vanilla bed resolves to foot and head composite subjects while preserving
  its source type. Because a vanilla bed is classified as `entity_block`, both
  subjects remain `entity_block` and attempt permitted BER, baked-model, and
  optional geometry before VoxelShape fallback.
- A vanilla door resolves to lower and upper composite subjects while preserving
  its source type. An ordinary vanilla door is therefore still ordinary
  `block`; multipart structure does not promote or demote it.
- The Create door specialization applies only to the five supported door IDs
  and only when the source is an EntityBlock door already classified as
  `entity_block`. It reuses the validated lower/upper composite and preserves
  that `entity_block` type. The exact integration gate is listed in
  [Create integration](../../integrations/create.md).

If a live pair is damaged or inconsistent, resolution falls back to a direct
source subject rather than inventing a valid composite. A resolver may also
return a handled empty presentation when a verified proxy relationship is no
longer valid; that must not silently become a direct rendering of the wrong
owner.

## Source-conditioned coverage

Coverage is a per-presentation, per-frame declaration tying one owner subject,
one required source, and one duplicate subject together. It is neither
transitive nor inferred from multipart relation alone.

For a supported Create door, the lower subject's BER may cover the upper subject
only after that exact BER source reports `RENDERED`. Lower baked-model success,
optional-source success, source invocation, ownership, or any other source
result does not cover the upper subject.

Within every uncovered subject, sources remain non-short-circuiting. Successful
coverage or geometry suppresses only the declared duplicate work for the
current frame, under the common
[source outcome contract](../geometry/geometry_sources.md). Any uncovered shape
fallback still uses the live [native edge route](../geometry/voxel_shape.md) and
the GPU invariant in [outline rendering](outline.md).
