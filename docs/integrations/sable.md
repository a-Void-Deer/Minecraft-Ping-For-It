# Sable external-block integration

## Scope and optional boundary

Sable is an optional provider for external block targets. Its common contract
contains identifiers and opaque strings only; it does not expose Sable objects
through the common target model. Missing Sable classes, an unavailable provider,
API shape drift, or a linkage failure disables this integration softly. Ordinary
entity, block and location pings remain available. There is no cross-dimension
entity tracking or Immersive Portals behavior here.

The additive content read source has its own discovery gate: an absent or
drifted content API, including the additive membership discovery that confirms
a position's containing sub-level, disables only the new content reads and
preview, while established capture, validation and materialization, leases and
Basic observation keep their existing lifecycle.

## Client capture and presentation

Client capture creates an external candidate only after positive Sable
sub-level containment; otherwise it preserves the existing projected-position
or location fallback. Server validation and materialization remain required
before that candidate can become a Marker. This is the ordinary projected
capture path; the separate supplemental transformed-behind discovery is
described below.

During server validation, Sable uses its logical pose to derive the external
validation anchor. Client presentation separately applies the current render
pose to live local block data. The validation and render-pose roles do not alter
the candidate or committed identity defined below.

A candidate that came from an actual native block hit carries that
provider-local hit face; a synthetic miss, an inside hit or an unobserved
containment contact carries none, and the face is never a world-transformed
direction. That provider-local face is the frozen read scope for the
candidate's inventory source access. The face contract is owned by
[capture](../architecture/picking/capture.md#ordinary-and-asynchronous-capture).

A client content preview resolves a candidate's live local block through
Sable's client companion: positive sub-level membership and loaded local state
precede any content read, and the resolved physical block is then subject to
the same client block-observation gates as an ordinary block. A generic
block-entity name without synchronization evidence is not a local value and the
field falls back to the authorized server preview, owned by
[target content preview](../architecture/presentation/presentation_snapshot.md#target-content-preview).

The current external model route and external fallback independently resolve
provider presentation. The required shared-subject and subject-type contract,
and the fact that a single immutable frame snapshot is not currently
guaranteed for those provider-local decisions, are recorded in
[presentation subjects](../architecture/rendering/presentation_subjects.md) and the
[verification inventory](../testing/verification.md#sable-integration-coverage).

## Supplemental transformed-behind discovery

The native selector's Precise live candidate capture includes a separate
Sable provider ray. It is the adopted bounded discovery of a Sable surface
behind a blocker or after a native miss, independently of the ordinary
projected capture above; it never changes, replaces or extends that ordinary
capture. Each live cast uses the current camera ray bounded by the hold-frozen
client `pingDistance` under the
[range contract](../architecture/picking/range.md#selector-candidate-supplements)
and the hold's frozen selection policy.

The provider walks its raw loaded-sublevel list directly, charging every
sublevel visit and provider call to the bounded candidate work budget and
re-checking the list for stability before reporting completion. A removed,
mismatched or unresolvable sublevel is skipped as positively empty or makes the
attempt incomplete; it never becomes a candidate. For each visited sublevel,
the logical pose is frozen for the attempt, the frozen world endpoints are
inverse-transformed into local space, the local bounds are intersected, and the
direct local native pick runs against that sublevel's own loaded local view
under the frozen selection policy. It does not recursively project a world hit
through candidate sublevels to locate the source.

A local hit is transformed back to world space and counted at its world
distance from the frozen ray origin only when it lies on the frozen world
segment within the integration's small projection epsilon; a transformed hit
outside the segment is not counted, and an off-segment deviation beyond that
epsilon makes the attempt incomplete. A discovered candidate carries positive
capture-local provider equivalence, the provider-local native hit face when the
local native pick actually hit a face, and keeps the canonical external
identity unmaterialized under the ordinary
[candidate and committed identity](#candidate-and-committed-identity) contract
below; the provider ray never materializes a tracking identity itself. This
path discovers targets only; it reads no inventory and grants no inventory
preview or tracking. Numeric engine limits remain implementation values, and
the integration's established provider API gate still applies.

## Candidate and committed identity

A client-side external candidate has an empty `stableTargetId`. It is not a
committed `TargetKey` or Marker and must be validated and materialized by the
server provider first. The candidate carries its dimension, provider ID,
expected block registry ID, opaque provider locator and block-entity
classification metadata.

Materialization generates or reuses a provider tracking UUID and produces a
committed target. The committed stable identity and its common
provider-independent domain constraints, including the identity quartet, the
non-identity status of the locator/anchor/classification fields, and the field
bounds, are owned by the
[target model](../architecture/identity/target_model.md#external-block-identity).
The provider-owned candidate fields listed above remain part of this
integration.

## Server validation and materialization

Provider validation is the nonallocating first phase. It checks provider ID,
candidate status, current dimension, locator encoding, expected registry ID,
live sublevel/container, local level, loaded local block, non-air state,
matching block registry ID, logical pose, and finite transformed validation
anchor. The normal server range check uses that anchor. Validation allocates no
tracking reference.

After validation, `MarkerCreationService` initially classifies the normalized
target and checks the requested Ping Type's membership. Only then does provider
materialization resolve live state again, create or reuse a tracking reference,
and replace the target/anchor with committed values. The service reclassifies
that committed target and repeats the requested Ping Type membership check. It
does **not** range-check the replacement anchor a second time. If the acquired
materialization later fails reclassification, fails the post-materialization
Ping Type check, or fails marker storage, it releases that reference rather than
committing the target.

A successfully committed reference is counted: marker removal, expiry, owner
disconnect, audience-empty cleanup, and server shutdown release it, and the last
reference retires the tracking point.

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

## Server information sampling

Server presentation may observe a committed Sable target through the generic
[external-block Basic sampling contract](../architecture/presentation/presentation_snapshot.md#external-block-basic-sampling).
This observation is read-only: it follows the committed stable tracking ID,
requires an active existing reference, and resolves the tracking point's current
Sable sublevel and local block position. It does not use a stale provider
locator or the logical-pose world anchor as a substitute for the current local
position.

The same active-reference rule governs the provider's committed content read
binding: it resolves the current tracking point's sub-level and local position,
ignores a stale committed locator, and cannot resolve after the reference is
released. A candidate content read instead resolves the candidate's own encoded
locator with positive membership and allocates no tracking reference. Either
binding keeps the original detached target identity separate from the
provider-confirmed physical position and logical range anchor, and neither
force-loads a chunk or sublevel; the generic binding rules are owned by the
[generic contract](../architecture/presentation/presentation_snapshot.md#external-block-basic-sampling).

The inventory source uses these same bindings. A preview request reads a
candidate's members only under its provider-confirmed scope, and a tracking
lease follows the committed tracking point; every member position is gated
before its state, block entity, capability or content is read. A moved source
or a changed member topology invalidates the old observation and the next
successful read starts a fresh baseline, and an expired or released external
lease is never resurrected by ordinary-block recovery tolerance. Inventory
identity, read-scope and recovery rules are owned by
[inventory](../architecture/presentation/inventory.md).

The resolved Sable sublevel must expose the same `ServerLevel` as the marker's
parent server level. Its local coordinates remain distinct from the logical-pose
world anchor used for authoritative validation. A removed, unloaded, mismatched,
unregistered, or released source is unavailable, and observation never force-
loads a chunk or sublevel.

Observation cannot materialize a target, acquire, release, migrate an index,
persist, or create provider tracking state, and it cannot create a new target
from a sample. Provider failures remain fail-soft. The generic Basic capture,
demand, atomic failure, and stale-retention rules remain owned by the linked
presentation snapshot contract.

## Names, permissions and diagnostics

Sable names are resolved from authoritative live block state and, when present,
the live `Nameable` block entity. Sable adds no administrator permission.
MarkerCreate still requires an authenticated sender and passes server
rate/channel policy, range, provider validation/materialization and allowed
Ping-Type checks. MarkerRemove still requires ownership of the active marker.
Server-settings editing remains the separate permission-level-3 operation documented in
[security](../architecture/security.md).

Reflection and provider failures are logged through bounded, rate-controlled
diagnostics with complete exception details where the diagnostics contract
requires them; they do not become hard dependencies for unrelated pings.

Related contracts: [target identity](../architecture/identity/target_model.md),
[presentation subjects](../architecture/rendering/presentation_subjects.md),
[server validation](../architecture/authority/target_validation.md),
[server authority decision](../decisions/D0004-server-authority.md), and
[Sable coverage and pending scenarios](../testing/verification.md#sable-integration-coverage).
