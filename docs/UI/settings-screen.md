# Configuration UI

This topic owns the client settings screen, its client/server scope navigation
and category layout, and its server-settings UI workflow. It does not own the
client file catalogue ([client configuration](../config/client.md)), the
persisted server file catalogue
([server configuration](../config/server.md)), the remote change transaction
([changing server configuration](../architecture/config/changing-server-config.md)),
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
- **Wheel Appearance**: Wheel Inner Radius, Wheel Outer Radius, Wheel Opacity,
  Wheel Target Font Size, and Wheel Option Font Size.
- **Input Interaction**: Wheel Hold Time, Wheel Timeout, Long-Press
  Compatibility Mode, Compatibility Time Slice, and Cancel Cone Half-Angle.
- **Channel & Notices**: Ping Channel, Ping Volume, and Configuration Notice
  Size.
- **Rendering & Config**: Entity Block Geometry and the configuration-file
  action.

The server scope has three categories:

- **Channel & Players**: Default Channel Mode and Player Tracking.
- **Send Rate**: Regeneration Time and Rate Limit.
- **Marker Duration**: Sync Duration.

The category structure supports later server categories; it defines no server
field of its own. Each option's value semantics and persisted form remain owned
by the configuration catalogues, and this page owns only which options are
exposed under which category.

The screen exposes no controls for the hidden native raycast cap or the four
direction-indicator safe-area insets; their file semantics remain in the client
catalogue. There is no user-facing reload control.

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

The server settings are one shared session within the screen, not a
per-category or per-visit section. Entering the server scope shows its overview
and requests the current server configuration when the client is eligible and
the session holds no loaded or in-flight snapshot. The loaded snapshot, the
draft, and any in-flight request are retained across scope-tab switches and
category navigation; entering a server category does not request another
snapshot.

The scope overview and the server leaf pages show the session status: loading
while a request is pending, a permission state when the client cannot edit, and
an unavailable state when no authoritative snapshot exists. A denied response
clears the authoritative server state and the draft; it is not presented as a
read-only snapshot.

An editable accepted snapshot becomes the authoritative screen state,
populates the visible options, initializes the draft from the snapshot, and
starts clean. `canEdit` is a UI availability condition: local client
permission, no recorded denial, a loaded snapshot, and that snapshot's editable
hint must all hold. It is a UI hint rather than the server authorization
boundary, which
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

Disconnecting clears all connection-scoped permission, denial, snapshot,
pending-request, dirty, and draft state. Losing the local permission state also
clears the same connection-scoped state. If a server leaf page is open when
that state clears, the screen returns to the server overview and explains why
the page is no longer editable. An observed false-to-true local permission
transition is required before a previously recorded server denial can be
retried. The permission requirement itself is owned by
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
