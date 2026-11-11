# Diagnostics and logging

This contract owns logging profiles, report severity, logical side, admission,
disclosure and diagnostic-output isolation. It covers failures and consequential
degradation across capture, presentation, inventory, networking, rendering and
optional integrations without redefining their domain outcomes.
[Security](security.md) owns authority, business catch boundaries and resource
lifetime; each topic owns its fallback, retry and reset semantics. Player
feedback is separate from logs; its boundaries remain with
[security](security.md#diagnostic-detail-and-user-feedback) and
[ping feedback](../UI/ping-feedback.md).

## Profiles and disclosure

Disclosure is selected by one profile per logical side:

| Profile | Disclosure |
| --- | --- |
| `NORMAL` | Stable side, site and handling codes; a caller-controlled static template with audited scalar arguments; and a bounded failure-class token. No original exception message, stack, cause, suppressed relationship or free-form context. |
| `DEEP` | The domain-permitted detached context and the bounded original exception graph. |
| `DEBUG` | The same full disclosure as `DEEP`, and eligibility for report sites reserved for the highest disclosure tier. |

The client and server each default to `NORMAL` independently. Early bootstrap
work uses the internal `BOOTSTRAP` side with a fixed `NORMAL` profile; it has no
third persisted setting and does not initialize configuration or platform
services to discover one. Production call sites select `CLIENT`, `SERVER` or
`BOOTSTRAP` explicitly. An integrated server keeps the server side even when it
shares a JVM with a client, and asynchronous or codec-before-handler paths retain
the side that owns the operation.

A site selects a minimum profile; the profile in effect for the logical side must
include that minimum. A selected minimum is only an eligibility condition. The
effective profile controls the disclosure of the resulting report, so a failure
reported through a normal minimum emits one report whose detail expands under
`DEEP` or `DEBUG` instead of emitting a separate summary and detail pair. A
stable caller-owned summary, when the site supplies one, is present at every
profile; higher disclosure adds context and exception detail rather than
replacing that summary. If optional detail generation fails, that summary
remains and the detail is marked unavailable. The minimum profile, backend
severity and explicit channel are independent decisions; none substitutes for
another.

Backend severity remains assigned by the domain. `DEBUG` describes expected
probing, ordinary absence, rejection or recovery detail when the domain calls
for a report. `WARN` reports unexpected failures consumed into a fallback,
rejection or disabled capability, and important degradation even without an
exception. `ERROR` retains the severity assigned by the owning domain. Ordinary
status messages may use `INFO` or another established status level. A profile
neither promotes nor demotes severity; `NORMAL` reporting still carries the
domain's `WARN` or `ERROR` level.

Report at the boundary that consumes the failure, while the original exception
is available. Identify the failed operation and its known handling or affected
capability. Do not duplicate the same consumed failure at each forwarding layer.
An exception-free event has a stable reason and scalar context, not a fabricated
exception or a null-exception report.

`NORMAL` detail consists only of fixed, audited material: a stable caller-owned
site and handling code, a caller-controlled template with scalar values from a
finite numeric, boolean or code allowlist, and a stable bounded failure-class
token. The token distinguishes known failure classes without disclosing the
class name, original message or source graph. Provider messages, identifiers,
names, positions, payloads and arbitrary reasons cannot enter a `NORMAL`
template.

`DEEP` and `DEBUG` may include complete domain-permitted context and the
original exception relationships without redaction: messages, types, source
frames, causes and suppressed exceptions within finite text, node, depth and
frame bounds. Explicit truncation, cycle and unavailable markers distinguish
omitted or inaccessible detail from an absent failure. Scalar control
characters are escaped recognizably while report structure keeps readable line
breaks.

Disclosure follows the effective profile for every domain, including capture
coordination. A domain may offer less context or retain a stricter
content-free summary, but it cannot widen disclosure beyond the effective
profile. A higher profile changes neither source-read, capture or
payload-traversal authority nor a business catch boundary.

## Configuration publication

Configuration loading starts from `NORMAL` for the affected side. The handler
then publishes the loaded profile at an effective load, reset, recovery,
invalid-file protection, save or save-without-domain-update entry point. A
protected future-version file keeps its original bytes and the in-memory
defaults, including their `NORMAL` profile. The configuration model setter
stores only the preference. The [configuration UI](../UI/settings-screen.md#logging-profile-controls)
owns the client's immediate publication, and
[changing server configuration](config/changing-server-config.md#server-apply-order-and-persistence)
owns the server-side publication order and persistence attempt.

## Report pipeline

One terminal report follows this order:

1. snapshot the effective profile for its logical side;
2. check the selected minimum and, for detail-only sites, the non-`NORMAL`
   requirement;
3. prepare the backend and ask whether the assigned severity is enabled;
4. admit through an explicit channel when the call site supplies one;
5. evaluate admitted context and format the report; and
6. submit one bounded string to the backend.

One limited ordering exception applies only to the two remaining inventory gate
terminals—`inventory/fluid-route` and `inventory/snapshot-discovery`—whose
established backend-disabled semantics consume an opportunity. For those
terminals, one effective-profile snapshot and minimum check run first; the same
report invocation then runs its existing domain admission callback; only after
that does the terminal prepare the backend and check its enabled severity; the
original snapshot context and failure are formatted last. A callback that
returns null rejects the report without consulting the backend. A
below-minimum site does not run the callback. A backend-disabled result leaves
the callback's consumption in effect and does not roll it back. This exception
adds no channel, rate limit or public admission permit, and retains neither the
callback nor the original throwable. Ordinary explicit domain channels retain
the after-backend admission order described below.

`inventory/snapshot` is not one of those terminals. Its limit reporting uses the
inventory limit report path, which keeps the fixed site/reason and the
effective-`DEBUG` interval-only bypass; it must not be routed back through the
legacy domain gate, because that ordering would suppress the required
per-occurrence limit reports.

A report uses the profile snapshot taken when it starts; a concurrent profile
change applies to later reports. The backend enabled check is a hint: it does not
evaluate detailed context and does not consume channel admission. An explicit
`isEnabled` query follows the same rule.

Detailed context suppliers are lazy. Disabled, below-minimum and
channel-suppressed reports do not evaluate them or read exception accessors.
An admitted supplier is evaluated at most once after admission and outside
channel locks. A supplier must defer collection from the raw source; wrapping an
already produced string does not satisfy the lazy boundary. Attempt-local
access does not authorize keeping a target, world, provider or payload object
alive after the report. A recoverable context failure leaves an explicit
unavailable marker and never replaces the original business failure.

The final report is one bounded string containing its envelope, permitted
context and permitted exception detail. Submit it once without attaching the
exception for a second, unbounded logger expansion.

Production reporting has no implicit severity window, fingerprint store,
capacity gate or global duplicate suppression. Frequency control exists only
through an explicit channel or a domain-owned gate. A report without a channel
does not read a clock or maintain window history.

## Explicit admission channels

A call site that needs frequency control supplies an explicit channel. Channel
identity is object identity; its controlled name is only a label. Each channel
keeps independent state for `CLIENT`, `SERVER` and `BOOTSTRAP`, shared across
severity and profile changes. A profile change does not reset that history.

An interval channel admits its first report and then admits again only after its
monotonic interval. Suppressed attempts increment a saturating count, and the
next admitted report includes the accumulated count. Frequency control does not
replace or remove detail permitted by the effective profile. The channel retains
only bounded counters and timing facts, never an exception, world, provider
handle or deferred context.

A limit-handling site may report every occurrence only at effective `DEBUG` by
bypassing an interval channel. The bypass consumes no interval admission and
adds no suppressed count, and the report marks it explicitly. A domain channel
is never bypassed and still runs its admission callback for every report,
including through the inventory limit helper. Below
`DEBUG`, the same site remains interval-limited. This opt-in changes neither the
minimum profile nor backend severity, applies only to an occurrence that
actually triggered limit handling, and keeps the one summary-plus-detail report
structure. A successful or ordinary non-limit occurrence does not use it.

An explicit domain channel runs its admission callback once after the terminal
report passes the profile and backend checks. It adds no interval, clock or
capacity gate. A below-minimum site neither collects logging-only material nor
consumes a report opportunity. Existing domain observation, cadence and
lifecycle rules remain domain-owned, including their backend-disabled
opportunity semantics; the terminal pipeline does not replace those rules. The
limited domain-before-backend exception above is not an explicit channel and
does not change the after-backend order for them.

## Self-fault reporting

Reporting-pipeline failures use a separate self-fault reporter rather than a
business diagnostic. Fault identity is the logical side, controlled site,
pipeline stage and fault kind. Stages identify setup, backend enablement,
channel clock, admission, context, exception-accessor, formatting, sink and
reentry failures; kinds distinguish runtime, linkage and reentry faults.

The first occurrence at `NORMAL` disclosure emits one summary without throwable
detail; a first occurrence with higher disclosure may emit the bounded detail
directly. A later genuine occurrence may add the next higher disclosure layer
once. Attempted disclosure is monotonic, and the reporter retains no throwable or
deferred context to replay. Raising the profile alone emits nothing. Occurrence,
repeat and attempted-disclosure counters saturate and remain observable without
becoming business channels or clocks.

A nested business report during an active terminal is detached and counted, then
reported after the outer terminal releases its channel, clock and backend
callbacks. Self-fault formatting recursion terminates. If self-fault text
formatting itself fails, one fixed fallback record is attempted, and later
formatting failures only increment counters. `NORMAL` self-faults omit the class
and message; higher profiles include one bounded class and message without a
stack, cause, suppressed graph or throwable string conversion.

Self-fault output uses a process-lifetime emergency writer over the standard
error file descriptor. The writer never closes that descriptor and never
installs a shutdown hook or global appender. Each complete UTF-8 record ends
with a newline and is written and flushed as one attempt; formatting and
encoding happen outside the descriptor lock. Overflow is marked explicitly
within the bounded record. An open, write or flush failure permanently stops the
fallback after a finite attempt, so output never recurses through the main
logger. The writer does not use the `System.err` stream, replace the main logger,
or take over a status listener. Ordinary reports continue to use the main
logging backend.

Emergency output is best effort and synchronous; it may block on the descriptor
and provides no delivery acknowledgement, replay or reliable-delivery promise.
It does not capture every swallowed or asynchronous backend failure and does not
guarantee that a record reaches `latest.log`.

## Backend and output isolation

The production backend owns the exact loggers `pingforit.client`,
`pingforit.server` and `pingforit.bootstrap`. Each has an explicit `DEBUG`
level so every profile-selected severity can reach the backend. Backend
initialization changes only those exact logger levels; it does not change the
root logger, another mod's logger, appenders, filters or additivity. Existing
appender or filter thresholds may still discard an enabled report, and external
logging configuration remains authoritative.

Backend initialization is recoverable after a lookup or setup failure. It
observes Log4j reconfiguration and reapplies the exact owned levels idempotently.
On Forge and NeoForge, a real `DEBUG` record may appear only in the loader's
debug log; the backend does not guarantee placement in `latest.log`.

Recoverable reporting failures, including runtime or linkage failures in
context, formatting or output, stay local without recursively logging another
business diagnostic. Suppression or an output failure does not change
authority, accounting, fallback, retry, publication or marker lifetime. An
admitted attempt may consume its report opportunity even when the sink fails;
reporting promises neither reliable delivery nor automatic replay. Logging does
not enlarge a business boundary's recoverable exception set or establish a
blanket catch-all policy.

The [shared diagnostics evidence](../testing/verification/integrations.md#shared-exception-diagnostics)
owns formatter, profile, self-fault and backend coverage and deployment
limitations. Domain evidence remains with the
[restricted capture seams](../testing/verification/interaction.md#capture-wheel-and-cancellation),
[presentation](../testing/verification/presentation.md),
[inventory](../testing/verification/inventory.md) and
[optional integrations and rendering](../testing/verification/integrations.md).
These inventories do not establish deployed appender behavior or completion of
the [pending live scenarios](../testing/verification/manual-integration.md#pending-manual-and-integration-matrix).
