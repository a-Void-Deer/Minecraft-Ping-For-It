# Configuration UI

This topic owns the client settings screen, its client/server scope navigation
and category layout, and its server-settings UI workflow. It does not own the
client file catalogue ([client configuration](../config/client.md)), the
persisted server file catalogue
([server configuration](../config/server.md)), the remote change transaction
([changing server configuration](../architecture/config/changing-server-config.md)),
the presentation policy rule-view route and selector semantics
([presentation snapshot](../architecture/presentation/presentation_snapshot.md)),
the permission requirement for editing
([server configuration authority](../architecture/authority/server-config.md)),
server-side enforcement ([security](../architecture/security.md)), or handler
persistence policy
([configuration revisioning](../architecture/config/revisioning.md)).

## Screen structure

The settings screen is a root screen with native vanilla-style scope tabs for
the client and server scopes. Each scope tab opens an overview of category
navigation buttons; a category button carries an ellipsis to mark that it opens
a further page instead of performing an action.

A category opens a leaf page:

- the root scope tabs are hidden;
- the title identifies the scope and the category;
- related options are introduced by plain-text, non-interactive subgroup
  headings rather than interactive boxes or collapsing sections;
- options use native option widgets; and
- only explicitly related pairs share a row; every singleton occupies its own
  full-width row rather than filling an unrelated half-row.

A pair stacks vertically, in its original order, when the viewport is too narrow
for native half-width controls or either current translated label does not fit
its half-width control. This also applies to presentation-editor pairs, including
Allow/Block and selector/action rows. The native full-width list maximum is
retained; rows and headings fit inside the GUI-scaled viewport with room for the
scrollbar, and footer controls stay inside the viewport without overlapping the
scrollable content. A clipped subgroup heading exposes its complete localized
text through a native tooltip without becoming interactive.

The footer is fixed. Back and Escape return to the current scope overview;
Done and Escape at the root close the screen. Keyboard traversal uses the
platform's native widget navigation in visual order. Scrollable content stays
above the footer rather than covering it, and each page retains its scroll
position and focus context, so returning to an overview and re-entering a
category resumes where the player left. A window resize or GUI-scale change
does not discard the session or a draft.

## Category layout

The lists below give overview order, then subgroup and row order within each
category. Paired controls are marked explicitly; all other controls are
full-width singletons. Localized category labels retain their stable navigation
identities.

The client scope has seven categories:

- **Markers & Range**:
  - Reach and display duration: Ping Distance, then Marker Display Duration.
  - Marker presentation: Ping Size, then paired Item Icons and Direction
    Indicator.
  - Author presentation: paired Player Info and Team Color.
- **Target Selection**: Mark Transparent Blocks, Mark Blacklisted
  Targets, and Mark Fluids under a target-selection heading.
- **Wheel Appearance**:
  - Layout: paired Root Menu Distance and Submenu Size, then paired Inventory
    Max Width and Inventory Max Height.
  - Readability: paired Wheel Opacity and Wheel Target Opacity, then paired
    Wheel Option Font Size and Wheel Target Font Size.
  - Gesture trail: Show Gesture Trail, then paired Trail Length and Trail
    Opacity.
  - Motion: Reduce Selector Motion.
- **Controls & Gestures**:
  - Opening: Wheel Hold Time, then paired Gesture Skips Long Press and Minimum
    Gesture Hold.
  - Compatibility: Long-Press Compatibility Mode, then Compatibility Time Slice.
  - Double-click Danger: Double-Click Danger Window, then Double-Click Danger
    Angle.
  - Marker cancellation: Cancel Cone Half-Angle.
  - Spatial selector gestures: paired Center Deadzone and Entry Stroke Length,
    then Entry Dwell, then paired Require Sustained Hover to Return and
    Back-Hover Dwell.
  - Content selection: Content Intent Selection, then Inventory Selection Stay
    Open.
- **Channel, Sound & Notices**: Ping Channel as a full-width text input under
  Channel, followed by Ping Volume and Configuration Notice Size under Sound
  and notices.
