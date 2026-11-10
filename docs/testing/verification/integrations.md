# Integration verification

Use the [verification index](README.md) for the global evidence rules and the
[pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)
for scenarios that remain pending.

## Shared exception diagnostics

The behavior contract is owned by
[diagnostics and logging](../../architecture/logging.md).

`SafeExceptionReportTest` covers original messages, exception types, source
frames, causes and suppressed relationships without redaction; recognizable
control-character escapes with readable structural line breaks; separate
context and message bounds that preserve the top source frame; explicit
truncation, depth, node, frame and cycle markers; and hostile message, stack and
cause accessors without localized-message or throwable string conversion, while
fatal message-accessor failures propagate unchanged. Its logger seam receives
one complete formatted string without a second throwable argument.

`DiagnosticPolicyTest` covers the explicit-side profile pipeline through a
recording sink: all severities under every profile and minimum, effective-profile
disclosure separate from minimum eligibility, `NORMAL` summaries without
context-supplier or throwable-accessor reads, `DEEP` context and original
exception detail, audited scalar templates, bounded formatting, side
independence, hidden clocks or windows without an explicit channel, suppressed-
count recovery and clock wraparound, hints that consume no admission, a fixed
effective snapshot during a report, and disabled or below-minimum terminal
reports that do not invoke an explicit-domain admission callback. A
backend-disabled domain observation remains subject to its domain's opportunity
rules; the inventory bound-reason gate advances without evaluating detail.
Pipeline failures at setup, enablement, channel clock, admission, context,
accessor, formatting and sink stages become self-faults; nested reports terminate
or defer until outer callback locks release, and an admitted sink failure
consumes the opportunity without replay. A blocking context never retains
channel or policy locks, while fatal context errors propagate. The same suite
covers the limited domain-before-backend ordering used by the three inventory
gates: one effective-profile snapshot across concurrent changes, gate
consumption followed by backend suppression without rollback, callback-null and
below-minimum short-circuits, attached-channel rejection, admission/setup faults
and reentry.

`Log4jDiagnosticBackendTest` covers the exact owned client, server and bootstrap
logger levels through setup and reconfiguration, concurrent registration and
publication, a foreign reconfiguration listener without lock inversion, recovery
after logger-lookup failure, and a real Log4j test appender receiving one bounded
string at the expected level and exact logger name with no attached throwable. It
leaves the root level, another mod's logger and the existing appender threshold
unchanged; it does not establish deployed appenders, layouts, asynchronous
logging or loader behavior.

`DiagnosticFaultReporterTest` covers self-fault identity by side, site, stage and
kind; monotonic disclosure from a `NORMAL` summary to one later higher-profile
detail without throwable replay; saturating occurrence, repeat and layer
counters; bounded class/message-only detail without a self stack, cause or string
conversion; one fixed fallback for a failing formatter; recursive-accessor
termination; and one-time writer initialization under concurrent reporters. The
writer emits complete UTF-8 newline-terminated records with one flush; open,
write or flush failure stops after one finite attempt without closing the
output. The cases use an injected output stream, not the process descriptor, a
launcher, a live appender or an actual asynchronous backend failure.

`DiagnosticEmergencyWriterProcessTest` passes in the scoped common execution
using a child JVM over the real standard-error descriptor: a `NORMAL` self-fault
summary omits private class or message detail, one complete record is
newline-terminated and flushed, ordinary standard error remains usable, and
emergency output does not route through or close the Java `System.err` wrapper.
This is process-level descriptor evidence, not a live appender, loader or game
run.

`DiagnosticsTest` and `GlobalExceptionLoggingTest` cover the public explicit-side
and `Global` routes: independent side profiles and assigned severity, no implicit
duplicate suppression, disabled accessor laziness, bounded escaped detail, one
bounded original graph per call, `NORMAL` and `DEEP` selection, and isolated
initialization without global config or platform services.

