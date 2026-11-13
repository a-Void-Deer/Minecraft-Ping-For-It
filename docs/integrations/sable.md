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
[verification inventory](../testing/verification/integrations.md#sable-integration-coverage).

## Transformed entity frames and frozen block proof

Sable can supply a detached optional parent frame for the exact retained rotor
identity owned by [Aeronautics](aeronautics.md#scope-and-optional-boundary).
The server-safe logical lookup and the client render lookup both require
positive native containment of the entity's current base position. Their
resolution has three states:

| State | Meaning |
| --- | --- |
| `WORLD` | Sable is absent, the entity is outside the exact rotor scope, or native containment confirms no containing sub-level; the frame is identity. |
| `CONTAINED` | The entity is positively contained; the result carries a copied affine parent frame and the sub-level ID. |
| `UNAVAILABLE` | The exact rotor's containment or optional lookup/pose failed or produced unusable data. It is never converted to identity. |

Positive containment only supplies the optional frame. It does not establish
standing or support, server authority, a tracking reference, or any lease.
The picking path uses a query-local frozen logical pose for the local native
ray; the client marker path uses the current render pose at the render tick; the
server uses the copied logical pose to project the rotor's native base position
for authoritative acceptance. Repositioning the provider's live pose after a
copy does not move the retained frame. Range and rejection behavior are owned by
[range](../architecture/picking/range.md#server-acceptance) and [target
validation](../architecture/authority/target_validation.md), and the marker
fallback by [client marker state](../architecture/markers/client-state.md#live-entity-display-position).

A Sable-scoped native clip can return a provider-local `BlockHitResult`. A
successful clip freezes the queried logical pose and may attach a detached proof
that binds the sub-level ID, immutable local block position, local hit location
and frozen parent frame. The native hit result, face and local position are
preserved; world comparison separately uses the proof frame's projection of the
local hit location, never the native plot coordinate.

When the optional pose scope cannot be used, the native clip runs unscoped; a
failure while freezing a queried pose restores the previous scope before retry.
A confirmed ordinary-world block still receives its ordinary world
point, may occlude a farther entity and may create an ordinary candidate
acquisition. A provider-local block without observed frozen proof, or a block
whose containment lookup fails, has no trusted world point. Its preserved
native result follows [local-geometry comparison](../architecture/picking/local_geometry.md#distance-and-native-local-shapes)
and [capture eligibility](../architecture/picking/capture.md#precise-live-candidate-capture).
A capture using a proof validates the current provider identity, local binding
and loaded state, then uses the frozen proof rather than resolving a newer pose
for the candidate.

The transformed-frame capability is independently optional. Its absence or
failure is isolated from the established external-block provider's capture,
validation, materialization, leases, Basic observation, content reads and
block/content link guard. Optional clip-scope failure also remains independent
of stable entity-frame lookup.

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
preview or tracking. The integration's established provider API gate still
applies.

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

After validation, server marker creation first classifies the normalized
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

The inventory source uses these same bindings. An `ITEM` preview request reads a
candidate's members only under its provider-confirmed positive scope, and a
tracking lease follows the committed tracking point; every item member position
is gated before its state, block entity, capability or content is read. A
`FLUID` source uses the provider's positive scope endorsement for its internal
storage unit under
[fluid provider trust](../architecture/presentation/inventory.md#fluid-provider-trust-and-validation-exceptions),
without independently gating every member; the target-level checks remain owned
by that contract. `UNKNOWN` root membership is never a positive endorsement. A
moved source or a changed member topology follows ordinary invalidation and
fresh-baseline recovery, except for the narrow fluid transition below; internal
member-layout evidence for a `FLUID` unit remains provider-owned. An expired or
released external lease is never resurrected by ordinary-block recovery
tolerance. Inventory identity, read-scope and recovery rules are owned by
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

### Fluid sub-level transition

When the same live lease still follows the same target, only its Sable sub-level
changes and all other source identity and layout remain continuous, fluid
tracking enters `UPDATING` and keeps the quantity already displayed while
recomputing. That transition alone sends no `INVALID`, does not clear the
retained quantity and does not repeat a content receipt.
It preserves the marker and its original expiry; item tracking and ordinary
multiblock topology invalidation do not acquire this exception.
Simultaneous scope and structural changes still take ordinary invalidation;
scope smoothing cannot conceal a changed source or layout.

The new scope must be resolved and authorized afresh, with new physical source
evidence and an independent zero-based accumulator. Old queued success data is
fenced from the new scope. The retained quantity is presentation history only:
neither the old cut, preview seed, historical proof nor old partial accumulator
can establish new-scope completion or absence-derived zero. A fresh usable
complete observation, with its delivery barrier satisfied, atomically replaces
the displayed quantity. Actual revocation, an unreadable or missing source,
lease release, removal and expiry still follow the ordinary
[inventory failure and lifetime rules](../architecture/presentation/inventory.md#source-identity-invalidation-and-recovery).
The transition authorizes no new target or storage context.

## Names, permissions and diagnostics

Sable names are resolved from authoritative live block state and, when present,
the live `Nameable` block entity. Sable adds no administrator permission.

Reflection and provider failures follow
[diagnostics and logging](../architecture/logging.md).

Related contracts: [target identity](../architecture/identity/target_model.md),
[presentation subjects](../architecture/rendering/presentation_subjects.md),
[server validation](../architecture/authority/target_validation.md),
[server authority decision](../decisions/D0004-server-authority.md), and
[Sable coverage and pending scenarios](../testing/verification/integrations.md#sable-integration-coverage).
