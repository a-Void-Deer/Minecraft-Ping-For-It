# D0005: Press-time capture

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

At the initial physical press, freeze the ray and start capture. The resolved
target and Target Type become frozen when the capture snapshot is ready. Async
completion, including Distant Horizons, completes that press-time capture and
does not sample the release camera. A short release creates the captured target
with its default Ping Type once capture is ready.

The wheel opens only after capture is ready and a present/render frame observes
the key still held. Its timeout begins at actual opening. If it never actually
opens, release uses the default Ping Type even when the threshold elapsed.

With long-press compatibility enabled, only a second press occurring while the
first capture is pending may be deferred. That press freezes only origin and
direction. After the first real `CreatePing` crosses the dispatch boundary, the
new capture starts and reads current range and selection policy. A deferred
press is not a queue of throttled creates.

## Rationale

Press-time ray capture preserves the user's input intent across camera and
target movement. It also makes synchronous and asynchronous capture obey the
same sampling rule. Delaying only the narrow compatibility capture prevents
parallel interaction ownership while allowing the established compatibility
sequence. Tying wheel timeout to actual presentation avoids consuming a UI
duration before the wheel exists.

## Why not other approaches

- Do not raycast on release or wheel movement: that can retarget the action.
- Do not let an async callback read the current camera: completion timing would
  change the captured intent.
- Do not start a second capture while the first is pending: two interactions
  would compete for ownership and dispatch.
- Do not freeze deferred range/policy at the raw second press: those settings
  are read when the deferred capture actually starts.
- Do not treat elapsed threshold as an opened wheel: opening requires a ready
  capture and a held present frame.

## Consequences

Capture state must retain a token, frozen ray and asynchronous completion
ownership. Release, wheel selection, cancellation and timeout consume that
context and never initiate a new selection ray. A stale callback must be
ignored or abandon its exact interaction without affecting a newer one.

## Related docs

[Capture](../architecture/picking/capture.md),
[long-press timing](../architecture/input/long-press.md),
[long-press compatibility](../architecture/input/long-press-compatibility.md),
[wheel](../architecture/picking/wheel.md), [target model](../architecture/identity/target_model.md), and
[server authority](D0004-server-authority.md).

Focused tests named by the current coverage include
`LongPressCompatibilityControllerTest`, `PingInteractionStateMachineTest`,
`PingCaptureCoordinatorTest`, `TargetSnapshotTest`,
`TargetSnapshotBlockClassificationTest`, and
`MinecraftTargetSnapshotFactoryDetailedTest`. They document deferred-ray,
capture-token, actual-wheel-open and frozen-context boundaries. Test existence
and a test run remain separate evidence claims.
