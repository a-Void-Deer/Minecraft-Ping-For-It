# Configuration and settings UI verification

[Verification index and evidence rules](README.md) ·
[Pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)

## Configuration recovery and selection grammar

The suite covers block whitelist grammar and evaluation, invalid client-config
recovery, config-version markers and precedence, migration, client/server
recovery, and future-version preservation seams.

`ConfigHandlerResetTest` covers resetting in-memory values to defaults, preserving
the bytes of a protected future-version file, independent client selector
objects and remembered-channel maps, stale references unable to affect a later
save, post-reset edits surviving save and reload, and detached server inventory
and presentation state. These reset-isolation cases are source-reviewed
automated coverage, with no passing run recorded here; they do not exercise live
settings editors.

`ConfigDefaultsIsolationTest` covers active server-default initialization in a
fresh isolated classloader with client and presentation default groups, platform
services, configuration models and handlers and selected optional integrations
unavailable. Project helpers are child-loaded; direct and transitive platform
denial is probed, and the initialized server channel-mode enum remains usable
without pinning its selected default. This is source-reviewed
isolated-initialization coverage, with no passing run recorded here; it does not
establish game startup, loader-specific dedicated-server initialization or
multiplayer behavior.

`ConfigHandlerDiagnosticsTest` covers filesystem-backed load-validation, backup,
save and recovery-reset failures with actual recovery-result reporting: server
invalid input distinguishes a written default file from defaults retained in
memory, and a hash-equal recovery save remains a no-op preserving the invalid
original bytes without invoking `onUpdate`. Warning reports retain long message
tails and root causes within the diagnostic bound at higher disclosure, without
an attached throwable; `NORMAL` hides path, value and failure-class detail. The
generated recovery reason remains a separate safe one-line comment. Failed
backup preserves the original bytes and blocks save and reset writes while
leaving defaults in memory; failed initial save creates no file, and failed
recovery-reset preserves the original source and blocks subsequent saves. These
are handler and injected-failure seams, not a live settings screen or
client/server persistence session.

`ConfigHandlerVersionTest` checks a successful older-config migration remaining
WARN-free at `NORMAL` and a failed migration write deferring version and path
detail until admitted higher disclosure while the original bytes and loaded
values remain usable. `ConfigPresentationMigrationTest` covers fail-closed
policy normalization diagnostics: a malformed global or individual target-type
rule emits one `NORMAL` warning with finite scope and affected count but no raw
value, while missing target-type defaults and an ordinary legacy flat-shape copy
stay quiet; a durable normalized reload does not repeat the malformed warning.

