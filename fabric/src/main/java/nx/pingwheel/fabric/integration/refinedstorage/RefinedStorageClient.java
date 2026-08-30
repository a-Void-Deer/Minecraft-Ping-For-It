package nx.pingwheel.fabric.integration.refinedstorage;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Lazy Fabric boundary for the optional Refined Storage 2 model adapter.
 *
 * <p>The mod check happens before the adapter class is resolved.  The adapter
 * itself contains no Refined Storage references, but retaining this boundary
 * also keeps an incompatible optional renderer from affecting ordinary pings.
 * Registration is rebuilt for each client connection and its exact common
 * registry handle is closed on disconnect.</p>
 */
public final class RefinedStorageClient {
	private static final String MOD_ID = "refinedstorage";
	private static final String ADAPTER_CLASS =
		"nx.pingwheel.fabric.integration.refinedstorage.RefinedStorageWorldAwareBlockModelOutlineAdapter";
	private static boolean adapterResolved;

	private RefinedStorageClient() {
	}

	public static void initialize() {
		loadAdapter();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> loadAdapter());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> closeAdapter());
	}

	private static void loadAdapter() {
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
			closeAdapter();
			return;
		}

		if (adapterResolved) {
			return;
		}

		try {
			Class<?> adapter = Class.forName(ADAPTER_CLASS, true, RefinedStorageClient.class.getClassLoader());
			adapter.getMethod("register").invoke(null);
			adapterResolved = true;
		} catch (ReflectiveOperationException | LinkageError | AssertionError ignored) {
			// Optional integration failures are deliberately silent and isolated.
		}
	}

	private static void closeAdapter() {
		if (!adapterResolved) {
			return;
		}

		try {
			Class<?> adapter = Class.forName(ADAPTER_CLASS, false, RefinedStorageClient.class.getClassLoader());
			adapter.getMethod("close").invoke(null);
		} catch (ReflectiveOperationException | LinkageError | AssertionError ignored) {
			// Teardown must not break the client when an optional API disappeared.
		} finally {
			adapterResolved = false;
		}
	}
}
