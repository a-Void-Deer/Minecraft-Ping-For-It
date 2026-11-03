package nx.pingwheel.common.client.outline;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import nx.pingwheel.common.Global;

/**
 * Cached reflective probe for a {@code requestOutlineEffect()} hook on
 * {@code LevelRenderer}.
 *
 * <p>Vanilla 1.21.1 {@code LevelRenderer} has no such method, so the default
 * path reports {@code false} (method absent) without any log; this is the
 * normal case. When another mod augments the renderer with a public
 * {@code requestOutlineEffect()} method, the CommonClient bridge invokes it
	 * once per frame while entity or block outlines are live, and the render redirects use
 * that success signal in addition to vanilla's
 * {@code shouldShowEntityOutlines()} gate. The {@link Method} is resolved once
 * per class and cached; a reflection invocation failure is diagnosed and
 * treated as not-succeeded so the frame still degrades to the vanilla gate.</p>
 *
 * <p>Test seams: {@link #findMethod(Class)} and {@link #invoke(Object, Method)}
 * operate on an injected {@link Method} and target, so headless tests can pin
 * present/missing resolution and success/failure invocation without a game
 * client or the global cache. The cache lives only in {@link #request(Object)}
 * and can be reset via {@link #resetCache()}.</p>
 */
public final class LevelRendererOutlineRequest {

	public static final String METHOD_NAME = "requestOutlineEffect";
	private static final int MAX_CACHE_ENTRIES = 64;

	private static final Object LOCK = new Object();
	private static final Map<Class<?>, Optional<Method>> METHOD_CACHE =
		new LinkedHashMap<>(16, 0.75F, true);

	private LevelRendererOutlineRequest() {}

	/**
	 * Requests the entity outline effect on {@code target} (a
	 * {@code LevelRenderer}) using the cached reflective resolution. Returns
	 * {@code true} only when the method exists and its invocation succeeded;
	 * a missing method or a failed invocation returns {@code false}.
	 */
	public static boolean request(Object target) {
		Objects.requireNonNull(target, "target");
		return invoke(target, resolveMethod(target.getClass(), LevelRendererOutlineRequest::findMethod));
	}

	/**
	 * Resolves the cached {@code requestOutlineEffect()} method for
	 * {@code type}; returns {@code null} when absent. The cache is keyed by
	 * the actual runtime class and stores missing resolutions as well, so an absent
	 * hook on one renderer class cannot poison a later class that provides it.
	 */
	public static Method resolveMethod(Class<?> type, Function<Class<?>, Method> finder) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(finder, "finder");

		synchronized (LOCK) {
			Optional<Method> cached = METHOD_CACHE.get(type);
			if (cached != null) {
				return cached.orElse(null);
			}

			Method method = finder.apply(type);
			METHOD_CACHE.put(type, Optional.ofNullable(method));
			if (METHOD_CACHE.size() > MAX_CACHE_ENTRIES) {
				METHOD_CACHE.remove(METHOD_CACHE.keySet().iterator().next());
			}

			return method;
		}
	}

	/** Public-method lookup of {@code requestOutlineEffect}; {@code null} when absent. */
	public static Method findMethod(Class<?> type) {
		Objects.requireNonNull(type, "type");
		try {
			return type.getMethod(METHOD_NAME);
		} catch (NoSuchMethodException ignored) {
			// Missing hook is the normal vanilla case; do not log.
			return null;
		}
	}

	/**
	 * Invokes {@code method} on {@code target}; a {@code null} method (absent
	 * hook) is a normal no-op returning {@code false}. Any reflective
	 * invocation failure is diagnosed and reported as {@code false}.
	 */
	public static boolean invoke(Object target, Method method) {
		Objects.requireNonNull(target, "target");

		if (method == null) {
			return false;
		}

		try {
			method.invoke(target);
			return true;
		} catch (Exception | LinkageError | AssertionError failure) {
			Global.LOGGER.warn(
				"entity outline effect request failed; method=" + method.getName()
					+ "; target=" + target.getClass().getName(),
				failure);
			return false;
		}
	}

	/** Test seam: drops the cached resolution so a later request re-resolves. */
	public static void resetCache() {
		synchronized (LOCK) {
			METHOD_CACHE.clear();
		}
	}
}
