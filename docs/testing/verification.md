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

### Render entity lookup and locator resolver seams

`RenderEntityLookupCacheTest` and focused locator-resolver unit seams cover the
render-only lookup algorithm and locator resolver boundary. The cache tests
exercise idle passes without scans; distinct,
repeated-hit, and miss UUID requests sharing one cold index; first-valid
duplicate selection; simulated HUD/outline warm reuse across passes; a different
world object with the same dimension; removed or unloaded entries; runtime-ID
reuse and UUID mutation; same-UUID replacement and a spawn after index creation
becoming visible in a later pass; an invalid warm entry causing one scan and
index build; pruning without touching an old entry during validation; clear/null-frame
handling; and null-world and null-UUID safety.

`EntityOutlineLocatorResolverTest` covers UUID and XP locators, runtime
non-orb rejection, gone/null/mismatched entities, map lookup, and the raw-cache
entity-mismatch seam. Neither this cache test nor these resolver tests are a
dragon-renderer test or establish canonicalization integration beyond their
stated seams. The cache fakes and access counters establish lookup-algorithm
behavior only: they are not actual Minecraft frame hooks, HUD callers, or
renderers, and they do not automatically cover the fresh non-render lookup
bypass. Source inspection remains useful for those call-site boundaries but is
not automated runtime coverage.

### Sable integration coverage

