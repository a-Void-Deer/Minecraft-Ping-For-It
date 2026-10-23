package nx.pingwheel.neoforge.integration.create;

import net.minecraft.world.level.BlockGetter;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Create-free reflective boundary; unavailable native bounds never authorize coarse picking. */
@NotNullByDefault
final class CreateContraptionNativeBounds {

	private CreateContraptionNativeBounds() {}

	static @Nullable BlockGetter lookup(Class<?> contraptionType, Object contraption) {
		try {
			Method method = contraptionType.getMethod("getContraptionWorld");
			@Nullable Object value = method.invoke(contraption);
			return value instanceof BlockGetter blockGetter ? blockGetter : null;
		} catch (NoSuchMethodException | IllegalAccessException failure) {
			report(contraptionType, failure);
			return null;
		} catch (InvocationTargetException failure) {
			@Nullable Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			report(contraptionType, failure);
			return null;
		}
	}

	private static void report(Class<?> type, Throwable failure) {
		Diagnostics.warn("create-contraption-native-bounds", type.getName(),
			() -> "getContraptionWorld unavailable; ownedCandidateRemainsUnavailable=true", failure);
	}
}
