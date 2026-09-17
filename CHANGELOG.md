# Ping For It Changelog

Changes on `agent/create-raycast-fix` since `56b5b22789200188fce7e4c28c3d6af6ab5def4c`.

### Precise Create contraption targeting

- Refine each supported contraption candidate before choosing the nearest target. The entity's coarse AABB is now only a candidate bound, so aiming through an empty part of a structure can select an entity or world block behind it instead of falsely selecting the surrounding structure.
- Add NeoForge support for controlled, oriented/moving, carriage, and gantry contraptions, including minecart-mounted structures. Use Create's native coordinate transforms and exclude portal-hidden portions so selection follows the structure's local geometry.
- Intersect finite rays with the exact boxes of the selected native `VoxelShape`, including thin, non-full, and protruding shapes. This avoids Create's interaction-ray traversal limit and Minecraft's length-scaled interior probe, while selecting the nearest constituent independently of block-map order.

### Existing selection settings inside structures

- Apply the transparent-block setting to contraption blocks through the same native `OUTLINE`/`VISUAL` strategies used for world picking, rather than restricting selection to collision shapes or introducing an opacity-based block list.
- Apply the fluid setting through native `NONE`/`ANY` shape selection. Block and fluid surfaces compete by distance, with blocks winning exact ties; fluid hits retain both the hosting block and fluid registry identities.
- Serve block, fluid, neighbor, and available block-entity queries from a captured contraption-local view. This supports represented waterlogged and fluid states without accidentally reading ordinary-world data at local coordinates. Fluid targeting follows native shape behavior rather than inventing missing fluid cells or replacing native fluid caches.

### Stable capture and optional integration

- Add deterministic entity-geometry ownership and an immutable per-ray registry snapshot. Once a candidate is owned, misses, unavailable data, and recoverable failures exclude it without reviving coarse-AABB selection; other targets remain eligible.
- Preserve immutable local hit details through the press-time capture pipeline and bind them to the exact selected entity. This provides the foundation for future whole-structure versus constituent-block selection without re-raycasting on release or wheel interaction. Markers currently still target the whole contraption entity.
- Load the Create delegate lazily behind a Create-free ownership shell, independently of Flywheel and outline rendering. Retain ownership on optional linkage failure, reset registration on client-session teardown, and add bounded detailed diagnostics so unavailable integration paths remain isolated and diagnosable.

### Documentation

- Add `docs/create-contraption-raycast.md` to explain selection settings, the geometry pipeline, captured data, optional-loading behavior, current limitations, and the future constituent-target extension boundary.
