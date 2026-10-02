# Changing server configuration

This focused architecture contract owns the remote client/server transaction
that changes server configuration. The persisted field catalogue is
[server configuration](../../config/server.md); who may edit is
[server configuration authority](../authority/server-config.md); trusted
server-side identity and enforcement are [security](../security.md); the client
screen that plans and edits drafts is
[configuration UI](../../UI/settings-screen.md). Persistence and recovery are owned by
[configuration revisioning](revisioning.md). This contract does not duplicate
the UI widget workflow, the rate algorithm ([rate policy](rate-limit.md)), or
the marker lifetime that consumes `syncDuration`
([marker lifecycle](../authority/marker_lifecycle.md)).

## Update surface

The remote update surface contains the five top-level fields:

- `defaultChannelMode`;
- `playerTrackingEnabled`;
- `msToRegenerate`;
- `rateLimit`; and
- `syncDuration`;

plus the nineteen `inventory` administration settings catalogued by
[server configuration](../../config/server.md#inventory-policy-object). Each
inventory cap or multiplier is one atomic leaf: its unlimited flag and its
retained finite value are selected and replaced together, and the value is
handled exactly on its confirmed grid rather than through binary floating-point
arithmetic.

`pingDistance` is explicitly outside this transaction. It is a JSON-only
server setting catalogued by [server configuration](../../config/server.md) and
consumed by the [range](../picking/range.md) acceptance contract. The
presentation sampling members are likewise file-only and are not carried here;
the per-target-type field policy has its own route described below.

The transaction carries a field selection plus the current values. The
selection is a bit mask with one stable bit per surface leaf: the five original
bits are unchanged and every inventory administration setting has its own bit.
The mask identifies which leaves the sender changed; it is not a partial
snapshot. A missing, zero, unknown, or malformed selection, or an otherwise
invalid update, performs no mutation. Which fields become dirty and when a plan
is produced are UI planning details owned by
[configuration UI](../../UI/settings-screen.md).

## Separate policy selector route

The per-target-type presentation field policy is read and changed through its own
versioned route, not through this server-configuration transaction. The selector
route never travels on the version-2 server-configuration request, snapshot, or
update routes and does not extend or replace the update surface; exact route
identifiers and wire grammar are owned by
[network protocol](../network/protocol.md). Its rule-view disclosure,
correlation, revision, and mutation semantics are owned by
[presentation snapshot](../presentation/presentation_snapshot.md); who may read
or mutate is owned by
[server configuration authority](../authority/server-config.md).

## Snapshot request and correlation

A snapshot request carries a positive request identifier and asks for the
server's current authoritative values; it is not an edit, and any connected
player may send one. The snapshot covers the complete remote surface — the five
top-level fields and every inventory administration leaf, including each cap's
or multiplier's retained finite value — and is accepted or rejected as one
whole. The response is bound to that identifier. A response is accepted only
while the initiating request is still pending on the same connection, the
snapshot is non-null and safe, and the response's positive identifier exactly
matches that pending identifier. A response that arrives after closing,
disconnecting, permission revocation, or a later opening is stale and is
rejected in full.

The response's `canEdit` value is a UI hint on the returned snapshot, and the
request identifier correlates that response with its request. A response whose
hint is false is still an authoritative snapshot: it may be displayed
read-only, but it never enables editing. What grants edit authority is owned by
the
[editing authority](../authority/server-config.md); trusted server-side
enforcement is owned by
[server enforcement](../security.md#server-configuration-update-enforcement).

An accepted response supplies the authoritative values used by the client's
server-settings state. Draft initialization and the connection, screen, and
permission lifecycle are owned by
[configuration UI](../../UI/settings-screen.md); the correlation and
stale-response rules above continue to govern when a response is accepted.

## Merge semantics

The server merges a valid update into the current authoritative snapshot. Fields
selected by the update replace the authoritative value; every unselected field
is preserved from that snapshot, including every unselected inventory leaf. A
rate-limit-only update therefore does not replace the channel mode,
player-tracking flag, regeneration interval, synchronization duration, or any
inventory administration value, and a synchronization-duration-only update
likewise leaves the other selected-surface fields intact; an inventory-only
update leaves all five top-level fields at their current authoritative values.
A malformed, missing, unknown, or zero field selection has no effect. If no
current authoritative snapshot exists when a valid update arrives, the merge
produces no result and no mutation.

## Server apply order and persistence

The packet-facing server path processes an update in this order:

1. a corrupt or structurally invalid packet is rejected without reaching any
   later stage;
2. the trusted server-side identity and permission check runs before any
   configuration state is touched ([security](../security.md));
3. the update is validated and merged; a rejected update leaves the
   authoritative values unchanged; and
4. for an applied update, the selected fields are assigned, the configuration
   is validated, and a persistence write is attempted.

The version-2 server-configuration payloads are fixed-shape and consumed to
their exact end: a payload with trailing bytes, or a superseded shorter shape
that does not supply every inventory leaf, is structurally invalid and is
rejected in full rather than reinterpreted as a valid prefix. Exact route
identifiers and wire grammar are owned by
[network protocol](../network/protocol.md).

User-visible admission ordering for marker creation is separate and owned by
[target validation](../authority/target_validation.md). Rate-field semantics are
owned by [rate policy](rate-limit.md), and the lifetime meaning of the
synchronization-duration field is owned by
[marker lifecycle](../authority/marker_lifecycle.md).

## No update result

There is no update-result or acknowledgement packet. Before dispatching a valid
plan, the client marks its draft clean and sends the update through a void send;
it does not wait for a success response, reload a snapshot, or retry a send
that could not be delivered. An update that selects only inventory leaves
follows the same path, with no acknowledgement, retry, confirmation, or
persistence rollback. The separate policy selector route has its own
correlated response, owned by
[presentation snapshot](../presentation/presentation_snapshot.md). A clean
client state therefore means only that the client stopped tracking the local
draft as dirty; it is not evidence that the server accepted, applied, or
persisted the update.

Applying an update can also trigger the handler's update notification
(reinitializing server-side state and re-broadcasting rate and
synchronization-duration policy) even when the subsequent persistence attempt
fails. A persistence failure is not reported back to the client, and the
already-applied assignment is not rolled back. Persisted-state recovery,
migration, and future-version protection are owned by
[configuration revisioning](revisioning.md).
