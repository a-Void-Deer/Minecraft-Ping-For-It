package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

@NotNullByDefault
final class NormalFailureType {
	private static final AtomicLong NEXT_TOKEN = new AtomicLong();
	private static final ClassValue<String> TOKENS = new ClassValue<>() {
		@Override
		protected String computeValue(Class<?> type) {
			if (type == NullPointerException.class) return "null-reference";
			if (type == IllegalArgumentException.class) return "invalid-argument";
			if (type == IllegalStateException.class) return "invalid-state";
			if (type == UnsupportedOperationException.class) return "unsupported";
			if (type == ClassNotFoundException.class) return "class-unavailable";
			if (type == NoClassDefFoundError.class) return "linkage-unavailable";
			if (type == java.io.IOException.class) return "io-failure";
			if (type == RuntimeException.class) return "runtime-failure";
			return "type-" + Long.toUnsignedString(NEXT_TOKEN.incrementAndGet(), 36);
		}
	};

	private NormalFailureType() {}

	static String token(@Nullable Throwable failure) {
		return failure == null ? "none" : TOKENS.get(failure.getClass());
	}
}
