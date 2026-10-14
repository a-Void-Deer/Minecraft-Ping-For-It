# Wheel interaction and cancellation

> **Coverage.** This topic states the adopted native-selector interaction.
> Coverage scope and pending runtime evidence for its headless controller,
> session, candidate-allocation, transition/renderer and native input seams are
> owned by [testing and verification](../../testing/verification.md).

## Opening and selection

Opening follows [capture readiness and the present-frame boundary](capture.md).
Every subsequent action operates on the captured context, except the Precise
branch, whose candidates are live while that branch is active
([Precise](#precise)). An actually open
selector has no elapsed-time auto close: it ends through a release, a
cancellation, or an interaction lifecycle abort. Pre-open release behavior is
owned by
[capture](capture.md#baseline-release-and-actual-wheel-opening).

One held interaction consumes one immutable, validated
[spatial-selector settings](../../config/client.md#spatial-selector-interaction)
snapshot; editing the live configuration cannot change a gesture already in
progress. The retired persisted wheel radius and wheel timeout keys never
configure this interaction; their removal and migration are owned by
[revisioning](../config/revisioning.md#ordered-migration-and-writeback) and
[client configuration](../../config/client.md#obsolete-keys).

## Native selector presentation

The selector keeps square nodes and straight interaction guides, with the
sector underlay and the center circle as its only curved elements: options are
framed by rectangular borders, the pointer is a straight crosshair, the trail
is a series of small squares, menu guides are straight lines, and the
Back-hover progress traces the focused Back frame's actual boundary. A radial
menu may place a semi-transparent circular sector under each entry to make its
bearing and angular span legible and to support label contrast. This sector is
a visual underlay; it does not round or replace the node, pointer, trail, guide,
or Back-hover geometry, and it does not change focus or release selection.

Each radial menu also draws center guides at its own origin: one closed circle
of the frozen center deadzone's radius — the deadzone's actual boundary,
not a circle at half that radius — and one straight divider on every actual
shared boundary between adjacent sectors, from that circle out to the outer
radius of that menu's sector underlay. The dividers follow the real sector
boundaries, including the root's unequal spans and the boundaries of any Back,
disabled or reserved entry. The center guides are interaction chrome,
independent of the wheel-underlay and target opacity preferences.

The root presents the fixed eight-sector layout:

| Bearing | Span | Root entry |
| ---: | ---: | --- |
| 0 | 55 | Danger — enabled only when the resolved Target Type allows `danger`; otherwise disabled |
| 45 | 35 | Reserved placeholder, disabled |
| 90 | 55 | Content |
| 135 | 35 | Reserved placeholder, disabled |
| 180 | 55 | Unping |
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
selection. A sector underlay's and a text-bearing frame's background coverage
is an independent interpolated channel: it follows the same appearance, exit,
rewind, retention, detached-exit and reduced-motion rules as the rest of the
transition, so either the foreground or the background alone can keep an entry
painted. While an enabled action or branch entry holds focus, that entry's own
sector and frame background are enhanced and the menu's other entries'
backgrounds are dimmed; an idle menu, Back, disabled or reserved focus, and the
deadzone keep the established base coverage, with the ancestor and
disabled/reserved base treatments retained, and inventory-list surfaces follow
their own coverage rules below. Opacity is split by layer: the wheel opacity
preference scales only the sector underlay's background coverage, while the
target opacity preference scales only the text-bearing frames' background
coverage; the foreground composition (node alpha, text, borders, icons, the
acknowledgement and the focused/sibling scale emphasis) keeps its existing
preferences and layering, and an entry stays painted while either its
foreground or its background is visible. Neither preference dims the
interaction chrome — the pointer and guides follow only the transition fade,
while the trail also uses its independent trail-opacity preference. The trail
is painted above the underlay and below every text-bearing frame, label, and
name preview; its opacity preference does not dim the guides or follower
connector. The font-size preferences remain the text inputs; the selector adds
no separate appearance catalogue, and
[client configuration](../../config/client.md#press-wheel-and-cancellation-interaction)
owns those keys and meanings. The trail and root distance are visual only and
never change gesture thresholds or selection.

While the active menu's focused entry is an enabled action or branch that is
neither Back nor reserved, the crosshair is hidden and that entry's node
follows the pointer: the node stays on the ray from the menu origin through
the hidden pointer, so the circle center, the pointer and the node stay
collinear, and a connector line joins the center circle's boundary to the near
edge of the node's actual scaled title frame only when the frame leaves an
uncovered segment between it and the circle. The node's radial distance eases
with the existing transition and reduced motion, and a return to rest uses
that same transition. Focus alone qualifies the entry — whether it is a branch
awaiting its dwell or turn entry, or an action a release would commit — with
no fully elapsed timer needed. Only the active menu's own node, successfully
painted with at least part of its actual scaled title frame visible in the
viewport, can hide the crosshair or acknowledge its focused choice: a Precise
leaf's candidate is acknowledged only from that painted frame, and an inventory
row, an ancestor menu, or a node that failed to paint or is fully transparent
never triggers this feedback. Hiding
the crosshair leaves the trail and its preference unaffected, and the screen
confinement guarantees only the pointer center on screen; a following frame or
label is not guaranteed to stay wholly inside the screen.

While that same eligible focus is active, the selector emphasizes the focused
entry and de-emphasizes that menu's sibling entries by visual scale. The
emphasis engages on focus alone, with no dwell or turn required, and it changes
only the painted scale: hit geometry, dwell and turn thresholds, and release
resolution keep their frozen values. Back, disabled, or reserved focus, the
deadzone, and an idle menu keep every entry at its resting appearance, and
inventory-list rows are never affected by this mode. The emphasis and its
return to rest follow the existing scale interpolation and reduced motion.

The trail is one bounded visual history shared by the pointer and the active
followed option, not separate overlapping trails. When an eligible option
gains focus, ownership transfers smoothly from the logical pointer; switching
focus between eligible options transfers to the newly focused option, while
leaving eligible focus transfers back to the logical pointer. These transfers
use the existing finite selector transition and honor reduced motion. While an
option owns the trail, its samples use the center of the node as actually
painted after its animation, not the raw pointer target. The transfer keeps one
common projected history and does not paint duplicate overlapping histories
that amplify alpha. If a new owner has not yet been painted, the trail may show
the last committed history until a painted position is available. Deadzone or
Back focus, submenu changes, or other focus changes end the current option's
ownership as appropriate; inventory rows are never trail owners. While the
inventory list surface itself is active, no trail is painted at all, including
a prior tail, and an item's radial menu returns to the ordinary crosshair,
follower and trail rules. Mode handoffs start a fresh trail epoch without a
joining stroke. Pointer rebases, user-return translations and corrections
caused only by projection or viewport changes do not add a synthetic
connecting stroke. On ordinary release the trail fades
with its departing owner rather than snapping to the root; a hard abort or a
new hold discards the prior history.

## Submenu centering and view translation

While a menu is active, the whole selector overlay is translated so that the
active menu's origin lands on the GUI center. Every radial submenu uses this
mapping, including an inventory item's radial menu, whose logical origin is its
selected row's anchor. The translation is one rigid value for the entire
pattern: ancestor menus, the pointer, the trail, the interaction guides, and
the inventory list all move by that same value, so the pattern never shears.

A programmatic pop changes no logical geometry: parent-child menu placement
and the inventory list's row anchoring are preserved, and the translation only
projects that controller space onto the screen. A user's one-level return
instead aligns the returned parent with the logical pointer: once the
trigger's physical input is admitted and the pointer confined, one level is
removed and every retained menu origin and parent/row anchor is rigidly
translated by exactly the vector from the returned parent's origin to the
pointer, so the returned parent's origin lands on the pointer while the
pointer itself stays where the trigger left it. Every
retained parent-child placement vector, every entry choice that opened a
level, and the list's selected key, window, ordering, discovery state and side
orientation are preserved by that translation; only the placement moves, and a
zero-distance alignment is still a user return. The root begins each hold at
its zero origin, so opening the selector leaves the established center
unchanged; after a user return within the same hold the retained root origin
moves with the pattern, and the existing view translation then centers
whichever origin is active, so the established GUI-center contract is
unchanged and no screen-pixel anchor or native warp is added. A user return
clears any stale turn candidate or pending automatic placement, resets the
returned parent's travel, arms its fresh-stroke requirement, seeds a fresh
trail epoch, clears the list's Back-side focus when the return lands back on a
menu surface, and leaves the shared held-Back block as it was; when the
returned parent is a radial menu, an immediate release at the returned origin
is a deadzone release and abandons with no action. A programmatic return such
as data revocation or root pruning keeps the old placement and is not user
travel. A normal exit keeps the last
displayed translation instead of snapping back to the center, so the exit tail
does not jump; a screen, world, or connection discontinuity discards the
retained translation instead.

The pointer is confined under the same translation the frame paints: before
any gesture is evaluated, the logical pointer is clamped to the inclusive GUI
pixel centers of the viewport, so the drawn pointer stays on screen. Confined
movement counts as gesture travel and adds trail points, except while the
inventory list surface is active, where physical list input never moves the
pointer ([inventory list](#inventory-list)). Inventory-list
vertical glide is the exception: it uses the authorized GUI-scaled native
vertical delta even when the confined pointer is against the top or bottom
edge, without moving the pointer or adding trail travel. A correction produced
only by a changed projection or a resize is not gesture input: it does not
glide the list, add trail travel, side-navigate, advance dwell, re-arm held
Back, or trigger an interaction gate. It can update the confined pointer's
focus, which a later release resolves. The native virtual positions stay
unbounded, incremental and continuously tracked — movement spent beyond the
boundary is discarded rather than banked, so a reversal moves the pointer at
once with no stored overshoot. The rest of the pattern keeps the rigid
translation while the clamp holds the pointer at the screen boundary.

A non-input relayout of the active caller-pushed external menu or of an item
menu, such as from a GUI resize or an updated item-menu clearance, updates
that menu's placement, re-evaluates focus and restarts any pending hover dwell
at the new geometry, while preserving the held Back block; it pays no stroke,
trail or timer progress, and only the unchanged viewport confinement may
subsequently correct the pointer.

The translation is smoothly interpolated like the other selector transitions,
and reduced motion snaps it immediately. It does not change gesture thresholds
or selection rules; projection-only corrections do not count as gesture input.

While a radial child menu is active, each ancestor radial menu keeps only the
entry through which the active path descends, together with its Back
affordance (frame and sector) and its navigation guides and links; the
ancestor's other option frames and the sector underlays that correspond to
them, including disabled and reserved ones, are hidden. The retained
breadcrumb entry is no longer a follower: its frame is placed at that
ancestor's own origin, and its own sector underlay is hidden along with its
siblings'. Each ancestor level applies this independently, and every retained
frame targets its own level's origin. The Content menu keeps its
inventory-display entry as the breadcrumb when the inventory list opens, and
the list itself stays visible while an item's radial submenu is open. The
hidden frames and sectors fade out and back in with the existing
appearance/disappearance transition, and reduced motion snaps; returning
restores the ancestor's full appearance. A hidden ancestor entry remains a
logical menu choice with unchanged geometry, but it is never interactive: it
can commit no action, and neither a hidden entry nor an ancestor or detached
frame acknowledges a focused choice or a Precise candidate or owns the active
follower.

## Radial release result

For a release of an actually open selector, the frozen menu geometry resolves
the focused entry. A release inside the center deadzone abandons the session
with no action. A release on a sector resolves that sector: a focused leaf
commits exactly one action and ends the session; a precise leaf commits the
last actually painted selectable candidate version and never initiates a new
candidate capture; a reserved, disabled,
navigation or actionless-branch entry reports its reason and never commits. A
release outside every sector without crossing the deadzone is a silent no-action
that sends neither a create nor a cancellation request.
The down (180) cancel entry activates
[Cancel Marker selection](#cancel-marker-selection).

## Headless spatial menu model

The model is headless: it owns no clock, renderer, configuration or Minecraft
input, and the caller supplies monotonic timestamps, deltas and the frozen
settings snapshot. The virtual pointer is the only logical pointer state;
physical mouse deltas are added to it, except under the inventory list's
fixed-pointer input handling ([inventory list](#inventory-list)). The renderer
draws the bounded shared trail history according to the active pointer or
followed-option ownership in
[native selector presentation](#native-selector-presentation).
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
pops one radial level; row-anchored external menus do not retrace. A user pop arms a
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
automatically appended Back takes that same parent-centered position and
carries the localized Back label rather than an unlabeled frame; siblings keep
their declared order and tile with equal spans.

### Intent

The intent branch offers only the resolved Target Type's ordered Ping Types.
Each leaf creates the frozen ordinary captured target with that Ping Type.

### Precise

The precise branch offers the five fixed Target Types in
[catalog priority order](../identity/catalogs.md#target-type-resolution-and-fixed-order).
Its candidates are captured live from the current camera ray while the branch
is active, under
[Precise live candidate capture](capture.md#precise-live-candidate-capture):
entering the branch starts capture, leaving it pauses, and the hold's frozen
settings still change only on the next hold. A type's leaf is enabled only
while that type has a currently certified candidate. An incomplete scan
disables the leaf even when an ordinary target of that type exists; a refresh
in progress keeps the last certified candidate selectable, and a leaf with no
certified candidate is disabled. A focused fixed-type leaf keeps its focus when
the live target changes. Each available leaf creates its assigned canonical
candidate using that candidate's own Target Type default Ping Type; release
follows [Radial release result](#radial-release-result). Each enabled leaf also
shows its captured candidate's actual display name as a preview below its own
fixed type title frame and outside that frame; a leaf with no captured
candidate shows the title alone. The name is resolved from the same immutable
snapshot that carries the candidate
entries for that paint, so a shown name always belongs to the candidate version
the same frame exposes, and a newer live publication cannot retarget an already
painted node. The name follows the candidate's certified lifetime under
[Precise live candidate capture](capture.md#precise-live-candidate-capture): a
pending refresh keeps the last certified name, while a final missing or
incomplete scan clears it together with the entry. An unavailable name uses the
established unknown-name fallback, and a Location candidate uses the
established Here name, under the
[names and chat](../rendering/names_chat.md) composition rules; the branch adds
no separate name or appearance preference. The preview never changes the title
frame: that frame keeps its own bounds, and the preview is painted below it
without frame background or border. The preview carries at most 32 visible
Unicode code points, counted after vanilla legacy formatting; formatting
sequences do not consume the visible budget. A name of at most 32 visible code
points is shown whole — wrapped within the width shared by the Precise slots
at a readable text size rather than cut at the title button's width — while a
longer name keeps its first 31 visible code points followed by one ellipsis.
Wrapping and capping preserve whole code points and each code point's explicit
or legacy-resolved style; a glyph that still exceeds the shared width at that
readable size is not truncated a second time. The preview moves, scales and
fades with its node and follows the target opacity layer, and an exit tail
keeps the name it was painted with. The separate,
equal-width Back entry is navigation and is never a candidate.

### Content

The content branch presents the captured target's authorized observations
client-first: the client shows its own authorized local observation and falls
back to the server's bounded preview only for an allowed field with no local
observation. Detailed property selection, per-target-type policy and the
session value store are owned by
[presentation snapshot](../presentation/presentation_snapshot.md); the
inventory list's data, status, count and ordering rules are owned by
[inventory preview and tracking](../presentation/inventory.md).

Whether the content hierarchy offers its additional property and item Ping
Type menus is frozen per hold from the client's content-intent preference
([client configuration](../../config/client.md#spatial-selector-interaction)).
While it is enabled, every otherwise selectable property carries its allowed
Ping Type submenu, and the inventory list's item rows can open their item
menu. While it is disabled, each otherwise selectable property becomes a
terminal leaf whose release commits that property with the Attention
annotation, using its original reference and observed value; pure navigation
groups stay actionless branches; the list offers no item menu and no Forward
affordance; and a direct release of a selected row commits the item with the
Attention annotation, under the item annotation's eligible set owned by
[inventory preview and tracking](../presentation/inventory.md#preview). In
both modes the whole-marker Ping Type remains the frozen Target Type's
default, the Attention annotation is a property or item annotation rather
than the marker's whole-marker type, and the root Intent and Precise branches
are unchanged. The Attention annotation must be present in the entry's
authorized allowed Ping Type set; an empty or disallowed set leaves the entry
disabled and commits nothing instead of substituting another annotation or
inventing permission. The preference freezes with the hold's snapshot, while
the ordinary live preview, reference and release authorization fences remain
live and are never frozen or bypassed.

The content menu presents property entries in a navigation hierarchy. The
`minecraft:block.state` record summary is an actionless navigation group:
its nested choices are that record's state property entries, such as
`snowy:false`, while the summary itself carries no default release action
and no Ping Type submenu of its own. Every nested state property choice
keeps its own default release action and, while content intent is enabled,
its allowed Ping Type submenu.
Every `create:`-namespace presentation field, and any nested reference to
one, belongs to a localized Create properties navigation group; generic
Minecraft properties and the separate inventory item list keep their own
positions outside it. Within Create properties, the localized Stress group
contains only `create:kinetic.stress`, `create:kinetic.capacity`, and
`create:kinetic.available_capacity`, together with any nested reference to
those fields; `create:kinetic.overstressed` and `create:kinetic.has_network`
stay directly under Create properties, and the kinetic speed entry remains
directly selectable there. These groups are pure navigation: they carry no
property intent, a release while one is focused commits nothing, and Back
returns one level.

### Inventory list

The content branch's inventory preview opens a square list surface. Navigation
is parent-relative: the selected row's Y is the child origin for that row's
item menu, so an item submenu is anchored to its row rather than the list
center, and its pointer rebase starts a fresh trail without a synthetic stroke.
The item menu's center is measured outward from the inventory panel's forward
edge by a clearance that keeps the child menu's outer sector and the child
menu's actual localized Back title frame disjoint from the panel: the
edge-to-center minimum is at least the child menu's outer sector radius and
also covers the measured extent of that Back frame toward the panel, so
neither the sector underlay nor the full Back frame, not merely its label
text, can intersect the panel. The measurement follows the current GUI
dimensions; a GUI resize updates the placement through the non-input relayout
described in
[submenu centering and view translation](#submenu-centering-and-view-translation),
so the disjointness holds across the resize's first and intermediate frames,
not only at settled geometry. The list's Back/Forward sides are frozen when
it opens from the parent menu bearing: Back is placed on the side that faces
the parent, and a vertical parent keeps Back on the left. A Forward stroke enters the selected row's item menu while content intent is
enabled, and has no effect while it is disabled ([Content](#content)). A Back
stroke or Back-hover returns one level. A release with a selected row commits
through the inventory owner's single-item create path under the frozen
content-intent mode; a release while the Back side is focused leaves without
committing. Count, ordering, folding and item-selection semantics are owned by
[inventory preview and tracking](../presentation/inventory.md#preview).

While the list is the active surface, the inventory panel and its existing
header overlay are emphasized, including while the list is empty; the
established selected-row overlay is emphasized only while a direct release of
that row could commit an inventory intent and the Back side is not focused.
The existing panel, header and selected-row fill stacking and their
non-background alpha are retained, and no previously unfilled unselected row
gains a fill. While an item's radial submenu is open, the list beneath it is
not the active surface and is presented unfocused.

While an item's submenu is active, the list paints only the entered row,
identified by the item key frozen when that submenu opened rather than by the
current selected index; the complete row model, selection, visible window,
layout, panel, header and footer remain as they were, and returning restores
the other window rows through the existing appearance transitions. That
filtering is paint-only: it never shrinks or re-sorts the model, rewrites the
selection or collapses the panel. While content intent is disabled there is no
item submenu and every ordinary row paints.

A user Back from the list, by Back stroke or Back-hover, returns to the parent
Content menu through the general user-return alignment
([submenu centering and view translation](#submenu-centering-and-view-translation)):
the retained pattern, including the still-open list's placement, is rigidly
translated so the returned Content origin lands on the pointer, and the list
keeps its selected key, window, ordering, discovery state and side orientation.
The old programmatic placement, the logical pointer on the circumference of
the returned menu's center deadzone in the frozen direction of the
inventory-display entry center, is retained only for a programmatic return
such as data revocation or root pruning. That placement belongs to the idle
deadzone, so it neither refocuses the entry nor commits anything; it is not
physical gesture travel, does not re-arm held Back or count toward the
fresh-stroke requirement, and adds no joining stroke to the trail
([Back-hover return state](#back-hover-return-state),
[headless spatial menu model](#headless-spatial-menu-model)). The viewport
confinement still applies while the parent menu's translation settles: a
desired point that is temporarily outside the viewport resolves as the
projection makes it reachable, and fresh effective physical input supersedes
the automatic alignment.

While the list surface is active, physical list input never moves the logical
pointer: both pointer axes stay fixed and are not warped, rebased or recentered
by list travel, and only the unchanged viewport confinement may still correct
the pointer. Vertical row glide and horizontal side navigation are independent
accumulators fed from the same admitted raw GUI samples. The list advances rows
by accumulating each sample's signed vertical component at the intrinsic glide
baseline scaled by the frozen target-glide preference, with each row advancing
at the frozen row-height threshold; the multiplier's initialized meaning is
owned by
[client configuration](../../config/client.md#spatial-selector-interaction).
The selected row's position does not accelerate the glide. When vertical input
reverses direction, an opposing sub-row remainder is discarded before movement
accumulates in the new direction; zero vertical input does not reverse it.
Horizontal side intent accumulates bounded signed horizontal progress,
independent of the fixed pointer, up to the existing side-exit distance with
overshoot discarded rather than banked, so a reversal acts at once. Each
admitted sample is classified before accumulation: when a sample has a nonzero
vertical component and its horizontal component is small relative to it, inside
the vertical-intent cone, the sample's horizontal component is ignored while
its vertical component and any previously accumulated horizontal progress are
retained; outside the cone, horizontal progress accumulates safely. Only
effective filtered horizontal progress can trigger the side hint, the
Back/Forward-side navigation gates or a physical release of held Back; a
vertical-only, cone-filtered or saturated sample cannot. Actual retained side
progress outside the side-hint corridor keeps the established suppression of
vertical glide, and a projection-only or resize correction cannot create that
suppression. Entering or leaving the list for an item submenu, and returning
from that submenu, rebase the relevant gesture accumulation without changing
the selected item or the visible list window; the item-to-list return also
translates the retained list placement by the user-return vector. Vertical
glide is not general gesture travel: it adds no trail points and cannot trigger
side navigation or re-arm held Back. On the list, the drawn crosshair is hidden as presentation only. An item's
radial submenu and a return to the parent follow the
[ordinary radial crosshair rules](#native-selector-presentation) rather than
forcing the crosshair visible, so a qualifying painted follower can hide it
there as well. Fractional ordinary-wheel input and its existing
wheel-lock behavior are unchanged by list gliding.

### Target-selection settings

The settings branch exposes the three target-selection toggles. The toggle
meanings and their next-capture application are owned by
[selection policy](selection_policy.md#selector-settings-branch).

Each toggle entry shows its localized name followed by a localized ON/OFF
state — green for ON, red for OFF — and only the state word is colored; the
name, the separator, and the entry's border styling stay unchanged. The
displayed state samples the live setting on every draw rather than the hold's
frozen raycast policy, so a setting changed while the interaction is still
held is reflected on the next draw without reopening the wheel, while the
captured target and the hold's frozen policy remain unaffected.

## Mouse ownership

The selector releases the vanilla mouse grab only while it is actually open
and no client screen is open. It re-grabs the mouse only when that grab was
released by the selector and no screen is open; an initially free pointer is
restored without grabbing. Live ownership synchronization defers while a screen
is open, and disposal may relinquish pending selector ownership rather than
stealing the cursor from a screen or another owner. Native disabled-mode
ownership is separate from that entitlement: it neither establishes nor
preserves ownership of the vanilla grab.

While the selector owns input for its current window — ownership is active,
the window is focused, and the mouse is not grabbed — it owns that window's
native disabled cursor mode: the physical pointer is hidden and confined, and
the selector receives continuous unbounded virtual cursor positions. The
vanilla mouse-grab flag remains false, so camera look remains isolated. The
selector draws its own virtual pointer and derives its owned GUI deltas from
scaled successive changes of those virtual positions, with camera sensitivity
never applied. The native mode transition, its virtual-coordinate reset, and
grab, focus, window and scale handoffs are primed so the selector's first
samples contribute no artificial travel; no per-frame recenter or reset
occurs. Raw mouse-button press and release edges continue to reach the
selector; only vanilla's automatic press-time re-grab is narrowly suppressed
while the wheel is open, and an ordinary close may re-grab under that
entitlement. The native input adapter exposes no screen facade, and the
selector only reads virtual positions for its own GUI deltas.

While the selector owns input for its current window, every scroll callback is
consumed before vanilla can act on it, including in radial mode, for a
horizontal-only delta, and for a zero vertical delta. Only the inventory list
consumes the vertical scroll amount to move its rows; a radial menu consumes
the callback without using a scroll value, and a consumed scroll moves no
pointer. A scroll outside that ownership passes through to vanilla unchanged.

Ending that ownership — closing the selector, a window change, focus loss, a
screen taking over, or an interaction abort — releases the selector's own cursor
mode without overriding a mode established by a newer owner. Native ownership
and its release neither move the drawn pointer nor change gesture behavior. The
screen-transition abort rule remains owned by
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
value in `[0, 1]` that a renderer can paint along the focused frame's actual
perimeter. At the dwell threshold the model reports one return trigger and then
blocks the same held
focus, so a single sustained focus never cascades through several levels — even
when the menu changes to the parent and the focus stays in the Back direction.
The block is released only when effective physical movement takes focus out of
Back; projection-only or stationary focus loss and outward movement saturated
at zero effective travel leave it blocked. A deliberate physical leave and
re-entry arms exactly one new return. Switching the focused menu restarts the
dwell clock. A rewound clock keeps its original baseline and reports the
minimum instead of restarting. Ending the interaction clears the timer and the
block, and a new session always starts unblocked. When the hover preference is
disabled, the session stays inert and never triggers.

The preference keys and their local authority are owned by
[client configuration](../../config/client.md#spatial-selector-interaction);
their numeric range, step, and default remain implementation values. Coverage
for the native screen and input integration and game-feel validation is owned
by [testing and verification](../../testing/verification.md).
