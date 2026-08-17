# Ping For It 0.1.0-pfi-beta1

This changelog describes the player- and server-admin-facing changes for Minecraft 1.21.1.

## Fork identity and support

- The fork is named **Ping For It** and uses `pingforit` as its mod ID.
- It targets Minecraft 1.21.1 on Fabric, Forge, and NeoForge.
- Use `/pingforit config` for settings and `/pingforit:server` for the server command.

## Target-aware pings

- Target selection follows a deterministic priority: dropped item > entity > block with a block entity or custom shape > block > location.
- The default type is **Loot** for dropped items, **Go To** for locations, and **Attention** for all other targets.
- The seven ping types are **Attention**, **Danger**, **Go To**, **Loot**, **Destroy**, **Take**, and **Request**.
- XP orbs and dragon parts are handled correctly.

## Input and wheel behavior

- The target and ray are captured when the ping key is pressed and stay locked for the interaction; moving the camera does not retarget it.
- A short release sends the default type. Holding the key for the default 300 ms opens the wheel.
- The wheel times out after the default 5 seconds with no action, while selecting a sector commits the chosen ping type.
- The center `X` cancels the nearest marker you own within the original press-ray cone and current dimension.
- When the ping key is shared with Pick Block, vanilla Pick Block behavior is preserved, and mouse capture is recovered after wheel interaction.

## Multiplayer markers and server behavior

- Creation and removal are validated by the server: target, range, dimension, and target name are checked, and ownership is required for removal.
- When several players ping the same target, the latest accepted marker receives visual emphasis without deleting the other owners' markers.
- Markers expire according to server settings, and an owner's markers are removed on disconnect. Audience channels, team behavior, and current-dimension visibility continue to apply.
- Rate limiting is authoritative and its policy is synchronized to clients. Rapid create requests are silently dropped rather than queued; removal and channel updates are unaffected.
- If a target is gone, the user receives target-gone feedback. A missing entity marker freezes at its last known position and resumes tracking if the entity returns.

## Visual and chat polish

- Entity outlines are colored without changing vanilla global glow or team state.
- Block outlines follow the target's shape and model, keeping non-full-cube outlines accurate instead of approximating them as full cubes.
- The radial wheel has labels, borders, icons, a target name, and a center `X` in a smaller layout.
- In-world markers can show target names, item icons, direction, owner display names, and team colors, with directional sound.
- Target names use `Custom Name (Vanilla Name)`, with `HERE` for location pings and `Unknown Target` as the fallback.
- Chat uses a server-derived sentence, with the ping phrase colored according to the selected ping type.

## Configuration

The seven configurable keys and their bounds are:

- `wheelHoldMillis`: 300 ms by default; 100–2000 ms.
- `wheelTimeoutMillis`: 5000 ms by default; 1000–30000 ms.
- `cancelHalfConeAngleDegrees`: 5° by default; 1–45°.
- `wheelInnerRadius`: 14 px by default; 6–30 px.
- `wheelOuterRadius`: 39 px by default; 20–75 px.
- `wheelOpacity`: 100% by default; 0–100%.
- `wheelFontSize`: 100% by default; 50–200%.

The wheel keeps an 8 px minimum annulus. The hold-time setting is snapshotted at key press, the timeout setting is snapshotted when the wheel opens, and visual settings update live. Opacity 0 hides the wheel without disabling selection. Out-of-range JSON values are clamped and logged at WARN with the key, supplied value, and effective value. The settings screen now scrolls correctly and keeps its footer visible.

## Compatibility and localization

- Distant Horizons integration was added for targeting distant terrain.
- Compatibility with Sable, FTB Teams, Voice Chat, and vanilla teams is retained and hardened; optional integrations fail soft when absent.
- Debug and configuration logs avoid exposing player identity, target details, channel names, or server/channel mappings.
- English and Simplified Chinese include complete new labels. Other bundled languages may fall back to English for new strings.

## Known behavior

- This fork provides no old Ping Wheel protocol interoperability or configuration migration. Simultaneous installation is not explicitly blocked, but interoperability is not guaranteed.
- Most rejected or throttled sends are intentionally silent; target-gone feedback is the exception.
- Wheel opacity 0 hides the wheel while leaving its selection behavior interactive.
