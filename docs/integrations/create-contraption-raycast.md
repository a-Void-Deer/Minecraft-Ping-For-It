# Create contraption ray targeting

This supplement owns Create-specific transforms, captured views, optional
loading, cost and limitations. Shared geometry ownership and the native picking
kernel are owned by [entity-local picking](../architecture/picking/local_geometry.md);
[D0006](../decisions/D0006-exact-owned-geometry.md) explains that boundary.
The integration overview is [Create](create.md).

## Scope

On Minecraft 1.21.1, the NeoForge integration refines ping targeting for these Create entity types:

| Entity ID | Create entity |
| --- | --- |
| `create:stationary_contraption` | Controlled contraption |
| `create:contraption` | Oriented/moving contraption, including minecart-mounted structures |
| `create:carriage_contraption` | Carriage contraption |
| `create:gantry_contraption` | Gantry contraption |

The adapter is compiled against Create `6.0.10-281`. Create remains optional. The adapter is independent of Flywheel and of the outline-rendering backend.

A successful hit still creates a marker for the exact contraption entity. It does not combine carriages into a train target, replace a mounted contraption with its minecart, or enable constituent-block markers.

## Existing target-selection settings

Contraption picking consumes the same frozen ray and immutable
[selection policy](../architecture/picking/selection_policy.md) as world picking.
Sampling is owned by [press-time capture](../architecture/picking/capture.md),
including the [deferred compatibility boundary](../architecture/input/long-press-compatibility.md).
The integration adds no separate block/fluid toggle or capture range; native
shape selection and competition follow [local geometry](../architecture/picking/local_geometry.md).

Fluid hits retain the hosting block's registry ID and the fluid's registry ID
separately.

Only fluid state represented by captured contraption block states is available, including represented waterlogged states or fluid blocks. The integration does not invent missing fluid cells, change Create assembly/disassembly, or alter Minecraft fluid-shape caches. Neighbor-sensitive fluid queries use the captured local view. Native `getHeight` and cached `getShape` may differ after a canonical fluid state has already had its shape computed; targeting follows the native shape returned for the request.

## Selection pipeline

The source ID is `pingforit:create_contraption_raycast`. It participates in the
existing world clip and broad-phase entity query under the owner-snapshot,
admission, ordering and failure rules of
[entity-local picking](../architecture/picking/local_geometry.md).

For an owned Create candidate:

1. Transform the captured finite segment into contraption-local coordinates
   using Create's native transform at partial tick `1.0`.
2. Capture local block states, represented fluid states, portal-hidden positions
   and available existing client block entities for the synchronous attempt.
3. Supply those native shapes at their actual local positions to the common
   picking kernel, then return the precise whole-entity hit to common nearest-hit
   competition.

The returned hit's interaction with Sable and Distant Horizons is owned by
[capture](../architecture/picking/capture.md#integration-and-authority-boundaries).

## Local geometry and optional loading

The Create-free ownership shell is registered after the Create mod-ID check and
lazily loads the Create-dependent delegate. Ownership of the known IDs survives
delegate unavailability, preserving the common owned-candidate contract after an
optional linkage failure. Its registration is closed on client-session teardown
and reset for re-registration. Detailed failure diagnostics are bounded;
targeting does not add toast or action-bar feedback.

The integration supplies the snapshot-local block view and collision context to
the common scanner. Missing or portal-hidden local positions are air. Fluid
state is derived from captured block state. Block entities come only from
already-existing Create client state; ordinary-world lookups at local
coordinates are not used.

Positional collision-context checks use the camera's transformed local feet.
Non-positional context behavior is delegated. The common native-shape algorithm
is owned by [local geometry](../architecture/picking/local_geometry.md#distance-and-native-local-shapes).
The reasons for using it instead of Create's interaction picker or native
`VoxelShape.clip` are recorded in [D0006](../decisions/D0006-exact-owned-geometry.md#alternatives-considered).

## Captured detail and future constituent markers

Create contributes entity-local capture metadata under the common
[identity-retention contract](../architecture/picking/local_geometry.md#frozen-metadata-and-whole-entity-identity).
Its result remains subject to [target locking](../architecture/picking/capture.md).

A future whole-versus-constituent option can derive its target from this frozen hit. A moving constituent must not be identified by an ordinary world `BlockTarget`. A future provider would need the dimension, exact contraption owner UUID, local block position, and expected registry identity, together with authoritative validation and lifecycle handling. Existing external-block provider facilities are a possible foundation, not an implemented Create constituent provider.

Whole-entity requests retain the [common authority boundary](../architecture/picking/local_geometry.md#frozen-metadata-and-whole-entity-identity).
The exact-surface versus server-anchor distance distinction is owned by
[range acceptance](../architecture/picking/range.md#server-acceptance).

## Cost and validation boundaries

Snapshotting and scanning are approximately linear in captured entries plus native shape boxes, once per ping capture rather than every rendered frame. There is no fixed traversal-cell cap, persistent shape index, or global geometry cache in this integration. Very large overlapping contraptions require performance measurement; no latency guarantee is made.

Discovery still depends on Create's entity bounds. Native selection shapes and Create transforms do not promise pixel-perfect agreement with GPU-rendered meshes or every render-only minecart correction.

Automated coverage is inventoried in
[verification](../testing/verification.md#exact-entity-local-picking-and-create-raycast-seams);
the [pending manual matrix](../testing/verification.md#pending-manual-and-integration-matrix)
owns the Create gameplay and performance scenarios.
