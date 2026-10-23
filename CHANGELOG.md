# Ping For It Changelog

Baseline: `0.4.2-beta1` (commit `2f2446dd0cb36a92205cae79d76577681a42a5df`)

This update replaces the old ping wheel with a full spatial selection menu, adds server-backed content and inventory information to pings, reworks input and logging. It also removes the FTB Teams and Simple Voice Chat integrations.

## Spatial selection menu

- The wheel is replaced by a spatial selection menu. Holding the ping key opens a fixed root with Danger, Content, Unping, Precise, Intent, and target-selection settings, alongside reserved slots.
- Selecting a branch enters a submenu; a one-level return recenters the parent on the pointer, and Back is available in every submenu.
- New visual and interaction preferences cover menu distance and size, the center deadzone, entry stroke and dwell timing, the pointer trail, wheel and target opacity, and a reduced-motion option.
- The Precise branch keeps a live list of candidates for each target type from the current camera view while the branch is open, and shows the captured candidate's name as a preview.
- The Content branch presents property menus and a scrollable inventory list. The selector draws its own pointer, takes over mouse input only while it is actually open, manages its own cursor, focus, and screen lifecycle, and gives clear Back and selection feedback.

## Content and property markers

- A ping can now carry property or item content. For each authorized attribute you can choose a Ping Type (for example an intent or color) independently of the marker's main type.
- The server recaptures the actual values of the selected target when the ping is created.
- The base attributes include the target name and custom name, entity health, item identity, block state, and other authorized fields.
    - Mainly on blocks & entity blocks.
- Content pings show their values in the HUD and in a dedicated localized chat message that keeps each selected entry's own phrase.
- Client-local observations are preferred.

## Inventory preview and tracking

- Inventory preview and tracking are available by default and need no separate opt-in. Selecting an inventory row creates an ordinary marker with an item annotation; its count keeps updating for the marker's lifetime.
- Item variants are folded when an exact identity cannot be encoded, and unknown, incomplete, and unavailable states are shown explicitly.
- Options let you enable the feature to keep the Content intent selection menus enabled or disabled, or keep inventory lists open after the first key release.(OFF by default)
- Inventory reads use the block face you were looking at.

## Input and gestures

- Double-click Danger: repeating a short press within a short window and without turning the view too far derives an extra Danger ping from the first capture.(ON by default)
- An optional gesture shortcut can open the selector before the normal long-press threshold (OFF by default).
- Long-press compatibility mode still takes priority and disables the gestures above while it is active.

## Server administration

- The settings screen adds a Server Presentation editor that shows each target type's allow and block rules and whitelist-only mode. Players without permission can view the authoritative server settings but cannot edit them.
- The Performance category manages the inventory preview and tracking budgets, caps, byte allowances, refresh and heartbeat cadence, and the administrator notifications.

## Diagnostics and logging

- Better diagnostics cover actual failures and important degradation in capture, content & inventory handling, sending, rendering, and optional integrations.
- Unexpected failures and important degradation produce warnings visible at an INFO log threshold.
- Repeated reports are suppressed and counted, with independent DEBUG, WARN and ERROR capacity.

## Integrations

All Integrations are optional.

- Create: kinetic properties such as actual RPM, network stress, capacity and available capacity, item and fluid summaries, Vault inventories, and a client-side kinetic preview. The integration is gated to a tested Create version (`6.0.10`).
- Sable: external block content preview and tracking, provider-local hit-face reads, and a supplementary transformed ray for the Precise branch.
- Removed: the FTB Teams and Simple Voice Chat integrations. Team context now uses vanilla scoreboard teams only.

## Upgrading from 0.4.x

- Releasing at the selector's center now abandons the interaction instead of cancelling your nearest marker. Cancellation moved to the root's Unping action.
- The selector no longer times out.
- The old wheel radius settings are retired and replaced by the new geometry preferences.
- `passThroughTransparentBlocks` is replaced by `markTransparentBlocks` with the opposite meaning; existing values are migrated automatically.
- The three target-selection toggles (transparent blocks, blacklisted targets, and fluids) now default to Unbound. Explicitly saved bindings for the blacklist and fluid toggles still load, but the renamed transparent-block toggle does not inherit an old custom binding and must be bound again.
- Obsolete client presentation keys are removed during migration.
- The network protocol was updated: the presentation, inventory, and server-configuration routes are versioned, and the older marker packet family is retained only as a registered no-op. Update the client and server together.
