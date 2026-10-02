# Network protocol: authoritative markers and legacy locations

This topic owns the logical packet families, registered ingress routes, and
current client packet acceptance for marker authority. It records packet
semantics and the boundary between the authoritative marker family and the
legacy location family; the versioned presentation snapshot and presentation
policy routes it registers are owned by
[presentation snapshot](../presentation/presentation_snapshot.md). The
dedicated inventory preview/tracking route, the one-shot presentation preview
route, and the server-configuration request, update, and snapshot routes
recorded below are registered here as well; their route grammar and acceptance
belong to this topic, while inventory status, zero, baseline and budget
semantics are owned by [inventory](../presentation/inventory.md), preview field
authorization, provenance and fallback by
[presentation snapshot](../presentation/presentation_snapshot.md), and the
server-configuration transaction by
[changing server configuration](../config/changing-server-config.md).
It is not a wire-format catalogue and does not promise interoperability with the
original mod. The supported loader set and the original-mod compatibility policy
are owned by [compatibility](../../compatibility.md).

## Registered ingress and current effects

All three supported loader entry points—Fabric, Forge, and NeoForge—register the
legacy location route, the superseded authoritative marker routes, the
versioned presentation snapshot route, the presentation policy route, the
inventory preview/tracking route, the presentation preview route, and the
server-configuration request, update, and snapshot routes, and dispatch them
through the common handlers. A
route being registered means this codebase can receive its current packet
family; it does not make the legacy packet family an authoritative marker
protocol, and registering a superseded route does not re-enable its effect.

