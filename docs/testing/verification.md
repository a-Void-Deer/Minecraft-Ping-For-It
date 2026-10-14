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
- `ServerMarkerStoreTest` covers the active-recipient winner snapshot: expired
  markers are filtered before selection, ordering uses arrival then larger ID,
  the returned list is immutable, and a baseline query does not mutate the
  stored winner;
- synchronized-deadline and visual-state seams for display expiry and
  expire-fallback behavior through `ClientMarkerDisplayDurationTest` and
  `ClientMarkerStoreTest`; and
- source ordering and one evaluation of each consulted resolver in
  `DefaultTargetResolverTest`.

Marker-store, packet, and update tests provide recipient-scoped state and
channel-mode transport seams, but do not establish the complete `ServerCore`
ordering and channel/admission matrix in a live client/server path. Loader
registration of the legacy, presentation-v1, presentation-policy, inventory-v1,
presentation-preview-v1, server-configuration-v3, and superseded marker routes
is confirmed by source inspection for Fabric, Forge, and NeoForge;
`ModIdentityTest` pins the fork identity, packet-ID namespaces, and the
versioned presentation-v1, inventory-v1, policy-v1, and server-configuration-v3
route IDs; focused packet tests pin those families' version boundaries and
codec shapes, and `PresentationPreviewWiringTest` statically confirms the
preview codec registration and main-thread handoff on every loader. These
checks do not establish server authority or live transport. There is no direct
automated test that a valid legacy location S2C packet or a superseded marker
S2C packet is ignored by the client, nor a live cross-loader network test.

### Presentation snapshot negotiation, policy and adapters

Focused common tests cover the versioned presentation contract at model,
property, child-deny, codec, admission, and lease-refresh/capture-budget seams:

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
  trusted base component instead of strict composition. The same seam covers
  the custom-name rules: a dropped item's stack custom name takes priority over
  an entity custom name, a custom-only demand reads the name source once,
  encodes no composed name and omits an empty name, a dropped item never falls
  back to an entity custom name, `custom_name` stays literal raw text rather
  than composed JSON or a stripped approximation, and a failed or throwing
  custom-name getter, composed-name encoder, or malformed custom component
  omits only the affected name fields while retaining independently demanded
  health. It runs headless
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
  staying nullable while consuming only the presence flag and no ID payload, a
  denied top-level frame skipping its whole frame including annotations,
  sequence entries carrying no addressable annotation,
  unknown annotation type and section trailing bytes rejected on decode, typed
  bounded property-intent frames with trailing safety, blank or whitespace
  record keys round-tripping without annotations, and a non-boolean annotation
  on a blank key path rejected on decode;
- `PresentationPacketsV3Test` keeps its historical class name and covers the
  version-one packet contract: no `SUBSCRIBE` kind and the version-one route IDs,
  schema-only `HELLO`, bounded typed property
  observations on `CREATE` with duplicate-ref rejection, `REMOVE` round trips
  and a targetless `CREATE` being corrupt, `OFFER` manifest round trips,
  `RESET` carrying the authoritative mask with an empty mask denying
  everything, `CREATED` carrying the default ref beside the annotated section
  (and being corrupt without one), `REMOVED`/`REJECT` round trips, and a
  previous protocol value rejected on both directions with the frame fully
  drained;
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
  selections still requiring available observation; a custom-only external
  admission recapturing the actual provider's raw custom text as the selectable
  value with one name read and a reduced Basic demand that drops masked block
  state; an absent or empty actual custom name rejecting the uploaded claim
  instead of admitting it; `custom_name` never replacing the default reference;
  a masked or unpaid custom selection rejected before any capture; an exact
  child-denied intent rejecting the whole request before any source capture
  while its complete root record still admits and projects with only the root
  annotation; and all-type masks revoking on permission and provider changes
  without client selections. The capture context and providers are fakes; the
  real server
  world, live provider, and end-to-end transport remain unexercised;
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
  advancing `nextSample`, announces the store's actual winner rather than a
  cached lease winner, and never fabricates a record outside the sampling
  cache; that winner reaches the client outline projection once its record is
  known; a policy reset re-announces the winner under the advanced view;
  positive per-capture allowances cannot reset the
  shared per-tick capture quota across leases; refreshed metadata does not
  advance the effective sampling interval; a zero `scanBudget` defers Basic and
  optional observations without mutating cached values or their stale flag, and
  empty demand still clears a previously sampled optional section; and
  exhausted shared work defers an optional observation without a fake failure
  while unused per-call allowance does not drain shared tick work. Its sessions,
  demand and Basic observation ports are in-memory or recording; the live
  server world, registry lookups, real providers, packet transport, rendering,
  and the complete tick/live baseline remain unexercised;
- `ClientPresentationTest` covers the version-one client session:
  `HELLO`/`OFFER`/`RESET`
  epoch and view guards without any client preference, a tighter reset mask
  pruning frozen fields and annotations without resurrection, accepted metadata
  being preserved but never treated as an authorization mask, `CREATED`
  initializing the stored target type and default while sections carry nested
  annotations, masks authorizing each marker by its stored type rather than
  its adapter or name, a reset's child deny map pruning a stored frozen
  annotation and a denied default reference while the parent field keeps its
  complete value and its descendants stay selectable, a denied annotation
  dropped before storage so neither a later loosening nor a frozen section can
  resurrect it,
  a `CREATED` selection naming a denied child rejecting atomically with the
  marker insertion, a zero-view reset rejected without publishing authority,
  and a soft-removed tombstone exposing an empty projection;
- `PresentationReceiptContentTest` covers the version-one receipt content
  descriptor as a strict codec: the non-property kinds carry no references, a
  property descriptor requires a non-empty sorted unique bounded reference list in a
  deterministic order and exposes it immutably, every kind round-trips, and
  the strict reader rejects an unknown kind, an over-capacity count, an illegal
  list shape, duplicate or out-of-order references, noncanonical numbers,
  invalid identifiers and path grammar, and malformed UTF-8 metadata while a
  literal replacement character and supplementary code points stay valid
  canonical text and the existing UTF length and character bounds are kept; the
  atomic initial carries the descriptor beside the marker snapshot, default
  reference and section, and trailing bytes or the pre-descriptor version-three
  shape are rejected and, on the safe read path, reported corrupt with the
  frame fully drained;
- `PresentationReceiptProjectorTest` covers the pure receipt projection: no
  selection or only nullable unannotated selections project the whole kind, an
  explicit selection set projects exactly its complete sorted references once
  even when the same reference also appears nullable, any denied reference or a
  denied target name suppresses without disclosing references, a health
  selection requires its authorized maximum-health dependency, item count and
  kinetic capacity are never mandatory dependencies, and the inventory kind
  requires the dedicated route and an authorized name;
- `PresentationServerReceiptTest` covers the production initial-sending
  projection: a default-only marker stays whole even when the default reference
  is a name, an explicit selection carries its exact references including a
  name the HUD skips, a nullable-only selection sends a whole initial a
  negotiated client accepts, a mixed nullable-and-annotated selection carries
  only the explicit references while the nullable value stays
  server-projected, one freshly denied reference suppresses the whole content
  receipt without leaking references, an exact child-denied selection
  suppresses the content receipt and drops only that annotation while a
  root selection still sends a property receipt carrying the complete root
  record, a denied target name suppresses a content
  marker while an ordinary receipt keeps its unknown-name behavior, a missing
  mask or accepted manifest entry suppresses, a health
  selection requires the freshly authorized maximum, and both the atomic
  initial and the cached re-baseline use the fresh inventory permission gate
  rather than the session's cached advertised view without rewriting that view;
- `ClientPresentationReceiptAcceptanceTest` covers client acceptance of the
  descriptor: a denied or unaccepted selection rejects before any atomic store
  mutation, a nested scalar selection and a present unannotated value reject
  without rolling back the prior initial, a missing authorized value still
  accepts and waits while a suppressed receipt retains no references, and the
  custom-name manifest field is accepted as raw text beside the composed name;
- `PresentationPropertyFormatterTest` covers the client display projection:
  distinct nested annotations following the default without inheriting an outer
  Ping Type, annotating the default without duplicating it and absent nested
  entries being skipped, bounded scalar fallbacks never dumping record objects,
  health requiring an authorized maximum while not hiding an independent name,
  an unannotated non-default value being retained but not treated as an
  explicit Ping, the target-name default staying out of the property plan for
  block, entity-block, and location targets, explicit target-name annotations
  not duplicating the authoritative name, and dropped-item item/count display
  remaining intact; it also covers kinetic used stress, total capacity, and
  available capacity formatting as SU from the same projection with the bundled
  English translations, where a used-stress percentage appears only beside a
  positive capacity and a missing or zero capacity keeps plain SU, while
  unrelated property formatting stays unchanged;
- `PresentationKineticFormatTest` covers the kinetic display formatter
  headlessly: used stress beside a positive capacity from the same projection
  renders as SU plus a percent that is never clamped to 100, while a
  missing, zero, non-positive, or non-number capacity omits the percent instead
  of inventing a zero and keeps plain SU; total capacity and available capacity
  stay plain SU with a negative available value unclamped; extreme and
  fractional values use plain two-decimal rounding; the capacity lookup is
  never consulted for capacity or available capacity; a root kinetic speed
  record formats its numeric effective RPM through the localized RPM display
  without consulting capacity — including a negative fractional value and an
  observed zero beside a nonzero theoretical RPM — while a record without a
  numeric `effective_rpm` stays unformatted for the caller's generic record
  summary; and unrelated adapter
  refs, non-numeric values, and nested refs stay unformatted; and
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
- `PresentationChildBlackPolicyTest` covers the persisted child deny list:
  every fresh target type denying exactly the two nested Create kinetic RPM
  references by default while their root and sibling record keys stay
  selectable, exact case-sensitive adapter/field/record-path matching without
  descendant or allow-selector override, an explicit empty list opting a type
  out, a programmatic malformed list durably denying only its own target type,
  and child references surviving deep copy, fingerprint and JSON round trips,
  including an absent member gaining the semantic defaults while an explicit
  empty member stays empty;
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
  rewritten, a malformed presentation object failing closed without
  resetting unrelated config, an absent child deny member gaining the semantic
  Create RPM defaults without a migration, normalization write, or reset while
  an explicit empty member opts out across reloads, each malformed child deny
  member durably denying only its own target type, a custom child reference
  surviving reload and normalization instead of stacking on the defaults, and
  reset restoring the semantic child defaults;
- `ServerPresentationPolicyServiceTest` covers the version-one policy rule-view
  service:
  read-all disclosing every target type in catalog order, missing settings or an
  unknown type failing closed, a permission-denied mutation changing no type,
  add and remove mutating only the selected target type,
  duplicate/missing/invalid/unknown-type requests never changing state,
  over-capacity and no-op requests leaving the rule view unchanged,
  whitelist-only assignment affecting the selected type only, every field
  mutation preserving the selected type's persisted child deny list in the
  rule view it returns, including a custom entry and an explicit opt-out,
  child metadata alone never turning a field no-op into a write, and
  detached-copy isolation with missing settings failing closed;
- `ServerPresentationPolicyPacketsTest` covers the version-one policy route and
  codec: a C2S read without a target type round-tripping,
  mutations round-tripping with a selected target type, unknown-type or
  selector mutations being corrupt, S2C carrying every target type, the
  default fallback carrying all five empty-allow views but being corrupt, a
  partial or unknown rule map rejected, a missing target type decoding to a
  fail-closed fallback, and trailing bytes yielding the corrupt fallback
  instead of an exception;
- `PresentationChildPolicyPacketsTest` covers the child deny wire: a current
  `RESET` round-tripping the complete five-type child map beside the field mask
  with an explicit child-empty type not gaining defaults, a `RESET` without the
  child deny map and a missing, unknown, duplicate, root, over-capacity,
  malformed-path, depth,
  non-canonical, missing-map or trailing child payload rejected as corrupt with
  the reader draining the frame, the version-one policy route carrying each
  type's complete child list and an explicit opt-out, a missing or malformed
  child list in that route rejected rather than defaulted, a complete older
  rule frame without child deny lists not reinterpreted as child-empty,
  maximal accepted reset and policy bodies fitting the transport body bound
  without truncation, and oversized bodies rejected before parsing with a
  strict boolean;
- `ServerPresentationPolicyStateTest` covers the per-target-type full rule map
  being read-only by type while mutations carry the selected type, timeout and
  broadcast ordering never creating or regressing rule views, an
  incomplete view never publishing an allow policy, and a remote child deny
  list detached from its source map while a field mutation leaves it unchanged
  and a child-denied reference yields no policy allowance beside an allowed
  root; and
- `PresentationSelectorDraftModelTest` covers per-target-type draft, feedback,
  and caret independence, one global pending submission clearing only the
  unchanged submitted draft, and a failed or timed-out submission retaining its
  draft while allowing another submission; this is a pure editing-state seam
  and does not exercise actual widget or disabled-control focus; and
- `PresentationSelectorListModelTest` covers selector normalization, grammar
  validation, empty-versus-invalid outcomes, the shared capacity and length
  caps, case-sensitive duplicate rejection, not-found and invalid removal, and
  immutable non-mutating results.

`ClientPingActionDispatcherTest` covers a typed property create using the
frozen target on the actual wire and an exact child revocation rejecting that
create without sending and without silently falling back to a plain create;
`ClientPingDispatchReceiptTest` covers the
actual-send receipt and transport-failure rollback; and
`ClientPingRuntimeInteractionTest` drives that property create through the
native selector's content release. `ModIdentityTest` pins the fork identity,
the packet-ID namespaces, and the versioned route IDs.

