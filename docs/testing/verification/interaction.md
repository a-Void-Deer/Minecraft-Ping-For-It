# Interaction verification

This page inventories capture, selection, input, radial-menu and inventory-list
interaction coverage and its known automated gaps. Read the
[verification guide](README.md) for the shared evidence and reporting rules.
Pending gameplay checks remain in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Capture, wheel and cancellation

`PingInteractionStateMachineTest` covers short press, long press,
pending/asynchronous capture, wheel state transitions, lifecycle abort, the
actual wheel-open boundary, and that an opened wheel never closes by elapsed
time alone while release still commits its selection.
`PingCaptureCoordinatorTest` covers capture tokens, rejection of stale
superseded completions, completion
races, first-completion ownership and identity-preserving metadata retention.
`PingSelectorReleaseTest` covers the selector release adjudication seam: a
wrong token, a threshold without an actual open, and a pending release with a
late capture never evaluate a selector proposal or retroactively open; elapsed
time alone never closes an actually open selector; terminal local intents and
explicit cancellation outcomes are single-use; precise and opaque inventory
proposals keep their frozen payload without item or count authority; an
unavailable candidate never falls back to the ordinary target; and an abort
fences a late completion before any release proposal can evaluate it,
including an abort re-entered from terminal release ports.
`TargetSnapshotTest` covers snapshot identity, copied capture metadata and frozen
context construction, and that an ordinary or external block target may carry a
capture face while other target kinds may not;
`TargetSnapshotBlockClassificationTest` covers explicit
and absent block-entity classification metadata; and
`MinecraftTargetSnapshotFactoryDetailedTest` covers retaining local detail only
for the matching entity-hit owner. `MinecraftTargetSnapshotFactoryFaceTest`
covers the ordinary-block capture face at the level-free factory seam: each of
the six resolved hit directions is copied as exactly that face, while a
synthetic `MISS` direction and an unavailable-block location fallback never
invent a face. `MinecraftSyntheticBlockFaceTest` covers retaining an actual
native face through the candidate factory path and capture coordinator, while
a synthetic concrete block keeps its target without acquiring a face, including
after direction/position copies and coordinator completion.
`PingCaptureCoordinatorTest` additionally covers
face identity retention: the captured face survives only when resolution
preserves the captured ordinary-block identity, duplicate and late-stale
completions cannot replace an accepted face, and a changed block type or
position discards it. For an external candidate the same retention requires the
exact read binding including the opaque provider locator: a target that merely
compares equal as a candidate, or a changed provider, registry or stable
identity, cannot replace the accepted face. These are factory and coordinator
seams; the complete
client/server `entity_block` classification path, including synthetic-face
provenance from every optional producer, remains unexercised.

`LongPressCompatibilityControllerTest` covers the ordinary rapid-click and
asynchronous deferred-compatibility paths, with focused bounds coverage supplied
by the applicable config-bounds tests. `LongPressCompatibilityDispatchTest`
drives that controller over the real baseline lifecycle, courtesy limiter and
dispatch receipt: a rejected or unready synchronous first default create cannot
seed rapid input; a rejected or unready asynchronous first default create drops
both rapid and deferred presses without queueing, retrying or replaying a
create or capture; a non-`CreatePing` result such as `TargetGone` discards the
deferred press; an actually opened menu disqualifies even a sent default type;
and successful receipts start the rapid second-ray and deferred fresh-press
captures. These are controller/config slices, not real
input callbacks or render-frame integration. An abort followed by a late
asynchronous completion is exercised by `PingSelectorReleaseTest` and
`ClientPingRuntimeInteractionTest`; the real focus-loss `KeyMapping.releaseAll`
hook, screen-transition callbacks, level-instance/dimension discontinuity
detection, loader/gameplay input lifecycle, and genuinely asynchronous
completion remain unverified.

`CancelCandidatePickerTest` covers press-ray cone filtering and nearest-candidate
selection, while `ClientMarkerStoreTest` covers owner/dimension retrieval.
Those seams do not runtime-cover a retained stale or display-hidden marker
reaching cancellation and then being rejected by the server without a local
fallback. Frozen press-ray behavior and the pending-capture/wheel interaction
boundary otherwise have focused test coverage.

### Gesture shortcut opening

`GestureSkipHoldTest` covers the passive pre-open input model: the minimum wait
is inclusive, the center-deadzone boundary is exclusive, GUI-scaled net
displacement rather than historical travel qualifies, and an elapsed-time
check needs no new callback. Ordered sample history is consumed once and reset
discards it; foreign-window and non-finite samples and scale or ownership
handoffs cannot invent displacement.

The gesture cases in `ClientPingRuntimeInteractionTest` cover early crossing
followed by a held frame at the minimum wait without another move, crossing
after the wait, the frozen deadzone and wait despite live edits, an independent
ordinary long press without movement, and return-to-center or short-flick
release retaining the default-create path. They also cover disabled and
compatibility modes, including virtual-held compatibility; pending capture,
invalid viewport and physical release gating actual opening; release observed
on a frame; and reset, screen, focus, level and dimension aborts fencing an old
capture and trajectory from a new hold. Press-time ray and target remain frozen
through the gesture opening. The ordered pre-open path pays the selector's
stroke and turn entry once and retains dwell timing and trail history; native
ownership begins only at opening, and reset/handoff priming adds no jump or
duplicate travel. Enabling compatibility during a pending gesture discards that
path while leaving the ordinary hold available.

