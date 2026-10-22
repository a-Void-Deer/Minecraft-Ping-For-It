package nx.pingwheel.common.presentation.preview;

import java.util.Map;
import java.util.Set;
import nx.pingwheel.common.domain.Target;

/** Fixed, explicitly registered local readers. No sender, store, or reflective dump. */
public interface PreviewFieldAccess {
	String adapterId();
	Map<String, Outcome> observe(Target target, Set<String> authorizedRoots, ReadContext context);
	interface ReadContext {
		Object levelIdentity();
		String dimensionId();
		long tick();
	}
	sealed interface Outcome permits Observed, Missing {}
	record Observed(PreviewObservation observation) implements Outcome {
		public Observed { java.util.Objects.requireNonNull(observation); }
	}
	enum Missing implements Outcome { UNAVAILABLE, NOT_APPLICABLE, PENDING, DEFERRED }
}
