# NeoForge Create integration

Create remains optional. All Create-dependent delegates are loaded lazily only
after the relevant mod-ID checks, and missing classes, API drift, unavailable
live state, or rejected registrations must fail soft without breaking ordinary
entity, block, or location pings. Common registries and outcome contracts remain
free of Create and Flywheel dependencies.

The integration has three separate primary routes--contraption press-time
picking, entity outlines, and Flywheel entity-block geometry--plus narrow block
presentation resolvers. Their gates and lifecycles are independent.

## Contraption press-time picking

The Create raycast source refines only the four supported contraption entity
types and preserves whole-entity marker identity. It is independent of
Flywheel and both outline backends. An owned candidate is accepted only after
an exact local-shape `HIT`; owned non-hits never recover through the coarse
entity AABB.

The complete transform, immutable local-view, represented-fluid, native-shape
kernel, capture metadata, cost, limitation, and manual scenario contract is
retained in [Create contraption ray targeting](create-contraption-raycast.md).
The generic owner-result rules and rejected coarse-bound alternative are in
[entity-local picking](../architecture/picking/local_geometry.md) and
[D0006](../decisions/D0006-exact-owned-geometry.md).

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

Optional API compilation, structural source checks, and headless outcome tests
do not constitute in-game Create validation. Create-specific kernel and motion
scenarios remain listed in the contraption raycast supplement; shared build,
test, and manual status is reported by the repository's verification document.
