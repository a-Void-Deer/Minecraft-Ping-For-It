# D0002: Preserve native geometry before VoxelShape fallback

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

Prefer an eligible established native-glow path when it actually renders. Use
the live native VoxelShape edge route only when an ordinary-block native attempt
is ineligible or does not render, when all permitted `entity_block` sources do
not render, or when `VOXEL_SHAPE_ONLY` explicitly selects the shape route.

Eligibility alone never suppresses fallback. Only a source outcome of
`RENDERED` covers the matching subject for the current frame. Once selected,
the shape route uses the current live `BlockState#getShape` and
`VoxelShape#forAllEdges`, together with the complete no-depth line-render and
late-submission invariant.

## Rationale

BER, baked-model, and optional geometry follow a target's established rendering
form more closely, including dynamic block-entity details and backend-specific
meshes. A VoxelShape represents native selection shape rather than a rendered
model silhouette. Keeping successful native glow therefore produces a more
faithful outline, while the shape route remains a deterministic fail-soft
fallback for absent, unsupported, empty, or failed sources.

The native shape still matters: it preserves non-full-cube geometry and its own
offset without requiring pixel-accurate model extraction. Rendering it late and
without depth testing preserves visibility through occluders and prevents
successful normal geometry from being overdrawn by a duplicate shape mask.

## Alternatives considered

- **Always use VoxelShape.** Rejected because it discards eligible dynamic and
  model geometry and turns a selection shape into a universal model substitute.
- **Never fall back after native-glow eligibility.** Rejected because an
  eligible source may be unavailable, emit zero vertices, or fail.
- **Use a full cube or shape-equivalent reconstruction.** Rejected because the
  live native shape and its edges are required, including non-full-cube forms.
- **Use depth-tested lines.** Rejected because the marker must remain visible
  through occluding blocks and at arbitrary camera angles.
- **Render polygons or quads instead of native edges.** Rejected because that is
  a different visual and GPU contract, not an implementation detail.

## Consequences

Normal sources run first and report honest outcomes. Uncovered subjects retain
shape fallback. The [native VoxelShape geometry](../architecture/geometry/voxel_shape.md)
and [VoxelShape GPU render invariant](../architecture/rendering/outline.md#voxelshape-gpu-render-invariant)
document the live-edge acquisition and render-state/submission portions of one
invariant.

## Related docs

[Geometry sources](../architecture/geometry/geometry_sources.md),
[native VoxelShape](../architecture/geometry/voxel_shape.md),
[outline rendering](../architecture/rendering/outline.md), and
[presentation subjects](../architecture/rendering/presentation_subjects.md).
