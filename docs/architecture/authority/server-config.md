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

## Not OP-gated by this rule

This authority gate is specific to server-configuration editing. Ordinary
`MarkerCreate` is not OP-gated; it is governed by packet validity, rate and
channel/team policy, target validation, and allowed Ping Type. `MarkerRemove`
is also not OP-gated, but it succeeds only for an active marker owned by the
requester. Administrator status does not replace that ownership contract.
