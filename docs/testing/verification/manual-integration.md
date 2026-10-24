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

- Exercise the [patch-only identity and display boundary](../../architecture/presentation/inventory.md#item-variants-and-component-fallback) with installed modded containers and component wrappers, including owo-backed items, through live preview, item selection and tracking on the supported loaders. This remains pending; headless fixtures are not installed-mod evidence.
- Exercise client/server local-default differences and the terminal size-only default icon in the live inventory list and tracking HUD, including retained reprojection after a ceiling change and delivery delayed by period-byte pressure. Automated transition evidence is tracked in [inventory verification](inventory.md#patch-only-identity-and-display-verification-pending).
