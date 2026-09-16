# Testing and verification

This document separates three kinds of evidence: automated coverage known to
exist, gaps that remain, and manual/integration scenarios that are still
pending. An inventory statement records established coverage scope; it is not a
claim that a test, build or game session was run for the current change.

When implementation code changes, run the relevant automated tests,
lint/static-analysis/format checks, type or compile checks, and builds for every
affected Fabric, Forge and NeoForge source set. The exact execution and reporting
rules, including the Windows Gradle requirements, are owned by `AGENTS.md`.
Never report rendering, multiplayer or interaction behavior as verified unless
it was actually exercised or covered by an appropriate automated test.

## Existing automated coverage inventory

### Domain, identity and authority

The current suite covers:

- the fixed Target Type and Ping Type catalogs, including their values;
- numeric-priority and declaration-order Target Type resolution;
- optional-content fail-soft matching;
- target identity and the captured-ray flow;
- Marker ID and marker codec behavior;
- `targetTypeId`, including `entity_block`, surviving marker codec round trips
  without changing the protocol shape; and
- same-target winner selection and recomputation.

### Capture, wheel and cancellation

The current suite covers short press, long press, pending/asynchronous capture,
wheel state transitions, the actual wheel-open boundary and timeout behavior. It
also covers cancellation filtering and nearest-candidate selection, frozen
press-ray behavior, and interaction behavior at the pending-capture/wheel
boundary.

### Exact entity-local picking and Create raycast seams

Focused coverage exercises:

- an immutable entity-local-geometry owner snapshot;
- owner resolution and `HIT`/`MISS`/`UNAVAILABLE`/`FAILED` candidate rules;
- retention of entity-local metadata only for the matching resolved identity;
- native local block/fluid shape policy and exact-tie behavior;
- the common native local-shape scanner and candidate pipeline;
- the Create-free contraption engine; and
- lazy Create adapter/loading seams and delegate-unavailable behavior.

These tests preserve whole-entity identity and exercise the non-optional engine
boundaries. They do not constitute an in-game Create validation.

### Presentation, chat, config and geometry outcomes

The current suite covers:

- chat template selection and Ping-Type-phrase-only coloring;
- wheel sector colors;
- block whitelist grammar and evaluation;
- entity-block source modes and per-attempt `RENDERED`/`EMPTY`/`FAILED`
  outcomes, including fallback consequences;
- invalid client-config recovery; and
- behavioral native-edge rendering.

### Rate-policy courtesy behavior

The current suite covers the create-only client token-bucket courtesy gate,
dropping throttled committed creates without queueing or dispatch tracking,
`MarkerRemove` and channel-update bypass, and corrupt-policy handling. This
client coverage does not close the server and end-to-end policy gaps below.

### Focused native block-outline regression coverage

The native VoxelShape route requires complementary checks rather than one broad
"outline works" assertion:

1. Production render-state coverage pins `BlockOutlineRenderType` to
   `VertexFormat.Mode.LINES`, vanilla `rendertype_lines`, the fixed 3.75 px
   width, `NO_DEPTH_TEST`/`GL_ALWAYS`, color-only writes and late composite
   submission.
2. Native geometry coverage pins the live
   `BlockState#getShape`-to-`VoxelShape#forAllEdges` edge route so a full-cube,
   polygon or shape-equivalent substitute cannot satisfy the regression.
3. Render-frame snapshot coverage separately exercises late submission and
   frame ownership.

Native-glow whitelist/eligibility coverage is separate from these VoxelShape
checks. Passing either side alone does not prove the other, and structural or
behavioral tests do not by themselves prove actual visibility through occluders
or from arbitrary in-game camera angles.

## Known automated gaps

The following gaps remain open until direct evidence closes them:

- the shared client/server `entity_block` classification path end to end;
- application of synchronized rate policy on reconnect and on effective live
  configuration change;
- server-side sanitization of negative rate-policy values; and
- detailed diagnostic behavior in the private
  `CreateEntityOutlineAdapter.EntityDiagnostics` path.

Coverage of Flywheel diagnostics or another adapter's diagnostic helper does
not close the private `EntityDiagnostics` gap.

## Pending manual and integration matrix

No scenario in this matrix is recorded as performed merely because it is listed
or because related automated tests exist.

| Area | Pending scenarios |
| --- | --- |
| Block | Plain `block` versus `entity_block`; `ALL`/`COMPATIBLE`/`VOXEL_SHAPE_ONLY` modes and source fallback; whitelist native glow and fallback; a non-full native shape; same-type state change versus block-type replacement. |
| Entity | Ordinary entity and dropped item; movement and same-dimension teleportation; death and disappearance. |
| Wheel | Short and long press; every sector and border color; 5000 ms timeout; frozen target; location fallback. |
| Movement, death and replacement | Target movement while the wheel is open; entity death or dimension change; block state change or replacement while open. |
| Naming and chat | Custom-name formatting; localized base names; item naming; phrase-only text color. |
| Cancellation | Cone and nearest-own-marker selection; inability to cancel another player's marker. |
| Multiplayer | Same-target latest-server-arrival winner; equal-arrival larger-Marker-ID tie; winner fallback after removal or expiry. |
| Settings and config | External edits do not reload in-session and apply after restart or explicit reload; invalid-config recovery and preservation lock. |
| Rate policy | Synchronization on reconnect and on effective live configuration change. |
| Optional content and rendering | Absent or partially present optional content; Create/Flywheel routes; occlusion and arbitrary camera angles; current shape, offset and seed. |
| Create contraption raycast | Hollow, sparse and overlapping contraptions; world-wall ordering; all transparent/fluid policy combinations including waterlogging; moving/rotated, minecart-mounted, carriage and gantry forms; portal-hidden or loading data; held press-time target; Create-absent and delegate-unavailable paths; large-structure press cost. |

## Recording future evidence

- Add or update focused tests in the same change as behavior where practical.
- Record a gap as closed only when a named automated check or an actually
  performed manual/integration scenario covers it.
- Keep automated coverage, commands actually run, and manual observations as
  separate claims in implementation reports.
- Update the owning topic contract when expected behavior changes; changing a
  test alone does not redefine the product.
- Do not convert pending manual scenarios into completed validation based on
  compilation, document review, optional-API loading or unit-test seams alone.
