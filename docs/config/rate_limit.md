# Send-rate policy and client courtesy gate

## Authoritative policy synchronization

The rate-policy payload contains exactly `rateLimit` and `msToRegenerate`. The
server synchronizes their effective values to clients on reconnect and whenever
effective configuration changes. A reconnect-only cache is insufficient.

### Effective values and boundary-specific zero handling

| Boundary | Defaults / ownership | Zero value | Negative or corrupt input |
| --- | --- | --- | --- |
| Server enforcement | Server config defaults to `rateLimit = 5` and `msToRegenerate = 1000` ms. | `rateLimit = 0` skips the server-side rate check. With a positive `rateLimit`, `msToRegenerate = 0` is passed to the existing zero-duration limiter rather than disabling enforcement. | A negative `rateLimit` recovers to `0`, which skips the server-side check; a negative `msToRegenerate` recovers to `1000` ms. |
| Client courtesy gate | The non-persisted `ClientRateLimitPolicy.DEFAULT` fallback is `rateLimit = 5` and `msToRegenerate = 1000` ms for initial and connection-reset state. An accepted synchronized server policy replaces it; these values are not independently client-configurable. | Either value being `0` disables courtesy limiting, so the local gate permits the committed create. | A corrupt or negative synchronized policy is ignored rather than installed as an invalid courtesy limiter, retaining the current policy. |

The server's zero-duration limiter still evaluates wall-clock time. Under normal
non-reversing time it permits requests, but a wall-clock rollback can cause a
rejection; `msToRegenerate = 0` is therefore not an unconditional server-side
unlimited guarantee.

Shared future-version preservation is defined by
[future-version preservation](client.md#future-version-preservation). This
rate-policy contract does not redefine that configuration rule.

`syncDuration` is not a third rate-policy field and does not feed the courtesy
token bucket. Its server bounds, frozen marker-snapshot lifetime, and separate
client display-duration relationship are owned by
[marker lifecycle](../authority/marker_lifecycle.md).

For a positive effective `rateLimit`, the server independently enforces its
limit for **every client**, including malicious clients which skip the courtesy
gate. The client mirror is not an authority or trust boundary.

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
