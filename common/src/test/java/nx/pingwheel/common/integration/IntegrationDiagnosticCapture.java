package nx.pingwheel.common.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticBackend;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

/** Test-appender capture with scoped explicit backend, profiles and owned channel state. */
@NotNullByDefault
public final class IntegrationDiagnosticCapture implements AutoCloseable {
	private final Logger logger = (Logger) LogManager.getLogger("pingforit");
	private final LoggerContext context = logger.getContext();
	private final Configuration configuration = context.getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final List<LogEvent> events = new ArrayList<>();
	private final DiagnosticBackend previousBackend;
	private final EnumMap<DiagnosticSide, LoggingProfile> profiles = new EnumMap<>(DiagnosticSide.class);
	private final List<SavedField> channelFields = new ArrayList<>();
	private boolean closed;
	private final AbstractAppender appender = new AbstractAppender(
		"integration-diagnostics-test", null, null, true, Property.EMPTY_ARRAY) {
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	};

	public IntegrationDiagnosticCapture(Level level) { this(level, LoggingProfile.DEEP); }

	public IntegrationDiagnosticCapture(Level level, LoggingProfile profile) {
		appender.start();
		LoggerConfig config = new LoggerConfig(logger.getName(), level, false);
		config.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName());
		configuration.addLogger(logger.getName(), config);
		context.updateLoggers();
		previousBackend = swapBackend(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return logger.isEnabled(severity); }
			@Override public void log(DiagnosticSide side, Level severity, String message) { logger.log(severity, message); }
		});
		try {
			for (DiagnosticSide side : DiagnosticSide.values()) {
				profiles.put(side, Diagnostics.profile(side));
				Diagnostics.setProfile(side, profile);
			}
			for (String owner : CHANNEL_OWNERS) resetChannels(Class.forName("nx.pingwheel.common." + owner,
				false, IntegrationDiagnosticCapture.class.getClassLoader()));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
			close();
			throw new AssertionError("Integration diagnostic test scope unavailable", failure);
		}
	}

	public @UnmodifiableView List<LogEvent> events() { return java.util.Collections.unmodifiableList(events); }

	public void level(Level level) {
		configuration.getLoggerConfig(logger.getName()).setLevel(level);
		context.updateLoggers();
	}

	@Override public void close() {
		if (closed) return;
		closed = true;
		try {
			for (SavedField saved : channelFields) saved.field.set(saved.owner, saved.value);
		} catch (IllegalAccessException failure) {
			throw new AssertionError("Integration diagnostic channel restore failed", failure);
		} finally {
			try {
				swapBackend(previousBackend);
			} finally {
				profiles.forEach(Diagnostics::setProfile);
				configuration.removeLogger(logger.getName());
				if (previous != null) configuration.addLogger(logger.getName(), previous);
				try { context.updateLoggers(); }
				finally { appender.stop(); }
			}
		}
	}

	private static DiagnosticBackend swapBackend(DiagnosticBackend replacement) {
		try {
			Method swap = Diagnostics.class.getDeclaredMethod("swapBackendForTesting", DiagnosticBackend.class);
			swap.setAccessible(true);
			return (DiagnosticBackend) swap.invoke(null, replacement);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("Integration diagnostic backend seam unavailable", failure);
		}
	}

	private void resetChannels(Class<?> owner) throws ReflectiveOperationException {
		for (Field field : owner.getDeclaredFields()) {
			if (field.getType() != DiagnosticChannel.class) continue;
			field.setAccessible(true);
			if (Modifier.isStatic(field.getModifiers())) resetChannel((DiagnosticChannel) field.get(null));
			else if (owner.isEnum()) for (Object constant : owner.getEnumConstants()) resetChannel((DiagnosticChannel) field.get(constant));
		}
		for (Class<?> nested : owner.getDeclaredClasses()) resetChannels(nested);
	}

	private void resetChannel(DiagnosticChannel channel) throws ReflectiveOperationException {
		for (String name : List.of("client", "server", "bootstrap")) {
			Field sideField = DiagnosticChannel.class.getDeclaredField(name);
			sideField.setAccessible(true);
			Object state = java.util.Objects.requireNonNull(sideField.get(channel));
			for (Field field : state.getClass().getDeclaredFields()) {
				field.setAccessible(true);
				channelFields.add(new SavedField(state, field, field.get(state)));
				if (field.getType() == boolean.class) field.setBoolean(state, false);
				else field.setLong(state, 0L);
			}
		}
	}

	@NotNullByDefault
	private record SavedField(Object owner, Field field, @Nullable Object value) {}

	private static final @Unmodifiable List<String> CHANNEL_OWNERS = List.of(
		"integration.sable.SableDiagnostics", "integration.DistantHorizonsDiagnosticChannels",
		"integration.externalblock.ExternalBlockServerProviderRegistry", "integration.externalblock.ResolvedBlockReadSource",
		"integration.externalblock.ExternalBlockReferenceIndex", "client.outline.BlockPresentationResolverRegistry",
		"client.outline.VirtualBlockDisplayRenderer", "client.outline.LevelRendererOutlineRequest");
}
