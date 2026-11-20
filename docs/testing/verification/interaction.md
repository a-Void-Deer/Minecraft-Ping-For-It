# Interaction verification

This page inventories capture, selection, input, radial-menu and inventory-list
interaction coverage and its known automated gaps. Read the
[verification guide](README.md) for the shared evidence and reporting rules.
Pending gameplay checks remain in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Capture, wheel and cancellation

`PingInteractionStateMachineTest`, `PingCaptureCoordinatorTest` and
`PingSelectorReleaseTest` cover short and long press, pending asynchronous
capture, wheel state transitions, lifecycle abort, the actual wheel-open
boundary, release adjudication, stale or superseded completion fencing,
first-completion ownership, identity-preserving metadata retention, single-use
terminal outcomes and finite DEBUG reasons.
`TargetSnapshotTest`, `TargetSnapshotBlockClassificationTest`,
`MinecraftTargetSnapshotFactoryDetailedTest`,
`MinecraftTargetSnapshotFactoryFaceTest` and `MinecraftSyntheticBlockFaceTest`
cover snapshot identity, copied metadata and frozen context, capture-face
eligibility by target kind, block-entity classification, matching entity-hit
detail, all six resolved faces and the absence of a synthetic face after copies
or coordinator completion. `PingCaptureCoordinatorTest` adds face retention only
when resolution preserves the captured ordinary-block identity, including the
exact external read binding. These are factory and coordinator seams; the
complete client/server `entity_block` classification path, including
synthetic-face provenance from every optional producer, remains unexercised.

`LongPressCompatibilityControllerTest` covers ordinary rapid-click and deferred
compatibility, including enabling the mode during an active pre-enable baseline.
`LongPressCompatibilityDispatchTest` drives the real baseline lifecycle,
courtesy limiter and dispatch receipt: rejected or unready first creates cannot
seed rapid input or queue, retry or replay deferred presses, non-`CreatePing`
results discard the deferred press, an actually opened menu disqualifies even a
sent default, and successful receipts start the rapid second-ray and deferred
fresh-press captures. These are controller and config slices; real input
callbacks, the focus-loss `KeyMapping.releaseAll` hook, screen and level
lifecycle, loader gameplay and genuinely asynchronous completion remain
unverified. The abort-plus-late-completion seam is covered by
`PingSelectorReleaseTest` and `ClientPingRuntimeInteractionTest`.

`ClientBusinessDiagnosticsTest` covers failed ordinary resolution and
optional candidate finalization warnings, restricted-capture context,
incomplete or missing precise slots, selector preview and close failures, chat
template lookup and marker-display consumption. A separate
`ClientPingRuntimeInteractionTest` case records presentation-not-ready for an
authorized property release with a pending reset preview. These are headless
coordinator, refresh-controller and logging seams, not live input, native world
scanning or a Distant Horizons trace.

### Cancellation and retained-winner interleaving

