package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Set;

import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Code-owned explicit exceptions to provider-unit trust. The production set is intentionally empty;
 * no plugin SPI, configuration key, matching priority or current-hit fallback is defined here.
 */
@NotNullByDefault
final class InventoryFluidStrictValidation {
	private static final @Unmodifiable Set<String> TARGETS = Set.of();
	private InventoryFluidStrictValidation() {}

	/** Uses only the existing provider and expected block registry identifiers. */
	static boolean requiresStrictValidation(BlockReadSource source) {
		return TARGETS.contains(key(source));
	}
	static @Unmodifiable Set<String> codeOwnedTargets() { return TARGETS; }
	private static String key(BlockReadSource source) {
		return source.providerId() + "/" + source.blockTarget().blockRegistryId();
	}
}
