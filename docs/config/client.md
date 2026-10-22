# Client configuration

This topic owns the complete persisted client-file catalogue, file grammar, and
local authority boundary. It does not define server configuration, server
acceptance, marker-lifetime authority, configuration recovery, or configuration-UI
workflow.

## File, metadata, and locality

The client configuration file is `pingforit.json`. Its persisted fields are
catalogued below. `pingforit-version` is an additional handler-owned metadata
key rather than a catalogued field; its grammar, migrations, and recovery
behavior belong to
[configuration revisioning](../architecture/config/revisioning.md).

Every setting below is client-local. It can affect local input, capture, or
presentation but cannot grant server range acceptance or alter server marker
lifetime. A chosen channel is notified to the server when applicable, but the
server remains authoritative for its own channel acceptance and marker state;
see [target validation](../architecture/authority/target_validation.md).

Current implementation defaults, validation metadata, and widget definitions
are intentionally not mirrored here; the
[configuration UI](../UI/settings-screen.md) owns which controls are exposed.

## Persisted field catalogue

Numbers use JSON numeric form. Units are stated where they are meaningful to the
field; implementation defaults and numeric bounds are deliberately not mirrored
here.

### Sound, reach, and local presentation

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `pingVolume` | number | Local ping-sound volume. |
| `pingDistance` | number | Local capture-distance preference. [Range](../architecture/picking/range.md) owns its interaction with native and server limits. |
| `itemIconVisible` | boolean | Whether item-icon presentation is visible. |
| `directionIndicatorVisible` | boolean | Whether the off-screen direction indicator is visible. |
| `pingSize` | number | Local visual scale for marker and direction-indicator presentation. |
| `configurationNoticeSize` | number | Local visual size for target-selection toggle notices. |
| `markerDisplayDuration` | number | Local marker-display-duration preference. Serialized `0` means each marker follows its frozen server-side duration, rather than a literal zero-duration display; [client marker state](../architecture/markers/client-state.md) owns the resulting state behavior. |
| `raycastDistance` | number | Native local raycast cap; [range](../architecture/picking/range.md) owns capture-range composition. Its current screen exposure is owned by the [configuration UI](../UI/settings-screen.md). |

