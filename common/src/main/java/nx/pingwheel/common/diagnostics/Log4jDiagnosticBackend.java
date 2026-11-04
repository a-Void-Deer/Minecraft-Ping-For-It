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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Owns only the three exact PingForIt logger levels; never appender/filter policy. */
@NotNullByDefault
final class Log4jDiagnosticBackend implements DiagnosticBackend {
	private final Function<String, Logger> lookup;
	private final Object setupLock = new Object();
	private @Nullable LoggerContext observed;
	private final AtomicBoolean updating = new AtomicBoolean();
	private final PropertyChangeListener listener = event -> {
		if (!LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())) return;
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
		// A reconfigure thread can own Log4j's configuration lock while invoking
		// this listener. Never make it wait for our setup lock while another
		// thread is inside updateLoggers or a foreign property-change listener.
		if (!updating.compareAndSet(false, true)) return;
		try {
			synchronized (setupLock) {
				LoggerContext context = core.getContext();
				if (observed != context) {
					if (observed != null) observed.removePropertyChangeListener(listener);
					context.addPropertyChangeListener(listener);
					observed = context;
				}
				Configuration configuration = context.getConfiguration();
				boolean changed = false;
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
				if (changed) context.updateLoggers();
			}
		} finally {
			updating.set(false);
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
