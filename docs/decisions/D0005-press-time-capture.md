# D0005: Press-time capture

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

Preserve press-time intent across synchronous and asynchronous completion.
[Capture](../architecture/picking/capture.md) owns the sampling, target locking,
release outcomes and actual-wheel-open boundary;
[wheel interaction](../architecture/picking/wheel.md) owns an opened wheel's
timeout and selection behavior.

Allow only the established narrow deferred-capture exception under
[long-press compatibility](../architecture/input/long-press-compatibility.md).
Its sequencing and qualifying-dispatch rules preserve single interaction
ownership rather than introducing a queue of creates.

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

Coverage and pending integration evidence are owned by
[verification](../testing/verification.md#capture-wheel-and-cancellation).
