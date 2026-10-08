package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.state.SelectorReleaseProposal.Admission;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.presentation.PresentationLimits;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.render.SpatialInventoryLayout;
import nx.pingwheel.common.render.SpatialInventoryView;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

/**
 * Headless native selector facade over the radial controller and streamed list.
 * Capture allocation, observations, authorization, networking and actual-open
 * lifecycle guards are external. This instance can open and release only once.
	 * All pointer/layout positions are GUI-centre-relative; an opaque level identity
	 * fences live updates but grants no world access to this model.
 *
 * @param <R> detached opaque inventory-selection reference owned by the backend
 */
public final class SpatialSelectorSession<R> implements NativeSelectorInput.Sink {

	/** Narrow capture port, not another identity model or candidate allocator. */
	public record CapturedTarget(String candidateId, ResolvedTarget resolvedTarget,
		Optional<BlockFace> face, Optional<WorldVector> hit) {
		public CapturedTarget {
			requireId(candidateId);
			Objects.requireNonNull(resolvedTarget); Objects.requireNonNull(face); Objects.requireNonNull(hit);
			if (face.isPresent() && !(resolvedTarget.target() instanceof Target.BlockTarget))
				throw new IllegalArgumentException("a hit face belongs only to an ordinary block");
			hit.ifPresent(point -> {
				if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z()))
					throw new IllegalArgumentException("hit must be finite");
			});
		}
	}

	/** A target/request/session fence supplied by the observation owner. */
	public record ContentFence(long selectorSession, long requestId, long previewSession, String candidateId) {
		public ContentFence { requireId(candidateId); }
	}

	/** Only already-authorized detached values and effective property Ping Types enter here. */
	public record Property(String id, String labelKey, PresentationPropertyRef ref,
		PresentationValue observedValue, List<PingType> allowedTypes, PingType defaultType) {
		public Property {
			requireId(id); Objects.requireNonNull(labelKey); Objects.requireNonNull(ref); Objects.requireNonNull(observedValue);
			PresentationLimits.validate(observedValue);
			allowedTypes = checkedTypes(allowedTypes, defaultType);
		}
	}

	/** Count is display-only; null is unknown, never synthesized to zero or sent. */
	public record InventoryRow<R>(String key, String label, Long count, String itemId, Status quality, R reference) {
		public InventoryRow {
			requireId(key); Objects.requireNonNull(label); Objects.requireNonNull(quality); Objects.requireNonNull(reference);
			if (count != null && count < 0) throw new IllegalArgumentException("negative display count");
		}
	}

	/** Rows may be a sparse discovery/update batch; status owns completeness independently. */
	public record InventoryPreview<R>(String labelKey, Status status, List<InventoryRow<R>> rows,
		List<PingType> allowedItemTypes, PingType defaultItemType) {
		public InventoryPreview {
			Objects.requireNonNull(labelKey); Objects.requireNonNull(status); rows = List.copyOf(rows);
			allowedItemTypes = checkedTypes(allowedItemTypes, defaultItemType);
			Set<String> seen = new HashSet<>();
			for (InventoryRow<R> row : rows)
				if (!seen.add(row.key())) throw new IllegalArgumentException("duplicate inventory row");
		}
	}

	/**
	 * A complete authorized property projection plus an optional sparse inventory
	 * batch. An unavailable/invalid/expired update, or reset, clears old rows and
	 * references before applying data. Revision is monotonic within this fence.
	 */
	public record ContentProjection<R>(ContentFence fence, long revision, boolean reset, Status status,
		List<Property> properties, InventoryPreview<R> inventory) {
		public ContentProjection {
			Objects.requireNonNull(fence); Objects.requireNonNull(status); properties = List.copyOf(properties);
			Set<String> seen = new HashSet<>();
			for (Property property : properties)
				if (!seen.add(property.id())) throw new IllegalArgumentException("duplicate property");
		}
	}

	/**
	 * Must prefer actual authorized local observations and use server fallback only
	 * when local observation is unavailable. Null means no new update, not a clear;
	 * resets/unavailability must be returned explicitly. No raw sender/store port.
	 */
	@FunctionalInterface
	public interface ContentPort<R> {
		ContentProjection<R> read(CapturedTarget target, ContentFence fence);
	}

	/** Immutable layout supplied by the native layout owner, not prototype row literals. */
	public record ListGeometry(int visibleRows, double width, double headerHeight, double rowHeight,
		double footerHeight, double childClearance) {
		public ListGeometry {
			if (visibleRows <= 0 || !positive(width) || !positive(rowHeight)
				|| !nonNegative(headerHeight) || !nonNegative(footerHeight) || !positive(childClearance))
				throw new IllegalArgumentException("invalid list geometry");
		}
		public SpatialInventoryLayout layout(SpatialInventoryView view) {
			return SpatialInventoryLayout.of(view, 0.0, 0.0, width, headerHeight, rowHeight, footerHeight);
		}
		/** Copy dimensions from the native renderer's logical layout, never animated paint geometry. */
		public static ListGeometry fromLayout(int visibleRows, SpatialInventoryLayout layout, double childClearance) {
			return new ListGeometry(visibleRows, layout.width(), layout.headerHeight(), layout.rowHeight(),
				layout.footerHeight(), childClearance);
		}
	}

	/** Frozen radial/list frame; session/list keys fence transition identities across opens. */
	public record Snapshot(boolean active, String sessionKey, SpatialSelectorSettings.Snapshot settings,
		ContentFence contentFence, Status contentStatus, SpatialController.Snapshot radial,
		InventoryListModel.Snapshot inventory, SpatialInventoryView inventoryView, PreciseFrame preciseFrame) {}

	/** Detached atomic paint payload, including each candidate's own capture ray and generation-scoped ID. */
	public record PreciseFrame(long revision, Map<String, CapturedTarget> choices, Map<String, CapturedPingContext> contexts) {
		public PreciseFrame { choices = Map.copyOf(choices); contexts = Map.copyOf(contexts); }
	}

	private final CapturedTarget ordinary;
	private Map<String, CapturedTarget> precise;
	private InteractionToken liveToken;
	private Object liveLevel;
	private PreciseFrame preciseFrame, issuedPreciseFrame, presentedPreciseFrame;
	private final SpatialSelectorSettings.Snapshot settings;
	private final ContentFence fence;
	private final ContentPort<R> contentPort;
	private final ListGeometry geometry;
	private final InventoryListModel list;
	private final InventoryGesture gesture;
	private final SpatialController radial;
	private final String sessionKey;
	private final String contentMenu;
	private final String inventoryMenu;
	private final Map<String, SelectorIntent<R>> actions = new HashMap<>();
	private final Map<String, InventoryRow<R>> rows = new LinkedHashMap<>();
	private ContentProjection<R> projection;
	private InventoryPreview<R> preview;
	private boolean opened;
	private boolean active;
	private boolean revisionSeen;
	private long revision;
	private boolean listWasActive;
	private String itemKey;
	private double itemAnchorY = Double.NaN;

	/** Precise allocations are copied as-is in catalog order; acquisition/nonreuse is external. */
	public SpatialSelectorSession(CapturedTarget ordinary, Map<String, CapturedTarget> precise,
		SpatialSelectorSettings.Snapshot settings, ContentFence fence, ContentPort<R> contentPort,
		ListGeometry geometry) {
		this.ordinary = Objects.requireNonNull(ordinary);
		this.precise = Map.copyOf(precise);
		this.settings = Objects.requireNonNull(settings);
		this.fence = Objects.requireNonNull(fence);
		this.contentPort = Objects.requireNonNull(contentPort);
		this.geometry = Objects.requireNonNull(geometry);
		if (!fence.candidateId().equals(ordinary.candidateId())) throw new IllegalArgumentException("wrong content target");
		for (Map.Entry<String, CapturedTarget> allocation : this.precise.entrySet()) {
			if (!preciseSlot(allocation.getKey()).matches(allocation.getValue().resolvedTarget()))
				throw new IllegalArgumentException("precise allocation has wrong type");
		}
		sessionKey = "selector:" + fence.selectorSession();
		contentMenu = sessionKey + ":content";
		inventoryMenu = contentMenu + ":inventory";
		list = new InventoryListModel(geometry.visibleRows());
		gesture = new InventoryGesture(list, settings.targetGlide().doubleValue(), settings.hoverEnabled(), geometry.rowHeight());
		radial = new SpatialController(buildRoot(), new SpatialController.Tuning(settings.deadzone(), settings.stroke(),
			settings.dwellMillis(), settings.hoverEnabled(), settings.hoverMillis()));
	}

	public boolean open(long nowMillis) {
		if (opened) return false;
		opened = true; active = true;
		radial.start(nowMillis);
		pollContent(nowMillis);
		return true;
	}

	@Override
	public void moveGui(double deltaX, double deltaY, long nowMillis) {
		if (!active || !Double.isFinite(deltaX) || !Double.isFinite(deltaY)) return;
		if (isListActive()) {
			SpatialController.Point pointer = radial.snapshot().pointer();
			gesture.alignPointer(pointer.x(), pointer.y());
			radial.moveExternalPointer(deltaX, deltaY, nowMillis);
			InventoryGesture.Action navigation = gesture.moveGui(deltaX, deltaY);
			boolean backFocused = settings.hoverEnabled() && gesture.isBackSideFocused();
			list.setBackHoverFocused(backFocused);
			if (!radial.updateExternalBack(backFocused, nowMillis)) {
				if (navigation == InventoryGesture.Action.BACK) radial.back(nowMillis);
				else if (navigation == InventoryGesture.Action.FORWARD) enterItem(nowMillis);
			}
		} else radial.movePhysical(deltaX, deltaY, nowMillis);
		synchronizeNavigation(nowMillis);
	}

	@Override
	public void scrollRows(double rowDelta, long nowMillis) {
		if (!active || !isListActive() || !Double.isFinite(rowDelta)) return;
		gesture.wheelRows(rowDelta);
	}

	public void tick(long nowMillis) {
		if (!active) return;
		pollContent(nowMillis);
		if (isListActive()) radial.updateExternalBack(settings.hoverEnabled() && gesture.isBackSideFocused(), nowMillis);
		else radial.tick(nowMillis);
		synchronizeNavigation(nowMillis);
	}

	/** Push an immutable port update; late/wrong-fence/non-advancing updates are inert. */
	public boolean updateContent(ContentProjection<R> update, long nowMillis) {
		if (!active || update == null || !fence.equals(update.fence())
			|| (revisionSeen && update.revision() <= revision)) return false;
		revisionSeen = true; revision = update.revision();
		boolean unavailable = unavailable(update.status());
		if (update.reset() || unavailable || update.inventory() == null || unavailable(update.inventory().status())) {
			rows.clear();
			list.close();
			if (itemKey != null && itemMenu().equals(activeMenuId())) radial.back(nowMillis);
		}
		projection = unavailable ? new ContentProjection<>(fence, revision, true, update.status(), List.of(), null) : update;
		preview = projection.inventory();
		if (preview != null && !unavailable(preview.status()) && inventoryEligible()) {
			for (InventoryRow<R> row : preview.rows()) rows.put(row.key(), row);
			if (list.isOpen()) list.applyBatch(listEntries(preview.rows()));
			list.setStatus(preview.status() == Status.READY ? InventoryListModel.Status.COMPLETE : InventoryListModel.Status.UPDATING);
		}
		radial.replaceRoot(buildRoot(), nowMillis);
		if (itemKey != null && itemMenu().equals(activeMenuId())) {
			if (!rows.containsKey(itemKey) || preview == null || preview.allowedItemTypes().isEmpty()) radial.back(nowMillis);
			else radial.replaceExternalMenu(buildItemMenu(itemKey), nowMillis);
		}
		synchronizeNavigation(nowMillis);
		return true;
	}

	public Snapshot snapshot() {
		issuedPreciseFrame = preciseFrame;
		return new Snapshot(active, sessionKey, settings, fence,
			projection == null ? Status.UNKNOWN : projection.status(), radial.snapshot(), list.snapshot(), inventoryView(), preciseFrame);
	}

	/** Production live mode starts empty; press-time precise attachments are not installed. */
	public void beginLivePrecise(InteractionToken token, Object level) {
		if (opened || liveToken != null || token.sequence() != fence.selectorSession()) throw new IllegalStateException("wrong live session");
		liveToken = Objects.requireNonNull(token); liveLevel = Objects.requireNonNull(level); precise = Map.of();
		radial.replaceRoot(buildRoot(), 0);
	}
	public boolean isPreciseBranchActive() { return active && (sessionKey + ":precise").equals(activeMenuId()); }

	/** Replaces the fixed type slots without moving focus, origin or ordinary/content identity. */
	public boolean updatePrecise(PreciseCaptureRefresh.Published update, long nowMillis) {
		if (!active || liveToken == null || update.inputs().token() != liveToken || update.inputs().level() != liveLevel
			|| preciseFrame != null && update.revision() <= preciseFrame.revision()) return false;
		Map<String, CapturedTarget> next = new LinkedHashMap<>(), choices = new LinkedHashMap<>();
		Map<String, CapturedPingContext> contexts = new LinkedHashMap<>();
		for (var type : PreciseTargetType.values()) {
			var slot = update.slots().get(type);
			if (slot.outcome().candidate().isEmpty()) continue;
			var candidate = slot.outcome().candidate().orElseThrow();
			String id = "live:" + liveToken.sequence() + ":" + slot.generation() + ":" + type.targetTypeId() + ":" + candidate.candidateId();
			var target = new CapturedTarget(id, candidate.resolvedTarget(), candidate.blockHitFace(), Optional.of(candidate.worldHit()));
			next.put(type.targetTypeId(), target); choices.put("precise:" + type.targetTypeId(), target);
			contexts.put(id, slot.context().orElseThrow());
		}
		precise = Map.copyOf(next); preciseFrame = new PreciseFrame(update.revision(), choices, contexts);
		radial.replaceRoot(buildRoot(), nowMillis);
		return true;
	}

	/** Only actually painted active node IDs advance the release payload; disabled paints clear their old action. */
	public Optional<PreciseFrame> markPresented(Snapshot painted, Set<String> paintedChoiceIds) {
		if (!active || liveToken == null || painted == null || !sessionKey.equals(painted.sessionKey())
			|| painted.preciseFrame() == null || painted.preciseFrame() != issuedPreciseFrame
			|| paintedChoiceIds.stream().noneMatch(id -> id.startsWith("precise:")) || !isPreciseSnapshot(painted.radial())
			|| presentedPreciseFrame != null && painted.preciseFrame().revision() < presentedPreciseFrame.revision()) return Optional.empty();
		Map<String, CapturedTarget> choices = new LinkedHashMap<>(presentedPreciseFrame == null ? Map.of() : presentedPreciseFrame.choices());
		for (String id : paintedChoiceIds) if (id.startsWith("precise:")) {
			choices.remove(id);
			var target = painted.preciseFrame().choices().get(id);
			if (target != null) choices.put(id, target);
		}
		Map<String, CapturedPingContext> contexts = new LinkedHashMap<>();
		for (var target : choices.values()) {
			var context = painted.preciseFrame().contexts().get(target.candidateId());
			if (context == null && presentedPreciseFrame != null) context = presentedPreciseFrame.contexts().get(target.candidateId());
			contexts.put(target.candidateId(), Objects.requireNonNull(context));
		}
		presentedPreciseFrame = new PreciseFrame(painted.preciseFrame().revision(), choices, contexts);
		return Optional.of(presentedPreciseFrame);
	}
	public Optional<PreciseFrame> presentedPrecise() { return Optional.ofNullable(presentedPreciseFrame); }
	private boolean isPreciseSnapshot(SpatialController.Snapshot snapshot) {
		return snapshot.active() && !snapshot.menus().isEmpty() && (sessionKey + ":precise").equals(snapshot.menus().getLast().menuId());
	}

	public SelectorIntent<R> releaseIntent(long nowMillis) {
		if (!active) return new SelectorIntent.None<>();
		SelectorIntent<R> result = new SelectorIntent.None<>();
		boolean livePrecise = liveToken != null && isPreciseBranchActive();
		String preciseFocus = livePrecise ? radial.snapshot().focusId() : null;
		if (isListActive()) {
			if (!gesture.isBackSideFocused() && preview != null && list.selectedKey() != null)
				result = inventoryIntent(list.selectedKey(), preview.defaultItemType());
			radial.cancel();
		} else if (radial.release(nowMillis) instanceof SpatialController.Release.Committed committed) {
			result = actions.getOrDefault(committed.action(), new SelectorIntent.None<>());
		}
		if (livePrecise) {
			CapturedTarget painted = presentedPreciseFrame == null || preciseFocus == null ? null : presentedPreciseFrame.choices().get(preciseFocus);
			result = painted == null ? new SelectorIntent.None<>() : new SelectorIntent.CreateTarget<>(painted,
				painted.resolvedTarget().targetType().defaultPingType(), Admission.PRECISE_PRESENTED, presentedPreciseFrame.revision());
		}
		finish();
		return result;
	}

	/** Lifecycle abort is never ordinary release and cannot be revived by late observations. */
	public void abort() { opened = true; radial.cancel(); finish(); }

	private void finish() {
		active = false; list.close(); rows.clear(); actions.clear(); projection = null; preview = null;
		itemKey = null; itemAnchorY = Double.NaN; listWasActive = false;
		precise = Map.of(); preciseFrame = null; issuedPreciseFrame = null; presentedPreciseFrame = null; liveToken = null; liveLevel = null;
	}

	private void pollContent(long nowMillis) { updateContent(contentPort.read(ordinary, fence), nowMillis); }

	private SpatialMenu buildRoot() {
		actions.clear();
		PingType danger = ordinary.resolvedTarget().targetType().pingTypes().stream()
			.filter(type -> type.id().equals("danger")).findFirst().orElse(null);
		SpatialMenu.Choice dangerChoice = danger == null ? SpatialMenu.Choice.disabled("danger", "pingforit.ping_type.danger")
			: targetChoice("danger", "pingforit.ping_type.danger", ordinary, danger);
		List<SpatialMenu.Choice> intents = new ArrayList<>();
		for (PingType type : ordinary.resolvedTarget().targetType().pingTypes())
			intents.add(targetChoice("intent:" + type.id(), type.displayKey(), ordinary, type));
		List<SpatialMenu.Choice> candidates = new ArrayList<>();
		for (var type : TargetTypeCatalog.builtIn().resolutionOrder()) {
			CapturedTarget candidate = precise.get(type.id());
			String id = "precise:" + type.id();
			String label = "pingforit.spatial.target_type." + type.id();
			candidates.add(candidate == null ? SpatialMenu.Choice.disabled(id, label)
				: targetChoice(id, label, candidate, candidate.resolvedTarget().targetType().defaultPingType()));
		}
		List<SpatialMenu.Choice> content = buildContent();
		List<SpatialMenu.Choice> toggles = new ArrayList<>();
		for (SelectorIntent.CaptureToggle toggle : SelectorIntent.CaptureToggle.values()) {
			String id = "settings:" + toggle.name();
			actions.put(id, new SelectorIntent.ToggleNextCapture<>(toggle));
			toggles.add(SpatialMenu.Choice.leaf(id, "pingforit.spatial.toggle." + toggle.name().toLowerCase(java.util.Locale.ROOT), id));
		}
		actions.put("cancel-marker", new SelectorIntent.CancelOwnMarker<>());
		return SpatialMenu.of(sessionKey + ":root",
			dangerChoice.withSector(0, 55),
			SpatialMenu.Choice.reserved("reserved-ne", "pingforit.spatial.reserved").withSector(45, 35),
			(content.isEmpty() ? SpatialMenu.Choice.disabled("content", "pingforit.spatial.content")
				: SpatialMenu.Choice.branch("content", "pingforit.spatial.content", new SpatialMenu(contentMenu, content))).withSector(90, 55),
			SpatialMenu.Choice.reserved("reserved-se", "pingforit.spatial.reserved").withSector(135, 35),
			SpatialMenu.Choice.leaf("cancel-marker", "pingforit.spatial.cancel", "cancel-marker").withSector(180, 55),
			SpatialMenu.Choice.branch("precise", "pingforit.spatial.precise", new SpatialMenu(sessionKey + ":precise", candidates)).withSector(225, 35),
			SpatialMenu.Choice.branch("intent", "pingforit.spatial.intent", new SpatialMenu(sessionKey + ":intent", intents)).withSector(270, 55),
			SpatialMenu.Choice.branch("settings", "pingforit.spatial.settings", new SpatialMenu(sessionKey + ":settings", toggles)).withSector(315, 35));
	}

	private List<SpatialMenu.Choice> buildContent() {
		List<SpatialMenu.Choice> result = new ArrayList<>();
		if (projection == null) return result;
		for (Property property : projection.properties()) {
			String id = contentMenu + ":property:" + menuToken(property.id());
			if (property.allowedTypes().isEmpty()) { result.add(SpatialMenu.Choice.disabled(id, property.labelKey())); continue; }
			List<SpatialMenu.Choice> types = new ArrayList<>();
			for (PingType type : property.allowedTypes()) {
				String action = id + ":" + type.id();
				actions.put(action, propertyIntent(property, type));
				types.add(SpatialMenu.Choice.leaf(action, type.displayKey(), action).withOutlineColor(type.outlineColor()));
			}
			actions.put(id, propertyIntent(property, property.defaultType()));
			result.add(SpatialMenu.Choice.branch(id, property.labelKey(), id, new SpatialMenu(id + ":types", types))
				.withOutlineColor(property.defaultType().outlineColor()));
		}
		if (preview != null) {
			if (!inventoryEligible() || unavailable(preview.status()) || preview.allowedItemTypes().isEmpty())
				result.add(SpatialMenu.Choice.disabled(inventoryMenu, preview.labelKey()));
			else {
				result.add(SpatialMenu.Choice.branch(inventoryMenu, preview.labelKey(),
					SpatialMenu.of(inventoryMenu, SpatialMenu.Choice.reserved(inventoryMenu + ":surface", preview.labelKey()))));
				if (itemKey != null && rows.containsKey(itemKey)) buildItemMenu(itemKey);
			}
		}
		return result;
	}

	private SpatialMenu.Choice targetChoice(String id, String label, CapturedTarget candidate, PingType type) {
		actions.put(id, new SelectorIntent.CreateTarget<>(candidate, type));
		return SpatialMenu.Choice.leaf(id, label, id).withOutlineColor(type.outlineColor());
	}

	private SelectorIntent<R> propertyIntent(Property property, PingType type) {
		return new SelectorIntent.CreateProperty<>(ordinary, ordinary.resolvedTarget().targetType().defaultPingType(),
			PresentationPropertyIntent.of(property.ref(), property.observedValue(), type.id()));
	}

	private SelectorIntent<R> inventoryIntent(String key, PingType type) {
		InventoryRow<R> row = rows.get(key);
		if (row == null || preview == null || type == null || !preview.allowedItemTypes().contains(type)) return new SelectorIntent.None<>();
		return new SelectorIntent.SelectInventory<>(ordinary, fence, row.reference(),
			ordinary.resolvedTarget().targetType().defaultPingType(), type);
	}

	private SpatialMenu buildItemMenu(String key) {
		List<SpatialMenu.Choice> choices = new ArrayList<>();
		String menu = inventoryMenu + ":item:" + menuToken(key);
		for (PingType type : preview.allowedItemTypes()) {
			String action = menu + ":" + type.id();
			actions.put(action, inventoryIntent(key, type));
			choices.add(SpatialMenu.Choice.leaf(action, type.displayKey(), action).withOutlineColor(type.outlineColor()));
		}
		return new SpatialMenu(menu, choices);
	}

	private void enterItem(long nowMillis) {
		if (list.selectedKey() == null || preview == null) return;
		SpatialInventoryView view = inventoryView();
		SpatialInventoryLayout layout = geometry.layout(view);
		double y = layout.rowCenterY(list.selectedIndex());
		if (!Double.isFinite(y)) return;
		double sign = list.direction().forward() == InventoryListModel.Side.RIGHT ? 1 : -1;
		SpatialController.Point anchor = new SpatialController.Point(layout.centerX(), y);
		SpatialController.Point origin = new SpatialController.Point(anchor.x() + sign * (layout.width() / 2 + geometry.childClearance()), y);
		itemKey = list.selectedKey();
		itemAnchorY = y;
		radial.enterExternal(buildItemMenu(itemKey), origin, anchor, nowMillis);
	}

	private void synchronizeNavigation(long nowMillis) {
		boolean hasList = radial.snapshot().menus().stream().anyMatch(menu -> menu.menuId().equals(inventoryMenu));
		if (!hasList) {
			list.close(); listWasActive = false; itemKey = null; itemAnchorY = Double.NaN;
			return;
		}
		if (isListActive()) {
			if (!list.isOpen()) {
				var menu = activeMenu();
				list.open(listEntries(new ArrayList<>(rows.values())), menu.origin().x(), menu.origin().y(),
					bearing(menu.origin(), menu.parentOrigin()));
				list.setStatus(preview.status() == Status.READY ? InventoryListModel.Status.COMPLETE : InventoryListModel.Status.UPDATING);
				gesture.begin(menu.origin().x(), menu.origin().y(), list.direction());
				radial.useExternalSurface();
				radial.rebase(menu.origin().x(), menu.origin().y(), nowMillis);
			} else if (!listWasActive) {
				SpatialController.Point pointer = radial.snapshot().pointer();
				gesture.rebase(pointer.x(), pointer.y());
				list.setBackHoverFocused(false);
				radial.rebase(pointer.x(), pointer.y(), nowMillis);
			}
			itemKey = null;
			itemAnchorY = Double.NaN;
			listWasActive = true;
		} else listWasActive = false;
	}

	private SpatialInventoryView inventoryView() {
		InventoryListModel.Snapshot state = list.snapshot();
		List<SpatialInventoryView.Row> paintRows = new ArrayList<>();
		for (InventoryListModel.Entry entry : state.entries()) {
			InventoryRow<R> row = rows.get(entry.key());
			if (row != null) paintRows.add(new SpatialInventoryView.Row(row.key(), row.label(), row.count(), row.itemId(), row.quality()));
		}
		String listKey = inventoryMenu + ":" + fence.requestId() + ":" + fence.previewSession();
		SpatialInventoryView view = new SpatialInventoryView(listKey, active && state.open(), paintRows, state.selectedIndex(), state.windowFirst(),
			geometry.visibleRows(), state.direction().back() == InventoryListModel.Side.LEFT,
			preview == null ? Status.UNKNOWN : preview.status(), state.axisX(), state.glideBaseY());
		boolean backFocused = isListActive() && gesture.isBackSideFocused();
		view = view.withBackAffordance(backFocused, backFocused ? radial.snapshot().hoverProgress() : 0.0);
		return itemKey == null ? view : view.withSelectedRowCenterY(itemAnchorY);
	}

	private List<InventoryListModel.Entry> listEntries(List<InventoryRow<R>> batch) {
		return batch.stream().map(row -> new InventoryListModel.Entry(row.key(), row.label(),
			row.count() == null ? -1 : row.count(), row.quality().name())).toList();
	}

	private boolean inventoryEligible() {
		return !(ordinary.resolvedTarget().target() instanceof Target.BlockTarget) || ordinary.face().isPresent();
	}
	private boolean isListActive() { return active && inventoryMenu.equals(activeMenuId()); }
	private String itemMenu() { return inventoryMenu + ":item:" + menuToken(itemKey); }
	private String activeMenuId() { var menu = activeMenu(); return menu == null ? null : menu.menuId(); }
	private SpatialController.MenuView activeMenu() {
		List<SpatialController.MenuView> menus = radial.snapshot().menus();
		return menus.isEmpty() ? null : menus.get(menus.size() - 1);
	}
	private static boolean unavailable(Status status) {
		return status == Status.UNAVAILABLE || status == Status.INVALID || status == Status.EXPIRED;
	}
	private static double bearing(SpatialController.Point from, SpatialController.Point to) {
		return (Math.toDegrees(Math.atan2(to.x() - from.x(), from.y() - to.y())) + 360) % 360;
	}
	private static List<PingType> checkedTypes(List<PingType> types, PingType defaultType) {
		List<PingType> copy = List.copyOf(types);
		Set<String> ids = new HashSet<>();
		for (PingType type : copy) if (!ids.add(type.id())) throw new IllegalArgumentException("duplicate Ping Type");
		if (copy.isEmpty() ? defaultType != null : !copy.contains(defaultType)) throw new IllegalArgumentException("default must be allowed");
		return copy;
	}
	/** A precise map key names a fixed slot whose admissible canonical class may be broader than its own id. */
	private static PreciseTargetType preciseSlot(String targetTypeId) {
		for (PreciseTargetType type : PreciseTargetType.values())
			if (type.targetTypeId().equals(targetTypeId)) return type;
		throw new IllegalArgumentException("unknown precise allocation type");
	}
	private static void requireId(String id) {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("blank identity");
	}
	/** UI keys are framed independently of opaque domain keys and action suffixes. */
	private static String menuToken(String value) {
		return java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
	private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
	private static boolean nonNegative(double value) { return Double.isFinite(value) && value >= 0; }
}
