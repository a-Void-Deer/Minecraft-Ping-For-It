# Wheel interaction and cancellation

> **Implementation status.** This topic states the adopted native-selector
> interaction. Its headless controller, session, candidate-allocation and
> transition/renderer models exist, but the native input and HUD integration is
> still in progress: the live client keeps the superseded wheel path until that
> integration lands, and no game or runtime behavior is verified here.

## Opening, selection and timeout

Opening follows [capture readiness and the present-frame boundary](capture.md).
Every subsequent action operates on the captured context. Exceeding the
configured timeout closes an actually open selector with no ping, no
cancellation and no timeout error. Timeout is measured from actual opening.
The timeout value is frozen at that actual-open boundary. Pre-open release
behavior is owned by
[capture](capture.md#baseline-release-and-actual-wheel-opening).

One held interaction consumes one immutable, validated
[spatial-selector settings](../../config/client.md#spatial-selector-interaction)
snapshot; editing the live configuration cannot change a gesture already in
progress. The retired persisted wheel radii never configure this interaction;
their removal and migration are owned by
[revisioning](../config/revisioning.md#ordered-migration-and-writeback) and
[client configuration](../../config/client.md#obsolete-keys).

## Native selector presentation

The selector is a square, straight HUD: options are framed by rectangular
borders, the pointer is a straight crosshair, the trail is a series of small
squares, menu guides are straight lines, and the Back-hover progress is a
square perimeter. No arc, circle or rounded corner is drawn; a bearing only
positions a node along a straight guide.

The root presents the fixed eight-sector layout:

| Bearing | Span | Root entry |
| ---: | ---: | --- |
| 0 | 55 | Danger — enabled only when the resolved Target Type allows `danger`; otherwise disabled |
| 45 | 35 | Reserved placeholder, disabled |
| 90 | 55 | Content |
| 135 | 35 | Reserved placeholder, disabled |
| 180 | 55 | Cancel |
| 225 | 35 | Precise |
| 270 | 55 | Intent |
| 315 | 35 | Target-selection settings |

Bearings are degrees, zero is up and positive is clockwise; sectors are
half-open, so an exact sector start belongs to the following sector. The danger
sector is enabled only when the resolved Target Type's ordered Ping Types
include `danger`: a release on it creates the frozen ordinary captured target
with `danger`. When that Target Type does not allow `danger`, the sector is
presented disabled and never commits. The center deadzone has no focus. A
choice that carries a Ping Type uses that Ping Type's outline color for its
border; the [fixed catalog](../identity/catalogs.md) owns the colors.

Position, opacity and scale are smoothly interpolated for an unchanged entry,
for appearance and disappearance, and across a radial/list mode switch. A
removed entry keeps only detached paint data for one exit interval and is never
interactive. Reduced motion collapses the interpolation without changing
selection. The existing wheel opacity and font-size preferences remain the
appearance inputs; the selector adds no separate appearance catalogue, and
[client configuration](../../config/client.md#press-wheel-and-cancellation-interaction)
owns those keys and meanings. The trail and root distance are visual only and
never change gesture thresholds or selection.

## Radial release result

For a non-timeout release of an actually open selector, the frozen menu
geometry resolves the focused entry. A release inside the center deadzone
abandons the session with no action. A release on a sector resolves that
sector: a focused leaf commits exactly one action and ends the session; a
reserved, disabled, navigation or actionless-branch entry reports its reason
and never commits. A release outside every sector without crossing the deadzone
is a silent no-action that sends neither a create nor a cancellation request.
The down (180) cancel entry activates
[Cancel Marker selection](#cancel-marker-selection). Timeout remains the
separate no-action close described above.

## Headless spatial menu model

The model is headless: it owns no clock, renderer, configuration or Minecraft
input, and the caller supplies monotonic timestamps, deltas and the frozen
settings snapshot. The virtual pointer is the only pointer state; physical
mouse deltas are added to it, and the bounded trail is what a renderer draws.
A focused branch is entered by one of two admitted gestures. A stationary dwell
enters it after the dwell threshold, requiring prior pointer travel beyond the
stroke distance plus an additional minimum travel. A qualified direction turn
enters it at the turn corner without waiting for the dwell: after the focused
entry's minimum interval, with the pointer and the turn corner beyond the
stroke distance from the menu origin, a turn whose outbound direction differs
from the inbound radial direction by at least the turn threshold, over an
outbound segment of at least the minimum turn segment, opens the child menu at
that corner. Disabled and reserved entries never enter. Movement that satisfies
neither admitted gesture enters no branch, and a rebase or focus change clears
any pending turn corner. A focused Back uses the same dwell when hover is
disabled and the [Back-hover](#back-hover-return-state) state when hover is
enabled. With hover disabled, a reverse stroke back toward the parent origin
pops one radial level; row-anchored external menus do not retrace. A pop arms a
fresh-stroke requirement so a stationary pointer cannot re-enter the same
submenu immediately; a new full stroke plus dwell, or a qualified turn after
that stroke, is required. Cancel ends the session without an action and is
idempotent. An externally composed submenu is pushed at a caller-computed
origin, which rebases the virtual pointer and starts a fresh trail; a rebase
never draws a synthetic stroke between the old and new positions.

Root entries carry caller-fixed sector geometry, so a root menu never derives
its own geometry. Every non-root menu instead computes equal sectors from its
actual entry count, including its Back entry. An explicit Back entry stays
centered on the bearing from the child origin back to its parent origin, and an
automatically appended Back takes that same parent-centered position; siblings
keep their declared order and tile with equal spans.

### Intent

The intent branch offers only the resolved Target Type's ordered Ping Types.
Each leaf creates the frozen ordinary captured target with that Ping Type.

### Precise

The precise branch offers the five fixed Target Types in
[catalog priority order](../identity/catalogs.md#target-type-resolution-and-fixed-order).
A leaf is enabled only for a candidate installed by
[press-time candidate allocation](capture.md#press-time-candidate-allocation);
availability, installation and completion rules are owned there. A missing or
incomplete allocation is a disabled leaf, never a fallback to another
candidate. Each available leaf creates its assigned canonical candidate using
that candidate's own Target Type default Ping Type. The separate, equal-width
Back entry is navigation and is never a candidate.

### Content

The content branch presents the captured target's authorized observations
client-first: the client shows its own authorized local observation and falls
back to the server's bounded preview only for an allowed field with no local
observation. Detailed property selection, per-target-type policy and the
session value store are owned by
[presentation snapshot](../presentation/presentation_snapshot.md); the
inventory list's data, status, count and ordering rules are owned by
[inventory preview and tracking](../presentation/inventory.md).

### Inventory list

The content branch's inventory preview opens a square list surface. Navigation
is parent-relative: the selected row's Y is the child origin for that row's
item menu, so an item submenu is anchored to its row rather than the list
center, and its pointer rebase starts a fresh trail without a synthetic stroke.
The list's Back/Forward sides are frozen when it opens from the parent menu
bearing: Back is placed on the side that faces the parent, and a vertical
parent keeps Back on the left. A Forward stroke enters the selected row's item
menu; a Back stroke or Back-hover returns one level. A release with a selected
row commits through the inventory owner's single-item create path; a release
while the Back side is focused leaves without committing. Count, ordering,
folding and item-selection semantics are owned by
[inventory preview and tracking](../presentation/inventory.md#preview).

### Target-selection settings

The settings branch exposes the three target-selection toggles. The toggle
meanings and their next-capture application are owned by
[selection policy](selection_policy.md#selector-settings-branch).

## Mouse ownership

The selector releases mouse capture only while it is actually open and no
client screen is open. It re-grabs the mouse only when that capture was
released by the selector and no screen is open. Live ownership synchronization
defers while a screen is open, and disposal may relinquish pending selector
ownership rather than stealing the cursor from a screen or another owner.

The native input adapter converts absolute window mouse positions into owned
GUI deltas and exposes no screen facade. A window, scale, focus or capture
change, a re-prime, or a cursor warp resets the sample baseline and contributes
no movement or action. The screen-transition abort rule remains owned by
[capture](capture.md#interaction-lifecycle-aborts) and is unchanged.

## Cancel Marker selection

The client-side candidate collection and the server's active cancellable set
are deliberately different. On a cancel activation, the client collects every
**stored** marker whose owner is the local player and whose target dimension is
the current dimension. This query does not filter for visual activity or for
`SYNCHRONIZED` versus `STALE` under the
[client-state contract](../markers/client-state.md); a stored candidate can
therefore be stale or past its local display deadline. This local collection is
only a selection aid and is not an assertion that the marker is still
server-active or removable.

1. Keep the cone origin and direction from the press-time ray. Candidates must
   be within the configured half-angle, including the boundary; a zero-distance
   candidate is eligible.
2. Resolve each candidate's current position without starting a new target ray:
   a live entity uses its current rendered top-center; an external block uses a
   currently resolved provider position or its authoritative anchor; other
   markers use a matching presentation position when one exists and otherwise
   their authoritative anchor.
3. Choose the nearest candidate by world-space distance. An exact distance tie
   chooses the larger Marker ID. If no candidate is eligible, cancellation is a
   silent no-op.
4. Dispatch one removal request for that selected Marker ID. The server alone
   checks active status and ownership; on success it synchronizes the removal
   and resulting winner changes. Another player's marker can never be
   cancelled.

The selected stored candidate does not authorize a new target-selection ray or
make it server-cancellable. A stale, expired, missing, or unauthorized nearest
candidate can be rejected by the server; that rejection does not make the
client retry the action with a farther candidate. Such removals are silent
no-ops/rejections under [target validation](../authority/target_validation.md).
Removing a valid winner triggers
[server winner recomputation](../authority/ping_winner.md).

## Back-hover return state

The return state machine is a standalone, headless client model. A session
freezes whether Back-hover is enabled and the required dwell at its start, so a
later preference change cannot alter a running interaction. While a Back
affordance is focused, sustained focus accumulates dwell and reports a progress
value in `[0, 1]` that a renderer can paint as square progress. At the dwell
threshold the model reports one return trigger and then blocks the same held
focus, so a single sustained focus never cascades through several levels — even
when the menu changes to the parent and the focus stays in the Back direction.
The block is released when the Back focus is lost, and a deliberate leave and
re-entry arms exactly one new return. Switching the focused menu restarts the
dwell clock. A rewound clock keeps its original baseline and reports the
minimum instead of restarting. Ending the interaction clears the timer and the
block, and a new session always starts unblocked. When the hover preference is
disabled, the session stays inert and never triggers.

The preference keys and their local authority are owned by
[client configuration](../../config/client.md#spatial-selector-interaction);
their numeric range, step, and default remain implementation values. Native
screen and input integration and game-feel validation remain pending.
