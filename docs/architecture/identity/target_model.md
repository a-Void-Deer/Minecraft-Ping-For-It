# Target model and stable identity

## Domain terms

- A `Target` is the concrete entity, block or pure location resolved by the
  capture associated with the initial ping-key press. Capture may complete
  asynchronously or start through the narrow deferred-compatible path in
  [long-press compatibility](../input/long-press-compatibility.md).
- A `Target Type` is a code-defined matcher; a `Resolved Target` is the captured
  target plus the winning Target Type.
- A `Ping Type` is a predefined semantic choice for chat, outline color, text
  emphasis, wheel presentation and optional icon.
- A `Marker` is an active ping with stable ID, owner, target identity, selected
  Ping Type and lifecycle data.

These stable concepts are separate from live renderable geometry and
[presentation subjects](../rendering/presentation_subjects.md). The
[geometry pipeline](../geometry/geometry-pipeline.md) describes their data
flow; [D0003](../../decisions/D0003-multipart-presentation-types.md) explains the
identity/presentation boundary.

## Ordinary identities and lifecycle

Entity identity uses a server-canonical tagged locator plus dimension. The
locator may be a UUID or a runtime ID. A runtime-ID request is valid only for an
Experience Orb; a runtime-ID request for any other entity is rejected. After
resolving the actual entity, the server normalizes its locator to the actual
runtime ID for an Experience Orb and to the actual UUID for every other entity.
Movement or same-dimension teleportation does not change identity. A dimension
change, death, disappearance or absence at authoritative marker creation makes
the target invalid.

The physical block target is dimension, hit block position and block type. A
BlockState or property change with the same block type remains valid at marker
creation; replacement by a different type is invalid. `TargetKey.from` derives
this exact physical identity; it performs no grouping or world lookup.

### Frozen logical block groups

An accepted marker carries its physical `Target.BlockTarget` and anchor separately
from its server-resolved logical `TargetKey`. Supported container groups use:

| Key | Identity |
| --- | --- |
| `DoubleChestKey` | Dimension, exact `minecraft:chest` or `minecraft:trapped_chest` registry ID, and both horizontally adjacent member positions in strict lexicographic X/Y/Z order. |
| `VaultKey` | Dimension, exact `create:item_vault` registry ID, and verified controller position. Extent and member list are not key fields. |

A detached grouped carrier requires an ordinary block target with matching
dimension and registry identity. A chest target must be one of the pair's two
positions; a Vault carrier contains no member bounds, so only authoritative
creation establishes its live membership. Other key variants must equal the
physical derivation. Carrier compatibility never certifies current topology.

