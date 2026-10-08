# D0009: Precise live candidate capture

## Status

Confirmed product decision represented by the linked topic contracts.

## Decision

The Precise branch captures its candidate set live from the current camera ray
while that branch is active, on the persisted
[precise capture period](../config/client.md#spatial-selector-interaction),
instead of the press-frozen ordinary ray. Ordinary short-press, Danger, Intent,
Content, and Cancel actions keep their press-time freeze; only Precise is live.

All candidates of one fixed Target Type compete by nearest actual hit from the
live ray origin. A scan that cannot complete disables that type's leaf even when
an ordinary result exists. Entering the Precise branch starts capture and
leaving it pauses capture. A refresh in progress keeps the last certified
candidate selectable while the replacement capture is pending; a completed
result owns availability. Every capture is bounded and generation-fenced, so a
late or stale asynchronous completion never changes the live set, the ordinary
capture, or another interaction, and release consumes the last actually painted
selectable candidate version without initiating a new cast.

[Capture](../architecture/picking/capture.md#precise-live-candidate-capture)
owns the live capture, competition, certification and scheduling rules;
[wheel interaction](../architecture/picking/wheel.md#precise) owns leaf
enablement, focus, pending and release behavior; the period key is catalogued
in [client configuration](../config/client.md#spatial-selector-interaction).

## Rationale

Precise selection is an explicit aim-and-pick act: the player is choosing the
exact thing under the crosshair, so its candidates must follow the current
camera ray rather than the press edge. The ordinary actions keep press-time
capture because they must preserve the intent captured when the interaction
started.

## Why not other approaches

- Do not keep the press-frozen candidate set for Precise: it would stop
  following the crosshair and defeat the branch's purpose.
- Do not cast on release: release must commit a version the player actually
  saw, not a target that appeared afterwards.
- Do not let an incomplete scan fall back to the ordinary target: an incomplete
  same-type scan is a disabled leaf, not a different action.
- Do not let a pending refresh clear a previously certified candidate: it stays
  selectable while the replacement capture is pending, and the completed result
  owns availability.
- Do not let a late asynchronous completion publish into the live set or the
  ordinary capture: it is fenced to its own capture generation.

## Consequences

Capture state must keep a live, per-type candidate set with certification and
scheduling bounded by the configured period, plus a generation fence for
asynchronous completions. Held settings remain frozen per hold, so a change
applies on the next hold and not to the running one. Ordinary press-frozen
behavior is unchanged.

## Related docs

[Capture](../architecture/picking/capture.md),
[wheel](../architecture/picking/wheel.md),
[range](../architecture/picking/range.md),
[selection policy](../architecture/picking/selection_policy.md),
[client configuration](../config/client.md),
[D0005](D0005-press-time-capture.md), and
[verification](../testing/verification.md).
