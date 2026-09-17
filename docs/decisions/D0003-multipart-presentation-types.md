# D0003: Preserve multipart presentation types

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

Resolve multipart blocks into explicit presentation subjects without rewriting
the marker's canonical target. Normal composite subjects preserve the source
Target Type. A verified proxy resolver may choose a subject-level render target
type only to present the real owner/master according to that owner's established
rendering form.

All normal geometry sources and VoxelShape fallback consume the same resolved
subjects and type decisions. Multipart extent alone never coerces a subject to
ordinary `block`.

Accordingly:

- vanilla beds remain `entity_block` and attempt the active entity-block source
  set before shape fallback;
- ordinary vanilla doors remain `block` while resolving to lower and upper
  composite subjects;
- supported Create EntityBlock doors preserve `entity_block` on both subjects;
- a verified owner/master proxy may present the real owner under its correct
  type without changing marker identity.

## Rationale

Target identity and rendering ownership answer different questions. A marker
must continue to identify the captured object for networking, ownership,
conflict resolution, and lifecycle. Presentation must be able to represent all
visible parts and to follow real owner/master relationships used by the target's
renderer.

Preserving subject types is required for correct source selection. Demoting a
bed to `block` would bypass BER, world-aware baked, and optional entity-block
sources. Promoting an ordinary door would invoke a geometry policy it does not
own. Explicit coverage relations handle the narrow case where one rendered
owner source truly draws another subject.

## Alternatives considered

- **Render only the originally hit cell.** Rejected because valid multipart
  presentation would be incomplete.
- **Convert every multipart subject to `block`.** Rejected because physical
  extent does not determine established rendering form.
- **Rewrite the marker to the presentation owner.** Rejected because a
  client-local render decision must not change canonical network identity.
- **Treat owner relation as automatic coverage.** Rejected because ownership or
  invocation does not prove that the duplicate subject was emitted.
- **Let successful first source short-circuit a subject.** Rejected because BER,
  baked, and optional geometry may independently contribute.

## Consequences

Presentation resolution produces an ordered subject list and optional exact
coverage relations. Each subject carries an explicit `renderTargetTypeId`.
Coverage requires `RENDERED` from the declared owner source and applies only for
that frame; empty, failed, unavailable, or unrelated source results do not cover
another subject. Invalid pairs or proxy relationships must fail soft without
inventing a different owner.

## Related docs

[Presentation subjects](../rendering/presentation_subjects.md),
[geometry pipeline](../architecture/geometry-pipeline.md),
[geometry sources](../geometry/geometry_sources.md), and
[Create integration](../integrations/create.md).
