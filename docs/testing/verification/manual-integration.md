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
- Exercise both full-width Tracking separation toggles immediately after Tracking
  Period with native mouse/keyboard input, all bundled locales' assembled
  Off/On/current-authority/next-scan help, draft reversion, read-only permission
  changes and the client-save-first root-close path. Confirm the ordinary no-ack
  update behavior rather than treating a clean draft as persistence success.
  Exercise matching configuration-v5 GUI endpoints and incompatible v4/v5 peers
  over real loader connections; headless codec rejection does not establish live
  mixed-version behavior. External server-file edits are not an automatic reload
  mechanism.

### Logical container targets and whole presentation

- In live multiplayer, ping opposite halves of normal and trapped double chests
  and different members of a supported NeoForge Create Vault. Check one visible
  winner per recipient, independent ownership/cancellation/expiry and selected
  item tracking, and recovery to a surviving loser. Include equal-arrival ties,
  different frozen audiences, owner and recipient disconnects, and same-owner
  repeated markers.
- Split and rejoin chests and alter Vault topology after creation. Check frozen
  logical identity and lifetime, new markers using current structure, and current
  presentation following verified metadata without widening inventory authority.
  Include locked and loot-backed chests, missing neighboring chunks and direct
  fallback, and preserve original hit anchors and exact preview selection roots.
- Exercise installed tested-version Create client synchronization: controller
  and member packets in differing orders, stale member dimensions, chunk
  unload/reload, missing/removed members, absent or unsupported Create, and
  recovery on later frames. Inspect per-cell baked-model outlines and fallback
  with GPU rendering; headless archive/metadata tests do not establish live
  packet ordering or rendered appearance. Verify ordinary chest behavior on all
  loaders and exact/direct Vault fallback on unsupported loaders.
- Exercise matching presentation-v2 negotiation and incompatible v1/v2 peers
  over real loader connections. Codec rejection is not a recorded live handshake
  or mixed-version gameplay result. Grouped receipt completion and inventory
  publication under real delayed/reordered delivery remain pending.

### Inventory

- On normal/trapped chests and an installed tested-version NeoForge Create Vault,
  exercise all four face/player separation combinations with two actual owners,
  differing faces and member hits, independently selected items and audiences.
  Verify authorized fresh-round sharing, Preview/new-selection isolation, creator
  departure and owner-only denial without losing an authorized peer's progress.
  Toggle during partial scans, established successor handoffs and queued multipart
  publication, including a rapid reverse toggle, without baseline/seed/lifetime
  changes solely from the toggle. Include stalled or abandoned successors and
  final-frame owner/lock revocation after earlier parts.
- Exercise partner/member chunk unload, replacement, lock/loot and topology
  changes during certified reads and queued delivery, including cross-chunk
  chests and a large Vault. Confirm safe invalidity, no forced chunk load or
  stale success, recovery under existing expiry rules, and exact fallback for
  unsupported/decorated/external providers. The
  [headless certified-pool matrix](inventory.md#certified-tracking-pool-and-publication-coverage)
  does not exercise installed Create lifecycle, live multiplayer or performance.
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
