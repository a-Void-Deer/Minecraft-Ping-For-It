# Shared source capture and sync

This topic owns the generic, domain-neutral mechanism shared by presentation
sources: source access, capture results, cost accounting, and sync publication.
It is the adopted contract for the approved shared-source mechanism: the
server-side source-access contract, the detached source-key and capture-result
models, the cost ledgers, and the sync publisher are integrated through the
production runtime, source wrapper and publisher seams. Coverage scope and
pending runtime evidence are owned by
[testing and verification](../../testing/verification.md). The existing
one-shot adapter capture path, the per-marker cache, and whole-section
replacement keep their present semantics on their current routes, and this
adoption does not rewrite those routes.

Inventory domain policy — item identity, preview and tracking behavior, zero and
component-fallback rules, and recovery deadlines — is owned by
[inventory](inventory.md). Negotiation, field policy, adapters, and the session
value store remain owned by
[presentation snapshot](presentation_snapshot.md). Route IDs, message families
and wire encoding belong to [network protocol](../network/protocol.md);
configuration keys, bounds and defaults belong to the configuration owners.
This topic therefore defines no wire key, numeric catalogue, or Java type list.
The role signatures below are shape illustrations only; exact API names and
types stay implementation choices.

The four boundaries are logical roles and seams:

- **Source access** resolves a target to a provider-backed source and steps a
  bounded observation of it.
- **Capture result** carries one bounded observation with independent
  availability, completeness and consistency facts.
- **Cost accounting** admits bounded work before reading or allocating, and
  charges the separate resource kinds without a shared unit conversion.
- **Sync publication** projects and publishes results to isolated consumers
  under a domain sync policy.

Provider kinds and result encodings are registered explicitly in code; this is
not a user plugin registry, a dynamic type system, or a second numeric
catalogue. Generic layers own mechanism; domain owners own strategy.

## Source access

Source access is expressed as logical roles:

```text
resolve(target, serverReadScope, guard) -> SourceDescriptor | Deferred | Unavailable | Unsupported
begin(descriptor, serverDerivedDemand, guard) -> ServerLocalHandle
step(handle, grant) -> CaptureResult
cancel(handle) / release(handle)
```

A descriptor carries the code-registered kind and schema, the provider's actual
source identity, the compatible visibility/facing/context, and the capability
declaration. `Deferred` (the guard rejects admission before any world read) is
distinct from `Unavailable` and from `Unsupported`. A budget defer produced by
`begin` or `step` is a scheduling outcome, not an invalid source.

The source key is the provider's actual source identity plus compatible view
context. It never contains a Ping ID, and the generic layer adds no
source-instance generation counter; `sourceVersion` remains independent
observation evidence rather than identity. An external identity is established
by dimension, provider ID, stable target ID and expected block registry ID; a
provider locator is an opaque, refreshable resolution payload, not identity, and
must not be assumed permanently stable. A detached target's external
coordinates are normalized and cannot serve as physical identity; provider-
confirmed stable target or source keys are used instead.

Demand is the server-derived union of already-authorized demand only. Providers
never observe baselines, HUD state, or inventory fallback policy. Compatible
views share one physical observation.

Capabilities are independent. The mode (one-shot or resumable) is separate from
the independent capabilities of a stable cursor, a stable version, and an
atomic snapshot; a one-shot read is not automatically atomic. Atomic snapshot
evidence requires a complete detached capture made in one contiguous
server-thread operation; a tick number, a single generic live step, or a stable
cursor does not establish it. A real source version is independent evidence:
it is never fabricated when absent, and it does not by itself make a capture
atomic.

A world object or provider handle lives only on the server thread, inside the
server-local handle, and only for as long as the handle is needed. Cursor state
is bounded. Each step revalidates source validity and reacquires a handle when
necessary. Source access must not force-load chunks, trigger loot-table
generation, or modify the world. When a consumer leaves, only its own demand is
removed; the handle is released when the source becomes invalid or the last
consumer leaves.

A conservative bridge for adapters that expose no resumable or version
capability may only perform one-shot bounded calls. It must not invent
continuations, stable versions, or cross-Ping identity. In that bridge a `null`
result retains the previous demanded value as stale, and a defer is not an
invalid result; the bridge reuses the existing minimum interval, work and
capture bounds. A migrated source wrapper maps the actual observed domain units
and the existing work or capture counters without double-charging the same
counter or dropping an independent guard; a legacy one-shot capture-budget
scan cannot be converted into domain units without an explicit bridge cost
mapping. Legacy paths without such a mapping stay on their current route and
are covered by contract tests only. A migrated observation uses its exclusive
provider path; unmigrated paths are unchanged and no cross-path deduplication
is claimed.

## Capture results

