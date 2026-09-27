# D0007: No Meaningless tests

## Status

Confirmed decision. It defines the purpose boundary and
oracle requirements for automated tests; it is not a coverage inventory and
does not assert that every existing test already satisfies it. Coverage and
known gaps remain owned by
[testing and verification](../testing/verification.md).

## Decision

A test earns its place only when it protects a named contract, boundary, or
regression with an expectation independent of the code under test. Test
additions and reviews follow this boundary:

- Do not add, and remove where present, tests whose only assertion is that a
  tuning value or default equals a copied literal. Such a snapshot detects a
  tuning edit rather than a behavioral regression. A semantic default that is
  itself policy, such as a client following the server's marker duration
  instead of a local number, is behavior and remains testable.
- A tuning or default change must not break tests of unrelated clamp, ordering,
  migration, or authority behavior. A test depending on shared defaults
  establishes its relevant preconditions explicitly and asserts the
  relationship, invariant, or meaningful positive/negative boundary it owns.
  Boundary probes may sit relative to a shared valid limit, including just
  outside it where the permitted range can represent that value, but the
  expected outcome comes from the contract independently of the code under
  test, not from the same helper or formula under test.
- Replacing a numeric snapshot with an assertion that reads the same constant,
  or with an expectation computed by the same helper or formula under test,
  regains no oracle.
- Source text, private structure, exact member-name lists, and fixed prose are
  not oracles. They are acceptable only when a concrete boundary or realistic
  regression makes that static fact the thing being protected and the test is
  narrowed to it; this is not a blanket ban on source-, reflection-, or
  implementation-level tests. A static seam shows structure, not that a
  runtime path executes.
- Legitimate static or structural coverage includes loader lifecycle behavior,
  registration and optional linking, exact mixin or hook targets with their
  descriptors and signatures, supported versions, protocol identifiers and
  grammar or format rules, semantic sentinels, persisted and wire
  serialization formats, host constraints, and deterministic algorithm
  invariants. Even then, whitespace, local variable names, or incidental
  source-token occurrence counts do not establish that boundary; prefer
  evidence matching what is actually protected. A real invocation count can
  protect lifecycle or single-consumption behavior and is not excluded.
- When deciding whether to keep, narrow, or delete a test, weigh the protected
  contract and owner, the independence of the oracle, the realistic regression
  the test catches, its sensitivity to harmless refactors, and its overlap with
  stronger tests that retain the same unique guarantee. Different seams are not
  automatically duplicates; delete overlap only when the unique guarantee
  survives elsewhere.

## Rationale

Tuning values change for ordinary product reasons. When tests copy them, every
adjustment produces failures across tests that do not own the changed value,
burying the failures that do. The same coupling hides in inherited fixture
defaults: an unrelated default change silently moves a test's starting state
and expected values, so the test stops expressing the relationship it names.

Assertions over source spelling and private structure look like deep coverage
but usually restate implementation shape. They fail on harmless renames and
restructuring, pass when behavior is wrong but the shape is preserved, and turn
maintenance into upkeep of an accidental interface. Protocol identifiers,
version and grammar facts, sentinels, and explicitly confirmed product
boundaries are different: their exact value is part of the promised behavior,
so pinning them remains legitimate.

## Alternatives considered

- **Keep constant snapshots as cheap regression detection.** Rejected: the
  snapshot identifies a number, not a behavior; it fails on unrelated tuning
  and passes when a behavioral regression preserves the number.
- **Forbid all source-, reflection-, and implementation-level tests.**
  Rejected: loader lifecycle, optional registration, hook targets and
  serialization formats cannot always be exercised in the common test runtime.
  Necessity and narrowed scope are the requirement, not elimination.
- **Pin every fixture to explicit tuning constants.** Rejected: that replaces
  an accidental coupling with a maintenance burden and still tests literals
  rather than relationships. Fixtures state the preconditions they depend on.
- **Derive expected values with the production helper under test.** Rejected:
  the assertion becomes tautological and cannot detect a wrong helper or
  formula.

## Consequences

New tests state the behavior or boundary they protect and where the independent
expectation comes from. Review applies the ownership, oracle, regression and
overlap questions above. Default and bounds changes require test updates only
where the changed value is the test's own subject or an explicitly established
fixture precondition. A remaining static requirement is named with the boundary
it guards so later readers can judge the necessity instead of the spelling.

## Examples

1. **Unrelated default coupling.** A test of how configuration options
   constrain each other must not inherit an unstated starting value and then
   fail when an unrelated default is tuned. Establish the relevant
   preconditions and verify the relationship, rather than copying incidental
   default outputs.

2. **Source spelling instead of behavior.** A test must not infer correct
   behavior from a particular local name, source fragment, or number of
   constructor expressions. Harmless refactoring should not break it, and
   preserving those spellings must not hide an incorrect outcome. Verify the
   required result; reserve exact identifiers for genuine host or compatibility
   contracts.

## Related docs

This record explains test purpose and oracle boundaries; product behavior
remains owned by the linked topic documents.

[Testing and verification](../testing/verification.md),
[client configuration](../config/client.md),
[server configuration](../config/server.md),
[network protocol](../architecture/network/protocol.md),
[presentation subjects](../architecture/rendering/presentation_subjects.md),
[names and chat](../architecture/rendering/names_chat.md), and
[compatibility](../compatibility.md).
