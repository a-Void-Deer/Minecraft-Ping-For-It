# Testing and verification

This page explains what verification evidence means and routes to the files
that own it. The verification set separates automated coverage known to exist,
gaps that remain, and manual/integration scenarios that are still pending. An
inventory statement records established coverage scope; it is not a claim that
a test, build or game session was run for the current change.

Each verification page owns the established coverage, limitations and known
gaps for its scope. [Build checks](build-checks.md) owns build, source-set and
artifact evidence; [manual and integration
verification](manual-integration.md) owns pending scenarios; the topic pages own
automated coverage and gaps for their subsystems. This page is not an index of
every task or run.

For implementation changes, the tracked verification expectations are to:

- run the relevant automated tests and repository lint, static-analysis, format,
  type, and compile checks;
- build every affected Fabric, Forge, and NeoForge source set, and run
  `verifyModIdentity` when loader packaging, artifact identity, or the complete
  shippable artifact set is in scope; and
- report every command actually run and its result, every applicable check not
  run and the exact reason, manual validation actually performed, and remaining
  manual or integration gaps in the change's task report. The report is
  change-specific; persistent verification pages record only coverage, gaps and
  durable evidence under
  [Recording future evidence](#recording-future-evidence).

Use the public commands and task orientation in the
[repository README](../../../README.md#install-build-and-verify). Local maintainer
or agent instructions may add workspace-specific execution constraints, but are
not a public prerequisite and do not replace these tracked expectations. Never
report rendering, multiplayer, interaction, or other manual behavior as verified
unless it was actually exercised or covered by an appropriate automated test.

For a documentation-only change, check the changed documents' links, headings,
fenced blocks, and affected coverage statements. Report those checks as the
commands or review actually performed; document review alone is not runtime,
build, multiplayer, rendering, or manual gameplay validation.

## Evidence responsibilities

Each page below owns a stable part of the evidence set; the
[documentation guide](../../README.md) routes product behavior to its normative
topic owners. These pages own evidence, limitations and gaps, not product
contracts.

| Page | Responsibility |
| --- | --- |
| [Build checks](build-checks.md) | Source-set, build-task, artifact-identity and documentation-tooling evidence and its limits; durable execution evidence for identified inputs. |
| [Manual and integration verification](manual-integration.md#pending-manual-and-integration-matrix) | Pending manual/integration scenarios; listing a scenario does not record a completed run. |
| [Identity and authority](identity-authority.md) | Domain and target identity, authority, client marker runtime and rate-policy coverage. |
| [Presentation](presentation.md) | Presentation negotiation, policy, adapters, wire projection, content preview and receipt coverage. |
| [Interaction](interaction.md) | Capture, wheel, cancellation, selection, picking seams and client interaction runtime coverage. |
| [Configuration and settings UI](configuration-ui.md) | Configuration grammar, server-settings, logging-profile, HUD and settings-screen coverage. |
| [Shared sources](shared-sources.md) | Shared-source identity, access, capture results, sync publication and cost accounting coverage. |
| [Inventory](inventory.md) | Inventory preview/tracking, logical containers, pools, budgets, snapshots, HUD and performance coverage. |
| [Rendering](rendering.md) | Names, geometry outcomes, presentation subjects, overlay layout and renderer seams. |
| [Integrations](integrations.md) | Diagnostics, Create, Sable, Simulated and optional-rendering coverage. |

Each topic page keeps its coverage and local known automated gaps together.

## Known gaps

The gaps owned by each topic page remain open until a named automated check or
an actually performed manual/integration scenario closes them. Compilation,
document review, optional-API loading or unit-test seams alone do not convert a
pending manual scenario into completed validation. Build evidence is scoped in
[build checks](build-checks.md); pending live scenarios remain in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Recording future evidence

- Add or update focused tests in the same change as behavior where practical.
- Record execution evidence in a verification page only when it remains useful
  beyond the change: retain the input and scope, commands or scenario, result,
  material limitations, and sufficient traceability such as revision,
  environment, or artifact identity to interpret the record.
- Durable evidence retains any failure, unexecuted check, or material input
  difference not represented by another record.
- Keep raw logs and full execution histories in the change's external evidence
  report. Do not use a personal account path or temporary directory as the
  shared locator for evidence, and do not promise that externally held logs
  remain available. Required toolchain facts such as JDK, platform, and
  dependency versions are legitimate evidence, not personal information.
- Keep automated coverage, commands actually run, and manual observations as
  separate claims in implementation reports.
- Update the owning topic contract when expected behavior changes; changing a
  test alone does not redefine the product.
