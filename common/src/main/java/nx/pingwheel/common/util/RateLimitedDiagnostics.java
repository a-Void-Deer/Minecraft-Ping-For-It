package nx.pingwheel.common.util;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Compatibility entry points; all state and logging belong to {@link Diagnostics}. */
@NotNullByDefault
public final class RateLimitedDiagnostics {

	private RateLimitedDiagnostics() {}

	public static void debug(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		Diagnostics.debug(operation, scope, context, failure);
	}

	public static void warn(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		Diagnostics.warn(operation, scope, context, failure);
	}

	public static void error(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		Diagnostics.error(operation, scope, context, failure);
	}
}
