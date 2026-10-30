# D0016: Separate logical container targets from physical reads

## Status

Confirmed product decision, including explicit approval of presentation protocol
version two. The linked contracts own behavior; verification inventories own
execution evidence and remaining integration gaps.

The later [D0017](D0017-certified-tracking-observation-pools.md) refines physical
owner/face partitioning only for certified Tracking views. It does not replace
this decision's independent exact read authority or logical grouping rationale.

## Decision

Supported container structures have one server-frozen logical target key while
retaining each marker's original physical hit, anchor and independent lifetime.
Whole-structure presentation uses currently verified metadata and cannot rewrite
that identity. Physical inventory reads keep their owner/face authority and
original read binding independently of the logical quota group.

The [target model](../architecture/identity/target_model.md#frozen-logical-block-groups)
owns key identity, [creation authority](../architecture/authority/target_validation.md#create-adjudication-order)
owns the freeze point, and the [network protocol](../architecture/network/protocol.md#presentation-snapshot-route-presentation-v2)
owns versioned transport. [Presentation subjects](../architecture/rendering/presentation_subjects.md#vanilla-chests)
and [Create](../integrations/create.md#vault-logical-target-and-presentation-topology)
own structure evidence. [Inventory](../architecture/presentation/inventory.md)
and [shared sources](../architecture/presentation/shared_sources.md#observation-eligibility-and-sharing)
own read binding, quotas and observation reuse.

## Rationale

Two players can address different parts of one container and still expect one
visible winner. Replacing the captured hit with the controller would lose the
original selection and read authority; deriving identity from client geometry
would make packet state depend on local chunk availability. A separate key
supports one logical target without combining owners, selected items or
cancellation rights into a single record.

Creation-time freezing makes winner comparison deterministic even while a
structure splits or rejoins. Client presentation can follow later verified
geometry without retroactively changing server admission. Metadata-only
discovery keeps ordinary pings available when inventory reads are locked or
unsupported; complete relationship verification prevents partial discovery from
inventing a larger target or outline.

The client must receive the authoritative key rather than reconstruct it from
its physical target. Explicit versioned transport makes that required field
unambiguous and rejects peers that cannot preserve it. Logical equality is not
permission to reuse another owner's contents or an earlier observation: source
scope and freshness remain separate boundaries.

## Consequences

Existing grouped markers keep their creation identity through topology changes,
while newly created markers use the then-verified structure. Several records can
share a winner/quota group and still require independent physical scans or
fresh observations. This refines the identity boundary without changing the
presentation-type rationale in [D0003](D0003-multipart-presentation-types.md),
inventory recovery in [D0008](D0008-inventory-source-recovery.md), or the
preview-seeded fresh-scan decision in
[D0014](D0014-preview-seeded-inventory-tracking.md).
