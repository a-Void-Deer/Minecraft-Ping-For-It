# Presentation verification

Use the [verification index](README.md) for the global evidence rules and the
[pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)
for scenarios that remain pending.

## Presentation snapshot negotiation, policy and adapters

Focused common tests cover the versioned presentation contract at model,
property, child-deny, codec, admission and lease-refresh/capture-budget seams.
The suite groups are:

- `PresentationCoreTest`, `PresentationPropertyCoreTest`,
  `PresentationCodecAnnotationTest`, `PresentationPacketsV2Test`,
  `PresentationReceiptContentTest` and `PresentationReceiptProjectorTest` cover
  selector precedence and fail-closed construction, epoch/view store tombstones
  and bounded history, version-two packet and annotation shape, bounded property
  references, and receipt descriptor codec and pure projection. The server
  `sendInitial` bound is source/compile-covered rather than directly exercised.
- `PresentationServerBasicEntityTest`, `PresentationServerExternalBlockTest`,
  `ServerPropertyAdmissionTest`, `PresentationServerRefreshTest`,
  `PresentationServerFairRotationTest`, `PresentationServerBaselinePagingTest`
  and `PresentationServerReceiptTest` cover production Basic and external
  assembly, property admission and recapture, lease refresh and cached
  projections, fair sampling with a population beyond cache, read-free baseline
  paging, and initial-sending receipt projection. The scheduler and baseline
  cases follow the
  [sampling and baseline contract](../../architecture/presentation/presentation_snapshot.md#server-capture-and-per-recipient-projection).
  Sessions and observation ports are in-memory or recording; the live server
  world, providers, packet transport and rendering remain unexercised.
- `ClientPresentationTest`, `ClientPresentationReceiptAcceptanceTest`,
  `PresentationPropertyFormatterTest`, `PresentationKineticFormatTest`,
  `PresentationFieldCatalogTest`, `PresentationSelectorDraftModelTest` and
  `PresentationSelectorListModelTest` cover client epoch/view guards and frozen
  annotations, receipt acceptance, display and kinetic formatting, field
  catalogue, editing drafts and selector-list grammar. Historical dynamic-policy
  cases remain evidence for their recorded input only; current frozen-grant
  projection is under
  [frozen recipient content authorization](#frozen-recipient-content-authorization-coverage-and-live-gaps).
  Actual widget focus, screen rendering and live locale behavior are not
  exercised.
- `PresentationConfigTest`, `PresentationChildBlackPolicyTest`,
  `PresentationSettingsPolicyTest`, `ConfigPresentationMigrationTest`,
  `ServerPresentationPolicyServiceTest`, `ServerPresentationPolicyPacketsTest`,
  `PresentationChildPolicyPacketsTest` and
  `ServerPresentationPolicyStateTest` cover policy defaults and isolation,
  child-deny persistence, migration and normalization, rule-view service and
  packet/codec boundaries, and detached rule maps. Isolation, explicit-null and
  array-input cases are source-reviewed, with no passing run recorded here.
- `PresentationDiagnosticsTest`, `PresentationServerDiagnosticsTest`,
  `ClientPingActionDispatcherTest`, `ClientPingDispatchReceiptTest`,
  `ClientPingRuntimeInteractionTest` and `ModIdentityTest` cover real
  test-appender diagnostic events and disclosure alongside fallbacks, dispatcher
  and receipt paths, a property create through the native selector's content
  release, and route and identity pins. `InventoryProductionPolicyTest` keeps the
  initial baseline pending after a thrown creation handoff with baseline-retry
  handling. These are headless production-call-site and recording-delivery
  seams.

Related optional-adapter evidence is recorded under
[Create presentation adapters](integrations.md#create-presentation-adapters).

The refresh tests invoke production delivery, refresh and sampling methods
through in-memory sessions and recording observation ports. The set does not
establish live client/server negotiation, reset, mask or child-deny pruning over
a real connection, transport registration in a running game, live receipt
transport or chat delivery, a real world or provider observation, live
permission projection, a live policy rule-view read, mutation or broadcast,
property upload admission against a real world, rendered property, chat or HUD
lines, the live settings draft, field-catalogue rendering, feedback, caret or
list-capacity behavior, a persistence fault during a policy mutation, or
in-game Create or Sable sampling.

## Logical target wire coverage

`CompositeTargetPacketsTest` round-trips both chest group kinds and the Vault
controller key through production `CREATED` and `WINNER` codecs and the real
client presentation and store seams. A winner arriving before its record cannot
invent one; after insertion the explicit logical key addresses the winner while
outline selection still uses the original physical hit and anchor. Malformed or
noncanonical pairs, wrong registries or members, incompatible target or key
carriers, invalid UTF-8, a missing mandatory snapshot key, every truncation,
trailing bytes and version-one frames reject. Grouped key tags in a C2S target
and an old `HELLO` are corrupt, with safe-read input consumed. This establishes
codec and headless session acceptance, not a live mixed-version handshake or
loader transport. The wire owner is
[presentation-v2](../../architecture/network/protocol.md#presentation-snapshot-route-presentation-v2).

Grouped pending-chat completion has separate runtime and store coverage under
[content receipts](#content-receipt-chat-and-pending-completion); the wire tests
above do not establish a live receipt flow.

`CompositeTargetPacketsTest` covers tank controller-key `CREATED` and `WINNER`
round trips and explicit-key client acceptance. An independently read tag `4` payload checks
controller-coordinate and registry ordering; raw mutations reject wrong
registries, dimensions or target kinds, unknown or noncanonical tags, missing
mandatory keys, every truncation and trailing bytes, and C2S rejects the grouped
tag. Passing execution is recorded in the
[tank combined-tree run](build-checks.md#fluid-tank-grouping-and-presentation-verification).

## Target content preview

The
[content-preview owner](../../architecture/presentation/presentation_snapshot.md#target-content-preview)
has foundation and production-seam tests, not a live client/server validation:

- `PresentationPreviewAccessTest`, `ClientPresentationPreviewAccessTest`,
  `ClientPresentationPreviewTest`, `PreviewFoundationRegressionTest` and
  `PreviewPropertyEntriesTest` cover accepted-manifest and target-type mask
  intersection, unready and unknown-field denial, field kinds, detached access
  snapshots, reset revocation and pending-response fencing, local-first
  observations, fallback, observed zero and false, one-shot history, cadence,
  timeout and deferred outcomes, level and view fences, detached projections,
  marker-cache identity and schema boundaries, exact child and intermediate
  denial, flattened entries, release reauthorization, and nested literal keys
  without invented sequence indexes.
- `PresentationPreviewServerTest` and `PresentationServerPreviewTest` cover
  queue admission without ingress reads, residual-work and capture limits,
  bounded expiry, cancellation, fresh authorization before capture and send,
  cadence across request IDs, no repeat after transport refusal, production
  access, validation and tick order, advertised/fresh permission intersection,
  shared-work admission, revocation, bounded optional failures, composed packet
  codecs, and no marker state or uncommitted external candidate. Candidate
  preview covers identity drift including the opaque provider locator,
  server-resolved target type, unavailable safe source, budget before read,
  candidate-aware adapter collection, and a custom-only candidate through one
  paid member-gated observation, where failed membership, a missing binding, a
  partial or mismatched observation, or a masked custom field never reads or
  publishes the name. Child-denial union cannot widen a detached snapshot. Its
  residual-grant cases and presentation-cycle monitoring are headless.
- `MinecraftPreviewFieldAccessTest`, `MinecraftBlockReadSourcesTest`,
  `PresentationPreviewPacketsTest` and
  `PresentationPreviewInnerSectionTest` cover the Basic reader with real
  headless entity and item instances, provider-resolved external binding,
  detached read coordinates and locator preservation, bounded request, result
  and control codecs, exact consumption, duplicate and mask/kind/schema checks,
  denied-field skipping, annotation rejection, strict integer decoding,
  malformed replies not consuming a pending request, and the legacy section
  route.
- `PresentationPreviewWiringTest` provides static loader registration,
  main-thread handoff, client-only prediction-guard and queue-cleanup evidence;
  it does not exercise transport registration or mixin application in a game.

`PresentationPreviewDiagnosticsTest` covers throwing local and retained-cache
readers with causes, one-shot fallback timeout without invented values,
content-free `NORMAL` warnings that keep the request eligible, sender pending
state before rethrow, client access and cancel failures, server access and
capture denial, and a failed send not triggering another read. It uses fake
reader, world and transport ports with a test appender, not live preview delivery
or input. Optional Create receipt and preview-gate evidence is under
[Create content preview](integrations.md#create-content-preview).

These tests exist at headless runtime, reader, model, codec and static wiring
seams. The preview inventory records no execution result of its own, and none of
these cases establishes world-backed
validation, prediction-guard application, live provider source or membership
resolution, live permission changes, native content selection through
authoritative CREATE, installed optional providers, network delivery, rendering
or multiplayer behavior.

## Content receipt chat and pending completion

The content receipt's message and completion lifecycle has headless model, store
and port coverage rather than live chat evidence.

`ContentChatTemplateTest`, `ContentChatComposerTest` and
`ContentChatLocalizationTest` cover the localized single and list template
families, reordered and repeated tokens, literal braces and percent sequences,
missing-token fallback, per-Ping-Type and per-entry overrides, legacy lambda
sources, ordered explicit references, missing or unannotated selections,
custom-name quoting and authoritative decoding, annotation-only coloring, kinetic
capacity, exact long counts, explicit zero, quality preservation, multi-entry
composition, literal property text, the production item decoder, the bundled
locale vocabulary and placeholder parity across all eight locales, and template
parsing in every locale.

`PendingContentChatControllerTest` and `PendingContentChatAcceptedStoresTest`
cover the pending completion controller and real accepted presentation/inventory
stores: only a newly seen accepted content kind begins, duplicates cannot replace
the capture, every explicit reference and annotation is required, missing or
stale names wait while denial cancels, health waits for an authorized maximum,
stale sections wait, optional capacity never blocks stress, creation snapshots
keep independent quantity uncertainty and are not replaced by fresh Tracking,
reset, removal, eviction, disconnect and epoch/view changes fence late data,
cancellation leaves no permanent history, reset or clear inside callbacks cannot
send late output, reentrancy cannot duplicate, and sink/composer failures follow
their bounded retry and propagation rules. Accepted Basic completes once, a
reset cancels pending, expiry cancels while the marker store may retain a visual
record, and accepted inventory snapshots use scoped receipt authority. The
fixture is not production runtime wiring.

`ClientPingRuntimeContentReceiptTest` drives the real packet-dispatched runtime,
stores and composer with recording effect, world and input ports: ordinary
receipt immediacy and replay suppression, property content through the content
hook, nullable-only and mixed selections, non-Basic section arrival, current
authorized value waiting, exact long counts, reset, expiry, removal, eviction and
disconnect fences, stale and final-deletion cancellation, author retention across
an external locator refresh, missing names, revocation, suppressed sound-only
receipts, malformed selections, name decoding, stale epoch/view/revision,
optional stress without capacity, denied capacity, sibling supersession, and
receipt callbacks that never capture or send. Accepted creation snapshots
traverse the same runtime: immediate chat and HUD `UPDATING` preserve the value,
later Tracking changes only the HUD, old lane denial cannot veto the creation
grant, true denial suppresses, uncertainty stays independent, and sound or
removal reentry cannot resurrect chat. The production selected-locale template
source is also exercised for a multi-property receipt, including list grammar,
per-entry overrides, fallback-locale isolation, annotation coloring and no
repeat on later updates. A throwing name formatter keeps the marker and immediate
sound, reports the cause and completes once after repair; a throwing content sink
propagates without retracting the marker. These are headless runtime and
recording-port checks.

The grouped-container cases in the receipt fixtures match the accepted snapshot
and stored marker through their explicit frozen logical key for chests, trapped
chests, Vaults and fluid tanks. Client copies and stale transitions retain that
identity; another logical group at the same physical hit cannot inherit the
receipt. The runtime cases encode and decode presentation-v2 packets and inventory
frames: property receipts require a fresh accepted annotated section, and wrong
epochs, stale data and absent annotations cannot complete them. Duplicate frames
or creates, reset and authoritative expiry cannot repeat or revive a property
receipt, while `WHOLE` feedback stays immediate and once-only. The grouped tank
fluid receipt's independent lane authorization is included. Its recorded
[tank combined-tree run](build-checks.md#fluid-tank-grouping-and-presentation-verification)
is historical coverage for that headless receipt path and does not establish the
immediate creation-snapshot path; the same headless effect and transport limits
apply.

`ChatDiagnosticsTest` covers controlled debug events for malformed whole and
content templates while preserving each family's fallback, a throwing locale
selection lookup with the original cause, and no attached throwable.
`ClientBusinessDiagnosticsTest` adds a locale-key cache failure reported once at
warning level under an INFO threshold for one language identity, then recovery
when that identity changes; ordinary absent resources are quiet. These are
resource lookup and cache seams, not an actual resource reload or locale switch.

These are headless model, codec, store and composer seams over recording effect,
world and input ports. They do not exercise live packet transport or loader
registration, the Minecraft chat overlay or GUI delivery, actual sound playback,
resource reload or locale switching, or multiplayer behavior.

### Immediate inventory creation snapshot coverage

Historical evidence note: these cases passed with the dynamic lane and receipt
authorization of their recorded input, and the details below describe that input.
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md)
changes new-Ping authorization timing; the revised current-source variants passed
in the final combined execution recorded in
[build checks](build-checks.md#provider-trust-and-frozen-authorization-final-verification).
The live limits below remain.

`InventoryCreationSnapshotIntegrationTest` covered 17 parameterized cases across
eight methods on that recorded input: immediate creation chat with HUD updating
before later Tracking changes only the HUD; old-deny and new-grant ordering across
many HELLOs; true denial yielding suppressed content without resource or chat;
creation-grant isolation from another marker's denial; uncertain completion
without a current Preview; a refused inventory initial not blocking ordinary or
property receipts; sound-callback revocation not undone by seed installation or
server replay; and deferred creation removed before handoff not emitting late chat
for `EXPIRED` or `CANCELLED`.

Those recorded cases passed in the JDK 21 headless common run using actual codecs
and injected final send and chat sinks. Their current-source variants passed in
the
[final combined execution](build-checks.md#provider-trust-and-frozen-authorization-final-verification);
the frozen-authorization section below records the new coverage. The cases do not
establish a real socket, FML or game client, or GPU pixels. Supporting unit
coverage includes `ClientInventoryLanesTest` for marker-scoped creation authority
before an inventory handshake and isolation from older lane denials, and
`ClientDisplayTextTest` for detached bounded component-text freeze.

`InventoryCreationSnapshotPacketsTest` covers the literal `CREATED` inventory
receipt wire, exactly one snapshot with no refs, and strict malformed, truncated
and trailing rejection. `EncodedPresentationPayloadTest`, the loader
`EncodedCreatedTransportTest` cases and the Fabric and NeoForge
`PresentationPayloadRegistrationTest` cases cover frozen-body write-once,
existing route and codec registration, missing or unnegotiated channel refusal,
send-throw caller ownership and unchanged ordinary record and control wire. These
use injected final send and eligibility and real registered codecs, not a running
multiplayer connection. Server-side freeze, missing-row rejection, replay,
retirement and budget coverage is owned by
[inventory verification](inventory.md#creation-snapshot-server-coverage). The
final combined execution is recorded in
[build checks](build-checks.md#provider-trust-and-frozen-authorization-final-verification).

### Frozen recipient content authorization coverage and live gaps

The dynamic recipient, field, child and receipt checks inventoried above remain
historical evidence for their recorded inputs. They do not establish
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md)'s
create-time policy.

The current-source cases below passed in the final combined execution recorded in
[build checks](build-checks.md#provider-trust-and-frozen-authorization-final-verification);
they establish the frozen-grant boundary headlessly, and the remaining live
scenarios stay pending.

- `FrozenGrantReplayTest` covers client projection against the `CREATED` grant:
  per-marker opposite fields and exact child denials, same-epoch tighter and
  looser `RESET` values neither pruning nor widening an existing grant, later
  `SECTION` values projected through each marker's grant, a changed-grant replay
  rejected without mutation, a same-grant replay accepted, a differing-view
  replay rejected by the view fence, close and new-epoch fences, and no
  restoration of old grants.
- `FrozenGrantCompatibilityTest` covers the client typed gate: a mismatched
  adapter schema or field kind rejects `CREATED` before a marker grant or value
  is retained, a matching schema and kind accepts, and a property receipt
  reference cannot be authorized by a mismatched inventory adapter schema.
- `FrozenInitialAdmissionTest` covers a zero-adapter deny-all grant that still
  admits marker identity without retaining a denied field, mismatched schema or
  kind behavior, and projected grant sizing equal to wire filtering with inert
  child references ignored. Its inventory admission case is under
  [inventory](inventory.md#frozen-recipient-authorization-and-fluid-provider-trust-coverage-and-live-gaps).
- `FrozenGrantMalformedFrameTest` covers canonical round trips, every truncation,
  duplicate or noncanonical adapter and field order, unknown kinds, root child
  references, bounded counts before reading bodies, malformed UTF, an oversized
  write emitting no bytes, and complete body consumption.

The server creation cut, the per-recipient authorization value and the actual
initial, baseline, refreshed and cached send-entry delivery case are inventoried
under
[identity and authority](identity-authority.md#frozen-recipient-authorization-coverage-and-live-gaps).

The `CREATED` marker-content-grant grammar is owned by the
[presentation-v2 protocol](../../architecture/network/protocol.md#presentation-snapshot-route-presentation-v2);
the inventory Preview request grant is the session-wide `OPENED` control in the
[inventory-v2 protocol](../../architecture/network/protocol.md#inventory-previewtracking-route-inventory-v2).

Pending live evidence covers delivery and repair of a create-time result for
Basic, Property, SECTION, Inventory and receipt content over a real connection;
the ready or `HELLO` gate for an original audience member that was not ready at
create; true disconnect behavior; later `RESET` and metadata changes applying
only to new authorization and new Pings while the no-Ping preview continues to
require the accepted `RESET` mask and advertised/fresh child union; and content
updates under current source, session, schema, expiry and resource gates with
source-read revocation still rejecting. The
[immediate creation snapshot coverage](#immediate-inventory-creation-snapshot-coverage)
records that path's historical scope. The live scenarios remain pending in the
[manual matrix](manual-integration.md#frozen-recipient-authorization-and-provider-trust).

## Names and chat

`PingChatBuilderTest` covers required placeholders, escaping, and missing-token
and legacy fallback seams. It does not close localized-name, custom-name, locale
or resource-fallback scenarios. Name-composition and rendering evidence is
recorded under [rendering verification](rendering.md).

## Known automated gaps

- live presentation policy rule-view read, selector add or remove,
  whitelist-only change, child-deny disclosure and preservation, refresh,
  feedback, unsolicited broadcast, and live loader registration or network
  transport for the presentation snapshot and policy routes;
- a live two-sided presentation session: negotiation and reset over a real
  connection, per-recipient permission projection, same-epoch `RESET` retaining
  an old Ping's frozen child and default-reference result without retroactive
  pruning or expansion under the
  [global frozen authorization rule](../../architecture/presentation/presentation_snapshot.md#frozen-recipient-content-authorization),
  the epoch/view fence still governing a reset, policy changes advancing the view
  for new creates, and delivery of Basic and adapter values across the loader
  transport;
- external-refresh and client-receipt behavior under live conditions: those
  seams are headless, so no automated test exercises a real Minecraft or Sable
  world (logical anchor or render pose), a loaded sublevel, world-backed
  projection, source sampling cost, packet-level ordering, a policy change
  between snapshot and delivery that must not alter the Ping's frozen
  recipient/content result, or the rendered client marker;
- content receipt descriptor, projection, server initial-sending and client
  acceptance, pending content-chat completion and composition have the headless
  coverage inventoried above. No automated test exercises the receipt over a live
  connection; loader transport tests inject final send and eligibility with real
  registered codecs and do not establish running negotiation. The Minecraft chat
  overlay or GUI delivery, actual sound playback, resource reload, locale
  switching and multiplayer remain manual gaps; the receipt lifecycle owner is
  [names and chat](../../architecture/rendering/names_chat.md#content-receipt-lifecycle);
- a live property-upload admission round trip: admission, recapture, override and
  creation-rollback seams use fakes, in-memory state or recording references, so
  no real server world, live provider, external materialization or packet
  transport is exercised; property Ping Type override matching is model-tested
  against supplied tag IDs rather than a live registry or tag manager; rendered
  property HUD lines and frozen-mask retained values are client/provider seams
  rather than a frame observation. Content preview has client-first,
  server-fallback and production-seam coverage, but native property selection
  through authoritative CREATE in a live session remains pending;
- the live local invalid-target chat path: focused tests cover the composed
  translatable prefix and message component, its color and the bundled resource
  text, but no test exercises the Minecraft chat overlay or the client trigger
  paths that show the line.
