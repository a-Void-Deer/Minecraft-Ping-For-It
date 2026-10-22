package nx.pingwheel.common.presentation.budget;

import java.util.Objects;

/** Detached, bounded diagnostics. Producers own per-cycle work/reason deduplication. */
public interface BudgetDelaySink {

	BudgetDelaySink NONE = new BudgetDelaySink() {
		@Override public void observe(DelayEvent event) {}
		@Override public void state(Module module, long cycleId, long nowNanos, DelayState state) {}
		@Override public void endCycle(Module module, long cycleId, long nowNanos) {}
	};

	void observe(DelayEvent event);
	void state(Module module, long cycleId, long nowNanos, DelayState state);
	void endCycle(Module module, long cycleId, long nowNanos);

	public enum Module { PRESENTATION, INVENTORY_PREVIEW, INVENTORY_TRACKING }
	public enum DelayState { DELAYED, HEALTHY, IDLE, NEUTRAL }

	public enum Reason {
		CAPTURE_COUNT, SOURCE_WORK, PER_CALL_ALLOWANCE, CAPTURE_DISABLED, CACHE_CAPACITY,
		PHYSICAL_SLOTS, LOGICAL_CLIENT_OR_TARGET, LOGICAL_GLOBAL, PROBE_WORK, PROVIDER_WORK,
		MEMORY, ROUND_CAPACITY, SUBJECT_CAPACITY, PUBLICATION_WORK, QUEUE_CAPACITY,
		WIRE_CLIENT_OR_STREAM, WIRE_GLOBAL, FRAME_OR_FRAGMENT_UNSERVICEABLE;

		public int bit() { return 1 << ordinal(); }
	}

	public enum Unit {
		MARKER_ADAPTER, REQUEST_ADAPTER, CONSUMER_WORK, PUBLICATION_WORK, FRAME,
		PHYSICAL_SLOT, LOGICAL_SLOT, ENTRY, BYTE, UNKNOWN
	}

	/** Safe scalar context only; never pass content, opaque locators or live world objects. */
	public record WorkItem(String identity, String description) {
		public WorkItem {
			identity = singleLine(identity);
			description = singleLine(description);
		}

		private static String singleLine(String text) {
			Objects.requireNonNull(text, "diagnostic context");
			StringBuilder result = new StringBuilder(Math.min(text.length(), 256));
			for (int offset = 0; offset < text.length() && result.length() < 256;) {
				int codePoint = text.codePointAt(offset);
				offset += Character.charCount(codePoint);
				int type = Character.getType(codePoint);
				if (Character.isISOControl(codePoint) || type == Character.FORMAT
					|| type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) continue;
				if (result.length() + Character.charCount(codePoint) > 256) break;
				result.appendCodePoint(codePoint);
			}
			return result.toString();
		}
	}

	public record Quantity(Unit unit, long amount, boolean known) {
		public Quantity {
			Objects.requireNonNull(unit, "unit");
			if (known && amount < 0) throw new IllegalArgumentException("negative known quantity");
		}

		public static Quantity unknown() { return new Quantity(Unit.UNKNOWN, 0, false); }
		public static Quantity of(Unit unit, long amount) { return new Quantity(unit, amount, true); }
	}

	public record DelayEvent(Module module, long cycleId, long nowNanos, WorkItem item,
		boolean firstItemObservation, int newReasonBits, Quantity quantity) {
		public DelayEvent {
			Objects.requireNonNull(module, "module");
			Objects.requireNonNull(item, "item");
			Objects.requireNonNull(quantity, "quantity");
			int validReasons = (1 << Reason.values().length) - 1;
			if (newReasonBits == 0 || (newReasonBits & ~validReasons) != 0)
				throw new IllegalArgumentException("empty or unknown delay reasons");
		}
	}
}
