package nx.pingwheel.common.config;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the configuration-default ownership boundary: the server group is
 * pure data and can initialize without any client group, platform service,
 * configuration handler or optional integration. Permitted project helpers are
 * child-defined so their transitive project dependencies cannot bypass the gate.
 */
@NotNullByDefault
class ConfigDefaultsIsolationTest {

	private static final String CONFIG_DEFAULTS = "nx.pingwheel.common.config.ConfigDefaults";
	private static final String SERVER_GROUP = CONFIG_DEFAULTS + "$Server";
	private static final String CLIENT_GROUP = CONFIG_DEFAULTS + "$Client";
	private static final String PRESENTATION_GROUP = CONFIG_DEFAULTS + "$Presentation";
	private static final String CHANNEL_MODE = "nx.pingwheel.common.config.ChannelMode";
	private static final String MOD_CONTEXT = "nx.pingwheel.common.integration.ModContext";
	private static final String PLATFORM_CONTEXT = "nx.pingwheel.common.platform.IPlatformContextService";

	private static final @Unmodifiable Set<String> DENIED_CONFIG_OWNERS = Set.of(
		"nx.pingwheel.common.config.ClientConfig",
		"nx.pingwheel.common.config.ClientConfigBounds",
		"nx.pingwheel.common.config.ConfigHandler",
		"nx.pingwheel.common.config.ConfigVersionUpdater",
		"nx.pingwheel.common.config.IConfig",
		"nx.pingwheel.common.config.InventorySettings",
		"nx.pingwheel.common.config.ServerConfig",
		"nx.pingwheel.common.config.ServerConfigBounds",
		"nx.pingwheel.common.config.ServerConfigSnapshot",
		"nx.pingwheel.common.config.ServerConfigUpdate",
		"nx.pingwheel.common.config.ServerConfigUpdateService",
		"nx.pingwheel.common.config.SpatialSelectorSettings",
		"nx.pingwheel.common.presentation.PresentationSettings");

	private static final @Unmodifiable Set<String> DENIED_OPTIONAL_INTEGRATIONS = Set.of(
		"nx.pingwheel.common.integration.DistantHorizonsIntegration",
		"nx.pingwheel.common.integration.SableIntegration");

	private static final @Unmodifiable List<String> DENIED_PROBES = List.of(
		CLIENT_GROUP,
		PRESENTATION_GROUP,
		"nx.pingwheel.common.config.ClientConfig",
		"nx.pingwheel.common.config.ServerConfig",
		"nx.pingwheel.common.config.ConfigHandler",
		"nx.pingwheel.common.Global",
		PLATFORM_CONTEXT,
		"nx.pingwheel.common.integration.SableIntegration");

	@Test
	void serverDefaultsInitializeAndStayUsableWithoutClientPlatformOrHandlerClasses() throws Exception {
		// The gate must reject each claimed category, or an empty attempt log would be vacuous.
		try (IsolatedConfigDefaultsLoader gate = new IsolatedConfigDefaultsLoader()) {
			for (String denied : DENIED_PROBES) {
				assertThrows(ClassNotFoundException.class, () -> gate.loadClass(denied));
				assertTrue(gate.deniedAttempts().contains(denied), denied);
			}
		}

		try (IsolatedConfigDefaultsLoader loader = new IsolatedConfigDefaultsLoader()) {
			Class<?> server = Class.forName(SERVER_GROUP, true, loader);
			assertSame(loader, server.getClassLoader());

			Field defaultChannelMode = server.getDeclaredField("DEFAULT_CHANNEL_MODE");
			Object mode = defaultChannelMode.get(null);
			assertNotNull(mode);
			assertTrue(mode instanceof Enum<?>);

			assertEquals(CHANNEL_MODE, defaultChannelMode.getType().getName());
			Class<?> modeClass = mode.getClass();
			assertEquals(defaultChannelMode.getType(), modeClass);
			assertSame(loader, modeClass.getClassLoader());

			// The default stays a usable enum value without pinning the selected constant.
			String name = (String) modeClass.getMethod("name").invoke(mode);
			assertFalse(name.isEmpty());
			assertSame(mode, modeClass.getMethod("valueOf", String.class).invoke(null, name));

			assertTrue(loader.deniedAttempts().isEmpty(),
				() -> "server defaults initialization reached unavailable classes: " + loader.deniedAttempts());
		}

		// A permitted project helper is child-defined, so its own platform dependency is
		// denied instead of resolving through the parent's test service loader.
		try (IsolatedConfigDefaultsLoader helperLoader = new IsolatedConfigDefaultsLoader()) {
			Class<?> modContext = Class.forName(MOD_CONTEXT, true, helperLoader);
			assertSame(helperLoader, modContext.getClassLoader());

			InvocationTargetException failure = assertThrows(InvocationTargetException.class,
				() -> modContext.getMethod("indexMods").invoke(null));
			assertTrue(failure.getCause() instanceof NoClassDefFoundError,
				() -> "expected transitive link failure, got " + failure.getCause());
			assertTrue(helperLoader.deniedAttempts().contains(PLATFORM_CONTEXT),
				() -> "transitive platform dependency escaped the gate: " + helperLoader.deniedAttempts());
		}
	}

	/**
	 * Child-defines the project classes so their transitive project dependencies
	 * pass through the same deny gate; external and JDK classes stay parent-loaded.
	 */
	@NotNullByDefault
	private static final class IsolatedConfigDefaultsLoader extends URLClassLoader {
		private final Set<String> deniedAttempts = new LinkedHashSet<>();

		private IsolatedConfigDefaultsLoader() {
			super(
				new URL @Unmodifiable [] {
					ConfigDefaults.class.getProtectionDomain().getCodeSource().getLocation()
				},
				ConfigDefaults.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (isDenied(name)) {
				deniedAttempts.add(name);
				throw new ClassNotFoundException("Class is intentionally unavailable to server defaults: " + name);
			}

			if (!isProjectClass(name)) {
				return super.loadClass(name, resolve);
			}

			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					loaded = findClass(name);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}

		private @UnmodifiableView Set<String> deniedAttempts() {
			return Collections.unmodifiableSet(deniedAttempts);
		}

		private static boolean isProjectClass(String name) {
			return name.startsWith("nx.pingwheel.common.");
		}

		private static boolean isDenied(String name) {
			if (name.equals("nx.pingwheel.common.Global") || name.startsWith("nx.pingwheel.common.Global$")) {
				return true;
			}
			if (name.startsWith("nx.pingwheel.common.platform.")
				|| name.startsWith("nx.pingwheel.common.client.")
				|| name.startsWith("nx.pingwheel.common.integration.sable.")) {
				return true;
			}
			if (isInGroup(name, CLIENT_GROUP) || isInGroup(name, PRESENTATION_GROUP)) {
				return true;
			}
			for (String owner : DENIED_CONFIG_OWNERS) {
				if (isInGroup(name, owner)) {
					return true;
				}
			}
			for (String integration : DENIED_OPTIONAL_INTEGRATIONS) {
				if (isInGroup(name, integration)) {
					return true;
				}
			}
			return false;
		}

		private static boolean isInGroup(String name, String group) {
			return name.equals(group) || name.startsWith(group + "$");
		}
	}
}
