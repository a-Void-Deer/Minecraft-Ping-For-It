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
No separately configured Java lint, formatter, or static-analysis task exists; the
Java/build checks are the Gradle test tasks and the loader build and
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

## Documentation tooling coverage

`test_documentation_links.py` covers the shared Markdown scanner and the
`broken_link_check.py` and `count_duplicate_links.py` CLIs with parser, heading,
target-normalization and disposable-repository process/Git fixtures. Added
regressions distinguish literal backticks in link titles and destinations from
real code spans across inline, reference and image forms, preserving following
links, missing-target reports and duplicate groups. They cover thematic breaks
before Setext headings, valid empty reference destinations, resolved reference
labels followed by literal parentheses, and speculative multiline titles at
fence boundaries. Hidden-definition, forward-reference and duplicate-precedence
cases check the first visible definition without treating paragraph continuations,
real code, comments or fences as definitions.

Subprocess cases check complete UTF-8 reports under cp936 and ASCII child stdout
encodings; imported `main` cases check that caller-owned streams remain unchanged.
Four deterministic scanning-work tests count block classifications, copied slices
and character checks for repeated definitions, inline payloads/code and heading
preparation, rather than relying on timing thresholds. These finite workloads do
not prove universal adversarial-linear complexity. The coverage is local scanner
and CLI evidence, not complete CommonMark/GFM equivalence or validation of
external URIs; embedded CLI help remains the syntax and output behavior reference.

Recorded execution date: 2026-11-01, code input
`6c2f1db2ed113a774d6ffc935b3dd419c3a462af`.
`python -B scripts/docs/tests/test_documentation_links.py -v` passed all 69 tests:
65 semantic cases and the four scanning-work cases. AST parsing and in-memory
compilation passed for all four Python files. No separately configured Python
lint, type-check or formatter exists. Independent final review passed its narrow
11-test, work-count and equivalence checks; this is not a whole-branch review.
The four source hashes matched the final test manifest before and after the code
commit. Saved logs and manifests belong to the external
`reference-boundary-performance` directory and its
`commit-finalization-20261101-140729-c71af933` subdirectory.

The final documentation-only corpus snapshot ran the normal CLIs from PowerShell
against 69 Markdown source files, including the four updated verification pages:

| Command | Exit | Result |
| --- | --- | --- |
| `python -B scripts/docs/broken_link_check.py` | 0 | 1,335 local links; zero broken links; zero external URIs. |
| `python -B scripts/docs/count_duplicate_links.py` | 0 | 1,335 document links; 222 duplicate groups; 347 extra occurrences. |
| `python -B scripts/docs/count_duplicate_links.py --ignore-chapter-anchors` | 0 | 1,335 document links; 272 duplicate groups; 657 extra occurrences. |

These totals describe that corpus snapshot, not fixed acceptance counts or a
requirement to remove repeated navigation links. This documentation-only follow-up
reran the corpus commands, not the unchanged Python suite or Java builds.

### Markdown edge-case regression run

Recorded execution date: 2026-11-01, code input
`8ef60ac7eb3bd1640a8d2b559855eafb8a710a28`. This is a separate execution from
the historical 69-test run above. The 15 added semantic methods cover:

- Git empty-pattern directory/root records accepted only with the exact source
  line's blank-CRLF provenance, while malformed framing, target identity, line
  numbers and nonempty rules remain strict; real directory-only ignores and
  negations retain their effect;
- nonblank multiline reference-definition labels under LF and CRLF, normalized
  uses and opening-line reports, visible-first precedence, titles and hidden/code
  controls, with blank-label and paragraph-continuation rejection; and
- exact semicolon-terminated named/numeric character references across
  destinations, heading anchors and both CLIs. Literal and decoded filenames stay
  distinct; backslash escapes, percent decoding, code spans and recursively
  rendered labels preserve the single-pass boundary.

`python -B scripts/docs/tests/test_documentation_links.py -v` passed all 84 tests:
80 semantic and four scanning-work cases. AST parsing and compilation checks
passed for all four Python files, the scoped diff check passed, and independent
review passed for this code input. `count_duplicate_links.py` was unchanged by
this code commit. These results do not establish cross-Git-version compatibility
or exhaustive CommonMark/GFM equivalence.

The supplied PowerShell CLI results used the pre-documentation-edit corpus
corresponding to `03915d4d674617834868f40e9c569b09897c0cf7`, not the subsequently
expanded inventories:

| Command | Exit | Result |
| --- | --- | --- |
| `python -B scripts/docs/broken_link_check.py` | 0 | 69 Markdown sources; 1,335 local links; zero broken links; zero external URIs. |
| `python -B scripts/docs/count_duplicate_links.py` | 0 | 1,335 document links; 222 duplicate groups; 347 extra occurrences. |
| `python -B scripts/docs/count_duplicate_links.py --ignore-chapter-anchors` | 0 | 1,335 document links; 272 duplicate groups; 657 extra occurrences. |

These are snapshot-specific corpus counts, not acceptance thresholds or a claim
that later documentation edits leave the counts unchanged.

After the five verification inventories were expanded, a documentation-only
refresh ran the same three CLIs against 69 Markdown sources: 1,347 local links,
zero broken links and zero external URIs. Duplicate reporting found 223 groups
and 349 extra occurrences, or 274 groups and 667 extras with
`--ignore-chapter-anchors`; all three commands exited zero. That refresh did not
rerun the Python suite or Gradle.

The later fixture-path addendum's final corpus check covered 69 sources and
1,349 local/document links, with zero broken links or external URIs. Duplicate
counts were 223 groups and 349 extras, or 274 groups and 668 extras ignoring
chapter anchors. Each CLI exited zero; no Python suite or Gradle was rerun for
the documentation addendum.

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

### Logical container implementation run

