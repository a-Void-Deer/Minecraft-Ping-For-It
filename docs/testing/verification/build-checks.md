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

The 2026-11-08 ITEM documentation finalization reran both CLIs on 71 sources:
1,604 local links, zero broken links and zero external URIs. Duplicate reporting
found 263 groups and 416 extra occurrences, or 304 groups and 862 extras with
`--ignore-chapter-anchors`. Both commands exited zero. The link checker also
resolved the changed heading anchors; the 71 sources contain 36 fenced blocks
with zero unclosed fences, and ownership review kept execution statistics on
this page with summary links from the topic inventory. The documentation
`git diff --check` exited 0. That pass did not rerun the Python suite or Gradle.

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

### Logging-profile common execution

Recorded 2026-11-04 for the common logging-profile change. The tested input was
the current common main/test source tree; loader source sets and live runtime
were outside this run. The exact command was:

```powershell
.\gradlew.bat --no-daemon :common:test :common:check :common:javadoc
```

It exited 0. `:common:test` produced 515 suites and 5,644 tests with 0 failures,
0 errors and 2 skipped, and `:common:check` passed. The two skips are
`InventoryFluidLoaderIntegrationTest` cases requiring a loader-native fixture
and classpath. `:common:javadoc` was `UP-TO-DATE` against the same production
inputs as its prior successful generation; that generation retained missing
`@param`/`@return` warnings, so this is not warning-clean doclint evidence. This
is common automated evidence only; the three loader builds are recorded
separately below, and live/manual evidence remains pending.

A later focused output-boundary execution used:

```powershell
.\gradlew.bat --no-daemon :common:test --tests 'nx.pingwheel.common.diagnostics.Log4jDiagnosticOutputBoundaryTest'
```

It exited 0 in 31 seconds with 3 tests, 0 failures, 0 errors and 0 skipped; the
pre/post input manifests were identical. The focused scope is synchronous
isolated Log4j output-boundary behavior, not asynchronous logging or a deployed
loader run. The earlier common-suite result above remains the broad common
inventory; this focused run replaces only the filtered Gradle test-result
directory. The final combined acceptance below carries the current acceptance
status.

### Logging-profile loader execution

Recorded 2026-11-05 for code input commit `b8178699`. An external, uncommitted
loader-native test init script supplied the four Forge patched-first integration
tasks; it is not a repository task, product configuration or prerequisite. The
actual command shape was:

```powershell
.\gradlew.bat :fabric:build :forge:build :neoforge:build `
  :forge:loggingFluidPresentationIntegration `
  :forge:loggingNativeFluidAmountIntegration `
  :forge:loggingFluidRegistryIntegration `
  :forge:loggingNativeFluidRegistryFreezerIntegration `
  --init-script $loaderNativeInit --continue --no-daemon
```

It exited 0 with `BUILD SUCCESSFUL` in 2 minutes 1 second: 54 actionable tasks,
10 executed and 44 up-to-date. Fabric passed 91 tests with 0 skipped; Forge passed
66 with 14 existing vanilla-first skips; NeoForge passed 396 with 0 skipped. The
four patched-first Forge integration tasks passed their 15 tests (2 + 5 + 7 + 1),
covering the 14 existing skip points. The run produced the three loader `build`
tasks and nine main/sources/Javadoc jars; artifact inspection found no test-fixture
leak and loader/mod versions were unchanged. This loader-owner checkpoint predates
the final reentry fix and did not include `verifyModIdentity`; the final combined
acceptance below carries the current status.

### Logging-profile final combined acceptance

Recorded 2026-11-05. Validated HEAD
`bce5bc07c40b49a237325d1ccb85b89e1b28d328`, tree
`13958c1bb1518bc822fcf2e26315147255e2aece`. The 1,376 source/build inputs had
stable manifest SHA256
`336C4F89EEDB179236190BDA15413C38EDB412A68EE4F9F66EC144B35DD168E1` across the
focused, combined and post-check snapshots; implementation and index diffs were
empty, with no untracked source/build inputs. Documentation changes remained
separate from this validation.

The focused reentry command was:

```powershell
.\gradlew.bat --no-daemon :neoforge:test `
  --tests 'nx.pingwheel.neoforge.integration.create.CreateRegistrationLifecycleTest' `
  --tests 'nx.pingwheel.common.diagnostics.CreateAdapterLifecycleDiagnosticsTest'