`CancelCandidatePickerTest` covers press-ray cone filtering and nearest-candidate
selection. Store-level owner, dimension and authoritative-expiry checks for
local dismissal are inventoried under
[identity and authority](identity-authority.md#client-marker-runtime-coverage).
The live candidate collection behind the runtime port — real Minecraft entity
lookup by UUID or runtime id and the runtime choice between that live point and
the presentation/anchor fallback — is source-inspected, not covered by a
dedicated runtime case.

`ClientPingRuntimeInteractionTest` covers the cancellation path through real
wheel release, runtime dispatch and store, with candidate positions, time, mouse
ownership, asynchronous completion and transport as test ports. The physical
press that supplies the aiming ray locks the nearest own stored marker or the
empty result; opening and focusing send nothing, and confirmation sends at most
one request for the locked ID. Its cases show that:

- `unpingKeepsTheMarkerThatWasInTheConeAtPhysicalPress` covers a ready selector,
  a pending asynchronous capture and an invalid-viewport-delayed open: each
  keeps the press-time marker after later candidate and ray changes make another
  own marker eligible;
- an empty lock does not acquire a marker that appears before release, and an
  aborted or abandoned interaction cannot lend its lock to a new press;
- a locked marker already recorded by the client as authoritatively removed
  sends no request and does not fall back to another own marker;
- an owner's local dismissal of an authoritatively expired, still-visible
  marker, whether the expiry precedes the press or arrives before confirmation,
  sends no `REMOVE`, removes the local record, and a delayed creation cannot
  revive it;
- the two parameterized arrival orders of the expiry and `NOT_FOUND` response
  for one active Unping are handled;
- a remove denial or stored-owner mismatch leaves the expired record in place;
- `ClientPingRuntimeInteractionTest#fallbackStaleOwnUnpingSendsOneRemoveWithoutDismissingTheVisual`
  covers an own candidate that is `STALE` only through local packet-loss
  fallback: one `REMOVE` is sent and the visual remains until an authoritative
  `CANCELLED`, rather than being dismissed without expiry evidence.

Compatibility and Danger entry points keep a separate locking edge: the rapid
second physical press supplies the cancel ID while its virtual hold keeps the
first press's timing, and later clicks only extend that hold; a deferred fresh
press locks from its own raw edge. A press that terminates a virtual wheel locks
the new interaction before the closing wheel dismisses its already-expired
choice, including the case where that marker is still nearest; the new lock
therefore reflects the pre-dismissal candidate positions.
`overdueVirtualInventoryWheelGivesThePhysicalPressToTheStayingSession` covers an
overdue virtual inventory wheel settled by the press or the frame first: both
retain the original selector token and preview, start no ordinary capture or
Unping snapshot, and the same tap commits the original inventory `SELECT` once.
The held adjacent Danger tap uses its own physical press for wheel timing and
the lock. An ordinary press keeps its lock across Precise branch entry and exit.

At the state seam, `PingSelectorReleaseTest` covers a locked cancellation
emitted only for the confirming release and only once, with duplicate and wrong,
stale or superseded tokens unable to consume it or fall back;
`PingInteractionStateMachineTest` covers center confirmation with and without a
locked candidate.

`ClientPingRuntimeInteractionTest` covers retained-winner interleaving across a
same-connection view reset: old assignments are retired while a stored record's
last winner survives either the expiry-first or empty-winner-first order;
old-view winner and removal packets are rejected; a new-view non-empty
assignment replaces the tail; a reset drops an assignment with no matching local
record; and session close or a new epoch discards history and removal evidence.

The fallback-only case receives `CANCELLED`; it does not exercise a rejection
of that `REMOVE`. These port tests do not exercise real input delivery,
transport, server adjudication or multiplayer, or the separate natural-expiry
eviction and same-ID replay path. The live press-edge and moving-target scenario
remains pending in the [manual matrix](manual-integration.md#wheel).

### Gesture shortcut opening

`GestureSkipHoldTest` covers the passive pre-open input model: inclusive minimum
wait, exclusive center deadzone, GUI-scaled net displacement rather than
historical travel, an elapsed-time check without a new callback, ordered
single-use history, foreign or non-finite samples, and scale or ownership
handoffs. Its resource-bound cases cover the last retained and first excess
sample, immutable handoff, whole-history discard and recovery on a new hold.

The gesture cases in `ClientPingRuntimeInteractionTest` cover early crossing and
held frames, crossing after the wait, the frozen deadzone and wait under live
edits, an independent ordinary long press, return-to-center or short-flick
release, disabled and compatibility modes, pending capture and viewport gates,
release observed on a frame, reset, screen, focus, level and dimension aborts,
frozen press ray and target, one-time selector entry, dwell and trail history,
native ownership at opening, reset or handoff priming, and compatibility
enabling during a pending gesture. The alternative-eligibility cases in
`PingInteractionStateMachineTest` cover capture readiness, held present, token
ownership, release precedence and abort. This is headless input-model,
runtime-port and state-machine evidence, not live camera, rebound-key delivery,
GLFW ownership or loader gameplay; the
[manual gesture scenarios](manual-integration.md#pending-manual-and-integration-matrix)
remain pending. Configuration persistence is under
[configuration verification](configuration-ui.md#inventory-and-spatial-selector-configuration).

## Precise live candidate capture

Headless tests cover
[Precise live candidate capture](../../architecture/picking/capture.md#precise-live-candidate-capture):

- `FrozenCandidateAcquisitionTest` and `CandidateCaptureLifecycleTest` cover
  allocation and de-duplication, retained class and face, nearest
  same-category competition and tie order, missing versus incomplete evidence,
  an incomplete scanned class staying unavailable even when the ordinary result
  matches it, specific and generic class consumption, provider-local equivalence,
  immutable evidence, near-contact same-identity results, and same-token,
  duplicate, superseded, aborted and reentrant completion fences with ordinary
  readiness when finalization fails.
- `NativeBlockCandidateScanTest`, `NativeBlockCandidateRegressionTest` and
  `NativeBoundaryFaceProvenanceTest` cover native block and fluid shapes behind
  blockers, finite-segment and loaded access gates, actual surface coordinates
  and faces on long oblique rays, moving-piston access, origin containment, no
  adjacent owner expansion, and boundary contact provenance through the
  production scan, snapshot factory and frozen allocation. The traversal cases
  also reach late supported stone after many loaded neighbor reads and keep a
  later hit on another axis reachable when one delta component is subnormal
  within a cell. An endpoint outside the integer grid leaves the tested scan
  incomplete before its first cell read. A malformed shape after a provisional
  hit also leaves the scan incomplete.
- `NativeEntityEnumerationTest` drives the raw visible entity lookup at fixture
  level: the lazy `getAll` view returns off-ray entries without an
  entity-section walk, and a real iterator fault after a usable provisional
  entry propagates so the partial iteration is not treated as completion.
- `RaycastSupplementalCandidateTest` covers farther entity candidates,
  exact-owned non-hit rejection without coarse fallback, frozen geometry
  ownership, selection filters, and canonical multipart and XP locators; the
  exported report seams remain finite and failure-bounded. A later native read
  failure leaves both scanned block categories incomplete. The ordinary trace
  retains its original hit; the derived Location uses the nearer contact already
  observed on the same block. The multipart case is a factory fixture, not a
  dragon-world or rendering test.
- `PreciseCandidateTraversalTest` drives the public `traceDirectionalCandidates`
  path against a constructed headless client level: 2,048 raw entries repeat one
  off-ray entity, the fixture registers no exact owner, and the only eligible
  entity lies beyond the ordinary native segment. The ordinary result stays a
  `MISS`, the certified supplemental hit carries that entity's identity, and the
  duplicates add no candidate. A further case places the target's inflated pick
  box around the ray origin: the ordinary trace selects that entity at the
  origin, a zero-length ordinary segment still reports `MISS`, and the Precise
  scan certifies the same entity at distance zero with one evidence entry, so
  the camera is excluded. Real `Minecraft` view-vector cases at a yaw of -45
  degrees accept a center exactly 60° from the look direction and reject
  adjacent representable centers one double-precision step outside in both
  ordinary and Precise results; one rejected case would still tie under rounded
  squared-distance comparison.
- `PreciseCaptureRefreshTest` covers the production refresh controller:
  immediate entry then the frozen period, re-entry not duplicating the first
  scan, pause, restart and stop, native slots before the location request,
  per-slot ray and pending value, final missing or incomplete replacement
  without ordinary fallback, one in-flight optional request with latest-work
  coalescing, stale, duplicate, foreign and post-end fencing, and no optional
  work after reentrant exit.
- `SpatialSelectorSessionTest`, `PingSelectorReleaseTest` and
  `ClientPingRuntimeInteractionTest` cover the session, release and runtime path:
  revised publications preserving slot focus, origin and color, only painted
  current nodes acknowledging, an empty acknowledgement not authorizing,
  disabled paint clearing older selectable versions, the `PRECISE_PRESENTED`
  admission and its fences, the press-ray alternative, an ordinary press staying
  frozen while precise targets change, and releasing the last painted candidate
  without recapture.
- `SpatialOverlayRendererPresentationTest`, `SpatialOverlayRendererFollowerTest`
  and `SelectorPreciseTargetLabelsTest` cover paint-admission predicates, pointer
  following and eased movement, connector geometry, crosshair and
  acknowledgement gates, and labels resolved from the bound snapshot. A failed,
  transparent or quantized-zero paint cannot hide the crosshair or acknowledge a
  choice. These are recording-port frame and helper checks, not `GuiGraphics` or
  GPU submission.

The period key's config and persistence coverage is under
[configuration verification](configuration-ui.md#inventory-and-spatial-selector-configuration),
and the transformed Sable scanner has its own
[integration evidence](integrations.md#sable-integration-coverage). Game-world
and chunk lifecycle, full client ticks, installed-provider behavior,
multiplayer, loader input callbacks and GPU/render submission remain
unexercised; unbounded-geometry wall-clock behavior remains unmeasured, and the
manual Precise scenarios remain pending.

## Selection-policy and input-state seams

The [selection policy](../../architecture/picking/selection_policy.md) has
focused unit and state-seam evidence:
`ToggleInputStateTest` covers the native callback `GLFW_PRESS` origin and the
non-repeat gate: one press authorizes one vanilla mapping click; a screen at
either the callback origin or the mapping destination prevents a toggle;
`GLFW_REPEAT` and focus loss cannot become a new press; and a release permits a
fresh focused press.
`NativeSelectorMixinContractTest` adds the keyboard and mouse toggle hooks: the
HEAD origin observes GLFW's action parameter before UI or loader consumption,
the argument modification preserves the native key, and only the vanilla
mapping click is gated. `ClientConfigTargetSelectionTest` covers
model-initialized target-selection choices, block mode, JSON round trip and
missing or null transparent-block choices. `EntitySelectionBlacklistTest` covers
registration aggregation, idempotent removal and the optional Create boundary;
`EntitySelectionBlacklistDefaultRuleTest` checks the exact default
`simulated:honey_glue` and `tiab:accelerator` IDs and representative near misses.

`PingClipContextTest` covers the exact `cyclic:glass_connected`,
`cyclic:glass_dark` and `cyclic:glass_dark_connected` IDs, `VISUAL`-only
pass-through, native `OUTLINE` selection, near misses, fluid independence and
the shared native candidate scan. `NativeLocalShapePolicyTest` adds local
scanner pass-through and selection of a target behind the matching block.
These fixtures supply IDs through a resolver seam; no unit test uses actual
loaded Cyclic blocks through the registry resolver. The ordinary world-clip
route has source and bytecode review, not end-to-end test coverage. No live
Cyclic or TIAB validation is recorded; the scenarios remain in the
[manual matrix](manual-integration.md#cyclic-and-tiab-selection).

The blacklist is distinct from block whitelist, block-shape and display-blacklist
behavior, and is not raycast-filter coverage: spectator exclusion is implemented
by `Raycast`, not `EntitySelectionBlacklist`. These unit seams do not establish
live GUI or screen callbacks, vanilla saved-binding restoration or
loader-specific physical key-repeat behavior.

## Exact entity-local picking and Create raycast seams

Focused coverage exercises an immutable entity-local geometry owner snapshot;
owner resolution and `HIT`/`MISS`/`UNAVAILABLE`/`FAILED` candidate rules;
metadata retention only for the matching resolved identity; native local
block/fluid shape policy and the exact kind tie; the common native local-shape
scanner and candidate pipeline; and `EntityLocalGeometryRegistryTest` for
duplicate-first-kept, null-result and callback-failed candidate reporting, with
source ID and priority visible only at higher disclosure, a disabled backend
collecting nothing, and registration or snapshot progress while a warning sink
is blocked.

`EntityParentFrameTest` covers detached affine frame validation, remote
fractional point projection and anchor-only parent scaling.
`NativeLocalShapeTraversalTest` covers the native local-shape traversal: the
nearest surface can come from the last of many disjoint native boxes on a long
shape, and a later real neighbor-read failure rejects the earlier provisional
local hit instead of returning it.
`LoadedExactCandidateTest` covers native-first ordinary discovery, loaded
supplement only for a first-owner `LOADED_EXACT` owner, identity deduplication,
and exact endpoint exclusion. `PickingFrameContextTest` covers an
ordinary-world block retaining its point under optional pose-scope failure, an
unproven contained block retaining its native result without a world point or
arbitrary entity replacement, and detached block proof.
`RaycastCandidateFlowTest` covers projected native-block world competition
while preserving the native face/proof and owned non-hit rejection. Its
origin-contact cases cover an inflated pick box containing the press origin: a
45-degree diagonal center competes at the origin and the first origin contact
in iteration order wins the equal-distance tie, while a center at the cone's
apex is eligible; the inclusive 60-degree edge is accepted but a center just
beyond it, a center behind the ray, an exterior box the ray misses, a start or
end on the inflated face and a zero-length segment still miss. An origin inside
the pick box does not revive an owned `MISS`, `UNAVAILABLE` or `FAILED` outcome,
and every traced candidate still reaches its owner; an owned exact surface can
win when the origin is inside and the box center is behind. These are headless
entity fixtures, not live entity selection, loader input or a rendered frame;
no passing run is recorded here.

These tests preserve whole-entity identity and exercise the non-optional engine
boundaries. They do not constitute in-game Create or Aeronautics validation.
Create-specific coverage is under
[Create raycast seams](integrations.md#create-raycast-seams); Aeronautics
provider-specific coverage is under
[Aeronautics rotor coverage](integrations.md#aeronautics-rotor-coverage).

## Capture and acceptance range

`ServerConfigBoundsTest` covers server range clamps,
`RaycastCandidateFlowTest` covers native candidate and raycast seams, and
`OptionalDependencySafetyTest` covers absent optional-integration safety. Its
isolated class loader proves the disabled Distant Horizons boundary resolves
and returns no completion with the Distant Horizons API absent; the Common test
runtime also carries the pinned Distant Horizons 5.1.0 test-scope API, so
absence is not established by the whole test runtime.

`DistantHorizonsWorldTraceTest` drives the production asynchronous adapter with
recording API proxies for the pinned Distant Horizons API. It covers choosing the
captured client world when another world of the same dimension type is loaded,
single-player integrated-server and client-alias binding, no trace start when
the match is not unique, and an unrelated, absent or disappearing world.
Per-world terrain-cache isolation after replacement is also covered.

`DistantHorizonsWorldTraceTest` also drives an injected clock over one client
level and Distant Horizons data source: the same binding reuses a cached hit
until the cache expires, then replaces the cache and reads fresh terrain on a
later trace. If allocation fails, that trace returns no hit. The idle expired
cache is cleared once, and the next trace can read fresh terrain. With two
readers still using the expired cache, both return their original hits and the
cache is cleared once, after the last reader finishes.

On the same client level, a binding change before the client-thread callback
does not publish the old result. A failed retired-cache cleanup does not block
the replacement trace or overwrite a valid in-flight result, and the stale hit
is still discarded by that binding fence. The [Distant Horizons
integration](../../integrations/distant-horizons.md) owns world
matching and terrain-cache lifetime; the ordinary and Precise captures retain
the generation and level fences described by
[capture](../../architecture/picking/capture.md). These are headless API
fixtures, not an installed Distant Horizons runtime or a live single-player or
multiplayer session.

`ClientPingRuntimeNativeRangeTest` drives the production capture and native
trace against a headless client level. An ordinary miss where Minecraft's
lookup-table view vector would place the scaled endpoint outside the squared
limit keeps its captured location inside that limit and on the captured ray
while still reaching the boundary to world-coordinate precision; the synthetic
miss path used when the trace yields no result produces the same location.
At large absolute world coordinates, where an exactly normalized direction
still overshoots after coordinate rounding, the native segment and the
separately bounded supplemental scan each end within their own limit, the
block-capture provider receives the bounded supplemental endpoint, and the
ordinary result remains a `MISS`. An exactly representable boundary miss is not
shortened, and a native block hit keeps its contact and face instead of
becoming a range endpoint. The same fixture drives the real Precise Distant
Horizons adapter: the native scan requests the distant completion, the adapter
receives the raw captured ray and the integration's own longer range, and a
camera turn before the callback does not change the request's bounded no-hit
endpoint, which matches the ordinary location.
`MinecraftAuthoritativeTargetValidatorTest` carries the represented-boundary
concern into acceptance: at a large-coordinate edge the production validator
accepts a Location target on the boundary and rejects the next representable
outside point as `OUT_OF_RANGE`. These are headless level, runtime-port and
recording-API fixtures, not an installed Distant Horizons runtime, packet
exchange, live world or multiplayer session; no passing run is recorded here.

Source and focused seam evidence establish the native
`min(raycastDistance, pingDistance)` limit and its represented-endpoint cap, the
independent Distant Horizons trace, the server acceptance range, Create and
Sable reuse of the finite native segment in the ordinary trace, and the
hold-frozen client `pingDistance` bound of the Precise live casts. No automated
end-to-end test exercises the entire client capture, optional-provider, packet
and authoritative server-acceptance pipeline.

## Back-hover return state

`BackHoverControllerTest` covers the headless Back-hover timing model: progress
before the threshold never triggers, the threshold triggers exactly once, a held
focus never repeats, leaving and re-entering restarts the clock, a parent Back
stays blocked through held focus, observed focus loss and motion within Back,
and only an effective physical leave followed by re-entry permits rearming.
Switching the focused menu restarts the clock, a rewound clock clamps rather
than resetting, `end` clears the timer and block idempotently, a fresh session
is unblocked, a disabled session never arms or triggers, pre-start updates are
inert, and hover parameters freeze at session start. It is a standalone model
seam: no native screen, input route, renderer or in-game timing is exercised.

## Headless spatial radial menu

`SpatialControllerTest` covers the headless radial session: deadzone
abandonment, single-use release, exactly one focused commit, reserved, disabled,
navigation and actionless exclusions, sector boundaries and the 0/360 wrap,
outside no-action, dwell and turn entry, hover or Back one-level pop with a fresh
stroke requirement, cancel, pushed submenus, automatic Back, entry breadcrumbs
and root pruning. Its viewport cases cover inclusive pixel-centre clamping,
saturation without overshoot or travel debt, effective movement only, non-finite
inertness, resize or pan correction not completing dwell, held-Back rearming and
trail bounds. Programmatic circumference placement keeps the frozen deadzone,
admits no focus, stays pending while unreachable, pays no fresh stroke on
resolution and is canceled by fresh physical movement. User returns admit the
trigger pointer, translate retained parent and row anchors, remove one level,
seed one trail point, clear stale focus and preserve the held-Back block;
programmatic returns and root pruning leave placement and focus intact. External
relayout restarts pending dwell without travel, leaves unchanged deadlines,
re-evaluates focus, clears stale turn corners and preserves the held-Back block.
Renderer-facing sample identity separates effective input, projection alignment,
rebase discontinuity and external-menu entry. `SpatialPreferredBearingTest`
covers preferred non-Back placement over zero through twelve siblings, equal
spans and tiling, the inclusive hit convention, explicit Back, disabled choices
still receiving placement, and root-fixed geometry.

`SpatialSelectorSessionTest` covers the production headless facade: the frozen
root menu and typed intents, catalog outline colors, supplied precise
allocations, broad-slot canonical types, single-use release, next-capture
toggles, property values and annotations, inventory submenu and list behavior,
lifecycle fences, face admission and retention, declared content navigation
groups, ordering, list Back, and OFF/ON content intent.
`NativeSelectorInputTest` covers input normalization, primitive and wheel
ownership, scroll callback handling, balanced horizontal movement and fractional
samples. `NativeSelectorContentTest` covers the production content bridge against
a real headless client inventory session: opaque references, explicit zero and
quality, lane-local rows, close cleanup, reentrant abort, label fallbacks, kinetic
and RPM projection, ping-type policies, block-state and Create namespace groups,
and exact child-denied omission. `SelectorToggleLabelsTest` covers the three
target-selection label concerns and their plain name/separator with a colored
state word.

These are headless model, session, input-normalization and client-facade seams;
the model sections exercise no native screen, renderer, input callback,
configuration or world-candidate source, and the content bridge is not native
input or HUD evidence.

## Native client interaction runtime

`ClientPingRuntimeInteractionTest` drives the production interaction runtime
through headless world, cursor and clock ports: press-time ray and target freeze,
pending completion not opening or starting a preview, one default create on
release, abort invalidating late capture and synthetic release, center release
abandoning without cancellation, list release sending `SELECT` then `CLOSE`
without a plain fallback, a long-held selector not auto-closing, reset and late
preview not reviving or selecting, precise release consuming the supplied
candidate, distinct broad block and entity slots, property create and denial,
single toggle commits, deferred compatibility receipts, resize and invalid-frame
repriming, held preview and late-response fences, an old capture not clearing a
new selector, rapid receipts keeping the second ray, press-frozen spatial
settings, invalid viewport refusal, a reentrant abort blocking release packets,
cancellation readiness before negotiation and after `OFFER` alone without
`REMOVE`, a later ready cancellation sending once while the following create
retains the sole allowance, a nearer same-identity precise contact preserving the
inventory face, and an external-block press carrying its provider-local face. It
also covers native-only ownership handoff, resize before an immediate release,
saturated raw baselines, frozen trail and style preferences, and the
inventory-fraction press path. The press-edge Unping lock and its compatibility,
Danger and overdue inventory-wheel cases are inventoried under
[cancellation and retained-winner interleaving](#cancellation-and-retained-winner-interleaving).

The same suite covers follow-server marker timing across the two parameterized
sample/winner orderings, observed progress versus an extended local pause and
client ticks, fallback from the latest upsert with local grace, a delayed
same-ID upsert, and the eviction boundary. Its
`clientLevelChangeEntryUpdatesTheObservedWorldBeforeTheNextFrame` case covers
immediate old-world interaction abort and no repeated abort on the next tick; it
drives the runtime entry directly, not the shared `LevelRenderer.setLevel`
injection or a live game/heap unload.

`ClientPingDispatchReceiptTest` covers actual-send receipt and transport-failure
rollback. `WheelMouseCaptureLifecycleTest` covers native `DISABLED` ownership,
cursor synchronization, normal-close grab reclaim versus a free-pointer close,
pending-look clearing, screen-owned cursor refusal, scroll consumption and
ownership fences; `WheelMouseCaptureTest` covers the lifecycle action matrix and
press-time grab guard; `NativeSelectorMixinContractTest` covers the mapped
callback ABI and fields, release redirect, press-time guard and per-loader
manifest registration.
These are recording-seam checks of the
[mouse-ownership contract](../../architecture/picking/wheel.md#mouse-ownership)
and production-orchestration, recording-port and source-reviewed test-existence
seams, not a live client run, Mixin application, native Windows/GLFW cursor or
desktop behavior, GPU submission or multiplayer verification.

## Inventory list gestures and navigation

The behavior contract is owned by
[wheel interaction](../../architecture/picking/wheel.md#inventory-list); the
following records source-reviewed test responsibilities, not an execution result.

`InventoryGestureTest` composes the gesture helper with a real headless
controller: full-list selection at viewport endpoints and intermediate
positions, monotonic movement, unquantized pointer Y, split versus batched
samples, endpoint reversal without overshoot debt, and empty, single-row and
single-pixel viewport safety. Its wheel cases cover signed fractional
accumulation, representative-Y synchronization, X movement during the Y lock,
opposing unlock travel, exact-threshold and crossing samples, split/batched
equivalence, side suppression, and Back-hover clearing partial unlock travel.
Panel and cone cases cover interior and deadzone admission, exterior-only
crossing travel, re-entry progress, stroke measurement independent of the
deadzone, mirrored directions, non-default thresholds, filtering only the current
horizontal sample, edge coincidence, asymmetric and offscreen panels, collapsed
viewports, saturation and immediate reversal. Hover owns Back instead of distance
entry while still allowing Forward; alignment preserves side progress and
partial unlock without selecting or freezing discovery order; non-finite input
leaves the usable wheel fraction intact.

`InventoryListModelTest` covers sorted stable rows, selection and row-window
clamping, huge row steps saturating without overflow and reversing immediately,
a frozen-panel translation preserving entries, selection, window, direction,
status, discovery order, scroll freeze and fractional remainder, inert
non-finite deltas, and capacity relayout keeping the selected key visible. These
are row-model checks; they do not independently establish pointer mapping, side
gestures or input ownership.

`SpatialSelectorSessionTest` adds the production facade: initial screen-top
placement, unsnapped small physical samples, endpoint reversal without trail
growth, unchanged viewport publication preserving pointer and trail epoch, and
discovery retaining the selected key while distinguishing physical scroll freeze
from system synchronization. Viewport corrections retain selection and partial
wheel unlock without side navigation; horizontal and vertical projection
animation preserves pointer, selection and trail epoch while interleaved mouse
deltas check continuous selection. The held non-default boundary case exercises
both mirrored Back/Forward sides with a frozen snapshot despite later live
edits; a mirrored release case checks nonhover Back-side wheel selection versus
Back-hover suppression. The frozen session deadzone reaches the controller read
model unchanged, including a non-default radius. Layout-only row-height and
capacity changes do not recalibrate selection, mapping waits for a real viewport,
a collapsed vertical viewport retains legal selection, and an item Back keeps
the shared held-Back block through raw edge extrapolation, projection changes and
capacity relayout until effective physical leave and re-entry.

`NativeSelectorContentTest` feeds mixed-lane packets through the production
content bridge and selector session: an unrelated fold or lane-baseline
replacement retains a selected lane-qualified key, lane-local scroll and
discovery survive sibling replacement, removed or invalid rows use the surviving
fallback, revocation clears release eligibility, and release intents dispatch
the correct lane and opaque reference once before `CLOSE`. The
[combined-tree execution record](build-checks.md#native-fluid-boundary-and-presentation-verification)
records their passing headless execution.

`SpatialInventoryNavigationIntegrationTest` drives the headless native-input
adapter and a real selector session through production inventory layout,
viewport preparation and frame rendering with a recording painter: list entry
from viewport corners, centering pan and scale handoff, absolute selection and
reversal at both viewport edges, normal and reduced motion, both forward-side
orientations, selection painting after a streamed view update, item-Back return
and resize, crosshair visibility, panel-intent Y suppression, both Back styles,
an oblique slot entry, other GUI scales and deadzone radii, translation to the
returned centre, one trail seed, stationary-frame and deadzone behavior, OFF
content intent, item-clearance repositioning, ancestor breadcrumbs, qualified
turn entry, content measurement reuse, GUI-scale handoff, font/capacity refresh,
and custom fractions in both orientations. Item-submenu placement checks that
the child centre stays diagonally below the selected row by the measured
clearance on entry, clearance update and resize, while the row remains the
return anchor. These are source-reviewed
session/input/layout fixtures, not recorded test runs or native-window delivery.
The fixture does not instantiate the complete `ClientPingRuntime`, a GLFW
window, `GuiGraphics` or GPU submission; native-window and gameplay confirmation
of inventory selection reachability after the item-Back resize handoff remains
pending. Panel, text and Back paint coverage is under
[rendering verification](rendering.md#selector-overlay-layout-and-frame-rendering).

## Double-click Danger and inventory stay-open

Source-reviewed coverage exists for the double-click Danger derivation and the
inventory stay-open selector session; this inventory is not a recorded run.

`LookInputTrackerTest` covers signed net yaw/pitch accumulation with exact
out-and-back cancellation, a generation fence on reset, whole-baseline rejection
of non-finite or overflowing input, vanilla non-smoothed sensitivity and scoping,
strict three-dimensional angles, high-pitch and yaw-wrap endpoints, end-point
pitch clamping, a generation mismatch returning zero, and degenerate-ray or null
input rejection. `RapidDangerControllerTest` covers an undelayed first default
create, completed taps flushed only after the first local dispatch, distinct
`danger` tokens reusing the first target, reentrant abort, a non-qualifying first
outcome clearing dependents, a long candidate using its own press time and ray,
a released candidate remaining a frozen `danger` tap, a look-generation reset,
live compatibility disabling recognition, zero-angle and tolerance boundaries,
the adjacent-press window, an externally turned camera and look input beyond
tolerance.

`InventorySelectionStayControllerTest` covers the first release entering the
stay, a short tap committing, threshold and frame cancellation, path-exit edge
swallowing and full lifecycle reset. `ClientPingRuntimeInteractionTest` adds the
stay and Danger paths: enabled stay selection and close, threshold cancellation,
frame cancellation, item and list Back ancestry, lifecycle abort with
mouse-ownership disposal, reset and policy revocation, held-tap revocation and a
legitimate empty preview keeping the list open; adjacent and later taps creating
independent `danger` requests from the first frozen target, flush ordering,
candidate wheel exceptions, abort and transport-failure clearing, compatibility
enable/disable edges, failed capture, advancing clocks and repeated releases not
double-committing. `SpatialSelectorSessionTest` covers the inventory path
predicate over list and item submenu; `NativeSelectorMixinContractTest` covers
the look-input hooks reading pre-update coordinates. Configuration, catalog,
option-codec and locale coverage is under
[configuration verification](configuration-ui.md#double-click-danger-and-inventory-stay-open-configuration).

These are headless model, controller, runtime-port, session and mixin-contract
seams. They do not establish native callback delivery, Mixin application,
constructed or rendered settings screens, assembled locale labels, GPU
submission, loader gameplay or multiplayer behavior; the pending live scenarios
remain in the
[manual matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Known automated gaps

- the shared client/server `entity_block` classification path end to end,
  including synthetic-face provenance from every optional producer;
- cancellation of a display-hidden or fallback-only `STALE` record without
  authoritative expiry: the path from rejection of its `REMOVE` to keeping the
  local record without retrying another candidate; real transport and server
  adjudication also remain unverified;
- real input-callback and render-frame behavior for rapid and deferred long-press
  compatibility;
- the content-intent toggle lacks a dedicated asynchronous press-edit-to-next-hold
  runtime fixture: config freeze and OFF/ON session behavior are covered, but a
  live edit between press and release is not;
- OFF-mode declared-navigation-group behavior and an inventory whose allowed set
  lacks Attention have no dedicated behavioral fixtures in either mode;
- asynchronous completion after lifecycle abort in a real input or loader
  context, including the token invalidation and ownership-clear boundary; the
  headless abort-plus-late-completion seams are inventoried above;
- live GUI and screen input callbacks and physical key-repeat behavior across
  Fabric, Forge and NeoForge, beyond the raw callback and mapping-contract
  seams, including GLFW/loader delivery and Mixin application;
- the complete range pipeline across native, Distant Horizons, Create and Sable,
  packet transport and authoritative acceptance;
- deterministic local-position ordering of equal-distance, same-kind local child
  hits: the x/y/z comparator is source-confirmed, but no test exercises position
  ordering; the focused exact-tie case pins only block versus fluid;
- native mouse callback delivery and Mixin application for the
  [double-click Danger](../../architecture/input/double-click-danger.md)
  look-input bridge, including real spyglass, invert and ignored-initial-callback
  behavior across loaders.
