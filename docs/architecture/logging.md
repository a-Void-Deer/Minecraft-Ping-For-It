# Diagnostics and logging

This contract owns shared diagnostic reporting: severity, report identity,
frequency admission, bounded detail, disclosure and output isolation. It covers
failures and consequential degradation across capture, presentation, inventory,
networking, rendering and optional integrations without redefining their domain
outcomes. [Security](security.md) owns authority, business catch boundaries and
resource lifetime; each topic owns its fallback, retry and reset semantics.
Player feedback is separate from logs; its boundaries remain with
[security](security.md#diagnostic-detail-and-user-feedback) and
[ping feedback](../UI/ping-feedback.md).

## Levels and reporting boundaries

Ordinary status messages may use INFO or other established status levels on the
shared logging backend; they are not all diagnostic-window events. Diagnostic
reports use DEBUG, WARN or ERROR:

- DEBUG describes expected probing, ordinary absence, rejection or recovery
  detail when that domain calls for a report. A missing optional mod or a normal
  permission denial is not automatically an exceptional failure.
- WARN reports unexpected failures consumed into a fallback, rejection or
  disabled capability, and important degradation even without an exception.
  These reports remain visible at an INFO threshold.
- ERROR retains the severity assigned by the owning domain. Shared reporting
  does not downgrade those failures or promote every ordinary outcome to WARN.

Report at the boundary that consumes the failure, while the original exception
is available. Identify the failed operation and its known handling or affected
capability. Do not replace the original cause with only its class or message
where exception detail is permitted, or duplicate the same consumed failure at
each forwarding layer. An exception-free diagnostic event has a stable reason
and scalar context, not a fabricated exception or a null-exception report.

## Stable identity and general admission

Operation and scope identify stable, low-cardinality stages and capabilities,
such as a provider, target class or registry kind. An exception fingerprint also
distinguishes exception type and source location; an exception-free event uses
its stable reason. Request IDs, interaction rounds, positions, timestamps and
changing message text belong in permitted context, not new admission keys.
Changing those details must not defeat duplicate suppression.

General diagnostic windows use monotonic elapsed time and bounded keys and
state. DEBUG, WARN and ERROR have independent window capacity, duplicate counts
and overflow accounting: traffic at one level cannot consume another level's
report admission. Repeated fingerprints are suppressed within their window;
their accumulated suppressed count is reported when that retained fingerprint
is next admitted.

Capacity pressure does not evict active entries to admit a stream of new keys.
Expired entries can release capacity. Excess new fingerprints contribute to a
bounded, low-frequency overflow summary at their own level without evaluating
their detailed context. Counts saturate rather than wrap, so a saturated count
is a lower bound. These are reporting bounds, not gameplay send-rate limits or
a guarantee that every distinct failure will be logged.

## Domain admission and lifecycle

A domain that owns a transition, heartbeat, once-only or accounting-cycle gate
uses that gate as its frequency admission, without a second general diagnostic
window or capacity gate. Level checks, lazy detail, bounded formatting and
output isolation still apply. An ungated retry site cannot use this route to
bypass frequency control. Domain-specific cadence and reset conditions remain
with the owning topic, including
[budget-delay monitoring](presentation/shared_sources.md#budget-delay-monitoring).

Observing a domain outcome is distinct from consuming a report opportunity.
State observation may occur with DEBUG disabled, but it must not consume the
later WARN transition's reporting allowance. A disabled report does not justify
formatting detail merely to update diagnostic state. Recovery, removal,
teardown and server or client lifetime changes clear or advance domain state
only under that domain's lifecycle contract, not by inventing a logging retry.

Retained admission state consists of bounded detached keys, counters and timing
or transition facts. It does not retain exception instances, target or world
objects, provider handles or deferred context between reports. Attempt-local
access to permitted context does not authorize keeping those objects alive
after the reporting call.

## Lazy, bounded reports

Disabled levels do not evaluate detailed context or exception accessors.
Suppressed reports do not format detailed context or exception graphs; enabled
general admission may inspect the bounded fingerprint needed to make its
decision. Admitted context is evaluated at most once, after admission and
outside the shared reporter's general-window locks. A domain gate retains its
own synchronization boundary. A recoverable context failure leaves an explicit
unavailable marker rather than replacing the original business failure.

Report context is detached scalar text. One bounded report contains its envelope,
prefix, context and permitted exception detail. The exception graph includes
original messages, types, source frames, causes and suppressed relationships
within finite text, node, depth and frame bounds. Separate context and message
bounds leave room for source frames.
Explicit truncation, cycle and unavailable markers distinguish omitted or
inaccessible detail from an absent failure. Completeness means the permitted
bounded graph, not a promise to reach an arbitrarily deep root cause.

Scalar control characters are escaped recognizably while report structure keeps
readable line breaks. Exception formatting avoids localized-message and generic
throwable string conversion; hostile accessors have defensive fallbacks. Submit
the formatted message once, without also attaching the exception for a second,
unbounded logger expansion.

Lazy evaluation and final-text bounds do not certify arbitrary context suppliers'
elapsed time, internal work or allocations. They grant no extra source-read,
capture or payload-traversal authority beyond the owning domain's resource
rules. Native codec error suppliers may therefore be left unevaluated in favor
of a caller-owned failure stage; the encoding trust boundary belongs to
[inventory](presentation/inventory.md#component-classification-and-encoding-trust).

## Permitted detail

Bounded, frequency-controlled optional-integration and rendering reports may
retain complete target, position, registry, class, material, component and payload
detail without redaction, including permitted original exception relationships.
Frequency control must not be replaced by removing useful diagnostic information.

Restricted capture-coordination diagnostics instead use a scalar allowlist:
interaction token sequence, target kind, dimension ID, resolved Target Type ID
when available, and the exception's simple class name for a failure. Established
special-identity status may describe the locator strategy and whether multipart
canonicalization occurred, not the locator value or entity identity. UUIDs,
block/entity registry IDs, positions, names, item data, chat and target payloads
are not disclosed at this boundary. Neither exception messages, source frames,
causes nor suppressed details may bypass the restriction through another logging
layer. Increasing severity does not widen disclosure. Other domains may impose
stricter context rules, such as the detached, content-free summaries owned by
[budget-delay monitoring](presentation/shared_sources.md#budget-delay-monitoring).

## Output isolation and evidence

Recoverable reporting failures, including runtime or linkage failures in context
or output, stay local without recursively logging another diagnostic. Suppression
or such an output failure does not change authority, accounting, fallback, retry,
publication or marker lifetime. An admitted attempt may consume its report
opportunity even when the sink fails; reporting promises neither reliable
delivery nor automatic replay. Logging does not enlarge a business boundary's
recoverable exception set or establish a blanket catch-all policy.

The [shared diagnostics evidence](../testing/verification/integrations.md#shared-exception-diagnostics)
owns formatter, admission and backend coverage and deployment limitations.
Domain evidence remains with the
[restricted capture seams](../testing/verification/interaction.md#capture-wheel-and-cancellation),
[presentation](../testing/verification/presentation.md),
[inventory](../testing/verification/inventory.md) and
[optional integrations and rendering](../testing/verification/integrations.md).
These inventories do not establish deployed appender behavior or completion of
the [pending live scenarios](../testing/verification/manual-integration.md#pending-manual-and-integration-matrix).
