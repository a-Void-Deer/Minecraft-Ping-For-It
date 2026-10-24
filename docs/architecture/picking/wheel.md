# Wheel interaction and cancellation

> **Coverage.** This topic states the native-selector interaction contract.
> Coverage scope and pending runtime evidence are owned by [testing and verification](../../testing/verification/interaction.md#headless-spatial-radial-menu).

## Opening and selection

Opening follows [capture readiness and the present-frame boundary](capture.md).
Every subsequent action operates on the captured context, except the Precise
branch, whose candidates are live while that branch is active
([Precise](#precise)). An actually open
selector has no elapsed-time auto close: it ends through a release, a
cancellation, or an interaction lifecycle abort. An enabled inventory surface
stay-open is the confirmed release exception, owned by
[inventory selection stay-open](#inventory-selection-stay-open). Pre-open
release behavior is owned by
[capture](capture.md#baseline-release-and-actual-wheel-opening).

One held interaction consumes one immutable, validated
[spatial-selector settings](../../config/client.md#spatial-selector-interaction)
snapshot; editing the live configuration cannot change a gesture already in
progress by mutating that snapshot. The pre-open shortcut disqualifications
below still apply. The retired persisted wheel radius and wheel timeout keys
never configure this interaction; their removal and migration are owned by
[revisioning](../config/revisioning.md#ordered-migration-and-writeback) and
[client configuration](../../config/client.md#obsolete-keys).

### Pre-open trajectory and resource fallback

While the optional
[physical-hold gesture](../input/long-press.md#optional-physical-hold-gesture)
remains active, actual opening carries the complete admitted pre-open movement
into the selector once, in its original order and with its original sample
times. The stroke, dwell, turn corners and trail therefore continue from the
press rather than starting from only the final pointer position. This handoff
does not backdate actual opening or native input ownership. Release, abort, or
an observed activation of compatibility mode discards the pending shortcut
movement instead of replaying it into another hold.

Pre-open trajectory storage and handoff work are bounded. Exceeding that
resource bound disables only this hold's gesture shortcut and discards its
entire trajectory; neither a retained prefix nor a later tail may be replayed,
and later movement cannot revive the shortcut within that hold. The current
interaction token, original press time, frozen target, capture and held state
remain intact. Nothing is sent early, and no additional notice is shown.
Ordinary long press continues from the original press time under
[capture's actual-open gates](capture.md#baseline-release-and-actual-wheel-opening);
if it opens, its selector starts at the center and accepts only subsequent
owned input. A release before an actual open keeps the ordinary default-create
path, including waiting for pending capture. The next physical press can use
the shortcut again, without changing its persisted preference.

## Native selector presentation

The selector keeps square nodes and straight interaction guides, with the
radial underlays and the center circle as its only curved elements: options are
framed by rectangular borders, the pointer is a straight crosshair, the trail
is a series of small squares, menu guides are straight lines, and the
Back-hover progress traces the focused Back title frame's actual boundary. A
radial menu may place a semi-transparent circular sector under each entry to
make its bearing and angular span legible and to support label contrast. This
sector is a visual underlay; it does not round or replace the node, pointer,
trail, guide, or Back-hover geometry, and it does not change focus or release
selection.

Circular sector underlays and the inventory list's Back semicircle fade
smoothly outward from their center coverage to transparency at their outer
edge. The existing focus, transition and opacity layers still determine that
center coverage. This radial fade affects only those underlay backgrounds:
text, icons, borders, Back progress and interaction chrome retain their own
appearance, and rectangular title and inventory-panel backgrounds do not fade
radially. It is not a glow or bloom effect and introduces no appearance
preference.

Each radial menu also draws center guides at its own origin: one closed circle
of the frozen center deadzone's radius — the deadzone's actual boundary,
not a circle at half that radius — and one straight divider on every actual
shared boundary between adjacent sectors, from that circle out to the outer
radius of that menu's sector underlay. The dividers follow the real sector
boundaries, including the root's unequal spans and the boundaries of any Back,
disabled or reserved entry. An ancestor derives actual shared-boundary dividers
only from the original sector geometry of its retained breadcrumb and Back
choices; hidden choices contribute no divider, while the center circle remains.
The center guides are interaction chrome, independent of the wheel-underlay and
target opacity preferences.

The root presents the fixed eight-sector layout:

| Bearing | Span | Root entry |
| ---: | ---: | --- |
| 0 | 55 | Danger — enabled only when the resolved Target Type allows `danger`; otherwise disabled |
| 45 | 35 | Content |
| 90 | 55 | Reserved placeholder, disabled |
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
choice that can submit a Ping uses the actual submitted Ping Type's outline
color for a complete solid border, including a branch with a default submission
and a selectable inventory row. Property and item annotations use their own
annotation type rather than the whole-marker type; the
[fixed catalog](../identity/catalogs.md) owns those colors. Pure navigation and
Back keep neutral borders, and disabled or reserved choices keep dashed borders.
Unping and the three target-selection toggles use distinct, fixed local-action
border colors: coral orange for Unping, cyan for fluids, violet for the entity
blacklist, and mint green for transparent blocks. These are local action roles,
not additional Ping Types, and their colors remain the same when focused or
when a toggle changes state.

Titles remain single-line at their configured text size and are painted
complete; each title frame takes its natural width from its own complete label.
The active menu's unfocused title frames may move vertically within a bounded
neighborhood to reduce overlap, with their centers staying in their original
sectors. This moves the complete frame and its text together, without changing
its width, height, horizontal anchor, sector, hit test, dwell, turn or release
result. The area considered for an unfocused frame's bounded avoidance is the
union of that frame and (in [precise](#precise)) any external name preview painted below it. Placement retains a stable solution
through small input changes and follows the existing finite transitions and
reduced-motion preference. A changed text measurement or viewport is a visual
relayout, never gesture input. Ancestor breadcrumbs, inventory rows and the
inventory item menu's Back frame retain their own positioning rules.

The focused frame keeps its established pointer-following geometry and is
painted above other titles, inventory content, ancestors and detached exit
frames as one group of background, border, text and attached feedback. Former
focus in an exit tail has no active-focus priority. Menu links lie below text,
and the follower connector leaves painted title frames uncovered. The final
pointer feedback still follows successful node painting. Titles may retain
overlap where space is limited or content exceptionally dense: bounded, stable
placement and readable focus take precedence over wrapping, shrinking or
unbounded spreading.

Position, opacity and scale are smoothly interpolated for an unchanged entry,
for appearance and disappearance, and across a radial/list mode switch. A
removed entry keeps only detached paint data for one exit interval and is never
interactive. Reduced motion collapses the interpolation without changing
selection. A sector underlay's and a text-bearing frame's background coverage
is an independent interpolated channel: it follows the same appearance, exit,
rewind, retention, detached-exit and reduced-motion rules as the rest of the
transition, so either the foreground or the background alone can keep an entry
painted. In a radial menu, while an enabled action or branch entry holds focus,
that entry's own sector and frame background are enhanced and the menu's other
entries' backgrounds are dimmed; an idle menu, radial Back, disabled or reserved
focus, and the deadzone keep the established base coverage, with the ancestor
and disabled/reserved base treatments retained. Inventory-list surfaces and
their Back affordance instead follow the coverage rules in
[Inventory list](#inventory-list). Opacity is split by layer: the wheel opacity
preference scales only the radial underlays' background coverage, while the
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
deadzone, and an idle menu keep every entry at its resting scale, and
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
a prior tail, and list movement records no trail samples. An item's radial
menu returns to the ordinary crosshair, follower and trail rules. Mode
handoffs start a fresh trail epoch without a joining stroke. Pointer rebases,
user-return translations and corrections
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

A programmatic pop preserves parent-child menu placement and the inventory
list's row anchoring, and the translation only projects that controller space
onto the screen; item-to-list pointer synchronization follows
[Inventory list](#inventory-list). A user's one-level return
instead aligns the returned parent with the logical pointer: once the
trigger's physical input is admitted and the pointer confined, one level is
removed and every retained menu origin and parent/row anchor is rigidly
translated by exactly the vector from the returned parent's origin to the
pointer, so the returned parent's origin lands on the pointer while the
pointer itself stays where the trigger left it; an item-to-list return may then
synchronize its screen Y under [Inventory list](#inventory-list). Every
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
radial movement counts as gesture travel and adds trail points. The
[inventory list](#inventory-list) instead moves the same confined pointer under
its list-specific input rules, records no trail travel, and admits bounded
horizontal screen-edge extrapolation for side intent. A correction produced
only by a changed projection or a resize is not gesture input: it adds no trail
or gesture travel, side-navigates no level, advances no dwell, re-arms no held
Back, and triggers no interaction gate. It can update the confined radial
pointer's focus, which a later release resolves; list corrections preserve a
legal selected key instead of selecting from the corrected position. The
native virtual positions stay unbounded, incremental and continuously tracked
— movement spent beyond the boundary is discarded rather than banked, so a
reversal moves the pointer at
once with no stored overshoot. The rest of the pattern keeps the rigid
translation while the clamp holds the pointer at the screen boundary.

A non-input relayout of the active caller-pushed radial menu or of an item
menu, such as from a GUI resize or an updated item-menu clearance, updates
that menu's placement, re-evaluates focus and restarts any pending hover dwell
at the new geometry, while preserving the held Back block; it pays no stroke,
trail or timer progress, and only the unchanged viewport confinement may
subsequently correct the pointer.

The translation is smoothly interpolated like the other selector transitions,
and reduced motion snaps it immediately. It does not change gesture thresholds
or selection rules.

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

Before the frozen-geometry adjudication below, a release first passes the
[inventory selection stay-open](#inventory-selection-stay-open) handling; only
a release that handling leaves to the ordinary result follows this section. A
release inside the center deadzone abandons the session with no action. A
release on a sector resolves that sector: a focused leaf commits exactly one
action and ends the session; a precise leaf commits the
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
physical mouse deltas are added to it subject to confinement and the
[inventory list's input rules](#inventory-list), rather than creating a second
list pointer. The renderer draws the bounded shared trail history according to
the active pointer or followed-option ownership in
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
their declared order and tile with equal spans, subject only to the top-level
[Content inventory placement](#content).

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
settings still change only on the next hold. Leaf enablement and certification
follow [Precise live candidate capture](capture.md#precise-live-candidate-capture).
A focused fixed-type leaf keeps its focus when the live target changes. Each
available leaf creates its assigned canonical candidate using that candidate's
own Target Type default Ping Type; release
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
frame, and the preview is painted below it without frame background or border.
The preview carries a bounded visible code-point budget, counted after vanilla
legacy formatting; formatting sequences do not consume it. A name within the
budget is shown whole — wrapped within the width shared by the Precise slots at
a readable text size — while an over-budget name reserves the final visible
code point for one ellipsis. Wrapping and capping preserve whole code points and
each code point's explicit or legacy-resolved style; a glyph that still exceeds
the shared width at that readable size is not truncated a second time. The
preview moves, scales and fades with its node and follows the target opacity
layer, and an exit tail keeps the name it was painted with. The separate Back
entry is navigation and is never a candidate.

### Content

The content branch presents the captured target's authorized observations
client-first: the client shows its own authorized local observation and falls
back to the server's bounded preview only for an allowed field with no local
observation. Detailed property selection, per-target-type policy and the
session value store are owned by
[presentation snapshot](../presentation/presentation_snapshot.md); the
inventory list's data, status, count and ordering rules are owned by
[inventory preview and tracking](../presentation/inventory.md).

Within the equal-sector, parent-centered Back geometry, the top-level Content
menu places its inventory choice as close to due right as the non-Back slots
permit. When a non-Back slot contains the 90-degree bearing, that is the
inventory slot. It does not force that slot's center to 90 degrees or rotate
Back away from the real parent direction. At an equal-distance shared boundary,
the existing start-inclusive/end-exclusive hit rule determines the inventory slot so a
due-right stroke focuses inventory rather than its neighbor. Other properties
and navigation groups keep their relative order; without an inventory choice
there is no reordering, a disabled inventory choice retains its slot, and
nested groups are unaffected.
The root's upper-right Content entry followed by a rightward gesture can
therefore reach inventory when the ordinary branch-entry gates admit it; this
placement bypasses no stroke, dwell or turn requirement.

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

The content branch's inventory preview opens a rectangular list surface. Its
content panel, including the header and footer, grows with the presented
content up to viewport-relative width and height maxima from the held
[preferences](../../config/client.md#spatial-selector-interaction), measured
against the current GUI-scaled viewport; these are maxima, not forced dimensions.
Inventory text keeps its configured size rather than shrinking to fit the panel.
The minimum readable panel takes precedence on a small GUI and may exceed those
proportions. The exterior Back affordance is not part of the content-panel size.

A resize within the same hold recomputes the panel against the new viewport
using those same frozen fractions. Input geometry and painting share the
resulting logical panel metrics, so the configured caps also govern row
capacity, panel-edge side intent and item-menu placement; they do not change
the held deadzone or stroke distances.

Navigation is parent-relative: the selected row's Y is the child origin for
that row's item menu, so an item submenu is anchored to its row rather than the
list center, and its pointer rebase starts a fresh trail without a synthetic
stroke.
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
not only at settled geometry.

The list's Back/Forward sides are frozen when it opens from the parent menu
bearing: Back is placed on the side that faces the parent, and a vertical parent
keeps Back on the left. The localized rectangular Back title frame sits outside
that side of the panel at its vertical midpoint, mirrored when the Back side
changes. An outward-facing semicircular underlay sits behind it and is enhanced
while Back is focused; the rectangular frame and its progress remain distinct
from the underlay. A Forward stroke enters the selected row's
item menu while content intent is enabled, and has no effect while it is
disabled ([Content](#content)). Back uses the stroke when hover is disabled and
the shared Back-hover timer when enabled; either returns one level.
Once the [inventory selection stay-open](#inventory-selection-stay-open)
handling leaves it to the ordinary result, a release with a selected row commits
through the inventory owner's single-item create path under the frozen
content-intent mode; a release while the Back side is focused leaves without
committing. Count, ordering, folding and
item-selection semantics are owned by
[inventory preview and tracking](../presentation/inventory.md#preview).

While the list is the active surface, the inventory panel and its existing
header overlay are emphasized, including while the list is empty; the
established selected-row overlay is emphasized only while a direct release of
that row could commit an inventory intent and the Back side is not focused.
The existing panel, header and selected-row fill stacking and their
non-background alpha are retained, and no previously unfilled unselected row
gains a fill. While an item's radial submenu is open, the list beneath it is
not the active surface and is presented unfocused.

On the active list, only the currently selected row that a direct release can
commit carries its annotation-colored solid border. Data-quality text may remain
grey without losing that border. Unselected rows and rows that leave the visible
window do not retain that annotation border. Back-side focus, an active item
submenu, or loss of direct annotation authorization removes the direct-send
decoration; a retained row behind its item menu does not claim to be the current
release target. Border painting changes neither row fills nor selection gates.

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
the logical pointer stays fixed while the remaining radial pattern is rigidly
translated so the returned Content origin lands on the pointer. Leaving the
inventory navigation path closes the list and clears its rows and selection.
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

The active list draws a moving virtual crosshair from the controller's sole
logical pointer. It never exposes a native cursor or hands camera look back to
vanilla; [mouse ownership](#mouse-ownership) remains in force. An item's radial
submenu and a return to Content use the
[ordinary radial crosshair rules](#native-selector-presentation), so a
qualifying painted follower can hide the crosshair there. List input is not
radial gesture travel; trail ownership remains as described in that section.

Vertical mouse selection uses absolute screen Y across the entire ordered
list, not the visible row window or the panel's row height. For a GUI viewport
of height `H`, the inclusive pixel-center interval from `0` to `H - 1` maps
across all `N` discovered rows: the top selects the first row, the bottom the
last, and downward movement advances toward later rows. Mouse selection leaves
the pointer at its actual confined Y rather than snapping it to the selected
row's representative screen Y. An empty list has no selected row, and a
single-row list remains selectable without dividing by a nonexistent interval.

Ordinary-wheel input retains fractional row amounts and advances the selection
by whole rows exactly, clamped at the endpoints. A whole-row wheel step
synchronizes pointer Y to the resulting row's representative screen Y and locks
mouse-driven Y and row selection against accidental drift; X remains movable.
Unlocking requires the existing signed-net vertical GUI travel threshold, not
the sum of absolute travel: opposing movement cancels the pending unlock
travel. While locked, admitted vertical input pays only that threshold. A
sample that crosses it moves Y only by its remaining vertical delta, never by
replaying travel already spent unlocking. Only Back-hover focus suppresses
wheel selection. With hover disabled, Back-side focus still permits wheel
selection and its pointer-Y synchronization, while a release remains
noncommitting. Vertical input suppressed by side intent cannot pay unlock
travel.

Initial list entry selects the first available row and places pointer Y at the
screen top. New-row discovery, a resize, publication of a changed viewport or
projection, and an item-to-list return retain a legal selected key and keep it
visible, synchronizing pointer Y to that key's representative screen Y only
when necessary to keep the mapping consistent. This is system positioning,
not user selection: it cannot change discovery ordering, pay gesture or unlock
travel, navigate, advance dwell, release the held-Back block, or add a joining
trail stroke. In particular, an item return may synchronize Y after the general
user-return translation without being mistaken for physical input. Entering
or leaving an item submenu resets the relevant side-gesture accumulation
without reopening or re-sorting the retained list.

Horizontal side intent uses the shared, unanimated logical content-panel side
bounds, including the header and footer but excluding the exterior Back title
frame. Motion within that lateral span has no side intent. Crossing a panel
edge starts side progress at zero at that edge and counts only the portion
outside; returning inside clears it. The panel's painted animation is never a
moving gesture boundary. Each admitted sample retains the existing inclusive
vertical-intent cone: it filters only that sample's horizontal component and
does not discard earlier side progress. Vertical-only or cone-filtered input
cannot create side intent.

The held snapshot's deadzone and stroke distances also govern list sides,
measured in GUI pixels from the panel edge rather than from the list center.
They are independent distances from that same origin, not distances added
together. Progress at or below the deadzone carries no side intent. Beyond it,
the side hint engages and pointer Y and mouse selection freeze; on the Back
side it also establishes Back focus and prevents a release from committing the
selected row. Reaching the stroke distance returns one level on the Back side
only when Back-hover is disabled; with hover enabled, the shared
[Back-hover state](#back-hover-return-state) exclusively controls that return
and the stroke cannot preempt its timer or held-focus block. On the Forward
side, reaching the stroke distance can enter the selected item's menu only
under [Content's intent gate](#content). A disabled Forward path creates no new
release-cancellation behavior: it does not turn that side into Back.

Screen confinement does not make a list side unreachable. After an actual
panel-edge crossing, authorized raw outward GUI deltas can continue bounded
side progress while the pointer is clamped at the screen edge. If the panel
edge lies on or beyond that screen edge so a real crossing is impossible,
outward input at the corresponding screen edge may instead start progress
there. Progress saturates at the stroke distance and discards overshoot, so a
reversal acts immediately without stored debt. This extrapolation is side
travel only: it never moves the crosshair off screen and is not a physical
leave of Back that could release the shared held-Back block. Projection,
resize, and system pointer synchronization likewise cannot release that block
or manufacture side navigation.

### Inventory selection stay-open

An opt-in preference keeps an actually open selector's inventory surfaces open
after release. Its key and local meaning belong to the
[client configuration](../../config/client.md#spatial-selector-interaction); it
freezes with the held interaction's snapshot and therefore applies to the next
interaction rather than mutating the running one.

While the preference is enabled and the inventory list or an item's radial
submenu is the active surface, the first release of the held interaction
(wherever it resolves, including inside the item menu's center deadzone or over
a Back affordance) neither commits nor ends the session. It retains the
interaction token, the inventory preview data and native input ownership, and
the interaction's already-frozen settings remain in force. A later physical tap
of the same Ping key whose release resolves a committable entry on that surface
commits once
under the ordinary release result; a hold that reaches the frozen long-press
threshold cancels and closes instead, and a release is checked for that boundary
even when no render frame was delivered. Vertical mouse-wheel selection stays
available, and an item submenu's Back return to the list preserves the session.

An inventory path exit cancels the whole stay-open selector and closes it,
without sending a marker cancellation: this covers a list Back that leaves the
inventory ancestry, a content revocation, and a permission revocation. The
selector never remains open on Content after such an exit, and no stale row may
submit. Focus loss, a screen transition, a world or level discontinuity, and a
connection discontinuity abort it under
[capture](capture.md#interaction-lifecycle-aborts). A committing release keeps
the established SELECT-before-CLOSE ordering. The behavior adds no hold timeout
or heartbeat because the inventory preview has none.

This session takes precedence over
[long-press compatibility](../input/long-press-compatibility.md) and
[double-click Danger](../input/double-click-danger.md); lifecycle aborts still
run first.

### Target-selection settings

The settings branch exposes the three target-selection toggles. The toggle
meanings and their next-capture application are owned by
[selection policy](selection_policy.md#selector-settings-branch).

Each toggle entry shows its localized name followed by a localized ON/OFF
state — green for ON, red for OFF — and only the state word changes color with
the setting. The name and separator keep their text styling; the entry retains
its fixed local-action border under [native selector presentation](#native-selector-presentation). The
displayed state samples the live setting on every draw rather than the hold's
frozen raycast policy, so a setting changed while the interaction is still
held is reflected on the next draw without reopening the wheel, while the
captured target and the hold's frozen policy remain unaffected.

## Mouse ownership

Before actual opening, observing movement for the optional gesture shortcut is
passive: camera look and the existing mouse ownership continue normally. Only
actual opening starts the selector's ownership below. The ordered movement
handoff under
[pre-open trajectory and resource fallback](#pre-open-trajectory-and-resource-fallback)
does not consume the same physical sample twice, and the native-mode transition
is primed without adding a jump to the continued gesture.

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
consumes the vertical scroll amount to move its rows, with pointer-Y
synchronization under [Inventory list](#inventory-list); a radial menu consumes
the callback without using a scroll value or moving its pointer. A scroll
outside that ownership passes through to vanilla unchanged.

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

The return state machine is a standalone, headless client model. One held
interaction shares its timer and held-Back block across radial and inventory-list
Back affordances. A session freezes whether Back-hover is enabled and the
required dwell at its start, so a later preference change cannot alter a running
interaction. While a Back affordance is focused, sustained focus accumulates
dwell and reports a progress value in `[0, 1]` that a renderer can paint along the
focused frame's actual
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
[client configuration](../../config/client.md#spatial-selector-interaction).
