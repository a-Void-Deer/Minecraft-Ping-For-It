# Authority, failure isolation and diagnostics

## Trust boundaries

For authoritative `MarkerCreate`, captured client intent is a request. The
server derives/verifies target classification, existence, dimension,
block/entity state, range, allowed Ping Type, names, colors, ownership,
channel, audience and lifecycle data from authoritative state where possible.
That packet has no client-provided channel or audience field; the server uses
its stored channel state when it applies the marker-creation policy.

This channel/audience guarantee is specific to `MarkerCreate`. It must not be
extended to the registered legacy `PingLocationC2SPacket`, which retains an
in-packet channel and legacy forwarding path. See
[network protocol](authority/network_protocol.md) for the deliberately narrow
legacy-versus-authoritative boundary.

Clients cannot authorize marker removal or select a server winner by sending
presentation values. Detailed packet and invalidation timing lives in
[target validation](authority/target_validation.md); deterministic winner
selection lives in [ping winner](authority/ping_winner.md).

Entity-local geometry is client capture metadata. Whole-entity identity and
anchor-based validation survive precise surface hits; the server does not
claim to replay a client ray. External provider candidates and opaque locators
must pass provider validation/materialization before gaining committed identity;
see [Sable](../integrations/sable.md).

## Server configuration update enforcement

The server derives the requester's identity and permission from trusted
server-side state, checks the current editing authority for every configuration
update before mutation, and rejects corrupt or unauthorized updates without
changing configuration state. Who may edit is owned by
[server configuration authority](authority/server-config.md); the
request/correlation and merge transaction is owned by
[changing server configuration](config/changing-server-config.md).

The operations covered by this settings authority, including the boundaries of
ordinary `MarkerCreate` and `MarkerRemove`, are owned by
[server configuration authority](authority/server-config.md) and
[target validation](authority/target_validation.md).

## Rate and malformed-state handling

Send-rate enforcement and policy synchronization are owned by
[rate policy](config/rate-limit.md). The client courtesy mirror is not trusted
authority; zero and negative policy semantics and all other authoritative rate
handling are defined there.

Which rejection response may show the local invalid-target error, and the
silent/debug-only outcomes for other responses, are owned by
[target validation](authority/target_validation.md).

## Isolation and resource lifetime

Optional-mod gates and lazy/reflective boundaries prevent linkage failures from
breaking unrelated pings. Render registries use immutable snapshots and
retained, idempotently closed registration handles. Rendering uses local/scoped
state, not persistent vanilla glowing/team mutations. Flywheel adapters must
not revive or mutate stale/hidden/deleted/foreign handles.

For geometry source attempts, only `Exception`, `LinkageError` and
`AssertionError` are recoverable. Fatal JVM/resource errors propagate. A partial
recoverable emission follows the exact
[source outcome contract](geometry/geometry_sources.md), not a blanket catch or
duplicate fallback render. Config recovery must preserve original bytes under
[client invalid-file recovery](config/revisioning.md#invalid-file-recovery-differs-by-config-type).

## Diagnostic detail and user feedback

Keep detailed optional/render diagnostics lazy, bounded and rate-controlled,
including complete exception details. Rate-controlled diagnostics may retain
complete target, position, registry, class, material, component and payload
details without redaction. Frequency limits must not be replaced by deleting
the information needed to diagnose a failed optional integration.

Do not add toast or action-bar feedback for rate or integration failures. The
required light-red local invalid-target message is separately specified in
target validation. These are established product boundaries, not evidence of a
completed security audit; coverage limitations remain in
[verification](../testing/verification.md).
