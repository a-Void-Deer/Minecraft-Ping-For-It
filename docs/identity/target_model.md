# Target model and stable identity

## Domain terms

- A `Target` is the concrete entity, block or pure location resolved by the
  capture associated with the initial ping-key press. Capture may complete
  asynchronously or start through the narrow deferred-compatible path in
  [capture](../picking/capture.md).
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

Entity identity uses a stable entity UUID where available, plus dimension.
Movement or same-dimension teleportation does not change identity. A dimension
change, death, disappearance or absence at authoritative marker creation makes
the target invalid.

Block identity is dimension, block position and block type. A BlockState or
property change with the same block type remains valid at marker creation;
replacement by a different type is invalid. The state's properties are not a
new target identity.

After an ordinary entity or block marker is committed it is not continuously
server-revalidated: it remains until normal removal or expiry. Presentation
in another dimension is skipped. An unavailable entity may use its last or
authoritative anchor. An ordinary committed block replacement does not itself
remove the marker; the renderer uses the current block render state. Explicitly
supported external-target markers retain their established periodic
invalidation exception. Pure locations retain existing location-ping semantics.

Existing ping lifetime, range, cooldown and comparable mechanics are preserved
unless an explicit product requirement changes them. Pre-commit validation and
error messages are owned by [target validation](../authority/target_validation.md).

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

This is an existing external-target exception, not a Create constituent-block
implementation or cross-dimension tracking feature.

## Marker state and network continuity

Marker data must carry or derive stable Marker ID, owner, concrete target
identity and kind, dimension, Target Type ID, Ping Type ID, authoritative name
data and creation/arrival/lifetime state. Marker IDs support deterministic
larger-ID comparison. `targetTypeId`, including `entity_block`, survives marker
codec round trips; entity-local hit detail does not change the packet shape.

An external locator/anchor refresh rebuilds the stored marker with the same ID,
owner, Target Type, Ping Type, arrival, expiry and immutable audience. It is an
update of the committed marker, not another receipt or a new winner candidate.
This continuity within the fork does not imply original-mod protocol support.

Packet roles are specified in [target validation](../authority/target_validation.md);
the server's selection of visible same-target state is specified in
[ping winner](../authority/ping_winner.md).
