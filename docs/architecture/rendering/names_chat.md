# Localized target names and chat

## Route-independent target names

Names and chat are required for block and entity pings regardless of whether an
outline uses BER, baked-model, optional geometry, or VoxelShape fallback. A
render route may fail or change without removing the authoritative target name.
Server-derived name data follows
[authoritative validation](../authority/target_validation.md). On the
negotiated presentation route the name travels as the Basic
`minecraft:target.name` value of the atomic initial; its retention and receive
policy are owned by
[presentation snapshot](../presentation/presentation_snapshot.md), while the
composition rules below remain the name contract.

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
flattened to its plain text before composition. Its text remains, but its
incoming colors, styles (including italic), and events are not inherited. The
trusted localized base component is appended unchanged,
so any independent retained styling refers to that base component rather than
to the flattened custom input.

## Translation keys and template selection

| Purpose | Key |
| --- | --- |
| Common chat template | `pingforit.chat.pingmsg.template` |
| Per-Ping-Type override | `pingforit.chat.<id>.template.override` |
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

The content message family is a separate localized template family whose exact
resource keys are implementation-owned and are not part of the whole-message key
set above. The whole-message placeholders, per-Ping-Type override, and legacy
fallback above remain unchanged for ordinary receipt lines.

## Phrase-only color

Only the Ping-Type-specific phrase receives that Ping Type's `textColor`.
Player name, connective wording, and target name retain their normal/default
chat color except for independently retained styling on trusted components; this
does not preserve styling carried by the flattened custom input. For example,
only the equivalent of `ATTENTION` is emphasized in
`Steve requests ATTENTION Zombie`.

The content message family applies the same rule to its selected annotation
phrase: only that phrase receives its Ping Type's `textColor`, while the author,
target, field, value, and count keep their default/trusted styling. The whole
property group is never colored as a unit.

Exact phrase/display keys and color values are in the
[fixed catalog](../identity/catalogs.md). Wheel sectors use outline colors under
the [wheel contract](../picking/wheel.md), not this phrase-only text-color rule.

## Content message family

A marker created from a wheel content selection — a property selection or an
inventory item or fluid selection — replaces its ordinary first-receipt chat line with a
content message. The message retains the author and reports the composed target
name and, for each selected entry, that entry's own annotation phrase (the
property's or resource's annotation Ping Type phrase) and selected content: a
property field and value, or an inventory resource and quantity. Each explicit
property selection is annotated independently, so one message preserves every
selected entry's own phrase and action rather than applying one phrase to the
whole set. The whole-marker Ping Type remains marker identity, and the ordinary
whole-message formats are unchanged.

Content messages come from full localized templates with their connectives and
quoting, never from concatenated language fragments. For a single selected
entry — a single explicit property selection or a single-resource
inventory case — the common form is
`author: 请求 {contentType} {targetName} 的 {content}`, where `author` is the
retained player name, the request and possessive connectives belong to the
localized template, `{contentType}` is that entry's annotation phrase, and
`{content}` is that entry's field/value or resource/quantity. Untrusted plain text,
such as a custom name, is quoted as regular text; the quoting belongs to the
localized template. The whole-marker main Ping Type phrase is not prepended:
each annotated entry's phrase appears exactly once for that entry, so a chest
content ping does not gain a redundant default `注意` before
`请求 拿走 箱子 的 ...`.

