# Documentation Folder Rules

Read [README.md](README.md) before any other work under `docs/`.

## Scope

Document system design and behavior, including the internal details needed to
understand formats, state lifecycles, relationships, authority, security, and
compatibility. Exclude code walkthroughs and incidental implementation structure,
such as class locations and source line numbers.

Keep authoring and agent procedures in `AGENTS.md`, outside product documentation.

## Ownership and exact detail

Follow the ownership policy in `README.md`. Give each substantive fact one
primary owner based on change responsibility. Other pages may retain necessary
interface semantics and a link; do not duplicate detailed rules, catalogues,
algorithms, or values.

Do not link product documentation to source-code files or line numbers.

Keep implementation tuning defaults, bounds, UI steps, and clamp formulas in
code or an existing implementation reference. Do not reproduce these tuning
values in topic or configuration pages. Link to an existing documentation
reference when needed.

Preserve exact facts that define behavior, including protocol IDs, grammar
tokens, numeric sentinels, permission requirements, ordering, and relationship
invariants.

The client and server configuration pages own their complete filenames, keys,
formats, legal enum values and selectors, meanings, useful examples,
relationships, locality, authority, and references. Keep configuration grammar
examples and their literal tokens in those pages.

## Writing style

Identify the page's reader question and document type: behavioral contract,
architecture explanation, decision record, reference, verification inventory,
or navigation. Use a form appropriate to that purpose.

Keep statements that contribute necessary behavior, relationships, rationale,
reference information, evidence, or navigation. Omit generic assurances and
statements about unaffected subsystems unless they specify a requirement or
resolve a plausible ambiguity in the page.

Preserve negative statements that exclude a plausible implementation, clarify
authority or side effects, define failure behavior, or limit an evidence claim.

After drafting or during reviewing, ask: "What useful information or connection would the reader
lose if this sentence were removed?" Delete sentences with no defensible
contribution. Keep editorial explanations and deletion justifications outside
the finished document.

## When to update

Update the owning document when its subject changes or existing information is
incorrect, missing, or unclear. A code fix alone does not require changing an
already accurate behavioral contract.

Keep fix narratives and incidental development history out of behavioral
contracts. Do not add "now," "no longer," or "currently" merely to describe a
completed fix. Decision records may include relevant decision context and
status; verification records may include coverage, results, limitations, and
gaps.

Assess test changes separately under
[D0007: No meaningless tests](decisions/D0007-no-meaningless-tests.md).

## Agent roles

Subagents may edit `docs/` only when explicitly authorized to edit documentation
and assigned a task containing no code changes. Otherwise, report proposed
documentation changes to the main agent.

The main agent decides which changes are needed, then makes them or delegates
a documentation-only task. Resolve routine content and placement questions
using `README.md` and these rules. Ask the user when unresolved uncertainty
about intended behavior, document authority, or task scope prevents a correct
change.

## Explaining documentation

When explaining implemented behavior, consult the owning documentation and
relevant code when available. Distinguish the documented contract from verified
implementation behavior, and report discrepancies or missing evidence.

Include an in-game example or scenario when it clarifies the behavior.