package nx.pingwheel.common.integration.externalblock;

import java.util.Objects;

/**
 * Pure routing for one external-marker refresh observation. Provider state is
 * retained while temporarily unavailable, updated only when the refreshed
 * target keeps the marker identity, and removed for every invalid observation.
 */
public final class ExternalBlockRefreshTriage {

	private ExternalBlockRefreshTriage() {
	}

	public enum Action {
		RETAIN,
		UPDATE,
		REMOVE
	}

	public static Action action(
		ExternalBlockServerProvider.RefreshResult result, boolean sameTargetKey
	) {
		Objects.requireNonNull(result, "result");

		if (result instanceof ExternalBlockServerProvider.RefreshResult.TemporarilyUnavailable) {
			return Action.RETAIN;
		}

		if (result instanceof ExternalBlockServerProvider.RefreshResult.Available && sameTargetKey) {
			return Action.UPDATE;
		}

		return Action.REMOVE;
	}
}
