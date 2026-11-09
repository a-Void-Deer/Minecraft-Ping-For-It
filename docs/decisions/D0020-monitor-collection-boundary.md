# D0020: Monitor collection boundary

## Status

Confirmed product decision represented by the linked topic contracts. Evidence
status remains owned by [build checks](../testing/verification/build-checks.md).

## Decision

Server monitor collection belongs to the server's authoritative configuration,
not to client viewing. Enabling a lane collects its bounded values even with no
viewer or subscriber, so collection is independent of viewing demand;
subscription and client metric selection govern transfer and display only. The
lanes are limited to `DEBUG` diagnostic use, and the client HUD and metric
selections are client-local state that never controls server collection.
Persisted defaults and profile-derived state transitions are owned by
[server configuration](../config/server.md) and
[client configuration](../config/client.md); numeric telemetry authority is
owned by [server configuration authority](../architecture/authority/server-config.md#remote-performance-telemetry).

## Rationale

Coupling collection to viewers would make measurements depend on who is
watching and would miss idle behavior. Keeping the HUD and metric selections
client-local preserves that boundary: selection expresses display demand, not
server authority. The existing `DEBUG` gate limits collection to diagnostic use.

## Consequences

Collection can therefore continue with no viewer, and subscription, HUD or
metric selection cannot change what the server collects. The monitor
subscription, status and sample route family is owned by
[network protocol](../architecture/network/protocol.md); this record does not
define its identifiers or grammar.
