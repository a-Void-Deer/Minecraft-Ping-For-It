package nx.pingwheel.neoforge.platform;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Provider-local DEBUG context. Each supplier reads only values the rejected attempt had already
 * observed; it never performs a new capability, world or provider getter.
 */
@NotNullByDefault
public final class FluidSourceDiagnostics {
	// One stable site/reason per locally consumed absence. A probe may be retried, so these are
	// explicit interval channels rather than a global duplicate filter.
	private static final DiagnosticChannel CREATE_PROBE_ABSENT =
		DiagnosticChannel.interval("fluid-create-probe-absent", Duration.ofSeconds(10));
	private static final DiagnosticChannel CREATE_FINISH_ABSENT =
		DiagnosticChannel.interval("fluid-create-finish-absent", Duration.ofSeconds(10));
	private static final DiagnosticChannel CAPABILITY_PROBE_ABSENT =
		DiagnosticChannel.interval("fluid-capability-probe-absent", Duration.ofSeconds(10));

	private FluidSourceDiagnostics() {}

	public static InventorySourceUnavailableException unavailable(String message, Supplier<String> context) {
		return new InventorySourceUnavailableException(message, Objects.requireNonNull(context, "context"));
	}

	public static String describeRoute(FluidCapabilityAccess.Route route) {
		return "route=" + route.description() + ";capability=" + route.capabilityPosition().toShortString()
			+ ";face=" + route.face().getName() + ";members=" + route.members().size();
	}

	public static String describeHandler(@Nullable IFluidHandler handler) {
		return handler == null ? "none" : handler.getClass().getName();
	}

	public static String describeException(InventorySourceUnavailableException failure) {
		@Nullable String context = failure.diagnosticContext();
		return context == null ? "" : ";ctx=" + context;
	}

	public static void createProbeAbsent(BlockPos root, Direction face, String blockId, String entityId) {
		Diagnostics.server().atDebug().rateLimited(CREATE_PROBE_ABSENT).event(Level.DEBUG,
			"fluid/create-probe", "capability-absent",
			() -> "position=" + root.toShortString() + ";face=" + face.getName()
				+ ";block=" + blockId + ";entity=" + entityId);
	}

	public static void createFinishAbsent(FluidCapabilityAccess.Route route) {
		Diagnostics.server().atDebug().rateLimited(CREATE_FINISH_ABSENT).event(Level.DEBUG,
			"fluid/create-finish", "capability-absent",
			() -> describeRoute(route) + ";handler=none");
	}

	public static void capabilityProbeAbsent(FluidCapabilityAccess.Route route) {
		Diagnostics.server().atDebug().rateLimited(CAPABILITY_PROBE_ABSENT).event(Level.DEBUG,
			"fluid/capability-probe", "capability-absent",
			() -> describeRoute(route) + ";handler=none");
	}

	/** Caller owns a per-access first-failure transition; this method reports exactly one failure. */
	public static void validationRejected(FluidCapabilityAccess.Route route, @Nullable IFluidHandler handler,
		int count, InventorySourceUnavailableException failure) {
		Diagnostics.server().atDebug().failure(Level.DEBUG, "fluid/capability-valid", "source-rejected",
			() -> describeRoute(route) + ";handler=" + describeHandler(handler) + ";count=" + count
				+ describeException(failure), failure);
	}
}
