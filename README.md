# Ping For It

Ping For It is a target-aware ping mod with mod ID `pingforit`.

- Version: `0.1.0-pfi-beta1`
- Minecraft: `1.21.1`
- Java: `21`
- Loaders: Fabric, Forge, and NeoForge

## Features

Ping For It captures the target and ray result when the ping key is first
pressed, then keeps that target locked for the interaction. It provides seven
ping types: Attention, Danger, Go To, Loot, Destroy, Take, and Request.

- A short click uses the captured target type's default ping type.
- Holding the key opens a wheel for the captured target's available ping types.
- The wheel center cancels the nearest eligible marker owned by the local player.
- Long-Press Compatibility Mode can recognize rapid clicks as one virtual long
  press.
- Markers, target validation, ownership, shared-target winner selection, and
  rate limiting are server-authoritative. The client only mirrors the server's
  create policy as a courtesy gate.
- Entity outlines and native `VoxelShape` block outlines follow the selected
  ping type, including shape-accurate non-full-cube outlines.
- Settings and chat output use Minecraft localization.

## Controls and commands

| Action | Default |
| --- | --- |
| Ping Location | Mouse5 (rebindable) |
| Open Settings | Unbound (rebindable) |

The available commands are:

- `/pingforit config` opens the settings screen.
- `/pingforit channel` reads or changes the player's ping channel.
- `/pingforit:server` opens operator-only server settings, including channel,
  player tracking, regeneration time, and rate limit controls.

## Installation

Install the jar matching both your loader and Minecraft `1.21.1`. Fabric also
requires Fabric API. Do not use a Fabric, Forge, or NeoForge jar with a
different loader.

Optional integrations and optional target content are soft dependencies. If an
optional mod, registry entry, or integration is absent, unrelated ping
functionality continues to work.

This fork uses its own identity and protocol. It provides no old protocol or
configuration migration and does not promise cross-mod interoperability. It
does not declare or enforce a runtime block against the original mod;
simultaneous installation is not explicitly blocked, but cross-mod behavior is
not guaranteed.

## Build and verification

From the repository root with JDK 21:

```powershell
.\gradlew.bat --no-daemon build
.\gradlew.bat --no-daemon verifyModIdentity
```

The verification task checks the identity and required resources in all three
shippable loader jars.

## Status and attribution

This is a beta fork. Rendering, multiplayer synchronization, and hold/wheel
interaction still require in-game validation on each supported loader; build
verification does not claim that manual validation has been performed.

The fork retains factual attribution to the original Ping Wheel work by
LukenSkyne. Existing metadata author attribution is unchanged.
