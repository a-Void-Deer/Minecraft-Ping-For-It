# Shared-source verification

Evidence rules and verification expectations are owned by the
[verification guide](README.md). Pending live scenarios are listed in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Shared source identity and capture results

`SourceKeyCaptureResultTest` covers the detached identity and result models as
headless seams: equal provider identity plus equal compatible read scope is the
same source while a different read scope is a different source, identity tokens
are non-blank and byte-bounded, snapshot and keyed-fragment payload maps are
copied and immutable, an explicit zero stays explicit while a missing key is
never synthesized to zero, an absent payload is distinct from an empty
snapshot, unavailable and invalid results reject a payload, a complete
eventual sweep may carry a directly observed zero, coverage rejects blank
stamps and negative counts, a known zero expected count is distinct from an
unknown expected count, the keyed-fragment page bound is independent of the
existing record-value entry bound, snapshot records reuse the existing
presentation field and value bounds, and opaque evidence and cursor tokens are
bounded and detached. `OpaqueKeyedPayloadTest` covers the opaque keyed-payload
model: an exact long beyond double precision survives the domain byte codec
while the legacy double form cannot represent it; a domain display value larger
than the generic presentation text bound is carried independently and rejected
by that generic bound; construction and access copy caller bytes and maps;
an untagged value keeps the ordinary value bound while a value tagged through
a closed, code-owned allowance admits only that allowance's fixed identity and
larger bound, and a tagged value must match the fragment's codec identity even
when the carried bytes are small or empty; the carried size is readable without
copying; opaque values compare by their bytes; the exact ordinary opaque value
bound is admitted and one more rejected; invalid values, keys, and codecs are
rejected; the opaque page bound is independent of the record-value entry
bound; and a
readable capture result carries the opaque payload while unavailable and
invalid results reject a payload. These are model seams only: no real source
step, provider read, shared-consumer accounting, transport or running server
path exercises them.

## Shared source access contract

