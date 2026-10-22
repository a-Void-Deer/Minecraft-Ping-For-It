package nx.pingwheel.neoforge.integration.create;

import net.minecraft.world.level.LevelAccessor;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Cached optional method shape; a probe failure is reported once, outside the cache lock. */
@NotNullByDefault
final class CreateEntityOutlineBackendProbe {

	private final String managerClassName;
	private final String methodName;
	private final ClassLookup lookup;
	private volatile @Nullable Availability availability;

	CreateEntityOutlineBackendProbe(String managerClassName, String methodName) {
		this(managerClassName, methodName,
			name -> Class.forName(name, false, CreateEntityOutlineBackendProbe.class.getClassLoader()));
	}

	CreateEntityOutlineBackendProbe(String managerClassName, String methodName, ClassLookup lookup) {
		this.managerClassName = managerClassName;
		this.methodName = methodName;
		this.lookup = lookup;
	}

	Evaluation evaluate(LevelAccessor level) throws ReflectiveOperationException {
		Availability current = availability();
		if (!current.available()) {
			return new Evaluation(false, false, current.description());
		}
		return new Evaluation(true, invokeSupports(Objects.requireNonNull(current.method()), level), current.description());
	}

	Availability availability() {
		@Nullable Availability current = availability;
		if (current != null) {
			return current;
		}
		@Nullable Throwable failure = null;
		synchronized (this) {
			current = availability;
			if (current == null) {
				Resolution resolution = resolve();
				current = resolution.availability();
				availability = current;
				failure = resolution.failure();
			}
		}
		if (failure != null) {
			String description = current.description();
			Diagnostics.warn("create-entity-backend-probe", managerClassName, () -> description, failure);
		}
		return current;
	}

	private Resolution resolve() {
		try {
			Class<?> managerClass = lookup.load(managerClassName);
			Method method = managerClass.getMethod(methodName, LevelAccessor.class);
			if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class) {
				return unavailable("method-signature-invalid", null);
			}
			return new Resolution(new Availability(method, true, "present"), null);
		} catch (ClassNotFoundException ignored) {
			return unavailable("class-missing", null);
		} catch (NoSuchMethodException failure) {
			return unavailable("method-missing", failure);
		} catch (LinkageError | SecurityException | AssertionError failure) {
			return unavailable("probe-failed", failure);
		}
	}

	private static Resolution unavailable(String description, @Nullable Throwable failure) {
		return new Resolution(new Availability(null, false, description), failure);
	}

	String diagnosticDescription() {
		@Nullable Availability current = availability;
		return current == null ? "not-probed" : current.description();
	}

	private static boolean invokeSupports(Method method, LevelAccessor level) throws ReflectiveOperationException {
		try {
			@Nullable Object result = method.invoke(null, level);
			if (!(result instanceof Boolean supported)) {
				throw new ReflectiveOperationException("supportsVisualization returned a non-boolean value: " + result);
			}
			return supported;
		} catch (InvocationTargetException failure) {
			@Nullable Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			if (cause instanceof Error errorFailure) {
				throw errorFailure;
			}
			if (cause == null) {
				throw new ReflectiveOperationException("supportsVisualization failed without a cause", failure);
			}
			throw new ReflectiveOperationException("supportsVisualization threw a checked exception", cause);
		}
	}

	@NotNullByDefault
	@FunctionalInterface
	interface ClassLookup {
		Class<?> load(String name) throws ClassNotFoundException;
	}

	@NotNullByDefault
	record Availability(@Nullable Method method, boolean available, String description) {}

	@NotNullByDefault
	record Evaluation(boolean available, boolean enabled, String description) {}

	/** Only the thread performing the probe holds this transient result. */
	@NotNullByDefault
	private record Resolution(Availability availability, @Nullable Throwable failure) {}
}
