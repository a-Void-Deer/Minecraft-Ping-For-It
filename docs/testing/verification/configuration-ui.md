# Configuration and settings UI verification

[Verification index and evidence rules](README.md) ·
[Pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)

## Configuration recovery and selection grammar

The current suite covers:

- block whitelist grammar and evaluation;
- invalid client-config recovery;
- `ConfigHandlerResetTest` coverage of resetting in-memory values to defaults
  and preserving the bytes of a protected future-version file;
- config-version marker, precedence, migration, client/server recovery, and
  future-version preservation seams.

`ConfigHandlerResetTest` also covers independent client selector objects and
mutable remembered-channel maps, reset replacing both, stale references being
unable to affect a later save, and post-reset edits surviving save and reload.
Its server reset fixture checks detached inventory and presentation state:
editing pre-reset references cannot replace the fresh defaults on save/reload.
These reset-isolation cases are source-reviewed automated coverage, with no
passing run recorded here; they do not exercise live settings editors.

`ConfigDefaultsIsolationTest` covers active server-default initialization in a
fresh isolated classloader with client/presentation default groups, platform
services, configuration models/handlers and selected optional integrations
unavailable. Project helpers are child-loaded; explicit denial probes and a
`ModContext` probe exercise direct and transitive platform denial. The initialized
server channel-mode enum remains usable through name/value lookup without
pinning its selected default. This is source-reviewed isolated-initialization
coverage, with no passing run recorded here; it does not establish game startup,
loader-specific dedicated-server initialization or multiplayer behavior.

`ConfigHandlerDiagnosticsTest` covers filesystem-backed load-validation,
backup, save and recovery-reset failures. Warning reports retain long original
message tails and root causes within the diagnostic bound, without an attached
throwable; the generated recovery reason remains a separate safe one-line
comment. Failed backup preserves the original bytes and blocks save/reset
writes while leaving defaults in memory; failed initial save creates no file,
and failed recovery-reset preserves the original source and blocks subsequent
saves. These are handler and injected-failure seams, not a live settings screen
or client/server persistence session.

