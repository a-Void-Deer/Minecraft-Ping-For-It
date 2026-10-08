# Target-selection policy

This topic owns the three persistent client settings that determine how a
press-time ray selects blocks, fluids, and ignored entity candidates. It does
not define block-outline or native-glow display eligibility.

## Selection controls and bindings

| Control | Default key binding | Selection effect |
| --- | --- | --- |
| Pass through transparent blocks | Left Shift | Select block shapes with `ClipContext.Block.OUTLINE` when false and `ClipContext.Block.VISUAL` when true. |
| Mark blacklisted targets | Left Control | Exclude or include candidates matched by the entity-selection blacklist. |
| Mark fluids | Left Alt | Select fluids with `ClipContext.Fluid.NONE` when false and `ClipContext.Fluid.ANY` when true. |

The persisted JSON-key catalogue is owned by
[client configuration](../../config/client.md#target-selection-and-entity-block-presentation).

The names describe the established UI intent, not a general opacity classifier:
`VISUAL` and `OUTLINE` are Minecraft shape strategies. `VISUAL` follows each
block's implementation rather than making every translucent model pass-through;
`OUTLINE` can select decorations with an empty collision shape. Native
block/fluid competition is owned by [local geometry](local_geometry.md#distance-and-native-local-shapes).
Create's represented block/fluid-state rules are owned by the
[Create supplement](../../integrations/create-contraption-raycast.md#existing-target-selection-settings).

## Toggle input and attempted persistence

These are toggles, not held modifiers. A matching physical press edge flips the
in-memory value; the matching physical release makes that raw key eligible for
another toggle. GLFW repeat clicks while the key remains held are ignored.

A press received while any client screen is open is suppressed through its
matching release and does not flip a setting. In particular, a repeat received
after that screen closes cannot become a new press edge. The bindings in the
table are defaults; the behavior follows the matching configured key mapping.
This toggle-specific GUI suppression is separate from the active ping
interaction's screen-transition abort rule in [capture](capture.md#interaction-lifecycle-aborts).

After a claimed toggle, the client attempts a guarded save. The in-memory value
has already changed, so this is not a guarantee of durable persistence. Handler
versioning, recovery, and save-protection behavior are owned by
[configuration revisioning](../config/revisioning.md).

## Raycast use and blacklist boundary

At hold start, the three values are copied into one immutable raycast policy
for that hold. That policy selects the block and fluid modes in the table and
decides whether ignored entity candidates join nearest-hit competition. The
ordinary press-time sampling boundary is owned by [capture](capture.md). The
same immutable policy is consumed by the ordinary trace, by the Precise
branch's live casts while the hold is open, and by provider supplements; the
live-capture boundary is owned by
[capture](capture.md#precise-live-candidate-capture). A deferred compatibility
press stores only its ray and reads this policy when its later capture starts;
that sequence is owned by
[long-press compatibility](../input/long-press-compatibility.md).

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

## Selector settings branch

The native selector's settings branch exposes these same three toggles as
release-committed entries. Committing one updates the same in-memory setting
that a later capture reads; the running interaction already copied its
immutable raycast policy at hold start and is unaffected. The selector
branch's presentation and release behavior are owned by
[wheel](wheel.md#headless-spatial-menu-model).
