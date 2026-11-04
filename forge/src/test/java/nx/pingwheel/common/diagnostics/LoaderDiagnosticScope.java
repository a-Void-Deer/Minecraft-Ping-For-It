package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Loader-owned seam: explicit profiles and the test appender, not deployed backend setup. */
@NotNullByDefault
public final class LoaderDiagnosticScope implements AutoCloseable {
	private final LoggingProfile client = Diagnostics.profile(DiagnosticSide.CLIENT);
	private final LoggingProfile server = Diagnostics.profile(DiagnosticSide.SERVER);
	private final DiagnosticBackend previous;
	private final List<SavedField> fields = new ArrayList<>();

	public LoaderDiagnosticScope() {
		Logger logger = LogManager.getLogger("pingforit");
		previous = Diagnostics.swapBackendForTesting(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return logger.isEnabled(severity); }
			@Override public void log(DiagnosticSide side, Level severity, String message) { logger.log(severity, message); }
		});
		Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
		Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
		try {
			for (Field field : Class.forName("nx.pingwheel.forge.platform.PlatformInventoryServiceImpl").getDeclaredFields()) {
				if (field.getType() != DiagnosticChannel.class || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
				field.setAccessible(true); isolate((DiagnosticChannel) field.get(null));
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
			close(); throw new AssertionError("Cannot isolate Forge diagnostic channels", failure);
		}
	}

	private void isolate(DiagnosticChannel channel) throws ReflectiveOperationException {
		Field gate = DiagnosticChannel.class.getDeclaredField("domainGate"); gate.setAccessible(true);
		if (gate.get(channel) != null) return;
		for (String side : List.of("client", "server", "bootstrap")) {
			Field stateField = DiagnosticChannel.class.getDeclaredField(side); stateField.setAccessible(true);
			Object state = stateField.get(channel);
			synchronized (state) {
				for (Field field : state.getClass().getDeclaredFields()) {
					field.setAccessible(true); fields.add(new SavedField(state, field, field.get(state)));
					if (field.getType() == boolean.class) field.setBoolean(state, false);
					else field.setLong(state, 0L);
				}
			}
		}
	}

	@Override public void close() {
		try {
			for (SavedField saved : fields) synchronized (saved.owner) { saved.field.set(saved.owner, saved.value); }
			fields.clear();
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot restore Forge diagnostic channels", failure); }
		finally {
			Diagnostics.swapBackendForTesting(previous);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, client);
			Diagnostics.setProfile(DiagnosticSide.SERVER, server);
		}
	}

	@NotNullByDefault
	private record SavedField(Object owner, Field field, @Nullable Object value) {}
}