NeoForge Create adapter seams cover the optional summary route:
`CreatePresentationAdapterTest` (server/client manifest parity including the
default-enabled derived available-capacity number field, demand gating,
per-field projection, derived-only available-capacity projection: computed only
from the demanded raw capacity/stress pair, never leaking that pair into a
projection that did not demand it, sharing one source capture, never reaching
the source under a zero budget, yielding no derived value when either raw value
is missing, and keeping a negative or double-precision result exact;
unavailable-versus-partial summary distinction; and cadence override),
`CreatePresentationRegistrationTest` (idempotent
once-per-registry registration and absent-adapter rejection),
`CreatePresentationCollectorBudgetTest` (shape/work/version gates plus the paid
visited-member prefix: a rejected, throwing or budget-exhausted member never
refunds earlier member checks and a throwing gate never reads that member),
`BoundedCreateSummaryTest` (registry-ID aggregation and scan/work/output
limits), `CreatePresentationMixinPluginTest` (version gate and constructed
ASM node field-shape gate for the cached network accessor), and
`CreateCachedKineticPreviewTest` (the Create-free locally received cached
network totals seam: a missing receipt never reads the network or raw totals; a
missing network or cached-field accessor shape reads only its shape probe and
no raw total; each condition yields per-field unavailability rather than an
asserted zero, while a genuinely received zero stays observed; the raw
accessors are read at most once per observation and the derived available
capacity reuses that pair with double promotion over extreme finite floats; an
undemanded family field is neither read nor published, so the raw pair never
leaks into the projection; and a throwing or linkage-failing raw accessor or a
non-finite or negative raw value loses only its own field).
`CreateMixinLoaderGateTest` goes beyond the constructed ASM node field-shape
gates: it reads the already-resolved compile-only Create artifact as bytes and
drives both production plugins headlessly through the real ModLauncher bytecode
provider, covering the tested archive's actual accessor and receipt shapes, the
positive decision for each mixin, an untested version stopping before any
bytecode discovery, an unavailable provider staying fail-soft, and a shape
drift that closes only the affected gate. It also pins the provider behavior
those decisions depend on: the unsupported untransformed lookup is rejected
before any class byte is read, and the Mixin-owned lookup does not consult the
installed Mixin class processor. That exclusion uses a per-instance recording
processor whose positive control first shows an ordinary ModLauncher reason
reaching it once — with the caller's reason observed and its AFTER nomination
aggregated into the provider's result — while the Mixin-tagged lookup adds no
processor call, and the actual provider lookup still returns the Create target.
The exercised provider is the repository's normal test-classpath Mixin (0.8.6);
the separate 0.8.7 provider run was an external verification command, not
repository dependency metadata, so this coverage makes no provider-version
promise. It adds no runtime Create dependency and does not apply either
mixin in a launched game.
`CreatePresentationExternalBlockTest` also covers the production `observeSource`
routing seam: committed external metadata gates, the current committed
source-binding and member-gate forwarding, demand and budget forwarding,
ordinary-block routing without external observation, and unavailable or
mismatched inputs stopping before collection. It additionally covers the
candidate preview entry: provider-resolved local position with the original
detached metadata, member-gate forwarding, and foreign metadata, a missing
binding or an out-of-scope or throwing member gate stopping before any member
read. It also covers the compatible source-access defaults: they never fall
back to the legacy observation or drop a resolved member scope. Its recording
`SourceAccess` does not exercise a real Create collector or handler, the Create
mixin/accessor, or a live Sable API.

These are model, property, child-deny, codec, receipt descriptor/projection,
admission, and lease-refresh/capture-budget seams. The refresh tests invoke
production delivery, refresh and sampling
methods through in-memory sessions and recording observation ports. They do not
establish live client/server negotiation, session reset or mask pruning or child
deny pruning over a
real connection, transport registration in a running game, live receipt
transport or chat delivery, a real world or
provider observation, live permission projection, a live policy rule-view
read/mutation/broadcast, property upload admission against a real world,
rendered property/chat/HUD lines, the live settings-screen draft,
field-catalogue rendering, feedback, caret, or list-capacity behavior, a
persistence fault during a policy mutation, or in-game Create/Sable sampling.

### Target content preview

