# Manual and integration verification

[Verification index and evidence rules](README.md) · [Build checks](build-checks.md)

Additional requirements for this file: 
- Clear manual retest steps (or multiple step groups) should be provided to facilitate retesting.
- This document follows the style requirements of docs/AGENTS.md.

## Pending manual and integration matrix

### General

- General multiplayer and other-mod playtests remain pending.
  - Limited recorded observation.

### Wheel

- The [inventory-list input and presentation
  contract](../../architecture/picking/wheel.md#inventory-list) under live input
  on Forge. (NeoForge & Fabric done)

### Selector readability

- Layouts in different language except en-US and zh-CN are not validated. May be truncated or missing translation.

### Settings and server performance

On macOS and Linux (currently untested), on a live client and a dedicated or integrated server, with a Minecraft window and the platform modifier key (Option on macOS, Alt on Linux), exercise both logging rows through activation with the modifier held and released. Confirm that a current `DEBUG` value keeps its caption when the modifier is released, the next non-modifier activation exits to `NORMAL`, and `DEBUG` cannot be selected again without the modifier.
