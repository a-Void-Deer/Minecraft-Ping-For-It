package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;

import java.util.Optional;

/**
 * Pure state for the collapsible server section of the settings screen.
 * Permission changes, authoritative snapshots, dirty tracking, and the
 * update-plan preconditions live here rather than in Screen callbacks.
 */
public final class ServerSettingsModel {
	private static final long NO_PENDING_REQUEST = -1L;
	private static long requestIdSequence;

	private boolean clientPermission;
	private boolean authoritativeAccessDenied;
	private boolean permissionRevokedAfterDenial;
	private boolean expanded;
	private boolean loading;
	private long pendingRequestId = NO_PENDING_REQUEST;
	private ServerConfigSnapshot authoritative;
	private ChannelMode defaultChannelMode;
	private boolean playerTrackingEnabled;
	private String msToRegenerate = "";
	private String rateLimit = "";
	private int dirtyFields;

	public ServerSettingsModel(boolean clientPermission) {
		this.clientPermission = clientPermission;
	}

	public boolean clientPermission() {
		return clientPermission;
	}

	/** True after a correlated server response denied access for this session. */
	public boolean accessDenied() {
		return authoritativeAccessDenied;
	}

	public boolean expanded() {
		return expanded;
	}

	public boolean loading() {
		return loading;
	}

	public long pendingRequestId() {
		return pendingRequestId;
	}

	public boolean loaded() {
		return authoritative != null && !loading;
	}

	public boolean canEdit() {
		return clientPermission && !authoritativeAccessDenied && loaded() && authoritative.canEdit();
	}

	public int dirtyFields() {
		return dirtyFields;
	}

	public boolean dirty() {
		return dirtyFields != 0;
	}

	public ServerConfigSnapshot authoritative() {
		return authoritative;
	}

	public ChannelMode defaultChannelMode() {
		return defaultChannelMode;
	}

	public boolean playerTrackingEnabled() {
		return playerTrackingEnabled;
	}

	public String msToRegenerateText() {
		return msToRegenerate;
	}

	public String rateLimitText() {
		return rateLimit;
	}

	/**
	 * Allocates and returns the positive request id for a newly clicked header,
	 * or the no-pending sentinel when the header cannot start an expansion
	 * request.
	 */
	public long beginExpansion() {
		if (!clientPermission || authoritativeAccessDenied || expanded) {
			return NO_PENDING_REQUEST;
		}

		expanded = true;
		loading = true;
		dirtyFields = 0;
		pendingRequestId = nextRequestId();
		return pendingRequestId;
	}

	/**
	 * Applies a snapshot only as the response to the currently loading
	 * expansion and only when its request id exactly matches the pending id.  A
	 * response that arrives after cancellation, disconnect, permission
	 * revocation, or a later expansion is stale and must not reopen the section.
	 */
	public boolean applySnapshot(long requestId, ServerConfigSnapshot snapshot) {
		if (snapshot == null
			|| !snapshot.isSafe()
			|| requestId <= 0L
			|| !clientPermission
			|| !expanded
			|| !loading) {
			return false;
		}
		if (requestId != pendingRequestId) {
			return false;
		}

		pendingRequestId = NO_PENDING_REQUEST;
		if (!snapshot.canEdit()) {
			authoritative = null;
			authoritativeAccessDenied = true;
			permissionRevokedAfterDenial = false;
			collapseAndDiscard();
			clearDraft();
			return true;
		}

		authoritative = snapshot;
		expanded = true;
		loading = false;
		dirtyFields = 0;
		copyAuthoritativeToDraft();
		return true;
	}

	/**
	 * A client-side permission revocation immediately closes the editable
	 * section and drops its draft.  The server still checks permission for every
	 * packet, so this is only a UI safety and responsiveness measure.
	 */
	public void setClientPermission(boolean permission) {
		if (clientPermission == permission) {
			return;
		}

		clientPermission = permission;
		if (!permission) {
			if (authoritativeAccessDenied) {
				permissionRevokedAfterDenial = true;
			}
			collapseAndDiscard();
			authoritative = null;
			clearDraft();
		} else if (authoritativeAccessDenied && permissionRevokedAfterDenial) {
			// A stale local level can remain elevated after the server denied the
			// request.  Require an observed false -> true transition before
			// allowing a fresh expansion attempt.
			authoritativeAccessDenied = false;
			permissionRevokedAfterDenial = false;
		}
	}

