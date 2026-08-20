package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Combines the independent native-glow attempts for an {@code entity_block}.
 *
 * <p>The suppliers are deliberately evaluated into separate booleans before
 * applying the OR. This keeps the BlockEntityRenderer and baked-model routes
 * non-short-circuiting while leaving the combination policy pure and easy to
 * exercise without client renderer mocks.</p>
 */
final class EntityBlockGlowAttempts {
	private EntityBlockGlowAttempts() {}

	static boolean attemptBoth(BooleanSupplier blockEntityAttempt, BooleanSupplier bakedModelAttempt) {
		Objects.requireNonNull(blockEntityAttempt, "blockEntityAttempt");
		Objects.requireNonNull(bakedModelAttempt, "bakedModelAttempt");

		boolean blockEntitySucceeded = blockEntityAttempt.getAsBoolean();
		boolean bakedModelSucceeded = bakedModelAttempt.getAsBoolean();
		return blockEntitySucceeded || bakedModelSucceeded;
	}
}
