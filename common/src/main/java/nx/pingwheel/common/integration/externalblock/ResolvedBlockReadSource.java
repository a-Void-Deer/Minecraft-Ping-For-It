package nx.pingwheel.common.integration.externalblock;

import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Synchronous game-thread handle. Never retain this value, its level, or its
 * member gate across ticks; retain only the detached descriptor/observations.
 * Every additional member must pass the gate before any state or block-entity read.
 */
public record ResolvedBlockReadSource(Level level, BlockReadSource descriptor,
	Predicate<BlockPos> memberGate) {
	public ResolvedBlockReadSource {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(descriptor, "descriptor");
		Objects.requireNonNull(memberGate, "memberGate");
	}

	public boolean containsMember(BlockPos position) {
		if (position == null) return false;
		try { return memberGate.test(position); }
		catch (RuntimeException | LinkageError unavailable) { return false; }
	}
}
