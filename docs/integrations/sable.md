# Sable external-block integration

## Scope and optional boundary

Sable is an optional provider for external block targets. Its common contract
contains identifiers and opaque strings only; it does not expose Sable objects
through the common target model. Missing Sable classes, an unavailable provider,
API shape drift, or a linkage failure disables this integration softly. Ordinary
entity, block and location pings remain available. There is no cross-dimension
entity tracking or Immersive Portals behavior here.

## Client capture and presentation

Client capture creates an external candidate only after positive Sable
sub-level containment; otherwise it preserves the existing projected-position
or location fallback. Server validation and materialization remain required
before that candidate can become a Marker.

The server uses Sable's logical pose for the authoritative external anchor.
Client presentation separately applies the current render pose to live local
block data. Those pose roles do not alter the candidate or committed identity
defined below.

The current external model route and external fallback independently resolve
provider presentation. The required shared-subject and subject-type contract,
and the fact that a single immutable frame snapshot is not currently
guaranteed for those provider-local decisions, are recorded in
[presentation subjects](../rendering/presentation_subjects.md) and the
[verification inventory](../testing/verification.md#sable-integration-coverage).

## Candidate and committed identity

A client-side external candidate has an empty `stableTargetId`. It is not a
committed `TargetKey` or Marker and must be validated and materialized by the
server provider first. The candidate carries its dimension, provider ID,
expected block registry ID, opaque provider locator and block-entity
classification metadata.

Materialization generates or reuses a provider tracking UUID and produces a
committed target. Its stable identity is exactly:

```text
dimension + providerId + stableTargetId + expectedBlockRegistryId
```

The provider locator, current anchor and block-entity classification are not
identity. Locator or anchor refresh therefore does not manufacture a new target
or winner. `providerId`, non-empty `stableTargetId` and
`expectedBlockRegistryId` are each limited to 256 characters. The opaque
`providerLocator` is limited to 32767 characters. `dimensionId` must be
non-blank, but has no 256-character external-identifier limit in the common
model.

## Server validation and materialization

The provider checks its provider ID, candidate status, current dimension,
locator encoding, expected registry ID, live sublevel/container, local level,
loaded local block, non-air state, matching block registry ID, logical pose and
finite transformed anchor. The server then applies the normal authoritative
range check and target-type/Ping-Type checks.

Materialization creates or reuses a tracking point. References are counted:
marker removal, expiry, owner disconnect, audience-empty cleanup and server
shutdown release the reference, and the last reference retires the tracking
point. A failed transaction is released or rolled back without committing an
unidentifiable target.

## Refresh lifecycle

Committed external markers are refreshed periodically by the server:

- temporary provider unavailability retains the marker;
- an available target with the same stable identity updates locator/anchor when
  they changed, preserving marker ID, owner, Target/Ping Types, arrival, expiry
  and immutable audience;
- invalid live state or identity drift removes the marker through the ordinary
  server removal path and recomputes the winner.

Refresh keeps the expected registry identity and committed block-entity
classification stable. A provider observation of a changed Java-side flag does
not create a new classification or winner. Refresh and materialization are
provider lifecycle operations; ordinary entity/block markers do not acquire this
continuous revalidation behavior.

## Names, permissions and diagnostics

Sable names are resolved from authoritative live block state and, when present,
the live `Nameable` block entity. Sable adds no administrator permission.
MarkerCreate still requires an authenticated sender and passes server
rate/channel policy, range, provider validation/materialization and allowed
Ping-Type checks. MarkerRemove still requires ownership of the active marker.
Server-settings editing remains the separate permission-level-3 operation documented in
[security](../security.md).

Reflection and provider failures are logged through bounded, rate-controlled
diagnostics with complete exception details where the diagnostics contract
requires them; they do not become hard dependencies for unrelated pings.

Related contracts: [target identity](../identity/target_model.md),
[presentation subjects](../rendering/presentation_subjects.md),
[server validation](../authority/target_validation.md),
[server authority decision](../decisions/D0004-server-authority.md), and
[Sable coverage and pending scenarios](../testing/verification.md#sable-integration-coverage).
