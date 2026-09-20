# Target model and stable identity

## Domain terms

- A `Target` is the concrete entity, block or pure location resolved by the
  capture associated with the initial ping-key press. Capture may complete
  asynchronously or start through the narrow deferred-compatible path in
  [long-press compatibility](../architecture/input/long-press-compatibility.md).
- A `Target Type` is a code-defined matcher; a `Resolved Target` is the captured
  target plus the winning Target Type.
- A `Ping Type` is a predefined semantic choice for chat, outline color, text
  emphasis, wheel presentation and optional icon.
- A `Marker` is an active ping with stable ID, owner, target identity, selected
  Ping Type and lifecycle data.

These stable concepts are separate from live renderable geometry and
[presentation subjects](../rendering/presentation_subjects.md). The
[geometry pipeline](../architecture/geometry-pipeline.md) describes their data
flow; [D0003](../decisions/D0003-multipart-presentation-types.md) explains the
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

Block identity is dimension, block position and block type. A BlockState or
property change with the same block type remains valid at marker creation;
replacement by a different type is invalid. The state's properties are not a
new target identity.

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

Existing ping lifetime, range, cooldown and comparable mechanics are preserved
unless an explicit product requirement changes them. Pre-commit validation and
error messages are owned by [target validation](../authority/target_validation.md).

## Marker data versus current presentation

Canonical marker identity, lifecycle and HUD data are separate from the
current-frame presentation. The committed marker retains its ID, owner,
concrete target identity, Target/Ping Types and lifecycle state, together with
the authoritative name data used by HUD and chat, until the normal marker
lifecycle removes or expires it. Presentation resolution may nevertheless
produce no current subject; that absence does not mean that the marker or its
HUD data has been removed. The lifecycle rules are owned by
[marker lifecycle](../authority/marker_lifecycle.md).

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

Entity-local geometry detail is capture metadata only. Retain it in the frozen
context only if it belongs to the matching resolved entity identity. It does
not rewrite the whole-entity Target, marker identity or packet shape. The
server validates the whole entity rather than replaying a client-local ray or
validating a local constituent. See [local geometry](../picking/local_geometry.md).

## External-block identity

The common external-block model, currently used by
[Sable](../integrations/sable.md), distinguishes a create candidate from a
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

These are domain constraints. At the packet boundary, each encoded field must
also fit its codec limit. In particular, `Target` and `TargetKey` encode
`dimensionId` with `writeUtf(..., 256)`: this is a 256-character wire limit,
not a byte limit. That transport limit does not make a dimension ID an external
identifier or change its non-blank domain constraint.

This is an existing external-target exception, not a Create constituent-block
implementation or cross-dimension tracking feature.

## Marker state and network continuity

Marker data must carry or derive stable Marker ID, owner, concrete target
identity and kind, dimension, Target Type ID, Ping Type ID, authoritative name
data and creation/arrival/lifetime state. Marker IDs support deterministic
larger-ID comparison. `targetTypeId`, including `entity_block`, survives marker
codec round trips; entity-local hit detail does not change the packet shape.

An external locator/anchor refresh rebuilds the stored marker with the same ID,
owner, Target Type, Ping Type, arrival, expiry and current audience. It is an
update of the committed marker, not another receipt or a new winner candidate.
This continuity within the fork does not imply original-mod protocol support.

Packet roles are specified in [target validation](../authority/target_validation.md);
the server's selection of visible same-target state is specified in
[ping winner](../authority/ping_winner.md).
