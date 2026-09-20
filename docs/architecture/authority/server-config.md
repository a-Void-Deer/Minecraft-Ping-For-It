# Server configuration authority

This focused architecture contract owns who may change persisted server
configuration. It does not own the field catalogue
([server configuration](../../config/server.md)), the request/correlation and
merge transaction
([changing server configuration](../config/changing-server-config.md)), or the
server-side identity and enforcement mechanism ([security](../security.md)).

## Required authority

Changing server configuration requires inherent server permission level 3. This
eligibility governs whether a snapshot may expose editable controls and whether
an update is allowed. Requesting a configuration snapshot does not itself grant
edit authority.

## Capability hints are not credentials

Neither a snapshot's `canEdit` hint nor a request identifier grants edit
authority. Their protocol meaning is owned by
[changing server configuration](../config/changing-server-config.md);
[security](../security.md#server-configuration-update-enforcement) owns how the
server derives and enforces the trusted check.

## Scope of this gate

This authority gate is specific to server-configuration editing. Ordinary marker
creation and removal eligibility are owned by
[target validation](target_validation.md).
