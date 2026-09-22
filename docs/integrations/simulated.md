# Simulated integration

This integration is an optional, NeoForge-only compatibility path. The mod is
gated by the presence of Simulated and must remain fail-soft when the optional
mod, its classes, or a particular runtime object is unavailable.

## Loading and failure boundary

The Simulated adapter is discovered and invoked lazily through reflection. Its
optional classes and members are not resolved during ordinary loading when the
mod is absent. Reflection failures, linkage failures, and unsupported runtime
shapes produce a handled-empty result; they must not break unrelated capture,
validation, or presentation.

## Paired docking connector

The supported connector case is `paired_docking_connector`. Resolution checks
the registry/block identity, block entity availability, facing and opposite
facing relationship, and the `POWERED` property before accepting the paired
connector. The paired connector's owner is the `docking_connector` block entity.

When all checks succeed, the adapter returns `PROXY_TO_OWNER` with the
`entity_block` target form. A direct `docking_connector` owner is unhandled by
this adapter and remains available to the ordinary owner path. A failed or
unsupported paired-connector check returns handled-empty rather than inventing
an owner or falling through as a different target.

## Candidate filtering

The default `simulated:honey_glue` entity blacklist belongs to entity candidate
filtering. It does not establish that a live Simulated runtime has been loaded
or validated; live-mod-session coverage remains an integration gap documented
in [verification](../testing/verification.md).
