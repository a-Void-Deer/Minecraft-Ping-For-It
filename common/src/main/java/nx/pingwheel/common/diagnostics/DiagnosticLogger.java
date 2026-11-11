package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable report selection. Profiles decide eligibility and disclosure,
 * severity selects a backend level, and only an explicit channel limits cadence.
 */
@NotNullByDefault
public final class DiagnosticLogger {
	private static final ThreadLocal<@Nullable Attempt> REPORTING = new ThreadLocal<>();
	private final DiagnosticSide side;
	private final LoggingProfile minimum;
	private final @Nullable DiagnosticChannel channel;
	private final String site;

	DiagnosticLogger(DiagnosticSide side, LoggingProfile minimum, @Nullable DiagnosticChannel channel, String site) {
		this.side = side;
		this.minimum = minimum;
		this.channel = channel;
		this.site = site;
	}

	public DiagnosticLogger atNormal() { return selection(LoggingProfile.NORMAL); }
	public DiagnosticLogger atDetailed() { return selection(LoggingProfile.DEEP); }
	public DiagnosticLogger atDebug() { return selection(LoggingProfile.DEBUG); }

	private DiagnosticLogger selection(LoggingProfile profile) {
		return new DiagnosticLogger(side, profile, channel, site);
	}

	public DiagnosticLogger rateLimited(DiagnosticChannel explicitChannel) {
		return new DiagnosticLogger(side, minimum, Objects.requireNonNull(explicitChannel, "channel"), site);
	}

	/** Stable caller-owned site code; never provider text or a per-request identity. */
	public DiagnosticLogger site(String stableSite) {
		return new DiagnosticLogger(side, minimum, channel, DiagnosticText.controlled(stableSite));
	}

	/** A hint only: never touches a channel or consumes an admission. */
	public boolean isEnabled(Level severity) {
		return attempt(severity, false, true, null, null, null, null, null, false, null, null, null);
	}

	/**
	 * One profile-aware report. The terminal takes one effective-profile snapshot,
	 * checks the selected minimum and backend, and then applies the explicit
	 * channel below DEBUG. When {@code everyOccurrenceAtDebug} is set, a DEBUG
	 * report bypasses an interval channel without consuming admission, but never
	 * bypasses a domain channel. NORMAL never evaluates detail. DEEP and DEBUG
	 * receive the effective profile for precision selection. The stable summary is
	 * a fixed template with audited safe scalars and remains present at every
	 * profile, including when the detail supplier fails. The original throwable is
	 * rendered only by the shared bounded formatter.
	 */
	public boolean reportByProfile(Level severity, String stableSite, String handling,
		@Nullable NormalSummary normalSummary, @Nullable ProfileDetail detail, @Nullable Throwable failure,
		boolean everyOccurrenceAtDebug) {
		return site(stableSite).attempt(severity, false, false, null, null,
			DiagnosticText.controlled(handling), normalSummary, detail, everyOccurrenceAtDebug,
			null, failure, null);
	}

	/** Defaults to cadence-limited reporting at every profile. */
	public boolean reportByProfile(Level severity, String stableSite, String handling,
		@Nullable NormalSummary normalSummary, @Nullable ProfileDetail detail, @Nullable Throwable failure) {
		return reportByProfile(severity, stableSite, handling, normalSummary, detail, failure, false);
	}

	public void debug(String template, SafeDiagnosticValue @Unmodifiable ... values) { event(Level.DEBUG, template, values); }
	public void info(String template, SafeDiagnosticValue @Unmodifiable ... values) { event(Level.INFO, template, values); }
	public void warn(String template, SafeDiagnosticValue @Unmodifiable ... values) { event(Level.WARN, template, values); }
	public void error(String template, SafeDiagnosticValue @Unmodifiable ... values) { event(Level.ERROR, template, values); }

