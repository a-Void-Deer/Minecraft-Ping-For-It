package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/** Create-free work ledger and the production vault/tank member-verification loop. */
final class CreateSamplingWork {
	private final int initial;
	private int remaining;

	CreateSamplingWork(int remaining) {
		if (remaining < 0) throw new IllegalArgumentException("negative Create work budget");
		this.initial = remaining;
		this.remaining = remaining;
	}

	int remaining() { return remaining; }
	int used() { return initial - remaining; }

	boolean take(int cost) {
		if (cost < 0 || cost > remaining) return false;
		remaining -= cost;
		return true;
	}

	/** Admission precedes iteration; each attempted member stays paid even if its gate/read fails. */
	boolean verifyShape(BlockPos origin, int x, int y, int z, CreateSamplingLimits limits,
		Predicate<BlockPos> memberGate, Predicate<BlockPos> verifyMember) {
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(verifyMember, "verifyMember");
		if (!limits.permitsShape(x, y, z, remaining)) return false;
		for (int dx = 0; dx < x; dx++) {
			for (int dy = 0; dy < y; dy++) {
				for (int dz = 0; dz < z; dz++) {
					if (!take(1)) return false;
					BlockPos part = origin.offset(dx, dy, dz);
					if (!member(memberGate, part) || !verifyMember.test(part)) return false;
				}
			}
		}
		return true;
	}

	/** A rejected or throwing gate is unavailable, never permission to read the member. */
	static boolean member(Predicate<BlockPos> memberGate, BlockPos position) {
		if (memberGate == null) return true;
		try { return memberGate.test(position); }
		catch (RuntimeException | LinkageError unavailable) { return false; }
	}
}
