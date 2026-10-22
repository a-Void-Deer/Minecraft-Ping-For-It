# D0010: Native persistent component codec trust

## Status

Confirmed product decision represented by the linked topic contracts. This
record explains the trust trade-off, not completed implementation or verification.

## Decision

Inventory variant identity follows native persistent semantics rather than a
closed list of component types understood by Ping. Generic registered
persistent components have an explicit external-trusted native-codec route
alongside the audited native fast path. Identity fidelity and isolated display
are separate responsibilities; a display limitation does not redefine an exact
variant.

[Inventory](../architecture/presentation/inventory.md#item-variants-and-component-fallback)
owns component classification, identity, display and the precise trust boundary.
[Shared sources](../architecture/presentation/shared_sources.md#cost-accounting)
owns generic admission and accounting, not the component policy.

## Rationale

An extensible component registry can carry persistent item meaning that Ping
does not recognize. Equating unfamiliarity with unsafe identity sacrifices
variant fidelity even when the game's registered codec can describe it. Native
persistent semantics provide the compatibility boundary without requiring Ping
to mirror each mod's component model.

That choice cannot also certify arbitrary codec or provider internals. Native
encoding can invoke external behavior and caches before returning a small value;
measuring the output cannot establish an internal work or allocation bound.
Explicit trust makes that limitation visible while preserving guards over
Ping-owned processing and detached output.

## Why not other approaches

- Do not silently omit unfamiliar persistent semantics and call the remainder
  exact: distinct native variants could become indistinguishable.
- Do not require a separate component integration for generic compatibility:
  that would turn the audited catalogue into the definition of item identity.
- Do not promise complete unknown-component icons as a consequence of exact
  identity: display also needs isolation from opaque values and native hooks.
- Do not treat codec compatibility as permission to remove pipeline guards:
  external trust and owned resource admission answer different questions.

## Consequences

Compatibility deliberately includes uncertified external work and retention;
it is not a whole-JVM, wall-clock or allocation guarantee. The owning contracts
keep that boundary explicit rather than attributing external behavior to bounded
Ping-owned processing. Inventory source recovery and marker lifetime remain
independent decisions under [D0008](D0008-inventory-source-recovery.md).

Coverage and pending runtime evidence remain owned by
[testing and verification](../testing/verification/inventory.md); this decision
records no test or manual result.
