package nx.pingwheel.common.interaction.candidate;

import java.util.Iterator;
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
 * Counts every inspected entity before any rejection. No entity sections are traversed.
 * Budget exhaustion cannot certify nearest among an unordered prefix.
 */
public final class NativeEntityCandidateScan {
	private NativeEntityCandidateScan() {}

	public static boolean scan(Iterable<Entity> visibleEntities, AABB bounds, @Nullable Entity excluded,
		CandidateWorkBudget budget, Consumer<Entity> candidateConsumer) {
		return scan(visibleEntities, bounds, excluded, budget, entity -> false, candidateConsumer);
	}

	public static boolean scan(Iterable<Entity> visibleEntities, AABB bounds, @Nullable Entity excluded,
		CandidateWorkBudget budget, Predicate<Entity> loadedExact, Consumer<Entity> candidateConsumer) {
		Objects.requireNonNull(visibleEntities, "visibleEntities");
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(budget, "budget");
		Objects.requireNonNull(candidateConsumer, "candidateConsumer");
		Iterator<Entity> iterator = visibleEntities.iterator();
		Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		while (iterator.hasNext()) {
			if (!budget.visitEntity()) return false;
			@Nullable Entity entity = iterator.next();
			if (entity == null) continue;
			if (seen.add(entity) && entity != excluded
				&& (loadedExact.test(entity) || entity.getBoundingBox().intersects(bounds))) candidateConsumer.accept(entity);
			if (entity instanceof EnderDragon dragon) {
				for (EnderDragonPart part : dragon.getSubEntities()) {
					if (!budget.visitEntity()) return false;
					if (seen.add(part) && part != excluded
						&& (loadedExact.test(part) || part.getBoundingBox().intersects(bounds))) candidateConsumer.accept(part);
				}
			}
		}
		return true;
	}
}
