# Testing and verification

This document separates three kinds of evidence: automated coverage known to
exist, gaps that remain, and manual/integration scenarios that are still
pending. An inventory statement records established coverage scope; it is not a
claim that a test, build or game session was run for the current change.

For implementation changes, the tracked verification expectations are to:

- run the relevant automated tests and repository lint, static-analysis, format,
  type, and compile checks;
- build every affected Fabric, Forge, and NeoForge source set, and run
  `verifyModIdentity` when loader packaging, artifact identity, or the complete
  shippable artifact set is in scope; and
- report every command actually run and its result, every applicable check not
  run and the exact reason, manual validation actually performed, and remaining
  manual or integration gaps.

Use the public commands and task orientation in the
[repository README](../../README.md#install-build-and-verify). Local maintainer
or agent instructions may add workspace-specific execution constraints, but are
not a public prerequisite and do not replace these tracked expectations. Never
report rendering, multiplayer, interaction, or other manual behavior as verified
unless it was actually exercised or covered by an appropriate automated test.

For a documentation-only change, check the changed documents' links, headings,
fenced blocks, and affected coverage statements. Report those checks as the
commands or review actually performed; document review alone is not runtime,
build, multiplayer, rendering, or manual gameplay validation.

## Existing automated coverage inventory

### Domain, identity and authority

The current suite covers:

- the fixed Target Type and Ping Type catalogs, including their values;
- numeric-priority and declaration-order Target Type resolution;
- optional-content fail-soft matching;
- target identity and the captured-ray flow;
- Marker ID and marker codec behavior;
- `targetTypeId`, including `entity_block`, surviving marker codec round trips
  without changing the protocol shape; and
- same-target winner selection and recomputation;
- synchronized-deadline and visual-state seams for display expiry and
  expire-fallback behavior through `ClientMarkerDisplayDurationTest` and
  `ClientMarkerStoreTest`; and
- source ordering and one evaluation of each consulted resolver in
  `DefaultTargetResolverTest`.

Marker-store, packet, and update tests provide recipient-scoped state and
channel-mode transport seams, but do not establish the complete `ServerCore`
ordering and channel/admission matrix in a live client/server path. Loader
registration of the legacy, presentation-v3, presentation-policy, and
superseded marker routes is confirmed by source inspection for Fabric, Forge,
and NeoForge; `ModIdentityTest` additionally pins the fork identity, the
packet-ID namespaces, and the versioned v3/v2 presentation route IDs.
Focused packet tests cover the permitted create/remove request fields, exact
consumption of their encoded payloads, and safe decoding. These checks do not
establish server authority. There is no direct automated test that a valid
legacy location S2C packet or a superseded marker S2C packet is ignored by the
client, nor a live cross-loader network test.

### Presentation snapshot negotiation, policy and adapters

Focused common tests cover the versioned presentation contract at model,
property, codec, admission, and lease-refresh/capture-budget seams:

- `PresentationCoreTest` covers allow/deny selector wildcard semantics with
  allow-before-deny precedence, fail-closed selector construction and value
  limits, epoch/view store behavior (frozen snapshots after expiry, per-adapter
  revisioned clears, tombstone/eviction resurrection guards, bounded-history
  fail-closed behavior, and server-mask pruning that keeps marker identity),
  the framed codec's skip-denied, duplicate, depth, and oversize rejection, and
  bounding oversized semantically valid Basic field content to an empty stale
  section before initial delivery while fitting sections pass through
  unchanged; the server-side `sendInitial` ordering that applies this bound
  before delivery is source/compile covered rather than directly exercised;
- `PresentationServerBasicEntityTest` covers the world-free production
  `basicEntity` assembly seam used by live Basic capture: an unnamed living
  entity keeps its entity type, current and maximum health, and localized base
  name; an unnamed dropped item keeps its entity type, item ID, count, icon,
  and localized base name; a present custom name composes as custom-plus-base
  for both an entity and an item stack; an undemanded name builds no name field
  and skips the registry-bound encoder; and an absent custom name keeps the
  trusted base component instead of strict composition. It runs headless
  against real entity and item instances with builtin registries and component
  JSON name encoding; the surrounding live path (server-level and registry
  dimension lookup, entity lookup acceptance, world-backed projection, packet
  delivery, and rendering) remains unexercised. Capture scheduling, lease
  admission and budget handling are covered separately and headlessly by
  `PresentationServerRefreshTest` below;
- `PresentationServerExternalBlockTest` covers the production detached
  external-target boundary, demanded-versus-undemanded Basic state observation,
  the name-only path without state observation, null-name omission, failed fresh
  capture, and the transition that retains only still-demanded fields as stale.
  These are pure assembly and transition seams; the complete world-backed
  server capture, live-provider, network, HUD, and rendering path remains
  unexercised;
- `PresentationPropertyCoreTest` covers the property-reference model: root and
  nested literal record-key resolution, invalid-id/depth/key rejection,
  deterministic ref ordering, annotation requirements (section adapter, an
  existing addressed value, a known Ping Type), and that sequence contents are
  never annotatable; per-target-type default references failing closed for an
  unknown type; tag and registry-ID target-selector lookups staying
  independent; the selector length bound counting the tag prefix; ordered
  property-Ping overrides that can re-add; and typed intent/selection
  validation against known Ping Types;
- `PresentationCodecAnnotationTest` covers the wire annotation contract:
  deterministic root and nested annotation round trips, absent annotations
  staying nullable without consuming payload, a denied top-level frame skipping
  its whole frame including annotations, sequence entries carrying no
  addressable annotation,
  unknown annotation type and section trailing bytes rejected on decode, typed
  bounded property-intent frames with trailing safety, blank or whitespace
  record keys round-tripping without annotations, and a non-boolean annotation
  on a blank key path rejected on decode;
- `PresentationPacketsV3Test` covers the v3 packet contract: no `SUBSCRIBE`
  kind and new route IDs, schema-only `HELLO`, bounded typed property
  observations on `CREATE` with duplicate-ref rejection, `REMOVE` round trips
  and a targetless `CREATE` being corrupt, `OFFER` manifest round trips,
  `RESET` carrying the authoritative mask with an empty mask denying
  everything, `CREATED` carrying the default ref beside the annotated section
  (and being corrupt without one), and `REMOVED`/`REJECT` round trips;
- `ServerPropertyAdmissionTest` covers the production admission and recapture
  seams with a read-only capture context: a server value winning over a stale
  numeric claim and one adapter capture seeding both roots; rejection of
  unavailable, wrong-kind, and unauthorized selections without leaking claimed
  data; actual tag-ID membership changing the allowed Ping Type independently
  of the whole-marker type; zero budget rejecting before any source read; a
  fresh capture exception rejecting the whole request without partial seeds; a
  missing nested key rejecting without synthesizing a zero or an annotation; a
  prepaid validated context still bounding an optional collector and rejecting
  when exhausted; recipient policy and marker selections staying independent
  across live samples; an external name-only path using the authoritative name
  without reading unavailable block state; external name, type, and state
  selections still requiring available observation; and all-type masks
  revoking on permission and provider changes without client selections. The
  capture context and providers are fakes; the real server world, live
  provider, and end-to-end transport remain unexercised;
- `PresentationServerRefreshTest` covers the production server lease, refresh
  and capture-budget seams headlessly: a same-id external refresh delivers a
  CREATED snapshot with the updated locator/anchor to a known online recipient,
  preserving marker ID, arrival, expiry and the previously delivered owner name
  without a source observation or an owner profile lookup — a defensive seam
  verified independently of owner presence, since an owner disconnect removes
  the owner's records in ordinary play — never backfilling an unbaselined
  recipient and skipping expired leases or offline recipients; refresh
  re-projects the cached value against each recipient's current allowed fields
  and revokes cached fields and annotations per recipient without mutating the
  server cache; a marker beyond the bounded sampling cache refreshes its known
  recipient from the already-delivered Basic section with no new lease
  allocation and no source read; a negotiated baseline across many entirely due
  cached leases replays CREATED snapshots without re-entering source capture or
  advancing `nextSample`; positive per-capture allowances cannot reset the
  shared per-tick capture quota across leases; refreshed metadata does not
  advance the effective sampling interval; a zero `scanBudget` defers Basic and
  optional observations without mutating cached values or their stale flag, and
  empty demand still clears a previously sampled optional section; and
  exhausted shared work defers an optional observation without a fake failure
  while unused per-call allowance does not drain shared tick work. Its sessions,
  demand and Basic observation ports are in-memory or recording; the live
  server world, registry lookups, real providers, packet transport and
  rendering remain unexercised;
- `ClientPresentationTest` covers the v3 client session: `HELLO`/`OFFER`/`RESET`
  epoch and view guards without any client preference, a tighter reset mask
  pruning frozen fields and annotations without resurrection, accepted metadata
  being preserved but never treated as an authorization mask, `CREATED`
  initializing the stored target type and default while sections carry nested
  annotations, and masks authorizing each marker by its stored type rather than
  its adapter or name;
- `PresentationPropertyFormatterTest` covers the client display projection:
  distinct nested annotations following the default without inheriting an outer
  Ping Type, annotating the default without duplicating it and absent nested
  entries being skipped, bounded scalar fallbacks never dumping record objects,
  health requiring an authorized maximum while not hiding an independent name,
  and an unannotated non-default value being retained but not treated as an
  explicit Ping; and
- `PresentationFieldCatalogTest` covers namespace grouping by field ID rather
  than the owning adapter, exact field IDs, first-occurrence de-duplication,
  immutability, retained server-advertised default and label over local
  metadata, adapter lookup from a bridging adapter, and the bundled
  namespace-heading key pattern; it does not construct the settings screen or
  render rows; and
- `PresentationConfigTest` covers missing-key defaults without migration,
  partial nested policy objects not silently elevating allow lists, independent
  per-target-type policies surviving JSON round trips, bounded unknown
  selectors never granting access, whitelist-only and arbitrary-position
  wildcards independent of permissions, effective sampling limits and
  fingerprints, and the replaceable permission provider's vanilla default and
  fail-closed behavior;
- `PresentationSettingsPolicyTest` covers per-target-type policy independence
  with missing types failing closed, mutation isolation between target types,
  invalid selectors denying only their own target type, deep-copy/fingerprint
  independence, a global invalid policy state surviving as a durable deny-all,
  and legitimate overrides and scoped problems not denying every target type;
- `ConfigPresentationMigrationTest` covers the persisted shape normalization:
  legacy flat policy copied to every target type without aliasing rule
  instances, the new map winning over flat keys with missing types failing
  closed, partially configured maps materializing fail-closed entries, a fully
  configured current shape not being rewritten on load, mistyped nested rules
  denying only their own type, malformed flat shapes and malformed maps failing
  closed for every target type, malformed permission collections becoming
  durable global denial, malformed interval shapes not resetting the config,
  older-version migration composing with the shape copy, obsolete client keys
  removed while unknown data is preserved, future-version files never
  rewritten, and a malformed presentation object failing closed without
  resetting unrelated config;
- `ServerPresentationPolicyServiceTest` covers the v2 rule-view service:
  read-all disclosing every target type in catalog order, missing settings or an
  unknown type failing closed, a permission-denied mutation changing no type,
  add and remove mutating only the selected target type,
  duplicate/missing/invalid/unknown-type requests never changing state,
  over-capacity and no-op requests leaving the rule view unchanged,
  whitelist-only assignment affecting the selected type only, and detached-copy
  isolation with missing settings failing closed;
- `ServerPresentationPolicyPacketsTest` covers the v2 route and codec: the
  version-two route, a C2S read without a target type round-tripping,
  mutations round-tripping with a selected target type, unknown-type or
  selector mutations being corrupt, S2C carrying every target type, the
  default fallback carrying all five empty-allow views but being corrupt, a
  partial or unknown rule map rejected, a missing target type decoding to a
  fail-closed fallback, and trailing bytes yielding the corrupt fallback
  instead of an exception;
- `ServerPresentationPolicyStateTest` covers the per-target-type full rule map
  being read-only by type while mutations carry the selected type, timeout and
  broadcast ordering never creating or regressing rule views, and an
  incomplete view never publishing an allow policy; and
- `PresentationSelectorDraftModelTest` covers per-target-type draft, feedback,
  and caret independence, one global pending submission clearing only the
  unchanged submitted draft, and a failed or timed-out submission retaining its
  draft while allowing another submission; this is a pure editing-state seam
  and does not exercise actual widget or disabled-control focus; and
- `PresentationSelectorListModelTest` covers selector normalization, grammar
  validation, empty-versus-invalid outcomes, the shared capacity and length
  caps, case-sensitive duplicate rejection, not-found and invalid removal, and
  immutable non-mutating results.

`ClientPingActionDispatcherTest` adds a programmatic property create that uses
the frozen target and the actual typed wire without any GUI trigger, and
`ModIdentityTest` pins the fork identity, the packet-ID namespaces, and the
versioned v3/v2 presentation route IDs.

NeoForge Create adapter seams cover the optional summary route:
`CreatePresentationAdapterTest` (server/client manifest parity, demand gating,
per-field projection, unavailable-versus-partial summary distinction, and
cadence override), `CreatePresentationRegistrationTest` (idempotent
once-per-registry registration and absent-adapter rejection),
`CreatePresentationCollectorBudgetTest` (shape/work/version gates),
`BoundedCreateSummaryTest` (registry-ID aggregation and scan/work/output
limits), and `CreatePresentationMixinPluginTest` (version gate and constructed
ASM node field-shape gate for the cached network accessor).
`CreatePresentationExternalBlockTest` also covers the production `observeSource`
routing seam: committed external metadata gates, provider-local position
selection, demand and budget forwarding, ordinary-block routing without external
observation, and unavailable or mismatched inputs stopping before collection.
Its recording `SourceAccess` does not exercise a real Create collector or
handler, the Create mixin/accessor, or a live Sable API.

These are model, property, codec, admission, and lease-refresh/capture-budget
seams. The refresh tests invoke production delivery, refresh and sampling
methods through in-memory sessions and recording observation ports. They do not
establish live client/server negotiation, session reset or mask pruning over a
real connection, transport registration in a running game, a real world or
provider observation, live permission projection, a live policy rule-view
read/mutation/broadcast, property upload admission against a real world,
rendered property/chat/HUD lines, the live settings-screen draft,
field-catalogue rendering, feedback, caret, or list-capacity behavior, a
persistence fault during a policy mutation, or in-game Create/Sable sampling.

### Capture, wheel and cancellation

`PingInteractionStateMachineTest` covers short press, long press,
pending/asynchronous capture, wheel state transitions, lifecycle abort, the
actual wheel-open boundary and timeout behavior. `PingCaptureCoordinatorTest`
covers capture tokens, rejection of stale superseded completions, completion
races, first-completion ownership and identity-preserving metadata retention.
`TargetSnapshotTest` covers snapshot identity, copied capture metadata and frozen
context construction; `TargetSnapshotBlockClassificationTest` covers explicit
and absent block-entity classification metadata; and
`MinecraftTargetSnapshotFactoryDetailedTest` covers retaining local detail only
for the matching entity-hit owner.

`LongPressCompatibilityControllerTest` covers the ordinary rapid-click and
asynchronous deferred-compatibility paths, with focused bounds coverage supplied
by the applicable config-bounds tests. These are controller/config slices, not
real input callbacks or render-frame integration. They do not exercise the real
focus-loss `KeyMapping.releaseAll` hook, screen-transition callbacks,
level-instance/dimension discontinuity detection or loader/gameplay input
lifecycle. The abort-before-ownership-clear order is source-confirmed; the
state-machine abort and coordinator stale-token cases do not directly test an
abort followed by a late asynchronous completion.

`CancelCandidatePickerTest` covers press-ray cone filtering and nearest-candidate
selection, while `ClientMarkerStoreTest` covers owner/dimension retrieval.
Those seams do not runtime-cover a retained stale or display-hidden marker
reaching cancellation and then being rejected by the server without a local
fallback. Frozen press-ray behavior and the pending-capture/wheel interaction
boundary otherwise have focused test coverage.

### Selection-policy and input-state seams

The [selection policy](../architecture/picking/selection_policy.md) has focused unit and
state-seam evidence, rather than live input-callback evidence:

- `ToggleInputStateTest` covers physical-press de-duplication until release,
  including suppression of repeated presses before the next release;
- GUI/screen eligibility is exercised at the input-state seam, not through a
  live GUI callback;
- `ClientConfigTargetSelectionTest` checks the disabled defaults for the three
  target-selection toggles and their JSON round trip; and
- `EntitySelectionBlacklistTest` covers registration aggregation, idempotent
  removal, and the optional Create registration boundary, while
  `EntitySelectionBlacklistDefaultRuleTest` checks the default
  `simulated:honey_glue` entity rule and representative nonmatches.

The entity-selection blacklist evidence is distinct from block whitelist,
block-shape, and display-blacklist behavior. It must also not be treated as
raycast-filter coverage: spectator exclusion is implemented by `Raycast`, not
by `EntitySelectionBlacklist`. The unit seams do not establish live GUI/screen
callbacks or loader-specific physical key-repeat behavior.

### Exact entity-local picking and Create raycast seams

Focused coverage exercises:

- an immutable entity-local-geometry owner snapshot;
- owner resolution and `HIT`/`MISS`/`UNAVAILABLE`/`FAILED` candidate rules;
- retention of entity-local metadata only for the matching resolved identity;
- native local block/fluid shape policy and the exact block/fluid kind tie;
- the common native local-shape scanner and candidate pipeline;
- the Create-free contraption engine; and
- lazy Create adapter/loading seams and delegate-unavailable behavior.

These tests preserve whole-entity identity and exercise the non-optional engine
boundaries. They do not constitute an in-game Create validation.

### Capture and acceptance range

`ServerConfigBoundsTest` covers server range clamps,
`RaycastCandidateFlowTest` covers native candidate/raycast seams, and
`OptionalDependencySafetyTest` covers absent optional-integration safety.
Source and focused seam evidence establish
the native `min(raycastDistance, pingDistance)` limit, Distant Horizons'
independent trace, the server acceptance range, and Create/Sable reuse of the
finite native segment. No automated end-to-end test exercises that entire client capture,
optional-provider, packet, and authoritative server-acceptance pipeline.

### Presentation, chat, config and geometry outcomes

The current suite covers:

- chat template selection and Ping-Type-phrase-only coloring;
- wheel sector colors;
- block whitelist grammar and evaluation;
- entity-block source modes and per-attempt `RENDERED`/`EMPTY`/`FAILED`
  outcomes, including fallback consequences;
- invalid client-config recovery;
- `ConfigHandlerResetTest` coverage of resetting in-memory values to defaults
  and preserving the bytes of a protected future-version file;
- config-version marker, precedence, migration, client/server recovery, and
  future-version preservation seams; and
- behavioral native-edge rendering.

`PingChatBuilderTest` covers required placeholders, escaping, missing-token and
legacy fallback seams. This does not close localized-name, custom-name, locale,
or resource-fallback scenarios.

`TargetNameComposerTest` covers custom color/italic stripping and base-component
identity. Its event-named case does not construct click or hover event fixtures,
so it is not event-specific regression coverage.

`ClientPingRuntimeTest` covers the current-local-store membership predicate
before and after a same-ID external-locator upsert. Through a recording receipt
port it also drives the packet-dispatched CREATED path into the presentation
store and client marker store: a same-ID newer initial upserts the changed
locator/anchor while preserving ID, arrival, expiry, local visual deadline,
target/Ping Type and any winner slot; older and equal-revision initials cannot
roll back the authoritative payload; feedback fires once for a newly seen
marker rather than on refresh; an elapsed visual on a still-stored record is not
re-shown and a preserved winner slot does not expose it; an authoritatively
expired or hard-removed marker is not resurrected by a later initial, while a
create after local housekeeping deleted the record remains the separate same-ID
gap below; and a refreshed active winner
keeps its slot and visual deadline. These are headless store/port assertions:
they do not exercise a live world, network transport, Sable locator resolution,
actual sound playback or GUI chat delivery, packet ordering over a connection,
or client rendering. The public entry wiring of this path is source-inspected,
not runtime-verified.

### Inventory and spatial-selector configuration

`InventorySettingsTest` covers the authoritative inventory budget model:
explicit unlimited caps keep their finite value and use the caller-owned finite
guard, finite caps clamp to positive instead of a disable sentinel, confirmed
finite boundaries clamp, zero heartbeat disables only the periodic cadence, the
two multiplier grids normalize piecewise values, the five byte multipliers
stay independent and control only their own scope, the snapshot fragment and
per-client period scopes stay equal, unlimited byte multipliers stay finite,
the step helpers cross boundaries and saturate, pending-memory steps follow the
piecewise grid, and independent defaults do not alias.
`SpatialSelectorSettingsTest` covers glide and hover clamping and the opt-in
default of Back-hover. `InventorySettingsPersistenceTest` covers additive
defaults without rewriting a current-version file, older-version migration
writing the nested inventory object while preserving user data, explicit
unlimited round trips, out-of-range values clamping without resetting
unrelated fields or producing a broken backup, the future-version guard
leaving defaults in memory and refusing to save, and the client
spatial-selector keys defaulting and clamping. These are model and persistence
seams only: no server-administration path, remote change route, settings-UI
exposure, scheduler consumption or native selector integration exercises them.

### Shared source identity and capture results

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
bounded and detached. These are model seams only: no real source step,
provider read, shared-consumer accounting, transport or running server path
exercises them.

### Back-hover return state

`BackHoverControllerTest` covers the headless Back-hover timing model: progress
before the threshold never triggers, the threshold triggers exactly once and a
held focus never repeats, leaving and re-entering restarts the clock, a parent
Back stays blocked while held and re-arms only after focus loss, switching the
focused menu restarts the clock, a rewound clock clamps instead of resetting
the baseline, `end` clears the timer and block idempotently and a fresh session
is unblocked, a disabled session never arms or triggers, updates before start
are inert, and the hover parameters are frozen at session start. It is a
standalone model seam: no native screen, input route, renderer, or in-game
timing is exercised.

### Shared source access contract

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
independently. This is a contract seam only: no real Minecraft provider, world
read, integrated server runtime, transport, or sync publication exercises it.

### Server-settings snapshots and updates

`ServerSettingsModelTest` covers request correlation, stale responses, denial,
draft state, and update planning. It also covers a safe non-editable snapshot
being accepted and rendered for a viewer below the required level without
enabling edits or an update plan, unsafe or uncorrelated snapshots never
becoming viewable, retaining a read-only view through permission revocation,
restoring an editable session for a retained editable snapshot, dropping a
retained read-only view and requiring a fresh request after promotion to editor,
an authoritative denial keeping the snapshot viewable but not editable, the
per-field invalid numeric draft mask and its repair, clearing that mask on
permission revocation, disconnect, and an explicit clean mark that preserves
draft text, the shared once-per-session request that does not replace a loaded
or in-flight snapshot, denial lock with the false-to-true permission retry
transition, and connection-scoped disconnect reset. `ServerConfigUpdateTest`,
`ServerConfigUpdateServiceTest`, and the focused server-config packet tests cover
field-masked partial merge and codec/handler seams. These model and packet seams
do not establish the live settings UI, permission changes over a connection,
actual packet exchange, or persistence behavior in a running client/server
session.

### Settings-screen navigation, catalog, layout and localization

The [configuration UI](../UI/settings-screen.md) scope, category, and page
behavior has model, catalog, geometry, and resource seams rather than live
screen evidence:

- `SettingsNavigationModelTest` covers the initial client overview, per-scope
  overview selection, each category opening its own leaf page within its scope,
  the immutable six-category client and four-category server order, the
  server Presentation category opening without ordinary-server permission, the
  separate Server Presentation leaf, independent ordinary-server and
  presentation-policy view access, and independent per-page viewport and focus
  retention; it also covers rejection of a
  foreign-scope category without navigating, Back to the owning overview with a
  root that reports itself as not closable, per-page scroll and focus retention
  across back, scope switching and forced routing, clamping of negative scroll,
  routing an invalid server draft mask to the category that owns its first
  invalid field, retaining a shared `ServerSettingsModel` draft across
  navigation, and keeping a server leaf open when a permission revocation
  retains a viewable snapshot while forcing the scope overview without one;
- `SettingsCategoryCatalogTest` covers exactly-once placement of every existing
  control in the approved category order and immutable per-category lists,
  including the client scope without a presentation category and the server
  Presentation category; it does not cover full-width layout flags or actual
  widget placement;
- `PresentationFieldOutcomeTest` covers the server policy role: the advertised
  manifest default, exact and wildcard allow rules winning over block rules,
  exact membership toggles mapping to single list operations, whitelist-only
  blocking unmatched fields while honoring allow rules, block rules beating the
  default while keeping unknown selectors visible, an unknown policy never
  fabricating membership or an empty authoritative view, and the server rule
  controls following the pending and permission gate; the former client-role
  coverage was removed with the client receive/display policy; it is a pure
  decision model and does not construct the settings screen or exercise widget
  state;
- `SettingsScreenLayoutTest` covers computed root and leaf header, footer, list
  viewport, and button geometry at representative compact, standard, and large
  GUI sizes and clamped tiny-size viewports without negative space; it does not
  establish row reachability or actual widget placement;
- `DeferredActionCoordinatorTest` covers the structural seam that runs a queued
  action after the native dispatch returns, waits for the outermost nested
  dispatch while keeping only the latest transition, and drops the transition
  when the dispatch or a nested dispatch fails; and
- `SettingsScreenFocusKeyTest` covers the shared focus-key lookup helper before
  an options list exists, resolution of a focused list's focused child,
  retention of a directly focused widget's key without a list-child lookup,
  and the independent `isFocused` fallback when nothing is focused; it pins
  the regression that a null focused widget with a null list never calls the
  list-child supplier. This is headless coverage of the production focus-key
  helper, not a constructed `SettingsScreen`, live focus-list traversal, or
  in-game focus validation; and
- `ClientConfigLocalizationTest` covers the eight bundled locale files and the
  settings, category-navigation, server-status, entity-block-mode, and
  target-gone resource keys, their required format placeholders, the category
  entrance ellipsis, the exact local feedback prefix, and the restart-required
  external-list tooltips; it does not render or assemble screen labels; and
- `PresentationSettingsLocalizationTest` covers the eight bundled locale files,
  every presentation key present, non-blank, and with an identical key set
  across them, the approved English presentation labels, key placeholders,
  localized names and descriptions for every builtin and Create field in every
  locale, bundled translations for the recognized namespace headings, the
  read-only server status wording, and a property request phrase separate from
  the whole-marker Ping Type phrase in every bundled locale; it does not render
  or assemble screen labels.

The production screen routes native widget input through the deferred-action
coordinator, but these tests exercise the model, catalog, geometry, and resource
seams rather than native widget containers, mouse dispatch, or live focus lists.

### Render entity lookup and locator resolver seams

`RenderEntityLookupCacheTest` and focused locator-resolver unit seams cover the
render-only lookup algorithm and locator resolver boundary. The cache tests
exercise idle passes without scans; distinct,
repeated-hit, and miss UUID requests sharing one cold index; first-valid
duplicate selection; simulated HUD/outline warm reuse across passes; a different
world object with the same dimension; removed or unloaded entries; runtime-ID
reuse and UUID mutation; same-UUID replacement and a spawn after index creation
becoming visible in a later pass; an invalid warm entry causing one scan and
index build; pruning without touching an old entry during validation; clear/null-frame
handling; and null-world and null-UUID safety.

`EntityOutlineLocatorResolverTest` covers UUID and XP locators, runtime
non-orb rejection, gone/null/mismatched entities, map lookup, and the raw-cache
entity-mismatch seam. Neither this cache test nor these resolver tests are a
dragon-renderer test or establish canonicalization integration beyond their
stated seams. The cache fakes and access counters establish lookup-algorithm
behavior only: they are not actual Minecraft frame hooks, HUD callers, or
renderers, and they do not automatically cover the fresh non-render lookup
bypass. Source inspection remains useful for those call-site boundaries but is
not automated runtime coverage.

### Sable integration coverage

The linked [Sable integration](../integrations/sable.md) topic remains the
behavior owner for [client capture and presentation](../integrations/sable.md#client-capture-and-presentation),
[server validation and materialization](../integrations/sable.md#server-validation-and-materialization),
[server information sampling](../integrations/sable.md#server-information-sampling),
and [refresh lifecycle](../integrations/sable.md#refresh-lifecycle). The
following focused seams provide limited structural, locator-codec,
provider-observation and diagnostic evidence:

- `SableClientCompanionAccessContractTest` statically parses the compiled
  access-class constant pool, requires the exact
  `SableCompanion.getContaining(Level, Position)` symbol, and excludes the
  exact names `getClientLevel` and `getContainingClient`;
- `SableExternalBlockLocatorTest` covers representative encode/parse
  round-trips and selected malformed, noncanonical, and out-of-bounds cases;
- `ExternalBlockServerProviderRegistryTest` covers the production observation
  dispatch and validation seams with a read-only fake world access: loaded
  non-air state, expected registry and provided-state matching, same-level,
  server-thread, dimension, committed-target, missing-provider, and provider
  failure gates. Its fake tokens do not establish a real `ServerLevel`, loaded
  world, or live provider observation;
- `SableExternalBlockObservationTest` covers the production-called stable-entry
  observation helper selecting the committed tracking ID's current sublevel and
  local position, while leaving entry and reference maps unchanged, and failing
  closed for non-live references, wrong entries, invalid tracking-point state,
  and invalid coordinates. It does not load the Sable API or exercise live
  reflection, sublevels, materialization, persistence, or release;
- `SableRefreshLogGateTest` checks decision state for tested locator or reason
  changes and duplicates, rather than a refresh operation or log sink;
- `SableDiagnosticsTest` and `SableServerDiagnosticsTest` check selected event
  metadata and record fields, including same-throwable identity for a server
  exception and constructed-invalid or `LinkageError` cases;
- `SableClientDiagnosticsTest` checks an empty capture result when Sable is
  absent and diagnostic presence from explicit `logCaptureFallback` calls with
  a reason; and
- `SablePresentationLogGateTest` checks cadence, capacity, repeated
  failure-class key de-duplication, and throwable identity.

These seams do not load a Sable runtime or establish live provider reflection,
materialization, tracking-point reference lifecycle, live-sublevel observation
or refresh, multiplayer, or in-game behavior described by those topic sections.
The registry and stable-entry tests are narrow production-used observation
seams with fake access and in-memory state, not live server/world evidence.
The external model and fallback routes also resolve provider presentation
independently; therefore this evidence cannot guarantee that provider-local
multipart or subject-type decisions came from one immutable shared snapshot.
That is an implementation-conformance and automated-coverage gap, not an
external exception to the shared-subject contract in
[presentation subjects](../architecture/rendering/presentation_subjects.md).

### Shared source cost ledger

`CostLedgerTest` covers the pure-JVM bounded cost-ledger seam of the shared
source mechanism headlessly: admission reserves before measured use; an
all-or-nothing multi-counter reservation deducts nothing when any counter is
insufficient; the exact limit is admitted and one more defers; a measured
commit charges only the measured subset and refunds omitted counters across
distinct units; closing an uncommitted reservation refunds everything and
repeated close is idempotent; over-commit, unknown counters, negative amounts,
blank scopes and negative limits are rejected before any counter moves;
`Long.MAX_VALUE` limits do not overflow remaining accounting; and caller maps
are copied so later caller mutation cannot move the ledger. This is a model
seam only: no integrated scheduler, provider, real read admission, retained
memory or wire lifetime, or running server path exercises the ledger.

### Rate-policy courtesy behavior

The current suite covers the create-only client token-bucket courtesy gate,
dropping throttled committed creates without queueing or dispatch tracking,
`MarkerRemove` and channel-update bypass, and corrupt-policy handling. This
client coverage does not close the server and end-to-end policy gaps below.

### Simulated integration coverage

Executable stand-in resolver tests cover paired-owner resolution, the facing
relationship, powered owner, owner block-entity identity, and handled-empty
results. Separate static guards cover the production registry identities and
optional registration wiring. The stand-ins do not exercise real registry
lookup, and the static guards do not establish a running loader lifecycle, an
installed Simulated runtime, or in-game presentation.

### Optional rendering structural coverage

Executable common tests cover class loading with optional dependencies absent
and the distinction between empty and positive geometry output. Separate
coordinate-transform and environment-policy tests cover their own common
behavioral seams.

Static guards cover optional reflective registration, loader-specific host API
bindings, integration wiring, and the optional visualization mixin's target and
injection signature. They do not establish large-water-wheel master-resolution
outcomes, once-only dispatcher execution, nested mask-scope cleanup, or live
world-aware model-data flow, culling, pose restoration, and geometry emission.
Those execution boundaries remain automated verification gaps; source presence
and successful compilation are not substitutes for exercising them.

### Focused native block-outline regression coverage

The native VoxelShape route requires complementary checks rather than one broad
"outline works" assertion:

1. Production render-state coverage pins `BlockOutlineRenderType` to
   `VertexFormat.Mode.LINES`, vanilla `rendertype_lines`, a fixed width wider
   than vanilla selection lines,
   `NO_DEPTH_TEST`/`GL_ALWAYS`, color-only writes and late composite
   submission.
2. Native geometry coverage pins the live
   `BlockState#getShape`-to-`VoxelShape#forAllEdges` edge route so a full-cube,
   polygon or shape-equivalent substitute cannot satisfy the regression.
3. Render-frame snapshot coverage separately exercises late submission and
   frame ownership.

Native-glow whitelist/eligibility coverage is separate from these VoxelShape
checks. Passing either side alone does not prove the other, and structural or
behavioral tests do not by themselves prove actual visibility through occluders
or from arbitrary in-game camera angles.

## Build, source-set and artifact verification

The included Gradle projects are `common`, `fabric`, `forge`, and `neoforge`.
Their loader source sets receive common Java and resources through the shared
loader wiring. Fabric retains the common mixin configuration and Loom-generated
intermediary refmap; Forge and NeoForge use their loader-local official-Mojmap
configuration instead. This routing identifies existing tasks and artifact
purposes only. Public build orientation and commands are listed in the
[repository README](../../README.md#install-build-and-verify); the tracked
[geometry pipeline](../architecture/geometry/geometry-pipeline.md) is the public
architecture entry point. Local agent instructions, when present, are
supplementary execution guidance rather than a public documentation prerequisite.

| Module or artifact scope | Task | Purpose |
| --- | --- | --- |
| `common` test source set | `:common:test` | Runs the common JUnit Platform tests, including shared behavior and integration seams. |
| `neoforge` test source set | `:neoforge:test` | Runs the NeoForge JUnit Platform tests, including NeoForge-specific resolver coverage. |
| Affected loader source set | `:fabric:build`, `:forge:build`, or `:neoforge:build` | Builds the affected Fabric, Forge, or NeoForge source set and its loader jar. |
| All shippable loader artifacts | `verifyModIdentity` | Depends on all three loader `build` tasks, then inspects the expected Fabric, Forge, and NeoForge jars in their loader `build/libs` directories for fork identity. |

## Known automated gaps

The following gaps remain open until direct evidence closes them:

- the shared source mechanism is partly implemented: the cost-ledger seam, the
  detached source-key and capture-result models, and the source-access boundary
  contract are covered (see the coverage notes below), while real provider
  implementations, an integrated server runtime, sync publication and the
  inventory preview/tracking domain remain adopted contracts with no runtime
  implementation or automated coverage; every statement in
  [shared source capture and sync](../architecture/presentation/shared_sources.md)
  and [inventory preview and tracking](../architecture/presentation/inventory.md)
  that is not backed by those notes is a confirmed contract pending
  implementation rather than existing behavior;
- inventory boundaries without coverage: preview ordering and freeze rules,
  selected-item zero versus unknown, per-item revisioning, per-Ping+recipient
  baseline and resynchronization isolation, unknown-baseline expiry versus
  admitted fragment-baseline progress, component-too-long all-variant folding,
  per-client period snapshot byte accounting across targets, heartbeat zero
  semantics, variant identity and quotas, and the hard stop at Ping expiry;
- inventory budget and spatial-selector configuration have only model and
  persistence seams: the server administration path, remote change route,
  settings-UI exposure, scheduler consumption, and native selector integration
  remain unexercised;
- the shared source result and publication runtime paths without coverage:
  real Minecraft provider implementation, world reads through an integrated
  server runtime, one-physical-read and one-logical-progress charging across
  consumers, receiver-isolated publication, and the ledger's integrated
  admission path; the model-level identity, result and access-contract
  validation is covered by the identity/capture and access notes;
- the shared client/server `entity_block` classification path end to end;
- stale or display-hidden cancellation followed by authoritative rejection with
  no local fallback;
- real input-callback and render-frame behavior for rapid/deferred long-press
  compatibility;
- asynchronous completion after lifecycle abort, including the token
  invalidation/ownership-clear boundary;
- compatibility create-only dispatch conformance: the controller currently
  receives an action result rather than an explicit successful-dispatch outcome.
  A courtesy-rejected `CreatePing` can therefore still look qualifying even
  though the limiter/dispatcher did not track or hand it to the sender; the
  deferred path can also launch after a non-`CreatePing` result such as
  `TargetGone`. This action-versus-dispatch defect is not intended behavior;
  the pending regression matrix below specifies the required checks;
- live GUI/screen input callbacks and physical key-repeat behavior across
  Fabric, Forge, and NeoForge, beyond the input-state seams; and
- the complete range pipeline across native, Distant Horizons, Create/Sable,
  packet transport, and authoritative acceptance;
- deterministic local-position ordering of equal-distance, same-kind local
  child hits: the x/y/z comparator is source-confirmed, but no test exercises
  position ordering; the focused exact-tie case pins only block versus fluid;
- live server-settings UI, permission, read-only viewing below the required
  level, request/response, update, and persistence behavior;
- live presentation policy rule-view read, selector add/remove, whitelist-only
  change, refresh, feedback, and unsolicited broadcast behavior;
- direct runtime proof that valid legacy S2C locations and superseded marker S2C
  packets are presentation no-ops, plus live loader registration/network
  transport for the presentation snapshot and policy routes;
- a live two-sided presentation session: negotiation and reset over a real
  connection, per-recipient permission projection, policy changes advancing the
  view, and delivery of Basic/adapter values across the loader transport;
- external-refresh and client-receipt behavior under live conditions: those
  seams are headless, so no automated test exercises a real Minecraft or Sable
  world (logical anchor or render pose), a loaded sublevel, world-backed
  projection, source sampling cost, packet-level ordering or an authorization
  change between snapshot and delivery, or the rendered client marker;
- a live property-upload admission round trip: the admission, recapture,
  override, and creation-rollback seams use fakes, in-memory state, or
  recording references, so no real server world, live provider, external
  materialization, or packet transport is exercised; property Ping Type
  override matching is model-tested against supplied tag IDs rather than a live
  registry or tag manager; rendered property HUD lines and frozen-mask retained
  values are client/provider seams rather than a frame observation; and no
  in-game property-entry GUI exists by design, so the programmatic/harness
  create path is the only creation route;
- installed-Create presentation sampling and display remain unexercised in-game:
  automated seams cover vault/tank structure capture, bounded summaries,
  nested count property selections, the cached network stress/capacity
  accessor, and the external-block `observeSource` position/demand/budget route
  with recording access, but not a real Create collector or handler, Create
  accessor/mixin, Sable API, or resulting HUD label lines;
- the complete `ServerCore` operation ordering and channel/admission matrix in
  an end-to-end server path;
- same-ID marker creation after local record deletion, where current behavior
  treats the late create as a new insertion with a new visual deadline;
- the live local invalid-target chat path: focused tests cover the composed
  translatable prefix and message component, its color, and the bundled resource
  text, but no test exercises the Minecraft chat overlay or the client trigger
  paths that show the line;
- repeated-ping HUD compositing for one target: existing tests cover winner
  selection and independent visual lifetimes, but no test checks whether several
  display-active same-target records change the displayed HUD alpha; the
  invariant is owned by
  [client marker state](../architecture/markers/client-state.md#winner-slots-are-not-the-render-marker-collection);
- the assembled settings-screen label for the local marker display duration
  option: `ClientConfigLocalizationTest` checks resource keys, values, and the
  `%s` placeholder, and `SettingsScreenLayoutTest` checks layout, but neither
  checks the complete localized `<setting name>: <value>` label for the Follow
  server sentinel or an explicit duration; the label form is owned by
  [configuration UI](../UI/settings-screen.md#marker-display-duration-option);
- application of synchronized rate policy on reconnect and on effective live
  configuration change;
- server-side sanitization of negative rate-policy values;
- the local `ModelBlockRendererMixin` position-seed guard:
  `@ModifyConstant(method = "*", require = 2)` counts the minimum number of
  matching class-wide `42L` constants, including an unused local, so it does not
  prove that both `RandomSource#setSeed` call sites remain covered; this is a
  coverage/guard limitation, not a product behavior change; and
- detailed diagnostic behavior in the private
  `CreateEntityOutlineAdapter.EntityDiagnostics` path.

Render-entity lookup gaps remain for same-dimension world unload/rejoin and
runtime-ID reuse in a game session, shared epochs between real HUD and outline
callers, and integration proof that non-render callers bypass render-path cache
results. No automated evidence currently establishes CPU frame cost or
allocation behavior for one, ten, or fifty entity marks in a dense world at high
frame rates.

### Pending long-press compatibility regression matrix

| Scenario | Required regression assertion |
| --- | --- |
| Courtesy limiter rejects the first default `CreatePing`, then a rapid second press occurs | No rapid candidate or virtual capture begins; the dropped request is not queued, retried, or replayed. |
| Courtesy limiter rejects the first default `CreatePing`, then a deferred fresh press is present | No deferred capture begins; the dropped request is not queued, retried, or replayed. |
| A first result is `TargetGone` or another non-`CreatePing` while a deferred fresh press is present | The deferred press is discarded and no ordinary capture starts. |

### Sable-specific gaps

Sable-specific gaps remain for:

- installed-Sable API compatibility and client capture/presentation against a
  live sublevel, including the established
  [logical-anchor/render-pose boundary](../integrations/sable.md#client-capture-and-presentation);
- live server information sampling through the production registry and Sable
  provider against a real `ServerLevel` and loaded sublevel, including current
  tracking-point relocation, sublevel rotation/translation, state or registry
  identity change, unload/reload, no-force-load behavior, and marker/session
  lifetime; the new observation tests use fake world-access tokens or in-memory
  entry/reference maps;
- provider materialization, tracking-point reference counting, rollback and
  release, including the existing empty-audience cleanup path documented under
  [server validation and materialization](../integrations/sable.md#server-validation-and-materialization);
- live-sublevel refresh through the documented available, temporarily
  unavailable, and invalid outcomes in the
  [refresh lifecycle](../integrations/sable.md#refresh-lifecycle); and
- end-to-end server-authoritative names, fail-soft behavior, and multiplayer
  marker synchronization at the boundaries owned by
  [Sable](../integrations/sable.md#names-permissions-and-diagnostics) and
  [target validation](../architecture/authority/target_validation.md).

Coverage of Flywheel diagnostics or another adapter's diagnostic helper does
not close the private `EntityDiagnostics` gap.

## Pending manual and integration matrix

No scenario in this matrix is recorded as performed merely because it is listed
or because related automated tests exist.

| Area | Pending scenarios |
| --- | --- |
| Block | Plain `block` versus `entity_block`; `ALL`/`COMPATIBLE`/`VOXEL_SHAPE_ONLY` modes and source fallback; whitelist native glow and fallback; a non-full native shape; same-type state change versus block-type replacement. |
| Entity | Ordinary entity and dropped item; movement and same-dimension teleportation; death and disappearance; same-dimension world unload/rejoin and runtime-ID reuse in a game session. |
| Wheel | Short and long press; every sector and border color; configured timeout; frozen target; location fallback; Back-hover dwell, one-level return, and leave/re-entry re-arm under real input. |
| Selection policy and input | Live GUI/screen callbacks for selection gating; focus-loss `KeyMapping.releaseAll`, screen-transition and level-instance/dimension discontinuity aborts with late asynchronous completion; loader/gameplay input lifecycle and physical key-repeat behavior on Fabric, Forge, and NeoForge; selection toggles, entity blacklist/default `simulated:honey_glue` rule, and spectator exclusion in a game session. |
| Movement, death and replacement | Target movement while the wheel is open; entity death or dimension change; block state change or replacement while open. |
| Naming and chat | Custom-name formatting; localized base names; item naming; phrase-only text color. |
| Invalid-target feedback | Localized invalid-target message with the `[ping for it]` leading marker on both feedback paths, local pre-commit target loss and the correlated server `TARGET_GONE` rejection; the displayed text and marker come from language resources ([presentation owner](../UI/ping-feedback.md#presentation)). |
| Cancellation | Cone and nearest-own-marker selection; inability to cancel another player's marker; stale/display-hidden candidate followed by server rejection with no local fallback. |
| Multiplayer and protocol | Same-target latest-server-arrival winner; equal-arrival larger-Marker-ID tie; winner fallback after removal or expiry; complete `ServerCore` ordering/channel matrix; all-loader authoritative transport and ignored valid legacy S2C location; an owner-online refresh that changes the locator or the anchor; a marker beyond the bounded sampling cache synchronizing its known recipients without a new lease; an older or equal-revision initial arriving after a newer one without rolling back the stored payload; a legitimate policy change between cached projection and delivery; and a legitimate later packet arriving after the refresh. |
| Marker HUD | Repeated same-target pings from one sender and from several senders while same-target records remain display-active: the target's displayed HUD alpha does not accumulate with the number of same-target records ([invariant owner](../architecture/markers/client-state.md#winner-slots-are-not-the-render-marker-collection)). |
| Inventory and shared sources (planned) | Preview and tracking on Fabric, Forge, and NeoForge; vanilla single and double chests, hopper, furnace, shulker box, and an unopened loot chest without loot-table generation; installed Create Vault; private or unavailable inventories; permission and tag governance; unload/reload; block-type replacement versus same-type restore before expiry; different-block invalidation and grey status; hard stop at Ping expiry without recovery afterward; session end; queue overload and coalescing including explicit zero values; component-too-long all-variant folding; heartbeat zero mode with repair and status still active; and an explicit unlimited physical mode keeping finite work and memory guards. |
| Presentation snapshot | Live v3 negotiation, offer and reset-mask pruning, and reset on Fabric, Forge, and NeoForge; the server-selected mask removing retained and frozen values; permission-gated and per-target-type-policy-gated projection for two recipients of one marker; live property uploads through the intended programmatic/harness create path (there is no in-game property-entry GUI by design) recaptured against authoritative world state with whole-create rejection on a wrong-kind, unknown, forbidden, or unavailable selection; rendered default display reference and property HUD lines; live property Ping Type override resolution and tag/registry selector matching against actual block, item, and entity tags; the policy rule-view read and per-target-type mutation route over a real connection, including the permission-3 mutation gate, rule-view revision ordering, and unsolicited broadcast to a second client; the server per-target-type policy page with no property-entry editor; superseded marker C2S/S2C routes mutating nothing; installed-Create kinetic and vault/tank summaries including nested count property selections and the cached stress/capacity accessor path and an untested-version fallback; and a live validated Sable external-block input through the Create route with unavailable and stale outcomes; and a zero server `scanBudget` under live demand with captures deferred while cached values and staleness semantics are retained. |
| Settings and config | External edits do not reload in-session and apply after restart or explicit reload; invalid-config recovery and preservation lock; live scope-tab and category navigation with leaf Back/Escape and root Done/Escape, a fixed footer with non-covering scrolled content, and per-page scroll/focus retention across back navigation and GUI resize, native widget input dispatch and focus-list traversal after a deferred page transition, and root and leaf pages at small GUI sizes and with long localized labels; one shared server session with a single correlated snapshot request retained across category and scope navigation, loading/permission/unavailable status, and a non-editable snapshot rendering read-only for a viewer below the required level; live permission revocation retaining a read-only leaf view, permission-return draft reset, promotion requesting a fresh snapshot, and reconnect draft behavior; invalid-draft close blocking with routing to the offending category and field; the client configuration file action and confirmation-dialog flows, including the reset warning when a server draft exists; the server Presentation category's per-target-type policy rows with their paired allow/block toggles and editable add/remove, whitelist-only changes, refresh, feedback, and broadcast to another client when permitted, including bounded no-response timeout retry, list-capacity feedback, caret and field focus, and a persistence fault during a mutation; and the marker display duration option shows its complete localized `<setting name>: <value>` label for both the Follow server sentinel and an explicit duration ([label owner](../UI/settings-screen.md#marker-display-duration-option)). |
| Range | Client/server range combinations in one live pipeline: native minimum, a live long-distance Distant Horizons target, Create/Sable finite-segment reuse and server acceptance, including exact Create surface selection followed by whole-entity server-anchor range rejection. Installed-Sable scenarios are listed below. |
| Rate policy | Synchronization on reconnect and on effective live configuration change. |
| Optional content and rendering | Absent or partially present optional content; Create/Flywheel routes; occlusion and arbitrary camera angles; current shape, offset and seed. |
| Render entity lookup | A real frame epoch shared by HUD marker updates and optional outlines; fresh non-render lookups after render misses; CPU-frame and allocation measurements for 1, 10, and 50 entity marks in a dense world at high FPS. |
| Create contraption raycast | Hollow, L-shaped, sparse and non-full-block structures: hit occupied surfaces and pass through holes; front empty AABBs, overlapping structures and intervening world walls: select the nearest actual target; all four transparent/fluid policy combinations including represented waterlogging and partial fluid shapes; controlled rotations, moving and minecart-mounted structures, pitched carriages and gantry forms; current-dimension portal-hidden portions and client-loading data availability; long rays starting inside only broad bounds versus an actual selected shape; hold the key while the camera or structure moves and retain the press-time target; Create-absent, delegate-unavailable, client-reconnect and different Flywheel/outline backend paths; measure large-structure press-edge targeting cost. |
| Sable external blocks | An installed-Sable client/server session covering [candidate capture and presentation](../integrations/sable.md#client-capture-and-presentation), [server information sampling](../integrations/sable.md#server-information-sampling), and [server materialization and release](../integrations/sable.md#server-validation-and-materialization) after removal, expiry, owner disconnect, and empty-audience cleanup; sublevel rotation/translation and current tracking-point relocation; state or registry identity change; unload/reload, no-force-load behavior, provider reference and marker/session lifetime; names and fail-soft behavior; and multiplayer Create create, refresh, and removal. |

## Recording future evidence

- Add or update focused tests in the same change as behavior where practical.
- Record a gap as closed only when a named automated check or an actually
  performed manual/integration scenario covers it.
- Keep automated coverage, commands actually run, and manual observations as
  separate claims in implementation reports.
- Update the owning topic contract when expected behavior changes; changing a
  test alone does not redefine the product.
- Do not convert pending manual scenarios into completed validation based on
  compilation, document review, optional-API loading or unit-test seams alone.
