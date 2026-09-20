# Changing server configuration

This focused architecture contract owns the remote client/server transaction
that changes server configuration. The persisted field catalogue is
[server configuration](../../config/server.md); who may edit is
[server configuration authority](../authority/server-config.md); trusted
server-side identity and enforcement are [security](../security.md); the client
screen that plans and edits drafts is
[configuration UI](../../UI/config.md). Persistence and recovery are owned by
[configuration revisioning](revisioning.md). This contract does not duplicate
the UI widget workflow, the rate algorithm ([rate policy](rate-limit.md)), or
the marker lifetime that consumes `syncDuration`
([marker lifecycle](../authority/marker_lifecycle.md)).

## Update surface

The remote update surface contains exactly these five fields:

- `defaultChannelMode`;
- `playerTrackingEnabled`;
- `msToRegenerate`;
- `rateLimit`; and
- `syncDuration`.

`pingDistance` is explicitly outside this transaction. It is a JSON-only
server setting catalogued by [server configuration](../../config/server.md) and
consumed by the [range](../picking/range.md) acceptance contract.

The transaction carries a field selection plus the current values. The
selection identifies which of the five fields the sender changed; it is not a
partial snapshot. A missing, zero, unknown, or malformed selection, or an
otherwise invalid update, performs no mutation. Which fields become dirty and
when a plan is produced are UI planning details owned by
[configuration UI](../../UI/config.md).

## Snapshot request and correlation

A snapshot request carries a positive request identifier and asks for the
server's current authoritative values; it is not an edit. The response is bound
to that identifier. A response is accepted only while the initiating request is
still pending on the same connection, the snapshot is non-null and safe, and
the response's positive identifier exactly matches that pending identifier. A
response that arrives after closing, disconnecting, permission revocation, or a
later opening is stale and is rejected in full.

The response's `canEdit` value is a UI hint on the returned snapshot, and the
request identifier correlates that response with its request. What grants edit
authority is owned by the
[editing authority](../authority/server-config.md); trusted server-side
enforcement is owned by
[server enforcement](../security.md#server-configuration-update-enforcement).

An accepted snapshot initializes the client's authoritative values and its
draft. Authoritative values, draft, and pending correlation are
connection-scoped: a disconnect clears all three, and permission loss
invalidates editability. Collapse, discard, and screen cleanup behavior are
owned by [configuration UI](../../UI/config.md).

## Merge semantics

The server merges a valid update into the current authoritative snapshot. Fields
selected by the update replace the authoritative value; every unselected field
is preserved from that snapshot. A rate-limit-only update therefore does not
replace the channel mode, player-tracking flag, regeneration interval, or
synchronization duration, and a synchronization-duration-only update likewise
leaves the other selected-surface fields intact. A malformed, missing, unknown,
or zero field selection has no effect. If no current authoritative snapshot
exists when a valid update arrives, the merge produces no result and no
mutation.

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

User-visible admission ordering for marker creation is separate and owned by
[target validation](../authority/target_validation.md). Rate-field semantics are
owned by [rate policy](rate-limit.md), and the lifetime meaning of the
synchronization-duration field is owned by
[marker lifecycle](../authority/marker_lifecycle.md).

## No update result

There is no update-result or acknowledgement packet. Before dispatching a valid
plan, the client marks its draft clean and sends the update through a void send;
it does not wait for a success response, reload a snapshot, or retry a send
that could not be delivered. A clean client state therefore means only that the
client stopped tracking the local draft as dirty; it is not evidence that the
server accepted, applied, or persisted the update.

Applying an update can also trigger the handler's update notification
(reinitializing server-side state and re-broadcasting rate and
synchronization-duration policy) even when the subsequent persistence attempt
fails. A persistence failure is not reported back to the client. Persisted-state
recovery, migration, and future-version protection are owned by
[configuration revisioning](revisioning.md).
