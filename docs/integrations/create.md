# NeoForge Create integration

Create remains optional. All Create-dependent delegates are loaded lazily only
after the relevant mod-ID checks, and missing classes, API drift, unavailable
live state, or rejected registrations must fail soft without breaking ordinary
entity, block, or location pings. Common registries and outcome contracts remain
free of Create and Flywheel dependencies.

The integration has three separate primary rendering routes--contraption
press-time picking, entity outlines, and Flywheel entity-block geometry--plus
narrow block presentation resolvers and a server-side presentation summary
adapter. Their gates and lifecycles are independent.

## Contraption press-time picking

The Create raycast source refines only the four supported contraption entity
types and preserves whole-entity marker identity. It is independent of
Flywheel and both outline backends. An owned candidate is accepted only after
an exact local-shape `HIT`; owned non-hits never recover through the coarse
entity AABB.

The transform, immutable local-view, represented-fluid, cost and limitation
contract is retained in [Create contraption ray targeting](create-contraption-raycast.md).
The generic owner-result rules, native kernel and capture metadata are owned by
[entity-local picking](../architecture/picking/local_geometry.md) and
the rejected coarse-bound alternative is explained by
[D0006](../decisions/D0006-exact-owned-geometry.md). Manual scenarios are owned by
[verification](../testing/verification.md#pending-manual-and-integration-matrix).

## Create entity-outline source

After the Create mod-ID check, reflective loading registers source
`pingforit:create_entity_outline` and retains its handle for deterministic
closure.

### Claim rules

- `SuperGlueEntity` is claimed independently of Flywheel visualization.
- `AbstractContraptionEntity` and `PackageEntity` are claimed only when
  `VisualizationManager.supportsVisualization` is enabled and execution is
  outside the nesting-safe `CreateEntityOutlineMaskScope`.
- If those conditions are not met, the adapter does not claim the entity and
  the common ping outline remains the only route.

### Contraption and package dispatch

For one claimed contraption or package entity, on the render thread issue
exactly one direct `EntityRenderDispatcher.render` call into the shared outline
buffer through `OutlineOnlyBufferSource`. Use a fresh PoseStack, interpolated
camera-relative position and yaw, the current partial tick, dispatcher light,
the block-atlas texture fallback, and a **262144-vertex** cap. The call runs
inside the nesting-safe mask scope.

Do not flush the shared buffer, call `endOutlineBatch`, mutate entity visibility
or glowing state, or invoke separate state-mutating render helpers. The scope
may disable Flywheel visualization only during this dispatcher call, allowing
Create's complete fallback renderer to contribute structure, child block
entities, actors, bogeys, and package models in one dispatch.

### Super Glue mask

Super Glue uses a camera-relative AABB mask with exactly six quads and 24
vertices. Each quad uses fixed UVs `(0,0)-(1,0)-(1,1)-(0,1)`, the Create glue
texture, and the exact opaque ping color. This direct mask remains independent
of Flywheel visualization support.

The adapter follows the common
[source outcome contract](../architecture/geometry/geometry_sources.md). Normal zero output
is `EMPTY`; a recoverable exception with no committed vertex is `FAILED`; a
recoverable exception after shared-buffer vertices have been committed is
`RENDERED` for that frame with a partial-emission diagnostic. The integration
does not redefine those outcomes.

Keep diagnostics lazy, bounded, and rate-controlled while retaining complete
entity, class, payload, and exception details. Retain and close the registration
handle during deterministic teardown.

## Create/Flywheel entity-block geometry

This is a separate optional source for `entity_block` presentation under mode
`ALL`; it does not compose with the entity-outline dispatcher route. Load it
lazily only after both Create and Flywheel mod-ID checks and register it through
the common optional entity-block geometry registry. Its current source ID is an
implementation identifier, not a public compatibility or stability promise.
The current Flywheel **1.0.6** support contract includes both direct instancing
and indirect backends; it is not a compatibility promise for arbitrary versions.

For every attempt, resolve current live state rather than retaining stale visual
handles. Prefer a direct live `AbstractInstancer`; otherwise resolve the active
indirect backend with `IndirectInstancer.fromState(state)`. Never revive,
unhide, delete, or otherwise mutate stale, hidden, deleted, foreign, or non-live
handles. Skip an invalid handle while preserving valid siblings. If no live
instance remains, report `EMPTY` so the normal next-frame retry and fallback
rules apply.

Before committing any mask, preflight the live visual, instances, models, full
vertex data, materials, and indexed triangles. Build a complete immutable
camera-relative plan; reject the whole unusable plan rather than submitting a
usable prefix. Submit every material texture through vanilla's
`OutlineBufferSource` and emit each indexed triangle as `(v0,v1,v2,v2)` with
its original UVs. Preserve Create's scrolling-UV calculation exactly:

```text
diff + fract(speed * renderTicks + offset) * scale
```

Zero committed output and recoverable partial commits follow the common outcome
contract. In particular, a shared-buffer commit that fails after writing one or
more vertices is `RENDERED` for that frame, while pre-commit plan failures are
`FAILED` or `EMPTY` according to the observed result. Retain and close the
registration handle, and keep full detailed diagnostics lazy, bounded, and
rate-controlled.

## Create presentation summary adapter

A fourth, rendering-independent route registers adapter `create:presentation`
(schema 1) in the versioned presentation snapshot. The client's manifest-only
registration never loads Create classes; the server registers a lazy,
server-thread sampling source. The generic negotiation, demand-driven
capture, policy, and projection contracts are owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md).

