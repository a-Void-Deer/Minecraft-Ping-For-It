package nx.pingwheel.common.interaction.candidate;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;

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

	public static boolean scan(Iterable<Entity> visibleEntities, AABB bounds, Entity excluded,
		CandidateWorkBudget budget, Consumer<Entity> candidateConsumer) {
		Objects.requireNonNull(visibleEntities, "visibleEntities");
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(budget, "budget");
		Objects.requireNonNull(candidateConsumer, "candidateConsumer");
		Iterator<Entity> iterator = visibleEntities.iterator();
		while (iterator.hasNext()) {
			if (!budget.visitEntity()) return false;
			Entity entity = iterator.next();
			if (entity == null) continue;
			if (entity != excluded && entity.getBoundingBox().intersects(bounds)) candidateConsumer.accept(entity);
			if (entity instanceof EnderDragon dragon) {
				for (EnderDragonPart part : dragon.getSubEntities()) {
					if (!budget.visitEntity()) return false;
					if (part != excluded && part.getBoundingBox().intersects(bounds)) candidateConsumer.accept(part);
				}
			}
		}
		return true;
	}
}
