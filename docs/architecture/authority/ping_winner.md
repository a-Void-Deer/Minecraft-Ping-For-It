# Same-target ping winner

This topic owns the authoritative, recipient-scoped selection of the visible
same-target marker. The client record and fallback projection is owned by
[client marker state and display](../markers/client-state.md); the server
record lifetime is owned by [marker lifecycle](marker_lifecycle.md).

## Deterministic authoritative ordering

For each recipient, the server considers only active same-target markers whose
current frozen audience contains that recipient, then chooses the visible winner
by:

1. latest **server arrival time**;
2. larger Marker ID when arrival times are equal.

Marker IDs must support deterministic larger-ID comparison. Client timestamps,
receipt order, render order, and unordered traversal on an individual client are
not substitutes for this ordering. Concrete target grouping follows
[stable identity](../identity/target_model.md), not render proxy positions or
the local constituent hit on a whole entity.

The audience snapshot at creation is owned by
[target validation](target_validation.md#audience-snapshot-at-create). Winner
selection consumes that frozen recipient snapshot; it does not independently
re-evaluate a player's later channel, group, or team. Later disconnect cleanup
can shrink the snapshot and is owned by [marker lifecycle](marker_lifecycle.md).

## Presentation and lifecycle

The winner's Ping Type supplies the shared visible outline and its related
color; [outline rendering](../rendering/outline.md) owns that consumption.
Non-winning markers remain active: choosing a winner does not implicitly delete
the others.

On cancellation, expiry, authoritative invalidation, or owner disconnect, the
server recomputes each affected recipient's winner among the remaining active
candidates and synchronizes the resulting state. If a previous non-winner
becomes the winner, its selected Ping Type supplies the visible color. Removal
requires the active-status and ownership checks owned by
[target validation](target_validation.md); the reasons and audience shrink are
owned by [marker lifecycle](marker_lifecycle.md).

An external locator/anchor refresh preserves the committed identity and does not
manufacture a new arrival or winner event; see
[target identity](../identity/target_model.md) and
[Sable](../../integrations/sable.md). On the client, an authoritative winner
slot is distinct from the collection of locally display-active records, and
loss-recovery fallback is overwritten by a later authoritative update; see
[client marker state and display](../markers/client-state.md).

Multiplayer winner and equal-arrival tie scenarios remain pending verification
work; the coverage inventory and gaps are owned by
[testing and verification](../../testing/verification.md). Rationale is in
[D0004](../../decisions/D0004-server-authority.md).
