# Geometry and presentation pipeline

This page describes the client presentation pipeline without turning Minecraft
models, presentation subjects, or current-frame rendering outcomes into the
same concept.

## Pipeline overview

```text
winning marker and canonical target
    -> resolve one block presentation
    -> produce presentation subjects
    -> select permitted geometry sources for each subject
    -> run every permitted source under the common outcome contract
    -> select native VoxelShape fallback for uncovered subjects
    -> submit normal outlines and late native-edge outlines
```

The canonical target identity remains unchanged across every presentation
stage. The pipeline may choose different positions or a verified owner/master
for rendering, but those choices do not rewrite marker ownership, network
identity, or lifecycle.

## Stage responsibilities

| Stage | Inputs | Outputs | Responsibility |
| --- | --- | --- | --- |
| Marker selection | Active markers and same-target winner state | One winning presentation request and color | Select visible marker state; do not resolve geometry |
| Presentation resolution | Source block identity, live world state, source target type | Ordered presentation subjects plus explicit coverage relations | Validate multipart/proxy relationships and choose subject positions, states, expected registry IDs, relations, and render-target types |
| Route and source selection | Subject type, live state, client whitelist/blacklist, entity-block mode | Ordered set of permitted normal sources, or direct shape selection | Decide eligibility only; do not claim successful rendering |
| Source execution | One subject and source-specific live context | `RENDERED`, `EMPTY`, or `FAILED` per source | Attempt geometry without short-circuiting and classify committed output honestly |
| Coverage and fallback | Source outcomes and declared coverage relations | Covered subjects and uncovered native-shape work | Apply only exact source-conditioned coverage; choose VoxelShape when no normal source rendered or shape-only mode requires it |
| Rendering submission | Normal outline buffers and native shape edges | Composited local outline | Submit normal geometry and then the required late, no-depth native-edge pass without mutating persistent global glow/team state |

## Identity and presentation boundaries

The canonical Target and Marker answer "what was pinged, by whom, and for how
long?" A presentation answers "which live subjects should represent that target
on this client frame?" A subject-level `renderTargetTypeId` answers "which
established rendering form should this subject use?" A source outcome answers
"did this source actually commit geometry this frame?"

These values must not be substituted for one another:

- Multipart expansion does not create additional markers.
- A proxy-to-owner subject does not replace the network target.
- A subject with render shape `MODEL` is not automatically rendered.
- A source that claims or runs is not automatically `RENDERED`.
- A fallback shape does not reclassify an `entity_block` as `block`.

See [presentation subjects](rendering/presentation_subjects.md) and
[D0003](../decisions/D0003-multipart-presentation-types.md).

## Model, source, and current-frame renderability

The term **model** is deliberately narrow:

- Minecraft `RenderShape.MODEL` is an eligibility signal on the live state.
- A Minecraft `BakedModel` is one geometry source accessed through a
  loader-aware world route.
- A Flywheel model is backend-specific mesh/material data and is not a
  Minecraft `BakedModel`.

Other sources, including BER, direct entity dispatch, and VoxelShape edges, can
produce geometry without being baked models. Consequently, **renderable in the
current frame** means that an allowed source emitted and committed geometry and
reported `RENDERED`. The full contract is in
[geometry sources](geometry/geometry_sources.md), with rationale in
[D0001](../decisions/D0001-separate-model-from-renderable.md).

## Render entity lookup lifetime

The render-pass UUID lookup is a shared raw-entity lookup accelerator for HUD
marker updates and optional entity outlines. It is not canonical identity, a
network target, a presentation owner, a geometry source, or evidence that a
source reported `RENDERED`.

Position evaluation and actual source submission remain per-frame work after a
lookup result. The cache epoch, live-result validation, and negative-result
lifetime are owned exclusively by [the render entity UUID lookup
contract](rendering/outline.md#render-entity-uuid-lookup).

## Geometry and GPU ownership

Geometry documentation owns how source geometry is acquired and classified.
For native shape fallback, this includes live `BlockState#getShape` acquisition
and `VoxelShape#forAllEdges` extraction. Rendering documentation owns GPU
primitive mode, shader, depth state, write mask, line width, and submission
order. A valid VoxelShape presentation requires both halves:

- [native VoxelShape geometry](geometry/voxel_shape.md); and
- [outline GPU rendering](rendering/outline.md).

Optional integrations may register resolvers or sources, but they remain
subject to these common contracts. [Create integration](../integrations/create.md)
documents only Create-specific gates, extraction, and dispatch behavior.
