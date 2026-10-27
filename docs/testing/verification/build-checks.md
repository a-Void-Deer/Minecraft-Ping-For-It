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

## Automated verification results

Recorded execution date: 2026-10-27. The saved JDK 21, PowerShell executions
used UTF-8, `--no-daemon`, no explicit timeout and separate stdout/stderr files.
The commands below retain the recorded task arguments; log destinations are
represented by placeholders.

The loader execution compiled and built all three loaders and ran their tests
successfully:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat --no-daemon :fabric:build :forge:build :neoforge:build > $loaderStdout 2> $loaderStderr
```

The combined check/build and artifact-identity invocation exited zero with
`BUILD SUCCESSFUL`:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain > $stdout 2> $stderr
```

The inspected JUnit XML distinguishes fresh execution from reused passing
results. All rows have zero failures, errors and skips:

| Module | Suites | Tests | Evidence in combined invocation |
| --- | --- | --- | --- |
| Common | 446 | 4823 | Fresh complete test execution. |
| Fabric | 5 | 24 | Passing reports from the loader execution; test task `UP-TO-DATE`. |
| Forge | 5 | 26 | Passing reports from the loader execution; test task `UP-TO-DATE`. |
| NeoForge | 30 | 190 | Passing reports from the loader execution; test task `UP-TO-DATE`. |

Common test compilation and tests executed in the combined invocation. All
three loader `compileJava`, `test`, `build` and Javadoc tasks were `UP-TO-DATE`;
the loader jars were reused, not freshly rebuilt. `verifyModIdentity` actually
executed and passed for all three jars with
`pingforit` / `Ping For It` / `0.5.0-pfi-beta2` identity.

Coverage scope is inventoried under
[configuration verification](configuration-ui.md#server-performance-contract-verification),
[inventory resynchronization and budgets](inventory.md#resynchronization-and-budget-policy-verification),
[preview-seeded tracking](inventory.md#preview-seeded-tracking-coverage) and
[shared-source monitoring](shared-sources.md#shared-source-cost-ledger).
The [inventory performance evidence](inventory.md#vault-tracking-performance-evidence)
owns the deterministic Vault coverage and recorded allocation comparison.

## Verification limitations

The combined invocation's stderr was empty; stdout retained the Gradle 9
deprecation notice and Problems-report reference. The build records also contain
deprecated-API, mapping/processor and Javadoc warnings. These are not
warning-free results. Compiler, Javadoc/doclint and configured loader-validation
evidence is scoped to the build/check executions that performed that work; the
combined invocation did not freshly compile or document the loaders.

Passing headless tests, builds and artifact-identity checks do not establish
live loader registration, installed-provider or Create/Refined Storage behavior,
native screen rendering, multiplayer transport, UI review completion or game
performance. The isolated real-jar probe retains its own input and construction
limits in [inventory verification](inventory.md#patch-only-identity-and-display-verification-pending).
The scoped allocation comparison does not measure live CPU, GC, allocation rate,
heap retention or TPS, and automated results do not supply missing independent
scratch-bound review evidence. These limits and the separate named-host Mixin
probe limits are detailed in the
[inventory gaps](inventory.md#known-automated-gaps). The
[manual matrix](manual-integration.md#pending-manual-and-integration-matrix)
remains pending.
