# Authority, failure isolation and diagnostics

## Trust boundaries

For authoritative `MarkerCreate`, captured client intent is untrusted request
data. Authoritative admission and removal are adjudicated from trusted server
state. The request field boundary is owned by
[network protocol](network/protocol.md); the admission stages and outcomes,
including removal adjudication, are owned by
[target validation](authority/target_validation.md).

This trusted-state guarantee is specific to `MarkerCreate`. It must not be
extended to the registered legacy `PingLocationC2SPacket`, which retains an
in-packet channel and legacy forwarding path. See
[network protocol](network/protocol.md) for the deliberately narrow
legacy-versus-authoritative boundary.

Presentation snapshot values come from server-side capture. Client-uploaded
property observations are untrusted claims; accepted selections use
server-recaptured values.
[Presentation snapshot](presentation/presentation_snapshot.md) owns negotiation,
field authorization, projection, and retention.

Entity-local geometry remains client capture metadata, while whole-entity
identity and anchor validation are a server boundary; the server does not claim
to replay a client ray. Exact geometry and whole-entity rules are owned by
[local geometry picking](picking/local_geometry.md#frozen-metadata-and-whole-entity-identity).
External provider candidates and opaque locators must pass provider
validation/materialization before gaining committed identity; see
[Sable](../integrations/sable.md).

## Server configuration update enforcement

For configuration updates and presentation policy mutations, the server derives
the requester's identity and permission from trusted server-side state and
rechecks current editing authority before mutation. Corrupt, invalid, or
unauthorized requests leave configuration state unchanged. Neither a
client-provided edit hint nor a request identifier is an authorization
credential. Who may read or edit is owned by
[server configuration authority](authority/server-config.md); the
request/correlation, merge, and rule-view transaction is owned by
[changing server configuration](config/changing-server-config.md) and
[presentation snapshot](presentation/presentation_snapshot.md).

## Rate and malformed-state handling

Send-rate enforcement and policy synchronization are owned by
[rate policy](config/rate-limit.md). The client courtesy mirror is not trusted
authority; zero and negative policy semantics and all other authoritative rate
handling are defined there.

Which rejection response may show the local invalid-target error, and the
silent/debug-only outcomes for other responses, are owned by
[ping feedback](../UI/ping-feedback.md).

## Isolation and resource lifetime

Optional-mod gates and lazy/reflective boundaries prevent linkage failures from
breaking unrelated pings. Render registries use immutable snapshots and
retained, idempotently closed registration handles. Rendering uses local/scoped
state, not persistent vanilla glowing/team mutations. Flywheel adapters must
not revive or mutate stale/hidden/deleted/foreign handles.

For geometry source attempts, only `Exception`, `LinkageError` and
`AssertionError` are recoverable. Fatal JVM/resource errors propagate. This
enumeration scopes only the shared geometry-source attempt; it is not a general
optional-integration or registry catch policy. A partial recoverable emission
follows the exact
[source outcome contract](geometry/geometry_sources.md), not a blanket catch or
duplicate fallback render. Config recovery must preserve original bytes under
[client invalid-file recovery](config/revisioning.md#invalid-file-recovery-differs-by-config-type).

## Diagnostic detail and user feedback

[Diagnostics and logging](logging.md) owns severity, report admission, bounded
exception detail, disclosure restrictions and diagnostic-output isolation.
Those reporting rules do not replace the authority and business-failure
boundaries above.

Do not add toast or action-bar feedback for rate or integration failures. The
required local invalid-target message, including its color and chat output, is
separately specified in [ping feedback](../UI/ping-feedback.md). These are
established product boundaries, not evidence of a completed security audit;
coverage limitations remain in [verification](../testing/verification/README.md).
