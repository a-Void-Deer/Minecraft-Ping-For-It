# Long-press compatibility

This focused architecture contract owns optional rapid-click and
pending-first-capture compatibility. It has two one-slot interaction paths, not
a queue of creates. Its persisted fields are catalogued in
[client configuration](../../config/client.md#press-wheel-and-cancellation-interaction),
and their timing relation is owned by [long-press timing](long-press.md).

> **Current implementation-conformance gaps.** The create-only dispatch gate
> for both paths has known gaps. Their concrete defect explanation and pending
> regression matrix are maintained only in
> [verification](../../testing/verification.md#known-automated-gaps).

## Observed mode and slice

Compatibility mode and effective slice are observed at relevant raw edges and
render frames. An observed enabled-to-disabled transition aborts the baseline
interaction and compatibility sequence. Enabling does not abort an interaction.
General reset, screen, world, and token-abort rules remain owned by
[capture](../picking/capture.md#interaction-lifecycle-aborts).

Both paths require a successful local dispatch outcome for the preceding default
`CreatePing`: it passed the courtesy limiter, was recorded as dispatched, and
was handed to the sender. This is not server acceptance. The create-only gate
and its rejected-request consequences are owned by
[rate policy](../config/rate-limit.md#create-only-dispatch-boundary).

## Rapid-click virtual hold

After an ordinary first interaction reaches that qualifying outcome, a second
physical press within the compatibility slice can seed a rapid-click candidate.
It retains the first press timestamp for virtual duration and freezes the second
press ray for a new capture. Further adjacent press edges within the slice extend
the candidate window. Releases while it exists are swallowed, while render frames
advance the baseline as virtually held. This applies to both a synchronous first
interaction and an asynchronous first interaction after its first create reaches
the dispatch boundary.

The baseline begins with that first timestamp and second ray. It samples range
and selection policy when its capture starts, then follows ordinary
[capture](../picking/capture.md). A later raw edge or frame strictly beyond
the slice ends adjacency; equality remains eligible. At termination, a wheel
that actually opened and remains open follows ordinary
[wheel release](../picking/wheel.md#radial-release-result). If it never
opened, the still-pressed baseline aborts; if it already closed, the candidate
is discarded. Neither outcome emits another default tap. Timeout, lifecycle
abort, or observed disabling can end the sequence sooner.

## Pending-first-capture deferred fresh press

While the original interaction remains pending, a later press outside its
rapid-click slice can become a deferred fresh press. It stores only the
immutable ray and whether it was released; it does not raycast a target or freeze
range and selection policy at that raw edge.

Only after the preceding default `CreatePing` reaches the qualifying local
dispatch outcome can a new ordinary capture start from the stored ray. Its
remembered release is applied to that new interaction. A missing ray, or a first
interaction ending without a qualifying dispatched create, discards the deferred
press. The new capture reads current range and selection policy when it starts
and may complete synchronously or asynchronously under
[capture](../picking/capture.md). Compatibility does not defer every press or
synthesize a separate duration policy.
