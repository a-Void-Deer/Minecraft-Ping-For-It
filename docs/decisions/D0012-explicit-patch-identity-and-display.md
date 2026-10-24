# D0012: Explicit-patch identity and display

## Status

Confirmed product decision represented by the linked topic contract. This record
explains the boundary and trade-offs, not completed implementation or verification.

## Decision

Inventory identity and Ping-owned component processing start from the actual
resident or provider explicit persistent patch, rather than the effective map.
Both ends share that patch-only reading boundary. Native defaults remain native
behavior, not an additional component graph for Ping to authenticate or own.

Display retains the sparse inheritance decision in
[D0011](D0011-display-default-inheritance.md). Its ordinary suppression ladder has
one size-only terminal exception that permits the native default icon instead
of losing the icon solely because the suppression patch is too large. This is
not an identity fallback or a recovery from malformed or unprocessable data.

[Inventory](../architecture/presentation/inventory.md#item-variants-and-component-fallback)
owns exact identity input, projection, refusal and retained-state semantics;
its [budget contract](../architecture/presentation/inventory.md#budgets-queues-and-memory)
owns publication limits. [D0010](D0010-persistent-component-codec-trust.md) retains
the registered persistent-codec trust boundary.

## Rationale

The effective map mixes explicit variant data with item defaults and mod-derived
state. Treating all of that graph as identity or display ownership makes Ping
depend on component-layer discovery and default interpretation it does not own.
The actual patch provides one concrete cross-end input boundary without requiring
mod-specific wrapper adapters. It also preserves distinctions a provider still
explicitly carries, even if a default would produce the same effective value;
native normalization remains the native system's responsibility.

Sparse inheritance would not retain that boundary if Ping expanded defaults to
sanitize, certify or deep-copy them afterward. Accepting native default behavior
therefore gives up universal default-graph isolation, while leaving explicit
mutable values and hook-written patch entries subject to Ping's checks. Exact
persistent compatibility does not promise complete unknown-component icons.

Suppression metadata can itself exhaust a display ceiling after all carried
values are removed. A native default icon preserves item recognizability without
reclassifying a valid exact variant. The cost is deliberate loss of those
tombstones at this terminal size boundary. Choosing the display before queuing
keeps congestion separate from content: temporary send allowance must not decide
which component detail a recipient sees.

## Why not other approaches

- Do not reconstruct normalized-away patch entries or compare against defaults:
  that would invent identity input rather than preserve the current provider's
  explicit state.
- Do not add component-layer reflection or a special mod wrapper reader: that
  would reopen the default/derived graph and split the shared reading boundary.
- Do not discard suppression metadata after a structure, codec or work refusal:
  the default-icon exception answers size exhaustion, not failed validation.
- Do not let a period-byte shortage simplify an already chosen display or add a
  new wire quality marker: scheduling and display semantics remain separate.

## Consequences

Two patches with the same effective values can identify distinct variants if
their explicit persistent entries differ. Recipients can inherit different local
defaults, and the terminal size exception can inherit values suppressed earlier
in projection. Neither trade-off changes exact selection meaning or counts.
Retained terminal display state is monotone rather than a recipe to rebuild
discarded detail later.

Coverage changes and pending runtime evidence are owned by
[inventory verification](../testing/verification/inventory.md#patch-only-identity-and-display-verification-pending).
This decision records no test, installed-mod or manual result.
