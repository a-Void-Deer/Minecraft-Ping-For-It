# Send-rate policy and client courtesy gate

## Authoritative policy synchronization

The rate-policy payload contains exactly `rateLimit` and `msToRegenerate`. The
server synchronizes their effective values to clients on reconnect and whenever
effective configuration changes. A reconnect-only cache is insufficient.

These two malformed-state boundaries are distinct:

- a client ignores a corrupt or negative synchronized policy rather than
  installing an invalid courtesy limiter; and
- server config validation recovers a negative `msToRegenerate` to `1000` and
  a negative `rateLimit` to `0` before the effective policy is used.

`syncDuration` has its own synchronization policy and is not a third rate-policy
field.

The server independently enforces its limit for **every client**, including
malicious clients which skip the courtesy gate. The client mirror is not an
authority or trust boundary.

## Create-only dispatch boundary

Immediately before sending `MarkerCreate`, the client mirrors the synchronized
policy with a token bucket as a courtesy gate only. If that gate rejects a
committed create:

- drop it;
- do not queue it for later;
- do not send it;
- do not record it as dispatched.

`MarkerRemove` and channel-update behavior, including policy updates, remain
unchanged. Do not apply a create-only limiter to them. The pending-capture
compatibility queue in [capture](../picking/capture.md) is a separate input
mechanism, not permission to queue throttled creates.

## Feedback, diagnostics and evidence

Do not add toast or action-bar feedback. Rate-controlled diagnostics may include
complete target, position, registry, class, material, component, payload and
exception details; do not redact those details. This policy does not suppress
the [local pre-commit invalid-target message](../authority/target_validation.md).
Only actually dispatched creates participate in the latest-request
`TARGET_GONE` response rule.

Automated evidence and remaining reconnect/live-update and sanitization coverage
gaps are maintained centrally in [verification](../testing/verification.md). This
contract describes required behavior rather than evidence that those integration
paths have been exercised.
