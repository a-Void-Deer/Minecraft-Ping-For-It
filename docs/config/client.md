# Client settings and recovery

## Current defaults and locality

These are current configurable defaults/settings, not unresolved or immutable
product values:

| Setting | Default and bounds | Application |
| --- | --- | --- |
| Long-press threshold (`wheelHoldMillis`) | **300 ms**; 100–2000 ms, 10 ms UI step | Capture/wheel interaction |
| Long-press compatibility slice (`longPressCompatibilitySliceMillis`) | **20 ms**; minimum 10 ms, maximum described below, 5 ms UI step | Compatibility rapid-click adjacency window; enabled behavior is owned by [capture](../picking/capture.md) |
| Maximum wheel-open duration (`wheelTimeoutMillis`) | **5000 ms**; 1000–30000 ms, 200 ms UI step | Starts only when wheel actually opens |
| Cancellation cone half-angle | **5 degrees** | Press-ray cone, live own-marker candidates |
| Long-press compatibility (`longPressCompatibilityMode`) | **disabled** | Rapid-click virtual hold and pending-first-capture deferred fresh press; see [capture](../picking/capture.md) |
| Pass through transparent blocks (`passThroughTransparentBlocks`) | **disabled** | Persistent target-selection policy; see [selection policy](../picking/selection_policy.md) |
| Mark blacklisted targets (`markBlacklistedTargets`) | **disabled** | Persistent entity-selection policy; see [selection policy](../picking/selection_policy.md) |
| Mark fluids (`markFluids`) | **disabled** | Persistent target-selection policy; see [selection policy](../picking/selection_policy.md) |
| Block display whitelist | exactly `*:*` | Client-local native-glow eligibility |
| Block-shape blacklist | empty | Overrides whitelist match |
| Entity-block geometry source mode | `ALL` | Client-local, read on every render attempt/frame |

The long-press threshold is clamped to **100–2000 ms**. The compatibility
slice is clamped to **10 ms** through the lower of **300 ms** and the floored,
five-millisecond-step half of the effective threshold; therefore the default
300 ms threshold has a 150 ms maximum slice. JSON validation and direct setting
mutations use the same clamp, and changing the threshold immediately reclamps
the stored slice. Runtime compatibility independently applies that same clamp
to supplier values. When the settings option is created, its upper bound is
computed from the then-current threshold and exposed in five-millisecond steps;
the option does not make compatibility enabled by itself.

At a baseline press, the interaction freezes the clamped long-press threshold.
At actual wheel opening, it separately freezes the clamped wheel timeout. In
contrast, compatibility mode and its effective slice are observed at relevant
raw edges and render frames; the slice's threshold-derived cap can therefore
change while a rapid-click candidate remains alive. The interaction state,
rather than elapsed threshold alone, decides whether a present frame actually
opened a wheel. The two compatibility paths and the resulting release/abort
rules are owned by [capture](../picking/capture.md).

`pingDistance` is a client-local setting with default **2048** and a settings
UI range of 0–2048 in 16-block steps (where 0 is shown as hidden and the maximum
as infinite). It is not synchronized to the server. The client configuration
validation path does not apply a `ClientConfigBounds` clamp to either this field
or the hidden native ray-distance field; the UI range is therefore not a claim
about arbitrary persisted values. Its capture role, optional integrations, and
server authority are owned by [capture range](../picking/range.md); this table
must not be read as a server acceptance guarantee.

Detailed timing lives in [capture](../picking/capture.md) and
[wheel](../picking/wheel.md). Existing range/lifetime/cooldown mechanics are
preserved. The client display-duration setting and marker state transitions are
owned by [marker lifecycle](../authority/marker_lifecycle.md), not this settings
table. Server send policy is separate: [rate limit](rate_limit.md).

## Whitelist and blacklist grammar

There is no GUI list editor. Both lists accept only:

- exact `namespace:block`;
- namespace wildcard `namespace:*`;
- global wildcard `*:*`;
- block tag `#namespace:tag`.

Entries have union semantics, but matching and persisted-config validation are
separate boundaries:

- Within a supplied direct-matcher list, blank or grammatically malformed
  entries are ignored, so those entries cannot match. A grammatically valid
  entry whose block, tag or optional-mod content is unavailable also matches
  false.
- Persisted client lists are validated strictly when the configuration loads.
  A `null` list, or a `null`, blank or grammatically malformed entry, makes the
  persisted client configuration invalid; it is not accepted as a harmless
  non-match. That case is owned by
  [invalid-file recovery](#invalid-file-recovery-and-preservation-lock).

A blacklist match overrides a whitelist match. These lists are not a datapack
or server-sync system.
Target-type/live-state conditions still apply after a list match; see
[outline attempt eligibility](../rendering/outline.md).

## Entity-block mode persistence

Allowed values are `ALL`, `COMPATIBLE` and `VOXEL_SHAPE_ONLY`. The new
configuration default is `ALL`, and a missing persisted field uses that default.
An explicit persisted `null` or unknown persisted value recovers to `COMPATIBLE`;
that recovery value is distinct from the new configuration default. Do not
conflate default initialization with persisted-value recovery.

The setting has no server synchronization or reconnect cache; read it each
render attempt/frame. Ordinary `block` rendering does not read this mode.
[Geometry sources](../geometry/geometry_sources.md) owns the ordered execution
and outcome semantics.

## Initialization, editing and reload

Load config during client initialization. Opening or reopening a
`SettingsScreen` in the same session does not reload an externally edited file.
The config action saves and closes the settings screen before opening the
client config file with the platform file opener. External list edits take
effect after restart or an explicit reload.

## Invalid-file recovery and preservation lock

The cross-client/server schema marker, migration, recovery, serialization and
save-protection contract is owned by
[configuration versioning](versioning.md). In particular, client strict-list
validation participates in that document's client invalid-file recovery path;
it is not a harmless per-entry non-match. The recovery header, backup-before-
reset ordering and preservation-lock limits are defined there. Coverage and the
pending in-session/external-edit cases remain tracked in
[verification](../testing/verification.md).

### Future-version preservation

Client and server future-version preservation is defined by
[configuration versioning](versioning.md#future-version-protection). A client
future-version file is not an invalid-file recovery input and does not define a
downgrade or migration path.
