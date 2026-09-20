# Press-time capture and target locking

## Ordinary and asynchronous capture

At the initial physical key press, capture the ray and start target resolution.
An ordinary synchronous press immediately freezes ray, target, and resolved
Target Type. An asynchronous path, such as Distant Horizons, starts at that
press edge and completes capture in a later callback, potentially after release.
The target-selection values are copied into the ordinary ray's immutable policy
at capture start; their toggles and raycast meanings are owned by
[selection policy](selection_policy.md).

When the snapshot is ready, evaluate matchers sequentially in the catalog's
ascending numeric-priority order, retaining declaration order for equal
priorities. Skip an absent binding or inactive matcher; the **first** `MATCH`
ends evaluation and supplies the frozen Target Type. This is not an
all-active-match evaluation. The [catalog priority/declaration rules](../identity/catalogs.md)
own the catalog itself. Freeze the resolved Target and Target Type for the rest
of the interaction. Camera motion, target motion, and another entity entering
the crosshair must not retarget or change the wheel. Release, selection, and
timeout do not initiate a new selection ray.

## Baseline release and actual wheel opening

At the baseline press, freeze the effective long-press threshold. Its timing
relationship is owned by [long-press timing](../architecture/input/long-press.md).
Release and wheel-open outcomes are:

| Interaction state at the release/present boundary | Outcome |
| --- | --- |
| Released before the frozen threshold, with capture ready | Create the captured target using that Target Type's default Ping Type. |
| Released before the frozen threshold, with capture pending | Wait for capture, then create its default Ping Type. |
| Threshold elapsed but no wheel actually opened | Use the same default-create path, including pending-capture waiting. |
| Wheel actually opened | Delegate release to [wheel](wheel.md#radial-release-result). |

Holding beyond the threshold opens a wheel only after capture is ready and a
render/present frame occurs while the key remains held. Elapsed time alone does
not fabricate an opened wheel. [Wheel](wheel.md) owns timeout snapshot timing,
its value, and its resulting close behavior. No release outcome samples the
release-time camera or casts a release-time ray.

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
[long-press compatibility](../architecture/input/long-press-compatibility.md).

Focused state-machine seams cover lifecycle abort and stale capture-token
handling. They do not exercise the real focus-loss hook, screen-transition
callback, or loader/gameplay input lifecycle; those remain distinct integration
evidence boundaries in [verification](../testing/verification.md).

## Integration and authority boundaries

Each ray uses one immutable [entity-local geometry](local_geometry.md) owner
snapshot. Exact Create results remain `EntityHitResult`, preserving the existing
Sable/block and Distant Horizons/miss branching. Sable's external-block capture
is described in [its integration contract](../integrations/sable.md). The
separate capture and server-acceptance limits, including native and optional
integration paths, are owned by [capture range](range.md).

Captured local detail is copied metadata attached only to its matching entity.
It is not a server-authoritative constituent identity. Creation still obeys
[target validation](../architecture/authority/target_validation.md). The rationale for these
timing boundaries is [D0005](../decisions/D0005-press-time-capture.md).
