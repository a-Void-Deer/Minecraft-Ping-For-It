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

### Render entity UUID lookup

One world-render pass shares a render-only UUID lookup between HUD marker
updates and optional entity-outline resolution, so marker views prepared for the
HUD and optional entity outlines use the same lookup work for that pass.
Beginning this shared lookup is not a client-tick operation and is not coupled
to an entity outline's success or reset state.

Beginning it does not scan entities. A live positive result requested in the
immediately preceding pass can be reused in the next pass, and a positive
remains reusable only while it continues to be requested across adjacent
passes; the cache does not acquire a positive merely because an entity appeared
in a scan. A change in the client world object by identity (`!=`), rather than a
dimension identifier or string, clears this state. A null world and world leave
clear it as well.

Every returned entity, whether reached through a positive or an index, must be
live for this request: it must not be removed, its current UUID must equal the
requested UUID, and `currentWorld.getEntity(entityId)` must still be that same
object. An invalid positive is unresolved rather than stale. If a pass has no
UUID requests, or every request is a live warm positive, it performs no entity
scan. The first unresolved request performs at most one traversal of the
current world's renderable entities and creates a complete UUID index local to
that pass. All later hits and misses in that pass share that index. If multiple
live entities expose the same UUID, the first valid one in traversal order is
the result. The temporary index is discarded for the next pass, and misses are
not retained as an independent cross-pass negative cache.

The cache stores raw entity objects only. Canonicalization and locator matching
remain the resolver's responsibility, so dragon-parent handling, target
identity, and source outcomes do not change. The XP locator remains a direct
integer-ID lookup that accepts only an `ExperienceOrb`; it neither consults nor
populates the UUID cache. Non-render `GameContext#getEntity` lookups for
validation, names, and cancellation remain fresh immediate lookups, unaffected
by a render-path miss. Render-time position, tick delta, and projection are
still evaluated each frame, and the existing last-live fallback remains in
place. The lookup-algorithm and locator-resolver seams are inventoried in
[testing and verification](../../testing/verification.md#render-entity-lookup-and-locator-resolver-seams).

An index represents what was available when it was built. An entity added or
replaced after construction may not be visible until a later pass, while a removed
or replaced cached object cannot be returned stale because of the live checks.
An entity that becomes available before index construction can still be found
in that same pass. Accordingly, a miss does not imply that every lookup must
wait until the next frame; a later pass remains able to search again.

When the optional entity-outline registry is empty, skip that entity's resolve
and runner fragment. This does not change vanilla outline processing,
block-model routes, frame flags, or post-processing.

## Live block state and native-glow attempt eligibility

Committed block lifetime, block replacement, and same-type state/property
behavior are owned by [the target model](../identity/target_model.md). After
acceptance, presentation uses the current live BlockState, and the current
renderer will not claim a stale presentation subject as a successful native
route.

The client [whitelist and blacklist](../../config/client.md) gate native-glow
attempts. A blacklist match overrides a whitelist match. Additional route
conditions are:

- Ordinary `block`: no BlockEntity and live render shape `MODEL`; when
  eligible, attempt vanilla model glow through the ordinary-block route.
- `entity_block`: under the active entity-block mode, the permitted BER and
  loader-aware baked-model preconditions, and optional-source admission, are
  owned by the [source outcome contract](../geometry/geometry_sources.md).

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

When eligible and successful, normal BER, baked-model, or optional geometry
follows the target's established rendering form more closely than a shape-only
outline. The rationale and rejected alternatives are recorded in
[D0002](../../decisions/D0002-voxel-shape-fallback.md).

Source outcomes, fallback suppression, and direct shape-route selection are
owned by the [source outcome contract](../geometry/geometry_sources.md). A
marker's lifecycle and HUD data can remain
active even when the current presentation has no subject; that is distinct
from a subject whose sources are empty. The replacement and same-registry
live-state presentation consequences are owned by
[the target model](../identity/target_model.md).

## VoxelShape GPU render invariant

Whenever the VoxelShape route is selected, including fallback from an eligible
but non-rendering native-glow attempt, production rendering must retain every
state below:

| Property | Required state |
| --- | --- |
| Primitive mode | `VertexFormat.Mode.LINES` |
| Shader | Vanilla `rendertype_lines` |
| Width | Fixed and wider than vanilla selection lines; the live value is owned by [`BlockOutlineRenderType.LINE_WIDTH`](../../../common/src/main/java/nx/pingwheel/common/client/outline/BlockOutlineRenderType.java) |
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
[Create entity-outline adapter](../../integrations/create.md) has a distinct
entity claim and dispatcher contract and must not be conflated with the
Create/Flywheel entity-block source.
