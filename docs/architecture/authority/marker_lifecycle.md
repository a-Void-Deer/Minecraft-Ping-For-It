# Marker lifecycle

This topic owns the server-authoritative marker record lifetime. The client-side
synchronized record and display contract is owned by
[client marker state and display](../markers/client-state.md). Creation-time
audience construction and create/remove adjudication are owned by
[target validation](target_validation.md); recipient-scoped same-target
selection is owned by [ping winner](ping_winner.md).

## Server-authoritative record lifetime

The server store contains only active markers. A committed marker has a stable
ID, owner, target key, arrival tick, expiry tick, and a non-empty recipient
snapshot. An accepted create receives its authoritative arrival and expiry
ticks, and those values are immutable marker/snapshot data: the server keeps the
active record through its expiry boundary and sends the frozen lifetime to
recipients. A later configuration change does not reinterpret an existing
marker's ticks. The persisted `syncDuration` setting name and format are owned
by [server configuration](../../config/server.md).

| From | Trigger | To or result |
| --- | --- | --- |
| No server record | The server accepts a create after authoritative validation and audience snapshotting | One active record with a new monotonic ID. Its arrival and expiry ticks are the authoritative lifetime carried in later snapshots. |
| Active | An external-target refresh with the same committed identity succeeds | Still active. The locator or anchor may change, but ID, owner, target key, arrival, expiry, Ping Type, Target Type, and audience stay unchanged. It is not a new create or a new winner arrival; the preserved identity fields are owned by [target identity](../identity/target_model.md) and [Sable](../../integrations/sable.md). |
| Active | Owner cancellation | The record is physically removed with reason `CANCELLED`. |
| Active | Server tick reaches the expiry tick (`expiresAtTick <= currentTick`) | The record is physically removed with reason `EXPIRED`. The boundary is server time, not a client display deadline. |
| Active | Authoritative target invalidation | The record is physically removed with reason `TARGET_INVALID`. |
| Active | Owner disconnect | The owner's records are physically removed with reason `OWNER_DISCONNECTED`. |
| Active | A recipient disconnects | The server removes that recipient from the frozen audience. A record left with no recipients is dropped; this is audience cleanup, not channel/team re-evaluation. |
| Active | Server lifecycle reset | The store is cleared without recipient synchronization because no server audience remains. |

Creation-time audience construction from the sender's stored channel and current
server context is owned by
[target validation](target_validation.md#audience-snapshot-at-create). Later
recipient and owner cleanup is owned here. Every ordinary removal recomputes
affected authoritative winner slots as specified by
[ping winner](ping_winner.md).

Evidence for the server-store and lifecycle seams is consolidated in
[testing and verification](../../testing/verification.md).
