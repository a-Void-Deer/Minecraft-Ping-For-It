# Inventory preview and tracking

This topic owns the inventory domain of the versioned presentation subsystem:
preview, tracking, detached snapshot capture and schema, item-variant identity,
zero and component-fallback rules, budget and sync policy, and source recovery
deadlines. The generic mechanism —
source access, capture results, cost accounting, and sync publication — is
owned by [shared sources](shared_sources.md). The reason a tracked inventory
keeps the live Ping and original target identity instead of a source-instance
generation is recorded in
[D0008](../../decisions/D0008-inventory-source-recovery.md).

This is the adopted contract for the confirmed inventory design. Coverage scope
and pending runtime evidence for the ordinary-block and provider-confirmed
external preview and tracking,
item-choice create and native input/HUD seams are owned by
[testing and verification](../../testing/verification.md). The existing
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
original block registry ID, or a canonical container alias. For a double chest,
the source identity is a canonical alias over both half positions and the
original registry ID, so either half addresses the same source; the original
hit half's position remains the target and marker identity, and the alias is
source identity only. A consumer binds tracking to the live Ping and the
original target identity. Ping identity is not part of the physical source key,
no source-instance generation is introduced, and a block-state or property
change with the same registry ID is not a new source. A capture observation,
including a complete detached snapshot, is evidence about the source, never its
identity: it does not become the source key, a source-instance generation, or a
protocol baseline generation. Detecting a destroyed, missing or otherwise
unavailable source is an invalidity, not an empty result.

An external source identity is the provider scope, the provider-confirmed
sub-level, the canonical container alias, and the frozen read context of owner
and face. The original detached target identity stays separate from that
physical source binding: the provider-confirmed physical position never
replaces the original target, and a capture observation never becomes the
source identity. Each external candidate requires its own provider-confirmed
read binding and validity check; different opaque locators identify different
candidate read bindings, not necessarily different physical sources. Candidates
confirmed to share provider, sub-level, dimension, owner and frozen face, and
canonical container alias may share one physical source; a locator alone does
not establish physical identity. A committed source is addressed by its active
lease's current tracking point, so a locator refresh does not create a new
source and a stale locator is never adopted as the read position.

