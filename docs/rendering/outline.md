# Local outlines, route eligibility, and GPU submission

This document owns outline route eligibility and GPU rendering requirements.
Native shape acquisition and edge enumeration are specified separately in
[native VoxelShape geometry](../geometry/voxel_shape.md).

## Common entity outlines

Every pingable entity, including ordinary entities and dropped items, can
receive a local ping outline. The active
[same-target winner](../authority/ping_winner.md) supplies its outline color,
and movement within the same dimension keeps the outline attached to the same
entity identity.

Use a scoped local render pass or equivalent. Do not persistently mutate
vanilla glowing, visibility, or scoreboard-team state, and do not clobber
unrelated rendering state. Target names and chat remain required for block and
entity pings regardless of outline route; see [names and chat](names_chat.md).

## Live block state and native-glow attempt eligibility

Authoritative marker creation rejects a block that has already been replaced by
a different block type. After acceptance, presentation uses the current live
BlockState. A same-type state/property change preserves the target and updates
the live model or shape. Replacing an ordinary committed block does not by
itself remove the marker, although the current renderer will not claim a stale
presentation subject as a successful native route.

The client [whitelist and blacklist](../config/client.md) gate native-glow
attempts. A blacklist match overrides a whitelist match. Additional route
conditions are:

- Ordinary `block`: no BlockEntity and live render shape `MODEL`; when
  eligible, attempt vanilla model glow through the ordinary-block route.
- `entity_block`: a relevant live BlockEntity is required. Under the active
  entity-block mode, independently attempt the actual BER source and, for a
  `MODEL` state, the loader-aware baked-model source. Optional sources are
  admitted only by that mode.

Here `W` is the target-type whitelist result and `B` is a blacklist match. The
table describes **attempt eligibility**, never guaranteed geometry emission:

| W | B | Native-glow attempt eligible |
| ---: | ---: | --- |
| false | false | false |
| false | true | false |
| true | false | true |
| true | true | false |

An eligible `entity_block` runs all permitted sources without short-circuiting,
even after one emits. Every route consumes the same resolved
[presentation subjects](presentation_subjects.md). Whitelisting cannot turn an
absent, unsupported, zero-emitting, or failed source into a rendered result.

## Why native glow remains preferred

When eligible and successful, BER, baked-model, or optional geometry follows the
target's established rendering form more closely than a shape-only outline.
The VoxelShape route outlines the native selection shape; it is not a model
silhouette and is not expected to reproduce dynamic BER details or arbitrary
rendered meshes. Native glow therefore remains preferred, while the shape route
provides a deterministic outline when normal geometry is ineligible or fails.
The decision and rejected alternatives are recorded in
[D0002](../decisions/D0002-voxel-shape-fallback.md).

Only a `RENDERED` result under the common
[source outcome contract](../geometry/geometry_sources.md) suppresses duplicate
VoxelShape fallback for that subject and frame. `EMPTY`, `FAILED`, unavailable
sources, and mere route eligibility preserve fallback. `VOXEL_SHAPE_ONLY`
selects the shape route directly.

## VoxelShape GPU render invariant

Whenever the VoxelShape route is selected, including fallback from an eligible
but non-rendering native-glow attempt, production rendering must retain every
state below:

| Property | Required state |
| --- | --- |
| Primitive mode | `VertexFormat.Mode.LINES` |
| Shader | Vanilla `rendertype_lines` |
| Width | Fixed **3.75 px**, approximately 1.5 times the roughly 2.5 px vanilla selection width |
| Depth | `NO_DEPTH_TEST`, with the effective comparison `GL_ALWAYS` |
| Writes | `COLOR_WRITE` only; do not write depth |
| Submission | Late composite submission, after normal outline-source success is known |
| Visibility | Through all occluding blocks and from arbitrary camera angles |

Do not reintroduce depth-tested occlusion. Do not change the route to polygons,
quads, a full cube, or another shape substitute. Late submission is part of the
contract: it allows successful normal geometry to suppress only the matching
subject while uncovered subjects still contribute their native edges.

The GPU contract combines with the live `BlockState#getShape` and
`VoxelShape#forAllEdges` requirements in
[native VoxelShape geometry](../geometry/voxel_shape.md). Neither half is a
complete implementation on its own. Native-glow whitelist coverage is separate
from this VoxelShape invariant.

Model offset and seed placement are specified in
[model placement](model_placement.md). The optional
[Create entity-outline adapter](../integrations/create.md) has a distinct
entity claim and dispatcher contract and must not be conflated with the
Create/Flywheel entity-block source.