	/** Free-form detail suppliers require an effective DEEP/DEBUG profile, even on atNormal(). */
	public void debug(Supplier<@Nullable String> detail) { detail(Level.DEBUG, detail); }
	public void info(Supplier<@Nullable String> detail) { detail(Level.INFO, detail); }
	public void warn(Supplier<@Nullable String> detail) { detail(Level.WARN, detail); }
	public void error(Supplier<@Nullable String> detail) { detail(Level.ERROR, detail); }

	public void debug(String stableSite, String handling, @Nullable Throwable failure,
		@Nullable Supplier<@Nullable String> detail) { failure(Level.DEBUG, stableSite, handling, detail, failure); }
	public void info(String stableSite, String handling, @Nullable Throwable failure,
		@Nullable Supplier<@Nullable String> detail) { failure(Level.INFO, stableSite, handling, detail, failure); }
	public void warn(String stableSite, String handling, @Nullable Throwable failure,
		@Nullable Supplier<@Nullable String> detail) { failure(Level.WARN, stableSite, handling, detail, failure); }
	public void error(String stableSite, String handling, @Nullable Throwable failure,
		@Nullable Supplier<@Nullable String> detail) { failure(Level.ERROR, stableSite, handling, detail, failure); }

	public void debug(String stableSite, String handling, @Nullable Throwable failure) { debug(stableSite, handling, failure, null); }
	public void info(String stableSite, String handling, @Nullable Throwable failure) { info(stableSite, handling, failure, null); }
	public void warn(String stableSite, String handling, @Nullable Throwable failure) { warn(stableSite, handling, failure, null); }
	public void error(String stableSite, String handling, @Nullable Throwable failure) { error(stableSite, handling, failure, null); }

	/** Template must be caller-controlled prose, not provider/request text. Only explicit safe scalars interpolate. */
	public void event(Level severity, String template, SafeDiagnosticValue @Unmodifiable ... values) {
		attempt(severity, false, false, template, values, null, null, null, false, null, null, null);
	}

	public void detail(Level severity, Supplier<@Nullable String> detail) {
		attempt(severity, true, false, null, null, null, null, null, false, Objects.requireNonNull(detail, "detail"), null, null);
	}

	/**
	 * One failure report, not a normal+deep pair. NORMAL contains only the stable
	 * site/handling and a class token; the CURRENT policy (not minimum) permits
	 * lazy context and the bounded original exception graph at higher profiles.
	 */
	public void failure(Level severity, String stableSite, String handling,
		@Nullable Supplier<@Nullable String> detail, @Nullable Throwable failure) {
		DiagnosticLogger selected = site(stableSite);
		selected.attempt(severity, false, false, null, null, DiagnosticText.controlled(handling), null, null, false,
			detail, failure, null);
	}

	/** Scalar-only counterpart; raw provider reasons belong only in the lazy detail. */
	public void event(Level severity, String stableSite, String safeReason, @Nullable Supplier<@Nullable String> detail) {
		DiagnosticLogger selected = site(stableSite);
		selected.attempt(severity, false, false, null, null, DiagnosticText.controlled(safeReason), null, null, false,
			detail, null, null);
	}

	/**
	 * Runs an existing domain gate after profile eligibility but before backend
	 * admission, under the same report's policy snapshot. A null descriptor denies
	 * the report. Backend suppression/failure does not refund the domain gate.
	 * The callback performs domain admission only and returns lazy diagnostic
	 * detail; neither callback nor descriptor is retained or replayed. This route
	 * cannot be combined with a channel or a second admission policy.
	 */
	public void domainBeforeBackend(Level severity, String stableSite, Supplier<@Nullable DomainReport> admission) {
		site(stableSite).attempt(severity, false, false, null, null, null, null, null, false, null, null,
			Objects.requireNonNull(admission, "admission"));
	}

	/** Attempt-local result of an existing domain gate, never a public admission permit. */
	@NotNullByDefault
	public record DomainReport(String handling, @Nullable Throwable failure, @Nullable Supplier<@Nullable String> detail) {}

