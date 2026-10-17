# Integration verification

Use the [verification index](README.md) for the global evidence rules and the
[pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)
for scenarios that remain pending.

## Shared exception diagnostics

`SafeExceptionReportTest` covers original messages, exception types, source
frames, causes and suppressed relationships without redaction; recognizable
control-character escapes with readable structural line breaks; separate
context and message bounds that preserve the top source frame; and explicit
truncation, depth, node, frame and cycle markers. Hostile-accessor cases cover
message, stack and cause fallbacks without localized-message or throwable
string conversion, while fatal message-accessor failures propagate unchanged.
Its logger seam receives one complete formatted string without a second
throwable argument.

`RateLimitedDiagnosticsTest` covers WARN/ERROR reports through a recording sink:
disabled-level laziness, duplicate suppression despite message/request churn,
independent stage, scope, exception-type and source-location admissions, exact
window recovery and clock wraparound, bounded capacity without active-entry
eviction, counted low-frequency overflow summaries, bounded long fields and
distinct long scopes, and concurrent duplicate admission. Recoverable context
failures retain the original exception, recoverable sink failures remain
fail-soft, and a fatal context failure propagates.

`GlobalExceptionLoggingTest` uses a real Log4j test appender to cover disabled
DEBUG/WARN/ERROR helpers not reading throwable accessors and the disabled public
DEBUG route evaluating neither context nor throwable accessors. The enabled
helpers and public rate-limited DEBUG/WARN/ERROR routes emit their intended
levels, retaining
context, original messages, causes, suppressed details and source frames in one
bounded report without an attached throwable for additional logger rendering.
The common real-appender fixtures install isolated limiter instances with a
Log4j-backed test sink and restore the previous instances after capture; public
static routes and bounded formatting remain exercised. This isolation is not
evidence of continuous production operation or high-cardinality soak behavior.
These are helper and test-configured logging seams, not deployed appender,
layout, asynchronous logging, live provider, loader/Mixin or game-session
evidence. They do not establish that every integration call site emits the
required diagnostic details.

## Create presentation adapters

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

The common presentation evidence and its live-world limitations are recorded
under [presentation snapshot negotiation, policy and adapters](presentation.md#presentation-snapshot-negotiation-policy-and-adapters).

## Create content preview

NeoForge's `CreatePreviewReceiptStateTest` covers explicit client receipt
evidence and its invalidation without treating absent constructor fields as
observed zero. `CreatePreviewMixinPluginTest` covers a constructed target's
receipt/invalidation method and field-shape gate, not live mixin application.
`CreateSableContentPreviewTest` covers the adapter-level separation between the
candidate-aware preview collection and the committed collection, including a
zero preview budget never reaching the source and an adapter without a
candidate source keeping its committed path.

The common [target content preview evidence](presentation.md#target-content-preview)
records the headless and live-validation boundaries for these seams.

## Create raycast seams

The common [exact entity-local picking evidence](interaction.md#exact-entity-local-picking-and-create-raycast-seams)
owns the shared geometry coverage. Create-specific coverage exercises:

- the Create-free contraption engine; and
- lazy Create adapter/loading seams and delegate-unavailable behavior.

These tests preserve whole-entity identity and exercise the non-optional engine
boundaries. They do not constitute an in-game Create validation.

## Sable integration coverage

The linked [Sable integration](../../integrations/sable.md) topic remains the
behavior owner for [client capture and presentation](../../integrations/sable.md#client-capture-and-presentation),
[server validation and materialization](../../integrations/sable.md#server-validation-and-materialization),
[server information sampling](../../integrations/sable.md#server-information-sampling),
and [refresh lifecycle](../../integrations/sable.md#refresh-lifecycle). The
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
[presentation subjects](../../architecture/rendering/presentation_subjects.md).

## Simulated integration coverage

Executable stand-in resolver tests cover paired-owner resolution, the facing
relationship, powered owner, owner block-entity identity, and handled-empty
results. Separate static guards cover the production registry identities and
optional registration wiring. The stand-ins do not exercise real registry
lookup, and the static guards do not establish a running loader lifecycle, an
installed Simulated runtime, or in-game presentation.

## Optional rendering structural coverage

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

## Known automated gaps

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
- detailed diagnostic behavior in the private
  `CreateEntityOutlineAdapter.EntityDiagnostics` path.

Coverage of Flywheel diagnostics or another adapter's diagnostic helper does
not close the private `EntityDiagnostics` gap.

## Sable-specific gaps

Sable-specific gaps remain for:

- installed-Sable API compatibility and client capture/presentation against a
  live sublevel, including the established
  [logical-anchor/render-pose boundary](../../integrations/sable.md#client-capture-and-presentation);
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
- provider materialization, tracking-point reference counting, rollback and
  release, including the existing empty-audience cleanup path documented under
  [server validation and materialization](../../integrations/sable.md#server-validation-and-materialization);
- live-sublevel refresh through the documented available, temporarily
  unavailable, and invalid outcomes in the
  [refresh lifecycle](../../integrations/sable.md#refresh-lifecycle); and
- end-to-end server-authoritative names, fail-soft behavior, and multiplayer
  marker synchronization at the boundaries owned by
  [Sable](../../integrations/sable.md#names-permissions-and-diagnostics) and
  [target validation](../../architecture/authority/target_validation.md).

The provider-confirmed external inventory-source gap is recorded under
[inventory preview and tracking foundation](inventory.md#inventory-preview-and-tracking-foundation).
