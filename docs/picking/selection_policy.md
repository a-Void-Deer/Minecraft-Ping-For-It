# Target-selection policy

This topic owns the three persistent client settings that determine how a
press-time ray selects blocks, fluids, and ignored entity candidates. It does
not define block-outline or native-glow display eligibility.

## Stored values and default bindings

| JSON key | Default | Default key binding | Selection effect |
| --- | ---: | --- | --- |
| `passThroughTransparentBlocks` | `false` | Left Shift | Select block shapes with `ClipContext.Block.OUTLINE` when false and `ClipContext.Block.VISUAL` when true. |
| `markBlacklistedTargets` | `false` | Left Control | Exclude or include candidates matched by the entity-selection blacklist. |
| `markFluids` | `false` | Left Alt | Select fluids with `ClipContext.Fluid.NONE` when false and `ClipContext.Fluid.ANY` when true. |

The names describe the established UI intent, not a general opacity classifier:
`VISUAL` and `OUTLINE` are Minecraft shape strategies. The exact native
block/fluid competition and represented-state rules for Create contraptions are
owned by the [Create target-selection table](../integrations/create-contraption-raycast.md#existing-target-selection-settings).

## Toggle input and attempted persistence

These are toggles, not held modifiers. A matching physical press edge flips the
in-memory value; the matching physical release makes that raw key eligible for
another toggle. GLFW repeat clicks while the key remains held are ignored.

A press received while any client screen is open is suppressed through its
matching release and does not flip a setting. In particular, a repeat received
after that screen closes cannot become a new press edge. The bindings in the
table are defaults; the behavior follows the matching configured key mapping.

After a claimed toggle, the client calls `saveSafely`. That is a persistence
attempt, not an unconditional disk-write guarantee: an existing invalid-file or
future-version preservation lock can skip it, and serialization or I/O can fail.
The in-memory value has already changed in either case, so this behavior must
not be documented as a guaranteed durable save.

## Raycast use and blacklist boundary

At ordinary capture start, the three values are copied into one immutable
raycast policy. That policy selects the block and fluid modes in the table and
decides whether ignored entity candidates join nearest-hit competition. The
ordinary press-time and narrow deferred-capture sampling boundaries are owned by
[capture](capture.md): a deferred compatibility press stores only its ray and
reads this policy when its later capture actually starts.

Spectator entities are always excluded, including when
`markBlacklistedTargets` is true. The shared entity blacklist starts with a
built-in entity-type rule for `simulated:honey_glue`; that rule is present in
the global client raycast from initialization, not only after an optional
integration registers. Additional optional-integration predicates can join the
same blacklist. It is applied to every live entity candidate before either
ordinary entity intersection or an entity-local geometry owner's narrow-phase
trace. Setting `markBlacklistedTargets` to true includes these ignored entity
candidates, but does not affect the spectator rule.

This entity-selection blacklist is distinct from `blockShapeBlacklist` in the
client configuration. `blockShapeBlacklist`, together with the block display
whitelist, controls client-local native-glow/outline attempt eligibility; it is
not consulted while selecting a target. Conversely,
`markBlacklistedTargets` does not edit or evaluate either block-display list.
