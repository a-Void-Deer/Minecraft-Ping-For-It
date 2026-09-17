# Target and Ping Type catalogs

## Target Type resolution and fixed order

Target Types are code-defined matchers. When the captured snapshot becomes
available, evaluate every active matcher. Lower numeric priority wins; equal
priorities use earlier declaration order. Pure location is always the lowest-
priority fallback. The resolved Target and Target Type are then frozen for the
interaction; the server independently repeats classification from its own state.

The following declaration order, ordered Ping Type sets and defaults are fixed:

| Target Type | Priority | Kind | Ordered Ping Types | Default |
| --- | ---: | --- | --- | --- |
| `dropped_item` | 100 | entity | `loot, attention, danger` | `loot` |
| `entity` | 200 | entity | `attention, danger, go_to` | `attention` |
| `entity_block` | 250 | block | `attention, destroy, take, request` | `attention` |
| `block` | 300 | block | `attention, go_to, danger` | `attention` |
| `location` | `Integer.MAX_VALUE` | location | `go_to, attention, danger` | `go_to` |

Definitions and lookup may use maps, but resolution and presentation must use
explicit ordered lists, never unordered map iteration, accidental registration
order or loader-specific ordering. Adding a code definition must not require
ad-hoc changes throughout input, networking, rendering or chat. Target/Ping
Types have no config/datapack/user-definition system or public plugin/scripting
layer in this iteration.

## Block classification

`entity_block` means the registered block implements the Minecraft 1.21.1
`EntityBlock` interface. The same built-in matcher is used on client and server;
classification does not require a live `BlockEntity`. A native
BlockEntityRenderer attempt separately requires a valid live BlockEntity and
renderer. Unknown or absent registry/classification data fails soft to generic
`block`. The virtual `minecraft:block_display` is an entity target and can
never be an `entity_block`.

Absent optional registrations are ignored; partially present content continues
to match, while a group with no valid concrete content is inactive and
non-matching. See [compatibility](../compatibility.md) and
[presentation subjects](../rendering/presentation_subjects.md) for the distinct
render-target classification of owner/master subjects.

## Ping Type values

The following declaration order, phrase/display keys, 24-bit RGB colors and
empty icon values are established values and must not be changed without a
product decision:

| Ping Type | Phrase key | Display key | Outline | Text | Icon |
| --- | --- | --- | ---: | ---: | --- |
| `attention` | `pingforit.ping_type.attention.phrase` | `pingforit.ping_type.attention` | `0xFFC247` | `0xFFAA00` | empty/default |
| `danger` | `pingforit.ping_type.danger.phrase` | `pingforit.ping_type.danger` | `0xFF4D4D` | `0xFF5555` | empty/default |
| `go_to` | `pingforit.ping_type.go_to.phrase` | `pingforit.ping_type.go_to` | `0x4DB8FF` | `0x55FFFF` | empty/default |
| `loot` | `pingforit.ping_type.loot.phrase` | `pingforit.ping_type.loot` | `0x52D273` | `0x55FF55` | empty/default |
| `destroy` | `pingforit.ping_type.destroy.phrase` | `pingforit.ping_type.destroy` | `0xE66BDD` | `0xF0A0EA` | empty/default |
| `take` | `pingforit.ping_type.take.phrase` | `pingforit.ping_type.take` | `0x52D273` | `0x55FF55` | empty/default |
| `request` | `pingforit.ping_type.request.phrase` | `pingforit.ping_type.request` | `0x8C8CFF` | `0xB8B8FF` | empty/default |

Every built-in row stores an empty icon ID. Rendering resolves that value to
the established default icon and tint behavior. Ping Type controls chat,
outline color, text emphasis, wheel presentation and optional icon; exact
[chat composition](../rendering/names_chat.md) and
[wheel behavior](../picking/wheel.md) remain separate contracts.
