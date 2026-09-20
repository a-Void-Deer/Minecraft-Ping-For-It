# Vanilla model placement and random seed

Placement rules are source-specific. A Minecraft baked-model source, a BER, a
provider-owned transform, and a native shape must not be collapsed into one
generic "model offset" operation.

## Ordinary world block through a virtual display

For a whitelisted ordinary world `block`, validate the live state first and
compute `BlockState#getOffset` once for the current frame. Keep the synthetic
BlockDisplay's own position anchored at the integer block **MIN corner**. Add
the live offset exactly once to the camera-relative dispatcher coordinates; do
not also add it through PoseStack translation or the display position.

This route is for ordinary block model glow. It does not substitute for an
`entity_block` world-aware baked source and does not define placement for BER or
VoxelShape geometry.

## Provider-owned ordinary block

A provider-owned ordinary `block` resolves its live state, local level, local
position, and transformed render pose through its provider rather than looking
up a main-world block at numerically similar coordinates. Its transformed world
origin includes the live model offset exactly once. The prepared PoseStack owns
camera-relative translation, orientation, and scale, so dispatcher x/y/z stay
zero and cannot double-transform the display.

This rule applies to the established provider-owned presentation route; it is
not a claim that every possible moving or external renderer shares one generic
model representation. Stable marker identity and live provider presentation
remain separate; see [Sable](../integrations/sable.md).

## Entity-block geometry

An `entity_block` BER receives the subject's block origin or provider transform
without a BlockState model offset. Dynamic BER geometry owns its own placement.
The loader-aware baked-model source uses the subject's live world/local level,
position, model data, render types, and position-derived randomness. It does not
fall back to the ordinary virtual display and must not reuse the display's
offset injection as an extra transform.

These independent sources are governed by the
[geometry outcome contract](../geometry/geometry_sources.md). Having a
`MODEL` state is only baked-source eligibility, not proof of successful
rendering.

## Position-dependent variants and scoped seed

For weighted or rotated variants, derive `BlockState#getSeed(pos)` from the
validated live state and actual subject position. The ordinary virtual-display
route scopes its override only around that display render dispatch and restores
vanilla random behavior outside the scope. Loader-aware baked adapters consume
the same position-derived seed through their native world-aware model path.

The local virtual-display mixin matches class-wide `42L` constants using
`@ModifyConstant(method = "*", require = 2)`. The minimum counts matching
constants, including an unused local; it does not prove that both
`RandomSource#setSeed` call sites remain covered. Fewer than two matches fail
mixin application, while drift of one actual seed site may still pass. Preserve
this verification limitation rather than treating the annotation as a semantic
proof.

## Native shape placement

The VoxelShape route uses the live native shape, which may already contain its
own offset. Do not reconstruct that shape from model placement, apply the model
offset a second time, or substitute a full cube. See
[native VoxelShape acquisition](../geometry/voxel_shape.md).
