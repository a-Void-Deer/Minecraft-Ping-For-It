# Send-rate policy and client courtesy gate

This focused architecture contract owns the send-rate policy shared by the
server and the client: effective-value synchronization, zero/negative/corrupt
handling, refill and counter invariants, server enforcement, and the
create-only client courtesy boundary. It does not own the persisted server
field catalogue ([server configuration](../../config/server.md)), the marker
admission order that reports `RATE_LIMITED`
([target validation](../authority/target_validation.md)), the server-side trust
and feedback boundary ([security](../security.md)), or the marker
synchronization lifetime
([marker lifecycle](../authority/marker_lifecycle.md)).

## Policy synchronization and effective values

The rate-policy payload contains exactly `rateLimit` and `msToRegenerate`. The
server synchronizes their effective values to clients on reconnect or channel
update and whenever effective configuration changes; a reconnect-only cache is
insufficient. The two fields are server-authoritative: persisted server values
are validated and normalized before publication. A persisted negative
`rateLimit` is normalized to the disabled/zero policy before publication, and a
persisted negative `msToRegenerate` is replaced by the valid server
configuration interval before publication. Neither negative value is sent as a
client policy. [Configuration revisioning](revisioning.md) owns persistence and
recovery policy; this contract does not restate fallback constants.

A client accepts a synchronized policy only when it is a safe policy value. A
corrupt or negative synchronized packet is ignored, retaining the current
policy, and an equal policy is a no-op. Initial state and connection reset use
the transport's default fallback policy; an accepted synchronized value
replaces it for that connection. The fallback is not independently
client-configurable.

Applying a synchronized policy at runtime changes the effective limit and
regeneration interval while preserving the limiter's existing history: an
already-consumed allowance is not replayed, dropped, or reset by the policy
update. The client token bucket is a courtesy mirror, not a second authority.

## Boundary-specific zero handling

- On the server, a zero `rateLimit` bypasses the server-side rate check.
- With a positive server `rateLimit`, a zero `msToRegenerate` is still passed
  through the existing server limiter; it is not an unconditional server-side
  unlimited guarantee, because the limiter still evaluates wall-clock time and
  a wall-clock rollback can cause a rejection.
- On the client, the courtesy gate is disabled when either synchronized value
  is zero, so the local gate permits the committed create rather than rejecting
  it.
- Negative or corrupt synchronized policy values are ignored client-side,
  retaining the current policy.

## Server enforcement and client courtesy

For a positive effective `rateLimit`, the server independently enforces the
limit for every client, including modified clients that skip the courtesy gate.
The client mirror is not an authority or trust boundary. The server limiter is
per-player and is reached before the channel, recipient, target, range,
classification, or Ping Type stages; its admission ordering and rejection
reporting are owned by [target validation](../authority/target_validation.md).
A permitted or exceeded check is not rolled back by a later rejection.

`MarkerRemove` and channel-update behavior, including policy updates, remain
unchanged. The courtesy gate is never applied to them; it exists only
immediately before a `MarkerCreate` dispatch.

`syncDuration` is not a rate-policy field and does not feed the courtesy token
bucket. Its persisted catalogue entry is
[server configuration](../../config/server.md) and its lifetime behavior is
owned by [marker lifecycle](../authority/marker_lifecycle.md).

## Create-only dispatch boundary

Immediately before sending `MarkerCreate`, the client mirrors the synchronized
policy with a token bucket as a courtesy gate only. If that gate rejects a
committed create:

- drop it;
- do not queue it for later;
- do not send it;
- do not record it as dispatched.

The separate pending-capture input sequence is defined in
[long-press compatibility](../input/long-press-compatibility.md).

## Feedback and evidence

Rejection feedback, rate-controlled diagnostic detail, and user-visible
feedback boundaries are owned by [security](../security.md). Client processing
of rejection responses is owned by
[target validation](../authority/target_validation.md).

Automated evidence and remaining reconnect, live-update, and sanitization
coverage gaps are maintained centrally in
[verification](../../testing/verification.md). This contract describes required
behavior rather than evidence that those integration paths have been exercised.
