package nx.pingwheel.neoforge.integration.create;

import net.minecraft.world.level.BlockGetter;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import java.time.Duration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Create-free reflective boundary; unavailable native bounds never authorize coarse picking. */
@NotNullByDefault
final class CreateContraptionNativeBounds {
	private static final DiagnosticChannel REPORTS = DiagnosticChannel.interval("create-contraption-native-bounds", Duration.ofSeconds(10));

	private CreateContraptionNativeBounds() {}

	static @Nullable BlockGetter lookup(Class<?> contraptionType, Object contraption, DiagnosticLogger diagnostics) {
		try {
			Method method = contraptionType.getMethod("getContraptionWorld");
			@Nullable Object value = method.invoke(contraption);
			return value instanceof BlockGetter blockGetter ? blockGetter : null;
		} catch (NoSuchMethodException | IllegalAccessException failure) {
			report(contraptionType, failure, diagnostics);
			return null;
		} catch (InvocationTargetException failure) {
			@Nullable Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			report(contraptionType, failure, diagnostics);
			return null;
		}
	}

	private static void report(Class<?> type, Throwable failure, DiagnosticLogger diagnostics) {
		diagnostics.atNormal().rateLimited(REPORTS).failure(org.apache.logging.log4j.Level.WARN, "create-contraption-native-bounds",
			"native-bounds-unavailable", () -> "class=" + type.getName(), failure);
	}
}
