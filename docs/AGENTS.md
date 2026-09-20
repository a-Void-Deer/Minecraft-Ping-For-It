# Documentation Folder Rules

## All Agents

Read `README.md` in this folder before working or searching under `docs/`.

### Scope and exclusions

Documentation describes system design and behavior, not implementation detail.
Retain the behavior, formats, state lifecycles, relationships, authority,
security, and compatibility facts needed to understand the design. Exclude
developer- or maintainer-only material such as class locations, line numbers,
widget-specific tuning, and reference-implementation walkthroughs. Developer
readership is not a reason to remove internal facts that the design requires.

### Ownership

Assign each substantive fact one primary owner based on change responsibility,
following the public ownership policy in [README.md](README.md). Other documents
retain only the necessary interface semantics, an ownership pointer, and a link;
they do not copy catalogues, constants, algorithms, or tuning values.

### Numerical and tuning detail

Keep implementation numeric defaults, bounds, UI steps, and clamp formulas in
code or an existing implementation reference. Config catalogues describe
semantics and format rather than mirroring those values; link their source when
needed, and do not create another numeric catalogue.

This restriction covers tuning values, not every exact fact. Relationship
invariants remain normative, as do the meaning of numeric sentinels, permission
policy, and protocol IDs or grammar/format values where they define behavior.
Config client and server documents own their complete filenames, keys, formats,
legal enum values and selectors, their meanings, helpful examples, relations,
locality, authority, and references. Keep config grammar examples and their
actual tokens in the config owner, not repeated here.

## Subagents

Do **not** modify `docs/` unless both are true:

- you are **explicitly authorized** to edit documentation;
- your assigned task contains **no code changes**.

Otherwise, do not edit `docs/`. Report the documentation changes you believe are needed and return these rules to the main agent.

Code-change authorization does not imply documentation authorization.

## Main Agent

Require implementation subagents to report possible documentation changes instead of editing docs.

Decide whether those changes are needed, then either update the documentation yourself or assign a documentation-only subagent.

If not sure whether something should be recorded, or where it should be, **ask the user** to help you make decision.
