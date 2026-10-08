# Press-time capture and target locking

## Ordinary and asynchronous capture

At the initial physical key press, capture the ray and start target resolution.
An ordinary synchronous press immediately freezes ray, target, and resolved
Target Type. An asynchronous path, such as Distant Horizons, starts at that
press edge and completes capture in a later callback, potentially after release.
The target-selection values are copied into the ordinary ray's immutable policy
at capture start; their toggles and raycast meanings are owned by
[selection policy](selection_policy.md).

When the snapshot is ready, resolve its Target Type under the
[catalog matching rules](../identity/catalogs.md). Freeze the resolved Target
and Target Type for the rest of the interaction. Camera motion, target motion,
and another entity entering the crosshair must not retarget or change the
ordinary short-press, Danger, Intent, Content, or Cancel action. Release and
selection do not initiate a new selection ray. The Precise branch is the
confirmed exception: its candidates are captured live from the current camera
ray while that branch is active
([Precise live candidate capture](#precise-live-candidate-capture)).

An ordinary block hit also retains the actual hit face from the press-time
result, and the same-target capture and coordinator path carries that face
forward. The frozen face is read context for ordinary-block inventory access,
owned by
[inventory preview and tracking](../presentation/inventory.md#provider-layer-and-safety);
it is not part of the captured target identity or the marker identity. A miss
result's direction is arbitrary and is ignored: only a concrete block hit
establishes a face, and a location fallback never acquires one. Every
ordinary-block candidate allocated at the same press edge retains its own
actual hit face under these same rules.

## Precise live candidate capture

The native selector's precise branch captures its bounded candidate set live
from the current camera ray while that branch is active, on the configured
[capture period](../../config/client.md#spatial-selector-interaction); entering
the branch starts capture and leaving it pauses capture. Each live cast uses
the hold's frozen
[selection policy](selection_policy.md#raycast-use-and-blacklist-boundary) and
range snapshot, so a settings change applies to the next hold rather than the
running one. Release and selection never initiate another live cast. The live
scan's range and its independence from the ordinary native trace and the
Distant Horizons route are owned by
[capture range](range.md#selector-candidate-supplements). It traverses the
established native and provider pick paths under
[local geometry](local_geometry.md); it defines no separate collision or
display-extent rule.

All candidates of one fixed Target Type compete by nearest actual hit from the
live ray origin. A more specific class consumes its identity first; a generic
class may skip an identity already consumed by a more specific class and
compete for the next nearest. A type's candidate is certified only when the
established native or provider pick path completed its bounded traversal
within the candidate work budget; certification is not coverage of every
registered shape or of block-display/outline eligibility. A scan that cannot
complete disables that type's leaf even when an ordinary target of that type
exists; the leaf never falls back to the ordinary result. While a refresh is
in progress the leaf keeps the last certified
candidate selectable, and a leaf with no previously certified candidate is
disabled. Native categories publish first; the Distant Horizons location
candidate completes independently and a final missing or incomplete result
disables its leaf. Every live capture is bounded and generation-fenced: a late
or stale asynchronous completion never publishes into the live set, the
ordinary capture, or another interaction.

Live candidates follow ordinary capture. An entity candidate uses the
same canonical locator as ordinary capture, including the established
experience-orb runtime-ID and multipart canonicalization rules
([target model](../identity/target_model.md#ordinary-identities-and-lifecycle)).
An external block candidate requires positive capture-local provider
equivalence and never fabricates a server materialization ID. Sable's bounded
transformed-behind discovery is owned by the
[Sable integration](../../integrations/sable.md#supplemental-transformed-behind-discovery).
The location class is derived rather than scanned: it uses the current concrete
hit point when a live native trace has one and otherwise the independently
completing Distant Horizons route. An exact-owned non-hit remains rejected
under [local geometry](local_geometry.md); a candidate scan never revives its
coarse bounds.

## Baseline release and actual wheel opening

At the baseline press, freeze the effective long-press threshold. Its timing
relationship is owned by [long-press timing](../input/long-press.md).
Release and wheel-open outcomes are:

| Interaction state at the release/present boundary | Outcome |
| --- | --- |
| Released before the frozen threshold, with capture ready | Create the captured target using that Target Type's default Ping Type. |
| Released before the frozen threshold, with capture pending | Wait for capture, then create its default Ping Type. |
| Threshold elapsed but no wheel actually opened | Use the same default-create path, including pending-capture waiting. |
| Wheel actually opened | Delegate release to [wheel](wheel.md#radial-release-result). |

Holding beyond the threshold opens a wheel only after capture is ready and a
render/present frame occurs while the key remains held. Elapsed time alone does
not fabricate an opened wheel. [Wheel](wheel.md) owns the actual-open snapshot
and the resulting selection and cancellation behavior. No release outcome
samples the release-time camera or casts a release-time ray.

## Interaction lifecycle aborts

An input reset, detected screen transition, detected level-instance or dimension
discontinuity, or unavailable world or player abandons an active interaction; it
is never interpreted as an ordinary release. In particular, the focus-loss
`KeyMapping.releaseAll` hook aborts before its synthetic key releases are
delivered. A compatibility sequence is discarded under its owner when its
baseline aborts.

An abort invalidates the capture token **before** it clears interaction
ownership, so a late asynchronous completion cannot revive an abandoned action.
It clears pending captured ray/selection state and ping hold state. It creates
neither a ping nor a cancellation, does not clear an unrelated marker store, and
does not retract a packet already handed to the sender. GUI suppression for
target-selection toggles is separate input behavior; see
[selection policy](selection_policy.md#toggle-input-and-attempted-persistence).

## Compatibility interface

Long-press compatibility begins a new baseline under its own sequencing and
virtual-held rules, then invokes this ordinary capture contract. It therefore
does not replace immutable ray/target/type capture, actual-wheel readiness, or
lifecycle token ownership. Rapid and deferred paths, the qualifying local-dispatch
boundary, and compatibility-specific transition behavior are owned by
[long-press compatibility](../input/long-press-compatibility.md).

Coverage and remaining input-lifecycle scenarios are inventoried in
[verification](../../testing/verification.md#capture-wheel-and-cancellation).

## Integration and authority boundaries

Each ray uses one immutable [entity-local geometry](local_geometry.md) owner
snapshot. Exact Create results remain `EntityHitResult`, preserving the existing
Sable/block and Distant Horizons/miss branching. Sable's external-block capture
is described in [its integration contract](../../integrations/sable.md). The
separate capture and server-acceptance limits, including native and optional
integration paths, are owned by [capture range](range.md).

Captured local detail is copied metadata attached only to its matching entity.
It is not a server-authoritative constituent identity. Creation still obeys
[target validation](../authority/target_validation.md). The rationale for these
timing boundaries is [D0005](../../decisions/D0005-press-time-capture.md).
