# Testing and verification

This document separates three kinds of evidence: automated coverage known to
exist, gaps that remain, and manual/integration scenarios that are still
pending. An inventory statement records established coverage scope; it is not a
claim that a test, build or game session was run for the current change.

For implementation changes, the tracked verification expectations are to:

- run the relevant automated tests and repository lint, static-analysis, format,
  type, and compile checks;
- build every affected Fabric, Forge, and NeoForge source set, and run
  `verifyModIdentity` when loader packaging, artifact identity, or the complete
  shippable artifact set is in scope; and
- report every command actually run and its result, every applicable check not
  run and the exact reason, manual validation actually performed, and remaining
  manual or integration gaps.

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

## Verification navigation

The [documentation guide](../../README.md) routes product behavior to its
normative topic owners. The pages in this directory own evidence inventories,
their limitations and gaps, not those product contracts.

| Page | Evidence responsibility |
| --- | --- |
| [Testing and verification](README.md) | Global evidence rules, check selection, topic routing, and recording future evidence. |
| [Build checks](build-checks.md#build-source-set-and-artifact-verification) | Source-set, build-task and artifact-identity evidence and its limits. |
| [Manual and integration verification](manual-integration.md#pending-manual-and-integration-matrix) | Pending manual/integration scenarios; listing a scenario does not record a completed run. |

## Existing automated coverage inventory

Each topic page keeps its coverage and local known automated gaps together.

| Topic page | Coverage sections | Gaps |
| --- | --- | --- |
| [Identity and authority](identity-authority.md) | [Domain, identity and authority](identity-authority.md#domain-identity-and-authority); [client marker runtime coverage](identity-authority.md#client-marker-runtime-coverage); [rate-policy courtesy behavior](identity-authority.md#rate-policy-courtesy-behavior). | [Known automated gaps](identity-authority.md#known-automated-gaps) |
| [Presentation](presentation.md) | [Presentation snapshot negotiation, policy and adapters](presentation.md#presentation-snapshot-negotiation-policy-and-adapters); [target content preview](presentation.md#target-content-preview); [content receipt chat and pending completion](presentation.md#content-receipt-chat-and-pending-completion). | [Known automated gaps](presentation.md#known-automated-gaps) |
| [Interaction](interaction.md) | [Capture, wheel and cancellation](interaction.md#capture-wheel-and-cancellation); [Precise live candidate capture](interaction.md#precise-live-candidate-capture); [selection-policy and input-state seams](interaction.md#selection-policy-and-input-state-seams); [exact entity-local picking and Create raycast seams](interaction.md#exact-entity-local-picking-and-create-raycast-seams); [capture and acceptance range](interaction.md#capture-and-acceptance-range); [Back-hover return state](interaction.md#back-hover-return-state); [headless spatial radial menu](interaction.md#headless-spatial-radial-menu); [native client interaction runtime](interaction.md#native-client-interaction-runtime); [inventory-list gestures and navigation](interaction.md#inventory-list-gestures-and-navigation); [double-click Danger and inventory stay-open](interaction.md#double-click-danger-and-inventory-stay-open). | [Known automated gaps](interaction.md#known-automated-gaps) |
| [Configuration and settings UI](configuration-ui.md) | [Configuration recovery and selection grammar](configuration-ui.md#configuration-recovery-and-selection-grammar); [inventory and spatial-selector configuration](configuration-ui.md#inventory-and-spatial-selector-configuration); [double-click Danger and inventory stay-open configuration](configuration-ui.md#double-click-danger-and-inventory-stay-open-configuration); [server-settings snapshots and updates](configuration-ui.md#server-settings-snapshots-and-updates); [settings-screen navigation, catalog, layout and localization](configuration-ui.md#settings-screen-navigation-catalog-layout-and-localization). | [Known automated gaps](configuration-ui.md#known-automated-gaps) |
| [Shared sources](shared-sources.md) | [Shared source identity and capture results](shared-sources.md#shared-source-identity-and-capture-results); [shared source access contract](shared-sources.md#shared-source-access-contract); [shared source sync publication contract](shared-sources.md#shared-source-sync-publication-contract); [shared source cost ledger](shared-sources.md#shared-source-cost-ledger). | [Known automated gaps](shared-sources.md#known-automated-gaps) |
| [Inventory](inventory.md) | [Inventory preview and tracking foundation](inventory.md#inventory-preview-and-tracking-foundation). | [Known automated gaps](inventory.md#known-automated-gaps) |
| [Rendering](rendering.md) | [Name and geometry outcomes](rendering.md#name-and-geometry-outcomes); [selector overlay layout and frame rendering](rendering.md#selector-overlay-layout-and-frame-rendering); [render entity lookup and locator resolver seams](rendering.md#render-entity-lookup-and-locator-resolver-seams); [focused native block-outline regression coverage](rendering.md#focused-native-block-outline-regression-coverage). | [Known automated gaps](rendering.md#known-automated-gaps) |
| [Integrations](integrations.md) | [Shared exception diagnostics](integrations.md#shared-exception-diagnostics); [optional failure diagnostics](integrations.md#optional-failure-diagnostics); [Create presentation adapters](integrations.md#create-presentation-adapters); [Create content preview](integrations.md#create-content-preview); [Create raycast seams](integrations.md#create-raycast-seams); [Sable integration coverage](integrations.md#sable-integration-coverage); [Simulated integration coverage](integrations.md#simulated-integration-coverage); [optional rendering structural coverage](integrations.md#optional-rendering-structural-coverage). | [Known automated gaps](integrations.md#known-automated-gaps), including [Sable-specific gaps](integrations.md#sable-specific-gaps) |

## Known automated gaps

The following gaps remain open until direct evidence closes them:

- [Identity and authority](identity-authority.md#known-automated-gaps)
- [Presentation](presentation.md#known-automated-gaps)
- [Interaction](interaction.md#known-automated-gaps)
- [Configuration and settings UI](configuration-ui.md#known-automated-gaps)
- [Shared sources](shared-sources.md#known-automated-gaps)
- [Inventory](inventory.md#known-automated-gaps)
- [Rendering](rendering.md#known-automated-gaps)
- [Integrations](integrations.md#known-automated-gaps)

Build evidence is scoped separately in [build checks](build-checks.md), and
pending live scenarios remain in the
[manual and integration matrix](manual-integration.md#pending-manual-and-integration-matrix).

## Recording future evidence

- Add or update focused tests in the same change as behavior where practical.
- Record a gap as closed only when a named automated check or an actually
  performed manual/integration scenario covers it.
- Keep automated coverage, commands actually run, and manual observations as
  separate claims in implementation reports.
- Update the owning topic contract when expected behavior changes; changing a
  test alone does not redefine the product.
- Do not convert pending manual scenarios into completed validation based on
  compilation, document review, optional-API loading or unit-test seams alone.
