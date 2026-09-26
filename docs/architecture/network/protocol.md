# Network protocol: authoritative markers and legacy locations

This topic owns the logical packet families, registered ingress routes, and
current client packet acceptance for marker authority. It records packet
semantics and the boundary between the authoritative marker family and the
legacy location family; the versioned presentation snapshot and presentation
policy routes it registers are owned by
[presentation snapshot](../presentation/presentation_snapshot.md).
It is not a wire-format catalogue and does not promise interoperability with the
original mod. The supported loader set and the original-mod compatibility policy
are owned by [compatibility](../../compatibility.md).

## Registered ingress and current effects

All three supported loader entry points—Fabric, Forge, and NeoForge—register the
legacy location route, the superseded authoritative marker routes, the
versioned presentation snapshot route, and the presentation policy route, and
dispatch them through the common handlers. A
route being registered means this codebase can receive its current packet
family; it does not make the legacy packet family an authoritative marker
protocol, and registering a superseded route does not re-enable its effect.

| Packet family and direction | Registered route | Server-side effect | Current client acceptance and presentation |
| --- | --- | --- | --- |
| Legacy `PingLocationC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handler. | Reads the **packet's** channel, applies the legacy rate and empty-channel policy, may update the sender's stored channel to that packet value, and forwards its paired legacy `PingLocationS2CPacket` to matching recipients. It does not create an authoritative marker record or enter the authoritative marker store. | Not applicable on C2S. Its paired legacy S2C route is listed below. |
| Legacy `PingLocationS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | A corrupt packet is warned about; a valid packet is deliberately ignored. It does not mutate marker state, overlay/outline state, or presentation state. A valid ignored packet is not an accepted presentation update. |
| Superseded authoritative `MarkerCreateC2SPacket` and `MarkerRemoveC2SPacket` (C2S) | Fabric, Forge, and NeoForge common server handlers remain registered. | Disabled no-ops: they perform no marker mutation. Marker create and remove requests enter only through the negotiated presentation `CREATE`/`REMOVE` intents below. | Not applicable on C2S. |
| Superseded marker S2C packets: `MarkerCreated`, `MarkerRemoved`, `MarkerWinnerChanged`, and `MarkerRejected` | Fabric, Forge, and NeoForge common client handlers remain registered. | No server ingress effect. | Registered no-ops: no marker mutation and no client display values are sourced from this family. |
| Presentation `PresentationC2SPacket` (C2S): `HELLO`, `SUBSCRIBE`, `CREATE`, `REMOVE` | Fabric, Forge, and NeoForge common server handler. | The versioned route negotiates presentation sessions and carries marker create/remove intents with the negotiated epoch. Session negotiation, manifest, policy, and projection semantics are owned by [presentation snapshot](../presentation/presentation_snapshot.md); accepted intents enter the admission and removal rules owned by [target validation](../authority/target_validation.md); rejections are sent to the requester. | Not applicable on C2S. |
| Presentation `PresentationS2CPacket` (S2C): `OFFER`, `RESET`, `CREATED`, `SECTION`, `REMOVED`, `WINNER`, `REJECT` | Fabric, Forge, and NeoForge common client handler. | Accepted marker creation, removal, and winner changes are projected per recipient; a rejection is sent only to its requester. | Accepted only for the negotiated session epoch/subscription/view. The atomic Basic initial, retained values, receive/display policy, and legacy-name boundary are owned by [presentation snapshot](../presentation/presentation_snapshot.md); marker record state and visual lifetime are owned by [client marker state](../markers/client-state.md); rejection presentation remains owned by [ping feedback](../../UI/ping-feedback.md). Corrupt packets, or packets received without a runtime, are safely dropped. |
| Presentation policy `ServerPresentationPolicyC2SPacket` (C2S): `READ`, `ADD_WHITE`, `REMOVE_WHITE`, `ADD_BLACK`, `REMOVE_BLACK`, `SET_WHITELIST_ONLY` | Fabric, Forge, and NeoForge common server handler. | The versioned route carries the correlated rule-view read and the bounded selector and whitelist-only mutations. Disclosure, revision, transaction, and broadcast semantics are owned by [presentation snapshot](../presentation/presentation_snapshot.md); authority and server-side enforcement are owned by [server configuration authority](../authority/server-config.md) and [security](../security.md#server-configuration-update-enforcement). | Not applicable on C2S. |
| Presentation policy `ServerPresentationPolicyS2CPacket` (S2C) | Fabric, Forge, and NeoForge common client handler. | No server ingress effect. | Accepted into the connection-scoped rule-view mirror under the correlation and revision rules owned by [presentation snapshot](../presentation/presentation_snapshot.md); it does not mutate marker state, overlay/outline state, or presentation field values. |

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
