package nx.pingwheel.common;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Bootstraps the vanilla registries before any test class runs.
 *
 * <p>Some tests intentionally exercise registry-free helpers and therefore do
 * not declare their own {@code @BeforeAll}; without a global bootstrap those
 * classes can initialize a registry first and poison the shared test JVM.
 * 1.20.1 also requires {@link SharedConstants#tryDetectVersion()} before the
 * registry bootstrap.</p>
 */
public final class TestBootstrap implements BeforeAllCallback {

	private static boolean bootstrapped;

	@Override
	public void beforeAll(ExtensionContext context) {
		if (bootstrapped) {
			return;
		}

		synchronized (TestBootstrap.class) {
			if (bootstrapped) {
				return;
			}

			SharedConstants.tryDetectVersion();
			Bootstrap.bootStrap();
			bootstrapped = true;
		}
	}
}