	public void collapseAndDiscard() {
		expanded = false;
		loading = false;
		pendingRequestId = NO_PENDING_REQUEST;
		dirtyFields = 0;
		if (authoritative != null) {
			copyAuthoritativeToDraft();
		}
	}

	/** Clears all connection-scoped server state after leaving a world. */
	public void resetForDisconnect() {
		clientPermission = false;
		authoritativeAccessDenied = false;
		permissionRevokedAfterDenial = false;
		expanded = false;
		loading = false;
		pendingRequestId = NO_PENDING_REQUEST;
		authoritative = null;
		dirtyFields = 0;
		clearDraft();
	}

	public void cycleDefaultChannelMode() {
		if (!canEdit()) {
			return;
		}

		ChannelMode[] modes = ChannelMode.values();
		int current = defaultChannelMode == null ? 0 : defaultChannelMode.ordinal();
		defaultChannelMode = modes[(current + 1) % modes.length];
		recomputeDirtyFields();
	}

	public void togglePlayerTracking() {
		if (!canEdit()) {
			return;
		}

		playerTrackingEnabled = !playerTrackingEnabled;
		recomputeDirtyFields();
	}

	public void setMsToRegenerateText(String value) {
		if (!canEdit()) {
			return;
		}

		msToRegenerate = value;
		recomputeDirtyFields();
	}

	public void setRateLimitText(String value) {
		if (!canEdit()) {
			return;
		}

		rateLimit = value;
		recomputeDirtyFields();
	}

	public boolean hasInvalidDraft() {
		return dirty()
			&& (parseNonNegative(msToRegenerate).isEmpty() || parseNonNegative(rateLimit).isEmpty());
	}

	public Optional<ServerConfigUpdate> updatePlan() {
		if (!canEdit() || !expanded || !dirty() || hasInvalidDraft()) {
			return Optional.empty();
		}

		return Optional.of(new ServerConfigUpdate(
			dirtyFields,
			defaultChannelMode,
			playerTrackingEnabled,
			parseNonNegative(msToRegenerate).orElseThrow(),
			parseNonNegative(rateLimit).orElseThrow()));
	}

	public void markClean() {
		dirtyFields = 0;
	}

	private void copyAuthoritativeToDraft() {
		defaultChannelMode = authoritative.defaultChannelMode();
		playerTrackingEnabled = authoritative.playerTrackingEnabled();
		msToRegenerate = Integer.toString(authoritative.msToRegenerate());
		rateLimit = Integer.toString(authoritative.rateLimit());
	}

	private void clearDraft() {
		defaultChannelMode = null;
		playerTrackingEnabled = false;
		msToRegenerate = "";
		rateLimit = "";
	}

	private void recomputeDirtyFields() {
		if (authoritative == null) {
			dirtyFields = 0;
			return;
		}

		int fields = 0;
		if (defaultChannelMode != authoritative.defaultChannelMode()) {
			fields |= ServerConfigUpdate.DEFAULT_CHANNEL_MODE;
		}
		if (playerTrackingEnabled != authoritative.playerTrackingEnabled()) {
			fields |= ServerConfigUpdate.PLAYER_TRACKING_ENABLED;
		}
		if (!matchesAuthoritative(msToRegenerate, authoritative.msToRegenerate())) {
			fields |= ServerConfigUpdate.MS_TO_REGENERATE;
		}
		if (!matchesAuthoritative(rateLimit, authoritative.rateLimit())) {
			fields |= ServerConfigUpdate.RATE_LIMIT;
		}
		dirtyFields = fields;
	}

	private static boolean matchesAuthoritative(String value, int authoritativeValue) {
		return parseNonNegative(value)
			.map(parsed -> parsed == authoritativeValue)
			.orElse(false);
	}

	private static Optional<Integer> parseNonNegative(String value) {
		if (value == null || value.isEmpty()) {
			return Optional.empty();
		}

		try {
			int parsed = Integer.parseInt(value);
			return parsed >= 0 ? Optional.of(parsed) : Optional.empty();
		} catch (NumberFormatException ignored) {
			return Optional.empty();
		}
	}

	private static synchronized long nextRequestId() {
		if (requestIdSequence == Long.MAX_VALUE) {
			requestIdSequence = 1L;
		} else {
			requestIdSequence++;
		}

		return requestIdSequence;
	}

	static synchronized void setRequestIdSequenceForTesting(long sequence) {
		requestIdSequence = sequence;
	}
}
