# Ping For It 0.2.0-pfi-beta1

## Overview

These notes describe only the changes on `flywheel-support-and-language-polish`
relative to the local `1.21.1` baseline. Baseline mod identity, target locking,
server-authoritative markers, the base protocol, and multi-loader support are
not repeated as new features here.

The release targets Minecraft `1.21.1`. Create/Flywheel integration is optional
support for NeoForge only.

- The mod icon has been refreshed for this beta.

## Chat and localization

- Chat templates can now be overridden independently for each Ping Type. An
  override is resolved only from the currently selected locale's resource stack,
  so another locale cannot supply a template by accident.
- English and Simplified Chinese chat text received additional polish, alongside
  broader localization updates for the settings and server-configuration UI.
- The bundled resource set now contains exactly eight locales: `en_us`, `de_de`,
  `es_ar`, `fr_fr`, `pl_pl`, `tr_tr`, `zh_cn`, and `zh_tw`. The former `uk_ua`
  resource was removed.

## Unified settings

- The settings screen now includes the server settings for authorized players:
  default channel, player tracking, `msToRegenerate`, and `rateLimit`.
- Server requests use request IDs so delayed responses cannot overwrite newer
  state. Permissions are checked again on the server when requests and updates
  are processed, rather than only when the screen is opened.
- Updates send dirty fields only. Malformed, unknown, or negative values are
  rejected instead of being applied, and disconnects clear the server-settings
  state held by the client.
- The old `ServerCommandBuilder` path was removed. `/pingforit config` now opens
  the unified settings screen, with the related labels and messages localized.

## External list and configuration handling

- The block display whitelist and shape blacklist are not edited in a GUI list.
  Saving and closing the settings screen opens the client configuration file
  with the platform file opener. External list edits take effect after a client
  restart; reopening settings in the same session does not reload the file.
- When a client configuration is corrupt, recovery first creates a
  `.broken-...bak` backup and then uses an atomic replacement for the reset
  file. If the backup cannot be created, the original file is preserved and
  reset and normal saves are prevented from overwriting it.
- Configuration files now record the `pingforit-version` of the mod version
  that wrote them. When a newer mod loads an older config, `ConfigHandler` uses
  `ConfigVersionUpdater` to update the marker automatically while preserving
  user values, with the same backup and atomic-write safety as other config
  writes. `PingForItVersion` and `MavenComparableVersion` provide strict
  Ping For It version parsing and Maven-compatible ordering.

## Block and block-entity outlines

- `blockDisplayWhitelist` now defaults to exactly `*:*`, while the separate
  `blockShapeBlacklist` defaults to empty. Entries support exact
  `namespace:block`, namespace wildcard `namespace:*`, global wildcard `*:*`,
  and block tags such as `#namespace:tag`. Matching uses union semantics;
  invalid entries and missing IDs or tags fail soft without disabling other
  routes, and a blacklist match always takes precedence.
- The native model-glow route applies to an ordinary `block` target only when
  it has no `BlockEntity` and its current state has render shape `MODEL`.
  An `entity_block` target must have a `BlockEntity` and has independent
  attempts for its real `BlockEntityRenderer` geometry and live baked-model
  geometry. Non-eligible or non-whitelisted targets use the shape route.
- Entity-block geometry has the local modes `ALL`, `COMPATIBLE`, and
  `VOXEL_SHAPE_ONLY`. The default and recovery value is `COMPATIBLE`.
  `ALL` runs the built-in renderer and baked-model sources plus registered
  optional sources; `COMPATIBLE` runs the built-in sources; and
  `VOXEL_SHAPE_ONLY` constructs no source context. All allowed sources run
  independently. A temporary buffer records each attempt, and only a
  `RENDERED` result suppresses the native shape fallback; empty, failed, and
  zero-vertex attempts fall back to the shape. Current-state model offset and
  position-derived render seed are used for each validated render attempt.
- The shape route uses live native `VoxelShape` edges through
  `VoxelShape#forAllEdges`; it does not approximate a shape with a full cube.
  Its render invariant is `LINES` with vanilla `rendertype_lines`,
  `NO_DEPTH_TEST` / `GL_ALWAYS`, color-only writes, late composite submission,
  and a fixed 3.75 px line width. This is the through-wall route; native model
  glow remains a separate route. These notes do not claim in-game rendering
  validation.

## Optional outline-source architecture

- Optional outline sources run in explicit deterministic order from one
  immutable snapshot. Duplicate source IDs use the first registration, and
  each registration returns a closeable handle for teardown.
