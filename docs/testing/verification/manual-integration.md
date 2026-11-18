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
6. Place two distinguishable rotors of the same entity type on one sight line.
   Keep the test surfaces within client ping range and both rotor anchors within
   server ping range. For each setup below, aim through both, choose the Entity
   leaf in Precise, and note which rotor receives the marker.
   - Normal selection: with local shape traversal order controllable, give the
     large rotor a near surface late in that order and a far surface early in
     it, and place the reference rotor's surface between them. Expect Precise
     to select the large rotor. Its early surface alone is farther away than
     the reference rotor's surface.
   - Fault isolation: in a controlled fixture that fails a later state or shape
     read after the large rotor's early far surface was observed, place the
     reference rotor's surface farther than that early surface. The reference
     rotor must be selected; leaking the failed rotor's provisional contact
     would select the large rotor instead.
   - If the live setup cannot control local shape traversal order or induce that
     failure, record only the observable selection and leave the affected
     sub-checks pending.
   - Record observed latency and stalls; no performance guarantee is
     established.

### Sable external blocks

Pending against an installed Sable runtime for the
[client presentation contract](../../integrations/sable.md#client-capture-and-presentation);
record the exact Minecraft, Sable, Sable Companion and loader versions.

1. Place a vanilla door, a vanilla bed, a normal double chest and a trapped
   double chest on one Sable sub-level. Move and rotate the sub-level, then mark
   each structure from both ends. Confirm the outline follows the local
   structure under the current pose and covers both halves from either end; the
   marker still uses its original identity, grouping and HUD anchor, and the
   expected door/bed/chest render form.
2. Repeat while the sub-level moves or rotates between marking and observation.
   Confirm the outline stays aligned with the local structure and no half is
   drawn twice.
3. Unload or replace one half while the other remains. Confirm only the
   remaining half is outlined, with no stale paired outline or invented second
   half.

Pending against an installed Sable runtime for
[server validation and materialization](../../integrations/sable.md#server-validation-and-materialization)
and [refresh lifecycle](../../integrations/sable.md#refresh-lifecycle), using
the same recorded versions.

1. Place a block in each of two active sub-levels at similar local positions
   and mark both. Move or rotate one sub-level. Confirm that its marker follows
   the moved block and the other marker stays with its own block.
2. With a Sable runtime where optional content-read discovery is unavailable,
   place an ordinary ping on a Sable block. Confirm that the server accepts the
   ping, the marker appears, and it follows the block when the sub-level moves.

### Selector readability

- Layouts in different language except en-US and zh-CN are not validated. May be truncated or missing translation.

### Settings and server performance

On macOS and Linux (currently untested), on a live client and a dedicated or integrated server, with a Minecraft window and the platform modifier key (Option on macOS, Alt on Linux), exercise both logging rows through activation with the modifier held and released. Confirm that a current `DEBUG` value keeps its caption when the modifier is released, the next non-modifier activation exits to `NORMAL`, and `DEBUG` cannot be selected again without the modifier.

The server Performance slot-count sliders (Physical Slots per Tick, Preview
Slots per Client, Preview Slots Server-wide, Tracking Slots per Target, and
Tracking Slots Server-wide) remain pending live verification:

1. On each of the five sliders, drag around a commonly used value and through
   the high range; confirm the handle follows the pointer and the intended value
   can be selected.
2. Repeat at a narrow window and at a different GUI scale; confirm stops near
   both ends, a high-range boundary, and the unlimited tail remain reachable by
   click and drag and the value caption stays legible.
3. Use the arrow keys to adjust one stop at a time in the fine range, across a
   high-range boundary, and at both ends.
4. Move to the last finite stop, to unlimited, and back; confirm the caption,
   handle and draft dirty/revert state match the selected mode.
5. Load a server carrying a legal non-grid slot value, open the setting,
   navigate away and back, then rebuild or reopen the screen; confirm the
   original value remains selectable and clean until edited. Save an edit and
   fetch the server configuration again; confirm the persisted value is
   reported.

Mixed-build checks for the finite upper bounds remain pending:

- Load a server configuration written by the build with the earlier higher
  bounds. Confirm each finite slot count above the current maximum is clamped
  to that maximum on load, while an unlimited leaf remains unlimited and its
  retained finite value is also clamped.
- Run the build with the earlier higher bounds and the build with the lower
  finite upper bounds against the same version-five server-configuration shape.
  With the earlier server and the client with the lower bounds, confirm an
  out-of-range snapshot is rejected as a whole, including an out-of-range
  retained value on an unlimited leaf. With the server with the lower bounds and
  the earlier client, confirm the corresponding out-of-range update is rejected,
  including that unlimited-retained case, and a fetched snapshot still reports
  the prior authoritative values. Confirm ordinary in-range values exchange
  normally in both directions. Updates have no acknowledgement, so confirm the
  applied or rejected state from a subsequent snapshot; a clean client draft
  alone does not establish server acceptance.
