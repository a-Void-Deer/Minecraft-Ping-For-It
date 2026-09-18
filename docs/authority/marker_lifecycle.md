# Marker lifecycle

This topic owns the lifecycle contract for server-authoritative markers and
their client-side synchronized records and display state. Target acceptance and
audience construction are owned by [target validation](target_validation.md);
recipient-scoped same-target selection is owned by
[ping winner](ping_winner.md).

## Distinct lifetimes and sampling points

An active server marker, a synchronized client record, and a locally visible
marker are related but are not the same thing. The server does not maintain an
`EXPIRED` marker state: it removes an active record with `EXPIRED` as a
`MarkerRemovalReason`. The client state enum has only `SYNCHRONIZED` and
`STALE`; `EXPIRED` is not a third client state.

| Lifetime or setting | Owner and allowed values | When it is fixed | Meaning |
| --- | --- | --- | --- |
| Server synchronization lifetime | Server `syncDuration`: default **7 seconds**, clamped to **1–60 seconds** | An accepted create receives authoritative `arrivalTick` and `expiresAtTick`; those values are immutable marker/snapshot data. | The server keeps the active record through its expiry boundary and sends the frozen lifetime to recipients. A later config change does not reinterpret an existing marker's ticks. |
| Client marker-display setting | Client `markerDisplayDuration`: default **0**; `0` is the follow-server sentinel; custom values are **1–60 seconds** in **1-second** steps. | A newly inserted client record fixes its own visual deadline at receipt. | `0` uses that snapshot's frozen `expiresAtTick - arrivalTick` duration, rather than a latest connection policy. A custom value is converted at 20 client ticks per second and may be shorter or longer than the server lifetime. |
| Client synchronization fallback | Per-record derived value, not a user display setting | At every applied snapshot, from its frozen server duration plus the store's grace ticks and the local receipt tick. | It is loss recovery for a missing authoritative removal. It governs a possible transition to `STALE`, not the independently selected visual deadline. |

The display-duration policy is sampled for the first successful insertion of an
ID. A same-ID snapshot may refresh the stored payload and synchronization
fallback, but preserves the already recorded `displayExpiresAtLocalTick`.
Changing the local setting therefore affects later markers, not an existing
marker's deadline; a same-ID retransmission or external-target refresh cannot
extend that deadline.

## Server-authoritative record lifetime

The server store contains only active markers. A committed marker has a stable
ID, owner, target key, arrival tick, expiry tick, and a non-empty recipient
snapshot. Its lifetime transitions are:

| From | Trigger | To / result |
| --- | --- | --- |
| No server record | Server accepts a create after authoritative validation and audience snapshotting | One active `ServerMarker` with a new monotonic ID. Its arrival and expiry ticks are the authoritative lifetime carried in later snapshots. |
| Active | An external-target refresh with the same committed identity succeeds | Still active. The locator/anchor may change, but ID, owner, target key, arrival, expiry, Ping/Target Types, and audience stay unchanged. It is not a new create or a new winner arrival. |
| Active | Owner cancellation | The record is physically removed with reason `CANCELLED`. |
| Active | Server tick reaches `expiresAtTick` (`expiresAtTick <= currentTick`) | The record is physically removed with reason `EXPIRED`. The inclusive boundary is server time, not a client display deadline. |
| Active | Authoritative target invalidation | The record is physically removed with reason `TARGET_INVALID`. |
| Active | Owner disconnect | The owner's records are physically removed with reason `OWNER_DISCONNECTED`. |
| Active | A recipient disconnects | The server removes that recipient from the frozen audience. A record left with no recipients is dropped; this is audience cleanup, not channel/team re-evaluation. |
| Active | Server lifecycle reset | The store is cleared without recipient synchronization because no server audience remains. |