- Source attempts have the explicit outcomes `RENDERED`, `EMPTY`, and
  `FAILED`. Recoverable failures are isolated from the other sources and emit
  detailed but rate-limited diagnostics. Entity locators resolve live movement
  rather than freezing a moving target.
- Debug and hitbox line geometry (`LINES`/`LINE_STRIP`), non-quad geometry, and
  formats without UV0 no longer reach the shared textured outline buffer,
  fixing the F3+B client crash (`Missing elements in vertex: UV0`). Variant and
  fallback outline routing now accepts only textured `QUADS` geometry with UV0.
- The outline paths use scoped rendering and do not modify vanilla global glow
  or scoreboard-team state.

## Optional Create/Flywheel integration

- The NeoForge adapter targets Create `6.0.10` and Flywheel `1.0.6` at compile
  time. It checks for the optional mod before reflective lazy loading, so other
  loaders and installations without the optional content remain on the common
  paths.
- The optional visualization-scope Mixin is declared as an interface to match
  Flywheel's interface target, fixing a runtime `InvalidMixinException` caused
  by a target-type mismatch that previously prevented the Create entity outline
  pass from applying when Create and Flywheel were present.
- Direct Flywheel instances are preferred, with indirect parent instances used
  when available. Hidden, deleted, foreign, or otherwise non-live states are
  skipped without visibility or deletion mutations, while valid siblings remain
  eligible. Live mesh, material, UV, transform, and scrolling data are planned
  within vertex and model budgets, with weak caching to avoid retaining stale
  visual objects.
- Geometry is submitted as a vanilla outline-buffer silhouette mask. Each
  indexed triangle expands to four vertices `(v0, v1, v2, v2)`, retaining the
  source UVs and ping color. Optional source registration handles are retained
  and closed with the integration lifecycle.
- Create entity outlines keep SuperGlue independent. Contraption and package
  geometry is claimed only when `VisualizationManager.supportsVisualization` is
  enabled outside the temporary visualization scope. A claimed entity receives
  one direct dispatcher render into the shared outline buffer through
  `OutlineOnlyBufferSource`; the adapter does not flush, call
  `endOutlineBatch`, or mutate visibility or glowing state. A nested scope
  disables visualization only for that dispatch, allowing Create's complete
  fallback render to include structure, child block entities, actors, bogeys,
  and package models.
- SuperGlue uses a camera-relative AABB mask of six quads and 24 vertices with
  fixed UVs and the opaque ping color. A zero-vertex attempt is treated as
  empty or failed and can use the native fallback; a recoverable exception
  after vertices have been committed preserves the partial mask for that frame,
  suppresses a duplicate shape overlay, and retries normally on the next frame.
  Detailed diagnostics remain rate-limited.

## Test coverage

The branch adds focused automated coverage for the new areas, including:

- locale-scoped Ping Type template lookup, the eight-locale resource set, and
  localized unified-settings text;
- server-settings request IDs, permission rechecks, dirty-field updates,
  invalid-value rejection, disconnect cleanup, and packet/state handling;
- external configuration recovery, backup preservation, atomic replacement, and
  the block-list whitelist/blacklist grammar and route gates;
- config version migration, `PingForItVersion`, and `MavenComparableVersion`
  ordering;
- outline-buffer format-filtering regressions for line/no-UV geometry,
  backing-source isolation, and the F3+B scenario;
- entity-block modes, independent geometry-source outcomes, immutable source
  snapshots, duplicate handling, closeable registrations, entity locators, and
  optional-class safety;
- the VoxelShape invariant in production `BlockOutlineRenderType` state and
  the native `VoxelShape#forAllEdges` edge route. Native-glow whitelist tests
  are covered separately;
- Create/Flywheel direct and indirect instance selection, live-state handling,
  transforms, materials, UVs, scrolling, budgets, weak caching, silhouette
  planning, and Create entity outline dispatch and partial-emission behavior;
- optional Mixin contract pinning for the interface target and the exact
  `supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z`
  descriptor.

This list describes coverage added or updated by the branch; it does not claim
that those tests were executed or passed for this documentation-only change.

## Manual beta validation gap

Automated coverage does not replace real-game checks. Actual rendering,
multiplayer behavior, Create/Flywheel integration, and held-key wheel
interaction still require manual beta validation. This documentation change
does not claim that those checks were performed.

## Beta limitations

The following remain release limitations rather than new branch features:

- There is no backward protocol compatibility with the original Ping Wheel
  mod, and simultaneous installation has no guaranteed cross-mod
  interoperability.
- Cross-dimension entity tracking and Immersive Portals compatibility remain
  out of scope for this release.
