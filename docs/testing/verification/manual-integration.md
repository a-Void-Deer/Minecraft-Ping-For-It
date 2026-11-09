# Manual and integration verification

[Verification index and evidence rules](README.md) · [Build checks](build-checks.md)

Additional requirements for this file: 
- Clear manual retest steps (or multiple step groups) should be provided to facilitate retesting.
- This document follows the style requirements of docs/AGENTS.md.

## Pending manual and integration matrix

### General

- General multiplayer and other-mod playtests remain pending.
  - Limited recorded observation: dev-environment manual playtests were
    performed, but their environment, scenarios, date and results are
    unrecorded, so they do not close this scenario.

### Wheel

- The [inventory-list input and presentation
  contract](../../architecture/picking/wheel.md#inventory-list) under live input
  remains pending on Fabric and Forge.
- Focus loss and Alt-Tab, window replacement, screen handoffs and abort without
  overriding a newer cursor owner

### Selector readability

- Layouts in different language except en-US and zh-CN are not validated. May be truncated or missing translation.

### Settings and server performance

On macOS and Linux (currently untested), on a live client and a dedicated or integrated server, with a Minecraft window and the platform modifier key (Option on macOS, Alt on Linux), exercise both logging rows through activation with the modifier held and released. Confirm that a current `DEBUG` value keeps its caption when the modifier is released, the next non-modifier activation exits to `NORMAL`, and `DEBUG` cannot be selected again without the modifier.

### Fluid inventory

All scenarios here are pending. The
[fluid inventory automated coverage](inventory.md#fluid-inventory-coverage)
records the final headless coverage separately from these unperformed live checks.

- On Minecraft 1.21.1 Fabric, Forge 52.0.2 and the supported NeoForge setup,
  open a static block that exposes both items and fluids. Check one panel's
  resource groups, independent field authorization/status/completion, shared
  request close, one-resource selection, replay and ordinary annotation policy.
  Deny and restore only one field while the other remains usable; include
  multipart repair, delayed controls and known-marker removal.
- Keep a selected row stationary while an unrelated variant folds or either
  lane replaces its baseline, including equal opaque keys in both groups and a
  scrolled list. Check that a still-selectable lane-qualified key survives and
  release submits its intended resource, with fallback only when that row is
  actually removed or invalid.
- Exercise Fabric component variants and sub-millibucket amounts, Forge NBT
  variants, and NeoForge component variants through actual capabilities. Include
  same-ID distinct variants, identity-only refusal, display-only refusal,
  checked conversion/sum limits, true empty views and selected-resource absence
  only after complete scanning. Confirm frozen-face and owner/member/load gates,
  no forced load, mutation or creation-time presence getter, and no stale
  success after revocation or topology loss.
- Replace a Fabric public wrapper while preserving its stable native underlying
  storage, then merge or detach that underlying storage. Check current-wrapper
  reads, bounded validation, invalidation only where continuity is lost and
  fresh complete recovery. On a generic opaque provider, exercise exposed
  route/count/capacity changes and the accurate per-server/provider-class INFO
  notice; do not treat unchanged public metadata as proof against a hidden remap.
- In an installed tested-version NeoForge Create tank, target different members
  and faces, including cross-chunk load changes, controller replacement, merge,
  split, creative tanks and active boilers. Confirm controller-only quantity
  without member duplication, fixed-face capability access, mode denial without
  generic fallback and fresh recovery under the original marker expiry. Repeat
  the creative-tank denial and valid 3x3x20 (180-member) and 3x3x32
  (288-member) targets with each side's logging profile at `DEBUG`: correlate
  the request, resource kind and target; for a denial or a changed
  member/controller, observe the first rejection's stage/reason and
  controller/shape/member/handler context, then the matching selector
  transition to grey status. At `NORMAL`, expected unavailability stays absent.
  A successful 180- or 288-member tank completes without a rejection line or
  size refusal; a target whose hit lies outside the verified prism names the
  distinct `hit-outside-structure` reason rather than the member limit. While
  sampling the same 288-member tank at roughly 3-5 Hz for 20 s, compare TPS tail
  latency with a matched run that samples no fluid target; this remains a
  pending measurement. These checks do not widen the supported topology.
- With an installed Sable provider and an otherwise supported fluid target,
  move the same target under the same live lease to another sub-level. Check
  `UPDATING` retains the displayed quantity without transition-only `INVALID`
  or duplicate chat, new-scope permission/physical evidence is established and
  only a fresh complete delivered result replaces the quantity, including zero.
  Withhold final parts and inject real denial, unreadability, release and expiry;
  old cuts/seeds/partial sums must not complete the new scope. Keep item and
  ordinary multiblock invalidity as independent controls.
- Compare list, HUD and exactly-once content chat in bundled locales for zero,
  integer mB, positive sub-mB, exact buckets and very large quantities. Inspect
  approximate indicators, six-significant-digit large forms, Latin billion
  versus bucket labels, CJK units, long custom names and safe empty icons for
  unknown/malformed display data. Exercise narrow viewports, fractional text
  scales, native font fitting and GPU output, not only localized resource presence.
- With installed native fluid handlers, include a non-placeable fluid such as
  supported Create tea. Check localized base names, missing-translation fallback,
  literal custom names, native still sprites and text-only failure. Reload client
  resources after both a successful and a failed lookup. Launch a dedicated
  server with the loader name service present and confirm no client render
  initialization is required.
- Exercise mixed inventory-v1/v2 peers on real loader connections and matching
  v2 peers with delayed multipart delivery. Confirm the dedicated route has no
  v1 reinterpretation, while unrelated presentation/configuration behavior stays
  on its established version contracts. Measure combined item/fluid work under
  low shared budgets before making throughput, TPS, allocation or GC claims.
