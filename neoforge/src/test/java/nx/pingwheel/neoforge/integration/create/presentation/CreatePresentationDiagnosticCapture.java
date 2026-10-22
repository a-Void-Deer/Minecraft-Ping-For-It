package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.LongSupplier;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** NeoForge test capture; the common test source set is absent from this classpath. */
@NotNullByDefault
public final class CreatePresentationDiagnosticCapture implements AutoCloseable {
	private final Logger logger = (Logger) Diagnostics.logger();
	private final Configuration configuration = logger.getContext().getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final RecordingAppender appender = new RecordingAppender();
	private final LimiterScope limiter = LimiterScope.install();
	public CreatePresentationDiagnosticCapture() {
		appender.start();
		var config = new LoggerConfig(logger.getName(), Level.WARN, false);
		config.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName()); configuration.addLogger(logger.getName(), config);
		logger.getContext().updateLoggers();
	}
	public @Unmodifiable List<LogEvent> events() { return List.copyOf(appender.events); }
	public @Unmodifiable List<String> messages() { return appender.events.stream().map(event -> event.getMessage().getFormattedMessage()).toList(); }
	@Override public void close() {
		limiter.close();
		configuration.removeLogger(logger.getName());
		if (previous != null) configuration.addLogger(logger.getName(), previous);
		logger.getContext().updateLoggers(); appender.stop();
	}
	/** The common test source set is not published to NeoForge; restore the same production-only test hook reflectively. */
	@NotNullByDefault
	private record LimiterScope(Method swap, Object previous) implements AutoCloseable {
		private static LimiterScope install() {
			try {
				Class<?> reporter = Class.forName("nx.pingwheel.common.diagnostics.DiagnosticReporter");
				Class<?> sink = Class.forName("nx.pingwheel.common.diagnostics.DiagnosticReporter$Sink");
				var constructor = reporter.getDeclaredConstructor(LongSupplier.class, sink);
				constructor.setAccessible(true);
				Object loggerSink = Proxy.newProxyInstance(sink.getClassLoader(), new Class<?>[] {sink}, LimiterScope::invokeSink);
				Object fresh = constructor.newInstance((LongSupplier) System::nanoTime, loggerSink);
				Method swap = Diagnostics.class.getDeclaredMethod("swapProductionForTesting", reporter);
				swap.setAccessible(true);
				return new LimiterScope(swap, java.util.Objects.requireNonNull(swap.invoke(null, fresh)));
			} catch (ReflectiveOperationException failure) {
				throw new IllegalStateException("cannot isolate presentation diagnostic test limiter", failure);
			}
		}
		private static @Nullable Object invokeSink(Object proxy, Method method, @Nullable Object @Nullable [] arguments) {
			if (arguments == null) throw new AssertionError("unexpected diagnostic sink method " + method);
			return switch (method.getName()) {
				case "isEnabled" -> Diagnostics.logger().isEnabled((Level) arguments[0]);
				case "log" -> { Diagnostics.logger().log((Level) arguments[0], (String) arguments[1]); yield null; }
				default -> throw new AssertionError("unexpected diagnostic sink method " + method);
			};
		}
		@Override public void close() {
			try { swap.invoke(null, previous); }
			catch (ReflectiveOperationException failure) { throw new IllegalStateException("cannot restore presentation diagnostic test limiter", failure); }
		}
	}
	@NotNullByDefault
	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events = new ArrayList<>();
		private RecordingAppender() { super("create-presentation-diagnostics-test", null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}
}
