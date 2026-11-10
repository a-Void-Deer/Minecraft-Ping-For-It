# Shared-source verification

Evidence rules and verification expectations are owned by the
[verification guide](README.md). Pending live scenarios are listed in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Shared source identity and capture results

`SourceKeyCaptureResultTest` covers the detached identity and result models
headlessly: provider identity plus a compatible read scope defines the same
source; identity tokens are non-blank and byte-bounded; snapshot and
keyed-fragment maps are copied and immutable; an explicit zero is distinct from
a missing key and an absent payload from an empty snapshot; unavailable and
invalid results reject payloads; a complete eventual sweep may carry an observed
zero; coverage rejects blank stamps and negative counts; a known-zero expected
count differs from an unknown count; keyed-fragment and record-value bounds are
independent; and opaque evidence and cursor tokens stay bounded and detached.
`OpaqueKeyedPayloadTest` covers the opaque payload model: exact long precision
beyond a double, a domain display value larger than the generic text bound,
defensive construction and access copies, tagged and untagged value bounds,
codec-identity matching, byte comparison, exact ordinary bounds and rejection of
invalid values, keys and codecs, an independent opaque page bound, and
payload-carrying readable results. These are model seams only: no real source
step, provider read, shared-consumer accounting, transport or running server
path exercises them.

## Shared source access contract

