package nx.pingwheel.common.integration;

import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;

/** Optional-API-free channel ownership, also safe for headless diagnostic scopes. */
@NotNullByDefault
final class DistantHorizonsDiagnosticChannels {
	static final DiagnosticChannel SCHEDULE_FAILURES = DiagnosticChannel.interval(
		"distanthorizons/schedule", Duration.ofSeconds(10));
	static final DiagnosticChannel TRACE_FAILURES = DiagnosticChannel.interval(
		"distanthorizons/trace", Duration.ofSeconds(10));

	private DistantHorizonsDiagnosticChannels() {}
}
