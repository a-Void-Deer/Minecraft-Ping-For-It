# Manual and integration verification

[Verification index and evidence rules](README.md) · [Build checks](build-checks.md)

## Pending manual and integration matrix

### General

- General multiplayer and other-mod playtests remain pending.
  - Limited recorded observation: dev-environment manual playtests were
    performed, but their environment, scenarios, date and results are
    unrecorded, so they do not close this scenario.

### Wheel

- The [inventory-list input and presentation
  contract](../../architecture/picking/wheel.md#inventory-list) under live input
  remains pending on Fabric and Forge.
  - Limited recorded observation: NeoForge was exercised, but the environment,
    scenarios, date and results are unrecorded, so the observation does not
    establish full live-input validation.
- Focus loss and Alt-Tab, window replacement, screen handoffs and abort without
  overriding a newer cursor owner

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
- Without resizing, cycle a localized enum from a caption that fits a related
  half-width pair to one that requires stacking, then back. Check the pair's
  declared order, retained widgets, keyboard focus, scroll and one edit per
  activation after the native gesture completes. Include nested callback-driven
  changes, wheel scrolling and a caption change during slider drag that waits for
  release. Click Reset, release on its confirmation, cancel with Escape, then use
  Tab/keyboard to change captions and server size sliders. Also suspend a pending
  size-slider drag for a dialog and return; check retained focus/drafts, deferred
  dependent-control refresh and no stale drag or edit replay. The
  [assembled headless input assertions](configuration-ui.md#assembled-settings-screen-caption-reflow)
  use supplied measurements, not live font fitting or rendering.
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
  Exercise matching configuration-v5 GUI endpoints and incompatible older peers
  over real loader connections; headless codec rejection does not establish live
  mixed-version behavior. External server-file edits are not an automatic reload
  mechanism.
- On a live client and dedicated or integrated server, with a real macOS Option
  key and a Minecraft window, exercise both logging rows through activation
  with Alt/Option held and released, keyboard input and wheel scrolling,
  navigation, resize, root close, reset and read-only server viewing. Confirm
  that a current `DEBUG` value keeps its caption when Alt/Option is released,
  the next non-Alt activation exits to `NORMAL`, and `DEBUG` cannot be selected
  again without the modifier. Check that labels, narration and rendered help
  never explain `DEBUG` or hint at the activation gesture. Trigger
  representative failures under `NORMAL`, `DEEP` and `DEBUG` on both
  sides and inspect the deployed appenders. Check that severity is unchanged by
  the profile, `NORMAL` omits original messages/stacks/causes/suppressed detail/
  free context, `DEEP`/`DEBUG` include only domain-permitted bounded original
  detail, and explicit-channel suppression appears only where the owning domain
  defines it. External filters may drop output, and Forge/NeoForge `DEBUG`
  records may appear only in the loader debug log. Where a controlled
  fault-injection build permits, force a reporting failure and confirm one
  complete self-fault record on the emergency descriptor without closing it or
  replacing the main logger. These checks remain pending; the headless fixtures
  do not establish a live Minecraft window, a real macOS Option key, multiplayer
  or remote persistence, or live appender, layout or loader behavior.

### Performance monitor

All scenarios here remain pending, and none has been run. The
[performance monitor controls](../../UI/settings-screen.md#performance-monitor-controls)
are implemented, and the
[automated coverage](configuration-ui.md#performance-monitor-coverage), including
its earlier P1-P6 combined-tree execution, does not establish any live result.
**The earlier HUD UX redesign and [fixed semantic segment
wrapping](inventory.md#stable-semantic-hud-wrapping-coverage) have historical
headless coverage; the newer per-field `N/A`, per-row backing and reserved-height
UX now has [headless coverage](inventory.md#stable-hud-reservoir-ux-coverage),
with its execution record in [build
checks](build-checks.md#stable-hud-reservoir-ux-whole-tree-verification). The
live scenarios below remain pending.** The HUD scenarios below describe the
fixed upper-left, no-reserve projection with the new reservation contract;
earlier lower-left/reserved/visible-row assertions remain historical only. The
projection contract remains with the [performance monitoring
contract](../../architecture/presentation/inventory.md#performance-monitoring).
The 427x240 logical viewport for a 2560x1440 framebuffer at GUI scale 6 is a
computed headless reference, not a live window observation.

- On Fabric, Forge and NeoForge, exercise a dedicated server and an integrated
  server. Confirm the client and server scopes retain their eight and six
  navigation categories and that each Debug page's Performance monitor subgroup
  appears only for its own side's `DEBUG` profile: client runtime for the client
  subgroup, editable draft or read-only known snapshot for the server subgroup.
  Check that one side's `DEBUG` alone does not reveal the other side's subgroup.
- Leave and re-enter `DEBUG` on both sides. On the client, confirm the HUD turns
  off, metric selections remain, and returning to `DEBUG` does not re-enable the
  HUD. On an editable server page, confirm both collection switches become off
  in the draft only, collection continues until the draft is applied, re-entry
  does not restore the switches, and an accepted apply changes collection.
  Confirm a read-only viewer changes no collection state and that a clean draft
  is not treated as applied or persisted.
- Exercise the independent Preview and Tracking collection switches in all four
  on/off combinations with zero, one and multiple connected viewers. Check that
  a draft edit does not start collection, an applied lane drives its own
  telemetry, and Preview-only, Tracking-only and both-lane operation remain
  independent.
- Keep the `DEBUG` HUD open while dying and respawning on the same connection.
  Confirm server telemetry continues to update without reopening the HUD, and
  that this same-connection respawn is distinct from a disconnect/reconnect,
  which starts a fresh watch. The headless respawn and replacement-listener
  checks do not establish live behavior.
- With an administrator and a non-administrator connected, confirm server-wide
  numeric rows and the single Shared area are visible only to the administrator,
  while the non-administrator retains local rows. Confirm the limited `STATUS`
  summary carries no numeric statistics. Grant and revoke level 3 during a
  session, including between subscription and a later sample; confirm numeric
  rows clear on revocation, local rows remain, and inventory functions and
  permissions do not change.
- Produce each monitor state: explicitly off lanes, the Shared dependency-off
  state when both applied lanes are off, `WAITING` before the first sample,
  `UNKNOWN` without a response, `STALE` after expiry, `UNSUPPORTED` without the
  capability, and `UNAUTHORIZED` without numeric telemetry authority. Confirm
  the source/lane labels and state captions are localized; an off lane reads,
  for example, `Server - Inv Prev: OFF` or `Server - Inv Track: OFF` in English,
  with only the OFF token red. With both applied lanes off, confirm the
  localized Shared dependency-off label is distinct from `UNKNOWN`,
  `UNSUPPORTED`, `UNAUTHORIZED`, and `STALE`, and that an off lane or the
  dependency-off state does not close the HUD, clear selections, or hide local
  rows. Confirm a timeout never displays OFF.
- Inspect the fixed upper-left GUI-scaled HUD at multiple GUI scales and window
  sizes, including the 2560x1440 framebuffer at GUI scale 6 (427x240 logical
  viewport) and zero/extreme narrow viewports. Confirm native GPU state and
  pixel output, that the HUD does not reserve
  chat, heart/absorption or configuration-notice clearance and does not cap
  rows to the visible height: every prepared row is submitted, including rows
  whose bottom extends past the viewport, and the framebuffer clips them
  naturally without changing chat, hearts, notices or layout. Check that long
  unspaced CJK and Latin tokens wrap at the right edge using native glyph width,
  preserve the OFF token style and do not overflow horizontally. Confirm Local,
  Server and Shared sources remain distinct and readable.
- Exercise the fixed semantic HUD segments at those widths, including the
  427x240 reference, narrow and wide viewports. Confirm each timing phase, the
  non-sync requests/queue/work/state segment, each independent budget, memory
  and sync begins on its own visual line at the same left margin; changing value
  digits or units must not pull a following segment onto an earlier line or
  merge adjacent segments, while a segment that itself exceeds the width wraps
  natively and may push later lines down. Confirm the scope label appears once,
  continuation lines add no indentation or summary heading, and every selected
  field keeps its label and position while only a missing value position shows
  `N/A`; known zeros stay values, partial pairs or triplets retain known
  components, a summary does not expand a hidden constituent, and max-only adds
  no mean. Confirm no selected group or label disappears because of `N/A`.
  Repeat with Latin and CJK text in the bundled locales.
- Exercise active-layout stability under the new reservation contract. With the
  same selected set, effective width, Font/Language identity, glyph options,
  line height and connection, confirm a two-line group that becomes a one-line
  `N/A` value and returns to two lines keeps two reserved lines, and a
  one-to-three-to-one value sequence keeps three. A shorter or unavailable value
  must not pull later groups up; longer text may push them down; reserved blank
  rows paint no old value, label or backing. Confirm a selection, width,
  font/language change including a font-resource reload, glyph-option or
  line-height change clears the reservation, even while the HUD is hidden, while
  a numeric, sample, view, availability, height or GUI-scale-only change does
  not. Toggle the HUD off and on under an unchanged
  identity and confirm the heights survive; disconnect and reconnect and confirm
  they reset, while a same-connection respawn does not. Produce `OFF`, `WAITING`
  and `UNAUTHORIZED` control states and confirm only the status row is painted
  with no active padding or stale values, later scopes may move, the reservation
  is not cleared, and returning to `ACTIVE` restores the prior height.
- Exercise the compact labels and unified values in all eight bundled locales,
  including font-size changes, Force Unicode, Japanese variants, HiDPI displays
  and resource reloads. Check the byte-only four-digit cap at `8192B`/`8193B`, the
  `999.95KB` to `1000KB` carry, `999.4451KB` to `999.4KB` single rounding, and
  non-byte `999.95s`/`>=999.95/s` precision retention; use no space between
  number and unit (`8192B`, `8KB`, `1000ms`, `1s`). Check full non-byte huge
  integers, `<0.01`, negative/lower-bound/saturated/unknown readings, shared
  memory above 100% and a zero cap. Include a partially known field and mixed
  missing values; known components and zeros stay values and only missing
  positions read `N/A`. Verify phase rows show actual interval averages (ms or s)
  and max intervals rather than fabricated per-tick numbers, summary/raw rows do
  not duplicate each other, and the requests row uses only the existing Server
  open/received/admitted and Local applied/rejected readings.
- Check the per-row backing and bottom layer with the totem/animation overlay,
  F3, chat, inventory and pause screens open: the HUD must stay below those
  HUD/screen layers and never appear as a post-screen top overlay. On a real GL
  context, confirm each painted text row has the black [per-row
  backing](../../architecture/presentation/inventory.md#layout-and-render-layer)
  at the contractual alpha, covering only that row's glyph width, with the
  original font shadow and no panel-wide background; reserved blank rows paint
  neither text nor backing. At the right edge with wrapped CJK/Latin text,
  confirm the backing follows the glyph run and the shadow remains. Confirm the
  [scope-title and field
  colors](../../architecture/presentation/inventory.md#scope-groups-and-compact-labels)
  keep the source/lane prefix alone in the title color, ordinary labels and
  values in the original HUD color, and only `OFF` red; the existing bottom-layer
  gate and the three loader registrations and level gates are unchanged. Confirm
  no position setting and the original inter-field gap.
- Exercise Preview-only, Tracking-only and both lanes under representative
  inventory activity. Confirm the four metric classes, the single Shared area,
  and the default concise summary (processing time, requests and queue,
  principal budget, retained memory, and synchronization rate); detailed items
  and peaks remain opt-in. Verify client-local and server timings are not
  combined. Metric definitions and accounting remain owned by
  [inventory budgets, queues and memory](../../architecture/presentation/inventory.md#budgets-queues-and-memory).

The previous lower-left, reserved-clearance and visible-row-clipping HUD
assertions, including the historical `InventoryMonitorHudTest` and
`MonitorDisplayModelTest` results, remain historical coverage of the prior
layout; they do not establish this redesign or any live scenario above. The
preceding fixed semantic wrapping has separate historical headless coverage
inventoried in [inventory
verification](inventory.md#stable-semantic-hud-wrapping-coverage); the newer
per-field `N/A`, per-row backing and reserved-height relations now have headless
coverage in [inventory
verification](inventory.md#stable-hud-reservoir-ux-coverage), with execution
evidence in [build
checks](build-checks.md#stable-hud-reservoir-ux-whole-tree-verification). No
live GPU/font/window/layer, running-loader/Mixin, multiplayer or
performance/allocation result is recorded, and the existing 16 native-classpath
skips remain gaps. The added HUD pass's CPU cost and the whole-frame
allocation/GPU-query cost remain unmeasured; the earlier disabled monitor-API
allocation evidence does not cover them.

### Logical container targets and whole presentation

- In live multiplayer, ping opposite halves of normal and trapped double chests
  and different members of supported NeoForge Create Vaults and fluid tanks.
  Check one visible winner per recipient, independent ownership/cancellation/expiry
  and selected-resource tracking, and recovery to a surviving loser. Include
  equal-arrival ties, different frozen audiences, owner and recipient disconnects,
  and same-owner repeated markers.
- Split and rejoin chests and alter Vault or tank topology after creation. Check
  frozen logical identity and lifetime, new markers using current structure, and
  current presentation following verified metadata without widening inventory
  authority. Include locked and loot-backed chests, missing neighboring chunks and direct
  fallback, and preserve original hit anchors and exact preview selection roots.
- Exercise installed tested-version Create client synchronization: controller
  and member packets in differing orders, stale member dimensions, chunk
  unload/reload, missing/removed members, absent or unsupported Create, and
  recovery on later frames. Inspect per-cell baked-model outlines and fallback
  with GPU rendering; headless archive/metadata tests do not establish live
  packet ordering or rendered appearance. Verify ordinary chest behavior on all
  loaders and exact/direct Vault and tank fallback on unsupported loaders.
- On an ordinary static tank, toggle boiler activity and check complete per-cell
  presentation while its fluid read route remains independently gated. Include
  creative tanks as a different-registry control.
- Exercise matching presentation-v2 negotiation and incompatible v1/v2 peers
  over real loader connections. Codec rejection is not a recorded live handshake
  or mixed-version gameplay result. Grouped receipt completion and inventory
  publication under real delayed/reordered delivery remain pending.

### Inventory

- On normal/trapped chests and an installed tested-version NeoForge Create Vault,
  exercise all four face/player separation combinations with two actual owners,
  differing faces and member hits, independently selected items and audiences.
  Verify independently authorized active-prefix joins, zero-based per-consumer
  replay, Preview-only isolation and completed-round exclusion, creator departure
  and owner-only denial without losing an authorized peer's progress.
  Toggle during partial scans, established successor handoffs and queued multipart
  publication, including a rapid reverse toggle, without baseline/seed/lifetime
  changes solely from the toggle. Include stalled or abandoned successors and
  final-frame owner/lock revocation after earlier parts.
- With two connected owners on separate private channels, disable player
  separation and track the same unlocked 27-slot chest under the legal physical
  cap of 16 and a permission-gated inventory field policy. Remove B's field
  permission by de-op without disconnecting or making the source unreadable,
  both before a successor grant and after one has been established. Under
  create-time frozen recipient authorization, B's existing Ping keeps the
  content set authorized at its create; de-op does not retract it, and re-op
  does not backfill old Pings. Confirm A completes multiple later refreshes,
  B's old Ping keeps only its create-authorized content, a new Ping created
  after re-op receives the newly authorized content, and frozen audiences and
  marker lifetime do not change. Include an authorized peer waiting only for
  cadence as the continuity control. The
  [headless successor assertions](inventory.md#recipientless-tracking-and-successor-continuity)
  do not exercise live permission or channel changes.
- Under the legal physical cap of 16, capture certified first-page and
  retained-prefix budget-delay WARN output. Check that exhausted physical slots
  report only that reason with known remaining coverage; separately force actual
  memory/provider/item-processing pressure and check only the observed limiting
  categories, deduplicated quantities and resumed progress. Include paid-journal
  replay with no new physical allowance and no false delay. The
  [closed-cycle summary assertions](shared-sources.md#certified-pool-delay-diagnostics)
  do not establish live log output or administrator-message delivery.
- Keep completed unselected Previews open while two owner-partitioned Tracking
  cohorts update repeatedly. Verify original request rows/references remain usable
  under their validity rules without reserving future Tracking sweeps, periodic
  Preview recapture or forced close. The
  [headless lifetime regression](inventory.md#open-preview-and-recurring-tracking-lifetime)
  does not exercise the stay-open screen in game.
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

- Exercise [native incremental sweeps](../../architecture/presentation/inventory.md#native-incremental-reads) in live 540/900-slot inventories and the user-confirmed 1,620-slot installed tested-version Create Vault: partial preview, immediate marker creation without a content getter, selected-preview updating display through the next eligible normal publication tick, a complete first tracking result including zero, ordinary within-slot updates, completion-plus-period cadence, and old completed totals during replacement. Include pre-attachment prefix values on certified active joins and fresh first rounds on unsupported routes. The [headless native coverage](inventory.md#native-incremental-sweep-coverage) is not installed-provider or game-runtime evidence; cross-slot movement remains outside the accepted accuracy guarantee.
- Reproduce the reported Vault case with simultaneous import/export of one item, including installed Create/Refined Storage interaction and a cleared old observed slot before selection. Check the provisional-to-complete transition, incomplete grey history, invalidity without seed resurrection, exact/aggregate qualifiers, delayed Basic/parts, the immediate frozen creation-snapshot chat with its uncertainty notation, and its independence from later Tracking updates. [Bootstrap coverage](inventory.md#preview-seeded-tracking-coverage) does not establish the original incident's cause or guarantee all selection failures are resolved; no live reproduction is recorded.
- Exercise live owner/lock/loot, face/membership, load and topology changes during a large sweep, plus permission revocation, recovery, delayed multipart delivery and recipient-isolated repair in multiplayer. Preserve the existing marker lifetime and admission/range rules.
- Measure TPS, GC, heap retention and update latency under large-inventory and shared-consumer load on the supported loaders. Deterministic slot/work/memory admission and fixture tick bounds do not establish live performance.
- Pair before/after profiling of the user's confirmed 81-member Create Vault under
  simultaneous Refined Storage import/export, recording loader/mod jar versions,
  active server configuration and Vault capacity/shape. Compare real allocation,
  CPU and GC profiles to locate remaining hotspots; the
  [headless allocation comparison](inventory.md#vault-tracking-performance-evidence)
  does not reproduce the reported live allocation rate or CPU percentage.
- Exercise the [patch-only identity and display boundary](../../architecture/presentation/inventory.md#item-variants-and-component-fallback) with installed modded containers and component wrappers, including owo-backed items, through live preview, item selection and tracking on the supported loaders. This remains pending; headless fixtures are not installed-mod evidence.
- Exercise client/server local-default differences and the terminal size-only default icon in the live inventory list and tracking HUD, including retained reprojection after a ceiling change and delivery delayed by period-byte pressure. Automated transition evidence is tracked in [inventory verification](inventory.md#patch-only-identity-and-display-coverage-and-live-gaps).

### Frozen recipient authorization and provider trust

All scenarios here are pending. They cover
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md);
the passing dynamic recipient and member-gate tests inventoried elsewhere are
historical and do not establish this policy. The current-source automated cases
in the [identity](identity-authority.md#frozen-recipient-authorization-coverage-and-live-gaps),
[presentation](presentation.md#frozen-recipient-content-authorization-coverage-and-live-gaps)
and [inventory](inventory.md#frozen-recipient-authorization-and-fluid-provider-trust-coverage-and-live-gaps)
inventories passed in the final combined execution recorded in
[build checks](build-checks.md#provider-trust-and-frozen-authorization-final-verification);
they do not replace these live scenarios, and provider trust claims no measured
live performance benefit.

- With two connected owners, create Pings across Basic, Property, SECTION,
  Inventory and receipt. Change selector, child, provider, `RESET` and metadata
  policy after creation. Record which content old Pings keep and which new Pings
  receive.
- Open an Inventory Preview, then select. Compare the Preview-start and `SELECT`
  create authorization outcomes.
- Keep an original-audience member that had not completed `HELLO` at create
  late-ready during delivery or repair, and separately exercise a true disconnect.
- Revoke source-owner read authority separately from recipient permission.
  Confirm reads still reject while the create-time recipient result remains as
  recorded.
- On supported fluid targets, inspect Preview and Tracking provider validation
  and confirm the hardcoded stricter-validation exception set is empty.

### Fluid inventory

All scenarios here are pending. The
[fluid inventory automated coverage](inventory.md#fluid-inventory-coverage)
records the final headless coverage separately from these unperformed live checks.

- On Minecraft 1.21.1 Fabric, Forge 52.0.2 and the supported NeoForge setup,
  open a static block that exposes both items and fluids. Check one panel's
  resource groups, independent field authorization/status/completion, shared
  request close, one-resource selection, replay and ordinary annotation policy.
  Deny and restore only one field while the other remains usable; include
  multipart repair, delayed controls and known-marker removal.
- Keep a selected row stationary while an unrelated variant folds or either
  lane replaces its baseline, including equal opaque keys in both groups and a
  scrolled list. Check that a still-selectable lane-qualified key survives and
  release submits its intended resource, with fallback only when that row is
  actually removed or invalid.
- Exercise Fabric component variants and sub-millibucket amounts, Forge NBT
  variants, and NeoForge component variants through actual capabilities. Include
  same-ID distinct variants, identity-only refusal, display-only refusal,
  checked conversion/sum limits, true empty views and selected-resource absence
  only after complete scanning. Confirm frozen-face and owner/member/load gates,
  no forced load, mutation or creation-time presence getter, and no stale
  success after revocation or topology loss.
- Replace a Fabric public wrapper while preserving its stable native underlying
  storage, then merge or detach that underlying storage. Check current-wrapper
  reads, bounded validation, invalidation only where continuity is lost and
  fresh complete recovery. On a generic opaque provider, exercise exposed
  route/count/capacity changes and the accurate per-server/provider-class INFO
  notice; do not treat unchanged public metadata as proof against a hidden remap.
- In an installed tested-version NeoForge Create tank, target different members
  and faces, including cross-chunk load changes, controller replacement, merge,
  split, creative tanks and active boilers. Confirm controller-only quantity
  without member duplication, fixed-face capability access, mode denial without
  generic fallback and fresh recovery under the original marker expiry. Repeat
  the creative-tank denial and valid 3x3x20 (180-member) and 3x3x32
  (288-member) targets with each side's logging profile at `DEBUG`: correlate
  the request, resource kind and target; for a denial or a changed
  member/controller, observe the first rejection's stage/reason and
  controller/shape/member/handler context, then the matching selector
  transition to grey status. At `NORMAL`, expected unavailability stays absent.
  A successful 180- or 288-member tank completes without a rejection line or
  size refusal; a target whose hit lies outside the verified prism names the
  distinct `hit-outside-structure` reason rather than the member limit. While
  sampling the same 288-member tank at roughly 3-5 Hz for 20 s, compare TPS tail
  latency with a matched run that samples no fluid target; this remains a
  pending measurement. These checks do not widen the supported topology.
- With an installed Sable provider and an otherwise supported fluid target,
  move the same target under the same live lease to another sub-level. Check
  `UPDATING` retains the displayed quantity without transition-only `INVALID`
  or duplicate chat, new-scope permission/physical evidence is established and
  only a fresh complete delivered result replaces the quantity, including zero.
  Withhold final parts and inject real denial, unreadability, release and expiry;
  old cuts/seeds/partial sums must not complete the new scope. Keep item and
  ordinary multiblock invalidity as independent controls.
- Compare list, HUD and exactly-once content chat in bundled locales for zero,
  integer mB, positive sub-mB, exact buckets and very large quantities. Inspect
  approximate indicators, six-significant-digit large forms, Latin billion
  versus bucket labels, CJK units, long custom names and safe empty icons for
  unknown/malformed display data. Exercise narrow viewports, fractional text
  scales, native font fitting and GPU output, not only localized resource presence.
- With installed native fluid handlers, include a non-placeable fluid such as
  supported Create tea. Check localized base names, missing-translation fallback,
  literal custom names, native still sprites and text-only failure. Reload client
  resources after both a successful and a failed lookup. Launch a dedicated
  server with the loader name service present and confirm no client render
  initialization is required.
- Exercise mixed inventory-v1/v2 peers on real loader connections and matching
  v2 peers with delayed multipart delivery. Confirm the dedicated route has no
  v1 reinterpretation, while unrelated presentation/configuration behavior stays
  on its established version contracts. Measure combined item/fluid work under
  low shared budgets before making throughput, TPS, allocation or GC claims.
