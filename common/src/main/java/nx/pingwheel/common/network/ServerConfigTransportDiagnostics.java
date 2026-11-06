package nx.pingwheel.common.network;

import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Fixed, DEBUG-only decisions for the three server-configuration loader sends.
 * The runtime packet class selects the kind; the caller supplies only the
 * already computed route/connection outcome.
 */
@NotNullByDefault
public final class ServerConfigTransportDiagnostics {
	private ServerConfigTransportDiagnostics() {}

	public static @Nullable Kind kindOf(IPacket packet) {
		if (packet instanceof ServerConfigRequestC2SPacket) return Kind.REQUEST;
		if (packet instanceof ServerConfigUpdateC2SPacket) return Kind.UPDATE;
		if (packet instanceof ServerConfigSnapshotS2CPacket) return Kind.SNAPSHOT;
		return null;
	}

	public static void decision(DiagnosticLogger logger, @Nullable Kind kind, Decision decision) {
		if (kind == null) return;
		logger.atDebug().event(Level.DEBUG, "server-config/transport", decision.code(),
			() -> "packet=" + kind.code());
	}

	@NotNullByDefault
	public enum Kind {
		REQUEST("request"),
		UPDATE("update"),
		SNAPSHOT("snapshot");

		private final String code;

		Kind(String code) {
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	@NotNullByDefault
	public enum Decision {
		CONNECTION_MISSING("connection-missing"),
		ROUTE_UNAVAILABLE("route-unavailable"),
		SEND_DISPATCHED("send-dispatched");

		private final String code;

		Decision(String code) {
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
