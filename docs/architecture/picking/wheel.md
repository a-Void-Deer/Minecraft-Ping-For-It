# Wheel interaction and cancellation

## Opening, selection and timeout

Opening follows [capture readiness and the present-frame boundary](capture.md).
Every subsequent action operates on the captured context. The wheel center is
always **Cancel Marker**, including one-item Ping Type sets and the location
fallback. Each sector has inner and outer borders/arcs in its Ping Type's
outline color. The choices and default come from the resolved Target Type's
[ordered catalog](../identity/catalogs.md).
The wheel interaction settings and their qualitative relationships are catalogued
in [client configuration](../../config/client.md#press-wheel-and-cancellation-interaction).

Exceeding the configured timeout closes an actually open wheel with no ping,
no cancellation and no timeout error. Timeout is measured from actual opening.
The timeout value is frozen at that actual-open boundary.
Pre-open release behavior is owned by [capture](capture.md#baseline-release-and-actual-wheel-opening).

## Radial release result

For a non-timeout release of an actually open wheel, let `r` be the pointer's
radius from the wheel center in GUI pixels. The result is exactly:

| Pointer radius | Release result |
| --- | --- |
| `r <= wheelInnerRadius` | `CENTER`: **Cancel Marker** |
| `wheelInnerRadius < r <= wheelOuterRadius` | The corresponding frozen Ping Type sector |
| `r > wheelOuterRadius` | `NONE` |

If a computed sector is outside the frozen Ping Type list, it is normalized to
`NONE`. `NONE` is a silent release result: it sends neither a create nor a
cancellation request. This table applies only after actual opening; timeout
remains the separate no-action close described above.

## Mouse ownership

The wheel releases mouse capture only while it is actually open and no client
screen is open. It re-grabs the mouse only when that capture was released by the
wheel and no screen is open. Live ownership synchronization defers while a
screen is open, and disposal may relinquish pending wheel ownership rather than
stealing the cursor from a screen or another owner.

## Cancel Marker selection

The client-side candidate collection and the server's active cancellable set are
deliberately different. On a wheel release that can select the center action,
the client collects every **stored** marker whose owner is the local player and
whose target dimension is the current dimension. This query does not filter for
visual activity or for `SYNCHRONIZED` versus `STALE` under the
[client-state contract](../markers/client-state.md); a stored
candidate can therefore be stale or past its local display deadline. This local
collection is only a selection aid and is not an assertion that the marker is
still server-active or removable.

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
   and resulting winner changes. Another player's marker can never be cancelled.

The selected stored candidate does not authorize a new target-selection ray or
make it server-cancellable. A stale, expired, missing, or unauthorized nearest
candidate can be rejected by the server; that rejection does not make the client
retry the action with a farther candidate. Such removals are silent
no-ops/rejections under [target validation](../authority/target_validation.md).
Removing a valid winner triggers [server winner recomputation](../authority/ping_winner.md).

## Back-hover return state

The planned native target workflow adds a Back affordance whose sustained focus
can return one level without a click. Its behavior is owned here. The current
wheel's center **Cancel Marker** behavior above is unchanged: the root-centre
replacement is a planned migration and is not part of this contract.

The return state machine is a standalone, headless client model; no native
screen, renderer, or input route consumes it yet. A session freezes whether
Back-hover is enabled and the required dwell at its start, so a later
preference change cannot alter a running interaction. While a Back affordance
is focused, sustained focus accumulates dwell and reports a progress value in
`[0, 1]` that a renderer can paint as square progress. At the dwell threshold
the model reports one return trigger and then blocks the same held focus, so a
single sustained focus never cascades through several levels — even when the
menu changes to the parent and the focus stays in the Back direction. The
block is released when the Back focus is lost, and a deliberate leave and
re-entry arms exactly one new return. Switching the focused menu restarts the
dwell clock. A rewound clock keeps its original baseline and reports the
minimum instead of restarting. Ending the interaction clears the timer and the
block, and a new session always starts unblocked. When the hover preference is
disabled, the session stays inert and never triggers.

The preference keys and their local authority are owned by
[client configuration](../../config/client.md#spatial-selector-interaction);
their numeric range, step, and default remain implementation values. Native
screen integration, input routing, rendering of the progress affordance, and
game-feel validation remain pending.

## Headless spatial menu model

The planned native target workflow is prototyped as a standalone, headless
radial-menu model; its behavior is owned here. It is not the live wheel: the
current production wheel above still owns opening, selection and cancellation,
and its renderer and centre action stay unchanged until a separate integration
migrates them. The model does not implement the full prototype: qualified
turns, retrace, inventory-list expansion, and world-candidate acquisition are
known pending work and must not be claimed as prototype parity.

The model starts at the root centre. Root entries carry caller-fixed sector
geometry — the caller supplies each bearing and span, for example the
eight-way cardinal-55 / diagonal-35 layout — so a root menu never derives its
own geometry. Every non-root menu instead computes equal sectors from its
actual entry count, including its Back entry. An explicit Back entry stays
centred on the bearing from the child origin back to its parent origin, and an
automatically appended Back takes that same parent-centred position; siblings
keep their declared order and tile with equal spans. Bearings are degrees,
zero is up and positive is clockwise, and sectors are half-open, so an exact
sector start belongs to the following sector.

The virtual pointer is the only pointer state; physical mouse deltas are added
to it, and the bounded trail is what a renderer draws. Inside the centre
deadzone there is no focus and release abandons the session; release is the
only operation that commits an action. A focused leaf commits its action once
and the session ends; a reserved, disabled, navigation or actionless branch
entry reports its reason and never commits. A focused branch is entered only
by a stationary dwell after the dwell threshold, requiring prior pointer
travel beyond the stroke distance plus an additional minimum travel; a focused
Back uses the same dwell when hover is disabled and the
[Back-hover](#back-hover-return-state) state when hover is enabled. A pop arms
a fresh-stroke requirement so a stationary pointer cannot re-enter the same
submenu immediately; a new stroke plus dwell is required. Cancel ends the
session without an action and is idempotent. An externally composed submenu
can be pushed at a caller-computed origin, which rebases the virtual pointer
and starts a fresh trail.

The model is headless: it owns no clock, renderer, configuration or Minecraft
input, and the caller supplies monotonic timestamps, deltas and tuning. Native
screen integration, the rectangular square/straight renderer, qualified-turn
and retrace behavior, inventory-list and world-candidate sources remain
pending.
