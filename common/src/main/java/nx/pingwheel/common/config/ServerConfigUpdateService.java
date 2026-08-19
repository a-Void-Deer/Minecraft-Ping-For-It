package nx.pingwheel.common.config;

/** Pure authority/merge seam for server settings updates. */
public final class ServerConfigUpdateService {
	private ServerConfigUpdateService() {}

	public record Result(boolean applied, ServerConfigSnapshot snapshot) {}

	/**
	 * Permission is supplied by the authenticated server-side caller.  The
	 * method never treats a client flag as authority and always returns a safe
	 * snapshot for the caller.
	 */
	public static Result apply(
		boolean hasPermission,
		ServerConfigSnapshot current,
		ServerConfigUpdate update) {
		if (current == null) {
			return new Result(false, null);
		}

		final var authoritative = current.withCanEdit(hasPermission);
		if (!hasPermission || update == null || !update.isValid()) {
			return new Result(false, authoritative);
		}

		return new Result(true, update.applyTo(authoritative));
	}
}
