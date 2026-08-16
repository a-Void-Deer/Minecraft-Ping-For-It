package nx.pingwheel.common.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, cycle-safe root-cause extraction for privacy-safe fail-soft
 * diagnostic logging.
 *
 * <p>The walk follows {@link Throwable#getCause()} until the chain ends,
 * with two hard stops:
 * <ul>
 *   <li>a depth bound of {@value #MAX_DEPTH} hops, so a pathological chain
 *       can never make logging expensive; and</li>
 *   <li>an identity-based visited set, so a crafted or corrupt cause chain
 *       that loops back on itself (possible through serialization or
 *       {@code getCause} overrides, never through {@code initCause}) still
 *       terminates.</li>
 * </ul>
 *
 * <p>Only class names are ever derived from the result; callers must not log
 * throwable messages or stacks through this helper.
 * {@link #rootCauseClassName} can never throw: any failure raised while
 * traversing a chain degrades to the top-level throwable's class name.
 * Pure Java, no Minecraft dependency, safe for headless tests.
 */
public final class ThrowableCauses {

	/** Maximum cause-chain depth traversed before the walk stops. */
	public static final int MAX_DEPTH = 16;

	private ThrowableCauses() {}

	/**
	 * The last throwable reached at the bottom of {@code throwable}'s cause
	 * chain, or {@code throwable} itself when it has no cause. The walk is
	 * bounded ({@value #MAX_DEPTH} hops) and cycle-safe.
	 */
	public static Throwable rootCause(Throwable throwable) {
		Objects.requireNonNull(throwable, "throwable");

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = throwable;

		for (int depth = 0; depth < MAX_DEPTH; depth++) {
			visited.add(current);

			Throwable cause = current.getCause();

			if (cause == null || visited.contains(cause)) {
				return current;
			}

			current = cause;
		}

		return current;
	}

	/**
	 * The root cause's class name and nothing else: never a message, stack,
	 * or any payload-derived data, so this is safe for client logging
	 * policies that forbid sensitive content.
	 *
	 * <p>Never throws: a hostile or corrupt {@code getCause()} override that
	 * throws while the walk traverses the chain must not be able to break
	 * the logging call. Any such failure falls back to the top-level
	 * throwable's own class name, which is still a plain class name.
	 */
	public static String rootCauseClassName(Throwable throwable) {
		Objects.requireNonNull(throwable, "throwable");

		try {
			return rootCause(throwable).getClass().getName();
		} catch (Throwable ignored) {
			// Contain only the diagnostic walk itself: the traversal is the
			// failing operation here, so any throwable raised by a hostile
			// getCause override degrades to the top-level class name instead
			// of escaping the logging call. Callers (the renderer's
			// fail-soft catches) are deliberately not widened by this.
			return throwable.getClass().getName();
		}
	}
}
