# Ping For It Changelog

## Unreleased

Changes between local `1.21.1` HEAD `3abf2c5d` (`0.5.0-pfi-beta2`) and current HEAD,
compared directly.

### Grouped containers

- Either half of a vanilla normal or trapped double chest now addresses one
  logical target. Supported [Create item vaults on NeoForge](docs/integrations/create.md#vault-logical-target-and-presentation-topology).
- Same-target winner selection and HUD grouping use that logical container.
  Markers still keep their original physical hit, anchor, owner, cancellation
  rights and independent expiry.
- Container presentation covers both verified chest halves or each verified
  Vault cell. Missing, unloaded or inconsistent grouping metadata falls back to
  the exact hit block. See the
  [logical target model](docs/architecture/identity/target_model.md#frozen-logical-block-groups).
- Extended logical grouping and whole-structure outlines to supported static
  [Create fluid tanks on NeoForge](docs/integrations/create.md#fluid-tank-logical-target-and-presentation-topology),
  with exact-hit fallback when the complete structure cannot be verified.

### Inventory preview and tracking

- Certified native chest and ordinary NeoForge Create Vault views can reuse
  physical inventory reads across independently authorized players and faces
  when the provider proves their visible slots equivalent.
- New Tracking consumers can join an eligible, still-active Preview or Tracking
  scan while physical scanning demand remains.
- Eligible Previews can borrow active Tracking scans, without enabling general
  Preview-only cross-player or cross-face pooling. A completed Preview left open
  no longer blocks later Tracking sweeps and does not periodically rescan itself.
- Strengthened final send-time checks for current owner permissions, source,
  session, recipient and marker validity. Revocation or failure withdraws queued
  valid frames; an owner-only denial does not reset authorized sharing peers.
- Marker removal or expiry clears local inventory state and rejects delayed
  updates, preventing stale tracking data from returning. Detailed sharing and
  publication boundaries remain in the [inventory contract](docs/architecture/presentation/inventory.md#certified-tracking-observation-pools).
- Inventory-list updates preserve the selected row while its resource remains
  available.
- Revoking or restoring item or fluid permissions preserves the other resource's
  still-authorized preview and tracking state. Permission updates delayed by
  send budgets or discarded after a permission change are retried, allowing
  restored access to synchronize without resetting the other resource.

### Fluid preview and tracking

- Added fluid preview, selection and marker tracking for static block storage
  through Fabric Transfer and Forge/NeoForge fluid handlers, including supported
  ordinary Create tanks on NeoForge. Items and fluids share one grouped preview
  with independent permissions and state.
- Fluid quantities use consistent millibucket and bucket formatting in the list,
  tracking HUD and content chat, with approximate and sub-millibucket indicators.
  Quantity text takes priority over names and retains exact fits at fractional
  font scales.
- Fluid names and still-texture icons use native loader support, including for
  non-placeable fluids. Unavailable icons fall back to text-only display.
- Scans can finish after final validation is delayed by processing budgets,
  without rereading storage. Capacity-limited previews remain explicitly
  incomplete rather than claiming complete totals.
- Fixed negative native fluid amounts on Forge and NeoForge being mistaken for
  empty tanks and replacing tracking results with zero; such reads are now
  unavailable.
- Fluid icons fade smoothly when the interface opens or closes and when opacity
  changes, instead of disappearing abruptly at low opacity.

### Server controls and Create optimizations

- Added `inventory.tracking.distinguishFaces` and
  `inventory.tracking.distinguishPlayers`, both defaulting to `false`, with
  **Separate Tracking by Face** and **Separate Tracking by Player** toggles on
  the server Performance page. Enable either to partition certified scan sharing
  by that context
- Changes to these switches affect subsequent scans. Running scans finish under
  their previous policy without restarting or changing marker lifetimes. See
  [server configuration](docs/config/server.md#inventory-policy-object).
- Tracking's per-target quotas now span the frozen logical container group,
  rather than granting separate allowances for different member hits.
- Optimized supported Create Vault reads by reusing fixed reflection metadata
  and removing duplicate validation work on the audited ordinary path. Current
  handlers, topology and owner authority still require checks; contents and
  successful permission checks are not cached.

### Settings and help

- Renamed three client categories to **Markers & Range**, **Controls & Gestures**,
  and **Channel, Sound & Notices**.
- Reorganized pages into semantic sections: related controls share rows and
  singletons use full-width rows. Pairs stack vertically in order when the
  viewport or translated labels require it, retaining scrollbar clearance and
  a fixed footer. Presentation-editor pairs follow the same layout rules.
- Added native hover and keyboard-focus help across value controls, categories,
  navigation and actions. Help explains enum modes, Off/On effects, numeric
  direction, scope and application timing. Action help distinguishes Server
  Policy mutations sent on activation from ordinary server drafts submitted on
  final close; typing a selector alone sends no mutation.
- Updated labels, headings, help and assembled UI messages in all locales.

### Network compatibility

- **Update clients and servers together.** Presentation moves from
  `presentation-v1` to `presentation-v2`; server-configuration request, update
  and snapshot routes move from v4 to v5. Earlier routes have no fallback.
  The server derives and transmits logical target keys.
- Removed inactive legacy ping, marker and duration-policy routes. Unavailable
  inventory sources use `INVALID`. inventory `UNAVAILABLE` and `EXPIRED` statuses
  are removed, with expiry handled by marker lifecycle. Inventory `RESYNC` now
  requires a marker ID; the unused request-only form is removed.
