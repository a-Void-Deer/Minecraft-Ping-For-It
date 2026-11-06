package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.function.Function;

/** Owns only the three exact PingForIt logger levels; never appender/filter policy. */
@NotNullByDefault
final class Log4jDiagnosticBackend implements DiagnosticBackend {
	private final Function<String, Logger> lookup;
	private final Object setupLock = new Object();
	private @Nullable LoggerContext observed;
	private final ThreadLocal<Boolean> publishing = ThreadLocal.withInitial(() -> false);
	private final PropertyChangeListener listener = event -> {
		if (!LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())) return;
		// updateLoggers publishes logger private configurations before notifying
		// listeners. Only this thread's recursive notification can be skipped.
		if (publishing.get()) return;
		try {
			prepare(DiagnosticSide.BOOTSTRAP);
		} catch (RuntimeException | LinkageError failure) {
			Diagnostics.faults().report(DiagnosticSide.BOOTSTRAP, "backend-reconfigure",
				DiagnosticFaultReporter.Stage.SETUP, Diagnostics.profile(DiagnosticSide.BOOTSTRAP), failure);
		}
	};

	Log4jDiagnosticBackend() {
		this(LogManager::getLogger);
	}

	Log4jDiagnosticBackend(Function<String, Logger> lookup) {
		this.lookup = Objects.requireNonNull(lookup, "lookup");
	}

	@Override
	public void prepare(DiagnosticSide side) {
		Logger logger = logger(side);
		if (!(logger instanceof org.apache.logging.log4j.core.Logger core)) return;
		LoggerContext context = core.getContext();
		// The small registration lock never encloses Configuration mutation,
		// updateLoggers or any user/property-change callback.
		synchronized (setupLock) {
			if (observed != context) {
				if (observed != null) observed.removePropertyChangeListener(listener);
				context.addPropertyChangeListener(listener);
				observed = context;
			}
		}
		while (true) {
			Configuration configuration = context.getConfiguration();
			boolean changed;
			// addLogger itself uses this monitor in Core 2.22.1. No backend lock
			// or callback-producing updateLoggers is held inside this boundary.
			synchronized (configuration) {
				changed = false;
				for (DiagnosticSide owned : DiagnosticSide.values()) {
					String name = name(owned);
					@Nullable LoggerConfig exact = configuration.getLoggers().get(name);
					if (exact == null) {
						configuration.addLogger(name, new LoggerConfig(name, Level.DEBUG, true));
						changed = true;
					} else if (exact.getExplicitLevel() != Level.DEBUG) {
						exact.setLevel(Level.DEBUG);
						changed = true;
					}
				}
			}
			if (configuration != context.getConfiguration()) continue;
			@Nullable LoggerConfig exact = configuration.getLoggers().get(core.getName());
			if (changed || core.get() != exact || core.getLevel() != Level.DEBUG) {
				boolean nested = publishing.get();
				publishing.set(true);
				try {
					// No locks of ours: another prepare/reconfigure can finish even
					// while a foreign listener blocks this publishing thread.
					context.updateLoggers();
				} finally {
					if (nested) publishing.set(true); else publishing.remove();
				}
			}
			// A competing reconfigure/update may have published another generation.
			// Never report ready merely because another caller installed the nodes.
			if (configuration == context.getConfiguration() && exact != null
				&& exact.getExplicitLevel() == Level.DEBUG && core.get() == exact && core.getLevel() == Level.DEBUG) return;
		}
	}

	@Override
	public boolean isEnabled(DiagnosticSide side, Level severity) {
		return logger(side).isEnabled(severity);
	}

	@Override
	public void log(DiagnosticSide side, Level severity, String message) {
		logger(side).log(severity, message);
	}

	private Logger logger(DiagnosticSide side) {
		// No failure-poisoned static holder; a failed lookup is retried next attempt.
		return lookup.apply(name(side));
	}

	static String name(DiagnosticSide side) {
		return switch (side) {
			case CLIENT -> "pingforit.client";
			case SERVER -> "pingforit.server";
			case BOOTSTRAP -> "pingforit.bootstrap";
		};
	}
}