The alternative-eligibility cases in `PingInteractionStateMachineTest` cover
capture readiness, held present, current-token ownership, release taking
precedence over late completion, and abort not granting eligibility to a later
ordinary hold. Configuration persistence and control-resource coverage are
inventoried in
[configuration verification](configuration-ui.md#inventory-and-spatial-selector-configuration).
These are headless input-model, runtime-port and state-machine checks, not live
camera, rebound-key delivery, GLFW ownership or loader gameplay evidence; the
[manual gesture scenarios](manual-integration.md#pending-manual-and-integration-matrix)
remain pending.

Resource-bound cases in `GestureSkipHoldTest` and
`ClientPingRuntimeInteractionTest` cover the exact last retained sample and
first excess sample, ordered single-use handoff with an immutable transferred
history, whole-history discard without a retained prefix or later tail, and
recovery on a new hold.
The runtime cases exercise preserving the original token, capture, frozen
target and press timer without an early send or notice; ordinary opening at
the center with fresh owned input; and one default create on release with both
ready and pending capture, followed by a later press using the still-enabled
shortcut. These remain helper and runtime-port checks rather than real native
input, camera or gameplay verification.

## Precise live candidate capture

The [live-capture owner](../../architecture/picking/capture.md#precise-live-candidate-capture)
has focused headless evidence across its allocation, refresh-controller,
session, release-admission and paint-admission seams:

- `FrozenCandidateAcquisitionTest` covers bounded allocation and identity
  de-duplication, retained actual class and per-candidate face, nearest
  same-category competition (a nearer supplement beats a farther ordinary and
  vice versa, with an equal distance keeping the ordinary before an equivalent
  supplement), an incomplete scanned class staying unavailable even when the
  ordinary matches it while a complete no-hit is missing rather than
  incomplete, distinct missing and incomplete results for the same empty
  evidence, specific-class identity consumption before a generic class takes
  the next nearest, stable strict-tie ordering that keeps encounter order
  rather than a specialized bucket order, provider-local equivalence,
  immutable evidence and independent work guards, and a bounded collector
  matching an independent exhaustive nearest oracle across randomized
  mixed-identity orders. Its same-identity near-contact cases keep the ordinary
  resolved target and press context, use the selected contact's snapshot
  geometry and face, leave the ordinary hit unchanged on equal-far evidence,
  and check near-contact block-face and entity-metadata consistency; the
  exhaustive oracle derives its expectation from the complete candidate pool
  independently of the production collector;
- `CandidateCaptureLifecycleTest` covers same-token attachment, duplicate,
  superseded, aborted and reentrant completion fences, no cross-token/ray reuse,
  ordinary readiness and face preservation when optional finalization fails,
  and avoiding duplicate ordinary resolution;
- `NativeBlockCandidateScanTest` and `NativeBlockCandidateRegressionTest`
  exercise native block/fluid shapes behind blockers, finite-segment and loaded
  access gates, actual surface coordinates and faces on long oblique rays,
  moving-piston shape access, bounded neighbor/block-entity and shape work, and
  incomplete rather than certified prefixes on exhaustion. They also cover
  origin containment without an observed face and no adjacent presentation-owner
  expansion;
- `NativeBoundaryFaceProvenanceTest` carries boundary contacts through the
  production native scan, level-free snapshot factory and frozen allocation: a
  tangential boundary contact retains its point and target without installing
  a clamped slab face, a genuine inward boundary entry keeps its actual front
  face, an outward boundary contact stays a miss, true origin containment
  stays unobserved, and a real approach surface keeps its observed face; and
- `RaycastSupplementalCandidateTest` and `NativeEntityEnumerationBudgetTest`
  cover farther entity candidates, exact-owned non-hit rejection without coarse
  fallback, frozen geometry ownership, selection filters, canonical multipart
  and XP locator construction, and bounded lazy enumeration charging nonmatches
  before filtering. The multipart case is a factory fixture, not a dragon-world
  or rendering test;
- `PreciseCaptureRefreshTest` covers the production refresh controller
  headlessly: capture starts immediately on branch entry and then follows the
  frozen tick period, entering again does not duplicate the first scan, leaving
  pauses and re-entering restarts, and ending stops it; native slots publish
  before the independent location request; each slot keeps its own ray context
  and the last certified pending value; a final missing or incomplete result
  replaces or disables the slot without an ordinary fallback; only one optional
  request is in flight, coalescing the latest work without starving or
  overwriting newer native results; stale, duplicate, foreign and post-end
  completions are fenced, and a reentrant branch exit cannot start optional
  work after its native publication;
- `SpatialSelectorSessionTest` additionally covers the live precise session:
  a revised immutable publication preserves slot focus, origin and typed color
  while replacing the installed target; only an actually painted current node
  can be acknowledged, an empty acknowledgement cannot authorize, and a
  disabled paint clears the older selectable version; the release intent
  carries the painted candidate and its presentation revision as a
  `PRECISE_PRESENTED` admission while its previous constructor form remains
  compatible, and duplicate, stale, wrong-token, wrong-level, stale-snapshot
  and post-abort publications are rejected;
- `PingSelectorReleaseTest` covers the release-admission fence: a `PRESS_RAY`
  proposal stays strict to the press ray, while `PRECISE_PRESENTED` requires
  the exact current interaction, the painted context table and an advancing
  presentation revision; unpainted, wrong-revision, other-context and
  press-ray proposals produce no action, and the presentation fence rejects a
  wrong token, dimension or old revision and is revoked by abort;
- `ClientPingRuntimeInteractionTest` drives the precise branch through the
  runtime ports: an ordinary press stays frozen while the precise branch's
  live target changes, entering the branch starts the live request, release
  consumes the last painted candidate without an ordinary recapture or a
  release-time cast, and an unpainted newer target still releases the painted
  one;
- `SpatialOverlayRendererPresentationTest` covers the isolated paint-admission
  predicates: detached exit data, ancestor nodes, first-appearance or
  quantized-zero paint, and a backdrop or pointer alone cannot acknowledge a
  candidate, and absent graphics acknowledge nothing and start no transition;
- `SpatialOverlayRendererFollowerTest` drives the production frame path through
  recording paint and font-metrics ports. Only an enabled action leaf, typed
  property branch, or pure navigation group in the active radial menu follows
  the pointer before dwell; Back, disabled, reserved, and actionless leaves do
  not, nor do deadzone focus, ancestor menus, inventory rows, or detached exit
  paint hide the pointer. Across intermediate frames the follower stays
  collinear with the current pointer ray while its radial position eases as the
  pointer target changes, remains bounded under repeated retargeting, settles
  after a finite transition, and honors reduced motion and focus/exit handoffs.
  Its connector begins at the frozen
  deadzone boundary and ends at the near edge of the actual scaled title frame;
  it is omitted when that frame overlaps the deadzone, follows transition alpha,
  and ignores both opacity preferences. Only a successfully painted, visible,
  current active node hides the crosshair and acknowledges its choice; trail
  state remains independent, while outgoing duplicates, transparent or
  quantized-zero nodes, omitted or failed paints cannot hide or acknowledge.
  An intermediate eased follower may be wholly offscreen even while its pointer
  target is confined: only a positive intersection of the transformed title
  frame admits crosshair hiding, connector drawing and choice acknowledgement,
  and the painted title bounds used by that gate are measured at the focused
  scale.
  Independent PoseStack geometry checks reject tangency and admit positive
  partial intersections at all four viewport edges under fractional pan. The
  real Precise release handoff rejects an invisible current version both when
  no older candidate was admitted and when it must retain an older painted
  admission; an outgoing duplicate or unpainted newer publication cannot
  replace the last successfully painted candidate. These are production-frame
  checks with recording ports, not
  `GuiGraphics` or GPU submission; and
- `SelectorPreciseTargetLabelsTest` covers the Precise detail-label resolver
  headlessly: every enabled Precise leaf names its own captured candidate from
  the exact snapshot it is bound to, a broad slot keeps the candidate's
  canonical target identity instead of synthesizing its slot type, choices
  without a captured candidate never reach the naming function, a newer
  publication cannot retarget an older bound snapshot, literal and localized
  names pass through the existing name resolver including its unknown and Here
  fallbacks, and the returned component is detached from the live one. It is a
  label-resolution helper seam: it does not exercise the real selector draw,
  renderer wiring, or GPU submission.

These are allocation, capture, native-shape, enumeration, refresh-controller,
session, paint-admission, production-frame recording-painter and runtime-port
seams; the period key's config and
persistence coverage is inventoried in
[configuration verification](configuration-ui.md#inventory-and-spatial-selector-configuration),
and the transformed Sable scanner has its own
[integration evidence](integrations.md#sable-integration-coverage). Real
GPU/render submission, game-world and chunk lifecycle, provider behavior,
multiplayer, loader input callbacks, and unbounded-geometry wall-clock behavior
remain unexercised, and the
[manual Precise scenarios](manual-integration.md#pending-manual-and-integration-matrix)
remain pending.

## Selection-policy and input-state seams

The [selection policy](../../architecture/picking/selection_policy.md) has focused unit and
state-seam evidence, rather than live input-callback evidence:

- `ToggleInputStateTest` covers physical-press de-duplication until release,
  including suppression of repeated presses before the next release;
- GUI/screen eligibility is exercised at the input-state seam, not through a
  live GUI callback;
- `ClientConfigTargetSelectionTest` checks the disabled defaults for the three
  target-selection toggles and their JSON round trip; and
- `EntitySelectionBlacklistTest` covers registration aggregation, idempotent
  removal, and the optional Create registration boundary, while
  `EntitySelectionBlacklistDefaultRuleTest` checks the default
  `simulated:honey_glue` entity rule and representative nonmatches.

The entity-selection blacklist evidence is distinct from block whitelist,
block-shape, and display-blacklist behavior. It must also not be treated as
raycast-filter coverage: spectator exclusion is implemented by `Raycast`, not
by `EntitySelectionBlacklist`. The unit seams do not establish live GUI/screen
callbacks or loader-specific physical key-repeat behavior.

## Exact entity-local picking and Create raycast seams

Focused coverage exercises:

- an immutable entity-local-geometry owner snapshot;
- owner resolution and `HIT`/`MISS`/`UNAVAILABLE`/`FAILED` candidate rules;
- retention of entity-local metadata only for the matching resolved identity;
- native local block/fluid shape policy and the exact block/fluid kind tie;
- the common native local-shape scanner and candidate pipeline.

These tests preserve whole-entity identity and exercise the non-optional engine
boundaries. They do not constitute an in-game Create validation. Create-specific
coverage is inventoried under
[Create raycast seams](integrations.md#create-raycast-seams).

## Capture and acceptance range

`ServerConfigBoundsTest` covers server range clamps,
`RaycastCandidateFlowTest` covers native candidate/raycast seams, and
`OptionalDependencySafetyTest` covers absent optional-integration safety.
Source and focused seam evidence establish
the native `min(raycastDistance, pingDistance)` limit, Distant Horizons'
independent trace, the server acceptance range, Create/Sable reuse of the
finite native segment in the ordinary trace, and the hold-frozen client
`pingDistance` bound of the Precise live casts. No automated
end-to-end test exercises that entire client capture, optional-provider,
packet, and authoritative server-acceptance pipeline.

## Back-hover return state

`BackHoverControllerTest` covers the headless Back-hover timing model: progress
before the threshold never triggers, the threshold triggers exactly once and a
held focus never repeats, leaving and re-entering restarts the clock, a parent
Back stays blocked through held focus, observed focus loss, and motion within
Back; only an effective physical leave followed by re-entry permits rearming.
Switching the focused menu restarts the clock, a rewound clock clamps instead
of resetting the baseline, `end` clears the timer and block idempotently and a
fresh session is unblocked, a disabled session never arms or triggers, updates
before start are inert, and the hover parameters are frozen at session start.
It is a standalone model seam: no native screen, input route, renderer, or
in-game timing is exercised.

## Headless spatial radial menu

`SpatialControllerTest` covers the headless radial menu session: a centre
release inside the deadzone abandons and release is single-use; a focused leaf
commits exactly one action while reserved, disabled, navigation and
actionless-branch entries never commit; sector starts are inclusive of the
following half-open sector including the 0/360 wrap; outside sectors without
crossing the deadzone report an outside no-action; a disabled branch focuses
but is never entered by dwell; a focused branch is entered by dwell and a
focused Back dwell pops exactly one level when hover is disabled; a pop arms a
fresh-stroke requirement so a stationary pointer cannot re-enter until a new
full stroke plus dwell; hover return pops one level and reports delegated
progress without cascading; hover mode suppresses the ordinary Back dwell;
physical deltas accumulate and rebase starts a fresh trail; cancel clears the
session idempotently; an externally composed submenu is pushed at a given
origin with a rebased pointer and gains one automatic Back entry whose
localized label is non-empty and whose navigation release remains actionless;
and an explicit Back is centred on the parent bearing with equal non-root
sibling spans that tile. The same headless controller model covers
qualified-turn entry at a corner and reverse-stroke retrace, including that a
disabled branch cannot be entered by turn and a row-anchored external menu does
not retrace. Incremental one-pixel reverse samples and an exact two-pixel
reverse sample accumulate to the unchanged net parent-directed return distance,
where the sample reaching it returns one level to the root and further
reverse samples leave the root active; zero, tangential, outward and
out-of-corridor samples do not trigger a return, while strictly parent-directed
progress within the corridor can return once the net-distance threshold is met,
and hover mode owns Back instead of retracing. Each
pushed level exposes the radial entry choice that opened it, the root and an
externally pushed menu expose none, and an ancestor keeps its original entry
edge.
The same model owns the viewport projection: the logical pointer is clamped to
the inclusive GUI pixel centers of the current viewport under the frame's rigid
translation, including every edge and corner and fractional pan offsets, while
raw finite input saturates without overflow. Only effective movement counts:
outward input spent at the boundary adds no overshoot debt, trail point or
timer progress, a reversal moves the pointer at once, and non-finite deltas
leave radial and external state untouched. The root-floor case protects
pending dwell; separately, a resize or pan correction cannot complete pending
dwell or enter a branch. A non-root held-Back regression confirms that
projection-only leave/re-entry and saturated zero-effective movement cannot
re-arm an already-fired held Back, while effective physical leave/re-entry and
qualified-turn movement preserve legitimate rearming. Rebase, start, end and
Back snapshots stay inside the published viewport. The programmatic
circumference placement is still covered as its own seam: the
placed pointer keeps the frozen inclusive-deadzone radius and admits no focus,
a stationary release abandons, the reconstructed absolute point stays inside
the same inclusive deadzone predicate including at nonzero and mirrored
origins, an unreachable placement stays pending without travel, trail growth or
timer progress until the projection can show it, resolution pays no fresh
stroke by itself, a partial new stroke cannot pay the pop's fresh-stroke
requirement while a full stroke plus dwell re-enters, a fresh effective
physical movement cancels a pending placement instead of chasing it, and
resolution or projection cannot re-arm an already-fired held Back or append a
joining stroke while a deliberate physical leave and re-entry still arms one
return. User returns replace that placement on every path (explicit Back,
retrace, dwell, qualified turn, and hover) by admitting the trigger's confined
pointer and rigidly translating the returned parent's origin and every other
retained menu origin and parent/row anchor so the returned parent's centre
lands on the pointer, which itself stays at the trigger; exactly one level is
removed, retained parent-child vectors and entry choices survive, a nested
return translates all ancestors and an independent row anchor, a zero-distance
alignment still reports one cumulative user-return notification, and a
stationary release on the returned centre abandons even when the old root
direction was an action. The return seeds exactly one trail point with an
advanced epoch and clears stale focus. A programmatic return keeps the old
placement and focus and reports no notification, root pruning preserves an
already-translated placement, a user return discards a stale pending placement
before projection can resolve it, and only a new start resets the cumulative
notification. Incremental one-pixel reverse samples return exactly one level
at a non-root parent and cannot cascade without that level's own full reverse
distance. External relayout is also covered: it restarts a pending hover dwell
and a hover-disabled Back dwell at the new geometry without pointer, trail or
timer movement or an immediate pop, leaves an unchanged placement's running
deadline intact, re-evaluates focus without supplying branch travel, clears a
stale turn corner, cannot pay a return's fresh stroke while preserving real
partial travel, is inert for an inactive root or ordinary radial menu, and
preserves the shared held-Back block.
That model also exposes the renderer-facing sample identity that separates
effective input from projection and navigation: only effective pointer movement
advances the sample sequence, a saturated callback is not a sample, a projection
or resize correction advances a separate alignment identity and fences the
sequence without adding a trail point or a synthetic stroke, an explicit rebase
changes the discontinuity identity even at coincident coordinates, and entering
or returning from an external menu starts a fresh identity. The frozen per-hold
bound caps the published trail.
It is a standalone model seam: no native screen, renderer, input callback,
configuration, or world-candidate source is exercised.

`SpatialPreferredBearingTest` covers preferred non-Back placement over zero
through twelve siblings and multiple parent bearings: the slots keep their
original starts and equal spans, tile the full circle without rotating, and
retain the true parent-facing Back and the relative order of other non-Back
choices. A four-slot boundary case follows the start-inclusive hit convention
rather than forcing the preferred center due right. An explicit Back stays in
its own slot even when the preferred choice moves across it, a desired bearing
inside Back selects the nearest non-Back slot, and a disabled choice still
receives placement. A missing or Back choice preference is inert, root
caller-fixed geometry remains authoritative, and the parent-directed reverse
stroke still returns one level. These are headless controller checks, not a
live input or rendering run.

`SpatialSelectorSessionTest` covers the production headless selector facade:
the frozen root menu and its allowed typed intents (with a whole-marker choice
disallowed for the entity-block fixture rather than by a universal disable),
real typed choices inheriting their catalog Ping Type outline color while
untyped choices keep a null color, supplied precise allocations that are never
retargeted or reused, broad
precise slots keeping their candidate's canonical target type while a foreign
canonical type is rejected, single-use release,
unavailable/reserved/branch releases producing no action, next-capture
toggles that do not mutate the frozen target, actual property values and
annotations without fixture data, the inventory submenu anchored at the logical
selected row with Back preserving selection, direct list release choosing one
opaque reference without count authority, partial-batch ordering and freeze
rules with explicit zero retained, list Back-hover ownership and one-level pop,
lifecycle and unavailable fences discarding old references and late data,
missing counts staying unknown while an explicit zero remains selectable, and
opaque property keys not aliasing another annotation action. It also covers
captured-face admission and external-block face retention: a captured face is
accepted for the block and entity-block target types and rejected for entity
and location targets; an ordinary external-block target opens the selector and
releases exactly its own captured target, hit and provider-local face, present
or absent; and a live precise external-block candidate keeps that face through
publication, paint and release while the painted context keeps the live ray.
It also covers the declared content navigation groups: a declared group renders
as a parent whose focused release commits nothing while an unrelated top-level
row keeps its established action; grouped member children keep their own
references, observed values and typed default release; Back returns one level
and keeps the parent identity; a declared group with no visible member choice
is omitted instead of becoming a dead selectable root; a nested group path
renders its subgroups under the declared parent and releases the nested member;
and a property group path whose declared ancestor chain is missing is rejected.
Its Content-direction cases enter the real root through upper-right bearings
and then move exactly right across varying property/group counts: inventory is
focused without rotating Back, other property and group entries keep relative
order, an enabled inventory opens with the resolved breadcrumb and a disabled
one retains its focusable slot without opening or committing. With no inventory,
declared order and equal-sector geometry stay unchanged; nested groups and
property Ping Type menus keep their own ordering.
The session keeps the list's logical pointer fixed on both axes: physical list
samples never move it (only viewport confinement may still correct it),
vertical glide and bounded horizontal side progress accumulate independently
from the same admitted samples, the per-sample vertical-intent cone ignores
only that sample's horizontal component while keeping its vertical component
and prior horizontal progress, and only effective filtered horizontal progress
can cross a side gate or physically release held Back. Pointer alignment from a
pan or resize still does not glide, select, add a trail point, advance hover,
trigger automatic side navigation, or release stale item focus; the corrected
pointer is the next gesture baseline without correction travel. The frozen
session settings deadzone reaches the controller read model
unchanged from the value consumed by the renderer, including a non-default
radius. The shared item-to-list Back latch
honors the accumulator distinction: alignment after item return cannot
cascade, but effective physical leave and re-entry permits a later one-level
return. A user list Back is covered for both Back styles and both entry sides:
exactly one level returns to Content with the pointer staying at the trigger
and the returned Content centre rigidly translated there, no immediate focus
and a single trail seed; stationary frames cannot reopen the list or navigate,
and a deadzone release abandons; a full deliberate stroke plus dwell reopens
the list while a partial one cannot; a distant or resized exit never resolves
through the old circumference placement and fresh physical movement continues
from the returned centre; a programmatic item revocation applies no such
translation; an item-clearance update repositions an active item menu with its
frozen row anchor while preserving pointer and trail without adding hover
progress, is inert before entry or for an unchanged metric, and rejects
non-positive or non-finite
values; and the item-submenu Back return stays on the list without a Content
placement. Content intent is covered per mode: OFF turns an otherwise
selectable property into a terminal Attention-annotated leaf keeping its
original reference, observed value and the whole-marker Target-Type default,
disables a property without an authorized Attention instead of inventing a
fallback annotation, makes a direct selected-list release request the
Attention item annotation while preserving the whole-marker default, and keeps
a Forward stroke inert; ON exposes the Forward affordance and the frozen
retained row key while the paint model keeps the full window, Back focus
removes only direct-release committability and not the Forward affordance, and
an empty list is never committable under OFF.
`NativeSelectorInputTest` covers the headless input normalization seam: only
accepted successive virtual window positions become GUI travel; opening,
resize, focus loss, and reopen reprime without phantom travel; settled-coordinate
priming preserves the next real movement; actual native ownership gates movement
and every scroll callback independently of the vanilla grab flag; and scroll
normalization occurs once only while owned without moving the pointer, and
endless alternating owned GUI callbacks around the settled baseline leave the
fixed list pointer, trail, selection and hover progress untouched. Through
the selector session, owned GUI-scaled native vertical movement continues list
glide at confined edges and the first real reversed movement applies immediately
after a scale handoff, while unowned samples and handoff priming add no travel.
Fractional native samples — one window pixel being half a GUI pixel on each
axis — accumulate through the real input adapter into exactly one return to
the root: the first diagonal sample crossing the net return threshold returns,
while earlier samples stay in the child and further reverse samples leave the
root active.
These are headless model and input-normalization seams: no native screen,
renderer, input callback, world-candidate source, or in-game integration is
exercised.

`NativeSelectorContentTest` additionally exercises the production content bridge
against a real headless client inventory session: opaque selectable references,
explicit zero and row quality, folded-row replacement resetting the list, close
cleanup, reentrant abort during open without reviving the request, and safe
conversion of a null label to an empty component while unknown non-empty keys
keep their translation fallback. It also covers the kinetic stress/capacity
content projection: a locally observed pair needs no preview request and
formats with the same projection capacity without adding a request, an
authorized but locally missing capacity is requested exactly once and its
accepted response enables the percentage context, and a denied capacity is
neither demanded nor inferred, so the reader is never asked for a field outside
the accepted mask. It also covers the root kinetic speed projection from a
locally observed record through the bundled English localized RPM display: a
driven and a reversed signed effective RPM keep their own value, a stopped
overstressed record shows its observed zero rather than its nonzero
theoretical RPM or record size, nested typed rows keep their typed scalar
labels, a record without a numeric `effective_rpm` keeps the established
generic record summary instead of an invented RPM value, and the local
projection adds no preview request. The bridge also covers the content
ping-type policies: the
inventory item menu uses the inventory item policy including its
inventory-only take type while a regular property keeps the property policy
without take, and the legacy constructor keeps passing the shared policy; and
an automatic Back entry's localized key resolves through the content adapter
as a translatable component. The bridge also covers the block-state navigation
group over a real headless selector session: a locally observed block-state
record becomes a pure navigation group whose record root is dropped from the
selectable projection and whose grouped rows are declared; the group parent is
an actionless branch with no typed release of its own; its state children keep
their observed references, values and default Ping Type outline color with
typed releases, including a nested row releasing under the selected Ping Type;
Back returns one level and the frozen parent identity survives; an empty
record stays a disabled row rather than a dead selectable root; and an
unrelated record root keeps its ordinary selectable branch. The bridge also
covers the Create namespace hierarchy over the same session: every
create-namespace field and its record descendants join the localized Create
properties group, whose label resolves through the real language fallback
including its written ellipsis, while only the kinetic stress, capacity, and
available-capacity fields nest under the localized Stress group and the
network and overstress flags and every other Create field stay directly
selectable under Create; each group parent is an actionless branch whose
focused release commits nothing and carries no Ping Type color; the grouped
Stress children keep their own references, observed values and typed default
release; an empty Create membership declares no group; the declared hierarchy
keeps its identity across revisions; and Back from the Stress subgroup
returns one level to the Create menu with the parent identity preserved. An
exact child-denied kinetic field is omitted from both the locally observed and
server-backed menus while its root speed record stays selectable and still
formats RPM. This is client-session/facade coverage, not native input or HUD
evidence.

`SelectorToggleLabelsTest` covers the client-side settings-toggle label
resolver headlessly: each of the three target-selection toggles reads only its
own live concern (`markFluids`, `markBlacklistedTargets`,
`passThroughTransparentBlocks`), every resolution re-samples the supplied live
policy, the toggle name and separator stay plain while only the ON/OFF state
word carries the status color (green for ON, red for OFF), and a non-toggle or
unknown choice returns no label so the caller keeps its own resolver. It is a
label-resolution helper seam: it does not exercise the real selector draw,
renderer wiring, or GPU submission.

## Native client interaction runtime

`ClientPingRuntimeInteractionTest` drives the production interaction runtime
through headless world, cursor and clock ports: press-time ray and target stay
frozen through camera movement and asynchronous completion; a pending
completion cannot open a menu or start a held preview; release alone produces
exactly one default create; abort reasons invalidate a late capture and
synthetic release; a center release abandons without a cancellation while the
cancel branch builds the frozen cone lazily; list release sends `SELECT` before
the exact `CLOSE` and never falls back to a plain create; a long-held selector
never auto-closes and its release still selects the row; reset and late-preview
responses cannot revive or select; precise
release consumes the supplied selectable candidate; broad block and entity slots stay
distinct from the ordinary target and keep their candidate's canonical target;
property create preserves the address, observed value, annotation and
whole-marker type, and a denied property cannot
fall back to a plain create; toggles commit once; deferred compatibility
receipts gate the deferred capture; resize, invalid frames and screen disposal
reprime without synthetic travel; held preview forwarding and its late-response
fence; a late old capture cannot clear a new selector or its exact request;
rapid receipts backdate only time and keep the second physical ray; spatial
settings are frozen at press and an opened selector ignores elapsed time; an
invalid viewport cannot open; and a reentrant abort inside validation blocks
every release packet. A nearer same-identity precise contact without a face
preserves the ordinary press-time inventory face: the ordinary content branch
still opens with that face, its inventory selection remains enabled, and the
release selects without a fallback create. An external-block press with a
provider-local face opens the selector, sends that frozen face as the inventory
preview read scope, and releases exactly its own external target.
It also covers a native-only ownership handoff without a vanilla grab-state
change: both runtime movement and scroll paths stop accepting selector input,
and re-priming after reacquisition preserves the next real movement.
A window resize with no delivered move callback, snapshot read or paint still
recomputes the confined focus before an immediate release, so the release
resolves the new confined sector rather than the pre-resize diagonal, and a
saturated native raw baseline continues from the boundary with its first
reversal sample applied at once.
The runtime also freezes the trail preferences with the hold: the controller's
retained-history bound and the paint style's trail visibility, length, and
opacity all come from the press-time snapshot, a live edit during the hold
changes neither the retained history nor the painted style, and the next hold
adopts the edited values with the painter and controller sharing one bound.
`ClientPingDispatchReceiptTest` covers actual-send receipt, transport-failure
rollback without queueing or refund, empty-tracker restoration and preservation
of a newer reentrant route receipt.
`WheelMouseCaptureLifecycleTest` covers native `DISABLED` ownership with the
vanilla grab flag false through a recording cursor seam. Its fixture separates
confined physical coordinates from unbounded virtual callback coordinates:
movement beyond all four window boundaries produces exact scaled GUI deltas
without modeled physical escape or camera motion. Acquisition and handoff
priming exclude transition resets while counting the first real movement once;
delayed cursor entry after `DISABLED` acquisition, with no synthetic position
callback or immediately before the first real callback, preserves the settled
baseline and counts that movement once. Repeated synchronization neither resets
the pointer nor drops travel. It also covers a normal close reclaiming the
vanilla grab exactly once even before idle phase publication versus an initially
free pointer closing without a grab;
clearing pending look at close; a screen-owned cursor never being acquired;
every owned scroll callback being consumed, including horizontal-only and zero
vertical deltas, while transition callbacks remain unowned; and ownership
fences at focus loss, incoming-screen HEAD disposal, window replacement, a
different native-mode owner, or a vanilla re-grab in the same `DISABLED` mode.
A fresh vanilla release can reestablish ownership without a normal-mode cycle,
while callback-observed handoffs and reentrant acquisition, disposal or restore
cannot revive stale claims, overwrite a newer owner or cause a second re-grab.
These are recording-seam checks of the
[mouse-ownership contract](../../architecture/picking/wheel.md#mouse-ownership).
`WheelMouseCaptureTest` covers the pure lifecycle action matrix and press-time
grab guard across every interaction phase and screen/focus combination: a
vanilla mouse press cannot auto-regrab an open selector while screen-open and
unfocused presses keep the vanilla grab.
`NativeSelectorMixinContractTest` checks the mapped `MouseHandler` callback
ABI, accumulated-look field types and scoped release handoff hooks, and the
mixin hook descriptors — including a dedicated non-cancelling cursor-entry
delegate separate from the explicit reset delegate, the release redirect to the
actual vanilla position-before-mode call and the narrow press-time auto-grab
guard preserving raw press and release delivery to the key mapping — and that
each effective loader manifest registers the hook bridge exactly once on the
client side. Runtime cursor entry validates native ownership and frame validity,
clearing/re-priming the baseline when either is invalid.

These are production-orchestration tests over recording world, cursor, clock
and transport ports; they are source-reviewed test existence, not a live client
run, mixin application, native Windows/GLFW cursor or desktop behavior, GPU
submission, or multiplayer verification.

## Inventory list gestures and navigation

`InventoryGestureTest` covers the fixed-pointer list accumulator: physical
samples never move either logical pointer axis while vertical glide uses the
frozen row threshold with split and batched movement independent of starting
or aligned pointer Y, rebase resets the glide fraction while preserving
selection and window, reversals in both directions discard opposing sub-row
glide travel, the inclusive vertical-intent cone ignores a sample's
inside-or-exact horizontal component in both directions while preserving prior
accumulated progress, out-of-cone dx accumulates, vertical-only samples never
navigate or cross either side gate, the side-exit bound discards overshoot and
allows immediate reversal without debt or reaching the opposite gate in one
pixel, glide suppression outside the hint corridor derives from the accumulated
progress rather than an imported pointer, Back-hover suppression and
wheel re-enable react to effective accumulated progress, and wheel unlock
uses its separate signed, unscaled travel with only the unlocking sample
beginning glide; ordinary scroll keeps its independent signed fraction across
reversal and glide. The updated calibration tests an explicit neutral factor
against the intrinsic baseline and explicit faster and slower factors against
their relative row thresholds, independent of the configured non-neutral
default; this records calibration coverage, not a numeric
tuning catalogue. The model also covers huge finite endpoint travel with immediate
reverse movement and no overshoot debt, and non-finite movement as inert.
`InventoryListModelTest` covers sorted initial rows and stable ordering, selection
and row-window clamping, and huge attempted row steps saturating without integer
overflow and reversing immediately. A frozen-panel translation moves only the
panel axis and glide baseline while preserving entries, selection, window,
direction, status, discovery order, the scroll freeze and the fractional wheel
remainder, and non-finite deltas are inert. These are model tests; the session and native
input coverage in the [headless spatial radial menu](#headless-spatial-radial-menu)
exercises authorized glide against a confined pointer.
`SpatialInventoryNavigationIntegrationTest` drives the headless native-input
adapter and a real selector session through production inventory layout,
viewport preparation/retargeting and frame rendering with a recording painter.
It covers list entry from reachable viewport corners, centering pan and scale
handoff, vertical glide and reversal at confined edges in both directions,
normal and reduced motion, both forward-side orientations, selection painting
after a streamed view update, and item-Back return followed by resize while
preserving the selected key and row window and keeping vertical-only input on
the list. Session and gesture seams also verify that viewport correction
preserves partial glide, signed wheel-unlock and ordinary-wheel fractions, wheel
lock, selection, trail and the held-Back latch. The same fixture also covers
the user list Back through the real native-input and
session path with viewport preparation and retargeting: both Back styles,
normal and reduced motion, an oblique inventory-slot entry, other GUI scales
and deadzone radii, the pointer staying at the trigger while the returned
Content centre is translated there, one trail seed, stationary frames that
cannot reopen the list, a deadzone release that abandons, and a full
deliberate stroke plus dwell required to reopen the list; a resize never
resolves through the old circumference placement and fresh real input
continues from the returned centre. It also covers OFF content intent through
the real path (ordinary rows, no Forward entry or retained key, a direct
release carrying Attention) and an item-clearance update that repositions the
active item menu without pointer or trail travel. It also covers the ancestor
breadcrumb rule through the real path:
the root and Content ancestors keep only their entry breadcrumbs and the
Content Back navigation while the hidden root and property options are not
planned, the logical navigation choices remain complete, and the inventory list
panel stays painted under the item submenu. Its native upper-right-then-right
case enters Content by a qualified turn at the actual corner and continues the
same rightward stroke plus dwell into inventory. Controller focus, sector
paint and the active follower receive the same equal-sector geometry; Back
still faces the real root, and the entered inventory breadcrumb retains that
geometry rather than a forced due-right center. The fixture does not instantiate
the complete `ClientPingRuntime`, a GLFW window, `GuiGraphics`, or GPU submission;
native-window/gameplay confirmation of inventory-axis reachability after the
item-Back resize handoff remains pending.

## Known automated gaps

- the shared client/server `entity_block` classification path end to end,
  including synthetic-face provenance from every optional producer;
- stale or display-hidden cancellation followed by authoritative rejection with
  no local fallback;
- real input-callback and render-frame behavior for rapid/deferred long-press
  compatibility;
- the content-intent toggle lacks a dedicated asynchronous press-edit-to-next-hold
  runtime fixture: the config freeze and the OFF/ON session behavior are covered,
  but a live edit between press and release is not;
- OFF-mode declared-navigation-group behavior and an inventory whose allowed set
  lacks Attention have no dedicated behavioral fixtures in either mode;
- asynchronous completion after lifecycle abort in a real input/loader context,
  including the token invalidation/ownership-clear boundary; the headless
  abort-plus-late-completion seams are inventoried above;
- live GUI/screen input callbacks and physical key-repeat behavior across
  Fabric, Forge, and NeoForge, beyond the input-state seams; and
- the complete range pipeline across native, Distant Horizons, Create/Sable,
  packet transport, and authoritative acceptance;
- deterministic local-position ordering of equal-distance, same-kind local
  child hits: the x/y/z comparator is source-confirmed, but no test exercises
  position ordering; the focused exact-tie case pins only block versus fluid;
