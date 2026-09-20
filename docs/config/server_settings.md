# Server settings panel and partial updates

This topic owns the connection-scoped server-settings section of the client
settings screen: snapshot-request state, draft lifetime, dirty-field planning,
and the bounded server-configuration update surface. It does not make a
client-side display state, `canEdit`, or request identifier an authorization
credential. Server-setting authorization is owned by
[security](../security.md#server-setting-permission).

## Scope and related contracts

The editable update surface contains exactly these server settings:

- `defaultChannelMode`;
- `playerTrackingEnabled`;
- `msToRegenerate`;
- `rateLimit`; and
- `syncDuration`.

`pingDistance` is not in this screen's update mask or merge surface; its
separate range contract is [range](../picking/range.md). The timing and
client-courtesy meanings of `msToRegenerate` and `rateLimit` remain owned by
[rate policy](rate_limit.md), and the frozen marker-lifetime meaning of
`syncDuration` remains owned by
[marker lifecycle](../architecture/authority/marker_lifecycle.md). Persisted server-config
versioning, migration, and recovery are owned by
[configuration revisioning](../architecture/config/revisioning.md).

## Opening, snapshots, and stale responses

The collapsed section starts an expansion request only when the local client
permission state allows it, the model has not recorded an authoritative access
denial, and the section is not already expanded. Starting it marks the section
expanded and loading, clears dirty state, and allocates a positive pending
request identifier. A valid request asks for a snapshot; it is not an edit
operation. The server returns its current snapshot with a `canEdit` hint derived
from that requester's server-side permission check.

A snapshot is applied only while that exact expansion is still loading. The
model requires a non-null safe snapshot, a positive identifier exactly matching
the current pending identifier, local client permission, and an expanded,
loading section. It clears the pending identifier only after that correlation
check. Consequently a response that arrives after closing, disconnecting,
permission revocation, or a subsequent opening is stale and cannot reopen the
section. The identifier is a response-correlation value, not a permission
token.

An editable accepted snapshot becomes authoritative screen state, ends loading,
initializes the draft from the snapshot, and starts clean. A correlated snapshot
whose `canEdit` value is false instead records access denial, closes the
section, and clears both authoritative state and draft. `canEdit` is therefore
a UI availability condition: local client permission, no recorded denial, a
loaded snapshot, and that snapshot's editable hint must all hold. It is not the
server authorization rule.

## Draft lifetime and edit planning

The draft begins when an editable authoritative snapshot is accepted. Controls
are inert unless `canEdit` holds. A model collapse cancels loading, removes the
pending identifier, clears dirty state, and restores the draft from the last
accepted authoritative snapshot when one exists. In the settings screen, a
loaded dirty section first asks for discard confirmation; the confirmed collapse
performs that discard. Disconnect clears all connection-scoped permission,
denial, snapshot, pending-request, dirty, and draft state. Local permission
revocation also closes the section and discards the draft; an observed
false-to-true local permission transition is required before a previously
recorded server denial can be retried.

Each control recomputes dirty bits against the authoritative snapshot. Numeric
draft text must parse as a non-negative integer; an empty, non-numeric, or
negative numeric draft remains dirty but makes the update plan unavailable. A
draft that returns every edited value to its authoritative value has no dirty
bits and produces no update plan. Thus no-change and invalid-draft states do
not dispatch a settings update merely because the section is open.

## Field-masked merge and failure result

An update plan carries the current values together with dirty bits. The server
merge applies only the bits for the five fields listed above, preserving every
unselected field from the authoritative server snapshot. In particular, a
rate-limit-only update does not replace the channel mode, player-tracking flag,
regeneration interval, or synchronization duration; a synchronization-duration-
only update likewise leaves the other update-surface fields intact.

The server-side merge seam first derives its editable hint from the authenticated
permission result. A missing current snapshot produces no snapshot result. A
denied permission, missing update, or invalid update produces an unapplied
result with the current settings unchanged and a snapshot whose editable hint
reflects the permission result. The packet-facing server path independently
checks the required permission for every update as specified by
[security](../security.md#server-setting-permission); corrupt packets,
authorization denial, and rejected invalid updates return without mutating
global settings.

There is no update-result packet. Before dispatching a valid plan, the screen
marks its draft clean and sends the field-masked update asynchronously; it does
not wait for a success response or reload a snapshot. On the accepted server
path, selected fields are assigned, the config is validated, and persistence is
attempted. The absence of an acknowledgement means this screen state is not a
claim that the server accepted or persisted a later packet.

## Evidence boundary

`ServerSettingsModelTest` covers request correlation, stale close/reopen and
disconnect responses, denial state, draft invalidity, dirty-bit restoration,
and update-plan construction. `ServerConfigUpdateServiceTest` covers denied
merge non-mutation and selected-field preservation for rate-limit and
synchronization-duration updates. This names focused unit seams, not a claim
that a client/server session or this documentation change ran them. Broader
coverage inventory and pending integration scenarios are maintained in the
repository verification document.
