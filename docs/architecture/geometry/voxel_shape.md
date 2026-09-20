# Native VoxelShape acquisition and edge extraction

This document owns native shape acquisition and edge extraction. GPU primitive,
depth, write-mask, and submission requirements are owned by
[outline rendering](../rendering/outline.md); both documents apply whenever the
VoxelShape route is selected.

## When the route is selected

VoxelShape is a fallback, never the default substitute for an eligible existing
glow path. It is selected for an ordinary block when native glow is ineligible
or does not emit, and for an `entity_block` when all permitted normal geometry
sources are empty, failed, or unavailable. `VOXEL_SHAPE_ONLY` selects it
directly without constructing a normal-source context.

Selection follows the [source outcome contract](geometry_sources.md) and the
resolved [presentation subjects](../rendering/presentation_subjects.md).
Multipart structure alone is not a reason to coerce a subject to ordinary
`block`: an eligible bed still attempts its `entity_block` sources before
fallback.

## Live native shape and edges

For each uncovered subject, obtain Minecraft 1.21.1's native `VoxelShape` from
the current live `BlockState#getShape` call and enumerate that exact shape with
`VoxelShape#forAllEdges`. Use the live state and collision context for the
current frame rather than a captured full-cube proxy or reconstructed model
silhouette.

The shape may already contain its native positional offset. Do not add a model
placement offset to this route, flatten it to bounding boxes, replace it with a
full cube, rebuild it as polygons or quads, or substitute a merely
shape-equivalent representation. Shape-based outlines are sufficient where
this route applies; pixel-accurate model silhouettes are not required.

## Distinct from picking geometry

Entity-local picking may decompose a selected native shape with
`VoxelShape#forAllBoxes` and intersect those boxes with a finite ray segment.
That is a targeting kernel, not an outline renderer. It does not replace or
relax the live `getShape` to `forAllEdges` presentation route described here.
The shared distinction is summarized in
[D0006](../decisions/D0006-exact-owned-geometry.md).

## Combined invariant

A conforming VoxelShape outline therefore requires both:

1. the live native shape and edge route in this document; and
2. the complete no-depth line-render and late-submission contract in
   [outline rendering](../rendering/outline.md).

Native-glow eligibility is tested separately from this combined invariant. The
rationale for preserving normal geometry before shape fallback is recorded in
[D0002](../decisions/D0002-voxel-shape-fallback.md). Automated structure or
behavioral coverage does not by itself prove in-game visibility from every
camera angle; verification reporting is maintained separately.
