# D0013: Native incremental inventory sweeps

## Status

Confirmed product decision represented by the linked topic contracts. This
record explains the read-cost and accuracy trade-off, not runtime performance
or installed-mod verification.

## Decision

Native indexed inventories use budgeted live traversal across ticks rather than
an eager whole-inventory freeze followed by budgeted decoding. A fully traversed
stable-slot observation is accepted as a normal inventory result under an
explicit mixed-time accuracy boundary. That domain acceptance does not certify
atomicity or upgrade generic eventual consistency.

[Inventory](../architecture/presentation/inventory.md#native-incremental-reads)
owns read semantics and the stable-placement assumption; its
[budget](../architecture/presentation/inventory.md#budgets-queues-and-memory) and
[status](../architecture/presentation/inventory.md#status-presentation) sections
own accounting, retained sharing and completion quality.
[Shared sources](../architecture/presentation/shared_sources.md) retains generic
capability and consistency meanings, and
[Create](../integrations/create.md#vault-inventory-provider) owns Vault-specific
structure gates. Explicit snapshot adapters remain a separate capability.

## Rationale

Charging only the consumption of an already frozen inventory cannot distribute
the initial getters, detachment and item processing across ticks. Large repeated
inventories also need not retain a full encoded item observation per slot when
identity and display are shared. Incremental traversal makes the actual native
read the budgeted unit, while compact retained observations let compatible
consumers reuse paid work without replaying getters.

Cross-tick live reads cannot promise a single instant's contents. The accepted
use case assumes stable item placement, while allowing counts to be observed at
their own read times. Treating every completed native traversal as uncertain
would obscure this deliberate usable result; treating it as atomic would claim
evidence the source does not supply. Inventory-local acceptance keeps these
meanings separate and leaves generic providers conservative.

Stable addressing and dynamic authority are also different concerns. Reusing a
frozen layout avoids rebuilding descriptor data, but it cannot replace fresh
owner, membership or topology checks. Likewise, a provider may reuse a resident
item in its next callback, so synchronous detachment must precede that callback
without removing the final authority check.

## Why not other approaches

- Do not retain eager native capture behind a low decode quota: that quota does
  not constrain the original physical reads.
- Do not require atomic-detachment certification merely to traverse stable
  indexed slots: it answers a stronger, different consistency question.
- Do not infer unchanged contents from a stable cursor or a wrapper version
  whose semantics are not established.
- Do not promote all generic eventual results to ready: acceptance belongs to
  the inventory observation carrying its own evidence, not to a source key.
- Do not promise arbitrary distinct-variant capacity from a large repeated-item
  case or bypass finite memory to force completion.

## Consequences

Native totals can mix read times and cannot guarantee quantity consistency for
items moved between slots during traversal. Applications needing an atomic view
require an explicit adapter with stronger evidence. Existing source identity,
recovery and marker lifetime remain separate under
[D0008](D0008-inventory-source-recovery.md), and component identity and display
retain [D0012](D0012-explicit-patch-identity-and-display.md).

[Inventory verification](../testing/verification/inventory.md#native-incremental-sweep-coverage)
owns deterministic evidence and the remaining live-world and performance gaps.