```

It exited 0 in 53 seconds with 2 suites and 14 tests, 0 failures, 0 errors and 0
skips; NeoForge main and test compilation actually executed.

The final combined command was:

```powershell
.\gradlew.bat --no-daemon :common:check :common:javadoc verifyModIdentity
```

It exited 0 in 2 minutes 9 seconds. NeoForge test executed its full 59 suites and
402 tests with all passing; NeoForge main/sources/Javadoc jars were rebuilt;
`verifyModIdentity` executed and passed. Gradle `UP-TO-DATE` validation reused
the unchanged common 5,647 tests (5,645 passed, 2 skipped), Fabric 91 passed,
Forge 80 tests (66 passed, 14 skipped) and the common/Fabric/Forge Javadoc
outputs. The 5,647-test common run was actually executed by the preceding final
common run over the same source inputs; the combined invocation did not re-execute
that unchanged test task.

The two common skips are native-fluid loader-conditional cases and are not
counted as passes. The Forge 14 are ordinary headless patched-API skips covered
by the prior four patched-first tasks' 15 passing tests (2 + 5 + 7 + 1), which
central did not rerun. `verifyModIdentity` passed with metadata `pingforit` /
`Ping For It` / `0.5.0-pfi-beta2` / Minecraft 1.21.1. The three shippable jars
carried no test/fixture classes, test frameworks or scanned optional dependency
whitelist leakage; the NeoForge lifecycle class matched the shipped entry and
contained the owning-thread and controlled-reentry fix.

The source audit scanned 1,295 Java files with zero removed-legacy matches, 661
production files with zero unapproved raw logging outside the backend, and 13/13
packet decode paths with an explicit side. The added-file annotation scan covered
42 files and 90 named types with no missing nearby `@NotNullByDefault`; this is
scoped source inspection, not an exhaustive type proof. `git diff --check
ef8f62ec` passed for the source/build paths. Existing Javadoc and Gradle
deprecation warnings and expected injected diagnostic records remain; this is not
warning-clean evidence.

No Minecraft client or server and no external clients were launched. Manual
gameplay, rendering/GPU, installed Create, multiplayer, mixed-version and live
appender/address behavior are not verified. Synchronous isolated Log4j and child
`FD.err` tests are automated seams, not asynchronous or deployed logging evidence.

### Alt logging profile controls

Recorded 2026-11-05. The accepted input was HEAD
`4b82c691edd139f67404f228dd8aa02482922f34`, tree
`2981d22f447174223492d6d27bd444f6a8a54618`, plus the 14-path Alt patch. The
archived patch and `git diff 4b82c691..71de6ebe` are byte-identical with source
diff SHA256
`fc847fe1c917839e8a13c817b488d82d1cce365f282a12603915f799e5e8513d`; the same
content was committed as `71de6ebe61bce9fa330d5ff7c688a02b89f9cb08`. The 13
retained files currently equal the tested manifest and normalize to that commit,
and the old session class remains deleted, so the 14 paths have no later edits
or uncommitted repair. The patch covered three common screen paths, eight locale
resources and three screen-test paths.

The focused command was:

```powershell
.\gradlew.bat --no-daemon :common:test --tests 'nx.pingwheel.common.screen.LoggingProfile*' --tests 'nx.pingwheel.common.screen.SettingsLocalizationCoverageTest' --tests 'nx.pingwheel.common.screen.ServerSettingsLoggingProfileTest'
```

Three consecutive executions exited 0; the final focused invocation completed
in 18 seconds. `LoggingProfileControlTest` ran 8 tests, `LoggingProfileScreenTest`
7, `SettingsLocalizationCoverageTest` 11 and `ServerSettingsLoggingProfileTest`
4: 30 total with 0 failures, 0 errors and 0 skips. Focused stdout and stderr are
retained as `pingforit-logging-alt-focused.stdout.log` and
`pingforit-logging-alt-focused.stderr.log` in the local OpenCode temporary
evidence directory.

The final combined command was:

```powershell
.\gradlew.bat --no-daemon :common:check :common:javadoc :fabric:build :forge:build :neoforge:build verifyModIdentity
```

It exited 0 with `BUILD SUCCESSFUL` in 2m19s: 53 actionable tasks, 31 executed
and 22 up-to-date. All four module test tasks executed:

- Common: 522 suites, 5,733 tests, 5,731 passed and 2 skipped.
- Fabric: 10 suites and 93 passed.
- Forge: 12 suites, 82 tests, 68 passed and 14 skipped.
- NeoForge: 61 suites and 414 passed.

That is 6,322 tests, 6,306 passed, 16 skipped, 0 failures and 0 errors. The 16
skips match the previous audit class/method baseline: the two Common
`InventoryFluidLoaderIntegrationTest` cases require a loader-native fixture,
and the 14 Forge vanilla-first cases are `FluidPresentationServiceTest` 1,
`NativeFluidAmountTest` 5, `PlatformFluidRegistryServiceTest` 7 and
`NativeFluidRegistryFreezerTest` 1. The UI change is unrelated to those
fixtures; no patched-first task was rerun and the earlier supplemental pass is
not claimed here.

Common main and test compilation were `UP-TO-DATE`; the three loader
`compileJava` tasks executed and shared the common compilation. Forge
`compileTestJava` executed, while Fabric and NeoForge `compileTestJava` were
`UP-TO-DATE`. All four Javadoc tasks, the three loader `build` tasks and
`verifyModIdentity` executed. Identity remained `pingforit` / `Ping For It` /
`0.5.0-pfi-beta2` / Minecraft 1.21.1. Artifact inspection was limited to
identity, the new button and no old-session entry in the three main jars, and
the eight locale byte comparisons; it is not an exhaustive jar audit.

Of the 1,397 fresh pre/post inputs, 1,395 chain to the commit through
blob/EOL/current hashes; the two later-changed Create files were not directly
restored to test-time bytes. The archived patch equality above covers the 14 Alt
paths. This is not a full-tree or current-workspace PASS. `git diff --check HEAD --`
over the common main, common test and eight locale paths exited 0. Four Javadoc
warning groups totaling 400 lines matched the existing baseline; mapping,
deprecated, processor-option, MixinGradle and Gradle deprecation warnings
remain. No independent Java lint or format task exists or is claimed.

Detailed evidence remains under the local OpenCode prefix
`pingforit-logging-alt-final-1`; it retains raw stdout/stderr, the result and
execution summary, test XML, static audit, artifact checks, input captures, the
long-path note and state drift. The static and post audit invocations both
exited 0; static reported zero old session references, zero unlock-key
references and 16 tooltip variants free of Debug or modifier hints while the
real Debug value caption is preserved.

This PASS applies to the tested Alt-control input, not to the full tree or
latest workspace. Later inventory/Create edits are outside this result. No
Minecraft client or server, real macOS Option key, multiplayer, manual
appender/layout or rendered-tooltip check was performed.

### Provider trust and frozen authorization final verification

Recorded execution date: 2026-11-07. This is the current-source acceptance run
for [D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md).
The verified input is the working tree at HEAD
`4a67c3e78ebd1932ebc533fe2e8be282cbc69ceb` (`agent/perf-fix`) plus its
uncommitted source and test changes; HEAD alone does not contain that
implementation. The captured tracked-diff SHA-256, which also covers the
uncommitted documentation files outside the Java/build input scope, was
`378EFB37F84FB0E45BA30D0A92D4DC73F08E655FCFE56ED2E6134E78EDC969DA`
and the 18-file untracked-manifest SHA-256 was
`B43C9DF5DB94D05CA1CFBB10572E3E68C25EAF767DE4BBED98D170CB1ED103DA`.
Both values and HEAD were identical before and after execution. The later commit
below is a source-identity binding only; the run itself was not performed on a
clean commit.

The run was executed at HEAD `4a67c3e78ebd1932ebc533fe2e8be282cbc69ceb` plus the
working-tree hashes above. Afterwards, commit
`a4ae36fa121c1899c946e1a419ae66afbbfb4344` (`feat: freeze Ping content grants
and schedule idle source checks`) on parent
`4a67c3e78ebd1932ebc533fe2e8be282cbc69ceb` committed the identical 83 Java
implementation/test paths (20 production and 63 test). The external
`commit.evidence.json` records 1,334 Java sources with raw identity SHA-256
`3fd2a0d0118b34118bcd8cd7beece8d05fa5c04b722192b65e417e0e2ecd80fb`
both before and after the commit, all raw bytes matching the manifest, and all
canonical Git blobs matching the tested input. Those 83 paths were the complete
remaining implementation set, and no Gradle build metadata or documentation
changed. Gradle was not rerun for this commit: this binds the already-verified
source bytes to the commit, not a new run at HEAD `a4ae36fa` or a clean-commit
revalidation. The working-tree hashes and prior execution evidence above remain
the run record.

The single authorized PowerShell invocation used Temurin JDK `21.0.12.1+1-LTS`,
UTF-8, `--no-daemon`, `--console=plain`, `--continue` and separate stdout/stderr
files:

```powershell
$env:JAVA_HOME = 'E:/scoop/apps/temurin21-jdk/current'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat --no-daemon --console=plain --continue :common:check :common:javadoc verifyModIdentity > $stdout 2> $stderr
```

It exited 0 with `BUILD SUCCESSFUL` from 19:18:09 to 19:22:19 (+08:00) and
applied no test filter. All four test tasks executed, and all 621 archived JUnit
XML files were fresh: zero reused `UP-TO-DATE` reports and zero stale or
unproven reports.

| Module | XML suites | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Common | 535 | 5,843 | 5,841 | 2 |
| Fabric | 10 | 93 | 93 | 0 |
| Forge | 12 | 82 | 68 | 14 |
| NeoForge | 64 | 440 | 440 | 0 |
| Total | 621 | 6,458 | 6,442 | 16 |

Every row has zero failures and errors. Each skip is an assumption skip, not a
pass:

| Module | Class and method | Recorded reason |
| --- | --- | --- |
| Common | `InventoryFluidLoaderIntegrationTest.deterministicallyClearedOriginalNativeProofRetiresAndFreshRuntimeScanRecovers` and `.originalCommonCutRejectsReacquiredNativeRouteAndFabricAddressOrderWithIdenticalDetachedLayout` | `Assumption failed: requires a loader's native API/test fixture classpath`. |
| Forge | `FluidPresentationServiceTest.realPatchedFluidTypeBaseDescriptionAndLiteralOverrideUseNoNativeStack` | `Assumption failed: Forge's ordinary test task is vanilla-first; native success runs on the patched integration classpath`. |
| Forge | `NativeFluidAmountTest.legitimateEmptyPositiveAndPublicOverrideSemanticsArePreserved`, `.negativeNativeNbtConstructorAndSetterNeverBecomeEmptyVisits`, `.inaccessibleCachedPrimitiveFieldFailsClosedWithoutPoisoningTheNextRead`, `.nullAndThrowingNativeGettersRemainPaidSingleAttempts`, `.nativeReadFailureCannotReplaceThePreviousCompleteRuntimeResultWithZero` | Class-level `BeforeAll` assumption on the ordinary vanilla-first classpath; the XML skipped entries carry no message. |
| Forge | `NativeFluidRegistryFreezerTest.nativePositiveHandlerEntryFreezesThroughActualWrapper` | Ordinary vanilla-first classpath assumption; not a pass in the normal matrix. |
| Forge | `PlatformFluidRegistryServiceTest.foreignHolderMapAndMismatchedDelegateOrStageFailClosed`, `.realCurrentWrapperWaterFreezesExactlyWithAuthoritativeLongAmount`, `.refusalBeforeMetadataAndCurrentMapReadsIsPropagatedUnchanged`, `.retainedHolderIsNotProofOfActiveRegistrationAndSameObjectMapsAreReadAgain`, `.coldMetadataFailureIsCachedAndColdAdmissionPrecedesDiscovery`, `.realCurrentWrapperKeepsDistinctNativeNbtVariants`, `.emptyAndUnregisteredHostileFluidDoNotCallHashOrEquality` | Ordinary vanilla-first classpath assumption; not a pass in the normal matrix. |