Recorded execution date: 2026-10-29, code commit
`735a1e8b8e4e079083d1f1d20183c120cabcb733`, including logical-key transport,
vanilla chest, NeoForge Vault and grouped content-receipt identity. The saved PowerShell
invocation used Temurin JDK 21, UTF-8, `--no-daemon`, no explicit timeout and
separate redirected stdout/stderr:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain > $stdout 2> $stderr
```

The invocation exited zero with `BUILD SUCCESSFUL`. All four test tasks ran;
the saved XML contains zero failures, errors or skips:

| Module | Suites | Tests |
| --- | --- | --- |
| Common | 452 | 4858 |
| Fabric | 5 | 24 |
| Forge | 5 | 26 |
| NeoForge | 33 | 208 |
| Total | 495 | 5116 |

All three loader main compilations executed; Common main/test compilation was
`UP-TO-DATE` from the successful focused run. All loader builds and
`verifyModIdentity` passed. The saved source input manifest matched through
verification and the code commit. The receipt-focused regression reports
contain 538 tests in 35 suites, with zero failures, errors or skips. Seven
expected failures in the pre-fix negative control establish that the new cases
detect the receipt identity mismatch; they are not failures of this final tree.
Coverage is inventoried under
[logical identity](identity-authority.md#logical-container-identity-and-authority),
[wire acceptance](presentation.md#logical-target-wire-coverage),
[content receipts](presentation.md#content-receipt-chat-and-pending-completion),
[inventory quotas and sharing](inventory.md#logical-container-quotas-and-scan-sharing),
[container presentation](rendering.md#container-presentation-subjects) and
[Vault topology](integrations.md#vault-topology-grouping-and-presentation).

These execution results apply to the identified code inputs, not subsequent
source changes. Documentation-only follow-up ran no Gradle tasks. Live game, GPU,
multiplayer, mixed-version sessions and performance profiles were not exercised;
the [container manual matrix](manual-integration.md#logical-container-targets-and-whole-presentation)
remains pending.

### Tracking pool publication-stage run

Recorded execution date: 2026-10-30, code commit
`0c0b61c356ca97032c5cf690b250e706645ea086`. The saved final input
manifest includes the approved partition settings/configuration-v5 surface,
certified source and unload checks, runtime pools, successor continuity and final
historical-proof/recipient publication authority, including Preview-origin
proof-scoped repair. The saved PowerShell command used JDK 21, UTF-8,
`--no-daemon`, no explicit timeout and separate stdout/stderr files:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain > $stdout 2> $stderr
```

The saved `full-final` invocation exited zero with `BUILD SUCCESSFUL`. Its
inspected XML contains zero failures, errors or skips:

| Module | Suites | Tests |
| --- | --- | --- |
| Common | 461 | 5008 |
| Fabric | 5 | 24 |
| Forge | 5 | 26 |
| NeoForge | 36 | 221 |
| Total | 507 | 5279 |