- **Rendering & Config**: Entity Block Geometry under entity-block rendering,
  followed by the configuration-file action under its own heading.
- **Performance**: a Wheel options subgroup with Precise Selection Capture
  Period.

The server scope has five categories:

- **Channel & Players**: Default Channel Mode and Player Tracking.
- **Send Rate**: Regeneration Time and Rate Limit.
- **Marker Duration**: Sync Duration.
- **Performance**: a Monitoring subgroup with Budget Delay Notifications; a
  Shared inventory budgets subgroup with Physical Slots per
  Tick, Pending Memory, Display Byte Size, and Frame Byte Size; an Inventory
  preview subgroup with Preview Period, Preview Variants per Client Period,
  Preview Slots per Client, Preview Slots Server-wide, Preview Targets per
  Client, Preview Client Byte Multiplier, and Preview Global Byte Multiplier;
  and an Inventory tracking subgroup with Tracking Period, Separate Tracking by
  Face, Separate Tracking by Player, Tracking Variants per Target, Tracking Slots
  per Target, Tracking Slots Server-wide, Tracking
  Stream Byte Multiplier, Tracking Snapshot Byte Multiplier, Tracking Global
  Byte Multiplier, Resync Cooldown, Heartbeat Cadence, and Byte Smoothing
  Window.
- **Server Presentation**: Server Policy.

All ordinary server controls occupy full-width rows, including the Monitoring,
Shared inventory budgets, Inventory preview, and Inventory tracking subgroups.
The independent Server Policy editor retains its native controls and related
Allow/Block pairs.

Each option's value semantics and persisted form remain owned by the
configuration catalogues. This page owns their UI placement, layout and help,
and whether a group edits local or server policy.

