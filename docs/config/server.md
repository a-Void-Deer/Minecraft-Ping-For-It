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

The five fields other than `pingDistance` and `presentation` can also be changed
over the client/server connection through
[changing server configuration](../architecture/config/changing-server-config.md).
Within the `presentation` object, the per-target-type `white`, `black`, and
`whitelistOnly` rules are changed through the dedicated presentation policy
route owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md);
its `minUpdateIntervalTicks`, `scanBudget`, `permissionLevels`, and
`updateIntervals` members, like `pingDistance`, are configured only by editing
the file. The
[configuration UI](../UI/settings-screen.md#server-presentation-category)
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

## Presentation policy object

The `presentation` object persists one rule set per target type; the client
file has no presentation policy shape. Numeric bounds and defaults are applied
by the implementation and are deliberately not mirrored here.

| Member | JSON form | Meaning |
| --- | --- | --- |
| `targetTypes` | object mapping each of the five target-type IDs (`dropped_item`, `entity`, `entity_block`, `block`, `location`) to a rule object | Per-target-type field authorization. A missing or malformed target-type entry denies every field of that type instead of widening access. |
| `targetTypes.<type>.white` | array of selector strings | Allow selectors for that target type. An empty list grants nothing by itself; an advertised field's manifest default still applies unless whitelist-only mode denies it. |
| `targetTypes.<type>.black` | array of selector strings | Deny selectors for that target type; a matching field is denied unless an allow selector also matches, because an allow match wins. |
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

Field selectors are evaluated against the marker's exact target type: allow,
then deny, then the field's manifest default, with an allow match winning over a
deny match and whitelist-only denying an unmatched field. Evaluation, the
derived recipient mask, and the client consequence are owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md#per-target-type-field-policy).
Property Ping override selectors are a different grammar that matches a
target's registry ID or tags rather than field IDs; it is owned by
[presentation snapshot](../architecture/presentation/presentation_snapshot.md#property-ping).

Allow selectors that fail validation are skipped. A malformed `targetTypes` map
or a malformed `presentation` object denies every field of every target type,
while a malformed individual rule object denies only its own target type; an
invalid deny selector or an over-capacity allow/deny collection denies every
field of that target type. An invalid permission override or an over-capacity
permission-override collection denies every field instead of widening access,
and the resulting deny-all state is persisted durably. Invalid
`updateIntervals` entries are skipped; an invalid `updateIntervals` collection
removes all per-adapter overrides, so each adapter uses its declared interval
subject to the global minimum rather than denying fields. Out-of-range numeric
members are clamped. The obsolete flat `white`, `black`, and `whitelistOnly`
members are not part of this shape; their migration to the per-target-type map
is owned by
[configuration revisioning](../architecture/config/revisioning.md#same-version-shape-normalization).

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
  }
}
```

JSON keys other than the catalogue above are outside this contract. Retained
unknown entries, migration, and recovery behavior are owned by
[configuration revisioning](../architecture/config/revisioning.md).
