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
- related options may be introduced by plain-text, non-interactive subgroup
  headings rather than interactive boxes or collapsing sections;
- options use native option widgets; and
- the layout is two-column when the options fit and full-width only where a
  control needs the whole width.

The footer is fixed. Back and Escape return to the current scope overview;
Done and Escape at the root close the screen. Keyboard traversal uses the
platform's native widget navigation in visual order. Scrollable content stays
above the footer rather than covering it, and each page retains its scroll
position and focus context, so returning to an overview and re-entering a
category resumes where the player left. A window resize or GUI-scale change
does not discard the session or a draft.

## Category layout

The client scope has six categories:

- **Marker Display**: Ping Distance, Marker Display Duration, Ping Size, Item
  Icons, Direction Indicator, Player Info, and Team Color.
- **Target Selection**: Pass Through Transparent Blocks, Mark Blacklisted
  Targets, and Mark Fluids.
- **Wheel Appearance**: Root Menu Distance, Wheel Opacity, Wheel Target Font
  Size, Wheel Option Font Size, Show Gesture Trail, and Reduce Selector Motion.
- **Input Interaction**: Wheel Hold Time, Wheel Timeout, Long-Press
  Compatibility Mode, Compatibility Time Slice, and Cancel Cone Half-Angle,
  followed by a Spatial selector gestures subgroup with Center Deadzone, Entry
  Stroke Length, Entry Dwell, Inventory Vertical Mouse Sensitivity, Require
  Sustained Hover to Return, and Back-Hover Dwell.
- **Channel & Notices**: Ping Channel, Ping Volume, and Configuration Notice
  Size.
- **Rendering & Config**: Entity Block Geometry and the configuration-file
  action.

The server scope has five categories:

- **Channel & Players**: Default Channel Mode and Player Tracking.
- **Send Rate**: Regeneration Time and Rate Limit.
- **Marker Duration**: Sync Duration.
- **Performance**: a Shared inventory budgets subgroup with Physical Slots per
  Tick and Pending Memory; an Inventory preview subgroup with Preview Period,
  Preview Variants per Client Period, Preview Slots per Client, Preview Slots
  Server-wide, Preview Targets per Client, Preview Client Byte Multiplier, and
  Preview Global Byte Multiplier; and an Inventory tracking subgroup with
  Tracking Period, Tracking Variants per Target, Tracking Slots per Target,
  Tracking Slots Server-wide, Tracking Stream Byte Multiplier, Tracking
  Snapshot Byte Multiplier, Tracking Global Byte Multiplier, Resync Cooldown,
  Heartbeat Cadence, and Byte Smoothing Window.
- **Server Presentation**: Server Policy.

The category structure supports later categories; it defines no configuration
field of its own. Each option's value semantics and persisted form remain owned
by the configuration catalogues, and this page owns only which options are
exposed under which category and whether a group edits local or server policy.

The screen exposes no controls for the hidden native raycast cap or the four
direction-indicator safe-area insets; their file semantics remain in the client
catalogue. The retired wheel radius keys are also absent from the screen and
have no persisted meaning
([client configuration](../config/client.md#obsolete-keys)). There is no
user-facing reload control.

## Server performance category

The Performance category exposes the inventory administration controls in three
plain-text, non-interactive subgroups: Shared inventory budgets, Inventory
preview, and Inventory tracking. Each control is one atomic leaf: a numeric
field holds the finite value, and a cap or multiplier also has a
finite/unlimited mode control. The mode is independent of the finite text, so
switching a row to unlimited never discards the finite value, including text
that is currently invalid. Multiplier fields and the pending-memory field offer
native step buttons that use the confirmed grids and steps without
floating-point rounding. The persisted keys, bounds, grid normalization, and
unlimited semantics are owned by
[server configuration](../config/server.md#inventory-policy-object) and
[inventory preview and tracking](../architecture/presentation/inventory.md).

These controls belong to the shared ordinary server settings session and its
correlated snapshot, not to a separate inventory session; the draft, close, and
read-only rules in [Server settings session](#server-settings-session) apply to
them.

## Server presentation category

The client scope has no presentation-policy category. The server scope's
Server Presentation category contains only the Server Policy editor, with one
target-type selection over exactly the five fixed target types. The editor has
no property-entry control: property selections are not configured in the GUI.
Its field list is the fields advertised by the connection's accepted offer after
the local ID/kind compatibility check, so it shows only compatible fields and is
unknown — not an authoritative empty list — until a valid offer is accepted. The
offline local preview is the locally registered manifest rather than server
truth. The policy snapshot and status are independent of the ordinary
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

The rule-view route, its disclosure, correlation, revision, and pending
lifecycle are owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md),
which also owns the accepted field-catalog metadata, the per-target-type
application, and the outcome evaluation these rows display. The mutation
permission threshold is owned by
[server configuration authority](../architecture/authority/server-config.md).
Selector grammar and the persisted shape are owned by the configuration
catalogues.

## Marker display duration option

All options displays the complete label
`<setting name>: <value>`, with both the setting name and the current value
localized, both when the value is an explicit duration and when it uses a string. The value semantics are owned by
[client configuration](../config/client.md); this page owns only the displayed
label.

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
Send Rate, Marker Duration, and Performance categories, including the nineteen
inventory administration leaves. Entering the server scope shows its overview
and requests the current server configuration when the client holds a live
connection and the session holds no loaded or in-flight snapshot. The loaded
snapshot, the draft, and any in-flight request are retained across scope-tab
switches and category navigation; entering an ordinary server category does
not request another snapshot. The separate Server
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
integers; each inventory leaf parses under its own kind, range, and grid. An
empty, malformed, out-of-range, or off-grid draft remains dirty but makes the
update plan unavailable. An inventory cap or multiplier is one leaf: its
unlimited mode is independent of its finite text, and toggling the mode never
discards the finite text, including text that is currently invalid. A draft
that returns every edited value to its authoritative value has no dirty bits
and produces no update plan. Thus no-change and invalid-draft states do not
dispatch a settings update merely because a page is open. The field-masked
merge and the no-update-result consequence are owned by
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
[verification matrix](../testing/verification.md#pending-manual-and-integration-matrix).

## Evidence boundary

The evidence for the server-settings snapshot and update seams is maintained in
[testing and verification](../testing/verification.md#server-settings-snapshots-and-updates).
