package nx.pingwheel.common.presentation.inventory;

/** Monotonic accounting periods. Reconfiguring cadence never refunds the current period. */
final class InventoryPeriodClock {
	private long period, next = -1;
	private int duration;
	boolean advance(long tick, int ticks) {
		if (ticks < 1) throw new IllegalArgumentException("period ticks");
		if (next < 0) { duration = ticks; next = Math.addExact(tick, ticks); return true; }
		if (duration != ticks) {
			next = Math.max(next, Math.addExact(tick, ticks)); duration = ticks;
		}
		if (tick < next) return false;
		long elapsed = 1 + (tick - next) / duration;
		period = Math.addExact(period, elapsed); next = Math.addExact(next, Math.multiplyExact(elapsed, duration));
		return true;
	}
	long period() { return period; }
}