Every ordinary removal recomputes affected authoritative winner slots as
specified by [ping winner](ping_winner.md). The disconnect audience exception
is specified by [target validation](target_validation.md#audience-snapshot-at-create).

## Client record and visual-state machine

Client bookkeeping is main-thread local and retains enough state to tolerate
packet ordering and packet loss. `displayExpiresAtLocalTick` is visually active
only while `localTick < displayExpiresAtLocalTick`; reaching the deadline does
not by itself delete a synchronized record.

| From | Event / condition | Record result | Visual result |
| --- | --- | --- | --- |
| No record | A created-marker snapshot for an ID not tombstoned by an authoritative removal | Insert `SYNCHRONIZED`; derive fallback expiry and fix the display deadline. | It participates in `renderMarkers()` until its display deadline. |
| `SYNCHRONIZED` or locally fallback-`STALE` | A later snapshot for the same non-tombstoned ID | Upsert the newest payload and refresh the synchronization fallback; the state becomes `SYNCHRONIZED`, but the existing display deadline is retained. | A deadline that has already elapsed is not extended by this upsert. |
| `SYNCHRONIZED` | The local display deadline is reached | The synchronized stored record remains available for late authoritative packets. | It stops appearing in `renderMarkers()` and cannot be exposed by `winnerId`. |
| `STALE` | Its local display deadline is reached and fallback housekeeping runs | Delete the stale record. | It is no longer renderable. |
| `SYNCHRONIZED` or `STALE` | Reason-aware authoritative removal `EXPIRED`, while the visual is active and no synchronized same-target sibling exists | Tombstone the ID and retain/change the record as `STALE`. | Keep the stale visual until its independent display deadline. This is the normal case where the server expires first but a longer client display duration remains. |
| `SYNCHRONIZED` or `STALE` | Authoritative `EXPIRED` when the visual has already elapsed, or when a synchronized same-target sibling supersedes it | Tombstone and delete the record. | No stale visual is retained. |
| `SYNCHRONIZED` or `STALE` | Authoritative `CANCELLED`, `TARGET_INVALID`, or `OWNER_DISCONNECTED` removal | Tombstone and hard-delete immediately. | The display deadline does not defer these reasons. |
| `SYNCHRONIZED` | Fallback expiry is reached without an authoritative removal, the visual remains active, and no synchronized same-target sibling exists | Change to `STALE`. | Keep the visual to its fixed display deadline as loss recovery. |
| `SYNCHRONIZED` or `STALE` | Fallback processing finds the visual already elapsed, or finds a synchronized same-target sibling | Delete the record. | It is no longer renderable. |
| `STALE` | A newly synchronized marker for the same target but a different ID arrives | Delete stale same-target records before exposing the new synchronized record. | The fresh marker supersedes stale visuals for that target. |
| `STALE` | An authoritative winner update names an already-known synchronized marker for the same target | Delete stale same-target records before the synchronized winner is exposed. | The synchronized marker supersedes stale visuals for that target. |
| No record | An authoritative removal arrives before its created-marker snapshot | Tombstone the ID and clear any winner slot that named it. | A later delayed create is ignored, so it never becomes visible. |
| Any stored record | Client-store clear | Delete all records, winner slots, and removal tombstones. | No records remain visible. |

An authoritative removal tombstones its ID for the connection, so a delayed
created-marker packet cannot resurrect it. In contrast, a `STALE` record caused
only by local fallback has not received an authoritative removal and can return
to `SYNCHRONIZED` if a later same-ID snapshot arrives; while the record remains
stored, that recovery still does not change its existing display deadline. Once
local housekeeping has finally deleted a record after its display deadline,
there is no local tombstone for that deletion; a later same-ID create is
currently treated as a new insertion and receives a new deadline. This final
late-create case is a remaining contract/test gap, not an authoritative server
resurrection.

## Winner slots are not the render-marker collection

`renderMarkers()` returns every stored record whose independent visual deadline
is still active, including non-winners and stale records. `winnerId(targetKey)`
instead exposes at most the authoritative winner slot for that target, and only
when the referenced record is known, has the matching key, and is still
visually active. Thus a renderable record need not have an exposed winner yet,
and a winner slot is not a list of renderable markers.

When fallback cleanup has to remove an announced winner, the client may choose
a temporary local fallback among remaining visual records by server ordering
(latest arrival, then larger ID). That is packet-loss recovery only; a later
authoritative winner packet replaces it. It does not change the server rule in
[ping winner](ping_winner.md).

## Cancellation boundary

Fallback, stale visibility, and a locally elapsed display deadline are client
bookkeeping only. They do not grant cancellation authority or extend the
server-active lifetime. The wheel's local own-marker candidate rule is owned by
[wheel interaction](../picking/wheel.md); any resulting removal request still
passes the server's active-status and ownership check. A server-side stale or
unauthorized removal is therefore a silent no-op/rejection under
[target validation](target_validation.md), rather than a local lifecycle
recovery path.

`ClientMarkerStore.markersOwnedInDimension` deliberately returns stored
owner/dimension records without filtering for visual activity or
`SYNCHRONIZED` versus `STALE`. A fallback-stale or display-hidden record is
therefore not excluded by that store query alone; the wheel still applies its
press-ray cone and nearest-candidate policy, and the server remains decisive
about whether the requested marker is active and owned. Local retention must
not be described as server removal eligibility.

## Evidence boundary

`ServerConfigBoundsTest`, `ClientConfigBoundsTest`,
`ClientMarkerDisplayDurationTest`, `ClientMarkerStoreTest`, and
`ServerMarkerStoreTest` exercise the boundary, display, client-state, and
server-store seams described here. They are not a claim that this documentation
change ran those tests, nor end-to-end multiplayer or rendering evidence.
Named coverage and outstanding integration gaps belong in
[testing and verification](../testing/verification.md).
