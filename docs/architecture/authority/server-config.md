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
rule view may expose editable controls and whether a mutation is allowed.

Reading server configuration is not an edit and does not require that level:
every authenticated player may request the server configuration snapshot and
the presentation policy rule view. Requesting either view does not itself grant
edit authority. Which values a route discloses and how a mutation is transacted
are owned by
[changing server configuration](../config/changing-server-config.md) and
[presentation snapshot](../presentation/presentation_snapshot.md).

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
