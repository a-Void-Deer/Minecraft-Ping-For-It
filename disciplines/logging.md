# Logging guide for maintainers

This is the practical guide for adding, changing and removing a diagnostic
report site. [Diagnostics and logging](../docs/architecture/logging.md) remains
the normative owner of profiles, severity, admission, disclosure and output
isolation; security boundaries remain with
[security](../docs/architecture/security.md#diagnostic-detail-and-user-feedback).
Use the shared `Diagnostics` API, not a platform logger, and omit reports that do
not change understanding.

## Diagnostic completeness

A diagnostic report must explain an observed outcome, not merely restate its symptom.

For failures, unexpected rejections, degraded outcomes, and fallback paths, preserve the diagnostic evidence already available to the owning operation.

### Required diagnostic evidence

When applicable, a report must make the following distinguishable:

1. **Failure point:** The operation, stage, or predicate that rejected or failed.
2. **Observed reason:** The specific condition or failure category responsible for that decision, rather than a generic description such as `invalid`, `failed`, or `unavailable`.
3. **Recovery decision:** Why a fallback was selected, skipped, or abandoned.
4. **Recovery outcome:** Which fallback strategies were actually attempted, in what order when relevant, and why each failed or succeeded.
5. **Final outcome:** Whether the overall operation failed, recovered, degraded, or skipped optional functionality.

These are diagnostic requirements for the operation, not mandatory fields on every individual log entry. Omit inapplicable evidence and never invent explanations unsupported by the execution.

### Preserve decisions, not just symptoms

- Do not replace a known rejection reason with a generic failure message.
- If distinct branches reject for different reasons, preserve those distinctions using stable domain-defined reason codes.
- When multiple fallbacks exist, preserve their actual attempted outcomes, not merely the final fallback result.
- Distinguish a fallback that was not attempted from one that was attempted and failed.
- Preserve meaningful decision inputs and observed conditions when permitted, rather than inferring a root cause from the final outcome.
- An exception is evidence of a failure, not necessarily a complete explanation of the domain decision.

### Disclosure and collection

At `NORMAL`, expose diagnostically meaningful distinctions through fixed templates and audited, finite safe codes where the API permits. Do not expose provider-controlled data, identifiers, paths, positions, messages, or other prohibited values.

At `DEEP` and `DEBUG`, use lazy detail to preserve permitted decision context, attempted recovery steps, and failure evidence.

Capture diagnostic facts from the business operation as it executes. Do not initiate new reads, repeat validation, expand source authority, or change fallback behavior for logging.

Preserve the existing one-report ownership and admission policies. Diagnostic completeness does not justify duplicate reports or an additional `NORMAL`/`DEEP` pair.

### Review requirement

For every modified failure or recovery path, ask:

**Could a maintainer reading the intended logging profile distinguish what failed, why the operation made its recovery decisions, what recovery actually attempted, why it did or did not work, and what happened finally?**

If the answer is no, the diagnostic implementation is incomplete unless the missing evidence is unavailable, inapplicable, prohibited by disclosure policy, or intentionally deferred to a higher profile.

Tests should verify representative failure and recovery outcomes, their permitted diagnostic evidence, and the absence of logging-induced behavior changes.

## Quick start

These examples use business facts already owned by the enclosing operation.

### A safe, NORMAL-eligible event

```java
// The template and codes belong to this subsystem's finite vocabulary.
Diagnostics.client().atNormal().error(
    "Target render failed fallback={} reason={}",
    SafeDiagnosticValue.code("VoxelShape"),
    SafeDiagnosticValue.code("render-exception"));
```

`atNormal()` sets minimum `NORMAL`, not `NORMAL`-only disclosure; the effective
profile still decides detail. The fixed template interpolates only explicit
`SafeDiagnosticValue` scalars. `code` checks shape and length, not meaning: pass
a finite caller allowlist, not provider text, names, UUIDs, paths or source
frames; `number` can still be a coordinate or identity.

### A consumed failure with lazy detail

```java
// requestId, pingType and recipients are existing business facts.
Diagnostics.server().atNormal().failure(Level.WARN, "marker-create", "store-rejected",
    () -> "request=" + requestId + " kind=" + pingType.id() + " recipients=" + recipients,
    failure);
```

At `NORMAL` this emits side, site and handling plus a bounded failure-class token
without calling the supplier or exception accessors. Under `DEEP`/`DEBUG` the
same report adds permitted context and the bounded original graph; do not add a
second `.atDetailed()` copy. Prefer the shorthand `warn(site, handling,
failure[, detail])` and its `debug`/`info`/`error` equivalents; generic `failure`
takes detail before the throwable.

### A DEEP- or DEBUG-only diagnostic

```java
Diagnostics.client().atDebug().event(Level.DEBUG,
    "target-name/decode", "malformed-json",
    () -> "payloadBytes=" + payload.length() + " locale=" + locale);
```

`.atDebug()` sets minimum `DEBUG`, so this is skipped at `NORMAL` and `DEEP`
before its supplier runs. The site/reason form puts the controlled reason in the
envelope and expands lazy detail only when effective disclosure permits. For
context with no stable failure, use `.atDetailed().detail(Level.DEBUG, () -> ...)`.
Do not use `.atNormal().detail(...)` or `.atNormal().warn(() -> ...)` for an error
that must appear in `NORMAL`; use a site/handling event or failure instead.

## Choose each dimension independently

| Dimension | API | Rule |
| --- | --- | --- |
| Logical side | `Diagnostics.client()`, `Diagnostics.server()`, `Diagnostics.bootstrap()` | Select the side that owns the operation; a known-side worker captures it, an integrated server stays `SERVER`, and bootstrapping is only for genuinely early work with no owner. Do not infer the side from the thread or nearest class. |
| Minimum profile | `.atNormal()`, `.atDetailed()`, `.atDebug()` | Eligibility only. A lower effective profile skips the whole call. |
| Backend severity | `debug`, `info`, `warn`, `error`, or an explicit `Level` | Assigned by the domain. A profile neither promotes nor demotes it. |

Minimum, severity and channel are independent decisions; none substitutes for
another. The effective profile controls the detail shown by each minimum:

| Effective profile | `.atNormal()` | `.atDetailed()` | `.atDebug()` |
| --- | --- | --- | --- |
| `NORMAL` | emits a `NORMAL` summary | skipped | skipped |
| `DEEP` | emits with `DEEP` detail | emits with `DEEP` detail | skipped |
| `DEBUG` | emits with `DEBUG` detail | emits with `DEBUG` detail | emits with `DEBUG` detail |

Keep severity tied to the domain outcome; do not raise `WARN` or `ERROR` for
visibility, because `NORMAL` already carries the assigned level. A failed
original render target remains `ERROR` even when a VoxelShape fallback draws,
while an expected absent optional capability is not promoted at `NORMAL`.

## Correct overloads

Selections are immutable; use the returned logger. `.site(...)` and
`.rateLimited(...)` preserve the other selections, so chain order is irrelevant.

| Use | Signature |
| --- | --- |
| Fixed template event with safe scalars | `debug`/`info`/`warn`/`error(String template, SafeDiagnosticValue... values)` |
| Lazy high-detail text only | `debug`/`info`/`warn`/`error(Supplier<@Nullable String> detail)` or `detail(Level, Supplier<@Nullable String>)` |
| Failure summary or summary plus lazy detail | `debug`/`info`/`warn`/`error(String site, String handling, @Nullable Throwable failure[, @Nullable Supplier<@Nullable String> detail])` |
| Generic failure | `failure(Level, String site, String handling, @Nullable Supplier<@Nullable String> detail, @Nullable Throwable failure)` |
| Site/reason event plus lazy detail | `event(Level, String site, String reason, @Nullable Supplier<@Nullable String> detail)` |
| Generic template event | `event(Level, String template, SafeDiagnosticValue... values)` |
| Existing inventory gate | `domainBeforeBackend(Level, String site, Supplier<@Nullable DomainReport>)` |

`isEnabled(Level)` is only a hint: it checks minimum eligibility and the backend
level, does not consume channel admission or evaluate suppliers, and cannot
predict that a supplier-only `NORMAL` site will be rejected. Argument
expressions, `SafeDiagnosticValue` factories and bound receiver method references
run before the terminal; malformed site codes or non-positive intervals can
throw. The pipeline protects its own callbacks, not arbitrary invalid API use.

## Lazy collection and the NORMAL disclosure audit

The lazy boundary starts where collection starts. Build the string inside the
supplier, or use a statement block that collects only after admission:

```java
// Wrong: describeGraph(...) runs before the logger can discard the report.
String summary = describeGraph(visibleRoot);
Diagnostics.client().atNormal().failure(Level.WARN, "inventory/read", "source-failed",
    () -> summary, failure);

// Right: collection starts only if this report is eligible and admitted.
Diagnostics.client().atNormal().failure(Level.WARN, "inventory/read", "source-failed",
    () -> describeGraph(visibleRoot), failure);
```

The admitted supplier runs at most once, synchronously, and is not retained.
"Attempt-local" does not widen source authority: a native codec error supplier
may walk NBT owned by the business operation, but a `DEBUG` report must not start
a new graph, file or provider read merely because detail is enabled. Mandatory
business work stays outside logging conditions; disabled logging must not change
control flow, fallback or cleanup.

Audit every `NORMAL` site for disclosure:

- Site, handling, reason and channel names are short stable codes assembled only
  from fixed text or finite enum/code values, never provider text, names, UUIDs,
  paths, positions or stack frames.
- A template is fixed prose with audited `SafeDiagnosticValue.number(long)`,
  `number(double)`, `bool` or `code` values. `code` checks shape, not meaning,
  and `number` does not make a coordinate safe.
- `NORMAL` failure detail is a stable class token, not a class name, message,
  stack, cause, suppressed graph or free-form context.
- Use one `failure` or `event` call for summary and higher-detail context; a
  below-minimum supplier is never evaluated, so there is no duplicate pair.
- An exception-free event is not a failure; do not fabricate a throwable.
- A higher profile does not authorize a new read, wider catch or different
  fallback ([security](../docs/architecture/security.md#diagnostic-detail-and-user-feedback)).

## Explicit admission channels

Add a channel only when the same operation can repeatedly produce the same
report and would otherwise flood the log. No channel means no clock, window or
fingerprint history; if the domain already has a gate, do not add a second.

```java
@NotNullByDefault
final class PresentationApplyDiagnostics {
    // This subsystem owns this cadence. There is no shared logging interval.
    private static final DiagnosticChannel APPLY_FAILURES =
        DiagnosticChannel.interval("presentation/apply", Duration.ofSeconds(10));

    static void applyFailed(DiagnosticLogger logger, long revision, Throwable failure) {
        logger.atNormal().rateLimited(APPLY_FAILURES).failure(Level.WARN,
            "presentation/apply", "apply-failed",
            () -> "revision=" + revision, failure);
    }
}
```

`interval(name, duration)` and its test-clock overload are interval channels;
`domain(name, admission)` preserves an existing gate without adding an interval.
A production channel is a stable field. Identity is object identity, while the
name is only a label: a new `interval(...)` per call loses history. The example
interval is this owner's cadence, not a project default; use the domain's
existing value. An interval channel controls repeated report admission within its
domain-owned interval; the
[explicit admission channels](../docs/architecture/logging.md#explicit-admission-channels)
contract owns first-report, suppression and saturated-count behavior. It keeps
independent state per logical side and shares that side's history across
severity and profile changes; a profile change does not reset it. A domain
channel runs the same captured callback for every side and does not partition
mutable callback state per side; the owning domain must key side-local state
itself. Split channels when outcomes need independent
suppression: a summary and its failure path, or separate offer and policy
outcomes, differ even with a shared site. Reuse intentionally shares history and
the suppressed count.
Do not capture a world, target, provider, payload or request in a long-lived
channel; it retains bounded counters and timing only, and domain resources are
released by the owning lifecycle.

## Domain gates and the inventory exception

A domain gate remains the gate; a channel only controls admission and never
replaces domain cadence, lifecycle, retry or recovery. Report at the consuming
boundary while the exception is available; do not repeat consumed failures.

`domainBeforeBackend` is not general-purpose and must not be chosen for a new
site. Only the three existing inventory terminals use it to preserve
backend-disabled opportunity semantics: profile snapshot first, existing gate,
backend preparation and severity, then context/failure formatting. Null denies;
backend-disabled output does not refund the gate; it cannot combine with a
channel. Follow the [report pipeline exception](../docs/architecture/logging.md#report-pipeline)
rather than copying a gate algorithm or adding a fourth helper.

## Profile publication and runtime ownership

Ordinary sites do not call `Diagnostics.setProfile`; configuration handlers own
load, reset, recovery and save publication. The client's immediate publication and
the server draft are owned by
[logging profile controls](../docs/UI/settings-screen.md#logging-profile-controls),
and the server-side apply and persistence transaction is owned by
[server apply order and persistence](../docs/architecture/config/changing-server-config.md#server-apply-order-and-persistence).
Fields and values remain with
[client configuration](../docs/config/client.md#diagnostic-logging) and
[server configuration](../docs/config/server.md#persisted-fields).

`BOOTSTRAP` is only for genuinely early work with no owner; it is fixed `NORMAL`,
not a third setting, and must not be used to avoid choosing a side or set a profile.

## Self-fault reporting

The terminal handles recoverable supplier, accessor, clock, formatting and sink
failures. Use `Diagnostics.faults().report(side, controlledSite, stage, profile,
@Nullable Throwable failure)` only in a pure helper that owns its own catch or
sink. Pass a stable site, a `DiagnosticFaultReporter.Stage` (`SETUP`, `ENABLED`,
`CHANNEL_CLOCK`, `ADMISSION`, `CONTEXT`, `ACCESSOR`, `FORMAT`, `SINK`, `REENTRY`)
and the `Diagnostics.profile(side)` snapshot. Business failures use `failure`, never
here; one throwable must not use both routes; run sinks after releasing monitors.

Self-fault output is a separate best-effort record over the standard-error file
descriptor; it does not use the main logger and is not guaranteed to appear in
`latest.log`. External appender thresholds, asynchronous backend failures and
swallowed failures stay outside this path. Higher profiles remain bounded class
and message text with no stack, cause or throwable string conversion.

## Checklist for a new or changed site

- [ ] Minimum times every effective profile gives the intended result; severity
      was chosen independently from disclosure.
- [ ] The side is explicit; an integrated server uses `SERVER`, and shared
      helpers receive an injected `DiagnosticLogger`.
- [ ] A `NORMAL`-required outcome uses an event or failure with a stable summary.
- [ ] Disabled, below-minimum and suppressed reports run no supplier or accessor;
      collection starts inside the supplier and gains no new source read.
- [ ] `NORMAL` output has only fixed prose and audited scalars, with no provider
      text, name, UUID, path or source frame, and no fabricated throwable.
- [ ] Any channel is a stable field with intentional identity, per-side state,
      cross-severity history and suppression; an existing gate or recovery path
      is not duplicated.
- [ ] A pure helper reports self-faults once; business errors use `failure`, and
      logging cannot change authority, cleanup, lifetime or other domain behavior.

Implementation references: [DiagnosticLogger](../common/src/main/java/nx/pingwheel/common/diagnostics/DiagnosticLogger.java),
[DiagnosticChannel](../common/src/main/java/nx/pingwheel/common/diagnostics/DiagnosticChannel.java)
and [SafeDiagnosticValue](../common/src/main/java/nx/pingwheel/common/diagnostics/SafeDiagnosticValue.java);
evidence limits are in [shared exception diagnostics](../docs/testing/verification/integrations.md#shared-exception-diagnostics).
