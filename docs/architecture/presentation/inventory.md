# Inventory preview and tracking

This topic owns the inventory domain of the versioned presentation subsystem:
preview, tracking, item-variant identity, zero and component-fallback rules,
budget and sync policy, and source recovery deadlines. The generic mechanism —
source access, capture results, cost accounting, and sync publication — is
owned by [shared sources](shared_sources.md). The reason a tracked inventory
keeps the live Ping and original target identity instead of a source-instance
generation is recorded in
[D0008](../../decisions/D0008-inventory-source-recovery.md).

This is the adopted contract for the confirmed inventory design. The
ordinary-block preview foundation is implemented on the server and client as
headless runtime and model seams: the dedicated route and its configurable
budgets exist, and the server can open bounded preview sessions for accepted
ordinary-block targets. Tracking delivery (`SELECT`), the native input and HUD
facade, and the remaining provider contexts are not implemented; the contract
below stays normative for that work. Until a route is integrated, the existing
one-shot capture and whole-section presentation paths keep their present
semantics, and the existing server gates for permission, range, lock state and
safe reads are unchanged. Wire routes and message families are owned by
[network protocol](../network/protocol.md); persisted keys, bounds and defaults
are owned by the configuration topics. Marker lifetime remains owned by
[marker lifecycle](../authority/marker_lifecycle.md), target identity by
[target model](../identity/target_model.md), and mod-specific providers by
[Create integration](../../integrations/create.md).

## Source identity, invalidation and recovery

An ordinary-block source identity is the dimension, the block position, and the
original block registry ID, or a canonical container alias. A consumer binds
tracking to the live Ping and the original target identity. Ping identity is
not part of the physical source key, no source-instance generation is
introduced, and a block-state or property change with the same registry ID is
not a new source. Detecting a destroyed, missing or otherwise unavailable
source is an invalidity, not an empty result.

