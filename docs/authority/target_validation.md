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

## Audience snapshot at create

The MarkerCreate packet does not authorize its channel or recipients. The
server uses the sender's stored channel and the current server channel mode,
then snapshots a non-empty recipient list at creation. The sender is included
in every accepted snapshot; other recipients are online players selected by
the following table. Normal target, Ping Type, range, and rate validation still
applies before this channel/audience stage.

| Sender's stored channel at create | Server `ChannelMode` | Creation gate | Audience snapshot |
| --- | --- | --- | --- |
| Non-empty channel `C` | `AUTO`, `DISABLED`, `GLOBAL`, or `TEAM_ONLY` | None from the default channel mode | Sender plus every online other player whose stored channel is exactly `C`. Team/context matching is not additionally applied. |
| Empty channel | `DISABLED` | Reject creation | No marker or audience is created. |
| Empty channel | `TEAM_ONLY` | Reject when the sender has no team context | When the sender has a context, include the sender plus online players in the same context. |
| Empty channel | `AUTO` | No team-context admission gate | Include the sender plus online players in the same context, including the precise no-context equivalence below. |
| Empty channel | `GLOBAL` | No team-context admission gate | Include the sender plus every online player whose stored channel is also empty; team/context is not additionally applied. |

For empty-channel `AUTO` and permitted `TEAM_ONLY`, "same context" is the
current `TeamContextHandler.inSameContext` predicate, not a broad statement
that two players are both nominally teamed:

1. Each player selects a Voice Chat group ID when one is available; otherwise
   it selects an eligible FTB Teams ID. If the sender has such a selected ID,
   the other player must have the same selected UUID. The comparison is UUID
   equality; it does not add a source-kind check.
2. If only the other player has a selected Voice/FTB ID, they are not in the
   same context.
3. If neither has a selected Voice/FTB ID, compare vanilla scoreboard-team
   references directly. Equal team references match; two `null` references
   therefore match. In particular, `NONE` versus `NONE` is equivalent for this
   predicate when neither player has a Voice group, eligible FTB team, or
   vanilla team.

`TeamContextHandler.hasTeam`, used by the `TEAM_ONLY` admission gate, identifies
the sender's context in this priority order: Voice Chat, FTB Teams, vanilla
team, then `NONE`. FTB personal/player teams and unavailable or fail-soft
optional integrations yield no selected FTB/Voice context. `GLOBAL` and a
non-empty channel do not consult this predicate.

The server assigns arrival/expiry state and synchronizes the accepted marker as
described by [marker lifecycle](marker_lifecycle.md). Channel switches and
team/group changes after creation do not rewrite its recipient snapshot.
Disconnect cleanup is the only audience mutation: the server removes markers
owned by the disconnected player, removes that player from remaining marker
audiences, and drops a marker left with no recipients. It does not recalculate
channel/team policy for remaining recipients.

The store tests cover recipient-scoped winner isolation and disconnect audience
shrink/empty-audience cleanup. The current focused evidence does not establish
the full `ServerCore` admission and audience matrix above (empty-channel mode
gates, exact non-empty-channel matching, or live Voice/FTB/vanilla priority).
Those scenarios remain verification work rather than an inferred product
change.

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
