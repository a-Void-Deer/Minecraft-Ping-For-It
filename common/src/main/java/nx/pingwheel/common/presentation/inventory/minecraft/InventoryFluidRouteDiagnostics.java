package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Active-server bounded notice set. No eviction, coordinates, owner identity or configuration. */
@NotNullByDefault
final class InventoryFluidRouteDiagnostics {
	private static final int MAX_CLASSES = 256;
	private static @Nullable MinecraftServer active;
	private static final Set<String> classes = new HashSet<>();
	private static boolean summary;
	private InventoryFluidRouteDiagnostics() {}
	static void used(MinecraftServer server, IPlatformFluidService.RouteInfo route) {
		if (route.topology() != IPlatformFluidService.TopologyEvidence.PUBLIC_ADDRESS_ONLY) return;
		Diagnostics.server().atDetailed().domainBeforeBackend(Level.INFO, "inventory/fluid-route", () -> {
			if (active != server) { active = server; classes.clear(); summary = false; }
			if (classes.contains(route.providerClass())) return null;
			if (classes.size() == MAX_CLASSES) {
				if (summary) return null;
				summary = true;
				return new DiagnosticLogger.DomainReport("capacity", null,
					() -> "Further opaque fluid provider classes use public address evidence; hidden equal-capacity remaps cannot be guaranteed detectable.");
			}
			classes.add(route.providerClass());
			return new DiagnosticLogger.DomainReport("public-address", null,
				() -> "provider=" + route.providerClass() + " Fluid provider uses public route/address/capacity metadata; hidden equal-capacity remaps cannot be guaranteed detectable.");
		});
	}
	static void clear() { active = null; classes.clear(); summary = false; }
}
