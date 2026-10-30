# Manual and integration verification

[Verification index and evidence rules](README.md) · [Build checks](build-checks.md)

## Pending manual and integration matrix

### General

- General Multi-player / other mod playtests.
  - dev-environment manual playtests have done.

### Wheel

- the [inventory-list input and presentation contract](../../architecture/picking/wheel.md#inventory-list) under live input on Fabric, Forge (NeoForge done)
- Focus loss and Alt-Tab, window replacement, screen handoffs and abort without overriding a newer cursor owner

### Selector readability

- Layouts in different language except en-US and zh-CN are not validated. May be truncated or missing translation.

### Settings and server performance

- Exercise both scope overviews and every category with compact and wide
  viewports, GUI-scale changes, and long labels in the bundled locales. Check
  explicit related pairs stay together or stack in order when the viewport or
  translated labels require it, while singletons stay full-width. Include
  presentation Allow/Block and selector/action pairs, clipped headings' complete
  hover text, scrollbar clearance, fixed-footer containment, and retained scroll
  and keyboard focus after Back/Escape, navigation, and resize, including footer
  traversal. These live native-screen checks remain pending; the
  [headless list reflow/input coverage](configuration-ui.md#settings-screen-navigation-catalog-layout-and-localization)
  does not establish game-window, font or render behavior.
- Inspect every value control's assembled help with native hover and keyboard
  focus, plus all category/scope, Back, Done, Reset, configuration-file, and
  presentation-editor actions. Check numeric decrease/increase, boolean off/on,
  each enum mode, scope, meaningful zero/unlimited states, apply timing, and
  action consequences against the
  [UI help contract](../../UI/settings-screen.md#contextual-help-and-localization).
  Resource presence does not establish localized readability or actual effects.
- Navigate ordinary server settings as an editor and a read-only viewer, through
  pending/unavailable views, permission loss and recovery, and disconnect.
  Exercise retained drafts across category/scope navigation and resize without
  submission, clean opening/rebuilds, and final-close validation routing to and
  revealing the first invalid field. Check native focus and inert controls in
  retained read-only views against the
  [session contract](../../UI/settings-screen.md#server-settings-session).
- Exercise Server Presentation independently while ordinary server settings are
  unavailable, read-only, or hold an invalid draft. Check view navigation and
  refresh, retained selector drafts and feedback, permission/pending gates, and
  mutation-on-activation versus ordinary settings' final-close behavior against
  the [editor contract](../../UI/settings-screen.md#server-presentation-category).
  A successful ordinary close is not evidence that the server applied or
  persisted its no-acknowledgement update.
- Compare Team Color mode help with live output across Player Info modes. The
  known discrepancy between the
  [client catalogue's intent](../../config/client.md#target-selection-and-entity-block-presentation)
  and renderer behavior remains unresolved; tooltip clarification is not
  evidence of a rendering fix.
- Exercise all server Performance sliders and the monitoring toggle with native
  mouse and keyboard input, compact layouts, GUI-scale changes, category/scope
  navigation and read-only snapshots. Check in-range integers between UI-only
  adjustment stops stay exact and clean on opening/rebuild, remain available for
  revert after editing, and survive a real
  close/update/reopen transaction. Exercise unlimited-tail entry, finite-stop
  exit and the left-adjacent maximum finite stop, plus linked display/frame
  adjustments and their notice. Model and codec coverage is tracked in
  [configuration verification](configuration-ui.md#server-performance-contract-verification).
- Check the notice toggle's enabled/disabled effect and the active selection-set
  meaning of Tracking Variants per Target in assembled localized help. Verify
  live notice delivery when toggled and remote editing under permission changes;
  resource presence does not establish this behavior.

### Inventory

- Exercise zero resynchronization cooldown, including changing from a positive
  value while repair is waiting, under delayed unknown-baseline streams,
  completion parts and future heartbeats. Verify the common client waiting
  window and continued progress of a slow serviceable baseline without changing
  the initial-baseline or marker-lifetime rules. The passing
  [headless timing scenarios](inventory.md#resynchronization-and-budget-policy-verification)
  do not establish real delayed transport or multiplayer behavior.

- Exercise [native incremental sweeps](../../architecture/presentation/inventory.md#native-incremental-reads) in live 540/900-slot inventories and an installed tested-version Create Vault: partial preview, immediate marker creation without a content getter, selected-preview updating display through the next eligible normal publication tick, a fresh complete first tracking result including zero, ordinary within-slot updates, completion-plus-period cadence, and old completed totals during replacement. The [headless native coverage](inventory.md#native-incremental-sweep-coverage) is not installed-provider or game-runtime evidence; cross-slot movement remains outside the accepted accuracy guarantee.
- Reproduce the reported 900-slot Vault case with simultaneous import/export of one item, including installed Create/Refined Storage interaction and a cleared old observed slot before selection. Check the provisional-to-fresh transition, incomplete grey history, invalidity without seed resurrection, exact/aggregate qualifiers, delayed Basic/parts and no provisional chat. [Bootstrap coverage](inventory.md#preview-seeded-tracking-coverage) does not establish the original incident's cause or guarantee all selection failures are resolved; no live reproduction is recorded.
- Exercise live owner/lock/loot, face/membership, load and topology changes during a large sweep, plus permission revocation, recovery, delayed multipart delivery and recipient-isolated repair in multiplayer. Preserve the existing marker lifetime and admission/range rules.
- Measure TPS, GC, heap retention and update latency under large-inventory and shared-consumer load on the supported loaders. Deterministic slot/work/memory admission and fixture tick bounds do not establish live performance.
- Pair before/after profiling of the user's actual 900-slot Create Vault under
  simultaneous Refined Storage import/export, recording loader/mod jar versions,
  active server configuration and Vault capacity/shape. Compare real allocation,
  CPU and GC profiles to locate remaining hotspots; the
  [headless allocation comparison](inventory.md#vault-tracking-performance-evidence)
  does not reproduce the reported live allocation rate or CPU percentage.
- Exercise the [patch-only identity and display boundary](../../architecture/presentation/inventory.md#item-variants-and-component-fallback) with installed modded containers and component wrappers, including owo-backed items, through live preview, item selection and tracking on the supported loaders. This remains pending; headless fixtures are not installed-mod evidence.
- Exercise client/server local-default differences and the terminal size-only default icon in the live inventory list and tracking HUD, including retained reprojection after a ceiling change and delivery delayed by period-byte pressure. Automated transition evidence is tracked in [inventory verification](inventory.md#patch-only-identity-and-display-verification-pending).
