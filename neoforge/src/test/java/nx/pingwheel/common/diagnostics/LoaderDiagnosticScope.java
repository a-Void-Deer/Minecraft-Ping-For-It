package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loader-owned seam: explicit profiles and the test appender, not deployed backend setup. */
@NotNullByDefault
public final class LoaderDiagnosticScope implements AutoCloseable {
	private final LoggingProfile client = Diagnostics.profile(DiagnosticSide.CLIENT);
	private final LoggingProfile server = Diagnostics.profile(DiagnosticSide.SERVER);
	private final LoggingProfile bootstrap = Diagnostics.profile(DiagnosticSide.BOOTSTRAP);
	private final DiagnosticBackend previous;
	private final List<SavedField> fields = new ArrayList<>();
	private final Set<DiagnosticChannel> channels = Collections.newSetFromMap(new IdentityHashMap<>());

	public LoaderDiagnosticScope() {
		this(LoggingProfile.DEEP, LoggingProfile.DEEP);
	}

	public LoaderDiagnosticScope(LoggingProfile clientProfile, LoggingProfile serverProfile) {
		Logger logger = LogManager.getLogger("pingforit");
		previous = Diagnostics.swapBackendForTesting(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return logger.isEnabled(severity); }
			@Override public void log(DiagnosticSide side, Level severity, String message) { logger.log(severity, message); }
		});
		Diagnostics.setProfile(DiagnosticSide.CLIENT, clientProfile);
		Diagnostics.setProfile(DiagnosticSide.SERVER, serverProfile);
		Diagnostics.setProfile(DiagnosticSide.BOOTSTRAP, LoggingProfile.NORMAL);
		try {
			for (String owner : List.of("nx.pingwheel.neoforge.platform.PlatformInventoryServiceImpl",
				"nx.pingwheel.neoforge.NeoClient",
				"nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationDiagnostics",
				"nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability",
				"nx.pingwheel.neoforge.integration.create.CreateVaultTopology",
				"nx.pingwheel.neoforge.integration.create.CreateFluidTankTopology",
				"nx.pingwheel.neoforge.integration.create.CreateContraptionNativeBounds",
				"nx.pingwheel.neoforge.integration.simulated.SimulatedDockingConnectorPresentationResolver")) {
				isolateChannels(Class.forName(owner, false, getClass().getClassLoader()));
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
			close();
			throw new AssertionError("Cannot isolate loader diagnostic channels", failure);
		}
	}

	/** Only explicit interval state is isolated; real transition/once/recovery gates are never reset. */
	public void isolateChannels(Class<?> owner) throws ReflectiveOperationException {
		for (Field field : owner.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			if (field.getType() == DiagnosticChannel.class) {
				field.setAccessible(true);
				isolate((DiagnosticChannel) field.get(null));
			} else if (Map.class.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				if (field.get(null) instanceof Map<?, ?> map)
					for (Object value : map.values()) if (value instanceof DiagnosticChannel channel) isolate(channel);
			}
		}
	}

	private void isolate(DiagnosticChannel channel) throws ReflectiveOperationException {
		Field gate = DiagnosticChannel.class.getDeclaredField("domainGate"); gate.setAccessible(true);
		if (gate.get(channel) != null || !channels.add(channel)) return;
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
			fields.clear(); channels.clear();
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot restore loader diagnostic channels", failure); }
		finally {
			Diagnostics.swapBackendForTesting(previous);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, client);
			Diagnostics.setProfile(DiagnosticSide.SERVER, server);
			Diagnostics.setProfile(DiagnosticSide.BOOTSTRAP, bootstrap);
		}
	}

	@NotNullByDefault
	private record SavedField(Object owner, Field field, @Nullable Object value) {}
}
