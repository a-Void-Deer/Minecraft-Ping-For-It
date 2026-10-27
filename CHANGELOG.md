# Ping For It Changelog

## 0.5.0-pfi-beta2 (local working tree)

Compared with local `1.21.1` HEAD `16bf0257` (`0.5.0-pfi-beta1`).
This summarizes the local working tree, not a published release. No remote refs
were refreshed for this comparison.

### Item identity and display

- Item variants use the item ID and explicit persistent component patch,
  distinguishing additions from removals without expanding native defaults into
  identity. Root stack count stays separate. Client icons and names likewise
  apply the transmitted patch over local native defaults.
- When display-size exhaustion leaves even the final removal patch too large,
  display falls back to the native default icon and registry translation label,
  preserving exact identity, quantity and selection. Codec, structure and
  processing-budget failures do not qualify for this default-icon fallback.

### Large-container scanning

- Stable-indexed native inventories read their actual slots incrementally across
  ticks within budget, instead of freezing the entire inventory first. A complete,
  usable sweep can reach Ready; it remains a mixed-time observation, not an atomic
  snapshot of moving contents.
- Compatible consumers share already-read slot prefixes. Compact slot journals
  and shared variant definitions reduce repeated processing and retained copies.
  Item-field access is reused after initialization, and temporary processing
  buffers allocate on demand under the existing budgets.
- Ordinary NeoForge Create Vault sources reuse layout, slot mapping and cost
  metadata while rechecking current sources, permissions, members and topology.
  This does not introduce unconditional caching for every provider.

### Selection and tracking

- Selecting a valid preview entry creates its marker synchronously without
  requiring the previously observed slot to still contain that item. Server
  permissions, source bindings, selection tokens and admission checks still apply.
- Tracking starts with the selected server-retained preview quantity shown as
  Updating. Closing the preview preserves that value without retaining its
  unselected catalogue. The first fresh, complete scan replaces it even when the
  quantity is unchanged; an absent selected item becomes `0`, not a removed marker.
- Initial quantities follow normal publication budgets and send-time authority
  checks. Duplicate or other selections in the same tick cannot flush them early.

### Synchronization and HUD fixes

- Fixed premature completion during multipart tracking bootstrap and recovery.
  Checksums, partial replacements and control messages cannot turn historical
  quantities into fresh completed results. Failure and recovery preserve the last
  fully installed historical display rather than unfinished replacement data.
- Preview completion covers all retained entries, not just an empty or partial
  final delta. Entry admission accounts for actual multipart framing; an
  indivisible oversized entry is reported as incomplete instead of remaining
  permanently pending.
- HUD lifecycle indicators stay independent of the All variants qualifier.
  Shortening long item names or translated wording preserves the full long
  quantity instead of cutting off its digits.

### Performance settings and upgrading

- All numeric server inventory performance controls use sliders, with an end stop
  for unlimited mode where supported. Finite values and valid integers between
  adjustment stops are preserved; opening or rebuilding the page does not dirty
  an unchanged draft. Tooltips better explain scope and player-visible effects.
- Pending Memory now stores a finite value and an unlimited flag instead of a
  single integer. Existing integers migrate to finite mode while unrelated and
  unknown configuration entries are preserved.
- Budget defaults and validation ranges have been retuned without resetting
  existing legal saved values. Resync Cooldown accepts `0` to skip the cooldown;
  client waits for missing data remain separate.
- Budget-delay administrator notices use server ticks rather than wall-clock
  timing and include a `[ping for it]` source prefix.
- Server-configuration request, update and snapshot routes move from v3 to v4.
  Update clients and servers together: earlier configuration routes are not
  compatible. The inventory and presentation wire versions are unchanged.

Detailed behavior remains in the [inventory contract](docs/architecture/presentation/inventory.md),
[server configuration](docs/config/server.md#inventory-policy-object) and
[performance controls](docs/UI/settings-screen.md#server-performance-category).
