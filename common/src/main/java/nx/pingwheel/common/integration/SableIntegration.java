package nx.pingwheel.common.integration;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Dedicated boundary for the optional Sable integration: every Sable
 * companion class reference lives here behind a JDK/Minecraft-only signature.
 */
public class SableIntegration {
	private SableIntegration() {}

	private static final IntegrationLinkGuard LINK_GUARD = new IntegrationLinkGuard("sable");

	/**
	 * Projects a block hit inside a Sable sub-level back into the real level.
	 *
	 * @return the projected position, or empty when Sable is absent, disabled,
	 *         or the hit is outside any sub-level.
	 */
	public static Optional<Vec3> projectOutOfSubLevel(ClientLevel level, Vec3 hitPosition) {
		if (!ModContext.HasSable || LINK_GUARD.disabled()) {
			return Optional.empty();
		}

		try {
			final var subLevelAccess = SableCompanion.INSTANCE.getContainingClient(hitPosition);

			if (subLevelAccess == null) {
				return Optional.empty();
			}

			return Optional.of(SableCompanion.INSTANCE.projectOutOfSubLevel(level, hitPosition));
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
			return Optional.empty();
		}
	}
}
