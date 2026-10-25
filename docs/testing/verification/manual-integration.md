# Manual and integration verification

[Verification index and evidence rules](README.md) · [Build checks](build-checks.md)

## Pending manual and integration matrix

### General

- General Multi-player / other mod playtests.
  - dev-environment manual playtests have done.

### Wheel

- the [inventory-list input and presentation contract](../../architecture/picking/wheel.md#inventory-list) under live input on Fabric, Forge (NeoForge done)
- Focus loss and Alt-Tab, window replacement, screen handoffs and abort without overriding a newer cursor owner

### Selector readability

- Layouts in different language except en-US and zh-CN are not validated. May be truncated or missing translation.

### Inventory

- Exercise [native incremental sweeps](../../architecture/presentation/inventory.md#native-incremental-reads) in live 540/900-slot inventories and an installed tested-version Create Vault: partial preview, complete-only selected tracking, fresh live witness and first tracking read, ordinary within-slot updates, completion-plus-period cadence, and old completed totals during replacement. The [headless native coverage](inventory.md#native-incremental-sweep-coverage) is not installed-provider or game-runtime evidence; cross-slot movement remains outside the accepted accuracy guarantee.
- Exercise live owner/lock/loot, face/membership, load and topology changes during a large sweep, plus permission revocation, recovery, delayed multipart delivery and recipient-isolated repair in multiplayer. Preserve the existing marker lifetime and admission/range rules.
- Measure TPS, GC, heap retention and update latency under large-inventory and shared-consumer load on the supported loaders. Deterministic slot/work/memory admission and fixture tick bounds do not establish live performance.
- Exercise the [patch-only identity and display boundary](../../architecture/presentation/inventory.md#item-variants-and-component-fallback) with installed modded containers and component wrappers, including owo-backed items, through live preview, item selection and tracking on the supported loaders. This remains pending; headless fixtures are not installed-mod evidence.
- Exercise client/server local-default differences and the terminal size-only default icon in the live inventory list and tracking HUD, including retained reprojection after a ceiling change and delivery delayed by period-byte pressure. Automated transition evidence is tracked in [inventory verification](inventory.md#patch-only-identity-and-display-verification-pending).
