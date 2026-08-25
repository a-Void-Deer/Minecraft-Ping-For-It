package nx.pingwheel.common.client;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.state.TargetGoneReason;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.interaction.state.TargetValidator;

import static nx.pingwheel.common.CommonClient.Game;

/**
 * The Minecraft 1.21.1 client adapter of {@link TargetValidator}, used by the
 * local interaction state machine immediately before a create action is
 * dispatched.
 *
 * <p>This is a best-effort client-side pre-check only; the server remains
 * authoritative (see
 * {@link nx.pingwheel.common.marker.MinecraftAuthoritativeTargetValidator}).
 * The verdict is derived exclusively from live client state:
 * <ul>
 *   <li>the current level's dimension id must exactly equal the captured
 *       dimension id (a level-less or cross-dimension capture is reported as
 *       {@link TargetGoneReason#DIMENSION_CHANGED});</li>
 *   <li>an entity target that the client does not currently have loaded is
 *       left to the authoritative server: only a loaded entity that is dead or
 *       removed is reported as
 *       {@link TargetGoneReason#ENTITY_GONE_OR_DEAD};</li>
 *   <li>a block target whose chunk is not currently loaded is likewise left to
 *       the server; a loaded block whose current
 *       {@code BuiltInRegistries.BLOCK} id differs from the captured id is
 *       {@link TargetGoneReason#BLOCK_REPLACED}, while a {@code BlockState}-only
 *       change keeps the target valid;</li>
 *   <li>a location target is finite by construction and only needs the
 *       dimension check.</li>
 * </ul>
 *
 * <p>Server-authority rationale: the client can only judge what it has
 * currently loaded. "Not currently loaded" cannot be distinguished from
 * "gone" using only local state, and a false negative here would drop a valid
 * request before the server could validate the underlying target. Therefore
 * this validator only rejects what the client can positively prove (dimension
 * mismatch, a loaded dead entity, or a loaded replaced block) and otherwise
 * lets the request reach the authoritative server validator, which re-checks
 * the same target against authoritative server state.
 *
 * <p>No logging happens here: the verdict reasons are already safe enum
 * values, and this class never emits identities, positions, or names.
 */
public final class MinecraftClientTargetValidator implements TargetValidator {

	@Override
	public TargetValidation validate(ResolvedTarget resolvedTarget) {
		Objects.requireNonNull(resolvedTarget, "resolvedTarget");

		if (Game == null || Game.level == null) {
			return TargetValidation.gone(TargetGoneReason.DIMENSION_CHANGED);
		}

		Level level = Game.level;
		String currentDimensionId = level.dimension().location().toString();
		Target target = resolvedTarget.target();

		if (!currentDimensionId.equals(target.dimensionId())) {
			return TargetValidation.gone(TargetGoneReason.DIMENSION_CHANGED);
		}

		return switch (target) {
			case Target.EntityTarget entity -> validateEntity(entity);
			case Target.BlockTarget block -> validateBlock(level, block);
			// Provider-specific validation is intentionally deferred to the later
			// integration. The server remains authoritative for this opaque target.
			case Target.ExternalBlockTarget ignored -> TargetValidation.valid();
			case Target.LocationTarget ignored -> TargetValidation.valid();
		};
	}

	private TargetValidation validateEntity(Target.EntityTarget entityTarget) {
		// GameContext iterates the client level's current entity storage
		// (entitiesForRendering), so a found entity is currently loaded and in
		// the current dimension (the dimension check above already passed).
		Entity entity = GameContext.getEntity(entityTarget.locator());

		// Not currently loaded on the client (out of view distance, never
		// received, or lost after a reload): the client cannot prove the
		// entity is gone, so the authoritative server decides.
		if (entity == null) {
			return TargetValidation.valid();
		}

		if (!entity.isAlive() || entity.isRemoved()) {
			return TargetValidation.gone(TargetGoneReason.ENTITY_GONE_OR_DEAD);
		}

		return TargetValidation.valid();
	}

	private TargetValidation validateBlock(Level level, Target.BlockTarget blockTarget) {
		BlockPos position = new BlockPos(blockTarget.x(), blockTarget.y(), blockTarget.z());

		// Not currently loaded on the client: the client cannot prove the
		// block was replaced, so the authoritative server decides.
		if (!level.isLoaded(position)) {
			return TargetValidation.valid();
		}

		ResourceLocation capturedId = ResourceLocation.tryParse(blockTarget.blockRegistryId());

		if (capturedId == null) {
			return TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED);
		}

		ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(
			level.getBlockState(position).getBlock());

		if (currentId == null || !currentId.equals(capturedId)) {
			return TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED);
		}

		return TargetValidation.valid();
	}
}
