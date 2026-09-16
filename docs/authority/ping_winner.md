# Same-target ping winner

## Deterministic authoritative ordering

For each recipient, the server considers only active same-target markers whose
immutable audience contains that recipient, then chooses the visible winner by:

1. latest **server arrival time**;
2. larger Marker ID when arrival times are equal.

Marker IDs must support deterministic larger-ID comparison. Client timestamps,
render order, unordered map traversal and receipt order on an individual client
are not substitutes for this ordering. Concrete target grouping follows
[stable identity](../identity/target_model.md), not render proxy positions or
the local constituent hit on a whole entity.

## Presentation and lifecycle

The winner's Ping Type drives the shared visible outline and outline-related
color presentation. Non-winning markers remain active: choosing a winner does
not implicitly delete the others.

On cancellation, expiry, authoritative invalidation or owner disconnect, the
server recomputes each affected recipient's winner from the remaining active
markers and synchronizes resulting state. If a previous non-winner becomes the
winner, its selected Ping Type supplies the visible color. Removal still requires the
[ownership/active-status checks](target_validation.md).

External locator/anchor refresh preserves marker ID, owner, Target/Ping Types,
arrival time, expiry, target key and immutable audience, so it does not
manufacture a new winner;
see [Sable](../integrations/sable.md). The
[verification matrix](../testing/verification.md) keeps multiplayer winner and tie cases
pending until actually exercised. Rationale is in
[D0004](../decisions/D0004-server-authority.md).
