# Authority, failure isolation and diagnostics

## Trust boundaries

Captured client intent is a request. The server derives/verifies target
classification, existence, dimension, block/entity state, range, allowed Ping
Type, names, colors, ownership, channel, audience and lifecycle data from
authoritative state where possible.
Clients cannot authorize marker removal or select a server winner by sending
presentation values. Detailed packet and invalidation timing lives in
[target validation](authority/target_validation.md); deterministic winner
selection lives in [ping winner](authority/ping_winner.md).

Entity-local geometry is client capture metadata. Whole-entity identity and
anchor-based validation survive precise surface hits; the server does not
claim to replay a client ray. External provider candidates and opaque locators
must pass provider validation/materialization before gaining committed identity;
see [Sable](integrations/sable.md).

## Server-setting permission

Requesting the server-settings snapshot is not itself an edit authorization.
The snapshot's `canEdit` value is only a server-provided UI capability hint.
The server independently checks `player.hasPermissions(3)` for every settings
update on the server thread and rejects updates below that permission level.
No client-provided permission or editable display state is authoritative.

This permission gate is specific to server-configuration editing. Ordinary
MarkerCreate is not OP-gated; it is governed by packet validity, rate and
channel/team policy, target validation and allowed Ping Type. MarkerRemove is
also not OP-gated, but it succeeds only for an active marker owned by the
requester. Administrator status does not replace that ownership contract.

## Rate and malformed-state handling

[Rate policy](config/rate_limit.md) synchronizes on reconnect/effective changes
and is enforced server-side for all clients. Corrupt/negative synchronized
policy is ignored client-side, while negative persisted server values are
recovered by server-config validation. Its client token bucket is only a
courtesy immediately before creates.
Throttled committed creates are dropped, not queued or tracked as dispatched;
remove/channel operations retain their behavior.

Only the latest dispatched create's `TARGET_GONE` response may show the local
invalid-target error. Older/unknown responses, other reasons, invalid removals
and empty cancellations follow their explicit silent/debug-only contracts.

## Isolation and resource lifetime

Optional-mod gates and lazy/reflective boundaries prevent linkage failures from
breaking unrelated pings. Render registries use immutable snapshots and retained,
idempotently closed registration handles. Rendering uses local/scoped state,
not persistent vanilla glowing/team mutations. Flywheel adapters must not revive
or mutate stale/hidden/deleted/foreign handles.

For geometry source attempts, only `Exception`, `LinkageError` and
`AssertionError` are recoverable. Fatal JVM/resource errors propagate. A partial
recoverable emission follows the exact
[source outcome contract](geometry/geometry_sources.md), not a blanket catch or
duplicate fallback render. Config recovery must preserve original bytes under
the [backup-failure lock](config/client.md).

## Diagnostic detail and user feedback

Keep detailed optional/render diagnostics lazy, bounded and rate-controlled,
including complete exception details. Rate-controlled diagnostics may retain
complete target, position, registry, class, material, component and payload
details without redaction. Frequency limits must not be replaced by deleting
the information needed to diagnose a failed optional integration.

Do not add toast or action-bar feedback for rate or integration failures. The
required light-red local invalid-target message is separately specified in
target validation. These are established product boundaries, not evidence of
a completed security audit; coverage limitations remain in
[verification](testing/verification.md).
