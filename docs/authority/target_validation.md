# Target validation, packets and rejection semantics

## Authority at marker creation

The server repeats classification from its own game state. It never trusts
client-supplied classification, display names, colors, ownership or target
validity when it can validate the underlying target or marker. Authoritative
names follow [names and chat](../rendering/names_chat.md).

`MarkerCreate` carries captured stable target identity and a selected Ping Type
ID for a committed create. Before accepting it, the server validates or derives:

- the sender-owned request and current server dimension;
- target existence and live entity/block/provider state;
- eye-to-authoritative-anchor range against the configured server ping range;
- the Target Type by re-running the server resolver on the normalized target;
- that the requested Ping Type exists and belongs to that Target Type; and
- the authoritative target name, owner, arrival, lifetime and audience data.

The server does not accept a client-provided Target Type, name, color, owner,
arrival time, lifetime, channel or audience. Color is derived from the accepted
server Ping Type definition. Whole-entity local-geometry captures remain
whole-entity requests; the server validates the entity and its authoritative
anchor, not a replay of the client's ray or a local constituent.

| Target | Creation-time validity | Ordinary post-commit behavior |
| --- | --- | --- |
| Entity | Stable identity in the requester's current dimension, present and alive, and authoritative entity anchor within range; movement/same-dimension teleport keeps identity | No continuous server revalidation; normal removal/expiry; unavailable entity may use last/authoritative anchor |
| Block | Loaded position in the requester's current dimension, same block type, and block-center anchor within range; same-type state/property changes are valid | Replacement alone does not remove marker; renderer uses current render state |
| Location | Finite captured coordinates in the requester's current dimension and exact location anchor within range | Preserve established location lifecycle |
| Explicit external target | Provider-authoritative candidate validation/materialization and provider-derived anchor within range | Established periodic refresh/invalidation exception; see Sable |

Presentation in another dimension is skipped. See
[target identity](../identity/target_model.md) and
[Sable](../integrations/sable.md) for the lifecycle boundary; do not generalize
the external-target exception into continuous ordinary-marker validation.
Entity locator form and server canonicalization, including the Experience Orb
runtime-ID exception, are defined by [target identity](../identity/target_model.md).

A malformed block registry ID or invalid provider request is an invalid request.
A gone/dead/cross-dimension entity, a missing or differently typed block, or a
temporarily unavailable external target is rejected as target-gone. An
authoritative anchor outside the configured range is rejected as out-of-range.
Pure location validation does not replay the client ray or add an obstruction
test. When server player tracking is disabled, a player entity request is
normalized to a location at the player's authoritative current position.

## Removal and channel roles

`MarkerRemove` carries a marker identity/request. The server checks that the
requester owns the active marker, then synchronizes a valid removal. Stale or
unauthorized requests are safely ignored/rejected. Another player's marker
cannot be cancelled even if a modified client requests it.

Existing channel-update behavior, including policy updates, remains intact; it
is not treated as a create-only operation. The
[rate limiter](../config/rate_limit.md) applies its client courtesy gate only
immediately before MarkerCreate. Cancellation/expiry also drive
[winner recomputation](ping_winner.md).

The MarkerCreate packet does not authorize its channel or recipients. The
server uses the sender's stored channel and channel/team policy, snapshots the
recipient audience at creation, assigns server arrival and expiry state, and
then synchronizes accepted state. Later channel switches do not rewrite that
marker's audience. Disconnect cleanup is a lifecycle exception: the server
removes markers owned by the disconnected player, removes that player from the
remaining marker audiences, and removes a marker whose audience is then empty.
This cleanup does not recalculate channel/team policy.

## Local pre-commit invalidation

When the captured target is invalid before commit, reject creation, create no
marker, end the interaction as appropriate and immediately show the local player
light-red text whose rendered `zh_cn` value is exactly `目标消失或死亡` (literal
or translation key).

This applies to a missing/dead/cross-dimension entity or a block replaced by a
different block type at creation. Same-type BlockState changes are not an error.
This local check does not supersede authoritative server validation.

## Server responses and silent outcomes

A server `TARGET_GONE` rejection shows that same light-red message only for the
latest **actually dispatched** create request. Older or unknown request
responses and all other rejection reasons are debug-only. A courtesy-throttled
create is not sent or recorded as dispatched and adds no toast/action-bar
feedback. This does not suppress the local pre-commit invalidation message.

Wheel timeout, cancel with no eligible own marker, stale removal and unauthorized
removal are silent no-ops/rejections. Recoverable geometry uses its separate
[source outcome contract](../geometry/geometry_sources.md); fatal JVM/resource
errors must not be swallowed. See [security](../security.md) and
[D0004](../decisions/D0004-server-authority.md).
