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
| `wheelTimeoutMillis` | number, milliseconds | Maximum duration of an actually open wheel; [wheel](../architecture/picking/wheel.md) owns actual-open snapshot and timeout behavior. |
| `cancelHalfConeAngleDegrees` | number, degrees | Half-angle for local own-marker cancellation; [wheel](../architecture/picking/wheel.md#cancel-marker-selection) owns candidate selection. |
| `wheelInnerRadius` | number, GUI pixels | Center/cancellation boundary. It must remain less than `wheelOuterRadius`. |
| `wheelOuterRadius` | number, GUI pixels | Outer sector boundary. It must remain greater than `wheelInnerRadius`. |
| `wheelOpacity` | number | Local wheel visual opacity. |
| `wheelFontSize` | number | Local wheel-label text size. |
| `wheelTargetFontSize` | number | Local target-label text size. |

### Target selection and entity-block presentation

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `passThroughTransparentBlocks` | boolean | Persistent block-shape selection policy; see [selection policy](../architecture/picking/selection_policy.md). |
| `markBlacklistedTargets` | boolean | Persistent entity-selection-blacklist policy; see [selection policy](../architecture/picking/selection_policy.md). |
| `markFluids` | boolean | Persistent fluid-selection policy; see [selection policy](../architecture/picking/selection_policy.md). |
| `playerInfoMode` | `HOLD`, `DISABLED`, `ALWAYS`, or `COMPACT` | Player-author presentation: `HOLD` shows verbose information while the player-list key is held; `ALWAYS` keeps it verbose; `COMPACT` puts the author in the distance label; `DISABLED` omits it. |
| `teamColorMode` | `FULL`, `DISABLED`, `PING_ONLY`, or `LABELS_ONLY` | Whether team coloring applies to both ping and labels, neither, only the ping, or only labels. |
| `entityBlockRenderMode` | `ALL`, `COMPATIBLE`, or `VOXEL_SHAPE_ONLY` | Local entity-block geometry route. [Geometry sources](../architecture/geometry/geometry_sources.md) owns route and outcome semantics. When decoding persisted input, an explicit `null` or unknown value normalizes to `COMPATIBLE`; a missing field is initialized by the config model. |

### Spatial selector interaction

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `spatialSelector` | object | Client-local target-list glide and Back-hover preference; it is never sent to the server and cannot alter server policy. |
| `spatialSelector.targetGlide` | number | Target-list glide factor. The frozen press result and glide application are owned by [wheel](../architecture/picking/wheel.md); its range and reset value are implementation values. |
| `spatialSelector.hoverEnabled` | boolean | Whether Back-hover target return is enabled. Back-hover is opt-in and stays inactive until the user enables it. |
| `spatialSelector.hoverMillis` | number, milliseconds | Dwell threshold before Back-hover returns one level. Its range and step are implementation values. |

No other spatial-selector prototype tuning, such as dead zone or stroke, is
persisted. The return-state contract — one level, leaving before re-entry to
re-arm, and a frozen press start — is owned by
[wheel](../architecture/picking/wheel.md#back-hover-return-state).

### Block display lists

| Key | JSON form | Local meaning |
| --- | --- | --- |
| `blockDisplayWhitelist` | array of strings | Enables matching blocks for client-local native-glow/outline attempts. |
| `blockShapeBlacklist` | array of strings | Excludes matching blocks from those attempts. A blacklist match takes precedence over a whitelist match. |

These display lists are separate from the entity-selection blacklist controlled
by `markBlacklistedTargets`; see
[the blacklist boundary](../architecture/picking/selection_policy.md#raycast-use-and-blacklist-boundary).

### Obsolete presentation policy keys

`presentationReceive` and `presentationDisplay` are obsolete keys with no
persisted meaning. The handler removes them from a loaded or serialized
document rather than retaining them, so no client-local receive or display
policy exists. The removal and recovery behavior is owned by
[configuration revisioning](../architecture/config/revisioning.md). The
versioned presentation field policy is server-owned; its persisted shape is
catalogued in [server configuration](server.md#presentation-policy-object).

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
