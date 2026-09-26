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
registration of the legacy, presentation-v2, presentation-policy, and
superseded marker routes is confirmed by source inspection for Fabric, Forge,
and NeoForge.
`MarkerPacketsTest` and `PacketHandlerTest` cover authoritative packet
codec/safety seams, but there is no direct automated test that a valid legacy
location S2C packet or a superseded marker S2C packet is ignored by
`CommonClient`, nor a live cross-loader network test.

### Presentation snapshot negotiation, policy and adapters

Focused common tests cover the versioned presentation contract at model and
codec seams:

- `PresentationCoreTest` covers allow/deny selector wildcard semantics with
  allow-before-deny precedence, fail-closed selector construction and value
  limits, session-generation store behavior (frozen snapshots after expiry,
  per-adapter revisioned clears, tombstone/eviction resurrection guards, and
  bounded-history fail-closed behavior), the framed codec's skip-denied,
  duplicate, depth, and oversize rejection, and bounding oversized semantically
  valid Basic field content to an empty stale section before initial delivery
  while fitting sections pass through unchanged; the server-side `sendInitial`
  ordering that applies this bound before delivery is source/compile covered
  rather than directly exercised;
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
  dimension lookup, entity lookup acceptance, capture scheduling, projection,
  packet delivery, and rendering) remains unexercised;
- `ClientPresentationTest` covers the accepted `HELLO`/offer path with
  mandatory Basic, subscription filtering under a local deny list, reset gating
  by epoch/generation/view, whole-section replacement, receive-tightening
  pruning of already-retained frozen values with re-subscription, display-policy
  and UI provider projection that sends no network traffic, and incompatible
  field kinds never being subscribed or retained. It also covers default health
  and maximum-health subscription and the default health-line projection with no
  explicit rule; a display denial of one health field removing the health line;
  a missing maximum never producing a partial line; the default provider
  projecting a Create RPM line only from the receive/display-filtered view; the
  accepted server catalogue keeping the advertised default and label and
  clearing on close; a compatible-empty accepted offer marking the catalogue
  known; the offline local manifest being available without sending a packet; a
  structurally malformed offer (duplicate field IDs inside one adapter,
  manifest/schema mismatch, invalid schema, or adapter/field bounds) rejecting
  the whole offer atomically while a later valid offer is still accepted; an
  unknown advertised adapter being ignored and never published; and a
  structurally valid offer without a compatible Basic adapter being ignored
  without publishing connection state; and
- `PresentationFieldCatalogTest` covers namespace grouping by field ID rather
  than the owning adapter, exact field IDs, first-occurrence de-duplication,
  immutability, retained server-advertised default and label over local
  metadata, adapter lookup from a bridging adapter, and the bundled
  namespace-heading key pattern; it does not construct the settings screen or
  render rows; and
- `PresentationConfigTest` covers missing-key defaults without migration,
  partial nested policy objects not elevating allow lists, JSON round trips,
  bounded/clamped invalid server overrides denying access, fingerprint change,
  and the replaceable permission provider's vanilla default and fail-closed
  behavior;
- `ServerPresentationPolicyServiceTest` covers the rule-view read disclosing only
  the three selector values, missing-settings fail-closed reads, denial without
  mutation, atomic white/black add and remove, duplicate/invalid/full/missing
  rejection without a durable deny-all, whitelist-only set and no-op, and the
  detached-copy isolation used by the transactional server apply;
- `ServerPresentationPolicyPacketsTest` covers C2S read/mutation codec round
  trips, request-id and selector corruption rules, safe-decode draining of
  unknown operations and oversized fields, and the S2C rule-view round trip,
  unsolicited zero-request id, defensive list copies, and fail-closed
  encode/decode at the capacity, selector-length, and encoded-byte bounds;
- `ServerPresentationPolicyStateTest` covers the unknown initial state, the
  correlated OK readiness transition, error snapshots never publishing a view,
  mismatched and stale response rejection, unsolicited-revision gating, the
  disconnect clear with late-packet rejection, mutation allocation gated on
  known, granted, and no-pending state, the precedence-ordered disconnected,
  unavailable, pending, timed-out, failed, and ready view status, the bounded
  read timeout with its late response ignored, a timed-out mutation keeping the
  known view as uncertain until a confirming read, one outstanding command with
  read retries never replacing an in-flight mutation, and a newer broadcast
  retained when its correlated response or an equal-revision acknowledgement
  arrives; and