`SourceAccessTest` covers the server-side access boundary headlessly:
resolution returns an available descriptor for a compatible scope while
ordinary unavailability and unsupported targets are values rather than
exceptions; a budget-denied open defers without performing any source read and
without charging the ledger; an admitted handle reports its descriptor and
frozen demand, captures one complete eventual (non-atomic) result with a keyed
fragment, then defers without further reads while charging exactly one unit;
closing is idempotent; an authorized demand set is copied, immutable and
compatibility-checked against the descriptor's read scope; and one-shot,
stable-cursor and stable-version capabilities are declared and tested
independently. This is a contract seam; the production wrapper and publisher
seams are covered separately in the
[inventory foundation](inventory.md#inventory-preview-and-tracking-foundation),
while no live Minecraft world, integrated server runtime or transport exercises it.

## Shared source sync publication contract

`SyncPublisherTest` covers the publication boundary declaration with a fake
seam implementation: a per-key absolute publication merges without resetting
absent keys and respects the frozen authorized projection; a state-fence rebase
and a cancel are isolated per consumer and do not discard another consumer's
delivered values; a budget defer keeps the observation valid and publishes
nothing; an unavailable control result is accepted as status and never deferred
or turned into data or a fake zero; and the context and projection bounds are
validated and frozen. The fake is not a transport or production implementer.
The production publisher's ordering and repair behavior is exercised separately
by `InventoryPublicationIntegrationTest` with a recording transport and real
encoded frames; no live transport, integrated runtime or world read exercises
this boundary.

## Shared source cost ledger

`CostLedgerTest` covers the pure-JVM bounded cost-ledger seam of the shared
source mechanism headlessly: admission reserves before measured use; an
all-or-nothing multi-counter reservation deducts nothing when any counter is
insufficient; the exact limit is admitted and one more defers; a measured
commit charges only the measured subset and refunds omitted counters across
distinct units; closing an uncommitted reservation refunds everything and
repeated close is idempotent; over-commit, unknown counters, negative amounts,
blank scopes and negative limits are rejected before any counter moves;
`Long.MAX_VALUE` limits do not overflow remaining accounting; and caller maps
are copied so later caller mutation cannot move the ledger. This is a generic
counter-ledger model seam; the production runtime and source-wrapper tests
exercise it through recording providers, while a running server path remains
unexercised. `RetainedMemoryLedgerTest`
covers the production retained-memory ledger headlessly: a reservation holds
its actual cost until close and close is idempotent; closing an uncommitted
reservation releases its whole upper bound; a cap reduction keeps live
reservations charged and defers new admission until the ledger returns under
cap; one shared object is charged once while each recipient cursor is charged
separately; a denied admission runs no read, encode, or allocation callback and
moves nothing; and rejected requests leave the ledger untouched.
`LedgerTicketTest` covers combined admission across memory and counter ledgers:
one deferred ledger releases every already-granted provisional ticket, a
granted set holds every ledger until the caller closes, a denial short-circuits
later attempts, the returned tickets are the real ledger tickets, an
exceptional exit releases granted tickets in reverse order, rethrows the same
failure object, propagates an error instead of treating it as deferral,
suppresses a cleanup failure under the original failure while still closing
other tickets, releases granted tickets on null attempts or results, and
rejects an empty attempt list. These are ledger and admission-composition
seams; `InventorySourceSnapshotTest`, `InventoryRuntimeTest`,
`InventoryMinecraftSourcesTest` and the Create Vault wrapper test exercise
their integration into production source allocation, snapshot
preparation/capture and decode, scanning and delivery through recording
providers, while live world, transport and optional-provider behavior remains
unexercised.

The [budget-delay monitoring contract](../../architecture/presentation/shared_sources.md#budget-delay-monitoring)
has pure monitor/coordinator and production-producer coverage.
`BudgetDelayMonitorTest` and `BudgetDelayMonitoringTest` exercise cycle closure
and WARN summaries, bounded detached samples, separate resource units, known
zero versus unknown quantities, saturation, fresh monotonic-time evidence,
healthy/idle/neutral transitions, consolidated notices and delivered-notice
cooldown. They cover disabled messages preserving diagnostics and evidence,
failed or recipient-less notice retry, permission and authoritative-host
eligibility, live flag reads, server-lifetime replacement/stop, recoverable
sink failures and fatal-error propagation through recording ports. These are
not live WARN capture or administrator-message delivery. A test-appender case in
`BudgetDelayMonitoringTest` also drives the production summary-reporting entry:
adjacent closed delayed cycles each emit one WARN without a second general
diagnostic window or an attached throwable, while repeated closure of the same
cycle emits no duplicate. This is headless report delivery, not a live server
cycle or administrator notification.

`InventoryBudgetDelayStateTest` and `InventoryBudgetDelayIntegrationTest`
exercise production physical, logical, projection, publication and control-delay
stages with recording sinks: shared work is not multiplied by consumers or
recipients, duplicate reasons do not duplicate quantities, and known remaining
slots stay separate from unknown or measured publication work. Cases cover
cadence/no-audience and source/transport failure exclusions, outstanding
publication delay across not-yet-due sampling, cancellation and expiry, accepted
preview duplicate suppression, retained repair still observing its new work,
revoked or expired reprojection doing no delayed work, and heartbeat progress
not resolving independently refused completion control. Presentation and
property-preview producer cases are inventoried under
[presentation snapshot negotiation, policy and adapters](presentation.md#presentation-snapshot-negotiation-policy-and-adapters)
and [target content preview](presentation.md#target-content-preview).
The recorded focused run passes the complete inventory monitoring integration
suite; no live server, notification, heap/TPS or soak result follows from these seams.

## Known automated gaps

- `SourceAccessTest` exercises a fake source, while
  [legacy section capture](presentation.md#presentation-snapshot-negotiation-policy-and-adapters)
  has separate headless production-seam coverage. These fixtures do not establish
  legacy-to-domain-unit cost mapping or cross-path deduplication;
- the shared-source identity, opaque-payload, result, access-contract,
  snapshot-preparation, sync-publication declaration, counter/retained-memory
  and combined-admission primitives have the headless coverage inventoried
  here and in the [inventory foundation](inventory.md#inventory-preview-and-tracking-foundation),
  and the production runtime, source-wrapper and publisher seams
  exercise snapshot preparation, capture and cross-period decode, allocation,
  shared scan-work and logical sharing, retained lifetime, invalidation and
  recovery through recording providers and transports. They do not establish
  live world or optional-provider behavior, real transport, GPU or multiplayer
  execution, provider wall-clock bounds, arbitrary external-trusted callback
  internals, or caller-trusted custom counter/decoder work. The
  [shared-source](../../architecture/presentation/shared_sources.md) and
  [inventory](../../architecture/presentation/inventory.md) owners remain the
  contracts; source presence alone does not close these evidence gaps;
- monitoring has pure timing, eligibility, localization and recording-producer
  coverage, but actual closed-cycle WARN output and localized vanilla system
  messages under live resource pressure have not been exercised. Live permission
  or host changes, notification-switch administration, server replacement and
  stop, and prolonged low-TPS/overload behavior remain integration scenarios;
