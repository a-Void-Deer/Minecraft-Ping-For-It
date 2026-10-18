# Inventory verification

Evidence rules and verification expectations are owned by the
[verification guide](README.md). Pending live scenarios are listed in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).
Inventory list gestures and navigation are inventoried under
[interaction](interaction.md#inventory-list-gestures-and-navigation), and selector
overlay layout and frame rendering under
[rendering](rendering.md#selector-overlay-layout-and-frame-rendering).

## Inventory preview and tracking foundation

The inventory foundation has headless coverage for the preview route and its
supporting models. `InventoryC2SPacketTest` and `InventoryS2CPacketTest` cover
the version-one request and response codec round trips, bounded text and frame
limits, corruption rejection, part-range and duplicate-key rejection, the
status values, and an unsupported protocol value or a previous version-four
frame rejected on the version-one route; the
version-one `OPEN` grammar round-trips a native block or an
uncommitted external candidate with its exact locator, classification and face,
and rejects a committed external, entity or location target, a wrong protocol
version, overlong or non-canonical fields, and trailing bytes; an offer's
positive period values round-trip at their upper bounds
including `72001` and `Integer.MAX_VALUE`, while zero, out-of-range, and raw
overlong or noncanonical period encodings are rejected without defaults; the
offer's effective display and frame budgets round-trip as an exact legal pair,
while off-grid budgets and a pair without the reserved metadata headroom are
rejected without defaults, and an effective write rejects a display beyond the
configured cap and an illegal pair; the whole frame budget includes its header
and absolute decode bound and rejects one byte more, and the absolute display
envelope round-trips at its absolute bound. At the display/wire boundary, a
real registry-aware single-item JSON payload at the maximum display bound
round-trips under a legal frame offer, while one byte over that display, a
frame too small, and two maximum entries that overflow the frame are rejected
with the destination left atomic; exact measurement matches an independent
vanilla UTF codec for every response kind and maximal multibyte entries,
allocates no display-sized buffers, and scales its workspace with the actual
packet, and a failed write preserves the existing destination bytes and
indices. `InventoryChecksumsTest` covers the entry checksum model.
`InventoryStrictDecodeTest` covers the strict version-one `OPEN` grammar on
raw frames plus raw overflowing request/response frame
numbers and strict helper rejection of overflowing or noncanonical varints and
overflowing varlongs; it is codec coverage, not transport or admission evidence.
`InventoryScannerTest`, `InventoryScanBrokerTest` and `InventoryWireWindowTest`
cover bounded scanning, the shared-read broker, the wire window, and
incomplete/unavailable outcomes. `InventoryPreviewServerTest` covers preview
session negotiation, request binding, bounded budget admission, invalidation,
close, and the ignored forged `SELECT`; it also covers the preview progress and
retained-memory model headlessly: duplicate requests reuse only one client's
paid coverage while different clients pay independent logical progress for a
shared observation, close, reopen, disconnect, reconnect, and invalidation
never refund already-charged period slots, a client's progress is retained and
accounted once and released by the last request, pending-memory pressure defers
new client progress until room is released, departed-UUID churn returns to the
anchored round's retained cost, and an exact long count beyond double precision
survives publication. `InventoryRuntimeTest` covers the production runtime
seam: one admitted slot prefix, covering snapshot decode or unsupported live
slot reads, charged once against the shared scan allowance and shared by
preview, duplicate-preview and tracking consumers while
client and target logical subjects stay independent and finite; a memory defer
performs no resolver or read and retained lifetime ends with the paid period;
cursorless enumeration cannot invent continuation;
owner and frozen face split live sharing; repeated tracking Pings share one
target quota while a fresh select skips an older preview sweep; live cadence
and cap changes cannot refund spent usage; invalid rounds retire all shared
handles once without closing consumers and unrelated work progresses; a
throwing read or close releases all retained and reserved memory; a live
scan-allowance reduction preserves already-spent slots and blocks reads or
selection past the new cap; and zero-allowance provider validation defers after
the fixed provider-work budget is exhausted. With a snapshot-capable source,
preparation captures once and fixes the route independently of the remaining
slot allowance; a zero remaining slot allowance cannot consume non-empty
snapshot slots; the frozen slots decode across later periods under a low
per-tick allowance, keep their captured values after live contents change,
never fall back to live reads, and release the retained snapshot on the last
consumer close; an already-delivered cached empty snapshot completes another
consumer without revalidation, capture, or read; and a snapshot-memory defer
retries later and admits the capture once capacity returns. A restarted sweep
cannot reattach an older peer's incomplete round; an invalid probe retires the
shared round while paid quotas survive until fresh tracking recovery; a
rejected completed sweep does not replace the still-published previous result,
and terminal publication uses retained evidence rather than a fresh
self-consistent wrapper; publication-evidence admission defers before any
provider call and never invalidates on budget pressure; the evidence lease
releases pages at the last scanner restart and closes only when replaced or
when the last consumer leaves; and the bounded retained-evidence-lease set
allows a fresh replacement without an unbounded cache. A second batch of new
consumers cannot consume the replacement reserve: with normal new-observation
admission full, their preparations defer before resolver work, retain no memory
and cannot turn into replacement leases, while the existing consumers'
replacements proceed across periods and keep their prior publication evidence
until an accepted replacement; releasing the prior leases lets the waiting
consumers enter, and closing evidence never refunds the current target's paid
progress. Compatible aliased inputs still share one physical round when normal
observation admission is full, because the bounded round guard counts physical
rounds rather than a compatible consumer's references, and per-target-key
quotas stay independent. The owner and frozen face remain part of that source
key: one original block resolves separately for a different owner or face
instead of reusing a same-binding shortcut, and retiring an invalid origin
input invalidates only the handle bound to that input while a different
owner's and a different face's handles stay active. A shared physical
replacement hands off from a
predecessor to its successor: same-owner, same-subject peers skewed across
periods share the one replacement, a fast peer's accepted successor stays
serviceable to a slow peer that can consume it without a second physical
sweep, and the handoff is counted in the bounded scan admission so another
handoff cannot fork before the slow peer advances. Rejected, invalid or merged
successors detach their handoff, and closing, retiring or merging peers cancel
handoff-only references without dropping independently owned old publication
evidence or refunding paid target progress; a successor's cached-validity
admission defers before provider work and cannot authorize an invalid cached
successor. Handoff completion and close leave no reserved or retained leak. A
configured display cap threads into new observations and an invalid requested
cap normalizes into the bounded range; lowering reprojects retained metadata
monotonically through a bounded, deferrable pass that keeps its cursor with no
ticket or progress loss, and a later increase cannot resurrect an omitted
display through the same snapshot. A released completed sweep ends its
deferred tightening pass while keeping its cumulative projection summary,
a tighter ceiling mid-pass restarts the walk from the first retained slot, the
still-referenced encoded page stays charged until its round is released, and
closing a consumer during a deferred pass leaves no workspace and rejects
later work. Additional admission tests cover refusal before resolver, lock,
witness or content callbacks; mapped exact and aggregate witnesses; charged
cursorless blank visits and throwing attempts; attempted capture work remaining
paid without spending decode-slot allowance; and discovery grants propagated
through plan lookup and validity checks with attempted-prefix settlement on
defer or exception. Retained-input reprojection tests refuse before parsing,
preserve the cursor and exact long count, release temporary workspace, and retry
after work returns. These synthetic admission seams do not prove ordinary
native-container serviceability by themselves. Separate native-read wiring tests
exercise complete single- and double-chest preview, a live selection witness and
fresh tracking under unchanged default memory, with exact read counts and no
outstanding reservations. Lazy-consumption tests refuse a read without fresh
scratch after capture scratch has closed; caller-wiring tests cover shared
Catalog grants and attempted work settlement without resetting the allowance for
each item. Exceptional cursorless-prefix tests cover actual attempted visits,
including blank views and a throwing handoff, for both scanning and selection;
unobserved legacy callbacks retain their conservative accounting boundary.
`InventorySourceSnapshotTest` covers the source-access snapshot preparation and
decode seam headlessly: capture stays lazy until preparation and the prepared
route is fixed for the handle lifetime; explicit atomic-detached evidence
stays verified across partial and later steps, unknown evidence stays
eventual, and a real source version remains independent evidence; a zero-slot
empty snapshot completes as a verified empty observation only when its
validation probe is admitted, with fixed provider work and no slot scan
charge; a non-empty snapshot without remaining allowance defers before any
snapshot read and never falls through to live reads; a source without a usable
plan keeps the established live route and its live slot charge, and a live
version change mid-sweep downgrades verification to eventual; memory-pressure
deferral retries later; negative, over-bound and structurally oversized
captures report incomplete, while an invalid source, a failed capture or a
throwing snapshot page reports its terminal state without publishing empty or
partial data, releasing retained memory and charging attempted reads; and
close is idempotent and releases snapshot, source and retained memory even
when cleanup throws, with fatal errors propagated without stranding resources.
`InventoryAuthorityOrderTest` covers the production
admission order before any source read: rate and channel reject first,
authoritative target-gone precedes source admission, and the witness and
annotation steps sit inside production dedicated admission before storage with
no leaked reservation. It also covers the committed lifecycle around that
order: the tracking sidecar is installed before the first initial publication
even with no section intents, a committed publication failure cannot retry,
re-create, or turn into a retryable rejection, and the sidecar query follows
the committed tracking lease until removal. `InventoryItemPingTypesTest`
covers the chest item content policy: the actual chest tag grants the
inventory-only take type exactly once, a missing or merely name-matching tag
fails closed, and non-chest items and the regular property policy keep their
existing types without take. `InventoryPublicationIntegrationTest` replays the
production publisher's real encoded frames into the client session: a recovery
fence precedes the baseline snapshot per recipient, a grey baseline is replaced
without resurrecting an old stream, each recipient's receipt stays isolated, a
withheld stream is repaired by real completion controls at the negotiated
cooldown, an admitted multipart baseline beyond its periods defers future
digests and repeated `RESYNC` resumes the same baseline, a future heartbeat
without application delivery requests bounded repair, a completed digest with a
missing part repairs without partial comparison, a failed fence blocks baseline
parts until the same fence can be sent, and duplicate controls never reset
assembly or the gap timer; a later shared retirement withdraws an earlier
checked terminal preview before the publisher drains, a cached tracking
publication defers with zero provider calls and resumes the same baseline when
budget returns, a tracking restart keeps published completed evidence until a
fresh accepted result and invalidity fences real frames, and positive `HELLO`
periods round-trip exactly including their upper bounds. Request-scoped repair
tests withhold an earlier encoded preview part, then restore the cumulative
retained rows at a newer watermark without recapture, renegotiation or a marker;
cooldown coalescing, waiting for admitted parts, truthful updating/invalid/empty
replay and exclusive marker-bearing dispatch have dedicated cases. Same-ID
retirement cases vary discovery order and page width, preserve exact long sums
and unrelated latest rows, reject retired selection references, and replay old
encoded data into the real client without resurrecting exact variants. Queue
refusal with repeated request repair and an independently refused publisher-work
grant exercise separate contention seams, not live packet loss.
`InventoryBackendTest` exercises the production
backend orchestration headlessly: one bounded preview selection creates once
and the first tracking observation reuses neither the preview snapshot nor its
count; invalidation, recovery and hard expiry leave marker lifetime untouched
and stop probing after the deadline; revoked views purge recipient item queues
while allowing current-view negotiation controls and preserving the frozen
audience; identity aggregation is scoped to each observation, and a recipient's
resync remains isolated from peers; a stale selected item is rejected at one
bounded witness without an automatic retry; a
failed prepared create releases its sidecar reservations; an invalid
preview releases its physical round so unrelated work can progress; positive
resync periods at the integer maximum stay exact in an existing session; and an
actual resync at those periods rejects an immediate repeat without overflow. A
superseded oversized preview selection during a pending cap reconciliation is
answered with the recorded bounded `REJECT` without consuming the fresh preview
attempt, so the fresh reference after rebaseline still creates from the same
preview. Actual identity-refusal fixtures retain the last completed all-ID total
without publishing a partial total. Control-recovery cases retry a refused
current offer and a recorded selection outcome after capacity or transport
returns, without repeating selection or marker creation, and fence old-view or
disconnected outcomes. Aggregate-display work-refusal cases keep retirement and
the retained count retryable before constructing the bounded display. Current-view
control and aggregate-witness cases pass in the recorded focused run; they remain
headless authority and recording-transport evidence.
`InventoryReconfigurationTest` drives the production cap-change flow headlessly
through the real backend, publisher, wire and client endpoint: a higher configured
cap captures and delivers the full retained display; lowering gates item data until
the updated offer hands off, then rebaselines preview and tracking; a
superseded lower offer cannot unblock after the pair returns higher; partial
and complete display stripping preserve the exact variant key, selection
meaning and count; deferred reprojection keeps the gate, recovers, and rejects a
superseded select without consuming the fresh attempt. Separate actual
identity-refusal cases cover tracking retirement, completed all-ID evidence,
recipient-isolated resync and expiry, while exact-count resync after display
tightening has its own fixture. Multibyte displays are measured in UTF-8 bytes.
The recorded focused run reaches both recipients' updated tracking count and
the selection, retained exact-count repair and expiry assertions without raising
the default memory limit.
`InventorySyncPublisherTest` covers the production publisher headlessly: the
same-recipient per-period SNAPSHOT byte total spans multiple targets, revoke
and memory defer encode and send nothing, and an indivisible oversized preview
batch terminates incomplete instead of waiting forever. Retained entry versions
are owned by object identity and stay charged while any offered, delivered,
pending or admitted reference remains, so a delivered version survives a gated
replacement or a group replacement until the group commits, admitted-only
versions release on withdrawal, and repeated replacement returns to a stable
current-version charge. A data workspace that cannot be admitted refuses before
host encoding and is retried without changing the display, and a tiny control
refuses insufficient memory before measurement. The bounded control drain is
reentrant-safe: cancelled or closed queued controls are skipped, callback-added
or replacement work waits for a later drain, and an already-accepted control is
never retried after a callback failure. Retirement and restaging cases cover
withdrawal before replacement admission, bounded retired-group history,
delivered-version ownership, latest unrelated offers, and period-owned wire
tickets releasing at the owning period rather than at consumer cancellation.
Failed offer replacement and exact-owner control cancellation have independent
cases; these are bounded recording-transport seams.
`InventoryMinecraftSourcesTest` composes the
production source wrapper with a recording platform view: a same-alias topology
change invalidates before the cursor continues, a fresh observation recovers
all slots, snapshot-plan discovery stays lazy without capturing or saving NBT
and without replacing the live selection witness, an aggregate alias
without an explicit member layout keeps the live route, a completed
double-chest preview withdraws its cross-period encoded batch when the original
hit half becomes a legal single, normal content changes do not rebase or
recapture a stable cross-period preview, and terminal evidence detects a
same-alias same-count snapshot-layout change even when access remains
self-valid. A live catalogue adopts the effective display cap and tightens
monotonically, snapshot capture adopts the source catalogue ceiling, and an
effective display cap below the legal minimum is rejected. `SableInventorySourceTest` covers the provider-confirmed external
inventory source headlessly with recording level, membership and provider
ports: canonical aliases share only inside the same provider, sub-level, world,
owner and frozen face, same-registry candidates with different locators neither
short-circuit resolution nor share invalid retirement, an input-only probe or
validate failure discards only that alias's own references without retiring the
origin's completed evidence or refunding its paid target quota, an origin probe
retires the shared evidence even after the origin consumer has left so a valid
alias cannot publish a sweep whose originating evidence became invalid, an
invalid alias during a shared replacement releases its references without
pinning a stale handoff or blocking the origin's later sweep, each committed
input keeps its own lease evidence for completed publication, a cached alias
prefix cannot be consumed after its own point moves even while a shared
controller stays valid, a provider layout cannot introduce foreign members, a
foreign chest partner is denied before any state, block entity or content read,
a same-sub-level double chest keeps the canonical alias and member-local slots
without replacing the original external target, release during a contiguous
capture publishes no atomic snapshot, committed evidence invalidates on point
relocation or release while a fresh existing recovery recaptures, fresh world
and membership closures are used across ticks, a partial snapshot rejects a
member topology change without a mixed tail, a committed locator refresh keeps
the binding while a descriptor scope change or released capture is invalid,
frozen face, lock and loot safety hold for external chest capture, a provider
lookup or last-metadata revocation cannot reach a content read or an
enumeration handoff so a stale handle cannot replay revoked contents, a
metadata-denied indexed step charges its attempt without publishing a zero and
a fresh handle recovers, an indexed enumeration re-checks each read before
visiting content, and a mismatched detached candidate or wrong resolver
binding is rejected before any world read.
`InventoryMinecraftSnapshotCaptureTest` drives production view validation and
detached slot capture against bootstrapped vanilla block entities: single- and
double-chest capture uses resident observations with save/serialization traps,
preserves the original hit half, member-local slot zero and frozen face order,
and gates every member before content. Topology loss or final revocation
publishes no atomic snapshot; ordinary content changes leave an already frozen
observation unchanged. Unsupported or layout-less providers retain their live
route, while malformed explicit layouts and missing members fail closed.
`InventoryObservationSnapshotTest` covers immediate freeze before the next
provider callback, independent authoritative long amounts, empty slots, detached
lifetime, and failed-capture cleanup. Validation-ledger regressions cover refusal
before the next layout or content attempt, nonrefundable attempted prefixes,
separate cumulative allocation and peak workspace, and bounded Vault layout
revalidation. Profile cases cover layout-less discovery, changed-shape
rejection before expanded member reads, failed single-to-double lock checks,
and unchanged-default-cap admission. Symbolic lock-certificate tests independently
check call multiplicity, zero-lock behavior and memory containment; external
budget arithmetic is not executed native serviceability.
Cached reprojection-summary tests distinguish actual retained input from a
global ceiling, deduplicate shared catalogue owners, combine sequential scratch
by peak rather than sum, and refresh metadata after tightening, including null
projections. The
[capture and schema owner](../../architecture/presentation/inventory.md#detached-snapshot-capture-and-schema)
defines which extraction routes may claim snapshot evidence.
`InventoryNbtSnapshotTest` separately covers the caller-supplied extension's
detached per-member NBT structure and strict item decoding: per-member and
per-segment structure with a frozen-face mapping order that never widens to
hidden slots; unsigned byte slots and nontruncating integer slots; malformed
item IDs, counts, components, slots and hidden entries as read failures
instead of invented empty values; component SNBT strings rejected before
Minecraft custom-data decoding; explicit empty, sparse and explicitly
empty-face observations; a consuming custom counter schema whose mutation
cannot corrupt long counts beyond double precision; payload and decode
workspace reserved at capture; depth, cycle, node and array bounds failing
closed before deep copy; and detached, immutable exported tags with close
rejecting later reads. The configured display cap is adopted at capture and
tightens monotonically: a larger component JSON survives under the raised cap,
a tightening before the first read keeps the lazy decode at the tighter ceiling
without resurrecting the old display, a runtime tightening preserves the
partial decode cursor, and an invalid or below-minimum cap never mutates the
snapshot. `InventorySnapshotLayoutTest` covers the detached
layout model: positions, lists and nested mappings are detached and immutable,
structural members invent no slots, duplicate positions and invalid or
duplicate per-segment mappings are rejected, and the total visible bound spans
all members rather than each member alone. NeoForge's
`CreateVaultInventoryAccessTest` covers the segmented Vault access snapshot
layout: member order, controller role, member-local visible mappings and layout
data are preserved, and an invalid current layout exposes no snapshot
descriptor; its member-gate variant gates the root, controller and every member
before world or local reads and re-checks after revocation, including reads
through an already acquired member or local handler. `CreateVaultSourceWrapperTest` composes the real lazy
segmented Vault provider with the production common wrapper: a same-controller
rotation invalidates before the cursor continues, and a fresh handle recovers
the complete twelve-slot sum. `SableInventoryHandoffTest` covers the production
preview-to-tracking handoff with recording transports: a candidate preview is
request-scoped without allocating a marker key or provider reference, a valid
selection materializes once, binds the current physical root, witnesses the
live item and stores committed tracking with a fresh count, a wrong sub-level,
root, registry or inactive source rejects before the witness with a single
release and no store, a tampered owner or face binding rejects before
validation, witness or annotation, same-registry candidates do not cross-bind
across requests, a selection replay resends the stored result without a second
materialization, witness or reference, and an invalid source before the first
tracking publication publishes no quantity while recovery starts a fresh
baseline. `InventoryPresentationTest`,
`DedicatedDeliveryBoundaryTest` and `ClientDedicatedDeliveryTest` cover the
dedicated-delivery boundary: the inventory adapter is negotiated and
policy-catalogued but never sampled, masked, published or rendered by the
section machinery. `InventoryItemCodecTest` and `VanillaInventorySourceTest`
cover the ordinary-block item codec and source: canonical persistent component
identity with sorted bounded canonical bytes and digest-collision rejection,
partial display omission keeping identity without claiming component fallback,
and safe exact versus unsafe-identity aggregate selection. Additional component
fidelity and ordinary-item tests compare admitted persistent encodings against
independent native-codec oracles, including prototype defaults, explicit
removals, supported tools, equipment and food, nested items and potion-copy
semantics. `InventoryVanillaComponentCoverageTest` includes eight durable-item
defaults and a nonempty PROFILE with signed and unsigned properties in its
native persistent-identity comparisons. `InventoryRegistryContextGuardTest`
compares both nonempty enchantment component forms, including stored
enchantments, with tooltip visibility enabled and disabled against the native
codec. Registry-context and holder guards test unknown providers stopping
before callbacks, owner compatibility, unbound holders and admitted native
lookup shapes. Canonical-writer tests use an independent byte oracle for tag
kinds, key order, UTF and floating-point cases; adventure/SNBT cases compare
against native output. Root-boundary, ordinary-source, native-error and primitive
argument cases cover context admission before map access, capture exhaustion,
actual component descriptors, a large literal-name exact-identity control,
native success/error paths and refusal before unsafe numeric callbacks.
`InventoryCatalogAdmissionTest` compares digest bytes with an independent oracle,
exercises padding boundaries, sticky digest collisions and map treeification,
and checks exact/one-short grants and refusal before mutation. SUN-provider
bootstrap acceptance is exercised on the test JVM, not arbitrary providers or
JDKs. These finite oracles and the passing named-holder comparisons are not a
whole-root numerical certificate. Native JSON stack-growth tests observe actual
writer array identities, compare equal-node depth boundaries, and exercise
pre-call refusal versus exact-allowance native-codec controls.
`InventoryItemDisplayProjectionTest` covers the configured display cap: a
configured cap preserves a display beyond the default bound, partial or complete
display omission preserves identity, and monotone tightening never re-expands
retained displays. Fitting-envelope and malformed-input cases preserve unavailable
projection without reconstructing defaults. `InventoryItemUnavailableDisplayTest`
covers
nullable projection with unchanged metadata and shared canonical bytes, malformed
input, bounded aggregate metadata, shulker removals and name aliases.
`InventoryDisplayWriterTest` independently exercises retained-input syntax,
escaped and duplicate component aliases, conflicting removals, unknown
additions, nested opaque spans, depth and UTF boundaries, suppression overflow,
and all projection ladder outcomes. The
[item identity and display owner](../../architecture/presentation/inventory.md#item-variants-and-component-fallback)
defines their separation; no display-only refusal is identity-fold evidence.
`InventoryDomainCodecTest` covers the closed code-owned inventory observation
codec: the absolute display envelope round-trips within its bound, a display
beyond the absolute bound is rejected before encoding, a wide display length is
checked against the bound and the remaining input before allocation, the
empty/null/trailing rules are unchanged, and inventory values stay isolated to
the adopted version-one codec identity while a differently versioned
descriptor is rejected. `ClientInventoryV2Test` keeps
its historical class name and covers the version-one client preview channel:
an external candidate captured with a real press-time face is sent verbatim
with its provider locator and classification and no physical block is invented,
a capture without a real face or a committed external, entity or location
target opens no channel, and same-registry candidates with different locators
do not alias preview requests. `InventoryClientStoreTest` and
`ClientInventoryTest` cover the client connection session: preview projection
and its accepted barrier across every part, byte bound and fence, baseline
assembly, unknown-baseline buffer, checksum comparison and resync scheduling;
accepted per-key revisions own metadata and quantity together; byte-bound
replacement growth is refused before quantity, metadata, or cut mutation; the
tracking closed-watermark barrier closes only after every accepted distinct
part and a deferred heartbeat is rechecked once the cut closes; a future
baseline buffered before an invalidation completes recovery without readmitting
the old baseline; and an evicted snapshot or multipart-stream replay cannot
reopen its closed barrier or suppress repair. The negotiated effective caps are
enforced at admission: an oversized data frame or entry display is rejected
before any metadata, count, revision, watermark or baseline mutation, a lowered
same-epoch offer fences incompatible partial cuts and unknown buffers while
keeping compatible progress, committed counts and closed digests, a
metadata-only revision updates the display without changing the closed
checksum, an increase keeps stripped state and only later accepted data adds
detail back, a wrong-epoch offer cannot widen the accepted caps, delayed
old-cap frames cannot mutate, and the display cap is measured in exact UTF-8
bytes rather than characters.

`ClientItemDisplayTest` covers the client item-display decode seam: transmitted
encoded display data decodes into an owned render stack while malformed,
missing, unknown or oversized data keeps the registry-label text with an empty
icon rather than restoring prototype defaults or displaying raw text; an
identity mismatch is terminal for the icon as well. Successful decoded stacks
keep enchantments, glint override, custom data and custom model data and are
always count one regardless of the encoded source count; a normal variant keeps
its stack-dependent localized name
while custom and item-name forms use the chat composition with flattened plain
text; the bounded access-order cache refreshes recency, evicts the least
recently resolved variant under pressure, stays within its entry and byte
bounds, resets on registry or explicit reset, and never hands out a mutable
cached render stack; same-id variants stay distinct while identical payloads
reuse a template without aliasing; transmitted component removals survive the
retained naming and render stacks; and real vanilla delayed and warmed-inline
unresolved profile callbacks resolve owned working stacks without changing the
retained transmitted outer profile, name or display. Nested Lore or shulker
contents, including defaults materialized by native decoding, suppress unsafe
icons while supported ordinary nested contents remain. Projectile safety tests
compare with an independent native-decode callback baseline and prove the guard
adds no post-decode copied-stack PROFILE callbacks, including repeated cache
hits. Native nested decode may itself enqueue callbacks; recursive retained
PROFILE isolation and callback-free decoding are not established.
`SpatialInventoryRowDisplayTest`,
the inventory-bridge cases of `NativeSelectorContentTest`, and
`SpatialInventoryLayoutTest` carry that display through the native bridge into
the painted row projection: encoded display data survives both compatibility
projections, the row hands native rendering an owned count-one stack carrying
the transmitted components that cannot corrupt the next frame, custom names use
the shared quoted composition, and missing data leaves an empty icon and the
registry-label text without raw data. These are headless CPU, codec and
native-painter seams over real vanilla constructor verification,
resolvable-profile resolution and checked-executor handoff with only
network/session and main-thread executor ports: no GPU rendering, external
profile HTTP or live screen is exercised. `PlatformInventoryServiceContractTest`
covers the loader service contract, including the optional snapshot-layout
seam of the platform access bridge. `PlatformInventoryMemberGateTest` covers
the default member-gate overload: a denied root is rejected before the
single-position provider is called, and an admitted root forwards the exact
level, position and frozen face without widening to another member lookup.
Fabric and Forge add their own `PlatformInventoryServiceMemberGateTest` in
their loader test source sets over the actual native wrappers headlessly:
their storage and handler wrappers re-check membership at lookup and before
every slot count, slot, blank, amount, resource and enumeration visit, so a
reentrant revocation between world operations or during a slotted read, an
indexed or cursorless traversal, or a visitor callback rejects before the next
world or native call and a stale handle cannot replay; cursorless blank views
still consume their visited bound without a content read; a revoked cursorless
step reports unavailable and incomplete with no partial or synthesized entry,
charges its admitted bound and attempted provider work, and a fresh handle
recovers; ordinary unguarded contents and conjoined nested guards stay
unchanged, and fatal lookup or guard failures propagate. Fabric substitutes a
recording item-variant fixture for the cache mixin and Forge runs headlessly
with the vanilla Minecraft artifact ahead of the patched one; these
loader-source-set tests do not launch a loader or register mixins.
`PlatformInventoryResidentTest` in common and each loader adds resident-read
coverage for inherited or composed native graphs, frozen member/face scope,
long amounts independent of stack count, and live compatibility for uncertified
getters without using old copy/conversion routes. Unknown overrides, foreign
delegates and final revocation have negative controls; Vault locals have
save/serialization traps and an all-member final gate. Saved-delegate/stateful
metadata authority and overlong-lookup refusal have headless loader coverage.
`InventoryExternalFieldsTest` directly exercises the production cached-field
ports for both fastutil map capacities and PROFILE properties on ordinary,
untransformed external-library objects, without ServiceLoader substitution.
Equal-size maps with different resident capacities keep their actual capacities;
PROFILE reads retain the original backing and property objects; unsupported map
owners and subclasses reject before overridden methods run. Sparse map and
enchantment cases check budget refusal before an entry view or iterator is
created, with positive controls for the entry-view cache. The headless
`InventoryResidentAccessFields` fixture inherits these three production ports
and substitutes only Minecraft field reads. `InventoryResidentAccessorShapeTest`
checks the remaining Minecraft accessor descriptors and loader registrations;
it does not cover external-library ports or apply Mixins. Separate
isolated named-host probes exercise the selected registry, holder, builder and
enchantment raw ports on captured compiled Common, Fabric and NeoForge inputs;
Forge's registry/builder reads pass, but native enchantment initialization stops
at an absent active FML loading list. Exported getter-body checks pass for all
four profiles. Separate isolated nine-case RGB probes apply the overwrite on Common,
Fabric, Forge and NeoForge and compare native public-API results with an
independent formatter oracle, including native-constructor masking of negative
and high inputs and retained named colors. These probes do not establish
full production configuration or Fabric API coexistence, FML lifecycle, shipped
intermediary/SRG namespaces, arbitrary provider internals, or an installed Create
runtime.

An isolated named-module probe using the installed NeoForge dependency set and
SecureJarHandler exercises the production cached-field helper from an automatic
GAME caller module. Both direct access to fastutil and authlib in MC-BOOTSTRAP
and access through a modeled GAME → SERVICE → MC-BOOTSTRAP ancestor chain pass
the field and helper checks without target-package opens or added read edges.
This establishes named-layer library access, not a complete inventory preview:
it does not launch the FML lifecycle, real PLUGIN/SERVICE wiring, Minecraft,
Mixin-transformed PropertyMap or game-thread execution. Fabric and Forge have
no corresponding module-runtime probe evidence.

### Failure diagnostic seams

`InventoryCaptureDiagnosticsTest` checks a real Log4j test appender for the
original external-capture cast message and source frame, provider identity,
locator, frozen face and read owner without inventing physical coordinates.
Expected budget or access denials, directly or as cursorless causes, neither
emit a warning nor evaluate diagnostic context; an unexpected provider failure
with the same message still warns. `InventorySourceSnapshotTest` checks capture,
live-read and snapshot-read cast failures retaining original messages, frames
and source/target context with unavailable outcomes and no empty or partial
payloads. Failed capture never falls back to live reads and attempted reads
stay charged. Metadata/read failure followed by snapshot-close failure keeps
both exceptions and their causes in one report, closes once and releases all
retained/reserved state. Additional cases prevent self-suppression, report
unexpected cleanup associated with an otherwise silent expected denial, and
propagate fatal cleanup/read failures with attempted reads still charged and
recoverable cleanup attached to a fatal read. Resolve and live-witness cases
distinguish a real primary's combined report from a separately associated
unexpected cleanup after expected denial, including cursorless causes; denial
without cleanup stays silent and paid. Self/fatal combinations preserve failure
identity and one close, a positive witness with failing close returns false,
and failed replacement or wrong-scope resolution clears the prepared source
without double-close. Ordinary topology loss and expected direct/cursorless
read denial stay unavailable without an exception warning.
These diagnostic fixtures do not inject an external cached-field resolution or
access failure; the direct external-port tests above do not establish that
failure's log delivery.
`InventoryRuntimeTest` separately checks failed plan discovery with consumer,
round and source context, the attempted prefix still paid, no capture/read and
no retained or reserved leak.

`InventoryMinecraftDiagnosticsTest` checks layout callback messages, causes,
suppressed details, frames and read context, cross-position warning suppression
without extra content reads, and a failed layout callback retaining its live
route. Missing/unregistered schemas, absent optional bindings, ordinary lease
loss and membership denial stay silent; throwing resolver, current-binding,
membership and validity callbacks report their causes without changing denial
or fallback. Default guarded-access cases distinguish ordinary false/denial
from throwing gates/delegates, rate-control repeated gate warnings and propagate
a fatal gate error. `InventoryMinecraftSnapshotCaptureTest` checks lock, pending
loot, unload and topology loss without WARN or item reads, and a throwing frozen
authority callback diagnosed before capture becomes unavailable with its
attempted read counted. `InventoryContainerReadsTest` checks expected denial for
foreign delegates and pending loot without unpacking that loot table.

Each loader's `PlatformInventoryDiagnosticsTest` checks silent membership denial
versus a throwing gate's WARN and original cause. Fabric and Forge additionally
cover absent capabilities and throwing lookups at different positions with one
warning and no extra lookup, while Fabric checks the throwing-version fallback.
NeoForge's `CreateVaultInventoryDiagnosticsTest` checks silent revocation,
unloaded members and topology changes against the recording member fixture,
and throwing lookup, validation or snapshot metadata preserving unavailable or
absent results with cause-bearing warnings. These do not install Create or
launch any loader or Mixin runtime.

`InventorySyncPublisherTest` checks transport failure reports while retries keep
the same immutable packet and charge each handoff; repeated failure suppresses
the warning, not the retry, and later transport recovery progresses. Its
control-callback cases retain the data gate after accepted-transport callback
failure, report OFFER and POLICY independently, and defer callback-added work.
`InventoryAuthorityOrderTest` checks an ERROR with committed-marker and request
context after initial publication fails, while retaining the selected outcome
and tracking sidecar without re-entering admission or re-creating a marker.
These are headless production-call-site and test-appender seams; shared helper
coverage and logging-deployment limitations are owned by
[shared exception diagnostics](integrations.md#shared-exception-diagnostics).

### Tracking projection

`InventoryTrackingRendererTest`
projects received tracking data into bounded renderer lines: only received tracking data supplies bounded item lines, explicit zero
is retained, unknown or invalid counts are never synthesized as zero, status
and grey state stay explicit, and the tracking annotation resolves the shared
localized name policy rather than the raw label.

Across this foundation, the
[inventory list interaction](interaction.md#inventory-list-gestures-and-navigation)
and [selector overlay rendering](rendering.md#selector-overlay-layout-and-frame-rendering),
the coverage comprises headless runtime, model and production-seam tests over
recording providers and transports, plus a headless resident-slot capture of a
single chest and both double-chest members.
Snapshot atomicity is modeled for the snapshot-capable branch: the capture
test exercises bounded detached extraction against bootstrapped block entities,
not a live `ServerLevel`, chunk lifecycle, running loader or multiplayer
session, and the modeled branch is not a guarantee for every present or future
provider. Caller-trusted custom counters and decoders, external-trusted provider
internals, provider wall-clock safety, live-world-backed provider safety, installed
optional-provider wiring,
[native HUD integration](interaction.md#native-client-interaction-runtime), live transport,
[rendering](rendering.md#selector-overlay-layout-and-frame-rendering), GPU submission and the
[manual inventory matrix](manual-integration.md#pending-manual-and-integration-matrix)
remain pending.

## Known automated gaps

- the inventory preview-foundation, production runtime and publication tests
  cover per-client/target logical progress, snapshot preparation/capture and
  cross-period decode, accepted barriers, the tracking closed-watermark
  barrier, production selection admission order and publisher recovery/repair
  seams. Native input/HUD, live-world-backed provider safety, installed
  optional-provider wiring and live transport still require their own
  evidence, and no manual world, GPU, transport or multiplayer validation is
  recorded by those seams;
- inventory call-site logging coverage is limited to the
  [diagnostic seams above](#failure-diagnostic-seams). Other runtime
  probe/projection branches, capture-handle source-close and standalone
  snapshot/ticket-close diagnostics, scanner/broker and legacy preview-server branches lack
  direct log-event assertions. Test appenders do not establish production
  file-appender delivery or low-frequency behavior in a prolonged live soak;
- default-cap capture, live selection and fresh tracking have headless native
  seam evidence. Independent review establishes the composed root/native/Catalog/
  caller allocation and work bounds within the versioned static source-event
  model and reviewed finite guards. Cursorless attempted-prefix, symbolic
  lock-certificate and native JSON stack-boundary tests exercise those finite
  seams, not whole-JVM certification. Reviewed composition assumes caller-backed
  grants, fresh lazy-read scratch and an explicit process-fixed initialization
  boundary within the guarded standard JDK 21/25 envelope. It does not measure
  total JVM allocation or certify arbitrary JVM/providers, total heap, profiling,
  GC, TPS or elapsed time; external-trusted LIVE provider internals remain outside
  that certification;
- isolated named-host Mixin probes cover the selected raw ports on captured
  Common, Fabric and NeoForge compiled inputs, with Forge registry/builder reads
  and all-profile exported getter-body checks; separate RGB overwrite probes
  exercise native public-API boundaries on all four profiles. Forge enchantment
  runtime reads remain blocked by its real active-FML LoadingModList requirement.
  Full production configuration and Fabric API coexistence, FML lifecycle and
  transformation ordering and shipped intermediary/SRG runtime remain outside
  that isolated-host evidence; compilation and field-port tests do not close
  those gaps;
- inventory ordering/freezing, frozen snapshot continuity after ordinary
  content changes, empty-snapshot completion, explicit zero versus unknown,
  per-key revisions, client barriers and publisher recovery/repair have
  headless and production-seam evidence above, not live execution.
  Per-Ping/recipient baseline and resynchronization isolation are exercised at
  the production seam, and detached double-chest/cross-period preview,
  same-alias layout-change, restart/retirement and evidence-admission seams are
  covered by the tests above. The unknown-baseline window has a headless
  expiry/delete/RESYNC seam but no per-tick no-early-expiry proof or live
  roundtrip; the publisher's cross-target period accounting is headless for
  SNAPSHOT bytes only; the zero-heartbeat publication seam exercises watermark
  and repair without asserting the absence of a periodic HEARTBEAT. A maximum
  legal display value through the production server identity catalogue and its
  queue and budget admission remains unproven, as do transport-asynchronous
  heap retention and a live heap soak. Same-ID aggregate retirement, checked
  long sums and retained request repair have encoded-frame/client seam evidence,
  not live execution. Variant quotas, a real provider resolver and
  recovery-probe expiry, the hard stop at Ping expiry, a fresh capture after
  ordinary single- or double-chest contents change, a multi-period
  low-consumption quota completing stably, a real mod-provided bounded
  multi-member extractor or installed multi-member Create Vault, live-world safety
  invalidation and recovery, live-source failure diagnostics, and real
  world, multiplayer, tick-order and GPU execution remain manual/integration
  scenarios;
- headless provider-confirmed external inventory coverage is inventoried above,
  including `SableInventorySourceTest` alias/source independence and the Create
  Vault member-access tests. No automated test exercises live provider
  membership, a real loaded sub-level, a real provider layout or capability, or
  loader/runtime integration;
- item-display safety has headless decode, cache, row and callback-baseline
  evidence, not GPU or live profile-service evidence. Native nested decoding may
  create PROFILE callbacks before icon eligibility is known; the post-decode raw
  safety guard does not establish callback-free decode or recursive retained
  PROFILE isolation. Arbitrary mod component codecs/hooks remain outside those
  tests;
- the provider-confirmed external inventory source against a live Sable runtime
  and world: live membership and sub-level checks, a real provider layout or
  capability, member-gate revocation between operations, a live item witness
  and the preview-to-tracking handoff, and inventory reads on the three
  supported loaders;
