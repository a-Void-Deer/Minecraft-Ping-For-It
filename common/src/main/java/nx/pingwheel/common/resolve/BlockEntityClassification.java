package nx.pingwheel.common.resolve;

import java.util.Objects;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The shared classification seam for {@code entity_block} targets.
 *
 * <p>An {@code entity_block} is a registered target block that actually owns a
 * Minecraft {@link BlockEntity}: chest, sign, furnace, and similar blocks all
 * implement {@link EntityBlock} in 1.21.1. The virtual
 * {@code minecraft:block_display} entity is an entity target, never a block,
 * so it can never satisfy this classification.
 *
 * <p>Both the client capture and the authoritative server derive this
 * classification from their own game state through this single seam, so the
 * built-in resolver observes identical input on both sides and no
 * client-trusted classification ever reaches the server. An unregistered or
 * otherwise unknown block simply is not an {@code EntityBlock} and fails soft
 * to the generic {@code block} target type.
 */
public final class BlockEntityClassification {

	private BlockEntityClassification() {}

	/**
	 * Whether the block owning {@code state} actually implements
	 * {@link EntityBlock}, i.e. can own a Minecraft {@code BlockEntity}.
	 */
	public static boolean hasBlockEntity(BlockState state) {
		Objects.requireNonNull(state, "state");
		return state.getBlock() instanceof EntityBlock;
	}

	/**
	 * Whether {@code block} actually implements {@link EntityBlock}, i.e. can
	 * own a Minecraft {@code BlockEntity}.
	 */
	public static boolean hasBlockEntity(Block block) {
		Objects.requireNonNull(block, "block");
		return block instanceof EntityBlock;
	}
}
