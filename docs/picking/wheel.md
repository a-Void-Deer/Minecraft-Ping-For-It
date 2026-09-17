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

1. Consider only active markers owned by the local player in the current
   dimension. Use their live candidate positions.
2. Keep the cone origin and direction from the press-time ray. Candidates must
   be within the configured half-angle (5 degrees by default).
3. Choose the nearest candidate by world-space distance. No eligible candidate
   is a silent no-op.
4. Request removal from the server. The server checks active status and ownership
   and synchronizes a valid removal. Another player's marker can never be cancelled.

The live candidate position does not authorize a new target-selection ray.
Stale/unauthorized removals are silent rejections under
[target validation](../authority/target_validation.md). Removing a winner triggers
[server winner recomputation](../authority/ping_winner.md).
