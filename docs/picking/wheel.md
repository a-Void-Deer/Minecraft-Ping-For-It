# Wheel interaction and cancellation

## Opening, selection and timeout

The current configurable defaults are a 300 ms long-press threshold, 5000 ms
maximum wheel-open duration and 5-degree cancellation cone half-angle. Their
configuration owner is [client settings](../config/client.md); these are defaults,
not unresolved or immutable product constants.

Opening follows [capture readiness and the present-frame boundary](capture.md).
Every subsequent action operates on the captured context. The wheel center is
always **Cancel Marker**, including one-item Ping Type sets and the location
fallback. Each sector has inner and outer borders/arcs in its Ping Type's
outline color. The choices and default come from the resolved Target Type's
[ordered catalog](../identity/catalogs.md).

Exceeding the configured timeout closes an actually open wheel with no ping,
no cancellation and no timeout error. Timeout is measured from actual opening.
If the wheel has not opened, releasing uses the default Ping Type rather than
fabricating a wheel selection from elapsed duration.

## Cancel Marker selection

The client-side candidate collection and the server's active cancellable set are
deliberately different. On a wheel release that can select the center action,
the client collects every **stored** marker whose owner is the local player and
whose target dimension is the current dimension. `markersOwnedInDimension` does
not filter for visual activity or for `SYNCHRONIZED` versus `STALE`; a stored
candidate can therefore be stale or past its local display deadline. This local
collection is only a selection aid and is not an assertion that the marker is
still server-active or removable.

1. Keep the cone origin and direction from the press-time ray. Candidates must
   be within the configured half-angle (5 degrees by default, including the
   boundary); a zero-distance candidate is eligible.
2. Resolve each candidate's current position without starting a new target ray:
   a live entity uses its current rendered top-center; an external block uses a
   currently resolved provider position or its authoritative anchor; other
   markers use a matching presentation position when one exists and otherwise
   their authoritative anchor.
3. Choose the nearest candidate by world-space distance. An exact distance tie
   chooses the larger Marker ID. If no candidate is eligible, cancellation is a
   silent no-op.
4. Dispatch one removal request for that selected Marker ID. The server alone
   checks active status and ownership; on success it synchronizes the removal
   and resulting winner changes. Another player's marker can never be cancelled.

The selected stored candidate does not authorize a new target-selection ray or
make it server-cancellable. A stale, expired, missing, or unauthorized nearest
candidate can be rejected by the server; that rejection does not make the client
retry the action with a farther candidate. Such removals are silent
no-ops/rejections under [target validation](../authority/target_validation.md).
Removing a valid winner triggers [server winner recomputation](../authority/ping_winner.md).