- `PresentationSelectorDraftModelTest` covers stable per-panel and per-list
  slots, independent per-slot drafts and feedback, a single outstanding
  submission, an accepted submission clearing only the unchanged submitted
  draft, a post-submission edit never discarded by a late success, a failed
  submission retaining its text, completion for another slot being ignored,
  reset keeping every draft, forward and backward selection direction
  preserved, negative and out-of-range caret positions clamped after shortened
  or cleared text, and independent per-slot caret state surviving
  reconstruction; this is a pure editing-state seam and does not exercise
  actual widget or disabled-control focus; and
- `PresentationSelectorListModelTest` covers selector normalization, grammar
  validation, empty-versus-invalid outcomes, the shared capacity and length
  caps, case-sensitive duplicate rejection, not-found and invalid removal, and
  immutable non-mutating results.

NeoForge Create adapter seams cover the optional summary route:
`CreatePresentationAdapterTest` (server/client manifest parity, demand gating,
per-field projection, unavailable-versus-partial summary distinction, and
cadence override), `CreatePresentationRegistrationTest` (idempotent
once-per-registry registration and absent-adapter rejection),
`CreatePresentationCollectorBudgetTest` (shape/work/version gates),
`BoundedCreateSummaryTest` (registry-ID aggregation and scan/work/output
limits), and `CreatePresentationMixinPluginTest` (version gate and constructed
ASM node field-shape gate for the cached network accessor).

These are model, codec, registration, and assembly seams. They do not establish
live client/server negotiation, session reset over a real connection, transport
registration in a running game, live permission projection, a live policy
rule-view read/mutation/broadcast, the live settings-screen draft,
field-catalogue rendering, feedback, caret, or list-capacity behavior, a
persistence fault during a policy mutation, chat/HUD delivery, or in-game
Create sampling.

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

`ClientPingRuntimeTest` covers only the current-local-store membership predicate
before and after a same-ID external-locator upsert. It does not invoke
`applyCreated` or cover corrupt/tombstone suppression, actual sound playback,
GUI chat delivery, or cross-dimension execution.

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
  the immutable seven-category client and four-category server order, the
  client-scoped Presentation category opening without server permission, the
  separate Server Presentation leaf, independent ordinary-server and
  presentation-policy view access, and independent per-page viewport and focus
  retention for the two presentation leaves; it also covers rejection of a
  foreign-scope category without navigating, Back to the owning overview with a
  root that reports itself as not closable, per-page scroll and focus retention
  across back, scope switching and forced routing, clamping of negative scroll,
  routing an invalid server draft mask to the category that owns its first
  invalid field, retaining a shared `ServerSettingsModel` draft across
  navigation, and keeping a server leaf open when a permission revocation
  retains a viewable snapshot while forcing the scope overview without one;
- `SettingsCategoryCatalogTest` covers each category's exact setting membership
  and order, including the client Presentation category's receive and display
  settings, its one shared read-only server reference, and the server
  Presentation category's sole server-policy setting, exactly-once placement of
  every catalog setting across categories, the production section composition
  used by both presentation pages, and immutable per-category lists; it does
  not cover full-width layout flags or actual widget placement;
- `PresentationFieldOutcomeTest` covers the client roles always using the
  server-authorized default while the server role uses the advertised manifest
  default, exact and wildcard allow rules winning over block rules, one exact
  field-ID list operation per membership toggle, whitelist-only blocking
  unmatched fields while honoring allow rules, block rules beating the default
  while keeping unknown selectors visible, a null policy never fabricating
  membership or an empty authoritative view, and the server rule controls
  following the pending, uncertain, and permission gate; it is a pure decision
  model and does not construct the settings screen or exercise widget state;