	/**
	 * Fixed caller-owned summary template and finite audited scalars, shown at
	 * every profile. The template is fixed prose; provider, request or other
	 * arbitrary text is not a permitted summary value.
	 */
	@NotNullByDefault
	public record NormalSummary(String template, SafeDiagnosticValue @Unmodifiable [] values) {
		public NormalSummary {
			Objects.requireNonNull(template, "template");
			Objects.requireNonNull(values, "values");
		}
	}

	/** Profile-aware lazy detail for {@link #reportByProfile}; NORMAL never invokes it. */
	@NotNullByDefault
	@FunctionalInterface
	public interface ProfileDetail {
		@Nullable String format(LoggingProfile profile);
	}

	private boolean attempt(Level severity, boolean detailOnly, boolean hint, @Nullable String template,
		@Nullable SafeDiagnosticValue @Nullable @Unmodifiable [] values, @Nullable String handling,
		@Nullable NormalSummary normalSummary, @Nullable ProfileDetail profileDetail, boolean everyOccurrenceAtDebug,
		@Nullable Supplier<@Nullable String> detail, @Nullable Throwable failure,
		@Nullable Supplier<@Nullable DomainReport> domainAdmission) {
		@Nullable Attempt outer = REPORTING.get();
		if (outer != null) {
			// A clock/domain gate/backend callback can reenter while holding its
			// own lock. Defer the descriptor write until the outer terminal has
			// returned from all of those callbacks and released channel locks.
			Reentry key = new Reentry(side, site, Diagnostics.profile(side));
			outer.record(key);
			return false;
		}
		Attempt attempt = new Attempt();
		REPORTING.set(attempt);
		LoggingProfile effective = Diagnostics.profile(side);
		DiagnosticFaultReporter.Stage stage = DiagnosticFaultReporter.Stage.SETUP;
		try {
			if (!effective.includes(minimum) || (detailOnly && effective == LoggingProfile.NORMAL)) return false;
			if (domainAdmission != null) {
				stage = DiagnosticFaultReporter.Stage.ADMISSION;
				if (channel != null) throw new IllegalStateException("Domain-before-backend reports cannot have a channel");
				@Nullable DomainReport report = domainAdmission.get();
				if (report == null) return false;
				handling = DiagnosticText.controlled(report.handling());
				failure = report.failure();
				detail = report.detail();
			}
			stage = DiagnosticFaultReporter.Stage.SETUP;
			DiagnosticBackend backend = Diagnostics.backend();
			backend.prepare(side);
			stage = DiagnosticFaultReporter.Stage.ENABLED;
			if (!backend.isEnabled(side, severity)) return false;
			if (hint) return true;
			boolean bypassedInterval = channel != null && channel.isInterval()
				&& everyOccurrenceAtDebug && effective == LoggingProfile.DEBUG;
			long suppressed = 0;
			if (channel != null && !bypassedInterval) {
				stage = DiagnosticFaultReporter.Stage.ADMISSION;
				DiagnosticChannel.Admission admission = channel.admit(side);
				if (!admission.allowed()) return false;
				suppressed = admission.suppressed();
			}
			String header = Diagnostics.PREFIX + "side=" + side.name() + " site=" + site
				+ (handling == null ? "" : " handling=" + handling)
				+ (channel == null ? "" : bypassedInterval
					? " channel=" + channel.name() + " bypass=debug-every-occurrence"
					: " channel=" + channel.name() + " suppressed=" + suppressed);
			String body;
			stage = DiagnosticFaultReporter.Stage.FORMAT;
			@Nullable String summary = normalSummary == null ? null
				: renderTemplate(normalSummary.template(), normalSummary.values());
			if (template != null) {
				body = template(template, values);
			} else if (effective == LoggingProfile.NORMAL) {
				if (summary == null) {
					body = failure == null ? "event" : "failure=" + NormalFailureType.token(failure);
				} else {
					body = DiagnosticText.scalar(summary, SafeExceptionReport.MAX_CONTEXT_CHARACTERS)
						+ (failure == null ? "" : " failure=" + NormalFailureType.token(failure));
				}
			} else {
				@Nullable String context = null;
				try {
					if (profileDetail != null) context = profileDetail.format(effective);
					else if (detail != null) context = detail.get();
				} catch (RuntimeException | LinkageError contextFailure) {
					Diagnostics.faults().report(side, site, DiagnosticFaultReporter.Stage.CONTEXT, effective, contextFailure);
					context = "[context unavailable]";
				}
				@Nullable String combined = combine(summary, context);
				if (failure == null) {
					body = DiagnosticText.scalar(combined, SafeExceptionReport.MAX_CONTEXT_CHARACTERS);
				} else {
					body = Diagnostics.formatter().format(combined, failure, (accessor, accessorFailure) ->
						Diagnostics.faults().report(side, site, DiagnosticFaultReporter.Stage.ACCESSOR, effective, accessorFailure));
				}
			}
			String message = DiagnosticText.bounded(header + "\n" + body, SafeExceptionReport.MAX_CHARACTERS);
			stage = DiagnosticFaultReporter.Stage.SINK;
			backend.log(side, severity, message);
			return true;
		} catch (DiagnosticChannel.ClockFailure clockFailure) {
			Diagnostics.faults().report(side, site, DiagnosticFaultReporter.Stage.CHANNEL_CLOCK, effective, clockFailure.original());
			return false;
		} catch (RuntimeException | LinkageError reportingFailure) {
			Diagnostics.faults().report(side, site, stage, effective, reportingFailure);
			return false;
		} finally {
			try {
				// Detached records only; never keep the business throwable or context.
				// Detach before output so a hostile injected writer cannot mutate the
				// collection being traversed or recursively flush an unbounded queue.
				@Nullable Map<Reentry, Long> pending = attempt.detach();
				if (pending != null) pending.forEach((reentry, count) -> Diagnostics.faults().reportOccurrences(reentry.side,
					reentry.site, DiagnosticFaultReporter.Stage.REENTRY, reentry.profile, null, count));
			} finally {
				REPORTING.remove();
			}
		}
	}

