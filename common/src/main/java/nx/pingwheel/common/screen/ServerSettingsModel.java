package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.InventorySizeScale;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Optional;

/**
	 * Pure state for the shared server-settings session, including inventory performance.
 * Permission changes, authoritative snapshots, dirty tracking, and the
 * update-plan preconditions live here rather than in Screen callbacks.
 */
@NotNullByDefault
public final class ServerSettingsModel {
	private static final long NO_PENDING_REQUEST = -1L;
	private static long requestIdSequence;

	private boolean clientPermission;
	private boolean authoritativeAccessDenied;
	private boolean permissionRevokedAfterDenial;
	private boolean expanded;
	private boolean loading;
	private long pendingRequestId = NO_PENDING_REQUEST;
	private @Nullable ServerConfigSnapshot authoritative;
	private @Nullable ChannelMode defaultChannelMode;
	private boolean playerTrackingEnabled;
	private boolean budgetDelayNotificationsEnabled;
	private String msToRegenerate = "";
	private String rateLimit = "";
	private String syncDuration = "";
	private final EnumMap<Field, String> inventoryText = new EnumMap<>(Field.class);
	private final EnumMap<Field, Boolean> inventoryUnlimited = new EnumMap<>(Field.class);
	private final EnumMap<Field, InventoryPerformanceScale> inventoryScales = new EnumMap<>(Field.class);
	private @Nullable SizeAdjustment sizeAdjustment;
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

	public @Nullable ServerConfigSnapshot authoritative() {
		return authoritative;
	}

	public @Nullable ChannelMode defaultChannelMode() {
		return defaultChannelMode;
	}

	public boolean playerTrackingEnabled() {
		return playerTrackingEnabled;
	}