These 16 remain the normal-matrix skips. The
[supplemental native-classpath run](#supplemental-native-classpath-verification)
below separately passes all 16 scenarios across seven dedicated tasks; it does
not rewrite these counts or turn a normal-matrix skip into a pass.

All three loader `compileJava` tasks, all loader `test`, `check`, `javadoc`,
jar/source/Javadoc packaging and `build` tasks, Fabric remapping and
`verifyModIdentity` executed successfully. Common main and test compilation and
all three loader test compilations were `UP-TO-DATE`. Of 53 actionable tasks, 27
executed and 26 were `UP-TO-DATE`.

The three shippable jars retained their exact names and version:

| Loader | Artifact | SHA-256 |
| --- | --- | --- |
| Fabric | `Ping-For-It-0.5.0-pfi-beta2-fabric-1.21.1.jar` | `06B7743001F84EB95E46A040C7B960B5BC00DF4683AA82819085F8A73995D175` |
| Forge | `Ping-For-It-0.5.0-pfi-beta2-forge-1.21.1.jar` | `B31A49CA95ECF8FC1580E39A7941AB76EFC50DAE3EA48C65ECC9156F9C3D803C` |
| NeoForge | `Ping-For-It-0.5.0-pfi-beta2-neoforge-1.21.1.jar` | `20B290FBE4147C13E5585B0369D5D1A8501FC1692FEE735765CA1E9BC7A2D79C` |

The external archive
`C:\Users\Void_Deer\AppData\Local\Temp\opencode\d0019-verification\combined-final-1-artifacts\20261107T1918097358676\jars`
retains these exact jars and their metadata. Identity is `pingforit` /
`Ping For It` / `0.5.0-pfi-beta2` on Minecraft 1.21.1. These are build and
artifact-identity checks, not an exhaustive package-content audit.

#### Intermediate rounds and repairs

The following executions explain how the final input was reached. Only the
invocation above is the acceptance result; failed or reused intermediate results
are not folded into its counts. The `combined-*` rounds used the recorded
PowerShell, Temurin JDK 21, UTF-8, `--no-daemon`, `--console=plain`, `--continue`
and redirected-log harness, with the task set named in the row. The focused and
direct rows retain only their own recorded evidence; unrecorded flags, JDK
versions, timestamps or hashes are not inherited or reconstructed.

| Round | Task set | Result |
| --- | --- | --- |
| `combined-production-1` | `:common:compileJava :fabric:compileJava :forge:compileJava :neoforge:compileJava` | **FAIL** exit 1; `:common:compileJava` failed and dependent compilers did not run. Tests were not run. |
| `combined-compile-2` | `:common:compileTestJava :fabric:compileTestJava :forge:compileTestJava :neoforge:compileTestJava` | **FAIL** exit 1; Common and NeoForge test compilation failed. |
| `combined-compile-3` | Same test-compilation task set | **FAIL** exit 1; Common main compilation failed before downstream tasks. |
| `combined-compile-4` | Same test-compilation task set | **PASS** exit 0; all four main and test compilations succeeded. |
| `combined-tests-1` | `:common:test :fabric:test :forge:test :neoforge:test` | **FAIL** exit 1; 6,455 tests, 230 failures, 16 skipped. |
| `combined-tests-2` | Same test task set | **FAIL** exit 1; Common test compilation failed and its 534 stale XML reports were excluded from conclusions; fresh NeoForge results contained 3 failures. |
| `combined-tests-2-retry-1` | Same test task set | **FAIL** exit 1; 6,455 tests, 59 Common failures, 16 skipped. Fabric/Forge reused 22 `UP-TO-DATE` XML reports. |
| `combined-tests-3` | Same test task set | **FAIL** exit 1; 6,457 tests, 13 failures, 16 skipped. |
| `combined-tests-4` | Same test task set | **FAIL** exit 1; 6,458 tests, 6 failures, 16 skipped. |
| Focused final repair | Common `--tests` selector rounds; exact selector list and global flags not retained | **FAIL then PASS**; the first round executed 90 tests with six failures; after source/test repair, the final focused round executed 176 tests and passed. |
| `nf-order-1` | Direct `javac` plus JUnit Launcher `java @ordered.args` and `java @isolation.args` | **PASS**; 30 ordered executions in one JVM (11 + 11 + 8) plus one independent isolation execution. Not a Gradle run. |
| Direct fluid/loader audit | Direct `javac` plus JUnit Platform | **PASS**; 92 full-class executions (26 Common + 44 NeoForge + 22 Create boundary) plus five focused executions. Intermediate, not part of the final matrix. |
| `FrozenGrantDeliveryTest` focus | `.\gradlew.bat --no-daemon :common:test --tests "nx.pingwheel.common.presentation.minecraft.FrozenGrantDeliveryTest"` (two attempts; UTF-8, `*>` redirect, no explicit timeout; `--console=plain` and `--continue` were not recorded) | **FAIL** exit 1 on both attempts at `:common:compileTestJava`; the test task never ran. Neither attempt is a pass; JDK version, start/end times and log hashes were not recorded. |
| `combined-final-1` | `:common:check :common:javadoc verifyModIdentity` | **PASS** exit 0; 6,458 tests, 0 failures, 0 errors, 16 skipped. |

The six `combined-tests-4` failures were in `FrozenGrantDeliveryTest`,
`InventoryProductionCreateTest` and `InventoryProductionPolicyTest`. Focused
diagnostics isolated the create and policy paths; source/test repair then let the
final Common full suite above run them successfully. The later `1/1` delivery
case result is from that full suite, not from either failed focused attempt.
The direct audit and `nf-order-1` are separate evidence and do not add to the
final totals; `combined-tests-2-retry-1`'s reused reports are explicitly reuse,
not fresh execution. The direct audit's intermediate classpath-repair logs were
overwritten; only its final direct run is retained, and it is not final
acceptance. Earlier ad-hoc logs with no recorded command line were not used as
evidence and are not reconstructed.

`git diff --check HEAD -- <changed implementation, test and build files>` exited
0; its only output was LF-to-CRLF working-copy warnings. The combined-final
snapshot's documentation check scanned 71 Markdown files and passed 1,577 local
links with zero broken links and zero validated external URIs. The
documentation-only evidence update reran the same checker on the edited corpus:
71 files, 1,588 local links, zero broken and zero external URIs. No separately
configured Java lint, formatter or static-analysis task exists, and none was
invented or run; the actual Java compile, Javadoc/doclint, Gradle check,
`verifyModIdentity` and Git checks above are the configured evidence. Nonfatal
Javadoc missing-comment/`@param`, Mixin mapping, Forge annotation-processor and
Gradle deprecation warnings remain; this is not a warning-clean result.

This is headless automated evidence. No Minecraft client or server, loader
launch, multiplayer/mixed-version connection, installed Create/Sable provider,
GPU/font/UI output, manual gameplay or CPU/TPS/GC/allocation/FPS measurement was
performed. The frozen-authorization Create path still discovers the full initial
target structure; its automated operation-count assertions are current-check
counts, not a live FPS or allocation improvement. The optional dedicated-provider
SPI remains future work and is not claimed. Live delivery/repair, a member that
was not `HELLO`-ready at create, true disconnect, live source-read revocation and
live reconnect remain pending in the
[manual matrix](manual-integration.md#frozen-recipient-authorization-and-provider-trust).

#### Supplemental native-classpath verification

The ordinary matrix above retains its 6,458 tests, zero failures and 16 skips.
A separate external supplemental harness exercised those skipped scenarios on
loader/native classpaths against the same commit
`a4ae36fa121c1899c946e1a419ae66afbbfb4344` and source raw identity
`3fd2a0d0118b34118bcd8cd7beece8d05fa5c04b722192b65e417e0e2ecd80fb`.
It did not rewrite the ordinary XML or add its executions to 6,458. The three
shippable jars remained byte- and mtime-identical, and no ordinary-matrix test
task was rerun.

Both invocations used PowerShell, Temurin `21.0.12.1+1-LTS`, UTF-8, `--offline`,
`--no-daemon`, `--no-build-cache`, `--console=plain`, `--continue` and separate
stdout/stderr files. Here `$evidence` is
`C:\Users\Void_Deer\AppData\Local\Temp\opencode\d0019-verification`:

```powershell
.\gradlew.bat --offline --no-daemon --no-build-cache --console=plain --continue --init-script "$evidence/native-supplement.init.gradle" --init-script "$evidence/native-supplement-forge.init.gradle" :fabric:fluidSourceIntegration :forge:fluidSourceIntegration :neoforge:fluidSourceIntegration :forge:loggingFluidPresentationIntegration :forge:loggingNativeFluidAmountIntegration :forge:loggingFluidRegistryIntegration :forge:loggingNativeFluidRegistryFreezerIntegration
.\gradlew.bat --offline --no-daemon --no-build-cache --console=plain --continue --init-script "$evidence/native-supplement-forge-source-fixed.init.gradle" :forge:fluidSourceIntegration
```

| Script | SHA-256 |
| --- | --- |
| `native-supplement.init.gradle` | `1671e97cf2e458eac4bf5f07ed4cc96a148894630a06d59f83ba857e5481d809` |
| `native-supplement-forge.init.gradle` | `0a1987cb2fdd62b6b6bc6464b95caf0a23556abc1ebd12cfe8ee6de5dcdba8a2` |
| `native-supplement-forge-source-fixed.init.gradle` | `5bd2e531ef78c4232f03080a5cf2bdb6a2d9e1952c3ff4d54c5fde749b3209a6` |
| `native-supplement-state.py` | `c3154d281f11d8f90835834875338ab448782fe0143c4f8d1c626b75a410950a` |
| `native-supplement-2-state.py` | `897def82c1b9b4a8f4a241e02ebf578e387cb4d217f7459fd8fbe9bc56845a23` |

The first invocation ran from 20:00:25 to 20:01:24 (+08:00), 58.8 seconds, and
exited 1. It ran seven tasks and produced seven fresh XML files: 19 passing
scenario executions and one Forge `fluidSourceIntegration` initialization failure.
That failure was a `NoSuchMethodException` for
`net.minecraft.world.level.material.Fluid.getFluidType()` during
`InventoryFluidLoaderIntegrationTest` bootstrap because the vanilla jar preceded
the patched Forge jar. It is an initialization failure, not a scenario pass; the
first invocation is not recorded as all-seven passing.

The second invocation ran from 20:06:38 to 20:07:05 (+08:00), 26.8 seconds, and
exited 0. It reran only Forge `fluidSourceIntegration`; its two cases passed.

| External task | First invocation | Second invocation | Passing scenario executions |
| --- | --- | --- | ---: |
| `:fabric:fluidSourceIntegration` | 2/2 passed | Not run | 2 |
| `:forge:fluidSourceIntegration` | 0 scenarios; 1 initialization failure | 2/2 passed | 2 |
| `:neoforge:fluidSourceIntegration` | 2/2 passed | Not run | 2 |
| `:forge:loggingFluidPresentationIntegration` | 2/2 passed | Not run | 2 |
| `:forge:loggingNativeFluidAmountIntegration` | 5/5 passed | Not run | 5 |
| `:forge:loggingFluidRegistryIntegration` | 7/7 passed | Not run | 7 |
| `:forge:loggingNativeFluidRegistryFreezerIntegration` | 1/1 passed | Not run | 1 |
| Total | 19 passed + 1 initialization failure | 2 passed | 21 |

The only harness correction moved the already-present, same-version Forge
patched jar from classpath index 6 to index 0. Its SHA-256
`6ca6adb420e93cdd2c4b3e39fd580ebcc6473fd10861cd79b674fb81e436ddee`
matched, every other original classpath entry retained its relative order, and
the selectors, `pingforit.test.fluidLoader=forge` system property, JUnit and
worker settings were unchanged. No dependency version changed.

The 14 ordinary Forge skip methods pass in the four Forge tasks (2 + 5 + 7 + 1 =
15 executions, including one extra in `loggingFluidPresentationIntegration`).
The two Common fluid-loader skip methods pass on Fabric, NeoForge and Forge (2 x
3 = 6 executions). The resulting 21 passing scenario executions span seven tasks
and include repeated source methods on different loader classpaths; 21 is not a
unique-test count and does not replace the 16 normal-matrix skips. The external
evidence archives are `native-supplement-1-artifacts` and
`native-supplement-2-artifacts`; their result files are
`native-supplement-1.results.json` and `native-supplement-2.results.json` in the
same evidence directory. These are headless JUnit/classpath executions, not
loader launch, a Minecraft client/server or game behavior.

### ITEM redundant-work final verification

Recorded execution date: 2026-11-08. This is the final acceptance run for the
ITEM redundant-work optimization set on branch `agent/perf-fix`. The tested input
was a dirty combined working tree at HEAD
`0301adb625b3148839929340b5c30b9dae05302b` (tree
`c90cf1a44a5c90f31072d9529621b14077a292ac`), not a clean commit. The certified
chest-mapping change (P3) was already in that HEAD; the P1 lane-policy hint, P4
publication reuse and their test changes remained uncommitted, together with the
pre-existing documentation changes. After execution, commit
`0d9f841116e3789ac224cc1393c6990b0f01cb33` on parent
`0301adb625b3148839929340b5c30b9dae05302b` committed the identical 14 Java
paths (seven production and seven test); no Gradle or tests were rerun for that
commit. The recorded result remains the execution at HEAD `0301adb` plus the
working hashes below, and the external evidence below binds those bytes to
`0d9f8411` without making this a clean-commit execution or retest.

The tested source/build input manifest SHA-256 was
`4E93DC0302B0AC883A80D2355DAEC6CC50B863D2E845AAE661B0A76D3DD023D9`
(`final.inputs.csv`, 1,425 non-documentation build/source inputs). The tracked
binary diff SHA-256 was
`06817ED07A89FFA838568E95274C1E42DAB102A4592A9AC79917AA692E5F9260`
(`final.diff`). The all-file manifest SHA-256 was
`0B05DED9B528AE3427BA064E782DB8ADEAB055088336AB08E75D678855F074D1`
(`final.files.csv`): it covers all 1,513 tracked and non-ignored untracked
repository files, paths plus bytes, so it identifies the untracked Java files and
dirty tracked sources and documentation as well as tracked changes. It is not an
untracked-only manifest and the tracked diff alone does not identify the
untracked bytes. HEAD, the tracked diff and both manifests were identical before
and after every recorded invocation and at final inspection.

The external `item-final-commit.evidence.json` records commit
`0d9f841116e3789ac224cc1393c6990b0f01cb33` (tree
`b1bbe37c0f41ff335b800c002557ddc7d684a754`, parent `0301adb`), which contains
exactly those 14 Java paths: seven production and seven test. All 1,425 raw
source/build inputs matched the tested `final.inputs.csv` manifest above before
and after the commit; all 1,425 normalized committed blobs
match the tested tree; the nine archived loader main/source/Javadoc artifacts,
including the three shippable jars, retained their SHA-256 and `archiveMatches`;
and the protected build/configuration/network/resource files, mod version
`0.5.0-pfi-beta2`, Minecraft 1.21.1 and the inventory/presentation protocol
version-2 values were unchanged.

All 1,425 source/build paths match the owner's passing
`policy-lifecycle-repair-01/run4-full` input manifest byte-for-byte
(`prior-full-input-delta.csv` is empty); that run's manifest additionally covers
17 repository files outside the build-input scope, so its manifest SHA-256 is a
different scope, not a different source state. The full `run4-full` test run at
the same HEAD and tracked diff ran 6,498 tests: 6,482 passed, 16 skipped, and zero
failures or errors. It supplies the reused Fabric and Forge reports below.

The single final PowerShell invocation used Temurin JDK `21.0.12.1+1-LTS`, UTF-8,
`--no-daemon`, `--console=plain`, `--continue` and separate stdout/stderr files:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat --no-daemon --console=plain --continue :common:check :common:javadoc verifyModIdentity > $stdout 2> $stderr
```

It exited 0 with `BUILD SUCCESSFUL` from 01:02:58 to 01:07:37 (+08:00), with no
test filter and no rerun init script. All four test tasks produced a complete
result set, but only Common and NeoForge executed freshly in this invocation:

| Module | Suites | Tests | Passed | Skipped | Execution in final invocation |
| --- | ---: | ---: | ---: | ---: | --- |
| Common | 540 | 5,876 | 5,874 | 2 | Fresh, unfiltered. |
| Fabric | 11 | 94 | 94 | 0 | `UP-TO-DATE`; same-input reuse of the passing `run4-full` XML. |
| Forge | 14 | 85 | 71 | 14 | `UP-TO-DATE`; same-input reuse of the passing `run4-full` XML. |
| NeoForge | 66 | 443 | 443 | 0 | Fresh, unfiltered. |
| Total | 631 | 6,498 | 6,482 | 16 | Zero failures and zero errors. |

The reused Fabric/Forge XML is byte-identical to the archived `run4-full`
reports, and the compared source/build inputs are unchanged.
The 16 skips are the same normal-matrix fluid-classpath cases as the
[D0019 record above](#provider-trust-and-frozen-authorization-final-verification):
two Common `InventoryFluidLoaderIntegrationTest` cases and 14 Forge
patched-first native-fluid cases. They are not ITEM wrapper cases; the ITEM
wrapper and regression cases ran without skips in this suite. The old fluid
native-classpath supplemental harness was not rerun because the ITEM change does
not affect those fluid classpaths.

`common:check`, `common:javadoc`, all three loader `build`, `javadoc` and
packaging tasks, Fabric remapping and `verifyModIdentity` succeeded. Version and
protocol metadata were unchanged. The three current shippable jars were newly
built and retained:

| Loader | Artifact | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Fabric | `Ping-For-It-0.5.0-pfi-beta2-fabric-1.21.1.jar` | 3,545,911 | `4b86f553bbf000ed192444217e3b16b53b536588cbe33479127a06c2a05355c6` |
| Forge | `Ping-For-It-0.5.0-pfi-beta2-forge-1.21.1.jar` | 3,497,279 | `85304ce07367251ae2f2e467b9e6169d57971fd12e9108eddcfab4f9ae42a10e` |
| NeoForge | `Ping-For-It-0.5.0-pfi-beta2-neoforge-1.21.1.jar` | 3,803,648 | `3f926671df234928fc9bc8fbbfd7e98db97d0f1c5da7becf40dbc89843799367` |

Identity verification passed for `pingforit` / `Ping For It` /
`0.5.0-pfi-beta2` on Minecraft 1.21.1. These are artifact-identity checks, not an
exhaustive package-content audit. No independently configured Java lint,
formatter or static-analysis task exists. The actual
`git diff --check -- common/src fabric/src forge/src neoforge/src` exited 0. The
run retained existing Javadoc missing-tag, mapping and Gradle deprecation
warnings; it is not warning-clean evidence.

#### Delivered ITEM scope

- **P1 on-demand lane-policy hint.** `InventoryPolicyFrozenDemandRegressionTest`
  passed 3/3 and `InventoryProductionPolicyTest` passed 99/99. The execution
  covered accepted Preview and existing Ping grant retention with fresh later
  `OPEN`/`SELECT` resolution; the detailed authority, retry and ownership
  assertions are owned by
  [lane policy hint refresh verification](inventory.md#lane-policy-hint-refresh-verification).
  These are authority and ownership assertions, not allocation measurements.
- **P3 certified chest mapping; P2 withdrawn.** `CertifiedChestFrozenMappingTest`
  passed 7/7 and the common/Fabric/Forge/NeoForge resident-scope and actual
  Forge/NeoForge wrapper-boundary regressions passed. The immutable single- and
  double-chest address mapping (27/54 pairs) is proved once at construction; the
  view owns immutable entity, address and member copies; current validation
  still checks live identity, pairing, size, owner, lock and grant without
  re-walking producer-owned address lists. The baseline authority gates and
  existing conservative cost accounting remain unchanged and the old cost is
  not lowered. P2 lookup deduplication was withdrawn because the evaluated
  `valid()` callback paths did not establish callback-free closure; the baseline
  current-authority sequence is retained. The new tests protect that baseline
  authority and add no unpaid extra authority pass or P2 completion/benefit
  claim.
- **P4 immutable publication work reuse.** `CutWorkReuseTest` passed 3/3,
  `InventoryChecksumsTest` passed 7/7, `InventorySyncPublisherTest` passed 52/52
  and `InventoryFanoutEncodingReuseTest` passed 3/3. FNV-1a checksum bytes and
  the v2 wire format are unchanged. The checksum, retry, delivery and fan-out
  assertion scope is owned by
  [immutable publication work reuse verification](inventory.md#immutable-publication-work-reuse-verification);
  no N-times or end-to-end allocation gain is claimed.

Full command lines, logs, JUnit XML/HTML, manifests, failure stacks, archive
hashes and the complete attempt history are retained in the external
`item-redundant-work-20261107` evidence directory and its `execution-ledger.md`.

#### Matched baseline-to-after observations

The dedicated after commands re-ran the same five Common and three NeoForge
performance/regression classes on the tested input: 167/167 Common and 16/16
NeoForge passed. All eight test-source SHA-256 values were compared; the three
NeoForge performance sources are unchanged, while
`InventoryProductionPolicyTest` (88 to 99 cases) and
`InventorySyncPublisherTest` (49 to 52 cases) changed. The selected Common total
153 to 167 is therefore regression verification, not an identical-case
benchmark comparison. Both certified 1,620-slot profiles match fixture/world
class hashes, normalized settings, JDK/VM, three warmups and five samples; the
900-slot metadata string also matches.

The comparison matched 72 keys (36 counter and 36 allocation) with no missing
keys, profile changes or schema issues. Across all 36 counter rows, every actual
operation counter is unchanged: physical visits, committed addresses, pages,
completions/scans, loaded/read checks, defers and invalid results. The only
numeric counter change is modeled retained bytes `+2` in all 24 certified rows;
reserved bytes stay zero in every row. The 900-slot retained/reserved counters
are unchanged. Certified `inferredCommonCurrent` and provider matches, and the
900 inferred full matches, are derived values, not direct gate-call counters.
The 900 and 1,620 fixtures have different slots, fixtures and paths and are not
cross-compared or subtracted.

| Representative full sweep | Physical visits | Pages | Completion/scan | Actual common/provider loaded | Inferred matches | Modeled retained |
| --- | --- | --- | --- | --- | --- | --- |
| certified 1,620 / INCIDENT / EMPTY | 1,620 → 1,620 | 51 → 51 | 1 → 1 | 1,081,179 / 2,439,684 unchanged | 13,266 → 13,266 | 767,116 → 767,118 |
| certified 1,620 / DEFAULTS / EMPTY | 1,620 → 1,620 | 51 → 51 | 1 → 1 | 1,081,179 / 2,439,684 unchanged | 13,266 → 13,266 | 767,116 → 767,118 |
| vault 900 / MIXED | 900 → 900 | 29 → 29 | 1 → 1 | 84,134 / 584,036 unchanged | 5,458 → 5,458 | 253,412 → 253,412 |

Allocation observations use `ThreadMXBean` current-thread allocated bytes and
`System.nanoTime` elapsed time with three warmups and five samples; they are not
retained heap, allocation rate, CPU, TPS or a portable threshold. Every
certified full-sweep median increased by 9,792 bytes (about 0.010–0.014%):
EMPTY 70,661,616 → 70,671,408 and 70,660,392 → 70,670,184 for INCIDENT and
DEFAULTS, REPEATED 79,791,592 → 79,801,384 and 79,788,600 → 79,798,392, MIXED
93,532,344 → 93,542,136 and 93,526,952 → 93,536,744. Certified window32 increased
192 bytes and idle validation 64 bytes. The 900-slot EMPTY and REPEATED sweep
medians are unchanged; MIXED increased 384 bytes (17,094,112 → 17,094,496).
Time medians move in both directions; no ratio determines pass/fail and no
speedup is claimed.

These macro fixtures do not traverse the P1 Backend hint path, the P3 chest
route or most P4 fan-out paths, so their unchanged counters do not prove those
optimizations and their small allocation increases are not asserted to be
noise. The direct named regressions above are the evidence for those boundaries.
No overall FPS, TPS, CPU, GC, allocation-rate or live-performance improvement is
claimed.

#### Historical attempts and audit exceptions

The following runs are retained as history and are not folded into the final
acceptance totals. External logs, exact commands and failure stacks remain the
detailed record.

| Attempt | Result and treatment |
| --- | --- |
| Baseline `gradlew --version` probe | Audit exception: omitted `--no-daemon` and stdout/stderr redirection; ran no build or test and was not reconstructed as compliant. |
| Four test-agent policy compile attempts | Four unauthorized, unfrozen-input `:common:compileTestJava` attempts, all exit 1; not a gate, not reused and not acceptance evidence. |
| `central-integration-01` | First compile failed on nine javac errors in two untracked tests; one mechanical repair passed. The targeted subset then ran 244 tests with 238 passes and six assertion failures. |
| `central-integration-02` | Full four-module tests ran 6,492 tests with 6,469 passes, seven failures and 16 skips; the isolated chest class passed 7/7. After-baseline and later gates were not entered. |
| `central-integration-03` | Full four-module tests ran 6,493 tests with 31 failures and 16 skips; later gates were not entered. |
| `central-repair-01` | Six diagnostic-only invocations, all failed; no JUnit XML or result record exists for diag2–diag6, and only diag1 retains an input manifest. They are not acceptance evidence. |
| `policy-lifecycle-repair-01` | Run 1 ran 152 tests with 31 failures; run 2 ran 157 with two failures; run 3 passed 157; `run4-full` ran 6,498 tests: 6,482 passed, 16 skipped, zero failures/errors, and supplies the reused Fabric/Forge XML. |
| After pair and final run | The two after commands passed 167/167 Common and 16/16 NeoForge; the final command passed as recorded above. |

This is headless automated evidence. No live or in-game Minecraft client or
server, loader launch, multiplayer/mixed-version connection, installed
Create/Sable provider, GPU/font/UI output, manual gameplay or live
CPU/TPS/GC/allocation/FPS measurement was performed. The headless
`ThreadMXBean` allocation observations and scoped macro comparison above remain
recorded; they do not measure live CPU, GC, allocation rate, TPS or FPS. The
6,498-test final result applies to the tested working-tree bytes.

### Inventory initial-admission and terminal-reset final verification

Recorded execution date: 2026-11-08. This is the final acceptance run for the
three Inventory regression fixes on branch `agent/perf-fix`. The tested input was
a dirty combined working tree at HEAD
`c124208ad265878e03da86f4ba68e705c9b9da01` (tree
`98a99a9c980235304a4f3a143a0f17c58d44493c`), not a clean commit. Eight changed
paths were present: seven Java production/test paths plus `inventory.md`; the
index was empty. The 1,427 source/build inputs had manifest SHA-256
`9741B4D62DFCAF8278BF71947E8D0224F78AB79F072108BAE82604BAE616D9E2`.
All 1,515 tracked and non-ignored untracked repository files remained unchanged
through the full, native and final-inspection phases. This documentation update
is later than that input and is outside the source/build scope.

After execution, commit `a610498608dd9ce18b898bf3a6dfb75cef96667e`
(`fix: correct inventory admission and publication lifecycles`) on parent
`c124208ad265878e03da86f4ba68e705c9b9da01` committed exactly the seven tested
Java paths (four production and three test); documentation was not committed.
All 1,427 post-commit source/build inputs remained byte-identical to the tested
manifest, and the seven committed blobs are normalization-equivalent to the
tested files. This is a byte-identity binding, not a clean-commit retest: the
execution above remains `c124208` plus the dirty seven-code/one-doc input, and
no Gradle or tests were rerun for the commit. `commit-report.md` and
`commit-authorized.result.json` retain the commit evidence.

`central-full-checks-1` did not reach a task: its evidence init evaluated
`:common` against `buildSrc` and failed with `Project with path ':common' could
not be found in project ':buildSrc'`. The sole harness correction added a
root-project guard to both evidence hooks; no production or test source changed.
`central-full-checks-2` is the acceptance invocation:

```powershell
$evidence = 'C:\Users\Void_Deer\AppData\Local\Temp\opencode\inventory-regressions-20261108\central-02'
$env:JAVA_HOME = 'E:/scoop/apps/temurin21-jdk/current'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat ':common:check' ':common:javadoc' 'verifyModIdentity' '--continue' '--no-daemon' '--console=plain' '--init-script' "$evidence\force-fresh.gradle" '--init-script' "$evidence\full-evidence.init.gradle" 1> "$evidence\central-full-checks-2.stdout.log" 2> "$evidence\central-full-checks-2.stderr.log"
```

It exited 0 with `BUILD SUCCESSFUL` in 5m 58s: 53 actionable tasks, 29 executed
and 24 `UP-TO-DATE`. All four ordinary Test tasks executed freshly with no test
filters:

| Module | Suites | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Common | 541 | 5,900 | 5,898 | 2 |
| Fabric | 12 | 99 | 99 | 0 |
| Forge | 14 | 85 | 71 | 14 |
| NeoForge | 66 | 443 | 443 | 0 |
| Total | 633 | 6,527 | 6,511 | 16 |

Every row has zero failures and errors; all 633 XML suites were fresh and none
was reused. `InventoryTerminalPreviewResetTest` passed 5/5,
`FluidIdentityAdmissionScalingTest` passed 5/5 and
`InventoryProductionPolicyTest` passed 118/118 (99 prior, 15
initial-admission and four removal/fan-out cases), all without skips.

#### Native supplement and skip mapping

The native supplement ran in two invocations: six tasks in `native-six-1` and the
corrected Forge source task in `native-forge-source-1`. Seven tasks produced
seven fresh XML suites and 21 successful scenario executions, with zero
failures, errors or skips:

| Task | Executions |
| --- | ---: |
| `:fabric:fluidSourceIntegration` | 2 |
| `:neoforge:fluidSourceIntegration` | 2 |
| `:forge:fluidSourceIntegration` | 2 |
| `:forge:loggingFluidPresentationIntegration` | 2 |
| `:forge:loggingNativeFluidAmountIntegration` | 5 |
| `:forge:loggingFluidRegistryIntegration` | 7 |
| `:forge:loggingNativeFluidRegistryFreezerIntegration` | 1 |
| Total | 21 |

Repeated common methods on distinct loader classpaths are scenario executions,
not 21 unique new tests. Current workspace test classes were on each classpath,
class hashes matched before and after, and no classpath entry was missing. The
Forge patched-first source context moved its index from 6 to 0, preserved every
other entry's relative order and used patched SHA-256
`6CA6ADB420E93CDD2C4B3E39FD580EBCC6473FD10861CD79B674FB81E436DDEE`.
The 16 ordinary skips remain 16 and are not converted to passes: two Common
`InventoryFluidLoaderIntegrationTest` cases and 14 Forge vanilla-first
native-fluid cases. `ordinary-skip-native-coverage.csv` maps every skipped method
to its passing supplemental task.

#### Build and artifact results

All three loader builds, `:common:javadoc` and `verifyModIdentity` succeeded;
mod version `0.5.0-pfi-beta2` and Minecraft 1.21.1 were unchanged. The executed
check graph contains the four Test tasks plus Fabric `validateAccessWidener`
(`UP-TO-DATE`); no standalone lint, formatter or static-analysis task is
configured. Nine main/source/Javadoc jars were archived; the three shippable jars
were:

| Loader | Artifact | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Fabric | `Ping-For-It-0.5.0-pfi-beta2-fabric-1.21.1.jar` | 3,546,338 | `C1F890EDC11435DD163914DB44D02DAC33892BFBF5C3CD4FB4CEC9F55EA0A4A8` |
| Forge | `Ping-For-It-0.5.0-pfi-beta2-forge-1.21.1.jar` | 3,497,667 | `79252C86341CCE50C0941FB7D8A7C779CAE235F97A61AE3C52B0347CEE790AD0` |
| NeoForge | `Ping-For-It-0.5.0-pfi-beta2-neoforge-1.21.1.jar` | 3,804,036 | `06B2AD8E081D71476B91D7175E375570B31999D7353EFD1C02096AE649DE622C` |

The run retained existing deprecated API/Gradle, mapping/processor and Javadoc
warnings, and some tool-rendered diagnostics contained replacement characters
despite UTF-8 settings; the raw logs preserve the output. No OOM, compile
failure, test failure or heap/cap adjustment occurred.

#### Superseded and focused evidence

These runs are retained as history or prior-input owner evidence; they are not
added to the central totals.

| Run | Scope | Result |
| --- | --- | --- |
| `central-01` | Four module test-compilation tasks, then focused selectors | Compilation exited 0 for the four test-compilation tasks. The focused run executed 508 tests: 503 passed, four `InventoryTerminalPreviewResetTest` fixture failures and one known Forge vanilla-first skip. Its terminal fixture set the preview period to 20, above `InventoryLimits.MAX_PERIOD_TICKS`; it is a historical failed attempt, not acceptance. |
| `terminal-focused-02` | Final terminal class, related selectors, negative/positive control | Final terminal class 5/5, related selectors 9/9, negative control 1 expected `INVALID`-versus-`INCOMPLETE` failure, restored positive control 1/1 pass. |
| `suppressed-followup-03` | Callback-time removal negative control and final positive classes | Pre-guard 4 executions: canonical ITEM/FLUID 2 expected failures, sidecar-only ITEM/FLUID 2 passes. Post-guard five classes passed 140/140, including `InventoryProductionPolicyTest` 118/118. |

Logs, XML, manifests, task graphs and artifact records for the central run are
retained in the external
`C:\Users\Void_Deer\AppData\Local\Temp\opencode\inventory-regressions-20261108\central-02`
directory; the focused owner records are in the external
`C:\Users\Void_Deer\AppData\Local\Temp\opencode\inventory-regressions-20261108\terminal-focused-02`
and `C:\Users\Void_Deer\AppData\Local\Temp\opencode\suppressed-followup-03`
directories. The central directory retains `final-execution-ledger.md`,
`final-summary.json`, `final.state.json`, `full-module-counts.json`,
`regression-class-counts.csv`, `native-results.json`,
`native-provenance-check.json`, `ordinary-skip-native-coverage.csv`,
`full-task-graph.json` and `build-artifacts.json`.

Coverage scope, including the seeded-Basic removal path and the statically
reviewed alternative empty-source branch, is owned by
[fluid inventory coverage](inventory.md#fluid-inventory-coverage),
[creation snapshot server coverage](inventory.md#creation-snapshot-server-coverage)
and [terminal Preview evidence](inventory.md#terminal-preview-evidence-and-active-demand).
This record adds no live game, loader launch, multiplayer, rendering, heap, TPS,
latency or manual gameplay evidence. The executor's documentation check covered
71 files with zero broken links and `git diff --check` exited 0; the final
coverage update was rechecked with the same command over 71 files and 1,611
local links with zero broken links.

### Inventory reset regression final verification

Recorded execution date: 2026-11-08. This is the final acceptance run for the
Preview cancellation/`OPENED` reset boundary and client creation-admission
regressions on branch `agent/perf-fix`. The tested input was a dirty combined
working tree at HEAD `658235a5f3a8323a18a2f006a54df1d1781dd6fa` (tree
`fd65ecd28165f1416bd0263a84ee1cd0d1efe799`), not a clean commit. The final 1,429
source/test/resource/build inputs had manifest SHA-256
`67FF8699F7669F5FC95B1082C3AFBFE5A1AFA7ACBF5D1F140B2FD229A48BF2C8`; the two new
test files were untracked, the index was empty, and the six task files matched
their external final backups byte for byte. Thirty pre-existing dirty
documentation/discipline paths remained outside that source/build scope. Two
authorized topic-document edits occurred during the final runs and did not change
the frozen source/build manifest. The recorded executor made no source, test,
build or documentation edit and did not stage, commit or push; only the
authorized minimal control hunks were temporarily patched and restored.

After execution, commit `02ec9aafd1178cae664d1ca2414b96b9adb4726f` (tree
`1fd98d26a3db3b648dda6a3ef7aeab167f35cf92`) on parent
`658235a5f3a8323a18a2f006a54df1d1781dd6fa` with subject
`fix: preserve preview reset lifecycle and stale replacement admission`
committed exactly the six Java paths: four production and two test. Only those
six paths were committed; documentation, build metadata, version and evidence
files were not staged, and no push occurred. The accepted Gradle runs remain the
old HEAD plus the dirty six-file worktree, not a clean-commit retest. Before
staging, after staging and after commit, all 1,429 input hashes and all nine
recorded jar hashes were unchanged, and each worktree blob equalled its committed
HEAD blob. No Gradle command or test rerun was made for the commit.
`central-03/commit-report.md` records this binding.

Earlier phases and expected-failure controls are retained as history and are
not added to the final totals:

| Phase | Result |
| --- | --- |
| `central-01` four-module test compilation | Exit 0 on the earlier input; 24 actionable tasks, eight executed and 16 up-to-date; all four `compileTestJava` tasks succeeded and all four main compilations executed; no tests ran. |
| `central-01` focused | Exit 1; 73 executions, 72 passed, one fixture-precondition failure and zero skips. |
| `server-focused-02` exact | Exit 0; 1/1. |
| `server-focused-02` regression-eight | Exit 0; 8/8, the new server class 3/3 and existing terminal class 5/5. |
| `central-02` negative-A | Exit 1; strict view equality left the request unreleased, matching the intended `CLOSE` control. |
| `central-02` negative-B | Exit 1; an earlier 256-versus-257 frame count, not certified as `OPENED`-ordering evidence. |
| `central-03` negative-B | Exit 1; terminal `INVALID` before replacement `OPENED`, matching the intended ordering control. |
| `central-03` negative-C | Exit 1; the false parameter missed the expected marker admission while the true parameter passed. |
| `central-03` positive | Exit 0; 21/21 across the new server, new client and existing terminal classes. |

The initial focused failure was a fixture precondition: a below-minimum
physical-slot setting normalized to the legal minimum, so the Preview completed
before its idle-health period and terminal invalidity was not due at the original
tick. The corrected fixture used the legal minimum, spent the legal budget with
real `HELLO` traffic, and required an actual due health validation before the
reset assertions. Negative A was not rerun; its failure remains historical
evidence for the strict-view control, while the final positive/full runs
exercise the fixed current input. Each temporary control hunk was restored by
its inverse before the next phase; every restore rechecked all 1,429 final input
hashes. Expected control failures are not final-test failures.

The restored positive invocation selected `InventoryPreviewResetLifecycleTest`,
`ClientInventoryCreationCapacityTest` and `InventoryTerminalPreviewResetTest`:
three suites, 21 executions, all passed, zero skips. The final unfiltered
four-module invocation was:

```powershell
.\gradlew.bat ':common:cleanTest' ':common:check' ':common:javadoc' 'verifyModIdentity' '--init-script' 'C:/Users/Void_Deer/AppData/Local/Temp/opencode/inventory-reset-regressions-20261108/central-03/full.init.gradle' '--continue' '--no-daemon' '--console=plain'
```

It exited 0 with `BUILD SUCCESSFUL` in 5m 7s: 54 actionable tasks, 25 executed
and 29 up-to-date. All four ordinary Test tasks ran freshly with empty include,
exclude and command-line filter sets. Counts:

| Module | Suites | Tests | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Common | 543 | 5,916 | 5,914 | 0 | 2 |
| Fabric | 12 | 99 | 99 | 0 | 0 |
| Forge | 14 | 85 | 71 | 0 | 14 |
| NeoForge | 66 | 443 | 443 | 0 | 0 |
| Total | 635 | 6,543 | 6,527 | 0 | 16 |

`InventoryPreviewResetLifecycleTest` passed 3/3,
`ClientInventoryCreationCapacityTest` 13/13 and
`InventoryTerminalPreviewResetTest` 5/5, all without skips. All 39 previously
planned selectors were present in the unfiltered output and expanded to 51
passing executions; no focused rerun substitutes for the full result.

All four Javadoc tasks, all three loader builds, Fabric remapping and
`verifyModIdentity` executed and passed; nine main/source/Javadoc jars were
archived. Mod version `0.5.0-pfi-beta2`, Minecraft 1.21.1 and the route versions
were unchanged. Main and test compilation tasks were `UP-TO-DATE` in the full
run; this is reused compilation, not a fresh-recompile claim. The earlier
four-module compilation covers its historical input.

The 16 ordinary skips are the unchanged native-classpath limitation: two Common
`InventoryFluidLoaderIntegrationTest` cases and 14 Forge fluid-presentation,
amount, registry and freezer cases. No native-fluid supplement ran because those
adapters and profiles were unchanged; historical native passes are not claimed
as current same-run coverage. No separate lint, formatter or static-analysis task
is configured. The timeout proposal remained read-only and no timeout test ran.
No live game, loader launch, dedicated server, manual interaction, rendering,
multiplayer, heap/TPS/latency or other performance evidence was produced.
Documentation changes during the runs are outside the source/build scope; any
later source/test/build edit invalidates this input-specific record.

The complete command ledger, logs, module/new-class/skip/artifact summaries,
task graph, filter audit and negative-control records are retained at
`C:\Users\Void_Deer\AppData\Local\Temp\opencode\inventory-reset-regressions-20261108\central-03`;
the earlier phases are under the sibling `central-01`, `server-focused-02` and
`central-02` directories. Coverage scope is owned by
[terminal Preview evidence](inventory.md#terminal-preview-evidence-and-active-demand)
and [client creation admission capacity](inventory.md#client-creation-admission-capacity).

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
limits in [inventory verification](inventory.md#patch-only-identity-and-display-coverage-and-live-gaps).
The scoped allocation comparison does not measure live CPU, GC, allocation rate,
heap retention or TPS, and automated results do not supply missing independent
scratch-bound review evidence. These limits and the separate named-host Mixin
probe limits are detailed in the
[inventory gaps](inventory.md#known-automated-gaps). The
[manual matrix](manual-integration.md#pending-manual-and-integration-matrix)
remains pending.