| Packet family and direction | Registered route | Server-side effect | Current client acceptance and presentation |
| --- | --- | --- | --- |
| Legacy `PingLocationC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Reads the **packet's** channel, applies the legacy rate and empty-channel policy, may update the sender's stored channel to that packet value, and forwards its paired legacy `PingLocationS2CPacket` to matching recipients. It does not create an authoritative marker record or enter the authoritative marker store. | Not applicable on C2S. Its paired legacy S2C route is listed below. |
| Legacy `PingLocationS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | A corrupt packet is warned about; a valid packet is deliberately ignored. It does not mutate marker state, overlay/outline state, or presentation state. A valid ignored packet is not an accepted presentation update. |
| Superseded authoritative `MarkerCreateC2SPacket` and `MarkerRemoveC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handlers remain registered. | Disabled no-ops: they perform no marker mutation. Marker create and remove requests enter only through the negotiated presentation `CREATE`/`REMOVE` intents below. | Not applicable on C2S. |
| Superseded marker S2C packets: `MarkerCreated`, `MarkerRemoved`, `MarkerWinnerChanged`, and `MarkerRejected` | Fabric, Forge, and NeoForge common client handlers remain registered. | No server ingress effect. | Registered no-ops: no marker mutation and no client display values are sourced from this family. |
| Presentation `PresentationC2SPacket` (C2S): `HELLO`, `CREATE`, `REMOVE` | Fabric, Forge, and NeoForge common server handler. | The versioned route negotiates presentation sessions, answers `HELLO` with an offer and a server-selected authorization mask, and carries marker create/remove intents with the negotiated epoch; a create may carry property selections. Session negotiation, manifest, mask, policy, property, and projection semantics are owned by [presentation snapshot](../presentation/presentation_snapshot.md); accepted intents enter the admission and removal rules owned by [target validation](../authority/target_validation.md); rejections are sent to the requester. | Not applicable on C2S. |
| Presentation `PresentationS2CPacket` (S2C): `OFFER`, `RESET`, `CREATED`, `SECTION`, `REMOVED`, `WINNER`, `REJECT` | Fabric, Forge, and NeoForge common client handler. | Accepted marker creation, removal, and winner changes are projected per recipient; a rejection is sent only to its requester. | Accepted only for the negotiated session epoch/view. The atomic Basic initial, default display reference, retained values, mask pruning, and legacy-name boundary are owned by [presentation snapshot](../presentation/presentation_snapshot.md); marker record state and visual lifetime are owned by [client marker state](../markers/client-state.md); rejection presentation remains owned by [ping feedback](../../UI/ping-feedback.md). Corrupt packets, or packets received without a runtime, are safely dropped. |
| Presentation policy `ServerPresentationPolicyC2SPacket` (C2S): `READ`, `ADD_WHITE`, `REMOVE_WHITE`, `ADD_BLACK`, `REMOVE_BLACK`, `SET_WHITELIST_ONLY` | Fabric, Forge, and NeoForge common server handler. | The versioned route carries the correlated read of all five per-target-type rule views and the bounded selector and whitelist-only mutations for a selected target type. Disclosure, revision, transaction, and broadcast semantics are owned by [presentation snapshot](../presentation/presentation_snapshot.md); authority and server-side enforcement are owned by [server configuration authority](../authority/server-config.md) and [security](../security.md#server-configuration-update-enforcement). | Not applicable on C2S. |
| Presentation policy `ServerPresentationPolicyS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted into the connection-scoped rule-view mirror under the correlation and revision rules owned by [presentation snapshot](../presentation/presentation_snapshot.md); it does not mutate marker state, overlay/outline state, or presentation field values. |
| Inventory `InventoryC2SPacket` (C2S): `HELLO`, `OPEN`, `CLOSE`, `RESYNC`, `SELECT` | Fabric, Forge, and NeoForge common server handler. | `HELLO` creates or renews the per-player preview session and answers with an offer of server-selected periods plus the inventory target-type policy view; every other kind runs only under the session epoch and the current presentation epoch/view fence. `OPEN` binds one bounded preview request to a server-validated ordinary block target and its carried face, `CLOSE` releases it, `RESYNC` schedules a corrective resend (a marker id selects a tracked Ping), and `SELECT` resolves one opaque retained entry reference under its baseline/state fences into a correlated selection outcome. The request carries no player identity, item count, or client-supplied authoritative target. Inventory domain semantics are owned by [inventory](../presentation/inventory.md). | Not applicable on C2S. |
| Inventory `InventoryS2CPacket` (S2C): `OFFER`, `POLICY`, `SELECTED`, `REJECT`, `PREVIEW`, `SNAPSHOT`, `STREAM`, `STATUS`, `HEARTBEAT` | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted into the connection-scoped inventory session only under the negotiated epoch and the current presentation epoch/view: `OFFER` establishes the epoch and periods, `POLICY` carries the inventory target-type policy view, `SELECTED`/`REJECT` are accepted only against a pending commit with the matching request id, `PREVIEW` is request-scoped, and `SNAPSHOT`/`STREAM`/`STATUS`/`HEARTBEAT` are marker-scoped tracking frames. Coverage and pending runtime evidence for native input and HUD consumption of the session are owned by [testing and verification](../../testing/verification.md). Inventory domain semantics are owned by [inventory](../presentation/inventory.md). |
| Presentation preview `PresentationPreviewC2SPacket` (C2S): `READ`, `CANCEL` | Fabric, Forge, and NeoForge common server handler. | A bounded one-shot hint, not a subscription or authority: `READ` asks only for presentation fields the client cannot observe locally and is answered only under the current accepted presentation epoch/view and an accepted adapter schema and field authorization; `CANCEL` withdraws the pending request. It creates no marker, lease, winner slot, or lifetime. Field authorization, provenance, and fallback are owned by [presentation snapshot](../presentation/presentation_snapshot.md). | Not applicable on C2S. |
| Presentation preview `PresentationPreviewS2CPacket` (S2C): `RESULT`, `UNAVAILABLE`, `DEFERRED`, `REJECTED` | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted only against the current request authorization: `RESULT` carries one framed presentation section that stays undecoded until the accepted adapter schema and field authorization are available, and the control statuses carry no section. Corrupt packets are safely dropped. Field authorization, provenance, and fallback are owned by [presentation snapshot](../presentation/presentation_snapshot.md). |
| Server-configuration `ServerConfigRequestC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Carries one positive request identifier and asks for the server's current authoritative configuration snapshot; any connected player may send one. The response is correlated to that identifier. Transaction semantics are owned by [changing server configuration](../config/changing-server-config.md). | Not applicable on C2S. |
| Server-configuration `ServerConfigUpdateC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Carries a changed-field mask and the fixed-shape configuration values as a one-way mutation request with no acknowledgement or update result. Authority and enforcement are owned by [server configuration authority](../authority/server-config.md) and [security](../security.md#server-configuration-update-enforcement); the transaction is owned by [changing server configuration](../config/changing-server-config.md). | Not applicable on C2S. |
| Server-configuration `ServerConfigSnapshotS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted only while the initiating request is still pending on the same connection and the positive identifier matches it; the snapshot covers the complete remote surface and is accepted or rejected as one whole. Transaction semantics are owned by [changing server configuration](../config/changing-server-config.md). |

## Inventory preview/tracking route (`inventory-v2`)

The dedicated inventory route (`pingforit-c2s:inventory-v2` client-to-server
and `pingforit-s2c:inventory-v2` server-to-client) carries preview and tracking
requests and responses, parallel to `presentation-v3` and
`server-presentation-policy-v2`. Every frame declares protocol version two.
`HELLO` is the epoch-zero handshake; every other request runs under the
negotiated nonzero epoch and a non-negative request id, and only while the
current presentation epoch/view fence matches. `OFFER` and `POLICY` are
session messages; `OPEN`, `CLOSE`, `RESYNC` without a marker id, `SELECT`, and
the `PREVIEW` response are request-scoped; `RESYNC` with a marker id and the
`SNAPSHOT`, `STREAM`, `STATUS`, and `HEARTBEAT` responses are marker-scoped.

Client requests: `HELLO` is empty. `OPEN(epoch, requestId, target, face)` binds
a preview request to one bounded server-validated ordinary block target and its
carried face under a request id that must advance past the session's last
opened preview request id; it is ignored otherwise, and a request id never
changes its target. `CLOSE(epoch, requestId)` releases that request.
`RESYNC(epoch, requestId[, markerId])` schedules a corrective resend: a marker
id selects a tracked Ping, while an absent marker id addresses the request
session. `SELECT(epoch, requestId, commitId, baselineId, stateRevision,
entryKey, pingType)` carries one opaque retained entry reference and the
commit/baseline/state fences; the server derives the authoritative target and
count from its own retained reference and answers `SELECTED` or `REJECT`
correlated by request and commit id, so a repeated `SELECT` for a commit id
replays the stored outcome instead of re-running the selection.

Server messages: `OFFER(epoch, previewPeriodTicks, trackingPeriodTicks,
resyncMinPeriods, heartbeatPeriods)` establishes the epoch and the
server-selected periods, where an explicit zero heartbeat period disables the
periodic heartbeat; `POLICY` carries the inventory target-type policy view.
`PREVIEW`, `SNAPSHOT` and `STREAM` each carry one fragment under an independent
`(epoch, requestId, markerId, baselineId)` identity plus `statusRevision`,
`watermark`, `partIndex`/`partCount`, `completeScan`, a status, a checksum, and
a bounded entry list; `markerId` is required for `SNAPSHOT`/`STREAM` and absent
for `PREVIEW`, `SNAPSHOT` is the baseline fragment, and `STREAM` continues it.
`STATUS` carries the same state fields without entries. `HEARTBEAT` carries
state and checksum without items and never resends values. Entry fields are
`key`, `itemId`, `label`, optional `displayJson`, `count`, `itemRevision`,
`fallback`, an optional per-entry `quality` status, `groupRevision`,
`replaceGroup`, and an optional `itemPingType`; duplicate keys, negative
counts, unknown kinds or statuses, invalid part ranges, oversized frames and
trailing bytes are rejected. Status values are `UPDATING`, `READY`,
`UNCERTAIN`, `INCOMPLETE`, `UNAVAILABLE`, `INVALID`, `EXPIRED` and
`COMPONENT_TOO_LONG`.

Byte accounting on this route counts the whole encoded inventory frame —
including its header, kind and entry framing — and excludes compression and the
underlying Minecraft transport. The frame, entry-payload and per-entry bounds
are enforced during encoding and rejected during decoding before any large
allocation. The `inventory-v2` route ID is version-bound: it does not decode an
earlier inventory wire version, and no fallback reinterprets one. Route
registration and grammar remain owned here; the dedicated adapter's delivery
boundary is owned by
[presentation snapshot](../presentation/presentation_snapshot.md#dedicated-delivery-adapters),
and inventory status, zero/fallback, baseline and budget semantics are owned by
[inventory](../presentation/inventory.md).

## Presentation preview route (`presentation-preview-v1`)

The dedicated presentation preview route (`pingforit-c2s:presentation-preview-v1`
and `pingforit-s2c:presentation-preview-v1`) carries the wheel's one-shot
content hint and its response, parallel to `presentation-v3` and
`server-presentation-policy-v2`. Every frame declares protocol version one and
is bound to the current accepted presentation epoch and view plus a positive
request id. `READ` carries one target, its target type, one adapter id, and the
non-empty requested field ids; it asks only for fields the client cannot
observe locally and is not a subscription, capability grant, or authority.
`CANCEL` withdraws the pending request. The dedicated inventory adapter is
excluded from this route and keeps its own route.

The server answers only under fresh authorization for the same epoch, view,
and target type, and only for an accepted adapter whose accepted fields contain
every requested field. `RESULT` carries one framed presentation section;
`UNAVAILABLE`, `DEFERRED` and `REJECTED` are control statuses without a
section. The section bytes stay undecoded until the current request
authorization is available, and are then decoded strictly against that
authorization: a mismatched adapter or schema, an unaccepted field, a stale
section, any annotation, or trailing bytes rejects the response rather than
widening the accepted mask. Oversized request or response frames, empty or
duplicate requested fields, unknown kinds, unknown target types, and trailing
bytes are rejected during decoding.

The `presentation-preview-v1` route ID is version-bound: it does not decode
another wire version, and no fallback reinterprets one. Route registration and
grammar remain owned here; preview field authorization, provenance and fallback
are owned by [presentation snapshot](../presentation/presentation_snapshot.md),
and the [wheel](../picking/wheel.md) owns the branch's native presentation
while [capture](../picking/capture.md) owns the interaction abort.

## Server-configuration routes (`server-config-request-v2`, `server-config-update-v2`, `server-config-snapshot-v2`)

The server-configuration surface travels on three dedicated version-two route
IDs (`pingforit-c2s:server-config-request-v2`,
`pingforit-c2s:server-config-update-v2`, and
`pingforit-s2c:server-config-snapshot-v2`), separate from the presentation
policy route and the inventory routes. The
request and update routes are client-to-server; the snapshot route is
server-to-client. A request carries one positive request identifier and asks
for the server's current authoritative values; the snapshot echoes that
identifier and is correlated to the still-pending request on the same
connection. An update carries a changed-field mask and the fixed-shape
configuration values as a one-way mutation request with no acknowledgement or
update result.

Numbers on these routes are canonical non-negative varints: overflow,
overlong, and non-canonical encodings are rejected, booleans are strict, the
inventory administration payload is fixed-shape and consumed to its exact end,
and trailing bytes are rejected in full before any value is applied. The route
IDs are version-bound: they do not decode an earlier server-configuration wire
version, and no fallback reinterprets one. Route registration and grammar
remain owned here; the disclosed field catalogue is owned by
[server configuration](../../config/server.md), the transaction, correlation,
field mask, merge and apply rules by
[changing server configuration](../config/changing-server-config.md), and
authority and enforcement by
[server configuration authority](../authority/server-config.md) and
[security](../security.md#server-configuration-update-enforcement).

## Route effects and channel establishment

`UpdateChannelC2SPacket` is the ordinary way to establish the server-stored
channel that marker creation consumes; a create request does not carry its own
channel.
Channel operations bypass the create-only courtesy gate; their rate and policy
behavior is owned by [rate policy](../config/rate-limit.md). Conversely, the
legacy location handler's retained in-packet channel behavior must not be used
to weaken the authoritative marker contract.

## Authority and feedback boundaries

The authoritative creation order and exact audience matrix are owned by
[target validation](../authority/target_validation.md). In particular, the
client's inability to provide a marker-create channel or audience applies to
marker-create requests on the negotiated presentation route and to the
superseded `MarkerCreateC2SPacket`, not to legacy location ingress.

Rejection presentation and the eligible-message rule are owned by
[ping feedback](../../UI/ping-feedback.md). Legacy valid location packets have no
marker presentation path to which that rule could apply.

## Encoded identity constraints

This section records only the encoded identity constraints that the wire
boundary imposes on an otherwise domain-owned identity; it remains part of the
logical protocol description and is not a complete wire-format catalogue.

- `Target` and `TargetKey` encode `dimensionId` with a 256-character `writeUtf`
  limit. This is a wire character limit, not a domain external-identifier limit
  or a byte limit; the domain requires only that `dimensionId` is non-blank.
- `targetTypeId`, including `entity_block`, survives marker codec round trips.

The identity variants, provider-independent external identity constraints, and
the non-blank dimension requirement are owned by the
[target model](../identity/target_model.md#external-block-identity).

## Evidence

Existing coverage inventory and remaining integration gaps are owned by
[testing and verification](../../testing/verification.md).
