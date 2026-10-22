package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Objects;

import net.minecraft.server.MinecraftServer;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationRegistry;

/**
 * Loader reflection entry point for the two independent registries. The loader
 * checks the Create mod ID before loading this class; the factories also check
 * the tested runtime version before creating either adapter. No Create classes
 * are referenced on the client registration path.
 */
public final class CreatePresentationRegistration {
	private CreatePresentationRegistration() {}

	/** Called reflectively for each server registry/session, on the server thread. */
	public static boolean registerServer(PresentationRegistry registry, MinecraftServer server) {
		Objects.requireNonNull(registry, "registry");
		Objects.requireNonNull(server, "server");
		return register(registry, CreatePresentationAdapters.server(server));
	}

	/** Called reflectively for each client registry/session, without a world. */
	public static boolean registerClient(PresentationRegistry registry) {
		Objects.requireNonNull(registry, "registry");
		return register(registry, CreatePresentationAdapters.client());
	}

	/** Shared registration guard; a session may initialize its registry twice. */
	static boolean register(PresentationRegistry registry, PresentationAdapter adapter) {
		Objects.requireNonNull(registry, "registry");
		if (adapter == null) {
			return false;
		}
		if (registry.get(adapter.adapterId()) != null) {
			return false;
		}
		registry.register(adapter);
		return true;
	}
}
