# Inventory verification

Evidence rules and verification expectations are owned by the
[verification guide](README.md). Pending live scenarios are listed in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).
Inventory list gestures and navigation are inventoried under
[interaction](interaction.md#inventory-list-gestures-and-navigation), the
stay-open selector path under
[interaction](interaction.md#double-click-danger-and-inventory-stay-open), and
selector overlay layout and frame rendering under
[rendering](rendering.md#selector-overlay-layout-and-frame-rendering). That
stay-open path changes only when ordinary selection is submitted, not preview or
tracking admission.

This page owns the inventory coverage inventory, local automated gaps and the
inventory performance evidence. A test-source assertion recorded here does not
establish that it passed; historical execution statements are linked to their
build record and are not this round's results. Execution evidence belongs to
[build checks](build-checks.md), and pending live checks stay in the manual
matrix. The [patch-only contract](../../architecture/presentation/inventory.md#item-variants-and-component-fallback)
has test-source assertions inventoried below; its completed integration run and
live gaps are scoped
[below](#patch-only-identity-and-display-coverage-and-live-gaps).

## Fluid inventory coverage

The confirmed [resource-lane contract](../../architecture/presentation/inventory.md#resource-lanes-and-scope),
[fluid identity/display/quantity contract](../../architecture/presentation/inventory.md#fluid-variants-and-quantities),
[staged operation accounting](../../architecture/presentation/shared_sources.md#cost-accounting),
[Create tank gates](../../integrations/create.md#fluid-tank-inventory-provider)
and [Sable fluid transition](../../integrations/sable.md#fluid-sub-level-transition)
have passing headless coverage in code input `628c033b`, including the approved
[weak historical evidence](../../architecture/presentation/shared_sources.md#weak-historical-identity-evidence).
The 2026-11-02
[final combined-tree record](build-checks.md#fluid-inventory-final-combined-tree-verification)
owns the exact input, command, task results, counts and expected Common-only
skips. Pending live checks are in the
[fluid inventory manual scenarios](manual-integration.md#fluid-inventory).

The following matrix records established coverage, not a current-round run.

| Acceptance surface | Established coverage and boundary |
| --- | --- |
| Foundation and codec | `InventoryResourceFoundationTest`, `InventoryFluidCodecTest`, `InventoryFluidDisplayCodecTest`, `InventoryV2LaneTest`, `InventoryC2SPacketTest`, `InventoryS2CPacketTest`, `InventoryPresentationTest`, `ClientDedicatedDeliveryTest` and `PlatformFluidServiceContractTest`: exact units/checked arithmetic, lane/owner/face identity separation, display/amount independence from exact identity, identity refusal and checksum-domain separation, above-double-precision and malformed codec cases, strict route/grammar boundaries, independently authorized fields, schema-two advertisement and old-schema exclusion, absent-service fallback. The display codec is native-free; the platform fixture does not execute native loader capabilities. |
| Shared request and lanes | `InventoryResourceLanesTest`, `InventoryResourcePublisherTest`, `InventoryV2LaneTest` and `ClientInventoryLanesTest`: shared open/close/replay, independent policy/baseline/completion/checksums, cross-lane rejection, lane-local denial/recovery and sibling continuity. |
| Runtime and admission | `InventoryFluidRuntimeTest`, `InventoryResourceLanesTest`, `FluidPortLifecycleTest`, `InventoryFluidAdmissionTest`, `InventoryValidationAdmissionTest`, `InventoryFluidWorldTest` and `InventoryFluidRouteDiagnosticsTest`: one journal/runtime/global ledger, shared physical/logical consumption, paid failed visits, bounded catalogue/retained evidence, owner/face isolation, cursorless incompleteness, staged admission, no double boundary charge, normal/nested/abnormal close orders, refusal/overflow cleanup and INFO deduplication. No fluid pool. |
| Weak proof and provider safety | `FluidAddressWitnessTest`, `InventoryFluidScopeFramesTest`, `InventoryFluidServerContextTest`, `PlatformFluidServiceTest` and `InventoryFluidLoaderIntegrationTest`: hostile native equality/hash callbacks, schema/order/length differences, changed/cleared evidence rejects old cuts, fresh scans recover, a source-owned non-native facade has no owning server graph path, deterministic reference clearing, loader adapters/APIs with recording fixtures, equal detached layout not hiding replacement routes. Loader-classpath scenarios are not loader launches; no live GC measurement. |
| Identity, publication and display | `InventoryFluidFreezerTest`, `ClientInventoryLanesTest`, `PendingContentChatControllerTest`, `ClientPingRuntimeContentReceiptTest`, `ClientFluidDisplayTest`, `InventoryQuantityFormatTest` and `InventoryListModelTest`: persistent variant identity, checked long amounts, identity/display refusal separation, overflow/getter failure without completed zero, provisional selected seed, final source checks, multipart barriers/repair, history-only quantities, exactly-once emission, literal/base-fluid projection, display-refusal isolation, grouped ordering and exact/approximation boundaries. GPU icons, native font fitting and narrow-window layout remain unverified; cross-boundary client chat is in [presentation verification](presentation.md#immediate-inventory-creation-snapshot-coverage). |
| Create | `CreateFluidTankArchiveTest`, `CreateFluidTankMetadataTest` and `CreateFluidTankAccessTest`: tested-version/archive gates, controller/member/scope/mode checks, controller-only frozen-face reads, denied creative/boiler/virtual routes without fallback, shape/capacity admission, topology/handler replacement and postchecks. Headless probes admit 3x3x20 (180) and 3x3x32 (288), admit exact 2x2x256 (1,024) and 3x3x113 (1,017), and reject 3x3x114 (1,026); the exact-limit shape is arithmetic, not a live configuration. Archive/headless seams, not installed gameplay. |
| Sable membership and fluid-only transition | `ExternalBlockMembershipTest` and `SablePhysicalMembershipTest` cover positive/negative/unknown physical membership. `InventoryFluidWorldTest`, `InventoryFluidScopeFramesTest`, `InventoryFluidServerContextTest` and `ClientInventoryLanesTest` cover continuous scope-only `UPDATING` with retained quantity, fresh complete replacement, old-cut/seed/partial fencing and real failure/expiry. Simultaneous scope/identity/layout change still invalidates; installed Sable remains pending. |
| Formal regression | Historical formal regression on the identified final input; exact execution and loader-native skip handling belong to the linked build record. |

Outside that matrix, `FluidIdentityAdmissionScalingTest` adds five current-source
cases over the production common fluid source, Fabric indexed provider adapter
and runtime ledgers: a 2,048-address sweep completes under default budgets
without replay or restart; the maximum legal address count advances one address
per tick for three ticks; memory refusal before metadata or reads invokes no
getter or count callback and recovery reads the first address; an admitted
metadata callback lowering the budget refuses the next phase before a content
getter; and a root replacement at the final count or view callback is rejected
before the native getter with no reservation. All five passed in the
[final combined execution](build-checks.md#inventory-initial-admission-and-terminal-reset-final-verification).
These are Fabric test-source admission/cursor/ownership assertions, not heap,
throughput, loader-launch or every-provider evidence.

`InventoryFluidPreparationRetryTest` covers a retained unprepared round
retrying through shared preparation without resolving the source again, with
admission, debits, authority and final validation unchanged. The Create suites
also cover boiler mode with stale former-controller state, provider-local `DEBUG`
reporting and lazy failure context; invalid shape, member-limit exhaustion and a
hit outside the verified prism retain the independent `invalid-shape`,
`member-limit-exceeded` and `hit-outside-structure` reasons, and
`PlatformFluidServiceTest` distinguishes unloaded-chunk from
uninstantiated-entity positions. These are headless/archive cases, not a full
loader build or live source run.

`InventoryFluidReadOperationTest` covers the common single-use state machine:
pre/post validations, synchronous freeze and single completion; missing,
duplicate, reordered, reentrant, wrong-provider, wrong-slot, wrong-thread and
post-close calls fail closed and clear owned references; a budget defer before
freeze yields no provisional value; codec reentry cannot complete a detached
value. NeoForge's `InventoryFluidCoordinatedSourceTest` drives the real common
source and capability access through the guarded wrapper: the ordinary route
keeps provider current check and admission immediately before one native getter,
common pre/post validations and synchronous freeze; the external/Sable route
keeps the legacy standalone path. The 2026-11-06 integration was historical, not
a current-tree result; see [negative controls](build-checks.md#verification-negative-controls).
The final suite also covers metadata/provider-current callback reentry, and getter
count stays at most one. This is not live native serviceability.

`CreateFluidTankValidationPerformanceTest` is one deterministic headless counter
baseline over the real Create tank algorithm and capability discovery, using
detached metadata, a production-shaped nested-gate world and default ledgers.
The unchanged provider-only fixture supplies the observe rows; the common plus
production-guard source test supplies provider-current/bind; the Backend
first-Preview regression supplies construction:

| Headless comparison | Result |
| --- | --- |
| Provider-only 288-member observe context/member/authority | 99,168 -> 47,280 |
| Provider-only 1,024-member observe context/member/authority | 352,352 -> 167,984 |
| Provider-only observe capability/current passes | 4 -> 2 |
| Common + production guard provider current passes; binds | legacy 4 / 315 -> coordinated 3 / 237 |
| Backend first-Preview source constructions | 2 -> 1 |

Consume and observe retain one content getter; valid, consume and cleanup rows
remain in the integration counters. The provider-only fixture excludes the common
guarded source and live Create. These deterministic counts are not an end-to-end
Preview, TPS, latency or allocation measurement; the live 288-member 3-5 Hz/20 s
comparison remains pending in the
[manual matrix](manual-integration.md#fluid-inventory). Mixed-lane selection is
inventoried under [interaction](interaction.md#inventory-list-gestures-and-navigation),
quantity/sprite dispatch under [rendering](rendering.md#base-fluid-icon-presentation),
and loader APIs/Create tea archive under
[integrations](integrations.md#native-fluid-presentation).

### Native fluid boundary regressions

These regressions passed on the
[native fluid combined-tree input](build-checks.md#native-fluid-boundary-and-presentation-verification).

| Boundary | Established coverage |
| --- | --- |
| Complete definitions and paid prefix | `InventoryFluidCodecTest` counts full lane/registry/variant keys against the 256-definition guard; repeated keys remain usable at capacity, refusal retains collision evidence, and collision/canonical-payload guards stay independent. `InventoryNativeFluidBoundaryTest` and `InventoryFluidWorldTest` cover 257 complete keys from 129 encodings, aggregate/extension-journal exhaustion, refusal inside one paid page, explicit readable `INCOMPLETE`, charged attempts and unrelated progress. No completed total or absence-zero replaces the selected historical seed. |
| Pending final validation | Repeated final-check budget defers keep the paid cursor; metadata retries with both logical and physical allowance exhausted; the accumulated total publishes without another getter; current revocation or exception invalidates; capacity exhaustion cannot become completion; empty-source completion and ticket/retained cleanup have controls. |
| Bounded native names and client display | `FluidDescriptionsTest` covers detached translation/literal leaves, discarded styles/events, Unicode/byte bounds, complex/cyclic/opaque refusals without flattening, optional callback failures and native first-initialization refusal; an independent retry keeps identity/amount, while ordinary/linkage failures stay optional. `ClientFluidDisplayTest` covers local translation/literal/fallback precedence, verified server labels, registry-label fallback, custom-name composition, validated icons, and refused/mismatched projections never invoking native naming. |
| Server/client separation | `FluidPresentationClassSafetyTest` executes absent/broken name-service discovery with client/render classes forbidden; SPI constructors propagate capture-limit subclasses, sentinel `OutOfMemoryError` and `ThreadDeath`; installed-payload integration requires current adapters for every loader before client-free naming and atlas-free service construction. Classloading evidence, not a dedicated-server launch. |

### Production lane policy and native amount regressions

Historical note: these lane-policy and recipient assertions exercised dynamic
permission behavior at their recorded input.
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md)
changes new-Ping recipient/content authorization timing; the passing results are
historical and do not establish the frozen policy. They passed in the
[lane-policy and native-amount execution](build-checks.md#lane-policy-native-amount-and-icon-opacity-verification).

`InventoryProductionPolicyTest` drives actual `PresentationServer.tick`
permission derivation and backend ticks through a recording network service.
ITEM/FLUID deop and reop keep the presentation epoch, view and SECTION mask
without a shared `RESET`; sibling accepted preview, tracking recipient/baseline,
paid cursor, watermarks and admitted queue survive. A revoked lane retires queued
data, baselines and tokens; reallow admits a fresh scan and baseline under the
same parent request. `InventoryResourceLanesTest` checks fresh lane-local
admission without rereading the sibling. `InventoryProductionPolicyTest` and
`InventorySyncPublisherTest` retain each lane's exact desired `POLICY` packet
until `transport.send` returns normally under the current session, epoch, view
and authority. Queue admission is not completion or a client ACK; refusal, defer,
failure and discard leave bounded retry work; identity deduplication prevents
duplicate entries; old equal-valued completions cannot clear a newer desired
packet; view/epoch/close/session/disconnect fence retired callbacks; recursive
drains defer added work; failures stay isolated; cancellation releases memory.
Controls cover shared target quota/close, exactly-once pending `SELECTED`,
all-lane denial retaining the Basic known marker, control-admission retry, fresh
marker receipt authorization and SECTION/child-policy `RESET`. Fixtures are not a
server or multiplayer launch.

Forge and NeoForge `NativeFluidAmountTest` use real native stacks through
production handler adapters; Forge requires the patched-first integration
classpath. Retained negative constructor/setter amounts are rejected before an
empty visitor even when the public getter reports zero. Forge also tests negative
native NBT; NeoForge distinguishes native parser rejection to `FluidStack.EMPTY`
from adapter rejection of a retained negative value. Controls cover zero/empty
and positive/max, Forge getter overrides, paid single null/throwing getter
attempts, and failure followed by recovery. Runtime replacement keeps prior
complete quantity when a negative read yields `UNAVAILABLE`, non-complete output
with no slots and no retained completion; it does not substitute exact zero.
These use fixture identity bytes and world binding, not full native
identity-codec integration.

## Inventory preview and tracking foundation

Headless test-source assertions cover the preview route and supporting models.
Exact assertions remain in the tests.

- **Wire and codec.** `InventoryC2SPacketTest` and `InventoryS2CPacketTest`
  cover version-two request/response codecs, bounded text/frame limits,
  corruption, part-range/duplicate-key rejection, status/quality values and
  unsupported/old-version rejection. The offer's display and frame budget pair
  covers an exact legal pair, off-grid and missing-metadata-headroom refusal,
  illegal or over-cap effective writes, the whole-frame header/absolute decode
  bound with one-byte-over refusal, two maximum registry-JSON entries overflowing
  the frame with a destination left atomic, independent vanilla UTF measurement,
  and no display-sized buffers with workspace scaled to the actual packet. The
  v2 `OPEN` grammar round-trips a native
  block or uncommitted external candidate with exact locator/classification/face
  and rejects committed external/entity/location targets, wrong versions,
  overlong/noncanonical fields and trailing bytes. Sparse-display cases compare
  sparse and full-effective resident-patch payloads for stone, a tool and a
  damaged tool: native full-map oracle count/effective components match, the
  synthetic full-map patch has a different variant key, and the sparse frame is
  smaller. An ID-only terminal display is distinct from unavailable `null` while
  preserving long counts, revisions and selection keys. `InventoryChecksumsTest`,
  `InventoryStrictDecodeTest` and `InventoryWireWindowTest` cover entry checksums,
  strict `OPEN` raw frames, overflowing/noncanonical varints and varlongs and the
  wire window. Codec seams only, not live transport, send-queue or byte budget.
- **Backend lifecycle and runtime.** `InventoryBackendLifecycleTest` exercises
  duplicate previews sharing paid progress, idempotent `CLOSE`, last-close stop,
  paid-period ticket retirement, per-client/server-wide slot limits that do not
  refund on reopen (including different-UUID reuse), duplicate disconnect,
  reconnect epoch fencing, old `OPEN`/`CLOSE` isolation, no old-session output,
  memory-pressure defer/resume without `OPEN`, and 300 guest UUIDs returning to
  one anchored cost after every period without stranding reservations.
  `InventoryRuntimeTest` covers shared access/quotas, explicit snapshots,
  publication evidence/successor handoff and display tightening: one admitted
  slot prefix is charged once and shared by preview/duplicate/tracking consumers
  while client/target subjects stay independent; memory defer does no resolver or
  read; cursorless enumeration cannot invent continuation; owner/frozen face
  split live sharing; invalid rounds retire handles once; throwing read/close
  releases memory; live allowance reduction preserves spent slots; compatible
  aliased inputs share one physical round when normal observation admission is
  full, while owner/face remain part of the source key. Explicit snapshots capture
  once, fix the route independently of remaining allowance, decode across
  periods, keep captured values after live changes, never fall back to live
  reads, and release on last close; zero-slot empty completion requires an
  admitted validation probe with fixed provider work and no slot scan charge;
  nonempty without allowance defers; a live version change downgrades
  verification to eventual; oversized/invalid capture reports incomplete
  or terminal without empty/partial data. Publication-evidence admission defers
  before provider work; leases release at the last scanner restart and close when
  replaced or when the last consumer leaves; the bounded lease set permits a
  fresh replacement; a shared physical replacement hands off predecessor to
  successor and counts the handoff in scan admission; rejected/invalid/merged
  successors detach; close/retire/merge cancels handoff-only references without
  dropping old evidence or refunding progress. Display tightening threads the
  configured cap, normalizes invalid caps, reprojects monotonically with a
  deferrable cursor, cannot resurrect an omitted display after a later raise,
  restarts a mid-pass tighter walk, keeps the encoded page charged, and rejects
  work after close.
- **Publication, snapshots and source capture.** `InventorySourceSnapshotTest`
  covers lazy explicit-adapter capture, atomic-detached evidence, unknown
  eventual evidence, independent source versions, memory-pressure retry,
  incomplete/oversized captures, terminal invalid/failed/throwing pages without
  empty/partial publication, charged attempts, and idempotent close with fatal
  propagation. `InventoryAuthorityOrderTest` covers production admission order
  (rate/channel, target-gone, source evidence/annotation before storage), tracking
  sidecar installation before first publication, committed publication failure
  that cannot retry/re-create, and sidecar query following the committed lease.
  `InventoryItemPingTypesTest` grants the inventory-only take type exactly once
  for the actual chest tag; missing/name-matching tags fail closed.
  `InventoryPublicationIntegrationTest` covers per-recipient recovery fences,
  grey-baseline replacement, receipt isolation, withheld-stream repair at
  cooldown, multipart baseline deferral/resume, future-heartbeat repair, missing
  parts, failed fences, duplicate controls, retirement/restart evidence, same-ID
  retirement with exact sums and unrelated rows, queue/transport recovery without
  rereads/marker creation, and cross-tick partial preview. Encoded-frame/client
  assertions, not live packet loss. `InventoryBackendTest` covers one preview
  create that seeds tracking without reusing snapshot/count, marker lifetime
  under invalidation/recovery/expiry, revoked-view queue purge with frozen
  audience, per-observation identity aggregation, isolated recipient resync,
  absence not vetoing a reference, replay creating once, prepared-create
  cleanup, invalid-preview round release, refused terminal `INVALID` retry,
  snapshot-plan memory refusal, transport-refused immutable part delivery,
  superseded oversized selection, last all-ID total, control recovery and
  aggregate-display work refusal. Snapshot-plan memory refusal leaves preparation
  and reads unperformed, leaks no reservation and never reports a completed empty
  observation; later admission prepares/reads once. `InventoryBudgetDelayIntegrationTest` keeps a
  delivered completed preview deduplicated under memory pressure while validity
  checks continue, with no false delay, duplicate cut or read after invalidity.
  `InventoryPatchIdentityFlowTest` keeps two exact keys of one item ID separate,
  selects without a getter, sums decorated/ID-only/unavailable display forms,
  rejects raw exact keys instead of opaque references, and assembles one
  completed preview cut before a fresh tracking snapshot; it does not exercise
  native patch encoding, client replay or a live provider path.
  `InventoryReconfigurationTest` covers higher-cap full display delivery,
  lowering gate/rebaseline, superseded offers, stripping preserving key/count,
  deferred reprojection, identity-refusal retirement, recipient-isolated resync
  and UTF-8 measurement. `InventorySyncPublisherTest` covers per-period
  `SNAPSHOT` totals across targets, no encode/send on revoke/defer, indivisible
  oversized previews, fragment-budget retention, object-identity entry versions,
  data-workspace refusal before encoding, tiny-control memory refusal, bounded
  reentrant control drain, retirement/restaging, period-owned wire tickets and
  failed-offer/control cancellation. `InventoryMinecraftSourcesTest` covers
  same-alias topology invalidation, fresh recovery, lazy explicit snapshot-plan
  discovery without NBT saves, live fallback without explicit layout,
  double-chest cumulative cuts, legal-single topology withdrawal, ordinary
  content changes finishing one immutable cut, same-alias same-count layout
  change detection, and display-cap tightening/ceiling/below-minimum rejection.
  `SableInventorySourceTest` covers canonical alias scope, same-registry
  different locators neither short-circuiting nor sharing invalid retirement,
  input-only probe failure, origin evidence retirement after departure, cached
  prefix fencing, foreign member/chest denial, member-local double-chest identity,
  no atomic publication on release during capture, relocation/descriptor
  invalidation, fresh closures, partial-topology rejection, frozen lock/loot,
  revoked metadata/content reads, paid metadata-denied steps and indexed
  rechecks. `InventoryMinecraftSnapshotCaptureTest` covers explicit bounded
  capture of vanilla block entities with save traps, original hit half,
  member-local zero/frozen face order, per-member gates, topology/revocation
  atomicity, and live fallback for unsupported/layout-less providers.
  `InventoryObservationSnapshotTest` covers immediate freeze, long amounts,
  empty slots, detached lifetime, failed cleanup, validation-ledger refusal
  before layout/content, nonrefundable prefixes, allocation/peak workspace,
  Vault revalidation and lock-certificate multiplicity. Profile cases cover
  layout-less discovery, changed-shape rejection before expanded member reads, a
  failed single-to-double lock check, and unchanged-default-cap admission.
  Cached reprojection-summary cases distinguish retained input from a global ceiling,
  deduplicate shared catalogue owners, combine scratch by peak rather than sum,
  and refresh after tightening including null projections; the source-owned
  finish ticket adds no provider or world callback to that boundary.
  `InventoryNbtSnapshotTest`
  covers detached per-member/per-segment NBT, frozen-face mapping, unsigned byte
  slots, nontruncating integers, malformed entries as read failures, SNBT before
  custom-data decode, empty/sparse/explicitly empty-face observations, consuming
  counters, reserved workspace, depth/cycle/node/array bounds before deep copy,
  immutable exports, display cap adoption/tightening with cursor preservation and
  invalid-cap rejection. `InventorySnapshotLayoutTest` covers immutable positions/lists/
  mappings, no invented slots, duplicate rejection, total visible bound and the
  1,024-member accept / 1,025 reject boundary. `CreateVaultInventoryAccessTest`
  preserves member order/controller role/member-local mappings, exposes no
  descriptor for an invalid layout, gates root/controller/members before reads
  (including an already acquired member/local handler) and rechecks after
  revocation; `CreateVaultSourceWrapperTest` covers same-controller rotation
  invalidation and fresh 12-slot recovery.
  `SableInventoryHandoffTest` covers request-scoped preview, once-only selection
  materialization, physical-root binding, seed without a content getter,
  wrong-binding rejection, no cross-request cross-binding, replay without a
  second materialization/getter, and post-seed invalidity with fresh recovery.
- **Identity, patch and component trust.** `InventoryPresentationTest`,
  `DedicatedDeliveryBoundaryTest` and `ClientDedicatedDeliveryTest` keep the
  inventory adapter negotiated/policy-catalogued but never sampled, masked,
  published or rendered by section machinery. `InventoryItemCodecTest` and
  `VanillaInventorySourceTest` cover canonical persistent component identity,
  sorted bounded bytes, digest-collision rejection, partial display omission and
  safe exact versus unsafe aggregate selection. `InventoryComponentFidelityTest`,
  `InventoryVanillaComponentCoverageTest` and native-source fixtures compare
  canonical identity with the native persistent-patch codec, explicitly restore
  tool/equipment/food values, and include a nonempty signed/unsigned PROFILE.
  `InventoryRegistryContextGuardTest` compares both nonempty enchantment forms,
  carries `TOOL`, and tests unknown-provider stops, owner compatibility, unbound
  holders and admitted lookup shapes; that refusal is not a blanket
  external-trusted restriction. `InventoryItemFrontGuardTest` uses an unregistered
  component type, not an unfamiliar registered persistent type.
  `InventoryPatchesTest` covers surviving equal-default additions/redundant
  removals, no reconstruction of native-normalized entries, copy-on-write/backing
  identity, no prototype traversal or value comparison, independent backing/
  mutable values, array/open-hash/empty backings, and refusal before visitors. `InventoryPatchIdentityTest`
  compares directly supplied patches with `DataComponentPatch.CODEC`, separates
  the empty-patch digest from item ID/aggregate sentinel, preserves raw/default/
  erased distinctions, root count not changing identity, compares container/bundle/
  projectile/food child semantics, traps prototype reads, distinguishes
  present-but-empty `components` envelopes, and keeps empty/transient-only patches
  identical.
  `InventoryGenericComponentCompatibilityTest` covers scalar/record/map
  identities against native encoding, cache-wrapped codecs, implicit-empty versus
  explicit equal-default, override/removal without prototype encoding, transient
  exclusion, unregistered-codec refusal, partial/oversized aggregate mode,
  throwing-codec propagation/budget reuse, identity-preserving display omission,
  non-cache-wrapped encode counters and `Registry<Integer>` provider-specific
  bytes. `InventoryComponentBoundsTest` covers owned persistent-tree tag kinds,
  typed empty lists, empty-string compound keys, post-mutation bytes, unknown
  backing/cycles, allocation refusal before output copying, detached later
  mutation and empty-list INT element type. `InventoryCatalogAdmissionTest`
  covers digest oracle, padding, sticky collisions, map treeification,
  exact/one-short grants, mutation refusal, SUN-provider bootstrap, and native
  JSON stack-growth array identities/depth boundaries; not a whole-root
  numerical certificate. `InventoryItemDisplayProjectionTest`,
  `InventoryItemUnavailableDisplayTest` and `InventoryDisplayWriterTest` cover
  configured cap, monotone tightening, fitting envelope, terminal reprojection,
  bounded aggregate metadata, shulker removals, name aliases, retained-input
  syntax, escaped/duplicate aliases, conflicting removals, unknown additions,
  nested opaque spans, depth/UTF bounds, ID-only suppression overflow,
  terminal grammar, maximum legal ID, actual registered removal patches at high/
  low caps, same key through tightening/raise/reinterning, and refusal without
  size-terminal recovery.
  Sparse display-default inheritance is covered by the same projection/
  compatibility/writer families: native local inheritance, explicit entries and
  ordinary tombstones, default-read traps, nested container/bundle/projectile/
  food patches and local-default drift with registry restoration. Execution limits
  are scoped under [patch-only coverage](#patch-only-identity-and-display-coverage-and-live-gaps).
- **Client display and text.** `InventoryDomainCodecTest` covers the closed
  observation codec's absolute display envelope, pre-encode bound rejection,
  length/remaining-input check before allocation, empty/null/trailing rules and
  version isolation. `ClientInventoryV2Test` sends an external candidate with a
  real press-time face and exact locator/classification, opens no channel without
  a real face or for committed/entity/location targets, and does not alias
  different locators. `InventoryClientStoreTest` and `ClientInventoryTest` cover
  preview projection/barrier, baseline assembly, unknown-baseline buffering,
  checksum/resync, per-key revisions, byte-bound growth refusal before mutation,
  closed-watermark gating, deferred heartbeat recheck, future-baseline recovery,
  evicted replay fencing, cap enforcement before mutation, lowered-offer fencing
  with compatible progress, metadata-only display revision, stripped-state
  retention, wrong-epoch rejection, delayed old-cap rejection and UTF-8 display
  caps. `ClientInventoryTest` also clears tracking/unknown buffers on marker
  removal and rejects late `STREAM`/`SNAPSHOT`/`STATUS`/`HEARTBEAT`;
  `ClientPingRuntimeContentReceiptTest` clears inventory state on
  `REMOVED(EXPIRED)` while the stale marker visual remains and blocks late data/
  controls/delayed `CREATED`. `ClientItemDisplayTest` decodes owned render stacks,
  falls back to registry label/empty icon for malformed/missing/unknown/oversized
  payloads, treats identity mismatch as terminal, preserves enchantments/glint/
  custom data/model data at count one, handles normal/custom/item-name forms,
  bounds the access-order cache without mutable handouts, keeps same-id variants
  distinct, preserves removals, resolves vanilla delayed/warmed profile callbacks
  without changing the outer profile/name/display, and fences nested Lore/shulker
  icons. Native nested decode may still enqueue PROFILE callbacks; existing
  controls do not establish callback-free decode or general asynchronous
  isolation. Food suites cover explicit `FOOD` patches, effect identity/
  probability, hidden-chain isolation, loader-local access, NeoForge private
  constructor/codec capture, inherited FOOD defaults, honey cures, effect-owner
  callbacks and cure-table bounds without Mixins or loader launch.
  `ClientItemDisplayOpaqueComponentsTest` covers unknown explicit JSON refusal
  before value codecs, removals suppressing unknown mutable defaults,
  inherited registered/unregistered/transient opaque defaults across cache hits,
  one native name lookup, terminal name skip, and hook-written outer-patch
  refusal; not an installed owo/Mixin/loader test. Inherited Lore/nested fixtures
  assert native reference inheritance without sanitization/copying, explicit
  projectile child ownership over an opaque default, hook-written outer-patch
  refusal and default-Lore shared references. `ClientDisplayTextTest` compares
  36 name combinations with native hover precedence, preserves name provenance,
  owns returned text and keeps public naming free of load hooks/repeated fallback.
  Its validation/text fixtures cover finite visit/depth/character allowances,
  projectile-DAG/text-cycle envelopes, custom/item names, Lore, book pages,
  translation/sibling/selector/NBT/hover forms, cached mutated hovers, writable
  books, production item-chat barriers, trusted name keys/args/style/hover,
  custom/item-name flattening, refusal as empty optional, registry-label fallback,
  legacy `show_item`/`show_entity` text, JSON/SNBT parser admission, embedded
  NBT-path/selector recompilation refusal, small native-parser positives and
  formatting-graph behavior. Text-snapshot isolation mutates source arrays/
  siblings/hovers, preserves style flags, detaches projectile/Lore and explicit
  values, observes local default mutation, and limits item-hover copying to
  supported shapes. A client preview keeps the accepted reference/count/fences
  after display refusal; vanilla count-one/defaults and explicit Lore icon
  suppression have controls. Canonical-terminal cases keep the registry label,
  inherited local name, Lore/opaque values, empty retained patch, cache reuse and
  no name-hook invocation; malformed/mismatched payloads still refuse.
  `SpatialInventoryRowDisplayTest`, `NativeSelectorContentTest` and
  `SpatialInventoryLayoutTest` carry display through both compatibility
  projections into an owned count-one painted row with explicit removals
  suppressed, custom quoted names and registry-label fallback; headless CPU/
  codec/native-painter seams, not GPU/live screen.
- **Platform services and loader seams.** `PlatformInventoryServiceContractTest`
  covers the loader service contract and optional snapshot-layout seam.
  `PlatformInventoryMemberGateTest` rejects a denied root before the
  single-position provider and forwards exact level/position/face when admitted.
  Fabric and Forge loader member-gate tests recheck membership at lookup and every
  slot count/slot/blank/amount/resource/enumeration visit, reject reentrant
  revocation before world/native calls, fence stale handles, consume cursorless
  blank bounds without content, report revoked steps unavailable/incomplete with
  charged attempts, recover on fresh handles and propagate fatal failures; no
  loader launch or Mixin registration. `PlatformInventoryResidentTest` covers
  resident reads in common and each loader, frozen member/face scope, long
  amounts, uncertified getters, unknown overrides/foreign delegates/final
  revocation, Vault save traps/all-member gates, saved-delegate metadata and a
  513-character binary name beyond the 512 bound with an independent reflection
  oracle across eight methods; execution is in the
  [Common fixture-path run](build-checks.md#common-resident-fixture-path-run).
  `InventoryExternalFieldsTest` covers fastutil map capacities and PROFILE
  properties on untransformed objects without ServiceLoader; unsupported owners/
  subclasses reject before overrides and sparse-map/enchantment budget refusal
  precedes entry-view/iterator creation. `InventoryResidentAccessorShapeTest`
  checks remaining descriptors/registrations without external ports or Mixins.
  Named-host probes exercise selected registry/holder/builder/enchantment ports
  on compiled Common/Fabric/NeoForge inputs, with Forge registry/builder passing
  and enchantment initialization blocked by an absent active FML loading list;
  exported getter bodies pass all four profiles, and nine-case RGB probes compare
  native public-API results with an independent formatter oracle. A NeoForge
  named-module probe (`SecureJarHandler`, automatic GAME caller) passes direct
  fastutil/authlib access and a modeled GAME → SERVICE → MC-BOOTSTRAP chain
  without opens or read edges. These do not establish full production
  configuration, Fabric API coexistence, FML lifecycle/transformation ordering,
  shipped intermediary/SRG namespaces, arbitrary providers or installed Create;
  Fabric/Forge have no module-runtime probe.

### Failure diagnostic seams

`InventoryCaptureDiagnosticsTest`, `InventorySourceSnapshotTest` and
`InventoryRuntimeTest` cover captured external-cast messages/source frames,
provider/locator/face/owner context without invented coordinates, silent expected
denials, one content-free `WARN` at `NORMAL` for unexpected failures with finite
handling, higher-disclosure expansion, cast failures with unavailable outcomes,
charged attempts, combined metadata/read and close failures, self-suppression,
cleanup attached to expected denial or fatal read, resolve/live-witness
distinctions, one-close identity, failed replacement clearing without
double-close, failed plan discovery with context/paid prefix/no leak, and
snapshot-discovery bound preservation without content read, with the
bound-reason gate reaching the shared backend independently of unrelated report
volume. External cached-field
resolution/access failure log delivery is not injected.

`InventorySourceUnavailableExceptionTest` and
`InventoryRuntimeSourceDiagnosticsTest` cover lazy provider context, legacy
constructor without context, `DEBUG`-only expected unavailability with operation/
stage/source/target, independent ITEM/FLUID and source failures, consumer-local
recovery suppression and silent `NORMAL`/`DEEP`/disabled. `InventoryBackendDiagnosticsTest`,
`ClientInventoryLanesTest`, `ClientInventoryDiagnosticsTest`,
`SpatialSelectorSessionTest` and `InventoryFluidWorldTest` cover accepted/rejected
OPEN fences, one INVALID per lane, non-memory preflight naming, policy-skipped
lane reasons, repeated INVALID transitions, below-minimum/disabled laziness,
enabled `DEBUG` site/reason, grey-reason changes and fluid member-gate rejection
context without extra reads. These `DEBUG` reports leave earlier direct-seam
silence and `WARN` suppression unchanged.

`InventoryCertifiedFailureDiagnosticsTest` drives the real certified native port
through retirement: codec callback failure retains one graph with
invalidate-observation `WARN` and charges uncommitted work; typed metadata
unavailability is quiet; unexpected metadata exception reports once; final
publication failure reports once after charged validation; certificate
construction reports at exact-read-fallback; forwarding is silent; owner denial
leaves an authorized peer usable; read failure plus revocation retains cause and
detaches only that owner. `InventoryItemFailureDiagnosticsTest`,
`InventoryFluidFreezerTest`, `InventoryGenericComponentCompatibilityTest`,
`InventoryNbtSnapshotTest` and `ClientItemDisplayOpaqueComponentsTest` cover
quiet expected canonical-byte/display/fluid-identity refusals, one
all-variants-native-codec-rejected or empty-icon-registry-label `WARN` with
trusted stage only at higher disclosure, no native error-message supplier
evaluation, original structural refusal as cause, throwing name-hook causes without
attached throwables, and cached-construction runtime fallback/eviction versus
linkage propagation, without asserting Ping traversal or inherited-default
revalidation.

`InventoryMinecraftDiagnosticsTest`, `InventoryMinecraftSnapshotCaptureTest`,
`InventoryContainerReadsTest` and each loader's `PlatformInventoryDiagnosticsTest`
cover layout callback messages/causes/suppressed details/frames/read context,
cross-position warning suppression, failed layout callback retaining the live
route, quiet missing/unregistered schemas/optional bindings/lease loss/expected
denial/typed unavailability, `NORMAL` snapshot-layout failure with
live-read-fallback detail, throwing resolver/binding/membership/validity causes
without changing denial/fallback, guarded-access false/denial versus throwing
gates/delegates, repeated rate-control warnings, fatal gate propagation, lock/
loot/unload/topology silence, throwing frozen authority before unavailable
capture, foreign-delegate/pending-loot denial without loot-table unpacking,
loader membership denial versus throwing-gate WARN, absent capabilities/
throwing lookups at different positions, Fabric version fallback and NeoForge
Vault revocation/topology/metadata warnings. `InventorySyncPublisherTest` and
`InventoryAuthorityOrderTest` cover transport failure retry/report behavior,
packet-kind channel independence, control-callback failure handling, and
committed-marker ERROR context after initial publication failure without
re-entering admission or re-creating a marker. Shared helper/deployment limits
are owned by
[shared exception diagnostics](integrations.md#shared-exception-diagnostics).
Test appenders do not establish production file-appender delivery or low-frequency
live-soak behavior.

### Tracking projection

`InventoryTrackingRendererTest` projects received tracking data into bounded
lines: only received data supplies item lines, explicit zero is retained, unknown
or invalid counts never become zero, status/grey are explicit, lifecycle and All
variants labels stay independent with long names, and the annotation uses the
shared localized name policy. Eight bundled locales preserve full decimal
quantities for zero, `9007199254740993` and `Long.MAX_VALUE` within line bounds;
reversed grammar and oversized phrases have controls. CPU text/projection only,
not HUD/GPU.

Across this foundation, the
[inventory list interaction](interaction.md#inventory-list-gestures-and-navigation)
and [selector overlay rendering](rendering.md#selector-overlay-layout-and-frame-rendering),
coverage comprises headless runtime/model/production seams over recording
providers/transports plus one headless resident-slot capture of a single chest
and both double-chest members. Snapshot atomicity is modeled only for the
snapshot-capable branch against bootstrapped block entities, not a live
`ServerLevel`, chunk lifecycle, loader or multiplayer session, and not every
provider. Caller-trusted custom counters/decoders, external-trusted provider
internals, wall-clock/live-world provider safety, installed optional-provider
wiring, [native HUD integration](interaction.md#native-client-interaction-runtime),
live transport,
[rendering](rendering.md#selector-overlay-layout-and-frame-rendering), GPU
submission and the
[manual inventory matrix](manual-integration.md#pending-manual-and-integration-matrix)
remain pending.

## Logical container quotas and scan sharing

`InventoryFluidRuntimeTest` covers different physical tank members spending one
`CreateTankKey` tracking-slot allowance while each source opens with its original
owner/frozen face even with separation disabled; sources remain independently
read. Logical-quota evidence, not fluid-pool certification; execution is in the
[tank combined-tree run](build-checks.md#fluid-tank-grouping-and-presentation-verification).
`CompositeTargetQuotaTest` covers selected categories spanning opposite member
hits, repeated selection sharing a claim, the frozen key reaching tracking slot
subjects and recipient/target `STREAM` routes through rebaseline, independent
physical views spending the same group quota, no cross-binding of an exact preview
to another member at create, and losing markers keeping tracking when a sibling is
cancelled until their own removal/expiry. `DoubleChestGroupingSafetyTest` uses
actual production pair views for normal/trapped chests from either hit; locked,
loot-backed or unavailable siblings remain unreadable and grant no content
authority.

`CreateVaultComposedSharingTest` composes real segmented Vault access, ordinary
source authority and runtime with recording native fixtures on the exact legacy
path. Compatible tracking consumers attached before capture with the same owner/
face each consume a 900-slot sweep from one 900-getter journal with 900 logical
slot charges for their shared `TrackingSubject(VaultKey)`. Changing owner or face
requires two separate 900-getter rounds and spends 1,800 logical slots. A late
preview cannot join a started round but can share a compatible replacement
created after attachment; a newly selected tracking consumer starts fresh rather
than inheriting the preview prefix. These are conditional scan-reuse cases, not
one tracking record per Vault or a CPU reduction; they do not cover the certified
cross-player pool below. Older synthetic fixtures reusing one input owner across
different `PreviewSubject` UUIDs test separate logical subjects, not two actual
players. Live Create behavior, packet delivery, TPS, allocation and GC remain
unmeasured; topology/client evidence is under
[Create verification](integrations.md#vault-topology-grouping-and-presentation).

## Certified Tracking pool and publication coverage

`InventoryCertifiedReadTest` covers exact native chest/trapped-chest entities with
recording world/lock ports: opposite hits, different owners and all frozen faces
produce equal certified ordered address proofs while generic scopes stay
distinct; metadata construction/validation perform no getter or resident audit;
subclasses, reordered mappings, replacement members/worlds/registries, malformed
pairing, lock/loot denial and one-short admission are distinguished; cross-chunk
partner-unload traps cover construction/validation/catalogue/read, including
revocation during component freeze, without an unloaded-state query or accepted
partial item. `InventoryCertifiedVaultTest` composes production segmented Vault
access and the common certificate with headless native ports, covering member/
local-boundary/route ownership, same-layout local-handler replacement, callback
revocation and external/decorator isolation; not installed Create or actual chunk
lifecycle.

`InventoryPooledRuntimeTest`, `InventoryPooledChestRuntimeTest` and
`InventoryPooledVaultRuntimeTest` cover policy cohorts, committed-prefix reuse,
independent exact bindings/evidence, creator departure/owner denial, unsupported/
mapping/world/member isolation, current-port tail reads, shared collision history,
display tightening, a nine-address failure with 3 committed reads, 3 failed-page
attempts and 6 retry reads (12 attempts, no failed-page append/refund), capacity
cases retaining only the validated incomplete prefix, admission refusal,
completed-round exclusion, active certified Preview admission, exact-path sharing,
policy changes, successor entitlements and cleanup with zero retained/reserved
charges. Resource-refusal reasons, retained coverage and paid-journal replay are
under [shared-source delay diagnostics](shared-sources.md#certified-pool-delay-diagnostics).
Successor cases cover idle and fully adopted abandoned replacements across 32
cohorts with partial/`INCOMPLETE` observations and exact-path controls: fixed
variants reach 32/32 admissions; all-adopted slow peers consume fresh values
without extra getters while their original proof stays valid; against unchanged
runtime `0369f4c`, all-adopted negatives reach 0/32 while exact-path controls
reach 32/32. Historical proof survives without retaining pages; close releases
all handles/rounds/memory. Finite schedules, not every overlap or live soak.

`InventoryPooledBackendTest` and `InventoryPooledVaultBackendTest` drive preview
selection, four tracking markers, backend/publisher, native sources and encoded
frames into real client sessions with two owner UUIDs and two frozen faces, raised
pending-memory/logical allowances, both cadences changed and a separate physical
allowance. These are tuned, budget-admitted compatible cohorts, not
production-default guarantees. Tracking-only physical visits:

| Separate faces | Separate players | 54-slot chest | 900-slot Vault |
| --- | --- | --- | --- |
| On | On | 216 | 3600 |
| On | Off | 108 | 1800 |
| Off | On | 108 | 1800 |
| Off | Off | 54 | 900 |

Every chest marker reaches 756 and every Vault marker `2,700,407,250` per
recipient; the Vault has 45 members with 20 slots. Headless getter/ledger and
encoded-client oracles, not live players, transport, default-budget throughput or
CPU/allocation/heap/TPS/GC measurement. Per-owner metadata checks remain required
when getter counts fall; other schedules can defer a first association and require
additional reads under the
[active-admission rules](../../architecture/presentation/inventory.md#certified-tracking-observation-pools).
`InventoryPublicationAuthorizationTest` and `InventoryPublicationReservationTest`
cover offered/admitted/delivered evidence, old-cut/heartbeat ownership, final
validation after effective-budget measurement, revocation between irreversible
parts, defer without wire/watermark advance, balanced withdrawal/rebase/cancel,
one-tick validation opportunity, one-short refusal, attempted-work charging and
historical metadata without retained pages. Encoded cases add owner revocation by
another read or measurement, disconnect during final validation, authorized-peer
completion without leaked seed data, and policy changes preserving baseline/seed.
`InventoryPublicationReviewTest` fences changed recipient policy/view/session/
marker state before frames, spends no wire allowance on denied frames, preserves
differently proven completions/partials, keeps same-proof failure invalidating and
owner denial binding-wide, and covers encoding refusal/expiry/reference
release/reentrant tick. `InventoryPreviewFailurePublicationTest` withdraws and
re-fences matching Tracking cuts without discarding a differently proven
completion/cursor/revision/paid work, delivers clean replacements without
recapture, and keeps independent owners serviceable; Preview and Tracking use
distinct rounds, so failure propagation is not scan-sharing evidence.

### Certified active-prefix and 1620-slot coverage

`InventoryActiveJoinTest` covers pre-attachment journal replay, all four partition
combinations, exact Preview-only sharing, rejection of widened reuse after tracker
departure, installed association versus attachment, non-granting round terminals,
logical high-water accounting, owner denial, recovery floors, changed proofs,
policy sealing/restarts and staggered-cohort convergence. `CertifiedVault1620BackendTest`
composes backend, runtime, publisher, C2S/effective-budget S2C codecs and client
inventory over certified native `ItemStackHandler` locals on the user-confirmed
81×20 shape (1,620 slots). Stone and dirt on opposite or far faces: supplied and
normalized default profiles admit partial-preview cases without tuning; Tracking
pins its association before normal Preview close, the next Preview replays the
prefix, and both markers' initial round totals exactly 1,620 physical visits;
`SELECT` adds none. Complete counts are `1,620,438,210` and `1,620,439,290`; each
marker completes its initial observation and four replacements before unchanged
1,000-tick expiry. Completed Preview/Tracking controls do not grant the original
round. Mutation cases change already-read/unread slots before late attachment,
retaining the old prefix and only unread increases: `1,620,439,210` and
`1,620,440,290` from one 1,620-visit mixed-time round, without adding the seed.
Duplicate selection, rejected store and join-budget defer keep synchronous
once-only creation and release tentative ownership without getters; the current
`metadataBudgetDefer...` case still commits the synchronous selection with
independent receipt authorization/snapshot and later fresh 1,620-slot Tracking.
Native-source/encoded-client headless assertions, not live players, screen
interaction or installed Create/Refined Storage.

### Open Preview and recurring Tracking lifetime

`InventoryActiveJoinTest` lets a one-shot Preview finish its initial journal after
faster Tracking moves on without future successor entitlements, while slow
Tracking protects/adopts its successor including a pregranted sealed handoff; the
legacy control releases future handoff ownership when recurring peers leave.
`InventoryPooledBackendTest` keeps later unselected double-chest Previews open for
two owner-partitioned Tracking cohorts through tick 750 with the legal normalized
physical cap 16, a stricter three-slot seam, 16 MiB pending memory and default
logical quotas/cadences. Original selected Previews close normally; later requests
join active cohorts and complete once. Both open requests retain 756/round/cursor/
revision/opaque reference while both Tracking counts reach 768, then further
completion revisions; late selection does no getter and does not use the completed
Preview as cache. Negative controls on unchanged `9f41bf59` stall at 756;
at `7e435d4f`, the fix passed these regressions and its independent rereview.
Encoded headless lifetime, not stay-open UI. Earlier
pool execution is in
the [publication-stage run](build-checks.md#tracking-pool-publication-stage-run)
and [active-join run](build-checks.md#certified-active-join-and-validation-run);
live UI/config/provider/multiplayer/performance gaps are in the
[manual matrix](manual-integration.md#inventory).

### Terminal Preview evidence and active demand

`InventoryPooledBackendTest` reaches a Preview's 16-variant limit after 32 getters
while the 54-slot journal still reports `CONTINUE`; an already-read stone amount
changes 1 -> 64. `SELECT` adds no getter and retains the old amount only as a
provisional seed, not a Tracking accumulator.

| Admission case | Scope |
| --- | --- |
| Only the terminal Preview retains the old round | Tracking completes at 64 from a fresh observation, 86 getters across both. |
| A real Tracking peer still scans the old round | New tracker shares the paid prefix and completes at 1, 54 getters in all. |
| A new Preview opens while the terminal request stays open | New request completes at 64 with 86 getters; old request keeps incomplete count 1 and selectable references. |

An audience-pause case rejects widened admission through a retained paused tracker
without an admission getter; resuming keeps its paid journal while the other
owner's Preview uses an independent observation. This follows the
[mixed-time admission boundary](../../decisions/D0017-certified-tracking-observation-pools.md),
not a fresh-snapshot guarantee. Execution is in the
[terminal Preview and settings-resize run](build-checks.md#terminal-preview-admission-and-settings-resize-run).
`InventoryTerminalPreviewResetTest` adds five cases across same-epoch rebuilds:
transport-deferred `INVALID` through two resets and normal-tick retry; real
byte-budget defer/retry; retained-memory registration refusal through two resets
to current-view `INVALID`; close-before-handoff retirement; and a non-terminal
completed Preview keeping its closed value/selectable row. Retries do no new read
or validation; the registration case uses the real `RetainedMemoryLedger` and
records a real projection `MEMORY` delay. `InventoryPreviewResetLifecycleTest`
adds three cases for same-epoch `CLOSE`/replacement-`OPENED`: both close races
retire the previous view's request, release ownership immediately, block a late
replacement and reopen after the next eligible period; wrong epochs/view/request/
recipient, old-connection same-ID close and stale `OPEN`/`SELECT` reject while
current-view actions still pass. The 91-session case saturates the legal control
queue; terminal `INVALID` waits through replacement `OPENED`, hands off before
`INVALID`, and the client preview becomes invalid/unselectable without another
probe/read. Real client reset interface, not the complete presentation `RESET`
network path or live multiplayer. Execution is in the
[reset regression final verification](build-checks.md#inventory-reset-regression-final-verification).

### Recipientless Tracking and successor continuity

Historical note: these cases exercised dynamic recipient permission at their
recorded input; D0019 changes new-Ping timing, so they are not frozen-policy
evidence. Three `InventoryPooledBackendTest` cases use two owners on one unlocked
27-slot chest with player separation disabled and physical cap 16; explicit
owner-only frozen audiences model private channels, not live administration.
Removing B's inventory-field permission (not B's source-read authority) before
successor creation or during A's read after entitlement excludes B from future
grants and releases its held grant while retaining B's completed evidence/cursor/
paid prefix; A completes replacements/refreshes without baseline reset; B gets no
quantity/success/heartbeat frame while denied; restoring permission completes
fresh updates without widening audiences, source invalidation or lifetime change;
expiry stops tracking and teardown releases memory. The authorized cadence-wait
control delays B until A's successor completes, then B adopts it without a second
physical sweep. Execution is in the
[combined Java run](build-checks.md#recipientless-tracking-diagnostics-and-caption-reflow-verification);
live permission changes remain in the [manual matrix](manual-integration.md#inventory).

## Native incremental sweep coverage

The [native read contract](../../architecture/presentation/inventory.md#native-incremental-reads)
and [D0013](../../decisions/D0013-native-incremental-inventory-sweeps.md) separate
mixed-time traversal from explicit adapter snapshots; earlier whole-capture
assertions cover the explicit seams, not eager capture as the native route.

`InventoryCatalogNativeReadWiringTest` drives real native source, resident
freezer, catalogue and runtime with bootstrapped items and recording ports:
metadata-only preparation/refusal without fresh scratch before getter or snapshot
discovery; getter-time detachment with post-read revocation still rejecting reads/
witnesses; contiguous typed/legacy steps sharing one cursor; admitted empty-view
completion without getter; charged empty/throwing attempts; finite slot/catalogue
capacity; explicit incomplete totals; 540/900-slot sweeps under default memory
with 7 physical reads/tick, 11 logical slots/client period, two independently
charged consumers, a slower peer replaying the paid prefix without getters,
reverse mapping, blanks and `9007199254740993` amounts; already-read earlier
values versus unread later values; a live absolute-slot witness; a distinct
complete tracking round; replacement handoff/retained evidence; memory refusal
without cursor advance; display tightening without rescan; and release of retained/
reserved state. A >20 KiB display slow-replay case defers on one-short scratch/work
without cursor advance, replays exactly without another getter, and preserves an
earlier immutable display under tightening. A real 4096-slot view checks
pre-resolution refusal, ownership transfer and cleanup after failure/restart/
close. Headless ownership/admission, not live large-container or total-heap
measurements.

Backend/client cases pass production bytes into the real client: cross-tick
partial preview consumes each native slot once under one baseline; empty views,
provisional selected display, fresh tracking and absence-derived zero, retained
old totals, completion-plus-period scheduling, hard expiry, resync without
recapture, delayed-part gating, display rebaseline preserving identity/counts and
stripped detail, identity-refusal aggregate quality, no zero on getter/capacity
failure, and withdrawal on invalidity/revocation. Generic eventual recovery stays
uncertain; default 540/900-slot fixtures finish preview/select/fresh tracking
within bounded lifetime. Deterministic ticks, not throughput/elapsed benchmarks.
Trailing-empty-slot cases withhold earlier exact/aggregate rows, require the final
cut to repeat them unchanged, reject delayed/reordered data, resurrected retired
references and cross-invalidation, keep settled completion stable and complete a
true-empty case without a getter; no live transport.

`InventoryStepTest`, `InventorySlotJournalTest`, `InventoryDomainCodecTest` and
`InventoryCatalogAdmissionTest` cover detached pages, absolute bounds, empty/zero
distinction, long amounts, legacy conversion/evidence isolation, journal growth,
catalogue/slot capacity, hash collisions, prefix preservation, display tightening,
fixed observation cells rather than display-sized buffers on replay, invalid
observation rejection, unique retained cost/duplicate ownership and monotone
display reuse. `InventorySourceRetentionTest` covers owned-graph bounds,
shared-layout accounting, external evidence without live views, callback-free
cached cost and operation-local access; cost-model assertions, not provider
internals or whole-JVM retention. `InventoryValidationAdmissionTest` covers
pre-resolution/metadata admission, cumulative caller allowance, attempted-prefix
settlement, fresh grants and retained-evidence defer; a legal 16 MiB variant admits
only the probe, while a real Backend case keeps an absent FLUID sibling resolving
to `INVALID` and a small ITEM preview completing once. `InventoryProbeAttemptTest`,
`InventoryBackendLifecycleTest` and `InventoryBackendTest` cover same-iteration
candidate transfer, current revalidation, unused/duplicate/rejected candidates,
later ticks/ceilings, recovery restart, provider-key/face isolation, fail-close
retirement, one first-sampling source per preview and later-tick round reuse.
`InventoryItemDiscoveryAdmissionTest` covers 15 production discovery cases with
paid stages, work/`CaptureLimit` deferrals (never invalid/empty), paid attempted
prefixes, cancelled/throwing lookups without reads or reservations, source-owned
finish tickets, 1/257/1,024-member ITEM layouts (the 1,024 case at 128 MiB, not a
default 64/16 MiB guarantee), and legacy/non-staged profiles; the separate FLUID
1,024-member Create case is not ITEM evidence. `CreateVaultInventoryAccessTest`
covers descriptor reuse, in-place dynamic validation, empty offsets, a 900-slot
final address, local-count redistribution, resident handoff and final revocation;
no installed Create lifecycle. `InventorySyncPublisherTest` varies scoped evidence/
consistency/completeness/quality, 127/128/256-part frames, exact-cap/one-byte-over
entries, VarInt reindexing, dropped frames, indivisible oversized entries,
cumulative equal aggregates, corrective replay and release; recording-transport
framing, not live transport. `InventoryPublicationIntegrationTest` keeps generic
eventual empty/absence-zero uncertain while explicit atomic snapshots stay ready;
no generic `CaptureResult` or wire schema upgrade.

Execution and artifact limits are in
[build checks](build-checks.md#automated-verification-results). Installed Create,
live-world updates, multiplayer transport, TPS, GC and heap/elapsed performance
remain in the [manual matrix](manual-integration.md#inventory).

## Vault tracking performance evidence

### Deterministic construction, authority and scratch coverage

`CreateVaultTrackingPerformanceTest` drives the real NeoForge segmented provider,
common view/source validation, resident freezer, catalogue and runtime journal
over a legal 3×3×5 shape: 45 members × 20 slots = 900 slots. The slot count is
Create's default per-member capacity, not the user's actual `vaultCapacity` or
Vault shape. World/member ports and block entities are headless; resident access
uses a test service, not production Mixins. Assertions cover empty, repeated stone
and three mixed explicit patches, native identity partitions, totals beyond
signed-int range, physical ledger charges, no getter during preparation/idle
checks, same-total local-boundary invalidation before tail reads, and zero
retained/reserved after close. A full sweep reads 900 slots in 29 pages, one
completion only on the last page, at most 32 physical visits per reading tick;
pages are not network publications.

`InventoryResidentDiscoveryTest`, `PlatformInventoryScopeFactoryTest` and
`InventoryResidentScopeFactoryTest` cover first-request discovery, one service
across requests/scratch, first-context resources, absent-service Mixin fallback,
no rediscovery, visible initialization failure, legacy dispatch, one supplier per
consuming access, zero when declined, fresh guarded delegation, frozen face/dynamic
authority and member prechecks. `InventoryInPlaceSourceContractTest`,
`VaultCurrentSourceValidationTest` and `VaultInPlaceAccessContractTest` cover
current route/root/member identity, registry, owner/key/lock/loot/removal/load and
topology, same-shape local replacement, unsafe backing refusal before getter,
callback revocation, immediate freeze before metadata mutation, invalidation and
fresh recovery, shared-peer lifetime and memory release, rerouting for generic
capability replacement, no decorator opt-in, per-operation external bindings,
zero/one-short refusal before callbacks, exact native grants without whole-capture
audit, paid failed attempts and cached page profiles 0-64 as source metadata.
Source-owned profiles/member keys are retained; shared-layout/Vault charges and
callback-free cached cost are checked. Finite admission/lifecycle oracles, not
heap-retention measurements.

`InventoryCanonicalWriterTest`, `InventoryDisplayWriterTest`,
`InventoryEncodingCostOracleTest` and `InventoryScratchAllocationTest` cover the
independent all-tag/sorted-key/malformed-UTF byte oracle, geometric growth, exact
cap/one-over refusal, full replacement allocation, cumulative-copy admission,
UTF-8 growth/escaped/surrogate bounds, failed-ladder reset, no rehydration,
work/allocation refusal without size-terminal recovery, lazy JSON/NBT scratch,
direct scratch debits, first-allocation refusal, map cardinality/collision-tree
splitting, zero-effect laziness, first-array refusal before cure callbacks, ordered
phases/nested planning and multiple native effect families. Existing native/
explicit/default/mutation/cycle/DAG/external-codec/collision fixtures remain
unrelaxed. No identical acceptance at every budget threshold; adaptive-growth
costs remain guarded.

### Recorded allocation comparison

This historical comparison uses a 900-slot forwarding `CountingHandler` fixture
and the noncertified exact path; its percentages do not describe the later
certified 1,620-slot route. The two workloads are not one combined benefit.

Four conditions are compared: baseline, resident discovery with lazy scopes, Vault
current validation with cached cost profiles, and on-demand processing scratch.
SHA-256/Git-blob checks confirm the three harness/support files are identical
across all conditions; input provenance is external, and these are recorded
measurements, not a fresh run. All used JDK `21.0.12.1+1-LTS`, three warmups and
five samples per workload. `ThreadMXBean` measures current-thread allocated bytes,
not retained memory, total heap or allocation rate. Each sample uses a fresh
runtime/consumer over preconstructed native residents. The full sweep starts
after preparation, includes tick advances, page replay and retained-ledger work,
and excludes setup/close; separate workloads measure fresh-runtime admission (not
JVM cold start), the second 32-slot page and a completed-evidence idle probe.
Counters are separate from timed samples.

Median allocated bytes per prepared 900-slot sweep; reduction is from baseline:

| Contents | Baseline | Resident/lazy scope | Vault validation | On-demand scratch | Reduction from baseline |
| --- | --- | --- | --- | --- | --- |
| Empty | 186928368 | 96408264 | 709136 | 621392 | 99.67% |
| Repeated | 304761648 | 214241712 | 118464184 | 5688272 | 98.13% |
| Mixed | 305988176 | 215468072 | 119692104 | 17140424 | 94.40% |

On-demand scratch full-sweep min/median/max: empty 621392/621392/659584;
repeated 5688272/5688272/5689160; mixed 17140424/17140424/17150024. Second-page
medians are 25688/203544/612360 bytes for empty/repeated/mixed. The idle probe is
4512/4640/4640 bytes min/median/max for empty and 4512/4512/4512 for repeated/
mixed, with no getter. Raw five-sample byte/elapsed vectors, distributions and the
four-stage table are in the external `vault-perf-*-manifest.txt`,
`vault-perf-p4-final.xml`, `vault-perf-p4-final.measurements.log`,
`vault-perf-p4-four-stage-comparison.csv` and fairness-hash evidence. Elapsed
observations include JVM/GC variance and are not CPU time, game-tick duration or
live throughput.

For each prepared full sweep, common view/provider-factory calls change
1829/1829 -> 0/0, warm resident-service constructions 2729 -> 0, direct
adapter-loaded checks 1002877 -> 584036, common-loaded checks 420670 -> 84134 and
inferred full structure checks 7287 -> 5458. Factory calls imply segmented/layout/
descriptor construction in that path; constructors are not directly probed. The
scratch condition has the same validation/getter counters as Vault validation.
Reuse eliminates reconstruction, not all per-slot member checks; validation still
scales with slots and members.

Historical fixture inputs: physical allowance 32 slots/tick, tracking period 4,
target/server logical allowances 256/2048, pending memory 256 MiB and display/
frame multipliers 4. These are benchmark inputs, not the supplied profile for the
later certified run. At 32 physical slots/tick, 900 visits need at least 29
reading-eligible ticks; that allowance does not cap all validity checks at 32 or
prevent idle probes. Pending memory is not an MB/s allocation throttle, as defined
by the [configuration owner](../../config/server.md#inventory-policy-object).

The benchmark does not exercise real Create `MinecraftMember` reflection,
`ServerLevel`/`ServerPlayer` lookups, Mixin/service/bootstrap wiring, backend
selection, marker creation, publisher, network or a fully populated installed-mod
registry/provider workload. The reported live 1100 MB/s allocation and 30% CPU
case was not independently reproduced; these results neither convert to those
units nor prove the incident solved. Installed Create/Refined Storage import/
export, paired profiling and remaining
[manual scenarios](manual-integration.md#inventory) remain pending.

### Certified 1620-slot baseline and measurement boundary

Test-only baseline `18b1539f` establishes a separate 81×20 Vault using exact
native `ItemStackHandler` locals, without getter-counting wrappers.
`CertifiedVault1620BaselineTest` runs the real common source, certified native
port, segmented provider, resident freezer, catalogue and runtime journal through
typed world/member and resident-service ports. It checks ordered canonical
addresses, blank visits, independent native persistent-patch/canonical-byte/hash
oracles, exact long totals, no premature completion, detached results after
mutation and zero retained/reserved after close. The full sweep commits 1,620
addresses in 51 pages, one completion, no defer/invalid; physical evidence is the
ledger plus contiguous committed addresses, not direct native getter
instrumentation.

The directly inspected ignored local server feedback profile is the intended
input, with user-attested local-JAR provenance matching the 2026-10-30 06:20
baseline. Profile: physical allowance 1,024/tick, pending memory 16 MiB, display/
frame multipliers 1; Preview period 5 with client/server slot quotas 128/1,024;
Tracking period 3 with target/server slot quotas 512/4,096; both partition
switches off. `Profile.INCIDENT` validates without normalization;
`Profile.DEFAULTS` separately constructs/validates model defaults; neither
overwrites defaults or loads the feedback copy. This does not claim the incident
was reproduced or add file watching.

The harness separates fresh-runtime admission, second prepared 32-slot page, full
sweep and completed idle evidence; JDK `21.0.12.1+1-LTS`, three warmups, five
samples, separate counter pass. `ThreadMXBean` reports current-thread allocated
bytes; `nanoTime` reports elapsed time, not CPU, allocation rate, TPS or retained
heap. The full sweep excludes setup/close and does not time backend selection. Six
benchmark/support source hashes, fixture/world class hashes and both normalized
profiles match across baseline, fixed-metadata and coordinated-read stages; the
sequential backend correctness test is separate. The typed workload does not
execute production `MinecraftMember` reflection or real `ServerPlayer`/world
authority. `CertifiedVaultReflectionBaselineTest` is a separate metadata-only
32-validation fixture over unchanged actual Create archive method bodies and
initialized native locals with isolated optional ancestors and a stand-in
`ServerLevel`; not a composed content read, installed-mod launch or whole-game
benchmark. The later ITEM baseline-to-after comparison is in
[build checks](build-checks.md#item-redundant-work-final-verification): unchanged
actual counters, modeled retained bytes `+2` and small allocation deltas; a
matched observation, not a new baseline or speedup claim.

### Fixed metadata and coordinated-validation coverage

`CreateVaultMetadataPortTest` covers lazy tested-version gating, defining-loader
isolation, a thread-context-loader trap, class/owner/signature/return drift,
optional linkage/field-accessibility refusal, one discovery of six methods and the
native backing field, and later current controller/extents/axis/handler/backing/
audit reads; replacement, null/unsafe backing, getter override, scope/route
revocation and unloaded members stay dynamic. `InventoryVaultMetadataCostTest`
separates warm invocation accounting from conservative cold acquisition and
unfamiliar local-class costs; this stage does not reduce validation multiplicity.
`InventoryCoordinatedVaultReadTest` has 14 operation/authority/accounting
regressions over the audited certified route: handler replacement/empty handoff,
revocation stages, native detachment before metadata mutation, wrong provider/slot,
missing/duplicate/reordered stages, reentrancy, post-close reuse, cleared
references, one-short grants, unsafe backing/unloaded members and unknown-provider
coordination refusal; failed pages append no addresses, attempted work remains
paid, authorized peers retry tails. `InventoryCoordinatedCostTest` covers page
widths 1-64 and 1/2/45/81 members; retention includes operation/profile ownership,
not reduced retained memory. Review status is in
[known gaps](#known-automated-gaps), separately from tests/measurements.

### Certified validation measurements

P1 compares fresh pre-cache `015b37a9` versus `9f41bf59`, not combined P1/P2 gain.
Actual-archive metadata-only median allocation for 32 validations changes
3,264,768 -> 544,000 bytes (83.34% lower); elapsed min/median/max
5.0993/5.2015/6.5688 ms versus 1.9190/2.2346/3.0418 ms. Dynamic load/entity
counters are unchanged. Typed full-sweep elapsed medians in the P1 run are slower
than its fresh control; reflection-only savings do not establish universal speedup
or live CPU improvement.

P2 compares fresh `7e435d4f` (contains P1 and the Preview lifetime fix) versus
`a7fa3315`; successful-path counters include the independent association check:

| Workload | Common current checks | Provider matches | Provider loaded calls |
| --- | --- | --- | --- |
| Second 32-slot page | 163 -> 131 | 390 -> 262 | 69,176 -> 48,184 |
| Prepared 1,620-slot sweep | 8,253 -> 6,633 | 19,746 -> 13,266 | 3,502,404 -> 2,439,684 |

Both stages retain 1,620 physical/committed visits, 51 pages, one completion, no
defer/invalid; preparation/idle probes do no physical visits. Validation remains
Θ(slots × members), not constant or cached. The retained ledger grows by 108 bytes;
every close releases retained/reserved charges.

Median allocated bytes per prepared certified 1,620-slot sweep; reduction is
from the fresh P2 control:

| Profile | Contents | Fresh P2 control | Coordinated read | Reduction |
| --- | --- | --- | --- | --- |
| Supplied | Empty | 87,582,720 | 70,553,448 | 19.44% |
| Supplied | Repeated | 96,707,896 | 79,683,424 | 17.60% |
| Supplied | Mixed | 110,517,768 | 93,424,176 | 15.47% |
| Defaults | Empty | 87,523,960 | 70,552,224 | 19.39% |
| Defaults | Repeated | 96,706,264 | 79,680,432 | 17.61% |
| Defaults | Mixed | 110,495,864 | 93,418,784 | 15.46% |

The original `18b1539f` supplied repeated median is 96,897,784 bytes, historical
context not P2's fresh control. Raw vectors and distributions remain external.
Idle allocation rises slightly; default repeated-item admission elapsed median is
slower after P2; P2's reflection-only median allocation is unchanged. These
limitations preclude one CPU claim or a promised millisecond improvement.

External evidence directories `certified-vault-1620-baseline`,
`active-inventory-join`, `certified-vault-metadata-cache`,
`active-join-preview-lifetime-fix` and `certified-vault-coordinated-read` retain
`HANDOFF.txt`, commands, logs, XML, hashes and raw measurements; the last adds
`before-after.csv`, `derived-counters.csv`, `measurement-hash-comparison.txt` and
raw JSONL/distributions. No live Create/Refined Storage import/export, CPU,
allocation-rate, TPS, GC, heap or update-latency result is established; those
remain [pending](manual-integration.md#inventory).

## Preview-seeded tracking coverage

The [handoff and bootstrap contract](../../architecture/presentation/inventory.md#selected-preview-bootstrap)
has headless source-to-encoded-client and publication coverage.
`InventoryCatalogNativeReadWiringTest` clears the old observed slot before
`SELECT`, creates immediately without an extra getter, then replaces the seed with
a fresh positive, equal or absent/zero result. Its 540/900-slot partial-preview
cases keep exact long quantities after close while the noncertified new sweep
starts at slot zero, without an old round or seeded accumulator; certified active
prefix is [separate](#certified-active-prefix-and-1620-slot-coverage). Separate
cases retain one selected value rather than the native catalogue and release
unselected payloads on close. Queued-publication cases change a real native chest
lock after withholding the seed, then deliver unknown, duplicate and other-valid
selections in one tick: marker/sidecar, Basic and correlated outcomes stay
synchronous, no selection drains queued quantities, and the next normal tick
detects revocation without a getter. A provider-backed long-count case and
unchanged-provider/native-chest controls pass production bytes into the client;
they cover seed retirement, fresh zero recovery and expiry without a live world or
loader launch.

`InventoryBackendTest` rejects missing counts, forged/retired tokens, owner,
baseline, state and current-invalidity without a content read; retained exact-key
count versus last delivered row and folded-key count versus another all-ID subtotal
are under [creation snapshot](#creation-snapshot-server-coverage). Patch-flow,
authority-order and `SableInventoryHandoffTest` cover distinct exact identities,
current binding, rollback and one materialization/store/outcome under replay;
Sable alias/revocation stays recording-provider scope. Native backend/client cases
cover Basic-before-seed, independent recipient baselines without recipient
previews, owner lock/layout, display-cap repair without recapture/detail,
first-scan incomplete history retry/repair, permanent seed retirement on
invalidity through recovery, exact-to-aggregate retirement without a false
subtotal, and hard expiry before completion; complete accepted native results stay
ready, generic eventual recovery uncertain. `InventoryBootstrapPublicationTest`
passes real writer/reader frames into the client: installed seed baselines versus
source completion, long exact/aggregate seeds, equal-count replacement,
missing/reordered parts, early controls, heartbeat stability, send/memory refusal/
release, obsolete updating withdrawal, grey incomplete/invalid history through
rebase, recipient isolation, old-seed withdrawal even after a fresh offer, late
incomplete controls not downgrading a completed result, pre-fresh
`READY`/`UNCERTAIN` leaving seeds updating, reverse multipart keeping the closed
seed, first-delivery failure retaining the seed, future checksums waiting,
completed controls refreshing, cap reduction stripping the provisional display,
the two-entry selected-replacement regression, no unclosed recovery key through
duplicate invalidation/rebase, reset/removal cleanup and stale invalidation. The
narrow path refuses generic partial captures, preview routes and multi-key
bootstraps. Tracking projection is [above](#tracking-projection); encoded-publisher
seed gating/property receipt is in
[presentation verification](presentation.md#content-receipt-chat-and-pending-completion).

### Creation snapshot server coverage

`InventoryBackendTest` distinguishes retained exact-key versus last delivered and
folded-key versus another all-ID subtotal, rejects missing references without a
zero, and preserves aggregate display/selection outcomes across transport refusal.
`InventoryCreationPublicationTest` covers fully handed cumulative Preview-cut
completion, initial staging sharing Tracking capacity and exact settlement.
`InventoryInitialPayloadTest` covers pure Basic sizing against the real codec,
oversized semantic refusal without an encoding probe and the full-envelope bound
without a 1 MiB reservation. `InventoryInitialRetryPagingTest` covers fixed
retry-pass boundaries, shared discovery capacity, known-record skipping,
lifecycle clearing, behind-cursor canonical refresh and later-failure retention.

`InventoryProductionPolicyTest` covers selected Preview creation before the first
Tracking tick, post-encode fresh Basic/annotation authorization, metadata refresh
retry/coalescing, at most two narrowing attempts, memory admission before rebuild,
native fluid source/route/layout/scope and exact-to-aggregate retirement, physical
completion without the current Preview cut remaining uncertain, recipient pressure
from 24 HELLOs and the 0.125 multiplier, late first-recipient creation value 5
after Tracking 9, expired deferred creation, invalid creation not resurrecting and
callback/lane revocation. Four parameterized methods (15 cases) cover the frozen
initial boundary: ITEM/FLUID memory, detached operation-work and staging refusal
without `CREATED`/session/known-record advance; recovery admits the payload-free
suppressed initial for the denied recipient while the owner keeps source-backed
snapshot and later permission does not widen denial; both lanes pay exact
encoded-body/workspace charges through handoff with no extra validation/content
read or retained staging; audience/expiry/view/removal/disconnect after encoding
release staging/admission; first late-recipient and cached-metadata refreshes pay
admission and a true disconnect cannot restore the receipt. Two further ITEM/FLUID
methods add four callback-time removal cases: removing the real canonical marker
and sidecar after suppressed encoding sends no later `CREATED`, advances no session/
known record and releases staging/reservation/publication ownership; removing only
the sidecar keeps the current fan-out's ordinary fallback. These exercise the
seeded-Basic path; the alternative `sourceSeeds.isEmpty()` path was reviewed
statically but not independently exercised dynamically, so coverage does not
generalize to every audience/source combination.

`InventoryAuthorityOrderTest` covers committed Preview evidence/annotation before
storage without a content witness, receipt-binding metadata revalidation paying
failed metadata without content reads, and publication failure preserving the
committed create. `InventoryBudgetDelayIntegrationTest` keeps memory defer
retryable while unavailable/unsafe/empty/bounded continuation outcomes are not
delayed, including expiry, revocation and eligible counterpart pressure. Execution
for current-source additions is in
[build checks](build-checks.md#inventory-initial-admission-and-terminal-reset-final-verification);
earlier creation-snapshot cases are in the
[provider-trust final verification](build-checks.md#provider-trust-and-frozen-authorization-final-verification).
Cross-boundary client chat/transport is in
[presentation verification](presentation.md#immediate-inventory-creation-snapshot-coverage).
These headless tests/builds do not establish live Create/Refined Storage behavior,
the reported Vault import/export failure cause, live multiplayer, GPU rendering or
performance; those remain [manual](manual-integration.md#inventory).

### Client creation admission capacity

`ClientInventoryCreationCapacityTest` adds 13 wire-decoded client-admission cases.
It fills finite metadata-channel capacity with real initial receipts while no
tracking channels are active, then replaces an old same-logical-target marker
whose local fallback is stale but server expiry is not due. The replacement uses a
frozen double-chest key across different physical members/owners, is admitted
net-zero, and keeps the accepted marker/frozen creation snapshot including a long
amount above the exact-integer boundary; a timely `EXPIRED` releases the slot.
Four ineligible scenarios reject without store/presentation/name/seed/feedback;
invalid epoch/view/name/revision creates have no effects while the same revision
stays retryable; a same-ID stale/newer refresh preserves old metadata/visual
deadline; and the prospective check visits actual unique metadata entries without
releasing a slot. Real wire envelope/client runtime only; no server eviction/
capacity, live expiry/clock skew, complete presentation `RESET` path or live
multiplayer. Execution is in the
[inventory reset regression final verification](build-checks.md#inventory-reset-regression-final-verification).

## Patch-only identity and display coverage and live gaps

The [inventory owner](../../architecture/presentation/inventory.md#item-variants-and-component-fallback)
and [D0012](../../decisions/D0012-explicit-patch-identity-and-display.md) define the
accepted boundary. The test-source inventory above includes shared-reader, native
patch oracle, server projection, client explicit-value/default-inheritance,
pre-frozen-key backend flow and publisher scheduling assertions.

**Automated coverage.** The identified whole-tree execution covered all four test
modules, all three loader builds and `verifyModIdentity`; its exact command, test
counts, artifacts and documentation checks are owned by the
[patch-only full-tree matrix](build-checks.md#patch-only-full-tree-matrix), not
restated here.

Separately, an isolated `OwoDerivedComponentMapProbe` passed with the real
`DerivedComponentMap` from installed
`owo-lib-neoforge-0.12.15.6-beta.1+1.21.jar`: direct `javac`/`java` exited zero
with `PROBE_RESULT=PASS`, using a classpath derived from the Gradle test worker.
For stone and cooked porkchop, an exact native `PatchedDataComponentMap` with the
real owo wrapper as prototype produced the same canonical identity/display as an
unwrapped control, matching the native persistent-patch oracle; cases included
explicit additions, an equal-default addition, removals and an empty patch, while
manually seeded differing wrapper name/food stayed outside projection and using
the wrapper directly as the outer map was refused. It passed against final
compiled Common main/test classes, manually constructed the post-Mixin map shape
and seeded the delegate without `derive`; it did not apply Item/ItemStack Mixins,
start FML or exercise client naming/rendering/networking. It establishes the real
jar prototype shape against those classes, not automatic wiring or client
behavior; no Gradle invocation or game session was part of the probe. Native
oracle, pre-frozen-key flow, wire round trip and client resolver fixtures are
complementary seams, not one native-container-to-live-row test; the publisher
budget fixture starts from preprojected data, the client wrapper models a local
default map rather than installing owo, and the real-jar probe has only baseline/
construction scope.

**Pending live evidence.** Automatic Mixins, live installed-mod behavior, loader
lifecycle, default drift across real installations, delayed transport, GUI rows,
tracking HUD and GPU behavior remain outside that source evidence. Live scenarios
remain in the [manual matrix](manual-integration.md#inventory).

## Resynchronization and budget policy verification

The revised
[resynchronization](../../architecture/presentation/inventory.md#tracking) and
[budget policy](../../architecture/presentation/inventory.md#budgets-queues-and-memory)
contracts have passing headless evidence in
[automated verification](build-checks.md#automated-verification-results).
`InventoryS2CPacketTest` round-trips legal periods/zero cooldown in the unchanged
`OFFER` shape and rejects out-of-range/overlong/noncanonical encodings without
defaults; configuration/strict wire evidence is under
[configuration verification](configuration-ui.md#server-performance-contract-verification).
`InventoryBackendTest` covers positive cooldown refusing repair just before the
deadline and accepting at it, zero/maximum negotiation and live refresh,
positive-to-zero tracked-Ping repair, old deadlines not blocking zero, same-tick
repair, coalescing pending requests and resuming an admitted blocked baseline.
`ClientInventoryTest` covers the same positive-to-zero bypass/same-tick repair,
unknown-baseline/incomplete-completion/future-heartbeat expiry waiting through the
tick, separate 8/9/15-tick dominant waiting terms, an independent short retry
cooldown, and a serviceable multipart baseline completable beyond the window.
Finite deterministic scenarios, not live-load starvation guarantees.
`InventoryMemoryBudgetIntegrationTest` drives a simulated 3 GiB retained ticket
under the 4 GiB unlimited guard: unlimited mode retains its saved finite value,
admits to the guard and refuses one byte more; lowering updates the existing
ledger without deleting live charges; preview/tracking defer before resolver,
validation or read; releasing the ticket shares one physical read without raising
the cap; cleanup releases costs. The large tickets model accounting only.
`InventorySettingsTest` checks the tracking-slot unlimited guard not widening other
count guards and memory-mode conversion retaining its finite value. Live timing and
multiplayer repair remain in the [manual matrix](manual-integration.md#inventory).

## Frozen recipient authorization and fluid provider trust coverage and live gaps

The lane-policy, recipientless, certified-pool, member-gate and creation-snapshot
cases above remain evidence for dynamic permission and per-member gates, not for
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md).

**Automated coverage.** `InventoryFrozenControlTest` covers first `HELLO` using
live host session authority, same-epoch reset isolation between recipients, a
normal reset republishing Preview, and `CLOSE` removing a deferred `OPENED` retry.
`InventoryOpenedPacketTest` covers the `OPENED` wire kind, lane-bit authorization,
session-wide shape, exact size and malformed/truncated/trailing rejection; the
[inventory-v2 protocol](../../architecture/network/protocol.md#inventory-previewtracking-route-inventory-v2)
owns the grammar. `FrozenInitialAdmissionTest` covers first-delivery admission at
capacity without mutation and reopening after removal. Execution is in the
[provider-trust final verification](build-checks.md#provider-trust-and-frozen-authorization-final-verification).

**Pending live evidence.** Preview authorization at request start and a separate
new authorization for `SELECT` without inheriting the Preview result; creation
snapshot/receipt behavior under the frozen rule, including de-op/re-op where old
Pings keep their create-time result and new Pings use the new result; the global
field/selector/child, `RESET`, metadata and same-type Ping grant scenarios in
[presentation coverage](presentation.md#frozen-recipient-content-authorization-coverage-and-live-gaps);
and fluid validation through the target provider, whose boundary/stricter
exceptions remain owned by the
[inventory provider contract](../../architecture/presentation/inventory.md#fluid-provider-trust-and-validation-exceptions).
No dedicated-provider implementation or measured live performance benefit is
recorded. The
[manual matrix](manual-integration.md#frozen-recipient-authorization-and-provider-trust)
owns these checks.

## Idle source health scheduling coverage and live gaps

**Automated coverage.** `InventoryIdleHealthSchedulingTest` has six headless
current-source cases over production backend/runtime with counted validation/read
callbacks: completed Preview period and deferred overdue retry; active Tracking
probing while an idle lease waits; live Tracking period change; `INVALID` recovery
retaining per-tick attempts; a late-joining recipient's immediate check/completed
handoff; and a pending handoff validating immediately before send without a
separate probe. Execution is in the
[provider-trust final verification](build-checks.md#provider-trust-and-frozen-authorization-final-verification).
Deterministic seams, not live timing.

**Pending live evidence.** Live timing remains pending; the
[idle source health scheduling contract](../../architecture/presentation/inventory.md#idle-source-health-scheduling)
owns the behavior.

## Lane policy hint refresh verification

`InventoryPolicyFrozenDemandRegressionTest` covers the on-demand
[lane policy hint refresh contract](../../architecture/presentation/inventory.md#lane-policy-hint-and-refresh):
a replaceable provider changing a lane without provider replacement leaves the
cached hint stale, while the next `OPEN`/create resolves current authority and
keeps the accepted Preview's `OPEN` lanes and existing Ping's creation-frozen
content/Tracking. In steady state with frozen Preview/Tracking, ticks do zero
permission callbacks for the two Inventory fields while SECTION-mask callbacks and
live quantity/source reads continue; a later SECTION change evaluates its own
fields without clearing frozen lanes; provider replacement refreshes the queued
hint once and budget retries do not re-evaluate it. `InventoryProductionPolicyTest`
includes first-HELLO OFFER-before-POLICY, deferred-offer/data gate, transport
failure retry, queued-owner retention across retry/reconfiguration/view/epoch,
provider-callback refresh and session-replacement/token cases. A new inventory
create resolves its own audience/content authorization and does not inherit a
Preview's frozen lanes/type/hint. Behavioral ownership assertions, not a
performance claim; execution is in the
[final ITEM execution](build-checks.md#item-redundant-work-final-verification).

## Resident scope and certified mapping boundary verification

`ResidentScopeReadBoundaryTest` exists in common and all three loader test source
sets and passes in the final suite: the explicit current-authority gate remains
the whole-source check at mutable-callback boundaries; frozen membership/mapping
are not a current authority token; production membership/mapping boundaries keep
nested authority checks and refuse a foreign delegate. Loader cases count explicit
loader gates without replacing them with frozen lookups; Forge/NeoForge
`ResidentScopeReadBoundaryLoaderTest` uses the actual native wrapper, installs a
removal hook in the final callback and asserts zero content getters, no world key/
native wrapper on refused caller allowance, and a paid admitted prefix.
`CertifiedChestFrozenMappingTest` covers the retained P3 certificate: single/
double-chest ordered address mappings (27/54 pairs) proved once at construction,
immutable entity/slot-address/member copies, malformed/reversed mapping refusal,
and later current validation rechecking live identity/pairing/size/owner/lock/grant
without producer-owned address lists. Baseline authority gates and conservative
cost accounting remain unchanged; the old cost is not lowered. The frozen mapping
remains under the
[certified pool contract](../../architecture/presentation/inventory.md#certified-tracking-observation-pools);
the authority boundary remains under
[provider safety](../../architecture/presentation/inventory.md#provider-layer-and-safety).
P2 lookup deduplication was withdrawn because evaluated `valid()` callback paths
did not establish callback-free closure; the new tests protect the retained
baseline sequence and add no unpaid authority pass. P2 is not a delivered
optimization and has no completion/performance/allocation claim.

## Immutable publication work reuse verification

The [publication authority and progress contract](../../architecture/presentation/inventory.md#publication-authority-and-progress)
owns inventory cut authorization/progress; the
[sync publication contract](../../architecture/presentation/shared_sources.md#sync-publication)
owns generic immutable cut/cost mechanics. `CutWorkReuseTest` covers the detached
encoded-fragment helper: one immutable fragment backs independent recipients
without mutable bytes, and a cross-lane value is refused before encoding.
`InventoryChecksumsTest` covers independent ITEM/FLUID golden vectors over
non-ASCII samples, input-order independence, lane-domain separation, wire-entry
projection and one whole-cut visit; FNV-1a bytes and v2 wire format are unchanged.
`InventorySyncPublisherTest` covers checksum computation once per admitted cut and
reuse across parts, a newer offer not rewriting it, retry reusing the immutable
packet without another admission traversal, delivery freezing a separate
delivered-map checksum for heartbeat, and withdrawal/rebase starting a fresh cut;
admitted workspace stays charged through delivery and releases at completion.
`InventoryFanoutEncodingReuseTest` covers operation-local fan-out: an identical
tracking projection encodes once across recipients without sharing proof,
authorization or session state; each recipient retains admission/current checks;
distinct folds use separate encodings; provisional seed and completed cut are
distinct operations and each case observes its own publication; a blocked
recipient keeps its prior closed value and shared encoding does not substitute for
proof/delivery admission. Headless assertions, not live transport, elapsed-time,
allocation, CPU or throughput measurements; no N-times benefit is established.
The helper adds no service-provider interface or cross-tick completed-content
cache. Execution is in the
[final ITEM execution](build-checks.md#item-redundant-work-final-verification).

## Performance monitoring coverage and production evidence

The [performance monitor
contract](../../architecture/presentation/inventory.md#performance-monitoring)
owns measurement meaning, lane/shared attribution, non-interference and sampling
lifetime. Persisted keys/selector grammar are owned by [client
configuration](../../config/client.md#performance-monitor) and [server
configuration](../../config/server.md#persisted-fields); HUD controls/control-state
presentation by [settings
screen](../../UI/settings-screen.md#performance-monitor-controls); HUD source-state
presentation by [HUD projection and
presentation](../../architecture/presentation/inventory.md#hud-projection-and-presentation);
numeric authority by [server configuration
authority](../../architecture/authority/server-config.md#remote-performance-telemetry);
and message grammar by [network protocol](../../architecture/network/protocol.md).

### Monitor-core evidence

At input `f24b5b3`, 22 monitor-core methods passed. `MonitorSchemaTest` covers
closed scope/selector-identity catalogues, summary expansion without child-timing
or shared-memory double counting, defensive copies, missing-field unavailability,
saturation, negative memory remaining and endpoint-separated means/rates.
`MonitorAccumulatorTest` covers four enable combinations, bounded rolling windows/
latest-only reads, gauge/budget invalidation, generation/lifecycle clearing,
saturation, warm-up, idle-gap/clock boundaries, local-only phases and a rolling-
history oracle. `MonitorDemandTest` covers transmission-demand derivation without
enabling collection. Headless schema/accumulator/demand results, not server/client
sampling, protocol, HUD, multiplayer or live performance. Configuration/selector
coverage is under
[configuration verification](configuration-ui.md#performance-monitor-coverage);
execution is in
[build checks](build-checks.md#performance-monitor-implementation-and-final-acceptance).

### Production monitor evidence

Recorded isolated P3/P4/P6 selections and the earlier P1-P6 combined-tree
acceptance passed. P3 selected `InventoryMonitorBackendTest`,
`InventoryMonitorPublisherTest`, `InventoryMonitorSharedTest` and later
`InventoryMonitorAttributionTest`; P4 selected `ClientMonitorCollectorTest`,
`ClientMonitorStateTest`, `MonitorDisplayModelTest`,
`ClientInventoryMonitorTest`, `ClientPingRuntimeMonitorTest` and
`InventoryMonitorHudTest`; P6 covered respawn/connection continuity. Exact
commands/counts/identities are in
[build checks](build-checks.md#performance-monitor-implementation-and-final-acceptance)
and external evidence. See [negative controls](build-checks.md#verification-negative-controls)
for pre-fix selection details.
Selected headless runs do not establish sockets, loader
launches, live GPU/font output or performance measurements.

**Server sampling.** `InventoryMonitorBackendTest` covers: both switches off read
no clock/retain no sample through open/tick/failed selection/close/reconnect; four
enable combinations expose only enabled lanes, an existing request takes a fresh
baseline, a new server generation resets it and both-off reads no clock; real
nested intervals use business time, a bounded read step is not per-getter,
inclusive parent/child are not summed, requests are not sessions and lane/shared
readings stay independent; a detached capture is timed once and later decode does
not recapture; retry-only ticks include publication while same-tick sink reentry/
nested ticks cannot erase/double count; a deferred round's capture belongs to the
preparing lane, publication reservation is visible before reset, repeated ticks do
not accumulate usage and physical/logical period IDs settle at their owners.
`InventoryMonitorPublisherTest` keeps fixed-key packets/checksums/queue depths/
charges/retained bytes identical monitoring off/on; each normal handoff sends one
complete packet and classifies only observed bytes; unknown handoff stays omitted;
no extra retry/monitor encode; a throwing send keeps conservative charge/retry,
separates known/unknown and uses one body measurement per attempt; sequential
ledger snapshots retain -10/-5/15 without rewriting earlier snapshots; activating
while a baseline is queued exposes logical queue cost but no invented charged-byte
counter. `InventoryMonitorSharedTest` covers paid-prefix replay adding no physical
charge, only newly visited tails charged, fresh ledger not cumulative history, and
one tracking seed detached-encoded once for two recipients without recapturing
completed Preview work.

**Client sampling, state and paint.** `ClientInventoryMonitorTest` accepts actual
Preview/snapshot/stream packets and measures one inclusive APPLY per packet across
nested commits; direct store fragment/replacement paths are instrumented; a
disabled collector reads no clock or changes counts.
`ClientPingRuntimeMonitorTest` separates runtime ingress, selector preparation/
draw and inventory-tracking preparation/draw; disabled early returns read no
clock, local data exists without remote telemetry and the HUD painter is not
sampled. `ClientMonitorCollectorTest` collapses nested same-lane intervals, keeps
lanes/phases separate and cannot publish an interval disabled mid-span.
`ClientMonitorStateTest` fences status/sample: a sample cannot authorize, ACTIVE
waits for first sample, expiry is STALE at the exact bound, and permission/
collection/server-generation/demand-revision/reconnect/capability transitions
clear/reject; authoritative OFF stays public without disabling local collection;
local-only selection creates no remote demand; shared dependency-off is not an
invented zero. `MonitorDisplayModelTest` keeps five default summaries compact/
deduplicated, does not sum inclusive phases, shows missing as unavailable not zero,
retains negative shared memory/lower-bound/saturated meaning, styles only OFF red,
emits no rows for a disabled view and preserves metric identity/immutable prepared
text. The historical `InventoryMonitorHudTest` lower-left painter result covered
GUI-scaled paint, chat/notice clearance, clipping without model mutation and zero/
empty views; it does not cover the later upper-left projection, whose per-group
reserved-height behavior is in [stable HUD reservoir
coverage](#stable-hud-reservoir-ux-coverage). Headless runtime/state/painter seams,
not live GPU/font/window/socket/loader/multiplayer; live scenarios are in the
[manual performance-monitor matrix](manual-integration.md#performance-monitor).

### Timing attribution evidence

`InventoryMonitorAttributionTest` drives real ingress/tick/runtime/selection/
publication/source-close callbacks with a business clock; 16 invocations cover: a
Preview source close from session revocation charged to Preview TICK_WORK as one
interval with no invented INGRESS and the disabled Tracking peer uncharged;
neutral session refresh charged to neither lane; nested Tracking inside Preview
tick separately attributed while nested HELLOs form one INGRESS union with one
count and max=total; SELECT-create callbacks nesting HELLO keeping Preview/
Tracking ingress separate even with one lane enabled, and a nested tick recorded
in both unions (overlap, not disjoint costs); `SELECTED`/`REJECT` measure/publish
belonging to Tracking while control-byte counter stays with Preview wire, with P3
sampling separately timing retried `SELECTED`; and over-depth HELLO recursion
exhausting bounded timing scope, omitting INGRESS rather than partial, completing
all packets and measuring exactly next ingress, with no clock read when disabled.
Earlier selected-execution counts are historical; final combined-tree
acceptance is in
[build checks](build-checks.md#performance-monitor-implementation-and-final-acceptance).
P6 respawn/connection continuity covered same-UUID/listener keeping the watch and
later samples through the current player, a replacement listener not inheriting
without fresh subscription, and post-respawn delivery/capability using current
permissions; headless composition, not a real socket/loader/multiplayer run.

### Hot-allocation and clock evidence

An external harness checked nine captured actual sources on the owner thread:
`ServerInventoryMonitor`, `ClientMonitorCollector`, `MonitorAccumulator`,
`MonitorSchema`, `MonitorTuning`, `TransportHandoff`, `CostLedger`,
`RetainedMemoryLedger` and `LedgerTicket`. Input was Git HEAD `f24b5b3` plus the
captured working tree; before/after SHA-256 and final recheck report all nine
unchanged. It compiled byte-for-byte source copies with JDK 21 `javac --release
21`, ran isolated compiled classes with only the annotations jar, without
repository outputs/Gradle/domain suite, on Eclipse Temurin `21.0.12.1+1-LTS`
Windows x64. `ThreadMXBean` current-thread allocation support/enablement were
true; repeated current-thread/by-id reads agreed and allocated zero; an escaped
4096 × `byte[256]` control reported 1,114,112 bytes.

Three independent JVMs passed 13 scenarios each (39 rows): two normal seeds and
one `-XX:-DoEscapeAnalysis` run with `dontinline` for three target classes/harness
helpers. Every hot case used two 500,000-iteration warmups and eight measured
500,000-iteration batches; disabled batches alternated never-configured objects
with enabled-then-switched-off objects. Across every JVM/batch: disabled server
set (ingress, tick, lane/phase scopes, end/count/gauge/invalidation, budget,
memory, shared gauge/budget, sent outcomes, tick completion, latest) allocated
zero, read the clock zero times and produced no sample; disabled client collector
(APPLY/PREPARE/DRAW begin-end, count, gauge, latest, tick completion) and disabled
accumulator (duration/count/gauge/budget/shared recording, invalidation, latest,
snapshot) likewise zero; enabled primitive server/client paths allocated zero
while proving execution (7,000,000 and 2,000,000 injected clock calls per 500,000-
iteration batch, with tokens/counters/nesting checked), and direct accumulator
recording zero; duplicate completion zero/no clock/no new sample. The enabled
scalar budget/memory/shared-budget wrapper combination allocates reading records
by production design, with no clock/sample during the interval. Enabled small-
window publication used a test-only two-ticks-per-bucket/four-bucket window with
two 16,384-tick warmups and eight 4,096-tick batches: each read the clock 4,096
times, published 2,048 escaping samples and retained the last eight one-per-tick
observations; allocations are consistent with periodic immutable publication/
scalar wrappers, not disabled-path violations. The 358 fixed arrays/5,374 cells
and four bucket slots kept identity/length; bounded storage reuse, not retained
heap. The window is test input, not production tuning.

Setup/configuration/reset allocation is outside measured intervals and not
claimed zero. The check does not establish whole Backend/client-frame/rendering/
GPU allocation, heap, live allocation rate, GC, CPU/TPS or wall-clock, and no
`nanoTime`/ns-per-op comparison was made; the concurrent Gradle build was not
measured. It also does not establish live-session overhead, sockets, loader
launches or HUD behavior. Reproduction is in external
`inventory-monitor-hotalloc-20261108`: `RESULTS.md` retains environment/table,
`METHODS.md` methods/exclusions, and `check-support.ps1`, `run.ps1`,
`summarize.ps1`, command files, per-batch logs, `results.csv`, `results.json` and
source/harness hashes are retained. A separate packet oracle uses off-measurement
small `OFFER` encode calls and counter reads before warmups to avoid cold C2
string-constant resolution; its original 64 measurements/checksum/workspace/strict
`allocated == 0` assertion are unchanged, and injected `byte[1]`/`byte[4096]`
allocations still fail at 1,536/263,168 bytes. This closes only hot-API and
measurement-test isolation; no whole production-path allocation, retained heap,
live rate, GC, CPU/TPS, transport cost or wall-clock claim. External
`inventory-packet-allocation-20261108` retains handoff, reproductions, JFR/native
traces, controls and hashes.

### Monitor queue/publication/read accounting verification

The [work, queue and memory
readings](../../architecture/presentation/inventory.md#work-queue-and-memory-readings)
and [sync and transport
measurement](../../architecture/presentation/inventory.md#sync-and-transport-measurement)
require source-connected evidence. The final whole-tree run in [build
checks](build-checks.md#inventory-monitor-queuepublicationread-accounting-verification)
provides it from `InventoryMonitorPublisherTest`, `InventoryMonitorReadAttemptsTest`
and `InventoryMonitorSharedTest`, driving production publisher/runtime/queue/
shared-source/transport with a recording transport and business clock.

- Queue gauges: merged pending absolute entries per context; admitted cut parts
  minus the business-advanced part, excluding a successful sent prefix; older
  admitted cut plus newer pending counted together; queue cost retained until the
  last part; observed empty zero; missing baseline unavailable; `CREATED` staging/
  standalone excluded. Reentrant cancel/replacement/withdrawal/rebase/partial
  success/thrown sends keep the surviving peer's admitted frame, avoid negative
  and do not promote an old cut.
- Publication/cut: bootstrap/unchanged-revision installation, multipart and empty/
  unchanged cuts, and duplicate/deferred/invalid/retry/failure/cancellation/
  withdrawal/rebase scenarios; semantics/exclusions are owned by the sync/
  transport contract. Fixed-key packets/checksums/queue depths/charges/retained
  bytes stay identical monitoring off/on; queued baseline/unique retained memory
  stays immutable across cap reduction/release.
- Read attempts: native, legacy/adapter, certified pooled and `SELECT` witness
  attempts, including failed getter, typed failure after validated prefix, pooled
  page failure committing a prefix or nothing, opaque conservative grant, detached
  decode failure and uninitialized runtime reporting unavailable not zero; charged
  attempts rather than returned slots/READ timing/successful journal entries; paid
  replay excluded; capture_reads/validation/probes/provider work separate; one
  shared reading not multiplied across lane/consumer/recipient; rolling counter
  distinct from current-period budget; no disable-to-enable backfill; baseline may
  include backlog. Collection/generation changes and nested selection scopes fence
  old operations without replay; ledger rotation preserves the fatal witness
  identity.
- Handoff invariance: `HANDED_OFF`, `NOT_HANDED_OFF` and `UNOBSERVED` keep legacy
  business progress/retry; unknown bytes stay omitted. Regressions cover
  same-callback cancel/replacement/withdrawal/rebase and runtime ledger rotation;
  review-control details are under
  [negative controls](build-checks.md#verification-negative-controls).

Headless source-connected assertions, not live sockets/loader/game/multiplayer/
HUD/installed provider/whole-path allocation/heap/GC/CPU/TPS/wall-clock. The
enabled shared `SELECT` path permits small per-operation scope allocations; the
disabled zero result does not extend to it. Stable semantic HUD wrapping is
separate and not part of this accounting result.

### Pending production test matrix

The following remain outside the headless P3/P4/P6 seams, P1-P6 combined-tree
acceptance and scoped hot-allocation check. Named production tests/external
measurements verify their own stated contracts, not live/integrated behavior here.

| Acceptance surface | Pending monitor-specific evidence | Existing fixture base |
| --- | --- | --- |
| Actual route | SUBSCRIBE/STATUS/SAMPLE over a real negotiated connection/runtime handshake, latest-only delivery, corrupt/stale rejection, live permission/collection transitions. | P2 packet/coordinator headless; P6 respawn/connection selection is headless but not a real connection. |
| Loader lifecycle | Actual optional-payload registration, dispatch and client callback under loader launch, dedicated/integrated startup and stop. | Isolated registration/dispatch checks do not launch a loader. |
| Actual subscribers | Zero/one/many clients through the live service, subscription changes not altering collection, cleanup on disconnect/permission loss. | Coordinator/backend composition headless, not a socket/permission provider. |
| Live HUD | Fixed upper-left GPU/font rendering at multiple scales/windows, no chat/heart/notice reserve, all rows submitted/framebuffer-clipped, CJK/Latin right-wrap with scope-title/field/`OFF` colors, backing alpha/glyph width, reserved blanks, layout retention/reset, control compaction, unified byte/time formatting, eight-locale labels, font/resource reload, zero/narrow viewports, GL state/pixels, bottom-layer totem/F3/chat/inventory/pause. | Headless formatter/model/label/layout/cache/preparation/ABI cover current projection including `N/A`, per-row backing and reserved-height relations; prior lower-left painter is historical; no live GPU/font/window/layer run. |
| Live performance | External-profiler comparison, TPS/GC/allocation-rate, measured transport bytes, real-session overhead and HUD CPU cost. | Scoped hot-allocation covers only listed monitor APIs on the owner thread; whole-path allocation, HUD GL-query allocation, heap/rate/GC/CPU/TPS/wall-clock unmeasured. |

Real server/client sampling has headless production-seam evidence; the scoped
external check covers listed disabled APIs plus enabled primitive/duplicate/
publication shapes. Route interoperability, native provider behavior, runtime
handshake, loader lifecycle, actual subscribers, live HUD and whole-production/
live performance remain pending. Complete scenarios are in the
[manual performance-monitor matrix](manual-integration.md#performance-monitor).
P1-P6 combined-tree acceptance is in
[build checks](build-checks.md#performance-monitor-implementation-and-final-acceptance).

### HUD UX redesign coverage

The redesign has headless source/full-Common/isolated-classpath evidence for
formatter, display model, compact labels, layout, cache and Mixin ABI; current
per-field `N/A`, per-row backing and reserved-height are in [stable HUD reservoir
coverage](#stable-hud-reservoir-ux-coverage). Live GPU/font/locale/window/layer
acceptance remains pending.

- `MonitorNumberFormatTest` contributes 18 Common XML tests; an independent
  external oracle did 256,989 exact comparisons, 113,589 lower-bound inequalities
  and 169,937 byte syntax/cap assertions without production helpers/`BigDecimal`.
  It covers the strict `>8192` unit boundary, one-pass carry/digit-cap, non-byte
  integer/two-decimal precision, rational rates/ratios/percentages, lower-bound/
  saturation/unknown and signed-long-scale input. External artifact
  `monitor-number-readonly-review-20261109-4e3bd5a2`; pure-code, not paint/locale.
- `MonitorDisplayModelTest` contributes 43 tests under reservoir coverage; an
  independent probe passed 283 assertions/116 raw selectors. Coverage includes
  same-scope dedup, peak-only/actual-interval means, memory triplet/percentage/
  unit honesty, one `N/A` per scope, status/OFF styling, immutable prepared text
  and native-width wrapping. No live paint/font/GPU/screen.
- The compact vocabulary adds 81 HUD-only `monitor.pingforit.hud.*` keys in each of
  eight locales. `MonitorHudLabelsLocalizationTest` contributes 18 tests after
  wrapping, deriving the finite key set from closed schema enums and checking
  duplicate-free JSON, placeholder/format signatures, every field/presentation
  token, five scope prefixes, ASCII punctuation and fixed English/Chinese prefix/
  status. The joint resource run predates wrapping; its execution and initial
  failures are under [negative controls](build-checks.md#verification-negative-controls).
  Eight locale files retain 116 full-vocabulary entries; 121 selector identities/default
  selections unchanged. Resource/format, not live locale/translation/font review.
- Four HUD classes contributed 12 earlier UX tests (`InventoryMonitorHudAbiTest`
  5, `InventoryMonitorHudCacheTest` 2, `InventoryMonitorHudStateTest` 1,
  `InventoryMonitorHudTest` 4); current `InventoryMonitorHudTest` has 5 after
  wrapping. Layout covers fixed top-left margin, all prepared rows including below
  framebuffer, height-only resize, zero/narrow/extreme widths, native long Latin/
  CJK wrap with OFF-only red, cache invalidation for view/width/scale/font/
  language/options and scalar GL-state restoration. ABI covers the typed
  `GuiGraphics` store, strict type-only hook, original graphics return, separate
  frame HEAD, removal of old late `GuiMixin` paint, resource-reload identity,
  manifest placement and executable gate truth table. Each loader passed four
  static ABI tests plus actual Mixin transformer apply over loader-transformed
  `GameRenderer`; reproduced independently. Bytecode/reflection/transformer smoke,
  not GL/pixel/loader launch/running-game Mixin.
- Prior lower-left/reserved/visible-row results are historical coverage of the
  replaced layout, not evidence for fixed upper-left/no-reserve or new-redesign
  proof.

Full Common execution is part of the
[HUD UX whole-tree run](build-checks.md#hud-ux-final-whole-tree-verification).
Disabled hot-allocation covers only sampled APIs, not zero allocation/GL-query/
whole-frame CPU for the new HUD; mirroring painter state is not GL/pixel evidence.
Live scale/window/font/locale/reload/layering/multiplayer/performance remain in
the [manual performance-monitor matrix](manual-integration.md#performance-monitor).
The projected contract is [HUD projection and
presentation](../../architecture/presentation/inventory.md#hud-projection-and-presentation).

### Stable semantic HUD wrapping coverage

The [HUD projection contract](../../architecture/presentation/inventory.md#hud-projection-and-presentation)
owns fixed semantic breaks within one scope logical row. This section records the
preceding semantic-wrap stage; its isolated/whole-tree executions do not cover the
subsequent per-field `N/A`, per-row backing or reserved-height UX, which has
separate coverage in [stable HUD reservoir
coverage](#stable-hud-reservoir-ux-coverage). Live paint remains pending for both.
The five affected classes were compiled with JDK 21 `javac --release 21 -Xlint:all
-Werror` on an external classpath and run together; isolated and whole-tree
executions are in
[build checks](build-checks.md#fixed-semantic-hud-wrapping-final-verification).
Eight wrapping cases account for the whole-tree increase.

- Fixed groups: selected timing phases, independent budgets, non-sync requests/
  queue/work/state, memory and sync start new visual lines at the same left
  margin; different digit/unit widths keep group starts across widths; one scope
  prefix, one preceding `N/A`, and logical row text/metric IDs remain available.
  The stateless re-forming from current view is superseded by the reservation
  contract.
- Locale/width: five-group structure across eight bundled locale resources,
  including Latin/CJK; each keeps one scope prefix and loses no non-separator
  content at fixed/native wrap points. Localized rows fit at 18/43/97 native-width
  units; group metadata is not parsed from field separators.
- Fallback/paint: over-wide groups wrap and may push later groups down; absolute
  line numbers are not fixed. Narrow/extreme non-byte integers stay complete
  without truncation/abbreviation. Every fitted line is one painted row at the
  unchanged top margin/height/spacing, including below the 427x240 headless
  reference bottom; no line dropped, only `OFF` red, non-active rows use native
  wrap. Painter/hook/state paths unmodified; the painter test uses the existing
  paint loop.
- Scope: no new compact label key/setting/sampling/protocol; existing 81 HUD keys
  per locale, selector catalog and defaults unchanged.

Headless source-connected/painter-seam evidence for the preceding stage; does not
cover newer `N/A`/backing/reserved-height or live GPU/font/window/layer/loader/
multiplayer/performance; 427x240 is a computed reference. Pending live scenarios
are in the [manual performance monitor matrix](manual-integration.md#performance-monitor).

### Stable HUD reservoir UX coverage

The [HUD projection and
presentation](../../architecture/presentation/inventory.md#hud-projection-and-presentation)
contract owns per-field `N/A`, per-row text backing, scope-title color and
active-layout reservation. This section summarizes current headless coverage; the
[whole-tree execution
record](build-checks.md#stable-hud-reservoir-ux-whole-tree-verification) owns
command/totals/manifest/artifacts. Exact title/backing values remain
architecture-owned.

- Model/reservation: `MonitorDisplayModelTest` contributes 43 tests for selected-
  field and pair/triplet subvalue `N/A` slots with labels/group positions retained,
  known-zero/partial-known values, principal-only summary fields, `2 -> missing ->
  2` retention, `1 -> 3 -> 1` high-water growth, blank rows without stale text,
  compact control rows and `ACTIVE` recovery, including TITLE/`OFF`/ordinary
  literal/reordered/duplicate-text/native-wrap grammar. `MonitorLayoutStateTest`
  contributes 5 tests for normalized order/duplicate selection, view/status/
  sample/availability/control and HUD-off, identity-boundary clearing, disabled-
  selection round trips and connection reset with a reused server generation.
- Localization: `MonitorHudLabelsLocalizationTest` contributes 18 tests for five
  scope prefixes, localized field/status vocabulary, ASCII grammar, only-`OFF`
  red, localized missing slots/known zeros and native CJK/Latin wrap without
  deriving group boundaries from labels.
- Renderer/paint: `InventoryMonitorHudPreparationTest` contributes 5 tests for one
  native-width measurement per nonblank visual row, one background before each
  text row across cached widths, reserved blanks emitting nothing, horizontal
  shadow/padding clamping without bottom culling and TITLE/`OFF`/ordinary runs
  without label parsing. `InventoryMonitorHudCacheTest` contributes 2 tests for
  retained-layout invalidation/resource-reload identity;
  `InventoryMonitorHudAbiTest` 7 for typed graphics store/type-only hook/native
  two-pass background-text ordering/depth/resource reload;
  `InventoryMonitorHudStateTest` 3 for normal/boundary/flush-failure restoration,
  empty preparation and scissor padding; unchanged `InventoryMonitorHudTest` 5 for
  top-left paint, fixed groups, height-only resize and native wrap.

An independent read-only probe recorded 86/86 checks, including non-null old
remote data followed by compact controls and a short `ACTIVE` value, confirming no
stale values/padding/reuse; headless, not GL. These seams do not establish a real
GL context, pixels, native font/resource reload, window/GUI-scale layout,
totem/F3/chat/inventory/pause layering, running loader/Mixin, multiplayer or
performance/allocation behavior. The existing 16 native-classpath skips remain
gaps, not passes. Live scenarios remain in the
[manual performance-monitor matrix](manual-integration.md#performance-monitor).

## Known automated gaps

- Live loader/Mixin application, GPU/font/UI and narrow-window rendering,
  multiplayer/mixed-version handshakes, installed Create/Sable/Refined Storage,
  manual gameplay and CPU/TPS/GC/allocation measurements remain pending in the
  [fluid inventory manual scenarios](manual-integration.md#fluid-inventory).
- Certified pool/successor/final-handoff fixtures are headless. Live two-owner/
  chunk/lock/topology changes, installed tested-version Create and mixed config
  protocols require the [manual inventory scenarios](manual-integration.md#inventory);
  tuned getter matrices do not establish default-budget throughput or CPU/
  allocation-rate/heap/TPS/GC gains.
- Certified active joins and supplied/default 1,620-slot schedules have native-
  source/encoded-client evidence, not every admission/overlap schedule or live
  completion guarantees. The scoped P1/P2 evidence did not include a full Gradle
  rerun. Scoped allocation does not establish whole-JVM bounds, installed Create/
  Refined Storage behavior or resolution of the live incident.
- Reported installed-container selection failures lack live reproduction or
  resolution confirmation; passing headless tests/builds do not close them.
- Revised timing/memory admission passed the combined-tree run, but active
  selected-variant capacity across period boundaries has no dedicated regression;
  simulated large memory tickets do not measure actual heap.
- Preview-foundation/runtime/publication coverage is bounded lifecycle/cancellation,
  presentation cleanup, per-client/target progress, explicit snapshot, cross-period
  decode, native cross-tick reads/journal replay, barriers, closed-watermark,
  admission order and publisher recovery/repair. Native input/HUD, live-world
  provider safety, installed optional-provider wiring and live transport still need
  evidence; no manual world/GPU/transport/multiplayer validation is recorded.
  Finite UUID churn/ledger cleanup is not prolonged connection or heap-soak proof.
- Server-safe fluid registration lookup is headless in
  `InventoryFluidRegistryLookupTest`, `FluidRegistryClassSafetyTest` and
  `InventoryFluidLoaderIntegrationTest`; Forge patched-first
  `PlatformFluidRegistryServiceTest`/`NativeFluidRegistryFreezerTest` cover wrapper/
  default-root WATER and handler/freezer cases. These are patched-first/classpath
  checks, not installed dedicated-server/real-FML evidence, and do not widen
  audited registration trust or supply a fallback key. The supplemental
  native-classpath run passed Forge integration and previously skipped patched-
  first cases ([execution record](build-checks.md#supplemental-native-classpath-verification));
  installed-server/real-FML behavior remains manual.
- Inventory call-site logging coverage is limited to the
  [diagnostic seams above](#failure-diagnostic-seams). Other runtime probe/
  projection branches, capture-handle source-close and standalone snapshot/
  ticket-close diagnostics lack direct log-event assertions. Test appenders do not
  establish production file-appender delivery or low-frequency live soak.
- Default-cap capture, preview-seeded selection and fresh tracking have headless
  native-seam evidence. Independent review of the audited native fast path
  establishes composed root/native/Catalog/caller allocation/work bounds only for
  reviewed inputs within the versioned static source-event model and finite guards.
  Separate independent review of on-demand scratch is not recorded; the composed
  review does not establish it. Closed component certificates do not certify the
  generic external-trusted codec route. Cursorless attempted-prefix, symbolic
  lock-certificate and native JSON stack-boundary tests are finite seams, not whole-
  JVM certification. Reviewed composition assumes caller-backed grants, fresh
  lazy-read scratch and an explicit process-fixed initialization boundary within
  the guarded JDK 21/25 envelope; it does not measure total JVM allocation or
  certify arbitrary JVM/providers, heap, profiling, GC, TPS or elapsed time.
  External-trusted LIVE provider/generic codec/lookup-getter/hash-equality/native-
  cache internals and their retention remain outside certification; finite
  compatibility/returned-tree fixtures cannot bound them universally.
- Generic registered-component compatibility/external-context/owned-tree
  assertions do not establish installed Offroad or other mod runtime compatibility,
  or live preview-to-tracking identity fidelity. The
  [patch-only transition](#patch-only-identity-and-display-coverage-and-live-gaps)
  passed the complete integration run. Client hook/explicit-value text-snapshot
  fixtures remain local seams, not guarantees for arbitrary hooks, inherited
  defaults or asynchronous mutation.
- Isolated named-host Mixin probes cover selected raw ports on compiled Common/
  Fabric/NeoForge inputs; Forge registry/builder reads pass while enchantment
  initialization is blocked by its active-FML LoadingModList requirement; RGB
  overwrite probes exercise native public-API boundaries on all four profiles.
  Full production config/Fabric API coexistence/FML lifecycle/transformation
  ordering/shipped intermediary-SRG remain outside; compilation/field-port tests
  do not close them.
- Inventory ordering/freezing, explicit snapshot continuity after ordinary content
  changes, native mixed-time sweeps, empty-view completion, explicit zero versus
  unknown, per-key revisions, client barriers and publisher recovery/repair have
  headless/production-seam evidence, not live execution. Per-Ping/recipient
  baseline/resync isolation is at the production seam; detached double-chest
  capture/cross-period preview/same-alias layout change/restart-retirement/
  evidence admission are covered. Unknown-baseline expiry/RESYNC has each dominant
  waiting term but no live roundtrip; cross-target period accounting is headless
  for SNAPSHOT bytes only; zero-heartbeat publication exercises watermark/repair
  without asserting absence of periodic HEARTBEAT. A maximum legal display value
  through the production server identity catalogue and queue/budget admission is
  unproven, as are transport-asynchronous heap retention and live heap soak.
  Same-ID aggregate retirement/checked sums/normal retained publication have
  encoded-frame/client seam evidence, not live execution. Variant quotas, a real
  provider resolver/recovery-probe expiry, hard stop at Ping expiry, fresh capture
  after ordinary live contents change, multi-period low-consumption quota under
  live load, a real mod-provided bounded multi-member extractor/installed Vault,
  live-world safety invalidation/recovery, live-source failure diagnostics and real
  world/multiplayer/tick-order/GPU remain manual.
- Headless provider-confirmed external inventory coverage includes
  `SableInventorySourceTest` alias/source independence and Create Vault member-
  access tests. Pending live: membership/sub-level checks, real provider layout/
  capability, member-gate revocation between operations, getter-free selection
  and preview-to-tracking handoff, reads on three loaders and loader/runtime
  integration.
- Item-display safety has headless decode/cache/row/callback-baseline evidence,
  not GPU/live profile service. Native nested decode may create PROFILE callbacks
  before icon eligibility; selected detached nested-head/ordinary mutable-value
  controls do not establish callback-free decode or general asynchronous PROFILE
  isolation. Opaque/parser-admission/text/working-stack fixtures cover specific
  explicit-value/returned-text/expansion-cache/isolation/hook boundaries, not
  arbitrary mod codec/hook internals/every mod text/universal deep copy. Real
  FML/GLFW lifecycle, GPU rendering, language hot reload and asynchronous mutation
  after validation remain outside.
- Sparse display-default fixture families are headless codec/decode/projection/
  writer seams with a limited representative sample of actual inventory-frame wire
  sizes through production writer/reader. Patch-only/terminal assertions passed
  the integration run scoped above. They do not exercise a live GUI row, installed
  mod local defaults, client/server default drift across real installations,
  asynchronous component mutation, multiplayer transport or timing; the full
  send-queue/byte-budget path remains manual/integration evidence.
