package nx.pingwheel.common.client.outline;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Attempt-local live-instance resolution; failed handles never discard valid siblings. */
@NotNullByDefault
public final class FlywheelInstanceResolution {

	private FlywheelInstanceResolution() {}

	public static <I, R> Batch<R> resolve(List<I> instances, Function<I, @Nullable R> resolver) {
		List<R> live = new ArrayList<>();
		@Nullable Throwable firstFailure = null;
		int firstFailureIndex = -1;
		String firstFailureClass = "<none>";
		int failureCount = 0;
		int nonLiveCount = 0;
		for (int index = 0; index < instances.size(); index++) {
			try {
				@Nullable R resolved = resolver.apply(instances.get(index));
				if (resolved == null) {
					nonLiveCount++;
				} else {
					live.add(resolved);
				}
			} catch (Exception | LinkageError | AssertionError failure) {
				nonLiveCount++;
				failureCount++;
				if (firstFailure == null) {
					firstFailure = failure;
					firstFailureIndex = index;
					I instance = instances.get(index);
					firstFailureClass = instance == null ? "<null>" : instance.getClass().getName();
				}
			}
		}
		return new Batch<>(List.copyOf(live), nonLiveCount, failureCount, firstFailureIndex, firstFailureClass, firstFailure);
	}

	/** This transient batch is never cached by the adapter or diagnostics state. */
	@NotNullByDefault
	public record Batch<R>(@Unmodifiable List<R> live, int nonLiveCount, int failureCount,
		int firstFailureIndex, String firstFailureClass, @Nullable Throwable firstFailure) {

		public String failureDetails() {
			return "resolutionFailureCount=" + failureCount + "; firstFailureIndex=" + firstFailureIndex
				+ "; firstFailureClass=" + firstFailureClass
				+ "; liveSiblingCount=" + live.size() + "; retryNextFrame=true";
		}
	}
}