The screen exposes no controls for the hidden native raycast cap or the four
direction-indicator safe-area insets; their file semantics remain in the client
catalogue. The retired wheel radius and wheel timeout keys are also absent from
the screen and have no persisted meaning
([client configuration](../config/client.md#obsolete-keys)). There is no
user-facing reload control.

## Contextual help and localization

Every exposed value control provides native hover and keyboard-focus help that
identifies its scope and explains the effect the player can observe. Numeric
help explains decreasing and increasing the value; boolean help explains both
off and on; enum help explains every mode rather than relying on mode names.
Help preserves meaningful zero or unlimited states, interactions with related
settings, and when edits take effect, including next-hold application where
applicable. It must not promise a distinction that the implementation does not
produce.

All twelve category buttons have scope-appropriate descriptions of what the
category contains. The client and server Performance descriptions distinguish
local capture work from server monitoring and inventory administration.

Navigation and action controls also provide native hover and keyboard-focus
help. Scope/category navigation and Back distinguish navigation from final
close; Done describes the close-time persistence attempt without promising
server acknowledgement. Reset and configuration-file help explain the
consequences in [File action and reset](#file-action-and-reset), including the
client-only reset scope, draft discard, and save/close/open-file behavior;
file-action help also explains external-edit reload timing. Presentation-editor
help distinguishes changing the displayed view or requesting a refresh from
sending a mutation through its independent route.

Labels, subgroup headings, tooltips, notices, and assembled visible format
strings, including presentation field outcomes and name/value text, are
localized in every bundled locale. Technical field IDs and selectors retain
their literal identities. Help uses native tooltip behavior, not a custom
tooltip renderer. Configuration catalogues remain the owners of detailed value
meanings.

## Input interaction controls

Center Deadzone and Entry Stroke Length are shared controls for radial and
inventory-side gestures; there is no separate inventory vertical
mouse-sensitivity control. Their value semantics and persisted form are owned
by [client configuration](../config/client.md#spatial-selector-interaction).

The gesture controls' tooltips state that edits apply to the next hold. While
compatibility mode is enabled, the gesture toggle displays OFF and both gesture
controls are disabled; changing compatibility refreshes their displayed value and
availability on the same page. Leaving compatibility mode makes them editable
again without turning the toggle back on. The persisted preference-clearing
relationship is owned by
[client configuration](../config/client.md#spatial-selector-interaction), and
gesture timing is owned by
[long-press timing](../architecture/input/long-press.md#optional-physical-hold-gesture).

Double-Click Danger Window and Double-Click Danger Angle are the two controls of
the [double-click Danger gesture](../architecture/input/double-click-danger.md);
their keys and meanings are owned by
[client configuration](../config/client.md#press-wheel-and-cancellation-interaction).
The angle control's leftmost stop is its disabled value and presents the
complete localized `<setting name>: <off value>` label rather than a bare zero;
a non-zero value is presented as that setting's degree value. While compatibility
mode is enabled the control is disabled and shows the same disabled label.
Inventory Selection Stay Open is the
[inventory stay-open](../architecture/picking/wheel.md#inventory-selection-stay-open)
preference, and its tooltip states that a change applies to the next hold.

## Server performance category

The Performance category begins with a plain-text, non-interactive Monitoring
subgroup and a full-width Budget Delay Notifications server-value toggle. Its
tooltip identifies administrator budget-delay notices and the effect of
enabling or disabling them; value semantics and the persisted key are owned by
[server configuration](../config/server.md#persisted-fields).

The inventory administration controls follow in three
plain-text, non-interactive subgroups: Shared inventory budgets, Inventory
preview, and Inventory tracking. Each control edits one atomic leaf, and all
twenty-one numeric inventory settings use sliders. For integer settings,
piecewise step sizes apply only to UI adjustment, not file or server validation.
Each integer slider uses its field's validated bounds and preserves an in-range
integer between UI stops exactly as an additional stop. The five byte
multipliers and two size scalars retain their
exact grids and the size-pair constraint owned by
[server configuration](../config/server.md#inventory-policy-object).
Opening the page, rebuilding it, resizing, or navigating never snaps a value or
makes a clean draft dirty. The authoritative snapshot's extra integer stop
remains available throughout the editing session even after another value is selected,
so selecting it again can revert the leaf to clean.

Separate Tracking by Face and Separate Tracking by Player are native boolean
toggles immediately after Tracking Period, in that order, each in its own
full-width row. Their native hover and keyboard-focus help is localized in all
bundled locales and explains Off and On, current player permissions and
face-visible slots, and next-scan application while running scans finish under
the previous setting. Keys and defaults remain owned by
[server configuration](../config/server.md#inventory-policy-object), and certified
view reuse and transition semantics by
[inventory pools](../architecture/presentation/inventory.md#certified-tracking-observation-pools).

Each setting that supports unlimited mode has one explicit unlimited stop after
its maximum finite stop. Selecting that tail stop changes only the mode and
retains the finite value; selecting a finite stop exits unlimited mode and uses
that selected value. One step left from the unlimited stop selects the maximum
finite value. Mode and retained value remain one atomic leaf. The persisted
keys, normalization behavior, and unlimited semantics are owned
by [server configuration](../config/server.md#inventory-policy-object) and
[inventory preview and tracking](../architecture/presentation/inventory.md).

The two inventory byte-size controls are sliders shown as physical KiB sizes
while their persisted form remains the finite display and frame size scalars
owned by [server configuration](../config/server.md#inventory-policy-object).
The display slider's maximum follows the current frame value; lowering the
frame can lower the display value in the same atomic adjustment, which updates
both leaves and recomputes their dirty bits against the authoritative snapshot,
and shows a polite localized notice directly after the Frame Byte Size row; the
screen never raises the frame by itself to fit a display value. These two leaves
have no unlimited mode. The server's merged-pair validation, including its
rejection of a conflicting pair without mutating an unselected member, and the
no-acknowledgement transaction are owned by
[changing server configuration](../architecture/config/changing-server-config.md).

These controls belong to the shared ordinary server settings session and its
correlated snapshot, not to a separate inventory session; the draft, close, and
read-only rules in [Server settings session](#server-settings-session) apply to
them.

## Server presentation category

The client scope has no presentation-policy category. The server scope's
Server Presentation category contains only the Server Policy editor, with one
target-type selection over exactly the five fixed target types. The editor has
no property-entry control: property selections are not configured in the GUI.
The field list uses the connection's
[accepted field catalogue](../architecture/presentation/presentation_snapshot.md#field-manifest-and-value-model).
Until that catalogue is known, the page displays an unknown state rather than an
empty list. Entries are grouped by the field ID's namespace, not the owning
adapter's mod ID, and a repeated field ID produces one row.
The policy snapshot and status are independent of the ordinary
server-settings view, so that view does not gate entry to or viewability of this
policy page.

The category shows the selected target type's persisted white list, black list,
and whitelist-only rule. The connection-scoped mirror retains all five
target-type views, but the page renders one selected type at a time. The mirror
has no authoritative values before the first successful snapshot: until then the
status line presents the unsupported, unknown, loading, timed-out, or errored
state instead of a selector list, and a silent or timed-out request is never
reported as an unsupported route. Once a view is known, its field outcome labels
and retained selectors remain visible with their state while a request is
pending or the last response failed; only a known empty list is rendered as
empty. The displayed outcome is that field's effective outcome under the
selected type's rule, and it describes the configured rule outcome only; it does
not guarantee that target data, timing, or permission will produce a value.
When the viewer may edit, the field rows keep their paired allow and block
toggles, and the advanced lists offer Add and Remove and a whitelist-only
control for the selected target type; otherwise those controls are inert. Each
list keeps its own draft and feedback: text typed in one list survives page
rebuilds, navigation, and server events, and a successful server add clears only
that list's unchanged draft, while a failed, denied, or timed-out add keeps its
text for correction. Refresh asks the server for a fresh correlated rule-view
read and is available again for retry after a bounded no-response timeout.
Policy mutation actions send their requests when activated rather than waiting
for final root close; typing a selector draft alone sends no mutation. Their
help makes this distinction from the ordinary server-settings draft explicit.

The rule-view route, its disclosure, correlation, revision, and pending
lifecycle are owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md),
which also owns the accepted field-catalog metadata, the per-target-type
application, and the outcome evaluation these rows display. The mutation
permission threshold is owned by
[server configuration authority](../architecture/authority/server-config.md).
Selector grammar and persisted shape are owned by
[server configuration](../config/server.md#presentation-policy-object).

## Marker display duration option

The Marker Display Duration option displays the complete localized label
`<setting name>: <value>` for both explicit durations and the follow-server value.
Its value semantics are owned by
[client configuration](../config/client.md); this page owns its displayed label.

This label contract covers that option; it does not define a general label rule
for other client options or for the server-settings fields.

## Client configuration loading, edits, and close

The client configuration loads at client initialization. Opening or reopening
the screen edits the already-loaded in-memory configuration and does not reload
an externally edited file. Client modifications are live in memory while the
screen is open; client persistence happens on the final root leave, apart from
the file action and reset behaviors below.

On final root close, the screen first attempts local `saveSafely`; only if that
succeeds does it commit any dirty server-settings draft. It remains open if
either attempted step fails, and an invalid server-settings draft also blocks
the close and routes to the owning category and first invalid field, checking
Send Rate before Marker Duration before Performance so a draft with several
invalid leaves lands deterministically. Because
local persistence is attempted first, it can succeed before a later
server-draft commit fails. The snapshot-request, correlation, field-mask, and
no-acknowledgement transaction is owned by
[changing server configuration](../architecture/config/changing-server-config.md),
so the screen does not promise that the server applied or persisted an update.

## Server settings session

The ordinary server settings are one shared session within the screen, not a
per-category or per-visit section. This session covers the Channel & Players,
Send Rate, Marker Duration, and Performance categories, including the monitoring
toggle and the twenty-three inventory administration leaves. Entering the server
scope shows its overview and requests the current server configuration when the
client holds a live connection and the session holds no loaded or in-flight
snapshot. The loaded snapshot, the draft, and any in-flight request are retained
across scope-tab switches and category navigation; entering an ordinary server
category does not request another snapshot. The separate Server
Presentation category is not part of this session; its policy snapshot and
status follow the independent workflow described in
[Server presentation category](#server-presentation-category).

The ordinary server scope overview and its ordinary server leaf pages show
the session status: loading while a request is pending, a permission state when
a snapshot is viewable but not editable, and an unavailable state when no
authoritative snapshot exists. A snapshot the server marks non-editable is
retained as the read-only authoritative view rather than being discarded; for a
locally privileged requester it also records a denial, which is never presented
as an editable snapshot.

An accepted snapshot becomes the authoritative screen state, populates the
visible options, initializes the draft from the snapshot, and starts clean. A
read-only accepted snapshot renders those authoritative values without an
editable draft. `canView` holds while a safe authoritative snapshot is retained.
`canEdit` is a UI availability condition: local client permission, no recorded
denial, a loaded snapshot, and that snapshot's editable hint must all hold. It
is a UI hint rather than the server authorization boundary, which
[server configuration authority](../architecture/authority/server-config.md)
and [security](../architecture/security.md) own.

The draft begins when an editable authoritative snapshot is accepted. Controls
are inert unless `canEdit` holds. Each control recomputes dirty bits against the
authoritative snapshot. The original numeric fields must parse as non-negative
integers; inventory booleans use their two toggle states, while each numeric
inventory leaf validates under its own kind, bounds and,
for decimal settings, exact grid and size-pair constraints. An empty, malformed
or out-of-range draft, or a decimal draft outside its exact grid, remains dirty
but makes the update plan unavailable; an in-range integer between UI-only
adjustment stops is valid and retained exactly. A draft that returns every
edited value to its authoritative value has no dirty bits and produces no
update plan. Thus no-change and invalid-draft states do not dispatch a settings
update merely because a page is open. The field-masked merge and the
no-update-result consequence are owned by
[changing server configuration](../architecture/config/changing-server-config.md#no-update-result).

Category, scope-tab, and overview navigation neither dispatches an update nor
discards, commits, or resets the server draft; returning to an overview or
leaving the server scope retains the draft and the loaded snapshot. A late
snapshot continues to obey the request correlation owned by
[changing server configuration](../architecture/config/changing-server-config.md)
even when the player has navigated elsewhere or a confirmation dialog is open.

Disconnecting clears all connection-scoped ordinary-server permission, denial,
snapshot, pending-request, dirty, and draft state. Losing the local permission
state removes edit ability and drops the ordinary-server draft but retains a
safe authoritative view, and an open ordinary server leaf page keeps rendering
its retained values read-only instead of returning to the overview. A retained
editable snapshot reopens with the draft reset to its values when permission
returns; a retained read-only snapshot requires a fresh request after promotion
to editor. An observed false-to-true local permission transition is required
before a previously recorded server denial can be retried. The permission
requirement itself is owned by
[server configuration authority](../architecture/authority/server-config.md).

## File action and reset

The configuration-file action saves and closes the screen before opening the
client configuration file; it is not a GUI list editor. Its label and tooltip
may name the block-shape context it is offered from, and it always opens the
complete client file rather than a block-shape-only editor. It first runs the
persistence sequence used on final root close; if that sequence fails, it
returns without opening the file. On success it suppresses a stale close-save,
closes the screen, and asks the platform file opener to open the client
configuration file.

Reset is a client-only action on the client scope overview; it is not offered
inside a category and does not reset server settings. It confirms resetting
every client setting rather than only the visible category. A confirmed reset
suppresses a stale screen save, resets the in-memory configuration to its
defaults, and recreates the screen session; recreating the session discards any
server-settings draft. When a draft exists, the reset confirmation explicitly
warns that the unsaved server changes are discarded. The configuration handler
determines the reset result;
[configuration revisioning](../architecture/config/revisioning.md) owns handler
protection and persistence policy.

Pending manual external-edit and UI checks are in the
[verification matrix](../testing/verification/manual-integration.md#pending-manual-and-integration-matrix).

## Evidence boundary

The evidence for the server-settings snapshot and update seams is maintained in
[testing and verification](../testing/verification/configuration-ui.md#server-settings-snapshots-and-updates).
