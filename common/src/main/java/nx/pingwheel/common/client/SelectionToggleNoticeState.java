package nx.pingwheel.common.client;

import java.util.Optional;
import java.util.function.LongSupplier;

import nx.pingwheel.common.resource.LanguageUtils;

/**
 * The one-slot state machine for the notices emitted by target-selection
 * toggle key presses.  It deliberately stores semantic data rather than a
 * translated component so a language change is reflected on the next frame.
 */
public final class SelectionToggleNoticeState {
	public static final long OPAQUE_MILLIS = 2_000L;
	public static final long FADE_MILLIS = 1_000L;
	public static final long TOTAL_MILLIS = OPAQUE_MILLIS + FADE_MILLIS;

	public static final SelectionToggleNoticeState INSTANCE =
		new SelectionToggleNoticeState(SelectionToggleNoticeState::monotonicMillis);

	private final LongSupplier clockMillis;
	private Notice notice;

	public SelectionToggleNoticeState(LongSupplier clockMillis) {
		this.clockMillis = clockMillis;
	}

	public synchronized void show(Kind kind, boolean enabled) {
		show(kind, enabled, clockMillis.getAsLong());
	}

	public synchronized void show(Kind kind, boolean enabled, long shownAtMillis) {
		if (kind == null) {
			return;
		}

		// Replacing this value is intentional: there is never a queue of notices.
		notice = new Notice(kind, enabled, shownAtMillis);
	}

	public synchronized Optional<Snapshot> snapshot() {
		return snapshot(clockMillis.getAsLong());
	}

	public synchronized Optional<Snapshot> snapshot(long nowMillis) {
		if (notice == null) {
			return Optional.empty();
		}

		long elapsed = nowMillis - notice.shownAtMillis();
		if (elapsed >= TOTAL_MILLIS) {
			notice = null;
			return Optional.empty();
		}

		return Optional.of(new Snapshot(notice.kind(), notice.enabled(), alphaAt(elapsed)));
	}

	public synchronized void clear() {
		notice = null;
	}

	/** Returns an ARGB alpha byte for the supplied elapsed time. */
	public static int alphaAt(long elapsedMillis) {
		if (elapsedMillis < OPAQUE_MILLIS) {
			return 255;
		}
		if (elapsedMillis >= TOTAL_MILLIS) {
			return 0;
		}

		long remaining = TOTAL_MILLIS - elapsedMillis;
		// Ceiling keeps the last millisecond of the fade visible while the
		// terminal boundary remains exactly transparent.
		return (int) Math.ceil(255.0D * remaining / FADE_MILLIS);
	}

	private static long monotonicMillis() {
		return System.nanoTime() / 1_000_000L;
	}

	public enum Kind {
		PASS_THROUGH_TRANSPARENT_BLOCKS(LanguageUtils.keyOf("notice", "pass_through_transparent_blocks")),
		MARK_BLACKLISTED_TARGETS(LanguageUtils.keyOf("notice", "mark_blacklisted_targets")),
		MARK_FLUIDS(LanguageUtils.keyOf("notice", "mark_fluids"));

		private final String translationKey;

		Kind(String translationKey) {
			this.translationKey = translationKey;
		}

		public String translationKey() {
			return translationKey;
		}
	}

	public record Snapshot(Kind kind, boolean enabled, int alpha) {}

	private record Notice(Kind kind, boolean enabled, long shownAtMillis) {}
}
