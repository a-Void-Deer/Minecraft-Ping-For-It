# Press-time capture and target locking

## Ordinary and asynchronous capture

At the initial physical key press, capture the ray and start target resolution.
An ordinary synchronous press immediately freezes ray, target and resolved
Target Type. An asynchronous path, such as Distant Horizons, starts at that
press edge and completes capture in a later callback, potentially after release.
The target-selection values are copied into the ordinary ray's immutable policy
at capture start; their defaults, toggles and raycast meanings are owned by
[selection policy](selection_policy.md).

When the snapshot is ready, evaluate matchers sequentially in the catalog's
ascending numeric-priority order, retaining declaration order for equal
priorities. Skip an absent binding or an inactive matcher; the **first**
`MATCH` ends evaluation and supplies the frozen Target Type. This is not an
all-active-match evaluation. The [catalog priority/declaration rules](../identity/catalogs.md)
own the catalog itself. Freeze the resolved Target and Target Type for the rest
of the interaction. Camera motion, target motion and another entity entering
the crosshair must not retarget or change the wheel. Release, selection and
timeout do not initiate a new selection ray.

Release before the configured long-press threshold creates the captured target
with that Target Type's default Ping Type once capture is available. If capture
is still pending, wait for completion before that default create. Never sample
the release-time camera or cast a release-time ray.

## Actual wheel-open boundary

Holding beyond the threshold opens a wheel only after capture is ready and a
render/present frame occurs while the key is still held. If the wheel never
actually opened, release uses the default Ping Type even when elapsed hold time
exceeded the threshold. Pending capture still waits before that default create.
Timeout begins at actual wheel opening, not key down. See
[wheel and cancellation](wheel.md) and [client defaults](../config/client.md).

## Long-press compatibility paths

Long-press compatibility is disabled by default. It has two deliberately
separate one-slot paths; neither is a queue of creates.
The compatibility controller owns this sequencing and virtual key state only.
The baseline interaction still decides actual wheel opening on a present frame
from its capture-ready state and virtual held state; candidate termination uses
that current baseline phase, not elapsed time alone.

### Rapid-click virtual hold

After an ordinary first interaction has dispatched a real default `CreatePing`,
a second physical press within the configured compatibility slice can seed a
rapid-click candidate. The candidate retains the **first** press timestamp for
its virtual interaction duration and freezes the **second** press ray for the
new capture. Further adjacent press edges within the slice extend the candidate
window. Physical releases while the candidate exists are swallowed, while render
frames continue to advance the baseline as virtually held. This applies to a
normal first interaction as well as an asynchronous first interaction once that
first create has crossed its dispatch boundary; it is not restricted to a
pending first capture.

The baseline starts with the first timestamp and second ray, so range and
[selection policy](selection_policy.md) are sampled when that baseline capture
starts. The ordinary capture contract then applies to it. Physical release does
not itself end the candidate: later frames can continue its virtual hold. At
candidate termination, the ordinary wheel-release path is used only when a
present frame actually opened the wheel **and it remains open**; it then commits
the current frozen selection (or ordinary wheel cancellation). If it never
actually opened, termination aborts the still-pressed baseline; if it already
closed, the candidate is simply discarded. Neither outcome emits an additional
default tap. A later raw edge or a frame strictly beyond the slice ends the
adjacency window; equality remains eligible.
Mode/lifecycle aborts and an ordinary wheel timeout can clear the sequence
sooner.

### Pending-first-capture deferred fresh press

While the original interaction is still pending, a later press outside its
rapid-click slice is instead a deferred fresh press. It freezes only origin and
direction at that raw edge: it does not raycast a target or freeze range and
selection settings. Once the preceding real `CreatePing` action reaches the
dispatch boundary, a new ordinary capture may start with that stored ray. A
physical release that occurred while deferred is remembered and applied to that
new interaction. If the first interaction ends without a dispatched create, or
the deferred ray could not be captured, the deferred press is discarded.

**Current implementation gap.** This remains the required create-only boundary.
`LongPressCompatibilityController` can currently start a deferred fresh capture
after a non-`CreatePing` result such as `TargetGone`, because its deferred-action
handling is not gated on a real create crossing dispatch. That reachable behavior
is not an alternative any-action rule; it is recorded with its uncovered
regression scenario in [known automated gaps](../testing/verification.md#known-automated-gaps).

The deferred capture reads the then-current range and
[selection policy](selection_policy.md) when it starts, and may complete
synchronously or asynchronously. Thus compatibility does not defer every
press, replay throttled creates, or synthesize a separate duration policy.

The pending-first-capture deferred press must not be confused with a queue of
rate-throttled committed creates: [courtesy-throttled creates](../config/rate_limit.md)
are dropped and never recorded as dispatched.

## Integration and authority boundaries

Each ray uses one immutable [entity-local geometry](local_geometry.md) owner
snapshot. Exact Create results remain `EntityHitResult`, preserving the
existing Sable/block and Distant Horizons/miss branching. Sable's external-block
capture is described in [its integration contract](../integrations/sable.md).
The separate capture and server-acceptance limits, including the native and
optional integration paths, are owned by [capture range](range.md).

Captured local detail is copied metadata attached only to its matching entity.
It is not a server-authoritative constituent identity. Creation still obeys
[target validation](../authority/target_validation.md). The rationale for these
timing boundaries is [D0005](../decisions/D0005-press-time-capture.md).