### Press, wheel, and cancellation interaction

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `wheelHoldMillis` | number, milliseconds | Long-press threshold; [timing relationships](../architecture/input/long-press.md) and [capture-time consumption](../architecture/picking/capture.md#baseline-release-and-actual-wheel-opening) own its behavior. |
| `longPressCompatibilityMode` | boolean | Enables the narrow rapid/deferred compatibility sequence; [long-press compatibility](../architecture/input/long-press-compatibility.md) owns its behavior. |
| `longPressCompatibilitySliceMillis` | number, milliseconds | Compatibility adjacency slice. [Long-press timing](../architecture/input/long-press.md) owns its relation to the effective hold threshold. |
| `cancelHalfConeAngleDegrees` | number, degrees | Half-angle for local own-marker cancellation; [wheel](../architecture/picking/wheel.md#cancel-marker-selection) owns candidate selection. |
| `doubleClickDangerWindowMs` | number, milliseconds | Adjacent-press window for deriving repeated short-press `danger` creates; [double-click Danger](../architecture/input/double-click-danger.md) owns qualification and the adjacent-edge comparison. |
| `doubleClickDangerAngleDegrees` | number, degrees | Angular tolerance for that gesture; [double-click Danger](../architecture/input/double-click-danger.md) owns the comparison, its zero-disabled meaning and the angle derivation. It is independent of `cancelHalfConeAngleDegrees` and never configures own-marker cancellation. |
| `wheelOpacity` | number | Local opacity of the selector's visual underlay; [wheel](../architecture/picking/wheel.md#native-selector-presentation) owns what the underlay covers and how it layers. |
| `wheelTargetOpacity` | number | Local opacity of text-bearing selector frames together with their labels; [wheel](../architecture/picking/wheel.md#native-selector-presentation) owns the layering. |
| `wheelFontSize` | number | Local wheel-label text size. |
| `wheelTargetFontSize` | number | Local target-label text size. |

### Target selection and entity-block presentation

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `markTransparentBlocks` | boolean | Persistent block-shape selection policy, initialized enabled; see [selection policy](../architecture/picking/selection_policy.md). Version-boundary preservation of the previous toggle's actual selection effect is owned by [configuration revisioning](../architecture/config/revisioning.md#ordered-migration-and-writeback). |
| `markBlacklistedTargets` | boolean | Persistent entity-selection-blacklist policy, initialized disabled; see [selection policy](../architecture/picking/selection_policy.md). |
| `markFluids` | boolean | Persistent fluid-selection policy, initialized disabled; see [selection policy](../architecture/picking/selection_policy.md). |
| `playerInfoMode` | `HOLD`, `DISABLED`, `ALWAYS`, or `COMPACT` | Player-author presentation: `HOLD` shows verbose information while the player-list key is held; `ALWAYS` keeps it verbose; `COMPACT` puts the author in the distance label; `DISABLED` omits it. |
| `teamColorMode` | `FULL`, `DISABLED`, `PING_ONLY`, or `LABELS_ONLY` | Whether team coloring applies to both ping and labels, neither, only the ping, or only labels. |
| `entityBlockRenderMode` | `ALL`, `COMPATIBLE`, or `VOXEL_SHAPE_ONLY` | Local entity-block geometry route. [Geometry sources](../architecture/geometry/geometry_sources.md) owns route and outcome semantics. When decoding persisted input, an explicit `null` or unknown value normalizes to `COMPATIBLE`; a missing field is initialized by the config model. |

### Spatial selector interaction

`spatialSelector` holds client-local spatial-selector gesture and appearance
preferences. An absent or `null` object is initialized by the config model, and
a missing member keeps its model-initialized value. Explicit members follow
validation and the preference relationships below.

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `spatialSelector` | object | Container for the preferences below; never sent to the server and cannot alter server policy. |
| `spatialSelector.gestureSkipHoldEnabled` | boolean | Opt-in gesture shortcut, disabled by default, allowing a physical Ping-key hold and displacement to qualify for early selector opening. [Long-press timing](../architecture/input/long-press.md#optional-physical-hold-gesture) owns eligibility; compatibility mode takes priority under the preference relationship below. |
| `spatialSelector.gestureSkipHoldMillis` | number, milliseconds | Minimum continuous physical hold before the optional gesture shortcut may qualify, independent of the ordinary long-press threshold. |
| `spatialSelector.deadzone` | number, GUI pixels | Radial center deadzone and inventory-side intent threshold; [wheel](../architecture/picking/wheel.md#inventory-list) owns how the same held value applies at a list panel edge. |
| `spatialSelector.stroke` | number, GUI pixels | Radial submenu-entry stroke and inventory-side navigation distance; [wheel](../architecture/picking/wheel.md#inventory-list) owns the panel-edge measurement and navigation gates. |
| `spatialSelector.dwellMillis` | number, milliseconds | Dwell threshold before a focused branch entry is entered. |
| `spatialSelector.rootDistance` | number, GUI pixels | Visual root-menu distance from the center; it does not change the deadzone or entry stroke. |
| `spatialSelector.submenuRadiusScale` | number, multiplier | Client-local visual multiplier of the viewport-derived non-root radial radius, applied to every radial submenu including an inventory item's radial menu; the root menu's distance and every gesture threshold stay independent, and it freezes into the held gesture's snapshot like the other selector preferences. |
| `spatialSelector.inventoryMaxWidthFraction` | number, viewport-width fraction | Maximum inventory content-panel width as a fraction of the current GUI-scaled viewport width, not a fixed panel width. |
| `spatialSelector.inventoryMaxHeightFraction` | number, viewport-height fraction | Maximum inventory content-panel height as a fraction of the current GUI-scaled viewport height, not a fixed panel height. |
| `spatialSelector.hoverEnabled` | boolean | Whether sustained Back hover returns one level; opt-in and inert until enabled. |
| `spatialSelector.hoverMillis` | number, milliseconds | Dwell threshold before Back-hover returns one level. |
| `spatialSelector.showTrail` | boolean | Whether the shared pointer/active-option trail is drawn; false hides both pointer and option trail without changing gesture selection. |
| `spatialSelector.trailLength` | integer, retained points | Maximum retained points in the shared trail history; a larger retained history can draw a longer trail when sufficient movement exists. |
| `spatialSelector.trailOpacity` | integer, percent | Independent opacity multiplier applied only to the trail, not the wheel underlay, text-bearing options, guides, or connector. Zero makes the trail fully invisible without changing its history or gesture selection. |
| `spatialSelector.reduceMotion` | boolean | Reduces selector animation without changing gesture timing or selection. |
| `spatialSelector.preciseCapturePeriodTicks` | positive integer, ticks | Live candidate capture period for the Precise branch while it is active. A change applies to the next hold, not to the running one. |
| `spatialSelector.contentIntentEnabled` | boolean | Whether the Content hierarchy offers its additional property and inventory-item Ping Type selection menus; while disabled, an otherwise selectable property or a directly released inventory item requests the Attention annotation instead, without changing the whole-marker Ping Type. Frozen into the held gesture's snapshot like the other selector preferences. |
| `spatialSelector.inventorySelectionStayOpenEnabled` | boolean | Opt-in stay-open session for an open selector's inventory list and item menu, disabled by default. [Wheel](../architecture/picking/wheel.md#inventory-selection-stay-open) owns the behavior. |

The [inventory-list sizing contract](../architecture/picking/wheel.md#inventory-list)
owns which surfaces these fractions size and how content demand and readable
minima compose with the caps. Validation uses exact decimal values: missing or
`null` members use their model-initialized values, out-of-range values clamp to
the supported interval.

Missing inventory-fraction members alone do not schedule a same-version load
rewrite. Unknown nested-member preservation, guarded writeback, and
future-version protection remain owned by
[configuration revisioning](../architecture/config/revisioning.md).

The config model supplies an immutable validated snapshot for one held gesture,
so ordinary preference edits apply to the next hold rather than mutating that
snapshot. Compatibility's disabling relationship below still applies.
The gesture-shortcut choice and minimum wait belong to that snapshot. Enabling
long-press compatibility takes priority: it forces the gesture shortcut off,
clears any previously enabled preference for persistence, and prevents it from
being enabled while compatibility is active. Disabling compatibility does not
restore that choice; the player must explicitly enable it again. Observing
compatibility enabled can also end a pending shortcut under
[wheel's input-handoff contract](../architecture/picking/wheel.md#opening-and-selection).

The same priority applies to the double-click danger angle: enabling
compatibility forces its effective stored value to zero, a non-zero value is
rejected while compatibility remains active, and disabling compatibility leaves
it at zero rather than restoring or retaining a previous angle.
[Double-click Danger](../architecture/input/double-click-danger.md) owns the
gesture itself.

A loaded client document with both `longPressCompatibilityMode` and
`spatialSelector.gestureSkipHoldEnabled` explicitly `true` is normalized by
clearing the latter and scheduling the existing guarded writeback even at the
same version. Missing shortcut members alone do not trigger a load rewrite. A
loaded client document whose double-click danger angle is non-zero while
compatibility is enabled, or whose angle member is missing or `null` in that
mode, resolves to the disabled zero value and schedules that same guarded
writeback even at the same version; with compatibility disabled, a missing angle
member keeps the model-initialized value and does not force a rewrite. A
malformed non-numeric angle member is left to typed deserialization and
invalid-file recovery. The shared normalization, changed-source guard,
future-version protection and invalid-file recovery
remain owned by
[configuration revisioning](../architecture/config/revisioning.md#same-version-shape-normalization),
and the controls' disabled presentation is owned by the
[configuration UI](../UI/settings-screen.md#input-interaction-controls).

Trail visibility, length, and opacity are frozen in that snapshot; explicit
persisted values are retained, and missing or `null` members use the model's
initialized values without a migration that rewrites or rescales them.
Gesture behavior is owned by
[wheel](../architecture/picking/wheel.md#headless-spatial-menu-model), which
also owns the
[Back-hover return state](../architecture/picking/wheel.md#back-hover-return-state)
and the model's native-integration boundary. Numeric ranges, steps, and defaults
remain implementation values.

### Block display lists

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `blockDisplayWhitelist` | array of strings | Enables matching blocks for client-local native-glow/outline attempts. |
| `blockShapeBlacklist` | array of strings | Excludes matching blocks from those attempts. A blacklist match takes precedence over a whitelist match. |

These display lists are separate from the entity-selection blacklist controlled
by `markBlacklistedTargets`; see
[the blacklist boundary](../architecture/picking/selection_policy.md#raycast-use-and-blacklist-boundary).

### Obsolete keys

`spatialSelector.targetGlide` is not a modeled preference and has no input,
gesture, or settings-screen effect. It has no dedicated version boundary or
migration. Existing unknown-key preservation may leave it in a raw document
under [configuration revisioning](../architecture/config/revisioning.md), but a
retained value is never read or applied. Inventory mouse selection is owned by
[wheel](../architecture/picking/wheel.md#inventory-list), not by a sensitivity
multiplier.

`presentationReceive` and `presentationDisplay` are obsolete keys with no
persisted meaning. The handler removes them from a loaded or serialized
document rather than retaining them, so no client-local receive or display
policy exists. The removal and recovery behavior is owned by
[configuration revisioning](../architecture/config/revisioning.md). The
versioned presentation field policy is server-owned; its persisted shape is
catalogued in [server configuration](server.md#presentation-policy-object).

`wheelInnerRadius` and `wheelOuterRadius` are retired keys with no persisted
meaning. They are removed by the version-boundary migration and from every
client serialization at or after that boundary, so a later save cannot
resurrect them; removal, migration, and preservation behavior is owned by
[configuration revisioning](../architecture/config/revisioning.md). The keys do
not configure the live wheel, whose release boundaries are owned by
[wheel](../architecture/picking/wheel.md#radial-release-result).

`wheelTimeoutMillis` is a retired key with no persisted meaning. It is removed
by the version-boundary migration and from every client serialization at or
after that boundary, so a later save cannot resurrect it; removal, migration,
and preservation behavior is owned by
[configuration revisioning](../architecture/config/revisioning.md). It no
longer configures any selector lifetime; the open selector's interaction
lifetime is owned by
[wheel](../architecture/picking/wheel.md#opening-and-selection).

### Channel preferences

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `channel` | string | Current local preference and fallback while disconnected. It is not server configuration. |
| `serverChannels` | object mapping raw server-address strings to strings | Remembered preference keyed by the current server's raw address string. It is a managed client record, not server configuration. |

### Direction-indicator safe area

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `safeZoneLeft` | number, GUI-scaled inset | Left screen inset for off-screen direction-indicator placement. |
| `safeZoneRight` | number, GUI-scaled inset | Right inset, measured from screen width. |
| `safeZoneTop` | number, GUI-scaled inset | Top screen inset for off-screen direction-indicator placement. |
| `safeZoneBottom` | number, GUI-scaled inset | Bottom inset, measured from screen height. |

The [configuration UI](../UI/settings-screen.md) owns which file fields have
interactive controls. `blockDisplayPolicy` is derived transient state, not a
persisted key.

## Block-list grammar and file decoding

Each block-display list accepts exactly these entry forms:

- exact block ID: `namespace:block`;
- namespace wildcard: `namespace:*`;
- global wildcard: `*:*`; and
- block tag: `#namespace:tag`.

For example, `minecraft:stone`, `minecraft:*`, `*:*`, and
`#minecraft:planks` are valid. Entries within one list have union semantics. A
grammatically valid entry whose block, tag, or optional-mod content is absent
does not match.

A list match is only client-local display eligibility. Target-type and live-state
conditions remain owned by [outline attempt eligibility](../architecture/rendering/outline.md).

Direct matcher input ignores blank or malformed entries. Persisted-file decoding
is stricter: each persisted entry must decode to a non-null, nonblank,
grammatically valid block selector. A null list or a null, blank, or malformed
decoded entry makes the file invalid; only
[configuration revisioning](../architecture/config/revisioning.md#invalid-file-recovery-differs-by-config-type)
owns the resulting handler behavior. The lists are neither a datapack system nor
a server-synchronized policy.

Canonical enum spellings are the uppercase values shown in the catalogue. No
claim is made here about accepting alternative JSON casing.
