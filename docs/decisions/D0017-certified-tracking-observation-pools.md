# D0017: Certified Tracking observation pools

## Status

Confirmed product decision, including explicit authorization of GUI controls and
the server-configuration protocol upgrade. Verification inventories own execution
evidence and outstanding review or live-integration gaps; this decision is not a
completion report.

The subsequent active-observation extension explicitly accepts pre-attachment
mixed-time values for certified new Tracking and Preview consumers. It supersedes
the original fresh-only/Tracking-only eligibility subset, not its independent
authority or completed-result-cache boundary. It authorizes no further protocol,
configuration, version or capture-timer change.

## Decision

Separate exact owner/face authority from certified equivalence of physical
inventory addressing. Reuse an active native observation only through an
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
continuity without admitting new consumers under obsolete policy.

The original fresh-only cohort rule repeated getters when a user selected another
item while a compatible large sweep was still running. The confirmed extension
accepts that sweep's earlier prefix as part of the same deliberate mixed-time
observation, instead of treating attachment as a new content instant. Independent
zero-based replay keeps that evidence distinct from the provisional selected
display seed. Active admission, rather than a stored complete quantity, is the
reuse boundary; a newcomer who misses it must not acquire a completed cache.

Preview may borrow a live Tracking cohort without becoming recurring demand.
Keeping it one-shot prevents a completed open list from reserving future work
that it will never consume, while real slow Tracking peers retain bounded
successor continuity. Unsupported routes and Preview-only cross-binding reuse
remain conservative.

## Consequences

This refines the earlier unconditional owner/face physical partition where a
provider has established equivalence; unsupported routes stay isolated. It is
orthogonal to [D0016](D0016-logical-container-targets.md): logical winner/quota
identity does not certify physical sharing, and shared reads do not merge
markers, audiences, selections or lifetimes. It preserves
[D0015](D0015-inventory-metadata-reuse-and-scratch.md)'s no-authority/content-cache
boundary and the inventory contract's
[independent bootstrap and complete-result semantics](../architecture/presentation/inventory.md#selected-preview-bootstrap).
Fewer getters are not evidence of reduced total CPU, allocation rate, heap or
latency; [verification](../testing/verification/inventory.md#certified-tracking-pool-and-publication-coverage)
keeps those limits separate.
