# D0011: Display default inheritance

## Status

Confirmed product decision represented by the linked topic contract. This record
explains the display-transport trade-off, not completed implementation or
verification.

## Decision

Inventory item display travels as a sparse persistent patch over the recipient's
local prototype defaults, while variant identity keeps the complete
server-effective native persistent semantics. Unmodified defaults that need no
special display projection may be omitted; intentional strips remain explicit
suppressions. Display inheritance uses no default fingerprint, handshake or
synchronized default version.

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
installations share a default. Intentional strips therefore still need explicit
suppression, and an unsafe local default still uses the existing fail-soft
display path.

## Why not other approaches

- Do not send the complete effective display map: it repeats stable default data
  and defeats the bandwidth saving.
- Do not add a default fingerprint, handshake or synchronized default version:
  it would make display depend on a new cross-end negotiation without improving
  exact identity or counts.
- Do not treat an omitted default as safe: a client's local default may be
  unsafe, malformed or opaque, so isolation and fail-soft remain mandatory.
- Do not express an intentional strip as omission: local prototype defaults
  would restore the data the display was meant to hide.

## Consequences

Display may differ between recipients when local defaults differ; identity,
counts, selection references, aggregate folding and marker lifetime do not.
Patch retention remains owned display data rather than a resident value or a
provider read. Coverage and pending runtime evidence remain owned by
[testing and verification](../testing/verification/inventory.md); this decision
records no test or manual result.