A create with several explicit property selections produces one content message
listing the complete selection set ([receipt content
descriptor](../presentation/presentation_snapshot.md#receipt-content-descriptor))
in the marker's deterministic selection order; a partial list is never shown.
Each listed entry keeps its own annotation phrase and value, and each phrase
receives its own [phrase-only color](#phrase-only-color); the localized
template owns the list separators and grammar, and the whole-marker main Ping
Type phrase is not prepended or repeated for the list.

The composed target name follows the name rules above. A separate plain
`custom_name` target field is owned by the
[presentation snapshot](../presentation/presentation_snapshot.md) contract; the
composed-name rules above remain this document's name contract. Server-provided
display data is decoded to human-readable localized text and raw JSON never
reaches chat. Item content uses the localized exact variant from the
server-bounded display data or the base fallback; counts stay exact even when
long, an unknown count is not zero, and the component-fallback presentation
quality owned by
[inventory](../presentation/inventory.md#item-variants-and-component-fallback)
is preserved.

Fluid content uses the
[base-name resolution and display-refusal rules](../presentation/inventory.md#fluid-display-projection)
and, when present, the server-projected plain custom name in the same quoted
custom/base composition. That custom name is literal display text, never another
rich-text decoding entrance; coincident names do not merge variants. Fluid
quantities and their approximate/sub-millibucket qualifiers follow the same
[formatter contract](../presentation/inventory.md#quantity-presentation) as the
list and HUD, independently of All variants identity quality.

## New-marker feedback and dimension behavior

Only created-marker updates accepted under
[network protocol](../network/protocol.md), the negotiated
[presentation snapshot](../presentation/presentation_snapshot.md) receive
policy, and [client marker state](../markers/client-state.md) reach receipt
evaluation. The chat target name is that marker's receive-authorized Basic name;
an absent or denied name uses the unknown-name form instead of any superseded
marker packet.
Before upserting an accepted update, the client checks whether its ID is absent
from the **current** local store. Only such a newly seen marker is eligible for the
sound and chat hooks. An update to an ID that is still locally known—including
an upsert that refreshes an external locator—does not repeat either hook.

This is current local-store membership, not permanent once-per-ID history:
local marker-record cleanup can end membership, and a store clear can allow a
later accepted receipt to be newly seen. Record retention, tombstones and clear
behavior are owned by [client marker state](../markers/client-state.md).
A newly seen marker created with a content selection keeps this membership
eligibility; its chat hook is the content message family instead of the
ordinary immediate line, under the
[content receipt lifecycle](#content-receipt-lifecycle).
The sound hook is eligible only when a game, level, and player are available
and the marker target dimension matches the current level. The chat hook
deliberately has no target-dimension filter, so a marker recipient can receive a
cross-dimension line subject to the normal client presentation validity checks.
Eligibility attempts do not guarantee an audible sound or a visible chat line.

## Content receipt lifecycle

An ordinary newly seen marker keeps the immediate ordinary receipt line. A
content marker replaces it with the content message family and derives its
selection kind from the accepted server-authorized atomic initial, never from a
preview observation or a client-uploaded claim.

A property content marker waits for its complete content and emits exactly one
message when every selected property value has arrived. A single-resource
inventory marker does not wait: its atomic initial carries one bounded creation
snapshot, and the client emits exactly one content message from that snapshot on
that new local membership. The sent line uses the snapshot's exact amount and
authorized display; later inventory tracking updates, status changes and stream
fragments never add or revise chat, and there is no ordinary-line fallback. For
example, a creation snapshot of 5 remains the chatted value while the marker's
live display later reaches 9.

The inventory message appends the localized uncertainty notation when the
Preview cut was not complete or the snapshot's quantity state was independently
uncertain. A complete Preview is not marked uncertain solely because the first
Tracking scan has not completed.

A pending property receipt is bounded client state. It exists only while its
marker is a current live member of the local store under the current
presentation epoch/view; it holds no world handle, starts no second capture, and
creates no audience, lease, or other server state.

A pending property receipt consumes current authorized Basic and non-Basic
section values. A stale or superseded value or a mask-excluded field never
completes it. The inventory snapshot is already part of the accepted atomic
initial and is not a pending input. A denied selection suppresses the whole
message without disclosing the denied reference, and a denied target-name input
cancels the pending receipt; neither case shows a partial line, and the ordinary
line's unknown-name form is unchanged.

A pending property receipt is cancelled while its complete content is still
missing by a presentation `RESET` or other authorization revocation, an
authoritative marker removal ([marker lifecycle](../authority/marker_lifecycle.md))
including `EXPIRED`, local eviction, or disconnect. A sent inventory message is
immutable in chat history across those events and is never revised by later
Tracking data. A same-ID refresh or replay cannot restart a pending receipt or
repeat a resolved one; only a genuinely new local membership follows the
original new-marker rule. A locally elapsed visual deadline is not server
expiry and does not by itself cancel or complete the receipt. Coverage and
pending runtime evidence for live chat delivery and logging are owned by
[testing and verification](../../testing/verification/presentation.md#content-receipt-chat-and-pending-completion).
