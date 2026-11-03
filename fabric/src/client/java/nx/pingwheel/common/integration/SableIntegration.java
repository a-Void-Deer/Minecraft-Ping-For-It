package nx.pingwheel.common.integration;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

import nx.pingwheel.common.integration.sable.client.SableClientProvider;

/**
 * Compatibility facade for the optional Sable integration. The client provider
 * owns the guarded Companion calls and this facade keeps the legacy projected
 * location entry point available to the capture path.
 */
public class SableIntegration {
	private SableIntegration() {}

	/**
	 * Projects a block hit inside a Sable sub-level back into the real level.
	 *
	 * @return the projected position, or empty when Sable is absent, disabled,
	 *         or the hit is outside any sub-level.
	 */
	public static Optional<Vec3> projectOutOfSubLevel(ClientLevel level, Vec3 hitPosition) {
		return SableClientProvider.projectOutOfSubLevel(level, hitPosition);
	}
}
