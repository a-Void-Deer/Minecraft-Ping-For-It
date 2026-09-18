# Same-target ping winner

## Deterministic authoritative ordering

For each recipient, the server considers only active same-target markers whose
current audience contains that recipient, then chooses the visible winner by:

1. latest **server arrival time**;
2. larger Marker ID when arrival times are equal.

Marker IDs must support deterministic larger-ID comparison. Client timestamps,
render order, unordered map traversal and receipt order on an individual client
are not substitutes for this ordering. Concrete target grouping follows
[stable identity](../identity/target_model.md), not render proxy positions or
the local constituent hit on a whole entity. The server snapshots the audience
at creation. Later channel switches do not recalculate it; disconnect cleanup
can shrink it and is owned by [target validation](target_validation.md).

The complete creation-time audience matrix, including empty versus non-empty
channels and `AUTO`/`DISABLED`/`GLOBAL`/`TEAM_ONLY`, is owned by
[target validation](target_validation.md#audience-snapshot-at-create). Winner
selection consumes that frozen recipient snapshot; it does not independently
re-evaluate a player's later channel, group, or team.

## Presentation and lifecycle

The winner's Ping Type drives the shared visible outline and outline-related
color presentation. Non-winning markers remain active: choosing a winner does
not implicitly delete the others.

On cancellation, expiry, authoritative invalidation or owner disconnect, the
server recomputes each affected recipient's winner from the remaining active
markers and synchronizes resulting state. If a previous non-winner becomes the
winner, its selected Ping Type supplies the visible color. Removal still requires the
[ownership/active-status checks](target_validation.md).

Server expiry/removal reasons and the client distinction between synchronized,
stale, and hard-deleted records are owned by
[marker lifecycle](marker_lifecycle.md). On the client, an authoritative winner
slot is distinct from the collection of locally display-active records.
Loss-recovery recomputation can only be temporary and is overwritten by a later
authoritative winner update; see
[marker lifecycle](marker_lifecycle.md#winner-slots-are-not-the-render-marker-collection).

External locator/anchor refresh preserves marker ID, owner, Target/Ping Types,
arrival time, expiry, target key and current audience, so it does not
manufacture a new winner;
see [Sable](../integrations/sable.md). The
[verification matrix](../testing/verification.md) keeps multiplayer winner and tie cases
pending until actually exercised. Rationale is in
[D0004](../decisions/D0004-server-authority.md).