The linked [Sable integration](../integrations/sable.md) topic remains the
behavior owner for [client capture and presentation](../integrations/sable.md#client-capture-and-presentation),
[server validation and materialization](../integrations/sable.md#server-validation-and-materialization),
and [refresh lifecycle](../integrations/sable.md#refresh-lifecycle). The
following focused seams provide limited structural, locator-codec and
diagnostic evidence:

- `SableClientCompanionAccessContractTest` statically parses the compiled
  access-class constant pool, requires the exact
  `SableCompanion.getContaining(Level, Position)` symbol, and excludes the
  exact names `getClientLevel` and `getContainingClient`;
- `SableExternalBlockLocatorTest` covers representative encode/parse
  round-trips and selected malformed, noncanonical, and out-of-bounds cases;
- `SableRefreshLogGateTest` checks decision state for tested locator or reason
  changes and duplicates, rather than a refresh operation or log sink;
- `SableDiagnosticsTest` and `SableServerDiagnosticsTest` check selected event
  metadata and record fields, including same-throwable identity for a server
  exception and constructed-invalid or `LinkageError` cases;
- `SableClientDiagnosticsTest` checks an empty capture result when Sable is
  absent and diagnostic presence from explicit `logCaptureFallback` calls with
  a reason; and
- `SablePresentationLogGateTest` checks cadence, capacity, repeated
  failure-class key de-duplication, and throwable identity.

These unit and bytecode seams do not load a Sable runtime or establish the
provider, materialization, tracking-point reference, live-sublevel refresh,
multiplayer, or in-game behavior described by those topic sections.
The external model and fallback routes also resolve provider presentation
independently; therefore this evidence cannot guarantee that provider-local
multipart or subject-type decisions came from one immutable shared snapshot.
That is an implementation-conformance and automated-coverage gap, not an
external exception to the shared-subject contract in
[presentation subjects](../rendering/presentation_subjects.md).

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

## Build, source-set and artifact verification

The included Gradle projects are `common`, `fabric`, `forge`, and `neoforge`.
Their loader source sets receive common Java and resources through the shared
loader wiring. Fabric retains the common mixin configuration and Loom-generated
intermediary refmap; Forge and NeoForge use their loader-local official-Mojmap
configuration instead. This routing identifies existing tasks and artifact
purposes only. Public build orientation and commands are listed in the
[repository README](../../README.md#install-build-and-verify); the tracked
[geometry pipeline](../architecture/geometry-pipeline.md) is the public
architecture entry point. Local agent instructions, when present, are
supplementary execution guidance rather than a public documentation prerequisite.

| Module or artifact scope | Task | Purpose |
| --- | --- | --- |
| `common` test source set | `:common:test` | Runs the common JUnit Platform tests, including shared behavior and integration seams. |
| `neoforge` test source set | `:neoforge:test` | Runs the NeoForge JUnit Platform tests, including NeoForge-specific resolver coverage. |
| Affected loader source set | `:fabric:build`, `:forge:build`, or `:neoforge:build` | Builds the affected Fabric, Forge, or NeoForge source set and its loader jar. |
| All shippable loader artifacts | `verifyModIdentity` | Depends on all three loader `build` tasks, then inspects the expected Fabric, Forge, and NeoForge jars in their loader `build/libs` directories for fork identity. |

## Known automated gaps

The following gaps remain open until direct evidence closes them:

- the shared client/server `entity_block` classification path end to end;
- application of synchronized rate policy on reconnect and on effective live
  configuration change;
- server-side sanitization of negative rate-policy values; and
- detailed diagnostic behavior in the private
  `CreateEntityOutlineAdapter.EntityDiagnostics` path.

Render-entity lookup gaps remain for same-dimension world unload/rejoin and
runtime-ID reuse in a game session, shared epochs between real HUD and outline
callers, and integration proof that non-render callers bypass render-path cache
results. No automated evidence currently establishes CPU frame cost or
allocation behavior for one, ten, or fifty entity marks in a dense world at high
frame rates.

Sable-specific gaps remain for:

- installed-Sable API compatibility and client capture/presentation against a
  live sublevel, including the established
  [logical-anchor/render-pose boundary](../integrations/sable.md#client-capture-and-presentation);
- provider materialization, tracking-point reference counting, rollback and
  release, including the existing empty-audience cleanup path documented under
  [server validation and materialization](../integrations/sable.md#server-validation-and-materialization);
- live-sublevel refresh through the documented available, temporarily
  unavailable, and invalid outcomes in the
  [refresh lifecycle](../integrations/sable.md#refresh-lifecycle); and
- end-to-end server-authoritative names, fail-soft behavior, and multiplayer
  marker synchronization at the boundaries owned by
  [Sable](../integrations/sable.md#names-permissions-and-diagnostics) and
  [target validation](../authority/target_validation.md).

Coverage of Flywheel diagnostics or another adapter's diagnostic helper does
not close the private `EntityDiagnostics` gap.

### Feedback-review evidence boundaries

The following inventory records evidence identified during the documentation
review. It is not a claim that these tests were run for the current change.

- **G1 — marker lifecycle:** `ClientMarkerStoreTest` covers stable marker IDs,
  same-ID updates that do not renew the display deadline, stale handling,
  `EXPIRED` versus other removal reasons, hard deletion, tombstones and delayed
  creates, and the distinction between `winnerId()` and `renderMarkers()`.
  `ClientConfigBoundsTest` covers the display-duration sentinel and bounds;
  `SyncDurationPolicyTrackerTest` and `ServerMarkerStoreTest` cover the
  server-side duration/expiry seams. A live client/server session covering
  synchronized lifetime, display lifetime, stale rendering and winner fallback
  together remains a gap.
- **G2 — selection policy:** focused input tests cover GUI suppression, physical
  press de-duplication through `ToggleInputStateTest` and selection-policy
  defaults/validation through `ClientConfigTargetSelectionTest`. Configuration
  reset/persistence behavior is covered by `ConfigHandlerResetTest`, but the
  code path can report a lock or save failure; this does not justify claiming
  persistence is absolute. `EntitySelectionBlacklistTest` and
  `EntitySelectionBlacklistDefaultRuleTest` cover the entity-selection
  blacklist separately from the block-shape display blacklist, including
  spectator filtering. A live callback/input session and cross-loader key-repeat
  behavior remain pending.
- **G3 — Simulated integration:**
  `SimulatedDockingConnectorPresentationResolverTest` covers the stand-in
  resolver's connector IDs, facing/opposite-facing relationship, powered owner,
  owner block-entity type/registry identity, and handled-empty failure result.
  This is a resolver seam, not a live Simulated-mod session; loader registration,
  installed-mod behavior and in-game presentation remain unverified.
- **G4 — configuration versioning:** focused versioning tests cover the required
  non-empty `pingforit-version` marker, same-version loading, migration threshold,
  `pingDuration` to `syncDuration` migration with the new key taking precedence,
  client/server recovery differences, and protection of future-version files.
  `ConfigVersionUpdaterTest` and `ConfigHandlerVersionTest` are existing
  automated coverage inventory; no test command was run for this documentation
  change.
- **G5 — audience ownership:** `ServerCore` owns the complete creation gate and
  recipient snapshot. The matrix is: an empty channel applies `AUTO`,
  `TEAM_ONLY` or `GLOBAL` semantics (with `DISABLED` rejecting creation), while
  a non-empty channel selects matching channel members without reapplying the
  empty-channel team filter. `TeamContextHandler` resolves Voice Chat before
  FTB Teams before vanilla team, with `NONE` when no context exists; its
  same-context rule includes the no-team case. Existing marker-store and packet
  tests cover recipient-scoped state, audience shrink/cleanup and channel-mode
  data transport, but they do not provide a complete `ServerCore` branch matrix
  or live Voice/FTB/vanilla context coverage. This is a coverage/verification
  gap, not an absence of an implementation owner.
- **G6 — names and chat:** `PingChatBuilderTest` covers the three required
  placeholders (`playerName`, `pingType`, `targetName`), unknown or isolated
  placeholders, `{{`/`}}` escaping, missing required tokens and direct legacy
  fallback. Name-resolver coverage includes the plain ServerPlayer profile name
  boundary. Localized names, custom-name combinations and every locale/resource
  fallback boundary remain pending scenarios.

## Pending manual and integration matrix

No scenario in this matrix is recorded as performed merely because it is listed
or because related automated tests exist.

| Area | Pending scenarios |
| --- | --- |
| Block | Plain `block` versus `entity_block`; `ALL`/`COMPATIBLE`/`VOXEL_SHAPE_ONLY` modes and source fallback; whitelist native glow and fallback; a non-full native shape; same-type state change versus block-type replacement. |
| Entity | Ordinary entity and dropped item; movement and same-dimension teleportation; death and disappearance; same-dimension world unload/rejoin and runtime-ID reuse in a game session. |
| Wheel | Short and long press; every sector and border color; 5000 ms timeout; frozen target; location fallback. |
| Movement, death and replacement | Target movement while the wheel is open; entity death or dimension change; block state change or replacement while open. |
| Naming and chat | Custom-name formatting; localized base names; item naming; phrase-only text color. |
| Cancellation | Cone and nearest-own-marker selection; inability to cancel another player's marker. |
| Multiplayer | Same-target latest-server-arrival winner; equal-arrival larger-Marker-ID tie; winner fallback after removal or expiry. |
| Settings and config | External edits do not reload in-session and apply after restart or explicit reload; invalid-config recovery and preservation lock. |
| Rate policy | Synchronization on reconnect and on effective live configuration change. |
| Optional content and rendering | Absent or partially present optional content; Create/Flywheel routes; occlusion and arbitrary camera angles; current shape, offset and seed. |
| Render entity lookup | A real frame epoch shared by HUD marker updates and optional outlines; fresh non-render lookups after render misses; CPU-frame and allocation measurements for 1, 10, and 50 entity marks in a dense world at high FPS. |
| Create contraption raycast | Hollow, sparse and overlapping contraptions; world-wall ordering; all transparent/fluid policy combinations including waterlogging; moving/rotated, minecart-mounted, carriage and gantry forms; portal-hidden or loading data; held press-time target; Create-absent and delegate-unavailable paths; large-structure press cost. |
| Sable external blocks | An installed-Sable client/server session covering [candidate capture and presentation](../integrations/sable.md#client-capture-and-presentation), [server materialization and release](../integrations/sable.md#server-validation-and-materialization) after removal, expiry, owner disconnect, and empty-audience cleanup, [live-sublevel refresh outcomes](../integrations/sable.md#refresh-lifecycle), names and fail-soft behavior, and multiplayer create, refresh, and removal. |

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
