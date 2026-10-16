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

## Inventory and spatial-selector configuration

`InventorySettingsTest` covers the authoritative inventory budget model:
explicit unlimited caps keep their finite value and use the caller-owned finite
guard, finite caps clamp to positive instead of a disable sentinel, confirmed
finite boundaries clamp, zero heartbeat disables only the periodic cadence, the
two multiplier grids normalize piecewise values, the five byte multipliers
stay independent and control only their own scope, the snapshot fragment and
per-client period scopes stay equal, unlimited byte multipliers stay finite,
the step helpers cross boundaries and saturate, pending-memory steps follow the
piecewise grid, and independent defaults do not alias. The two finite size
leaves normalize on their own exact quarter-multiplier grids and repair a
conflicting pair downward with frame priority and the reserved metadata
headroom while never raising the frame, and they remain independent of the five
send-byte multipliers; `InventorySizeScaleTest` covers the ordered legal
positions and their exact byte and quanta conversion, half-up normalization,
pair legality, frame-priority reconcile and ordered position stepping.
`SpatialSelectorSettingsTest` covers the spatial-selector settings snapshot and
bounds: a missing, null or fresh-file target-glide setting initializes the
implementation's non-neutral configured default, while the neutral multiplier
remains an explicit case that preserves the intrinsic list-glide baseline.
Explicit factors, including a valid off-grid value, stay literal rather than
snapping to the step grid, out-of-range factors clamp to the confirmed range,
and each factor drives its own relative row threshold through validation,
round-trip and a frozen gesture snapshot; a live preference edit does not
change the already-frozen gesture, and a later null reset returns to the
configured default rather than the neutral factor. The updated glide
calibration exercises the neutral factor against the updated intrinsic baseline
and explicit faster and slower factors against their relative row thresholds.
This records the calibration coverage, not a numeric tuning catalogue. An
explicit saved factor remains literal in the tested version upgrade rather than
being mass-rescaled.
Hover, deadzone, stroke, dwell, root-distance, and submenu-radius-scale
clamping saturate on both sides, a missing, explicit-null or fresh-file center
deadzone initializes the current confirmed default while every valid saved
deadzone value, including the previous default and both bounds, is retained
literally without rescaling and a held snapshot keeps its frozen deadzone, the
distance and timing clamps stay exact over their whole valid interval, the
spatial distance defaults
and bounds preserve their half-scale relationship, null and out-of-range nested
values validate without throwing, the opt-in Back-hover default stays disabled,
a missing or explicit-null submenu radius scale gains the confirmed default and
the value clamps and freezes into the snapshot, the precise capture period
clamps to its persisted range, defaults to its minimum, and freezes into the
snapshot, the content-intent choice defaults disabled for fresh, missing and
null values, an explicit choice is retained and freezes into the held snapshot
while the live setter supplies the next snapshot, the canonical snapshot
constructor carries the choice while every previous constructor form defaults
it, the previous snapshot constructor forms remain compatible and default
the submenu radius scale and precise capture period, raw-JSON validation and a
frozen snapshot clamp every value without resetting boolean choices, a frozen
session snapshot never follows later config edits, root distance and gesture
preferences stay independent in both mutation directions, and fresh defaults do
not alias another client's mutable preferences. `SpatialSelectorMigrationTest`
also covers missing, null and explicit target-glide settings retaining their
model semantics without a same-version load rewrite: a fresh file persists the
configured non-neutral default, a same-version load and save keep an explicit
factor unchanged, and a version-boundary migration preserves an explicit
multiplier without rescaling. Same-version deadzone defaults follow the same
no-rewrite rule: a fresh file persists the current default, missing, null and
explicit saved deadzone values are retained without rescaling, an untouched
same-version save leaves the bytes unchanged, and a reload keeps the value. It
covers the release-boundary migrations that remove only obsolete wheel keys
without
injecting defaults: the exact boundaries and pre-target versions retire the old
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
[wire](../../architecture/network/protocol.md#server-configuration-routes-server-config-request-v3-server-config-update-v3-server-config-snapshot-v3)
owners retain the exact field selection and grammar. These model and packet seams
do not establish the live settings UI, permission changes over a connection,
actual packet exchange, or persistence behavior in a running client/server
session.

`InventoryConfigValuesTest` covers the twenty-one-leaf admin inventory value
model: each leaf's merge preserves every unselected current value and stays
independent of the rate-limit, channel, sync and monitoring fields; the two
finite size leaves are exact scalar multipliers with no unlimited mode, a
selected pair is accepted only when both leaves are present and legal, and an
invalid merged pair rejects atomically without touching unselected leaves; an invalid leaf
rejects the whole mixed update and unsafe snapshots fail closed without
defaulting; each leaf converts to and from its persisted member without
aliasing; missing, null, off-grid, and unexpected-unlimited values are unsafe
without substitution; and unsafe current or missing updates never report an
applied transaction. `ServerInventoryConfigPacketsTest` covers the strict v3
administration wire: the v3 request, update, and snapshot routes; every leaf
and explicit unlimited finite value round-tripping; the two finite size leaves
carrying one exact quarter-multiplier quanta value with no unlimited flag and
every legal grid value exact on the wire; truncation, old-prefix, and trailing
data rejecting without throwing; an invalid encoded leaf or malformed mode
rejecting the whole packet; and off-grid, zero and overflowing quanta, unknown
masks, missing inventory, nonpositive caps, and out-of-range scalars failing
closed without clamping. `ServerConfigVarNumbersTest`
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
model and wire seams: no `ServerCore` or live administration path exercises
them.

## Settings-screen navigation, catalog, layout and localization

The [configuration UI](../../UI/settings-screen.md) scope, category, and page
behavior has model, catalog, geometry, and resource seams rather than live
screen evidence:

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
- `SettingsCategoryCatalogTest` covers exactly-once placement of every existing
  control in the approved category order and immutable per-category lists,
  including the trail controls in Wheel Appearance, the content-intent control
  in the client Input Interaction spatial group, the client scope without a
  presentation category, the client
  Performance category exposing exactly the precise capture period control, the
  server Presentation category, and the server Performance category exposing
  the monitoring control first and every inventory leaf exactly once and only in
  the server scope; invalid-inventory focus routing skips the monitoring control,
  and the two size leaves follow pending memory in the shared inventory budgets
  group; it does not cover full-width layout flags or actual widget placement;
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
- `SettingsScreenLayoutTest` covers computed root and leaf header, footer, list
  viewport, and button geometry at representative compact, standard, and large
  GUI sizes and clamped tiny-size viewports without negative space; it does not
  establish row reachability or actual widget placement;
- `DeferredActionCoordinatorTest` covers the structural seam that runs a queued
  action after the native dispatch returns, waits for the outermost nested
  dispatch while keeping only the latest transition, and drops the transition
  when the dispatch or a nested dispatch fails; and
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
  rejected;
- `ClientConfigLocalizationTest` covers the eight bundled locale files and the
  settings, category-navigation, server-status, entity-block-mode, and
  target-gone resource keys, their required format placeholders, the category
  entrance ellipsis, the exact local feedback prefix, the restart-required
  external-list tooltips, the independent wheel and target opacity labels, the
  retired wheel timeout key's removal, and the complete spatial-selector
  preference key set with its wheel-options group heading, including the
  precise capture period, trail controls and tooltips, the content-intent label
  and tooltip, and numeric/boolean
  placeholder rules; point and percent unit placeholders are also checked for
  every bundled locale. It checks required key presence per locale rather than a
  full-translation guarantee, and does not
  render or assemble screen labels;
- `InventorySettingsLocalizationTest` covers the eight bundled locale files,
  the inventory settings label and tooltip key set, the performance category
  entrance, the inventory group headings, the shared inventory range
  placeholder, and the two size sliders' physical-KiB labels and adjustment
  notice with their placeholder rules; it does not render or assemble screen
  labels;
- `BudgetDelayLocalizationTest` covers the monitoring setting, subgroup,
  administrator-message and module keys across the bundled locales, their
  nonblank values and placeholder signatures, and the aggregate-status key's
  compatibility alias; it does not deliver system messages, switch a live locale
  or render settings controls;
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
  files for the content-intent setting and the root marker-removal label: the
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
coordinator, but these tests exercise the model, catalog, geometry, and resource
seams rather than native widget containers, mouse dispatch, or live focus lists.

## Known automated gaps

- inventory administration and spatial-selector configuration have model,
  persistence, strict wire, draft, catalog and focus-helper seams; live
  administration, native widget input/focus, permission changes, persistence,
  scheduler consumption and selector integration remain unexercised by them;
- live server-settings UI, permission, read-only viewing below the required
  level, request/response, update, and persistence behavior;
- the assembled settings-screen label for the local marker display duration
  option: `ClientConfigLocalizationTest` checks resource keys, values, and the
  `%s` placeholder, and `SettingsScreenLayoutTest` checks layout, but neither
  checks the complete localized `<setting name>: <value>` label for the Follow
  server sentinel or an explicit duration; the label form is owned by
  [configuration UI](../../UI/settings-screen.md#marker-display-duration-option);
