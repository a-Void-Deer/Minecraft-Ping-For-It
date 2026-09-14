# Create contraption ray targeting

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

The policy is captured when the ping key is first pressed. The same policy controls world picking and the selected native shapes inside a contraption:

| Setting | Disabled | Enabled |
| --- | --- | --- |
| Pass through transparent blocks | `ClipContext.Block.OUTLINE` | `ClipContext.Block.VISUAL` |
| Mark fluids | `ClipContext.Fluid.NONE` | `ClipContext.Fluid.ANY` |

These are native shape strategies, not opacity tests or a hard-coded transparent-block list. `VISUAL` follows each block's implementation; it does not imply that every translucent model is pass-through. `OUTLINE` can select visible decorations whose collision shape is empty.

When fluids are enabled, the block and fluid shapes compete by distance. A block wins an exact block/fluid tie. Fluid hits retain the hosting block's registry ID and the fluid's registry ID separately.

Only fluid state represented by captured contraption block states is available, including represented waterlogged states or fluid blocks. The integration does not invent missing fluid cells, change Create assembly/disassembly, or alter Minecraft fluid-shape caches. Neighbor-sensitive fluid queries use the captured local view. Native `getHeight` and cached `getShape` may differ after a canonical fluid state has already had its shape computed; targeting follows the native shape returned for the request.

## Selection pipeline

1. Capture one finite world-space ray and its selection policy at the press edge.
2. Keep the existing world clip and broad-phase entity query.
3. Resolve an exact entity-type geometry owner before admitting a candidate into nearest-hit selection.
4. For an owned Create candidate, transform the captured segment into contraption-local coordinates using Create's native transform at partial tick `1.0`.
5. Capture local block states, represented fluid states, portal-hidden positions, and available existing client block entities for the synchronous attempt.
6. Evaluate eligible native block/fluid shapes at their actual local positions and select the nearest real surface.
7. Compare that result against other entities and the world hit using the existing strict nearest-hit rules.

The coarse entity AABB is only a candidate bound. A ray through a hole must not select the surrounding contraption merely because it intersects that bound. Rejecting one candidate still allows another entity or a world block behind it to win. The final precise contraption hit remains an `EntityHitResult`, preserving the existing Sable/block and Distant Horizons/miss branching.

The source ID is `pingforit:create_contraption_raycast`. Registrations are ordered explicitly by numeric priority and source ID. The registry snapshot is fixed for one ray.

| Ownership/result | Candidate handling |
| --- | --- |
| No registered owner | Existing entity AABB selection |
| Owned `HIT` | Use the precise surface hit |
| Owned `MISS` | Skip the candidate |
| Owned `UNAVAILABLE` | Skip the candidate |
| Owned `FAILED` | Skip the candidate |

An owned non-hit never falls back to the coarse AABB. Recoverable capture or scan failures invalidate the entire candidate attempt, including provisional hits. Other candidates remain eligible.

## Local geometry and optional loading

`CreateContraptionRaycastAdapter` is a Create-free ownership shell. It is registered after the Create mod-ID check and lazily loads `CreateContraptionRaycastDelegate`. Ownership of the known IDs survives delegate unavailability, preventing an AABB fallback after an optional linkage failure. Its registration is closed on client-session teardown and reset for re-registration. Detailed failure diagnostics are bounded; targeting does not add toast or action-bar feedback.

`CreateContraptionRaycastEngine` supplies the snapshot-local `BlockGetter` and collision context to the common `NativeLocalShapeRaycaster`. Missing or portal-hidden local positions are air. Fluid state is derived from captured block state. Block entities come only from already-existing Create client state; ordinary-world lookups at local coordinates are not used.

Positional collision-context checks use the camera's transformed local feet. Non-positional context behavior is delegated. No live entity, world, block state, fluid state, or shape is retained in the locked domain metadata.

The common scanner uses `ClipContext.getBlockShape` and `getFluidShape`, then intersects the finite segment with boxes emitted by native `VoxelShape.forAllBoxes`. Those boxes are the selected native shape's exact decomposition; they are not unit-cube approximations. All candidate positions are considered, including shapes extending outside their owning block cell. Exact equal-distance child hits use deterministic local-position ordering.

Create's interaction picker is not reused: it selects outline shapes and its traversal has a roughly 201-step limit. Native `VoxelShape.clip` is also not used for the precise kernel because its interior probe is proportional to the complete segment length (`delta * 0.001`). The segment kernel handles origin containment and boundaries without that length-scaled probe. Existing entity endpoint exclusion and global block/entity tie rules are retained.

## Captured detail and future constituent markers

The detailed raycast result carries transient data bound to the exact selected entity. `MinecraftTargetSnapshotFactory` rejects mismatched owner detail, and `PingCaptureCoordinator` retains detail only if resolution preserves the same entity target.

`EntityLocalGeometryMetadata` reaches `CapturedPingContext` with copied values: source ID, block/fluid kind, local block position, expected block/fluid registry identities, and local/world hit points. Release, wheel selection, timeout, and camera or contraption movement do not trigger another selection ray.

A future whole-versus-constituent option can derive its target from this frozen hit. A moving constituent must not be identified by an ordinary world `BlockTarget`. A future provider would need the dimension, exact contraption owner UUID, local block position, and expected registry identity, together with authoritative validation and lifecycle handling. Existing external-block provider facilities are a possible foundation, not an implemented Create constituent provider.

Current packets and whole-entity server authority are unchanged: the server validates identity, liveness, classification, and range from the entity anchor. It does not replay the client's original ray or validate its precise geometry. A near surface on a very large contraption can still be outside the accepted whole-entity anchor range.

## Cost and validation boundaries

Snapshotting and scanning are approximately linear in captured entries plus native shape boxes, once per ping capture rather than every rendered frame. There is no fixed 201-cell cap, persistent shape index, or global geometry cache in this integration. Very large overlapping contraptions require performance measurement; no latency guarantee is made.

Discovery still depends on Create's entity bounds. Native selection shapes and Create transforms do not promise pixel-perfect agreement with GPU-rendered meshes or every render-only minecart correction.

Automated regression coverage targets the common candidate pipeline, native shape/policy scanner, immutable capture bridge, and Create-free engine/loading seams. These tests and optional-API compilation do not constitute an in-game Create validation.

Manual validation checklist:

- Hollow, L-shaped, sparse, and non-full-block structures: hit occupied surfaces and pass through holes.
- Front empty AABBs, overlapping structures, and intervening world walls: select the nearest actual target.
- All four transparent-block/fluid-setting combinations, including represented waterlogged blocks and partial fluid shapes.
- Controlled rotations, moving and minecart-mounted structures, pitched carriages, and gantry structures.
- Current-dimension portal-hidden portions and data availability during client loading.
- Long rays, starting inside only the broad bounds, and starting inside an actual selected shape.
- Hold the ping key while the camera or structure moves: retain the press-time target.
- Create absent, delegate unavailable, client reconnect, and different Flywheel/outline backends.
- Large structures: measure press-edge targeting time.
