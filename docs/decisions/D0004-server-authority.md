# D0004: Server authority

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

Treat client MarkerCreate data as intent. The server validates the captured
target against its own state, reclassifies the normalized target, validates the
selected Ping Type, and derives owner, authoritative name/color, channel,
audience, arrival and lifetime. MarkerRemove is accepted only for an active
marker owned by the requester. Same-target winner selection is server-owned and
recipient-scoped: latest server arrival wins, with larger Marker ID as the
equal-arrival tie-breaker. Losing markers remain active.

The server enforces create rate limits for every client. The client token bucket
is only a create-time courtesy gate; rejected courtesy creates are dropped and
not tracked as dispatched. Remove and channel-update behavior remains
unchanged. External providers may have their explicitly documented refresh
exception; ordinary committed markers are not continuously revalidated.

## Rationale

Client-supplied presentation, classification, ownership and timing are not
authoritative in a multiplayer system. Server state is the common source of
truth for target validity and permissions. Recipient-scoped winner calculation
also respects the immutable audience captured at creation, while retaining
losers permits deterministic recovery after removal or expiry.

## Why not other approaches

- Do not trust client classification or display data: modified clients can alter
  those values.
- Do not use client timestamps or local packet/render order: they are not a
  shared ordering source.
- Do not delete non-winning markers: a later removal would lose the correct
  fallback state.
- Do not make ordinary markers continuously provider-like: that changes the
  established lifecycle and is unnecessary for the ordinary identity model.
- Do not make server configuration permission imply ping or marker-removal
  permission: those operations have separate policy and ownership boundaries.

## Consequences

The server owns rejection reasons, presentation data, audience and winner
updates. The client must distinguish local pre-commit invalidation from a
`TARGET_GONE` response for the latest actually dispatched create. Optional
provider state needs explicit materialization and refresh handling.

## Related docs

[Target validation](../authority/target_validation.md),
[ping winner](../authority/ping_winner.md), [security](../security.md),
[rate policy](../config/rate_limit.md), and [Sable](../integrations/sable.md).

Focused tests named by the current coverage include
`AuthoritativeTargetValidationTest`, `MarkerCreationServiceTest`,
`MarkerWinnerTest`, `ServerMarkerStoreTest`, `MarkerPacketCodecTest`, and the
rate-policy tests. Test existence and a test run remain separate evidence claims.
