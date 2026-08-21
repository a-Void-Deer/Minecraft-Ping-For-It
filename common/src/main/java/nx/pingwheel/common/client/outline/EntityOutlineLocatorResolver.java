package nx.pingwheel.common.client.outline;

import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.interaction.MinecraftEntityTargetAdapter;

/**
 * Pure resolver that turns a frozen {@link EntityLocator} into the canonical
 * live entity that currently represents it.
 *
 * <p>The lookup is supplied as a function so headless tests can inject a fake
 * locator→entity map instead of a live {@code ClientLevel}. Resolution applies
 * the same canonicalization the ray-hit capture and the render redirects use:
 * an {@link EnderDragonPart} resolves to its parent dragon, and an
 * {@link ExperienceOrb} uses its synchronized runtime id. After
 * canonicalization the canonical entity's own locator must still equal the
 * requested one; any mismatch (stale, replaced, or miskeyed entity) is
 * rejected with {@code null}.</p>
 */
public final class EntityOutlineLocatorResolver {

	private EntityOutlineLocatorResolver() {}

	@FunctionalInterface
	public interface LocatorLookup {
		Entity find(EntityLocator locator);
	}

	/**
	 * Resolves {@code locator} to the canonical live entity via
	 * {@code lookup}, or {@code null} when the lookup is absent, the entity is
	 * gone, or canonicalization changes the locator identity.
	 */
	public static Entity resolve(EntityLocator locator, LocatorLookup lookup) {
		if (locator == null || lookup == null) {
			return null;
		}

		Entity raw = lookup.find(locator);
		if (raw == null) {
			return null;
		}

		Entity canonical = MinecraftEntityTargetAdapter.canonicalEntity(raw);
		if (!MinecraftEntityTargetAdapter.locatorFor(canonical).equals(locator)) {
			return null;
		}

		return canonical;
	}
}
