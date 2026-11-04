# D0018: Immediate creation snapshot receipt

## Status

Product decision represented by the linked topic contracts. This record
explains the timeliness trade-off, not completed implementation or verification.

## Decision

An inventory selection's atomic `CREATED` marker carries one server-frozen
creation snapshot of its currently selected, already-validated retained Preview
value. Timely chat is a product priority, so creation does not wait for a
complete Tracking observation, the inventory `POLICY` handshake, or a client
acknowledgement. The snapshot may therefore be partial or intrinsically
uncertain; such a value is marked as uncertain rather than hidden.

The snapshot belongs to creation. Later Tracking updates improve the marker's
live display without rewriting the one-shot chat already sent; the snapshot is
not Tracking evidence or a recipient cache. Existing creation authority, source,
recipient, range, audience and lifetime gates remain in force.

[Inventory](../architecture/presentation/inventory.md#creation-snapshot-receipt)
owns the frozen Preview value and its Tracking independence,
[presentation snapshot](../architecture/presentation/presentation_snapshot.md#receipt-content-descriptor)
owns receipt projection and marker-scoped creation authorization,
[network protocol](../architecture/network/protocol.md#presentation-snapshot-route-presentation-v2)
owns the wire grammar, and
[names and chat](../architecture/rendering/names_chat.md#content-receipt-lifecycle)
owns the one-shot message and uncertainty notation.

## Rationale

A ping exists to communicate at the moment of interaction. Waiting for a
complete inventory observation can delay that chat behind scan progress,
transport budgets, or a source that never completes, which defeats the social
signal even when the marker eventually becomes accurate. A retained Preview row
has already passed the server's selection and source authority; carrying it with
the marker gives every recipient a timely, server-authoritative starting value
without treating a client upload or a recipient's cache as truth.

An uncertain value communicated immediately is more useful than a correct value
communicated too late, provided the uncertainty is visible. A sent chat line is
also part of conversation history, so later Tracking accuracy improves the
marker display without editing or replacing what participants already read.

## Why not other approaches

- Do not wait for Tracking completion or a client `POLICY` state before sending
  the receipt: those conditions can be late or unavailable and would make chat
  timing depend on unrelated inventory progress.
- Do not use a client-uploaded quantity or a recipient's cached value: neither
  is current server authority for the selected source.
- Do not treat the creation snapshot as a Tracking baseline or completion
  evidence: that would let provisional data enter checksums, absence-zero
  decisions, or complete-scan claims.
- Do not rewrite or retract sent chat when later Tracking data differs: the
  message records what was communicated at creation time.
- Do not turn the marker-scoped receipt authorization into a global lane
  allowance or clear existing denials: a timely creation value must not widen
  ordinary Tracking permission.

## Consequences

A recipient can see a chat quantity that differs from the marker's later
Tracking display; that difference is expected, and only the display updates.
Existing creation admission and delivery budgets still apply, and this decision
changes neither source recovery nor marker lifetime.

Server authority, source recovery, component trust and certified observation
reuse remain unchanged under [D0004](D0004-server-authority.md),
[D0008](D0008-inventory-source-recovery.md),
[D0010](D0010-persistent-component-codec-trust.md) and
[D0017](D0017-certified-tracking-observation-pools.md).

Coverage and remaining runtime evidence are owned by
[presentation verification](../testing/verification/presentation.md#content-receipt-chat-and-pending-completion)
and [inventory verification](../testing/verification/inventory.md#preview-seeded-tracking-coverage);
this decision records no test or manual result.
