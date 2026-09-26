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

The client scope has seven categories:

- **Marker Display**: Ping Distance, Marker Display Duration, Ping Size, Item
  Icons, Direction Indicator, Player Info, and Team Color.
- **Target Selection**: Pass Through Transparent Blocks, Mark Blacklisted
  Targets, and Mark Fluids.
- **Wheel Appearance**: Wheel Inner Radius, Wheel Outer Radius, Wheel Opacity,
  Wheel Target Font Size, and Wheel Option Font Size.
- **Input Interaction**: Wheel Hold Time, Wheel Timeout, Long-Press
  Compatibility Mode, Compatibility Time Slice, and Cancel Cone Half-Angle.
- **Channel & Notices**: Ping Channel, Ping Volume, and Configuration Notice
  Size.
- **Rendering & Config**: Entity Block Geometry and the configuration-file
  action.
- **Presentation**: Client Receive, Client Display, and one shared read-only
  server-policy reference.

The server scope has four categories:

- **Channel & Players**: Default Channel Mode and Player Tracking.
- **Send Rate**: Regeneration Time and Rate Limit.
- **Marker Duration**: Sync Duration.
- **Server Presentation**: Server Policy.

The category structure supports later categories; it defines no configuration
field of its own. Each option's value semantics and persisted form remain owned
by the configuration catalogues, and this page owns only which options are
exposed under which category and whether a group edits local or server policy.

The screen exposes no controls for the hidden native raycast cap or the four
direction-indicator safe-area insets; their file semantics remain in the client
catalogue. There is no user-facing reload control.

## Presentation category

The client scope's Presentation category is reachable without server permission
and contains two local policy editors: Client Receive and Client Display. Each
panel lists the locally known fields grouped by the namespace of the field ID;
a bundled namespace name is localized, while an unknown namespace keeps its raw
ID instead of an invented name. Each row carries a localized field name and
description, and the raw field ID and its own default appear in the row tooltip
rather than requiring textual entry.

Each field row offers one On/Hidden control over that panel's local client
presentation policy. Turning it On adds exactly one exact allow selector for
that field ID; turning it Hidden removes only that field ID's exact allow
selector entries, including every duplicate, and leaves the block list, the
whitelist-only mode, wildcard and other broad selectors, and every other field
untouched. The displayed state is the field's effective outcome under the
panel's policy evaluated with the server-authorized default rather than its
exact allow-list membership, so a field still allowed by a remaining allow
selector or by default settings reads On. When hiding removes the exact allow
entry but the field nonetheless remains allowed by default settings, or by a
remaining group allow selector, the panel reports that outcome as a non-error
notice instead of claiming the field was hidden. The row tooltip carries the raw
field ID, its own default, the effective policy source — allowed by an allow
rule, allowed by default, allow-rule priority, blocked by a block rule, blocked
by whitelist-only, disabled by default, or waiting for server rules — and any
matching selectors. That label describes the configured rule outcome only; it
does not guarantee that target data, timing, or permission will produce a value.
The client panels evaluate against the server-authorized default, so a field
with no matching rule reads as accepting server-authorized fields or showing
received fields instead of inheriting that field's server-side disabled default.

Each panel collapses its raw selector lists and its whitelist-only toggle behind
an advanced section that starts collapsed and carries the current allow and
block counts. The advanced section keeps the existing white-list and black-list
editors with their Remove action and validated Add entry, so unknown custom
patterns remain editable and visible. Each list keeps its own draft and its own
feedback: text typed in one list survives page rebuilds, navigation, and server
events, and a successful server add clears only that list's unchanged draft,
while a failed, denied, or timed-out add keeps its text for correction. The
field control never owns a draft slot; its success, failure, or fallback notice
is reported in a per-panel feedback row that stays visible while the advanced
section is collapsed. Below both client editors, one shared server rule view is
shown as a read-only reference.

The server scope has a separate Server Presentation category containing only the
Server Policy editor. Its field list is the fields advertised by the connection's
accepted offer after the local ID/kind compatibility check, so it shows only
compatible fields and is unknown — not an authoritative empty list — until a
valid offer is accepted. The offline local preview that the client panels can
show is the locally registered manifest rather than server truth. The policy
snapshot and status are independent of the ordinary server-settings view, so
that view does not gate entry to or viewability of this policy page. The shared
server rule view has no authoritative values before the first successful
snapshot: until then the status line presents the unsupported, unknown, loading,
timed-out, or errored state instead of a selector list, and a silent or
timed-out request is never reported as an unsupported route. Once a view is
known, its field outcome labels and retained selectors remain visible with their
state while a request is pending or the last response failed; only a known empty
list is rendered as empty. When the viewer may edit, the server field rows keep
their paired allow and block toggles, and the advanced lists offer Add and
Remove and a whitelist-only control; otherwise those controls are inert.
Refresh asks the server for a fresh correlated rule view and is available again
for retry after a bounded no-response timeout.

The rule-view route, its disclosure, correlation, revision, and pending
lifecycle are owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md),
which also owns the accepted field-catalog metadata and the outcome evaluation
these rows display. The mutation permission threshold is owned by
[server configuration authority](../architecture/authority/server-config.md).
Selector grammar and the shared list shape are owned by the configuration
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
the close and routes to the category and field that needs correction. Because
local persistence is attempted first, it can succeed before a later
server-draft commit fails. The snapshot-request, correlation, field-mask, and
no-acknowledgement transaction is owned by
[changing server configuration](../architecture/config/changing-server-config.md),
so the screen does not promise that the server applied or persisted an update.

## Server settings session

The ordinary five-field server settings are one shared session within the
screen, not a per-category or per-visit section. This session covers the
Channel & Players, Send Rate, and Marker Duration categories. Entering the
server scope shows its overview and requests the current server configuration
when the client holds a live connection and the session holds no loaded or
in-flight snapshot. The loaded snapshot, the draft, and any in-flight request
are retained across scope-tab switches and category navigation; entering an
ordinary server category does not request another snapshot. The separate Server
Presentation category is not part of this session; its policy snapshot and
status follow the independent workflow described in [Presentation category](#presentation-category).

The ordinary server scope overview and its three ordinary server leaf pages show
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
authoritative snapshot. Numeric draft text must parse as a non-negative
integer; an empty, non-numeric, or negative numeric draft remains dirty but
makes the update plan unavailable. A draft that returns every edited value to
its authoritative value has no dirty bits and produces no update plan. Thus
no-change and invalid-draft states do not dispatch a settings update merely
because a page is open. The field-masked merge and the no-update-result
consequence are owned by
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
