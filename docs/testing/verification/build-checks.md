# Build checks

[Verification index and evidence rules](README.md) ·
[Pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)

## Build, source-set and artifact verification

The included Gradle projects are `common`, `fabric`, `forge`, and `neoforge`.
Their loader source sets receive common Java and resources through the shared
loader wiring. Fabric retains the common mixin configuration and Loom-generated
intermediary refmap; Forge and NeoForge use their loader-local official-Mojmap
configuration instead. This routing identifies existing tasks and artifact
purposes only; because each loader source set compiles the shared common
sources, an affected loader `build` is source-set and compile evidence for
those units, not runtime, transport, or in-game verification. Fabric and Forge
additionally carry a test source set whose headless native-wrapper seam tests
remain seam evidence only, not loader-launch, mixin-application or in-game
evidence. Public build
orientation and commands are listed in the
[repository README](../../../README.md#install-build-and-verify); the tracked
[geometry pipeline](../../architecture/geometry/geometry-pipeline.md) is the public
architecture entry point. Local agent instructions, when present, are
supplementary execution guidance rather than a public documentation prerequisite.

An inventory entry or static wiring test is not a Gradle execution record.
No separately configured lint, formatter, or static-analysis task exists; the
automated checks are the Gradle test tasks and the loader build and
artifact-identity tasks below. Final-state evidence for an implementation
change must identify the combined
tree, commands and results for the affected tests and loader source sets;
blocked or unrun tests, builds and artifact checks remain explicit gaps in that
change's report. Earlier runs do not validate subsequent relevant edits.

Captured combined-tree evidence includes passing headless suites, main/test
compilation, Javadoc generation, loader builds and `verifyModIdentity`. These
results establish automated test/build and configured artifact-identity evidence
for their identified inputs, not independent package-content or shipped-namespace
validation. Separately exercised named-host Mixin probes use their own captured
compiled inputs. Package-content and namespace audits and live validation require
distinct evidence; neither automated builds nor isolated probes substitute for
them. Full logs, input manifests, artifact hashes and detailed execution history
belong in the change's external evidence report; a scoped run summary below does
not replace that record.

| Module or artifact scope | Task | Purpose |
| --- | --- | --- |
| `common` test source set | `:common:test` | Runs the common JUnit Platform tests, including shared behavior and integration seams. |
| `fabric` test source set | `:fabric:test` | Runs the Fabric JUnit Platform tests for the native platform wrapper seams headlessly. |
| `forge` test source set | `:forge:test` | Runs the Forge JUnit Platform tests for the native platform wrapper seams headlessly. |
| `neoforge` test source set | `:neoforge:test` | Runs the NeoForge JUnit Platform tests, including NeoForge-specific resolver coverage. |
| Affected loader source set | `:fabric:build`, `:forge:build`, or `:neoforge:build` | Builds the affected Fabric, Forge, or NeoForge source set and its loader jar. |
| All shippable loader artifacts | `verifyModIdentity` | Depends on all three loader `build` tasks, then inspects the expected Fabric, Forge, and NeoForge jars in their loader `build/libs` directories for fork identity. |

## Server Performance combined-tree run

The server Performance and version-four administration change has a recorded
combined-tree run on 2026-10-26. Its tested source inputs correspond to commit
`acf707aa25e5d99bbcf0d96fb881bb18a1a11ce4`. The run used JDK 21, PowerShell and
UTF-8 with separate stdout/stderr files:

```powershell
.\gradlew.bat --no-daemon build verifyModIdentity
```

It exited zero with `BUILD SUCCESSFUL` in 4m 23s: 56 tasks, 38 executed and 18
up-to-date. All four module test tasks executed, and all 475 JUnit XML reports
were fresh:

| Module | Suites | Tests | Failed / errors / skipped |
| --- | --- | --- | --- |
| Common | 438 | 4732 | 0 / 0 / 0 |
| Fabric | 5 | 24 | 0 / 0 / 0 |
| Forge | 5 | 26 | 0 / 0 / 0 |
| NeoForge | 27 | 174 | 0 / 0 / 0 |
| Total | 475 | 4956 | 0 / 0 / 0 |

Main compilation, Javadoc generation, the configured checks and all three
loader builds completed; all three shippable loader jars were rebuilt and
`verifyModIdentity` passed. Before/after manifests matched for all 1168 source
inputs and 71 documentation files during the run. The captured stderr contains
deprecation notes and Javadoc warnings, so this is not a warning-free claim.

The exercised seams and their limitations are inventoried in
[configuration verification](configuration-ui.md#server-performance-contract-verification)
and [inventory verification](inventory.md#resynchronization-and-budget-policy-verification).
The run does not establish live loader registration, native screen rendering,
multiplayer transport, performance measurements or completion of UI review.
Live scenarios remain in the
[manual matrix](manual-integration.md#settings-and-server-performance).
