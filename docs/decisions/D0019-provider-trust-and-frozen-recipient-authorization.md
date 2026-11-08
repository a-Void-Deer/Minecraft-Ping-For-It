# D0019: Provider trust and frozen recipient authorization

## Status

Confirmed product decision represented by the linked topic contracts.
Implementation is present; current-source automated verification is recorded in
[build checks](../testing/verification/build-checks.md#provider-trust-and-frozen-authorization-final-verification);
live and performance evidence remain pending, and this record claims no measured
benefit.

## Decision

For Inventory Preview and Tracking, fluid reads trust the target provider's
validation unit rather than treating every physical member as an independent Ping
access-check object. Hardcoded exceptions for targets that need stricter
validation form an explicit set, and that set is empty. A future built-in or
external plugin may provide a dedicated provider that takes over validation. The
[inventory provider contract](../architecture/presentation/inventory.md#fluid-provider-trust-and-validation-exceptions)
owns that boundary; provider API, matching and fallback are not defined here.

All Ping content — Basic, Property, SECTION, Inventory and receipt — fixes its
recipient/content authorization at successful create; an Inventory Preview fixes
it when the Preview starts. The create records the complete effective result,
including native and custom permission, selector and child-reference constraints,
so later delivery does not repeat that authorization work. A Preview's result
does not carry into the `SELECT` create. The
[presentation snapshot contract](../architecture/presentation/presentation_snapshot.md#frozen-recipient-content-authorization)
owns the global rule, and the
[inventory contract](../architecture/presentation/inventory.md#receiver-authorization-freeze-points)
owns the freeze points. Later revocation or grant affects only new authorization
and new Pings. Values may still update, and source-owner read authority,
session/schema, expiry, disconnect and resource settings remain current. An
original audience member already authorized at create may still receive delivery
or repair when late-ready; actual disconnect rules are unchanged.

Example: A is authorized at creation and B is not. If A later loses authorization
and B gains it, the old Ping keeps A's create-authorized content and does not
backfill B; a new Ping uses the new result.

## Rationale

Low overhead is the priority, followed by a timeliness/correctness trade-off that
leans toward timeliness. Repeating permission, selector and child-reference checks
for every recipient and content update costs work that the create boundary can
settle once. The resulting staleness is intentional and limited to the recorded
authorization result. Source authority remains separate: recipient authorization
does not prove a source read is allowed, and source-read revocation still applies.

For fluids, the provider is trusted to expose its target's validation unit. Ping
does not claim that the provider guarantees every internal scope check or that
Ping independently re-checks each physical member. The empty hardcoded-exception
set records where stricter target validation would be needed without inventing
approval or matching semantics.

## Consequences

The complete create-time authorization result is retained with the Ping. This adds
per-Ping state and can deliver content under a later-revoked grant; neither a
measured benefit nor freedom from staleness is claimed. Provider trust and
validation are implemented with current-source automated verification; live and
performance evidence remain pending. A future built-in or external
dedicated-provider interface, matching and fallback remain undefined and
unimplemented. Pending evidence is owned by
[identity and authority](../testing/verification/identity-authority.md#frozen-recipient-authorization-coverage-and-live-gaps),
[presentation](../testing/verification/presentation.md#frozen-recipient-content-authorization-coverage-and-live-gaps),
[inventory](../testing/verification/inventory.md#frozen-recipient-authorization-and-fluid-provider-trust-coverage-and-live-gaps)
and the [manual matrix](../testing/verification/manual-integration.md#frozen-recipient-authorization-and-provider-trust).

The historical version approvals in
[D0016](D0016-logical-container-targets.md) and
[D0017](D0017-certified-tracking-observation-pools.md) remain in force. This
decision's wire-visible part joins the current protocol update without another
version bump; the [network protocol](../architecture/network/protocol.md) owns the
wire contract.