	public boolean budgetDelayNotificationsEnabled() {
		return budgetDelayNotificationsEnabled;
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

	/** Stops live for the authoritative snapshot session, including its original off-grid finite value. */
	InventoryPerformanceScale inventoryScale(Field field) {
		InventoryPerformanceScale scale = inventoryScales.computeIfAbsent(field, InventoryPerformanceScale::forField);
		if (authoritative != null) scale = scale.including(authoritative.inventory().value(field));
		var current = inventoryDraftValue(field);
		if (current.isPresent()) scale = scale.including(current.orElseThrow());
		inventoryScales.put(field, scale);
		return scale;
	}

	int inventoryPosition(Field field) {
		var scale = inventoryScale(field);
		Value value = inventoryDraftValue(field).orElseGet(() -> authoritative != null
			? authoritative.inventory().value(field) : new Value(false, scale.finiteValues().getFirst()));
		return scale.positionOf(value);
	}

	/** One finite selection changes mode and value atomically; unlimited preserves the retained finite value. */
	void selectInventoryPosition(Field field, int position) {
		if (!canEdit() || field.isSizeScale()) return;
		selectInventoryPosition(field, inventoryScale(field), position);
	}

	/** A widget submits against its immutable stop list, not a later rebuilt index mapping. */
	void selectInventoryPosition(Field field, InventoryPerformanceScale scale, int position) {
		if (!canEdit() || field.isSizeScale() || !scale.belongsTo(field)) return;
		boolean unlimited = scale.isUnlimited(position);
		if (!unlimited) inventoryText.put(field, scale.finiteValueAt(position).toPlainString());
		inventoryUnlimited.put(field, unlimited);
		recomputeDirtyFields();
	}

	/**
	 * Records a draft text edit. A valid size-scale value is applied through the
	 * frame-priority pair rule instead of being stored raw; malformed or
	 * off-grid size text stays visible and invalid for correction.
	 */
	public void setInventoryText(Field field, String text) {
		if (!canEdit()) return;
		if (field.isSizeScale()) {
			var parsed = field.parse(text, false);
			if (parsed.isPresent()) {
				applySizeScaleValue(field, parsed.orElseThrow().value());
			} else {
				inventoryText.put(field, text);
				sizeAdjustment = null;
			}
		} else {
			inventoryText.put(field, text);
		}
		recomputeDirtyFields();
	}

	/** Mode and retained finite text are independent parts of one atomic leaf setting. */
	public void toggleInventoryUnlimited(Field field) {
		if (!canEdit() || !field.supportsUnlimited()) return;
		inventoryUnlimited.put(field, !inventoryUnlimited(field));
		recomputeDirtyFields();
	}

	/** The most recent frame-priority display adjustment, or null when none is shown. */
	@NotNullByDefault
	public record SizeAdjustment(BigDecimal displayMultiplier, BigDecimal frameMultiplier) {
	}

	/**
	 * The ordered size-scale slider position of a leaf's current draft value.
	 * Positions address the ordered legal values, never the quarter-multiplier
	 * wire quanta; a malformed draft falls back to the authoritative value and a
	 * missing snapshot to the scale default.
	 */
	public int sizeScalePosition(Field field) {
		return field.sizeScale().indexOf(currentSizeMultiplier(field));
	}

	/**
	 * The largest display slider position compatible with the current frame
	 * draft. The frame budget keeps priority and is never raised by the display.
	 */
	public int maximumDisplayPosition() {
		return InventorySizeScale.DISPLAY.indexOf(
			InventorySizeScale.maximumDisplayMultiplier(currentSizeMultiplier(Field.FRAME_BYTE_MULTIPLIER)));
	}

	/**
	 * Applies an ordered size-scale slider position through the frame-priority
	 * pair rule: a frame value lowers the display when needed, and a display
	 * value is clamped to the frame-compatible maximum. Both leaves and the
	 * notice are updated atomically.
	 */
	public void setSizeScalePosition(Field field, int position) {
		if (!canEdit() || !field.isSizeScale()) return;
		applySizeScaleValue(field, field.sizeScale().valueAt(position));
		recomputeDirtyFields();
	}

	/**
	 * The single supported size-scale write seam. A frame value reconciles the
	 * pair with frame priority; a display value is clamped to the largest
	 * compatible value. A malformed opposite draft stays visible and blocks the
	 * plan instead of being overwritten.
	 */
	private void applySizeScaleValue(Field field, BigDecimal value) {
		if (field == Field.FRAME_BYTE_MULTIPLIER) {
			var displayDraft = inventoryDraftValue(Field.DISPLAY_BYTE_MULTIPLIER);
			if (displayDraft.isEmpty()) {
				// The malformed display draft remains editable and invalid; the
				// frame value is applied without inventing a replacement display.
				inventoryText.put(Field.FRAME_BYTE_MULTIPLIER, value.toPlainString());
				sizeAdjustment = null;
				return;
			}
			var pair = InventorySizeScale.reconcile(displayDraft.orElseThrow().value(), value);
			inventoryText.put(Field.DISPLAY_BYTE_MULTIPLIER, pair.displayMultiplier().toPlainString());
			inventoryText.put(Field.FRAME_BYTE_MULTIPLIER, pair.frameMultiplier().toPlainString());
			sizeAdjustment = pair.displayReduced()
				? new SizeAdjustment(pair.displayMultiplier(), pair.frameMultiplier())
				: null;
		} else {
			BigDecimal maximum = InventorySizeScale.maximumDisplayMultiplier(
				currentSizeMultiplier(Field.FRAME_BYTE_MULTIPLIER));
			inventoryText.put(field, value.min(maximum).toPlainString());
			sizeAdjustment = null;
		}
	}

	/** The current frame-priority adjustment notice, or null when none is shown. */
	public @Nullable SizeAdjustment sizeAdjustment() {
		return sizeAdjustment;
	}

	private BigDecimal currentSizeMultiplier(Field field) {
		if (!field.isSizeScale()) {
			throw new IllegalArgumentException("not a size scale: " + field);
		}
		var parsed = inventoryDraftValue(field);
		if (parsed.isPresent()) {
			return parsed.orElseThrow().value();
		}
		if (authoritative != null) {
			return authoritative.inventory().value(field).value();
		}
		return field.sizeScale().defaultMultiplier();
	}

	/** Legacy text-model seam: step to the adjacent finite stop without changing unlimited mode. */
	public void stepInventoryValue(Field field, boolean forward) {
		if (!canEdit()) return;
		var parsed = inventoryDraftValue(field);
		if (parsed.isEmpty()) return;
		BigDecimal value = parsed.orElseThrow().value();
		if (field.isSizeScale()) {
			value = forward ? field.sizeScale().next(value) : field.sizeScale().previous(value);
		} else {
			var scale = inventoryScale(field);
			int current = scale.positionOf(new Value(false, value));
			int next = Math.clamp(current + (forward ? 1 : -1), 0, scale.finiteValues().size() - 1);
			value = scale.finiteValueAt(next);
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
	public boolean applySnapshot(long requestId, @Nullable ServerConfigSnapshot snapshot) {
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
		inventoryScales.clear();
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

	public void toggleBudgetDelayNotifications() {
		if (!canEdit()) {
			return;
		}

		budgetDelayNotificationsEnabled = !budgetDelayNotificationsEnabled;
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
			new InventoryConfigValues(inventory),
			budgetDelayNotificationsEnabled));
	}

	public void markClean() {
		dirtyFields = 0;
	}

	private void copyAuthoritativeToDraft() {
		defaultChannelMode = authoritative.defaultChannelMode();
		playerTrackingEnabled = authoritative.playerTrackingEnabled();
		budgetDelayNotificationsEnabled = authoritative.budgetDelayNotificationsEnabled();
		msToRegenerate = Integer.toString(authoritative.msToRegenerate());
		rateLimit = Integer.toString(authoritative.rateLimit());
		syncDuration = Integer.toString(authoritative.syncDuration());
		for (Field field : Field.values()) {
			Value value = authoritative.inventory().value(field);
			inventoryText.put(field, value.value().toPlainString());
			inventoryUnlimited.put(field, value.unlimited());
		}
		sizeAdjustment = null;
	}

	private void clearDraft() {
		defaultChannelMode = null;
		playerTrackingEnabled = false;
		budgetDelayNotificationsEnabled = false;
		msToRegenerate = "";
		rateLimit = "";
		syncDuration = "";
		inventoryText.clear();
		inventoryUnlimited.clear();
		inventoryScales.clear();
		sizeAdjustment = null;
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
		if (budgetDelayNotificationsEnabled != authoritative.budgetDelayNotificationsEnabled()) {
			fields |= ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED;
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

	private static Optional<Integer> parseNonNegative(@Nullable String value) {
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