The grouping resolution point is owned by
[create adjudication](../authority/target_validation.md#create-adjudication-order).
Vanilla chest relationship evidence is owned by
[container presentation](../rendering/presentation_subjects.md#vanilla-chests);
the supported Vault topology and loader gates by
[Create](../../integrations/create.md#vault-logical-target-and-presentation-topology).
Other ordinary blocks retain their exact physical key. Beds and doors expand
presentation only and keep the single-hit logical key. External targets retain
their provider-stable identity and bypass ordinary-world group lookup; this is
not arbitrary multiblock discovery.

Grouping never replaces the original hit with a controller or sibling target,
moves its anchor, or merges marker records, including records from one owner.
The group key is frozen at creation: later splits, joins, controller changes or
unavailable metadata do not rekey an existing marker or resize its lifetime.
New markers resolve the structure present at their own creation. Current client
presentation may follow a currently verified structure without changing that
frozen key or granting inventory authority. Rationale is recorded in
[D0016](../../decisions/D0016-logical-container-targets.md).

### Committed marker lifecycle

After an ordinary entity or block marker is committed it is not continuously
server-revalidated: it remains until normal removal or expiry. World/HUD marker
visuals are skipped when the target is in another dimension. That visual filter
does not remove the marker or by itself filter receipt chat; the sound/chat
trigger and dimension distinction is owned by
[names and chat](../rendering/names_chat.md#new-marker-feedback-and-dimension-behavior).
An unavailable entity may use its last or authoritative anchor. An ordinary
committed block replacement does not itself remove the marker; the renderer uses
the current block render state. Explicitly supported external-target markers
retain their established periodic invalidation exception. Pure locations retain
existing location-ping semantics.

Pre-commit validation is
owned by [target validation](../authority/target_validation.md); the local
invalid-target message is owned by [ping feedback](../../UI/ping-feedback.md).

## Marker data versus current presentation

Canonical marker identity, lifecycle and HUD data are separate from the
current-frame presentation. The committed marker retains its ID, owner,
concrete physical target, frozen logical key, Target/Ping Types and lifecycle
state, together with the authoritative name data used by HUD and chat, until the
normal marker lifecycle removes or expires it. Presentation resolution may nevertheless
produce no current subject; that absence does not mean that the marker or its
HUD data has been removed. The server record lifetime is owned by
[marker lifecycle](../authority/marker_lifecycle.md); the client record and
display state is owned by
[client marker state](../markers/client-state.md).

For a committed ordinary block, a same-registry-ID BlockState or property
change keeps the target valid and resolves presentation from the current live
state. The normal sources and VoxelShape fallback then consume that current
subject. If the block is replaced by a different registry ID, the committed
marker data and lifetime are not removed solely by that replacement, but
presentation resolution returns no replacement subject. Consequently neither
the ordinary native block outline nor the VoxelShape fallback draws the
replacement block. “No marker” and “no current outline/presentation” are
therefore distinct outcomes.

## Entity-local capture metadata

Entity-local geometry detail is capture metadata only; retain it in the frozen
context only if it belongs to the matching resolved entity identity, and it
does not rewrite the whole-entity Target, marker identity or packet shape. See
[local geometry](../picking/local_geometry.md).

## External-block identity

The common external-block model, currently used by
[Sable](../../integrations/sable.md), distinguishes a create candidate from a
committed target:

- An uncommitted candidate has an empty stable target ID. It cannot become a
  committed Marker or TargetKey until the provider materializes it.
- Committed identity is dimension, provider ID, non-empty stable target ID and
  expected block registry ID. The opaque provider locator, current anchor and
  block-entity classification are not identity; refreshing them must not create
  a new winner.
- The common model contains no optional-mod objects. `providerId`, a non-empty
  `stableTargetId`, and `expectedBlockRegistryId` are each bounded to 256
  characters. The opaque `providerLocator` is bounded to 32767 characters.
  `dimensionId` is required to be non-blank but is not covered by the
  256-character external-identifier limit. Provider-specific parsing is
  isolated in the provider.

These are domain constraints. Encoded identity constraints at the packet
boundary, including the dimension wire limit and its distinction from the
non-blank domain constraint, are owned by the
[network protocol](../network/protocol.md#encoded-identity-constraints).

This is an existing external-target exception, not a Create constituent-block
implementation or cross-dimension tracking feature.

## Marker state and network continuity

Marker data must carry stable Marker ID, owner, concrete target identity and
kind, its separate frozen logical key, dimension, Target Type ID, Ping Type ID,
authoritative name data and creation/arrival/lifetime state. Marker IDs support
deterministic larger-ID comparison. Entity-local hit detail does not change the packet shape.
Encoded identity constraints, including Target Type ID survival across codec
round trips, are owned by the
[network protocol](../network/protocol.md#encoded-identity-constraints).

External locator/anchor refresh continuity, including the preserved marker
fields, is owned by
[marker lifecycle](../authority/marker_lifecycle.md) and the
[Sable integration](../../integrations/sable.md#refresh-lifecycle).

Packet roles are specified in [network protocol](../network/protocol.md); the
server's selection of visible same-target state is specified in
[ping winner](../authority/ping_winner.md).