On invalidity, tracking stops publishing valid updates, discards or cancels
queued old valid state, and sends the invalidation status. That status is
control information: it is prioritized independently of item quotas and is
charged to total bytes under [shared sources](shared_sources.md#sync-publication).
A pairing, slot-mapping or other view-topology change during an in-progress
observation is detected as the same invalidity: the partial sweep is discarded
and a later observation starts afresh under the clean baseline and revision
fences rather than the changed view completing as if continuous. Invalidity is
separate from the ordinary marker lifetime and never removes, shortens or
extends it.

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
expired external lease, and it does not rewrite target identity globally. A
committed source whose tracking point moved or whose member topology changed
invalidates the old observation; recovery re-resolves the provider scope and a
wrong sub-level or tracking point is rejected rather than adopted, and the
fresh baseline preserves the marker, its hard expiry and the current lease.
Entity and private-inventory contexts require their own owning contract.

## Preview

Inventory preview and tracking are enabled by default; no separate user
opt-in switch gates them. Each request still passes the existing server gates:
target and field permission, range acceptance, lock state, and safe-read
validation. The preview resolves accepted ordinary-block targets and
uncommitted provider-confirmed external candidates; entity, private-inventory,
and committed-external contexts report unavailable until their owning contracts
exist.

Holding the ping key starts a bounded initial inventory preview on a new
request path; it does not reuse the legacy subscription route. The preview is
served from one initial snapshot capture and has no periodic capture cycle of
its own. A preview may
begin before the Ping exists and therefore has its own request and session
identity, which must not be equated with a Ping or with the inventory snapshot
identity. A preview request binds to one bounded server target at open time and
never changes that target; the authoritative target is derived server-side, and
no player identity or client-selected item count travels on the request. The
server assigns bounded opaque entry keys scoped to the request, not item IDs.
Opening the request runs the existing authority gates plus nonallocating
provider validation for an external candidate; it creates no TargetKey, marker
identity, provider reference or tracking state, and its quota identity is the
preview request rather than a candidate target key.

The preview-to-tracking handoff follows the existing create authority first:
the server materializes an external candidate once, then binds the preview's
original input to the committed target through both current provider-confirmed
physical read bindings. An ordinary block must match its original identity
exactly; an external candidate's current source and the committed source must
resolve to the same provider, sub-level, physical root block and registry, so
same-registry candidates and different hit roots cannot cross-bind. The
selected item is then witnessed live against the authoritative source, the
annotation is checked, and retained memory is admitted before the tracking
lease is stored. Any failure releases the acquired reference and stores
nothing. The tracking lease keeps the committed input; its first count comes
from a fresh complete authoritative server-side capture, never the preview
snapshot or a partial observation, and no preview Ping is invented. The
committed tracking association is installed after the marker is stored and
before the created notification is published, so the recipient's atomic initial
can project the inventory selection kind
([receipt content descriptor](presentation_snapshot.md#receipt-content-descriptor))
from this existing tracking association rather than a second chat metadata
cache.

Choosing one inventory item from the preview and releasing creates a new Ping
immediately; items are never accumulated into a staged multi-item selection.
The create passes the existing marker-admission authority and gates owned by
[target validation](../authority/target_validation.md), with the selected item
reference validated safely and immediately against the authoritative source
instead of trusted from preview data. That live witness is not authorized by
the preview snapshot or by any capture observation, and captured data never
stands in for it. The new Ping's whole-marker type is the
frozen Target Type's default Ping Type; the chosen Ping Type travels as a
separate item annotation rather than as the whole-marker type. The item
annotation's eligible Ping Type set is the target's effective
[property Ping Type policy](presentation_snapshot.md#property-ping) plus one
known [`take`](../identity/catalogs.md#ping-type-values) entry when the target's
actual tags carry `#c:chests`; a non-chest target, and every regular property
selection, keeps the property policy unchanged and never acquires `take`. The
client's item menu derives its eligible set from the accepted registry's actual
tags, while the server decides the annotation's allowance from the actual
provider-confirmed physical block and its live tags, so a client-expected
registry or a merely claimed tag never authorizes the annotation. The
annotation's quantity starts unknown, and its first authoritative count follows the
complete-observation rules in [Tracking](#tracking). The first tracking
observation uses a fresh authoritative capture; it reuses neither the preview
snapshot nor its count.

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
not a continuous copy of the whole inventory. Normal tracking reads whatever
slots its selected keys require, which may be a full sweep, and publishes only
the selected keys; an incomplete scan must not claim a total for a selected
key. The only full-ID exception is the component-fallback aggregate, whose
permission and completeness rules are owned below.

Tracking reuses the existing tracking period with no separate capture-cycle
setting: it consumes the current snapshot, and the next capture follows the
existing cycle after that snapshot is consumed. A new cycle never replaces a
snapshot that a consumer has not finished consuming and never resets that
consumer's progress; a slow consumer keeps its earlier snapshot instead of
being starved by newer captures.

The sampling owner's container read condition is the sampling authority: the
preview requester, or for tracking the bound Ping owner, is checked for lock,
loot-table, loaded, block-identity and view-topology state, and those checks
continue to run on every read, cache consumption and recovery probe. The
sampled data is delivered to the Ping's normal frozen
[audience snapshot](../authority/target_validation.md#audience-snapshot-at-create)
under the existing per-target-type
[field policy](presentation_snapshot.md#per-target-type-field-policy). A
recipient does not need to hold the container's key or reproduce the owner's
look or read condition, and no new inventory distance or audience policy is
introduced: the marker's established audience and acceptance range remain
authoritative. The owner's read condition is checked again before cached or
queued valid inventory is published: if it no longer holds, that data is not
sent to a recipient even when the recipient's field permission remains. This
send-time check asks nothing of the recipient and changes no audience or
distance rule. An already captured frozen snapshot is not rewritten by ordinary
content changes inside the container; a new capture or the view-topology
invalidity changes what is published.

Only a complete scan produces a normal valid quantity update. One logical batch
may span several periods and may contain more entries than one fragment
carries; it must not rescan the inventory once per fragment. Stream updates are
revisioned per item key, so a late item for one key is not discarded by a
packet-global revision for another key. A key that has not been received stays
unchanged.

A selected item that is absent from a complete, successful, authoritative scan
is reported as zero even when the container holds other items; an empty
container is one such observation. For a face-scoped source, a complete,
successful observation that finds no items is a valid empty observation of
that view, not a claim that the whole source is empty outside the slots the
frozen face exposes. Partial, unknown or incomplete observations never report
zero.

A periodic heartbeat carries only checksum and watermark information and never
resends state. A positive heartbeat value selects the periodic cadence within
the configured range, while an explicit zero disables the periodic heartbeat;
zero is its own value, not an unlimited sentinel, and does not disable other
repair or status messages. The configured numeric range and its default are
implementation and configuration values, not catalogued here. Abnormal
resynchronization uses a bounded retry cooldown; the initial push for a new Ping
is exempt from that cooldown but is not exempt from the byte budget.

A stream whose baseline is unknown is buffered only for the negotiated
resynchronization interval, measured in tracking periods; no independent
timeout setting is introduced. If the baseline is not obtained within that
interval, the stream is dropped and resynchronization is scheduled after a
cooldown. That interval governs only the unknown-baseline buffer: an admitted,
stable, serviceable fragment baseline may continue beyond it and complete.
Repeated requests for such a baseline must coalesce, resume, or reuse work
rather than blindly restarting, and no new timeout value is invented.
Scheduling must prevent unbounded restart or starvation against a stable,
serviceable source, but completion is not guaranteed under sustained overload
or insufficient bandwidth.

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
rule belongs to tracking. Preview folding is scoped to the current preview
request: closing that request ends the fold, and a reopened preview starts a
fresh scan that may choose exact or folded presentation for the same item ID
again; the handoff to tracking preserves both the same-ID all-variant fold and
full variant identity.

The recommended engineering design is an atomic per-ID replacement or
tombstone that prevents double counting and isolates old variant streams. Any
allowlist, key, hash, or reassembly hard cap is an engineering choice and is not
defined here.

## Detached snapshot capture and schema

Each supported inventory source is captured into complete detached NBT; that
inventory capture provides the atomic-snapshot evidence. Ordinary vanilla
container snapshots are therefore verified without requiring a source version.
The generic atomicity, version evidence and later decoding rules are owned by
[shared sources](shared_sources.md#capture-results). The shared scan allowance
charges consumption of the captured data, not this inventory capture; cost
accounting is owned by [shared sources](shared_sources.md#cost-accounting).

A Minecraft block-entity inventory is retained per member: each member keeps
only its inventory-related NBT together with a structure envelope that keeps
the member positions and identity, the controller or layout relationship, the
segments, and the frozen-face slot mapping. A multi-member source is never
flattened into a structureless tag or a single total. The default supported
shapes are the ordinary single block and the vanilla double chest; additional
member structure exists only through explicitly registered member providers,
and arbitrary mod multipart discovery is not promised. Client presentation
subjects never establish or widen the server inventory structure or permission:
a door, bed or other multipart render form, including a large waterwheel form,
is client presentation only.

The default block-entity candidates are a root `Items` list and an
`Inventory.Items` list, whose wrapper may also carry `Size`; other fields and
custom counts require an adapter port, and no arbitrary NBT recursion guess is
performed. A 1.21.1 item entry is its ID, count and components. Present
candidates with an ambiguous reading are never summed, and a missing candidate
is never an empty result: the ambiguity is recorded as a diagnostic and the
source falls back to its unsupported live route. An explicitly empty recognized
items list is a valid explicit empty observation. An empty view proven by a
recognized known omission — an explicitly empty frozen-face slot mapping, for
example — is a distinct evidence-backed known-empty observation, not a
substitute for a missing candidate. A recognized candidate that is present but
malformed is a read failure and is never treated as empty.

A supported snapshot capture that fails or exceeds a bound publishes no mixed
partial data: consumers receive an explicit unavailable or incomplete state,
never a partial snapshot presented as atomic. A source with no snapshot support
keeps its existing live route, whose completed non-atomic observations may
remain uncertain. Temporary memory pressure may defer a capture and must not be
reported as a completed or empty snapshot. Limit and failure events keep fixed
work, the structure guard and the finite shared memory, and are logged at low
frequency with the reason and the source context; raw NBT is never dumped.

## Budgets, queues and memory

Preview and tracking keep separate period, byte, slot and quota accounting, and
the server keeps a shared source-scan allowance plus per-recipient wire-byte
accounting. Quota identity is preview-client for preview and target for
tracking, and neither is multiplied per recipient. The server-authoritative
per-client and global send-byte multipliers scale the byte allowances; they are
server settings, not a client display scale or UI preference, and their numeric
ranges and steps stay in implementation and configuration owners.

The shared scan allowance is configurable and measured in slots. It is charged
by the parse work that consumes a captured source snapshot, and a source with no
snapshot support keeps its live route and is charged by its live slots. Snapshot
capture, copy and freeze are bounded instead by fixed work, the structure guard,
and the finite shared memory; capture is never a free or unbounded operation.
The allowance guarantees no provider wall-clock time, and provider calls need
their own independent safety bound. An unlimited mode is an explicit mode that
removes only the configurable slot cap: the finite internal work and memory
guard and both logical quotas survive, and unlimited is never implemented as a
sentinel, integer overflow, unbounded array, or unbounded work loop.

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
the NeoForge item handler. Create's Vault is a separate NeoForge-only member
provider over the tested Vault structure; it preserves per-member segmentation,
variant identity and a canonical controller alias rather than aggregating by
registry ID, and its version, loaded-member, controller-alias and
per-member-layout rules are owned by
[Create integration](../../integrations/create.md#vault-inventory-provider). It
is not a general container solution and does not cover every container.

Ordinary-block inventory source access is face-scoped. Every preview, tracking
and recovery read uses the face frozen at press time by
[capture](../picking/capture.md#ordinary-and-asynchronous-capture), one of the
six block directions; an ordinary-block source with no frozen face is
unavailable, and an unsided read or one inferred from the reader's current
look is never substituted. The frozen face is read context only and is not part
of target identity, the canonical target key, or the marker identity. The safe
vanilla route is preferred: the vanilla container contract exposes
face-invariant slots, while the worldly-container contract exposes only the
exact slot mapping its frozen face permits; a worldly-container source that
supplies no mapping for that face is unavailable rather than read
face-invariantly, while an explicitly empty mapping is a valid complete empty
view of that face rather than an unavailable source. A vanilla lock,
loot-table or other safety denial makes the source unavailable; the read must
not fall through to a loader capability. Only an unsupported vanilla route may
try a loader item-storage capability, and only for that same selected face; a
missing or denied selected-face capability is unavailable and never widens to
a null, unsided, or different face. These failures report unavailable, not an
empty result.

A provider-confirmed external candidate uses the same frozen local face, one of
the six block directions, and never an unsided or look-inferred direction. Its
membership scope is resolved fresh for each operation and is never retained
across ticks; a stale or cross-tick predicate, a moved source or a changed
member topology makes the source invalid rather than read. Every supported
loader's capability route is entered only under that same frozen local face,
and a capability read is re-checked against the current membership scope, so a
revoked member or handler is unavailable rather than read.

A double chest is read as the pair, never either half alone. The pair is a
valid source only when both halves are loaded, share the same container type,
form a reciprocal pair, and both halves pass the owner's loot-table and lock
gates before either half is read; a missing, mismatched, non-reciprocal or
denied half makes the source unavailable, not empty. Both halves are read
under the same frozen face as one consistent source view, each contributing
the slots that face permits; a pairing or mapping change is the view-topology
invalidity owned by
[source identity, invalidation and recovery](#source-identity-invalidation-and-recovery),
not a completed mixed view. Any registered member source follows the same
all-members precheck: every member is gated before any member content is read,
and a failing member makes the source unavailable rather than partially empty.

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
sweep that is neither a verified detached snapshot nor backed by a stable
version remains a grey uncertain result. Data
that is unknown, or known to be missing without a complete observation, is not
zero and is distinct from an unknown consistency state. The exact localized
labels are added as resource keys with the implementation; the English state
names above define the semantics. The native HUD workflow that consumes these
states — direction trail, root-centre abandonment and downward marker
cancellation, and candidate priority — remains owned by the picking capture and
wheel contracts, and the inventory topic owns only the data and status
semantics it publishes to them.
