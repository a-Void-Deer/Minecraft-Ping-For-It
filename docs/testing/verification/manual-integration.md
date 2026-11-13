# Manual and integration verification

[Verification index and evidence rules](README.md) · [Build checks](build-checks.md)

Additional requirements for this file: 
- Clear manual retest steps (or multiple step groups) should be provided to facilitate retesting.
- User may delete the relevant entries after completing manual verification. Do not take this as a signal that further writing is not allowed.
- This document follows the style requirements of docs/AGENTS.md.

## Pending manual and integration matrix

### General

- General multiplayer and other-mod playtests remain pending.
  - Limited recorded observation.

### Wheel

- The [inventory-list input and presentation
  contract](../../architecture/picking/wheel.md#inventory-list) under live input
  on Forge. (NeoForge & Fabric done)

### Aeronautics rotors

Pending for the [Aeronautics contract](../../integrations/aeronautics.md) and
its [Sable frames](../../integrations/sable.md#transformed-entity-frames-and-frozen-block-proof).
Record the exact Minecraft, NeoForge, Create, Sable, Sable Companion, Flywheel
and Aeronautics versions in the test notes; the installed runtime combination
has not been validated.

1. With Aeronautics present, assemble and test both the normal and gyro rotor
   forms. For each form, test one rotor in the ordinary world and one
   parented/contained by a Sable sub-level, with the rotor's optional renderer
   active and with its presentation unavailable or disabled.
2. On both the ordinary press path and the Precise branch, check exact
   core/blade hits; gaps, hollow regions and the spaces between blades produce
   no false hit; protruding local shapes and very large assembled structures
   remain selectable without the native entity AABB deciding the hit.
3. For the ordinary trace, confirm a nearer ordinary wall occludes the rotor.
   Check that a frozen sub-level block keeps its native face while the marker
   uses the projected world point, and an unproven contained block cannot be
   replaced by an arbitrary farther entity. Check native block, represented
   fluid and existing block-entity presentation.
4. Verify the marker sits at the projected base plus the native world-up height,
   the cancellation candidate uses the matching current-tick position, and the
   outline follows the actual normal or gyro local model without a second parent
   translation. Move and rotate the rotor and its parent, change parent
   translation and nonuniform scale, and confirm the anchor moves while the local
   model does not inherit parent scale.
5. Check ordinary-world and sub-level unload/rejoin, absent Aeronautics and
   disconnect/reconnect lifecycle, and a multiplayer client/server session with
   bystander viewers.
6. With many captured entries, shape boxes and provider calls, drive Precise
   refresh and confirm an exhausted or incomplete attempt rejects the candidate
   rather than accepting a partial prefix. Record observed latency and any
   stalls; no performance guarantee is established.

### Selector readability

- Layouts in different language except en-US and zh-CN are not validated. May be truncated or missing translation.

### Settings and server performance

On macOS and Linux (currently untested), on a live client and a dedicated or integrated server, with a Minecraft window and the platform modifier key (Option on macOS, Alt on Linux), exercise both logging rows through activation with the modifier held and released. Confirm that a current `DEBUG` value keeps its caption when the modifier is released, the next non-modifier activation exits to `NORMAL`, and `DEBUG` cannot be selected again without the modifier.
