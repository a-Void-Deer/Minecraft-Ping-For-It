# Server configuration

This topic owns the complete persisted catalogue of the server-authoritative
configuration file. It does not own the client configuration file
([client configuration](client.md)), the settings UI
([configuration UI](../UI/settings-screen.md)), the remote change transaction
([changing server configuration](../architecture/config/changing-server-config.md)),
configuration revisioning ([revisioning](../architecture/config/revisioning.md)),
or the marker admission rules that consume these values.

## File, locality, and authority

The server file is `config/pingforit.server.json`, named from the mod ID
`pingforit` and the server-config suffix. It is a server-side persisted file:
the server retains the authoritative values, and a client does not
independently configure server behavior. The client file has a separate
`pingDistance` key; the two configuration objects are distinct even where names
match.

The server configuration object is the typed persisted schema. It applies live
validation, numeric bounds, and fallbacks; those implementation values are
deliberately not mirrored here.

The five top-level fields other than `pingDistance` and `presentation`, and the
nineteen `inventory` administration settings, can also be changed over the
client/server connection through
[changing server configuration](../architecture/config/changing-server-config.md).
Within the `presentation` object, the per-target-type `white`, `black`, and
`whitelistOnly` rules are changed through the dedicated presentation policy
route owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md);
like `pingDistance`, its `childBlack`, `minUpdateIntervalTicks`, `scanBudget`,
`permissionLevels`, and `updateIntervals` members are configured only by editing
the file. The [configuration UI](../UI/settings-screen.md#server-performance-category)
exposes the remote settings and the presentation policy controls.

## Persisted fields

| JSON key | JSON form | Meaning | Authority and owning behavior |
| --- | --- | --- | --- |
| `defaultChannelMode` | string; canonical values `AUTO`, `DISABLED`, `GLOBAL`, `TEAM_ONLY` | Selects the default channel mode applied when a sender has no stored channel. | Server-authoritative. Empty-channel admission and audience behavior are owned by [target validation](../architecture/authority/target_validation.md); the canonical spelling is the uppercase value. |
| `playerTrackingEnabled` | boolean | Selects whether a player entity request is tracked as that entity or normalized to the player's authoritative location. | Server-authoritative. Player normalization behavior is owned by [target validation](../architecture/authority/target_validation.md). |
| `msToRegenerate` | number, milliseconds | Regeneration interval used by the send-rate policy that the server publishes to clients. | Server-authoritative. Rate semantics are owned by [rate policy](../architecture/config/rate-limit.md). |
| `rateLimit` | number | Send-rate allowance used by the server's per-player create limiter and published to clients as a courtesy policy. | Server-authoritative. Rate semantics are owned by [rate policy](../architecture/config/rate-limit.md). |
| `syncDuration` | number, seconds | Server-held marker synchronization lifetime used for an accepted create's frozen expiry. | Server-authoritative. A valid positive duration after validation; it has no zero sentinel. Lifetime behavior is owned by [marker lifecycle](../architecture/authority/marker_lifecycle.md). |
| `pingDistance` | number, blocks | Server acceptance range measured from the requester's eye to the authoritative target anchor. | Server-authoritative acceptance setting. It is not a client advertised capture cap; capture composition and acceptance are owned by [range](../architecture/picking/range.md). |
| `presentation` | object | Per-target-type presentation field policy, sampling limits, and permission/interval overrides for the versioned presentation snapshot. | Server-authoritative. Projection, authorization, and demand-driven capture are owned by [presentation snapshot](../architecture/presentation/presentation_snapshot.md); the nested shape is catalogued below. |
| `inventory` | object | Server-authoritative inventory preview and tracking budgets, caps, and independent send-byte multipliers. | Server-authoritative. Budget, unlimited-mode, multiplier, and tracking-deadline semantics are owned by [inventory preview and tracking](../architecture/presentation/inventory.md); the nested shape is catalogued below. |

## Presentation policy object

The `presentation` object persists one rule set per target type; the client
file has no presentation policy shape. Numeric bounds and defaults are applied
by the implementation and are deliberately not mirrored here.

| Member | JSON form | Meaning |
| --- | --- | --- |
| `targetTypes` | object mapping each of the five target-type IDs (`dropped_item`, `entity`, `entity_block`, `block`, `location`) to a rule object | Per-target-type field authorization. A missing or malformed target-type entry denies every field of that type instead of widening access. |
| `targetTypes.<type>.white` | array of selector strings | Allow selectors for that target type. An empty list grants nothing by itself; an advertised field's manifest default still applies unless whitelist-only mode denies it. |
| `targetTypes.<type>.black` | array of selector strings | Deny selectors for that target type; a matching field is denied unless an allow selector also matches, because an allow match wins. |
| `targetTypes.<type>.childBlack` | array of child-reference objects | Exact record-child deny references for that target type; each object carries `adapterId`, `fieldId`, and a nonempty `recordPath` array of literal record keys. |
| `targetTypes.<type>.whitelistOnly` | boolean | When true, a field that matches neither list for that target type is denied even when its manifest default is enabled. |
| `minUpdateIntervalTicks` | number, ticks | Global minimum sampling interval; an adapter is never polled faster than this or its declared minimum. |
| `scanBudget` | number | Per-capture source-scan allowance; `0` disables capture. |
| `permissionLevels` | object mapping field ID to a vanilla level `0`–`4` | Per-field replacement of the manifest's required permission level. |
| `updateIntervals` | object mapping adapter ID to ticks | Per-adapter override that can only raise an adapter's declared sampling interval, not lower it. |

A field selector is a field-ID pattern written as `namespace:path`; it matches
field IDs only, so naming an adapter ID such as `create:presentation` does not
select that adapter's fields. Adapter-wide interval overrides use the separate
`updateIntervals` adapter-ID key instead. Each selector part accepts lowercase
letters, digits, `_`, `-`, `.`, and `*`; the path part may also contain `/`. A
`*` matches zero or more characters within its part and never crosses the `:`
separator. For example, `create:kinetic.*`, `*:target.name`, and `*:*` are valid
selectors.

A child reference is one `childBlack` entry: an object with `adapterId`,
`fieldId`, and a nonempty `recordPath` array of literal record keys; it
addresses exactly one entry inside a record-valued field. Matching is exact over
the complete tuple — there is no prefix, wildcard, case-folded, dotted-path, or
sequence-index form — so a record key is never parsed as selector syntax and a
sequence element is never addressable. A matching reference denies the addressed
child entry as an independent presentation entry: it is not offered for property
selection and carries no property Ping annotation, independently of the
field-selector outcome, so a top-field allow match cannot re-authorize it and a
selection or annotation naming it never restores it. It is not a value
redaction: the parent field stays authorized with its full value, so a denied
child is not removed from the parent's value, and a root value remains governed
by the field selectors — the whole `create:kinetic.speed` record, for example,
can still be authorized and formatted when both of its RPM entries are denied. A
reference naming a missing field or an absent optional adapter is retained but
inert, and it never grants access.

By default each target type's rule set denies two child references, and a
present rule set whose `childBlack` member is absent receives them: the
`create:presentation` adapter's `create:kinetic.speed` field with record path
`["effective_rpm"]`, and the same field with record path `["theoretical_rpm"]`.
An explicit empty array is a deliberate opt-out and stays empty, and an explicit
nonempty list is retained rather than overwritten. A target-type entry missing
from the map keeps its deny-all outcome instead of receiving defaults.

Field selectors are evaluated against the marker's exact target type: allow,
then deny, then the field's manifest default, with an allow match winning over a
deny match and whitelist-only denying an unmatched field. Evaluation, the
derived recipient mask, child-reference application, and the client consequence
are owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md#per-target-type-field-policy).
Property Ping override selectors are a different grammar that matches a
target's registry ID or tags rather than field IDs; it is owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md#property-ping).

Allow selectors that fail validation are skipped. A malformed `targetTypes` map
or a malformed `presentation` object denies every field of every target type,
while a malformed individual rule object denies only its own target type; an
invalid deny selector or child reference, or an over-capacity allow, deny, or
child-reference collection, denies every field of that target type and persists
that deny-all state durably. An invalid permission override or an over-capacity
permission-override collection denies every field instead of widening access,
and the resulting deny-all state is persisted durably. Invalid
`updateIntervals` entries are skipped; an invalid `updateIntervals` collection
removes all per-adapter overrides, so each adapter uses its declared interval
subject to the global minimum rather than denying fields. Out-of-range numeric
members are clamped. The obsolete flat `white`, `black`, and `whitelistOnly`
members are not part of this shape; their migration to the per-target-type map
is owned by
[configuration revisioning](../architecture/config/revisioning.md#same-version-shape-normalization).

## Inventory policy object

The `inventory` object persists the shared scan-work allowance, the single
pending-memory bound, and the separate preview and tracking budgets. The
existing `physicalSlotsPerTick` key is retained: supported detached snapshot
capture and copying do not consume its slot allowance; decoding and parsing the
captured snapshot are charged, while unsupported live fallback is charged by
the slots it reads. The source-specific accounting contract is owned by
[inventory preview and tracking](../architecture/presentation/inventory.md)
and [shared source capture and sync](../architecture/presentation/shared_sources.md).
Every cap is either a positive finite value or an explicit unlimited mode;
periods, resynchronization cooldown, pending memory and heartbeat have no
unlimited mode. There is no tracking-duration member: the tracking deadline
follows the Ping's own marker lifetime, owned by
[inventory preview and tracking](../architecture/presentation/inventory.md).

Numeric bounds, grids and defaults are applied by the implementation and are
deliberately not mirrored here. A finite cap is always positive; zero is not a
disable sentinel, except for the heartbeat member where zero is the explicit
"periodic heartbeat disabled" value and other repair and status paths remain
active.

An unlimited cap or multiplier is persisted explicitly as
`{"unlimited": true, "value": <finite number>}`. The finite value is retained
for the UI toggle, and the consuming runtime substitutes its own finite guard
instead of treating unlimited as a numeric sentinel, an overflow, an unbounded
array or an unbounded work loop.

| Member | JSON form | Meaning |
| --- | --- | --- |
| `physicalSlotsPerTick` | limit object | Shared scan-work allowance per tick: snapshot decode/parsing and unsupported live slot reads consume it; supported detached snapshot capture and copying do not. Unlimited removes only the configurable cap; finite work and memory guards and both logical quotas remain in force. |
| `pendingMemoryMiB` | number, MiB | Single finite server-wide pending-memory bound covering preview and tracking together. It is not a per-queue bound and has no unlimited mode. |
| `preview` | object | Preview accounting; the members below. |
| `preview.periodTicks` | number, ticks | Preview accounting period. |
| `preview.maxVariantsPerClientPeriod` | limit object | Per-client variant-category quota. |
| `preview.maxSlotsPerClient` | limit object | Per-client slot quota. |
| `preview.maxSlotsServer` | limit object | Server-wide preview slot quota. |
| `preview.maxTargetsPerClient` | limit object | Simultaneous preview targets per client. |
| `preview.clientByteMultiplier` | multiplier object | Preview per-client period byte allowance on the client multiplier grid. |
| `preview.globalByteMultiplier` | multiplier object | Preview global period byte allowance on the global multiplier grid. |
| `tracking` | object | Tracking accounting; the members below. |
| `tracking.periodTicks` | number, ticks | Tracking accounting period. |
| `tracking.maxVariantsPerTarget` | limit object | Per-target variant-category quota. |
| `tracking.maxSlotsPerTarget` | limit object | Per-target tracking slot quota. |
| `tracking.maxSlotsServer` | limit object | Server-wide tracking slot quota. |
| `tracking.streamByteMultiplier` | multiplier object | Tracking stream byte allowance per client+target period on the client multiplier grid. |
| `tracking.snapshotByteMultiplier` | multiplier object | Tracking snapshot byte allowance on the client multiplier grid; it bounds both one fragment and the client period total across all targets. |
| `tracking.globalByteMultiplier` | multiplier object | Tracking global period byte allowance on the global multiplier grid. |
| `tracking.resyncMinPeriods` | number, periods | Bounded abnormal-resynchronization cooldown; it is also the negotiated interval that bounds the unknown-baseline stream buffer. Positive and with no unlimited mode; buffer semantics are owned by [inventory preview and tracking](../architecture/presentation/inventory.md#tracking). |
| `tracking.heartbeatPeriods` | number, periods | Periodic checksum heartbeat cadence. `0` disables only the periodic heartbeat and is not an unlimited value; other repair and status paths remain active. |
| `tracking.gracePeriods` | number, periods | Rolling smoothing window for global wire accounting: the number of periods `n` in the confirmed excess-smoothing algorithm owned by [inventory preview and tracking](../architecture/presentation/inventory.md#budgets-queues-and-memory). It is independent of the resynchronization cooldown and of the bounded unknown-baseline stream window. |

A limit object has the stable JSON form
`{"unlimited": boolean, "value": number}`; a multiplier object has the form
`{"unlimited": boolean, "value": decimal number}`. The five send-byte
multipliers are independent: the preview client and global multipliers and the
tracking stream, snapshot and global multipliers each control only their own
scope, and a multiplier is a server-authoritative accounting scale rather than
a client display preference. The snapshot multiplier is the single multiplier
behind both the per-fragment cap and the per-client period total.

Missing nested objects and missing members receive model defaults. Finite
values are clamped into their confirmed ranges; multipliers are normalized
onto their quantized piecewise grids with exact arithmetic so no off-grid value
or byte drift is persisted. Out-of-range persisted values clamp without
resetting unrelated fields, and the additive object needs no version migration.

## Version marker

The file also carries the `pingforit-version` metadata marker. Its presence,
grammar, comparison, migration, protection, and recovery rules are owned by
[configuration revisioning](../architecture/config/revisioning.md).

## Example

Illustrative shape only; the values are samples, not defaults:

```json
{
  "pingforit-version": "1.2.3-pfi-example",
  "defaultChannelMode": "TEAM_ONLY",
  "playerTrackingEnabled": false,
  "msToRegenerate": 250,
  "rateLimit": 10,
  "syncDuration": 15,
  "pingDistance": 128,
  "presentation": {
    "targetTypes": {
      "block": {
        "white": ["create:kinetic.*"],
        "black": ["create:inventory.summary"],
        "childBlack": [
          {"adapterId": "create:presentation",
           "fieldId": "create:kinetic.speed", "recordPath": ["effective_rpm"]},
          {"adapterId": "create:presentation",
           "fieldId": "create:kinetic.speed", "recordPath": ["theoretical_rpm"]}
        ],
        "whitelistOnly": false
      },
      "entity": {
        "white": ["minecraft:entity.health"],
        "black": [],
        "whitelistOnly": false
      }
    },
    "scanBudget": 64,
    "permissionLevels": {"create:fluid.summary": 2}
  },
  "inventory": {
    "physicalSlotsPerTick": {"unlimited": false, "value": 512},
    "pendingMemoryMiB": 8,
    "preview": {
      "clientByteMultiplier": {"unlimited": false, "value": 0.5},
      "globalByteMultiplier": {"unlimited": true, "value": 1}
    },
    "tracking": {
      "streamByteMultiplier": {"unlimited": false, "value": 0.25},
      "heartbeatPeriods": 0
    }
  }
}
```

JSON keys other than the catalogue above are outside this contract. Retained
unknown entries, migration, and recovery behavior are owned by
[configuration revisioning](../architecture/config/revisioning.md).
