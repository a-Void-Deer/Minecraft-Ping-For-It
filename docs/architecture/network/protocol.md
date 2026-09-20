# Network protocol: authoritative markers and legacy locations

This topic owns the logical packet families, registered ingress routes, and
current client packet acceptance for marker authority. It records packet
semantics and the boundary between the authoritative marker family and the
legacy location family; it is not a wire-format catalogue and does not promise
interoperability with the original mod. The supported loader set and the
original-mod compatibility policy are owned by [compatibility](../../compatibility.md).

## Registered ingress and current effects

All three supported loader entry points—Fabric, Forge, and NeoForge—register the
legacy location and authoritative marker routes and dispatch them through the
common handlers. A route being registered means this codebase can receive its
current packet family; it does not make the legacy packet family an
authoritative marker protocol.

| Packet family and direction | Registered route | Server-side effect | Current client acceptance and presentation |
| --- | --- | --- | --- |
| Legacy `PingLocationC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Reads the **packet's** channel, applies the legacy rate and empty-channel policy, may update the sender's stored channel to that packet value, and forwards its paired legacy `PingLocationS2CPacket` to matching recipients. It does not create an authoritative marker record or enter the authoritative marker store. | Not applicable on C2S. Its paired legacy S2C route is listed below. |
| Legacy `PingLocationS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | A corrupt packet is warned about; a valid packet is deliberately ignored. It does not mutate marker state, overlay/outline state, or presentation state. A valid ignored packet is not an accepted presentation update. |
| Authoritative `MarkerCreateC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | The request carries a correlation request ID, captured stable target identity, and the selected Ping Type ID. It carries no client channel or audience, Target Type, authoritative name or color, owner, arrival, or lifetime; the server repeats classification and derives those from authoritative state. Admission and rejection order are owned by [target validation](../authority/target_validation.md); rejections are sent to the requester. | Not applicable on C2S; accepted state is delivered through the authoritative S2C marker packets below. |
| Authoritative marker S2C packets: `MarkerCreated`, `MarkerRemoved`, `MarkerWinnerChanged`, and `MarkerRejected` | Fabric, Forge, and NeoForge common client handlers. | Accepted creates and authoritative marker-store removal or winner changes emit the corresponding messages; a rejection is sent only to its requester. | A valid packet is applied only while a client runtime exists. Created, removed, and winner messages apply through the [client-state](../markers/client-state.md) contract; rejection processing is limited by its request tracker and its presentation is owned by [ping feedback](../../UI/ping-feedback.md). Corrupt packets, or packets received without a runtime, are safely dropped. |

## Route effects and channel establishment

`UpdateChannelC2SPacket` is the ordinary way to establish the server-stored
channel that `MarkerCreate` consumes; a create does not carry its own channel.
Channel operations bypass the create-only courtesy gate; their rate and policy
behavior is owned by [rate policy](../config/rate-limit.md). Conversely, the
legacy location handler's retained in-packet channel behavior must not be used
to weaken the authoritative marker contract.

## Authority and feedback boundaries

The authoritative creation order and exact audience matrix are owned by
[target validation](../authority/target_validation.md). In particular, the
client's inability to provide a marker-create channel or audience applies only
to `MarkerCreateC2SPacket`, not to legacy location ingress.

Rejection presentation and the eligible-message rule are owned by
[ping feedback](../../UI/ping-feedback.md). Legacy valid location packets have no
marker presentation path to which that rule could apply.

## Evidence

Existing coverage inventory and remaining integration gaps are owned by
[testing and verification](../../testing/verification.md).