`SourceAccessTest` covers the server-side access boundary headlessly:
availability and unsupported targets are values rather than exceptions; a
budget-denied open defers with no source read and no ledger charge; an admitted
handle reports its descriptor and frozen demand, captures one complete eventual
result with a keyed fragment, defers without further reads while charging
exactly one unit, and closes idempotently; authorized demand is copied,
immutable and compatibility-checked against the read scope; and one-shot,
stable-cursor and stable-version capabilities are independently declared and
tested. This is a contract seam with no live world, integrated runtime or
transport. The production inventory wrapper and publisher, including attachment
floors, paid-prefix replay, retained successor handoff and owner/face
partitioning, are under the
[inventory foundation](inventory.md#inventory-preview-and-tracking-foundation).
The
[composed Vault sharing cases](inventory.md#logical-container-quotas-and-scan-sharing)
add segmented-provider, ordinary-wrapper and runtime evidence on the exact
legacy path, and
[certified-pool coverage](inventory.md#certified-tracking-pool-and-publication-coverage)
adds independently bound cross-owner and face reuse. None is a live performance
measurement.

## Shared source sync publication contract

`SyncPublisherTest` covers the publication boundary with a fake seam: per-key
absolute publication merges without resetting absent keys and respects the
frozen authorized projection; state-fence rebase and cancel are isolated per
consumer; budget defer keeps the observation valid and publishes nothing;
unavailable control results are accepted as status and never become data or a
fake zero; and context and projection bounds are validated and frozen. The fake
is not a transport or production implementer. Production ordering and repair are
exercised separately by `InventoryPublicationIntegrationTest` with a recording
transport and real encoded frames; no live transport, integrated runtime or
world read applies.

## Shared source cost ledger

`CostLedgerTest` covers the pure-JVM bounded ledger: reserve-before-use,
all-or-nothing multi-counter admission, exact-limit acceptance, measured commit
with omitted-counter refunds, idempotent close of an uncommitted reservation,
rejection of over-commit, unknown counters, negative amounts, blank scopes and
negative limits, overflow-safe `Long.MAX_VALUE` limits, and copied caller maps.
`RetainedMemoryLedgerTest` covers the production retained-memory ledger: cost
held until close, idempotent close, whole-upper-bound release, cap reduction
keeping live reservations charged and deferring new admission, one shared object
charged once with separate recipient cursors, and denied or rejected requests
running no callback and moving nothing. `LedgerTicketTest` covers combined
admission across memory and counter ledgers: provisional release, held grants,
short-circuit denial, real ticket return, reverse-order exceptional release,
same-failure rethrow, error-versus-deferral propagation, cleanup suppression and
null or empty attempts. These are ledger and admission seams; integration into
production allocation, snapshot preparation/capture and delivery is covered by
`InventorySourceSnapshotTest`, `InventoryRuntimeTest`,
`InventoryMinecraftSourcesTest` and the Create Vault wrapper test through
recording providers. Live world, transport and optional-provider behavior remain
unexercised.

The
[budget-delay monitoring contract](../../architecture/presentation/shared_sources.md#budget-delay-monitoring)
has pure monitor/coordinator and production-producer coverage.
`BudgetDelayMonitorTest` and `BudgetDelayMonitoringTest` cover cycle
closure and WARN summaries, bounded detached samples, separate resource units,
known-zero versus unknown quantities, saturation, fresh final-delay evidence,
healthy/idle/neutral transitions, consolidated notices and delivered-notice
cooldown, including notification thresholds, global cooldown boundaries, streak
origins, repeated same-tick ingress and a late stale first flush. Boundary input
times come from the implementation timing sources; threshold, cooldown and
fresh-evidence outcomes remain independent contract expectations. They also cover
disabled messages preserving diagnostics, failed or recipient-less notice retry,
permission and authoritative-host eligibility, live flag reads, server-lifetime
replacement and stop, recoverable sink failures and fatal propagation.
`BudgetDelayMonitorTest` and `InventoryBudgetDelayStateTest` classify a throwing
warning sink or monitor-context factory as a `SINK` or `CONTEXT` self-fault
without replaying accounting, inventing a business WARN or rolling back
outstanding reasons. A failed administrator notice remains a business handoff,
is not a self-fault and consumes no delivered cooldown. A
`BudgetDelayMonitoringTest` test-appender case drives production summary
reporting with finite per-module handling and no duplicate on repeated closure.
These are not live WARN capture or administrator-message delivery.

`InventoryBudgetDelayStateTest` and `InventoryBudgetDelayIntegrationTest` cover
the production physical, logical, projection, publication and control-delay
stages with recording sinks: shared work is not multiplied by consumers or
recipients, duplicate reasons do not duplicate quantities, known remaining slots
stay separate from unknown or measured publication work, and cadence/no-audience,
source/transport failure, outstanding delay, cancellation, expiry, duplicate
suppression, retained repair, revoked or expired reprojection and refused
completion control are handled. The integration case qualifies a server-tick
notice from fresh evidence across accounting periods, keeps repeated same-tick
ingress from accelerating the streak or refunding allowance, and emits no
synthetic WARN for skipped periods. Presentation and property-preview producers
are inventoried under
[presentation snapshot negotiation](presentation.md#presentation-snapshot-negotiation-policy-and-adapters)
and [target content preview](presentation.md#target-content-preview). No saved
source-input execution record yet covers these added diagnostic cases; the
[shared diagnostics note](integrations.md#shared-exception-diagnostics) owns the
cross-domain gap. No live server, notification, heap/TPS or soak result follows.

## Certified pool delay diagnostics

`InventoryPooledChestRuntimeTest` adds seven source-reviewed cases across three
methods through the production certified chest runtime and `BudgetDelayMonitor`,
using 27-slot chests and the legal normalized physical cap of 16. They cover
physical exhaustion before the first page and after a retained 16-slot page
(27 or 11 known remaining physical slots), page-memory, provider-work, item-work
and combined exhaustion after one paid slot (26 known remaining, applicable
reason only, prefix preserved, resume when permitted), and already-paid
shared-journal replay with no remaining physical allowance and no duplicate
charge. Reason counts and quantities are checked on closed-cycle summaries;
cleanup releases retained and reserved memory. These assertions passed the full
Java 21 execution recorded in the
[combined Java run](build-checks.md#recipientless-tracking-diagnostics-and-caption-reflow-verification),
separate from the earlier Common results. They do not capture live WARN output or
administrator messages; wider pool coverage remains under
[inventory verification](inventory.md#certified-tracking-pool-and-publication-coverage).

## Known automated gaps

- `SourceAccessTest` exercises a fake source while
  [legacy section capture](presentation.md#presentation-snapshot-negotiation-policy-and-adapters)
  has separate headless production-seam coverage. These fixtures do not
  establish legacy-to-domain-unit cost mapping or cross-path deduplication;
- the shared-source primitives have the headless coverage above, and the
  production runtime, source-wrapper and publisher seams exercise snapshot
  preparation, capture and cross-period decode, allocation, native cross-tick
  reads and compact replay, shared scan work and logical sharing, retained
  lifetime, invalidation and recovery through recording providers and
  transports. They do not establish live world or optional-provider behavior,
  real transport, GPU or multiplayer execution, provider wall-clock bounds,
  arbitrary external-trusted callback internals, or caller-trusted custom
  counter or decoder work. The
  [shared-source](../../architecture/presentation/shared_sources.md) and
  [inventory](../../architecture/presentation/inventory.md) owners remain the
  contracts; source presence alone does not close these gaps;
- monitoring has pure evidence-transition, eligibility, localization,
  recording-producer and test-appender closed-cycle WARN coverage, but
  closed-cycle warnings and localized vanilla system messages under live
  resource pressure, live permission or host changes, notification-switch
  administration, server replacement and stop, and prolonged low-TPS or
  overload behavior remain integration scenarios.
