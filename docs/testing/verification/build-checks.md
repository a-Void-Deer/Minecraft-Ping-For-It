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

## Preview-seeded tracking combined-tree run

The final bootstrap implementation inputs correspond to
`188ae241d54fba91c1c6a917928557024a8af405`, including the preceding display/status
fix `2ff0a35e`, source-authority publication fix `696201dc` and bootstrap
implementation `fca26308`. The recorded run used Java 21, PowerShell, UTF-8,
no explicit timeout and separate stdout/stderr logs:

```powershell
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain
```

The invocation exited nonzero because `:common:test` failed; it is not a fully
passing check/build run. Captured final XML reports record:

| Module | Tests | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Common | 4781 | 4759 | 22 | 0 | 0 |
| Fabric | 24 | 24 | 0 | 0 | 0 |
| Forge | 26 | 26 | 0 | 0 | 0 |
| NeoForge | 174 | 174 | 0 | 0 | 0 |
| Total | 5005 | 4983 | 22 | 0 | 0 |

All 22 failing test identities match the isolated unmodified `efd21e2b`
baseline reproduction: 13 `BudgetDelayMonitorTest`, eight
`BudgetDelayMonitoringTest` and one `InventoryBudgetDelayIntegrationTest` case.
The notification fixtures expect 20/200-tick threshold/cooldown timing while
the implementation uses 60/600. This known mismatch remains unresolved; the
bootstrap run does not justify an all-tests-pass claim.

The final history-focused run passed 195 tests with no failures, errors or
skips across bootstrap publication, tracking renderer, both client inventory
suites, publication integration, publisher, reconfiguration, accepted-store chat
and runtime receipt suites. The preceding display-focused run passed 182 tests
and the authority-focused run passed 232 tests, also with no failures, errors or
skips. The authority run covered backend, authority order, patch flow, Sable
handoff, native read wiring, publication integration, reconfiguration, bootstrap
publication, publisher and receipt suites, plus the expiry and revocation
cap-reprojection budget-delay cases. The final full run includes all three repairs
and all module suites.

All three loader builds and `verifyModIdentity` completed successfully. The
final invocation executed all four module test tasks and the loader compilation,
Javadoc and build work; unchanged test-compilation and Fabric access-widener
validation tasks were up-to-date. Those task outcomes are captured in the logs,
not inferred from document review. Deprecation, mapping/processor and Javadoc
warnings remain. Full command manifests, stdout/stderr and saved XML
are external evidence; this documentation-only phase inspected them and ran no
Gradle command.

The exercised behavior is inventoried under
[preview-seeded tracking](inventory.md#preview-seeded-tracking-coverage).
Installed-mod, multiplayer, rendering and performance claims remain outside
these headless tests and builds; the
[manual inventory matrix](manual-integration.md#inventory) remains pending.

## Vault tracking performance combined-tree run

The final performance implementation is `ea0339d2cd7513cb91ba9eafe9dddbcc602d6633`,
following harness `a8d36f89`, resident/lazy-scope `095238f8` and Vault validation
`1d0886b1`. The saved 2026-10-27 run used JDK `21.0.12.1+1-LTS`, PowerShell,
UTF-8, separate redirected stdout/stderr, no explicit timeout and:

```powershell
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain
```

It exited 1 after 3m 5s, solely because `:common:test` failed. All 484 captured
JUnit XML reports were fresh:

| Module | Tests | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Common | 4805 | 4783 | 22 | 0 | 0 |
| Fabric | 24 | 24 | 0 | 0 | 0 |
| Forge | 26 | 26 | 0 | 0 | 0 |
| NeoForge | 190 | 190 | 0 | 0 | 0 |
| Total | 5045 | 5023 | 22 | 0 | 0 |

All 22 failure identities equal the earlier baseline mismatch recorded in the
[bootstrap run](#preview-seeded-tracking-combined-tree-run), with no additional
failing identity. One expected-set message differs only in display order; this is
not a byte-identical-message claim or a fully green run. Loader compilation,
checks and all three builds, Javadoc and `verifyModIdentity` completed; common
check was attempted but blocked by its test failure. Deprecated-API,
mapping/processor and Javadoc warnings remain. No separate formatter or
static-analysis task was found in the reviewed Gradle definitions.

The final focused run passed Common 650 and NeoForge 35 tests, with zero failures,
errors or skips. Its exact command was:

```powershell
.\gradlew.bat :common:test --tests 'nx.pingwheel.common.presentation.inventory.minecraft.Inventory*' --tests '*InventoryBackendTest' --tests '*InventoryRuntimeTest' --tests '*InventoryPatchIdentityFlowTest' --tests '*InventoryPublicationIntegrationTest' --tests '*InventoryValidationAdmissionTest' --tests '*ClientDisplay*' --tests '*ClientItemDisplay*' :neoforge:test --tests '*CreateVaultTrackingPerformanceTest' --tests '*ClientDisplayFoodTest' --tests '*VaultCurrentSourceValidationTest' --tests '*VaultInPlaceAccessContractTest' --tests '*CreateVaultSourceWrapperTest' --tests '*CreateVaultInventoryAccessTest' --tests '*CreateVaultInventoryDiagnosticsTest' --no-daemon --console=plain
```

The preceding focused attempt failed test compilation on a temporary annotation
target; copied XML from that attempt was stale and supplies no executed-test
evidence. The corrected focused run and full run above use the final source.
All 1169 source/build input SHA-256 values remained unchanged through full
verification and commit; there was no post-full source or annotation edit.
Detailed commands, hashes, stdout/stderr, XML and failure comparisons remain in
the external baseline/P1/P2/P4 manifests and `vault-perf-p4-full-*` evidence. This
documentation-only update inspected those records and did not run Gradle.

Deterministic coverage and the unchanged-harness allocation comparison have one
owner in [inventory verification](inventory.md#vault-tracking-performance-evidence).
These builds and headless measurements do not establish installed-provider,
live CPU/GC/allocation-rate, gameplay, rendering or multiplayer performance;
the [manual inventory matrix](manual-integration.md#inventory) remains pending.
