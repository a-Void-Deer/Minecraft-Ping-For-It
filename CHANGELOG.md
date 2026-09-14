# Ping For It Changelog

### Block presentation and compatibility

- Added Fabric render attachment support using the real level and block position for world-aware block-entity model glow.
- Added Forge and NeoForge support for live ModelData while preserving the original RenderType.
- Fixed Refined Storage 2 cable, importer, and exporter glow that previously rendered only their core geometry.
- Ordinary blocks retain virtual BlockDisplay glow.
- Block entities use VoxelShape only after normal BER, world-aware, and optional geometry sources are unavailable.
- Added client-side presentation-target resolution that separates marker identity from render subjects.
- Added full two-block door and bed outlines with per-subject fallback.
- Added Create large water-wheel structural-block redirection to the validated master.
- Added Simulated Project paired docking-connector redirection to the powered owner and its owner BER/fallback path.
- Preserved the established multipart render routes: beds remain entity-block presentations and attempt permitted BER, baked-model, and optional geometry before VoxelShape fallback, while doors remain ordinary block presentations.
- Resolved geometry success and VoxelShape fallback per stable render subject, preventing one emitted part from suppressing another part's outline.
- Added deterministic, fail-soft presentation-resolver and world-aware-adapter registration so unavailable optional integrations do not disrupt ordinary block outlines.
- Kept shape fallbacks tied to each subject's live native VoxelShape edges rather than using a full-cube approximation.
- Fixed Create door being rendered as `Block` outline instead of `EntityBlock` outline, causing they always falling back to VoxelShape.

### Configuration migration

- Added ordered, config-type-aware migrations that preserve existing settings and unknown JSON fields, updating the version marker only after all applicable transformations complete.
- Server configurations older than `0.3.0-pfi-beta1` now migrate `pingDuration` to `syncDuration` without unit conversion; an existing `syncDuration` wins and the consumed legacy field is removed.
- `pingDuration` in `0.3.0-pfi-beta1` and later server configurations is intentionally not treated as a legacy alias, so a configuration containing only that field uses the normal `syncDuration` default.
- Current-version configuration files are left unchanged when loaded, avoiding unnecessary on-disk rewrites.

### Shader and native-shape outline tracking

- Fixed native VoxelShape outlines that could ghost or trail during target or camera movement with TAA-enabled shader packs, including Iris with Complementary Reimagined.
- Moved the no-depth native line flush after world-composite work so it no longer participates in TAA scene-depth reprojection, without requiring shader-mod detection or load-order priority.
- Captured each world frame's transform and camera state for the late pass, then restored the ambient render state afterward to keep outlines aligned to their own frame.
- Discarded abandoned frame snapshots between world passes and consumed each captured frame only once, preventing stale late-outline draws.
- Retained live native `VoxelShape` edge rendering with vanilla `rendertype_lines` `LINES`, the 3.75 px no-depth/color-only state, and late submission so outlines remain visible through occluding blocks.

### Test coverage and maintenance

- Strengthened render-pipeline regression coverage for these glow paths.
- Added regression coverage for ordered migration sequencing, version bounds, field conflicts, preservation, and version stamping only after successful migration steps.
- Added frame-coherence and compiled-route coverage for post-composite native lines, camera and target motion at large world coordinates, one-time frame consumption, and render-state restoration.
- Made the NeoForge world-aware model-outline contract test portable across Windows line endings; adapter behavior is unchanged.