A capture result carries an optional payload, coverage facts, availability,
consistency, an optional source version and an optional next cursor. The
payload has three bounded forms: a whole-unit replacement snapshot of existing
presentation values, whose entries may be fractional, textual or boolean
scalars; a keyed fragment whose domain key/value entries are normalized,
bounded and immutable; and a keyed opaque fragment whose bounded opaque domain
keys map to deeply immutable opaque bytes under a code-registered codec
identity. The generic layer never decodes an opaque fragment: the identified
codec owns the domain normalization, validation and its own stricter encoding
bounds. The opaque form lets a domain carry lossless values, such as an exact
integer count or a display string beyond the presentation text bound, without
widening the existing presentation-value or section bounds. No payload form
carries live components, item stacks, or raw NBT, and none enlarges the
existing global record-value bounds; inventory's codec and limits are
independent and owned elsewhere. A `SnapshotRecord` may carry scalar or record
replacement for related units, but this introduces no general cross-source
transaction framework.

```text
CaptureResult {
  payload?: SnapshotRecord | KeyedFragment | OpaqueKeyedFragment
  coverage: { demandStamp, scanWatermark, progress }
  availability: readable | unavailable | invalid
  consistency: verified_snapshot | eventual | unknown
  sourceVersion?: opaque
  nextCursor?
}
```

When availability is unavailable or invalid the payload may be absent, and that
absence must never be interpreted as an empty snapshot.

The three result states are independent. Availability
(readable/unavailable/invalid), completeness
(continue/complete-demand-sweep/incomplete-budget) and consistency
(verified-snapshot/eventual/unknown) are judged separately. Consistency is
judged from evidence: a completed detached capture that was atomic by
construction is verified-snapshot even when no source version exists, while a
complete sweep that is neither snapshot-atomic nor backed by a stable version
keeps its eventual quality. Decoding the snapshot later, across ticks or
periods, does not downgrade its verification, and a missing version never
does. A defer caused by an exhausted budget is a scheduling result, not an
invalid source.

Coverage describes the truth of the whole sweep, not progress inside one page.
A completed narrow-range sweep must not be reused for a full-width demand.

Zero values and absence are distinct. A directly observed scalar, record field,
or collection value of zero is publishable under its domain's validity and
quality rules; it is not equivalent to a zero inferred from absence. A domain
may derive zero from absence only when a complete, usable scan validly proves
the key missing under that domain's rules. A complete sweep that is
neither snapshot-atomic nor backed by a stable version keeps its uncertain
quality. Evidence that is unknown, incomplete or unavailable never synthesizes
zero, even when it would display as grey. An unknown consistency state only
means consistent-version metadata is missing; it does not mean the data or
coverage is unknown, and atomicity is not a prerequisite for producing an
observed zero.

Sparse semantics distinguish an omitted key (unchanged), an explicit zero, a
deletion tombstone, and an unknown state; a tombstone is not zero. An optional
source version is evidence only and is not a protocol revision: the publication
layer assigns fences and revisions. A capture observation is likewise evidence
only: it is not source identity and does not by itself create or advance a
protocol baseline, which the owning domain establishes explicitly.

## Cost accounting

Cost accounting is expressed as logical roles:

```text
tryReserve(scope, codeOwnedUnit, upperBound) -> Ticket | Deferred
tryReserveAll(attempts) -> Tickets | Deferred
commit(ticket, measuredActual)
releaseUnused(ticket)
claimProgress(policySubject, watermark, boundedCoverage)
```

Admission precedes reading and allocation. A reservation covers one step or the
upper bound of a bounded call, and controlled access is charged by measured
actual use rather than self-report. A later report cannot undo an over-budget
access. The mechanism cannot preempt a provider call and does not guarantee
wall-clock time: it continues only while progress can be safely bounded, and
reports unavailable explicitly when it cannot be.

