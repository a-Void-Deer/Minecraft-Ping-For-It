# Ping For It 0.1.0-pfi-beta1

Release notes for the Minecraft 1.21.1 beta fork. These notes summarize the
player- and administrator-visible branch changes rather than listing commits.

## Fork identity and supported loaders

- Ping For It uses the new mod ID `pingforit` and its own network protocol.
- Release artifacts target Minecraft `1.21.1` on Fabric, Forge, and NeoForge
  with Java `21`.
- The fork does not migrate the original Ping Wheel protocol or configuration.
  It does not declare or enforce a conflict block against the original mod;
  simultaneous installation is not explicitly blocked and interoperability is
  not guaranteed.

## Target-aware input

- A target is captured when the ping key is initially pressed and remains fixed
  through short press, long press, wheel selection, cancellation, and timeout.
  Entity UUIDs preserve same-dimension movement and teleportation; block
  identity preserves dimension, position, and block type across state changes.
- The deterministic target catalog covers dropped items, entities,
  `entity_block` blocks, ordinary blocks, and the pure location fallback.
  Blocks with a `BlockEntity` resolve as `entity_block` before generic blocks;
  missing classification fails soft to `block`.
- The seven predefined ping types are **Attention**, **Danger**, **Go To**,
  **Loot**, **Destroy**, **Take**, and **Request**. Each target type has an
  ordered set and default, so short release is direct while long hold opens the
  captured target's wheel.
- The wheel center is Cancel Marker: only the local player's nearest owned
  marker in the view cone is eligible. Timeout closes the wheel with no action.
  Gone, dead, cross-dimension, or replaced targets are rejected with the
  localized target-gone feedback.

## Server-authoritative markers

- The server validates target identity, dimension, range, target type, ping
  type, lifetime, and marker ownership. It derives display names and target
  classification instead of trusting client presentation data.
- Multiple active markers may refer to one target. The latest server-arriving
  marker controls the visible outline and color; equal arrival times use the
  larger Marker ID. Removing or expiring the winner recomputes the next winner.
- The server remains authoritative for create rate limiting. Its active
  `rateLimit` and `msToRegenerate` policy is synchronized on reconnect and
  effective configuration changes. The client mirrors only `MarkerCreate` with
  a courtesy token bucket; a throttled create is dropped, not queued or marked
  dispatched. Removal and channel updates retain their existing behavior.
- Rate-controlled Flywheel diagnostics can retain complete target, position,
  registry, class, material, component, payload, and exception details.
  Negative or corrupt rate policy values fail safely without toast or action-bar
  feedback.

## Rendering, chat, and localization

- Entity markers outline ordinary entities and dropped items without requiring
  persistent vanilla glowing or team-state changes. Ping colors drive outlines,
  wheel borders, and the emphasized ping phrase in chat.
- Block markers use the native current `VoxelShape` for accurate non-full-cube,
  through-wall outlines. Whitelisted ordinary blocks use their native model
  route; `entity_block` targets independently try the live
  `BlockEntityRenderer` and baked-model geometry before falling back to shape.
- The client `blockDisplayWhitelist` defaults to exactly `*:*`; the separate
  `blockShapeBlacklist` defaults to empty, and a blacklist match wins. Strict
  entries are exact `namespace:block`, namespace wildcard `namespace:*`, global
  wildcard `*:*`, or block tag `#namespace:tag`; valid entries are combined by
  union, while invalid or missing content fails closed.
- Entity-block geometry selection is local and unsynchronized. `ALL` runs the
  built-in renderer and model sources plus registered optional sources;
  `COMPATIBLE` runs the built-ins; `VOXEL_SHAPE_ONLY` skips source construction
  and uses the shape fallback. The default is `COMPATIBLE`, and invalid saved
  values recover to it.
- On NeoForge, the optional Create `6.0.10` / Flywheel `1.0.6` adapter provides
  a vanilla outline-buffer silhouette mask in `ALL` mode. It handles both
  direct Flywheel instancing and indirect backends, resolves only live states,
  and loads lazily as a soft compile-only integration. It is beta functionality
  and remains optional when Create or Flywheel is absent.
- Chat and target names are localized. Exactly 8 languages are bundled:
  English (`en_us`), German (`de_de`), Argentine Spanish (`es_ar`), French
  (`fr_fr`), Polish (`pl_pl`), Turkish (`tr_tr`), Simplified Chinese
  (`zh_cn`), and Traditional Chinese (`zh_tw`).

## Configuration and commands

- The client settings screen now includes the server configuration section for
  connected players with permission level 3. Server values are edited through
  the authoritative GUI and rate policy changes are propagated to clients.
- The former standalone server command was removed. `/pingforit` and
  `/pingforit help` show help; `/pingforit config` opens settings; and
  `/pingforit channel` reads or changes the player's ping channel.
- Client settings are stored in `config/pingforit.json`; server settings are in
  `config/pingforit.server.json`. The GUI has no list editor: saving and closing
  opens the client file for external block-list edits. Changes to those lists
  apply after a client restart, and reopening settings in the same session does
  not reload them.

## Optional compatibility

- Optional target content and integrations fail soft when a mod, registry entry,
  renderer, or backend is missing. Existing compatibility paths for Distant
  Horizons, Sable, FTB Teams, and Voice Chat remain optional; vanilla team
  behavior continues to be respected.
- The Create/Flywheel adapter is NeoForge-only and does not make Create or
  Flywheel a hard runtime requirement for other loaders or ordinary pings.

## Tests and build verification

- Focused automated coverage exercises target priority and identity, input
  state transitions and wheel timeout, marker conflict/cancellation behavior,
  packet round trips, server configuration synchronization, rate limiting,
  whitelist grammar, entity-block modes, optional-class safety, localization,
  and Flywheel transform/silhouette planning.
- The release verification path builds the supported loader artifacts and runs
  `verifyModIdentity` for the fork ID and required resources. Manual in-game
  checks remain necessary for rendering, multiplayer interaction, and optional
  integrations.

## Known beta limitations

- This is a beta release; loader-specific rendering, multiplayer synchronization,
  Create/Flywheel silhouettes, and hold/wheel behavior still require manual
  validation in real game sessions.
- Cross-dimension entity tracking and Immersive Portals support are not part of
  this release. There is no backward protocol migration or interoperability
  guarantee with the original Ping Wheel installation.
