package nx.pingwheel.common.interaction.candidate;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.AABB;

/**
 * Walks the native visible lookup's lazy getAll view, not an AABB-filtered result list.
 * Visits every entry and its dragon parts without traversing entity sections.
 */
public final class NativeEntityCandidateScan {
	private NativeEntityCandidateScan() {}

	public static void scan(Iterable<@Nullable Entity> visibleEntities, AABB bounds, @Nullable Entity excluded,
		Consumer<Entity> candidateConsumer) {
		scan(visibleEntities, bounds, excluded, entity -> false, candidateConsumer);
	}

	public static void scan(Iterable<@Nullable Entity> visibleEntities, AABB bounds, @Nullable Entity excluded,
		Predicate<Entity> loadedExact, Consumer<Entity> candidateConsumer) {
		Objects.requireNonNull(visibleEntities, "visibleEntities");
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(candidateConsumer, "candidateConsumer");
		Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (@Nullable Entity entity : visibleEntities) {
			if (entity == null) continue;
			if (seen.add(entity) && entity != excluded
				&& (loadedExact.test(entity) || entity.getBoundingBox().intersects(bounds))) candidateConsumer.accept(entity);
			if (entity instanceof EnderDragon dragon) {
				for (EnderDragonPart part : dragon.getSubEntities()) {
					if (seen.add(part) && part != excluded
						&& (loadedExact.test(part) || part.getBoundingBox().intersects(bounds))) candidateConsumer.accept(part);
				}
			}
		}
	}
}
