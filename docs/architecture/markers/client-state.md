# Client marker state and display

This topic owns the client-side marker record and display contract: the
synchronized record state, the independent visual deadline, synchronization
fallback after packet loss, authoritative-removal tombstones, the temporary HUD
winner projection, and the retained outline winner. The server's authoritative
record lifetime is owned by
[marker lifecycle](../authority/marker_lifecycle.md); recipient-scoped
authoritative winner selection is owned by
[ping winner](../authority/ping_winner.md). State-message acceptance is owned by
[network protocol](../network/protocol.md); the marker-scoped authorization,
retention and pruning rules for the presentation values delivered with a record
are owned by
[presentation snapshot](../presentation/presentation_snapshot.md), and new-marker
receipt notifications are owned by [names and chat](../rendering/names_chat.md).

## Record states and lifetimes

A server-active marker, a synchronized client record, and a locally visible
marker are related but are not the same thing. The client record state enum has
only `SYNCHRONIZED` and `STALE`; `EXPIRED` is an authoritative removal reason,
not a third client state. Client bookkeeping is main-thread local and retains
enough state to tolerate packet ordering and packet loss.

The client retains the server-carried logical key separately from the physical
target and anchor, through upserts, copies, stale transitions and winner
fallback. All same-target sibling, HUD, outline and winner comparisons below use
that frozen key. Client structure resolution changes presentation subjects only;
it cannot reconstruct or replace the key from the current world. The identity
boundary is owned by the
[target model](../identity/target_model.md#frozen-logical-block-groups).

- The record has an independent visual deadline. It is visually active only
  while its display time base is strictly before that deadline;
  reaching the deadline does not by itself delete a synchronized record.
- The synchronization fallback deadline is a
  per-record derived value, not a user display setting. It is loss recovery for
  a missing authoritative removal and governs a possible transition to `STALE`,
  not the independently selected visual deadline. Its time base follows the
  record's display mode as described below.

In fallback housekeeping, a `SYNCHRONIZED` record is considered for transition
or deletion only once its own fallback deadline is due; before that point an
elapsed visual deadline leaves the synchronized record stored.

## Visual deadline fixing

The display-duration policy is sampled for the first successful insertion of an
ID and fixes that record's visual deadline. The
`markerDisplayDuration` configuration key, its follow-server sentinel, and its
legal values are catalogued in [client configuration](../../config/client.md).

Follow-server mode takes the snapshot's frozen server tick span, from the
authoritative arrival and expiry ticks, as the full visual span. Counting
starts at the first received game-time sample after the record is inserted and
advances only with later received vanilla world `gameTime` increments; the
sample interval containing receipt is not charged, so server-to-client delay
does not shorten the span. It does not substitute wall-clock time or an assumed
20 TPS. A mid-session server tick-rate change and a genuinely paused world
therefore affect the remaining display. A same-ID retransmission does not move
the already fixed visual deadline.

A custom local duration keeps the established local display-length semantics.
Its configured value is a local-duration preference; the same real elapsed time
is not promised under a non-20 TPS timeline.

Every same-ID non-tombstoned upsert refreshes the stored payload and the
synchronization fallback, but preserves the already recorded visual deadline.
Changing the local setting therefore affects later insertions, not stored
records; a same-ID retransmission or external-target refresh cannot extend that
deadline.

## Synchronization fallback time base

The synchronization fallback is independent of the fixed visual deadline. In
follow-server mode it starts at the most recent valid same-ID upsert and
consumes the record's frozen server tick span using received vanilla world
`gameTime` increments from the next observed sample; the sample interval
containing that upsert is not charged. Once received progress reaches the end of
the span, the existing local grace allowance applies. A retransmission
therefore refreshes the fallback relative to the latest upsert without moving
the visual deadline.

A custom-duration record keeps the established synchronization-fallback
derivation; the follow-server game-progress rule does not apply to it.

In follow-server mode, the sample span consumed from first receipt or the
latest upsert is a computed visual or recovery budget, not a guarantee that the
record remains displayable after a valid authoritative `EXPIRED` arrives. A
valid `EXPIRED` is applied when received; the transition table below owns its
mode-specific result.

## Live entity display position

For an entity target, the marker follows a live world point only when the render
lookup and the optional current parent frame both yield a trusted finite point.
An absent or removed entity, an unavailable render frame, or a non-finite
projection leaves the existing position tracker at its last live point, or at
the authoritative anchor before any live point has resolved. A later successful
point resumes following; a frame failure is not a permanent invalidation.

The render frame projects only the interpolated base position; the current
native bounding-box height is added along world up and is not rotated or scaled
by the parent. The cancellation candidate helper uses the same current-tick
top-center definition. When that point is unavailable, cancellation falls
through to the existing presentation/anchor fallback owned by
[wheel](../picking/wheel.md#cancel-marker-selection). Parent-frame resolution and
provider failure boundaries are owned by
[Sable](../../integrations/sable.md#transformed-entity-frames-and-frozen-block-proof).

## Client record and visual-state transitions

| From | Event or condition | Record result | Visual result |
| --- | --- | --- | --- |
| No record | A created-marker snapshot for an ID not tombstoned by an authoritative removal | Insert `SYNCHRONIZED`; derive the fallback expiry and fix the visual deadline. | It participates in the renderable-record projection until its visual deadline. |
| `SYNCHRONIZED` or locally fallback-`STALE` | A later snapshot for the same non-tombstoned ID | Upsert the newest payload and refresh the synchronization fallback; the state becomes `SYNCHRONIZED`, but the existing visual deadline is retained. | A deadline that has already elapsed is not extended by this upsert. |
| `SYNCHRONIZED` | The local visual deadline is reached before its synchronization fallback is due | The synchronized stored record remains available for late authoritative packets. | It stops appearing in the renderable-record projection and cannot be exposed by the HUD winner slot or the outline target. |
| `SYNCHRONIZED` or `STALE` | Reason-aware authoritative removal `EXPIRED` in follow-server display mode | Tombstone and delete the record immediately. | No stale visual is retained after the client accepts `EXPIRED`. |
| `SYNCHRONIZED` or `STALE` | Reason-aware authoritative removal `EXPIRED` in custom local display mode, while the visual is active and no synchronized same-target sibling exists | Tombstone the ID and retain or change the record as `STALE`. | Keep the stale visual until its independent visual deadline. This is the case where the server expires first but a longer local display duration remains. |
| `SYNCHRONIZED` or `STALE` | Authoritative `EXPIRED` when the visual has already elapsed, or when a synchronized same-target sibling supersedes it | Tombstone and delete the record. | No stale visual is retained. |
| `SYNCHRONIZED` or `STALE` | Authoritative `CANCELLED`, `TARGET_INVALID`, or `OWNER_DISCONNECTED` removal | Tombstone and hard-delete immediately. | The visual deadline does not defer these reasons. |
| `SYNCHRONIZED` | Its fallback deadline is due; fallback housekeeping first removes stale same-target siblings, then finds either an elapsed visual deadline or another synchronized same-target record | Delete the synchronized record. | It is no longer renderable. |
| `SYNCHRONIZED` | Its fallback deadline is due, its visual remains active, and no other synchronized same-target record exists | Remove stale same-target siblings, then change this record to `STALE`. | Keep this record's visual to its fixed visual deadline as loss recovery. |
| `STALE` | Its own fallback-housekeeping branch finds its visual deadline elapsed | Delete the stale record. | It is no longer renderable. |
| `STALE` | Its own fallback-housekeeping branch finds its visual still active | Retain the stale record. Its already-past fallback deadline is not a second deletion condition. | It remains renderable. |
| `STALE` | A synchronized same-target record reaches its own fallback deadline and is processed | Delete this stale record as a same-target sibling before the synchronized record's transition or deletion decision, even if this stale record's visual remains active. | It is no longer renderable. |
| `STALE` | A newly synchronized marker for the same target but a different ID arrives | Delete stale same-target records before exposing the new synchronized record. | The fresh marker supersedes stale visuals for that target. |
| `STALE` | An authoritative winner update names an already-known synchronized marker for the same target | Delete stale same-target records before the synchronized winner is exposed. | The synchronized marker supersedes stale visuals for that target. |
| No record | An authoritative removal arrives before its created-marker snapshot | Tombstone the ID and clear any winner slot or retained outline history that named it. | A later delayed create is ignored, so it never becomes visible. |
| Any stored record | Client-store clear | Delete all records, winner slots, retained outline history, and removal tombstones. | No records remain visible; the clear is not a permanent cross-clear history. |

A merely local fallback-`STALE` record has no authoritative-removal tombstone
and can recover to `SYNCHRONIZED` if a later same-ID snapshot arrives; while the
record remains stored, that recovery still does not change its existing visual
deadline. When local housekeeping finally deletes a record after its visual
deadline, the client also evicts that marker's retained presentation values and
leaves a session-scoped eviction tombstone, so a delayed same-ID create cannot
resurrect the marker in that session; the eviction-tombstone retention rule is
owned by
[presentation snapshot](../presentation/presentation_snapshot.md#client-retention-and-display).
Automated coverage of the complete production orchestration remains a gap
tracked in [verification](../../testing/verification/identity-authority.md).

The fallback branches above are state-specific. A visually expired
`SYNCHRONIZED` record remains stored until its own fallback deadline is due; at
that due pass it is deleted rather than first becoming stale. A `STALE` record's
own branch tests only visual activity, but it can also be removed as a stale
same-target sibling while a due synchronized record is processed. `EXPIRED`
remains an authoritative removal reason, never a client record state.

## HUD winner slot and presentation selection

The renderable-record projection includes every stored record whose independent
visual deadline is still active, including non-winners and `STALE` records.

The HUD winner slot normally stores the current server-assigned winner for a
target. Loss-recovery cleanup can locally recompute that slot among visually
active same-target records (see [Temporary local winner](#temporary-local-winner));
a later authoritative update replaces it. The slot is exposed only while the
referenced record is known, matches the key, and is visually active.

The HUD presentation applies a separate, non-writing per-target selection over
those renderable records: it presents at most one record per canonical target
key, so several visually active same-target pings, whether from one sender or
several, do not accumulate their displayed alpha. It prefers the exposed HUD
winner slot. When no slot is exposed for the target — because no authoritative
winner has arrived yet, or the winner record is unknown, mismatched, or its
visual deadline has elapsed — while other same-target records remain visually
active, the HUD instead presents the record selected by the authoritative
ordering defined in
[ping winner](../authority/ping_winner.md#deterministic-authoritative-ordering).
A target with no visually active record contributes nothing to the HUD.

This presentation selection is not written back to the HUD winner slot or the
outline history, and it does not modify stored records, synchronization state,
or visual deadlines; it neither resurrects an expired record nor resets a
display duration. It is separate from the fallback cleanup described above.

## Outline target and retained last winner

For each frozen target key, the client retains the ID most recently assigned as
the visible winner by the server. A later non-empty assignment replaces that ID;
an empty assignment retires the current assignment without erasing the
remembered ID. Deleting the remembered record from the client store clears its
history for that key. Accepting `EXPIRED` while retaining a custom-duration
record keeps that history.

An accepted same-connection presentation `RESET` retires the old view's current
assignment. If the remembered record is still stored, its history carries into
the new view: it needs a new assignment to supply an outline unless it already
has, or later receives, an authoritative `EXPIRED` and is still visually active.
A non-empty assignment in the new view replaces the remembered ID. Packets from
the old view are rejected; a new session or store clear discards all history.

Outline rendering consumes this projection. While the key's current
authoritative assignment yields a known, matching, visually active record, that
record is the outline target. A non-empty assignment that does not resolve that
way leaves the key with no outline target; the projection does not revert to an
earlier remembered ID. When the current assignment is empty, the
remembered last winner supplies the outline target only if it carries an
authoritative `EXPIRED` tombstone and remains within its local visual deadline.
If the remembered record is unknown, belongs to another key, is not
authoritatively expired, or is no longer visually active, the key has no
outline target. The projection never substitutes another visually active
same-target record, whether that record is a `STALE` non-winner, a synchronized
non-winner, or a HUD presentation selection.

Only the record selected here is the outline target;
[outline rendering](../rendering/outline.md#common-entity-outlines) owns its
consumption.

## Temporary local winner

When fallback cleanup has to remove an announced winner, the client may locally
recompute the affected HUD winner slot among remaining visually active
same-target records by consuming the authoritative ordering defined in
[ping winner](../authority/ping_winner.md) rather than restating its comparator.
This HUD-slot recomputation is packet-loss recovery only; it never supplies the
outline target. A later authoritative winner update replaces it, and it does not
change the server rule.

## Local retention is not server cancellation authority

Fallback, stale visibility, and a locally elapsed visual deadline are client
bookkeeping only. They do not grant cancellation authority or extend the
server-active lifetime. [Wheel interaction](../picking/wheel.md) owns the local
own-marker candidate rule and the local dismissal of an owner's own
authoritatively `EXPIRED` display; that dismissal removes only the client record
and asserts no server cancellation. Any dispatched removal request still passes
the server's active-status and ownership check under
[target validation](../authority/target_validation.md).
