# D0008: Inventory source identity and recovery

## Status

Confirmed product decision represented by
[inventory preview and tracking](../architecture/presentation/inventory.md).
Generic capture and sync mechanics are owned by
[shared source capture and sync](../architecture/presentation/shared_sources.md).
This record explains the identity and recovery rationale.

## Decision

Inventory tracking for an ordinary block ping uses the existing live Ping
identity (the stable marker ID) and the original target identity. It adds no
new inventory "source-instance generation"; that phrasing would make the
lifetime of the current world object the identity, and its use of "generation"
has no connection to Minecraft loot or world generation. Ordinary block source
identity is dimension, block position and the original block registry ID. A
matching Ping ID alone is not sufficient, and a BlockState or property change
with the same registry ID does not create a new source identity.

A destroyed, missing or otherwise unavailable original source is a detected
invalidity, not an empty result. On detection the live inventory status becomes
invalid: tracking stops publishing valid updates, cancels or discards queued old
valid state, and sends the invalidation state to recipients. This status is
separate from the Ping's normal marker lifetime; invalidation and recovery never
remove, shorten or extend the ordinary marker record, whose existing lifecycle
is owned by [marker lifecycle](../architecture/authority/marker_lifecycle.md).
While the same Ping remains alive and within its hard tracking duration — the
Ping's ordinary marker lifetime, ending exactly at its expiry — a same-type
container at the same coordinates may recover, even when it is a different
world object or container instance. A trusted scan of a present
container may legitimately report zero for a tracked item: a selected item
absent from a complete, successful, authoritative scan is a zero even when the
container holds other items, and an empty container is one such observation.
Absence before a completed observation, or an incomplete or unknown scan, is
never a definite zero, and a different block type is not a valid source while it
occupies the location. If the original registry ID later returns while the same
Ping and its hard tracking duration remain valid, the source may recover.

Recovery keeps the existing Ping ID. It advances the inventory status revision
and establishes a new inventory snapshot identity with a clean baseline for each
Ping+recipient pair; the source is scanned afresh and old streams and values are
invalidated rather than merged into the recovered state. This inventory baseline
is a new inventory-protocol concept, not a rename of the existing marker
snapshot identity or its initial delivery, and it changes no marker identity.
Baselines are per Ping+recipient: one recipient's resynchronization neither
advances nor resets another recipient's baseline. Recipients may share an actual
source read, but shared reads are not a shared protocol baseline and are not a
provider source generation.

Status revisions, baselines and invalidation remain required fences: they keep
queued or in-flight valid data from before an invalidation from resurrecting the
invalid state, and data from before a recovery baseline from overwriting the
recovered state. Inventory statuses, baseline and revision mechanics, and
execution details are owned by
[inventory preview and tracking](../architecture/presentation/inventory.md);
wire messages are owned by [network protocol](../architecture/network/protocol.md).

The inventory tracking duration ends in a hard stop. The hard deadline follows
the Ping's own marker lifetime exactly: there is no independent inventory
tracking-duration setting and no earlier tracking-only grey interval. At that
boundary, tracking stops scanning and stops recovery probing, and the source
never recovers afterward. Only a source invalidated before that boundary is
shown as grey; normal Ping expiry or removal removes the marker record as
usual and is not extended by tracking, invalidation or recovery. An expired or
removed Ping never recovers, and a fresh ping after the old one is a new
lifetime with its own identity and baselines.

This ordinary-block decision does not change external provider identity, lease
or removal rules, and replacement tolerance cannot revive an expired lease.
Entity and private-inventory tracking require their own owning contracts; see
[inventory source identity and recovery](../architecture/presentation/inventory.md#source-identity-invalidation-and-recovery).

## Rationale

The ping expresses intent about a place and an original target, not about a
particular live world object. A container can be broken and an equivalent
same-type container can stand at the same coordinates; requiring the original
container instance for validity would reject exactly the replacement case that
recovery exists to cover. Ordinary-block source identity therefore anchors to
dimension, position and the original registry ID rather than to the live
object's identity or instance lifetime, preserving the original target identity
across ordinary world edits while still rejecting a different block.

The stale-data problem is a fencing problem, not an identity problem. Status,
baseline and per-item revisions order updates inside one ping; they do not
identify the source object. A queued update captured before invalidation, or
before a recovery scan, is old protocol data, so advancing those fences is the
correct tool to retire it. Fencing does not require tracking the generation or
instance lifetime of the source object, and this boundary does not make the
original container instance a validity requirement: same-type recovery at the
same coordinates is explicitly allowed. Keeping the Ping ID and only advancing
inventory state keeps the pending-ping interaction, marker record and winners as
one continuous Ping even while inventory tracking invalidates and re-baselines.

## Why not other approaches

- Do not require the original container instance for source validity: a
  same-type replacement at the same coordinates is a confirmed recovery case, so
  validity cannot depend on the original object still being present. This is a
  scope choice about identity, not a claim that instance generations cannot be
  used for other purposes.
- Do not identify the source by Ping ID alone: a Ping identifies the ping, not
  the block, so it cannot reject a different block type at the tracked position.
- Do not treat a same-registry-ID state or property change as a new source: the
  property state is not source identity.
- Do not let inventory invalidation or the inventory tracking deadline change
  the ordinary marker lifetime: normal Ping expiry or removal remains
  authoritative, and inventory status and marker lifetime are independent
  boundaries.
- Do not keep delivering queued pre-invalidation or pre-recovery values: they
  would restore state that was explicitly invalidated or replaced.
- Do not treat absent, incomplete or unreadable data as zero for a tracked item:
  a zero requires a complete, successful, authoritative observation establishing
  that item's absence, whether the container is empty or holds other items.
- Do not extend the Ping or an external lease so recovery has more time: the
  hard tracking boundary and provider lease rules are fixed.
- Do not let one recipient's resynchronization reset another's baseline: masks,
  demand and delivery are per recipient.

## Consequences

Inventory tracking requires per-Ping+recipient status and baseline fences
against queued and in-flight stale updates. These fences do not replace marker
identity, marker lifetime or provider authority. Coverage and pending runtime
evidence remain owned by
[testing and verification](../testing/verification/inventory.md).

## Related docs

[Target model](../architecture/identity/target_model.md),
[marker lifecycle](../architecture/authority/marker_lifecycle.md),
[client marker state](../architecture/markers/client-state.md),
[presentation snapshot](../architecture/presentation/presentation_snapshot.md),
[shared source capture and sync](../architecture/presentation/shared_sources.md),
[inventory preview and tracking](../architecture/presentation/inventory.md),
[network protocol](../architecture/network/protocol.md),
[Sable integration](../integrations/sable.md), and
[testing and verification](../testing/verification/inventory.md).