	@NotNullByDefault
	record Reentry(DiagnosticSide side, String site, LoggingProfile profile) {}

	@NotNullByDefault
	static final class Attempt {
		private @Nullable Map<Reentry, Long> reentries;

		void record(Reentry key) {
			if (reentries == null) reentries = new HashMap<>();
			reentries.merge(key, 1L, DiagnosticFaultReporter::add);
		}

		@Nullable Map<Reentry, Long> detach() {
			@Nullable Map<Reentry, Long> pending = reentries;
			reentries = null;
			return pending;
		}
	}

	private static String template(String template, @Nullable SafeDiagnosticValue @Nullable @Unmodifiable [] values) {
		return DiagnosticText.scalar(renderTemplate(template, values), SafeExceptionReport.MAX_CONTEXT_CHARACTERS);
	}

	private static String renderTemplate(String template,
		@Nullable SafeDiagnosticValue @Nullable @Unmodifiable [] values) {
		StringBuilder result = new StringBuilder();
		int argument = 0;
		int index = 0;
		for (; index < template.length() && result.length() < SafeExceptionReport.MAX_CONTEXT_CHARACTERS; index++) {
			if (template.charAt(index) == '{' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
				@Nullable SafeDiagnosticValue value = values == null || argument >= values.length ? null : values[argument];
				result.append(value == null ? "[unavailable]" : value.text());
				argument++;
				index++;
			} else {
				result.append(template.charAt(index));
			}
		}
		if (index < template.length()) result.append("[truncated]");
		return result.toString();
	}

	private static @Nullable String combine(@Nullable String summary, @Nullable String detail) {
		if (summary == null || summary.isEmpty()) return detail;
		if (detail == null || detail.isEmpty()) return summary;
		return summary + " " + detail;
	}
}
