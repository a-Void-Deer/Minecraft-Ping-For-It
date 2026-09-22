# Geometry and presentation pipeline

This page is an explanatory overview of the client presentation pipeline. It
summarizes stages and boundaries and links to the focused architecture contracts
that normatively own the behavior; it is not a second owner of eligibility,
failure, cache, or placement rules. The canonical target identity remains
unchanged across every presentation stage: the pipeline may choose different
positions or a verified owner/master for rendering, but those choices do not
rewrite marker ownership, network identity, or lifecycle.

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

## Stage responsibilities and owners

| Stage | Responsibility summary | Primary owners |
| --- | --- | --- |
| Marker selection | Select visible marker state; do not resolve geometry | [Ping winner](../authority/ping_winner.md), [target model](../identity/target_model.md) |
| Presentation resolution | Choose subject positions, states, registry identity, relations, and render-target types | [Presentation subjects](../rendering/presentation_subjects.md), [D0003](../../decisions/D0003-multipart-presentation-types.md) |
| Route and source selection | Decide permitted sources; do not claim successful rendering | [Geometry sources](geometry_sources.md), [outline rendering](../rendering/outline.md) |
| Source execution | Attempt geometry without short-circuiting and classify committed output | [Geometry sources](geometry_sources.md), [D0001](../../decisions/D0001-separate-model-from-renderable.md) |
| Coverage and fallback | Apply exact source-conditioned coverage; select the native shape when required | [Presentation subjects](../rendering/presentation_subjects.md), [geometry sources](geometry_sources.md) |
| Rendering submission | Submit normal geometry, then the required late native-edge pass | [Outline rendering](../rendering/outline.md), [native VoxelShape geometry](voxel_shape.md) |

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

See [presentation subjects](../rendering/presentation_subjects.md) and
[D0003](../../decisions/D0003-multipart-presentation-types.md).

## Model, subject, and source boundaries

Minecraft `RenderShape.MODEL` is an eligibility signal on the live state, a
Minecraft `BakedModel` is one geometry source accessed through a loader-aware
world route, and a Flywheel model is backend-specific mesh/material data, not a
Minecraft `BakedModel`. Sources such as BER, direct entity dispatch, and
VoxelShape edges can produce geometry without being baked models. A subject is
renderable in the current frame only when an allowed source emits and commits
geometry and reports `RENDERED`. See [geometry sources](geometry_sources.md) and
[D0001](../../decisions/D0001-separate-model-from-renderable.md).

## Render entity lookup and GPU ownership

The render-pass UUID lookup is a shared lookup accelerator for HUD marker
updates and optional entity outlines; its behavior and invariants are owned by
the [render entity UUID lookup contract](../rendering/outline.md#render-entity-uuid-lookup).
Position evaluation and actual source submission remain per-frame work after a
lookup result. Geometry documentation owns how source geometry is acquired and
classified; rendering documentation owns GPU primitive mode, shader, depth
state, write mask, line width, and submission order. A valid VoxelShape
presentation requires both [native VoxelShape geometry](voxel_shape.md) and
[outline GPU rendering](../rendering/outline.md).

## Optional integrations

Optional integrations may register resolvers or sources, but they remain
subject to these common contracts. [Create integration](../../integrations/create.md)
documents only Create-specific gates, extraction, and dispatch behavior.
