# Target validation, packets and rejection semantics

## Authority at `MarkerCreate`

`MarkerCreate` is the authoritative marker-creation ingress. Its packet carries
captured stable target identity and a selected Ping Type ID, but no channel,
audience, Target Type, name, color, owner, arrival time, or lifetime. For this
packet, the server repeats classification from its own game state and does not
trust client-supplied target validity or presentation/ownership data.
Authoritative names follow [names and chat](../rendering/names_chat.md).

These `MarkerCreate` guarantees do **not** describe the separately registered
legacy `PingLocationC2SPacket`, whose payload still contains a channel and is
forwarded through its legacy path. The distinction in registration, server side
effects, and current client treatment is owned by
[network protocol](network_protocol.md); it is not a compatibility promise for
the original mod.

After the admission gates below have passed, `MarkerCreate` validates or derives:

- the sender-owned request and current server dimension;
- target existence and live entity/block/provider state;
- eye-to-authoritative validation-anchor range against the configured server ping
  range;
- the Target Type by re-running the server resolver on the normalized target;
- that the requested Ping Type exists and belongs to that Target Type; and
- the authoritative target name, owner, arrival, lifetime and audience data.

The server does not accept a client-provided Target Type, name, color, owner,
arrival time, lifetime, channel or audience for `MarkerCreate`. Color is derived
from the accepted server Ping Type definition. Whole-entity local-geometry
captures remain whole-entity requests; the server validates the entity and its
authoritative anchor, not a replay of the client's ray or a local constituent.

## `MarkerCreate` admission and rejection order

The loader route uses `MarkerCreateC2SPacket.readSafe`; a decode failure is
represented by its corrupt fallback packet. `ServerCore.onMarkerCreate` then
performs its packet structural check (`isCorrupt`) before it asks the rate
limiter. A corrupt packet receives `INVALID_REQUEST` and returns without
reaching the rate, channel, recipient, target, or marker-store creation stages.

For a structurally valid packet, the effective first-return order is:

1. when the configured server rate limit is positive, the existing server
   `RateLimiter.checkExceeded()` call; an exceeded check returns
   `RATE_LIMITED`;
2. the sender's server-stored channel and the empty-channel `DISABLED` or
   `TEAM_ONLY` admission gate; a failure returns `CHANNEL_DISABLED`;
3. recipient snapshot construction from that stored channel and current server
   context; then
4. `MarkerCreationService` argument checking, authoritative target/provider
   validation and range checking, server reclassification, and requested Ping
   Type membership.

Thus target/range validation, Target Type resolution, and Ping Type validation
do not run before the channel gate or recipient snapshot. Once the service is
called, its target/range validation precedes reclassification, and
reclassification precedes requested Ping Type lookup/membership. The first
returning gate is the reported rejection; no later reason is inferred.

For an explicit external target, nonallocating provider validation establishes a
provider-derived validation anchor for that range check. Initial classification
and Ping Type membership pass before provider materialization; the later
materialization/reclassification transaction, including Sable's acquired
tracking-reference release, is owned by
[Sable](../../integrations/sable.md#server-validation-and-materialization). It does
not add a post-materialization range check.

The server limiter is reached before channel, snapshot, target, range,
classification, or Ping Type checks and is not rolled back when one of those
later stages rejects. With a positive configured limit, the first structurally
valid request initializes the limiter and proceeds; later permitted checks
advance its limiter state, while an exceeded check returns `RATE_LIMITED`
without running a later stage. A corrupt packet never reaches that call, and a
configured limit of zero skips it. This is server enforcement, not the
client-side courtesy token bucket described by [rate policy](../config/rate-limit.md).

For example, a structurally valid request with an empty stored channel while
the mode is `DISABLED` receives `CHANNEL_DISABLED` even if its requested target
has disappeared: target validation is not reached. A valid, rate-permitted
request that passes the channel gate and then finds a dead or unavailable target
receives `TARGET_GONE`. Only the latter is eligible for the narrowly gated local
feedback rule in [server responses](#server-responses-and-silent-outcomes).

| Target | Creation-time validity | Ordinary post-commit behavior |
| --- | --- | --- |
| Entity | Stable identity in the requester's current dimension, present and alive, and authoritative entity anchor within range; movement/same-dimension teleport keeps identity | No continuous server revalidation; normal removal/expiry; unavailable entity may use last/authoritative anchor |
| Block | Loaded position in the requester's current dimension, same block type, and block-center anchor within range; same-type state/property changes are valid | Replacement alone does not remove marker; renderer uses current render state |
| Location | Finite captured coordinates in the requester's current dimension and exact location anchor within range | Preserve established location lifecycle |
| Explicit external target | Provider validates a normalized candidate and provider-derived validation anchor, then range checks that anchor; see [Sable's two-phase materialization](../../integrations/sable.md#server-validation-and-materialization) for the later committed target/anchor transaction | Established periodic refresh/invalidation exception; see Sable |

World/HUD marker visuals are skipped when the target dimension differs from the
local current dimension. That visual filter does not imply that every recipient
notification is dimension-filtered; [new-marker feedback and dimensions](../rendering/names_chat.md#new-marker-feedback-and-dimension-behavior)
owns the sound/chat distinction. See [target identity](../identity/target_model.md)
and [Sable](../../integrations/sable.md) for the lifecycle boundary; do not
generalize the external-target exception into continuous ordinary-marker
validation.
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
[rate limiter](../config/rate-limit.md) applies its client courtesy gate only
immediately before MarkerCreate. Cancellation/expiry also drive
[winner recomputation](ping_winner.md).

## Audience snapshot at create

The MarkerCreate packet does not authorize its channel or recipients. The
server uses the sender's stored channel and the current server channel mode,
then snapshots a non-empty recipient list at creation. The sender is included
in every accepted snapshot; other recipients are online players selected by
the following table. Target/range, Target Type, and Ping Type validation occur
after the channel gate and snapshot, while rate enforcement occurs before them.

For every online candidate, snapshot construction first requires exact equality
between the candidate's stored channel and the creator's stored channel. This
applies to empty channels too. Only after that equality check does an empty,
non-`GLOBAL` channel require `TeamContextHandler.inSameContext`. The creator is
added separately before this candidate loop and is retained once for every
accepted creation; the final snapshot de-duplicates the list.

| Sender's stored channel at create | Server `ChannelMode` | Creation gate | Audience snapshot |
| --- | --- | --- | --- |
| Non-empty channel `C` | `AUTO`, `DISABLED`, `GLOBAL`, or `TEAM_ONLY` | None from the default channel mode | Sender plus every online other player whose stored channel is exactly `C`. Team/context matching is not additionally applied. |
| Empty channel | `DISABLED` | Reject creation | No marker or audience is created. |
| Empty channel | `TEAM_ONLY` | Reject when the sender has no team context | When the sender has a context, include the sender plus online other players whose stored channel is also empty and who are in the same context. |
| Empty channel | `AUTO` | No team-context admission gate | Include the sender plus online other players whose stored channel is also empty and who are in the same context, including the precise no-context equivalence below. |
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
[D0004](../../decisions/D0004-server-authority.md).
