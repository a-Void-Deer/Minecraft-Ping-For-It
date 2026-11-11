package nx.pingwheel.common.network;

import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.DiagnosticLogger.NormalSummary;
import nx.pingwheel.common.diagnostics.SafeDiagnosticValue;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;
import java.util.Objects;

/**
 * One inventory-v2 frame that exceeded a finite wire bound. The exception is
 * classified during decoding and reported once at the decode boundary; it
 * carries only the bound identity and numeric requested/limit values, never
 * frame bytes or field text. A missing, negative, truncated or unknown value
 * remains an ordinary malformed frame and is not this limit.
 */
@NotNullByDefault
public final class InventoryWireLimitException extends IllegalArgumentException {
	/** Finite wire bounds. Each bound keeps its own channel and maps to the existing failure vocabulary. */
	public enum Bound {
		C2S_FRAME("c2s-frame", InventoryFailureReason.FRAME_LIMIT),
		S2C_FRAME("s2c-frame", InventoryFailureReason.FRAME_LIMIT),
		S2C_ENTRY_PAYLOAD("s2c-entry-payload", InventoryFailureReason.FRAME_LIMIT),
		ENTRY_COUNT("entry-count", InventoryFailureReason.ENTRY_LIMIT),
		PART_COUNT("part-count", InventoryFailureReason.WIRE_LIMIT),
		KEY_BYTES("key-bytes", InventoryFailureReason.WIRE_LIMIT),
		ID_BYTES("id-bytes", InventoryFailureReason.WIRE_LIMIT),
		LABEL_BYTES("label-bytes", InventoryFailureReason.WIRE_LIMIT),
		DISPLAY_BYTES("display-bytes", InventoryFailureReason.DISPLAY_LIMIT),
		ANNOTATION_BYTES("annotation-bytes", InventoryFailureReason.WIRE_LIMIT),
		ENTRY_BYTES("entry-bytes", InventoryFailureReason.ENTRY_LIMIT);

		private final String code;
		private final InventoryFailureReason reason;
		private final DiagnosticChannel channel;

		Bound(String code, InventoryFailureReason reason) {
			this.code = code;
			this.reason = reason;
			this.channel = DiagnosticChannel.interval("inventory-wire-limit/" + code, Duration.ofSeconds(10));
		}

		public String code() {
			return code;
		}

		public InventoryFailureReason reason() {
			return reason;
		}
	}

	private static final String SITE = "packet-decode";
	private static final String HANDLING = "wire-limit";
	private final Bound bound;
	private final long requested;
	private final long limit;

	public InventoryWireLimitException(Bound bound, long requested, long limit) {
		super("Inventory wire bound " + Objects.requireNonNull(bound, "bound").code()
			+ " exceeded: requested=" + requested + " limit=" + limit);
		this.bound = bound;
		this.requested = requested;
		this.limit = limit;
	}

	public Bound bound() {
		return bound;
	}

	public InventoryFailureReason reason() {
		return bound.reason();
	}

	public long requested() {
		return requested;
	}

	public long limit() {
		return limit;
	}

	/**
	 * Reports one limit occurrence through the caller's logical side. The
	 * effective DEBUG profile admits every occurrence by bypassing the bound's
	 * interval channel; other profiles do not admit this limit site.
	 */
	boolean report(DiagnosticLogger consumer) {
		NormalSummary summary = new NormalSummary("bound={} reason={} requested={} limit={}",
			new SafeDiagnosticValue[] {
				SafeDiagnosticValue.code(bound.code()),
				SafeDiagnosticValue.code(bound.reason().code()),
				SafeDiagnosticValue.number(requested),
				SafeDiagnosticValue.number(limit)
			});
		return consumer.atDebug().rateLimited(bound.channel)
			.reportByProfile(Level.DEBUG, SITE, HANDLING, summary, null, this, true);
	}
}
