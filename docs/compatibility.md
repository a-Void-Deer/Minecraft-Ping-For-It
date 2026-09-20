# Compatibility and scope boundaries

## Platform and original mod

The existing fork targets Minecraft **1.21.1** with the current supported loader
structure of **Fabric, Forge and NeoForge**. The mod ID is **`pingforit`**,
distinct from the original Minecraft-Ping-Wheel mod ID.

There is no backward network/protocol compatibility with the original mod.
Do not declare metadata incompatibility or perform runtime blocking against it;
do not add an original-mod runtime conflict guard absent a future product
decision. Simultaneous installation is not explicitly blocked, but
interoperability/cross-mod behavior is not guaranteed.

## Optional content fails soft

Optional Target Type content may be absent, partially present or wholly
unavailable. Ignore missing registry entries while retaining present referenced
content. A group with no valid concrete content is inactive and non-matching.
Optional classes must be referenced lazily/indirectly enough that a missing mod
cannot fail class loading.

The same rule applies to optional block/entity registrations, integration
targets and rendering sources. Missing optional content must not break unrelated
location, ordinary entity or ordinary block pings. Keep common registries free
of hard optional-mod dependencies and respect deterministic teardown handles.

- [Create](integrations/create.md) describes the three separate NeoForge routes
  and their different Create/Flywheel gates.
- [Sable](integrations/sable.md) describes the existing external-block provider.
- [Capture](architecture/picking/capture.md) preserves asynchronous Distant Horizons behavior.

Optional integration support is version- and API-shape-specific. Reflective or
lazy discovery may fail soft when the detected implementation does not provide
the expected contract; this is not a promise of compatibility with arbitrary
future versions. In particular, the current Sable adapter targets its established
2.0.5 API shape, while Create/Flywheel support follows the versions documented
by its integration contract.

## Fixed exclusions and preserved behavior

- Target Types and Ping Types are code-defined. Do not add a config definition
  system, datapack format, user-definition mechanism, plugin API or generalized
  scripting layer for them in this iteration. Client display settings and the
  internal optional-source registry do not relax this boundary.
- No backward original-mod protocol compatibility, cross-dimension entity
  tracking or Immersive Portals integration.
- No hard dependency created by absent optional content.
- Native shape-based block outlines are sufficient where the shape route
  applies; pixel/model-silhouette outlines are not required there. This does not
  authorize replacing an eligible glow path with shapes or approximating native
  shapes with full cubes.
- Existing ping range, lifetime, cooldown and comparable mechanics remain
  unchanged unless a requirement explicitly changes them.
- Unrelated refactors, formatting sweeps, dependency upgrades, loader migrations
  and architecture rewrites remain outside the scope of this work.

Future Create constituent-marker ideas in the
[raycast supplement](integrations/create-contraption-raycast.md) are design
boundaries, not an implemented provider or a new feature commitment.
