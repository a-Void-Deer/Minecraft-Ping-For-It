# Server configuration

This topic owns the complete persisted catalogue of the server-authoritative
configuration file. It does not own the client configuration file
([client configuration](client.md)), the settings UI
([configuration UI](../UI/config.md)), the remote change transaction
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

[`ServerConfig`](../../common/src/main/java/nx/pingwheel/common/config/ServerConfig.java)
is the typed persisted schema and the `ServerConfig.HANDLER` implementation
entry that binds the class and suffix.
[`ServerConfigBounds`](../../common/src/main/java/nx/pingwheel/common/config/ServerConfigBounds.java)
holds the boundary helpers that validation delegates to. The exact numeric
bounds and fallbacks live in that implementation metadata and are deliberately
not mirrored here.

The five fields other than `pingDistance` can also be changed over the
client/server connection through
[changing server configuration](../architecture/config/changing-server-config.md);
`pingDistance` is a JSON-only server setting.

## Persisted fields

| JSON key | JSON form | Meaning | Authority and owning behavior |
| --- | --- | --- | --- |
| `defaultChannelMode` | string; canonical values `AUTO`, `DISABLED`, `GLOBAL`, `TEAM_ONLY` | Selects the default channel mode applied when a sender has no stored channel. | Server-authoritative. Empty-channel admission and audience behavior are owned by [target validation](../architecture/authority/target_validation.md); the canonical spelling is the uppercase value. |
| `playerTrackingEnabled` | boolean | Selects whether a player entity request is tracked as that entity or normalized to the player's authoritative location. | Server-authoritative. Player normalization behavior is owned by [target validation](../architecture/authority/target_validation.md). |
| `msToRegenerate` | number, milliseconds | Regeneration interval used by the send-rate policy that the server publishes to clients. | Server-authoritative. Rate semantics are owned by [rate policy](../architecture/config/rate-limit.md). |
| `rateLimit` | number | Send-rate allowance used by the server's per-player create limiter and published to clients as a courtesy policy. | Server-authoritative. Rate semantics are owned by [rate policy](../architecture/config/rate-limit.md). |
| `syncDuration` | number, seconds | Server-held marker synchronization lifetime used for an accepted create's frozen expiry. | Server-authoritative. A valid positive duration after validation; it has no zero sentinel. Lifetime behavior is owned by [marker lifecycle](../architecture/authority/marker_lifecycle.md). |
| `pingDistance` | number, blocks | Server acceptance range measured from the requester's eye to the authoritative target anchor. | Server-authoritative acceptance setting. It is not a client advertised capture cap; capture composition and acceptance are owned by [range](../architecture/picking/range.md). |

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
  "pingDistance": 128
}
```

JSON keys other than the catalogue above are outside this contract. Retained
unknown entries, migration, and recovery behavior are owned by
[configuration revisioning](../architecture/config/revisioning.md).
