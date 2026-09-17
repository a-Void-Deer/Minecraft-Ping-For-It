# D0001: Separate model from renderable

## Status

Confirmed architecture decision derived from the current rendering contract and
recorded product behavior. It is a design boundary, not a claim that a specific
historical bug has been proven.

## Decision

Treat model eligibility, geometry-source availability, presentation subjects,
and current-frame renderability as separate concepts.

- Minecraft `RenderShape.MODEL` only permits a baked-model attempt.
- A Minecraft `BakedModel` is one geometry source.
- A Flywheel model is backend-specific mesh/material data, not a Minecraft
  `BakedModel`.
- BER, direct entity dispatch, and VoxelShape edges can render without being
  baked models.
- A presentation subject is the work item offered to sources; it is not itself
  evidence of geometry.
- A subject is renderable in the current frame only when an allowed source
  emits and commits geometry and reports `RENDERED`.

Canonical target identity versus presentation ownership is a separate,
orthogonal decision recorded in
[D0003](D0003-multipart-presentation-types.md).

## Rationale

Eligibility cannot predict output. A live model route may be unhandled, empty,
or failed; a non-model state may still have dynamic BER geometry. Shared-buffer
sources may also commit partial output before a recoverable exception, while an
attempt-local buffer can discard uncommitted partial work. Honest fallback and
duplicate suppression therefore depend on the final committed-output outcome,
not on class names, render shape, source ownership, or invocation.

The separation also permits each source to use its native context: world-aware
model data and render types for baked models, dynamic block-entity state for
BER, instance/material data for Flywheel, and native edges for VoxelShape.

## Alternatives considered

- **Treat `RenderShape.MODEL` as rendered.** Rejected because model eligibility
  does not guarantee a claiming adapter or nonzero geometry.
- **Call every geometry provider a model.** Rejected because it erases material
  differences between Minecraft baked models, Flywheel models, BER, direct
  entity rendering, and native shapes.
- **Use virtual BlockDisplay as the universal baked-model substitute.** Rejected
  for `entity_block` because it is not equivalent to a world-aware model route.
- **Suppress fallback after a source is configured or invoked.** Rejected
  because empty, unavailable, and pre-commit failed attempts would leave no
  outline.
- **Make this decision about canonical identity.** Rejected because identity,
  subject ownership, source eligibility, and emitted geometry are distinct
  stages with different responsibilities.

## Consequences

Every source reports `RENDERED`, `EMPTY`, or `FAILED` according to committed
output. Route policies express attempt eligibility only. Fallback and coverage
consume source outcomes rather than guessing from model presence. Documentation
must use qualified terms such as "baked-model source," "Flywheel model," or
"presentation subject" instead of using "model" or "renderable" as an
unbounded synonym.

## Related docs

[Geometry pipeline](../architecture/geometry-pipeline.md),
[geometry sources](../geometry/geometry_sources.md),
[outline rendering](../rendering/outline.md), and
[model placement](../rendering/model_placement.md).
