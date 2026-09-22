# Configuration UI

This topic owns the client settings screen and its server-settings panel UI
workflow. It does not own the client file catalogue
([client configuration](../config/client.md)), the persisted server file
catalogue ([server configuration](../config/server.md)), the remote change
transaction
([changing server configuration](../architecture/config/changing-server-config.md)),
the permission requirement for editing
([server configuration authority](../architecture/authority/server-config.md)),
server-side enforcement ([security](../architecture/security.md)), or handler
persistence policy
([configuration revisioning](../architecture/config/revisioning.md)).

## Client configuration loading and close

The client configuration loads at client initialization. Opening or reopening
this screen edits the already-loaded in-memory configuration and does not
reload an externally edited file.

On close, the screen first attempts local `saveSafely`; only if that succeeds
does it commit any server-settings draft. It remains open if either attempted
step fails. Because local persistence is attempted first, it can succeed before
a later server-draft commit fails. The snapshot-request, correlation, and
field-mask transaction is owned by
[changing server configuration](../architecture/config/changing-server-config.md).

## Option display

All options displays the complete label
`<setting name>: <value>`. Both the setting name and the current value are
localized, both when the option holds an explicit number and when it uses a string. The value semantics are owned
by [client configuration](../config/client.md); this page owns only the displayed
label.

This label contract covers that option; it does not define a general label rule
for other client options or for the server-settings fields.

## File action and reset

The configuration-file action is not a GUI list editor. It first runs the
persistence sequence used on close; if that sequence fails, it returns without
opening the file. On success it suppresses a stale close-save, closes the
screen, and asks the platform file opener to open the client configuration
file.

A confirmed reset suppresses stale screen save, invokes `resetToDefaults`, and
recreates the screen. The configuration handler determines the reset result;
[configuration revisioning](../architecture/config/revisioning.md) owns handler
protection and persistence policy.

There is no user-facing reload control in the current screen. The normal screen
has no controls for hidden `raycastDistance` or the four `safeZoneLeft`,
`safeZoneRight`, `safeZoneTop`, and `safeZoneBottom` fields; their file
semantics remain in the client catalogue. Current widgets and implementation
metadata are in
[`SettingsScreen`](../../common/src/main/java/nx/pingwheel/common/screen/SettingsScreen.java),
not a duplicate bounds catalogue. Pending manual external-edit and UI checks are
in the
[verification matrix](../testing/verification.md#pending-manual-and-integration-matrix).

## Server-settings panel

The collapsed section starts an expansion request only when the local client
permission state allows it, the model has not recorded an authoritative access
denial, and the section is not already expanded. Starting it marks the section
expanded and shows its loading state, clears dirty state, and allocates a
positive pending request identifier. The request asks for a snapshot; it is not
an edit operation. The server returns its current snapshot with a `canEdit`
hint derived from that requester's trusted server-side permission result.
Snapshot correlation, stale-response rejection, and connection-scoped model
invalidation are defined by
[changing server configuration](../architecture/config/changing-server-config.md).

An editable accepted snapshot becomes authoritative screen state, ends loading,
populates the visible fields, initializes the draft from the snapshot, and
starts clean. An accepted response whose `canEdit` value is false instead
records access denial, closes and clears the section, and clears both
authoritative state and draft. `canEdit` is a UI availability condition: local
client permission, no recorded denial, a loaded snapshot, and that snapshot's
editable hint must all hold. It is a UI hint rather than the server
authorization boundary, which
[server configuration authority](../architecture/authority/server-config.md)
and [security](../architecture/security.md) own.

The draft begins when an editable authoritative snapshot is accepted. Controls
are inert unless `canEdit` holds. A model collapse cancels loading, removes the
pending identifier, clears dirty state, and restores the draft from the last
accepted authoritative snapshot when one exists. In this screen, a loaded dirty
section first asks for discard confirmation; the confirmed collapse performs
that discard. Disconnect clears all connection-scoped permission, denial,
snapshot, pending-request, dirty, and draft state. Local permission revocation
also closes the section and discards the draft; an observed false-to-true local
permission transition is required before a previously recorded server denial
can be retried.

Each control recomputes dirty bits against the authoritative snapshot. Numeric
draft text must parse as a non-negative integer; an empty, non-numeric, or
negative numeric draft remains dirty but makes the update plan unavailable. A
draft that returns every edited value to its authoritative value has no dirty
bits and produces no update plan. Thus no-change and invalid-draft states do not
dispatch a settings update merely because the section is open. The field-masked
merge and the no-update-result consequence are owned by
[changing server configuration](../architecture/config/changing-server-config.md#no-update-result).

## Evidence boundary

The evidence for the server-settings snapshot and update seams is maintained in
[testing and verification](../testing/verification.md#server-settings-snapshots-and-updates).