The [content-preview owner](../architecture/presentation/presentation_snapshot.md#target-content-preview)
has foundation and production-seam tests, not a live client/server validation:

- `PresentationPreviewAccessTest` and `ClientPresentationPreviewAccessTest`
  cover accepted-manifest and exact target-type mask intersection, unready and
  unknown-field denial, matching field kinds, dedicated-inventory exclusion,
  detached access snapshots, and a production client-session reset revoking
  reader demand and late responses without populating the marker store,
  including a child-only reset fencing a pending response while a retained
  cached snapshot cannot reintroduce the denied entry;
- `ClientPresentationPreviewTest` and `PreviewFoundationRegressionTest` cover
  local-first observations, fallback for missing authorized roots, observed
  zero and false, one-shot attempt history and cadence, timeout and deferred
  outcomes without polling or invented values, interaction/level/view fences,
  detached projections that expose neither world handles nor caller tokens,
  and historical marker-cache identity, schema, mask and annotation boundaries;
  a locally observed and a server-fetched record both keep their root entry
  while an exact child denial omits only that entry, an intermediate denial
  hides its own entry without hiding descendants, the flattened
  `PreviewPropertyEntries` projection omits the denied exact reference, and a
  release re-checks the current child authorization after a painted or prepared
  entry was revoked.
  `PreviewPropertyEntriesTest` covers actual nested literal property keys and
  sequence roots without inventing addressable sequence indexes;
- `PresentationPreviewServerTest` covers queue admission without ingress reads,
  residual-work and capture limits, bounded queue expiry, cancellation, fresh
  authorization before capture and send, cadence across request IDs, and no
  repeated read after transport refusal. `PresentationServerPreviewTest`
  additionally exercises production access, validation/capture and tick-order
  seams: advertised and fresh permissions intersect, validation and reads are
  admitted before execution, leases consume shared work before preview,
  exhausted work defers, revocation stops publication, optional failures stay
  bounded, request/result codecs compose, and preview never creates marker
  state or materializes an uncommitted external candidate. It also covers the
  candidate preview route: identity drift including the opaque provider locator
  rejects before any provider source read, the actual server-resolved target
  type still governs, an unavailable safe source is unavailable rather than
  approximated, budget admission precedes the safe source read, and an optional
  adapter's preview uses its candidate-aware preview collection instead of its
  committed collection. An authorized custom-only candidate preview resolves
  through one paid member-gated observation: validation and capture are
  admitted before reads, the member gate is checked, the name source is read
  once with no block-state read, and the captured section carries the raw
  custom text and resolves it through its reference without annotations or
  session state; failed membership, a missing binding, a partial or mismatched
  state observation, or a masked custom field never read or publish the name;
  an advertised and a freshly configured child denial union into one
  authorization snapshot, and a view promotion enables a newly allowed exact
  child only through a fresh snapshot while a detached older snapshot is never
  widened;
- `MinecraftPreviewFieldAccessTest` covers the Basic reader with real headless
  entity and item instances: demand precedes getters, observed health and item
  data are retained, constructor-empty item data is unavailable rather than
  zero, and dimension, identity, unavailable-block and generic-name gates stop
  unsupported observations. It also covers a provider-resolved external
  binding: the physical binding is admitted only through its member gate, a
  foreign expected registry is unavailable, a block-owning generic name is not
  observed, and a block without a block entity exposes its physical state name;
- `MinecraftBlockReadSourcesTest` covers the detached read binding: physical
  read coordinates never leak into the original detached external target, and a
  candidate or committed binding requires the exact opaque provider locator
  alongside the unchanged identity quartet while a classification-only change
  keeps the binding;
- `PresentationPreviewPacketsTest` and `PresentationPreviewInnerSectionTest`
  cover bounded request/result/control codecs, exact consumption, duplicate
  demand rejection, mask/kind/schema checks, denied-field skipping, annotation
  rejection, and strict wrapper and inner-section integer decoding. An
  unaccepted field frame is skipped by its framed length before typed decoding,
  yet the whole result is rejected without consuming the pending request; mixed
  allowed and denied fields are never partially applied, and a later authorized
  zero or false is still observed. Malformed replies cannot become provisional
  values or consume the pending request; legacy section decoding remains a
  separately exercised route and keeps its existing grammar; and
- `PresentationPreviewWiringTest` provides static loader registration,
  main-thread handoff, client-only prediction-guard and queue-cleanup evidence.
  It does not exercise transport registration or mixin application in a game.

NeoForge's `CreatePreviewReceiptStateTest` covers explicit client receipt
evidence and its invalidation without treating absent constructor fields as
observed zero. `CreatePreviewMixinPluginTest` covers a constructed target's
receipt/invalidation method and field-shape gate, not live mixin application.
`CreateSableContentPreviewTest` covers the adapter-level separation between the
candidate-aware preview collection and the committed collection, including a
zero preview budget never reaching the source and an adapter without a
candidate source keeping its committed path.

These tests exist at headless runtime, reader, model, codec and static wiring
seams. They do not record a current Gradle run or establish world-backed
validation, prediction-guard application, live provider source or membership
resolution, live permission changes, native
content selection through authoritative CREATE, installed optional providers,
network delivery, rendering, or multiplayer behavior.

### Content receipt chat and pending completion

The content receipt's message and completion lifecycle has headless model,
store and port coverage rather than live chat evidence:

- `ContentChatTemplateTest` covers the localized content template: its tokens
  can be reordered, repeated tokens are allowed while escaped braces
  stay literal, literal percent sequences are never formatted again, a
  template missing any required token falls back to the safe default that
  keeps the author, target, content and annotation phrase and never falls back
  to the whole-message family, malformed templates report empty instead of
  building a partial line, and the per-Ping-Type override follows the selected
  locale's presence policy. Its list family covers the multi-entry grammar: the
  outer template requires only author, target and content and adds no whole-set
  annotation, each entry template carries its own annotation and content, an
  invalid list or entry template falls back within its own family while
  preserving every entry, the per-entry override follows the same
  selected-locale presence policy independently of the single template, and
  legacy single-template lambda sources remain compatible with the default list
  templates;
- `ContentChatComposerTest` covers the content composer: every explicit
  reference is composed in order including an explicit name the HUD plan
  excludes, a missing or unannotated selected reference or an empty selection
  makes the whole message unavailable, custom-name text stays literal and
  quoted exactly once, the injected authoritative name decoder receives the
  stored JSON and raw JSON never reaches the message, only the annotation type
  word is colored, a kinetic stress value reuses the same-projection
  authorized capacity and omits a zero capacity, the localized template
  source follows the override policy and a failed lookup stays a controlled
  default, the bundled Chinese property and inventory examples match the
  confirmed contract, an exact long count and an explicit zero are retained,
  a fallback uses the registry localized item name without the raw label
  literal, the observation quality is preserved without upgrading, and an
  unknown count or terminal quality is unavailable. It also covers the
  multi-entry presentation: mixed independent annotations keep each entry's
  own phrase, action and phrase-only color in one complete message in
  reference order rather than grouping by type, same-type selections each
  retain their own phrase once, and a missing secondary annotation fails
  closed even when every value is present while the compatibility summary
  never conceals it. Selected root and nested text payloads stay exact literal
  text — including braces, brackets and percent sequences — without the HUD's
  short-name reduction clipping them, while a selected record root keeps the
  established size summary instead of a value dump. The production item
  decoder flattens nested custom and `ITEM_NAME` styles and events to
  plain text, quotes only the custom text once beside the trusted
  localized base translation, keeps an unrenamed or normal variant's
  localized base unquoted, and the exact-count and quality rules above
  stay unchanged;
- `ContentChatLocalizationTest` covers the bundled locale files' complete
  content vocabulary: every declared content key and custom-name field
  label/description is present and non-blank, the template carries every
  required token, the quote, field, item-count and quality keys keep their
  placeholder counts, the separator is a written connective without a
  placeholder, and the content key set and placeholder counts stay in parity
  across locales. All eight bundled locales also carry the multiple and entry
  template keys: the outer template carries author, target and content and no
  `{type}`, each entry template carries its own annotation and content exactly
  once, and all three grammars parse in every locale;
- `PendingContentChatControllerTest` covers the pending completion controller
  headlessly: only a newly seen accepted content kind begins, a duplicate
  pending cannot replace the capture, separate adapter arrivals require every
  explicit reference and never send a partial list, each reference needs its
  own annotation, a missing first reference cannot conceal a later denial,
  a missing, malformed or stale name waits while an explicit denial cancels,
  health waits for its required authorized maximum, a stale selected section
  waits and an optional capacity never blocks stress, the inventory gates
  reject partial, unknown-baseline, grey and status-only observations while a
  complete zero is accepted, a long count stays exact, a ready inventory still
  waits for a fresh name and negotiation and then reads the latest count, an
  unknown inventory policy waits while an explicit denial cancels, reset,
  removal, eviction, disconnect and changed epoch/view fences discard late
  data, a positive pending bound fails closed without evicting another
  capture, cancellation leaves no permanent sent history, reset or clear
  inside the name or composer callbacks cannot send late output, reentrancy
  cannot duplicate and the sink runs only after the pending is removed, a
  throwing sink cannot leave a duplicate while a throwing composer can retry
  fresh data, revocation or value replacement is revalidated before emission,
  an exact child revocation cancels the pending capture even while its root
  value and annotation remain, a revocation during formatting is rechecked
  before the chat sink,
  and a clear/rebegin or a new inventory baseline cannot emit the old capture;
- `PendingContentChatAcceptedStoresTest` drives the controller from real
  accepted presentation and inventory stores: accepted Basic completes exactly
  once and a known create never repeats even after a zero local visual
  deadline, fragmented tracking waits for the committed baseline while preview
  and status-only readiness and later stream data never produce another line,
  an invalidated count is grey and cannot be resurrected while a complete zero
  is authoritative, an accepted reset cancels pending and an old or known new
  view cannot restart it, and authoritative expiry cancels while the marker
  store may retain a visual record; its fixture is not production runtime
  wiring;
- `ClientPingRuntimeContentReceiptTest` drives the real packet-dispatched
  runtime, stores and composer with game effects and world/input access as
  ports: an ordinary receipt is immediate once and a replay does not repeat it,
  a property content create sounds and chats through the content hook without
  adding an ordinary line or delaying the sound, a nullable-only selection
  stays whole while a mixed selection carries only the annotated reference as
  content authority and emits exactly one content line when its annotated
  section arrives with the nullable value still server-projected, non-Basic
  selections must all arrive before the complete current set emits, an
  inventory preview and partial baselines wait for the committed baseline
  while later stream data never produces another line, an exact long count
  stays exact, reset, authoritative expiry, hard removal, eviction and
  disconnect fence late completion,
  fallback stale and final deletion cancel while a short visual deadline does
  not, the original author is retained across a same-ID external locator
  refresh, a missing authorized name waits while policy revocation or an
  unavailable game cancels, a suppressed receipt still sounds but chats
  nothing and malformed selection cannot partially mutate the runtime, current
  name JSON and raw custom text decode while a missing annotation does not
  complete, a stale epoch, view or revision cannot complete, an optional
  stress value does not require capacity and the current mask never exposes a
  denied capacity, a synchronized sibling supersedes a stale pending without
  leaking its late values, and receipt callbacks never capture or send. The same
  runtime wiring also carries the production selected-locale template source
  through the controller and composer for a multi-property receipt: the bundled
  Chinese outer template owns the list grammar, a selected-locale per-entry
  override is used while a fallback-locale override present only in the merged
  language is never inherited, an entry without an override falls back to the
  localized entry template, and the single complete message colors only each
  entry's own annotation phrase and never repeats on a later update.

These are headless model, codec, store and composer seams over recording
effect, world and input ports. They do not exercise live packet transport or
loader registration, the Minecraft chat overlay or GUI delivery, actual sound
playback, resource reload or locale switching, or multiplayer behavior.

### Capture, wheel and cancellation

`PingInteractionStateMachineTest` covers short press, long press,
pending/asynchronous capture, wheel state transitions, lifecycle abort, the
actual wheel-open boundary, and that an opened wheel never closes by elapsed
time alone while release still commits its selection.
`PingCaptureCoordinatorTest` covers capture tokens, rejection of stale
superseded completions, completion
races, first-completion ownership and identity-preserving metadata retention.
`PingSelectorReleaseTest` covers the selector release adjudication seam: a
wrong token, a threshold without an actual open, and a pending release with a
late capture never evaluate a selector proposal or retroactively open; elapsed
time alone never closes an actually open selector; terminal local intents and
explicit cancellation outcomes are single-use; precise and opaque inventory
proposals keep their frozen payload without item or count authority; an
unavailable candidate never falls back to the ordinary target; and an abort
fences a late completion before any release proposal can evaluate it,
including an abort re-entered from terminal release ports.
`TargetSnapshotTest` covers snapshot identity, copied capture metadata and frozen
context construction, and that an ordinary or external block target may carry a
capture face while other target kinds may not;
`TargetSnapshotBlockClassificationTest` covers explicit
and absent block-entity classification metadata; and
`MinecraftTargetSnapshotFactoryDetailedTest` covers retaining local detail only
for the matching entity-hit owner. `MinecraftTargetSnapshotFactoryFaceTest`
covers the ordinary-block capture face at the level-free factory seam: each of
the six resolved hit directions is copied as exactly that face, while a
synthetic `MISS` direction and an unavailable-block location fallback never
invent a face. `MinecraftSyntheticBlockFaceTest` covers retaining an actual
native face through the candidate factory path and capture coordinator, while
a synthetic concrete block keeps its target without acquiring a face, including
after direction/position copies and coordinator completion.
`PingCaptureCoordinatorTest` additionally covers
face identity retention: the captured face survives only when resolution
preserves the captured ordinary-block identity, duplicate and late-stale
completions cannot replace an accepted face, and a changed block type or
position discards it. For an external candidate the same retention requires the
exact read binding including the opaque provider locator: a target that merely
compares equal as a candidate, or a changed provider, registry or stable
identity, cannot replace the accepted face. These are factory and coordinator
seams; the complete
client/server `entity_block` classification path, including synthetic-face
provenance from every optional producer, remains unexercised.

`LongPressCompatibilityControllerTest` covers the ordinary rapid-click and
asynchronous deferred-compatibility paths, with focused bounds coverage supplied
by the applicable config-bounds tests. `LongPressCompatibilityDispatchTest`
drives that controller over the real baseline lifecycle, courtesy limiter and
dispatch receipt: a rejected or unready synchronous first default create cannot
seed rapid input; a rejected or unready asynchronous first default create drops
both rapid and deferred presses without queueing, retrying or replaying a
create or capture; a non-`CreatePing` result such as `TargetGone` discards the
deferred press; an actually opened menu disqualifies even a sent default type;
and successful receipts start the rapid second-ray and deferred fresh-press
captures. These are controller/config slices, not real
input callbacks or render-frame integration. An abort followed by a late
asynchronous completion is exercised by `PingSelectorReleaseTest` and
`ClientPingRuntimeInteractionTest`; the real focus-loss `KeyMapping.releaseAll`
hook, screen-transition callbacks, level-instance/dimension discontinuity
detection, loader/gameplay input lifecycle, and genuinely asynchronous
completion remain unverified.

`CancelCandidatePickerTest` covers press-ray cone filtering and nearest-candidate
selection, while `ClientMarkerStoreTest` covers owner/dimension retrieval.
Those seams do not runtime-cover a retained stale or display-hidden marker
reaching cancellation and then being rejected by the server without a local
fallback. Frozen press-ray behavior and the pending-capture/wheel interaction
boundary otherwise have focused test coverage.

### Precise live candidate capture

The [live-capture owner](../architecture/picking/capture.md#precise-live-candidate-capture)
has focused headless evidence across its allocation, refresh-controller,
session, release-admission and paint-admission seams:

- `FrozenCandidateAcquisitionTest` covers bounded allocation and identity
  de-duplication, retained actual class and per-candidate face, nearest
  same-category competition (a nearer supplement beats a farther ordinary and
  vice versa, with an equal distance keeping the ordinary before an equivalent
  supplement), an incomplete scanned class staying unavailable even when the
  ordinary matches it while a complete no-hit is missing rather than
  incomplete, distinct missing and incomplete results for the same empty
  evidence, specific-class identity consumption before a generic class takes
  the next nearest, stable strict-tie ordering that keeps encounter order
  rather than a specialized bucket order, provider-local equivalence,
  immutable evidence and independent work guards, and a bounded collector
  matching an independent exhaustive nearest oracle across randomized
  mixed-identity orders. Its same-identity near-contact cases keep the ordinary
  resolved target and press context, use the selected contact's snapshot
  geometry and face, leave the ordinary hit unchanged on equal-far evidence,
  and check near-contact block-face and entity-metadata consistency; the
  exhaustive oracle derives its expectation from the complete candidate pool
  independently of the production collector;
- `CandidateCaptureLifecycleTest` covers same-token attachment, duplicate,
  superseded, aborted and reentrant completion fences, no cross-token/ray reuse,
  ordinary readiness and face preservation when optional finalization fails,
  and avoiding duplicate ordinary resolution;
- `NativeBlockCandidateScanTest` and `NativeBlockCandidateRegressionTest`
  exercise native block/fluid shapes behind blockers, finite-segment and loaded
  access gates, actual surface coordinates and faces on long oblique rays,
  moving-piston shape access, bounded neighbor/block-entity and shape work, and
  incomplete rather than certified prefixes on exhaustion. They also cover
  origin containment without an observed face and no adjacent presentation-owner
  expansion;
- `NativeBoundaryFaceProvenanceTest` carries boundary contacts through the
  production native scan, level-free snapshot factory and frozen allocation: a
  tangential boundary contact retains its point and target without installing
  a clamped slab face, a genuine inward boundary entry keeps its actual front
  face, an outward boundary contact stays a miss, true origin containment
  stays unobserved, and a real approach surface keeps its observed face; and
- `RaycastSupplementalCandidateTest` and `NativeEntityEnumerationBudgetTest`
  cover farther entity candidates, exact-owned non-hit rejection without coarse
  fallback, frozen geometry ownership, selection filters, canonical multipart
  and XP locator construction, and bounded lazy enumeration charging nonmatches
  before filtering. The multipart case is a factory fixture, not a dragon-world
  or rendering test;
- `PreciseCaptureRefreshTest` covers the production refresh controller
  headlessly: capture starts immediately on branch entry and then follows the
  frozen tick period, entering again does not duplicate the first scan, leaving
  pauses and re-entering restarts, and ending stops it; native slots publish
  before the independent location request; each slot keeps its own ray context
  and the last certified pending value; a final missing or incomplete result
  replaces or disables the slot without an ordinary fallback; only one optional
  request is in flight, coalescing the latest work without starving or
  overwriting newer native results; stale, duplicate, foreign and post-end
  completions are fenced, and a reentrant branch exit cannot start optional
  work after its native publication;
- `SpatialSelectorSessionTest` additionally covers the live precise session:
  a revised immutable publication preserves slot focus, origin and typed color
  while replacing the installed target; only an actually painted current node
  can be acknowledged, an empty acknowledgement cannot authorize, and a
  disabled paint clears the older selectable version; the release intent
  carries the painted candidate and its presentation revision as a
  `PRECISE_PRESENTED` admission while its previous constructor form remains
  compatible, and duplicate, stale, wrong-token, wrong-level, stale-snapshot
  and post-abort publications are rejected;
- `PingSelectorReleaseTest` covers the release-admission fence: a `PRESS_RAY`
  proposal stays strict to the press ray, while `PRECISE_PRESENTED` requires
  the exact current interaction, the painted context table and an advancing
  presentation revision; unpainted, wrong-revision, other-context and
  press-ray proposals produce no action, and the presentation fence rejects a
  wrong token, dimension or old revision and is revoked by abort;
- `ClientPingRuntimeInteractionTest` drives the precise branch through the
  runtime ports: an ordinary press stays frozen while the precise branch's
  live target changes, entering the branch starts the live request, release
  consumes the last painted candidate without an ordinary recapture or a
  release-time cast, and an unpainted newer target still releases the painted
  one;
- `SpatialOverlayRendererPresentationTest` covers the isolated paint-admission
  predicates: detached exit data, ancestor nodes, first-appearance or
  quantized-zero paint, and a backdrop or pointer alone cannot acknowledge a
  candidate, and absent graphics acknowledge nothing and start no transition;
- `SpatialOverlayRendererFollowerTest` drives the production frame path through
  recording paint and font-metrics ports. Only an enabled action leaf, typed
  property branch, or pure navigation group in the active radial menu follows
  the pointer before dwell; Back, disabled, reserved, and actionless leaves do
  not, nor do deadzone focus, ancestor menus, inventory rows, or detached exit
  paint hide the pointer. Across intermediate frames the follower stays
  collinear with the current pointer ray while its radial position eases as the
  pointer target changes, remains bounded under repeated retargeting, settles
  after a finite transition, and honors reduced motion and focus/exit handoffs.
  Its connector begins at the frozen
  deadzone boundary and ends at the near edge of the actual scaled title frame;
  it is omitted when that frame overlaps the deadzone, follows transition alpha,
  and ignores both opacity preferences. Only a successfully painted, visible,
  current active node hides the crosshair and acknowledges its choice; trail
  state remains independent, while outgoing duplicates, transparent or
  quantized-zero nodes, omitted or failed paints cannot hide or acknowledge.
  An intermediate eased follower may be wholly offscreen even while its pointer
  target is confined: only a positive intersection of the transformed title
  frame admits crosshair hiding, connector drawing and choice acknowledgement,
  and the painted title bounds used by that gate are measured at the focused
  scale.
  Independent PoseStack geometry checks reject tangency and admit positive
  partial intersections at all four viewport edges under fractional pan. The
  real Precise release handoff rejects an invisible current version both when
  no older candidate was admitted and when it must retain an older painted
  admission; an outgoing duplicate or unpainted newer publication cannot
  replace the last successfully painted candidate. These are production-frame
  checks with recording ports, not
  `GuiGraphics` or GPU submission; and
- `SelectorPreciseTargetLabelsTest` covers the Precise detail-label resolver
  headlessly: every enabled Precise leaf names its own captured candidate from
  the exact snapshot it is bound to, a broad slot keeps the candidate's
  canonical target identity instead of synthesizing its slot type, choices
  without a captured candidate never reach the naming function, a newer
  publication cannot retarget an older bound snapshot, literal and localized
  names pass through the existing name resolver including its unknown and Here
  fallbacks, and the returned component is detached from the live one. It is a
  label-resolution helper seam: it does not exercise the real selector draw,
  renderer wiring, or GPU submission.

These are allocation, capture, native-shape, enumeration, refresh-controller,
session, paint-admission, production-frame recording-painter and runtime-port
seams; the period key's config and
persistence coverage is inventoried above, and the transformed Sable scanner
has its own evidence below. Real GPU/render submission, game-world and chunk
lifecycle, provider behavior, multiplayer, loader input callbacks, and
unbounded-geometry wall-clock behavior remain unexercised, and the manual
Precise scenarios below remain pending.

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
independent trace, the server acceptance range, Create/Sable reuse of the
finite native segment in the ordinary trace, and the hold-frozen client
`pingDistance` bound of the Precise live casts. No automated
end-to-end test exercises that entire client capture, optional-provider,
packet, and authoritative server-acceptance pipeline.

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
so it is not event-specific regression coverage. `TargetNamePlainTextTest`
covers the plain-text reduction's UTF-8 byte bound: every cut keeps whole
supplementary code points and never turns a truncated surrogate into a
replacement byte, and the presentation text bound admits the maximum whole
emoji prefix without dropping literal punctuation or adding base text.

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
piecewise grid, and independent defaults do not alias. The two finite size
leaves normalize on their own exact quarter-multiplier grids and repair a
conflicting pair downward with frame priority and the reserved metadata
headroom while never raising the frame, and they remain independent of the five
send-byte multipliers; `InventorySizeScaleTest` covers the ordered legal
positions and their exact byte and quanta conversion, half-up normalization,
pair legality, frame-priority reconcile and ordered position stepping.
`SpatialSelectorSettingsTest` covers the spatial-selector settings snapshot and
bounds: a missing, null or fresh-file target-glide setting initializes the
implementation's non-neutral configured default, while the neutral multiplier
remains an explicit case that preserves the intrinsic list-glide baseline.
Explicit factors, including a valid off-grid value, stay literal rather than
snapping to the step grid, out-of-range factors clamp to the confirmed range,
and each factor drives its own relative row threshold through validation,
round-trip and a frozen gesture snapshot; a live preference edit does not
change the already-frozen gesture, and a later null reset returns to the
configured default rather than the neutral factor. The updated glide
calibration exercises the neutral factor against the updated intrinsic baseline
and explicit faster and slower factors against their relative row thresholds.
This records the calibration coverage, not a numeric tuning catalogue. An
explicit saved factor remains literal in the tested version upgrade rather than
being mass-rescaled.
Hover, deadzone, stroke, dwell, root-distance, and submenu-radius-scale
clamping saturate on both sides, a missing, explicit-null or fresh-file center
deadzone initializes the current confirmed default while every valid saved
deadzone value, including the previous default and both bounds, is retained
literally without rescaling and a held snapshot keeps its frozen deadzone, the
distance and timing clamps stay exact over their whole valid interval, the
spatial distance defaults
and bounds preserve their half-scale relationship, null and out-of-range nested
values validate without throwing, the opt-in Back-hover default stays disabled,
a missing or explicit-null submenu radius scale gains the confirmed default and
the value clamps and freezes into the snapshot, the precise capture period
clamps to its persisted range, defaults to its minimum, and freezes into the
snapshot, the content-intent choice defaults disabled for fresh, missing and
null values, an explicit choice is retained and freezes into the held snapshot
while the live setter supplies the next snapshot, the canonical snapshot
constructor carries the choice while every previous constructor form defaults
it, the previous snapshot constructor forms remain compatible and default
the submenu radius scale and precise capture period, raw-JSON validation and a
frozen snapshot clamp every value without resetting boolean choices, a frozen
session snapshot never follows later config edits, root distance and gesture
preferences stay independent in both mutation directions, and fresh defaults do
not alias another client's mutable preferences. `SpatialSelectorMigrationTest`
also covers missing, null and explicit target-glide settings retaining their
model semantics without a same-version load rewrite: a fresh file persists the
configured non-neutral default, a same-version load and save keep an explicit
factor unchanged, and a version-boundary migration preserves an explicit
multiplier without rescaling. Same-version deadzone defaults follow the same
no-rewrite rule: a fresh file persists the current default, missing, null and
explicit saved deadzone values are retained without rescaling, an untouched
same-version save leaves the bytes unchanged, and a reload keeps the value. It
covers the release-boundary migrations that remove only obsolete wheel keys
without
injecting defaults: the exact boundaries and pre-target versions retire the old
wheel radius keys and the retired wheel timeout key while unknown root data and
appearance values survive, a running version below a boundary keeps its key,
at-or-after-introduction and server configs are not consumed, a target-version
upgrade persists model defaults while preserving unknown nested selector data,
same-version absent, partial, and null selectors gain model defaults without a
load rewrite, a pre-target writeback keeps old key data until the target
upgrade, a current-version load is not retired-key normalization while a
target-version save cannot resurrect the retired keys,
every explicit preference round-trips independently of appearance, including
the precise capture period added without a migration, the content-intent
member never rewrites a same-version file for missing, null or explicit values,
an explicit choice persists across reload, and reset restores the disabled
default, and the future-version guard keeps all bytes and refuses save and
reset; an injected
failed write retains usable in-memory preferences and a retry discards the
stale source before migration. `InventorySettingsPersistenceTest` covers additive
defaults without rewriting a current-version file, older-version migration
writing the nested inventory object while preserving user data, explicit
unlimited round trips, every inventory cap leaf, including the nested preview
and tracking caps, restoring its owning field's model default through the real
filesystem-backed load path when its `value` member or the whole cap object
is absent or null, a missing `unlimited` member defaulting false,
`unlimited: true` without a value staying unlimited with the model default
finite value, an explicit zero or negative value still clamping to the finite
minimum, an untouched load and no-op save leaving the bytes unchanged and
an explicit save materializing the restored default without touching sibling
caps, missing, null and out-of-range size scalars normalizing
with frame priority while an explicit size scalar round-trips and an
unlimited-wrapped size scalar rejects the file, out-of-range values clamping
without resetting unrelated fields or producing a broken backup, the
future-version guard leaving defaults in memory and refusing to save, and the
client
spatial-selector keys defaulting and clamping, including the submenu radius
scale's missing-member default, out-of-range clamp without resetting unrelated
fields, serialized key, and persisted round trip, and the precise capture
period's persisted round trip. Trail length and opacity coverage includes
Gson-initialized missing/null values, retained explicit zero and 73 opacity
values, frozen snapshot values, unchanged same-version bytes on load, an
untouched save as a no-op, and round-trip persistence. A target-version
upgrade preserves explicit values and unknown nested selector data. These are
model and persistence seams only: no server-administration path, remote change
route, settings-widget interaction, scheduler consumption or full client
selector runtime is exercised.

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

### Back-hover return state

`BackHoverControllerTest` covers the headless Back-hover timing model: progress
before the threshold never triggers, the threshold triggers exactly once and a
held focus never repeats, leaving and re-entering restarts the clock, a parent
Back stays blocked through held focus, observed focus loss, and motion within
Back; only an effective physical leave followed by re-entry permits rearming.
Switching the focused menu restarts the clock, a rewound clock clamps instead
of resetting the baseline, `end` clears the timer and block idempotently and a
fresh session is unblocked, a disabled session never arms or triggers, updates
before start are inert, and the hover parameters are frozen at session start.
It is a standalone model seam: no native screen, input route, renderer, or
in-game timing is exercised.

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
independently. This is a contract seam; the production wrapper and publisher
seams are covered separately below, while no live Minecraft world, integrated
server runtime or transport exercises it.

### Shared source sync publication contract

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

### Headless spatial radial menu

`SpatialControllerTest` covers the headless radial menu session: a centre
release inside the deadzone abandons and release is single-use; a focused leaf
commits exactly one action while reserved, disabled, navigation and
actionless-branch entries never commit; sector starts are inclusive of the
following half-open sector including the 0/360 wrap; outside sectors without
crossing the deadzone report an outside no-action; a disabled branch focuses
but is never entered by dwell; a focused branch is entered by dwell and a
focused Back dwell pops exactly one level when hover is disabled; a pop arms a
fresh-stroke requirement so a stationary pointer cannot re-enter until a new
full stroke plus dwell; hover return pops one level and reports delegated
progress without cascading; hover mode suppresses the ordinary Back dwell;
physical deltas accumulate and rebase starts a fresh trail; cancel clears the
session idempotently; an externally composed submenu is pushed at a given
origin with a rebased pointer and gains one automatic Back entry whose
localized label is non-empty and whose navigation release remains actionless;
and an explicit Back is centred on the parent bearing with equal non-root
sibling spans that tile. The same headless controller model covers
qualified-turn entry at a corner and reverse-stroke retrace, including that a
disabled branch cannot be entered by turn and a row-anchored external menu does
not retrace. Incremental one-pixel reverse samples and an exact two-pixel
reverse sample accumulate to the unchanged net parent-directed return distance,
where the sample reaching it returns one level to the root and further
reverse samples leave the root active; zero, tangential, outward and
out-of-corridor samples do not trigger a return, while strictly parent-directed
progress within the corridor can return once the net-distance threshold is met,
and hover mode owns Back instead of retracing. Each
pushed level exposes the radial entry choice that opened it, the root and an
externally pushed menu expose none, and an ancestor keeps its original entry
edge.
The same model owns the viewport projection: the logical pointer is clamped to
the inclusive GUI pixel centers of the current viewport under the frame's rigid
translation, including every edge and corner and fractional pan offsets, while
raw finite input saturates without overflow. Only effective movement counts:
outward input spent at the boundary adds no overshoot debt, trail point or
timer progress, a reversal moves the pointer at once, and non-finite deltas
leave radial and external state untouched. The root-floor case protects
pending dwell; separately, a resize or pan correction cannot complete pending
dwell or enter a branch. A non-root held-Back regression confirms that
projection-only leave/re-entry and saturated zero-effective movement cannot
re-arm an already-fired held Back, while effective physical leave/re-entry and
qualified-turn movement preserve legitimate rearming. Rebase, start, end and
Back snapshots stay inside the published viewport. The programmatic
circumference placement is still covered as its own seam: the
placed pointer keeps the frozen inclusive-deadzone radius and admits no focus,
a stationary release abandons, the reconstructed absolute point stays inside
the same inclusive deadzone predicate including at nonzero and mirrored
origins, an unreachable placement stays pending without travel, trail growth or
timer progress until the projection can show it, resolution pays no fresh
stroke by itself, a partial new stroke cannot pay the pop's fresh-stroke
requirement while a full stroke plus dwell re-enters, a fresh effective
physical movement cancels a pending placement instead of chasing it, and
resolution or projection cannot re-arm an already-fired held Back or append a
joining stroke while a deliberate physical leave and re-entry still arms one
return. User returns replace that placement on every path (explicit Back,
retrace, dwell, qualified turn, and hover) by admitting the trigger's confined
pointer and rigidly translating the returned parent's origin and every other
retained menu origin and parent/row anchor so the returned parent's centre
lands on the pointer, which itself stays at the trigger; exactly one level is
removed, retained parent-child vectors and entry choices survive, a nested
return translates all ancestors and an independent row anchor, a zero-distance
alignment still reports one cumulative user-return notification, and a
stationary release on the returned centre abandons even when the old root
direction was an action. The return seeds exactly one trail point with an
advanced epoch and clears stale focus. A programmatic return keeps the old
placement and focus and reports no notification, root pruning preserves an
already-translated placement, a user return discards a stale pending placement
before projection can resolve it, and only a new start resets the cumulative
notification. Incremental one-pixel reverse samples return exactly one level
at a non-root parent and cannot cascade without that level's own full reverse
distance. External relayout is also covered: it restarts a pending hover dwell
and a hover-disabled Back dwell at the new geometry without pointer, trail or
timer movement or an immediate pop, leaves an unchanged placement's running
deadline intact, re-evaluates focus without supplying branch travel, clears a
stale turn corner, cannot pay a return's fresh stroke while preserving real
partial travel, is inert for an inactive root or ordinary radial menu, and
preserves the shared held-Back block.
That model also exposes the renderer-facing sample identity that separates
effective input from projection and navigation: only effective pointer movement
advances the sample sequence, a saturated callback is not a sample, a projection
or resize correction advances a separate alignment identity and fences the
sequence without adding a trail point or a synthetic stroke, an explicit rebase
changes the discontinuity identity even at coincident coordinates, and entering
or returning from an external menu starts a fresh identity. The frozen per-hold
bound caps the published trail.
It is a standalone model seam: no native screen, renderer, input callback,
configuration, or world-candidate source is exercised.

`SpatialSelectorSessionTest` covers the production headless selector facade:
the frozen root menu and its allowed typed intents (with a whole-marker choice
disallowed for the entity-block fixture rather than by a universal disable),
real typed choices inheriting their catalog Ping Type outline color while
untyped choices keep a null color, supplied precise allocations that are never
retargeted or reused, broad
precise slots keeping their candidate's canonical target type while a foreign
canonical type is rejected, single-use release,
unavailable/reserved/branch releases producing no action, next-capture
toggles that do not mutate the frozen target, actual property values and
annotations without fixture data, the inventory submenu anchored at the logical
selected row with Back preserving selection, direct list release choosing one
opaque reference without count authority, partial-batch ordering and freeze
rules with explicit zero retained, list Back-hover ownership and one-level pop,
lifecycle and unavailable fences discarding old references and late data,
missing counts staying unknown while an explicit zero remains selectable, and
opaque property keys not aliasing another annotation action. It also covers
captured-face admission and external-block face retention: a captured face is
accepted for the block and entity-block target types and rejected for entity
and location targets; an ordinary external-block target opens the selector and
releases exactly its own captured target, hit and provider-local face, present
or absent; and a live precise external-block candidate keeps that face through
publication, paint and release while the painted context keeps the live ray.
It also covers the declared content navigation groups: a declared group renders
as a parent whose focused release commits nothing while an unrelated top-level
row keeps its established action; grouped member children keep their own
references, observed values and typed default release; Back returns one level
and keeps the parent identity; a declared group with no visible member choice
is omitted instead of becoming a dead selectable root; a nested group path
renders its subgroups under the declared parent and releases the nested member;
and a property group path whose declared ancestor chain is missing is rejected.
The session keeps the list's logical pointer fixed on both axes: physical list
samples never move it (only viewport confinement may still correct it),
vertical glide and bounded horizontal side progress accumulate independently
from the same admitted samples, the per-sample vertical-intent cone ignores
only that sample's horizontal component while keeping its vertical component
and prior horizontal progress, and only effective filtered horizontal progress
can cross a side gate or physically release held Back. Pointer alignment from a
pan or resize still does not glide, select, add a trail point, advance hover,
trigger automatic side navigation, or release stale item focus; the corrected
pointer is the next gesture baseline without correction travel. The frozen
session settings deadzone reaches the controller read model
unchanged from the value consumed by the renderer, including a non-default
radius. The shared item-to-list Back latch
honors the accumulator distinction: alignment after item return cannot
cascade, but effective physical leave and re-entry permits a later one-level
return. A user list Back is covered for both Back styles and both entry sides:
exactly one level returns to Content with the pointer staying at the trigger
and the returned Content centre rigidly translated there, no immediate focus
and a single trail seed; stationary frames cannot reopen the list or navigate,
and a deadzone release abandons; a full deliberate stroke plus dwell reopens
the list while a partial one cannot; a distant or resized exit never resolves
through the old circumference placement and fresh physical movement continues
from the returned centre; a programmatic item revocation applies no such
translation; an item-clearance update repositions an active item menu with its
frozen row anchor while preserving pointer and trail without adding hover
progress, is inert before entry or for an unchanged metric, and rejects
non-positive or non-finite
values; and the item-submenu Back return stays on the list without a Content
placement. Content intent is covered per mode: OFF turns an otherwise
selectable property into a terminal Attention-annotated leaf keeping its
original reference, observed value and the whole-marker Target-Type default,
disables a property without an authorized Attention instead of inventing a
fallback annotation, makes a direct selected-list release request the
Attention item annotation while preserving the whole-marker default, and keeps
a Forward stroke inert; ON exposes the Forward affordance and the frozen
retained row key while the paint model keeps the full window, Back focus
removes only direct-release committability and not the Forward affordance, and
an empty list is never committable under OFF.
`NativeSelectorInputTest` covers the headless input normalization seam: only
accepted successive virtual window positions become GUI travel; opening,
resize, focus loss, and reopen reprime without phantom travel; settled-coordinate
priming preserves the next real movement; actual native ownership gates movement
and every scroll callback independently of the vanilla grab flag; and scroll
normalization occurs once only while owned without moving the pointer, and
endless alternating owned GUI callbacks around the settled baseline leave the
fixed list pointer, trail, selection and hover progress untouched. Through
the selector session, owned GUI-scaled native vertical movement continues list
glide at confined edges and the first real reversed movement applies immediately
after a scale handoff, while unowned samples and handoff priming add no travel.
Fractional native samples — one window pixel being half a GUI pixel —
accumulate through the real input adapter into exactly one return to the root,
with the pointer stopping at the net return distance and further reverse
samples leaving the root active.
These are headless model and input-normalization seams: no native screen,
renderer, input callback, world-candidate source, or in-game integration is
exercised.

`NativeSelectorContentTest` additionally exercises the production content bridge
against a real headless client inventory session: opaque selectable references,
explicit zero and row quality, folded-row replacement resetting the list, close
cleanup, reentrant abort during open without reviving the request, and safe
conversion of a null label to an empty component while unknown non-empty keys
keep their translation fallback. It also covers the kinetic stress/capacity
content projection: a locally observed pair needs no preview request and
formats with the same projection capacity without adding a request, an
authorized but locally missing capacity is requested exactly once and its
accepted response enables the percentage context, and a denied capacity is
neither demanded nor inferred, so the reader is never asked for a field outside
the accepted mask. It also covers the root kinetic speed projection from a
locally observed record through the bundled English localized RPM display: a
driven and a reversed signed effective RPM keep their own value, a stopped
overstressed record shows its observed zero rather than its nonzero
theoretical RPM or record size, nested typed rows keep their typed scalar
labels, a record without a numeric `effective_rpm` keeps the established
generic record summary instead of an invented RPM value, and the local
projection adds no preview request. The bridge also covers the content
ping-type policies: the
inventory item menu uses the inventory item policy including its
inventory-only take type while a regular property keeps the property policy
without take, and the legacy constructor keeps passing the shared policy; and
an automatic Back entry's localized key resolves through the content adapter
as a translatable component. The bridge also covers the block-state navigation
group over a real headless selector session: a locally observed block-state
record becomes a pure navigation group whose record root is dropped from the
selectable projection and whose grouped rows are declared; the group parent is
an actionless branch with no typed release of its own; its state children keep
their observed references, values and default Ping Type outline color with
typed releases, including a nested row releasing under the selected Ping Type;
Back returns one level and the frozen parent identity survives; an empty
record stays a disabled row rather than a dead selectable root; and an
unrelated record root keeps its ordinary selectable branch. The bridge also
covers the Create namespace hierarchy over the same session: every
create-namespace field and its record descendants join the localized Create
properties group, whose label resolves through the real language fallback
including its written ellipsis, while only the kinetic stress, capacity, and
available-capacity fields nest under the localized Stress group and the
network and overstress flags and every other Create field stay directly
selectable under Create; each group parent is an actionless branch whose
focused release commits nothing and carries no Ping Type color; the grouped
Stress children keep their own references, observed values and typed default
release; an empty Create membership declares no group; the declared hierarchy
keeps its identity across revisions; and Back from the Stress subgroup
returns one level to the Create menu with the parent identity preserved. An
exact child-denied kinetic field is omitted from both the locally observed and
server-backed menus while its root speed record stays selectable and still
formats RPM. This is client-session/facade coverage, not native input or HUD
evidence.

`SelectorToggleLabelsTest` covers the client-side settings-toggle label
resolver headlessly: each of the three target-selection toggles reads only its
own live concern (`markFluids`, `markBlacklistedTargets`,
`passThroughTransparentBlocks`), every resolution re-samples the supplied live
policy, the toggle name and separator stay plain while only the ON/OFF state
word carries the status color (green for ON, red for OFF), and a non-toggle or
unknown choice returns no label so the caller keeps its own resolver. It is a
label-resolution helper seam: it does not exercise the real selector draw,
renderer wiring, or GPU submission.

### Native client interaction runtime

`ClientPingRuntimeInteractionTest` drives the production interaction runtime
through headless world, cursor and clock ports: press-time ray and target stay
frozen through camera movement and asynchronous completion; a pending
completion cannot open a menu or start a held preview; release alone produces
exactly one default create; abort reasons invalidate a late capture and
synthetic release; a center release abandons without a cancellation while the
cancel branch builds the frozen cone lazily; list release sends `SELECT` before
the exact `CLOSE` and never falls back to a plain create; a long-held selector
never auto-closes and its release still selects the row; reset and late-preview
responses cannot revive or select; precise
release consumes the supplied selectable candidate; broad block and entity slots stay
distinct from the ordinary target and keep their candidate's canonical target;
property create preserves the address, observed value, annotation and
whole-marker type, and a denied property cannot
fall back to a plain create; toggles commit once; deferred compatibility
receipts gate the deferred capture; resize, invalid frames and screen disposal
reprime without synthetic travel; held preview forwarding and its late-response
fence; a late old capture cannot clear a new selector or its exact request;
rapid receipts backdate only time and keep the second physical ray; spatial
settings are frozen at press and an opened selector ignores elapsed time; an
invalid viewport cannot open; and a reentrant abort inside validation blocks
every release packet. A nearer same-identity precise contact without a face
preserves the ordinary press-time inventory face: the ordinary content branch
still opens with that face, its inventory selection remains enabled, and the
release selects without a fallback create. An external-block press with a
provider-local face opens the selector, sends that frozen face as the inventory
preview read scope, and releases exactly its own external target.
It also covers a native-only ownership handoff without a vanilla grab-state
change: both runtime movement and scroll paths stop accepting selector input,
and re-priming after reacquisition preserves the next real movement.
A window resize with no delivered move callback, snapshot read or paint still
recomputes the confined focus before an immediate release, so the release
resolves the new confined sector rather than the pre-resize diagonal, and a
saturated native raw baseline continues from the boundary with its first
reversal sample applied at once.
The runtime also freezes the trail preferences with the hold: the controller's
retained-history bound and the paint style's trail visibility, length, and
opacity all come from the press-time snapshot, a live edit during the hold
changes neither the retained history nor the painted style, and the next hold
adopts the edited values with the painter and controller sharing one bound.
`ClientPingDispatchReceiptTest` covers actual-send receipt, transport-failure
rollback without queueing or refund, empty-tracker restoration and preservation
of a newer reentrant route receipt.
`WheelMouseCaptureLifecycleTest` covers native `DISABLED` ownership with the
vanilla grab flag false through a recording cursor seam. Its fixture separates
confined physical coordinates from unbounded virtual callback coordinates:
movement beyond all four window boundaries produces exact scaled GUI deltas
without modeled physical escape or camera motion. Acquisition and handoff
priming exclude transition resets while counting the first real movement once;
delayed cursor entry after `DISABLED` acquisition, with no synthetic position
callback or immediately before the first real callback, preserves the settled
baseline and counts that movement once. Repeated synchronization neither resets
the pointer nor drops travel. It also covers a normal close reclaiming the
vanilla grab exactly once even before idle phase publication versus an initially
free pointer closing without a grab;
clearing pending look at close; a screen-owned cursor never being acquired;
every owned scroll callback being consumed, including horizontal-only and zero
vertical deltas, while transition callbacks remain unowned; and ownership
fences at focus loss, incoming-screen HEAD disposal, window replacement, a
different native-mode owner, or a vanilla re-grab in the same `DISABLED` mode.
A fresh vanilla release can reestablish ownership without a normal-mode cycle,
while callback-observed handoffs and reentrant acquisition, disposal or restore
cannot revive stale claims, overwrite a newer owner or cause a second re-grab.
These are recording-seam checks of the
[mouse-ownership contract](../architecture/picking/wheel.md#mouse-ownership).
`WheelMouseCaptureTest` covers the pure lifecycle action matrix and press-time
grab guard across every interaction phase and screen/focus combination: a
vanilla mouse press cannot auto-regrab an open selector while screen-open and
unfocused presses keep the vanilla grab.
`NativeSelectorMixinContractTest` checks the mapped `MouseHandler` callback
ABI, accumulated-look field types and scoped release handoff hooks, and the
mixin hook descriptors — including a dedicated non-cancelling cursor-entry
delegate separate from the explicit reset delegate, the release redirect to the
actual vanilla position-before-mode call and the narrow press-time auto-grab
guard preserving raw press and release delivery to the key mapping — and that
each effective loader manifest registers the hook bridge exactly once on the
client side. Runtime cursor entry validates native ownership and frame validity,
clearing/re-priming the baseline when either is invalid.

These are production-orchestration tests over recording world, cursor, clock
and transport ports; they are source-reviewed test existence, not a live client
run, mixin application, native Windows/GLFW cursor or desktop behavior, GPU
submission, or multiplayer verification.

### Inventory preview and tracking foundation

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
deferred tightening pass while keeping the cumulative change and fold summary,
a tighter ceiling mid-pass restarts the walk from the first retained slot, the
still-referenced encoded page stays charged until its round is released, and
closing a consumer during a deferred pass leaves no workspace and rejects
later work.
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
periods round-trip exactly including their upper bounds.
`InventoryBackendTest` exercises the production
backend orchestration headlessly: one bounded preview selection creates once
and the first tracking observation reuses neither the preview snapshot nor its
count; invalidation, recovery and hard expiry leave marker lifetime untouched
and stop probing after the deadline; revoked views purge recipient queues
without changing the frozen audience; component folding is decided afresh per
preview/recovery and a fresh resync drops only that recipient's fold; a stale
selected item is rejected at one bounded witness without an automatic retry; a
failed prepared create releases its sidecar reservations; an invalid
preview releases its physical round so unrelated work can progress; positive
resync periods at the integer maximum stay exact in an existing session; and an
actual resync at those periods rejects an immediate repeat without overflow. A
superseded oversized preview selection during a pending cap reconciliation is
answered with the recorded bounded `REJECT` without consuming the fresh preview
attempt, so the fresh reference after rebaseline still creates from the same
preview; a cap-induced fold carries the last completed all-ID total into the
retained completed projection instead of the exact selected count, and new
recipients initialize from that folded state.
`InventoryReconfigurationTest` drives the whole live cap-change flow through
the real backend, publisher, wire and client endpoint: a higher configured cap
captures and delivers the full retained display; lowering gates item data until
the updated offer hands off, then rebaselines preview and tracking; a
superseded lower offer cannot unblock after the pair returns higher; partial
stripping keeps the exact variant key and count while complete stripping folds
all variants using the last completed all-ID total; deferred reprojection keeps
the gate, recovers, and rejects a stale select; pending-cut and cap-fold resync
cases run through the production path; multibyte displays are measured in
UTF-8 bytes; and the deferred-pass cleanup assertions execute unchanged in the
heavy and combined runs.
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
never retried after a callback failure.
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
a same-sub-level double chest keeps the canonical alias and per-member NBT
without replacing the original external target, release during a contiguous
save publishes no atomic snapshot, committed evidence invalidates on point
relocation or release while a fresh existing recovery recaptures, fresh world
and membership closures are used across ticks, a partial snapshot rejects a
member topology change without a mixed tail, a committed locator refresh keeps
the binding while a descriptor scope change or released capture is invalid,
frozen face, lock and loot safety hold for external chest NBT, a provider
lookup or last-metadata revocation cannot reach a content read or an
enumeration handoff so a stale handle cannot replay revoked contents, a
metadata-denied indexed step charges its attempt without publishing a zero and
a fresh handle recovers, an indexed enumeration re-checks each read before
visiting content, and a mismatched detached candidate or wrong resolver
binding is rejected before any world read.
`InventoryMinecraftSnapshotCaptureTest` drives the production view and NBT
capture algorithms against bootstrapped vanilla block entities: a single chest
and both double-chest members are captured through the real vanilla save path
once per capture while the live selection witness still reads normally; the
captured topology keeps the original hit half and per-member local slot zero;
every member is lock- and loot-gated before the first save, and a topology loss
before or during capture publishes no snapshot; ordinary contents changing
after capture leaves the frozen snapshot and source validity untouched; a
non-container provider without an explicit layout stays live; malformed
explicit descriptors and a missing target member fail closed, an unsupported
schema stays on the live route, an ambiguous recognized candidate is
unsupported rather than guessed, and a malformed recognized candidate is a
read failure; and a frozen worldly face witness preserves the sided slot order
and invalidates on a mapping change. `InventoryNbtSnapshotTest` covers the
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
preview-to-tracking handoff with recording transports: a preview candidate uses
a request-scoped quota without a marker key or provider reference, a valid
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
and complete size-driven stripping claiming fallback only with size evidence.
`InventoryItemDisplayProjectionTest` covers the configured display cap: a
configured cap preserves a display beyond the default bound and keeps the size
evidence when complete stripping is required, reprojection keeps fitting
displays and falls back honestly, the cap changes neither canonical identity nor
its bounds, a minimum cap projects real fixtures and rejects smaller caps, and
monotone tightening reprojects retained displays and never re-expands them.
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
unknown or oversized data falls back to the registry key and bare stack without
raw text; an identity mismatch between the decoded payload and the supplied
item id falls back; the decoded stack keeps enchantments, glint override,
custom data and custom model data and is always count one regardless of the
encoded source count; a normal variant keeps its stack-dependent localized name
while custom and item-name forms use the chat composition with flattened plain
text; the bounded access-order cache refreshes recency, evicts the least
recently resolved variant under pressure, stays within its entry and byte
bounds, resets on registry or explicit reset, and never hands out a mutable
cached render stack; same-id variants stay distinct while identical payloads
reuse a template without aliasing; transmitted component removals survive the
retained naming and render stacks; and real vanilla delayed and warmed-inline
unresolved profile callbacks resolve owned working stacks without changing the
retained transmitted profile, name or display. `SpatialInventoryRowDisplayTest`,
the inventory-bridge cases of `NativeSelectorContentTest`, and
`SpatialInventoryLayoutTest` carry that display through the native bridge into
the painted row projection: encoded display data survives both compatibility
projections, the row hands native rendering an owned count-one stack carrying
the transmitted components that cannot corrupt the next frame, custom names use
the shared quoted composition, and missing data falls back to the bare stack
and registry key without raw data. These are headless CPU, codec and
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
`InventoryGestureTest` covers the fixed-pointer list accumulator: physical
samples never move either logical pointer axis while vertical glide uses the
frozen row threshold with split and batched movement independent of starting
or aligned pointer Y, rebase resets the glide fraction while preserving
selection and window, reversals in both directions discard opposing sub-row
glide travel, the inclusive vertical-intent cone ignores a sample's
inside-or-exact horizontal component in both directions while preserving prior
accumulated progress, out-of-cone dx accumulates, vertical-only samples never
navigate or cross either side gate, the side-exit bound discards overshoot and
allows immediate reversal without debt or reaching the opposite gate in one
pixel, glide suppression outside the hint corridor derives from the accumulated
progress rather than an imported pointer, Back-hover suppression and
wheel re-enable react to effective accumulated progress, and wheel unlock
uses its separate signed, unscaled travel with only the unlocking sample
beginning glide; ordinary scroll keeps its independent signed fraction across
reversal and glide. The updated calibration tests an explicit neutral factor
against the intrinsic baseline and explicit faster and slower factors against
their relative row thresholds, independent of the configured non-neutral
default; this records calibration coverage, not a numeric
tuning catalogue. The model also covers huge finite endpoint travel with immediate
reverse movement and no overshoot debt, and non-finite movement as inert.
`InventoryListModelTest` covers sorted initial rows and stable ordering, selection
and row-window clamping, and huge attempted row steps saturating without integer
overflow and reversing immediately. A frozen-panel translation moves only the
panel axis and glide baseline while preserving entries, selection, window,
direction, status, discovery order, the scroll freeze and the fractional wheel
remainder, and non-finite deltas are inert. These are model tests; the session and native
input coverage above exercises authorized glide against a confined pointer.
`SpatialInventoryNavigationIntegrationTest` drives the headless native-input
adapter and a real selector session through production inventory layout,
viewport preparation/retargeting and frame rendering with a recording painter.
It covers list entry from reachable viewport corners, centering pan and scale
handoff, vertical glide and reversal at confined edges in both directions,
normal and reduced motion, both forward-side orientations, selection painting
after a streamed view update, and item-Back return followed by resize while
preserving the selected key and row window and keeping vertical-only input on
the list. Session and gesture seams also verify that viewport correction
preserves partial glide, signed wheel-unlock and ordinary-wheel fractions, wheel
lock, selection, trail and the held-Back latch. The same fixture also covers
the user list Back through the real native-input and
session path with viewport preparation and retargeting: both Back styles,
normal and reduced motion, an oblique inventory-slot entry, other GUI scales
and deadzone radii, the pointer staying at the trigger while the returned
Content centre is translated there, one trail seed, stationary frames that
cannot reopen the list, a deadzone release that abandons, and a full
deliberate stroke plus dwell required to reopen the list; a resize never
resolves through the old circumference placement and fresh real input
continues from the returned centre. It also covers OFF content intent through
the real path (ordinary rows, no Forward entry or retained key, a direct
release carrying Attention) and an item-clearance update that repositions the
active item menu without pointer or trail travel. It also covers the ancestor
breadcrumb rule through the real path:
the root and Content ancestors keep only their entry breadcrumbs and the
Content Back navigation while the hidden root and property options are not
planned, the logical navigation choices remain complete, and the inventory list
panel stays painted under the item submenu. The fixture does not instantiate
the complete `ClientPingRuntime`, a GLFW window, `GuiGraphics`, or GPU submission;
native-window/gameplay confirmation of inventory-axis reachability after the
item-Back resize handoff remains pending.
`SpatialOverlayRendererTimingTest` covers renderer timing models.
`SpatialOverlayTransitionsTest`,
`SpatialInventoryLayoutTest`, `SpatialOverlayRendererStyleTest`,
`SpatialOverlayRendererSectorTest`, `SpatialOverlayRendererBorderTest` and
`SpatialSquareProgressTest` cover the CPU
overlay models: appearance,
exit, reappearance, retargeting, reduced motion, rewind and bounded churn, including a synchronized non-input
relayout that commits Cartesian anchors together while scale and both coverage
channels keep easing, and a shared geometry rebase that keeps detached paint
and the current follower on one center without advancing time; row
layout and coordinate conversion with status separate from rows and unknown
counts; caller style overrides, legacy font scaling, alpha arithmetic, the
independent wheel-underlay and target-frame opacity split, the per-layer gate
that keeps only the transition-faded chrome when both opacity preferences are
zero and drops every underlay or text-bearing frame, and the Back progress path
tracing the frame's actually painted pixel extents and never leaving them;
half-scale child-orbit bounds, the non-root submenu-radius multiplier with the
root distance and legacy forms unchanged, and selected offsets; the square
progress walk's last-painted extents and the inventory footer's immutable
back-affordance state; and sector scanline geometry, adjacent-sector tiling,
shared disabled/reserved alpha, and paint ordering;
`SpatialOverlayRendererGuideTest` covers the radial center guides headlessly:
one divider per true shared boundary, each emitted exactly once, across the
root's unequal spans, equal spans including Back, and the pair wrapping past
zero, while a single full disc or a gap between neighbors gains no phantom
divider; the closed deadzone circle as a clockwise polyline at the frozen
radius, with a non-positive or non-finite radius yielding none; non-default
frozen deadzones reaching that geometry through the controller read model;
guides placed at each menu's own origin under the rigid translation,
with an ancestor menu's guides dimming with that menu and a child menu's guides
on the child origin and a radially entered child records the radial entry
that opened it; and guide alpha following only the menu's own transition
fade, independent of both opacity preferences and of focus, selection or
release, with reduced motion and the exit tail handled by the shared transition
model, painted after the sector underlay and before the inventory panels. Every
endpoint is checked against geometry computed independently in the test; no
painted stroke, GuiGraphics or GPU submission is claimed.
`SpatialOverlayRendererFocusScaleTest` drives the production frame path through
recording paint and font-metrics ports: an eligible focused action or branch
selects the focused emphasis and sibling de-emphasis scales through the
existing finite interval from the displayed resting pose, a new follower starts
within its appearance fade, Back, disabled, reserved and deadzone focus keep the
established scales with no emphasis mode, an actionless branch focuses the same
way, reduced motion snaps, and inventory-list rows keep their own scale.
`SpatialOverlayTransitionsBackgroundTest` and
`SpatialOverlayRendererBackgroundTest` cover the independent
background-coverage channel: historical constructors delegate the channel from
the foreground alpha, bounds validate as a normalized channel, appearance
starts at zero coverage on its own finite interval, retargeting eases from the
displayed coverage and a separate alpha edit never restarts it, exit fades to
zero while detached data keeps its deadline and reappearance resumes from the
displayed coverage, reduced motion snaps and drops the exit tail, rewind cannot
rewind the displayed coverage, resting coverage uses the fractional base
coverages, eligible focus applies the confirmed sector and box gains to the
focused entry and its siblings, Back, disabled, reserved and deadzone focus
keep every resting gain, ancestor levels apply structural dimming and omit the
ancestor breadcrumb sector without repeating the foreground unselected factor,
the final byte rounds exactly once, each opacity knob reaches only its own
background layer, background-only paint is admitted but never acknowledges,
frame backgrounds fade with their own channel through a detached exit, the
active list and an empty active list emphasize their panel and header, the
selected row emphasizes only while direct release is committable with no Back
focus, retained-row filtering keeps only the entered item row while the
panel/window model stays complete and detached sibling tails are suppressed
before the return restores the window, a selection change draws the old
overlay's fade and the new overlay's growth without ever filling a
never-selected row, and the row-overlay draw honors reduced motion, opacity
zero and quantized zero.
`SpatialOverlayRendererAncestorBreadcrumbTest` drives the production radial
planning and frame seams through recording paint and font-metrics ports: each
ancestor of the active path plans only its entry breadcrumb and Back
navigation, with its other option frames and sectors, including disabled and
reserved siblings, filtered from planning; each retained breadcrumb frame and
its entry/exit
anchor sit at that ancestor's own origin with no pointer ray, and the
breadcrumb's own sector is omitted while the ancestor Back keeps its frame and
sector; a lone breadcrumb contributes no
hidden divider while the defined centre guide stays; entering fades hidden
siblings through the ordinary exit and returning fades them back in, reduced
motion snaps both ways, a disabled sibling's frame and sector settle hidden and
restore with their disabled styling, including a mid-fade restore, a hidden
ancestor never acknowledges and a breadcrumb is never an active
acknowledgement, and the logical stack and selection geometry are never
mutated.
`SpatialInventoryClearanceTest` covers the item-menu clearance metric and
paint geometry: the pinned viewport uses the independent maximum of the
outer-sector coverage bound and the measured toward-panel Back frame bound
including the selected push, a short label stays at the sector bound while a
long localized label widens the distance, the frame follows the option text
scale and never the inventory scale, a taller font line grows the measured
extent, intermediate resize dimensions stay finite and never shrink, the full
Back frame stays clear of the panel in both orientations, actual first, mid and
settled paint keeps the sector and measured Back frame disjoint through
enlarge and shrink with the first frame committing the new panel-to-menu
clearance, the follower ray, guide, connector and actual title acknowledgement
stay on one geometry, a longer Back label at the same viewport relayouts the
whole pattern before the new frame paints, a resize rebases a detached prior
panel with the same pattern instead of leaving its old GUI center, and invalid
inputs are rejected.
`SpatialOverlayRendererFollowerTest` additionally drives list and item-submenu
frames through the production renderer: an active inventory list hides the
crosshair even when frame opacity is zero, an item submenu at Back/deadzone uses
the radial pointer rule rather than inheriting list hiding, an eligible visible
item follower can hide it, and returning to the list or leaving for the parent
restores the applicable behavior. The list trail remains independent; inactive
exit paint does not retain list-specific hiding. These checks use recording
paint/metrics ports, not `GuiGraphics` or GPU submission.
`SpatialVisualTrailTest` covers the production shared-history model headlessly:
one bounded history serves both the logical pointer and the certified
follower; native raw pointer batches are consumed once and follower-era samples
never replay on return; an ownership change or an interrupted owner starts from
the last displayed endpoint through a finite handoff that reaches the current
target, while a settled owner samples its target without a second lag filter; a
projection correction fences old points while keeping post-correction callbacks
and adds no stroke, an explicit rebase starts fresh by identity even at
coincident coordinates, and a rigid pan alone appends nothing; reduced motion, a
hard reset, a quick reopen, a rewound clock, and rapid retargeting stay bounded;
and a missing departing paint freezes the history while `clear` empties it.
`SpatialOverlayRendererTrailTest` drives the actual production paint/commit
order with independently recorded node centres: the painted payload is an
immutable history taken before this frame's node paints and committed after
them, painted above the sector underlay and guides and below every
text-bearing paint, so the history committed after this frame's paints enters
the next frame's payload, with handoff endpoints easing toward the certified
painted centre; only a successful paint of the actual animated pose with
a visible title frame admits a follower sample, while a failed paint, a
disabled or Back focus, an omitted active node, or an actual offscreen pose
cannot certify a predicted owner or borrow a detached duplicate; an inventory
list hides every trail paint including a prior tail and its rows never own it,
a list-to-item rebase is not a joining stroke, and the item radial keeps the
shared history below text under the ordinary crosshair rule; a fractional
projection change or
rigid pan appends nothing, and trail visibility and its independent opacity
reach only the trail; an ordinary release fades with the exact departing
follower key without acknowledgement, connectors, or a snap to the origin, and
a new hold or reset discards the history; the retained bound holds under rapid
retargeting without changing paint admission; and overlapping rounded-square
pixels take maximum alpha with no duplicate coverage. An interrupted
pointer-to-follower or follower-to-follower handoff survives an ordinary
same-clock release and continues its original finite interval toward that
departing key's moving painted pose, never the outgoing duplicate's, while a
missing departing paint retains the displayed history and a later paint resumes
the same interval without retiming; only reduced motion, a hard reset, or a new
hold clears it. The release frame still paints the previously committed
immutable history, and a GUI resize during the exit tail reprojects from the
actual detached trail origin, so the committed endpoint matches the recorded
painted pose instead of being displaced by the new GUI center. These are
production-frame recording-painter and CPU-model seams, not `GuiGraphics`, GPU,
GLFW-window, or multiplayer evidence.
`SpatialOverlayRendererBorderTest` projects the real facade's typed border
color in both focus states, keeps the untyped and legacy `ping:` action
fallbacks, and retains the detached color through the exit transition.
`SpatialViewOffsetTest` and
`SpatialViewOffsetProjectionTest` cover the selector's single rigid view
translation: exact endpoints, continuous re-aiming, an unchanged origin never
restarting its interval, reduced-motion snapping, an inert rewound clock and a
hard clear, and, driven through the real headless controller and selector
session, every active menu origin — root, pushed child, nested child and Back
return — projecting onto the GUI center while pointer, trail and menu vectors
stay rigid; the open inventory list centers on its active origin and an item
submenu keeps the selected row's logical anchor under the same translation; and
an inactive frame freezes the last displayed exit translation that a quick
reopen re-aims from. The interaction frame publishes that projection once, and
confinement, paint, release and resize read it unchanged: an intermediate pan
sample confines the pointer and the displayed pointer center stays within the
inclusive GUI pixel bounds even at fractional offsets; paint neither resamples
the pan nor mutates interaction; navigation inside the frame re-aims from its
single sample and snaps under reduced motion; a resize reuses the last
published offset; and the pointer chrome follows the published center rather
than a stale interpolated GUI center, while an exit-transition chrome state
keeps its detached paint geometry.
`SpatialOverlayRendererNodeLayoutTest` drives real
controller snapshots through the production radial-planning, node-layout,
title-clipping and external-preview wrapping seams: ordinary menus keep the
historical single-line bounds and floors; every node of a Precise menu — the
five type slots including a disabled one, and Back — shares that menu's single
frame extent budget, a candidate name never resizes or clips the title frame it
is previewed beneath, and a title or target font preference stays independent
while its row has room; a name at or below the 32-visible-code-point cap is
shown whole and wrapped in the shared Precise width instead of being cut at the
title button width, while a longer name keeps its first 31 visible code points
plus one ellipsis; legacy formatting does not consume that visible cap, an
ellipsis keeps the last retained visible glyph's style, and capping never reads
the overflow tail while a formatting-only detail allocates no area; capping and
wrapping preserve whole supplementary code points — including a pair split
across component segments — whitespace, explicit styles and legacy-resolved
styles, and a legacy-formatting preview with bold, reset and color sequences
matches vanilla's resolved visual order and painted styles, while an incomplete
legacy sequence never crosses a component boundary; the centered preview lines
paint outside and below the frame at the readable scale floor, a larger title
font preference can only reduce the preview's clearance and scale down to that
floor without changing its visible text, the smallest viewport with large fonts
keeps nonblank title, preview, Back and disabled-type text, and Back-hover
progress keeps tracing the title frame when a preview is supplied;
resting-layout frame-collision checks use the actual rounded bounds including
the disabled border's inclusive endpoint across rotations, submenu-size tiers
and viewports, the default plan keeps resting sibling previews separated at a
readable scale, and under the default 200% option font a full rotation
sweep with every slot selected in both languages, for mixed and fully available
menus, keeps resting previews clear of sibling frames and previews,
with the reported bearing-208 29-code-point name re-wrapping only after the
title frame and gap are reserved while keeping its text complete; the painted
preview retains every capped glyph, a vertically-fitting constrained title
clips rather than shrinking its font while a clipped label keeps its component
style, and the preview adds no opacity or admission layer while an exit
transition retains its detached component and its painted preview. An overfull
glyph-width stress case keeps the readable floor and applies no second
truncation; arbitrary resource-pack font metrics remain a gap. A selected
follower intentionally leaves the resting layout, so these collision guarantees
do not apply to its moving frame or preview. These are pure projection, layout,
clipping and production-frame recording-painter seams: no `GuiGraphics`, GPU,
native callback or gameplay frame is involved.
`InventoryTrackingRendererTest`
projects received tracking data into bounded renderer lines: only received tracking data supplies bounded item lines, explicit zero
is retained, unknown or invalid counts are never synthesized as zero, status
and grey state stay explicit, and the tracking annotation resolves the shared
localized name policy rather than the raw label. These are headless runtime, model and
production-seam tests over recording providers and transports, plus a headless
real-vanilla-NBT capture of a single chest and both double-chest members.
Snapshot atomicity is modeled for the snapshot-capable branch: the capture
test exercises the production save path against bootstrapped block entities,
not a live `ServerLevel`, chunk lifecycle, running loader or multiplayer
session, and the modeled branch is not a guarantee for every present or future
provider. Custom provider schemas and codecs beyond the modeled item shape,
provider wall-clock safety, live-world-backed provider safety, installed
optional-provider wiring, native HUD integration, live transport, rendering,
GPU submission and the manual inventory matrix below remain pending.

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

`InventoryConfigValuesTest` covers the twenty-one-leaf admin inventory value
model: each leaf's merge preserves every unselected current value and stays
independent of the rate-limit, channel, and sync fields; the two finite size
leaves are exact scalar multipliers with no unlimited mode, a selected pair is
accepted only when both leaves are present and legal, and an invalid merged
pair rejects atomically without touching unselected leaves; an invalid leaf
rejects the whole mixed update and unsafe snapshots fail closed without
defaulting; each leaf converts to and from its persisted member without
aliasing; missing, null, off-grid, and unexpected-unlimited values are unsafe
without substitution; and unsafe current or missing updates never report an
applied transaction. `ServerInventoryConfigPacketsTest` covers the strict v3
administration wire: the v3 request, update, and snapshot routes; every leaf
and explicit unlimited finite value round-tripping; the two finite size leaves
carrying one exact quarter-multiplier quanta value with no unlimited flag and
every legal grid value exact on the wire; truncation, old-prefix, and trailing
data rejecting without throwing; an invalid encoded leaf or malformed mode
rejecting the whole packet; and off-grid, zero and overflowing quanta, unknown
masks, missing inventory, nonpositive caps, and out-of-range scalars failing
closed without clamping. `ServerConfigVarNumbersTest`
covers canonical integer and positive correlation boundaries, truncation,
overflow, signed-width and noncanonical encodings, malformed update/snapshot
numbers failing closed before service mutation or view publication, and legal
maximum values retaining their exact meaning. `ServerInventoryDraftTest` covers
the admin draft model: per-leaf dirty, revert, and independent update-plan state; a malformed
leaf not erasing other invalid drafts or permitting partial updates; read-only
values visible but every edit inert; unlimited leaves retaining finite text;
decimal parsing and stepped boundaries; the session lifecycle across
navigation, permission revocation, and disconnect; and unsafe or stale
responses neither installing a view nor overwriting drafts. The two size leaves
use ordered slider positions with exact physical endpoints, the frame slider
caps the display slider and lowers it atomically with a dual dirty mark and an
adjustment notice, every size edit entry point reconciles the pair and none
emits an invalid pair plan, valid display text edits clamp to the
frame-compatible maximum, raising the frame never raises the display, malformed
size text stays visible and blocks the plan, the notice clears on revert and
snapshot refresh, and the size leaves have no unlimited mode and ignore
read-only edits. These are headless
model and wire seams: no `ServerCore` or live administration path exercises
them.

### Settings-screen navigation, catalog, layout and localization

The [configuration UI](../UI/settings-screen.md) scope, category, and page
behavior has model, catalog, geometry, and resource seams rather than live
screen evidence:

- `SettingsNavigationModelTest` covers the initial client overview, per-scope
  overview selection, each category opening its own leaf page within its scope,
  the immutable seven-category client and five-category server order including
  the client Performance and server Performance categories, the client
  Performance category opening independently of the server Performance
  category, the server Presentation category opening without
  ordinary-server permission, the
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
  including the trail controls in Wheel Appearance, the content-intent control
  in the client Input Interaction spatial group, the client scope without a
  presentation category, the client
  Performance category exposing exactly the precise capture period control, the
  server Presentation category, and the server Performance category exposing
  every inventory leaf exactly once and only in the server scope, with the two
  size leaves placed in the shared inventory budgets group after pending memory;
  it does not
  cover full-width layout flags or
  actual widget placement;
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
  in-game focus validation;
- `SettingsListFocusTest` covers the headless settings-list focus and reveal
  helper: a deferred-rebuild validation from the bottom explicitly reveals the
  first invalid row after the rebuild, ordinary mouse focus keeps its retained
  viewport, an already-visible validation does not reset the viewport, an
  unknown widget is a no-op, and a lower row or keyboard-origin reveal does not
  reset the top; it does not construct a native widget list or exercise mouse
  dispatch;
- `InventorySizeOptionTest` covers the size slider codec and keyboard seam:
  every ordered position round-trips through the slider codec, positions address
  physical sizes rather than wire quanta, the label uses the localized
  physical-KiB unit, a varying maximum is respected across rebuilds, read-only
  sliders ignore keyboard adjustment, held arrow keys keep adjusting through a
  slider replacement while an ordinary focus loss clears the adjustment mode,
  and real-width arrow steps accumulate to the next legal position across a
  rebuild while fraction-only moves never call the model; it is a headless
  helper seam and does not render a screen or exercise mouse drag or live widget
  focus;
- `OptionUtilsDecimalTest` covers the decimal option codec's confirmed range
  and grid: every confirmed grid step round-trips exactly through encode and
  parse without floating-point drift, and zero or an out-of-range value is
  rejected;
- `ClientConfigLocalizationTest` covers the eight bundled locale files and the
  settings, category-navigation, server-status, entity-block-mode, and
  target-gone resource keys, their required format placeholders, the category
  entrance ellipsis, the exact local feedback prefix, the restart-required
  external-list tooltips, the independent wheel and target opacity labels, the
  retired wheel timeout key's removal, and the complete spatial-selector
  preference key set with its wheel-options group heading, including the
  precise capture period, trail controls and tooltips, the content-intent label
  and tooltip, and numeric/boolean
  placeholder rules; point and percent unit placeholders are also checked for
  every bundled locale. It checks required key presence per locale rather than a
  full-translation guarantee, and does not
  render or assemble screen labels;
- `InventorySettingsLocalizationTest` covers the eight bundled locale files,
  the inventory settings label and tooltip key set, the performance category
  entrance, the inventory group headings, the shared inventory range
  placeholder, and the two size sliders' physical-KiB labels and adjustment
  notice with their placeholder rules; it does not render or assemble screen
  labels;
- `SpatialInventoryLocalizationTest` covers the eight bundled locale files, the
  renderer-referenced inventory keys, every inventory status label as a
  non-formatted literal, and inventory key-set alignment across locales; it
  does not render or assemble screen labels;
- `SpatialSelectorLocalizationTest` covers the eight bundled locale files and
  the native selector facade's real menu-label keys: every non-blank label the
  headless facade publishes exists and is non-blank in every locale and carries
  no format placeholder, the content bridge's Create properties and Stress
  group label keys exist and are non-blank in every locale, code-defined
  toggle and target-type identities are published as keys, and the spatial key
  set stays aligned across locales; it
  does not render or assemble screen labels;
- `SpatialSelectorSettingsLocalizationTest` covers the eight bundled locale
  files for the content-intent setting and the root marker-removal label: the
  label and tooltip exist non-blank without placeholders and differ, every
  locale's tooltip states next-hold application, only the expected two
  content-intent keys exist with no stale variants, and the stable root cancel
  key keeps its identity while every locale states marker removal, with the
  confirmed Chinese content-intent label and the English removal word pinned
  exactly; it does not render or assemble screen labels; and
- `PresentationSettingsLocalizationTest` covers the eight bundled locale files,
  every presentation key present, non-blank, and with an identical key set
  across them, the approved English presentation labels, key placeholders,
  localized names and descriptions for every builtin, Create, and
  inventory-manifest field in every locale, bundled translations for the
  recognized namespace headings, the read-only server status wording, and a
  property request phrase separate from the whole-marker Ping Type phrase in
  every bundled locale; it does not render or assemble screen labels.

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
  failure gates. It also covers the content dispatch boundary: candidate and
  committed resolution select only their own provider method, unknown or legacy
  providers and a new-API `LinkageError` fail soft without materialization,
  observation or reference allocation, and a candidate read never allocates a
  provider reference through materialization. Its fake tokens do not establish
  a real `ServerLevel`, loaded world, or live provider observation;
- `SableExternalBlockObservationTest` covers the production-called stable-entry
  observation helper selecting the committed tracking ID's current sublevel and
  local position, while leaving entry and reference maps unchanged, and failing
  closed for non-live references, wrong entries, invalid tracking-point state,
  and invalid coordinates. It does not load the Sable API or exercise live
  reflection, sublevels, materialization, persistence, or release;
- `SableBlockReadSourceTest` covers the production content resolver and
  stable-entry seams with a recording level-free access: a preview descriptor
  keeps the original candidate identity separate from the detached physical
  binding and logical anchor, malformed provider, dimension, registry or
  locator identities reject before provider access, positive sub-level
  containment, live identity and loaded state precede any content read, a
  member outside the resolved sub-level is rejected, and a committed read
  follows the current tracking point, leaves the reference and entry maps
  unchanged, and cannot resolve after the reference is released;
- `SableSupplementalRaycasterTest` covers the production transformed scanner
  with real Companion poses and native shapes but recording sublevel ports:
  translated and rotated hits behind a blocker or after a miss, world-segment
  range under nonuniform scale, detached pose evidence, distinct provider-local
  equivalence, raw-entry and shape-work admission, loaded/invalid-pose gates,
  selection-policy reuse, removed entries and absent-Sable safety. It also
  covers the provider-local native face: a real local hit's own direction is
  copied while a world approach maps to the provider-local direction after
  rotation, origin containment and synthetic, inside or unobserved contacts
  stay faceless, and a nearer same-locator or different-locator contact
  installs only its own face without changing the frozen ordinary face. It does
  not exercise an installed Sable client, live list/reflection access, server
  materialization, or inventory reads;
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

These seams do not load a Sable runtime or establish live provider reflection
(including the additive content membership discovery), materialization,
tracking-point reference lifecycle, live-sublevel membership and observation,
the client-side chunk and prediction gates of a provider-resolved physical
block, a live multi-member content capability, or refresh, multiplayer,
GUI, network or in-game behavior described by those topic sections.
The registry, content resolver and stable-entry tests are narrow
production-used seams with fake access and in-memory state, not live
server/world evidence.
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
purposes only; because each loader source set compiles the shared common
sources, an affected loader `build` is source-set and compile evidence for
those units, not runtime, transport, or in-game verification. Fabric and Forge
additionally carry a test source set whose headless native-wrapper seam tests
remain seam evidence only, not loader-launch, mixin-application or in-game
evidence. Public build
orientation and commands are listed in the
[repository README](../../README.md#install-build-and-verify); the tracked
[geometry pipeline](../architecture/geometry/geometry-pipeline.md) is the public
architecture entry point. Local agent instructions, when present, are
supplementary execution guidance rather than a public documentation prerequisite.

An inventory entry or static wiring test is not a Gradle execution record.
No separately configured lint, formatter, or static-analysis task exists; the
automated checks are the Gradle test tasks and the loader build and
artifact-identity tasks below. Final-state evidence for an implementation
change must identify the combined
tree, commands and results for the affected tests and loader source sets;
blocked or unrun tests, builds and artifact checks remain explicit gaps in that
change's report. Earlier runs do not validate subsequent relevant edits.

| Module or artifact scope | Task | Purpose |
| --- | --- | --- |
| `common` test source set | `:common:test` | Runs the common JUnit Platform tests, including shared behavior and integration seams. |
| `fabric` test source set | `:fabric:test` | Runs the Fabric JUnit Platform tests for the native platform wrapper seams headlessly. |
| `forge` test source set | `:forge:test` | Runs the Forge JUnit Platform tests for the native platform wrapper seams headlessly. |
| `neoforge` test source set | `:neoforge:test` | Runs the NeoForge JUnit Platform tests, including NeoForge-specific resolver coverage. |
| Affected loader source set | `:fabric:build`, `:forge:build`, or `:neoforge:build` | Builds the affected Fabric, Forge, or NeoForge source set and its loader jar. |
| All shippable loader artifacts | `verifyModIdentity` | Depends on all three loader `build` tasks, then inspects the expected Fabric, Forge, and NeoForge jars in their loader `build/libs` directories for fork identity. |

## Known automated gaps

The following gaps remain open until direct evidence closes them:

- the shared-source identity, opaque-payload, result, access-contract,
  snapshot-preparation, sync-publication declaration, counter/retained-memory
  and combined-admission primitives have the headless coverage inventoried
  above, and the production runtime, source-wrapper and publisher seams
  exercise snapshot preparation, capture and cross-period decode, allocation,
  shared scan-work and logical sharing, retained lifetime, invalidation and
  recovery through recording providers and transports. They do not establish
  live world or optional-provider behavior, real transport, GPU or multiplayer
  execution, provider wall-clock bounds, custom provider schemas beyond the
  modeled item shape, or a complete backend migration. The
  [shared-source](../architecture/presentation/shared_sources.md) and
  [inventory](../architecture/presentation/inventory.md) owners remain the
  contracts; source presence alone does not close these evidence gaps;
- the inventory preview-foundation, production runtime and publication tests
  cover per-client/target logical progress, snapshot preparation/capture and
  cross-period decode, accepted barriers, the tracking closed-watermark
  barrier, production selection admission order and publisher recovery/repair
  seams. Native input/HUD, live-world-backed provider safety, installed
  optional-provider wiring and live transport still require their own
  evidence, and no manual world, GPU, transport or multiplayer validation is
  recorded by those seams;
- inventory ordering/freezing, frozen snapshot continuity after ordinary
  content changes, empty-snapshot completion, explicit zero versus unknown,
  per-key revisions, client barriers and publisher recovery/repair have
  headless and production-seam evidence above, not live execution.
  Per-Ping/recipient baseline and resynchronization isolation are exercised at
  the production seam, and the double-chest NBT/cross-period preview,
  same-alias layout-change, restart/retirement and evidence-admission seams are
  covered by the tests above. The unknown-baseline window has a headless
  expiry/delete/RESYNC seam but no per-tick no-early-expiry proof or live
  roundtrip; the publisher's cross-target period accounting is headless for
  SNAPSHOT bytes only; the zero-heartbeat publication seam exercises watermark
  and repair without asserting the absence of a periodic HEARTBEAT. A maximum
  legal display value through the production server identity catalogue and its
  queue and budget admission remains unproven, as do transport-asynchronous
  heap retention and a live heap soak. Full all-variant folding, variant
  quotas, a real provider resolver and
  recovery-probe expiry, the hard stop at Ping expiry, a fresh capture after
  ordinary single- or double-chest contents change, a multi-period
  low-consumption quota completing stably, a real mod-provided multi-member NBT
  source or installed multi-member Create Vault, live-world safety invalidation
  and recovery, low-frequency limit/failure diagnostics, and real world,
  multiplayer, tick-order and GPU execution remain manual/integration
  scenarios;
- headless provider-confirmed external inventory coverage is inventoried above,
  including `SableInventorySourceTest` alias/source independence and the Create
  Vault member-access tests. No automated test exercises live provider
  membership, a real loaded sub-level, a real provider layout or capability, or
  loader/runtime integration;
- inventory administration and spatial-selector configuration have model,
  persistence, strict wire, draft, catalog and focus-helper seams; live
  administration, native widget input/focus, permission changes, persistence,
  scheduler consumption and selector integration remain unexercised by them;
- the shared client/server `entity_block` classification path end to end,
  including synthetic-face provenance from every optional producer;
- stale or display-hidden cancellation followed by authoritative rejection with
  no local fallback;
- real input-callback and render-frame behavior for rapid/deferred long-press
  compatibility;
- the content-intent toggle lacks a dedicated asynchronous press-edit-to-next-hold
  runtime fixture: the config freeze and the OFF/ON session behavior are covered,
  but a live edit between press and release is not;
- OFF-mode declared-navigation-group behavior and an inventory whose allowed set
  lacks Attention have no dedicated behavioral fixtures in either mode;
- asynchronous completion after lifecycle abort in a real input/loader context,
  including the token invalidation/ownership-clear boundary; the headless
  abort-plus-late-completion seams are inventoried above;
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
  change, child deny disclosure and preservation, refresh, feedback, and
  unsolicited broadcast behavior;
- direct runtime proof that valid legacy S2C locations and superseded marker S2C
  packets are presentation no-ops, plus live loader registration/network
  transport for the presentation snapshot and policy routes;
- a live two-sided presentation session: negotiation and reset over a real
  connection, per-recipient permission projection, a reset's child deny map
  pruning retained annotations and default references, policy changes advancing
  the view, and delivery of Basic/adapter values across the loader transport;
- external-refresh and client-receipt behavior under live conditions: those
  seams are headless, so no automated test exercises a real Minecraft or Sable
  world (logical anchor or render pose), a loaded sublevel, world-backed
  projection, source sampling cost, packet-level ordering or an authorization
  change between snapshot and delivery, or the rendered client marker;
- content receipt descriptor, projection, server initial-sending and client
  acceptance, pending content-chat completion and content chat composition have
  the headless model, codec, store and port coverage inventoried above. No
  automated test exercises the receipt descriptor over a live connection or
  loader transport, the Minecraft chat overlay or GUI delivery, actual sound
  playback, resource reload or locale switching, or multiplayer; the receipt
  lifecycle owner is
  [names and chat](../architecture/rendering/names_chat.md#content-receipt-lifecycle);
- a live property-upload admission round trip: the admission, recapture,
  override, and creation-rollback seams use fakes, in-memory state, or
  recording references, so no real server world, live provider, external
  materialization, or packet transport is exercised; property Ping Type
  override matching is model-tested against supplied tag IDs rather than a live
  registry or tag manager; rendered property HUD lines and frozen-mask retained
  values are client/provider seams rather than a frame observation. Content
  preview has the client-first, server-fallback and production-seam coverage
  inventoried above, but native property selection through authoritative CREATE
  in a live session remains pending rather than established by those tests;
- installed-Create presentation sampling and display remain unexercised in-game:
  automated seams cover the summary adapter's shape, work and version gates,
  detached registry-ID aggregation, the derived available-capacity projection,
  the constructed ASM field-shape gate for the cached network accessor, the
  real-loader gate decisions over the actual Create archive bytes, the
  Create-free cached-kinetic-preview receipt/network/accessor and per-field
  fallback rules, and the external-block `observeSource`
  position/demand/budget route and candidate preview entry with recording
  access, but no automated test runs the production collector's own per-member
  checks or captures a real Create collector's vault or tank controller/member
  structure or samples a live block capability; a real Create collector or
  handler, the applied Create cached-accessor and receipt mixins under a
  launched game's live loader environment, an installed Create block
  entity read, the Sable API, an in-game goggles/game-HUD label line, and
  manual evidence for the freshness of a last-synchronized cached network total
  remain pending, so a last reliable client sync may be stale until an
  authoritative recapture;
- the complete `ServerCore` operation ordering and channel/admission matrix in
  an end-to-end server path;
- same-ID marker creation after local record deletion in the full client
  runtime: production final deletion evicts retained presentation values under
  a session tombstone, so the late create is dropped, while the isolated
  marker-record seam alone would reinsert it as a new insertion with a new
  visual deadline; no automated test drives that production orchestration end
  to end;
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
- the additive content read source against a live Sable runtime: companion
  containment and unique-id membership reflection, live sub-level identity and
  loaded-member checks, a live multi-member content capability, and the
  content-read diagnostics; the client received-chunk and pending-prediction
  gates on a provider-resolved physical block and content preview through the
  wheel's content branch in a live client/server session, including the bounded
  server fallback and its GUI and network delivery, remain unexercised;
- the provider-confirmed external inventory source against a live Sable runtime
  and world: live membership and sub-level checks, a real provider layout or
  capability, member-gate revocation between operations, a live item witness
  and the preview-to-tracking handoff, and inventory reads on the three
  supported loaders;
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
| Wheel | Short and long press; every sector and border color; independent wheel-underlay and target-frame opacity plus the independent background-coverage channel and its eligible-focus emphasis; frozen target; location fallback; Back-hover dwell, one-level return, and leave/re-entry re-arm under real input; Back progress following the focused Back frame boundary; radial root caller geometry, non-root Back centring, dwell entry and fresh-stroke re-arm under real input; the target-selection settings branch's live ON/OFF toggle labels in the bundled Chinese and English locales, including a setting changed while the wheel key is still held, with the captured target and frozen policy unaffected and the entry border unchanged; the rigid centered view translation across multi-level submenus and a Back return, including small GUI scales, large fonts, the largest submenu size, and a GUI-scale or window-size change while the translation is still interpolating; inventory-list glide and wheel unlock at the viewport edges with the fixed logical pointer and the per-sample vertical-intent cone over bounded horizontal progress, direction reversal, the active-list versus item-submenu pointer visibility at GUI scales, post-item-Back axis reachability after resize, a user list Back aligning the returned Content centre at the pointer and requiring a full fresh stroke to reopen, the entered item row's paint-only hide and restore, and the item-menu Back-frame clearance through a resize's first and intermediate frames; and the Precise slot frames and their outside-frame name previews under real rendering, including a disabled slot and Back, a capped long name wrapped below its frame at its readable text size, sibling-preview separation, and resource-pack font extremes; the painted center-guide strokes at real GUI scales, including the closed deadzone circle and the shared-boundary dividers under an in-progress pan and a resize; the focused eligible action or property-branch follower and its connector from the deadzone boundary to the scaled title frame under real GUI rendering, including shared-trail ownership handoff and return, its historical paint below text, exit fade versus a new-hold or abort clear, and opacity-layer behavior plus pointer-edge, sibling-collision and game-feel assessment, plus the focus emphasis with sibling de-emphasis and the ancestor breadcrumb hiding and restoration under real GUI rendering; and, on a real Windows GLFW window, native `DISABLED` confinement and hidden physical pointer while the wheel owns input with the vanilla grab flag false, repeated long motion across every window border without escape or lost virtual travel, raw mouse input enabled and disabled (including briefly during list glide), raw press/release delivery without auto-regrab, focus loss and Alt-Tab, window replacement, screen handoffs and abort without overriding a newer cursor owner, and close-camera stability after unprocessed motion; and the content menu's navigation-group hierarchy under real input: entering the block-state group and the Create properties and Stress subgroups, an actionless group parent committing nothing on release, nested-group entry, a one-level Back return preserving the parent identity; the Content Intent Selection toggle's OFF Attention-leaf and direct-release behavior and its ON intent menus under real input, including a live edit applying on the next hold; the root removal entry's `Unping` label; and restored component-bearing inventory-row icons and names under real rendering. |
| Precise live capture | Periodic capture while the Precise branch is active and pause on leaving it; a moving target while the ordinary action stays press-frozen; release committing the last actually painted selectable version; a pending refresh keeping the last certified candidate; independent Distant Horizons location completion; and multi-loader live sessions. |
| Selection policy and input | Live GUI/screen callbacks for selection gating; focus-loss `KeyMapping.releaseAll`, screen-transition and level-instance/dimension discontinuity aborts with late asynchronous completion; loader/gameplay input lifecycle and physical key-repeat behavior on Fabric, Forge, and NeoForge; selection toggles, entity blacklist/default `simulated:honey_glue` rule, and spectator exclusion in a game session. |
| Movement, death and replacement | Target movement while the wheel is open; entity death or dimension change; block state change or replacement while open. |
| Naming and chat | Custom-name formatting and content receipt chat lines in the live chat overlay; localized base names; item naming; phrase-only text color. |
| Invalid-target feedback | Localized invalid-target message with the `[ping for it]` leading marker on both feedback paths, local pre-commit target loss and the correlated server `TARGET_GONE` rejection; the displayed text and marker come from language resources ([presentation owner](../UI/ping-feedback.md#presentation)). |
| Cancellation | Cone and nearest-own-marker selection; inability to cancel another player's marker; stale/display-hidden candidate followed by server rejection with no local fallback. |
| Multiplayer and protocol | Same-target latest-server-arrival winner; equal-arrival larger-Marker-ID tie; winner fallback after removal or expiry; complete `ServerCore` ordering/channel matrix; all-loader authoritative transport and ignored valid legacy S2C location; an owner-online refresh that changes the locator or the anchor; a marker beyond the bounded sampling cache synchronizing its known recipients without a new lease; an older or equal-revision initial arriving after a newer one without rolling back the stored payload; a legitimate policy change between cached projection and delivery; and a legitimate later packet arriving after the refresh. |
| Marker HUD | Repeated same-target pings from one sender and from several senders while same-target records remain display-active: the target's displayed HUD alpha does not accumulate with the number of same-target records ([invariant owner](../architecture/markers/client-state.md#winner-slots-are-not-the-render-marker-collection)). |
| Inventory and shared sources (planned) | Preview and tracking on Fabric, Forge, and NeoForge; vanilla single and double chests in a live world (their headless real-NBT capture is inventoried above), hopper, furnace, shulker box, and an unopened loot chest without loot-table generation; a fresh capture after ordinary single- or double-chest contents change; installed Create Vault with a real multi-member topology and a real mod-provided multi-member NBT source; provider-confirmed external candidate preview and tracking (candidate and committed bindings, membership scope, live item witness and handoff); private or unavailable inventories; permission and tag governance; unload/reload and live-world safety invalidation and recovery; block-type replacement versus same-type restore before expiry; different-block invalidation and grey status; hard stop at Ping expiry without recovery afterward; session end; queue overload and coalescing including explicit zero values; a multi-period low-consumption quota completing stably; component-too-long all-variant folding; heartbeat zero mode with repair and status still active; low-frequency limit/failure diagnostics; an explicit unlimited scan mode keeping finite work and memory guards; and restored transmitted component icons and variant names in a live preview or tracking view. |
| Presentation snapshot | Live version-one negotiation, offer and reset-mask pruning, and reset on Fabric, Forge, and NeoForge; the server-selected mask removing retained and frozen values and the reset child deny map pruning retained annotations and default references; permission-gated and per-target-type-policy-gated projection for two recipients of one marker; live property uploads through the approved native content property-selection route (client-first selection, server fallback, and authoritative CREATE validation) recaptured against authoritative world state with whole-create rejection on a wrong-kind, unknown, forbidden, unavailable, or exactly child-denied selection; rendered default display reference and property HUD lines; live property Ping Type override resolution and tag/registry selector matching against actual block, item, and entity tags; the policy rule-view read and per-target-type mutation route over a real connection, including the permission-3 mutation gate, rule-view revision ordering, the disclosed child deny list preserved across field mutations, and unsolicited broadcast to a second client; the server per-target-type policy page with no property-entry editor; superseded marker C2S/S2C routes mutating nothing; installed-Create kinetic and vault/tank summaries including nested count property selections and the cached stress/capacity/available-capacity accessor path, including whether a last-synchronized local total is stale, and an absent or untested version registering no Create adapter; and a live validated Sable external-block input through the Create route, including the uncommitted candidate preview entry, with unavailable and stale outcomes; and a zero server `scanBudget` under live demand with captures deferred while cached values and staleness semantics are retained. |
| Settings and config | External edits do not reload in-session and apply after restart or explicit reload; invalid-config recovery and preservation lock; live scope-tab and category navigation with leaf Back/Escape and root Done/Escape, a fixed footer with non-covering scrolled content, and per-page scroll/focus retention across back navigation and GUI resize, native widget input dispatch and focus-list traversal after a deferred page transition, and root and leaf pages at small GUI sizes and with long localized labels; one shared server session with a single correlated snapshot request retained across category and scope navigation, loading/permission/unavailable status, and a non-editable snapshot rendering read-only for a viewer below the required level; live permission revocation retaining a read-only leaf view, permission-return draft reset, promotion requesting a fresh snapshot, and reconnect draft behavior; invalid-draft close blocking with routing to the offending category and field; the client configuration file action and confirmation-dialog flows, including the reset warning when a server draft exists; the server Presentation category's per-target-type policy rows with their paired allow/block toggles and editable add/remove, whitelist-only changes, refresh, feedback, and broadcast to another client when permitted, including bounded no-response timeout retry, list-capacity feedback, caret and field focus, and a persistence fault during a mutation; and the marker display duration option shows its complete localized `<setting name>: <value>` label for both the Follow server sentinel and an explicit duration ([label owner](../UI/settings-screen.md#marker-display-duration-option)); and the coupled Display Byte Size and Frame Byte Size sliders with their live cap-change adjustment notice under real screen input. |
| Range | Client/server range combinations in one live pipeline: native minimum, a live long-distance Distant Horizons target, Create/Sable finite-segment reuse and server acceptance, including exact Create surface selection followed by whole-entity server-anchor range rejection. Installed-Sable scenarios are listed below. |
| Rate policy | Synchronization on reconnect and on effective live configuration change. |
| Optional content and rendering | Absent or partially present optional content; Create/Flywheel routes; occlusion and arbitrary camera angles; current shape, offset and seed. |
| Render entity lookup | A real frame epoch shared by HUD marker updates and optional outlines; fresh non-render lookups after render misses; CPU-frame and allocation measurements for 1, 10, and 50 entity marks in a dense world at high FPS. |
| Create contraption raycast | Hollow, L-shaped, sparse and non-full-block structures: hit occupied surfaces and pass through holes; front empty AABBs, overlapping structures and intervening world walls: select the nearest actual target; all four transparent/fluid policy combinations including represented waterlogging and partial fluid shapes; controlled rotations, moving and minecart-mounted structures, pitched carriages and gantry forms; current-dimension portal-hidden portions and client-loading data availability; long rays starting inside only broad bounds versus an actual selected shape; hold the key while the camera or structure moves and retain the press-time target; Create-absent, delegate-unavailable, client-reconnect and different Flywheel/outline backend paths; measure large-structure press-edge targeting cost. |
| Sable external blocks | An installed-Sable client/server session covering [candidate capture and presentation](../integrations/sable.md#client-capture-and-presentation), [server information sampling](../integrations/sable.md#server-information-sampling), and [server materialization and release](../integrations/sable.md#server-validation-and-materialization) after removal, expiry, owner disconnect, and empty-audience cleanup; sublevel rotation/translation and current tracking-point relocation; state or registry identity change; unload/reload, no-force-load behavior, provider reference and marker/session lifetime; client and server content preview reads for an uncommitted candidate and a committed target, including membership and received-chunk/prediction gates; names and fail-soft behavior; and multiplayer Create create, refresh, and removal. |

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
