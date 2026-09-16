# Client settings and recovery

## Current defaults and locality

These are current configurable defaults/settings, not unresolved or immutable
product values:

| Setting | Default | Application |
| --- | --- | --- |
| Long-press threshold | **300 ms** | Capture/wheel interaction |
| Maximum wheel-open duration | **5000 ms** | Starts only when wheel actually opens |
| Cancellation cone half-angle | **5 degrees** | Press-ray cone, live own-marker candidates |
| Long-press compatibility | **disabled** | Narrow pending-capture/deferred-press behavior |
| Block display whitelist | exactly `*:*` | Client-local native-glow eligibility |
| Block-shape blacklist | empty | Overrides whitelist match |
| Entity-block geometry source mode | `ALL` | Client-local, read on every render attempt/frame |

Detailed timing lives in [capture](../picking/capture.md) and
[wheel](../picking/wheel.md). Existing range/lifetime/cooldown mechanics are
preserved. Server send policy is separate: [rate limit](rate_limit.md).

## Whitelist and blacklist grammar

There is no GUI list editor. Both lists accept only:

- exact `namespace:block`;
- namespace wildcard `namespace:*`;
- global wildcard `*:*`;
- block tag `#namespace:tag`.

Entries have union semantics. Invalid entries, missing referenced content and
unavailable optional-mod entries match false. A blacklist match overrides a
whitelist match. These lists are not a datapack or server-sync system.
Target-type/live-state conditions still apply after a list match; see
[outline attempt eligibility](../rendering/outline.md).

## Entity-block mode persistence

Allowed values are `ALL`, `COMPATIBLE` and `VOXEL_SHAPE_ONLY`. The default,
reset/recovery value and missing persisted field all yield `ALL`. An explicit
persisted `null` or unknown persisted value instead recovers to `COMPATIBLE`.
Do not conflate a missing field with an invalid explicit value.

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

If client config parsing is invalid, back up the original bytes **before** reset.
A successful reset file begins with exactly these three one-line comments,
followed by defaults:

```text
// Previous config had an error.
// Error reason: ...
// Backup file: ...
```

Sanitize the reason for a one-line comment. If backup fails, preserve the
original on disk. Defaults may remain in memory, but neither reset nor normal
saves can bypass the preservation lock. In-memory defaults remain disk-independent
until restart/manual intervention. Clear the lock only after a later successful
load or recovery. Coverage and the pending in-session/external-edit cases are
tracked in [verification](../testing/verification.md).