`TransparentBlockSelectionMigrationTest` contains filesystem-backed fixtures for
the transparent-toggle transformation at the existing client beta1 boundary:
legacy decoded values retain their actual raycast effect, a present new key takes
precedence, malformed authoritative values follow parser recovery and backup
protection, and unrelated root and nested data survive. A guarded writeback
fixture retains explicit unknown nulls at the root, inside objects and arrays,
and under spatial-selector preferences through failure, retry and reload,
without materializing an omitted nullable model field. Pre-boundary handler cases
check legacy-schema projection for fresh files, ordinary saves, resets and
guarded writeback, preservation of an already-present unknown future key, and a
later upgrade retaining the selected effect. Same-version fixtures keep the
obsolete-key load and untouched save as no-ops while a real serialization removes
the retired key; failed writes retain the usable policy, and retry observes
changed-source and future-version guards. These are source-reviewed model and
persistence fixtures, not a recorded test run, native key-binding load or
gameplay evidence. The transformation and recovery contracts remain owned by
[configuration revisioning](../../architecture/config/revisioning.md#ordered-migration-and-writeback).

## Inventory and spatial-selector configuration

`InventorySettingsTest` covers the authoritative inventory budget model:
unlimited caps keeping a finite value and caller-owned guard, finite caps
clamping positive, confirmed boundaries, zero heartbeat disabling only periodic
cadence, both multiplier grids normalizing piecewise, byte budgets scaling their
own scope and queues following their global allowances, the five byte
multipliers staying independent, the snapshot-fragment and per-client period
scopes staying equal, unlimited byte multipliers staying finite, step helpers
crossing boundaries and saturating, pending-memory steps following piecewise
stops, and independent defaults. Its isolation fixture exercises all nine caps
and five send-byte multipliers within and across fresh settings, later
construction not inheriting mutations, and null nested objects restoring
non-aliased fresh-equivalent values. These isolation cases are source-reviewed
automated coverage, with no passing run recorded here. The two finite size
leaves have exact byte/quanta conversion and repair a conflicting pair downward
with frame priority and reserved metadata headroom without raising the frame;
they remain independent of the five send-byte multipliers.
`InventorySizeScaleTest` covers ordered legal positions, exact byte and quanta
conversion, half-up normalization, pair legality, frame-priority reconcile and
ordered stepping. Performance controls are covered under
[Server performance contract verification](#server-performance-contract-verification).

`ByteMultiplierDefaultsTest` covers owning-field fallback substitution with
distinct non-unit values on both grids: missing, empty and null values restore
the supplied fallback, explicit values win, validation is idempotent and retains
unlimited mode, byte calculation leaves missing values unmaterialized and
explicit values unnormalized, unlimited mode returns the caller's guard before
consulting the grid, and compatibility APIs keep their finite one-times meaning.
`InventorySettingsPersistenceTest` exercises all five send-byte multiplier
fields through filesystem-backed load: omitted or null objects, empty objects,
missing or null values, and unlimited mode without a value restore each owning
field's default without changing explicit siblings; same-version load and
untouched save preserve bytes; restored values materialize on a real save and
survive reload; explicit zero or negative values clamp to the grid minimum while
siblings retain their values. These are source-reviewed automated coverage with
no passing run recorded here; they do not exercise scheduler consumption or live
administration.

`SpatialSelectorSettingsTest` covers the selector settings snapshot and bounds:
missing, null or fresh deadzone defaults; retained valid saved values including
both bounds; frozen held snapshots; the opt-in Back-hover default; submenu radius
scale default/clamp/freeze; precise capture period clamp and freeze; and
content-intent defaults. Its clamps preserve the valid interval and saturate on
both sides, every supported stroke can leave every supported center deadzone,
null and out-of-range nested values validate without throwing, and raw-JSON
validation and frozen snapshots clamp without resetting boolean choices. The
snapshot constructor keeps explicit choices, carries them into the next
snapshot, retains previous constructor compatibility, and keeps frozen sessions
independent of later edits and root distance or gesture preferences. Fresh
defaults do not alias another client's mutable preferences.

`SpatialSelectorMigrationTest` contains filesystem-backed cases for the obsolete
preference catalogued by [client configuration](../../config/client.md#obsolete-keys),
same-version deadzone defaults without a rewrite, release-boundary wheel-key
retirement, target-version upgrades preserving unknown nested selector data, and
same-version absent, partial and null selectors gaining model defaults without a
load rewrite. Explicit preferences round-trip independently of appearance,
including the precise capture period and content intent; reset restores the
disabled default; the future-version guard keeps all bytes and refuses save and
reset; a failed write retains usable in-memory preferences and retry discards the
stale source before migration. Inventory persistence cases cover additive
defaults without rewriting a current-version file, older-version migration, all
inventory cap leaves including nested preview and tracking caps, unlimited and
missing-member defaults, size scalar normalization and frame priority, the
future-version guard, and the client spatial-selector keys including submenu
radius, precise period, trail length and opacity. These are model and persistence
seams only: no server-administration path, remote change route, settings-widget
interaction, scheduler consumption or full client selector runtime is exercised.
The preference contract remains owned by
[client configuration](../../config/client.md#spatial-selector-interaction).

The inventory-panel fraction fixtures in `SpatialSelectorSettingsTest` and
`SpatialSelectorMigrationTest` cover missing or null members, safe snapshots,
independent clamping, normalized decimal precision, intact unrelated
preferences, held-snapshot freeze, previous constructor compatibility,
same-version byte preservation, explicit grid and off-grid round trips, in-memory
legalization without a load rewrite, older-source defaults, failed-write retry
and future-version protection. These are source-reviewed model and persistence
fixtures, not recorded runs, live settings controls or selector-layout evidence.

`GestureSkipHoldConfigTest` covers fresh, missing and null shortcut preferences
staying opt-in disabled, missing or null waits and older constructor waits
matching a fresh model, minimum-wait clamping through setters, validation and
snapshot construction, integer extremes saturating to the boundary with both
endpoints and an interior value retained, held snapshots keeping their
preference and wait, compatibility clearing an enabled preference without later
restoring it, preventing re-enablement while active, and retaining the configured
wait. Filesystem-backed cases cover explicit round trip, missing members and an
untouched save preserving same-version bytes, conflicting booleans persistently
reconciled through guarded normalization while unknown nested data survives,
reload persistence, pending failed normalization, external future-version edits
not overwritten, and future-version or invalid-file protections declining edits.
These are model and handler seams, not live widget interaction; runtime
eligibility is under
[interaction verification](interaction.md#gesture-shortcut-opening).

## Double-click Danger and inventory stay-open configuration

The two press preferences and the stay-open choice have source-reviewed model,
handler, codec, catalog and resource coverage. None of it is a recorded run.

`DoubleClickDangerConfigTest` covers explicit window and angle values independent
of the cancel half-cone in both mutation directions; missing and null JSON values
using model defaults and out-of-range values clamping; compatibility clearing the
angle and rejecting non-zero until disabled; validation and update enforcing the
same zero; NaN recovering to the default while infinity clamps; string NaN
loading as a finite default; filesystem-backed same-version normalization of
decodable string numbers, `NaN` and `Infinity` under compatibility with guarded
writeback, retained unknown data and a materialized zero for a missing or null
angle; no rewrite for explicit zero or disabled compatibility; ordinary round
trip; failed-write retry; changed-source reload; and typed invalid-file recovery
with a broken-file backup, whose failed backup installs protection that declines
angle writes. `ClientConfigBoundsTest` exercises direct window and angle clamps,
including NaN and positive infinity.

`SpatialSelectorSettingsTest` covers the inventory stay-open choice defaulting
closed, missing and null members staying closed, the live setter affecting the
next snapshot, and a held snapshot retaining its choice.
`ClientConfigLocalizationTest` covers the eight bundled locales' labels and
tooltips, placeholder signatures, the shared off value, and the pinned Chinese
and English off strings. `SettingsCategoryCatalogTest` places the window, angle
and stay-open controls once each in the client input category.
`OptionUtilsDecimalTest` covers every half-degree stop, out-of-range rejection,
and the complete `<setting name>: <off value>` label at zero. These are model,
handler, codec, catalog and resource seams, not a recorded run, live widget
input, or a constructed or rendered settings screen; screen presentation
remains owned by
[configuration UI](../../UI/settings-screen.md#input-interaction-controls).

## Server-settings snapshots and updates

`ServerSettingsModelTest` covers request correlation, stale responses, denial,
draft state and update planning. It also covers a safe non-editable snapshot
accepted and viewable for a viewer below the required level without enabling
edits or an update plan, unsafe or uncorrelated snapshots never becoming
viewable, a read-only view retained through permission revocation, an editable
session restored for a retained editable snapshot, a fresh request required after
promotion to editor, an authoritative denial keeping the snapshot viewable but
not editable, the per-field invalid numeric draft mask and its repair, clearing
on permission revocation and disconnect, an explicit clean mark preserving draft
text, the shared once-per-session request not replacing a loaded or in-flight
snapshot, denial lock with the false-to-true permission retry transition, and
connection-scoped disconnect reset. `ServerConfigUpdateTest`,
`ServerConfigUpdateServiceTest` and the focused server-config packet tests cover
field-masked partial merge and codec/handler seams; monitoring-switch cases cover
selected true/false, preservation of unselected false, immutable snapshots,
compatible constructor defaults, permission denial and atomic rejection beside
an invalid inventory size pair. Raw packet fixtures cover the final strict
boolean, shorter superseded shapes, truncation, invalid boolean bytes, trailing
bytes and unknown mask bits. The
[transaction](../../architecture/config/changing-server-config.md) and
[wire](../../architecture/network/protocol.md#server-configuration-routes-server-config-request-v5-server-config-update-v5-server-config-snapshot-v5)
owners retain exact field selection and grammar. These are model and packet
seams, not the live settings UI, permission changes over a connection, actual
packet exchange or running persistence.

`InventoryConfigValuesTest` covers the twenty-three-leaf admin inventory value
model: each leaf's merge and unselected-value preservation, the two finite size
leaves as exact scalar multipliers, pair acceptance only when both leaves are
present and legal, atomic rejection of an invalid merged pair, unsafe snapshot
failure without defaulting, exact persisted-member conversion, and rejection of
missing, null, out-of-range, off-grid and unsupported-unlimited values without
reporting an applied transaction. `ServerInventoryConfigPacketsTest` covers the
strict administration codec: every leaf and explicit unlimited finite value,
the two size leaves' exact quarter-multiplier quanta with no unlimited flag, all
legal grid values, truncation, old-prefix and trailing rejection, invalid leaves
or modes rejecting the whole packet, and zero, overflowing quanta, unknown
masks, missing inventory, nonpositive caps and out-of-range scalars failing
closed. `ServerConfigVarNumbersTest` covers canonical integer and positive
correlation boundaries, truncation, overflow, signed-width and noncanonical
encodings, malformed numbers failing closed before mutation or publication, and
legal maximum values retaining their meaning. `ServerInventoryDraftTest` covers
per-leaf dirty, revert and independent update-plan state; malformed leaves not
erasing other drafts or permitting partial updates; read-only visible values;
unlimited leaves retaining finite text; decimal parsing and stepped boundaries;
session lifecycle across navigation, revocation and disconnect; unsafe or stale
responses not installing a view or overwriting drafts; and the size pair's
ordered positions, frame/display reconciliation, dirty marks, adjustment notice,
malformed text blocking the plan, notice clearing and read-only behavior.
`BudgetDelayConfigPersistenceTest` covers the monitoring switch's defaults,
same-version bytes, explicit save/reload, unknown-data preservation and
future-version protection. The refreshed version-five memory, revised-bound and
literal non-step integer fixtures are inventoried
[below](#server-performance-contract-verification). These are headless model and
wire seams; the additional packet-facing `ServerCore` fixture is headless rather
than a live administration session.

The Tracking partition additions have dedicated model, persistence, transaction,
widget and strict-wire assertions: `InventorySettingsPersistenceTest` checks
absent or null defaults, all four independent boolean pairs, explicit sibling
preservation, unchanged same-version bytes, real save/reload and existing
migration with future-version protection. `InventoryConfigValuesTest`,
`ServerInventoryDraftTest` and `ServerSettingsModelTest` cover strict boolean
values, dirty/revert and boolean-only plans, toggle-back cleanliness,
preservation through other edits and promotion, unselected numeric or flag
preservation, and read-only, revocation, stale-response and disconnect gates.
`ServerConfigVarNumbersTest`, `ServerInventoryConfigPacketsTest` and
`ModIdentityTest` cover all three v5 IDs, independent literal-frame order,
retained mask bits, both new bits, all four flag combinations, malformed boolean
bytes, truncation, trailing bytes and superseded v4 shapes.
`ServerCoreTrackingConfigTest` enters the real packet-facing core with
constructor-free server/player fixtures: a numeric-only update preserves both
unselected flags through settings reconstruction and persistence, a face-only
edit preserves the player flag, permission denial and malicious values or masks
leave authority unchanged, and the snapshot round-trips actual values. A
separate stage-report case keeps permission-denied and invalid-update decisions
silent at `NORMAL`, expands their mask, trusted-permission and profile-only facts
only at `DEEP`, and records applied-in-memory followed by
persistence-attempted without claiming persistence or acknowledgement; a
selected logging profile is published before the attempt. No saved execution
acceptance record yet covers these added logging and diagnostic coverage items;
the [shared diagnostics evidence note](integrations.md#shared-exception-diagnostics)
owns the cross-domain gap.

Each loader's `ServerConfigTransportDiagnosticsTest` drives the production send
seam for request, update and snapshot packets: route unavailability, a missing
connection where that loader distinguishes it, and a dispatched send are
DEBUG-only decisions with the known packet kind. The seam adds no extra probe,
serialization, retry or return path; a non-config packet emits nothing. Fabric
and NeoForge use existing can-send and send seams, and Forge exercises the
existing event-channel path with a recording channel. These are headless loader
send seams, not live connection or multiplayer transport evidence.

## Logging-profile configuration and controls

`LoggingProfileConfigTest` covers both client and server configuration types:
fresh, missing, explicit-null and unknown profiles resolve to `NORMAL` before
and after validation; explicit `NORMAL`, `DEEP` and `DEBUG` are retained; an
untouched same-version file preserves its bytes across load and no-op save for
absent and explicit values; a profile-only edit participates in equality, hash,
save and reload; reset returns to `NORMAL` without a stale model reference
affecting a later save; older-version migration materializes a missing default
and preserves explicit profiles and unknown data; and future-version save/reset
protection preserves the original bytes.

`ServerConfigLoggingProfileTest` covers the independent remote leaf: selecting a
profile changes only that leaf, denied authority preserves the authoritative
snapshot, an unselected profile survives other edits, a null profile is invalid
even when unselected, an unsafe snapshot rejects the update, and a conflicting
inventory size pair rejects the selected profile atomically. It also covers
snapshot/update conversion, permission-copy propagation, `NORMAL` defaults from
source-compatible constructors, mask bit 29, the thirty-bit complete mask and
the inventory mask excluding the logging bit.

`ServerSettingsLoggingProfileTest` covers a draft initialized from the
authoritative profile, its own dirty bit and update plan, read-only display of an
authoritative `DEBUG` value without editing or planning, preservation through
other edits, disconnect reset and replacement by a fresh snapshot.
`LoggingProfileControlTest` covers the two-value cycle when Alt is released, the
three-value cycle while Alt is held, `DEBUG` exiting to `NORMAL` on the next
release-state activation, keyboard and mouse sharing the same live candidate, no
separate Shift path, wheel input leaving the value unchanged, an inactive
read-only server row refusing activation, and a server draft selection not
publishing the runtime profile.

`LoggingProfileScreenTest` covers the per-activation Alt read and current client
handler, including a replaced handler read and written through the same mounted
row; dropping Alt after a `DEBUG` selection; the client row publishing
immediately while the server row edits only the draft; navigation and resize not
carrying the temporary Alt candidate; nonblank native help on both mounted rows
with their labels and no `DEBUG`, Alt, Shift or unlock hint; and a read-only
server viewer seeing `DEBUG` without editing. `SettingsCategoryCatalogTest`
verifies the controls' Debug-category placement and approved catalogue totals.
`SettingsLocalizationCoverageTest` requires localized labels and tooltips for
both logging rows in all eight bundled locales. `ServerConfigVarNumbersTest`
covers profile tags `0`, `1` and `2`, mask bit 29, preservation when unselected,
and rejection of missing, unknown, noncanonical, truncated and trailing forms
even when unselected.

The focused result and full combined execution for the accepted Alt-control input
are recorded under
[Alt logging profile controls](build-checks.md#alt-logging-profile-controls).
None of these fixtures establishes live native mouse or keyboard delivery,
rendered tooltip layout, a live client/server profile switch or remote
persistence.

## Performance monitor coverage

The
[performance monitor controls](../../UI/settings-screen.md#performance-monitor-controls)
have catalog, navigation, model, mounted-screen and localization coverage, not a
live-screen or multiplayer acceptance record. Production sampling and client
runtime evidence are owned by
[inventory verification](inventory.md#performance-monitoring-coverage-and-production-evidence);
live scenarios remain in the
[manual performance-monitor matrix](manual-integration.md#performance-monitor).

`SettingsCategoryCatalogTest` fixes the production catalog totals and places one
conditional Performance monitor subgroup at the end of each Debug category,
checking the client HUD switch and two server collection switches in descriptor
order and visibility. `SettingsNavigationModelTest` keeps the eight client and
six server category identities and independent page routing without adding a
monitor navigation level. `LoggingProfileScreenTest` mounts the production
screen's rows, finds one native selector for every closed display metric, and
covers current-handler DEBUG reads, HUD switch behavior, metric editability,
DEBUG exit/entry visibility, read-only server drafts, native Space and
mouse activation through the deferred-action coordinator, focus survival and
resize/back preservation. `ServerSettingsMonitorTest` covers the server rows'
independent Preview/Tracking dirty bits, all four enable combinations, draft-only
plans, final-profile gating, read-only, revocation, stale-response and disconnect
fences, isolation from the numeric invalid mask, and refusal of partial plans.
`SettingsLocalizationCoverageTest` checks the monitor display group headings and
shared selector help in all eight bundled locales.

The historical `MonitorLabelsLocalizationTest` result covered the prior
`MonitorLabels` vocabulary used by selectors and the then-prepared HUD, including
full and short labels, tooltips, scope components, metric selectors, default
summaries, format signatures, OFF-only styling and measurement-limit help across
bundled languages and client status values. It did not review translation
semantics, render a live locale or measure font fit. Full settings labels and
the HUD compact-label provider remain separately tested; the later joint HUD run
covers compact labels, the display model and the formatter, not the settings
full-label catalog. Execution scope and results are recorded in
[build checks](build-checks.md#performance-monitor-implementation-and-final-acceptance).
These remain headless fixtures, not a live game, native screen, font,
permission-provider, socket/loader-lifecycle or multiplayer run.

### HUD UX redesign coverage

The HUD-side compact-label vocabulary, formatter, display model, layout, cache,
paint and ABI evidence, including execution counts and limits, is owned by
[inventory HUD UX redesign coverage](inventory.md#hud-ux-redesign-coverage).
Current per-field `N/A`, per-row backing and reserved-height coverage is in
[stable HUD reservoir UX coverage](inventory.md#stable-hud-reservoir-ux-coverage);
the preceding fixed semantic grouping and wrapping evidence is in
[stable semantic HUD wrapping coverage](inventory.md#stable-semantic-hud-wrapping-coverage).

The performance-monitor control coverage above complements the historical
`MonitorLabelsLocalizationTest`; compact HUD labels remain HUD-only and do not
replace settings full names or selector text. The full
Common task is part of the
[HUD UX whole-tree run](build-checks.md#hud-ux-final-whole-tree-verification);
the disabled-path allocation boundary is owned by
[inventory HUD UX redesign coverage](inventory.md#hud-ux-redesign-coverage). Live
screen, native font and locale checks remain in the
[manual matrix](manual-integration.md#performance-monitor). The projection
contract is
[HUD projection and presentation](../../architecture/presentation/inventory.md#hud-projection-and-presentation).

## Server performance contract verification

The [UI](../../UI/settings-screen.md#server-performance-category),
[configuration](../../config/server.md#inventory-policy-object),
[revisioning](../../architecture/config/revisioning.md#same-version-shape-normalization)
and
[wire](../../architecture/network/protocol.md#server-configuration-routes-server-config-request-v5-server-config-update-v5-server-config-snapshot-v5)
contracts have automated evidence in the
[combined-tree run](build-checks.md#automated-verification-results) for the
earlier numeric surface and in the
[publication-stage run](build-checks.md#tracking-pool-publication-stage-run) for
the v5 and boolean additions.

`InventoryPerformanceOptionTest` constructs the actual replacement-slider
factory headlessly for every replaced leaf: construction invokes no edit
callback, keyboard edits select only the owning leaf and revert exactly, and
click/drag endpoints, one-stop arrow movement, focus-loss exit, read-only input,
stale-widget input, extra integer stops across rebuilds and navigation, session
replacement, unlimited-tail entry, two zero sentinels and revoking stale input
are covered. It also constructs both full-width native Tracking toggle factories,
checking help presence, own-leaf dirty/revert and stale callbacks after
revocation or disconnect. `InventoryPerformanceScaleTest` covers ordered
piecewise joints, endpoints, linear stops, exact multiplier decimals, distinct
zero and unlimited stops, and immutable extra integer stops. `InventoryConfigRangeTest` checks both
bounds for every integer leaf: files clamp without a shape rewrite, strict
administration rejects a mixed invalid update atomically, and endpoints and
in-range non-step integers survive parsing, persisted load and detached
conversion exactly. `PendingMemoryMigrationTest` covers supported same-version
and older scalar normalization, clamped finite persistence, missing or null
defaulting, explicit unlimited round trips, refusal to extend scalar acceptance,
guarded normalization and failed-write retry preserving root and nested unknown
data, external replacement reload and future-version protection. Its unknown
preservation evidence is specific to guarded normalization and writeback, not a
general ordinary-save guarantee.

`ServerInventoryConfigPacketsTest`, `ServerConfigVarNumbersTest` and
`ModIdentityTest` exercise the version-five IDs, leaf round trips, strict field
bounds, exact decimal quanta, canonical numbers and independent raw fixtures for
the memory mode byte, retained finite value, Tracking partition booleans and
fixed ordering. The earlier combined-tree run predates these boolean additions;
their execution input is scoped to the publication-stage run. Superseded scalar
payloads, malformed modes, truncation and trailing bytes reject without
publication or mutation. Source inspection confirms that all three loaders
register through those packet constants; their builds compile that wiring, but
these checks are not running-loader or old-peer fallback tests. Inventory
`OFFER` value-bound evidence is owned by
[inventory verification](inventory.md#resynchronization-and-budget-policy-verification).
The server Performance localization fixtures cover all eight bundled locales in
the combined-tree run; the expanded settings-resource coverage below is not a
claim of another passing run. Live verification limits remain under
[Known automated gaps](#known-automated-gaps).

## Settings-screen navigation, catalog, layout and localization

The [configuration UI](../../UI/settings-screen.md) has model, catalog,
geometry, headless native-widget/list and resource coverage rather than live
screen evidence. The cases below are source-reviewed coverage rather than a
recorded full-suite run.

`SettingsNavigationModelTest` covers the initial client overview, per-scope
overview selection, each category's own leaf page, the immutable eight-category
client and six-category server order including both Performance and Debug
categories, independent Performance categories, the server Presentation category
without ordinary-server permission, independent ordinary-server and
presentation-policy access, per-page viewport and focus retention, foreign-scope
rejection, Back routing, negative-scroll clamping, invalid-mask routing to the
owning category, a shared `ServerSettingsModel` draft across navigation, and a
server leaf kept open when revocation retains a viewable snapshot.
`SettingsCategoryCatalogTest` covers all catalog entries exactly once, the
approved value and non-value totals, section headings and ordering, semantic
pairs, singleton server rows, nonempty sections, defensive and immutable
descriptor collections, rejection of malformed descriptors, distinct
description keys and server-only inventory leaves. `PresentationFieldOutcomeTest`
covers the server policy role: manifest default, exact and wildcard allow rules,
membership toggles, whitelist-only blocking, block rules beating the default
while unknown selectors stay visible, no fabricated membership or authoritative
view, and server controls following pending and permission gates. This is the
server policy role only; no client receive or display policy model is covered
here. It is descriptor and pure decision-model coverage, not native placement or
focus dispatch; the [UI contract](../../UI/settings-screen.md#category-layout)
owns the detailed category and grouping rules.

`SettingsScreenLayoutTest` covers responsive content width and centering, native
maximum, scrollbar clearance, equal pair widths, row-sharing eligibility from
viewport and both label measurements, footer action clamping, and root/leaf list
and footer boundaries at compact, standard, large and tiny sizes, including
zero-size and negative content or action widths. `SettingsRowWidgetsTest`
positions real native `Button` instances as full-width singletons and equal
non-overlapping pairs, checks native hit and focus rectangles, callback
preservation, narrow-to-wide focus retention, hidden and inert zero-width rows,
recovery, and pair eligibility after label changes. `SettingsOptionsListTest`
constructs the production native options list and exercises overview/editor
reflow, retained selector text, caret, focus, scroll and list/entry dragging,
callback suppression, unusable-size focus fencing and native keyboard
adjustment recovery. `SettingsEnumOptionTest` constructs native `CycleButton`
controls from the Player Info, Team Color and Entity Block Geometry factories,
checks captions, narration, tooltip components and unchanged configuration
values. `SettingsTitleWidgetTest` covers localized title identity, breadcrumb and
scope-tab formats, injected language separators and argument order, explicit
bounds, wrapped title height, header reservation, and word/code-point wrapping.
`DeferredActionCoordinatorTest` covers the native dispatch seam, nested dispatch
coalescing and failure dropping. `SettingsScreenFocusKeyTest` and
`SettingsListFocusTest` cover focus-key lookup and the headless list focus/reveal
helper. `InventorySizeOptionTest` covers the size-slider codec, physical-size
positions, localized unit, varying maximum, read-only behavior, held arrow keys
across replacement and real-width stepping. `OptionUtilsDecimalTest` covers the
decimal option range and grid, exact encode/parse round trips, out-of-range
rejection, and inventory-fraction slider mappings. These are computed-geometry,
headless native-widget/list, helper and codec seams with injected measurements,
not a complete rendered screen, font fit, text-entry dispatch, footer traversal,
live permission transition, networking or persistence.

`ClientConfigLocalizationTest` covers the eight bundled locale files and the
settings, category entrance, server-status, entity-block-mode and target-gone
keys, placeholders, ellipses, feedback prefix, active-file restart help,
transparent-selection labels and tooltips, title segments, wheel and target
opacity labels, and the spatial-selector resource set. `SettingsLocalizationCoverageTest`
derives required labels and help from the production catalog for all value
controls, checks category descriptions, section headings, navigation and action
help, enum/state labels, units and format resources across all eight bundled
locales, validates argument-bearing templates with a formatter, rejects duplicate
JSON keys, and distinguishes literal percent signs from strict templates.
`ConfigEnumLocalizationTest` covers resource-key generation under ROOT, US and
`tr-TR` JVM defaults for Player Info, Team Color and Channel Mode values and
dynamic tooltip keys; it is source-reviewed with no passing execution recorded
here. `InventorySettingsLocalizationTest` covers inventory label/tooltip
alignment, placeholders, performance category entrance, inventory group
headings, name/value labels, numeric and three-line boolean help, Off/On
prefixes, zero labels, obsolete-resource removal and the two size sliders.
`BudgetDelayLocalizationTest` covers the monitoring setting, subgroup,
administrator-message and module keys, placeholders, the aggregate-status alias
and the toggle tooltip structure. `SpatialInventoryLocalizationTest` covers
renderer-referenced inventory keys, status labels and key-set alignment.
`SpatialSelectorLocalizationTest` covers the native selector menu-label keys,
content-bridge Create and Stress groups, toggle and target-type identities, and
spatial key alignment. `SpatialSelectorSettingsLocalizationTest` covers gesture,
minimum wait, content intent and marker-removal labels and tooltips, the timing
placeholder, stale-variant absence and the confirmed Chinese and English pins.
`PresentationSettingsLocalizationTest` covers presentation keys, approved English
labels, placeholders, builtin/Create/inventory-manifest names and descriptions,
namespace headings, read-only server status and the separate property request
phrase. These are resource and format expectations, not translation quality,
assembled screen labels or rendered tooltip behavior.

The production screen routes native widget input through the deferred-action
coordinator. None of these seams establishes a live game render/input lifecycle
or focus traversal.

## Assembled settings-screen caption reflow

`SettingsScreenInputLayoutTest` assembles the production screen's widgets and
dispatches native mouse, keyboard, wheel and slider-drag input headlessly. Its
seven cases use bundled Turkish captions with deterministic text measurements.

- **Reflow timing:** A Team Color caption crossing the half-width threshold stays
  paired through its native callback and held mouse gesture, then stacks in
  declared order on release and pairs again after cycling back without resize.
  Keyboard reflow waits for the outermost dispatch, including nested dispatch in
  a save callback; completed wheel dispatch reevaluates changed captions while
  preserving list scroll, and neither wheel nor display-only changes edit enums.
- **State and callback retention:** Reflow retains the same list and widgets,
  focus and viewport, with one edit/save callback per activation. Caption changes
  during slider click/drag and intervening key input wait until release; the
  entry, focus and dragged value remain, without a repeated save.
- **Dialog and slider recovery:** Reset confirmation and Escape retain the list,
  widgets and focus; subsequent Tab and enum input reflow and save once per
  activation. Keyboard size-slider edits after return refresh both dependent
  controls, reconcile the draft and show the adjustment notice without an
  ordinary server-draft save, while focus and keyboard adjustment survive
  replacement. Suspending a pending drag cancels gesture flags without rebuilding
  the hidden page or losing focus; after return, the next dispatch refreshes
  without another edit, stale dragging cannot resume, and draft text and dirty
  fields remain unchanged.

The fixture preserves native screen removal, addition and retained-instance
initialization while bypassing GPU, mouse/window and title work. Server-size
cases inject accepted snapshot and permission state, not a connection or remote
transaction. The seven cases passed the focused screen suite and subsequent full
Java 21 verification recorded in the
[combined Java run](build-checks.md#recipientless-tracking-diagnostics-and-caption-reflow-verification),
which also built all three loaders. These headless assertions do not exercise
native glyph rendering, real GLFW input, hover or focus tooltip delivery,
screen-close persistence or live translation readability.

## Assembled settings-screen resize

`SettingsScreenResizeTest` assembles production screen widgets and invokes the
native resize path headlessly. Its four cases cover client-overview Reset becoming
hidden, inactive and unfocusable when the footer cannot contain it and recovering
on the same retained page and button; Reset remaining unavailable on the server
overview and representative leaf pages in both scopes through shrink and grow;
a retained subgroup heading keeping complete-text tooltip content when it starts
fitting but clips after resize without becoming interactive; and the Server
Presentation heading retaining its dedicated help through resize. Execution
evidence is scoped in the
[combined-tree Java verification and loader builds](build-checks.md#terminal-preview-admission-and-settings-resize-run).
Deterministic font measurements and tooltip-component assertions do not exercise
a real window, GUI-scale event, native glyph rendering, hover or focus tooltip
delivery, or wrapping.

## Known automated gaps

- logging-profile configuration and controls have focused and full common
  headless coverage, but live native Alt or Option-held and released activation,
  keyboard delivery, wheel scrolling, rendered localized help without a debug or
  gesture hint, a real macOS Option key, a Minecraft window, a live
  client/server profile switch, multiplayer and remote persistence remain
  unexercised; the screen fixture intercepts a physical modifier read without a
  game window or connection;
- the complete live settings screen remains unexercised: native-list reflow and
  input, enum caption, narration and tooltip components, assembled
  caption-change and dialog-return input and resize, actual font-driven pair
  stacking, rendered heading clipping and help, hover or keyboard-focus tooltip
  delivery, wrapping, color styling, text-entry dispatch, footer traversal,
  GUI-scale and window resizing, live permission transitions, and multiplayer
  read-only behavior. The server Performance factory, model, handler, codec and
  localization seams passed the combined-tree run but do not establish that
  screen's mouse dispatch, assembled localized tooltip layout, translation
  quality without native-speaker review or remote persistence session; native
  widget callbacks are exercised headlessly, not by gameplay. Execution evidence
  is scoped separately, and live scenarios remain in the
  [manual matrix](manual-integration.md#settings-and-server-performance);
- the assembled live screen coverage for the client inventory-cap sliders, the
  gesture controls' OFF/disabled presentation and live compatibility refresh, a
  constructed screen's disabled state and complete zero-angle off label, the
  inventory stay-open control input, and the local marker display duration label
  is still missing. Their option mapping, model, codec, catalog, resource or
  filesystem-handler fixtures do not render those controls, establish live input
  or screen-close persistence, or assemble the complete localized Follow or
  explicit-duration label, whose form is owned by
  [configuration UI](../../UI/settings-screen.md#marker-display-duration-option);
  pending native screen checks remain in the
  [manual matrix](manual-integration.md#pending-manual-and-integration-matrix);
- inventory administration and spatial-selector configuration have model,
  persistence, strict wire, draft, catalog and focus-helper seams; live
  administration, screen-level input and focus, permission changes, persistence,
  scheduler consumption and selector integration remain unexercised;
- live server-settings UI, permission, read-only viewing below the required
  level, request/response, update and persistence behavior;
- performance-monitor controls, mounted rows, compact labels and HUD
  layout/cache/ABI have headless coverage and passed the final combined-tree test
  and build, but live native screen and font rendering, GL and pixel output,
  actual permission-provider transitions, real socket/loader lifecycle and
  multiplayer persistence remain unexercised. Production sampling and HUD
  evidence is under
  [inventory verification](inventory.md#performance-monitoring-coverage-and-production-evidence),
  and live scenarios remain in the
  [manual matrix](manual-integration.md#performance-monitor).