Units are owned by code. Inventory's charged unit is the slot: consuming a
captured snapshot's parse statistics spends the shared scan allowance and the
per-client or per-target logical progress, while capture, copy and freeze are
bounded by fixed work and the finite memory guard instead. A source with no
snapshot support keeps its live route and is charged by its live slots, and
future tank or read units would be domain-specific examples rather than
registered units of this contract. The supported snapshot shapes are owned by
[inventory](inventory.md#detached-snapshot-capture-and-schema).

Four ledgers stay separate — shared scan work, logical progress, wire bytes,
and retained memory — with no mixed or weighted unit and no universal
conversion.

A period reservation commits its measured actual use permanently for that
period: closing a settled period ticket refunds nothing further. A
retained-memory reservation is persistent instead: its upper bound is held
until it settles, and the measured actual retained cost stays charged for the
whole lifetime of the cached object, released only when that object's ticket
closes. The retained-memory ledger is one finite server-wide cap. Lowering the
cap never discards or rewrites an outstanding charge: committed retained bytes
and outstanding reservations stay accounted, the remaining budget may become
negative, and further admission defers until enough charges close.

Several ledgers may be admitted together as one all-or-none reservation:
attempts are tried in order, and when any ledger defers, every earlier grant is
released and later attempts are not invoked. An ordinary exception or error
during admission or result construction likewise releases every earlier grant
and rethrows the original failure unchanged, with any cleanup failure
suppressed under it. No read, encoding, or allocation may run before the
complete grant is held, and a retained ticket's lifetime follows the object it
pays for. This is bounded admission bookkeeping, not an arbitrary rollback,
weighted-unit, or general plugin framework.

One compatible shared observation is charged once: a live read or a snapshot
decode does not multiply the scan charge across compatible consumers. Every
consumer records its applicable logical progress once per quota subject and
coverage watermark; a consumer that reuses a cached capture pays no new scan
charge but is not exempt from logical progress. The domain selects the quota
identity, and it is not multiplied by a new Ping or a new view: inventory uses
the target or client, for example, and target slots are not multiplied by the
number of recipients. Wire bytes are charged per recipient, so N recipients
mean N copies.

Consumer lag is bounded and must not block other consumers. A cache miss must
not masquerade as a complete observation.

A shared object occupies one real memory cost; each recipient's index or cursor
is charged separately. Serialized fan-out pending delivery counts against the
logical queue bound, and both the finite queue bound and the aggregate bound
are enforced. Cache and assembly structures have their own finite engineering
bounds. Even an explicit unlimited mode keeps finite structural and memory
guards.

The send path reserves bounded memory first, then performs bounded encoding and
length measurement, and only then charges the remaining wire-byte ledger or the
reserved known upper bound; it never encodes first to bypass the guard or the
ledger. The wire-byte and retained-memory ledgers stay separate. Actual
framing and byte baselines are defined by the owning contracts.

## Sync publication

Sync publication is expressed as logical roles:

```text
publish(result, authorizedProjection, domainCodec, consumerContext, syncPolicy)
cancel(context)
rebase(context, stateFence)
```

A consumer context is opaque and includes a consumer identity, a recipient, a
session view, and its authorization context; it does not require a Ping.
Inventory tracking binds a Ping and recipient on top of that context, while a
preview owns its own request identity and must not invent a preview Ping.

Two publication forms are defined. `SnapshotOnly` carries a bounded whole-unit
replacement and can reuse the existing whole-section channel. `KeyedAbsolute`
carries a per-key absolute value. Not every simple field is fragmented. A generic
`SnapshotOnly` publication does not automatically enable heartbeat,
resynchronization or fragmentation; the domain sync policy chooses the
heartbeat and watermark cadence.

The domain policy owns what may be published. A preview may allow partial data.
Inventory tracking publishes a normal valid quantity update only after a
complete scan, as owned by [inventory](inventory.md). Control information —
source invalidation, hard stop, and the corresponding status and fence — is
independent of item quotas, takes priority, is charged to total bytes, and must
not generate valid quantities or fake zeros. Recovered valid data still follows
its new baseline and rescan requirements. The generic layer does not force
complete scans or heartbeats on other content types.

Consumers are isolated: one consumer's cancellation or rebase must not reset
another's baseline or progress. Merging keeps the latest absolute value per key,
including zero; a late revision for one key must not be discarded by a
packet-global revision for a different key. Sparse updates leave a missing key
unchanged rather than re-sending or zeroing it; a tombstone has domain meaning
and is not zero.

A state or baseline fence discards data older than it. Publication re-projects
against the current server policy and session at send time. There is no
`SUBSCRIBE` intent and a client cannot widen its own authorization; budget
exhaustion schedules a defer, while an unavailable or invalid source maps to the
result availability and the domain status or fence, never to a defer and never
to a pretend-complete publication.

A logical baseline may be fragmented, but each fragment is bounded and the
baseline commits only after it completes. A temporarily displayed value does
not mean the checksum is complete. A per-item stream for a known baseline is
applied item by item, not as one atomic batch.

Watermarks distinguish offered from delivered state, and a digest cut includes
updates merged into the same range. Writing to a send queue is not receipt.
Completion-barrier semantics are defined by the owning contract.

Group folding is domain-provided. A domain may define bounded group replacement
or tombstone operations; the core sees only opaque group membership and does not
understand item IDs or components. Group staging is bounded before it commits
logically, and it is charged to the domain-specified category, byte and memory
quotas. It is not a general transaction framework.

The generic `cancel` and `rebase` tools do not make ordinary-block inventory
rules apply to entities or external leases. Other content types do not
automatically gain heartbeat, expiry, or recovery policy because a domain
chooses them.
