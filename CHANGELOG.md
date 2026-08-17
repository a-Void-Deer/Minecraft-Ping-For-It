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
- Releasing at or beyond the hold threshold before a render frame has actually opened the wheel still commits the captured target's default ping, including when capture finishes after release.
- The wheel times out after the default 5 seconds with no action, while selecting a sector commits the chosen ping type.
- The center `X` cancels the nearest marker you own within the original press-ray cone and current dimension.
- When the ping key is shared with Pick Block, vanilla Pick Block behavior is preserved, and mouse capture is recovered after wheel interaction.
- An optional Long-Press Compatibility Mode recognizes adjacent claimed clicks as one virtual long press. The first short click is sent immediately; suppressed clicks are never replayed, and the existing wheel/cancel action remains authoritative once the wheel actually opens.
- Compatibility timing is monotonic and event/frame driven: the adjacent-click slice is inclusive at its boundary, uses the effective 10–300 ms range (5 ms steps), and is capped at half the current wheel-hold duration.

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

The ten configurable keys and their bounds are:

- `wheelHoldMillis`: 300 ms by default; 100–2000 ms.
- `longPressCompatibilityMode`: disabled by default; when enabled, adjacent claimed clicks can form a virtual long press without changing normal short-click timing.
- `longPressCompatibilitySliceMillis`: 20 ms by default; nominally 10–300 ms in 5 ms steps, with an effective maximum of `min(300, floor((wheelHoldMillis / 2) / 5) * 5)` and a safe lower bound.
- `wheelTimeoutMillis`: 5000 ms by default; 1000–30000 ms.
- `cancelHalfConeAngleDegrees`: 5° by default; 1–45°.
- `wheelInnerRadius`: 14 px by default; 6–120 px.
- `wheelOuterRadius`: 39 px by default; 20–300 px.
- `wheelOpacity`: 100% by default; 0–100%.
- `wheelFontSize`: 100% by default; 10–500%, in 10% steps. This legacy JSON key now controls wheel option labels.
- `wheelTargetFontSize`: 100% by default; 10–500%, in 10% steps. This controls the captured target name.

The wheel keeps an 8 px minimum annulus. The hold-time setting is snapshotted at key press, the timeout setting is snapshotted when the wheel opens, and visual settings update live. The hold-time UI step is 10 ms and the timeout UI step is 200 ms. Opacity 0 hides the wheel without disabling selection. Out-of-range JSON values are clamped and logged at WARN with the key, supplied value, and effective value. The settings screen now includes a Reset All button with confirmation; confirming replaces and persists the complete default config, while canceling preserves current values. The settings screen scrolls correctly and keeps its footer visible.

## Compatibility and localization

- Distant Horizons integration was added for targeting distant terrain.
- Compatibility with Sable, FTB Teams, Voice Chat, and vanilla teams is retained and hardened; optional integrations fail soft when absent.
- Debug and configuration logs avoid exposing player identity, target details, channel names, or server/channel mappings.
- High-precision ping input is observed at client-thread `KeyMapping` press/release edges and advanced once per rendered frame using monotonic time; client tick input quantization is no longer used for the interaction.
- Compatibility transition logs contain only scalar timing, threshold, count, and result values. All nine bundled languages include the Reset All, split wheel-font, and long-press compatibility labels/tooltips.

## Known behavior

- This fork provides no old Ping Wheel protocol interoperability or configuration migration. Simultaneous installation is not explicitly blocked, but interoperability is not guaranteed.
- Most rejected or throttled sends are intentionally silent; target-gone feedback is the exception.
- Wheel opacity 0 hides the wheel while leaving its selection behavior interactive.
