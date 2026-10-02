package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.InventorySettings;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Optional;

/**
	 * Pure state for the shared server-settings session, including inventory performance.
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
	private String syncDuration = "";
	private final EnumMap<Field, String> inventoryText = new EnumMap<>(Field.class);
	private final EnumMap<Field, Boolean> inventoryUnlimited = new EnumMap<>(Field.class);
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

	/**
	 * True when a safe authoritative snapshot is retained for display.  A
	 * read-only snapshot is viewable even though this viewer cannot edit it.
	 */
	public boolean canView() {
		return authoritative != null;
	}

	/**
	 * True when the retained snapshot may be edited.  Local permission is an
	 * edit capability only: a viewer below the required level still receives and
	 * renders the authoritative values.
	 */
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

	public String syncDurationText() {
		return syncDuration;
	}

	public String inventoryText(Field field) {
		return inventoryText.getOrDefault(field, "");
	}

	public boolean inventoryUnlimited(Field field) {
		return inventoryUnlimited.getOrDefault(field, false);
	}

	public void setInventoryText(Field field, String text) {
		if (!canEdit()) return;
		inventoryText.put(field, text);
		recomputeDirtyFields();
	}

	/** Mode and retained finite text are independent parts of one atomic leaf setting. */
	public void toggleInventoryUnlimited(Field field) {
		if (!canEdit() || !field.supportsUnlimited()) return;
		inventoryUnlimited.put(field, !inventoryUnlimited(field));
		recomputeDirtyFields();
	}

	/** Native step buttons use the confirmed piecewise grids without floating-point rounding. */
	public void stepInventoryValue(Field field, boolean forward) {
		if (!canEdit()) return;
		var parsed = inventoryDraftValue(field);
		if (parsed.isEmpty()) return;
		BigDecimal value = parsed.orElseThrow().value();
		if (field.isMultiplier()) {
			value = forward ? field.grid().next(value) : field.grid().previous(value);
		} else {
			int current = value.intValueExact();
			int step = field == Field.PENDING_MEMORY_MIB
				? InventorySettings.pendingMemoryStepMiB(forward ? current : Math.max(field.minimum(), current - 1)) : 1;
			value = BigDecimal.valueOf(Math.clamp((long) current + (forward ? step : -step), field.minimum(), field.maximum()));
		}
		setInventoryText(field, value.toPlainString());
	}

	private Optional<Value> inventoryDraftValue(Field field) {
		return field.parse(inventoryText(field), inventoryUnlimited(field));
	}

	/**
	 * Allocates and returns the positive request id for a newly entered server
	 * settings session, or the no-pending sentinel when a request cannot start.
	 * Any connected player may request the snapshot; the response's edit hint
	 * decides whether the retained view is editable.
	 */
	public long beginExpansion() {
		if (authoritativeAccessDenied || expanded) {
			return NO_PENDING_REQUEST;
		}

		expanded = true;
		loading = true;
		dirtyFields = 0;
		pendingRequestId = nextRequestId();
		return pendingRequestId;
	}

	/**
	 * Starts the shared server-settings session if it has neither a loaded nor an
	 * in-flight snapshot.  The expansion method remains the primitive used by
	 * focused model tests and by this session-oriented entry point.
	 */
	public long beginSessionIfNeeded() {
		if (loaded() || loading) {
			return NO_PENDING_REQUEST;
		}
		return beginExpansion();
	}

	/**
	 * Applies a snapshot only as the response to the currently loading session
	 * and only when its request id exactly matches the pending id.  A
	 * response that arrives after cancellation, disconnect, permission
	 * revocation, or a later expansion is stale and must not reopen the section.
	 * A safe {@code canEdit=false} response is retained as the read-only
	 * authoritative view instead of being discarded; for a locally privileged
	 * requester it is also recorded as a denial.
	 */
	public boolean applySnapshot(long requestId, ServerConfigSnapshot snapshot) {
		if (snapshot == null
			|| !snapshot.isSafe()
			|| requestId <= 0L
			|| !expanded
			|| !loading) {
			return false;
		}
		if (requestId != pendingRequestId) {
			return false;
		}

		pendingRequestId = NO_PENDING_REQUEST;
		loading = false;
		expanded = true;
		dirtyFields = 0;
		authoritative = snapshot;
		authoritativeAccessDenied = !snapshot.canEdit() && clientPermission;
		permissionRevokedAfterDenial = false;
		copyAuthoritativeToDraft();
		return true;
	}

	/**
	 * A client-side permission revocation immediately removes the ability to
	 * commit or edit and drops the draft, while a retained safe snapshot stays
	 * viewable read-only.  The server still checks permission for every packet,
	 * so this is only a UI safety and responsiveness measure.
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
		} else if (authoritativeAccessDenied && permissionRevokedAfterDenial) {
			// A stale local level can remain elevated after the server denied the
			// request.  Require an observed false -> true transition before
			// allowing a fresh expansion attempt.
			authoritativeAccessDenied = false;
			permissionRevokedAfterDenial = false;
			clearAuthoritative();
		} else if (authoritative != null && !authoritative.canEdit()) {
			// Promotion from a read-only viewer to an editor: the retained hint
			// was issued for the lower level, so a fresh request is required.
			clearAuthoritative();
		} else if (authoritative != null) {
			// An editable snapshot retained through the revocation is current
			// again; reopen the session with the draft reset to its values.
			expanded = true;
			dirtyFields = 0;
			copyAuthoritativeToDraft();
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

	private void clearAuthoritative() {
		authoritative = null;
		expanded = false;
		loading = false;
		pendingRequestId = NO_PENDING_REQUEST;
		dirtyFields = 0;
		clearDraft();
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

	public void setSyncDurationText(String value) {
		if (!canEdit()) {
			return;
		}

		syncDuration = value;
		recomputeDirtyFields();
	}

	/**
	 * Bitmask of dirty numeric draft fields that cannot be safely planned,
	 * or zero when no dirty numeric field is invalid. The original fields use
	 * non-negative integers; inventory leaves retain their own ranges and grids. The
	 * mask reuses the {@link ServerConfigUpdate} field constants so validation
	 * routing can identify the owning category and field without re-parsing the
	 * draft.
	 */
	public int invalidFieldMask() {
		if (!dirty()) {
			return 0;
		}

		int fields = 0;
		if (parseNonNegative(msToRegenerate).isEmpty()) {
			fields |= ServerConfigUpdate.MS_TO_REGENERATE;
		}
		if (parseNonNegative(rateLimit).isEmpty()) {
			fields |= ServerConfigUpdate.RATE_LIMIT;
		}
		if (parseNonNegative(syncDuration).isEmpty()) {
			fields |= ServerConfigUpdate.SYNC_DURATION;
		}
		for (Field field : Field.values()) {
			if ((dirtyFields & field.mask()) != 0 && inventoryDraftValue(field).isEmpty()) fields |= field.mask();
		}
		return fields;
	}

	public boolean hasInvalidDraft() {
		return invalidFieldMask() != 0;
	}

	public Optional<ServerConfigUpdate> updatePlan() {
		if (!canEdit() || !expanded || !dirty() || hasInvalidDraft()) {
			return Optional.empty();
		}

		var inventory = new EnumMap<Field, Value>(Field.class);
		for (Field field : Field.values()) inventory.put(field, inventoryDraftValue(field).orElseThrow());
		return Optional.of(new ServerConfigUpdate(
			dirtyFields,
			defaultChannelMode,
			playerTrackingEnabled,
			parseNonNegative(msToRegenerate).orElseThrow(),
			parseNonNegative(rateLimit).orElseThrow(),
			parseNonNegative(syncDuration).orElseThrow(),
			new InventoryConfigValues(inventory)));
	}

	public void markClean() {
		dirtyFields = 0;
	}

	private void copyAuthoritativeToDraft() {
		defaultChannelMode = authoritative.defaultChannelMode();
		playerTrackingEnabled = authoritative.playerTrackingEnabled();
		msToRegenerate = Integer.toString(authoritative.msToRegenerate());
		rateLimit = Integer.toString(authoritative.rateLimit());
		syncDuration = Integer.toString(authoritative.syncDuration());
		for (Field field : Field.values()) {
			Value value = authoritative.inventory().value(field);
			inventoryText.put(field, value.value().toPlainString());
			inventoryUnlimited.put(field, value.unlimited());
		}
	}

	private void clearDraft() {
		defaultChannelMode = null;
		playerTrackingEnabled = false;
		msToRegenerate = "";
		rateLimit = "";
		syncDuration = "";
		inventoryText.clear();
		inventoryUnlimited.clear();
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
		if (!matchesAuthoritative(syncDuration, authoritative.syncDuration())) {
			fields |= ServerConfigUpdate.SYNC_DURATION;
		}
		for (Field field : Field.values()) {
			if (!inventoryDraftValue(field).map(value -> value.equals(authoritative.inventory().value(field))).orElse(false)) {
				fields |= field.mask();
			}
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
