# Build checks

[Verification index and evidence rules](README.md) ·
[Pending manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix)

## Build, source-set and artifact verification

The included Gradle projects are `common`, `fabric`, `forge`, and `neoforge`.
Loader source sets receive shared common Java and resources through the shared
loader wiring. Fabric retains the common mixin configuration and Loom-generated
intermediary refmap; Forge and NeoForge use their loader-local official-Mojmap
configuration instead. Because each loader source set compiles the shared common
sources, an affected loader build is source-set and compile evidence for those
units, not runtime, transport, or in-game verification. Fabric and Forge test
source sets exercise headless native-wrapper seams; that is seam evidence only,
not loader-launch, mixin-application or in-game evidence.

An inventory entry or static wiring test is not a Gradle execution record. No
separately configured Java lint, formatter, or static-analysis task exists; the
Java/build checks are the Gradle test tasks and the loader build and
artifact-identity tasks below. Final-state evidence for a change must identify
the combined tree, commands and results for the affected tests and loader source
sets; blocked or unrun tests, builds and artifact checks remain explicit gaps in
that change's report. Earlier runs do not validate subsequent relevant edits.

Public build and identity commands are in the
[repository README](../../../README.md#install-build-and-verify). A representative
combined matrix is shown below; individual entries name any extra loader
integration tasks.

```powershell
.\gradlew.bat --no-daemon --continue --console=plain :common:check :common:javadoc :fabric:build :forge:build :neoforge:build verifyModIdentity
```

Captured evidence includes passing headless suites, main/test compilation,
Javadoc generation, loader builds and `verifyModIdentity`. These establish
automated test/build and configured artifact-identity evidence for identified
inputs, not exhaustive package-content or shipped-namespace validation.
Separately exercised named-host Mixin probes use their own compiled inputs.
Package-content/namespace audits and live validation require distinct evidence.

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
target-normalization and disposable-repository process/Git fixtures. Cases cover
literal backticks in titles and destinations versus real code spans across
inline, reference and image forms; thematic breaks before Setext headings; empty
reference destinations; resolved labels followed by literal parentheses;
multiline titles at fence boundaries; hidden, forward and duplicate definitions
and their precedence; exact semicolon-terminated named/numeric character
references across destinations, heading anchors and both CLIs; and Git
empty-pattern directory/root records with nonempty ignores. Subprocess cases
check complete UTF-8 reports under cp936 and ASCII child stdout encodings; imported
`main` cases check that caller-owned streams remain unchanged. Four deterministic
scanning-work tests count block classifications, copied slices and character
checks for repeated definitions, inline payloads/code and heading preparation,
rather than relying on timing thresholds. These finite workloads do not prove
universal adversarial-linear complexity. The coverage is local scanner and CLI
evidence, not complete CommonMark/GFM equivalence or validation of external URIs;
embedded CLI help remains the syntax and output behavior reference.

Recorded execution date: 2026-11-01, code input
`8ef60ac7eb3bd1640a8d2b559855eafb8a710a28`.
`python -B scripts/docs/tests/test_documentation_links.py -v` passed all 84 tests:
80 semantic cases and the four scanning-work cases. AST parsing and compilation
passed for all four Python files. No separately configured Python lint,
type-check or formatter exists. These results do not establish cross-Git-version
compatibility or exhaustive CommonMark/GFM equivalence.

The latest recorded corpus snapshot, 2026-11-08, ran the normal CLIs from
PowerShell on 71 Markdown sources:

| Command | Exit | Result |
| --- | --- | --- |
| `python -B scripts/docs/broken_link_check.py` | 0 | 1,604 local links; zero broken links; zero external URIs. |
| `python -B scripts/docs/count_duplicate_links.py` | 0 | 1,604 document links; 263 duplicate groups; 416 extra occurrences. |
| `python -B scripts/docs/count_duplicate_links.py --ignore-chapter-anchors` | 0 | 1,604 document links; 304 duplicate groups; 862 extra occurrences. |

The link checker resolved the changed heading anchors; the 71 sources contain 36
fenced blocks with zero unclosed fences, and `git diff --check` exited 0. These
counts describe that snapshot, not fixed acceptance counts or a claim about
later edits. The pass reran the corpus CLIs, not the Python suite or Gradle.

## Automated verification results

The entries below summarize recorded executions. Each applies only to its
identified input; none is a claim that the current working tree or a later
commit passed. Unless an entry states otherwise, runs used JDK 21 (Temurin
21.0.12.1+1-LTS where recorded), PowerShell on Windows, UTF-8 Gradle/console
settings, `--no-daemon`, separate stdout/stderr files and no explicit timeout.
Raw logs and full execution history are outside this page under the
[verification evidence rules](README.md#recording-future-evidence); the entries
below keep only the durable summary.

Where an entry reports 16 skips, they are the recurring normal-matrix gaps: two
`InventoryFluidLoaderIntegrationTest` cases needing loader-native APIs and 14
Forge patched-first fluid cases. They are skips, not passes. Dedicated loader
tasks or the supplemental native-classpath run cover them separately where
recorded; those executions do not convert the ordinary skips.

### Early combined-tree and loader matrices

#### 2026-10-27 combined loader/build baseline

The loader execution built and tested all three loaders. The combined
`:common:check :fabric:build :forge:build :neoforge:build verifyModIdentity
--continue` invocation then exited 0. Common test compilation and tests executed
freshly: 446 suites and 4,823 tests, zero failures, errors or skips. Fabric
(24), Forge (26) and NeoForge (190) test tasks were `UP-TO-DATE` against reports
from the loader execution. All loader `compileJava`, `test`, `build` and Javadoc
tasks were also `UP-TO-DATE`; the loader jars were reused, not rebuilt.
`verifyModIdentity` executed and passed for `pingforit` / `Ping For It` /
`0.5.0-pfi-beta2`. This is build/test evidence for that input, not fresh loader
compilation or runtime behavior.

#### Patch-only full-tree matrix

The coverage update dated 2026-10-27 records a full matrix on the patch-only
identity and display integration tree. The recorded command was:

```powershell
.\gradlew.bat --no-daemon --console=plain --continue :common:test :fabric:test :forge:test :neoforge:test :fabric:build :forge:build :neoforge:build verifyModIdentity
```

All four main/test compilations and tests executed; 466 JUnit XML reports were
fresh.
The result was 4,771 tests, all passing with no skips: Common 4,554, Fabric 22,
Forge 25 and NeoForge 170. Loader builds, Javadoc and identity passed, producing
three shippable loader jars. A non-document input manifest stayed unchanged
across the run and report checks. Whitespace, conflict-marker, new-file and
documentation link/anchor/fence checks also passed.

The original coverage note recorded no commit or external log locator, so this
record's traceability is limited to the date, command and counts. Headless only;
live behavior was not covered.

#### Logical container implementation run

2026-10-29, commit `735a1e8b8e4e079083d1f1d20183c120cabcb733`, covering
logical-key transport, vanilla chest, NeoForge Vault and grouped content-receipt
identity. The combined matrix exited 0. All four test tasks ran and the XML had
zero failures, errors or skips: 5,116 tests in 495 suites (Common 4,858; Fabric
24; Forge 26; NeoForge 208). All three loader main compilations executed; Common
main/test compilation was `UP-TO-DATE` from the prior focused run. Loader builds
and `verifyModIdentity` passed. The receipt-focused reports contain 538 tests in
35 suites with zero failures, errors or skips. Seven expected failures in the
pre-fix negative control show that the new cases detect the receipt identity
mismatch; they are not final-tree failures. Headless only; live game, GPU,
multiplayer and performance were not exercised.

#### Tracking pool publication-stage run

2026-10-30, commit `0c0b61c356ca97032c5cf690b250e706645ea086`, covering the
approved partition settings/configuration-v5 surface, certified source and
unload checks, runtime pools, successor continuity and final publication
authority, including Preview-origin proof-scoped repair. `full-final` exited 0 with zero failures, errors or skips: 5,279 tests
in 507 suites (Common 5,008; Fabric 24; Forge 26; NeoForge 221). All four test
tasks executed; Fabric/Forge main compilation, all loader Javadocs, packaging,
Fabric remapping and all three builds executed. Common/NeoForge main and test
compilation, Javadoc-jar tasks and Fabric access-widener validation were
`UP-TO-DATE`. The preceding `focused-final` ran 508 tests (450 Common + 58
NeoForge); Common main and NeoForge test compilation were `UP-TO-DATE` there.
`verifyModIdentity` passed. Captured input and artifact hashes matched through
the commit. Nonfatal mapping, deprecated-API, Javadoc and Gradle warnings remain;
this is headless evidence only.

#### Certified active-join and validation run

Source input `a7fa33158d9b66a8bbb78e873e9e955f67970a8c`, covering the certified
1,620-slot baseline, active-prefix joins, fixed reflection metadata and the
completed-open-Preview lifetime fix. The final P2 `09` invocation exited 0. All
four test tasks executed; 5,342 tests in 515 suites (Common 5,036; Fabric 24;
Forge 26; NeoForge 256), zero failures, errors or skips. Fabric/Forge main/test
compilation executed; Common and NeoForge compilation reused prior output. All
loader jar/source/Javadoc packaging, Javadoc, builds, Fabric remapping and
identity executed. The preceding `08` focus ran 115 NeoForge tests; its 22 Common
results were `UP-TO-DATE` from `07`, not another run. Failed intermediate
fixture/oracle/bootstrap attempts and stale copied reports were excluded rather
than counted as fresh passes. Package checks found the operation/interface
classes in all three main jars, 13 normalized-EOL Forge/NeoForge source
equalities and six Fabric source-presence checks only; remapped Fabric sources
are not claimed text-identical. These are bounded content checks, not runtime
launch or exhaustive namespace validation. Nonfatal warnings remain; no live
session was performed.

#### Terminal Preview admission and settings-resize run

2026-11-01. Inputs match commits `016740231ce73df3313e79b7ad9e25ef71429c7e`
(terminal Preview admission) and `f76ce30be6a4c2b3b75eebc655f5e28094d6a2eb`
(settings resize). The focused `:common:test` selector run exited 0: 532 tests in
34 suites (screen 307/25, inventory 225/9), zero failures, errors or skips. The
full matrix exited 0 with all four test tasks fresh: 5,350 tests in 516 suites
(Common 5,044; Fabric 24; Forge 26; NeoForge 256), zero failures, errors or
skips. Both runs passed all 15 `InventoryPooledBackendTest` and four
`SettingsScreenResizeTest` cases. All three loader main compilations, packaging,
Javadocs, builds, Forge/NeoForge test compilation, Fabric remapping and identity
executed; Common compilation reused the focused output; Fabric test compilation,
Javadoc-jar tasks and access-widener validation were `UP-TO-DATE`. The two
invocations executed 3/13 and 25/52 actionable tasks. Nonfatal warnings remain;
no live rendering, loader launch, multiplayer, provider or performance
validation was performed.

A separate isolated Java 21 execution passed all four resize cases; its
historical record does not further identify the isolated input. The pre-fix
bytecode control failed two cases, and a broader related headless selection
passed 43 tests. These isolated results are separate from, and not additions to,
the combined-tree counts above.

#### Recipientless Tracking, diagnostics and caption-reflow verification

2026-11-01. HEAD `1964901d81d6352e0d244135f2f36032b0251666` plus two settings
sources later committed unchanged in
`7e42d3897431ff3121ad6b8858a9223b7e06c900`; the five inventory sources match the
former commit, and the two settings inputs match the latter. Documentation edits
were not Java/build inputs. The focused `nx.pingwheel.common.screen.*` run exited
0: 314 tests in 26 suites, including all seven `SettingsScreenInputLayoutTest`,
zero failures, errors or skips. The full matrix exited 0 with all four test tasks
fresh: 5,367 tests in 517 suites (Common 5,061; Fabric 24; Forge 26; NeoForge
256), zero failures, errors or skips. Common compilation reused the focused
output; loader main compilations, tests, Javadocs, packaging and remapping executed; loader test
compilation, Javadoc-jar tasks and access-widener validation were `UP-TO-DATE`;
builds and identity passed. 23/52 actionable tasks executed. Nonfatal
`LevelRendererMixin` mapping, Javadoc missing-parameter, deprecated-API, Gradle
deprecation and a MixinGradle integration skip remain. No live GUI, font,
rendering, multiplayer, provider or performance validation was performed.

The later Common-test-only fixture-path repair is a separate record below and
does not extend these counts.

#### Common resident fixture-path run

2026-11-01, source `7e42d3897431ff3121ad6b8858a9223b7e06c900` plus a Common
test-only patch committed unchanged in
`6e1facb74849f0c491ccfc35ce5c0aa1cca90869`. The focused
`PlatformInventoryResidentTest` run passed 8 tests in one suite; `:common:check`
then passed 5,061 tests in 466 suites. Both exited 0 with zero failures, errors
or skips; the focused run executed 2/13 tasks and the check 1/13. The shortened
fixture source is 120 absolute-path characters, its compiled class 131 and its
loader class 160; old oversized paths are absent. No production, dependency or
loader source changed, so no loader build was run. Both stderr files were empty;
stdout contained the Gradle deprecation notice. No live validation was performed.

### Fluid inventory and presentation matrices

#### Fluid inventory final combined-tree verification

2026-11-02, commit `628c033b0eb466a803ea72389bc241d4b047ac04`; tested
non-documentation inputs match that commit. One formal invocation with an
external init that forced fresh Test execution and wired the existing
native-source seam onto each loader test classpath (no repository build or
dependency change) exited 0. All suites were fresh: 5,707 executions in 550
suites, 5,705 passed, 2 skipped, zero failures or errors. Common 5,245
(5,243 passed, 2 skipped), Fabric 80, Forge 60, NeoForge 316; the three
`fluidSourceIntegration` tasks contributed 2 each. The two Common skips are
`InventoryFluidLoaderIntegrationTest` methods needing loader-native APIs and
fixtures absent from the Common-only classpath; both pass in each loader-specific
task, so these are the same two scenarios on three classpaths, not six. All four main/test compilations and standard tests executed,
as did Common check/Javadoc, loader checks/Javadocs/packaging/builds, remapping,
the three native tasks and identity. 35/56 tasks executed; 21 were `UP-TO-DATE`.
Warnings include 400 missing Javadoc tags, deprecated APIs, mapping, processor,
skipped Eclipse integration and Gradle 9 deprecation. Package checks covered
metadata, namespace, Mixin and Fabric refmap boundaries, not an exhaustive
shipped-package audit. No live loader, GPU, multiplayer, provider or performance
validation was performed.

#### Fluid tank grouping and presentation verification

2026-11-03, commit `e281aaa4`; all 1,281 non-documentation inputs match. The
formal invocation with an external init forcing fresh tests and supplying the
existing native fluid-source seam exited 0. All test tasks were fresh: 5,736
executions in 553 suites, 5,734 passed, 2 skipped, zero failures or errors.
Common 5,258 (5,256 passed, 2 skipped), Fabric 80, Forge 60, NeoForge 332; the
`fluidSourceIntegration` tasks contributed 2 each. The two Common skips are the
loader-native fluid cases, which pass in each loader's dedicated task. All four
main compilations and Common/Forge/NeoForge test compilation executed; Fabric
test compilation was `UP-TO-DATE`. Common check/Javadoc, loader checks/Javadocs/
packaging/builds, remapping and identity executed; 34/56 tasks executed.
Artifact checks confirmed the common tank key on all loaders, NeoForge-only tank
topology/resolver classes, expected metadata and absence of the checked test
fixtures. Warnings include 400 missing Javadoc tags, deprecated APIs, mapping,
processor options, skipped Eclipse integration and Gradle deprecation. Installed
Create, live loader/Mixin, BER/GPU, multiplayer and performance remain pending.

##### Shell-filter tank verification

2026-11-10 with PowerShell 7.6.6 and Temurin 21.0.12.1 at UTF-8, `--no-daemon`.
The focused run over `CreateFluidTankTopologyTest`,
`CreateFluidTankProductionBoundaryTest` and `CreateVaultProductionBoundaryTest`
passed 3 suites / 30 tests with zero failures, errors or skips. A separate full
`:neoforge:cleanTest :neoforge:check :neoforge:build` run passed 69 suites / 458
tests with zero failures, errors or skips and completed the build task. After
test-only cleanup, the final full rerun again passed 69 suites / 458 tests with zero
failures, errors or skips; `CreateFluidTankProductionBoundaryTest` had 14 tests
and no skips. The three new cases distinguish topology retention, filtered
source/native-edge publication, and missing/replaced-interior recovery. The
tested input was a worktree with no verifiable clean commit; this is not a
current-tree rerun. The focused and full selections overlap and must not be added
as distinct tests. Production compilation and jars were `UP-TO-DATE`; successful
build tasks made those existing artifacts available, not freshly generated jars.
The earlier 2026-11-03 tank matrix predates these shell-filter cases and does not
cover them. The harness remains headless: it does not observe actual BER,
baked-model or GPU submission, real-neighbor occlusion or visible shell
appearance, and it does not observe `EMPTY` or `FAILED` source outcomes.

#### Native fluid boundary and presentation verification

2026-11-03, commit `b30c0f1e107a232c73b38e17c50766816d2e163f`; all 1,310
non-documentation inputs stayed unchanged. The full invocation included an
external class-safety task. It exited 1 solely because that external Test task
lacked an explicit dependency on Forge `processResources`, so Gradle rejected it
before test execution. All other required tests, compilation, Javadocs, builds
and identity succeeded. After correcting only the external init dependency, the
focused command exited 0 and freshly completed the remaining task; together they
cover the input. All nine Test tasks ran fresh: 5,812 executions in 563 suites,
5,809 passed, 3 skipped, zero failures or errors. Common 5,318 (5,316 passed, 2
skipped), Fabric 83, Forge 62 (61/1), NeoForge 335, plus two each for the three
fluid-source and Forge presentation tasks and six class-safety. The skips are two
Common loader-native methods and Forge's ordinary `FluidPresentationServiceTest`
native FluidType case, which lacks patched `getFluidType`; the same methods pass
on the dedicated tasks, and repeated methods count as executions, not independent
scenarios. All four main compilations, Common test compilation and four Javadocs
executed; loader test compilation was `UP-TO-DATE`; packaging, remapping and
identity passed. Scoped jar checks found loader-specific production registrations
and no checked test fixtures or foreign packages, not an exhaustive audit.
Warnings include 400 missing Javadoc tags, deprecated APIs, mapping, processor,
skipped Eclipse integration, Gradle and Log4j diagnostics; some locale output is
garbled. The headless tea atlas logs `Sheets loaded too early` despite passing
tests. No live game, GPU/font, resource reload, loader/server launch, installed
Create/Sable, multiplayer or performance validation was performed.

#### Lane policy, native amount and icon opacity verification

2026-11-03, commit `27bbb83d89b762961f2f3bece994cfdc8f914ca2`; all 1,316
non-documentation inputs match. One full invocation with 10 Test tasks exited 0.
All were fresh: 5,880 executions in 568 suites, 5,872 passed, 8 skipped, zero
failures or errors. Common 5,370 (5,368 passed, 2 skipped), Fabric 83, Forge 67
(61/6), NeoForge 341; the fluid-source tasks contributed 2 each, presentation 2,
native amount 5 and class safety 6. The two Common skips need loader-native
fixtures and pass in all three fluid-source tasks. Forge's ordinary classpath
skips one `FluidPresentationServiceTest` method and all five
`NativeFluidAmountTest` methods; the same methods pass in the patched-first and
native-amount tasks. Repeated methods count as executions, not independent
scenarios. All four main compilations and Common/Forge/NeoForge test compilation
executed; Fabric test compilation was `UP-TO-DATE`. Javadocs, packaging,
remapping, builds and identity completed; 37/59 tasks executed. Scoped artifact
checks passed for production service providers and fixture/package boundaries;
the new test network SPI stayed in Common test output and all nine
main/source/Javadoc artifacts passed its leak check. These supplement identity
verification, not exhaustive certification. Warnings include 400 Javadoc tags,
deprecated APIs, mapping, processor/Mixin options, skipped Eclipse integration and
Gradle 9 deprecation; some localized output is garbled. The NeoForge headless
Create fixture logs `Sheets loaded too early` despite passing tests. The separate
GPU probe is recorded under the probe section below; it is not part of these
counts.

#### Final combined-tree acceptance and artifact verification

2026-11-04. InputID `8f9bfed20aff917b8e910b1aff2e84ffa0e1134afda8a630b4e115355df1dbc2`,
HEAD `0b6b279d4cd51ae33f0fd9c3199e57f8bfcfd88d` plus 55 validated dirty
non-document paths; HEAD alone lacks the inspected inventory creation-snapshot
changes. The affected scope was 76 paths (69 in the main scope plus one Vault
path, and six checkpoints), with 1,350 non-document inputs stable. One invocation
with 14 Test tasks (the ordinary four plus 10 loader integration tasks: three
`fluidSourceIntegration` and presentation, native-amount, class-safety, registry,
native-registry-freezer, cold-water and registry-module tasks) and two external
init scripts exited 0: 589 fresh suites, 6,094 executions, 6,078 passed, 16
skipped, zero failed or errored. All 14 Test tasks executed fresh. Each of the
16 ordinary skips has an exact same-class, same-method dedicated PASS in this
run, and the 6,064 unique display identities are not independent semantic
scenarios. Detector executions passed: `InventoryCreationSnapshotIntegrationTest`
17 (positive subset 8), preparation retry 5, Create current-controller five
classes 55, repair/old-failure controls 40, NeoForge registration 7,
`CertifiedVault1620BackendTest` 9 and Vault five-class adjacency 28. The
exact-method oracle covered 86 methods and 14 parameter invocations. 20/63 tasks
executed; `:neoforge:compileTestJava` executed, while other test compilations,
production compilations and Javadoc were `UP-TO-DATE`, so this is not
fresh-compilation or doclint evidence. The NeoForge compile classpath had 69
entries without the runtime-only event; the 119-entry runtime classpath had
exactly one real loader `4.0.21` definition, so the reflective event path ran
without an added dependency or stub. Scoped service-descriptor, protocol-version
and adjacency checks passed; six shipped `javap` checks reported presentation
version 2 and inventory version 2, and 30 production service descriptors matched
loader source with one provider and no test-fixture, test-SPI or foreign-loader
leakage. `git diff --check` and AST parsing passed. Nonfatal compiler, Javadoc and
Gradle warnings remain. Headless only; no game/FML launch,
socket or multiplayer, installed Create, renderer/GPU/resource reload, manual
gameplay or performance validation was performed.

### Logging-profile runs

#### Logging-profile common execution

2026-11-04, common logging-profile change; loader source sets and live runtime
were outside the run. `:common:test :common:check :common:javadoc` exited 0.
`:common:test` produced 515 suites and 5,644 tests: 0 failures, 0 errors, 2
skips (`InventoryFluidLoaderIntegrationTest` cases needing loader-native
fixtures). `:common:check` passed; `:common:javadoc` was `UP-TO-DATE` against its
prior successful generation, which carries missing `@param`/`@return` warnings.
A focused `Log4jDiagnosticOutputBoundaryTest` run then passed 3 tests with no
skips and unchanged pre/post manifests; it covers synchronous isolated output
boundary behavior, not asynchronous or deployed logging.

#### Logging-profile loader execution

2026-11-05, code input `b8178699`. An external uncommitted loader-native init
supplied four Forge patched-first integration tasks; it is not repository
configuration. The three loader builds plus those four tasks exited 0: 54
actionable tasks, 10 executed, 44 up-to-date. Fabric passed 91 tests with no
skips; Forge 66 with 14 existing vanilla-first skips; NeoForge 396 with no skips.
The four patched-first tasks passed 15 tests (2 + 5 + 7 + 1), covering the 14
skip points. Three builds and nine main/sources/Javadoc jars; artifact inspection
found no test-fixture leak and versions unchanged. This checkpoint predates the
final reentry fix and did not run `verifyModIdentity`.

#### Logging-profile final combined acceptance

2026-11-05, HEAD `bce5bc07c40b49a237325d1ccb85b89e1b28d328`, tree
`13958c1bb1518bc822fcf2e26315147255e2aece`; 1,376 source/build inputs were stable
across focused, combined and post-check snapshots. The focused reentry selector
run exited 0: 2 suites, 14 tests, no skips; NeoForge main/test compilation
executed. The combined `:common:check :common:javadoc verifyModIdentity` exited
0. NeoForge test ran 59 suites and 402 tests, all passing; NeoForge main/sources/
Javadoc jars were rebuilt; identity passed. Common 5,647 tests (5,645 passed, 2
skipped), Fabric 91 and Forge 80 (66 passed, 14 skipped) plus the
Common/Fabric/Forge Javadoc outputs were `UP-TO-DATE` reuse from the preceding
same-input runs; the combined invocation did not re-execute the common test task.
The 14 Forge skips are covered by the prior four patched-first tasks' 15 passing
tests, which this invocation did not rerun. The three jars carried no checked
test/fixture or optional-dependency leakage; the NeoForge lifecycle class matched
the shipped entry. Existing Javadoc and Gradle warnings remain; no client or
server launch, manual gameplay, rendering/GPU, installed Create, multiplayer or
live appender/address behavior was verified.

#### Alt logging profile controls

2026-11-05, HEAD `4b82c691edd139f67404f228dd8aa02482922f34` plus a 14-path Alt
patch, later committed as `71de6ebe61bce9fa330d5ff7c688a02b89f9cb08`; the patch
covered three common screen paths, eight locale resources and three screen-test
paths. The focused selector run exited 0; the final run passed 30 tests (8 + 7 +
11 + 4) with no failures, errors or skips. The combined `:common:check
:common:javadoc`, three loader builds and identity exited 0: 53 actionable tasks,
31 executed, 22 up-to-date. All four test tasks executed: Common 5,733 (5,731
passed, 2 skipped), Fabric 93, Forge 82 (68/14), NeoForge 414; total 6,322 tests,
6,306 passed, 16 skipped, zero failures or errors. Common main and test
compilation were `UP-TO-DATE`; loader `compileJava` executed and shared the common
compilation; Forge test compilation executed, Fabric/NeoForge test compilation
were `UP-TO-DATE`; all Javadocs, builds and identity executed. Artifact
inspection was limited to identity, the new button, absence of the old session
entry and eight locale byte comparisons. Input caveat: 1,395 of 1,397 fresh inputs
chain to the commit; two later-changed Create files were not restored to
test-time bytes, so this is not a full-tree or current-workspace PASS. No client,
server, macOS Option key, multiplayer, manual appender/layout or rendered-tooltip
check was performed.

### Provider-trust and optimization runs

#### Provider trust and frozen authorization final verification

2026-11-07, acceptance for
[D0019](../../decisions/D0019-provider-trust-and-frozen-recipient-authorization.md).
Tested input: HEAD `4a67c3e78ebd1932ebc533fe2e8be282cbc69ceb` plus uncommitted
source/test changes; HEAD alone does not contain the implementation. The
tracked-diff and untracked-manifest hashes were identical before and after. Later
commit `a4ae36fa121c1899c946e1a419ae66afbbfb4344` on parent `4a67c3e` committed
the identical 83 Java paths (20 production, 63 test), binding the verified bytes;
Gradle was not rerun for the commit, and this is not a clean-commit revalidation.
One `:common:check :common:javadoc verifyModIdentity` invocation with no filter
exited 0. All four test tasks executed; 621 XML suites, 6,458 tests, 6,442
passed, 16 skipped, zero failures or errors. The 16 skips are assumption skips:
two Common `InventoryFluidLoaderIntegrationTest` and 14 Forge, split
`FluidPresentationServiceTest` 1, `NativeFluidAmountTest` 5,
`NativeFluidRegistryFreezerTest` 1 and `PlatformFluidRegistryServiceTest` 7.
They are not passes. The supplemental run below passes all 16 across seven
dedicated tasks but does not rewrite these counts. All loader compile, test,
check, Javadoc, packaging and build tasks, remapping and identity executed;
Common and the three loader test compilations were `UP-TO-DATE`; 27/53 tasks
executed. The three shippable loader jars were inspected; identity is
`pingforit` / `Ping For It` / `0.5.0-pfi-beta2` on Minecraft 1.21.1.
These are build and artifact-identity checks, not an exhaustive package-content
audit. Nonfatal Javadoc, mapping, processor and Gradle warnings remain. No live
client/server, loader launch, multiplayer, installed Create/Sable, GPU/font/UI,
manual gameplay or performance validation was performed.

Earlier main/test compilation failures blocked dependent tasks, and stale Common
XML after a failed test compilation was excluded. Create, policy and frozen-grant
delivery failures were repaired before final acceptance; two focused delivery
attempts stopped at test compilation without running the test. Its passing
delivery result comes from the final full suite, not those attempts. Intermediate
direct audits are separate evidence; overwritten classpath-repair logs and
ad-hoc runs without recorded commands are not reconstructed or folded into these
counts.

A separate 2026-11-06 integration run for `InventoryFluidReadOperationTest` and
`InventoryFluidCoordinatedSourceTest` passed its cases. Review detectors for
guarded-tail authority, getter reentry and codec reentry failed on the unfixed
code and passed on the final code; the final suite also covers
metadata/provider-current callback reentry, and getter count stays at most one.
The record identifies only that date and the unfixed/final code pair, not a
commit or current-tree input, and its passing result is not a whole-tree rerun.

The frozen-authorization Create path still discovers the full initial target
structure; its automated operation-count assertions are current-check counts, not
a live FPS or allocation improvement. The optional dedicated-provider SPI remains
future work and is not claimed. Live delivery/repair, a member not
`HELLO`-ready at create, true disconnect, source-read revocation and reconnect
remain pending in the
[manual matrix](manual-integration.md#frozen-recipient-authorization-and-provider-trust).

#### ITEM redundant-work final verification

2026-11-08, ITEM optimization set on `agent/perf-fix`. Tested input was a dirty
tree at HEAD `0301adb625b3148839929340b5c30b9dae05302b`, with the P1 lane-policy
hint and P4 publication reuse uncommitted (P3 chest mapping was already in HEAD);
later commit `0d9f841116e3789ac224cc1393c6990b0f01cb33` on that parent committed
the identical 14 Java paths (7 production, 7 test) with no rerun. 1,425
non-documentation inputs and 1,513 tracked/untracked files were stable. The final
`:common:check :common:javadoc verifyModIdentity` invocation exited 0: 631
suites, 6,498 tests, 6,482 passed, 16 normal-matrix skips, zero failures or
errors. Common 5,876 (5,874 passed, 2 skipped) and NeoForge 443 executed fresh;
Fabric 94 and Forge 85 (71/14) were `UP-TO-DATE` reuse of byte-identical
`run4-full` XML on the same inputs, not fresh. All loader builds, Javadoc,
packaging, remapping and identity passed; three shippable jars were rebuilt.
The 16 skips are the two Common loader-native plus 14 Forge patched-first cases,
not ITEM wrapper cases; the old fluid supplement was not rerun because the ITEM
change does not affect those classpaths.

Earlier policy-lifecycle assertion failures and compile failures were repaired
before final acceptance; failed full attempts did not enter later gates. Unfrozen,
unauthorized compile attempts, diagnostic invocations lacking XML or complete input records,
and a version-only probe lacking the required execution flags/redirection are
not acceptance evidence. The 6,498-, 6,527- and 6,543-test final results in this
group do not represent first-pass acceptance.

P1: `InventoryPolicyFrozenDemandRegressionTest` 3/3 and
`InventoryProductionPolicyTest` 99/99, covering accepted Preview and existing
Ping grant retention with fresh later `OPEN`/`SELECT` resolution; these are
authority and ownership assertions, not allocation measurements. P3:
`CertifiedChestFrozenMappingTest` 7/7 plus resident-scope and Forge/NeoForge
wrapper regressions; the immutable 27/54-pair chest mapping is proved once at
construction, while live identity, pairing, size, owner, lock and grant checks
remain, with no unpaid extra authority pass. P2 was withdrawn because the
evaluated `valid()` callbacks did not establish callback-free closure; the
baseline current-authority sequence remains. P4: `CutWorkReuseTest` 3/3,
`InventoryChecksumsTest` 7/7, `InventorySyncPublisherTest` 52/52 and
`InventoryFanoutEncodingReuseTest` 3/3; checksum bytes and the v2 wire format are
unchanged, and no end-to-end allocation gain is claimed.

Matched baseline-to-after: the dedicated after commands passed 167/167 Common and
16/16 NeoForge. The three NeoForge performance sources are unchanged;
`InventoryProductionPolicyTest` grew from 88 to 99 cases and
`InventorySyncPublisherTest` from 49 to 52, so the selected Common total is
regression verification, not an identical-case benchmark. Both certified
1,620-slot profiles match fixture/world hashes, settings, JDK/VM, three warmups
and five samples; the 900-slot metadata string also matched. Allocation uses
`ThreadMXBean` current-thread allocated bytes and timing uses `System.nanoTime`
elapsed time, not retained heap, allocation rate, CPU, TPS or a portable threshold.
All 72 counter/allocation keys matched: every actual operation counter is unchanged;
the only numeric change is modeled retained bytes `+2` in all 24 certified rows;
reserved bytes are zero and the 900-slot retained/reserved counters are
unchanged. Certified full-sweep allocation medians increased 9,792 bytes (about
0.010–0.014%); 900-slot EMPTY and REPEATED were unchanged and MIXED increased 384
bytes. Time medians moved in both directions; no ratio determines pass/fail and
no speedup is claimed. These macro fixtures do not traverse the P1 hint, P3
chest route or most P4 fan-out paths, so their counters do not prove those
optimizations and the small allocation increases are not asserted to be noise.
No overall FPS, TPS, CPU, GC, allocation-rate or live-performance improvement is
claimed, and no live client/server, loader launch, multiplayer, installed
provider, GPU/font/UI, manual gameplay or performance measurement was performed.

#### Inventory initial-admission and terminal-reset final verification

2026-11-08, three Inventory regression fixes on `agent/perf-fix`. Tested input
was dirty HEAD `c124208ad265878e03da86f4ba68e705c9b9da01` plus seven Java paths
and `inventory.md`, not a clean commit; 1,427 source/build inputs and 1,515
tracked/untracked files were stable. Later commit
`a610498608dd9ce18b898bf3a6dfb75cef96667e` on that parent committed the same
seven Java paths, with no rerun. `central-full-checks-1` failed before any task
because its evidence init evaluated `:common` inside `:buildSrc`; a root-project
guard fixed only the harness, with no source change. `central-full-checks-2`
(`:common:check :common:javadoc verifyModIdentity` with force-fresh and evidence
inits) exited 0: 53 actionable tasks, 29 executed, 24 `UP-TO-DATE`; all four Test
tasks were fresh with no filters. 633 suites, 6,527 tests, 6,511 passed, 16
normal-matrix skips, zero failures or errors. Common 5,900 (5,898/2), Fabric 99,
Forge 85 (71/14), NeoForge 443. `InventoryTerminalPreviewResetTest` 5/5,
`FluidIdentityAdmissionScalingTest` 5/5 and `InventoryProductionPolicyTest` 118/118
passed without skips. The native supplement ran in two invocations: seven tasks,
seven fresh XML suites, 21 scenario executions, zero failures/errors/skips
(Fabric source 2, NeoForge source 2, Forge source 2, presentation 2, amount 5,
registry 7, freezer 1). Workspace test classes were on each classpath and hashes
matched; Forge's patched jar moved from index 6 to 0 with the same SHA and other
entries preserved. The 16 ordinary skips remain skips, not passes; each maps to
a passing supplemental task. All three loader builds, `:common:javadoc` and
identity passed; nine main/source/Javadoc jars were produced. No live game,
loader launch, multiplayer, rendering, heap, TPS or manual gameplay evidence was
produced.

A superseded focused attempt used a Preview period above the legal maximum and
failed four terminal-reset fixtures; that invalid fixture input is not acceptance.
`terminal-focused-02` retained one expected `INVALID`-versus-`INCOMPLETE` negative
control failure, followed by a restored positive pass. `suppressed-followup-03`
failed the two canonical-marker ITEM/FLUID removal cases before the guard while
both sidecar-only controls passed; after the guard, the five positive classes
passed 140/140. These controls are separate from the final matrix counts.

#### Inventory reset regression final verification

2026-11-08, Preview cancellation/`OPENED` reset and client creation-admission
regressions on `agent/perf-fix`. Tested input was dirty HEAD
`658235a5f3a8323a18a2f006a54df1d1781dd6fa` plus six Java paths, not a clean
commit; 1,429 source/test/resource/build inputs and 1,515 repository files were
stable. Later commit `02ec9aafd1178cae664d1ca2414b96b9adb4726f` on that parent
committed the same six Java paths, with no rerun. The final `:common:cleanTest
:common:check :common:javadoc verifyModIdentity` invocation exited 0: 635 suites,
6,543 tests, 6,527 passed, 16 normal-matrix skips, zero failures or errors. All
four Test tasks were fresh with empty filter sets. `InventoryPreviewResetLifecycleTest`
3/3, `ClientInventoryCreationCapacityTest` 13/13 and
`InventoryTerminalPreviewResetTest` 5/5; the 39 planned selectors expanded to 51
passing executions. Four negative controls failed as intended: strict-view
`CLOSE`, an earlier frame-count control not certified as `OPENED`-ordering
evidence, terminal `INVALID` before replacement `OPENED`, and a false parameter
that missed the expected admission. An initial focused failure was a
below-minimum slot fixture that normalized to the legal minimum; the corrected
fixture used real `HELLO` traffic and a due health validation. Temporary control
hunks were restored and input hashes rechecked; expected control failures are
not final-test failures. All Javadocs, three loader builds, remapping and
identity passed; nine jars were produced. Main and test compilation were
`UP-TO-DATE` in the full run, so this is reused compilation, not a
fresh-recompile claim. The 16 skips are unchanged native-classpath gaps; no
supplement ran, so historical native passes are not claimed. No live game,
loader launch, dedicated server, manual interaction, rendering, multiplayer or
performance evidence was produced.

### Performance-monitor and HUD runs

#### Performance monitor implementation and final acceptance

2026-11-08 on `agent/perf-fix`. Phase 1 used HEAD
`776a243ee88ef16b60aa43ecd5cfc00c76f91bfa` plus the monitor-foundation
worktree; later commit `f24b5b3037c91e5876511641a9c4e849124cc939` records the
passed code. `check build verifyModIdentity` exited 0: 56 actionable tasks, 35
executed, 21 up-to-date; 645 suites, 6,630 tests (Common 5,976; Fabric 108;
Forge 93; NeoForge 453), zero failures or errors, 16 normal-matrix skips; all
loader builds and identity passed.

The first phase-two attempt used HEAD `f24b5b3` plus the phase-two worktree and
stable 1,572-file manifests; it exited 1: 51 tasks, 34 executed, 17 up-to-date.
Six tasks failed (`:common:javadoc`, `:common:test`, `:fabric:javadoc`,
`:forge:javadoc`, `:forge:test`, `:neoforge:javadoc`), and dependent checks,
builds and `verifyModIdentity` did not execute, so this attempt provides no
loader-build or artifact-identity acceptance. It reported 668 suites / 6,780
tests: 6,750 passed, 14 failed, 16 skipped. Causes: all four Javadoc tasks hit a
literal `&&` parsed as an HTML entity; Common had nine creation-fixture failures
plus a 424-byte allocation failure in `InventoryS2CPacketTest`; Forge had four
duplicate global-channel registration failures. The 424-byte event was later
traced to cold C2 string-constant resolution and repaired with off-measurement
warmup while retaining the strict-zero assertion.

The final rerun used the same HEAD `f24b5b3` plus the phase-two worktree; its
tested code is identical to commit `251f58c53e7e50392e507af05d336da7b50678be`
on that parent, which was not retested. It exited 0: 56 tasks, 27 executed, 29
up-to-date; 668 suites, 6,784 tests, 6,768 passed, 16 skipped, zero failures or
errors. Common 6,122 (6,120/2) and Forge 96 (82/14) executed fresh; Fabric 111
and NeoForge 455 were `UP-TO-DATE` from the immediately preceding attempt, not
fresh. Four main compilations, four Javadocs, three loader builds and identity
executed. The 16 skips remain native gaps. A patched-first Forge experiment
failed 56 tests plus one bootstrap container and is not acceptance; it neither
establishes nor replaces native coverage. The Javadoc `&&`, `sendObserved`
fixture, duplicate channel and allocation warmup were repaired before the rerun;
the failure record remains historical. No live client or server, loader launch,
GPU/HUD, native provider, handshake, multiplayer, manual interaction,
heap/TPS/CPU/GC or whole-path allocation was measured.

An earlier selected red/green execution on the pre-fix P3 and attribution code
recorded 6 failures in 21 selected P3 and attribution cases. After repair, the
green rerun reported all 30 new cases passing (14 plus 16) and a 279-test
regression selection passing. These are selected executions; the record
identifies no commit or whole-tree input, and the later 30-plus-279 report does
not imply that every recorded execution repeated both selections.

#### HUD UX final whole-tree verification

2026-11-09 on `agent/perf-fix`. Tested input was HEAD
`251f58c53e7e50392e507af05d336da7b50678be` plus the dirty HUD-UX worktree, not
a clean commit; later commit `c2fc8756f350a81460a2f12f9e745e5262f4a306` on that
parent contains the same 23 code/resource/test paths and was not retested. The
first attempt exited 1: `:common:compileTestJava` failed on two Gson `Strictness`
errors in `MonitorHudLabelsLocalizationTest` because the resolved Common classpath
has Gson 2.10.1. `:common:test` did not execute, and the existing
568-suite/6,122-test XML was stale and not evidence. The loader Test tasks did
execute and passed 662 tests (Fabric 111, Forge 96 with 14 skips, NeoForge 455);
loader builds, four Javadocs and identity passed. The repair changed only the new
test to `reader.setLenient(false)`, without loosening assertions; the repaired
test passed 17/17. The accepted rerun exited 0: 56 tasks, 7 executed, 49
up-to-date; 673 suites, 6,850 tests, 6,834 passed, 16 skipped, zero failures or
errors. Common 6,188 (6,186/2) executed fresh; Fabric 111, Forge 96 (82/14) and
NeoForge 455 were `UP-TO-DATE` reuse of the first attempt's fresh loader XML, not
older pre-UX results. Loader builds/Javadocs/tests were `UP-TO-DATE` in the
rerun; Common compileTest/test/check/build and identity executed. NeoForge
remains on dependency `21.0.143` on the Minecraft 1.21 line;
the `1.21.1` archive label is not a runtime 1.21.1 claim. Existing Javadoc,
mapping, deprecated-API, processor and Gradle warnings remain. No live client or
server, loader launch, GPU/pixel, native font/locale, live layer, multiplayer or
performance session was performed.

A separate joint resource execution before the wrapping case covered 69 tests
across four classes (17 HUD locale, 7 selector locale, 27 display model, 18
formatter). Its first run failed three HUD cases: the zh_cn/zh_tw accepted-count
label collided with the phase label, and de_de carried a ratio space. An
approved resource-only repair changed the accepted labels to `已应用`/`已套用`
while the phase labels remain `应用`/`套用`; the repository rerun passed 69/69.
The record identifies only that pre-wrapping resource input, not a commit or
current tree, and is resource/format evidence, not a live locale or font-fit
review.

#### Inventory monitor queue/publication/read accounting verification

2026-11-09 on `agent/perf-fix`; counter-fix worktree. The initial whole-tree run
at HEAD `c2fc8756f350a81460a2f12f9e745e5262f4a306` exited 0: 56 tasks, 35
executed, 21 up-to-date; 674 suites, 6,904 tests, zero failures or errors, 16
skips; all builds and identity passed. That pass was not accepted because
publisher observer ownership on callback cancel/replace and runtime ledger
rotation were defective. The repair added 17 regressions: five
`InventoryMonitorPublisherTest` cases (same-callback cancel, replacement,
withdrawal, rebase ownership) and 12 `InventoryMonitorReadAttemptsTest`
invocations (old/new ledger rotation, nested scopes, unsettled native step, fatal
witness identity). Red selections reported five failures in 27 publisher cases
and four rotation failures; repaired isolated runs passed 113 owner and 236
runtime tests. The 17 additions explain 6,904→6,921. The final
rerun at HEAD `c2fc8756` plus the repair worktree exited 0: 56 tasks, 28
executed, 28 up-to-date; all four Test tasks fresh; 674 suites, 6,921 tests, 6,905
passed, 16 skipped, zero failures or errors. Common 6,259 (6,257/2), Fabric 111,
Forge 96 (82/14), NeoForge 455. Common XML contains 27 publisher, 40 read-attempt
and nine shared invocations, all passing. All loader builds and identity passed;
commit `9bcbd76a` on parent `c2fc8756` records the same six tested paths and was
not retested. The all-file manifest had 1,580 files before and after; the six
tested files were byte-identical, but `docs/testing/verification/inventory.md`
changed by 153 bytes during the run window, so this is not a whole-tree-identical
claim. The 16 skips are native gaps. No live client or server, loader launch,
socket, installed provider, multiplayer, HUD/GPU/font, heap/GC/CPU/TPS,
allocation-rate or wall-clock measurement was performed. The enabled shared
`SELECT` path permits small per-operation scope allocations; the earlier
disabled-path zero-allocation result does not generalize to that enabled path.

#### Fixed semantic HUD wrapping final verification

2026-11-09 on `agent/perf-fix`. Tested input was HEAD
`9bcbd76a8d4cb472a979dd619b8ff2d189f9293b` plus the four-file wrapping worktree;
commit `447f7415a95147d168f6c34b097065f5a802fe7d` on that parent contains the
same four paths and was not retested. The command exited 0: 56 tasks, 31
executed, 25 up-to-date; all four Test tasks fresh, 674 new XML suites; 6,929
tests, 6,913 passed, 16 skipped, zero failures or errors. Common 6,267
(6,265/2), Fabric 111, Forge 96 (82/14), NeoForge 455. The five affected HUD
classes had 81 tests: 33 display-model, 18 HUD-label localization, 7
selector-label localization, 18 number-format and 5 painter; isolated
compile/run and an independent rerun each passed 81/81, and the eight added
wrapping cases explain 6,921→6,929. All four modules ran `compileJava`,
`compileTestJava`, `javadoc`, `check` and `build`; loader builds, remapping and
identity passed; loader and Common `javadocJar` tasks were `UP-TO-DATE`, while
three loader jars and sources jars were fresh. The all-file manifest had 1,580
files before and after; the four tested paths were byte-identical, but
`docs/testing/verification/inventory.md` changed during the run window, so this
is not a whole-tree-identical claim. No live client or server, loader launch,
socket, installed provider, multiplayer, live GPU/font/window/layer paint,
heap/GC/CPU/TPS, allocation-rate or wall-clock measurement was performed. The
427x240 painter viewport is a computed headless reference.

#### Stable HUD reservoir UX whole-tree verification

2026-11-09 on `agent/perf-fix`. Tested input was HEAD
`447f7415a95147d168f6c34b097065f5a802fe7d` plus 11 uncommitted Java paths (four
production, seven test); later commit
`27778fd711c72a9132e149a7ce6e1718c7bc348e` on that parent contains the same 11
paths and was not retested. The command exited 0: 56 tasks, 34 executed, 22
up-to-date; all four Test tasks fresh, 676 XML suites; 6,953 tests, 6,937 passed,
16 skipped, zero failures or errors. Common 6,291 (6,289/2), Fabric 111, Forge 96
(82/14), NeoForge 455. All four modules ran `compileJava`, `check`, `build`,
`javadoc`, `javadocJar`, `sourcesJar` and `jar`; Common, Forge and NeoForge test
compilation executed, Fabric test compilation was `UP-TO-DATE` while its test
task ran fresh; remapping and identity passed; four main artifacts were fresh.
The 1,583-file tracked/untracked manifest and 11-file code subset were stable
through the run; the 14 pre-existing documentation paths were preserved.
Focused selections passed: A 93/93; B Common/Fabric 20/20 and Forge/NeoForge
19/19; final combined 128/128. The external Forge/NeoForge focused runner
excluded only the executable gate truth-table case requiring a bootstrapped
`LoadingModList`; Common/Fabric ran all 64 gate combinations, and static ABI
cases ran against each mapped target. These selections overlap with the 83
changed/added whole-tree cases and are not additional unique tests; the
whole-tree total is +24 tests and +2 suites over 6,929/674. The separate
independent probe is recorded under the probe section below. No live client or server, GL/pixel, native font/resource
reload, window/layer, loader/Mixin, multiplayer, heap/GC/CPU/TPS, allocation-rate
or wall-clock measurement was performed; the 16 skips are native gaps. NeoForge
remains `21.0.143` on the Minecraft 1.21 line; the archive `1.21.1` label is not
a runtime claim, and the mod version is unchanged.

#### Stable full verification (2026-11-11)

Stable snapshot `20261111-113705-205` ran
`.\gradlew.bat --no-daemon --continue --console=plain :common:check :common:javadoc :fabric:test :fabric:build :forge:test :forge:build :neoforge:test :neoforge:build verifyModIdentity`
on HEAD `fe8313c63162df61c2181fae229b8f5bc1af1864` plus the working-tree change.
The tracked/untracked tree manifest SHA-256
`2E735E9A3873CB9D8A87B6326CF5D607BA99EE67576258F763796DE25C535A8D` and tracked
diff SHA-256 `1875DBE120865B6997E32AC1F83D1DE2A111AE10ADF925666B18F3575F7C1BD8`
were identical before and after.

The invocation exited 1 after 52 tasks (35 executed, 17 up-to-date); this is not
a whole-tree pass. It recorded 7,140 tests: 7,081 passed, 43 failed, 16 skipped
and no XML errors. Common was 6,467 total (6,424 passed, 41 failed, 2 skipped),
Fabric 111 passed, Forge 96 total (82 passed, 14 skipped) and NeoForge 466 total
(464 passed, 2 failed). All four main and test compilations and Javadocs
executed successfully. Fabric and Forge builds succeeded. Common check and
NeoForge check/build were blocked by test failures, so `verifyModIdentity` did
not execute because its NeoForge dependency did not complete.

The same snapshot executed the targeted acceptance selections: the 18-case
minimum-budget serviceability set 18/18 (including native 128 KiB end-to-end
Preview→`SELECT`/`CREATED`→`STREAM` source versions 7→11 and resync),
`InventoryRuntimeScaleTest` 9/9, and `MultiVaultPipelineTest` 7/7. This failing
snapshot was superseded by the final full-tree verification below.

#### Final full-tree verification (2026-11-11)

Final snapshot `20261111-153119-432` ran one invocation on HEAD
`7d64670cc18e70b89dc417cc65ce06797647f3d9`, tree
`abc349cda5174741b85bdeb7c1d45413d4bc6a64`, and exited 0 (`BUILD SUCCESSFUL`).
The tracked/untracked manifest SHA-256
`85B9B7A87349F9DA4B7003726DE3A8681A06A20E1BA6528CD05FE39F9A12FFCA` and diff
SHA-256 `E80DFA3F998BA85D091B4BC2F3F5A5B34BCD9E0C371CEBC803D19FDAC7DD368A`
were unchanged before and after.

```text
.\gradlew.bat --no-daemon --continue --console=plain '-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -XX:TieredStopAtLevel=1' :common:check :common:javadoc :fabric:test :fabric:build :forge:test :forge:build :neoforge:test :neoforge:build verifyModIdentity
```

Common executed fresh: 588 suites, 6,512 tests, 6,510 passed, 0 failed, 2
skipped (the two loader-native fluid cases). Fabric, Forge and NeoForge test and
build tasks were `UP-TO-DATE` from `20261111-143456-509-final-full-tree-tier1`,
which shares the production source hashes: Fabric 111/111, Forge 96 total with
82 passed and 14 skipped, NeoForge 466/466, and all three builds passed.
MultiVault 7/7 is prior full-tree evidence, not common XML in the final command.
The combined unique result is 7,185 tests, 7,169 passed, 16 skipped, 0 failed
(6,512 + 111 + 96 + 466; 6,510 + 111 + 82 + 466).

Acceptance actual in the final snapshot: minimum-budget 18/18, Preview lifecycle
and retry 33/33, RuntimeScale 11/11, provider limits 7/7, wire-limit diagnostics
6/6, publication integration 25/25, monitor integration 4/4, backend lifecycle
21/21, pooled-chest runtime 8/8, catalog native read wiring 50/50, and creation
snapshot integration 22/22. `verifyModIdentity` executed and passed: all three
jars carry `pingforit` / `Ping For It` / `0.5.0-pfi-beta2`. Common Javadoc was
`UP-TO-DATE` after its earlier actual pass.

PowerShell was 7.6.6 on Temurin `21.0.12.1`; `JAVA_TOOL_OPTIONS` was absent before
and after, and the daemon-only JVM arguments retained `-Xmx3G`, UTF-8 flags and
`-XX:TieredStopAtLevel=1`. An earlier attempt (`20261111-143107-941`) hit a JDK 21
C2/javac crash before tests. A temporary `JAVA_TOOL_OPTIONS` Tier-1 workaround was
later found to pollute an `EmergencyWriter` subprocess stderr path and caused one
failure; the final run removed it and left build configuration and the JDK
unchanged. This is an engineering validation limitation of the workaround, not a
production bug or performance measurement.

No live client/server, multiplayer, GPU/render, heap/GC/TPS or performance result
follows from these Gradle invocations.

### Native-classpath, GPU and independent probes

#### Supplemental native-classpath verification

Same commit `a4ae36fa121c1899c946e1a419ae66afbbfb4344` and source raw identity
`3fd2a0d0118b34118bcd8cd7beece8d05fa5c04b722192b65e417e0e2ecd80fb` as D0019.
A separate external harness exercised the skipped scenarios on loader/native
classpaths. It did not rewrite the ordinary XML or add to 6,458; the three jars
stayed byte- and mtime-identical, and no ordinary test task was rerun. The two
invocations used `--offline`, `--no-build-cache`, `--no-daemon`, `--console=plain`
and `--continue`. The first ran seven tasks and produced seven fresh XML files:
19 passing scenario executions plus one Forge `fluidSourceIntegration`
initialization failure. The failure was `NoSuchMethodException` for
`Fluid.getFluidType()` during `InventoryFluidLoaderIntegrationTest` bootstrap
because the vanilla jar preceded the patched Forge jar; it was not a scenario
pass, so the first invocation is not all-seven passing. The second invocation
reran only Forge `fluidSourceIntegration` and passed 2/2 after moving the
already-present patched jar from classpath index 6 to 0; its hash matched, other
entries retained order, and selectors/properties were unchanged. Across the two
runs, 21 scenario executions passed in seven tasks: Fabric/NeoForge source 2
each, Forge source 2, Forge presentation 2, native amount 5, registry 7 and
freezer 1. The 14 ordinary Forge skip methods pass in four tasks (15 executions,
one extra in presentation), and the two Common skip methods pass on three loader
classpaths (6). These are repeated methods across classpaths, not 21 unique
tests, and they do not replace the 16 normal-matrix skips. This is headless
JUnit/classpath evidence only, not loader launch or game behavior.

#### Stock-GUI GPU probe

2026-11-03. An independent probe compiled the production icon sources with
`javac` and passed 14 focused JUnit tests with no skips or failures. Its hidden
GLFW probe used stock Minecraft 1.21.1 assets, real `ShaderInstance`,
`GuiGraphics.flush` and unmodified production quad submission on NVIDIA GeForce
RTX 4060 Laptop OpenGL 3.2. Framebuffer opacity/tint and texture cutoff,
queued/following GUI draws, nondefault caller-state/cache preservation and
injected-failure recovery passed with no GL errors. Only explicitly overridden
uniforms are restored; normal subsequent stock draws re-establish default
model/projection and samplers. The four render source/test inputs match the
formal input. The probe and its JUnit run were not rerun or added to the formal
Gradle counts. This does not verify full-game/native-font rendering, resource
reload, third-party shaders, installed Create, loader/FML/server launch,
multiplayer or long-run native-heap/performance.

#### Independent HUD model probe

2026-11-09. A headless read-only source probe reported `CHECKS=86 FAILURES=0`;
eight earlier red checks were corrected probe-oracle expectations, not
implementation defects. Its final case set covered a non-null old remote sample
followed by `OFF`, `UNAUTHORIZED` and `WAITING`, then `ACTIVE` with a short value,
showing compact rows suppress stale values and padding while `ACTIVE` reuses
reserved rows without reusing values. This is not a GL, window, loader-launch or
runtime test.

### Verification negative controls

The records linked below are the evidence entries for these pre-fix and
red-before-repair controls. Consult each entry for its counts, failure types and
input limits; a red or expected failure is not a current-tree pass.

- Settings-screen resize pre-fix control: the separate isolated result recorded
  in [Terminal Preview admission and settings-resize run](#terminal-preview-admission-and-settings-resize-run).
- 2026-11-06 fluid guarded-source detector red-before-repair: [provider trust
  run](#provider-trust-and-frozen-authorization-final-verification).
- P3 and attribution selected red run: [performance monitor
  run](#performance-monitor-implementation-and-final-acceptance).
- Inventory monitor publisher and ledger-rotation red-before-repair:
  [accounting run](#inventory-monitor-queuepublicationread-accounting-verification).
- HUD joint resource initial locale/format failures: [HUD UX whole-tree
  run](#hud-ux-final-whole-tree-verification).
- Terminal-reset `INVALID`/`INCOMPLETE` and suppressed-creation callback-removal
  controls: [initial-admission and terminal-reset run](#inventory-initial-admission-and-terminal-reset-final-verification).

## Verification limitations

The records above are input-specific; none establishes that the current tree or a
later commit passed. No run is warning-clean: Javadoc missing-tag, mapping/Mixin,
deprecated-API, annotation-processor, Gradle-deprecation and some localized output
diagnostics recur. The configured Java checks are the Gradle test, check, build and
Javadoc tasks plus `verifyModIdentity`; no separate lint, formatter or
static-analysis task exists. A passing task set can contain `UP-TO-DATE` reuse;
entries mark such reuse, which is not fresh compilation or test execution.

Passing headless tests, builds and artifact-identity checks do not establish live
loader launch or Mixin application, installed Create/Sable/Refined Storage
behavior, native screen rendering (except the narrow stock-GUI probe), multiplayer
or mixed-version transport, manual gameplay, or live CPU/TPS/GC/allocation/FPS.
The normal-matrix skips are gaps; supplemental native-classpath passes are
separate and do not convert them. Artifact checks are scoped identity/content
checks, not exhaustive archive certification. The isolated real-jar and named-host
Mixin probe limits remain owned by
[inventory verification](inventory.md#patch-only-identity-and-display-coverage-and-live-gaps)
and [inventory gaps](inventory.md#known-automated-gaps).

NeoForge remains on dependency `21.0.143` on the Minecraft 1.21 line; the
`1.21.1` archive label is not a runtime 1.21.1 claim. The
[manual matrix](manual-integration.md#pending-manual-and-integration-matrix)
owns the pending live scenarios.
