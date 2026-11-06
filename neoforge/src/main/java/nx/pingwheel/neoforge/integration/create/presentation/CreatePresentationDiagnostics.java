package nx.pingwheel.neoforge.integration.create.presentation;

import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/** Create-free explicit retry channels; no source, world or deferred detail is retained. */
@NotNullByDefault
public final class CreatePresentationDiagnostics {
	private static final @Unmodifiable Map<String, DiagnosticChannel> CHANNELS = Map.ofEntries(
		entry("create/presentation-capture"), entry("create/presentation-cached-stress"),
		entry("create/presentation-cached-capacity"), entry("create/presentation-cached-bridge"),
		entry("create/presentation-kinetic"), entry("create/presentation-vault-slot"),
		entry("create/presentation-vault"), entry("create/presentation-tank-entry"),
		entry("create/presentation-tank"), entry("create/presentation-member-gate"),
		entry("create/presentation-source"), entry("create/preview-network"),
		entry("create/preview-cached-access"), entry("create/preview-cached-stress"),
		entry("create/preview-cached-capacity"), entry("create/preview-field"),
		entry("inventory-vault-stacks-read"), entry("inventory-vault-find"),
		entry("inventory-vault-valid"), entry("inventory-vault-revalidate"));

	private CreatePresentationDiagnostics() {}

	private static Map.Entry<String, DiagnosticChannel> entry(String site) {
		return Map.entry(site, DiagnosticChannel.interval(site, Duration.ofSeconds(10)));
	}

	public static void failure(DiagnosticLogger diagnostics, String site, String handling,
		@Nullable Supplier<@Nullable String> detail, Throwable failure) {
		failure(diagnostics, CHANNELS.get(site), site, handling, detail, failure);
	}

	static void failure(DiagnosticLogger diagnostics, @Nullable DiagnosticChannel channel, String site, String handling,
		@Nullable Supplier<@Nullable String> detail, Throwable failure) {
		DiagnosticLogger selected = diagnostics.atNormal();
		if (channel != null) selected = selected.rateLimited(channel);
		selected.failure(Level.WARN, site, handling, detail, failure);
	}
}