`TransparentBlockSelectionMigrationTest` contains filesystem-backed fixtures
for the transparent-toggle transformation at the existing client beta1
boundary: legacy decoded values retain their actual raycast effect, a present
new key takes precedence, malformed authoritative values follow parser recovery
and backup protection, and unrelated root and nested data survive. A guarded
writeback fixture retains explicit unknown nulls at the root, inside objects
and arrays, and under spatial-selector preferences through failure, retry and
reload, without materializing an omitted nullable model field. Its
pre-boundary handler cases check legacy-schema projection for fresh files,
ordinary saves, resets and guarded writeback, preservation of an already-present
unknown future key, and a later upgrade retaining the selected effect.
Same-version fixtures keep the obsolete-key load and untouched save as no-ops
while a real serialization removes the retired key; failed writes retain the
usable policy, and retry observes changed-source and future-version guards.
These are source-reviewed model and persistence fixtures, not a recorded test
run, native key-binding load or gameplay evidence. The transformation and
recovery contracts remain owned by
[configuration revisioning](../../architecture/config/revisioning.md#ordered-migration-and-writeback).

## Inventory and spatial-selector configuration

`InventorySettingsTest` covers the authoritative inventory budget model:
explicit unlimited caps keep their finite value and use the caller-owned finite
guard, finite caps clamp to positive instead of a disable sentinel, confirmed
finite boundaries clamp, zero heartbeat disables only the periodic cadence, the
two multiplier grids normalize piecewise values, byte budgets scale their own
scope's base and queues follow their corresponding global allowances, the five
byte multipliers stay independent and control only their own scope, the snapshot fragment and
per-client period scopes stay equal, unlimited byte multipliers stay finite,
the step helpers cross boundaries and saturate, pending-memory steps follow the
piecewise stops, and independent defaults do not alias. The isolation fixture
exercises all nine caps and five send-byte multipliers within and across fresh
settings, checks that later construction cannot inherit mutations, and restores
null nested objects to values equivalent to fresh construction without aliasing.
These isolation cases are source-reviewed automated coverage, with no passing
run recorded here. The two finite size leaves have exact byte/quanta conversion
and repair a conflicting pair downward with frame priority and the reserved
metadata headroom while never raising the frame, and they remain independent of the five
send-byte multipliers; `InventorySizeScaleTest` covers the ordered legal
positions and their exact byte and quanta conversion, half-up normalization,
pair legality, frame-priority reconcile and ordered position stepping.
For the Performance controls' coverage, see
[Server performance contract verification](#server-performance-contract-verification).
`SpatialSelectorSettingsTest` covers the spatial-selector settings snapshot and
bounds. A missing, explicit-null or fresh-file center deadzone initializes the
current confirmed default while representative valid saved deadzone values,
including both bounds, are retained literally without rescaling and a held
snapshot keeps its frozen deadzone, the
distance and timing clamps preserve the valid interval and saturate on both
sides, every supported stroke can leave every supported center deadzone, and
null and out-of-range nested values validate without throwing, the opt-in
Back-hover default stays disabled,
a missing or explicit-null submenu radius scale gains the confirmed default and
the value clamps and freezes into the snapshot, the precise capture period
clamps to its persisted range and freezes into the snapshot, the content-intent
choice defaults disabled for fresh, missing and
null values, an explicit choice is retained and freezes into the held snapshot
while the live setter supplies the next snapshot, the canonical snapshot
constructor carries the choice while every previous constructor form defaults
it, the previous snapshot constructor forms remain compatible and default
the submenu radius scale and precise capture period, raw-JSON validation and a
frozen snapshot clamp every value without resetting boolean choices, a frozen
session snapshot never follows later config edits, root distance and gesture
preferences stay independent in both mutation directions, and fresh defaults do
not alias another client's mutable preferences.
`SpatialSelectorMigrationTest` contains filesystem-backed cases for the obsolete
preference catalogued by
[client configuration](../../config/client.md#obsolete-keys): fresh output omits
it, existing null, numeric and object-shaped values do not affect the live
snapshot, a same-version load and untouched save preserve the source bytes,
and reload keeps the same effective preferences without creating a backup.
Its partial-selector upgrade case checks retained unknown nested data,
including that obsolete member, separately from the modeled preferences.
Same-version deadzone defaults follow the no-rewrite rule: a fresh file
persists the current default, missing, null and explicit saved deadzone values
are retained without rescaling, an untouched
same-version save leaves the bytes unchanged, and a reload keeps the value. It
covers the release-boundary retirement of obsolete wheel keys without injecting
selector defaults: the exact boundaries and pre-target versions retire the old
wheel radius keys and the retired wheel timeout key while unknown root data and
appearance values survive, a running version below a boundary keeps its key,
at-or-after-introduction and server configs are not consumed, a target-version
upgrade persists model defaults while preserving unknown nested selector data,
same-version absent, partial, and null selectors gain model defaults without a
load rewrite, a pre-target writeback keeps old key data until the target
upgrade, a current-version load is not retired-key normalization while a
target-version save cannot resurrect the retired keys,
every explicit preference round-trips independently of appearance, including
the precise capture period added without a migration, the content-intent
member never rewrites a same-version file for missing, null or explicit values,
an explicit choice persists across reload, and reset restores the disabled
default, and the future-version guard keeps all bytes and refuses save and
reset; an injected
failed write retains usable in-memory preferences and a retry discards the
stale source before migration. `InventorySettingsPersistenceTest` covers additive
defaults without rewriting a current-version file, older-version migration
writing the nested inventory object while preserving user data, explicit
unlimited round trips, every inventory cap leaf, including the nested preview
and tracking caps, restoring its owning field's model default through the real
filesystem-backed load path when its `value` member or the whole cap object
is absent or null, a missing `unlimited` member defaulting false,
`unlimited: true` without a value staying unlimited with the model default
finite value, an explicit zero or negative value still clamping to the finite
minimum, an untouched load and no-op save leaving the bytes unchanged and
an explicit save materializing the restored default without touching sibling
caps, missing, null and out-of-range size scalars normalizing
with frame priority while an explicit size scalar round-trips and an
unlimited-wrapped size scalar rejects the file, out-of-range values clamping
without resetting unrelated fields or producing a broken backup, the
future-version guard leaving defaults in memory and refusing to save, and the
client
spatial-selector keys defaulting and clamping, including the submenu radius
scale's missing-member default, out-of-range clamp without resetting unrelated
fields, serialized key, and persisted round trip, and the precise capture
period's persisted round trip. Trail length and opacity coverage includes
Gson-initialized missing/null values, retained explicit zero and 73 opacity
values, frozen snapshot values, unchanged same-version bytes on load, an
untouched save as a no-op, and round-trip persistence. A target-version
upgrade preserves explicit values and unknown nested selector data. These are
model and persistence seams only: no server-administration path, remote change
route, settings-widget interaction, scheduler consumption or full client
selector runtime is exercised.

`ByteMultiplierDefaultsTest` covers owning-field fallback substitution with
distinct non-unit values on both grids: missing objects, empty objects and null
values restore the supplied fallback; explicit values take precedence;
validation is idempotent and retains unlimited mode. Byte calculation leaves
missing values unmaterialized and explicit values unnormalized, while unlimited
mode returns the caller's guard before consulting the grid. The compatibility
APIs retain their finite one-times meaning.
`InventorySettingsPersistenceTest` exercises all five send-byte multiplier
fields through filesystem-backed load: omitted or null objects, empty objects,
missing or null values, and unlimited mode without a value restore each owning
field's default without changing explicit siblings. Same-version load and
untouched save preserve the original bytes; omitted and unlimited-without-value
cases materialize the restored value on a real save and retain it after reload.
Explicit zero and negative values clamp to their grid minimum while sibling
multipliers and caps retain their values. These multiplier cases are
source-reviewed automated coverage, with no passing run recorded here; they do
not exercise scheduler consumption or live administration.

The inventory-panel fraction fixtures in `SpatialSelectorSettingsTest` check
missing and individually null members against model initialization without
changing the other dimension, a safe snapshot leaving a raw null member
unmodified, independent clamping through validation and setters, normalized
decimal representations retaining in-range off-grid precision, and unrelated
preferences remaining intact. A held snapshot keeps its fractions after live
edits; every previous constructor form initializes the fractions, while the
canonical form carries custom fractions alongside the supplied choices.
`SpatialSelectorMigrationTest` contains filesystem-backed cases for the same
preferences: absent or empty selectors keep same-version load and untouched-save
bytes unchanged; explicit grid and off-grid values survive a real save and
reload; null and out-of-range values legalize in memory without a load rewrite
and serialize on a real save. An older source gains missing defaults through
the existing migration while unknown root and nested data survive. An injected
pending-migration write failure leaves the source bytes and usable explicit
values intact, and retry plus reload preserve those values and unknown data.
The future-version fixture uses model defaults in memory and refuses save and
reset writes without changing protected bytes. These are source-reviewed model
and persistence fixtures, not recorded test runs, live settings controls or
selector-layout evidence. The preference contract remains owned by
[client configuration](../../config/client.md#spatial-selector-interaction).

`GestureSkipHoldConfigTest` covers fresh, missing and null shortcut preferences
staying opt-in disabled, missing/null waits and older snapshot constructor waits
matching a fresh model, and minimum-wait clamping through setters, validation
and snapshot construction. Explicit input/expected pairs check integer extremes
and just-outside values saturating to the corresponding bound, with both
endpoints and an interior value retained. Held snapshots keep their preference
and wait after live edits; older constructor forms keep the shortcut disabled.
It exercises
compatibility clearing an enabled preference without later restoring it,
preventing re-enablement while compatibility is active, and retaining the
configured wait. Filesystem-backed cases cover explicit round-trip values,
missing members and an untouched save leaving same-version bytes unchanged,
explicit conflicting booleans being persistently reconciled through guarded
same-version normalization while unknown nested data survives, and persistence
across reload. A failed normalization remains pending; a later external
future-version edit is not overwritten by save or reset, and future-version
and invalid-file protections still decline gesture-related edits. These are
model and handler seams, not live settings-widget interaction. The runtime
eligibility and trajectory evidence is inventoried in
[interaction verification](interaction.md#gesture-shortcut-opening).

## Double-click Danger and inventory stay-open configuration

The two press preferences and the stay-open choice have source-reviewed model,
handler, codec, catalog and resource coverage. None of it is a recorded run.

`DoubleClickDangerConfigTest` covers the two press preferences as model and
handler seams: explicit window and angle values remaining independent of the
cancel half-cone in both mutation directions; missing and null JSON values using
model-default fallback and out-of-range values clamping; compatibility
clearing the angle and rejecting non-zero until disabled; validation and update
enforcing the same zero; NaN recovering to the default while infinity clamps;
string NaN loading as a finite default; filesystem-backed same-version
normalization of decodable string numbers, `NaN` and `Infinity` under
compatibility to the disabled zero with the guarded writeback, including
retained unknown data and a materialized zero for a missing or null angle; no
rewrite for an explicit zero or disabled compatibility; an ordinary round trip;
failed-write retry; changed-source reload; and the separate typed invalid-file
recovery for an undecodable value (defaults plus a broken-file backup), with a
failed backup installing the protection that declines angle writes.

`ClientConfigBoundsTest` exercises direct window and angle clamps, including NaN
and positive infinity.
`SpatialSelectorSettingsTest` covers the inventory stay-open choice defaulting
closed, missing and null
members staying closed, the live setter affecting the next snapshot, and a held
snapshot retaining its frozen choice. `ClientConfigLocalizationTest` covers the
eight bundled locales' labels and tooltips, placeholder signatures, the
shared off value, and the pinned Chinese and English off strings.
`SettingsCategoryCatalogTest` places the window, angle and stay-open controls
once each in the client input category. `OptionUtilsDecimalTest` covers
every half-degree stop, out-of-range rejection, and the complete
`<setting name>: <off value>` label at zero. These are model, handler, codec,
catalog and resource seams, not a recorded run, live widget input, or a
constructed or rendered settings screen; screen presentation remains owned by
[configuration UI](../../UI/settings-screen.md#input-interaction-controls).

## Server-settings snapshots and updates

`ServerSettingsModelTest` covers request correlation, stale responses, denial,
draft state, and update planning. It also covers a safe non-editable snapshot
being accepted and rendered for a viewer below the required level without
enabling edits or an update plan, unsafe or uncorrelated snapshots never
becoming viewable, retaining a read-only view through permission revocation,
restoring an editable session for a retained editable snapshot, dropping a
retained read-only view and requiring a fresh request after promotion to editor,
an authoritative denial keeping the snapshot viewable but not editable, the
per-field invalid numeric draft mask and its repair, clearing that mask on
permission revocation, disconnect, and an explicit clean mark that preserves
draft text, the shared once-per-session request that does not replace a loaded
or in-flight snapshot, denial lock with the false-to-true permission retry
transition, and connection-scoped disconnect reset. `ServerConfigUpdateTest`,
`ServerConfigUpdateServiceTest`, and the focused server-config packet tests cover
field-masked partial merge and codec/handler seams. The monitoring-switch cases
cover selected true/false, preservation of unselected false, immutable snapshot
conversions and compatible constructor defaults, permission denial, and atomic
rejection beside an invalid inventory size pair. The independent raw packet
fixtures cover the final strict boolean, shorter superseded shapes, truncation,
invalid boolean bytes, trailing bytes and unknown mask bits. The
[transaction](../../architecture/config/changing-server-config.md) and
[wire](../../architecture/network/protocol.md#server-configuration-routes-server-config-request-v5-server-config-update-v5-server-config-snapshot-v5)
owners retain the exact field selection and grammar. These model and packet seams
do not establish the live settings UI, permission changes over a connection,
actual packet exchange, or persistence behavior in a running client/server
session.

`InventoryConfigValuesTest` covers the twenty-three-leaf admin inventory value
model: each leaf's merge preserves every unselected current value and stays
independent of the rate-limit, channel, sync and monitoring fields; the two
finite size leaves are exact scalar multipliers with no unlimited mode, a
selected pair is accepted only when both leaves are present and legal, and an
invalid merged pair rejects atomically without touching unselected leaves; an invalid leaf
rejects the whole mixed update and unsafe snapshots fail closed without
defaulting; each leaf converts to and from its persisted member without
aliasing; missing, null, out-of-range, off-grid decimal and unsupported-unlimited
values are unsafe without substitution; and unsafe current or missing updates never report an
applied transaction. `ServerInventoryConfigPacketsTest` has established strict
administration codec seams: every leaf and explicit unlimited finite value
round-tripping; the two finite size leaves
carrying one exact quarter-multiplier quanta value with no unlimited flag and
every legal grid value exact on the wire; truncation, old-prefix, and trailing
data rejecting without throwing; an invalid encoded leaf or malformed mode
rejecting the whole packet; and invalid zero or overflowing quanta, unknown
masks, missing inventory, nonpositive caps, and out-of-range scalars failing
closed without clamping. The refreshed version-five, memory mode, revised-bound
and literal non-step integer fixtures are inventoried
[below](#server-performance-contract-verification).
`ServerConfigVarNumbersTest`
covers canonical integer and positive correlation boundaries, truncation,
overflow, signed-width and noncanonical encodings, malformed update/snapshot
numbers failing closed before service mutation or view publication, and legal
maximum values retaining their exact meaning. `ServerInventoryDraftTest` covers
the admin draft model: per-leaf dirty, revert, and independent update-plan state; a malformed
leaf not erasing other invalid drafts or permitting partial updates; read-only
values visible but every edit inert; unlimited leaves retaining finite text;
decimal parsing and stepped boundaries; the session lifecycle across
navigation, permission revocation, and disconnect; and unsafe or stale
responses neither installing a view nor overwriting drafts. The two size leaves
use ordered slider positions with exact physical endpoints, the frame slider
caps the display slider and lowers it atomically with a dual dirty mark and an
adjustment notice, every size edit entry point reconciles the pair and none
emits an invalid pair plan, valid display text edits clamp to the
frame-compatible maximum, raising the frame never raises the display, malformed
size text stays visible and blocks the plan, the notice clears on revert and
snapshot refresh, and the size leaves have no unlimited mode and ignore
read-only edits. The monitoring-switch cases in `ServerSettingsModelTest` cover
a boolean-only update plan, toggle-back becoming clean, preservation through
other edits, read-only and revocation gates, promotion, stale correlation and
disconnect. `BudgetDelayConfigPersistenceTest` separately covers absent/null
defaults, retained false without a same-version rewrite, explicit save/reload,
unknown-data preservation and future-version protection for the switch
catalogued by
[server configuration](../../config/server.md#persisted-fields). These are headless
model and wire seams; the additional packet-facing `ServerCore` fixture below
is headless rather than a live administration session.

The Tracking partition additions have dedicated model, persistence, transaction,
widget and strict-wire assertions in the
[publication-stage input](build-checks.md#tracking-pool-publication-stage-run):

- `InventorySettingsPersistenceTest` checks absent/null defaults, all four
  independent boolean pairs, explicit sibling preservation, unchanged
  same-version bytes on load/no-op save, real save/reload, existing migration and
  future-version protection without a new version boundary.
- `InventoryConfigValuesTest`, `ServerInventoryDraftTest` and
  `ServerSettingsModelTest` cover strict boolean values, dirty/revert and
  boolean-only plans, unselected numeric/flag preservation and read-only,
  revocation, stale-response and disconnect gates.
- `ServerConfigVarNumbersTest`, `ServerInventoryConfigPacketsTest` and
  `ModIdentityTest` cover all three v5 IDs and independent literal-frame order,
  retained original mask bits, both new bits, all four flag combinations,
  malformed boolean bytes, truncation, trailing bytes and superseded v4 shapes.
- `ServerCoreTrackingConfigTest` enters the real packet-facing core with
  constructor-free server/player fixtures: a numeric-only update preserves both
  unselected flags through settings reconstruction and persistence, a face-only
  edit preserves the player flag, permission denial and malicious values/masks
  leave authority unchanged, and the snapshot round-trips the actual values.
- `InventoryPerformanceOptionTest` constructs both actual full-width native
  toggle factories, checks help presence, exactly-own-leaf dirty/revert and stale
  callbacks after revocation/disconnect. Catalog and eight-locale resource
  fixtures cover placement and Off/On, current authority and next-scan help;
  they do not establish assembled live tooltip delivery or translation quality.

## Server performance contract verification

The [UI](../../UI/settings-screen.md#server-performance-category),
[configuration](../../config/server.md#inventory-policy-object),
[revisioning](../../architecture/config/revisioning.md#same-version-shape-normalization)
and [wire](../../architecture/network/protocol.md#server-configuration-routes-server-config-request-v5-server-config-update-v5-server-config-snapshot-v5)
contracts have passing automated evidence in the
[combined-tree run](build-checks.md#automated-verification-results) for the earlier
numeric surface and in the
[publication-stage run](build-checks.md#tracking-pool-publication-stage-run) for
the v5/boolean additions:

- `InventoryPerformanceOptionTest` constructs the actual replacement-slider
  factory headlessly for every replaced leaf. Construction invokes no edit
  callback; keyboard edits select only the owning leaf and revert exactly.
  Additional cases exercise click/drag callback endpoints, exact one-stop arrow
  movement, focus-loss exit, read-only input and stale-widget input after
  permission revocation. The snapshot's extra integer stop survives rebuilds,
  model navigation and mode changes for clean revert; disconnect and a fresh
  accepted snapshot replace session extras, while a stale response does not.
  Unlimited-tail entry retains the finite value and its left neighbor selects
  the maximum finite value. Label construction distinguishes the two zero
  sentinels. These are native widget callback and model seams, not in-game input.
- `InventoryPerformanceScaleTest` exercises ordered piecewise joints,
  endpoints, linear stops, exact multiplier decimals, distinct zero and
  unlimited stops, and immutable extra integer stops that refuse illegal
  values.
- `InventoryConfigRangeTest` checks both bounds for every integer leaf: files
  clamp without a shape rewrite, strict administration rejects a mixed invalid
  update atomically, and endpoints and in-range non-step integers survive
  parsing, persisted load and detached settings conversion exactly.
- `PendingMemoryMigrationTest` exercises supported same-version and older
  scalar normalization, clamped finite persistence, missing/null defaulting,
  explicit unlimited round trips, and refusal to extend scalar acceptance to
  another limit field. Guarded normalization and failed-write retry preserve
  root and nested inventory unknown data, including nulls; external replacement
  reload and future-version save/reset protection are exercised. The unknown
  preservation evidence is specific to guarded normalization/writeback, not a
  general ordinary-save guarantee.
- `ServerInventoryConfigPacketsTest`, `ServerConfigVarNumbersTest` and
  `ModIdentityTest` exercise the version-five IDs, leaf round trips, strict
  field bounds, exact decimal quanta, canonical numbers and independent raw
  fixtures for the memory mode byte, retained finite value, Tracking partition
  booleans and fixed ordering. The earlier combined-tree run predates these
  boolean additions; their execution input is scoped above.
  Superseded scalar payloads, malformed modes, truncation and trailing bytes
  reject without publication or mutation. Source inspection confirms that all
  three loaders register through those packet constants; their builds compile
  that wiring, but these checks are not running-loader or old-peer fallback
  tests.
  Inventory `OFFER` value-bound evidence is owned by
  [inventory verification](inventory.md#resynchronization-and-budget-policy-verification).
- The server Performance localization fixtures passed across all eight bundled
  locales in the [combined-tree run](build-checks.md#automated-verification-results);
  the expanded settings-resource coverage below is not a claim of another passing
  run. Detailed coverage is inventoried under
  [Settings-screen navigation, catalog, layout and localization](#settings-screen-navigation-catalog-layout-and-localization).

Live verification limits remain under [Known automated gaps](#known-automated-gaps).

## Settings-screen navigation, catalog, layout and localization

The [configuration UI](../../UI/settings-screen.md) scope, category, and page
behavior has model, catalog, geometry, headless native-widget/list, and resource
seams rather than live screen evidence. The section/row catalog, responsive
layout, native widget/list, localized-title, and expanded settings-resource cases
below describe source-reviewed coverage rather than a recorded full-suite run:

- `SettingsNavigationModelTest` covers the initial client overview, per-scope
  overview selection, each category opening its own leaf page within its scope,
  the immutable seven-category client and five-category server order including
  the client Performance and server Performance categories, the client
  Performance category opening independently of the server Performance
  category, the server Presentation category opening without
  ordinary-server permission, the
  separate Server Presentation leaf, independent ordinary-server and
  presentation-policy view access, and independent per-page viewport and focus
  retention; it also covers rejection of a
  foreign-scope category without navigating, Back to the owning overview with a
  root that reports itself as not closable, per-page scroll and focus retention
  across back, scope switching and forced routing, clamping of negative scroll,
  routing an invalid server draft mask to the category that owns its first
  invalid field, retaining a shared `ServerSettingsModel` draft across
  navigation, and keeping a server leaf open when a permission revocation
  retains a viewable snapshot while forcing the scope overview without one;
- `SettingsCategoryCatalogTest` covers all 73 catalog entries exactly once in
  their approved categories, including the 71 value controls and two non-value
  entries. Independent expectations check section headings, section/row order,
  flattened order, the confirmed semantic pairs rather than unrelated packing,
  and singleton rows for ordinary server controls. It checks nonempty sections,
  defensive copies and immutable descriptor collections, and rejection of blank
  headings, empty sections/rows, oversized rows, and duplicate members in a
  pair. Every category has a distinct description key, including scope-distinct
  Performance keys. Inventory leaves remain server-only, and invalid-inventory
  routing skips the monitoring toggle and follows the owning control order.
  This is descriptor coverage, not native placement or focus dispatch; the
  [UI contract](../../UI/settings-screen.md#category-layout) owns the detailed
  category and grouping rules;
- `PresentationFieldOutcomeTest` covers the server policy role: the advertised
  manifest default, exact and wildcard allow rules winning over block rules,
  exact membership toggles mapping to single list operations, whitelist-only
  blocking unmatched fields while honoring allow rules, block rules beating the
  default while keeping unknown selectors visible, an unknown policy never
  fabricating membership or an empty authoritative view, and the server rule
  controls following the pending and permission gate; the former client-role
  coverage was removed with the client receive/display policy; it is a pure
  decision model and does not construct the settings screen or exercise widget
  state;
- `SettingsScreenLayoutTest` covers responsive content-column width and
  centering, the native maximum, scrollbar clearance, equal pair widths, and
  eligibility to share a row based on viewport width and both supplied label
  measurements. Footer action widths and positions clamp to the available
  viewport. Root/leaf list and footer boundaries are checked at compact,
  standard, large, and tiny sizes; zero-size layouts and zero/negative content
  or action widths avoid negative geometry. These are computed bounds and
  supplied-measurement seams, not actual font fitting, rendered rows, or row
  reachability;
- `SettingsRowWidgetsTest` positions real native `Button` instances as
  full-width singletons and equal non-overlapping pairs. It checks their native
  hit/focus rectangles move with the geometry, old positions and pair gaps stop
  being hits, positioning and focus acquisition invoke no callback, and the
  retained buttons still own their callbacks. Narrow-to-wide repositioning
  retains focus. Zero-width single/pair rows become hidden and inert to native
  hit, focus-path, mouse and keyboard probes, then recover without changing
  editable/read-only state. Pair eligibility measures both current native
  messages after label changes without activating either control. Measurement
  is deterministic, not a live Minecraft font; these cases do not construct a
  `SettingsScreen` or `OptionsList`, render controls, exercise `EditBox` dispatch,
  or change server permissions;
- `SettingsOptionsListTest` constructs the production native options list and
  exercises overview and editor row reflow from wide to narrow and back, keeping
  row-major order and the same controls without invoking callbacks. Editor
  reflow retains selector text, caret/selection, focus, scroll and list/entry
  dragging state without invoking the text responder. Pair eligibility is
  reevaluated from current captions and the original untrimmed selector label;
  label updates alone do not reflow during input. An unusable width or height
  clears native slider focus/dragging and fences focus paths, stale focus
  restoration, key press/release, character, mouse click/drag/release and scroll
  routes without edits. Restoring a usable viewport reopens native keyboard
  adjustment; resize preserves read-only flags, and an unchanged entry retains
  slider adjustment mode through reflow. The native slider fixture demonstrates
  why hiding a widget alone does not fence keyboard edits. These are headless
  native-list input and retained-editor-state cases with injected text
  measurements, not rendered font fitting, text-entry dispatch, footer traversal,
  permission transitions, networking or persistence;
- `SettingsEnumOptionTest` constructs native `CycleButton` controls from the
  screen's actual Player Info, Team Color and Entity Block Geometry option
  factories. It checks exactly one setting caption in the initial and updated
  name/value label and native narration, with mode-specific tooltip components
  following programmatic displayed-value changes while configuration values
  remain unchanged. The Player Info cases cover Disabled, Always and Compact;
  the Team Color and geometry cases cover every mode. This is headless
  formatter, caption/narration and tooltip-component coverage with an injected
  language, not click-driven config mutation, HOLD key-name help, live hover or
  tooltip rendering;
- `SettingsTitleWidgetTest` covers root-title identity and localized scope and
  breadcrumb formats retaining every styled semantic component as an argument.
  An injected language changes separators and argument order; scope-tab titles
  retain their independent scope keys across model navigation. Headless layout
  checks explicit widget bounds, wrapped title height and header reservation,
  short-title header minima, word/code-point wrapping and space-less CJK without
  losing text. Injected measurements and language fixtures are not live-font,
  bundled-locale rendering or complete-screen title evidence;
- `DeferredActionCoordinatorTest` covers the structural seam that runs a queued
  action after the native dispatch returns, waits for the outermost nested
  dispatch while keeping only the latest transition, and drops the transition
  when the dispatch or a nested dispatch fails;
- `SettingsScreenFocusKeyTest` covers the shared focus-key lookup helper before
  an options list exists, resolution of a focused list's focused child,
  retention of a directly focused widget's key without a list-child lookup,
  and the independent `isFocused` fallback when nothing is focused; it pins
  the regression that a null focused widget with a null list never calls the
  list-child supplier. This is headless coverage of the production focus-key
  helper, not a constructed `SettingsScreen`, live focus-list traversal, or
  in-game focus validation;
- `SettingsListFocusTest` covers the headless settings-list focus and reveal
  helper: a deferred-rebuild validation from the bottom explicitly reveals the
  first invalid row after the rebuild, ordinary mouse focus keeps its retained
  viewport, an already-visible validation does not reset the viewport, an
  unknown widget is a no-op, and a lower row or keyboard-origin reveal does not
  reset the top; it does not construct a native widget list or exercise mouse
  dispatch;
- `InventorySizeOptionTest` covers the size slider codec and keyboard seam:
  every ordered position round-trips through the slider codec, positions address
  physical sizes rather than wire quanta, the label uses the localized
  physical-KiB unit, a varying maximum is respected across rebuilds, read-only
  sliders ignore keyboard adjustment, held arrow keys keep adjusting through a
  slider replacement while an ordinary focus loss clears the adjustment mode,
  and real-width arrow steps accumulate to the next legal position across a
  rebuild while fraction-only moves never call the model; it is a headless
  helper seam and does not render a screen or exercise mouse drag or live widget
  focus;
- `OptionUtilsDecimalTest` covers the decimal option codec's confirmed range
  and grid: every confirmed grid step round-trips exactly through encode and
  parse without floating-point drift, and zero or an out-of-range value is
  rejected. Its inventory-fraction fixtures construct the production decimal
  options and exercise their actual slider value-set mapping at both endpoints
  and every adjustment stop, with codec round trips and out-of-range rejection;
  supplied off-grid values retain their codec precision without changing the
  model. These are source-reviewed headless option and codec fixtures, not
  recorded test runs, mouse dragging or keyboard adjustment of a live slider;
- `ClientConfigLocalizationTest` covers the eight bundled locale files and the
  settings, all category entrances, server-status, entity-block-mode, and
  target-gone resource keys, required format placeholders, category entrance
  ellipses, and the exact local feedback prefix. It checks the active client-file
  action's restart-required help, current transparent-selection label and
  tooltip with retired selection resources absent, distinct localized title
  segments, independent wheel/target opacity labels, and the complete
  spatial-selector resource set with numeric/boolean and unit placeholder
  contracts. Retired timeout and obsolete inventory-sensitivity resources stay
  excluded. These are resource expectations, not a guarantee of translation
  quality. The fixture does not assemble or render screen labels;
- `SettingsLocalizationCoverageTest` derives the 71 value controls' required
  nonblank labels and generic help from the production catalog/key builder. It
  checks all category descriptions and section headings, scope/navigation/action
  and presentation-editor help, used enum/state value labels, units, and visible
  format resources across all eight bundled locales. Eleven dynamic enum-mode
  tooltips have explicit signatures, including two string arguments for HOLD
  help. Label, unit, name/value, truncation and title templates retain their
  expected argument slots and types; argument-bearing templates are validated
  with a formatter. Zero-argument prose accepts bare or escaped literal percent
  signs but rejects consuming placeholders, with headless translatable-component
  fixtures distinguishing literal text from strict argument templates. The
  locale reader rejects duplicate JSON keys. This is resource/format coverage,
  not semantic translation review, complete tooltip assembly, actual font fit,
  hover/focus delivery or tooltip wrapping;
- `ConfigEnumLocalizationTest` covers locale-independent resource-key generation
  under ROOT, US and Turkish (`tr-TR`) JVM defaults. Every `PlayerInfoMode`,
  `TeamColorMode` and `ChannelMode` value label, and every player/team dynamic
  mode-tooltip key, must resolve to a nonblank string in the actual English and
  Turkish resources; the disabled player-info tooltip also retains its canonical
  key under every tested default. The fixture locks the shared locale resource
  and restores the default, display and format locales in a `finally` block.
  This is source-reviewed key/resource coverage with no passing execution
  recorded here, not UI hover, assembled tooltip or rendering evidence;
- `InventorySettingsLocalizationTest` covers the eight bundled locale files,
  the inventory settings label/tooltip key alignment and placeholder rules, the
  performance category entrance, the inventory group headings, the shared
  name/value label,
  numeric tooltip lines with localized decrease/increase prefixes and separate
  four-line boolean help for the Tracking partitions,
  distinct zero labels, removal of obsolete control resources, and the two size
  sliders' physical-KiB labels and adjustment notice; it does not render or
  assemble screen labels or establish translation semantic accuracy or live
  tooltip readability;
- `BudgetDelayLocalizationTest` covers the monitoring setting, subgroup,
  administrator-message and module keys across the bundled locales, their
  nonblank values and placeholder signatures, and the aggregate-status key's
  compatibility alias, plus the toggle tooltip's three-line nonnumeric structure;
  it does not certify translation semantic accuracy, deliver system messages,
  switch a live locale or render settings controls;
- `SpatialInventoryLocalizationTest` covers the eight bundled locale files, the
  renderer-referenced inventory keys, every inventory status label as a
  non-formatted literal, and inventory key-set alignment across locales; it
  does not render or assemble screen labels;
- `SpatialSelectorLocalizationTest` covers the eight bundled locale files and
  the native selector facade's real menu-label keys: every non-blank label the
  headless facade publishes exists and is non-blank in every locale and carries
  no format placeholder, the content bridge's Create properties and Stress
  group label keys exist and are non-blank in every locale, code-defined
  toggle and target-type identities are published as keys, and the spatial key
  set stays aligned across locales; it
  does not render or assemble screen labels;
- `SpatialSelectorSettingsLocalizationTest` covers the eight bundled locale
  files for the gesture shortcut, minimum wait, content-intent setting and root
  marker-removal label: gesture labels and tooltips are nonblank, the minimum
  wait label has exactly one value placeholder, both gesture tooltips state
  next-hold application, and the confirmed Chinese gesture-toggle label is
  pinned; for content intent and marker removal, the
  label and tooltip exist non-blank without placeholders and differ, every
  locale's tooltip states next-hold application, only the expected two
  content-intent keys exist with no stale variants, and the stable root cancel
  key keeps its identity while every locale states marker removal, with the
  confirmed Chinese content-intent label and the English removal word pinned
  exactly; it does not render or assemble screen labels; and
- `PresentationSettingsLocalizationTest` covers the eight bundled locale files,
  every presentation key present, non-blank, and with an identical key set
  across them, the approved English presentation labels, key placeholders,
  localized names and descriptions for every builtin, Create, and
  inventory-manifest field in every locale, bundled translations for the
  recognized namespace headings, the read-only server status wording, and a
  property request phrase separate from the whole-marker Ping Type phrase in
  every bundled locale; it does not render or assemble screen labels.

The production screen routes native widget input through the deferred-action
coordinator. The native-list cases exercise headless list dispatch and reflow;
the combined coverage does not establish a fully assembled settings screen's
render/input lifecycle or live game focus traversal.

## Known automated gaps

- native-list reflow/input and enum caption/narration/tooltip components have
  headless coverage above, but the fully assembled settings screen and game
  render/input lifecycle remain unexercised. Live GUI-scale/window resizing,
  footer traversal, text-entry dispatch, actual font-driven pair stacking,
  heading clipping/help, hover/keyboard-focus tooltip delivery and wrapping,
  translation accuracy, and multiplayer read-only permission transitions remain
  pending in the
  [manual matrix](manual-integration.md#settings-and-server-performance);
- the server Performance factory, model, handler, codec and localization seams
  [passed the combined-tree run](#server-performance-contract-verification), but
  do not establish a complete live settings screen's mouse dispatch, resizing,
  assembled localized tooltip layout, translation quality or remote persistence
  session; native widget callbacks are exercised headlessly, not by gameplay.
  Live scenarios remain in the
  [manual matrix](manual-integration.md#settings-and-server-performance);
- the client inventory-cap sliders' live input, assembled localized labels and
  tooltips, and screen-close persistence are not established by their option
  mapping, codec, catalog, resource or filesystem-handler fixtures; pending
  native screen and panel checks remain in the
  [manual matrix](manual-integration.md#pending-manual-and-integration-matrix);
- the gesture controls' assembled OFF/disabled presentation and live refresh
  when compatibility is toggled are not exercised by a constructed settings
  screen; model clearing, catalog placement and resource presence do not prove
  native widget state;
- a constructed settings screen's live disabled state, complete localized
  zero-angle off label and inventory stay-open control input are not covered by
  the model, codec, catalog or resource fixtures; pending native screen checks
  remain in the
  [manual matrix](manual-integration.md#pending-manual-and-integration-matrix);
- inventory administration and spatial-selector configuration have model,
  persistence, strict wire, draft, catalog and focus-helper seams; live
  administration, screen-level input/focus, permission changes, persistence,
  scheduler consumption and selector integration remain unexercised by them;
- live server-settings UI, permission, read-only viewing below the required
  level, request/response, update, and persistence behavior;
- the assembled settings-screen label for the local marker display duration
  option: `ClientConfigLocalizationTest` and `SettingsLocalizationCoverageTest`
  check resource keys, values and placeholder contracts, and
  `SettingsScreenLayoutTest` checks geometry, but none assembles that control's
  complete localized label for the Follow server sentinel or an explicit
  duration; the label form is owned by
  [configuration UI](../../UI/settings-screen.md#marker-display-duration-option);
