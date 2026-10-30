# D0017: Certified Tracking observation pools

## Status

Confirmed product decision, including explicit authorization of GUI controls and
the server-configuration protocol upgrade. Verification inventories own execution
evidence and outstanding review or live-integration gaps; this decision is not a
completion report.

## Decision

Separate exact owner/face authority from certified equivalence of physical
inventory addressing. Reuse a fresh Tracking observation only through an
explicit audited provider certificate, retaining each participant's authority
and publication evidence independently. Freeze partition policy per round
rather than restarting an observation when an administrator changes a switch.

[Inventory](../architecture/presentation/inventory.md#certified-tracking-observation-pools)
owns certification, pool eligibility and transitions; its
[publication contract](../architecture/presentation/inventory.md#publication-authority-and-progress)
owns fresh handoff checks. [Shared sources](../architecture/presentation/shared_sources.md)
keeps generic exact-scope semantics. [Server configuration](../config/server.md#inventory-policy-object),
[the change transaction](../architecture/config/changing-server-config.md),
[wire grammar](../architecture/network/protocol.md#server-configuration-routes-server-config-request-v5-server-config-update-v5-server-config-snapshot-v5)
and [UI](../UI/settings-screen.md#server-performance-category) own their separate
administration surfaces. [Create](../integrations/create.md#vault-inventory-provider)
owns its provider-specific opt-in.

## Rationale

Independent markers can ask different questions of the same physical slots.
Repeating every getter for an owner or face whose view is demonstrably identical
wastes shared scan capacity, but equal aliases or equal permission results do
not prove that identity. A narrow physical certificate makes reuse explicit
without weakening exact read binding, exposing hidden sided slots or granting
one player's access to another.

Mutable callbacks can revoke authority after scanning, queue admission or frame
measurement. Historical cut evidence and a fresh final handoff check prevent a
newer offer or another participant from authorizing stale queued data. Reserving
an existing-budget validation opportunity supports publication progress without
caching permission or adding free work.

Round-frozen policy lets a paid mixed-time sweep finish without discarding its
progress or provisional history. Sealing old eligibility preserves that
continuity without admitting new consumers under obsolete policy. Keeping the
widened pool Tracking-only preserves Preview and new-selection freshness rather
than turning completed observations into a result cache.

## Consequences

This refines the earlier unconditional owner/face physical partition where a
provider has established equivalence; unsupported routes stay isolated. It is
orthogonal to [D0016](D0016-logical-container-targets.md): logical winner/quota
identity does not certify physical sharing, and shared reads do not merge
markers, audiences, selections or lifetimes. It preserves
[D0015](D0015-inventory-metadata-reuse-and-scratch.md)'s no-authority/content-cache
boundary and [D0014](D0014-preview-seeded-inventory-tracking.md)'s fresh first scan.
Fewer getters are not evidence of reduced total CPU, allocation rate, heap or
latency; [verification](../testing/verification/inventory.md#certified-tracking-pool-and-publication-coverage)
keeps those limits separate.