All four module test tasks executed in `full-final`, as did Fabric/Forge main
compilation, all three loader Javadocs, jar packaging, Fabric remapping and all
three loader builds. Common/NeoForge main compilation, all test-compilation
tasks, Javadoc-jar tasks and Fabric access-widener validation were `UP-TO-DATE`;
those results were reused, not freshly executed. `verifyModIdentity` executed
and passed for all three jars. The preceding `focused-final` run executed Common
test compilation, NeoForge main compilation and both selected test tasks: 450
Common and 58 NeoForge tests, 508 total, with zero failures, errors or skips.
Common main and NeoForge test compilation were `UP-TO-DATE` in that focused run.
All 1,197 tracked source/build input hashes match across focused/full verification
and the commit, and all nine
recorded loader artifact hashes remain unchanged through commit. Assertion scope
is inventoried under
[configuration](configuration-ui.md#server-settings-snapshots-and-updates) and
[certified pool/publication coverage](inventory.md#certified-tracking-pool-and-publication-coverage).

The final run retained non-fatal mapping, deprecated-API, Javadoc missing-comment
and Gradle deprecation warnings; it is not warning-free evidence and uses no
historical-failure allowance. This documentation-only update inspected saved
logs/XML and hash manifests; it ran no Gradle, tests, live UI, game, multiplayer
or profiling session.

### Certified active-join and validation run

Recorded source input: `a7fa33158d9b66a8bbb78e873e9e955f67970a8c`, including the
certified 1,620-slot baseline, active-prefix joins, fixed reflection metadata and
the completed-open-Preview lifetime fix. The saved final P2 `09` invocation used
Temurin `21.0.12.1+1-LTS`, PowerShell, UTF-8, `--no-daemon`, no explicit timeout
and separate stdout/stderr files:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain > $stdout 2> $stderr
```

The saved command exited zero with `BUILD SUCCESSFUL`. All four test tasks
executed; archived XML contains zero failures, errors or skips:

| Module | Suites | Tests |
| --- | --- | --- |
| Common | 464 | 5036 |
| Fabric | 5 | 24 |
| Forge | 5 | 26 |
| NeoForge | 41 | 256 |
| Total | 515 | 5342 |

Fabric and Forge main/test compilation executed; Common and NeoForge compilation
reused successful prior output. All three loader jar, sources/Javadoc packaging,
Javadoc and build tasks, Fabric remapping and `verifyModIdentity` executed and
passed. Identity remains `pingforit` / `Ping For It` / `0.5.0-pfi-beta2` on
Minecraft 1.21.1. The preceding `08` focus executed 115 NeoForge tests; its 22
Common results were `UP-TO-DATE` from the successful `07` execution, not another
test run. Failed intermediate fixture/oracle/bootstrap attempts and stale copied
reports are identified in the external command/count manifest rather than
counted as fresh successful NeoForge executions.

The saved input hashes match the eleven committed P2 code/test files and six
unchanged benchmark/support sources. Nine loader artifact hashes are retained.
Package checks establish the operation/interface class presence in all three
main jars, 13 normalized-EOL production-source equalities for Forge/NeoForge and
six Fabric source-presence checks only; remapped Fabric sources are not claimed
text-identical. These are bounded artifact-content checks, not runtime launch or
exhaustive shipped-namespace validation.

Exact commands, full logs/XML, hash manifests, stage controls and allocation
measurements belong to the external `certified-vault-coordinated-read` evidence
and its predecessor directories inventoried by
[inventory verification](inventory.md#certified-1620-slot-baseline-and-measurement-boundary).
Nonfatal Javadoc/deprecated-API/Gradle warnings remain. Independent review status
is owned by the [inventory gaps](inventory.md#known-automated-gaps), not inferred
from these automated results. This documentation-only reconciliation inspected
saved evidence; it ran no Gradle, tests, performance probes, game, UI or multiplayer
session.

### Terminal Preview admission and settings-resize run

Recorded execution date: 2026-11-01. The verified Java/build inputs match code
commits `016740231ce73df3313e79b7ad9e25ef71429c7e` (terminal Preview admission)
and `f76ce30be6a4c2b3b75eebc655f5e28094d6a2eb` (settings resize). Both saved
PowerShell invocations used Temurin `21.0.12.1+1-LTS`, UTF-8, `--no-daemon`,
background execution without an explicit timeout and separate stdout/stderr
files. In the commands below, `$evidence` is the external directory
`pfi-java-verification-b2613ada-20261101-130253-84a89e28`:

```powershell
$env:JAVA_HOME = 'E:/scoop/apps/temurin21-jdk/current'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :common:test --tests '*InventoryPooledBackendTest' --tests '*InventoryActiveJoinTest' --tests '*InventoryPooledRuntimeTest' --tests '*InventoryRuntimeTest' --tests '*InventoryBackendLifecycleTest' --tests '*InventoryBootstrapPublicationTest' --tests '*InventoryPublicationAuthorizationTest' --tests '*InventoryBudgetDelayIntegrationTest' --tests '*InventoryCaptureDiagnosticsTest' --tests 'nx.pingwheel.common.screen.*' --no-daemon --console=plain > "$evidence\focus.stdout.log" 2> "$evidence\focus.stderr.log"
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain --info > "$evidence\full.stdout.log" 2> "$evidence\full.stderr.log"
```

The focused invocation exited zero with `BUILD SUCCESSFUL`. Common main/test
compilation and tests executed freshly: 532 tests in 34 suites, with zero
failures, errors or skips. The screen selection accounts for 307 tests in 25
suites and the inventory selection for 225 tests in nine suites. Of 13 actionable
tasks, three executed and ten were `UP-TO-DATE`.

The full invocation also exited zero with `BUILD SUCCESSFUL`. All four test tasks
executed and every archived XML report was fresh, with zero failures, errors or
skips:

| Module | Suites | Tests |
| --- | --- | --- |
| Common | 465 | 5044 |
| Fabric | 5 | 24 |
| Forge | 5 | 26 |
| NeoForge | 41 | 256 |
| Total | 516 | 5350 |

Both runs discovered and passed all 15 `InventoryPooledBackendTest` cases and all
four `SettingsScreenResizeTest` cases. Their assertion scope is owned by
[terminal Preview evidence and active demand](inventory.md#terminal-preview-evidence-and-active-demand)
and [assembled settings-screen resize](configuration-ui.md#assembled-settings-screen-resize).

All three loader main compilations, jar/source-jar packaging, Javadocs and builds
executed, as did Forge/NeoForge test compilation, Fabric remapping and
`verifyModIdentity`. Common compilation reused the passing focused output;
Fabric test compilation, all three Javadoc-jar tasks and Fabric access-widener
validation were `UP-TO-DATE`. The full invocation had 52 actionable tasks: 25
executed and 27 `UP-TO-DATE`. Identity verification passed for `pingforit` /
`Ping For It` / `0.5.0-pfi-beta2` on Minecraft 1.21.1.

All 1,209 Java/build input hashes and their path set matched across verification
and the code commits; all nine recorded loader artifact hashes remained unchanged
through commit. The external directory retains the exact commands, logs, XML,
task reports and hash manifests. Nonfatal mapping/processor, deprecated-API,
Javadoc and Gradle deprecation diagnostics remain; none named the five changed
Java files. These results are not warning-free or a whole-branch review. This
documentation update inspected saved evidence and ran no Gradle or tests. No
live window/rendering, loader launch, multiplayer, installed-provider, manual
gameplay or performance validation was performed; the
[manual matrix](manual-integration.md#pending-manual-and-integration-matrix)
remains pending.

### Recipientless Tracking, diagnostics and caption-reflow verification

Recorded execution date: 2026-11-01. Both commands ran with HEAD
`1964901d81d6352e0d244135f2f36032b0251666` plus the two settings sources later
committed unchanged in `7e42d3897431ff3121ad6b8858a9223b7e06c900`. The five
inventory sources match the former commit; before/after SHA-256 manifests retain
the two settings inputs and match the latter commit. Documentation edits during
the run were not Java/build inputs. This identifies the corrected dialog-return
lifecycle as well as the original caption-reflow and inventory fixes, not an
intermediate settings input.

The saved PowerShell executions used JDK 21, UTF-8, `--no-daemon`, no explicit
timeout and separate stdout/stderr files. In these commands, `$evidence` is the
external directory `pfi-settings-final-20261101-182340`:

```powershell
$env:JAVA_HOME = 'E:\scoop\apps\temurin21-jdk\current'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :common:test --tests 'nx.pingwheel.common.screen.*' --no-daemon --console=plain > "$evidence\focused.stdout.log" 2> "$evidence\focused.stderr.log"
.\gradlew.bat :common:check :fabric:build :forge:build :neoforge:build verifyModIdentity --continue --no-daemon --console=plain --info > "$evidence\full.stdout.log" 2> "$evidence\full.stderr.log"
```

The focused command exited zero with `BUILD SUCCESSFUL` in 31 seconds. Common
main/test compilation and tests executed: 314 tests in 26 suites, including all
seven `SettingsScreenInputLayoutTest` cases, with zero failures, errors or skips.
Of 13 actionable tasks, three executed and ten were `UP-TO-DATE`.

The full command exited zero with `BUILD SUCCESSFUL` in 2 minutes 17 seconds.
All four module test tasks executed freshly; the recorded XML counts contain
zero failures, errors or skips:

| Module | Suites | Tests |
| --- | --- | --- |
| Common | 466 | 5061 |
| Fabric | 5 | 24 |
| Forge | 5 | 26 |
| NeoForge | 41 | 256 |
| Total | 517 | 5367 |

Common main/test compilation reused the focused output. All three loader main
compilations, tests, Javadocs, jar/source-jar packaging and Fabric remapping
executed; loader test compilation, Javadoc-jar tasks and Fabric access-widener
validation were `UP-TO-DATE`. All three loader builds completed, and
`verifyModIdentity` executed and passed for `pingforit` / `Ping For It` /
`0.5.0-pfi-beta2`. Of 52 actionable tasks, 23 executed and 29 were `UP-TO-DATE`.

The full run covers
[recipientless Tracking successor continuity](inventory.md#recipientless-tracking-and-successor-continuity),
[certified pool delay diagnostics](shared-sources.md#certified-pool-delay-diagnostics)
and [assembled caption-change and dialog-return input](configuration-ui.md#assembled-settings-screen-caption-reflow).
Independent inventory and final settings reviews passed. The external evidence
directory retains the logs, count summaries, source hashes and revision/status
records. Existing nonfatal `LevelRendererMixin` mapping, Javadoc missing-parameter,
deprecated-API and Gradle deprecation diagnostics and a MixinGradle integration
skip remain; none names the changed settings sources. This is not warning-free
evidence. No live GUI/font/rendering, multiplayer, installed-provider, manual
gameplay or performance validation follows from these executions.

The subsequent Common-test-only long-fixture-path repair was not part of this
verified input. Its [separate execution record](#common-resident-fixture-path-run)
does not extend the counts or loader-build claims above.

### Common resident fixture-path run

Recorded execution date: 2026-11-01, source input
`7e42d3897431ff3121ad6b8858a9223b7e06c900` plus the Common test-only patch
committed unchanged in `6e1facb74849f0c491ccfc35ce5c0aa1cca90869`. The saved
source-manifest hashes match the two retained source files; the committed patch
also removes the old fixture. Independent review passed; no production,
dependency or loader source changed.
The saved path checks record the shortened fixture source at 120 absolute-path
characters, its compiled class at 131 and its loader class at 160; old oversized
Common source/output paths are absent.

Both PowerShell commands used JDK 21, UTF-8, `--no-daemon`, no explicit timeout
and separate stdout/stderr files. Here `$evidence` is the external directory
`pfi-common-fixture-path-20261101-746d51b2`:

```powershell
$env:JAVA_HOME = 'E:\scoop\apps\temurin21-jdk\current'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat --no-daemon :common:test --tests 'nx.pingwheel.common.platform.PlatformInventoryResidentTest' --console=plain > "$evidence\focused.stdout.log" 2> "$evidence\focused.stderr.log"
.\gradlew.bat --no-daemon :common:check --console=plain > "$evidence\common-check.stdout.log" 2> "$evidence\common-check.stderr.log"
```

Both commands exited zero with `BUILD SUCCESSFUL`. The focused run freshly
compiled the Common tests and passed eight tests in one suite, with zero
failures, errors or skips; two tasks executed and eleven were `UP-TO-DATE`.
The Common check freshly passed 5,061 tests in 466 suites, also with zero
failures, errors or skips. It reused the focused compilation; one task executed
and twelve were `UP-TO-DATE`. Both stderr files were empty; stdout retained the
Gradle deprecation notice. The evidence directory retains exact commands, XML,
summaries, path checks and source hashes. No extra loader build was required or
run for this Common-test-only patch, and no manual gameplay or live-runtime
validation was performed.

### Fluid inventory final combined-tree verification

Recorded execution date: 2026-11-02, code input
`628c033b0eb466a803ea72389bc241d4b047ac04`. The tested non-documentation inputs
match that commit; Markdown/docs are outside this code/build verification scope.

The external evidence directory
`pfi-fluid-formal-final-20261102-222512-a1571ded` retains the full command, logs,
JUnit results, input manifests, artifact hashes and scoped independent package checks.

The single formal PowerShell invocation used Temurin 21.0.12.1+1-LTS, UTF-8,
`--no-daemon`, no explicit timeout and separate stdout/stderr files. Here
`$evidence` is that external directory:

```powershell
$env:JAVA_HOME = 'E:\scoop\apps\temurin21-jdk\current'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat --no-daemon --continue :common:check :common:javadoc :fabric:build :forge:build :neoforge:build verifyModIdentity :fabric:fluidSourceIntegration :forge:fluidSourceIntegration :neoforge:fluidSourceIntegration --init-script "$evidence\formal.init.gradle" --console=plain > "$evidence\formal.stdout.log" 2> "$evidence\formal.stderr.log"
```

The external init only forces fresh Test execution and wires the existing
native-source seam onto each loader's test classpath; it changes no repository
build metadata or dependency. The invocation exited zero with `BUILD SUCCESSFUL`.
All suites in the table ran freshly in that invocation:

| Module / task | Suites | Tests | Passed | Skipped |
| --- | --- | --- | --- | --- |
| Common test | 490 | 5,245 | 5,243 | 2 |
| Fabric test | 6 | 80 | 80 | 0 |
| Forge test | 6 | 60 | 60 | 0 |
| NeoForge test | 45 | 316 | 316 | 0 |
| Fabric fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Forge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| NeoForge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Total | 550 | 5,707 | 5,705 | 2 |

Every row has zero failures and errors. The two Common skips are the
`InventoryFluidLoaderIntegrationTest` methods requiring loader-native APIs and
recording fixtures absent from the Common-only classpath. Both methods executed
and passed without skips in each loader-specific task. These are the same two
scenario definitions across three loader classpaths, not six independent scenarios.

All four main/test compilations and standard test tasks executed, as did Common
check/Javadoc, all loader checks/Javadocs/jar/source/Javadoc packaging/builds,
Fabric remapping, the three native-source tasks and `verifyModIdentity`.
The identity check passed for `pingforit` / `Ping For It` / `0.5.0-pfi-beta2`.
Of 56 actionable tasks, 35 executed and 21 were `UP-TO-DATE`; BuildSrc setup,
Minecraft artifact preparation, unchanged resources/test resources, Forge mappings,
Fabric include jars and access-widener validation reused their outputs.
No independently configured formatter, lint or static-analysis task exists.

The [fluid coverage matrix](inventory.md#fluid-inventory-coverage) identifies the
passing headless assertions. Independent package checks covered metadata,
namespace, Mixin and Fabric refmap boundaries, not an exhaustive shipped-package audit.

The run is not warning-free: four Javadoc groups report 400 missing
`@param`/`@return`/`@throws` diagnostics, alongside deprecated-API, Mixin mapping,
Forge processor-option, skipped Eclipse integration and Gradle 9 deprecation
diagnostics. Live loader launch/Mixin application, GPU/font/UI and narrow-window rendering,
multiplayer/mixed-version handshakes, installed Create/Sable/Refined Storage,
manual gameplay and CPU/TPS/GC/allocation measurements remain pending.

### Fluid tank grouping and presentation verification

Recorded execution date: 2026-11-03, code input `e281aaa4`. The saved formal
input manifest matches all 1,281 non-documentation inputs in that commit.
The external evidence directory
`pfi-tank-formal-20261103-051330-2e7f2627` retains the command, logs, JUnit XML,
task results, input manifests and scoped artifact checks.

The PowerShell execution used Temurin 21.0.12.1+1-LTS, UTF-8, `--no-daemon`,
no explicit timeout and separate stdout/stderr files. Here `$evidence` is that
external directory:

```powershell
$env:JAVA_HOME = 'E:\scoop\apps\temurin21-jdk\current'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& .\gradlew.bat --no-daemon --continue :common:check :common:javadoc :fabric:build :forge:build :neoforge:build verifyModIdentity :fabric:fluidSourceIntegration :forge:fluidSourceIntegration :neoforge:fluidSourceIntegration --init-script "$evidence/formal.init.gradle" --console=plain --info > "$evidence/formal.stdout.log" 2> "$evidence/formal.stderr.log"
```

The external init forces fresh Test execution and supplies the existing native
fluid-source seam on each loader's test classpath. The invocation exited zero
with `BUILD SUCCESSFUL`. All test tasks ran freshly:

| Module / task | Suites | Tests | Passed | Skipped |
| --- | --- | --- | --- | --- |
| Common test | 491 | 5,258 | 5,256 | 2 |
| Fabric test | 6 | 80 | 80 | 0 |
| Forge test | 6 | 60 | 60 | 0 |
| NeoForge test | 47 | 332 | 332 | 0 |
| Fabric fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Forge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| NeoForge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Total | 553 | 5,736 | 5,734 | 2 |

Every row has zero failures and errors. The two Common skips are the
`InventoryFluidLoaderIntegrationTest` methods requiring loader-native classes;
both passed without skips in each loader's dedicated task. Those are the same
two scenario definitions on three loader classpaths.

All four main compilations and Common/Forge/NeoForge test compilation executed;
Fabric test compilation was `UP-TO-DATE`. Common check/Javadoc, all three loader
checks/Javadocs/packaging/builds, Fabric remapping and `verifyModIdentity`
executed successfully. Of 56 actionable tasks, 34 executed and 22 were
`UP-TO-DATE`. Artifact checks confirmed the common tank key on all loaders,
NeoForge-only tank topology/resolver classes, expected metadata and absence of
the checked test fixtures. The verified identity is `pingforit` / `Ping For It` /
`0.5.0-pfi-beta2` on Minecraft 1.21.1.

Assertion scope belongs to [identity](identity-authority.md#logical-container-identity-and-authority),
[wire and receipts](presentation.md#logical-target-wire-coverage),
[source gates](rendering.md#container-presentation-subjects),
[tank topology and dispatch](integrations.md#fluid-tank-topology-grouping-and-presentation)
and [inventory quotas](inventory.md#logical-container-quotas-and-scan-sharing).

Nonfatal diagnostics include 400 Javadoc missing-tag warnings across four groups,
deprecated APIs, Mixin mapping, Forge processor options, skipped Eclipse
integration and Gradle deprecation. No independent formatter, lint or
static-analysis task is configured. Installed Create/gameplay, live loader/Mixin
application, BER/GPU output, multiplayer/mixed-version transport and performance
measurements remain pending in the
[manual matrix](manual-integration.md#logical-container-targets-and-whole-presentation).

### Native fluid boundary and presentation verification

Recorded execution date: 2026-11-03. The tested non-documentation inputs match
commit `b30c0f1e107a232c73b38e17c50766816d2e163f`: all 1,310 inputs were hashed,
with none missing, and stayed unchanged through verification. The external
evidence directory below retains the commands, logs, fresh JUnit results, input
manifest and scoped artifact checks.

Both commands ran from PowerShell 7 on Windows with Temurin 21.0.12.1+1-LTS,
UTF-8, `--no-daemon`, no explicit timeout and separate stdout/stderr files:

```powershell
$evidence = 'C:\Users\Void_Deer\AppData\Local\Temp\opencode\pfi-six-fluid-latest-formal-20261103-132444-71588440'
$env:JAVA_HOME = 'E:\scoop\apps\temurin21-jdk\current'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& .\gradlew.bat --no-daemon --continue :common:check :common:javadoc :fabric:build :forge:build :neoforge:build verifyModIdentity :fabric:fluidSourceIntegration :forge:fluidSourceIntegration :neoforge:fluidSourceIntegration :forge:fluidPresentationIntegration :common:fluidPresentationClassSafetyIntegration --init-script "$evidence/formal.init.gradle" --init-script "$evidence/class-safety.init.gradle" --console=plain --info > "$evidence/formal.stdout.log" 2> "$evidence/formal.stderr.log"
& .\gradlew.bat --no-daemon --continue :common:fluidPresentationClassSafetyIntegration --init-script "$evidence/class-safety.init.gradle" --console=plain --info > "$evidence/class-safety-completion.stdout.log" 2> "$evidence/class-safety-completion.stderr.log"
```

The full command exited 1 solely because the external class-safety Test task
lacked an explicit dependency on Forge `processResources`; Gradle rejected that
task before test execution. All other required tests, compilation, Javadocs,
builds and artifact-identity checks succeeded. After correcting only the external
init dependency, the focused command exited 0 with `BUILD SUCCESSFUL` and freshly
completed the remaining task. Together they complete the required automated
checks for this input.

All nine Test tasks ran freshly:

| Module / task | Suites | Test executions | Passed | Skipped |
| --- | --- | --- | --- | --- |
| Common test | 496 | 5,318 | 5,316 | 2 |
| Fabric test | 7 | 83 | 83 | 0 |
| Forge test | 7 | 62 | 61 | 1 |
| NeoForge test | 48 | 335 | 335 | 0 |
| Fabric fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Forge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| NeoForge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Forge fluidPresentationIntegration | 1 | 2 | 2 | 0 |
| Common fluidPresentationClassSafetyIntegration | 1 | 6 | 6 | 0 |
| Total | 563 | 5,812 | 5,809 | 3 |

Every row has zero failures and errors. The two Common skips are
`InventoryFluidLoaderIntegrationTest` methods requiring loader-native APIs; the
same methods passed in each loader's dedicated fluid-source task. Forge's ordinary
`FluidPresentationServiceTest` skips its native FluidType case because that
classpath lacks patched `getFluidType`; the identical method passed in the
patched-first presentation task. Repeated methods across classpaths count as
executions, not independent scenarios.

All four main compilations, Common test compilation and all four Javadocs
executed; the three loader test compilations were `UP-TO-DATE`. Loader jar/source
packaging, Fabric remapping and `verifyModIdentity` executed successfully, with
all loader check/build lifecycle tasks completed. Loader Javadoc jars, resources,
Minecraft preparation and Fabric access-widener validation reused outputs. The
focused completion reused compilation/resources. No independent Java lint,
formatter or static-analysis task is configured.

Scoped jar checks found each loader's own production description/render service
registrations and provider classes, with no checked test fixtures or foreign
loader packages. Main/source/Javadoc jars passed the checked fixture-leak
boundaries. These package checks are separate from `verifyModIdentity`, not an
exhaustive archive certification. Mod identity/version and shipped presentation
and inventory protocol constants retained their established values.

Passing assertion scope belongs to the
[fluid definition, completion and naming assertions](inventory.md#native-fluid-boundary-regressions),
[mixed-lane selection](interaction.md#inventory-list-gestures-and-navigation),
[quantity fitting](rendering.md#selector-overlay-layout-and-frame-rendering),
[sprite dispatch](rendering.md#base-fluid-icon-presentation) and
[loader/Create presentation](integrations.md#native-fluid-presentation).

Nonfatal diagnostics include 400 Javadoc missing-tag warnings, deprecated APIs,
Mixin mapping, Forge processor options, skipped Eclipse integration, Gradle
deprecation and Log4j diagnostics; some Javadoc locale output is garbled. The
headless tea atlas fixture emits an ERROR-level `Sheets loaded too early`
diagnostic despite passing assertions, not evidence of live client initialization.
Live game/GPU/native-font fitting, actual resource reload, dedicated-server and
loader/Mixin launch, installed Create/Sable, multiplayer/mixed-version transport
and performance remain pending in the
[fluid manual matrix](manual-integration.md#fluid-inventory).

### Lane policy, native amount and icon opacity verification

Recorded execution date: 2026-11-03. All 1,316 tested non-documentation inputs
match commit `27bbb83d89b762961f2f3bece994cfdc8f914ca2`, with none missing or
changed through verification. The external evidence directory below retains the
command, logs, fresh JUnit results, input checks and artifact checks.

The single full invocation ran from PowerShell on Windows with Temurin
21.0.12.1+1-LTS, UTF-8, `--no-daemon`, no explicit timeout and separate
stdout/stderr files:

```powershell
$evidence = 'C:\Users\Void_Deer\AppData\Local\Temp\opencode\pfi-policy-handoff-formal-20261103-182113-1d164ce3'
$env:JAVA_HOME = 'E:\scoop\apps\temurin21-jdk\current'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& .\gradlew.bat --no-daemon --continue :common:check :common:javadoc :fabric:build :forge:build :neoforge:build verifyModIdentity :fabric:fluidSourceIntegration :forge:fluidSourceIntegration :neoforge:fluidSourceIntegration :forge:fluidPresentationIntegration :forge:nativeFluidAmountIntegration :common:fluidPresentationClassSafetyIntegration --init-script "$evidence/formal.init.gradle" --init-script "$evidence/class-safety.init.gradle" --console=plain --info > "$evidence/formal.stdout.log" 2> "$evidence/formal.stderr.log"
```

Result: exit 0, `BUILD SUCCESSFUL`. All ten Test tasks ran freshly:

| Module / task | Suites | Test executions | Passed | Skipped |
| --- | --- | --- | --- | --- |
| Common test | 498 | 5,370 | 5,368 | 2 |
| Fabric test | 7 | 83 | 83 | 0 |
| Forge test | 8 | 67 | 61 | 6 |
| NeoForge test | 49 | 341 | 341 | 0 |
| Fabric fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Forge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| NeoForge fluidSourceIntegration | 1 | 2 | 2 | 0 |
| Forge fluidPresentationIntegration | 1 | 2 | 2 | 0 |
| Forge nativeFluidAmountIntegration | 1 | 5 | 5 | 0 |
| Common fluidPresentationClassSafetyIntegration | 1 | 6 | 6 | 0 |
| Total | 568 | 5,880 | 5,872 | 8 |

Every row has zero failures and errors. The two Common
`InventoryFluidLoaderIntegrationTest` skips require loader-native fixtures;
the same methods passed in all three fluid-source integration tasks. Forge's
ordinary vanilla-first classpath skips one `FluidPresentationServiceTest` method
and all five `NativeFluidAmountTest` methods; the same methods passed in their
patched-first presentation and native-amount tasks. The amount skip reason comes
from the class's `BeforeAll` assumption and classpath, while its XML contains
bare skipped entries. Repeated methods across tasks count as executions, not
independent scenarios.

All four main compilations and Common/Forge/NeoForge test compilations executed;
Fabric test compilation was `UP-TO-DATE`. Javadocs, loader main/source/Javadoc
jars, Fabric remapping, all three builds and `verifyModIdentity` completed.
Resource processing and Fabric access-widener validation reused outputs.
Gradle reported 59 actionable tasks: 37 executed, 22 up-to-date.
No independent Java lint, formatter or static-analysis task is configured.

Scoped artifact checks passed for all loader main jars' production service
providers and fixture/package boundaries. The new test network SPI stayed in
Common test output; all nine main/source/Javadoc artifacts passed its leak check.
These checks supplement artifact identity verification, not exhaustive archive
certification.

Passing assertions are inventoried under
[production lane policy, handoff and native amounts](inventory.md#production-lane-policy-and-native-amount-regressions)
and [low-opacity quads](rendering.md#low-opacity-quad-regressions). This formal run
uses headless tests; the separate GPU evidence below is not part of its counts.

Nonfatal diagnostics include 400 Javadoc warnings, deprecated APIs, mapping,
processor/Mixin options, skipped Eclipse integration and Gradle 9 deprecation.
Some localized Javadoc output is garbled. The NeoForge headless Create fixture
emits `Sheets loaded too early` despite passing assertions; it is not a live
client initialization result.

#### Stock-GUI GPU probe

Recorded 2026-11-03 in
`C:\Users\Void_Deer\AppData\Local\Temp\opencode\pfi-opacity-review-20261103`:
the independent review's `verify-review.ps1` compiled the production icon sources
with `javac` and passed 14 focused JUnit tests (no skips or failures); its hidden
GLFW probe used stock Minecraft 1.21.1 assets, real `ShaderInstance`,
`GuiGraphics.flush` and unmodified production quad submission on NVIDIA GeForce
RTX 4060 Laptop OpenGL 3.2. Framebuffer opacity/tint and texture cutoff,
queued/following GUI draws, nondefault caller-state/cache preservation and
injected-failure recovery passed with no GL errors.

The four render source/test inputs match this formal input. The GPU probe and
its focused JUnit run were not rerun or added to the formal Gradle counts.

Only explicitly overridden uniforms are restored, not the whole shader object;
normal subsequent stock draws re-establish default model/projection and samplers.
This stock-GUI result does not verify full-game/native-font rendering, actual
resource reload, third-party shaders, installed Create, full loader/FML/server
launch, multiplayer or long-run
native-heap/performance; live scenarios remain in the
[fluid manual matrix](manual-integration.md#fluid-inventory).

### Final combined-tree acceptance and artifact verification

Recorded execution date: 2026-11-04. The tested input is identified by InputID
`8f9bfed20aff917b8e910b1aff2e84ffa0e1134afda8a630b4e115355df1dbc2`, HEAD
`0b6b279d4cd51ae33f0fd9c3199e57f8bfcfd88d` plus 55 validated dirty non-document
paths; that HEAD alone does not contain the inspected inventory creation-snapshot
and related feature changes. A later commit
SHA may differ, so the input ID and affected source-tree scope identify this run.
The affected-file scope contains 76 paths: 70 active paths (69 in the main scope and one additional Vault path) and six checkpoint paths. The 1,350 non-document inputs were
stable across the before, immediate-after and final snapshots. Earlier failing
fixtures were repaired and rerun; the previous Common three, Forge two and
NeoForge `compileTestJava` failures all passed in this current-input run, and
their earlier results are not reused. Only one Vault test changed since the
previous formal input, and production source remained unchanged.

Exactly one authorized Gradle invocation ran with Temurin JDK 21, UTF-8,
`--no-daemon`, `--continue`, `--console=plain`, `--info`, two external init
scripts and separate stdout/stderr files. It exited 0 with `BUILD SUCCESSFUL`.
The task arguments were:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = 'E:/scoop/apps/temurin21-jdk/current'
.\gradlew.bat --no-daemon --continue --console=plain --info `
  "-Dpfi.formal.evidence=$evidenceDir" `
  '-Dpfi.formal.authorization=allfour-acceptance-20261104-122452-b65071b3' `
  :common:check :common:javadoc :fabric:build :forge:build :neoforge:build `
  verifyModIdentity :fabric:fluidSourceIntegration :forge:fluidSourceIntegration `
  :neoforge:fluidSourceIntegration :forge:fluidPresentationIntegration `
  :forge:nativeFluidAmountIntegration :common:fluidPresentationClassSafetyIntegration `
  :forge:fluidRegistryIntegration :forge:nativeFluidRegistryFreezerIntegration `
  :forge:fluidRegistryColdWaterIntegration :forge:fluidRegistryModuleIntegration `
  --init-script $formalInit --init-script $classSafetyInit > $stdout 2> $stderr
```

The run produced 589 fresh JUnit XML suites and 6,094 test executions including
required supplements: 6,078 passed, 16 skipped, 0 failed and 0 errored. All 14
Test tasks executed fresh. The 16 ordinary skips each have an exact same-class,
same-method dedicated PASS in this run; the 6,064 unique class/method display
identities are not 6,064 independent semantic scenarios.

| Test task | Executions | Passed | Failed/error | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `:common:test` | 5,535 | 5,533 | 0 | 2 |
| `:fabric:test` | 90 | 90 | 0 | 0 |
| `:forge:test` | 79 | 65 | 0 | 14 |
| `:neoforge:test` | 360 | 360 | 0 | 0 |
| `:fabric:fluidSourceIntegration` | 2 | 2 | 0 | 0 |
| `:forge:fluidSourceIntegration` | 2 | 2 | 0 | 0 |
| `:neoforge:fluidSourceIntegration` | 2 | 2 | 0 | 0 |
| `:forge:fluidPresentationIntegration` | 2 | 2 | 0 | 0 |
| `:forge:nativeFluidAmountIntegration` | 5 | 5 | 0 | 0 |
| `:common:fluidPresentationClassSafetyIntegration` | 7 | 7 | 0 | 0 |
| `:forge:fluidRegistryIntegration` | 7 | 7 | 0 | 0 |
| `:forge:nativeFluidRegistryFreezerIntegration` | 1 | 1 | 0 | 0 |
| `:forge:fluidRegistryColdWaterIntegration` | 1 | 1 | 0 | 0 |
| `:forge:fluidRegistryModuleIntegration` | 1 | 1 | 0 | 0 |

Required detector executions also passed:

| Detector scope | Executions | Result |
| --- | ---: | --- |
| `InventoryCreationSnapshotIntegrationTest` | 17, including a positive subset of 8 | Passed |
| Runtime retained-preparation retry (`InventoryFluidPreparationRetryTest`) | 5 | Passed |
| Create current-controller five classes | 55 | Passed |
| Reviewed common repair and old-failure controls | 40 | Passed |
| NeoForge real registration and transport | 7 | Passed |
| `CertifiedVault1620BackendTest` repaired class | 9 | Passed |
| Vault five-class adjacency | 28, including the repaired class, not 37 independent cases | Passed |

The required exact-method oracle contains 86 methods and 14 parameter
invocations; all passed. The 17-case integration includes the positive 8-case
subset rather than 25 unique cases. Exact method and parameter selectors, XML,
logs and the 76-path input manifest are retained in the change's external
evidence report.

The successful lifecycle tasks include `:common:check`, `:fabric:build`,
`:forge:build`, `:neoforge:build` and `verifyModIdentity`. Of 63 actionable
tasks, 20 executed and 43 were `UP-TO-DATE`. `:neoforge:compileTestJava` actually
executed; the other `compileTestJava` tasks, all production `compileJava` tasks
and the Javadoc tasks were `UP-TO-DATE`. This is not fresh-compilation or fresh
doclint evidence. Nine main/source/Javadoc artifacts retained
UP-TO-DATE producer provenance and were rechecked for SHA and content.

The NeoForge compile classpath has 69 entries without the runtime-only event,
while the 119-entry ordinary runtime classpath has exactly one real loader
`4.0.21` definition. Actual execution covers the reflective runtime event path
without an added dependency or stub. All 30 production service descriptors match
loader source with exactly one provider and no test fixture, test SPI or foreign
loader leakage; `EncodedPresentationPayload` is present. Six shipped `javap`
checks report presentation version 2 and inventory version 2, the mod version
remains `0.5.0-pfi-beta2`, and `verifyModIdentity` passed.

The main audit ran 4,520 checks with 0 failures; the supplemental closeout ran 42
checks with 0 failures. NeoForge classpath closeout and closeout-detail checks
exited 0. `git diff --check` exited 0, the untracked whitespace check was clean
and AST parsing passed. No separately configured lint, static-analysis or
formatter task exists or is claimed; compiler, Gradle, Javadoc and Git warnings
are retained in the raw logs and this is not a warning-clean result.

Coverage and remaining manual limits remain owned by
[inventory creation-snapshot coverage](inventory.md#creation-snapshot-server-coverage)
and [presentation creation-snapshot coverage](presentation.md#immediate-inventory-creation-snapshot-coverage).
The [pending manual matrix](manual-integration.md#pending-manual-and-integration-matrix)
still owns complete game/FML launch, real socket or multiplayer transport,
installed Create placement, new-feature renderer/GPU/shader/resource-reload and
performance scenarios.

## Verification limitations

The 2026-10-27 combined invocation's stderr was empty; stdout retained the Gradle 9
deprecation notice and Problems-report reference. The build records also contain
deprecated-API, mapping/processor and Javadoc warnings. These are not
warning-free results. Compiler, Javadoc/doclint and configured loader-validation
evidence is scoped to the build/check executions that performed that work; the
2026-10-27 combined invocation did not freshly compile or document the loaders.
The logical-container run retained Javadoc, deprecated-API, Mixin-option and
Gradle deprecation warnings; its successful result is not warning-free evidence.

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
