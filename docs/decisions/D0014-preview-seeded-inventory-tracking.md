# D0014: Preview-seeded inventory tracking

## Status

Confirmed product decision represented by the linked topic contracts. This
record explains selection and bootstrap rationale, not live incident diagnosis
or completed gameplay verification.

## Decision

A valid inventory selection commits synchronously under current server
authority, without an additional creation-time item-presence witness. The
server-retained selected preview value supplies a provisional initial display;
a separate fresh tracking scan establishes the first complete quantity.

[Inventory](../architecture/presentation/inventory.md#preview) owns reference
eligibility and source binding; its
[bootstrap](../architecture/presentation/inventory.md#selected-preview-bootstrap),
[status](../architecture/presentation/inventory.md#status-presentation) and
[budget](../architecture/presentation/inventory.md#budgets-queues-and-memory)
contracts own display history, completion, retirement and retention. Common
[create authority](../architecture/authority/target_validation.md#create-adjudication-order)
and [marker lifetime](../architecture/authority/marker_lifecycle.md) remain
independent of the provisional quantity.

## Rationale

Selection expresses intent to track an item at an authorized source, not a
claim that one previously observed slot still contains it. Ordinary input and
output can empty that slot between preview and selection while the source,
binding and intended item remain valid. A live witness at that old position can
therefore reject a useful selection. Searching for another position instead
would add creation-time content work and still would not make a changing
inventory timeless.

An already retained server value provides useful feedback while the new scan
progresses, without making synchronous marker creation depend on its delivery.
Retained quantities still need current publication authority; receiving another
selection is not evidence that an older queued source remains readable.
Keeping the value explicitly provisional avoids disguising a
preview subtotal as a completed tracking result. Retaining only the selected
value avoids coupling the marker's display lifetime to an open preview and its
unselected inventory. Current source and permission checks remain necessary:
historical item data is not authority to read or publish a source.

## Why not other approaches

- Do not search or wait asynchronously to prove current item presence: it adds
  work or a new selection lifecycle without resolving ongoing content changes.
- Do not fabricate a completed capture or reuse the preview scan accumulator:
  the initial display and the fresh observation answer different questions.
- Do not use a client-displayed quantity as authority or reinterpret a retired
  exact reference as an aggregate: neither preserves the selected server fact.
- Do not auto-delete the marker when a later complete scan reports absence:
  quantity and marker lifetime are separate contracts.

## Consequences

A selection can succeed after its old observed slot has cleared, and the first
fresh count can differ from the provisional display, including zero. Failure
history and source completion must remain separate from transport installation
so replay, invalidation and repair cannot certify stale data. This does not
broaden the cross-slot movement accuracy guarantee in
[D0013](D0013-native-incremental-inventory-sweeps.md), change the identity boundary
in [D0012](D0012-explicit-patch-identity-and-display.md), or alter the recovery
lifetime rationale in [D0008](D0008-inventory-source-recovery.md).

[Inventory verification](../testing/verification/inventory.md#preview-seeded-tracking-coverage)
owns automated evidence and remaining live-world gaps. A passing headless
scenario does not establish the cause of a reported installed-mod failure.
