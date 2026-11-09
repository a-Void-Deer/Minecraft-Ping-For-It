# Server configuration authority

This focused architecture contract owns who may change persisted server
configuration. It does not own the field catalogue
([server configuration](../../config/server.md)), the request/correlation and
merge transaction
([changing server configuration](../config/changing-server-config.md)), or the
server-side identity and enforcement mechanism ([security](../security.md)).

## Required authority

Changing persisted server configuration requires inherent server permission
level 3. This eligibility governs whether a snapshot or presentation policy
rule view may expose editable controls and whether a mutation is allowed. The
inventory administration values carried by the ordinary snapshot are part of
the same persisted configuration and use the same gate; no inventory-specific
permission exists.

Reading server configuration is not an edit and does not require that level:
every authenticated player may request the server configuration snapshot and
the presentation policy rule view, and a viewer below the level receives the
snapshot as a read-only view. Requesting either view does not itself grant edit
authority. Which values a route discloses and how a mutation is transacted are
owned by
[changing server configuration](../config/changing-server-config.md) and
[presentation snapshot](../presentation/presentation_snapshot.md).

## Remote performance telemetry

The performance monitor is a separate remote telemetry surface. It does not
change ordinary server-configuration reading, which remains available to every
authenticated player, or mutation, which still requires level 3.

Numeric server telemetry is administrator-only. A client must hold inherent
server permission level 3 to subscribe to numeric monitor data, and the server
rechecks that authority before every numeric `SAMPLE` emission. The check governs
the numeric data surface only.

A limited monitor `STATUS` control summary remains available to every
authenticated client. It reports availability and control state, including an
explicitly disabled lane, and carries no numeric statistics. Numeric `SAMPLE`
data is never included in that summary.

Losing level 3 stops numeric sample delivery and clears that client's numeric
server telemetry view. It does not disable the client HUD, remove client-local
metrics, or revoke any inventory preview or tracking authority. Telemetry
authority likewise grants no inventory read, marker, selection, or mutation
authority beyond the viewer's existing permissions. Message form, generation
handling, and delivery are owned by [network protocol](../network/protocol.md);
the accepted update transaction remains owned by
[changing server configuration](../config/changing-server-config.md).

## Capability hints are not credentials

Neither a snapshot's nor a rule view's `canEdit` hint, nor a request
identifier, grants edit authority. How the server derives and enforces the
trusted check for both configuration updates and presentation policy mutations
is owned by
[security](../security.md#server-configuration-update-enforcement); protocol
meaning is owned by
[changing server configuration](../config/changing-server-config.md) and
[presentation snapshot](../presentation/presentation_snapshot.md).

## Scope of this gate

This authority gate is specific to server-configuration editing. Ordinary marker
creation and removal eligibility are owned by
[target validation](target_validation.md).
