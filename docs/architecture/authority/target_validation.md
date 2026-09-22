# Target validation and rejection semantics

This topic owns the authoritative adjudication of marker creation and removal
and the initial audience snapshot. The packet families, request boundary, and
client packet acceptance are owned by [network protocol](../network/protocol.md);
how clients present rejection outcomes is owned by
[ping feedback](../../UI/ping-feedback.md). Trusted input derivation is owned by
[security](../security.md), and the detailed rate accounting is owned by
[rate policy](../config/rate-limit.md).

## Create adjudication order

`MarkerCreate` is the authoritative marker-creation ingress. For it, the server
repeats target classification from its own game state and does not trust
client-supplied target validity or presentation/ownership data. The request
field boundary is owned by [network protocol](../network/protocol.md#registered-ingress-and-current-effects).

The effective first-return order after a structurally valid request is:

1. the configured server rate gate; an exceeded check returns `RATE_LIMITED`;
2. the sender's server-stored channel and the empty-channel `DISABLED` or
   `TEAM_ONLY` admission gate; a failure returns `CHANNEL_DISABLED`;
3. recipient snapshot construction from that stored channel and current server
   context;
4. creation argument checking and authoritative target/provider validation,
   including the validation-anchor range check;
5. initial authoritative Target Type classification and requested Ping Type
   membership;
6. for an explicit external target, provider materialization followed by the
   post-materialization reclassification and Ping Type check; and
7. marker-store creation.

A structurally corrupt request receives `INVALID_REQUEST` and returns before the
rate, channel, recipient, target, or marker-store creation stages. Thus
target/range validation, the initial Target Type classification, and the initial
Ping Type check do not run before the channel gate or recipient snapshot. Once
validation begins, target/range validation precedes the initial authoritative
Target Type classification and Ping Type check, and the initial classification
precedes any external-target materialization. The later materialization
transaction and the post-materialization reclassification, cleanup, and release
rules are owned by
[Sable](../../integrations/sable.md#server-validation-and-materialization). The
first returning gate is the reported rejection; no later reason is inferred.

Rate accounting and the client-side courtesy token bucket are owned by
[rate policy](../config/rate-limit.md).

For example, a structurally valid request with an empty stored channel while
the mode is `DISABLED` receives `CHANNEL_DISABLED` even if its requested target
has disappeared, because target validation is not reached. A valid,
rate-permitted request that passes the channel gate and then finds a dead or
unavailable target receives `TARGET_GONE`. Only the latter is eligible for the
narrowly gated local feedback rule owned by
[ping feedback](../../UI/ping-feedback.md).

## Creation-time validation and outcomes

After the admission gates above have passed, the server validates or derives:

- the sender-owned request and current server dimension;
- target existence and live entity/block/provider state;
- eye-to-authoritative validation-anchor range against the configured server
  ping range;
- the Target Type by re-running the server resolver on the normalized target;
- that the requested Ping Type exists and belongs to that Target Type; and
- the authoritative target name, owner, arrival, lifetime, and audience data.

Authoritative names follow [names and chat](../rendering/names_chat.md). Color
is derived from the accepted server Ping Type definition. Whole-entity
local-geometry captures remain whole-entity requests; the server validates the
entity and its authoritative anchor, not a replay of the client's ray or a
local constituent. Packet-supplied fields are limited by the request boundary in
[network protocol](../network/protocol.md).

| Target | Creation-time validity |
| --- | --- |
| Entity | Stable identity in the requester's current dimension, present and alive, and authoritative entity anchor within range; movement/same-dimension teleport keeps identity |
| Block | Loaded position in the requester's current dimension, same block type, and block-center anchor within range; same-type state/property changes are valid |
| Location | Finite captured coordinates in the requester's current dimension and exact location anchor within range |
| Explicit external target | Provider validates a normalized candidate and provider-derived validation anchor, then range checks that anchor; see [Sable's two-phase materialization](../../integrations/sable.md#server-validation-and-materialization) for the later committed target/anchor transaction |

Ordinary post-commit entity and block behavior, current block-state
presentation, world/HUD dimension filtering, and the established external
periodic refresh/invalidation exception are owned by
[target identity](../identity/target_model.md) and
[names and chat](../rendering/names_chat.md), with the external exception
detailed by [Sable](../../integrations/sable.md); they are not part of
create-time validation. Entity locator form and server canonicalization,
including the Experience Orb runtime-ID exception, are defined by
[target identity](../identity/target_model.md).

A malformed block registry ID or invalid provider request is an invalid request
(`INVALID_REQUEST`). A gone/dead/cross-dimension entity, a missing or
differently typed block, or a temporarily unavailable external target is
rejected as target-gone (`TARGET_GONE`). An authoritative anchor outside the
configured range is rejected as out-of-range. Pure location validation does not
replay the client ray or add an obstruction test.

For an explicit external target, nonallocating provider validation establishes a
provider-derived validation anchor for that range check. Initial classification
and Ping Type membership pass before provider materialization; the later
materialization/reclassification transaction, including Sable's acquired
tracking-reference release, is owned by
[Sable](../../integrations/sable.md#server-validation-and-materialization). It
does not add a post-materialization range check; the range rule for the
validation anchor is owned by [range](../picking/range.md).

When server player tracking is disabled, a player entity request is normalized
to a location at the player's authoritative current position.

## Removal and channel roles

`MarkerRemove` carries a marker identity/request. The server checks that the
requester owns the active marker, then synchronizes a valid removal. Stale or
unauthorized requests are safely ignored or rejected. Another player's marker
cannot be cancelled even if a modified client requests it. Ordinary marker
creation and removal are not OP-gated; the ownership contract governs removal.

Existing channel-update behavior, including policy updates, remains intact; it
is not treated as a create-only operation, and its route effect is owned by
[network protocol](../network/protocol.md#route-effects-and-channel-establishment).
The [rate limiter](../config/rate-limit.md) applies its client courtesy gate only
immediately before a `MarkerCreate` dispatch. Cancellation and expiry also drive
[winner recomputation](ping_winner.md).

## Audience snapshot at create

The `MarkerCreate` request does not authorize its channel or recipients. The
server uses the sender's stored channel and the current server channel mode,
then snapshots a non-empty recipient list at creation. The sender is included
in every accepted snapshot; other recipients are online players selected by the
following table. Target/range, Target Type, and Ping Type validation occur after
the channel gate and snapshot, while rate enforcement occurs before them.

For every online candidate, snapshot construction first requires exact equality
between the candidate's stored channel and the creator's stored channel. This
applies to empty channels too. Only after that equality check does an empty,
non-`GLOBAL` channel require the same-context predicate below. The creator is
added separately before this candidate loop and is retained once for every
accepted creation; the final snapshot de-duplicates the list.

| Sender's stored channel at create | Server channel mode | Creation gate | Audience snapshot |
| --- | --- | --- | --- |
| Non-empty channel `C` | `AUTO`, `DISABLED`, `GLOBAL`, or `TEAM_ONLY` | None from the default channel mode | Sender plus every online other player whose stored channel is exactly `C`. Team/context matching is not additionally applied. |
| Empty channel | `DISABLED` | Reject creation | No marker or audience is created. |
| Empty channel | `TEAM_ONLY` | Reject when the sender has no team context | When the sender has a context, include the sender plus online other players whose stored channel is also empty and who are in the same context. |
| Empty channel | `AUTO` | No team-context admission gate | Include the sender plus online other players whose stored channel is also empty and who are in the same context, including the precise no-context equivalence below. |
| Empty channel | `GLOBAL` | No team-context admission gate | Include the sender plus every online player whose stored channel is also empty; team/context is not additionally applied. |

For empty-channel `AUTO` and permitted `TEAM_ONLY`, "same context" is the
predicate defined below, not a broad statement that two players are both
nominally teamed:

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

The `TEAM_ONLY` admission gate identifies the sender's context in this priority
order: Voice Chat, FTB Teams, vanilla team, then `NONE`. FTB personal/player
teams and unavailable or fail-soft optional integrations yield no selected
FTB/Voice context. `GLOBAL` and a non-empty channel do not consult this
predicate.

The server assigns arrival/expiry state and synchronizes the accepted marker as
described by [marker lifecycle](marker_lifecycle.md). Channel switches and
team/group changes after creation do not rewrite its recipient snapshot.
Disconnect cleanup is the only audience mutation, and it is owned by
[marker lifecycle](marker_lifecycle.md); it does not recalculate channel/team
policy for remaining recipients.

Coverage and gaps for this contract are inventoried in
[testing and verification](../../testing/verification.md).