On invalidity, tracking stops publishing valid updates, discards or cancels
queued old valid state, and sends the invalidation status. That status is
control information: it is prioritized independently of item quotas and is
charged to total bytes under [shared sources](shared_sources.md#sync-publication).
Invalidity is separate from the ordinary marker lifetime and never removes,
shortens or extends it.

Recovery probing and polling are allowed before the hard deadline. Recovery
keeps the existing Ping ID, advances the inventory status revision, and
establishes a new inventory snapshot identity with a clean baseline per
Ping and recipient; the source is scanned afresh and old values and streams are
invalidated rather than merged. A different block type is not a valid source
while it occupies the position. If the original registry ID later returns while
the same Ping is still alive and before its hard deadline, the source may
recover.

The hard tracking deadline follows the Ping's own marker lifetime: tracking
ends exactly at the marker's expiry, and there is no independent inventory
tracking-duration setting and no earlier tracking-only grey interval. At expiry
or removal, all scanning and recovery probing stop, and an expired or removed
Ping never recovers. Other preview or tracking consumers must not reactivate an
expired consumer.

External providers keep their established stable target IDs, leases and removal
rules. The ordinary-block replacement tolerance neither extends nor revives an
expired external lease, and it does not rewrite target identity globally.
Entity and private-inventory contexts require their own owning contract.

## Preview

Inventory preview and tracking are enabled by default; no separate user
opt-in switch gates them. Each request still passes the existing server gates:
target and field permission, range acceptance, lock state, and safe-read
validation. The foundation preview currently resolves accepted ordinary-block
targets only; entity, private-inventory, and external-provider contexts report
unavailable until their owning contracts exist.

Holding the ping key starts a bounded initial inventory preview on a new
request path; it does not reuse the legacy subscription route. A preview may
begin before the Ping exists and therefore has its own request and session
identity, which must not be equated with a Ping or with the inventory snapshot
identity. A preview request binds to one bounded server target at open time and
never changes that target; the authoritative target is derived server-side, and
no player identity or client-selected item count travels on the request. The
server assigns bounded opaque entry keys scoped to the request, not item IDs.
The authoritative handoff from preview to tracking is defined by the owning
implementation contract; no preview Ping is invented.

The first batch is sorted by count immediately rather than after the scan
finishes. Newly discovered batches may reorder entries only before the user has
scrolled vertically; once scrolling has occurred, existing rows stay fixed and
new batches are appended after being sorted internally. A pure count change
never reorders an open list; reopening sorts the discovered data. Rows with
count zero are not removed.

The "updating" state ends only when every slot has been traversed at least once
and all data has reached the complete-scan watermark and been delivered; a
temporarily empty send queue is not completion. Unknown or not-yet-seen data
may be presented as missing with progress, and incompleteness that cannot be
repaired must be presented explicitly rather than hidden.

Preview may publish partial data. Its queue and merge behavior is bounded: a
per-client overage sends fewer entries or delays, and an indivisible send
attempt is dropped whole; a global overage delays, and the lowest-priority
traffic yields first. Merging is two-layered — the outer layer merges by
request-stream key (target and client) and the inner layer keeps the latest
absolute value per item key including zero. Dropping one sparse batch must not
lose another key's latest value and must not repackage duplicates. Preview
selection references support both normal and component-fallback forms, and
component identity must work in both.

## Tracking

Tracking is a snapshot plus an absolute stream of the Ping's selected items,
not a continuous copy of the whole inventory. Normal tracking scans whatever
slots its selected keys require, which may be a full sweep, and publishes only
the selected keys; an incomplete scan must not claim a total for a selected
key. The only full-ID exception is the component-fallback aggregate, whose
permission and completeness rules are owned below.

Only a complete scan produces a normal valid quantity update. One logical batch
may span several periods and may contain more entries than one fragment
carries; it must not rescan the inventory once per fragment. Stream updates are
revisioned per item key, so a late item for one key is not discarded by a
packet-global revision for another key. A key that has not been received stays
unchanged.

A selected item that is absent from a complete, successful, authoritative scan
is reported as zero even when the container holds other items; an empty
container is one such observation. Partial, unknown or incomplete observations
never report zero.

A periodic heartbeat carries only checksum and watermark information and never
resends state. A positive heartbeat value selects the periodic cadence within
the configured range, while an explicit zero disables the periodic heartbeat;
zero is its own value, not an unlimited sentinel, and does not disable other
repair or status messages. The configured numeric range and its default are
implementation and configuration values, not catalogued here. Abnormal
resynchronization uses a bounded retry cooldown; the initial push for a new Ping
is exempt from that cooldown but is not exempt from the byte budget.

A stream whose baseline is unknown is buffered only within a bounded window. If
the baseline is not obtained within the configured window, the stream is
dropped and resynchronization is scheduled after a cooldown. That window
governs only the unknown-baseline buffer: an admitted, stable, serviceable
fragment baseline may continue beyond it and complete. Repeated requests for
such a baseline must coalesce, resume, or reuse work rather than blindly
restarting, and no new timeout value is invented. Scheduling must prevent
unbounded restart or starvation against a stable, serviceable source, but
completion is not guaranteed under sustained overload or insufficient
bandwidth.

Baseline assembly is bounded. A temporarily displayed value does not mean the
baseline is complete, and a per-item stream is applied item by item rather than
as one atomic batch. Checksums are computed over the recipient's authorized
selected projection, its identity mode, and explicit zeros; offered and
delivered or transmitted watermarks are distinct, and a checksum must not
compare partial fragments with a future state. A gap or mismatch schedules a
throttled snapshot repair. Writing an update into the send queue is not proof
that the client applied it, and a mismatch alone does not justify a TCP-loss
conclusion.

## Item variants and component fallback

Variant identity is the item ID plus the normalized full component set. The
same item ID with different enchantments, damage or custom name is an
independent entry and consumes its own quota.

A "component too long" fallback triggers only when every component has been
stripped because of size. Omitting only the display component does not change
identity, and a naturally componentless item needs no fallback and is not
marked as component-too-long. Within one Snapshot ID, all variants of that item
ID — including variants that were not initially selected — merge into a total
with the grey component-too-long presentation, and the merge persists until a
new snapshot. This all-ID variant sweep remains subject to permissions, and an
incomplete scan must not claim an aggregate total. That persistence and reset
rule belongs to tracking. Preview
scope and the handoff to tracking preserve both the same-ID all-variant fold
and full variant identity, but add no user-visible reset policy and assume
nothing about whether closing and reopening a preview resets the fallback.

The recommended engineering design is an atomic per-ID replacement or
tombstone that prevents double counting and isolates old variant streams. Any
allowlist, key, hash, or reassembly hard cap is an engineering choice and is not
defined here.

## Budgets, queues and memory

Preview and tracking keep separate period, byte, slot and quota accounting, and
the server keeps a shared physical scan budget plus per-recipient wire-byte
accounting. Quota identity is preview-client for preview and target for
tracking, and neither is multiplied per recipient. The server-authoritative
per-client and global send-byte multipliers scale the byte allowances; they are
server settings, not a client display scale or UI preference, and their numeric
ranges and steps stay in implementation and configuration owners.

The physical scan budget is configurable and measured in slots; it guarantees
no provider wall-clock time, and provider calls need their own independent
safety bound. An unlimited physical mode is an explicit mode that removes only
the configurable slot cap: the finite internal work and memory guard and both
logical quotas survive, and unlimited is never implemented as a sentinel,
integer overflow, unbounded array, or unbounded work loop.

Pending memory is a single finite server-wide bound covering preview and
tracking together, configurable to a finite hard cap; it is not a per-queue
bound, and every finite derived queue enforces its own bound separately. The
unlimited mode for byte budgets still uses a finite structure and memory guard.
The retained-cost model and the wire-byte ceiling are distinct. Dirty state,
unsent values, snapshots and recipient progress cannot escape their ledger by
not being encoded; shared data occupies one real memory cost while byte fan-out
is charged per send; and the source cache, client assembly, and unknown streams
each keep an independent finite engineering bound.

Global wire accounting keeps its confirmed smoothing algorithm. With a period
base `B`, actual sends `s_i` in a period, and excess
`e_i = max(0, s_i - B)`, a rolling window of `n` periods satisfies
`sum(e_i) <= n * B`, and the unused base allowance of a period is not carried
over. The single-period peak is `(n + 1) * B` and the rolling window total is
`2 * n * B`. The send queue capacity is four times the average bound, where
the average bound is `2 * B`; the preview send queue instead holds four global
server period byte amounts. The default values of `B` and `n` are
implementation and configuration values and are not catalogued here. A fixed
internal control reserve plus a ratio covers status and control traffic; the
reserve and ratio are tuning values owned by implementation, and control bytes
are still charged to the total and prioritized.

Snapshot bytes are bounded on two axes at once. A per-fragment cap and a
per-client period total are both enforced. The period total spans all targets
for that client in the period and applies across the whole period; it is not
reset per fragment or per target, and the per-fragment cap is not a separate
free allowance. The initial push for a new Ping is exempt only from the
resynchronization cooldown, never from the byte budget.

## Provider layer and safety

The provider layer covers the vanilla container and worldly-container contracts,
Fabric Transfer item storage and inventory storage, the Forge item handler, and
the NeoForge item handler. Create's Vault is a NeoForge-only, registry-ID
aggregate adapter with its own small adapter limit; it is not a general
container solution and does not cover every container.

Shared reads are reference-counted and a leaving consumer must not block other
consumers. A shared value is detached and immutable; world and provider objects
live only on the server thread and only inside the local handle. An unknown
block entity's "empty" is not evidence; only a reliable identity plus a fresh
local observation may serve as a temporary hint.

Source reads must not trigger loot-table generation, force-load chunks, or
modify the world. If a read cannot satisfy those gates, the source reports
unavailable instead of pretending that every private provider API is supported.
The same safety rules apply to recovery probing.

## Status presentation

Inventory status is presented separately from item data: updating, unknown,
uncertain, incomplete, unavailable, invalid, component-too-long, and expired
are distinct states with their own semantics. An inventory with no stable
cursor that cannot finish enumerating within its admitted hard budget ends as
incomplete, and preview and HUD present that incompleteness explicitly; a
small container without a cursor may still complete within budget. A completed
non-atomic sweep with no stable version remains a grey uncertain result. Data
that is unknown, or known to be missing without a complete observation, is not
zero and is distinct from an unknown consistency state. The exact localized
labels are added as resource keys with the implementation; the English state
names above define the semantics. The native HUD workflow that consumes these
states — direction trail, root-centre abandonment and downward marker
cancellation, and candidate priority — remains owned by the picking capture and
wheel contracts, and the inventory topic owns only the data and status
semantics it publishes to them.
