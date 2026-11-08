# Ping For It Changelog

## 1.20.1 Port (unreleased)

- Ported the `0.4.2-pfi-beta1` feature set to Minecraft 1.20.1, Java 17, Fabric, and Forge.
- Adapted networking to the pre-1.20.2 channel API, rendering hooks to the 1.20.1 render pipeline, and the settings screen to the 1.20.1 widget and list APIs.
- Corrected camera-relative block outlines, frame preparation, and bounded outline-buffer reuse on 1.20.1.
- Added AMEcs-compatible key handling so input mods that replace vanilla key dispatch no longer disable pings.
- Restored the Forge HUD entry points and Fabric packet main-thread dispatch.
- Fixed the Forge wheel being cut out inside the chat background: the overlay is now drawn before the chat panel instead of after the player list, so the chat depth planes shade it rather than occlude it.
- Fixed the momentary whole-screen blur when the wheel closed in modpacks with the Blur mod: re-grabbing the cursor no longer goes through `MouseHandler.grabMouse()`, whose redundant `setScreen(null)` started Blur's fade-out.
- Fixed settings-list scrolling, clipping, focus, and background rendering on 1.20.1.
- NeoForge 1.20.1, Sable, and the optional Create/Flywheel integration are not part of this branch.
