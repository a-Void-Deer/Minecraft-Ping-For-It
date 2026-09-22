# Client marker state and display

This topic owns the client-side marker record and display contract: the
synchronized record state, the independent visual deadline, synchronization
fallback after packet loss, authoritative-removal tombstones, and the temporary
local winner projection. The server's authoritative record lifetime is owned by
[marker lifecycle](../authority/marker_lifecycle.md); recipient-scoped
authoritative winner selection is owned by
[ping winner](../authority/ping_winner.md). State-message acceptance is owned by
[network protocol](../network/protocol.md), and new-marker receipt notifications
are owned by [names and chat](../rendering/names_chat.md).

## Record states and lifetimes

A server-active marker, a synchronized client record, and a locally visible
marker are related but are not the same thing. The client record state enum has
only `SYNCHRONIZED` and `STALE`; `EXPIRED` is an authoritative removal reason,
not a third client state. Client bookkeeping is main-thread local and retains
enough state to tolerate packet ordering and packet loss.

- The record has an independent visual deadline. It is visually active only
  while local time is strictly before that deadline;
  reaching the deadline does not by itself delete a synchronized record.
- The synchronization fallback deadline is a
  per-record derived value, not a user display setting. It is loss recovery for
  a missing authoritative removal and governs a possible transition to `STALE`,
  not the independently selected visual deadline.

In fallback housekeeping, a `SYNCHRONIZED` record is considered for transition
or deletion only once its own fallback deadline is due; before that point an
elapsed visual deadline leaves the synchronized record stored.

## Visual deadline fixing

The display-duration policy is sampled for the first successful insertion of an
ID and fixes that record's visual deadline. The
`markerDisplayDuration` configuration key, its follow-server sentinel, and its
legal values are catalogued in [client configuration](../../config/client.md).

Follow-server mode consumes the received snapshot's frozen server duration
derived from the authoritative arrival and expiry ticks, not a later connection
policy. A custom local duration may be shorter or longer than the server
lifetime.

Every same-ID non-tombstoned upsert refreshes the stored payload and the
synchronization fallback, but preserves the already recorded visual deadline.
Changing the local setting therefore affects later insertions, not stored
records; a same-ID retransmission or external-target refresh cannot extend that
deadline.

## Client record and visual-state transitions

| From | Event or condition | Record result | Visual result |
| --- | --- | --- | --- |
| No record | A created-marker snapshot for an ID not tombstoned by an authoritative removal | Insert `SYNCHRONIZED`; derive the fallback expiry and fix the visual deadline. | It participates in the renderable-record projection until its visual deadline. |
| `SYNCHRONIZED` or locally fallback-`STALE` | A later snapshot for the same non-tombstoned ID | Upsert the newest payload and refresh the synchronization fallback; the state becomes `SYNCHRONIZED`, but the existing visual deadline is retained. | A deadline that has already elapsed is not extended by this upsert. |
| `SYNCHRONIZED` | The local visual deadline is reached before its synchronization fallback is due | The synchronized stored record remains available for late authoritative packets. | It stops appearing in the renderable-record projection and cannot be exposed by the winner slot. |
| `SYNCHRONIZED` or `STALE` | Reason-aware authoritative removal `EXPIRED`, while the visual is active and no synchronized same-target sibling exists | Tombstone the ID and retain or change the record as `STALE`. | Keep the stale visual until its independent visual deadline. This is the normal case where the server expires first but a longer client display duration remains. |
| `SYNCHRONIZED` or `STALE` | Authoritative `EXPIRED` when the visual has already elapsed, or when a synchronized same-target sibling supersedes it | Tombstone and delete the record. | No stale visual is retained. |
| `SYNCHRONIZED` or `STALE` | Authoritative `CANCELLED`, `TARGET_INVALID`, or `OWNER_DISCONNECTED` removal | Tombstone and hard-delete immediately. | The visual deadline does not defer these reasons. |
| `SYNCHRONIZED` | Its fallback deadline is due; fallback housekeeping first removes stale same-target siblings, then finds either an elapsed visual deadline or another synchronized same-target record | Delete the synchronized record. | It is no longer renderable. |
| `SYNCHRONIZED` | Its fallback deadline is due, its visual remains active, and no other synchronized same-target record exists | Remove stale same-target siblings, then change this record to `STALE`. | Keep this record's visual to its fixed visual deadline as loss recovery. |
| `STALE` | Its own fallback-housekeeping branch finds its visual deadline elapsed | Delete the stale record. | It is no longer renderable. |
| `STALE` | Its own fallback-housekeeping branch finds its visual still active | Retain the stale record. Its already-past fallback deadline is not a second deletion condition. | It remains renderable. |
| `STALE` | A synchronized same-target record reaches its own fallback deadline and is processed | Delete this stale record as a same-target sibling before the synchronized record's transition or deletion decision, even if this stale record's visual remains active. | It is no longer renderable. |
| `STALE` | A newly synchronized marker for the same target but a different ID arrives | Delete stale same-target records before exposing the new synchronized record. | The fresh marker supersedes stale visuals for that target. |
| `STALE` | An authoritative winner update names an already-known synchronized marker for the same target | Delete stale same-target records before the synchronized winner is exposed. | The synchronized marker supersedes stale visuals for that target. |
| No record | An authoritative removal arrives before its created-marker snapshot | Tombstone the ID and clear any winner slot that named it. | A later delayed create is ignored, so it never becomes visible. |
| Any stored record | Client-store clear | Delete all records, winner slots, and removal tombstones. | No records remain visible. |

A removal before a create tombstones the ID and clears its winner slot, so a
delayed created-marker message cannot resurrect it. A store clear deletes all
records, winners, and tombstones; it is not a permanent cross-clear history.

A merely local fallback-`STALE` record has no authoritative-removal tombstone
and can recover to `SYNCHRONIZED` if a later same-ID snapshot arrives; while the
record remains stored, that recovery still does not change its existing visual
deadline. Once local housekeeping has finally deleted a record after its visual
deadline, a later same-ID create is treated as a new insertion and receives a
new visual deadline. Automated coverage of this behavior remains a gap tracked in
[verification](../../testing/verification.md).

The fallback branches above are state-specific. A visually expired
`SYNCHRONIZED` record remains stored until its own fallback deadline is due; at
that due pass it is deleted rather than first becoming stale. A `STALE` record's
own branch tests only visual activity, but it can also be removed as a stale
same-target sibling while a due synchronized record is processed. `EXPIRED`
remains an authoritative removal reason, never a client record state.

## Winner slots are not the render-marker collection

The renderable-record projection includes every stored record whose independent
visual deadline is still active, including non-winners and `STALE` records. The
exposed winner slot for a target instead identifies at most the authoritative
winner for that target, and only when the referenced record is known, has the
matching key, and is still visually active. Thus a renderable record need not
have an exposed winner yet, and a winner slot is not a list of renderable
markers. These are distinct projections.

## Temporary local winner

When fallback cleanup has to remove an announced winner, the client may choose a
temporary local fallback among remaining visual records by consuming the
authoritative ordering defined in [ping winner](../authority/ping_winner.md)
rather than restating its comparator. That is packet-loss recovery only; a later
authoritative winner update replaces it, and it does not change the server rule.

## Local retention is not server cancellation authority

Fallback, stale visibility, and a locally elapsed visual deadline are client
bookkeeping only. They do not grant cancellation authority or extend the
server-active lifetime. The wheel's local own-marker candidate rule is owned by
[wheel interaction](../picking/wheel.md); any resulting removal request still
passes the server's active-status and ownership check under
[target validation](../authority/target_validation.md). Local retention is not
server removal eligibility.