- `PresentationClientFieldControlTest` covers the ordinary client field
  control's exact-allow-only mutation: enabling adds exactly one exact allow
  selector and leaves the block list, whitelist-only mode, wildcard and other
  selectors, and pre-existing duplicates untouched, including when an exact
  block rule must be preserved while the allow wins; an already-present exact
  entry, a full list, an unknown or malformed field id, and a missing policy
  each change nothing and fabricate no allow list; hiding removes every exact
  allow duplicate, preserves every other selector's order, and reports whether
  default settings or a remaining group allow selector keeps the field allowed,
  while whitelist-only or a matching block leaves it actually hidden; and both
  client roles share the same truthful outcome; it is a pure decision model and
  does not construct the settings screen or exercise widget state;
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
  every presentation key present with an identical key set across them, the
  approved English presentation labels, the ordinary client field On/Hidden
  labels, tooltip action hints, and fallback notices, localized names and
  descriptions for every builtin and Create field in every locale, bundled
  translations for the recognized namespace headings, both client and server
  Presentation category registrations, and the read-only server status wording
  in every bundled locale; it does not render or assemble screen labels.

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
and [refresh lifecycle](../integrations/sable.md#refresh-lifecycle). The
following focused seams provide limited structural, locator-codec and
diagnostic evidence:

- `SableClientCompanionAccessContractTest` statically parses the compiled
  access-class constant pool, requires the exact
  `SableCompanion.getContaining(Level, Position)` symbol, and excludes the
  exact names `getClientLevel` and `getContainingClient`;
- `SableExternalBlockLocatorTest` covers representative encode/parse
  round-trips and selected malformed, noncanonical, and out-of-bounds cases;
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

These unit and bytecode seams do not load a Sable runtime or establish the
provider, materialization, tracking-point reference, live-sublevel refresh,
multiplayer, or in-game behavior described by those topic sections.
The external model and fallback routes also resolve provider presentation
independently; therefore this evidence cannot guarantee that provider-local
multipart or subject-type decisions came from one immutable shared snapshot.
That is an implementation-conformance and automated-coverage gap, not an
external exception to the shared-subject contract in
[presentation subjects](../architecture/rendering/presentation_subjects.md).

### Rate-policy courtesy behavior

The current suite covers the create-only client token-bucket courtesy gate,
dropping throttled committed creates without queueing or dispatch tracking,
`MarkerRemove` and channel-update bypass, and corrupt-policy handling. This
client coverage does not close the server and end-to-end policy gaps below.

### Simulated integration coverage

`SimulatedDockingConnectorPresentationResolverTest` covers the stand-in
resolver's connector IDs, facing relationship, powered owner, owner block-entity
identity, and handled-empty result. It does not establish loader registration,
an installed Simulated runtime, or in-game presentation.

### Focused native block-outline regression coverage

The native VoxelShape route requires complementary checks rather than one broad
"outline works" assertion:

1. Production render-state coverage pins `BlockOutlineRenderType` to
   `VertexFormat.Mode.LINES`, vanilla `rendertype_lines`, a fixed width wider
   than vanilla selection lines
   ([`BlockOutlineRenderType.LINE_WIDTH`](../../common/src/main/java/nx/pingwheel/common/client/outline/BlockOutlineRenderType.java)),
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
- installed-Create presentation sampling and display: verified vault/tank
  structure capture, bounded summaries, the cached network stress/capacity
  accessor, and the resulting HUD label lines with no in-game evidence yet;
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
| Wheel | Short and long press; every sector and border color; configured timeout; frozen target; location fallback. |
| Selection policy and input | Live GUI/screen callbacks for selection gating; focus-loss `KeyMapping.releaseAll`, screen-transition and level-instance/dimension discontinuity aborts with late asynchronous completion; loader/gameplay input lifecycle and physical key-repeat behavior on Fabric, Forge, and NeoForge; selection toggles, entity blacklist/default `simulated:honey_glue` rule, and spectator exclusion in a game session. |
| Movement, death and replacement | Target movement while the wheel is open; entity death or dimension change; block state change or replacement while open. |
| Naming and chat | Custom-name formatting; localized base names; item naming; phrase-only text color. |
| Invalid-target feedback | Localized invalid-target message with the `[ping for it]` leading marker on both feedback paths, local pre-commit target loss and the correlated server `TARGET_GONE` rejection; the displayed text and marker come from language resources ([presentation owner](../UI/ping-feedback.md#presentation)). |
| Cancellation | Cone and nearest-own-marker selection; inability to cancel another player's marker; stale/display-hidden candidate followed by server rejection with no local fallback. |
| Multiplayer and protocol | Same-target latest-server-arrival winner; equal-arrival larger-Marker-ID tie; winner fallback after removal or expiry; complete `ServerCore` ordering/channel matrix; all-loader authoritative transport and ignored valid legacy S2C location. |
| Marker HUD | Repeated same-target pings from one sender and from several senders while same-target records remain display-active: the target's displayed HUD alpha does not accumulate with the number of same-target records ([invariant owner](../architecture/markers/client-state.md#winner-slots-are-not-the-render-marker-collection)). |
| Presentation snapshot | Live negotiation, re-subscription, and reset on Fabric, Forge, and NeoForge; local receive-tightening removing retained and frozen values; permission-gated and policy-gated projection for two recipients of one marker; display-policy and provider HUD lines; the policy rule-view read and mutation route over a real connection, including the permission-3 mutation gate, rule-view revision ordering, and unsolicited broadcast to a second client; superseded marker C2S/S2C routes mutating nothing; installed-Create kinetic and vault/tank summaries including the cached stress/capacity accessor path and an untested-version fallback. |
| Settings and config | External edits do not reload in-session and apply after restart or explicit reload; invalid-config recovery and preservation lock; live scope-tab and category navigation with leaf Back/Escape and root Done/Escape, a fixed footer with non-covering scrolled content, and per-page scroll/focus retention across back navigation and GUI resize, native widget input dispatch and focus-list traversal after a deferred page transition, and root and leaf pages at small GUI sizes and with long localized labels; one shared server session with a single correlated snapshot request retained across category and scope navigation, loading/permission/unavailable status, and a non-editable snapshot rendering read-only for a viewer below the required level; live permission revocation retaining a read-only leaf view, permission-return draft reset, promotion requesting a fresh snapshot, and reconnect draft behavior; invalid-draft close blocking with routing to the offending category and field; the client configuration file action and confirmation-dialog flows, including the reset warning when a server draft exists; the Presentation category's local field-catalogue rows and their On/Hidden controls with default and group fallback notices, advanced list editing, and its server rule view shown read-only below the required level, with the server rows' paired allow/block toggles and editable add/remove, whitelist-only changes, refresh, feedback, and broadcast to another client when permitted, including bounded no-response timeout retry, list-capacity feedback, caret and field focus, and a persistence fault during a mutation; and the marker display duration option shows its complete localized `<setting name>: <value>` label for both the Follow server sentinel and an explicit duration ([label owner](../UI/settings-screen.md#marker-display-duration-option)). |
| Range | Client/server range combinations in one live pipeline: native minimum, a live long-distance Distant Horizons target, Create/Sable finite-segment reuse and server acceptance, including exact Create surface selection followed by whole-entity server-anchor range rejection. Installed-Sable scenarios are listed below. |
| Rate policy | Synchronization on reconnect and on effective live configuration change. |
| Optional content and rendering | Absent or partially present optional content; Create/Flywheel routes; occlusion and arbitrary camera angles; current shape, offset and seed. |
| Render entity lookup | A real frame epoch shared by HUD marker updates and optional outlines; fresh non-render lookups after render misses; CPU-frame and allocation measurements for 1, 10, and 50 entity marks in a dense world at high FPS. |
| Create contraption raycast | Hollow, L-shaped, sparse and non-full-block structures: hit occupied surfaces and pass through holes; front empty AABBs, overlapping structures and intervening world walls: select the nearest actual target; all four transparent/fluid policy combinations including represented waterlogging and partial fluid shapes; controlled rotations, moving and minecart-mounted structures, pitched carriages and gantry forms; current-dimension portal-hidden portions and client-loading data availability; long rays starting inside only broad bounds versus an actual selected shape; hold the key while the camera or structure moves and retain the press-time target; Create-absent, delegate-unavailable, client-reconnect and different Flywheel/outline backend paths; measure large-structure press-edge targeting cost. |
| Sable external blocks | An installed-Sable client/server session covering [candidate capture and presentation](../integrations/sable.md#client-capture-and-presentation), [server materialization and release](../integrations/sable.md#server-validation-and-materialization) after removal, expiry, owner disconnect, and empty-audience cleanup, [live-sublevel refresh outcomes](../integrations/sable.md#refresh-lifecycle), names and fail-soft behavior, and multiplayer create, refresh, and removal. |

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
