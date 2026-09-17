# Press-time capture and target locking

## Ordinary and asynchronous capture

At the initial physical key press, capture the ray and start target resolution.
An ordinary synchronous press immediately freezes ray, target and resolved
Target Type. An asynchronous path, such as Distant Horizons, starts at that
press edge and completes capture in a later callback, potentially after release.

When the snapshot is ready, evaluate every active matcher using the
[catalog priority/declaration rules](../identity/catalogs.md). Freeze the resolved
Target and Target Type for the rest of the interaction. Camera motion, target
motion and another entity entering the crosshair must not retarget or change
the wheel. Release, selection and timeout do not initiate a new selection ray.

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

## Narrow deferred long-press compatibility path

Long-press compatibility is disabled by default. Only while a first capture is
pending may a second physical press be queued. That deferred press freezes
only its origin and direction: it does not yet raycast a target or freeze range
and selection settings. Once the preceding real `CreatePing` action reaches the
dispatch boundary, a new capture may start using that stored ray. If the first
interaction ends without such an action, the deferred press is discarded.

The deferred capture reads the then-current range and selection policy when it
starts, and may complete synchronously or asynchronously. This is not a policy
to defer every compatibility press. Rapid-click virtual-hold behavior remains
owned by `LongPressCompatibilityController`; no separate synthetic duration
policy is introduced.

The capture queue above must not be confused with a queue of rate-throttled
committed creates: [courtesy-throttled creates](../config/rate_limit.md) are
dropped and never recorded as dispatched.

## Integration and authority boundaries

Each ray uses one immutable [entity-local geometry](local_geometry.md) owner
snapshot. Exact Create results remain `EntityHitResult`, preserving the
existing Sable/block and Distant Horizons/miss branching. Sable's external-block
capture is described in [its integration contract](../integrations/sable.md).

Captured local detail is copied metadata attached only to its matching entity.
It is not a server-authoritative constituent identity. Creation still obeys
[target validation](../authority/target_validation.md). The rationale for these
timing boundaries is [D0005](../decisions/D0005-press-time-capture.md).