The manifest declares kinetic speed (`create:kinetic.speed`, a record of
effective/theoretical signed RPM and a moving flag), `create:kinetic.has_network`,
`create:kinetic.overstressed`, `create:kinetic.stress`, `create:kinetic.capacity`,
and `create:kinetic.available_capacity` as enabled by default, and the item-vault
(`create:inventory.summary`) and fluid-tank (`create:fluid.summary`) registry-ID
summaries as disabled by default. `create:kinetic.capacity` and
`create:kinetic.stress` are numbers holding the network's total stress capacity
and its used stress; the derived number `create:kinetic.available_capacity`
equals `create:kinetic.capacity` minus `create:kinetic.stress` and may be
negative. A demand for the derived field reads the raw capacity/stress pair
once, but projection still carries only the demanded, authorized fields; the
derived field adds no new annotation, and no property create requires a capacity
context. Stress keeps its raw used-stress meaning and is not reinterpreted when
it is disabled or omitted; the game's overstress state remains the separate
`create:kinetic.overstressed` flag. Kinetic quantities use the SU unit, and a
used-stress display may optionally include a percent only when the same
authorized received projection also contains a capacity greater than zero; with
a zero capacity the percent is undefined and omitted. There is no utilization
field, and the percent is never clamped to 100%. The kinetic fields are
default-enabled, so an unmatched kinetic field is authorized for capture and
projection under the recipient's policy unless a block rule matches or
whitelist-only mode is on, and the server policy page's outcome label reflects
that default. Default
authorization is not display: the RPM HUD line appears only when the default
display reference selects it or a create carries a non-null property Ping
annotation naming it, and no target type's default reference currently selects
RPM. The disabled summaries stay disabled until explicitly allowed. Item and
fluid summaries are record values keyed by registry ID; an uploaded property
selection may address one nested registry-ID entry of such a summary. The property reference, recapture, authority, and
display contract is owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md#property-ping).
Selector evaluation and the outcome labels are owned
by [presentation snapshot](../architecture/presentation/presentation_snapshot.md#per-target-type-field-policy).
Sampling is requested only for demanded fields and for a supported whole-block
input. This includes an ordinary whole block and a committed, validated Sable
external-block target, provided the current provider registry identity still
matches the committed expected identity. A committed external read resolves the
provider's active committed source binding and its member scope, and uses the
provider's current tracking-point position for the whole-block collector path;
it does not make the detached placeholder coordinates or opaque locator a
sampling position, and no legacy observation fallback serves a committed
external read. On this committed route, an unrecognized provider, a missing or
inactive lease, wrong dimensions, unloaded chunks, mismatched state, or a
member outside the resolved scope is unavailable rather than asserted. Entity,
contraption-constituent, partial-geometry, and generic foreign-provider targets
are not thereby added to this route. A server interval override can only raise
the adapter's declared minimum sampling cadence.

Vault and tank member verification charges each visited member before its gate
or read and never refunds a failed attempt: a rejected, throwing or
budget-exhausted member keeps the whole visited prefix paid, so a rejected
structure cannot appear cheaper than the work it performed.

A separate preview entry accepts an uncommitted Sable external candidate
through a provider-confirmed read source. It resolves the candidate through the
same provider source as the generic external-block preview, keeps the original
detached candidate identity instead of the physical coordinates, and admits
only positions inside the resolved source's member scope: the root and every
controller, item-vault or fluid-tank member must pass that positive membership
check before its state, block entity or capability read. A candidate without a
resolved positive binding, or a member outside it, is unavailable rather than
read. This preview entry is separate from the committed collection route and
does not widen it, and it remains under the same tested-version and
lazy-loading gates.

Kinetic reads use Create's public speed, network, and overstress getters. Cached
network stress and capacity, and the derived available capacity computed from
them, additionally require a signature-gated accessor. That accessor stays
minimal: it shadows exactly the two protected instance `float` fields holding
stress and capacity, and the derived field adds no third shadowed field.
The tested Create version is `6.0.10` (`6.0.10-281` artifact). The runtime
Create mod metadata must report a tested version for the summary adapter to be
registered at all; an absent or untested version registers no Create adapter
and disables the whole summary route, with no public bridge for an unknown
version. Within that gate, the dedicated cached-accessor mixin additionally
requires an ASM shape check that `KineticBlockEntity` still has those two
protected instance `float` fields the accessor shadows; a shape mismatch
disables only the cached stress/capacity/available-capacity route, while speed,
network, and overstress continue through the public getters, and the adapter
stays optional.

### Client kinetic preview

A separate client preview reader answers `create:kinetic.speed`,
`create:kinetic.has_network`, and `create:kinetic.overstressed` locally for a
live block whose kinetic block entity has a reliable client receipt.
The receipt is accepted only for a client-applied update of a non-virtual,
non-moved block entity with the expected field shape, and a later failed or
empty update invalidates the earlier receipt; a removed or virtual block entity
yields no local value. A provider-confirmed external candidate may use this
reader only through its provider source and the same receipt gate; without that
evidence its kinetic fields fall back per field.

The same reader also answers the cached network totals
(`create:kinetic.stress`, `create:kinetic.capacity`, and the derived
`create:kinetic.available_capacity`) for an ordinary live block or a
provider-confirmed external candidate, using the reliable receipt, the client
cached-field accessor, and the network-present and finite, non-negative gates.
A missing receipt or network, a removed or virtual block entity, or a raw value
that fails its gate yields no local value for that field rather than an
asserted zero; a genuinely received zero remains valid observed data. Those
local totals are last-synchronized values, not guaranteed current world state;
an authoritative property create still recaptures them, as owned by
[property Ping](../architecture/presentation/presentation_snapshot.md#property-ping).
A field the reader cannot observe locally falls back per field to the
authorized server preview; registry-ID summaries have no local quantity
guarantee and always use that fallback, owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md#target-content-preview).
The receipt gate is independent of the cached-accessor gate, and the reader
loads Create classes lazily: speed, network, and overstress stay receipt-backed
without that accessor, while the cached totals need both gates.

Inventory and fluid sampling verify the whole controller structure before
asking a capability: item vaults are read from the verified controller's block
item-handler capability, and fluid tanks from the verified controller's block
fluid-handler capability. Creative tanks, an active boiler's input-only water
handler, removed parts, mismatched controllers, or incomplete verification are
unavailable rather than reported as empty. Amounts are detached and aggregated
by registry ID only; fluid amounts use NeoForge 1.21.1 millibuckets. Summaries
carry explicit `partial` and `scanned` markers, bound scan work independently of
output cardinality, and never retain block entities, handlers, stacks, or
components.

The route fails soft: absent, untested, drifted, or throwing Create state yields
no Create adapter or an unavailable/stale section, and Basic, the base Sable
integration, and unrelated pings keep working. Loader registration, version
gates, and the cached-accessor shape gate have unit and ASM-node seam coverage
only; no installed-Create in-game presentation scenario has run yet, as
recorded in [verification](../testing/verification.md).

## Vault inventory provider

The Vault inventory source is a NeoForge-only inventory provider registered
independently of the registry-ID `create:inventory.summary` presentation field
above; it is an ordinary source for the inventory preview and tracking domain,
not a registry-ID aggregate. It is available only under the tested Create
version gate, and a captured topology requires every member position to be
loaded and recognized, with the original position inside the verified
controller structure, before any member content is read; an unloaded, removed,
mismatched or incomplete member makes the source unavailable. A
provider-confirmed read scope gates the root, the controller and every member
before its state, relation or local handler is read, and the gate is re-checked
for reads through an already acquired member or handler, so a revoked member or
handler is unavailable rather than read. The source
identity is a canonical controller alias, and the captured layout keeps each
member's position, controller/member role, and its own inventory segments so
per-member slot boundaries and variants stay distinct. Unlike the summary, it
reads each verified member's local inventory rather than the controller's
combined item-handler capability. Snapshot capture, recovery, budget and
variant semantics remain owned by
[inventory preview and tracking](../architecture/presentation/inventory.md).

## Create block presentation resolvers

### EntityBlock doors

The door specialization applies only to:

- `create:andesite_door`
- `create:copper_door`
- `create:brass_door`
- `create:train_door`
- `create:framed_glass_door`

The source must be a supported DoorBlock that also implements EntityBlock and
is already classified as `entity_block`. The specialized resolver runs before
the generic vanilla door resolver, reuses its validated lower/upper composite,
and preserves the source `entity_block` type on both subjects.

Create's lower door BER owns the complete two-block renderer, but the upper
subject is covered only after the lower subject's exact BER source reports
`RENDERED`. A baked-model or Flywheel success, an attempted BER, ownership,
`EMPTY`, `FAILED`, or unavailable geometry cannot claim that coverage. Until
then, upper-subject sources and fallback remain eligible. The generic coverage
contract is in [presentation subjects](../architecture/rendering/presentation_subjects.md).

### Large water-wheel master proxy

An invisible structural large-water-wheel block may present through Create's
verified live terminal master. The resolver follows Create's structural-block
relationship and master lookup; it does not scan entities, chunks, block
entities, or Flywheel visuals to discover an owner. A valid master is presented
as the real `create:large_water_wheel` `entity_block` subject with a
proxy-to-owner relation. An invalid relationship yields no invented direct
presentation. The original marker identity remains the captured structural
target; only presentation selects the master rendering form.

## Verification boundary

Build and automated-coverage evidence is owned by
[verification](../testing/verification.md), including the
[pending Create gameplay and performance scenarios](../testing/verification.md#pending-manual-and-integration-matrix).
