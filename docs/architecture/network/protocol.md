# Network protocol: authoritative markers

This topic owns the logical packet families, registered ingress routes, and
current client packet acceptance for marker authority. It records packet
semantics; the versioned presentation snapshot and presentation policy routes
it registers are owned by
[presentation snapshot](../presentation/presentation_snapshot.md). The
dedicated inventory preview/tracking route, the one-shot presentation preview
route, the optional inventory monitor subscription/status/sample family, and the
server-configuration request, update, and snapshot routes recorded below are
registered here as well; their route grammar and acceptance belong to this
topic, while inventory status, zero, baseline and budget semantics are owned by
[inventory](../presentation/inventory.md), preview field authorization,
provenance and fallback by
[presentation snapshot](../presentation/presentation_snapshot.md), and the
server-configuration transaction by
[changing server configuration](../config/changing-server-config.md). Monitor
measurement meaning and sampling lifetime are owned by
[inventory performance monitoring](../presentation/inventory.md#performance-monitoring),
numeric telemetry authority by
[server configuration authority](../authority/server-config.md#remote-performance-telemetry),
metric-selector grammar by [client configuration](../../config/client.md#performance-monitor),
control presentation by
[settings screen](../../UI/settings-screen.md#performance-monitor-controls), and
HUD projection and presentation by [inventory HUD
presentation](../presentation/inventory.md#hud-projection-and-presentation).
It is not a wire-format catalogue and does not promise interoperability with the
original mod. The supported loader set and the original-mod compatibility policy
are owned by [compatibility](../../compatibility.md).

## Registered ingress and current effects

All three supported loader entry points—Fabric, Forge, and NeoForge—register the
channel-update and rate-policy routes, the versioned presentation snapshot
route, the presentation policy route, the inventory preview/tracking route, the
inventory monitor subscription, status, and sample routes, the presentation
preview route, and the server-configuration request, update, and snapshot
routes, and dispatch them through the common handlers. Marker creation enters
only through the negotiated presentation `CREATE` (including pure location
targets) or dedicated inventory
`SELECT` intents; removal enters through presentation `REMOVE`.

| Packet family and direction | Registered route | Server-side effect | Current client acceptance and presentation |
| --- | --- | --- | --- |
| Presentation `PresentationC2SPacket` (C2S): `HELLO`, `CREATE`, `REMOVE` | Fabric, Forge, and NeoForge common server handler. | The versioned route negotiates presentation sessions, answers `HELLO` with an offer and a server-selected current field mask, and carries marker create/remove intents with the negotiated epoch; a create may carry property selections. Session negotiation, manifest, mask, policy, property, and projection semantics are owned by [presentation snapshot](../presentation/presentation_snapshot.md); accepted intents enter the admission and removal rules owned by [target validation](../authority/target_validation.md); rejections are sent to the requester. | Not applicable on C2S. |
| Presentation `PresentationS2CPacket` (S2C): `OFFER`, `RESET`, `CREATED`, `SECTION`, `REMOVED`, `WINNER`, `REJECT` | Fabric, Forge, and NeoForge common client handler. | Accepted marker creation, removal, and winner changes are projected per recipient; a rejection is sent only to its requester. | Accepted only for the negotiated session epoch/view. The atomic Basic initial, marker content grant, receipt content descriptor, default display reference, retained values, and mask, child-deny and marker-scoped authorization rules are owned by [presentation snapshot](../presentation/presentation_snapshot.md); marker record state and visual lifetime are owned by [client marker state](../markers/client-state.md); rejection presentation remains owned by [ping feedback](../../UI/ping-feedback.md). Corrupt packets, or packets received without a runtime, are safely dropped. |
| Presentation policy `ServerPresentationPolicyC2SPacket` (C2S): `READ`, `ADD_WHITE`, `REMOVE_WHITE`, `ADD_BLACK`, `REMOVE_BLACK`, `SET_WHITELIST_ONLY` | Fabric, Forge, and NeoForge common server handler. | The versioned route carries the correlated read of all five per-target-type rule views, each including its persisted child deny list, and the bounded selector and whitelist-only mutations for a selected target type, which leave the child deny list unchanged. Disclosure, revision, transaction, and broadcast semantics are owned by [presentation snapshot](../presentation/presentation_snapshot.md); authority and server-side enforcement are owned by [server configuration authority](../authority/server-config.md) and [security](../security.md#server-configuration-update-enforcement). | Not applicable on C2S. |
| Presentation policy `ServerPresentationPolicyS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted into the connection-scoped rule-view mirror under the correlation and revision rules owned by [presentation snapshot](../presentation/presentation_snapshot.md); it does not mutate marker state, overlay/outline state, or presentation field values. |
| Inventory `InventoryC2SPacket` (C2S): `HELLO`, `OPEN`, `CLOSE`, `RESYNC`, `SELECT` | Fabric, Forge, and NeoForge common server handler. | `HELLO` creates or renews the per-player preview session and answers with an offer of server-selected periods, the effective display and frame byte budgets, and the inventory target-type policy view; every other kind runs under the session epoch and presentation epoch, with view acceptance and the narrow same-request cancellation exception owned by the [inventory route](#inventory-previewtracking-route-inventory-v2). `OPEN` binds one bounded preview request to a server-validated ordinary block target or uncommitted external provider candidate with its carried real local face, `CLOSE` releases it, `RESYNC` schedules repair for its required marker ID, and `SELECT` resolves one opaque retained entry reference under its baseline/state fences into a correlated selection outcome. The request carries no player identity, item count, or client-supplied authoritative target. Inventory domain semantics are owned by [inventory](../presentation/inventory.md). | Not applicable on C2S. |
| Inventory `InventoryS2CPacket` (S2C): `OFFER`, `POLICY`, `SELECTED`, `REJECT`, `PREVIEW`, `SNAPSHOT`, `STREAM`, `STATUS`, `HEARTBEAT`, `OPENED` | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted into the connection-scoped inventory session only under the negotiated epoch and the current presentation epoch/view: `OFFER` establishes the epoch, the periods, and the effective display and frame byte budgets, `POLICY` carries the inventory target-type policy view, `OPENED` grants one Preview request's lane authorization before any of its `PREVIEW` data, `SELECTED`/`REJECT` are accepted only against a pending commit with the matching request id, `PREVIEW` is request-scoped, and `SNAPSHOT`/`STREAM`/`STATUS`/`HEARTBEAT` are marker-scoped tracking frames. Coverage and pending runtime evidence for native input and HUD consumption of the session are owned by [testing and verification](../../testing/verification/inventory.md). Inventory domain semantics are owned by [inventory](../presentation/inventory.md). |
| Inventory monitor `InventoryMonitorSubscribeC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Carries one positive demand revision and the latest remote raw metric selection. A watch with no selected metrics is valid, and a stop is explicit rather than a zero-metric watch. Subscription changes transfer demand only; it never enables, disables or resets collection. | Not applicable on C2S. |
| Inventory monitor `InventoryMonitorStatusS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Control summary for the current demand, carrying no numeric telemetry. Accepted only under the demand-revision and generation fences in the [monitor route](#inventory-monitor-routes-inventory-monitor-v1); control-state presentation is owned by [settings screen](../../UI/settings-screen.md#performance-monitor-controls), and HUD source-state presentation by [inventory HUD presentation](../presentation/inventory.md#hud-projection-and-presentation). |
| Inventory monitor `InventoryMonitorSampleS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Latest numeric sample. Accepted only after a matching authorized status and generation fences; stale, replayed or unselected values are dropped. Measurement meaning is owned by [inventory performance monitoring](../presentation/inventory.md#performance-monitoring). |
| Presentation preview `PresentationPreviewC2SPacket` (C2S): `READ`, `CANCEL` | Fabric, Forge, and NeoForge common server handler. | A bounded one-shot hint, not a subscription or authority: `READ` asks only for presentation fields the client cannot observe locally and is answered only under the current accepted presentation epoch/view and an accepted adapter schema and field authorization; `CANCEL` withdraws the pending request. It creates no marker, lease, winner slot, or lifetime. Field authorization, provenance, and fallback are owned by [presentation snapshot](../presentation/presentation_snapshot.md). | Not applicable on C2S. |
| Presentation preview `PresentationPreviewS2CPacket` (S2C): `RESULT`, `UNAVAILABLE`, `DEFERRED`, `REJECTED` | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted only against the current request authorization: `RESULT` carries one framed presentation section that stays undecoded until the accepted adapter schema and field authorization are available, and the control statuses carry no section. Corrupt packets are safely dropped. Field authorization, provenance, and fallback are owned by [presentation snapshot](../presentation/presentation_snapshot.md). |
| Server-configuration `ServerConfigRequestC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Carries one positive request identifier and asks for the server's current authoritative configuration snapshot; any connected player may send one. The response is correlated to that identifier. Transaction semantics are owned by [changing server configuration](../config/changing-server-config.md). | Not applicable on C2S. |
| Server-configuration `ServerConfigUpdateC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Carries a changed-field mask and the fixed-shape configuration values as a one-way mutation request with no acknowledgement or update result. Authority and enforcement are owned by [server configuration authority](../authority/server-config.md) and [security](../security.md#server-configuration-update-enforcement); the transaction is owned by [changing server configuration](../config/changing-server-config.md). | Not applicable on C2S. |
| Server-configuration `ServerConfigSnapshotS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted only while the initiating request is still pending on the same connection and the positive identifier matches it; the snapshot covers the complete remote surface and is accepted or rejected as one whole. Transaction semantics are owned by [changing server configuration](../config/changing-server-config.md). |

## Presentation snapshot route (`presentation-v2`)

The presentation snapshot route (`pingforit-c2s:presentation-v2` client-to-server
and `pingforit-s2c:presentation-v2` server-to-client) carries the session
negotiation and marker mutation frames listed above. Every frame declares
protocol version two, and the route ID is version-bound: it does not decode
another presentation wire version, and no fallback reinterprets one.
Session negotiation, manifest, mask, child deny, projection, marker content grant
policy and receipt-descriptor policy remain owned by
[presentation snapshot](../presentation/presentation_snapshot.md); this section
owns the wire grammar and marker-grant replay rule.

The marker snapshot in `CREATED` carries a mandatory logical `targetKey`
immediately after its physical `target`. `WINNER` addresses that same key shape.
Within a BLOCK key, tag `0` is an exact ordinary key, `1` an external stable
key, `2` a double-chest pair, `3` a Vault controller and `4` a Create fluid-tank
controller. Tags `3` and `4` each carry controller X, Y and Z as three fixed-width
32-bit integers followed by the block registry ID; tag `4` requires exactly
`create:fluid_tank`. Grouped key tags are distinct from BLOCK target tags: C2S
targets still permit only ordinary `0` and external `1` and cannot claim a group.
Key identity and target compatibility are owned by the
[target model](../identity/target_model.md#frozen-logical-block-groups).
Unknown tags, noncanonical encodings, malformed pairs, incompatible target/key
carriers (including wrong target kind, dimension or expected registry identity),
missing keys, truncation and trailing bytes reject the whole frame;
the decoder does not normalize a pair or derive a missing key from the target.
Live membership is established by server creation, not rediscovered by the decoder.

Both endpoints require matching presentation version two and the existing
negotiated session fences. A version-one presentation frame cannot negotiate or
mutate this session, and there is no version-one route fallback. This transport
version does not change the Basic adapter schema, inventory, presentation
preview or policy route versions, configuration formats or mod version, and
requires no persistent-data migration.

`RESET` carries the epoch, the view, the field mask, and then the complete
per-target-type child deny map. The map contains exactly every existing target
type, each with at most the per-type child-deny capacity, and every entry is a
nested, unique, grammar-valid adapter/field/literal-record-path reference. A
missing or unknown target type, a duplicate type, a duplicate or root reference,
a malformed or non-canonical reference field, an over-capacity list, and
trailing bytes are rejected rather than normalized, and a missing target type
can never decode as an empty deny list. The mask and the child deny map travel
in one frame and become observable together.

The `RESET` mask and child deny map are the session's current advertised
view. They bound the independent
[target content preview](../presentation/presentation_snapshot.md#target-content-preview)
and its client-side current interaction hints; they are not an input to a
successful Ping's creation-time frozen authorization, which the server resolves
from its own policy, permission and child results without waiting for a `RESET`
or recipient readiness, and they do not re-authorize an existing marker. A
marker's
[frozen recipient content authorization](../presentation/presentation_snapshot.md#frozen-recipient-content-authorization)
is per Ping, so two markers of one target type can carry different frozen sets
and a session-wide mask cannot express them. `CREATED` carries that per-Ping
grant directly; runtime acceptance of the grant and its replay behavior remain
pending and are owned by
[testing and verification](../../testing/verification/presentation.md#frozen-recipient-content-authorization-coverage-and-live-gaps).

The atomic `CREATED` frame carries, in order, the canonical marker snapshot, the
owner name, the default property ref, the marker content grant, the receipt
content descriptor, and the framed Basic section; a `CREATED` frame without the
grant or descriptor is corrupt, and no other frame kind carries either. The
descriptor is one kind tag (`WHOLE`,
`PROPERTIES`, `INVENTORY`, or `SUPPRESSED`) followed by a ref count, that many
property refs in the same bounded shape as a create selection, and, only for
`INVENTORY`, exactly one length-prefixed creation snapshot payload.
`PROPERTIES` requires a non-empty bounded list and carries no snapshot;
`WHOLE` and `SUPPRESSED` require an empty list and carry no snapshot;
`INVENTORY` requires an empty list and exactly one snapshot. The list is unique
and in deterministic sorted order: a duplicate or out-of-order ref, an
over-capacity or malformed ref, a non-canonical number, an unknown kind, a
missing or extra snapshot, and trailing bytes are rejected rather than
normalized. Property descriptors never carry values, annotations, item counts,
content types, or client-uploaded content; those remain in the existing
authorized stores.

The marker content grant is the wire form of that marker's
[frozen recipient content authorization](../presentation/presentation_snapshot.md#frozen-recipient-content-authorization).
The server retains the complete resolved set; the encoded grant is the
recipient's session projection, intersected with that recipient's negotiated
adapter catalogue and accepted schemas and field kinds. The projection can omit
a field the recipient cannot decode, but it never adds a permission and never
replaces the canonical grant. The encoded grant is not the server's current
policy and is not a new `RESET` advertisement. A `CREATED` without the grant is
never completed from the current mask, a prior initial, earlier `SECTION`
values, or the Basic section's fields. A field still requires the recipient's
currently accepted compatible
schema and kind to decode; the grant authorizes content but does not bypass that
compatibility gate. No other `PresentationS2CPacket` kind carries a grant;
`SECTION` is projected only through the retained grant. A replay or baseline
re-delivery of an already-known marker carries the same grant; a changed grant
is rejected without replacing the marker's retained grant or values.

The grant body has this fixed order; it is not independently length-prefixed,
so its declared structure ends where the receipt descriptor begins.

```text
grant     = adapterCount, adapter*, childDenyCount, childDeny*,
            trackingPresent, [trackingKind]
adapter   = adapterId, schema, fieldCount, field*
field     = fieldId, kind
childDeny = adapterId, fieldId, pathDepth, pathKey*
```

`adapterCount` is at most 32 and each adapter is written once in strictly
ascending adapter-ID order; each ID is a grammar-valid namespaced ID of at most
193 characters. `schema` is a canonical varint from 1 through 255. `fieldCount`
is at most 48 per adapter and at most 256 fields are written across the grant;
each adapter's field IDs are strictly ascending, grammar-valid IDs of at most
193 characters. `kind` is the canonical varint ordinal of `TEXT`, `NUMBER`,
`FLAG`, `SEQUENCE`, `RECORD` in that order. `childDenyCount` is at most 256;
each entry is a nested, unique reference in strictly ascending canonical
property-reference order (adapter ID, field ID, the record-path key sequence,
then path length), with a path depth of at most four and nonblank record keys of
at most 128 UTF-8 bytes. A root reference is invalid here. `trackingPresent`
occupies one strict boolean byte; when true, exactly one canonical varint
follows as the tracking-kind wire id (`0` `ITEM`, `1` `FLUID`) and any other
value rejects. The tracking kind records the marker's immutable inventory
domain, not an authorization bit: it remains present when the receipt is
suppressed, and ordinary or Property markers keep it absent even when their
grant includes an inventory field. Zero adapters and zero child denials are
valid and encode an explicit deny-all grant; they are not an absent grant.

Strings use canonical bounded UTF-8: a non-negative length prefix, valid UTF-8
without malformed or unmappable bytes, and the character bound. Over-capacity
counts, a duplicate or out-of-order entry, a noncanonical number, a malformed or
overlong string, an unknown kind or tracking id, truncation, and an invalid
reference reject the whole frame before the receipt or Basic section is read or
applied.

The creation snapshot payload starts with one resource-lane byte (`0` for
`ITEM`, `1` for `FLUID`), followed by the bounded UTF opaque key, resource ID
and label, a strict display-present boolean and the optional display JSON, the
amount as a fixed-width signed non-negative 64-bit long, a strict aggregate
boolean, the required annotation Ping Type ID as bounded UTF, and strict
`previewComplete` and `quantityUncertain` booleans. `ITEM` amount is a count and
`FLUID` amount is exact `U`. An incomplete snapshot with
`quantityUncertain=false`, an unknown lane or annotation, a negative amount, a
non-boolean flag, a JSON/ID mismatch, a malformed, overlong or non-canonical UTF
length, truncation, and trailing payload bytes reject the snapshot and the
frame. The payload is limited to 133120 bytes before allocation, matching the
inventory entry ceiling; its canonical length prefix is part of the receipt
encoding and the unchanged 1048576-byte `CREATED` body ceiling still applies.
The snapshot reader validates display against the closed item or fluid
projection grammar without a native component codec, rich-text decode or
registry lookup. Known Ping Type IDs are accepted structurally; target-specific
annotation eligibility remains server-owned. The frame version remains
presentation version two, and inventory version two, inventory schema two and
the mod version are unchanged.

## Inventory preview/tracking route (`inventory-v2`)

The dedicated inventory route (`pingforit-c2s:inventory-v2` client-to-server
and `pingforit-s2c:inventory-v2` server-to-client) carries preview and tracking
requests and responses, parallel to the presentation snapshot and presentation
policy routes. Every frame declares protocol version two. Both endpoints require
this version; inventory-v1 is not registered as a fallback and its bytes are
never reinterpreted.
The response side carries the effective size budgets.
`HELLO` is the epoch-zero handshake; every other request runs under the
negotiated nonzero epoch and a non-negative request id, and only while the
current presentation epoch/view fence matches, with one exception: a `CLOSE`
for an existing Preview request may carry the current view or an earlier one
when the request belongs to the same authenticated connection and the same
inventory and presentation epochs; a later view is rejected like any other
fenced request. The exception applies only to `CLOSE`; the request ID keeps its
established meaning, and no request changes its target. A same-epoch view
rebuild retains the request, so an in-flight `CLOSE` carrying the previous view
is still honored. `OPEN`, `SELECT`, and `RESYNC` remain strictly bound to the
current view. `OFFER` and `POLICY` are session messages; `OPEN`, `CLOSE`,
`SELECT`, the `OPENED` response, and the `PREVIEW` response are request-scoped;
`RESYNC` and the `SNAPSHOT`, `STREAM`, `STATUS`, and `HEARTBEAT` responses are
marker-scoped.

The resource-lane byte is `0` for `ITEM` and `1` for `FLUID`; any other value
rejects the whole frame. C2S `SELECT` and `RESYNC` carry it immediately after
the common kind/version/epoch/presentation-epoch/view header and before the
request ID. C2S `HELLO`, `OPEN` and `CLOSE` carry no lane byte. Every S2C kind
except the session-wide `OFFER` and the request-scoped `OPENED` carries one
after its common kind/version/epoch/request-ID/presentation-epoch/view header,
including empty data, policy, selection outcomes and status controls. A frame is
homogeneous:
its lane alone chooses the resource registry, amount unit and display decoder;
an entry cannot override that interpretation.

Client requests: `HELLO` is empty. `OPEN(epoch, presentationEpoch, view,
requestId, target, face)` binds a preview request to one bounded
server-validated ordinary block target or one uncommitted external provider
candidate carrying its provider locator, expected registry and block-entity
classification, together with its carried real local face, under a request id
that must advance past the session's last opened preview request id; it is
ignored otherwise, and a request id never changes its target. A committed
external target, an entity or location target, a missing face, an overlong or
non-canonical field, a wrong protocol version, and trailing bytes are rejected
rather than reinterpreted. One `OPEN` serves both independently authorized lanes
of that target. `CLOSE(epoch, requestId)` releases the entire request, not only
one lane. `RESYNC(resourceKind, epoch, requestId, markerId)` schedules
tracked-Ping repair for that marker's lane only; an
unknown marker has no request-preview fallback. After the common kind, version,
epoch, presentation epoch and view header and lane byte, its body is the non-negative
`requestId` as a fixed-width long, a strict one-byte presence boolean of `1`,
and the marker ID as a long. The decoder rejects the frame for a missing, false
or non-boolean presence byte, a missing or truncated marker ID, a negative request
ID, or trailing bytes.
`SELECT(resourceKind, epoch, requestId, commitId, baselineId,
stateRevision, entryKey, pingType)` carries one opaque retained entry reference
and the commit/baseline/state fences; the server derives the authoritative
target and count from its own retained reference and answers `SELECTED` or `REJECT`
correlated by request, commit ID and lane. A successful selection is a Ping
creation and resolves that marker's own
[frozen recipient content authorization](../presentation/presentation_snapshot.md#frozen-recipient-content-authorization),
not an inherited Preview freeze or an older marker's grant; the inventory domain
owns the exact Preview and selection freeze points in
[inventory](../presentation/inventory.md#receiver-authorization-freeze-points).
Commit replay is shared across the
request: changing the lane cannot execute a second selection under an already
used commit ID. A repeated `SELECT` replays its stored outcome instead of
re-running materialization, marker storage or rate charging. Cross-lane entry
references and repair requests never fall back to the item lane.

Server messages: `OFFER(epoch, previewPeriodTicks, trackingPeriodTicks,
resyncMinPeriods, heartbeatPeriods, effectiveDisplayBytes, effectiveFrameBytes)`
establishes the epoch, the server-selected periods, and the effective display
and frame byte budgets, where an explicit zero heartbeat period disables the
periodic heartbeat and a zero resynchronization period disables its cooldown
check. These values use their field-specific validated bounds; preview and
tracking periods remain positive. The two budgets form an exact legal size-grid
pair that keeps the reserved metadata relationship; their persisted form and
relationship remain owned by
[server configuration](../../config/server.md#inventory-policy-object) and
[inventory](../presentation/inventory.md). `POLICY` carries its lane's inventory
target-type policy view.
`OPENED` is the final inventory S2C kind after `HEARTBEAT`; the complete wire
kind order is `OFFER`, `POLICY`, `SELECTED`, `REJECT`, `PREVIEW`, `SNAPSHOT`,
`STREAM`, `STATUS`, `HEARTBEAT`, `OPENED`, and each kind retains its established
encoding. It answers an accepted `OPEN` as the request-scoped Preview grant:
after the common header it carries one lane-bit byte and no resource-lane byte,
marker, entries or other body. Its request id must be positive, and `laneBits`
is one byte from `0` through `3`, where the low two bits independently authorize
the `ITEM` and `FLUID` lanes in wire-id order
(`0` is valid and grants no lane); any other value rejects the frame. The client
accepts a request's `PREVIEW` data only after its `OPENED` grant, so `OPENED`
precedes any `PREVIEW` fragment for that request and a data frame cannot widen
the grant. A repeated `OPENED` with the same session epoch, presentation epoch,
view, request id and lane bits is idempotent; a conflicting repeat is rejected
rather than merged, and the accepted grant neither widens nor narrows. A later
`POLICY` frame updates only the lane policy view and does not revoke, widen or
replace an accepted `OPENED` grant. `OPENED` neither establishes the inventory
session nor replaces the `OFFER` data gate. It is an inventory version-two S2C
kind; no additional version or schema applies.

`PREVIEW`, `SNAPSHOT` and `STREAM` each carry one fragment under an independent
`(resourceKind, epoch, requestId, markerId, baselineId)` identity plus `statusRevision`,
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
`UNCERTAIN`, `INCOMPLETE`, `INVALID` and `COMPONENT_TOO_LONG`. Status and optional
entry quality use UTF names rather than ordinals; `UNAVAILABLE` and `EXPIRED`
are rejected in data-frame status, control-frame status and entry quality alike.
The retained item-named entry fields denote a resource ID, authoritative amount,
resource revision and annotation in their containing lane: `count` is an item
count in `ITEM` and exact `U` in `FLUID`. The fluid unit and closed display
grammar are owned by
[inventory](../presentation/inventory.md#fluid-variants-and-quantities), not an
ItemStack decoder. Checksum domains include the lane, including an empty cut.

`completeScan` describes source-scan completion, not receipt of all
`partIndex`/`partCount` fragments. A marker-scoped selected-preview bootstrap
uses the existing quantity-bearing data frames with `completeScan=false`; its
fully installed transport baseline is still source-incomplete. The first
source-completion barrier, bootstrap eligibility, history and quality are owned
by [inventory tracking](../presentation/inventory.md#tracking); this adds no
field, status value or wire version.

Byte accounting on this route counts the whole encoded inventory frame —
including its header, kind and entry framing — and excludes compression and the
underlying Minecraft transport. The frame has a fixed absolute decode ceiling
and a negotiated server effective cap carried by `OFFER`; the server writes
under its effective display and frame budgets, and the whole-frame,
entry-payload and per-entry bounds are enforced during encoding and rejected
during decoding before any large allocation. An entry display value has its own
absolute decode bound, independent of the effective display budget. The
`inventory-v2` route ID is version-bound: it does not decode another inventory
wire version, and no fallback reinterprets one. Route
registration and grammar remain owned here; the dedicated adapter's delivery
boundary is owned by
[presentation snapshot](../presentation/presentation_snapshot.md#dedicated-delivery-adapters),
and inventory status, zero/fallback, baseline and budget semantics are owned by
[inventory](../presentation/inventory.md).

## Inventory monitor routes (`inventory-monitor-v1`)

The optional monitor family uses three dedicated version-one routes:
`pingforit-c2s:inventory-monitor-subscribe-v1` client-to-server,
`pingforit-s2c:inventory-monitor-status-v1` server-to-client, and
`pingforit-s2c:inventory-monitor-sample-v1` server-to-client. Each route ID is
version-bound: a frame from another monitor wire version is not decoded, and no
other route or version is a fallback. Subscription is latest transmission
demand, never a collection or configuration command; collection is server-owned
and independent of viewers, as recorded in
[inventory performance monitoring](../presentation/inventory.md#performance-monitoring).
Numeric telemetry authority is owned by
[server configuration authority](../authority/server-config.md#remote-performance-telemetry),
metric selection and its string selector grammar by
[client configuration](../../config/client.md#performance-monitor),
control presentation by
[settings screen](../../UI/settings-screen.md#performance-monitor-controls), and
HUD projection and presentation by [inventory HUD
presentation](../presentation/inventory.md#hud-projection-and-presentation).
This section owns the wire grammar and acceptance.

The three bodies have this fixed order:

```text
SUBSCRIBE = version, revision, watching, metricCount, metricWireId*
STATUS    = version, revision, serverGeneration, collectionGeneration,
            permissionGeneration, authorized, sampleCapability,
            previewEnabled, trackingEnabled
SAMPLE    = version, revision, serverGeneration, collectionGeneration,
            permissionGeneration, sequence, firstTick, lastTick, elapsedNs,
            regionCount, region*
REGION    = scopeId, timingCount, (phaseId, totalNs, count, maxNs)*,
            counterCount, (counterId, value, saturated)*,
            gaugeCount, (gaugeId, value)*,
            budgetCount, (budgetId, available, periodId, limit, used,
                          reserved, remaining, admissibleRemaining)*,
            memoryPresent, [retained, reserved, cap, remaining]
```

`version` is `1` in every monitor body. `revision`, `serverGeneration`,
`collectionGeneration`, `permissionGeneration` and `sequence` are positive
canonical varlongs; counts, IDs and all other numbers are canonical nonnegative
varints or varlongs. The two `remaining` fields are fixed-width signed 64-bit
big-endian values and may be negative, including the minimum value. Every boolean
is exactly one byte, `0` or `1`. A zero is valid for `firstTick`, `lastTick`,
`elapsedNs`, timing totals, counts and peaks, and counter and gauge values;
`lastTick` must not precede `firstTick`, and zero elapsed is valid warm-up.
`memoryPresent` is a strict boolean, and its four fields are present exactly when
it is true; a false `memoryPresent` has no memory fields.

The closed wire identities are explicit values, not enum ordinals:

| Scope ID | Scope | Wire use |
| ---: | --- | --- |
| 1 | `local.preview` | Client-only; rejected in a monitor frame |
| 2 | `local.tracking` | Client-only; rejected in a monitor frame |
| 3 | `server.preview` | Accepted in a region |
| 4 | `server.tracking` | Accepted in a region |
| 5 | `shared` | Accepted in a region |

| Phase ID | Phase | Allowed remote scopes |
| ---: | --- | --- |
| 1 | ingress | 3, 4 |
| 2 | tick_work | 3, 4 |
| 3 | capture | 3, 4 |
| 4 | read | 3, 4 |
| 5 | detached_encode | 3, 4 |
| 6 | measure | 3, 4 |
| 7 | publish | 3, 4 |
| 8 | apply | None; client-only |
| 9 | prepare | None; client-only |
| 10 | draw | None; client-only |

| Counter ID | Counter | Allowed remote scopes |
| ---: | --- | --- |
| 1 | requests.received | 3, 4 |
| 2 | requests.admitted | 3, 4 |
| 3 | work.consumers | 3, 4 |
| 4 | work.defer_attempts | 3, 4, 5 |
| 5 | work.logical_slots | 3, 4 |
| 6 | sync.publications_installed | 3, 4 |
| 7 | sync.cuts_completed | 3, 4 |
| 8 | sync.frames_preview | 3 |
| 9 | sync.frames_snapshot | 4 |
| 10 | sync.frames_stream | 4 |
| 11 | sync.budget_charged_bytes | 3, 4 |
| 12 | sync.handoff_bytes | 3, 4 |
| 13 | sync.handoff_frames | 3, 4 |
| 14 | sync.not_handed_off_frames | 3, 4 |
| 15 | sync.unobserved_handoff_frames | 3, 4 |
| 16 | sync.unobserved_handoff_candidate_bytes | 3, 4 |
| 17 | apply.accepted | None; client-only |
| 18 | apply.rejected | None; client-only |
| 19 | sync.repair_requests | None; client-only |
| 20 | work.read_attempts | 5 |
| 21 | work.read_failures | 5 |

| Gauge ID | Gauge | Allowed remote scopes |
| ---: | --- | --- |
| 1 | requests.open | 3, 4 |
| 2 | queue.logical_cost | 3, 4 |
| 3 | queue.cap | 3, 4 |
| 4 | queue.pending_entries | 3, 4 |
| 5 | queue.admitted_frames | 3, 4 |
| 6 | state.channels | None; client-only |
| 7 | state.sessions | 5 |
| 8 | buffer.frames | None; client-only |
| 9 | buffer.entries | None; client-only |
| 10 | buffer.estimated_bytes | None; client-only |
| 11 | state.consumers | 5 |
| 12 | state.active_observations | 5 |
| 13 | memory.outstanding_leases | 5 |

| Budget ID | Budget | Allowed remote scopes |
| ---: | --- | --- |
| 1 | logical_slots | 3, 4 |
| 2 | wire_bytes | 3, 4 |
| 3 | physical_slots | 5 |
| 4 | capture_reads | 5 |
| 5 | probes | 5 |
| 6 | provider_work | 5 |
| 7 | processing_charge | 5 |
| 8 | publication_work | 5 |

A raw metric's wire ID is derived from its scope and leaf ID:

```text
timing        = 1000 + 100*scopeId + 2*phaseId
timing.max    = 1001 + 100*scopeId + 2*phaseId
counter       = 2000 + 100*scopeId + counterId
gauge         = 3000 + 100*scopeId + gaugeId
budget        = 4000 + 100*scopeId + budgetId
shared.memory = 5001
```

Summary selectors are client-side groupings owned by
[client configuration](../../config/client.md#performance-monitor); they expand
on the client and are never transmitted. A watch expands selected summaries,
removes `LOCAL` entries, collapses duplicates and sorts the remaining known
remote raw IDs ascending. The closed remote raw catalogue has 85 entries, which
bounds `metricCount`; a summary, local, unknown or non-ascending ID rejects the
frame rather than being normalized.

The whole-body decode ceilings are 4096 bytes for `SUBSCRIBE`, 128 for `STATUS`
and 16384 for `SAMPLE`, checked before list allocation; the sample encoder also
enforces its ceiling. Exact-end is required. Counts are bounded by the closed
catalogue sizes (10 phases, 21 counters, 13 gauges and 8 budgets), and every
entry must support its region's scope. Regions are at most three, ordered by
ascending scope, at most one per scope, and at least one is present. `SHARED`
timing count is zero; lane regions carry `memoryPresent=false`, while `SHARED`
may carry memory. Timing `totalNs`, `count` and `maxNs` are nonnegative;
`count=0` requires both other values to be zero, `maxNs` may not exceed
`totalNs`, and `ceil(totalNs/count)` may not exceed `maxNs` when the count is
positive. A saturated counter is exactly `Long.MAX_VALUE`; a false saturation
flag does not require that value. Budget `periodId`, `limit`, `used`, `reserved`
and `admissibleRemaining` are nonnegative; an unavailable budget carries zero in
all six numeric fields; `admissibleRemaining` may not exceed
`max(0, remaining)`. Memory `retained`, `reserved` and `cap` are nonnegative, and
`remaining` must equal `cap - retained - reserved` exactly, without overflow.
Unknown IDs, a
leaf/scope mismatch, an unknown capability tag, a non-strict boolean,
noncanonical or overflowing numbers, missing or truncated fields, and trailing
bytes reject the whole frame; safe decoding drains and drops it.

`revision` is the client's positive latest-demand revision and strictly
increases per connection. Gaps are valid, and an equal or lower revision cannot
replace the accepted demand. `watching=true` may carry zero metrics: that watch
receives the control `STATUS` but requests no numeric `SAMPLE`. `watching=false`
is an explicit stop and requires `metricCount=0`; it unregisters the watch,
produces no further `STATUS` or `SAMPLE` for that demand, and retains its
revision as a per-connection tombstone so a repeated or lower demand cannot
re-register. A higher revision may watch again. A stop with metric IDs or any
invalid metric list rejects the frame. Disconnect, connection replacement and
server lifecycle reset clear the tombstone and all monitor state, so the next
connection has no prior demand.

A `STATUS` reports the current control summary for the echoed revision.
`authorized` is the current numeric telemetry authority, and
`permissionGeneration` changes with it. `previewEnabled` and `trackingEnabled`
report the server collection lanes, not client metric selection.
`sampleCapability` is the tag `0` for `UNKNOWN`, `1` for `AVAILABLE` and `2` for
`UNAVAILABLE`; `UNKNOWN` is an unresolved capability, `UNAVAILABLE` is an
observed absent `SAMPLE` route, and neither permits numeric data. An absent
optional monitor family is unsupported, while a present family without an
accepted status remains unknown; no other route or wire version supplies that
state. The control `STATUS` remains available to any authenticated watching
client; numeric `SAMPLE` is administrator-only and rechecked before numeric
handoff, as owned by
[server configuration authority](../authority/server-config.md#remote-performance-telemetry).

A client accepts a `STATUS` only for the current connection and demand
revision, with positive generations, and only while its server, collection and
permission generations do not move backward within their epochs. A
server-generation change starts new collection and permission epochs; otherwise
collection and permission may not move backward. A conflicting `authorized`,
`previewEnabled` or `trackingEnabled` value at unchanged generations rejects the
frame. An accepted status with `authorized=false`, a sample capability other
than `AVAILABLE`, or both lanes disabled clears numeric values but remains a
valid control status.

A client accepts a `SAMPLE` only after an accepted `STATUS` for the same
connection and demand revision, with numeric authorization, `AVAILABLE` sample
capability, at least one enabled collection lane, and at least one selected
server or shared metric. The sample's server, collection and permission
generations must match that status, its `sequence` must be positive and strictly
greater than the last accepted sequence under the current status generations.
Each present lane region must match a selected lane demand and enabled lane, and
a shared region must be selected. A sample before that status, before
authorization, after stop or reset, from a replaced connection, with a stale
generation or replayed sequence, or with an unselected or disabled region is
dropped, never applied. Numeric data cannot establish
authorization. Queued callbacks are bound to the connection that received them
and cannot apply to a replacement connection.

Samples are latest-only. The server projects only readings implied by the
current raw selection and enabled lanes; unobserved fields are omitted,
unavailable budgets are not projected, and a lane with no selected reading is
omitted. Delivery cadence and coalescing are implementation scheduling; the
protocol promises no fixed interval, retained history, retry or acknowledgement.
A sample carries no string labels or units.

## Presentation preview route (`presentation-preview-v1`)

The dedicated presentation preview route (`pingforit-c2s:presentation-preview-v1`
and `pingforit-s2c:presentation-preview-v1`) carries the wheel's one-shot
content hint and its response, parallel to the presentation snapshot and
presentation policy routes. Every frame declares protocol version one and
is bound to the current accepted presentation epoch and view plus a positive
request id. `READ` carries one target, its target type, one adapter id, and the
non-empty requested field ids; it asks only for fields the client cannot
observe locally and is not a subscription, capability grant, or authority.
`CANCEL` withdraws the pending request. The dedicated inventory adapter is
excluded from this route and keeps its own route.

The server answers only under fresh authorization for the same epoch, view,
and target type, and only for an accepted adapter whose accepted fields contain
every requested field. The accepted mask intersection, advertised/fresh child
denial union, and promotion after a new `RESET` remain the no-Ping preview
authorization owned by
[presentation snapshot](../presentation/presentation_snapshot.md#target-content-preview).
`RESULT` carries one framed presentation section;
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

## Server-configuration routes (`server-config-request-v5`, `server-config-update-v5`, `server-config-snapshot-v5`)

The server-configuration surface travels on three dedicated version-five route
IDs (`pingforit-c2s:server-config-request-v5`,
`pingforit-c2s:server-config-update-v5`, and
`pingforit-s2c:server-config-snapshot-v5`), separate from the presentation policy
route and the inventory routes.
The request and update routes are client-to-server; the snapshot route is
server-to-client. A request carries one positive request identifier and asks
for the server's current authoritative values; the snapshot echoes that
identifier and is correlated to the still-pending request on the same
connection. An update carries a changed-field mask and the fixed-shape
configuration values as a one-way mutation request with no acknowledgement or
update result.

The complete packet bodies have this fixed order:

```text
REQUEST  = requestId
UPDATE   = changedFields, defaultChannelMode, playerTrackingEnabled,
           msToRegenerate, rateLimit, syncDuration, inventory,
           budgetDelayNotificationsEnabled, loggingProfile,
           previewMonitorEnabled, trackingMonitorEnabled
SNAPSHOT = requestId, canEdit, defaultChannelMode, playerTrackingEnabled,
           msToRegenerate, rateLimit, syncDuration, inventory,
           budgetDelayNotificationsEnabled, loggingProfile,
           previewMonitorEnabled, trackingMonitorEnabled
```

`requestId` is a positive canonical varlong. `changedFields` is a canonical
nonnegative varlong carrying the field mask; its defined bits are owned by
[changing server configuration](../config/changing-server-config.md#update-surface).
The channel-mode ordinal (`AUTO`, `DISABLED`,
`GLOBAL`, `TEAM_ONLY` in that order), the original numeric settings and
`loggingProfile` are canonical non-negative varints. The logging profile tags
are `0` for `NORMAL`, `1` for `DEEP`, and `2` for `DEBUG`; any other tag is
invalid. Booleans occupy exactly one byte, `0` or `1`. `inventory` supplies all
leaves, regardless of the update mask, in this order:

```text
physicalSlotsPerTick, pendingMemoryMiB,
preview.periodTicks, preview.maxVariantsPerClientPeriod,
preview.maxSlotsPerClient, preview.maxSlotsServer, preview.maxTargetsPerClient,
preview.clientByteMultiplier, preview.globalByteMultiplier,
tracking.periodTicks, tracking.maxVariantsPerTarget,
tracking.maxSlotsPerTarget, tracking.maxSlotsServer,
tracking.streamByteMultiplier, tracking.snapshotByteMultiplier,
tracking.globalByteMultiplier, tracking.resyncMinPeriods,
tracking.heartbeatPeriods, tracking.gracePeriods,
displayByteMultiplier, frameByteMultiplier,
tracking.distinguishFaces, tracking.distinguishPlayers
```

Each inventory leaf that supports unlimited mode carries its strict boolean
flag followed by a varint retaining its finite value; finite-only numeric leaves
carry only their varint. The two final inventory leaves each carry exactly one
strict boolean byte, with no numeric value or unlimited flag.
Integer leaves encode their integer value, send-byte multipliers encode exact
grid quanta, and the two size scalars encode positive quarter-multiplier quanta.
UI-only step sizes for integer leaves do not restrict wire values.
Legal numeric forms and relationships belong to
[server configuration](../../config/server.md#inventory-policy-object).
The strict `budgetDelayNotificationsEnabled` byte follows the complete
inventory block in both UPDATE and SNAPSHOT, and the `loggingProfile` tag
follows that byte. The two monitor booleans follow `loggingProfile` in the same
Preview, Tracking order in both bodies; each is a strict one-byte `0` or `1`
and is carried regardless of the field mask. A SNAPSHOT whose logging profile
is not `DEBUG` must carry both monitor booleans as `false`; a true value rejects
that frame because the snapshot reports normalized authoritative state rather
than a pre-normalization candidate. REQUEST has no added field.

Overflow, overlong or non-canonical numbers, a negative or overflowing mask,
unknown mask bits, invalid enum or inventory values, invalid logging-profile
tags, missing fields, non-boolean bytes, and trailing bytes are rejected in full
before any value is applied. Exact-end validation follows the second monitor
boolean. A missing or invalid profile tag rejects the whole frame even when the
update mask does not select the profile; a missing or invalid monitor boolean
rejects it even when its bit is unselected. An UPDATE or SNAPSHOT without either
Tracking partition byte, the memory mode byte, `budgetDelayNotificationsEnabled`,
either monitor boolean, or the logging-profile tag is corrupt rather than
defaulted or accepted as a prefix; safe rejection drains the frame. All three
route IDs are version-bound, including the unchanged request body. There is one
current version-five body shape: an earlier unreleased development version-five
shape is not accepted, a released version-four payload is not parsed as version
five, and a frame is never completed from its remaining bytes or a default
monitor value. Other server-configuration route versions are not fallbacks, and
payloads are never reinterpreted across route versions.
Remote settings GUI endpoints must match this
configuration protocol; this does not change the independent presentation,
inventory, preview or presentation-policy routes, persisted configuration version
or mod version. The fixed remote surface is not whole-`ServerConfig` JSON on the
wire. Route registration and grammar
remain owned here; the disclosed field catalogue is owned by
[server configuration](../../config/server.md), the transaction, correlation,
field mask, merge and apply rules by
[changing server configuration](../config/changing-server-config.md), and
authority and enforcement by
[server configuration authority](../authority/server-config.md) and
[security](../security.md#server-configuration-update-enforcement).

## Route effects and channel establishment

`UpdateChannelC2SPacket` establishes the server-stored
channel that marker creation consumes; a create request does not carry its own
channel.
Channel operations bypass the create-only courtesy gate; their rate and policy
behavior is owned by [rate policy](../config/rate-limit.md).

## Authority and feedback boundaries

The authoritative creation order and exact audience matrix are owned by
[target validation](../authority/target_validation.md). In particular, the
client's inability to provide a marker-create channel or audience applies to
the negotiated presentation `CREATE` and inventory `SELECT` intents.

Rejection presentation and the eligible-message rule are owned by
[ping feedback](../../UI/ping-feedback.md).

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
[testing and verification](../../testing/verification/README.md).
