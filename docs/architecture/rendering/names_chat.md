# Localized target names and chat

## Route-independent target names

Names and chat are required for block and entity pings regardless of whether an
outline uses BER, baked-model, optional geometry, or VoxelShape fallback. A
render route may fail or change without removing the authoritative target name.
Server-derived name data follows
[authoritative validation](../architecture/authority/target_validation.md).

- A custom-named ordinary entity or block entity is shown as
  `Custom Name (Vanilla Name)`. Without a custom name, show only the localized
  vanilla/base name.
- `ItemEntity` uses its contained `ItemStack`. A custom stack name is shown as
  `Custom Name (localized base item name)`; otherwise show the localized base
  item name. An entity-level custom name must not replace this stack rule.
- A `ServerPlayer` target uses the plain, unstyled
  `GameProfile.getName()` profile name. It does not substitute a display/custom
  name or inherit team or Ping-Type color.
- An ordinary entity uses its localized entity-type name, and an ordinary block
  uses its localized block name. Do not hard-code English target names.

## Custom and base component composition

For the custom-name forms above, the incoming custom component is deliberately
flattened to `Component.literal(customName.getString())` before composition.
Its text remains, but its incoming colors, styles (including italic), and events
are not inherited. The trusted localized base component is appended unchanged,
so any independent retained styling refers to that base component rather than
to the flattened custom input.

## Translation keys and template selection

| Purpose | Key |
| --- | --- |
| Common chat template | `pingforit.chat.pingmsg.template` |
| Per-Ping-Type override | `pingforit.chat.<id>.template.override` |
| Ping-Type phrase only | `pingforit.ping_type.<id>.phrase` |
| Legacy fallback | `pingforit.chat.pingmsg` |

Templates use the named placeholders `{playerName}`, `{pingType}`, and
`{targetName}`. Determine whether a per-type override exists from the selected
locale's own active resource stack, not from a language view merged with
fallback locales.

Every one of those three placeholders must occur at least once; a valid
placeholder may occur more than once. `{{` and `}}` emit literal `{` and `}`
respectively. The parser accepts only the three exact placeholder names.

| Template form | Result |
| --- | --- |
| `{playerName} requests {pingType} {targetName}` | Valid named template. |
| `{{{playerName}}} requests {pingType} {targetName}` | Valid; the player name is surrounded by literal braces. |
| `{playerName} {pingType}` | Invalid because `{targetName}` is missing. |
| `{player} {pingType} {targetName}` | Invalid because `{player}` is unknown. |
| `{playerName} {pingType} {targetName` or a lone `}` | Invalid because an opening or closing brace is unmatched. |

After selecting the applicable common or override template, validate and build
that selected template once. An unknown placeholder, unmatched/isolated brace,
missing required placeholder, or any runtime construction failure falls directly
to legacy `pingforit.chat.pingmsg`; do not try another modern template or a
different locale first.

## Phrase-only color

Only the Ping-Type-specific phrase receives that Ping Type's `textColor`.
Player name, connective wording, and target name retain their normal/default
chat color except for independently retained styling on trusted components; this
does not preserve styling carried by the flattened custom input. For example,
only the equivalent of `ATTENTION` is emphasized in
`Steve requests ATTENTION Zombie`.

Exact phrase/display keys and color values are in the
[fixed catalog](../identity/catalogs.md). Wheel sectors use outline colors under
the [wheel contract](../picking/wheel.md), not this phrase-only text-color rule.

## New-marker feedback and dimension behavior

For a created-marker update, the client runtime first rejects corrupt input and
authoritatively tombstoned marker IDs. For a remaining update, it determines
whether the marker is newly seen from the marker's presence in the **current**
local store before upserting it. Only a newly seen marker is eligible for the
sound and chat hooks. An update to an ID that is still locally known—including
an upsert that refreshes an external locator—does not repeat either hook.

This is current local-store membership, not permanent once-per-ID history:
local marker-record cleanup can end membership. A full store clear also removes
retained tombstones, allowing a later receipt to be newly seen. While retained,
an authoritative-removal tombstone separately suppresses a late create for that
ID. The sound hook is eligible only when a game, level, and player are available
and the marker target dimension matches the current level. The chat hook
deliberately has no target-dimension filter, so a marker recipient can receive a
cross-dimension line subject to the normal client presentation validity checks.
Eligibility attempts do not guarantee an audible sound or a visible chat line.
