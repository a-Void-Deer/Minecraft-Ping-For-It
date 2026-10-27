# D0011: Display default inheritance

## Status

Confirmed product decision represented by the linked topic contract. This record
explains the display-transport trade-off, not completed implementation or
verification.

## Decision

Inventory item display travels as a sparse persistent patch over the recipient's
local prototype defaults, while variant identity keeps the actual explicit
persistent patch. Defaults are inherited without being transported or traversed
by Ping. Ordinary intentional strips use explicit suppressions; the terminal
size-only exception is explained by
[D0012](D0012-explicit-patch-identity-and-display.md). Display inheritance uses no
default fingerprint, handshake or synchronized default version.

[Inventory](../architecture/presentation/inventory.md#display-projection) owns
the patch, identity separation and display ladder.
[Shared sources](../architecture/presentation/shared_sources.md#cost-accounting)
owns generic admission and accounting, not this display policy.

## Rationale

A per-item display value is repeated across preview and tracking frames, while
most components on an ordinary stack are the item's prototype defaults. Sending
those defaults again for every entry spends wire budget on data the recipient
can obtain locally. Exactness is already carried by identity, so display can be
approximate without changing variant separation, counts or selection meaning.

Local defaults are a property of each client installation rather than a
negotiated session value. This choice accepts that two ends may render different
defaults for the same marker and does not make display a certificate that both
installations share a default. Ordinary intentional strips therefore still need
explicit suppression, but inheritance does not certify or deep-copy the local
default graph. Minecraft and installed mods own the native default behavior;
Ping's isolation and validation boundary is the explicit patch, including
entries introduced there by construction or hooks.

## Why not other approaches

- Do not send the complete effective display map: it repeats stable default data
  and defeats the bandwidth saving.
- Do not add a default fingerprint, handshake or synchronized default version:
  it would make display depend on a new cross-end negotiation without improving
  exact identity or counts.
- Do not treat an omitted default as a safety certificate or require Ping to
  authenticate the effective default graph: omission selects native inheritance,
  not Ping-owned default isolation.
- Do not express an ordinary intentional strip as omission: local prototype
  defaults would restore the data the display was meant to hide. The bounded
  terminal size exception deliberately accepts that trade-off.

## Consequences

Display may differ between recipients when local defaults differ; identity,
counts, selection references, aggregate folding and marker lifetime do not.
Patch retention remains owned display data rather than a resident value or a
provider read. Coverage and pending runtime evidence remain owned by
[testing and verification](../testing/verification/inventory.md); this decision
records no test or manual result.
