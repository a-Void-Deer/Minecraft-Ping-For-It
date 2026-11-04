# Inventory verification

Evidence rules and verification expectations are owned by the
[verification guide](README.md). Pending live scenarios are listed in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).
Inventory list gestures and navigation are inventoried under
[interaction](interaction.md#inventory-list-gestures-and-navigation), and the
stay-open selector path under
[interaction](interaction.md#double-click-danger-and-inventory-stay-open);
selector overlay layout and frame rendering are inventoried under
[rendering](rendering.md#selector-overlay-layout-and-frame-rendering). The
stay-open path reuses that ordinary single-item selection, already inventoried
through the backend and selector-session fixtures; it changes when that
selection is submitted, not the preview or tracking admission, so this page's
foundation evidence is unchanged by it. Request absence versus a legitimate
empty `updating` preview is exercised by the stay-open runtime fixtures
inventoried under [interaction](interaction.md#double-click-danger-and-inventory-stay-open);
it adds no preview-admission evidence here.

The [patch-only contract](../../architecture/presentation/inventory.md#item-variants-and-component-fallback)
has test-source assertions inventoried below. Reading those assertions does not
establish that they passed; the completed integration run and remaining live
verification gaps are scoped
[below](#patch-only-identity-and-display-verification-pending).

## Fluid inventory coverage

The confirmed [resource-lane contract](../../architecture/presentation/inventory.md#resource-lanes-and-scope),
[fluid identity/display/quantity contract](../../architecture/presentation/inventory.md#fluid-variants-and-quantities),
[staged operation accounting](../../architecture/presentation/shared_sources.md#cost-accounting),
[Create tank gates](../../integrations/create.md#fluid-tank-inventory-provider)
and [Sable fluid transition](../../integrations/sable.md#fluid-sub-level-transition)
have passing headless coverage in code input `628c033b`, including the approved
[weak historical evidence](../../architecture/presentation/shared_sources.md#weak-historical-identity-evidence).
The 2026-11-02 [final combined-tree record](build-checks.md#fluid-inventory-final-combined-tree-verification)
owns the exact input, command, fresh/reused task results, counts and expected
Common-only skips. Pending live checks are listed in the
[fluid inventory manual scenarios](manual-integration.md#fluid-inventory).

Committed foundation assertions in `InventoryResourceFoundationTest` cover
exact unit conversion and checked arithmetic, lane/owner/face identity
separation, amount/display independence from exact identity, identity refusal
and checksum domain separation including empty cuts. `InventoryFluidCodecTest`
uses an independent binary literal above double precision, truncation and
malformed-input cases, detached display bounds and codec isolation.
`InventoryV2LaneTest`, `InventoryC2SPacketTest`, `InventoryS2CPacketTest` and
`InventoryPresentationTest` exercise resource tags, strict route/grammar
boundaries and independently authorized fields. `ClientDedicatedDeliveryTest`
additionally covers schema-two advertisement, both accepted catalogue fields
and old-inventory-schema exclusion while Basic creates and updates still work.
`PlatformFluidServiceContractTest` exercises the absent-service fallback,
indexed blank visits, bounded observer completion, pre-callback refusal and
nonrefundable attempts; it does not execute native loader capabilities.

`InventoryFluidDisplayCodecTest` has nine committed methods for the independent
versioned literal, writer/reader agreement, expected-fluid fence, optional name,
UTF-8/Unicode and complete-document bounds, unknown/duplicate fields, wrong
types/versions, malformed/deep/trailing input and forbidden native/rich-text/icon
instructions. Its identity and selection controls preserve exact long amounts
and keys through display refusal. These are native-free grammar/codec assertions,
not server native-name extraction, installed fluid rendering or end-to-end
receipt evidence.

The matrix records automated coverage on that final input.

| Acceptance surface | Passing automated evidence and boundary |
| --- | --- |
| Shared request and independent lanes | `InventoryResourceLanesTest`, `InventoryResourcePublisherTest`, `InventoryV2LaneTest` and `ClientInventoryLanesTest`: shared open/close/replay, independent policy/baseline/completion/checksums, cross-lane token/control rejection, lane-local denial/recovery and sibling continuity. |
| Runtime and quota composition | `InventoryFluidRuntimeTest` and `InventoryResourceLanesTest`: one journal/runtime/global ledger, shared physical/logical consumption, paid failed visits, bounded catalogue/retained evidence, exact owner/face isolation, cursorless incompleteness and cleanup; no fluid pool. |
| Fluid port and shared admission | `FluidPortLifecycleTest`, `InventoryFluidAdmissionTest`, `InventoryValidationAdmissionTest` and `InventoryFluidWorldTest`: staged admission and real caller grants, source-remaining limits, no double boundary charge, normal/nested/sequential and abnormal close orders, each owner's attempted work settled once, refusal/overflow and ticket/workspace cleanup. `InventoryFluidRouteDiagnosticsTest` covers INFO deduplication. |
| Weak cross-acquisition proof and history | `FluidAddressWitnessTest`, `InventoryFluidWorldTest` and `InventoryFluidScopeFramesTest`: hostile native equality/hash callbacks, schema/order/length differences, deterministic clearing, admitted initial capture, comparison without recapture/content reads, closed external access and original-proof ownership through retained publication. Changed/cleared evidence rejects old cuts; fresh scans recover without permanent defer, false zero or old-proof revival. |
| Non-owning server context | All five `InventoryFluidServerContextTest` cases: a stable source-owned non-native facade, no owning graph path to server/level/member/Access/Context/closures, deterministic server-reference clearing, current-level loss, post-getter failure, fresh-source recovery and authorized scope-only recomputation. This is deterministic headless ownership evidence, not live GC measurement. |
| Native provider safety | Each loader's `PlatformFluidServiceTest` covers current native metadata/witnesses, face/member/owner gates and provisional getter postchecks. `InventoryFluidLoaderIntegrationTest` crosses actual loader adapters/APIs with recording fixtures: equal detached layout cannot hide replacement routes or Fabric address reordering; cleared original witnesses retire and a fresh scan recovers. The same scenarios run on each loader classpath, not a loader launch. |
| Identity, amount and selection | `InventoryResourceFoundationTest`, `InventoryFluidCodecTest`, `InventoryFluidFreezerTest` and `InventoryResourceLanesTest`: persistent variant identity, exact checked long amounts, identity/display refusal separation, overflow/getter failure without completed zero, provisional selected seed and permanent retirement on real invalidity. |
| Publication, client and receipt | `InventoryFluidScopeFramesTest`, `ClientInventoryLanesTest`, `PendingContentChatControllerTest` and `ClientPingRuntimeContentReceiptTest`: final source checks, multipart barriers/repair, history-only quantities and property exactly-once emission. Creation-snapshot server coverage is detailed below; cross-boundary client chat is in [presentation verification](presentation.md#immediate-inventory-creation-snapshot-coverage). |
| Fluid presentation | `ClientFluidDisplayTest`, `InventoryFluidDisplayCodecTest`, `InventoryQuantityFormatTest`, `InventoryListModelTest` and content-receipt fixtures cover literal/base-fluid projection, display refusal isolation, grouped ordering, exact quantity/approximation boundaries and composed content. GPU icons, native font fitting and narrow-window layout remain unverified. |
| Create | `CreateFluidTankArchiveTest`, `CreateFluidTankMetadataTest` and `CreateFluidTankAccessTest`: tested-version/archive gates, controller/member/scope/mode checks, controller-only frozen-face reads, denied creative/boiler/virtual routes without fallback, shape/capacity admission, topology/handler replacement and postchecks. These are archive/headless seams, not installed Create gameplay. |
| Sable membership and fluid-only transition | `ExternalBlockMembershipTest` and `SablePhysicalMembershipTest` cover positive/negative/unknown physical membership. `InventoryFluidWorldTest`, `InventoryFluidScopeFramesTest`, `InventoryFluidServerContextTest` and `ClientInventoryLanesTest` cover continuous scope-only `UPDATING` with retained quantity, fresh complete replacement, old-cut/seed/partial fencing, and real failure/expiry controls. Simultaneous scope/identity/layout change still invalidates. Installed Sable remains pending. |
| Formal regression/build | Completed on the identified final input; exact execution and loader-native skip handling belong to the linked build record. |

### Native fluid boundary regressions

The following regressions passed on the
[native fluid combined-tree input](build-checks.md#native-fluid-boundary-and-presentation-verification),
extending the earlier final-input matrix.

| Boundary | Passing automated evidence |
| --- | --- |
| Complete fluid definitions | `InventoryFluidCodecTest` counts full lane/registry/variant keys against the 256-definition guard, including equal canonical bytes across IDs and exact/aggregate definitions. Repeated keys remain usable at capacity; refusal leaves retained collision evidence untouched, while collision and canonical-payload guards remain independent. |
| Paid-prefix capacity exhaustion | `InventoryNativeFluidBoundaryTest` covers 257 complete keys from 129 encodings, aggregate-only and extension-journal exhaustion, a refusal inside one paid page, explicit readable `INCOMPLETE`, charged attempts and unrelated item progress. `InventoryFluidWorldTest` repeats the 257-key/129-NBT case through the native source/freezer. No completed total or absence-zero replaces the selected historical seed. |
| Pending final validation | `InventoryNativeFluidBoundaryTest` keeps the paid cursor through repeated final-check budget defers, retries metadata with both logical and physical address allowance exhausted, and publishes the accumulated total without another getter. Current revocation or an exception still invalidates; a deferred final check cannot turn capacity exhaustion into completion. Empty-source completion and ticket/retained cleanup have separate controls. |
| Bounded native names | `FluidDescriptionsTest` covers detached translation/literal leaves, discarded styles/events, Unicode/byte bounds, complex/cyclic/opaque refusals without flattening and optional callback failures. Capture-admission and fatal failures propagate through standard native wrappers; cyclic/deep wrapper chains fail closed without opaque cause callbacks. `InventoryFluidFreezerTest` checks native translation labels versus literal registry fallback, no base-name/custom-name conflation and independent exact/aggregate identity and long amounts through name failure. Native first-initialization refusal installs no observation; an independent retry keeps the exact identity and amount. |
| Strict client display | `ClientFluidDisplayTest` covers local native translation/literal/fallback precedence, locally verified server labels, literal registry fallback rather than invented block keys, literal custom-name composition, and optional naming failures with a validated fluid icon identity. Refused/mismatched projections and unknown local fluids never invoke native naming or restore the icon. |
| Server/client separation | `FluidPresentationClassSafetyTest` executes absent/broken name-service discovery with client/render classes forbidden. Actual SPI constructors propagate capture-limit subclasses, sentinel `OutOfMemoryError` and `ThreadDeath` without poisoning discovery; repeated attempts and bounded reentry have controls, while ordinary/linkage failures remain optional. Its installed-payload integration requires current adapters for every loader before checking client-free naming and atlas-free render-service construction. This is classloading/compiled-boundary evidence, not a dedicated-server launch. |

`InventoryFluidPreparationRetryTest` separately covers a retained unprepared
round retrying through the shared preparation entry without resolving the source
again, while admission, debits, authority and final validation remain unchanged.
These are headless caller-grant cases, not part of the historical combined-tree
pass, a full loader build or a live source run.

`CreateFluidTankArchiveTest`, `CreateFluidTankMetadataTest` and
`CreateFluidTankAccessTest` separately cover the current verified controller's
boiler mode with stale former-controller state. That is archive/headless
coverage, not installed Create gameplay or a combined loader run.

Mixed-lane selection assertions belong to
[interaction verification](interaction.md#inventory-list-gestures-and-navigation),
quantity fitting and sprite dispatch to
[rendering verification](rendering.md#base-fluid-icon-presentation), and actual
loader APIs and the Create tea archive to
[integration verification](integrations.md#native-fluid-presentation).

### Production lane policy and native amount regressions

These regressions passed in the
[lane-policy, native-amount and icon-opacity execution](build-checks.md#lane-policy-native-amount-and-icon-opacity-verification),
extending the earlier fluid coverage.

`InventoryProductionPolicyTest` drives actual `PresentationServer.tick`
permission derivation and backend ticks through a recording network service.
ITEM/FLUID deop and reop keep the presentation epoch, view and SECTION mask,
without a shared `RESET`. Assertions preserve the sibling's accepted preview,
tracking recipient and baseline, paid cursor, publication watermarks and admitted
queue. The revoked lane retires queued data, baselines and tokens; reallow admits a
fresh scan and baseline under the same parent request. `InventoryResourceLanesTest`
also checks fresh lane-local admission without rereading the sibling.

`InventoryProductionPolicyTest` and `InventorySyncPublisherTest` cover retaining
each lane's exact desired `POLICY` packet until `transport.send` returns normally
under the current session, epoch, view and authority. Queue admission is not
completion or a client ACK. Admission refusal, budget defer, transport failure
and authority discard leave bounded retry work; packet-identity deduplication
prevents duplicate queue entries. Old equal-valued completions cannot clear a newer
desired packet, and authority changes before send returns keep it retryable.
View/epoch changes, close, session replacement and disconnect fence retired
callbacks. Recursive drains defer callback-added work, callback runtime/linkage
failures remain isolated, and cancellation/cleanup release retained memory.
Production server/client ticks check eventual authorization convergence, sibling
continuity and no repeated policy sends after successful handoff.

Controls cover the shared target quota and close boundary, exactly-once delivery
of a pending `SELECTED`, all-lane denial retaining the Basic known marker,
control-admission retry and fresh marker receipt authorization. SECTION permission
and child-policy configuration changes retain shared `RESET` controls. Native
content, world setup and transport are fixtures, not a server or multiplayer
launch.

Forge and NeoForge `NativeFluidAmountTest` use real native stacks through the
production handler adapters; Forge requires the patched-first integration
classpath. Retained negative constructor/setter amounts are rejected before an
empty visitor, even when the public getter reports zero.
Forge also tests negative native NBT; NeoForge distinguishes its native parser's
rejection to legitimate `FluidStack.EMPTY` from adapter rejection of a retained
negative value. Controls cover zero/empty and positive/max amounts, Forge public
getter overrides, paid single null/throwing getter attempts, and amount-access
failure followed by recovery.

The runtime replacement assertions keep prior complete quantity evidence when a
negative native read yields `UNAVAILABLE`, non-complete output with no slots and
cannot be retained as completed. They do not replace it with exact zero. These
seams use fixture identity bytes and world binding, not full native identity-codec
integration.

## Inventory preview and tracking foundation

Headless test-source assertions cover the preview route and its supporting
models. `InventoryC2SPacketTest` and `InventoryS2CPacketTest` cover
the version-two request and response codec round trips, bounded text and frame
limits, corruption rejection, part-range and duplicate-key rejection, the
status values, and an unsupported protocol value or a previous version-four
frame rejected on the version-two route; the
version-two `OPEN` grammar round-trips a native block or an
uncommitted external candidate with its exact locator, classification and face,
and rejects a committed external, entity or location target, a wrong protocol
version, overlong or non-canonical fields, and trailing bytes. `OFFER` period
coverage is inventoried under
[Resynchronization and budget policy verification](#resynchronization-and-budget-policy-verification).
The offer's effective display and frame budgets round-trip as an exact legal pair,
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
indices.

`InventoryC2SPacketTest` decodes an independent golden literal `RESYNC` and
compares the writer byte-for-byte with it, fixing the version-two
header, explicit ITEM lane, request long, true presence byte and marker long. Zero request ID is
accepted; a missing marker is corrupt and rejected before any write mutates the
destination. Literal mutations reject false and noncanonical presence bytes,
missing presence and every marker truncation, a negative request ID, and
trailing bytes, consuming the rejected input. `InventoryS2CPacketTest` uses
literal UTF-name frames independent of the production enum codec: every retained
status is accepted in `PREVIEW`, `SNAPSHOT`, `STREAM` and `STATUS`, and every
retained entry quality is accepted in the three data kinds. Both `UNAVAILABLE`
and `EXPIRED` are rejected at each of those status and quality positions, with
the failed frame consumed rather than reinterpreted.

`InventoryS2CPacketTest`'s sparse-display cases additionally compare a sparse
display payload with a full-effective resident-patch baseline for stone, an
ordinary tool and a damaged tool: a native full-map item JSON oracle round-trips
count one and every effective component. The synthetic full-map patch has a
different native variant key; the wire-size comparison deliberately assigns
both display payloads the same authoritative selection metadata rather than
treating their source patches as identical. The production packet writer emits
exactly its measured encoded size, `readSafe` consumes the complete frame
without corruption and recovers the same packet and entries, and the sparse
frame's actual encoded size is smaller than the full baseline. These are
headless frame-codec assertions over a limited representative sample, not a
live network or full send-queue/byte-budget measurement; the general packet and
live-transport boundary remains owned by
[identity and authority](identity-authority.md#domain-identity-and-authority).
An additional packet round trip distinguishes an ID-only terminal display from
unavailable `null`, preserving exact long counts, revisions and selection keys
without setting the aggregate flag.

`InventoryChecksumsTest` covers the entry checksum model.
`InventoryStrictDecodeTest` covers the strict version-two `OPEN` grammar on
raw frames plus raw overflowing request/response frame
numbers and strict helper rejection of overflowing or noncanonical varints and
overflowing varlongs; it is codec coverage, not transport or admission evidence.
`InventoryWireWindowTest` covers the wire window.
`InventoryBackendLifecycleTest` exercises the production backend and runtime
through public packet, tick and memory-ledger seams:

- duplicate preview requests share one client's paid progress; a repeated
  `OPEN` adds no target admission or progress allocation. Idempotent `CLOSE`
  releases only its request, the peer continues the same sweep, and cancelled
  data cannot escape later drains. The last close stops resolution, validation
  and reads; paid-period tickets retire at their owning period;
- separate per-client and server-wide slot-limit cases keep close/reopen from
  refunding spent slots, including a different UUID trying to reuse global
  allowance. The next period admits exactly one new slot;
- duplicate disconnect cannot double-release ownership. Reconnect negotiates a
  fresh epoch without refunding global usage, old-epoch `OPEN` cannot bind or
  advance the new request fence, old `CLOSE` cannot cancel its same-ID successor,
  and no queued old-session output crosses reconnect;
- retained-memory pressure defers before resolution, validation or reads,
  without an empty or terminal preview. Releasing pressure resumes the bound
  request without another `OPEN` and completes its original sweep;
- 300 sequential guest UUIDs with one anchored request return to the anchor's
  retained cost after every paid period, without stranding reservations or
  exhausting live admission. The anchor has no periodic preview recapture, its
  source survives guest disconnects, and the final disconnect stops source work.
  Fixture cleanup balances every resolved source and retained/reserved charge.

`InventoryRuntimeTest` covers the production runtime's exact legacy/unsupported
source seam: one admitted slot prefix, covering adapter snapshot decode or live
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
the fixed provider-work budget is exhausted. With an explicit snapshot adapter,
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
quotas stay independent. On that exact path, owner and frozen face remain part
of the source key: one original block resolves separately for a different owner or face
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
witness or content callbacks; mapped exact and aggregate witness helpers; charged
cursorless blank visits and throwing attempts; attempted capture work remaining
paid without spending decode-slot allowance; and discovery grants propagated
through plan lookup and validity checks with attempted-prefix settlement on
defer or exception. Retained-input reprojection tests refuse before parsing,
preserve the cursor and exact long count, release temporary workspace, and retry
after work returns. These synthetic admission seams do not prove ordinary
native-container serviceability by themselves. Separate native-read wiring tests
exercise complete single- and double-chest preview, a direct live-witness helper and
fresh tracking under unchanged default memory, with exact read counts and no
outstanding reservations. Lazy-consumption tests refuse a read without fresh
scratch after capture scratch has closed; caller-wiring tests cover shared
Catalog grants and attempted work settlement without resetting the allowance for
each item. Exceptional cursorless-prefix tests cover actual attempted visits,
including blank views and a throwing handoff, for both scanning and selection;
unobserved legacy callbacks retain their conservative accounting boundary.
`InventorySourceSnapshotTest` covers explicit-adapter source-access snapshot
preparation and decode headlessly: capture stays lazy until preparation and the prepared
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
authoritative target-gone precedes source admission, and source evidence and
annotation checks sit inside production dedicated admission before storage with
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
fresh accepted result and invalidity fences real frames, and legal `HELLO`
periods round-trip exactly. Same-ID
retirement cases vary discovery order and page width, preserve exact long sums
and unrelated latest rows, reject retired selection references, and replay old
encoded data into the real client without resurrecting exact variants. Queue
and transport recovery use normal preview publication: an aggregate-required
slot at index 10 withdraws an admitted, transport-blocked exact cut before the
replacement encounters a recorded `QUEUE_CAPACITY` refusal. Restored queue
capacity admits one immutable watermark while transport still refuses it;
releasing transport delivers one cumulative eight-row cut, with the independent
long sum `9_007_199_254_741_011`, the latest seven unrelated rows and the earlier
unrelated selection reference intact, without rereads, another offer, marker
creation or a second completed cut. Late old exact data cannot resurrect a
variant. An independently refused publisher-work grant exercises a separate
contention seam. Cross-tick partial-preview publication keeps the paid cursor,
baseline and opaque key;
repeating a tick cannot refund physical allowance, and generic partial quantities
remain updating with uncertain quality until source completion. These are
encoded-frame/client assertions, not live packet-loss tests.
`InventoryBackendTest` exercises the production
backend orchestration headlessly: one bounded preview selection creates once,
seeds the selected retained preview count, and starts a fresh tracking observation
whose accumulators reuse neither the preview snapshot nor its count;
invalidation, recovery and hard expiry leave marker lifetime untouched
and stop probing after the deadline; revoked views purge recipient item queues
while allowing current-view negotiation controls and preserving the frozen
audience; identity aggregation is scoped to each observation, and a recipient's
resync remains isolated from peers; item absence does not veto an otherwise
valid reference or add a creation-time getter, and selection replay creates
only once; a failed prepared create releases its sidecar reservations; an invalid
preview releases its physical round so unrelated work can progress. A refused
terminal `INVALID` preview control retries on normal ticks without recovery
probes, reads or restored quantities and stops retrying after handoff. Initial
snapshot-plan memory refusal leaves preparation and reads unperformed, leaks no
reservation and never reports a completed empty observation; later admission
performs one preparation and read. Transport refusal retains the same admitted
immutable preview part at watermark one, then delivers it once without a new
source read or a rebuilt cut. Cooldown coverage is inventoried under
[Resynchronization and budget policy verification](#resynchronization-and-budget-policy-verification).
A superseded oversized preview selection during a pending cap reconciliation is
answered with the recorded bounded `REJECT` without consuming the fresh preview
attempt, so the fresh reference after rebaseline still creates from the same
preview. Actual identity-refusal fixtures retain the last completed all-ID total
without publishing a partial total. Control-recovery cases retry a refused
current offer and a recorded selection outcome after capacity or transport
returns, without repeating selection or marker creation, and fence old-view or
disconnected outcomes. Aggregate-display work-refusal cases keep retirement and
the retained count retryable before constructing the bounded display. Current-view
control and aggregate-selection cases pass in the recorded focused run; they remain
headless authority and recording-transport evidence.
`InventoryBudgetDelayIntegrationTest` checks that a delivered completed preview
stays deduplicated under retained-memory pressure while validity checks continue:
normal ticks add no reads or frames and report no false delayed cycle. Restoring
capacity emits no duplicate cut, and later source invalidity still produces
`INVALID` without another item read.
`InventoryPatchIdentityFlowTest` adds backend assertions across preview,
selected-value bootstrap and the first fresh tracking capture: two exact keys of
one item ID remain separate rows and selecting either performs no item getter
and tracks only its count. One key with decorated, ID-only or unavailable display
forms remains one selectable row with summed authoritative counts; display
variation does not change selection meaning. Supplying the raw exact key
instead of the request-scoped opaque reference is rejected. The fixture assembles
all parts of one completed preview cut without mixing sparse publications, then
drives tracking to its first complete snapshot within an explicit tick bound.
The fixture supplies
pre-frozen stand-in keys and a recording transport that measures real packets;
it does not exercise native patch encoding, replay frames into the real client,
or prove a live provider-to-client path.
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
batch terminates incomplete instead of waiting forever. Its independent
fragment-budget case retains an admitted large display while sending is
blocked, then asserts that the identical display payload is sent when the
allowance rises; scheduling pressure does not strip it. This fixture supplies
preprojected display text and does not exercise native item projection.
Retained entry versions
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
all slots, explicit snapshot-plan discovery stays lazy without capturing or saving NBT
and without replacing the direct live-witness helper, an aggregate alias
without an explicit member layout keeps the live route, and a native double-chest
sweep starts its normal cumulative complete cut with parts still admitted across
periods. A topology change to a legal single at the original hit half withdraws
those pending parts; ordinary content changes instead let the same immutable cut
finish once with every index and checksum intact, without rebaseline, rereads or
NBT saves. Terminal evidence detects a same-alias same-count snapshot-layout
change even when access remains self-valid. A live catalogue adopts the effective
display cap and tightens
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
and membership closures are used across ticks, a partial native sweep rejects a
member topology change without a mixed tail, a committed locator refresh keeps
the binding while a descriptor scope change or released capture is invalid,
frozen face, lock and loot safety hold for external chest capture, a provider
lookup or last-metadata revocation cannot reach a content read or an
enumeration handoff so a stale handle cannot replay revoked contents, a
metadata-denied indexed step charges its attempt without publishing a zero and
a fresh handle recovers, an indexed enumeration re-checks each read before
visiting content, and a mismatched detached candidate or wrong resolver
binding is rejected before any world read.
`InventoryMinecraftSnapshotCaptureTest` explicitly invokes the bounded capture
plan and production view validation against bootstrapped vanilla block entities:
single- and double-chest capture uses resident observations with save/serialization traps,
preserves the original hit half, member-local slot zero and frozen face order,
and gates every member before content. Topology loss or final revocation
publishes no atomic snapshot; ordinary content changes leave an already frozen
observation unchanged. This is an explicit whole-capture seam, not the default
native preview/tracking route. Unsupported or layout-less providers retain their live
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
selection materializes once, binds the current physical root, seeds committed
tracking without a content getter and then scans for a fresh count; a wrong sub-level,
root, registry or inactive source rejects before item data with a single
release and no store, a tampered owner or face binding rejects before
validation, preview evidence or annotation, same-registry candidates do not cross-bind
across requests, a selection replay resends the stored result without a second
materialization, getter or reference, and invalidity after the seed publishes no
new quantity while recovery starts a fresh
baseline. `InventoryPresentationTest`,
`DedicatedDeliveryBoundaryTest` and `ClientDedicatedDeliveryTest` cover the
dedicated-delivery boundary: the inventory adapter is negotiated and
policy-catalogued but never sampled, masked, published or rendered by the
section machinery. `InventoryItemCodecTest` and `VanillaInventorySourceTest`
cover the ordinary-block item codec and source: canonical persistent component
identity with sorted bounded canonical bytes and digest-collision rejection,
partial display omission keeping identity without claiming component fallback,
and safe exact versus unsafe-identity aggregate selection.
`InventoryComponentFidelityTest`, `InventoryVanillaComponentCoverageTest` and the
ordinary-source, adventure, encoding-admission, native-error, native-text-stack
and primitive-argument fixtures compare canonical identity with the native
persistent-patch codec. Native-family controls explicitly restore tool,
equipment, food and other component values into patches, rather than relying on
inherited defaults to exercise value encoding. The vanilla coverage also
includes a nonempty PROFILE with signed and unsigned properties.
`InventoryRegistryContextGuardTest`
compares both nonempty enchantment component forms, including stored
enchantments, with tooltip visibility enabled and disabled against the native
codec. Its sword control explicitly carries `TOOL`, preserving direct and named
holder-set coverage under both root and frozen registry contexts. In the audited
native fast path, registry-context and holder guards test
unknown providers stopping before callbacks, owner compatibility, unbound holders
and admitted native lookup shapes; that refusal is not a blanket restriction on
the generic external-trusted codec route. `InventoryItemFrontGuardTest`'s
no-codec/value-hook fixture uses an unregistered component type, not an
unfamiliar registered persistent type. Canonical-writer tests use an independent
byte oracle for tag kinds, key order, UTF and floating-point cases; adventure/SNBT
cases compare against native output. Root-boundary, ordinary-source, native-error
and primitive argument cases cover context admission before map access, capture
exhaustion, actual component descriptors, a large literal-name exact-identity
control, native success/error paths and refusal before unsafe numeric callbacks.

`InventoryPatchesTest` has direct assertions for the shared reader: surviving
equal-default additions and redundant removals, no reconstruction of
native-normalized entries, unchanged copy-on-write state and backing identity,
no prototype traversal or value comparison, and reconstruction with independent
backing and caller-owned mutable values. Array, open-hash and empty native
backings exercise admission and capacity-read accounting. Unsupported outer
maps/backings, malformed entries and invalid capacities refuse before the
visitor; admission and visitor failures propagate unchanged.

`InventoryPatchIdentityTest` compares directly supplied patches with
`DataComponentPatch.CODEC`, including an exact empty patch whose nonempty digest
is separate from the item ID and aggregate sentinel. Raw equal-default additions
and redundant removals stay distinct, native-erased changes stay absent, and
root count does not change identity. Container, bundle, projectile and food
conversion cases compare child patch, count, slot and order semantics with the
native codec. Raw-access traps fence prototype/default reads for both resident
stack and standalone-patch inputs. These are native encoding seams, not live
provider or installed-mod evidence.
Four additional nested-family controls distinguish a truly empty child patch
from transient-only additions or removals: independent native child and outer
codecs preserve the present-but-empty `components` envelope, slot/list order and
each family's native count form. Their canonical identities reflect that saved
envelope distinction while their ordinary displays agree. Separate root controls
keep empty and transient-only patches canonically identical.

The generic compatibility, external-context and owned-return-tree cases here
inventory assertion scope, not an execution log.
`InventoryGenericComponentCompatibilityTest` compares registered scalar, record
and map identities with native persistent-patch encoding, including a
cache-wrapped codec. It asserts distinct implicit-empty and still-explicit
equal-default identities, override/removal without prototype encoding, transient
addition and removal exclusion, and refusal before an unregistered codec runs.
Error-with-partial and oversized returned encodings assert aggregate mode
without partial canonical
identity; a throwing codec case asserts exception propagation and subsequent
reuse of the capture budget. Server-side display assertions keep exact identity
while omitting the opaque value with an explicit removal and retaining an
ordinary native display field. A non-cache-wrapped record codec's encode counter
asserts that display tightening and reinterning retain the exact key without
re-encoding the resident component; a native encoding-cache hit cannot mask a
repeat call in that fixture. A real custom `Registry<Integer>` key is resolved
through two supplied providers with different registered values: each result is
compared with its provider's native encoding oracle, the resulting bytes
differ, and returning to the first provider reproduces its bytes. These are not
client decode, working-stack-hook, installed-mod or live tracking cases.

`InventoryComponentBoundsTest`'s owned-persistent-tree assertions preserve native
tag kinds, typed empty lists and empty-string compound keys, and compare
canonical bytes after producer mutation. Unknown returned-tree backing and
cycles refuse without invoking their callbacks; an allocation-refusal case
checks admission before output copying. The generic compatibility fixture also
checks that later mutation of a codec-returned compound, lists and arrays leaves
the detached identity and display unchanged, including preservation of an empty
list's native INT element type. These assertions bound owned
returned-data processing, not the codec work that produced the tree.
`InventoryRegistryContextGuardTest` separately asserts lazy native custom-registry
lookup against the supplied provider without enumerating its registry graph,
reuse of the native lookup cache, and refusal to treat that external context as
an audited native owner. The classification, display and explicit trust boundary
remain owned by
[inventory](../../architecture/presentation/inventory.md#component-classification-and-encoding-trust).

`InventoryCatalogAdmissionTest` compares digest bytes with an independent oracle,
exercises padding boundaries, sticky digest collisions and map treeification,
and checks exact/one-short grants and refusal before mutation. SUN-provider
bootstrap acceptance is exercised on the test JVM, not arbitrary providers or
JDKs. These finite oracles and the named-holder comparisons are not a
whole-root numerical certificate. Native JSON stack-growth tests observe actual
writer array identities, compare equal-node depth boundaries, and exercise
pre-call refusal versus exact-allowance native-codec controls.
`InventoryItemDisplayProjectionTest` supplies configured-cap, unchanged-identity,
monotone-tightening, fitting-envelope and malformed-input assertions.
`InventoryPatchIdentityTest` additionally reaches the terminal display through
actual registered removal patches, compares native identity at high and low
caps, and retains the same key and terminal display through catalogue tightening,
a later raise and reinterning. `InventoryItemUnavailableDisplayTest` checks
terminal reprojection with shared canonical bytes and adjusted retained cost,
while malformed input remains unavailable. It also covers bounded aggregate
metadata, shulker removals and name aliases.
`InventoryDisplayWriterTest` independently exercises retained-input syntax,
escaped and duplicate component aliases, conflicting removals, unknown
additions, nested opaque spans, depth and UTF boundaries, and the ordinary
projection ladder. Fresh and retained suppression-size overflow assert canonical
ID-only output. Terminal grammar cases distinguish that exact form from
whitespace, escaped keys, mismatched or invalid IDs and an ordinary empty
`components` object; a maximum-length legal ID fits, and repeated reprojection
at lower or higher caps stays terminal. Nonfinite values, malformed/late invalid
entries and work-budget refusal do not gain the size-terminal recovery. The
[item identity and display owner](../../architecture/presentation/inventory.md#item-variants-and-component-fallback)
defines their separation; no display-only refusal is identity-fold evidence.

Sparse display-default inheritance has headless fixture families in
`InventoryItemDisplayProjectionTest`, `InventoryGenericComponentCompatibilityTest`
and `InventoryDisplayWriterTest`. Native local-inheritance, explicit-entry and
ordinary-tombstone assertions distinguish native-normalized omissions from
surviving equal-default additions. Server default-read traps accompany unknown,
mutable-text, NBT, PROFILE and nonempty nested-default fixtures: defaults supply
neither identity input nor synthetic display overrides or unknown-component
tombstones. Explicit nested container, bundle, projectile and food-conversion
patches retain sanitized sparse child displays. A local-default-drift fixture
decodes one sparse patch against different server and client defaults and
restores the registry afterward. Execution evidence and its limits are scoped
[below](#patch-only-identity-and-display-verification-pending).

`InventoryDomainCodecTest` covers the closed code-owned inventory observation
codec: the absolute display envelope round-trips within its bound, a display
beyond the absolute bound is rejected before encoding, a wide display length is
checked against the bound and the remaining input before allocation, the
empty/null/trailing rules are unchanged, and inventory values stay isolated to
the adopted version-one codec identity while a differently versioned
descriptor is rejected. `ClientInventoryV2Test` covers the version-two client
preview channel:
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

`ClientInventoryTest` starts marker removal with nonempty completed tracking and
a buffered future baseline: `markerRemoved` clears tracking and the unknown
buffer, and the known-marker gate rejects late `STREAM`, `SNAPSHOT`, `STATUS`
and `HEARTBEAT`, including newer baselines. `ClientPingRuntimeContentReceiptTest`
exercises the real presentation `REMOVED(EXPIRED)` dispatch with a retained
nonempty provisional quantity and buffered future baseline. Inventory state is
cleared while the independent stale marker visual remains; late data and
controls and a delayed `CREATED` cannot restore inventory admission or rows.
Content-feedback cancellation is inventoried under
[presentation verification](presentation.md#content-receipt-chat-and-pending-completion).

`ClientItemDisplayTest` covers the client item-display decode seam: transmitted
encoded display data decodes into an owned render stack, while a malformed,
missing, unknown or oversized display payload keeps the registry-label text
with an empty icon rather than displaying raw text; this decode-failure fallback
does not stand in for a sparse patch's ordinary omitted defaults, whose local
inheritance is covered above. An identity mismatch is terminal for the icon as
well. Successful decoded stacks keep enchantments, glint override, custom data
and custom model data and are always count one regardless of the encoded source
count; a normal variant keeps its stack-dependent localized name
while custom and item-name forms use the chat composition with flattened plain
text; the bounded access-order cache refreshes recency, evicts the least
recently resolved variant under pressure, stays within its entry and byte
bounds, resets on registry or explicit reset, and never hands out a mutable
cached render stack; same-id variants stay distinct while identical payloads
reuse a template without aliasing; transmitted component removals survive the
retained naming and render stacks; and real vanilla delayed and warmed-inline
unresolved profile callbacks resolve owned working stacks without changing the
retained transmitted outer profile, name or display. Explicit nested Lore and
shulker-content fixtures exercise icon safety while supported ordinary explicit
nested contents remain. Separate inherited-default assertions below deliberately
retain local nested values; they do not certify or sanitize those defaults.
Projectile safety tests
compare with an independent native-decode callback baseline and prove the guard
adds no post-decode copied-stack PROFILE callbacks, including repeated cache
hits. Native nested decode may itself enqueue callbacks; the selected unresolved
head fixture runs a queued decode callback without resolving its detached
returned head. These controls do not establish callback-free decoding or general
asynchronous PROFILE isolation.

Ordinary native-family controls exercise tool, food, book, firework, banner and
map icons on initial resolution and cache reuse, independently of the narrower
item-hover copier; they do not certify inherited-default isolation.
Explicit working-value mutation fixtures isolate custom
name text from both later cache hits and the previously composed name; container,
bundle, projectile and food-conversion child stacks retain their native encoded
count semantics while later hits isolate child-stack and nested-text mutations.
Additional cache-hit controls detach raw and filtered written-book pages, both
PROFILE property maps, custom NBT and mutable potion effects. These are specific
ordinary working-stack ownership assertions, not universal isolation of every
component or asynchronous producer.

Food controls in `ClientItemDisplayTest` use explicit `FOOD` patches encoded by
`FoodProperties.DIRECT_CODEC` with nonempty effects, rather than only prototype
defaults. They retain probability and native effect identity, distinguish the
codec's hidden-chain projection from direct working-copy isolation of a resident
hidden chain, and keep mutations out of retained transmission and later cache
hits. Native wrapper construction also honors the remaining root allowance.

`ClientDisplayFoodFabricTest`, `ClientDisplayFoodForgeTest` and
`ClientDisplayFoodNeoForgeTest` cover ordinary food icons, effect probability and
cache-hit ownership on their headless test artifacts. Common and Fabric exercise
direct resident effects; Forge uses its vanilla-first test runtime, not a patched
Forge launch. NeoForge uses the actual patched artifact, checks its private native
constructor and codec-produced capture against the native constructor's anchor,
and exercises field-only resident access without invoking a supplier. Its direct
copy controls isolate resident and hidden effects; unknown counting, throwing and
other-call-site suppliers refuse with zero callbacks and without source mutation.
Accepted metadata after explicit-patch refusal is checked by the common client
fixture below. NeoForge's inherited-FOOD fixture retains the native default
reference without invoking its unknown supplier, while preserving the exact
entry, long count and selection reference and using the terminal registry label.
Loader-local test service
fixtures supply
fixed-field resident ports without applying Mixins; these are codec/access and
ownership seams, not production service wiring, module-runtime or loader-launch
evidence. They add no permission to execute foreign suppliers.

The loader food-copy controls preserve native effect flags and independently owned
hidden chains. NeoForge additionally retains explicit honey cures through display
and `DIRECT_CODEC` re-encoding, isolates source, returned and cached cure sets,
and gives resident and hidden copies separate native blend state. A counting
effect-owner fixture observes zero `fillEffectCures` calls during ordinary overlay
and resolve, with no owned instance published to that hook. Cure-table growth
retains cardinality and immutable cure-leaf identities; unknown sets/backings,
invalid cure elements, excessive retained-table cost and exhausted or previously
spent root allowances refuse with zero collection/hook callbacks in the selected
fixtures. These are finite native-copy and refusal seams, not general proof of
effect internals or live effect-icon rendering.

`ClientItemDisplayOpaqueComponentsTest` supplies registered-opaque-component,
explicit-removal, codec-admission and hook assertions. Unknown explicit JSON
additions refuse before their native value codec runs, including supported
nested-item and text-hover entrances. Explicit removals suppress an unknown
mutable default without comparisons. Conversely, registered, unregistered and
transient opaque defaults remain inherited across cache hits without codec or
value-comparison calls; default changes do not invalidate the transmitted-patch
cache. A common headless default-wrapper fixture traps enumeration and counts
value reads through resolve and copies. A selected inherited custom name takes
one native name lookup without probing the lower name or other defaults, through
resolve, chat and public naming; the terminal skips name lookup. These are native
selection checks, not default-graph traversal. The fixture is not an installed owo wrapper,
production Mixin or loader-runtime test.

Inherited Lore and nested container, bundle, projectile and food-conversion
fixtures assert native reference inheritance without sanitization, owned
deep-copying or invented patch entries. An explicit projectile still owns its
child patch while that child inherits an opaque default. These controls are
specific native-construction seams, not universal default-graph safety claims.

Constructor-hook injections into naming and render stacks, a naming hook that
mutates its stack, and codec-only naming construction exercise hook-written
outer-patch refusal seams. Those checks remain required after the patch-only
transition. The assertions reject opaque hook additions before publishing a
derived name, check transmitted-patch precedence without losing unrelated
constructor additions/removals, and suppress ordinary icons when construction or
naming reintroduces explicit Lore.

Known-value hook fixtures distinguish naming, retained transmission and render
ownership: an in-place description-hook text edit affects the composed name but
not the cached patch or render text, and a constructor-supplied mutable item name
is detached before publication. An ordinary explicit adventure-predicate control
preserves the native predicate while isolating its NBT across cache hits.
The default-Lore fixture instead asserts shared native references and visible
local mutation, without suppressing the ordinary icon merely for inheritance.
These controls do not authorize explicit Lore in an ordinary icon.

Name-source assertions in `ClientDisplayTextTest` compare all 36 default and
explicit-name addition/removal combinations with native hover-name precedence
through resolve, cache reuse, decoded stacks and chat. Separate controls keep
the terminal on its registry label and distinguish equal-text, different-reference
names. Public-name and description-hook alias cases in that suite and
`ClientItemDisplayOpaqueComponentsTest` assert that detaching explicit patch
values preserves the native selected name's provenance: an inherited higher
name is not quoted because a lower explicit name aliases it, and a fallback
`getName` hook cannot retroactively quote a name it just wrote. Returned text is
owned, caller patch backing and mutable values stay unchanged, and public naming
adds no load hook or repeated fallback-name call. These assertions are included
in the completed integration run below.

The per-call validation fixtures exercise finite visit, depth and character
allowances across JSON, native text snapshots and expansion, name checks and icon
traversal for resolve, stack decode and name decode. Shared native
projectile-DAG and text-cycle fixtures inject explicit constructor-patch values
and assert refusal inside an independent work envelope, including production
chat, without reaching naming in the late-unsafe-DAG case. These finite envelopes
do not bound native callback internals or elapsed time.

Native text fixtures include custom and item names, raw and styled
Lore, and raw and filtered written-book pages. They follow translation arguments,
siblings, selector and NBT separators, `show_text`, `show_entity` names and
`show_item` children. Hover validation observes the fixed native item descriptor
and any already materialized stack rather than calling its lazy stack getter:
the fixtures reject a cached hover stack mutated after the first safe resolution,
a nested JSON hover whose item has an opaque-injecting constructor hook, and a
deferred hover with an item-load hook without starting that hook. Writable-book
fixtures reject opaque non-string page shapes without formatting them, while
supported native text, hover and filtered book values remain available. Cases
contrast inherited text with the same value explicitly restored by a hook;
inherited values remain native while unsafe explicit text refuses. They do not
establish default-graph validation.

Production item-chat decoder fixtures target name decoding and barriers after
description lookup and naming, before newly introduced text can be formatted or
the next hook runs. Explicit-hook and returned-text fixtures reject constructor
and naming mutations, including injected root text or book pages, contain a
naming-hook exception, and reject an unsafe returned hover or an unknown
translation-argument object without formatting it. Normal trusted names retain
their native translation keys, arguments, siblings, style and safe hover in owned
text snapshots; source mutation after resolution cannot change the returned
fixture names. Custom-name and item-name overrides keep their established
plain-text flattening and quoting instead. Refusal yields an empty optional at
the decoder seam. An inventory-message fixture checks the registry-label fallback
with its exact long count and accepted selection reference unchanged. General
composition and fallback coverage remains owned by
[content receipt verification](presentation.md#content-receipt-chat-and-pending-completion),
under the [name contract](../../architecture/rendering/names_chat.md#content-message-family).

Legacy-hover fixtures guard `show_item` SNBT and nested `show_entity` name text,
including the legacy alternative after modern payload failure, before an opaque
component codec can run. A small safe translated legacy item is flattened to an
owned literal before native conversion; an expanded legacy text bomb is refused.
Parser-admission cases reject excessive JSON/SNBT nesting before recursive tree
construction. JSON controls compare streaming admission with a small native Gson
lenient-token oracle, including quoted and unquoted names, escapes, comments and
alternate separators; quote-bearing unquoted names and comments cannot hide a
deep array. A previously consumed root character allowance also constrains token
workspace admission. Separate SNBT/path controls use native Brigadier quoting
and escapes rather than JSON token rules, keep quoted brackets non-structural,
reject excessive embedded nesting and honor prior work consumption. These seams
cover parser-specific structure and workspace admission, not every native parser,
codec or hook internal resource.

Embedded NBT-path controls refuse excessive structure before native path
recompilation during resident text copying. The opaque-component fixtures exercise
the same refusal through wire text, returned names, description-hook text and
constructor-hook text, including production chat. The contrasting local-default
case inherits the native value without applying that traversal to the default.

Embedded-selector SNBT controls in `ClientDisplayTextTest` refuse excessive
nesting before native selector recompilation in `SelectorContents`, selector-valued
`ScoreContents` and `EntityDataSource`. The opaque-component fixtures exercise
those forms through wire text, returned names and text
introduced by description, name and load hooks, including resolve, decode and
production chat entrances. Contrasting local defaults retain native references
and do not acquire Ping graph-validation guarantees.
Small native-parser positive controls preserve native
unresolved text for ordinary selector options, quotes/escapes and non-structural
quoted brackets, including native-ignored trailing suffixes. Separate literal
score-name and block-data controls retain native text and absolute, relative and
local coordinate forms. These are bounded structure-admission and text-copy
seams, not live selector/world resolution.

`ClientDisplayTextTest` compares a small independently bounded formatting graph
against native text for indexed and mixed arguments, escaped percent signs,
primitive and null arguments, and invalid templates. Repeated-argument graphs
refuse huge output within an independent fixture work envelope; empty text and
unresolved score/NBT leaves that emit no characters must likewise complete or
refuse within that envelope. A warmed resident translation cache containing
an old argument cannot substitute it for the current argument, even when the old
graph becomes cyclic or has a formatting trap. Selector, score and NBT controls
retain native unresolved text and siblings. Independent short/long input and
previously consumed character grants check the shared snapshot-and-flatten
allowance without fixing production tuning values or copy-pass counts.

Text-snapshot isolation fixtures mutate source argument arrays, siblings and
text/entity hover children after copying, while preserving native style details
including explicit false versus inherited flags. Item-hover controls detach
nested projectile text and Lore from source mutation while preserving native
Lore styling. A registered mutable-prototype fixture checks that transmitted text
equal to the prototype remains distinct through resolve and item-hover copying,
and the hover keeps that text after prototype mutation. Additional
`ClientDisplayTextTest` cases preserve raw equal-default additions and explicit
removals through working and repeated item-hover copies, isolating explicit text
from prototype, source and earlier-copy mutations. A separate empty-patch case
keeps inherited text and Lore as native references through repeated hover copies
and observes later local mutation. Item-hover coverage is
limited to concrete copier-supported shapes, not deep-copy support for every
display-supported component. The item-hover snapshot path has no admitted copier
for nonempty container/bundle graphs, food/effect state, PROFILE and other
unproven values, and fails soft for those shapes. Ordinary item, text and supported
projectile controls do not certify all known component hovers or arbitrary item
hooks. These item-hover restrictions are not ordinary naming/render-stack
restrictions on supported PROFILE, food, container or bundle values.

A client preview fixture keeps the accepted exact reference, long count and
selection fences unchanged after display refusal and dispatches that same
selection. Vanilla positive controls exercise native inherited defaults and
explicit custom model data in count-one stacks across repeated resolves; owned
mutable-value assertions apply to explicit patches, not the default graph.
Explicit Lore still suppresses only the icon while a safe name remains usable.
These are finite synchronous fixtures, not a guarantee for every native hook, mod-supplied
text implementation or later asynchronous mutation, and do not exercise installed
Offroad, GPU rendering or a live game session.

The canonical-terminal cases in `ClientItemDisplayOpaqueComponentsTest` assert
a count-one native default icon with the registry label, inherited local name,
Lore and opaque values, an empty retained transmitted patch, repeated cache
reuse, and no description/name-hook invocation. Name-only decoding and the chat
decoder decline a terminal-derived item name. A composed inventory-message
fixture preserves the registry label, exact long count and selection key without
an item hover. Mismatched identity, malformed/null payloads and explicit opaque
constructor injection still refuse. These are client resolver and chat seams,
not a live row or GPU rendering test.

`SpatialInventoryRowDisplayTest`,
the inventory-bridge cases of `NativeSelectorContentTest`, and
`SpatialInventoryLayoutTest` carry that display through the native bridge into
the painted row projection: encoded display data survives both compatibility
projections, the row hands native rendering an owned count-one stack whose
transmitted patch is resolved against local prototype defaults while explicit
removals stay suppressed, so it cannot corrupt the next frame; custom names use
the shared quoted composition, and a malformed or missing display payload
leaves an empty icon and the registry-label text without raw data. These are
headless CPU, codec and native-painter seams over real vanilla constructor
verification, resolvable-profile resolution and checked-executor handoff with
only network/session and main-thread executor ports: no GPU rendering, external
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
The Common fixture exercises overlong concrete-type refusal with an in-memory
subclass whose actual binary name is 513 characters, beyond the unchanged
512-character lookup bound, rather than oversized on-disk packages. Its eight
test methods retain the normal ownership and `UNCLASSIFIED`/false-inherits
controls; an independent reflection oracle confirms the overlong instance still
inherits `Object.toString` without an override. No generated subclass file is
written. Execution is recorded in the
[Common fixture-path run](build-checks.md#common-resident-fixture-path-run).
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
no retained or reserved leak. Its snapshot-discovery limit case also preserves
the original bound in the incomplete outcome's report without a content read
or reserved-memory leak. `InventoryCaptureDiagnosticsTest` additionally
checks that its existing bound-reason gate reaches the shared backend even
when the general warning window is full, without a second admission window.

`InventoryItemFailureDiagnosticsTest` covers actual canonical-byte refusal
producing an all-variants aggregate diagnostic without changing a safe exact
identity, while intentional display stripping and ceiling projection stay silent.
At the freezer seam, only the tested display root-limit
refusal retains exact canonical identity with unavailable display; ordinary
runtime and linkage getter failures propagate unchanged to the source owner
without an exact result or a duplicate local report. Native snapshot and client
display decoding reject incomplete codec results without adopting partial data
or retaining native error-payload text; client refusal keeps the empty-icon
fallback and reports the trusted stage and fixed refusal description. A
catalogue guard after a successful freeze does not falsely report an identity
aggregate. The real-Catalog refusal case in
`InventoryPublicationIntegrationTest` additionally drives the production
backend's preview fold: the authoritative long group total survives and the
retired exact selection is rejected before a content read or marker creation.
That case uses a recording packet host, not client replay or live transport.

`InventoryGenericComponentCompatibilityTest` checks that a codec error retains
aggregate identity without its partial payload and reports only the trusted
stage and fixed refusal description. With WARN enabled or disabled, native
error-message suppliers are never evaluated; snapshot rejection and an admitted
warning likewise do not invoke the failing decoder's supplier. The rejection
path retains neither that supplier nor its partial result. These assertions do
not promise the native codec's original error text. Ordinary throwable reports
remain covered separately by the cause-bearing diagnostic seams on this page.
`InventoryNbtSnapshotTest` checks the original structural refusal remains the
cause of a snapshot limit failure. `ClientItemDisplayOpaqueComponentsTest`
distinguishes silent safety suppression from a throwing name hook: repeated
runtime hook failures retain the original cause in one warning without an
attached throwable and keep display unavailable. Its constructor and native
decode-context cases exercise runtime-failure fallbacks and linkage propagation.
The cached-construction failure fixture throws from the item's native
components getter: a runtime failure uses the resolve-stage fallback and evicts
the cached entry, while a linkage failure propagates without fallback eviction.
It does not assert Ping traversal or revalidation of inherited defaults.
These are finite headless codec,
snapshot, hook and test-appender seams, not installed-mod codecs, live profile
callbacks or running-loader evidence.

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
absent results with cause-bearing warnings. Loader lookup/gate and Vault
callback assertions also check bounded messages without an attached throwable.
These do not install Create or launch any loader or Mixin runtime.

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
and grey state stay explicit, lifecycle labels and the All variants qualifier
remain independent even with a long item name, and the tracking annotation
resolves the shared localized name policy rather than the raw label. Resource
fixtures for all eight bundled locales preserve full decimal quantities for
zero, `9007199254740993` and `Long.MAX_VALUE`, with long names, lifecycle labels
and the aggregate qualifier inside the line bound. Reversed quantity/annotation
grammar and oversized descriptive phrases have separate controls. These are
CPU text/projection assertions, not HUD or GPU validation.

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

## Logical container quotas and scan sharing

`InventoryFluidRuntimeTest` covers different physical tank members spending one
`CreateTankKey` tracking-slot allowance while each source opens with its original
owner and frozen face, even with both separation settings disabled. Recording
sources remain independently read; this is logical-quota evidence, not fluid-pool
certification. Passing execution belongs to the
[tank combined-tree run](build-checks.md#fluid-tank-grouping-and-presentation-verification).

`CompositeTargetQuotaTest` exercises the production backend, runtime and
publisher seams: active selected categories span opposite member hits while
repeated selection of one category shares its claim; the frozen key reaches
tracking slot subjects and recipient/target `STREAM` routes through rebaseline.
Different physical read views spend the same group quota independently. Group
equality cannot cross-bind an exact preview target to another member at create.
Losing markers keep their tracking when a sibling is cancelled and stop only
at their own removal/expiry. These unsupported recording sources keep exact
owner/face physical partitioning, and a new consumer cannot reuse an old
observation; certified widening is covered separately below.

`DoubleChestGroupingSafetyTest` uses actual production pair views for normal and
trapped chests from either hit, reading each half under the existing source
wrapper without capabilities. Metadata grouping and presentation can succeed
while locked, loot-backed or unavailable sibling inventory remains unreadable;
they grant no content authority.

`CreateVaultComposedSharingTest` composes the real segmented Vault access,
ordinary source authority wrapper and inventory runtime with recording native
world/provider fixtures and different physical member hits on the exact legacy
path. Compatible tracking consumers attached before capture with the same owner
and frozen face each
consume a 900-slot sweep from one 900-getter journal, with 900 logical slot
charges for their shared `TrackingSubject(VaultKey)`. Changing owner or face
requires two separate 900-getter rounds on that path and spends 1,800 logical
slots against the group. A late preview cannot join an already-started round;
it can share a compatible replacement created after attachment. A newly selected tracking
consumer starts fresh rather than inheriting that preview prefix.

Those composed cases establish exact-path conditional scan reuse, not one
tracking record per Vault or a CPU reduction. They do not cover the separate
certified cross-player pool below. Older synthetic fixtures that assign different
`PreviewSubject` UUIDs while reusing one input read owner test separate logical subjects; they
do not demonstrate reads shared by two actual players. Live Create behavior,
packet delivery, TPS, allocation and GC remain unmeasured. Topology/client
evidence is scoped separately under
[Create verification](integrations.md#vault-topology-grouping-and-presentation).

## Certified Tracking pool and publication coverage

`InventoryCertifiedReadTest` exercises exact native chest and trapped-chest
entities with recording world and lock ports. Opposite hits, different UUID
owners and all frozen faces produce equal certified ordered address proofs while
generic read scopes remain distinct. Metadata construction and validation perform
no item getter or resident-backing audit. Subclasses, reordered mappings,
replacement members/worlds/registries, malformed pairing, lock/loot denial and
one-short admission are distinguished. Cross-chunk partner-unload traps cover
construction, initial/final validation, catalogue creation and native reads,
including revocation during a real component freeze, without an unloaded-state
query or accepted partial item. `InventoryCertifiedVaultTest` composes the
production segmented Vault access and common certificate with headless native
ports: member/local-boundary/route ownership checks, same-layout local-handler
replacement, callback revocation and external/decorator isolation have separate
assertions. These are not installed Create or actual chunk-lifecycle tests.

`InventoryPooledRuntimeTest`, `InventoryPooledChestRuntimeTest` and
`InventoryPooledVaultRuntimeTest` cover policy cohorts and committed-prefix reuse,
independent exact bindings and completed evidence, creator departure and
owner-local denial, unsupported/mapping/world/member isolation, current-port
tail reads, shared collision history and display tightening. A nine-address
failure case records three committed reads, three failed-page attempts and six
retry reads: twelve attempts for nine committed slots, with no failed-page
append or attempted-work refund. Separate capacity cases retain only the
validated incomplete prefix and cannot claim completion or zero. Admission
refusal, completed-round exclusion, active certified Preview admission, exact-path
sharing, same-object/same-tick policy changes, rapid reverse changes and
pre-established successor entitlements are exercised independently. Lagging-peer
and repeated cleanup fixtures check bounded progress and zero retained/reserved
charges at close.

The certified chest runtime's resource-refusal reasons, retained-coverage
quantities and paid-journal replay control are inventoried under
[shared-source delay diagnostics](shared-sources.md#certified-pool-delay-diagnostics).

Successor regressions cover both idle and fully adopted abandoned replacements,
each across 32 cohorts with partial and explicitly `INCOMPLETE` observations and
exact-path controls. Every fixed variant reaches 32/32 replacement admissions;
all-adopted slow peers consume fresh accepted values without additional getters
while their original completed proof remains valid. Against unchanged runtime `0369f4c`,
both pooled all-adopted negative controls reach 0/32, while both exact-path
controls reach 32/32. Separate cases preserve actual scanners and accepted
completions through policy sealing, multiple predecessors and independent live
successors. Historical publication proof survives without retaining round pages;
close releases all handles, rounds, retained and reserved memory. These finite
schedules do not establish every overlap schedule or prolonged live-soak behavior.

`InventoryPooledBackendTest` and `InventoryPooledVaultBackendTest` drive preview
selection, four independent tracking markers, production backend/publisher,
native sources and encoded frames into real client inventory sessions. The
fixtures use two distinct owner UUIDs and two frozen faces, not distinct logical
subjects sharing one input owner. They raise pending-memory and Preview/Tracking
logical-slot allowances, change both cadences, and set a separate physical-slot
allowance so compatible consumers can co-start and remain serviceable. These are
tuned, budget-admitted compatible-cohort cases, not production-default
configuration guarantees. Their Tracking-only physical slot visits are:

| Separate faces | Separate players | Normal or trapped 54-slot chest | Segmented 900-slot Vault |
| --- | --- | --- | --- |
| On | On | 216 | 3600 |
| On | Off | 108 | 1800 |
| Off | On | 108 | 1800 |
| Off | Off | 54 | 900 |

Every chest marker reaches 756 and every Vault marker reaches the exact long
count `2,700,407,250` in each recipient fixture. The Vault has 45 members with
20 local slots each. These are headless actual-getter/ledger and encoded-client
oracles, not live players, packet transport, default-budget throughput or a CPU,
allocation-rate, heap, TPS or GC measurement. Per-owner metadata checks remain
required even when getter counts fall. These totals are not a portable benchmark:
other admission or cadence schedules can defer a first association until after a
prior-tick round completes, requiring additional reads under the
[active-admission rules](../../architecture/presentation/inventory.md#certified-tracking-observation-pools).
Their Tracking-only measurement window does not measure the later sequential
Preview/Tracking cases below.

`InventoryPublicationAuthorizationTest` checks distinct offered/admitted/delivered
historical evidence, old-cut and delivered-heartbeat ownership, final validation
after effective-budget measurement, revocation between irreversible parts,
defer without wire/watermark advance, and balanced withdrawal/rebase/cancel.
`InventoryPublicationReservationTest` exercises one rotating new-tick validation
opportunity before continuous capture/ingress, same-tick non-reset, one-short
probe/provider/item/workspace refusal before callbacks, attempted-work charging,
and historical metadata without retained round pages. The encoded backend cases
add owner revocation by another owner's read or by measurement, metadata callback
disconnect during final validation, and authorized-peer completion without
leaked queued seed data. Bootstrap and running-sweep policy changes preserve
the baseline and seed without toggle-only invalidity or content getters.

`InventoryPublicationReviewTest` adds 18 regressions through certified chest
sources, the production backend and encoded client sessions, with a separate
typed owner-denial control. Revocation inside the final source-proof callback
fences changed recipient policy, view, session or known-marker state before
quantity/completion frames or heartbeats escape; denied frames spend no wire
allowance and advance no delivery, part or heartbeat state. A no-revocation
control remains serviceable. An obsolete admitted proof or delivered-heartbeat
proof cannot discard a differently proven valid completion or paid partial scan:
replacement/missing old sources, completed zero, equal-count completion and clean
rebaseline ordering are checked without content recapture. Same-proof failure
still invalidates, and typed owner denial remains binding-wide without denying
another owner. Encoding refusal, expiry, exact reference release and reentrant
tick callbacks have separate assertions. Against unchanged parent `793437ca`
production inputs, the final negative control has ten expected failures among
eleven cases: eight final-callback leaks and two discarded valid completions, with
the no-revocation case passing. All 18 cases pass on the recorded final input.

`InventoryPreviewFailurePublicationTest` adds seven cross-origin regressions
through the production backend, native chest sources, packet codec and client
sessions. A Preview's failed old proof withdraws and re-fences matching Tracking
cuts without discarding a differently proven valid completion, partial cursor,
revision or paid work. Clean fences precede delivery of the replacement without
content recapture, including after origin disconnect or close and through expiry
cleanup. Matching-proof retirement and binding-wide owner denial still apply,
while an independent owner's valid proof remains serviceable. Preview and
Tracking use distinct rounds in these fixtures: failure propagation is not itself
evidence of cross-domain scan sharing. Four negative-control cases fail on
unchanged `ffe6536b` inputs;
all seven cases pass on the recorded final input with released references and
zero retained/reserved memory at teardown.

### Certified active-prefix and 1620-slot coverage

`InventoryActiveJoinTest` exercises pre-attachment journal replay, all four
partition combinations, exact Preview-only sharing and rejection of widened
Preview-only reuse after the tracker departs. It distinguishes actual installed
associations from mere attachment, including resource defer and callback-time
candidate completion; complete, incomplete, failed, released and catalogue-terminal
rounds grant no new association. Independent logical high-water accounting,
owner denial, recovery floors, changed world/member/mapping proofs, policy sealing
and reverse toggles, unsupported legacy freshness, ordinary restart exclusions
and staggered-cohort convergence have separate assertions. Cleanup releases all
handles and retained/reserved charges.

`CertifiedVault1620BackendTest` composes the actual backend, runtime, publisher,
C2S/effective-budget S2C codecs and client inventory over certified native
`ItemStackHandler` locals. The user-confirmed shape has 81 members with 20 slots
each, 1,620 slots in all. Sequential selections use two different identities,
plain stone and dirt, on opposite faces of the same or far member. Supplied and
separately normalized default profiles admit the partial-preview cases without
tuning their caps: Tracking pins its own association before normal Preview close,
the next Preview replays the prefix, and both markers' initial round totals
exactly 1,620 physical visits. `SELECT` adds no physical visit. Independent
complete counts are `1,620,438,210` and `1,620,439,290`, and each marker completes
its initial observation and four replacements before its unchanged 1,000-tick
expiry. Completed-Preview and already-completed-Tracking controls do not grant
the original completed round to a new consumer.

Mutation cases change already-read and unread stone/dirt slots before late
attachment, without moving identities. The journal retains the old prefix and
includes only the unread increases, yielding `1,620,439,210` and `1,620,440,290`
from one 1,620-visit mixed-time round. The seed is not added to either total.
Duplicate selection, rejected store and join-budget defer cases preserve
synchronous once-only creation and release tentative ownership without getters.
The current
`CertifiedVault1620BackendTest.metadataBudgetDeferDoesNotRejectOtherwiseValidSynchronousSelectionOrGrantOldPreviewOwnership`
covers a join-budget defer that still commits the synchronous selection with
independent receipt authorization and creation snapshot, followed by fresh
1,620-slot Tracking without reusing old Preview ownership. These are native-source/encoded-client headless assertions,
not live players, screen interaction or installed Create/Refined Storage
behavior.

### Open Preview and recurring Tracking lifetime

`InventoryActiveJoinTest` separately checks that a one-shot Preview can finish its
initial journal after faster Tracking moves on, without receiving future
successor entitlements. Actual slow Tracking still protects and adopts its
successor, including a pregranted sealed-policy handoff. The legacy control
releases future handoff ownership when recurring peers leave even while the
original Preview retains its evidence.

`InventoryPooledBackendTest` keeps later, unselected native double-chest Previews
open for two owner-partitioned Tracking cohorts through tick 750. It exercises
the legal normalized physical cap of 16 and a deliberately stricter three-slot
runtime seam, with 16 MiB pending memory and default logical quotas/cadences.
Original selected Previews close normally; later requests join each active
Tracking cohort and complete once. Both open requests retain their original 756,
round, cursor, revision and opaque reference while both clients' Tracking counts
reach 768 after stable slot updates, followed by further completion revisions.
Late selection performs no getter and does not use the completed Preview as a
cache. Against unchanged `9f41bf59`, both negative controls stall at 756; the
`7e435d4f` fix passes these regressions and its independent rereview. This records
encoded headless lifetime behavior, not a stay-open in-game UI run.

Execution results for these assertions and their identified code input are
recorded in the
[publication-stage run](build-checks.md#tracking-pool-publication-stage-run) and
the later [active-join and validation run](build-checks.md#certified-active-join-and-validation-run).
Live UI, mixed config versions, installed-provider behavior and
multiplayer/performance scenarios remain in the
[manual matrix](manual-integration.md#inventory).

### Terminal Preview evidence and active demand

Additional `InventoryPooledBackendTest` cases drive the production backend,
native double-chest source and encoded client sessions. A Preview reaches its
16-variant projection limit after 32 getters while the 54-slot physical journal
still reports `CONTINUE`; the already-read stone amount then changes from 1 to
64. Ordinary client `SELECT` adds no getter and retains the old amount only as a
provisional seed, not a Tracking accumulator.

| Admission case | Assertion scope |
| --- | --- |
| Only the terminal Preview retains the old round | Tracking completes at 64 from a fresh observation, with 86 getters across Preview and Tracking. |
| A real Tracking peer still scans the old round | The new tracker shares the paid prefix and completes at 1, with 54 getters in all. |
| A new Preview opens while the terminal request stays open | The new request completes at 64 with 86 total getters; the old request keeps its incomplete count of 1 and selectable references. |

A separate audience-pause case rejects widened Preview admission through a
retained but paused tracker, without a getter during admission. Resuming that
tracker preserves its original paid journal, while the other owner's Preview
uses an independent observation. The active-peer result follows the existing
[mixed-time admission boundary](../../decisions/D0017-certified-tracking-observation-pools.md),
not a fresh-snapshot guarantee.

These additions passed the focused and full Java 21 executions recorded in the
[terminal Preview admission and settings-resize run](build-checks.md#terminal-preview-admission-and-settings-resize-run),
which also built all three loaders. They add no live UI, window, provider-launch
or multiplayer evidence.

### Recipientless Tracking and successor continuity

Three additional `InventoryPooledBackendTest` cases exercise recurring Tracking
through the production backend, certified native source, publisher and encoded
client sessions. Two owners share one unlocked 27-slot chest with player
separation disabled and the legal normalized physical cap of 16. Explicit
owner-only frozen audiences model separate private channels; the fixture does
not exercise live channel administration. Its configurable audience preserves
the existing two-recipient default for other cases.

The two suspension orderings remove B's inventory-field permission, not B's
source-read authority, either before successor creation or during A's read after
B has received an entitlement. Assertions check that the serviceable-demand
signal updated at recipient synchronization excludes an already-suspended B from
future grants and releases an already-held grant, while retaining B's original
completed evidence, cursor and paid prefix. A completes the replacement and
multiple later refreshes without a baseline
reset; B receives no quantity, success-control or heartbeat frame while denied.
Restoring permission lets B complete fresh updates without widening either frozen
audience, source invalidation or a marker-lifetime change. Expiry stops tracking
and teardown releases retained/reserved memory.

The authorized cadence-wait control delays B's next sweep until A's successor
has completed. B retains and adopts that entitlement without a second physical
sweep, distinguishing an ordinary cadence wait from absent serviceable demand.
These assertions passed the full Java 21 execution recorded in the
[combined Java run](build-checks.md#recipientless-tracking-diagnostics-and-caption-reflow-verification),
which also built all three loaders; independent inventory review passed. This
evidence is separate from the earlier terminal-Preview run, and live permission
changes remain in the [manual matrix](manual-integration.md#inventory).

## Native incremental sweep coverage

The [native read contract](../../architecture/presentation/inventory.md#native-incremental-reads)
and [D0013](../../decisions/D0013-native-incremental-inventory-sweeps.md) separate
mixed-time traversal from explicit adapter snapshots. Earlier whole-capture and
frozen-content assertions above apply to the explicit capture/adapter seams;
they do not establish eager capture as the native runtime route.

`InventoryCatalogNativeReadWiringTest` drives the real native source, resident
freezer, catalogue and shared runtime with bootstrapped native items and
recording world/provider ports. It covers:

- metadata-only preparation and refusal without fresh per-step scratch, before
  a getter or eager snapshot discovery;
- getter-time detachment before provider metadata can reuse mutable resident
  contents, with post-read revocation still rejecting reads and live witnesses;
- contiguous typed and legacy steps sharing one cursor, admitted empty-view
  completion without a getter, charged empty/throwing attempts, finite slot and
  catalogue capacity, and explicit incomplete rather than invented totals;
- 540- and 900-slot repeated-item sweeps under unchanged default pending memory,
  with seven actual physical reads per tick and eleven logical slots per client
  period, two independently charged consumers, a slower peer replaying the paid
  prefix without getters, reverse visible/physical mapping, blank slots and
  authoritative `9007199254740993` amounts;
- an already-read slot retaining its earlier observation while an unread slot
  supplies a later value, a direct live absolute-slot witness helper, and a distinct
  complete first tracking round;
- replacement handoff and retained completed evidence for a slow peer, memory
  refusal without cursor advance, unique-definition display tightening without
  rescanning, and release of retained and reserved state.

The large-display slow-replay case carries more than 20 KiB of native display
data into a later tick: one-short scratch or work grants defer without advancing
the cursor, exact grants replay the retained immutable projection without another
getter, and admitted tightening preserves the earlier immutable value while
later replay uses the smaller display. The ordinary-container builder case
constructs a real 4096-slot native view and mapping, rather than injecting an
already constructed provider layout. It checks pre-resolution refusal, transfer
of source ownership into retained accounting, and cleanup after read failure,
restart and close. These are bounded headless ownership/admission fixtures, not
live large-container or total-heap measurements.

Its backend/client cases pass production-encoded frames through the real client
session. Cross-tick partial preview consumes each native slot once under the
same baseline before ready completion. Other cases exercise completed empty
views, provisional selected display followed by a complete fresh tracking result
and absence-derived zero, retained old totals during replacement,
completion-plus-period scheduling and hard expiry. Tracking resync preserves
scoped sweep evidence without recapture; delayed parts still gate client
completion, and display rebaseline preserves exact identity/counts without
restoring stripped detail. Actual identity refusal retains aggregate quality
through selection and resync; getter/capacity failures publish no tracking zero; invalidity or permission
revocation withdraws queued ready data. Recovery through a generic eventual
adapter stays uncertain. Default-settings 540/900-slot backend fixtures finish
preview within their bounded reference lifetime, select without a getter and
complete a fresh tracking sweep. These deterministic tick assertions are not throughput
or elapsed-time benchmarks.

Trailing-empty-slot cases withhold an earlier encoded exact or aggregate row,
then require the final cumulative preview cut to repeat that unchanged row.
Delayed or reordered earlier data cannot satisfy a missing part of the final
client barrier, resurrect retired exact references, or cross invalidation.
Settled completion stays unchanged on later ticks without another getter or
offer, and the separate true-empty case still completes with no item getter.
These cases drive native reads through production bytes into the client session;
they do not exercise a live transport.

`InventoryStepTest` checks detached contiguous typed pages, absolute bounds,
empty slots, long amounts, legacy conversion and evidence isolation: conversion
from a generic result cannot manufacture native sweep evidence.
`InventorySlotJournalTest` covers chunk growth, separately charged catalogue
definitions and slot capacity, exact long boundaries, observed empty versus
zero, hash collisions, prefix preservation on capacity refusal, and shared
display tightening without changing identity or counts.
Its allocation fixture retains repeated read results to prevent scalar
replacement from hiding allocation, and checks that replay of a large display
allocates fixed observation cells rather than display-sized UTF-8 buffers on
the test JVM while preserving exact counts. `InventoryDomainCodecTest` retains
rejection of invalid newly constructed observations; replay does not relax the
public validation boundary.
`InventoryCatalogAdmissionTest` independently checks unique retained cost,
duplicate ownership and monotone display reuse rather than a full encoded
observation per repeated slot.

`InventorySourceRetentionTest` checks independent owned-graph lower bounds for
multipart sided mappings and Vault auxiliary state, shared-layout accounting
without a second descriptor charge, external evidence without retained live
views, callback-free cached retained-cost queries, and operation-local reacquired
access. The ordinary-builder fixture above exercises the resulting charge
through the production runtime's round reservation; these cost-model assertions
are not measurements of borrowed provider internals or whole-JVM retention.

`InventoryValidationAdmissionTest` covers admission before initial resolution
and metadata, one cumulative caller allowance through nested validity/read or
selection-witness callbacks, attempted-prefix settlement on denial/throw, fresh
operation grants and retained-evidence defer without invalidation. NeoForge's
`CreateVaultInventoryAccessTest` checks immutable descriptor reuse, in-place
dynamic member/local-boundary validation, empty-member offsets, a 900-slot final
member-local address, same-total local-count redistribution, immediate resident
handoff before metadata reuse, and final all-member revocation. These Vault
ports do not install Create or run its lifecycle.

`InventorySyncPublisherTest` varies scoped evidence, consistency, completeness,
known coverage and empty/nonempty payloads for both entry quality and completion
status. Short coverage and a later generic observation cannot inherit ready
acceptance; aggregate quality remains distinct.
Its multipart boundary cases use actual encoded frames with 127, 128 and 256
parts, exact-cap and one-byte-over entries at early and late indices, and
reindexing across VarInt widths. A dropped frame triggers checks with the longer
incomplete status; indivisible oversized entries terminate incomplete rather
than stranding admitted work. Cumulative completion repeats unchanged aggregates
and earlier sparse keys under normal queue/wire charging, including a final cut
with no changed entries. Corrective replay, withdrawal without false empty
completion, display preservation for fitting entries, and retained/reserved
release have separate assertions. These are recording-transport framing and
accounting seams, complementary to the native encoded-client cases above.
`InventoryPublicationIntegrationTest` separately replays encoded generic eventual
empty/absence-zero observations as uncertain, while explicit atomic adapter
snapshots retain ready status through resync. No generic `CaptureResult`
consistency or wire schema is upgraded by these fixtures.

Automated execution results and build/artifact evidence limits are owned by
[build checks](build-checks.md#automated-verification-results).
Installed Create, live-world updates, multiplayer transport, TPS, GC and heap/elapsed-time
performance remain pending in the [manual matrix](manual-integration.md#inventory).

## Vault tracking performance evidence

### Deterministic construction, authority and scratch coverage

The earlier flat 900-slot native fixture does not model per-member Vault
validation and reconstruction. `CreateVaultTrackingPerformanceTest` instead
drives the real NeoForge segmented provider, common view/source validation,
native resident freezer, catalogue and shared runtime journal over a legal
3×3×5 shape: 45 members with 20 local slots each. The slot count represents
Create's default per-member capacity, not evidence of the user's actual
`vaultCapacity` setting or Vault shape. World/member ports and block entities are
headless stand-ins, and resident field access uses a test service instead of
production Mixins.

Its deterministic assertions cover empty, repeated stone and three mixed explicit
patch variants, native persistent-codec identity partitions, authoritative totals
beyond signed-int range, actual physical-ledger charges, no getter during
preparation or idle evidence checks, same-total local-boundary invalidation before
tail reads, and zero retained/reserved charges after close. A full sweep reads
900 slots in 29 pages with exactly one completion, only on the last page, and at
most 32 physical visits per reading tick. Pages are not network publications.

`InventoryResidentDiscoveryTest` uses isolated defining/context class loaders to
check first-request discovery, one service across independent requests/scratch,
first-context rather than defining-loader resources, absent-service Mixin
fallback, no rediscovery after context changes, and visible initialization
failure. `PlatformInventoryScopeFactoryTest` and
`InventoryResidentScopeFactoryTest` check legacy-overload dispatch, one supplier
invocation for a consuming access, zero invocations when declined, fresh guarded
delegation, frozen face and dynamic authority, and source member prechecks even
when no optional scope is constructed.

`InventoryInPlaceSourceContractTest`, `VaultCurrentSourceValidationTest` and
`VaultInPlaceAccessContractTest` add current route/root/member identity, registry,
owner/key/lock/loot/removal/load and topology cases. They cover same-shape local
replacement reading current contents, unsafe native backing refusal before the
getter, callback-time revocation, immediate freeze before metadata mutation,
invalidation and fresh-source recovery, shared-peer lifetime and memory release.
Generic capability replacement still requires rerouting, decorators cannot grant
the opt-in, and external bindings resolve per operation. Zero/one-short validation
grants refuse before callbacks or cursor advance; exact native grants admit the
local audit without a whole-capture audit, and failed attempts remain paid.
Cached page profiles cover step sizes 0 through 64 as source metadata, not item
observations or validity results.
Source-owned profiles/member keys are included in the retained model; retention
tests check shared-layout/Vault auxiliary charges and callback-free cached cost
queries. These are finite admission and lifecycle oracles, not heap-retention
measurements.

`InventoryCanonicalWriterTest` keeps the independent all-tag, sorted-key and
malformed-UTF byte oracle and adds geometric growth, exact logical-cap/one-over
refusal, full replacement allocation and cumulative-copy admission.
`InventoryDisplayWriterTest` checks exact UTF-8 caps across growth, escaped and
surrogate boundaries, reset after a failed larger ladder pass, retained
no-rehydration, and work/allocation refusal without a size-terminal exception.
`InventoryEncodingCostOracleTest` checks empty/JSON/NBT lazy scratch schedules,
direct scratch debits separate from native event vectors, first-allocation
refusal, map cardinality, collision-tree splitting and alternating resets.
`InventoryScratchAllocationTest` covers zero-effect laziness, first-array refusal
before cure callbacks, ordered ranges across phases/nested planning, and multiple
native effect families against the persistent-patch oracle. Existing native,
explicit/default, mutation, cycle/DAG, external-codec and collision fixtures remain
unrelaxed. These tests do not claim identical acceptance at every processing-budget
threshold; cumulative adaptive-growth costs are part of the guarded contract.

### Recorded allocation comparison

This historical comparison uses a 900-slot forwarding `CountingHandler` fixture
and the noncertified exact path. Its percentages do not describe the later
certified native 1,620-slot route measured below.

The saved measurements compare four conditions: baseline, resident discovery
with lazy scopes, Vault current validation with cached cost profiles, and
on-demand processing scratch. SHA-256 and Git-blob comparisons confirm that the
three harness/support files are identical across all four conditions. Input
provenance belongs to the external evidence listed below; these are recorded
measurements, not a fresh measurement of the current code.

All conditions used JDK `21.0.12.1+1-LTS`, three warmup operations and five measured
samples per workload. `ThreadMXBean` measures current-thread allocated bytes,
not retained memory, total JVM heap or an allocation rate. Each sample uses a
fresh runtime/consumer over preconstructed native residents. The measured full
sweep starts after preparation, includes tick advances, page replay and retained
ledger work, and excludes setup/close. Separate workloads measure fresh-runtime
admission (not JVM cold start), the second 32-slot page and a completed-evidence
idle probe. Counters are collected separately from timed samples.

Median allocated bytes for the same prepared 900-slot sweep:

| Contents | Baseline | Resident/lazy scope | Vault validation | On-demand scratch | Reduction from baseline |
| --- | --- | --- | --- | --- | --- |
| Empty | 186928368 | 96408264 | 709136 | 621392 | 99.67% |
| Repeated | 304761648 | 214241712 | 118464184 | 5688272 | 98.13% |
| Mixed | 305988176 | 215468072 | 119692104 | 17140424 | 94.40% |

On-demand scratch full-sweep distributions in allocated bytes:

| Contents | Minimum | Median | Maximum |
| --- | --- | --- | --- |
| Empty | 621392 | 621392 | 659584 |
| Repeated | 5688272 | 5688272 | 5689160 |
| Mixed | 17140424 | 17140424 | 17150024 |

On-demand scratch second-page medians are 25688/203544/612360 bytes for
empty/repeated/mixed. The idle probe under that condition is 4512/4640/4640 bytes
minimum/median/maximum for empty, and 4512/4512/4512 for repeated and mixed, with
no getter. Raw five-sample byte
and elapsed-time vectors, all workloads' distributions and the four-stage table
remain in the external `vault-perf-*-manifest.txt`, `vault-perf-p4-final.xml`,
`vault-perf-p4-final.measurements.log`, `vault-perf-p4-four-stage-comparison.csv`
and fairness-hash evidence. Elapsed observations include JVM/GC variance and are
not CPU time, game-tick duration or live throughput guarantees.

For each prepared full sweep, common view/provider-factory calls change from
1829/1829 to 0/0, and warm resident-service constructions from 2729 to zero.
Direct adapter-loaded checks change from 1002877 to 584036, and common-loaded
checks from 420670 to 84134. Full structure checks inferred from controller-check
counts minus factory calls on the stable successful path change from 7287 to
5458. Successful factory calls imply segmented/layout/descriptor construction in
that path; constructors are not directly probed. The on-demand scratch condition
has the same validation/getter counters as the Vault validation condition. Reuse
eliminates reconstruction, not all per-slot member checks; validation still
scales with slots and members.

The historical fixture reproduces the then-recorded profile: physical allowance
32 slots/tick, tracking period 4, target/server logical allowances 256/2048,
pending memory 256 MiB, and display/frame multipliers 4. These are historical
benchmark inputs, not the supplied profile for the later certified run. At 32
physical slots/tick, 900 visits need
at least 29 reading-eligible ticks; that allowance does not cap all source-validity
checks at 32 or prevent idle probes between sweeps. The pending-memory setting is
not an MB/s allocation throttle, as defined by the
[configuration owner](../../config/server.md#inventory-policy-object).

The benchmark does not exercise real Create `MinecraftMember` reflection,
`ServerLevel`/`ServerPlayer` lookups, Mixin/service/bootstrap wiring, backend
selection, marker creation, publisher, network or a fully populated installed-mod
registry/provider workload. The reported live 1100 MB/s allocation and 30% CPU
case was not independently reproduced, and these results neither convert to
those units nor prove the incident solved. Installed Create/Refined Storage with
simultaneous import/export, paired real profiling and the remaining
[manual inventory scenarios](manual-integration.md#inventory) remain pending.

### Certified 1620-slot baseline and measurement boundary

The test-only baseline `18b1539f` establishes a separate workload using the
user-confirmed 81×20 Vault and exact native `ItemStackHandler` locals, without a
getter-counting subclass or wrapper. `CertifiedVault1620BaselineTest` runs the real
common source, certified native port, segmented provider, resident freezer,
catalogue and runtime journal through typed world/member and resident-service
ports. It checks ordered canonical addresses, blank visits, independent native
persistent-patch/canonical-byte/hash oracles, exact long totals, no premature
completion, detached results after mutation and zero retained/reserved charges
after close. The prepared full sweep commits 1,620 addresses in 51 pages, with
one completion and no defer or invalid result. Physical evidence is the ledger
plus contiguous committed addresses, not direct instrumentation of native getters.

The directly inspected ignored `feedback_log/pingforit.server.json` is the intended
input supplied for this run, with user-attested local-JAR provenance matching the
2026-10-30 06:20 baseline. Its inventory profile has physical allowance 1,024 per
tick, pending memory 16 MiB and display/frame multipliers 1; Preview uses period
5 and client/server slot quotas 128/1,024, Tracking period 3 and target/server
slot quotas 512/4,096, with both partition switches off. `Profile.INCIDENT`
validates those inputs without normalization changes; `Profile.DEFAULTS` separately
constructs and validates the actual model defaults. Neither overwrites defaults
or loads the feedback copy automatically. This provenance does not claim the
earlier live incident was independently reproduced or add external-file watching.

The unchanged measurement harness separates fresh-runtime admission, the second
prepared 32-slot page, a prepared full sweep and a completed idle evidence probe.
It uses JDK `21.0.12.1+1-LTS`, three warmups and five measured samples, with a
separate counter pass. `ThreadMXBean` reports current-thread allocated bytes;
`nanoTime` reports elapsed time, not CPU, allocation rate, TPS or retained heap.
The full sweep excludes setup/close and does not time backend selection. Six
benchmark/support source hashes, fixture/world class hashes and both normalized
settings profiles match across the baseline, fixed-metadata and coordinated-read
stages. The sequential backend test has separate correctness assertions and is
not one of those six byte-identical measurement sources.

The typed workload does not execute production `MinecraftMember` reflection or
real `ServerPlayer`/world authority internals. `CertifiedVaultReflectionBaselineTest`
is a separate metadata-only 32-validation fixture: it executes production
`MinecraftWorld`/`MinecraftMember` against unchanged actual Create archive method
bodies and initialized native locals, checking method signatures/bytecode with
isolated optional ancestors and a stand-in `ServerLevel`. It is not a composed
coordinated content read, installed-mod launch or whole-game benchmark.

### Fixed metadata and coordinated-validation coverage

`CreateVaultMetadataPortTest` checks lazy tested-version gating, defining-loader
isolation and a thread-context-loader trap; fixed class/owner/signature/return
drift, optional linkage and field-accessibility failure refuse safely. It observes
one discovery of the six methods and native backing field, while later reads use
current controller, extents, axis, local handler, backing and native audit.
Replacement, null/unsafe backing, getter override, scope/route revocation and
unloaded-member traps remain dynamic. `InventoryVaultMetadataCostTest` keeps warm
invocation accounting separate from conservative cold acquisition and unfamiliar
local-class costs. This stage does not reduce validation multiplicity.

`InventoryCoordinatedVaultReadTest` has 14 dedicated operation, authority and
accounting regressions over the audited certified Vault route. They exercise
current handler replacement and explicit empty handoff, registry/local-audit/
codec/final-callback revocation, immediate native detachment before later metadata
mutation, wrong provider/slot, missing/duplicate/reordered stages, reentrancy,
post-close reuse and cleared references. One-short metadata/catalogue grants and
unsafe getter/backing or unloaded members refuse before content. An unknown
provider claiming the interface cannot select coordination. Failed pages append
none of their addresses, attempted work remains paid, and an authorized peer
retries the tail. `InventoryCoordinatedCostTest` checks full allowance composition
for page widths 1 through 64 and 1/2/45/81 members; retention tests include the
added operation/profile ownership rather than claiming reduced retained memory.
Independent review status is recorded in the [known gaps](#known-automated-gaps),
separately from passing tests and measurements.

### Certified validation measurements

The P1 comparison is fresh pre-cache `015b37a9` versus `9f41bf59`, not a combined
P1/P2 gain. In the actual-archive metadata-only fixture, median allocation for 32
validations changes from 3,264,768 to 544,000 bytes (83.34% lower), with elapsed
min/median/max 5.0993/5.2015/6.5688 ms versus 1.9190/2.2346/3.0418 ms. Dynamic
load/entity counters are unchanged. The typed full-sweep elapsed medians in that
same final P1 run are slower than its fresh control; reflection-only savings do
not establish a universal speedup or live CPU improvement.

The P2 comparison uses a fresh `7e435d4f` control, already containing P1 and the
Preview lifetime fix, versus `a7fa3315`. Measured successful-path counters include
the independent association check as well as page work:

| Workload | Common current checks before → after | Provider matches before → after | Provider loaded calls before → after |
| --- | --- | --- | --- |
| Second 32-slot page | 163 → 131 | 390 → 262 | 69,176 → 48,184 |
| Prepared 1,620-slot sweep | 8,253 → 6,633 | 19,746 → 13,266 | 3,502,404 → 2,439,684 |

Both stages retain 1,620 physical and committed visits, 51 pages, one completion
and no defer/invalid result; preparation and idle probes perform no physical
visits. Validation remains Θ(slots × members), not constant time or cached validity.
The measured retained ledger grows by 108 bytes, and every close releases all
retained/reserved charges.

Median allocated bytes for the prepared certified sweep:

| Profile | Contents | Fresh P2 control | Coordinated read | Reduction |
| --- | --- | --- | --- | --- |
| Supplied | Empty | 87,582,720 | 70,553,448 | 19.44% |
| Supplied | Repeated | 96,707,896 | 79,683,424 | 17.60% |
| Supplied | Mixed | 110,517,768 | 93,424,176 | 15.47% |
| Defaults | Empty | 87,523,960 | 70,552,224 | 19.39% |
| Defaults | Repeated | 96,706,264 | 79,680,432 | 17.61% |
| Defaults | Mixed | 110,495,864 | 93,418,784 | 15.46% |

The original `18b1539f` supplied repeated-sweep median is 96,897,784 bytes; it is
historical context, not P2's fresh control. Raw five-sample vectors and
min/median/max distributions for admission, page, sweep and idle remain in the
external evidence. Idle allocation rises slightly, and the default repeated-item
admission elapsed median is slower after P2. P2's separate reflection-only median
allocation is unchanged. These limitations preclude combining benchmark stages
into one CPU claim or promising a millisecond improvement.

The external evidence directories `certified-vault-1620-baseline`,
`active-inventory-join`, `certified-vault-metadata-cache`,
`active-join-preview-lifetime-fix` and `certified-vault-coordinated-read` retain
their `HANDOFF.txt`, exact commands, logs, XML, hashes and raw measurements. The
last directory's `before-after.csv`, `derived-counters.csv`,
`measurement-hash-comparison.txt` and raw JSONL/distributions identify these
stage controls. No live Create/Refined Storage import/export, CPU, allocation-rate,
TPS, GC, heap or update-latency result is established; those scenarios remain
[pending](manual-integration.md#inventory).

## Preview-seeded tracking coverage

The [handoff and bootstrap contract](../../architecture/presentation/inventory.md#selected-preview-bootstrap)
has headless source-to-encoded-client and publication coverage:

- `InventoryCatalogNativeReadWiringTest` clears the old observed slot before
  `SELECT`, creates immediately with no additional item getter, then explicitly
  replaces the seed with a fresh positive, equal or absent/zero result. Its
  540/900-slot partial-preview cases preserve exact long quantities after preview
  close while this fixture's noncertified new sweep starts at slot zero, without
  an old round or seeded accumulator. Certified active-prefix coverage is
  [separate](#certified-active-prefix-and-1620-slot-coverage). Separate cases retain
  one selected value rather than the native
  preview catalogue and release unselected payloads on close.
- Its queued-publication regressions change an actual native chest lock after
  withholding the seed, then deliver unknown, duplicate and other-valid-preview
  selections in the same tick. Marker/sidecar, Basic and correlated outcomes
  stay synchronous, no selection drains queued quantities, and the next normal
  budgeted tick detects revocation without a content getter. A provider-backed
  long-count case exercises the same boundary; unchanged-provider and native
  chest controls progress on the next eligible tick. These tests pass production
  bytes into the client, then cover seed retirement, fresh zero recovery and
  expiry without a live world or loader launch.
- `InventoryBackendTest` distinguishes the current retained exact-key count
  from the last delivered row, and the current folded-key count from another
  all-ID subtotal. Missing counts, forged/retired tokens, owner, baseline, state
  and current-invalidity failures reject without a content read. The patch-flow,
  authority-order and `SableInventoryHandoffTest` fixtures cover distinct exact
  identities, current binding, rollback and one materialization/store/outcome
  under replay. Sable alias/revocation fixtures remain scoped to recording
  providers, not a live sub-level.
- Native backend/client cases cover Basic-before-seed delivery, independent
  recipient baselines without recipient previews, current owner lock/layout
  checks, display-cap repair without recapture or restored detail, first-scan
  incomplete history through retry and repair, permanent seed retirement on
  invalidity through recovery, exact-to-aggregate retirement without a false
  subtotal, and hard expiry before completion. Complete accepted native results
  remain ready; generic eventual recovery remains uncertain.
- `InventoryBootstrapPublicationTest` passes real writer/reader frames into the
  client session. It separates installed seed baselines from source completion,
  exercises long exact and aggregate seeds, equal-count explicit replacement,
  missing/reordered data parts, matching early controls, heartbeat stability,
  send/memory refusal and release, obsolete queued updating withdrawal, grey
  incomplete/invalid history through rebase, and recipient isolation. An
  admitted old seed is withdrawn even if a fresh result was offered before the
  failure; late incomplete controls cannot downgrade a newer completed result.
  Actual publisher `READY`/`UNCERTAIN` controls delivered before fresh data leave
  exact and aggregate seeds visibly updating. Reverse multipart delivery keeps
  the closed seed, incomplete/invalid history survives early controls and rebase,
  and failure during first delivery retains the seed rather than a partially
  applied replacement. Future checksums wait for data; completed controls still
  refresh an already complete view. Cap reduction strips the closed provisional
  display and a later increase cannot restore it.
  The actual publisher's two-entry multipart regression withholds the selected
  replacement while delivering a different fresh key, then repeats invalidation
  and recovery controls: only the prior closed seed remains visible until all
  recovery parts arrive. Direct cases distinguish no history, closed empty,
  completed and zero-count seed projections; none can acquire an unclosed
  recovery key through duplicate invalidation or rebase. Reset/removal cleanup
  and stale invalidation after complete recovery have controls. The two-entry
  case exercises the supported publication/client contract, not a live
  single-selected-item incident.
  Generic partial tracking captures, preview routes and multi-key bootstraps are
  refused by the narrow publication path.
- Tracking text projection is inventoried [above](#tracking-projection).
  Encoded-publisher seed gating and property receipt behavior are owned by
  [presentation verification](presentation.md#content-receipt-chat-and-pending-completion).

### Creation snapshot server coverage

`InventoryBackendTest` covers the selected retained exact or folded count rather
than the last client frame, missing-reference rejection without a zero, aggregate
display, and selection outcomes preserved across transport refusal.
`InventoryCreationPublicationTest` covers the current fully handed cumulative
Preview-cut completion proof, initial staging that shares Tracking capacity, and
exact settlement. `InventoryInitialPayloadTest` covers pure Basic sizing against
the real codec, oversized semantic refusal without an encoding probe, and the
deterministic full-envelope bound without a 1 MiB reservation.
`InventoryInitialRetryPagingTest` covers fixed retry-pass boundaries, shared
discovery capacity, known-record skipping, lifecycle clearing, behind-cursor
canonical refresh and retention of later failures.

`InventoryProductionPolicyTest` covers selected Preview creation before the first
Tracking tick, post-encode fresh Basic and annotation authorization, metadata
refresh retry and coalescing, at most two post-encode narrowing attempts, memory
admission before rebuild, native fluid source/route/layout/scope and
exact-to-aggregate retirement, physical completion without the current Preview
cut remaining uncertain, recipient pressure from 24 HELLOs and the 0.125
multiplier, late first-recipient creation value 5 after Tracking 9, expired
deferred creation, invalid creation that cannot resurrect, and callback/lane
revocation.
Named review-fix cases include `sourceCallbackCannotLeakRevokedBasicCustomName`,
`deferredKnownMetadataRefreshMustReachTransportOnRetry`,
`sourceCallbackChildDenialRebuildsAnnotationsWithoutSuppressingAllowedContent`,
`repeatedPostencodeNarrowingStopsAfterTwoPaidAttemptsThenRetries`,
`narrowedBasicRebuildCannotConstructBeforeANewMemoryAdmission`,
`deferredKnownRefreshCoalescesToLatestCanonicalLocatorAndAnchor` and
`successfulOlderHandoffCannotClearAReentrantNewerMetadataRefresh`.

`InventoryAuthorityOrderTest` covers committed Preview evidence and annotation
before storage without a content witness, receipt-binding metadata revalidation
that pays failed metadata without reading contents, and publication failure that
preserves the committed create. `InventoryBudgetDelayIntegrationTest` covers
memory defer as retryable while unavailable, unsafe, empty and bounded
continuation outcomes are not delayed, including expiry, revocation and eligible
counterpart pressure.

These are headless owner executions. The current-input combined execution and
artifact checks are recorded in
[build checks](build-checks.md#final-combined-tree-acceptance-and-artifact-verification).
Cross-boundary client chat and transport coverage is in
[presentation verification](presentation.md#immediate-inventory-creation-snapshot-coverage).

Execution results are recorded in the
[automated verification results](build-checks.md#automated-verification-results).
These headless tests and builds do not establish live Create/Refined Storage
behavior, the cause of the reported Vault import/export failure,
live multiplayer, GPU rendering
or performance. Those remain [manual scenarios](manual-integration.md#inventory).

## Patch-only identity and display verification pending

The [inventory owner](../../architecture/presentation/inventory.md#item-variants-and-component-fallback)
and [D0012](../../decisions/D0012-explicit-patch-identity-and-display.md) define the
accepted boundary. The test-source inventory above includes the shared-reader,
native patch oracle, server projection, client explicit-value/default-inheritance,
pre-frozen-key backend flow and publisher scheduling assertions.

The completed integration run used Java 21 from PowerShell with UTF-8 output and
redirected logs. It exited zero with `BUILD SUCCESSFUL` for:

```powershell
.\gradlew.bat --no-daemon --console=plain --continue :common:test :fabric:test :forge:test :neoforge:test :fabric:build :forge:build :neoforge:build verifyModIdentity
```

All four modules' main compilation, test compilation and tests executed in that
run. All 466 JUnit XML reports were fresh, with these results and no errors:

| Module | Tests | Passed | Failed | Skipped |
| --- | --- | --- | --- | --- |
| Common | 4554 | 4554 | 0 | 0 |
| Fabric | 22 | 22 | 0 | 0 |
| Forge | 25 | 25 | 0 | 0 |
| NeoForge | 170 | 170 | 0 | 0 |
| Total | 4771 | 4771 | 0 | 0 |

This includes all five Flow, eleven patch-identity, nine shared-reader, eight
registry-context, fifty-two opaque-client, twenty-three client-text, eight
NeoForge food and nineteen content-chat cases. All three loader builds and
Javadoc tasks completed, and `verifyModIdentity` passed with three shippable jars
produced. The non-document input manifest stayed unchanged across the run and
report checks. Whitespace, conflict-marker, new-file and documentation
link/anchor/fence checks also passed. Build and artifact evidence boundaries
remain owned by [build checks](build-checks.md); pending verification here is
the live behavior described below.

Separately, an isolated `OwoDerivedComponentMapProbe` passed with the real
`DerivedComponentMap` class: its CodeSource resolved to the installed
`owo-lib-neoforge-0.12.15.6-beta.1+1.21.jar`. Direct `javac` and `java` invocations
both exited zero, with `PROBE_RESULT=PASS`, using a classpath derived from the
Gradle test worker. For stone and cooked porkchop, an exact native
`PatchedDataComponentMap` with the real owo wrapper as its prototype produced
the same canonical identity and display as an unwrapped same-patch control,
matching the independent native persistent-patch codec oracle. Cases included
explicit additions, an equal-default addition, removals and an empty patch;
manually seeded differing wrapper name/food values stayed outside projection.
Supplying the wrapper directly as the outer map was correctly refused.

That probe passed against the final compiled Common main and test classes from
the successful integration run. It manually
constructed the post-Mixin map shape and seeded the wrapper delegate without
calling `derive`; it did not apply Item/ItemStack Mixins, start FML, or exercise
client naming, rendering or networking. It establishes the real jar class's
prototype shape against those final classes, not automatic mod wiring or
client behavior. No Gradle invocation or game session formed part of the
probe.

The native oracle, pre-frozen-key backend flow, wire round trip and client
resolver fixtures are complementary seams, not one native-container-to-live-row
test. The publisher budget fixture starts from preprojected data, and the client
wrapper fixture models a local default map rather than installing owo; the
separate real-jar probe has only the baseline and construction scope above.
Automatic Mixins, live installed-mod behavior, loader lifecycle, default drift across real
installations, delayed transport, GUI rows, tracking HUD and GPU behavior remain
outside that source evidence. Live scenarios remain in the
[manual matrix](manual-integration.md#inventory).

## Resynchronization and budget policy verification

The revised
[resynchronization](../../architecture/presentation/inventory.md#tracking) and
[budget policy](../../architecture/presentation/inventory.md#budgets-queues-and-memory)
contracts have passing headless evidence in the
[automated verification results](build-checks.md#automated-verification-results):

- `InventoryS2CPacketTest` round-trips legal periods and zero cooldown in the
  unchanged `OFFER` shape and rejects out-of-range, overlong or noncanonical
  encodings without defaults. Configuration and
  strict administration-wire evidence is inventoried in
  [configuration verification](configuration-ui.md#server-performance-contract-verification).
- `InventoryBackendTest` exercises positive cooldown refusing repair just before
  its deadline and accepting it at the deadline, zero and maximum cooldown
  negotiation/live refresh, and positive-to-zero changes for
  tracked-Ping repair. An old deadline cannot block zero, and
  completed repair can run again in the same tick; pending requests still
  coalesce and an admitted blocked baseline resumes rather than restarts.
- `ClientInventoryTest` exercises the same positive-to-zero deadline bypass and
  same-tick repair behavior. Unknown-baseline expiry, incomplete completion
  gaps and future-heartbeat gaps wait through the tick before expiry and request
  repair at the boundary, with separate cases for each dominant term of the
  common waiting window (8-, 9- and 15-tick windows). A short positive retry
  cooldown is independent of the waiting floor; an admitted serviceable multipart
  baseline remains completable
  beyond that window. These are finite deterministic scenarios, not a live-load
  starvation guarantee.
- `InventoryMemoryBudgetIntegrationTest` drives the production runtime ledger
  with a simulated 3 GiB retained ticket under the 4 GiB unlimited guard, beyond
  signed-int byte range. Unlimited mode retains its saved finite value, admits
  up to its finite guard and refuses one byte more. Lowering the cap updates the
  existing ledger without deleting
  live charges: preview and tracking defer before resolver, validation or read
  callbacks, and releasing the ticket lets both share one physical read without
  raising the cap. Cleanup releases retained/reserved costs. The large tickets
  model accounting only; no correspondingly large data is allocated or measured.
- `InventorySettingsTest` checks that the separate server-wide tracking-slot
  unlimited guard does not widen the other count guards and that memory mode
  conversion retains its finite value.

Execution scope is recorded in the
[automated verification results](build-checks.md#automated-verification-results).
Live timing and multiplayer repair remain pending in the
[manual matrix](manual-integration.md#inventory).

## Known automated gaps

- live loader/Mixin application, GPU/font/UI and narrow-window rendering,
  multiplayer/mixed-version handshakes, installed Create/Sable/Refined Storage,
  manual gameplay and CPU/TPS/GC/allocation measurements remain pending in the
  [fluid inventory manual scenarios](manual-integration.md#fluid-inventory);
- the passing certified-pool, successor and final-handoff fixtures above remain
  headless evidence. Live two-owner/chunk/lock/topology changes, installed
  tested-version Create and mixed configuration protocols still require the
  [manual inventory scenarios](manual-integration.md#inventory); tuned getter
  matrices do not establish default-budget throughput or CPU, allocation-rate,
  heap, TPS or GC gains;
- certified active joins and the supplied/default 1,620-slot sequential schedules
  have native-source/encoded-client evidence, not every admission/overlap schedule
  or live completion guarantees. P1 fixed-metadata review and the Preview-lifetime
  rereview passed; independent P2 authority and accounting reviews passed with no
  findings on `a7fa3315`. Authority review exercised 11 existing coordinated-read
  methods and six additional Java 21 probes; accounting review independently
  checked validation envelopes, page-cost formulas, saved counters and 48 sample
  distributions. Reviewers did not rerun full Gradle suites. This is finite
  source/headless evidence, not live-game assurance. The scoped allocation
  measurements do not establish whole-JVM
  bounds, installed Create/Refined Storage behavior or resolution of the live
  incident;
- reported installed-container selection failures lack live reproduction or
  confirmation of resolution; passing headless tests and builds do not close
  those incidents. Pending scenarios are listed in the
  [manual inventory matrix](manual-integration.md#inventory);
- the revised timing and memory-admission seams
  [passed the combined-tree run](#resynchronization-and-budget-policy-verification),
  but active selected-variant capacity across period boundaries has no dedicated
  regression; simulated large memory tickets do not measure actual heap usage;
- the inventory preview-foundation, production runtime and publication tests
  cover bounded request/session lifecycle and cancellation, presentation-driven
  cleanup of nonempty tracking and buffered state, per-client/target logical
  progress, explicit snapshot preparation/capture
  and cross-period decode, native cross-tick reads and journal replay, accepted
  barriers, the tracking closed-watermark
  barrier, production selection admission order and publisher recovery/repair
  seams. Native input/HUD, live-world-backed provider safety, installed
  optional-provider wiring and live transport still require their own
  evidence, and no manual world, GPU, transport or multiplayer validation is
  recorded by those seams. Finite UUID churn and ledger cleanup do not establish
  prolonged live connection or heap-soak behavior;
- server-safe fluid registration lookup has headless current-registration
  coverage in `InventoryFluidRegistryLookupTest`, `FluidRegistryClassSafetyTest`
  and `InventoryFluidLoaderIntegrationTest`; Forge's patched-first
  `PlatformFluidRegistryServiceTest` and `NativeFluidRegistryFreezerTest` cover
  the actual wrapper/default-root WATER and handler/freezer cases. These are
  patched-first or classpath adapter checks, not installed dedicated-server or
  real-FML evidence; they neither widen the audited registration trust nor
  supply a fallback key for an unregistered fluid. The current-input execution
  passed the Forge source integration cases; installed-server and real-FML
  behavior remain manual gaps;
- inventory call-site logging coverage is limited to the
  [diagnostic seams above](#failure-diagnostic-seams). Other runtime
  probe/projection branches, capture-handle source-close and standalone
  snapshot/ticket-close diagnostics lack direct log-event assertions.
  Test appenders do not establish production
  file-appender delivery or low-frequency behavior in a prolonged live soak;
- default-cap capture, preview-seeded selection and fresh tracking have headless native
  seam evidence. Independent review of the audited native fast path establishes
  the composed root/native/Catalog/caller allocation and work bounds only for
  its reviewed inputs within the versioned static source-event model and finite
  guards. Separate independent review results for the on-demand scratch changes
  are not recorded; the composed-bound review does not establish them by
  inference. The closed component certificates do not certify the generic
  external-trusted codec route.
  Cursorless attempted-prefix, symbolic lock-certificate and native JSON
  stack-boundary tests exercise those finite seams, not whole-JVM certification.
  Reviewed composition assumes caller-backed grants, fresh lazy-read scratch and
  an explicit process-fixed initialization boundary within the guarded standard
  JDK 21/25 envelope. It does not measure total JVM allocation or certify
  arbitrary JVM/providers, total heap, profiling,
  GC, TPS or elapsed time; external-trusted LIVE provider and generic codec,
  lookup/getter, hash/equality and native cache internals, including their
  retention, remain outside that certification. Finite compatibility and
  returned-tree fixtures cannot establish a universal bound on those internals;
- generic registered-component compatibility, external-context and owned-tree
  assertions do not establish installed Offroad or other mod runtime
  compatibility, or live preview-to-tracking identity fidelity. The
  [patch-only transition](#patch-only-identity-and-display-verification-pending)
  passed the complete integration run. Client hook and explicit-value
  text-snapshot fixtures remain local seams, not safety guarantees for arbitrary
  hooks, inherited defaults or asynchronous component mutation;
- isolated named-host Mixin probes cover the selected raw ports on captured
  Common, Fabric and NeoForge compiled inputs, with Forge registry/builder reads
  and all-profile exported getter-body checks; separate RGB overwrite probes
  exercise native public-API boundaries on all four profiles. Forge enchantment
  runtime reads remain blocked by its real active-FML LoadingModList requirement.
  Full production configuration and Fabric API coexistence, FML lifecycle and
  transformation ordering and shipped intermediary/SRG runtime remain outside
  that isolated-host evidence; compilation and field-port tests do not close
  those gaps;
- inventory ordering/freezing, explicit snapshot continuity after ordinary
  content changes, native mixed-time sweeps, empty-view completion, explicit zero
  versus unknown,
  per-key revisions, client barriers and publisher recovery/repair have
  headless and production-seam evidence above, not live execution.
  Per-Ping/recipient baseline and resynchronization isolation are exercised at
  the production seam, and explicit detached double-chest capture, native
  cross-period preview,
  same-alias layout-change, restart/retirement and evidence-admission seams are
  covered by the tests above. The unknown-baseline window has headless
  before/at-boundary expiry and RESYNC evidence for each dominant waiting term,
  not a live roundtrip; the publisher's cross-target period accounting is headless
  for
  SNAPSHOT bytes only; the zero-heartbeat publication seam exercises watermark
  and repair without asserting the absence of a periodic HEARTBEAT. A maximum
  legal display value through the production server identity catalogue and its
  queue and budget admission remains unproven, as do transport-asynchronous
  heap retention and a live heap soak. Same-ID aggregate retirement, checked
  long sums and normal retained publication have encoded-frame/client seam
  evidence, not live execution. Variant quotas, a real provider resolver and
  recovery-probe expiry, the hard stop at Ping expiry, a fresh capture after
  ordinary single- or double-chest contents change in a live world, a multi-period
  low-consumption quota completing stably under live load, a real mod-provided
  bounded
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
  create PROFILE callbacks before icon eligibility is known; selected detached
  nested-head and ordinary mutable-value controls do not establish callback-free
  decode or general asynchronous PROFILE isolation. Opaque-component,
  parser-admission and text/working-stack fixtures cover specific explicit-value,
  returned-text, expansion/cache, isolation and hook boundaries, not arbitrary
  mod codec/hook internals, every mod-supplied text implementation or universal
  deep copying of known component hovers. Real FML/GLFW lifecycle, GPU rendering,
  language hot reload and asynchronous mutation after validation remain outside
  these seams;
- sparse display-default fixture families are headless codec, decode, projection
  and writer seams, with a limited representative sample of actual inventory-frame
  wire sizes through the production writer and reader. Patch-only and terminal
  assertions passed the integration run scoped above. These fixture
  families do not exercise a
  live GUI row, an installed mod's local item defaults, client/server default
  drift across real installations, asynchronous component mutation, multiplayer
  transport or transport timing; the full send-queue and byte-budget path
  remains manual/integration evidence;
- the provider-confirmed external inventory source against a live Sable runtime
  and world: live membership and sub-level checks, a real provider layout or
  capability, member-gate revocation between operations, getter-free selection
  and the preview-to-tracking handoff, and inventory reads on the three
  supported loaders;
