# Geometry sources and source outcomes

This document owns the common geometry-source contract. Optional integrations
may describe when they register or claim work, but they do not redefine source
ordering, outcomes, fallback, or recoverable-failure semantics. The end-to-end
stage boundaries are summarized in the
[geometry pipeline](../geometry-pipeline.md).

## Model eligibility is not renderability

A presentation subject may expose several independent ways to obtain geometry:
a live BlockEntityRenderer (BER), a loader-aware Minecraft baked model, an
optional source such as Flywheel, or the native VoxelShape fallback. Minecraft
`RenderShape.MODEL` is only an eligibility condition for a baked-model attempt.
A `BakedModel` is one concrete source, not a synonym for every object that can
produce geometry.

The layers remain separate. The loader adapter resolves a model and its render
inputs, the renderer/dispatcher supplies the destination and live world
context, and the model or renderer may or may not commit geometry. In
particular, the NeoForge adapter replaces null model data with
`ModelData.EMPTY`, then still enumerates render types and calls the batched
renderer. Missing model data therefore does not itself determine the final
outcome; `EMPTY` is determined by whether the permitted source commits any
geometry. A missing live object or renderer can still make the BER source
empty, and an unavailable adapter can make the baked-model source unavailable.

For documentation purposes, a subject is renderable in the current frame only
when an allowed source reports `RENDERED`: geometry was actually emitted and
committed to the destination that will be submitted. Merely having a model,
claiming a subject, entering a source, or owning multipart presentation does not
establish that result. This distinction is recorded in
[D0001](../../decisions/D0001-separate-model-from-renderable.md).

## Entity-block mode and source order

`entity_block` source selection is client-local and read for every render
attempt/frame, without server synchronization or reconnect caching. Ordinary
`block` rendering does not read this mode. Persistence defaults and recovery
are specified in [client settings](../../config/client.md).

After the outer [native-glow gate](../rendering/outline.md) is eligible, sources
are selected as follows:

| Mode | Permitted source execution |
| --- | --- |
| `ALL` | Built-in BER, built-in loader-aware baked model, then one immutable optional-source registry snapshot |
| `COMPATIBLE` | Built-in BER, then built-in loader-aware baked model; no optional-source snapshot |
| `VOXEL_SHAPE_ONLY` | Construct no source context and attempt no normal source; select the native shape route |

The BER requires a valid live BlockEntity and renderer. The ordinary baked
source requires live render shape `MODEL` and an applicable world-aware loader
adapter; it does not require a live BlockEntity merely because the subject's
target type is `entity_block`. An entity-block subject may therefore enter the
ordinary baked-model adapter even when its BlockEntity is null, while the BER
attempt independently remains empty without its live object and renderer. The
absence of an adapter, model, renderer, or usable committed geometry leaves the
corresponding source unavailable or empty; it does not authorize an approximate
substitute.

All permitted sources run in order without short-circuiting. A dynamic BER and
a static baked model may both contribute, so one successful source does not stop
the remaining permitted sources on the same uncovered subject. The combined
result suppresses VoxelShape fallback only if at least one source reports
`RENDERED`.

## Common per-attempt outcome contract

Every geometry source has exactly one final outcome for an attempt:

| Observation | Outcome | Effect |
| --- | --- | --- |
| Normal completion with no committed vertices | `EMPTY` | Continue permitted sources; retain fallback eligibility |
| Recoverable failure before any committed vertex | `FAILED` | Diagnose the failure, continue permitted sources, and retain fallback eligibility |
| One or more committed vertices, including a later recoverable failure | `RENDERED` | Treat the subject as rendered for this frame and suppress duplicate fallback |

The word **committed** is important. A built-in attempt may render into an
attempt-local buffer that can be discarded completely on failure; uncommitted
partial work in such a buffer is `FAILED`, not `RENDERED`. A source writing to a
shared destination may be unable to roll back vertices committed before a
recoverable exception. That attempt is `RENDERED` for the current frame and
must produce a partial-emission diagnostic, preventing a duplicate fallback
mask from being drawn over it. Sources are retried normally on later frames.

If no normal source is permitted, available, or `RENDERED`, the subject remains
eligible for [native VoxelShape fallback](voxel_shape.md). `EMPTY` and `FAILED`
are intentionally different diagnostic outcomes but have the same fallback
effect. A source result must not be inferred from configuration, ownership, or
an invocation attempt.

Recover only `Exception`, `LinkageError`, and `AssertionError`. Fatal JVM and
resource errors propagate. Detailed diagnostics remain lazy, bounded, and
rate-controlled while retaining complete target, component, payload, and
exception details; see [security](../security.md).

## Internal optional-source registry

The entity-block optional-source registry has no Create or Flywheel dependency.
It stores one immutable volatile snapshot, accepts validated namespaced source
IDs, uses first-duplicate-wins and registration order, and returns an idempotent
handle for each accepted registration. Optional adapters retain their handles
and close them during deterministic teardown. This is an internal compatibility
seam, not a user-configurable definition format or public plugin API.

This registration order is independent of both the numeric-priority/source-ID
order used for [entity-local picking](../picking/local_geometry.md) and the
priority/declaration order of [Target Types](../identity/catalogs.md).

## Presentation and coverage

BER, loader-aware baked-model, optional geometry, and fallback routes consume
the same resolved [presentation subjects](../rendering/presentation_subjects.md),
including each subject's render-target type. Source-conditioned coverage may
suppress only its declared duplicate subject and only after the required source
reports `RENDERED`. `EMPTY`, `FAILED`, unavailable sources, and unrelated
rendered sources cannot claim that coverage.

[Create integration](../../integrations/create.md) supplies optional sources and
presentation resolvers while remaining subject to this common contract.