The profile, self-fault, backend and public-route fixtures include added
logging/diagnostic coverage with no saved source-input execution acceptance
record yet. The earlier
[common execution](build-checks.md#logging-profile-common-execution),
[loader execution](build-checks.md#logging-profile-loader-execution) and
[final combined acceptance](build-checks.md#logging-profile-final-combined-acceptance)
record earlier inputs and are not a substitute. `Log4jDiagnosticOutputBoundaryTest`
is recorded under the same common execution entry. The listed scopes remain
automated-seam coverage only; live appender, loader and game behavior and manual
scenarios remain pending.

## Optional failure diagnostics

`IntegrationFailureDiagnosticsTest` covers a once-only integration disable, the
tested Sable fallback and domain-approved calls, and asynchronous Distant
Horizons completion with a throwing callback. Against the explicit backend with
`DEEP` disclosure, the once-only report retains the original linkage cause and
root, ordinary and approved Sable reports remain independent, and the
asynchronous case unwraps its cause, completes once with no hit, and lets a
throwing completion callback propagate. These are direct guard and completion
seams, not an installed Sable or Distant Horizons runtime, an actual
asynchronous trace, loader startup or game-session evidence.

`ExternalBlockRegistryDiagnosticsTest` covers throwing and null-result providers
at validation, materialization, refresh, name, observation and candidate/committed
read-source dispatch while the tested invalid, temporarily-unavailable and empty
outcomes remain intact. Against the explicit backend with `DEEP` disclosure,
warnings retain provider identity and the original cause or null-result event
without an attached throwable. Missing-provider and ordinary temporary refresh
results stay quiet, and an `AssertionError` from refresh propagates. Membership
dispatch keeps a throwing provider outcome unknown with one `NORMAL` warning,
one provider-identity read and one membership call; ordinary unknown, budget
exhaustion and fatal errors stay quiet, and the warning does not hold the
registry monitor. Cleanup failures still allow later provider and reference
cleanup; a throwing member predicate denies access. These fixtures use fake
providers and uninitialized server/level tokens, not live world validation or
provider lifecycle evidence.

## NeoForge logging-profile lifecycle and loader diagnostics

The NeoForge integration suite adds explicit-side logging-profile coverage at
Create registration, optional registration, entity-outline reporting and
presentation call sites. `CreateRegistrationLifecycleTest` and
`CreateAdapterLifecycleDiagnosticsTest` cover concurrent and same-thread
reentrant install/close serialization without self-wait, retryable reservation,
non-blocking state reads, idempotent and interrupt-preserving teardown, output
outside the lifecycle monitor, same-thread reentry becoming `SINK` self-faults
without recursive retry, failed nested close that preserves the outer candidate
and registry winner, and serialized retry. `NeoClientOptionalRegistrationTest`,
`CreateEntityOutlineDiagnosticSeverityTest`, `CreateEntityOutlineDiagnosticsTest`
and `CreatePresentationLoggingProfileTest` cover probe and severity behavior,
`NORMAL`/`DEEP` disclosure without widening, heartbeat deduplication, profile
changes that do not reset interval or transition history, suppressed attempts
collecting no detail, and independent client/server profiles.
`LoaderDiagnosticScopeTest` scopes interval state per side and restores it;
`CreatePresentationFactoryCompatibilityTest` keeps the common reflection entries
and adapter fields with Create present or absent and no collector load.

The newer cases have no saved loader build/test execution record yet. As noted
under [shared exception diagnostics](#shared-exception-diagnostics), earlier
loader and combined records cover earlier inputs and remain historical. These
are headless or constructed fixtures with injected sinks; they do not establish
live loader launch, installed Create, GPU presentation, multiplayer or
mixed-version behavior.

## Create presentation adapters

NeoForge Create adapter seams cover the optional summary route.
`CreatePresentationAdapterTest` covers client/server manifest parity, demand
gating and per-field projection, including a default-enabled derived
available-capacity field computed only from a demanded raw capacity/stress pair,
one shared source capture, no source access under a zero budget, exact negative
and double-precision results, missing-component unavailability rather than
asserted zero, unavailable-versus-partial summaries and cadence.
`CreatePresentationRegistrationTest` covers once-per-registry registration and
absent-adapter rejection. `CreatePresentationCollectorBudgetTest` covers
shape/work/version gates and the paid visited-member prefix without refunding
earlier checks; its diagnostic case reports a throwing gate once with its cause
while the paid check stays consumed and the member read unreached.
`BoundedCreateSummaryTest` covers registry-ID aggregation and scan/work/output
limits. `CreatePresentationMixinPluginTest` and `CreatePreviewMixinPluginTest`
cover version and constructed ASM accessor/shape gates, including a throwing
bytecode-provider callback closing only its gate. `CreateCachedKineticPreviewTest`
covers the Create-free cached network-totals seam: a missing receipt reads
neither network nor raw totals, a missing network or accessor shape reads only
its probe, each condition yields per-field unavailability rather than asserted
zero, a received zero stays observed, raw accessors are read at most once, the
derived value reuses that pair with double promotion over extreme finite floats,
an undemanded family field is neither read nor published, and a bad raw value
loses only its field. Its diagnostic cases retain a repeated cached-stress warning
while capacity stays observed and a receipt-getter failure before network
access. `CreatePresentationKineticDiagnosticsTest` executes the compiled kinetic
method with a controllable Create stand-in: a runtime or linkage failure in a
public kinetic getter loses the whole kinetic result, a failed cached getter
retains its successful sibling, and fatal getter errors propagate without a local
report. These are headless bytecode/constructed
fixtures, not a loaded Create runtime, live getter, collector, mixin or GPU
evidence.

`CreateMixinLoaderGateTest` reads the resolved compile-only Create artifact and
drives both production plugins headlessly through the real ModLauncher bytecode
provider, covering the tested accessor and receipt shapes, positive mixin
decisions, an untested version stopping before bytecode discovery, an
unavailable provider staying fail-soft, and drift closing only the affected
gate. It pins the provider's unsupported untransformed rejection and the
Mixin-owned lookup not consulting the installed processor: a per-instance
recording processor's positive control first observed one ordinary ModLauncher
reason and its AFTER nomination, while the Mixin-tagged lookup added no
processor call and the provider lookup still returned the Create target. The
exercised provider is the repository's test-classpath Mixin 0.8.6, with no
provider-version promise. It adds no runtime Create dependency and does not apply
either mixin in a launched game. `CreatePresentationExternalBlockTest` covers the
production `observeSource` routing seam: committed metadata/source-binding/member-gate
forwarding, demand and budget forwarding, ordinary-block routing without
external observation, and candidate preview with provider-resolved position and
detached metadata. Unavailable, foreign, missing-binding or throwing gates stop
before reads; compatible source-access defaults never fall back to legacy
observation or drop a resolved member scope. Its recording `SourceAccess` does
not exercise a real Create collector or handler, the Create mixin/accessor or a
live Sable API.

The common presentation evidence and its live-world limitations are recorded
under [presentation snapshot negotiation, policy and adapters](presentation.md#presentation-snapshot-negotiation-policy-and-adapters).

## Vault topology, grouping and presentation

`CreateVaultTopologyTest` covers all admitted widths, lengths, axes and member
hits with detached metadata fixtures, including the complete 81-cell maximum:
each resolves to the same ordered structure, while missing roots/interiors/ends,
foreign members, bad controller roles or axes, bounds/overflow and replacement
during callbacks publish no prefix; a later valid operation recovers. For N
members, a successful topology operation makes `2 + 2N` loaded metadata reads,
including source/controller reads and complete member revalidation; this is not
a one-getter-per-member or performance bound. The behavior owner is
[Create Vault topology](../../integrations/create.md#vault-logical-target-and-presentation-topology).

`CreateVaultProductionBoundaryTest` executes the compiled production topology,
platform and resolver paths through an isolated loader against the resolved
tested Create archive, retaining actual metadata getters and client read/write
methods while replacing initialization-heavy ancestors. Actual archive
client-packet branches synchronize controller extents without inventory
serialization, tolerate stale noncontroller extents and absent last-known
position, and preserve controller-axis/member gates. Constructor-free native
server/client level and cache fixtures reject chunk loading and block-entity
promotion and supply only existing entries. The cases reach the actual server
creation factory, platform ServiceLoader, common grouping resolver, marker
store, wire codec/client winner and lazy NeoClient registration/common resolver
registry; they retain the original hit and anchor, frozen audience and lifetime,
exercise absent/untested/drifted optional routes and recovery, and share one
ordered subject snapshot between normal and late fallback. One rendered cell
never covers siblings; a compiled call-route guard excludes inventory,
capability, NBT and loading discovery. The archive is not a runtime dependency,
and these isolated methods and recording fixtures do not exercise an installed
Create game, live loader initialization, packet ordering, GPU rendering or
multiplayer. Inventory sharing evidence is separately inventoried under
[logical container quotas and scan sharing](inventory.md#logical-container-quotas-and-scan-sharing).

## Fluid tank topology, grouping and presentation

`CreateFluidTankTopologyTest` retains and validates every cell, including strict
interior cells, across all widths and sampled tall structures, and does not
exercise the shell filter. Invalid dimensions, overflow, missing or inconsistent
members, including a missing strict interior cell, and callback replacement
publish no prefix; later reads recover.

`CreateFluidTankProductionBoundaryTest` covers the production optional route
(actual tested-archive metadata, NeoForge server creation/platform dispatch,
lazy client registration, absent/untested/drifted routes, wrong dimensions and
member identity/load/controller gates, existing chunks and instantiated block
entities only), geometry/read separation (active-boiler metadata retains the
complete verified topology without gating presentation; creative and foreign
registry identities retain physical keys; throwing content ports and compiled
call guards exclude capability, inventory, fluid, NBT and loading discovery),
and filtered publication. A replaced strict interior cell invalidates the whole
topology before filtering, and the next frame recovers. After complete topology
verification, only the owner-defined shell subjects enter normal sources or
late fallback; a tank without a strict interior publishes every verified cell.
Every published shell
cell keeps `entity_block` and its original physical source, the dispatcher
visits every published subject after simulated controller BER success, and an
unrendered published cell emits its own native edges. Normal and late fallback
share one filtered frame snapshot; incomplete metadata falls back directly and
later frames recover. The structural rule is owned by the [tank topology
contract](../../integrations/create.md#fluid-tank-logical-target-and-presentation-topology).

The harness replaces initialization-heavy ancestors and geometry submission with
recording callbacks. It establishes filtered subject organization and native
edges from recorded subjects, not actual BER, baked-model or GPU submission,
real-neighbor occlusion or visible shell appearance. The shell-fallback case
proves unrendered shell subjects reach native fallback; it does not observe
`EMPTY` or `FAILED` source outcomes, which existing common coverage/runner tests
cover. `CreateVaultProductionBoundaryTest` also covers the neighboring
topology/registration regression. Execution scope is in [build
checks](build-checks.md#shell-filter-tank-verification); the earlier
[tank combined-tree run](build-checks.md#fluid-tank-grouping-and-presentation-verification)
predates the shell-filter cases.

Common identity, wire and source-gate coverage is inventoried under
[identity authority](identity-authority.md#logical-container-identity-and-authority),
[presentation transport](presentation.md#logical-target-wire-coverage) and
[container rendering](rendering.md#container-presentation-subjects). Installed
Create synchronization, GPU shell appearance, multiplayer and tank topology
split/merge remain in the [logical container pending
matrix](manual-integration.md#logical-container-targets-and-whole-presentation);
performance remains in the [fluid inventory pending
matrix](manual-integration.md#fluid-inventory).

## Native fluid presentation

Fabric's `FluidPresentationServiceTest` crosses actual native attribute and
render-handler APIs for base descriptions and still sprites, including literal
overrides, absent/throwing handlers, service discovery and replaced sprite
resources. Forge's version covers discovery and atlas-independent construction;
its native FluidType description and base still-texture case passed on the
patched integration classpath. The
[combined-tree and native-classpath execution](build-checks.md#native-fluid-boundary-and-presentation-verification)
records those results and ordinary-classpath skips.

NeoForge's passing `CreateFluidPresentationArchiveTest` inspects the tested
Create/embedded Registrate tea-registration bytecode chain to the native
`fluid.create.tea` description and still-texture assignment, checking the
archive's English/Chinese names, tea PNG and block-atlas inclusion. A
factory-result fixture supplies description properties and temporarily registers
the fluid, then crosses production loader ports, the server freezer and strict
localized client projection with an exact long amount and no invented custom
name; it executes the archive's virtual-fluid air legacy-block and native
description/client-extension bodies, while a recording atlas observes sprite
replacement. Initialization-heavy ancestors and game/atlas setup are
substituted. This is not full `AllFluids` registration, installed Create, GPU,
live resource-reload or dedicated-server launch evidence.

## Create content preview

`CreatePreviewReceiptStateTest` covers explicit client receipt evidence and
invalidation without treating absent constructor fields as observed zero.
`CreatePreviewMixinPluginTest` covers a constructed target's receipt/invalidation
method and field-shape gate, not live mixin application.
`CreateSableContentPreviewTest` covers candidate-aware versus committed
collection, a zero preview budget never reaching the source, and an adapter
without a candidate source keeping its committed path. The common [target content
preview evidence](presentation.md#target-content-preview) records the headless and
live-validation boundaries for these seams.

## Create raycast seams

The common [exact entity-local picking
evidence](interaction.md#exact-entity-local-picking-and-create-raycast-seams)
owns shared geometry coverage. Create-specific coverage exercises the Create-free
contraption engine and lazy Create adapter/loading seams with
delegate-unavailable behavior, preserving whole-entity identity; it does not
constitute in-game Create validation. `CreateContraptionNativeBoundsTest` drives
the production reflective lookup helper with stand-ins: a missing method or
checked invocation failure reports its cause and leaves bounds unavailable, a
wrong return shape stays unavailable, and runtime or fatal invocation causes
propagate unchanged. The shell-loading failure case in
`CreateContraptionRaycastAdapterTest` asserts one bounded full report without an
attached throwable. Neither seam exercises an installed Create contraption or
live native bounds.

## Sable integration coverage

The linked [Sable integration](../../integrations/sable.md) topic remains the
behavior owner for [client capture and presentation](../../integrations/sable.md#client-capture-and-presentation),
[server validation and materialization](../../integrations/sable.md#server-validation-and-materialization),
[server information sampling](../../integrations/sable.md#server-information-sampling),
and [refresh lifecycle](../../integrations/sable.md#refresh-lifecycle). Focused
seams provide limited structural, locator-codec, provider-observation and
diagnostic evidence:

- `SableClientCompanionAccessContractTest` statically parses the compiled
  access-class constant pool, requires the exact
  `SableCompanion.getContaining(Level, Position)` symbol, and excludes
  `getClientLevel` and `getContainingClient`;
- `SableExternalBlockLocatorTest` covers representative encode/parse round
  trips and selected malformed, noncanonical and out-of-bounds cases;
- `ExternalBlockServerProviderRegistryTest` covers production observation and
  validation dispatch with a read-only fake world: loaded non-air state,
  registry/state matching, same-level, server-thread, dimension, committed-target,
  missing-provider and provider-failure gates. Candidate and committed
  resolution select only their own provider method; unknown or legacy providers
  and a new-API `LinkageError` fail soft without materialization, observation or
  reference allocation, and a candidate read never materializes a reference;
- `SableExternalBlockObservationTest` covers the stable-entry helper selecting
  the committed tracking ID's current sublevel and local position without
  changing entry or reference maps, and failing closed for non-live references,
  wrong entries, invalid tracking-point state or coordinates;
- `SableBlockReadSourceTest` covers preview identity separate from the detached
  physical binding and logical anchor, malformed provider/dimension/registry/
  locator rejection before provider access, positive sub-level containment,
  live identity and loaded state before content reads, member rejection outside
  the resolved sub-level, and a committed read that follows the current tracking
  point, leaves maps unchanged and cannot resolve after release;
- `SableSupplementalRaycasterTest` covers the transformed scanner with real
  Companion poses and native shapes but recording sublevel ports: translated and
  rotated hits behind a blocker or after a miss, world-segment range under
  nonuniform scale, detached pose evidence, provider-local equivalence,
  entry/shape-work admission, loaded/invalid-pose gates, selection-policy reuse,
  removed entries and absent-Sable safety. It also covers provider-local native
  face copying, origin containment and faceless synthetic/inside/unobserved
  contacts, and nearer same- or different-locator contacts installing only their
  own face without changing the frozen ordinary face;
- `SableObservationFailureDiagnosticsTest` drives the production observation
  catch with an injected throwing reflective tracking-point stand-in: runtime
  and linkage failures return temporary unavailability with one cause-bearing
  warning carrying temporary-unavailability or integration-disabled handling,
  existing reference and entry counts stay intact, and linkage additionally
  disables the shared guard and invalidates a later refresh. Its
  refresh-transition case reports a warning after a debug-only observation,
  suppresses the duplicate and admits a different stable target;
- `SableRefreshLogGateTest` checks locator or reason changes and duplicates,
  disabled reporting observing state without consuming a later warning, and
  removal or recovery permitting a new failure report; it does not run a
  refresh operation or log sink;
- `SableDiagnosticsTest`, `SableServerDiagnosticsTest`,
  `SableClientDiagnosticsTest` and `SablePresentationLogGateTest` cover recorded
  capture, server and refresh events, same-throwable identity, finite failure
  handling, provider fields lazy at `NORMAL`, under a disabled backend or under
  suppression, a throwing descriptor supplier, the discovered
  twelve-descriptor API list and a constructed linkage failure, absent-Sable
  empty capture with attempt-start and projected/vanilla fallback stages, a
  supplemental failure leaving the guard usable, and presentation cadence,
  capacity, repeated failure-class de-duplication, throwable identity and a
  disabled-debug status followed by one warning without an attached throwable.

These seams do not load a Sable runtime or establish live provider reflection
(including the additive content membership discovery), materialization,
tracking-point reference lifecycle, live-sublevel membership and observation,
the client chunk and prediction gates of a provider-resolved physical block, a
live multi-member content capability, or refresh, multiplayer, GUI, network or
in-game behavior described by those topic sections. The registry, content
resolver and stable-entry tests are narrow production-used seams with fake
access and in-memory state; the observation-failure fixture installs reflective
stand-ins into uninitialized provider, server and level tokens. The external
model and fallback routes resolve provider presentation independently, so they
cannot guarantee that provider-local multipart or subject-type decisions came
from one immutable shared snapshot. That is an implementation-conformance and
automated-coverage gap, not an external exception to the shared-subject contract
in [presentation subjects](../../architecture/rendering/presentation_subjects.md).

## Simulated integration coverage

Executable stand-in resolver tests cover paired-owner resolution, the facing
relationship, powered owner, owner block-entity identity and handled-empty
results. Static guards cover the production registry identities and optional
registration wiring. `SimulatedRegistryDiagnosticsTest` exercises the
production-used lookup helper with injected callbacks: ordinary absence is
silent, a throwing lookup returns unavailable and retries, and the warning
retains the registry ID and original cause without an attached throwable. The
stand-ins and callbacks do not exercise real registry lookup, and the static
guards do not establish a running loader lifecycle, an installed Simulated
runtime or in-game presentation.

## Optional rendering structural coverage

Common tests cover class loading with optional dependencies absent, empty versus
positive geometry output, coordinate transforms and environment policy.
`FlywheelInstanceResolutionTest` exercises the production-used attempt-local
resolution helper: failed or missing handles do not discard valid siblings,
later attempts still run, and the immutable result retains the first original
failure for a cause-bearing diagnostic without an attached throwable across mixed
and all-failed batches; it does not cover live Flywheel handle resolution.
`CreateFlywheelGeometryAttemptTest` exercises the attempt and model-loop seams
with recording callbacks: mixed
resolution followed by a zero-triangle or over-budget sibling stops before
planning while retaining the first resolution failure and retrying the valid
sibling; a later model or terminal failure retains both original cause chains
through a fresh wrapper without mutating either, and supplied terminal outcomes
stay unchanged. Each attempt selects one report, domain suppression avoids
formatting a same-reason/outcome/failure-category report while a transition or
new episode is reported, a clean attempt retains no earlier failure, and
admitted detail comes from fixed attempt-local evidence without repeating
model, material or mesh getters. `CreateFlywheelDiagnosticEvidenceTest` executes
the compiled extraction, preflight and detail methods with recording stand-ins:
reads occur only as often as failed attempts require, admitted `DEEP`/`DEBUG`
detail needs no live read, disabled reports collect nothing, extraction/material
failures stay `ERROR`, and preflight budget failure is `WARN`.
`FlywheelDiagnosticsTest` covers disabled-debug recovery, disabled warning levels
preserving admission, a throwing admitted sink consuming the opportunity,
render/availability/mixed-success severity and handling, clock-free disabled
observations, new episodes, and heartbeats that are not refunded.
`CreateEntityOutlineDiagnosticGateTest`
covers the entity adapter's admission gate: disabled-debug recovery,
debounce/heartbeat, warning-level changes without resetting consumed admission,
bounded retained state and teardown; it does not execute the private full-report
construction, dispatcher, mask scope or GPU path. `CreateEntityOutlineBackendProbeTest`
covers the cached probe: disabled `WARN` reads no message/cause/stack, a
suppressed report reads neither, admitted formatting happens after availability
is published and outside the probe monitor, concurrent requests share one probe,
normal absence stays silent, and the bounded cause-bearing report has no attached
throwable. These are probe and logging seams, not live
backend discovery, visualization support, loader startup or entity dispatch.

Static guards cover optional reflective registration, loader-specific host API
bindings, integration wiring, and the optional visualization mixin's target and
injection signature. They do not establish large-water-wheel master-resolution
outcomes, once-only dispatcher execution, nested mask-scope cleanup, or live
world-aware model-data flow, culling, pose restoration and geometry emission.
Those execution boundaries remain automated verification gaps; source presence
and successful compilation are not substitutes for exercising them.

## Known automated gaps

- explicit-side profile disclosure across every optional-integration call site,
  deployed appender and filter selection, Forge/NeoForge debug-log placement,
  and emergency-writer behavior under a launched game remain unverified; the
  shared diagnostic fixtures use test sinks, test appenders and injected output
  streams;
- installed-Create presentation sampling and display remain unexercised in-game.
  Automated adapter, loader-gate, cached-accessor, receipt and external-block
  route seams are inventoried under
  [Create presentation adapters](#create-presentation-adapters); they do not run
  the production collector's own per-member checks, capture a real Create
  collector's vault or tank controller/member structure, or sample a live block
  capability. A real Create collector or handler, the applied Create
  cached-accessor and receipt mixins under a launched game's live loader
  environment, an installed Create block entity read, the Sable API, an in-game
  goggles/game-HUD label line, and manual evidence for the freshness of a
  last-synchronized cached network total remain pending, so a last reliable
  client sync may be stale until an authoritative recapture;
- live fluid-tank shell appearance, strict-interior invisibility and
  wall-adjacent through-block outlines remain unverified; the tank harness
  records published subjects and filtered native edges, not actual BER,
  baked-model, GPU or real-neighbor behavior;
- full-report construction and delivery in the private Create entity-outline
  diagnostic path; its admission gate and separate backend probe have the
  headless coverage above.

Coverage of the entity admission gate, backend probe, Flywheel diagnostics or
another adapter's helper does not close that full-report gap.

## Sable-specific gaps

Sable-specific gaps remain for:

- installed-Sable API compatibility and client capture/presentation against a
  live sublevel, including the established
  [logical-anchor/render-pose boundary](../../integrations/sable.md#client-capture-and-presentation);
- live server information sampling through the production registry and Sable
  provider against a real `ServerLevel` and loaded sublevel, including current
  tracking-point relocation, sublevel rotation/translation, state or registry
  identity change, unload/reload, no-force-load behavior, and marker/session
  lifetime; the observation tests use fake world-access tokens, in-memory
  entry/reference maps or an injected throwing reflective stand-in;
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
