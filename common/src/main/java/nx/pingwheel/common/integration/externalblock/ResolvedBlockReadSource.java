package nx.pingwheel.common.integration.externalblock;

import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import java.time.Duration;
import org.jetbrains.annotations.Nullable;

/**
 * Synchronous game-thread handle. Never retain this value, its level, or its
 * member gate across ticks; retain only the detached descriptor/observations.
 * Every additional member must pass the gate before any state or block-entity read.
 */
public record ResolvedBlockReadSource(Level level, BlockReadSource descriptor,
	Predicate<BlockPos> memberGate, DiagnosticLogger diagnostics) {
	private static final DiagnosticChannel MEMBER_FAILURES = DiagnosticChannel.interval("external-block/member-gate", Duration.ofSeconds(10));
	public ResolvedBlockReadSource {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(descriptor, "descriptor");
		Objects.requireNonNull(memberGate, "memberGate");
		Objects.requireNonNull(diagnostics, "diagnostics");
	}

	/** Server compatibility entry; client producers must select CLIENT explicitly. */
	public ResolvedBlockReadSource(Level level, BlockReadSource descriptor, Predicate<BlockPos> memberGate) {
		this(level, descriptor, memberGate, Diagnostics.server());
	}

	public boolean containsMember(@Nullable BlockPos position) {
		if (position == null) return false;
		try { return memberGate.test(position); }
		catch (RuntimeException | LinkageError unavailable) {
			diagnostics.atNormal().rateLimited(MEMBER_FAILURES).failure(org.apache.logging.log4j.Level.WARN,
				"external-block/member-gate", "member-access-denied", () -> "descriptor=" + descriptor
					+ " position=" + position, unavailable);
			return false;
		}
	}
}
