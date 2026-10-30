# D0015: Inventory metadata reuse and bounded scratch

## Status

Confirmed technical decision represented by the linked topic contracts. This
record explains the reuse and accounting boundary, not live performance or a
completed review result.

[D0017](D0017-certified-tracking-observation-pools.md) separately permits certified
Tracking reuse across exact bindings; it retains this decision's prohibition on
authority and content caches rather than making metadata reuse a pooling grant.

## Decision

Reuse process-fixed resident-access discovery and explicitly admitted immutable
source metadata instead of reconstructing them per slot. Allocate root-local
processing scratch on demand, admitting its complete growth cost before use.
Neither optimization permits caching a successful authority check or resident
item contents.

[Inventory](../architecture/presentation/inventory.md#provider-layer-and-safety)
owns discovery and scope semantics; its
[budget contract](../architecture/presentation/inventory.md#budgets-queues-and-memory)
owns inventory processing and retained ownership.
[Shared sources](../architecture/presentation/shared_sources.md#source-access)
owns the explicit revalidation capability and generic accounting boundary.
[Create](../integrations/create.md#vault-inventory-provider) owns the ordinary
Vault opt-in and current-route checks.

## Rationale

Slot budgeting alone does not make a read cheap when each slot rebuilds source
views, scope scratch or service discovery. A frozen mapping can be reused without
freezing its authority: the provider must explicitly support checking its current
route against that mapping, and the source must still check current world and
owner evidence. This develops the distinction already recorded in
[D0013](D0013-native-incremental-inventory-sweeps.md), rather than replacing its
mixed-time read decision.

Maximum-sized scratch for every small patch pays for unused capacity. On-demand
storage avoids that cost while keeping the same logical bounds. Growth creates
real replacement allocation and copying, so charging only net retained capacity
would hide attempted work and old/new overlap. Honest cumulative charging can
refuse a pathological near-budget input even when a fixed-buffer allocation
schedule accepted it.

## Why not other approaches

- Do not memoize validity by tick or infer it from a stable alias or slot total:
  callbacks can revoke authority or change topology inside one operation.
- Do not cache item stacks or bypass full canonical comparison for repeated
  items: mutable resident contents and digest collisions remain independent risks.
- Do not grant reuse to arbitrary capabilities, decorators or external bindings:
  their current-route and scope guarantees have not been established by the
  ordinary Vault opt-in.
- Do not raise processing budgets or refund failed growth to preserve every old
  threshold acceptance: that would conceal the work the implementation performs.

## Consequences

Reuse stays bounded by the owning handle or root rather than a global world
cache. Dynamic checks still scale with visited members; this is not a constant-
time validation promise. Explicit-patch semantics remain owned by
[D0012](D0012-explicit-patch-identity-and-display.md), and preview-seeded display
remains independent under
[D0014](D0014-preview-seeded-inventory-tracking.md).

[Inventory verification](../testing/verification/inventory.md#vault-tracking-performance-evidence)
owns deterministic coverage, measurements and the remaining live-world gaps.
