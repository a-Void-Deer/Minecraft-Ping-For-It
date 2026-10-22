# Presentation verification

Use the [verification index](README.md) for the global evidence rules and the
[pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)
for scenarios that remain pending.

## Presentation snapshot negotiation, policy and adapters

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
  cache through the compatibility replay seam; that winner reaches the client
  outline projection once its record is known; a policy reset re-announces the
  winner under the advanced view;
  positive per-capture allowances cannot reset the
  shared per-tick capture quota across leases; refreshed metadata does not
  advance the effective sampling interval; a zero `scanBudget` defers Basic and
  optional observations without mutating cached values or their stale flag, and
  empty demand still clears a previously sampled optional section; and
  exhausted shared work defers an optional observation without a fake failure
  while unused per-call allowance does not drain shared tick work. Its sessions,
  demand and Basic observation ports are in-memory or recording; the live
  server world, registry lookups, real providers, packet transport and rendering
  remain unexercised;
- `PresentationServerFairRotationTest` covers the production sampler over a
  canonical population larger than its observation cache: recurring Basic and
  optional-adapter opportunities during continued creates, independent cadence
  across eviction and reentry, resuming a blocked optional turn without repeating
  Basic, paid failed turns, restored grants, age-safe vacancy handling, and
  removal, expiry and demand exits. `PresentationServerBaselinePagingTest`
  covers read-free baseline discovery beyond cache capacity, one shared bounded
  discovery allowance across ready sessions, audience and population-boundary
  fences, Basic-before-optional delivery, current-view inventory gating,
  monotonic revisions, and each recipient's own retained projection after a
  failed reentry. Its authorization-cut tests cover reset and metadata-refresh
  pruning without resurrection on replay or loosening; only a fresh successful
  observation restores the removed projection. These are headless scheduler,
  store and delivery seams for the
  [sampling and baseline contract](../../architecture/presentation/presentation_snapshot.md#server-capture-and-per-recipient-projection),
  not live-world fairness, latency or heap/TPS measurements;
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

Related optional-adapter evidence is recorded under
[Create presentation adapters](integrations.md#create-presentation-adapters).

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

## Target content preview

The [content-preview owner](../../architecture/presentation/presentation_snapshot.md#target-content-preview)
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
  widened. Residual-grant tests retain an accepted request without validation
  reads until the complete lookup, validation and minimum-observation grant is
  available; monitoring cases distinguish actual resource refusal from cadence,
  revocation, expiry, source failure and transport refusal, deduplicate repeated
  request/reason observations, and close the shared presentation cycle after
  preview work;
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

Optional Create receipt and preview-gate evidence is recorded under
[Create content preview](integrations.md#create-content-preview).

These tests exist at headless runtime, reader, model, codec and static wiring
seams. They do not record a current Gradle run or establish world-backed
validation, prediction-guard application, live provider source or membership
resolution, live permission changes, native
content selection through authoritative CREATE, installed optional providers,
network delivery, rendering, or multiplayer behavior.

## Content receipt chat and pending completion

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

## Names and chat

The current suite covers:

- chat template selection and Ping-Type-phrase-only coloring;

`PingChatBuilderTest` covers required placeholders, escaping, missing-token and
legacy fallback seams. This does not close localized-name, custom-name, locale,
or resource-fallback scenarios.

Name-composition and rendering evidence is recorded under
[rendering verification](rendering.md).

## Known automated gaps

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
  [names and chat](../../architecture/rendering/names_chat.md#content-receipt-lifecycle);
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
- the live local invalid-target chat path: focused tests cover the composed
  translatable prefix and message component, its color, and the bundled resource
  text, but no test exercises the Minecraft chat overlay or the client trigger
  paths that show the line;
