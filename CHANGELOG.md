# Ping For It Changelog

## Unreleased

- Added Fabric render attachment support using the real level and block position for world-aware block-entity model glow.
- Added Forge and NeoForge support for live ModelData while preserving the original RenderType.
- Fixed Refined Storage 2 cable, importer, and exporter glow that previously rendered only their core geometry.
- Ordinary blocks retain virtual BlockDisplay glow.
- Block entities use VoxelShape only after normal BER, world-aware, and optional geometry sources are unavailable.
- Strengthened render-pipeline regression coverage for these glow paths.
