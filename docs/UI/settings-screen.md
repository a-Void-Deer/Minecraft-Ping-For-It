# Settings screen

This topic owns client settings-screen workflow. Client configuration loads at
client initialization; opening or reopening the screen edits already-loaded
in-memory configuration and does not reload an externally edited file. The
complete file/key catalogue is [client configuration](../config/client.md).

## Closing and local/server drafts

On close, the screen first attempts local `saveSafely`; only if that succeeds,
does it commit any server-settings draft. It remains open if either attempted
step fails. Since local persistence is attempted first, it can succeed before a
later server-draft commit fails. The server-draft snapshot, authorization, and
field-mask workflow belongs to [server settings](../config/server_settings.md).

## File action and reset

The configuration-file action is not a GUI list editor. It first runs the
[persistence sequence used on close](#closing-and-localserver-drafts); if that
sequence fails, it returns without opening the file. On success it suppresses
stale close-save, closes the screen, and asks the platform file opener to open
the client configuration file.

A confirmed reset suppresses stale screen save, invokes `resetToDefaults`, and
recreates the screen. The configuration handler determines the reset result;
[configuration revisioning](../architecture/config/revisioning.md) owns handler
protection and persistence policy.

There is no user-facing reload control in the current screen. The normal screen
has no controls for hidden `raycastDistance` or the four `safeZoneLeft`,
`safeZoneRight`, `safeZoneTop`, and `safeZoneBottom` fields; their file semantics
remain in the client catalogue. Current widgets and implementation metadata are in
[`SettingsScreen`](../../common/src/main/java/nx/pingwheel/common/screen/SettingsScreen.java),
not a duplicate bounds catalogue. Pending manual external-edit and UI checks are
in the [verification matrix](../testing/verification.md#pending-manual-and-integration-matrix).
