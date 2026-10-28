package nx.pingwheel.common.marker;

import java.util.UUID;
import nx.pingwheel.common.domain.Target;
import org.jetbrains.annotations.NotNullByDefault;

/** Creation-only server authority. Never called by preview, OPEN or marker refresh. */
@NotNullByDefault
@FunctionalInterface
public interface TargetGroupingResolver {
	TargetKey resolve(UUID owner, Target committedTarget);

	/** Interim production behavior until authoritative topology providers are installed. */
	static TargetGroupingResolver exact() {
		return (owner, target) -> TargetKey.from(target);
	}
}
