# Ping For It Changelog

## Unreleased

### Overview

This update adds target-aware Sable support and hardens the optional Create/Flywheel compatibility paths. Sable blocks and block entities can now remain identifiable and renderable as their sub-levels move, split, or rotate, while safe fallbacks remain available when optional integration data is unavailable.

### Added

- **Sable external targets:** Added a provider-neutral external block target model, stable target keys, and marker serialization for Sable candidates and committed targets. The wire model carries an explicit standard/external block variant plus the provider ID, stable target ID, expected block registry ID, bounded locator, and block-entity classification.
- **Target-aware Sable identity:** Server validation now materializes a stable TrackingPoint identity and expected block identity instead of treating a Sable hit as only a projected location. The provider seam supports authoritative validation, materialization, refresh, naming, and cleanup without exposing optional classes to common domain code.
- **Sable outline presentation:** Added live Sable block and block-entity capture, position resolution, shape rendering, and marker presentation using the current sub-level state.
- **Embedded geometry transforms:** Added reusable precision-safe transforms for external entity/block geometry, including camera-relative conversion from an embedded environment origin.
- **Target-selection modifiers:** Added three independent persistent toggles: Left Shift (**Select Transparent Blocks**) for transparent-block pass-through, Left Ctrl (**Allow Blacklisted Targets**) for blacklisted-target marking, and Left Alt (**Mark Fluids**) for fluid marking. All default to off, persist in client config, and are available in client settings.
- **HUD notices:** Added latest-only notices with a 2-second display and 1-second fade. Notice size is configurable from 0% to 500%.
- **Create entity-outline filtering:** Create SuperGlue entities are registered as blacklisted for normal target selection, with the Ctrl override available when they must be selected explicitly.
- **Focused automated coverage:** Added tests for external target identity and codec behavior, Sable provider/reference lifecycles, capture contracts, diagnostics, large-coordinate transforms, geometry routing and modes, optional-class safety, selection modifiers, and SuperGlue filtering.

### Changed

- **Tracking follows the live Sable object:** A marker keeps its stable TrackingPoint identity while its opaque locator and render anchor are updated as the sub-level moves, splits, or rotates. The server derives its authoritative anchor from Sable's logical pose; the client uses Sable's smooth render pose for outline transforms and marker presentation, with marker positioning falling back to the server anchor only when the live client observation is temporarily unavailable.
- **Validation and lifecycle cleanup:** Same-type block-state changes remain valid, while a replaced block type, invalid TrackingPoint, or otherwise invalid live target removes the marker authoritatively. Shared TrackingPoints use reference-counted leases and are retired on the last marker release, expiry, disconnect, failed creation, or server teardown.
- **External refresh behavior:** Temporarily unavailable Sable containers, sub-levels, or loaded state retain the marker for a later retry. A valid refresh updates the locator/anchor in place without changing marker identity, ownership, lifetime, or winner ordering; an invalid refresh takes the normal removal path.
- **Sable block rendering:** Eligible ordinary Sable `block` targets with a live `MODEL` state use the existing baked-model glow route. Ineligible, non-model, unavailable, empty, or failed model attempts use the native `VoxelShape` outline fallback instead of losing the marker outline.
- **Sable `entity_block` rendering:** Sable block entities now use the existing geometry runner. It attempts the actual local BlockEntityRenderer first and the baked-model source second, with optional registered sources added only in `ALL`; all allowed sources are attempted before deciding whether the native VoxelShape fallback is needed. `COMPATIBLE` skips optional sources while retaining built-ins, and `VOXEL_SHAPE_ONLY` skips geometry sources entirely. Persisted null or unknown modes safely use `COMPATIBLE`.
- **Embedded Create/Flywheel silhouettes:** External Sable geometry can now use live embedded Flywheel environments and their direct or indirect instance state. Valid sibling instances remain eligible while stale, deleted, hidden, foreign, or implausibly located environments are skipped; accepted geometry is transformed into the shared camera-relative outline silhouette path.
- **Geometry fallback accounting:** Model and geometry routes now suppress the VoxelShape pass only after committing vertices. Zero-emission or failed attempts retain the fallback, while a recoverable partial commit remains visible for the current frame and is retried on the next frame.
- **Marker refresh receipts:** Same-ID marker updates caused by Sable locator/anchor refreshes are treated as updates rather than new marker receipts, avoiding duplicate local receipt effects.
- **Detailed optional-integration diagnostics:** Sable capture, server validation/materialization, refresh, cleanup, and presentation diagnostics now use bounded, rate-controlled structured DEBUG reporting. Available target, position, registry, class, material/component/payload context, and original exception details are retained where available, without turning repeated frame/refresh failures into log storms.

### Fixed

- **Toggle input:** Toggle presses are suppressed while screens are open and remain suppressed until release.
- Sable containment and projection now use the level-aware Companion API (`getContaining(level, position)`), preventing the deprecated client-global lookup from selecting or projecting against the wrong level.
- Sable candidate selection now positively checks containing sub-level identity, filters invalid observations, and applies deterministic hit ordering when multiple sub-level candidates overlap.
- Sable outline transforms no longer narrow huge absolute plot coordinates into float state before subtracting the camera. Integer block origins are transformed in double precision; only the small camera-relative translation and linear orientation/scale enter float-backed rendering state.
- Optional geometry routing is hardened so unsupported Create/Flywheel backends leave the common outline route in control, and source/registration failures remain recoverable without corrupting the next frame.

### Compatibility

- Sable server and client adapters are loaded only after the Sable mod is detected, with optional API discovery kept reflective and lazy. Missing classes, partial content, unavailable containers, and API drift fail soft; ordinary Ping For It targeting and fallback rendering continue to work.
- Create/Flywheel integration remains optional. Create entity-outline registration is retained and closed during teardown, Flywheel probing and external geometry handling tolerate absent or unsupported backends, and the common outline remains the fallback route.
- Aeronautics/Simulated `simulated:honey_glue` is ignored by default like Create SuperGlue and can be selected when blacklisted-target marking is enabled.
- The marker codec's explicit block variant tag and external-target fields are an intentional change to this fork's wire format. This update adds no backward protocol compatibility with the original mod.

### Validation

- Focused automated regression coverage is included for the domain, server lifecycle, codec, transform, geometry, diagnostics, optional-dependency, and input changes described above, including the Windows CRLF-normalized geometry source check.
- Without those mods or a supported backend, their optional routes are intentionally unavailable and the fail-soft fallbacks apply.
