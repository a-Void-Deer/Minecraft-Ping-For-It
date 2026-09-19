# Network ingress: authoritative markers and legacy locations

This topic owns the narrow protocol boundary relevant to marker authority. It
records current registered routes, their server effects, and what the current
client accepts for presentation. It is not a wire-format catalogue and does not
promise interoperability with the original mod; the product's explicit
no-compatibility boundary remains in [the specification](../../spec.md).

## Registered ingress and current effects

All three supported loader entry points—Fabric, Forge, and NeoForge—register
the legacy location and authoritative marker routes in this table and dispatch
them through the common handlers. A route being registered means this codebase
can receive its current packet family; it does not make the legacy packet family
an authoritative marker protocol.

| Packet family and direction | Registered common route | Server-side effect | Current client acceptance and presentation |
| --- | --- | --- | --- |
| Legacy `PingLocationC2SPacket` (C2S) | Fabric, Forge, and NeoForge dispatch through `CommonServer.onPingLocationPacket` to `ServerCore.onPingLocation`. | The handler reads the **packet's** channel, applies the legacy rate and empty-channel policy, may update the sender's stored channel to that packet value, and forwards a `PingLocationS2CPacket` to matching recipients. It does not call `MarkerCreationService`, create a `ServerMarker`, or enter the authoritative marker store. | Not applicable on C2S. Its paired legacy S2C route is listed below. |
| Legacy `PingLocationS2CPacket` (S2C) | Fabric, Forge, and NeoForge dispatch to `CommonClient.onPingLocationPacket`. | No server ingress effect. | A corrupt packet is warned about; a valid packet is deliberately ignored. It does not mutate `ClientPingRuntime`, marker state, overlay/outline state, or render presentation. |
| Authoritative `MarkerCreateC2SPacket` (C2S) | Fabric, Forge, and NeoForge dispatch through `CommonServer.onMarkerCreatePacket` to `ServerCore.onMarkerCreate`. | The packet has no channel. After safe decoding and structural validation, the server rate check, stored-channel policy, and recipient snapshot run before `MarkerCreationService` performs authoritative target/range validation, reclassification, Ping Type validation, and possible marker-store creation. Rejections are sent to the requester. | Not applicable on C2S; accepted state is delivered through the authoritative S2C marker packets below. |
| Authoritative marker S2C packets: `MarkerCreated`, `MarkerRemoved`, `MarkerWinnerChanged`, and `MarkerRejected` | Fabric, Forge, and NeoForge dispatch to their corresponding `CommonClient` handlers. | `MarkerCreated` and winner/removal packets are emitted from authoritative marker-store outcomes; a rejection is sent only to its requester. | A valid packet is applied only while a client runtime exists. Created, removed, and winner packets mutate the runtime's synchronized marker state; rejection processing is limited by its request tracker. Corrupt packets, or packets received without a runtime, are safely dropped. |

`UpdateChannelC2SPacket` remains the ordinary channel-update path and establishes
the stored channel used by `MarkerCreate`; it is not evidence that a
`MarkerCreate` itself supplies a channel. Conversely, the legacy location
handler's retained in-packet channel behavior must not be used to weaken the
authoritative marker contract.

## Authority and feedback boundaries

The authoritative creation order and exact audience matrix are owned by
[target validation](target_validation.md). In particular, client inability to
provide a marker-create channel or audience applies only to
`MarkerCreateC2SPacket`, not to legacy location ingress.

The client does not translate every rejection into visible feedback. A server
rejection may show the local invalid-target message only when it is
`TARGET_GONE` for the latest actually dispatched authoritative create; this is
defined by [target validation](target_validation.md#server-responses-and-silent-outcomes).
Legacy valid location packets have no marker presentation path to which that
rule could apply.

## Evidence boundary

The current common tests include safe/corrupt marker-packet codec coverage
(`MarkerPacketsTest` and `PacketHandlerTest`),
`MarkerCreationService` ordering/outcome seams,
`ServerMarkerStore` lifecycle/audience seams, and the latest-create-request
tracker (`CreateRequestTrackerTest`). No test named for legacy S2C packet
ignoring or loader packet registration was found in the current test source
sets. The cited common tests therefore do not establish a live cross-loader
network session, complete loader registration behavior, or a runtime assertion
that a valid legacy S2C packet leaves every presentation-state object untouched.
Existing automated-coverage inventory and remaining integration gaps are owned
by [testing and verification](../testing/verification.md); this statement is an
evidence boundary, not a claim that those tests ran for this documentation
change.
