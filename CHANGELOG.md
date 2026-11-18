# Ping For It Changelog

## Unreleased

Changes since the `1.21.1` baseline (`a51a850b`).

### Aeronautics rotors and Sable sub-levels

- On NeoForge, ordinary and Precise selection can now hit the actual block
  shapes of assembled [Aeronautics rotors](docs/integrations/aeronautics.md),
  both normal and gyro. Hitting a blade marks the whole rotor; aiming through
  a gap can reach a target behind it.
- On a moving or rotating Sable sub-level, the marker, cancel action and
  supported Create outlines follow the rotor where it appears in the world.
  The server checks range to the rotor's base in world coordinates and rejects
  a new ping if it cannot locate that base. If the client temporarily loses
  the rotor's position, the marker stays at its last known point.
- A [Sable block hit](docs/integrations/sable.md#transformed-entity-frames-and-frozen-block-proof)
  keeps the local block and face you aimed at. To compare distances, the game
  projects the point you hit into world coordinates using the sub-level's pose
  at the time of the hit. Without a verified world hit point, Precise cannot
  offer that hit. If ordinary selection falls back to a location and cannot
  determine its world coordinates, it sends no location ping.
- On a [Sable sub-level](docs/integrations/sable.md#client-capture-and-presentation),
  marking either half of a door, bed or double chest (ordinary or trapped) now
  outlines the whole pair. If the other half is missing or unloaded, only the
  marked half is outlined. If one half needs a wireframe, drawing the other
  half's model no longer hides it. Sable also uses the existing
  [outline rules](docs/architecture/rendering/presentation_subjects.md#required-contract-and-external-conformance)
  for supported Create doors, large water wheels and Simulated docking connectors.
- When you ping a Sable block, the server checks range and updates the marker
  using the block's own sub-level, not a position in another sub-level. See
  [Sable server validation](docs/integrations/sable.md#server-validation-and-materialization).

### Precise target selection

- Within the same ping range, Precise can now select eligible targets
  encountered late in a crowded scene or large assembly. See
  [Precise live candidate capture](docs/architecture/picking/capture.md#precise-live-candidate-capture).
- Removed the fixed limits on block reads, entity visits, provider calls and
  shape checks, including searches through loaded Sable sub-levels. While a
  refresh is in progress, the last confirmed choices remain selectable. If a
  scan ends incomplete, the wheel disables the target types that depend on it
  rather than offering a partial result.

### Distant Horizons

- After the native trace misses, ordinary pings and **Precise** → **Location**
  can pick a distant target in both single-player and multiplayer. That Distant
  Horizons hit comes only from the world you were in when aiming, and the
  server's own range check still applies. See
  [Distant Horizons capture range](docs/architecture/picking/range.md#capture-pipeline-application).

### Server performance limits

The five inventory slot sliders on the server **Performance** page have fewer
high-end steps and lower finite limits:

| Setting under `inventory` | Previous maximum | New maximum |
| --- | ---: | ---: |
| `physicalSlotsPerTick` | 4,096 | 2,048 |
| `preview.maxSlotsPerClient` | 16,384 | 2,048 |
| `preview.maxSlotsServer` | 65,536 | 8,192 |
| `tracking.maxSlotsPerTarget` | 16,384 | 4,096 |
| `tracking.maxSlotsServer` | 262,144 | 8,192 |

Defaults and unlimited mode are unchanged. When loading an existing
[server configuration](docs/config/server.md#inventory-policy-object), the
server clamps values above the new limits, including the finite value kept in
unlimited mode. Clamping alone does not rewrite the file. A client running
this build rejects a settings snapshot that exceeds a limit; a server running
this build rejects an update containing such a value.

### Double-click Danger window migration

Added a [client config migration](docs/architecture/config/revisioning.md#ordered-migration-and-writeback)
for `0.5.1-pfi-beta2` or later. In a config from `0.5.1-pfi-beta1` or earlier,
it changes `doubleClickDangerWindowMs` from exactly `100` to `160` ms; other
values are preserved. The 160 ms default is unchanged. The current build is
still `0.5.1-pfi-beta1` and does not run this migration.

### Connection handling

- Fixed a Forge LAN issue where a packet from a guest could be mistaken for a
  server rate-limit update and prevent the host from placing pings.
- On Fabric, Forge and NeoForge, a delayed handshake from a closed connection
  no longer leaves pings unavailable when the same player reconnects.
  Respawning on the same connection still works.

### Server shutdown cleanup

- Leaving a single-player world now releases Ping For It's reference to that
  world as soon as the server stops. Shutdown also clears the server's marker
  tracking and per-player channel and rate-limit state. A late shutdown from
  an older server cannot clear the new session's [markers](docs/architecture/authority/marker_lifecycle.md#server-authoritative-record-lifetime).

### Direction indicator

- The arrow for a target behind you points in the target's direction even with
  unequal safe-area insets. For example, if you look level and the target is
  directly behind you and slightly below eye level, the arrow appears at the
  bottom of the safe area and points down. See
  [direction-indicator safe area](docs/config/client.md#direction-indicator-safe-area).

### Selection toggles

- Pressing and holding **Mark Transparent Blocks**, **Mark Blacklisted Targets**
  or **Mark Fluids** in a menu no longer flips the setting when you return to
  the game. Release the shortcut and press it again to toggle. See
  [toggle input and attempted persistence](docs/architecture/picking/selection_policy.md#toggle-input-and-attempted-persistence).

### Settings screen reload and save failures

- If loading or saving the client configuration replaces the in-memory config
  while Settings is open, the client option pages now show the replacement's
  values and later edits use it. A drag started on the old page is cancelled
  instead of changing the old object.
- If **Done** or **Esc** on the root page cannot save the client configuration,
  Settings now reports the failure and offers **Keep Editing** or
  **Exit Without Saving**. Keep Editing retains the server-settings draft for
  the next close; Exit Without Saving closes without retrying the save or
  sending the server update. Client changes already applied in memory are not
  undone. See
  [client configuration loading, edits, and close](docs/UI/settings-screen.md#client-configuration-loading-edits-and-close).

### Saving configuration changes

- Saving changed settings no longer skips an update as if nothing changed. For
  example, when a server administrator changes the ping rate limit and
  regeneration interval in one update, both new values are saved and take
  effect. See
  [configuration save behavior](docs/architecture/config/revisioning.md#marker-and-serialization).
