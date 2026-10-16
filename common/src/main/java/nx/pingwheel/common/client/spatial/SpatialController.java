package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import nx.pingwheel.common.client.spatial.BackHoverController.Result;

/**
 * Pure radial spatial-menu session controller.
 *
 * <p>The controller owns a stack of {@link MenuState}s over an immutable
 * {@link SpatialMenu} tree. A session starts at the root centre and is advanced
 * with explicit monotonic timestamps plus physical mouse deltas; the virtual
 * pointer (and its bounded trail) is the only pointer state, so tests can drive
 * exact trajectories without a game client. Bearings are degrees, {@code 0} is
 * up and positive is clockwise. Selection uses half-open sectors, the centre
 * deadzone means abandon, and release is the only operation that commits an
 * action.
 *
 * <p>A focused branch admits a stationary dwell or a qualified direction turn.
 * User returns align the retained pattern with the pointer and always arm
 * {@code freshStrokeRequired}; programmatic returns preserve the old placement.
 * Hover-enabled returns share one {@link BackHoverController} across menus and
 * external surfaces, so alignment cannot release a held Back block.
 *
 * <p>Nothing here touches Minecraft, a renderer, configuration, or an input
 * callback; {@link Snapshot} is the read model a rectangular renderer consumes.
 */
public final class SpatialController {

	/** Prototype runtime policy constants, not player-facing settings. */
	private static final double DWELL_MIN_TRAVEL = 8.0;
	private static final int TRAIL_LIMIT = 65;
	private static final long ENTRY_MIN_ELAPSED_MILLIS = 35L;
	private static final double ENTRY_MIN_TURN_SEGMENT = 8.0;
	private static final double TURN_MIN_DEGREES = 40.0;
	private static final double RETRACE_MIN_DISTANCE = 68.0;
	private static final double RETRACE_RATIO = 0.56;
	private static final double RETRACE_LATERAL_LIMIT = 16.0;

	/** A point in controller space (GUI pixels for the renderer). */
	public record Point(double x, double y) {}

	/** Bounded cumulative placement notification; a zero-distance user return still advances the sequence. */
	public record UserReturnPlacement(long sequence, Point totalTranslation) {}

	/** Immutable projection shared by interaction and paint; bounds include the last GUI pixel. */
	public record Viewport(int width, int height, double viewX, double viewY) {
		public Viewport {
			if (width <= 0 || height <= 0 || !Double.isFinite(viewX) || !Double.isFinite(viewY))
				throw new IllegalArgumentException("viewport must be positive and translation finite");
		}

		public double centerX() { return width / 2.0; }
		public double centerY() { return height / 2.0; }
		public Point confine(Point point) {
			return new Point(clamp(point.x(), -centerX() - viewX, width - 1.0 - centerX() - viewX),
				clamp(point.y(), -centerY() - viewY, height - 1.0 - centerY() - viewY));
		}
		private Point move(Point point, double dx, double dy) {
			return new Point(addConfined(point.x(), dx, -centerX() - viewX, width - 1.0 - centerX() - viewX),
				addConfined(point.y(), dy, -centerY() - viewY, height - 1.0 - centerY() - viewY));
		}
		private static double clamp(double value, double min, double max) {
			return Math.max(min, Math.min(max, value));
		}
		private static double addConfined(double value, double delta, double min, double max) {
			// Compare the remaining distance before adding: finite raw input cannot overflow.
			if (delta >= max - value) return max;
			if (delta <= min - value) return min;
			return value + delta;
		}
	}

	/** Frozen session parameters supplied by the configuration owner. */
	public record Tuning(double deadzone, double stroke, long dwellMillis, boolean hoverEnabled, long hoverMillis,
		int trailLength) {

		public Tuning(double deadzone, double stroke, long dwellMillis, boolean hoverEnabled, long hoverMillis) {
			this(deadzone, stroke, dwellMillis, hoverEnabled, hoverMillis, TRAIL_LIMIT);
		}

		public Tuning {
			if (trailLength <= 0 || trailLength > 512) throw new IllegalArgumentException("invalid trail bound");
			if (deadzone < 0.0) {
				throw new IllegalArgumentException("deadzone must be non-negative");
			}

			if (stroke <= 0.0) {
				throw new IllegalArgumentException("stroke must be positive");
			}

			if (dwellMillis < 0L) {
				throw new IllegalArgumentException("dwellMillis must be non-negative");
			}

			if (hoverMillis < 0L) {
				throw new IllegalArgumentException("hoverMillis must be non-negative");
			}
		}
	}

	/** The result of ending a session through {@link #release(long)}. */
	public sealed interface Release permits Release.Committed, Release.NoAction, Release.Abandoned, Release.Idle {

		/** A committing entry was focused; exactly one action is handed out. */
		record Committed(String choiceId, String action) implements Release {}

		/** The focused entry must not commit. */
		record NoAction(String choiceId, Reason reason) implements Release {

			/** Why a focused (or out-of-deadzone) release committed nothing. */
			public enum Reason {
				OUTSIDE,
				RESERVED,
				DISABLED,
				NAVIGATION,
				BRANCH_WITHOUT_ACTION
			}
		}

		/** Release inside the root deadzone: the centre abandons the session. */
		record Abandoned() implements Release {}

		/** No active session (idle, already released, or cancelled). */
		record Idle() implements Release {}
	}

	/**
	 * One entry as resolved for rendering or hit-testing. {@code outlineColor} is
	 * the nullable detached 24-bit Ping Type outline colour, never decoded from
	 * the opaque action.
	 */
	public record ChoiceView(
		String id,
		String label,
		String action,
		boolean back,
		boolean disabled,
		boolean reserved,
		boolean branch,
		boolean selected,
		double startDegrees,
		double spanDegrees,
		Integer outlineColor
	) {}

	/**
	 * One menu of the current ancestor path. {@code entryChoiceId} is the
	 * parent menu's choice that opened this menu, or {@code null} for the root
	 * and for a caller-pushed external menu; it is read-only ancestry metadata
	 * for presentation, never selection or release state.
	 */
	public record MenuView(
		String menuId,
		Point origin,
		Point parentOrigin,
		String focusId,
		List<ChoiceView> choices,
		String entryChoiceId
	) {
		/** Compatibility form predating the ancestry edge; a caller-built view has no radial entry. */
		public MenuView(String menuId, Point origin, Point parentOrigin, String focusId, List<ChoiceView> choices) {
			this(menuId, origin, parentOrigin, focusId, choices, null);
		}
	}

	/**
	 * Immutable read model for the renderer. {@code deadzone} is the frozen
	 * session's centre radius in GUI pixels, exposed so the renderer can draw
	 * the matching deadzone circle and its shared sector boundaries; {@code 0}
	 * means the caller supplied no radius and no centre guides are drawn.
	 */
	public record Snapshot(
		boolean active,
		Point pointer,
		List<Point> trail,
		String focusId,
		List<MenuView> menus,
		double hoverProgress,
		double deadzone,
		long trailSequence,
		long trailEpoch,
		long trailAlignment,
		long trailAlignmentSequence
	) {
		/** Old projections have no native sample identity; renderers may sample them once per frame. */
		public Snapshot(boolean active, Point pointer, List<Point> trail, String focusId,
			List<MenuView> menus, double hoverProgress, double deadzone) {
			this(active, pointer, trail, focusId, menus, hoverProgress, deadzone, -1L, 0L, 0L, 0L);
		}

		/** Compatibility form for callers that predate the exposed frozen deadzone. */
		public Snapshot(boolean active, Point pointer, List<Point> trail, String focusId,
			List<MenuView> menus, double hoverProgress) {
			this(active, pointer, trail, focusId, menus, hoverProgress, 0.0);
		}
	}

	private SpatialMenu root;
	private final Tuning tuning;
	private final BackHoverController hover = new BackHoverController();

	private boolean active;
	private Point pointer = new Point(0.0, 0.0);
	private final List<Point> trail = new ArrayList<>();
	private final List<MenuState> stack = new ArrayList<>();
	private double hoverProgress;
	private Viewport viewport;
	private Point pendingReturn;
	private long hoverAlignment;
	private long trailSequence;
	private long trailEpoch;
	private long trailAlignment;
	private long trailAlignmentSequence;
	private UserReturnPlacement userReturnPlacement = new UserReturnPlacement(0L, new Point(0.0, 0.0));

	public SpatialController(SpatialMenu root, Tuning tuning) {
		this.root = Objects.requireNonNull(root, "root");
		this.tuning = Objects.requireNonNull(tuning, "tuning");

		for (SpatialMenu.Choice choice : root.choices()) {
			if (!choice.hasSector()) {
				throw new IllegalArgumentException("root entry needs caller-fixed bearing and span: " + choice.id());
			}
		}
	}

	/** Starts a fresh session at the root centre with the frozen tuning. */
	public void start(long nowMillis) {
		active = true;
		pointer = confined(new Point(0.0, 0.0));
		pendingReturn = null;
		userReturnPlacement = new UserReturnPlacement(0L, new Point(0.0, 0.0));
		freshTrail();
		stack.clear();

		Point origin = new Point(0.0, 0.0);
		MenuState rootState = new MenuState(root, origin, origin);
		rootState.enteredAt = nowMillis;
		rootState.lastMovement = nowMillis;
		stack.add(rootState);

		hoverProgress = 0.0;
		hover.start(tuning.hoverEnabled(), tuning.hoverMillis());

		setFocus(nowMillis);
		updateHover(nowMillis);
	}

	public boolean isActive() {
		return active;
	}

	/** Placement changes since this hold began, independent of stack-depth and pointer-sample changes. */
	public UserReturnPlacement userReturnPlacement() {
		return userReturnPlacement;
	}

	/** Reprojects without input travel, trail, navigation or timer advancement. */
	public boolean constrainPointer(Viewport viewport, long nowMillis) {
		this.viewport = Objects.requireNonNull(viewport);
		if (pendingReturn != null && confined(pendingReturn).equals(pendingReturn)) {
			applyReturn(pendingReturn, nowMillis);
			pendingReturn = null;
			return true;
		}
		Point next = confined(pointer);
		if (next.equals(pointer)) return false;
		pointer = next;
		trailAlignment++;
		trailAlignmentSequence = trailSequence;
		if (active) {
			MenuState menu = activeMenu();
			menu.candidate = null;
			menu.candidateId = null;
			menu.traveled = 0.0;
			menu.enteredAt = nowMillis;
			menu.lastMovement = nowMillis;
			hoverAlignment++; // re-arm the timer without releasing Back's held-focus block
			hoverProgress = 0.0;
			setFocus(nowMillis);
		}
		return true;
	}

	private Point confined(Point point) { return viewport == null ? point : viewport.confine(point); }

	/** The only input accumulation path, shared by radial and external surfaces. */
	private Point movePointer(double deltaX, double deltaY) {
		Point previous = pointer;
		pointer = viewport == null ? new Point(pointer.x() + deltaX, pointer.y() + deltaY)
			: viewport.move(pointer, deltaX, deltaY);
		return new Point(pointer.x() - previous.x(), pointer.y() - previous.y());
	}

	/** Adds a physical delta to the virtual pointer and refreshes the focus. */
	public void movePhysical(double deltaX, double deltaY, long nowMillis) {
		if (!active || !Double.isFinite(deltaX) || !Double.isFinite(deltaY) || (deltaX == 0.0 && deltaY == 0.0)) {
			return;
		}

		MenuState menu = activeMenu();
		Point previous = pointer;
		Point effective = movePointer(deltaX, deltaY);
		if (effective.x() == 0.0 && effective.y() == 0.0) return;
		pendingReturn = null; // a fresh effective physical stroke abandons a pending return
		menu.traveled += Math.hypot(effective.x(), effective.y());
		appendTrail();

		menu.lastMovement = nowMillis;

		// A thin reverse-stroke corridor can pop an ordinary radial; row-anchored
		// (inventory) menus are excluded and hover mode owns Back instead.
		if (retracing(menu, previous)) {
			pop(nowMillis);
			return;
		}

		Resolved before = menu.focus;
		double radiusBefore = distance(menu.origin, previous);
		boolean branchable = before != null && !before.disabled() && (before.branch() || before.back());

		if (branchable
			&& radiusBefore >= tuning.stroke()
			&& (!menu.freshStrokeRequired || menu.traveled >= tuning.stroke())
			&& nowMillis - menu.enteredAt >= ENTRY_MIN_ELAPSED_MILLIS) {
			if (menu.candidate == null || !before.id().equals(menu.candidateId)) {
				menu.candidate = previous;
				menu.candidateId = before.id();
			}

			Point corner = menu.candidate;

			if (distance(corner, pointer) >= ENTRY_MIN_TURN_SEGMENT) {
				double inbound = bearing(menu.origin, corner);
				double outbound = bearing(corner, pointer);

				if (turnDegrees(outbound, inbound) >= TURN_MIN_DEGREES
					&& distance(menu.origin, corner) >= tuning.stroke()) {
					if (!before.back()) {
						enterAt(before, corner, nowMillis, true);
						return;
					}

					if (!tuning.hoverEnabled()) {
						pop(nowMillis);
						return;
					}
				}

				// Progress the possible corner along a straight stroke without
				// rebasing the menu origin on a purely radial crossing.
				menu.candidate = previous;
				menu.candidateId = before.id();
			}
		} else {
			menu.candidate = null;
			menu.candidateId = null;
		}

		setFocus(nowMillis);
		Resolved after = menu.focus;

		if (!Objects.equals(after == null ? null : after.id(), before == null ? null : before.id())) {
			menu.candidate = null;
			menu.candidateId = null;
		}

		updateHover(nowMillis, true);
	}

	/**
	 * Advances stationary timers. Hover completion pops one level; otherwise a
	 * stationary focused branch is entered after the dwell threshold.
	 */
	public void tick(long nowMillis) {
		if (!active) {
			return;
		}

		if (updateHover(nowMillis)) {
			return;
		}

		maybeDwell(nowMillis);
	}

	/**
	 * Ends the session with the outcome of the currently focused entry. The
	 * session can only be released once; a later call is {@link Release.Idle}.
	 */
	public Release release(long nowMillis) {
		if (!active) {
			return new Release.Idle();
		}

		MenuState menu = activeMenu();
		Resolved choice = focusAt(menu);
		Release result;

		if (choice == null) {
			result = distance(menu.origin, pointer) <= tuning.deadzone()
				? new Release.Abandoned()
				: new Release.NoAction(null, Release.NoAction.Reason.OUTSIDE);
		} else if (choice.reserved()) {
			result = new Release.NoAction(choice.id(), Release.NoAction.Reason.RESERVED);
		} else if (choice.disabled()) {
			result = new Release.NoAction(choice.id(), Release.NoAction.Reason.DISABLED);
		} else if (choice.back()) {
			result = new Release.NoAction(choice.id(), Release.NoAction.Reason.NAVIGATION);
		} else if (choice.action() == null) {
			result = new Release.NoAction(choice.id(), Release.NoAction.Reason.BRANCH_WITHOUT_ACTION);
		} else {
			result = new Release.Committed(choice.id(), choice.action());
		}

		endSession();
		return result;
	}

	/** Cancels the session without an action; returns whether one was active. */
	public boolean cancel() {
		if (!active) {
			return false;
		}

		endSession();
		return true;
	}

	/**
	 * Pushes a caller-owned submenu (for example a future inventory item menu)
	 * at an externally computed origin and rebases the virtual pointer there.
	 */
	public void enterExternal(SpatialMenu menu, double originX, double originY, long nowMillis) {
		if (!active) {
			return;
		}
		enterExternal(menu, new Point(originX, originY), activeMenu().origin, nowMillis);
	}

	/** Row-anchored children return toward the selected row, not the list centre. */
	public void enterExternal(SpatialMenu menu, Point origin, Point parentOrigin, long nowMillis) {
		if (!active) {
			return;
		}

		MenuState state = new MenuState(menu, origin, parentOrigin);
		state.rowAnchored = true;
		state.external = true;
		state.enteredAt = nowMillis;
		state.lastMovement = nowMillis;
		stack.add(state);
		rebaseInternal(origin, nowMillis);
	}

	/**
	 * Relayouts only the active caller-pushed menu, without pointer input,
	 * navigation or timer advancement. An effective placement change restarts
	 * the pending dwell at the new geometry even when the focused choice is
	 * unchanged; repeating an unchanged relayout cannot postpone that dwell.
	 * Accumulated travel, the fresh-stroke fence and the held Back block stay
	 * untouched.
	 */
	public void repositionExternal(Point origin, Point parentOrigin, long nowMillis) {
		if (!active || !activeMenu().external) return;
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(parentOrigin, "parentOrigin");
		MenuState menu = activeMenu();
		boolean placementChanged = !origin.equals(menu.origin) || !parentOrigin.equals(menu.parentOrigin);
		menu.origin = origin;
		menu.parentOrigin = parentOrigin;
		menu.candidate = null;
		menu.candidateId = null;
		if (placementChanged) {
			// setFocus alone only restarts the clock when the choice identity
			// changes, so the same focused choice could otherwise pop on the
			// stale deadline after its geometry moved.
			menu.enteredAt = nowMillis;
			menu.lastMovement = nowMillis;
		}
		hoverAlignment++; // the next hover sample starts at the new geometry; the held block stays intact
		hoverProgress = 0.0;
		setFocus(nowMillis);
	}

	/** Moves the virtual pointer without physical travel (external rebase). */
	public void rebase(double x, double y, long nowMillis) {
		if (!active) {
			return;
		}

		rebaseInternal(new Point(x, y), nowMillis);
	}

	/**
	 * Requests an exact return placement at {@code desired}, which the caller
	 * computes on the active menu's inclusive deadzone circumference. The point
	 * is corrected inward by at most a few representable steps when floating
	 * error would place it outside that inclusive deadzone, so focus stays
	 * empty. When the published projection can already show the exact point it
	 * is applied immediately; otherwise it is retained as one bounded pending
	 * target and resolved by later projection updates. Applying or resolving
	 * the placement rebases the trail and focus without physical travel, timer
	 * progress or a fresh stroke, and a fresh effective physical movement
	 * cancels a pending target instead of chasing it.
	 */
	public void placeReturn(Point desired, long nowMillis) {
		if (!active) {
			return;
		}
		Point point = inclusiveDeadzonePoint(activeMenu().origin, desired);
		if (viewport == null || confined(point).equals(point)) {
			applyReturn(point, nowMillis);
			pendingReturn = null;
		} else {
			pendingReturn = point;
		}
	}

	/** Keeps this controller as the sole pointer and hover owner for a list surface. */
	public void useExternalSurface() {
		if (!active) return;
		MenuState state = activeMenu();
		state.surface = true;
		state.focus = null;
		state.candidate = null;
		state.candidateId = null;
	}

	/** GUI deltas on an external surface do not run radial dwell, turn or retrace. */
	public Point moveExternalPointer(double deltaX, double deltaY, long nowMillis) {
		if (!active || !activeMenu().surface || !Double.isFinite(deltaX) || !Double.isFinite(deltaY)) return new Point(0.0, 0.0);
		Point effective = movePointer(deltaX, deltaY);
		if (effective.x() == 0.0 && effective.y() == 0.0) return effective;
		appendTrail();
		activeMenu().lastMovement = nowMillis;
		return effective;
	}

	/** Alignment-only list focus refresh; the ordinary tick/move owns hover advancement. */
	public void alignExternalBack(boolean focused) {
		if (active && activeMenu().surface) activeMenu().externalBackFocus = focused;
	}

	/** Advances the same hover state across radial/list boundaries; pops at most once. */
	public boolean updateExternalBack(boolean focused, long nowMillis) {
		return updateExternalBack(focused, nowMillis, false);
	}

	/** Only a caller-confirmed effective list movement may release the held Back block. */
	public boolean updateExternalBack(boolean focused, long nowMillis, boolean physicalMovement) {
		if (!active || !activeMenu().surface) return false;
		activeMenu().externalBackFocus = focused;
		return updateHover(nowMillis, physicalMovement);
	}

	/** User navigation aligns the returned parent with the unchanged pointer and arms a fresh stroke. */
	public boolean back(long nowMillis) {
		return active && pop(nowMillis);
	}

	/** Data revocation and other programmatic navigation keep the old placement and focus semantics. */
	public boolean backProgrammatically(long nowMillis) {
		return active && pop(nowMillis, false);
	}

	/** Refreshes an active row's allowed annotations without moving its origin. */
	public void replaceExternalMenu(SpatialMenu replacement, long nowMillis) {
		if (!active || !activeMenu().external || !activeMenu().menu.id().equals(replacement.id())) return;
		activeMenu().menu = replacement;
		setFocus(nowMillis);
	}

	/**
	 * Replaces a caller-owned projection without restarting the session. Removed
	 * branches prune their descendants; row-anchored external children remain only
	 * while their owning branch remains. Geometry and the pointer are unchanged.
	 */
	public void replaceRoot(SpatialMenu replacement, long nowMillis) {
		Objects.requireNonNull(replacement, "replacement");
		if (!root.id().equals(replacement.id())) throw new IllegalArgumentException("root identity must remain frozen");
		for (SpatialMenu.Choice choice : replacement.choices()) {
			if (!choice.hasSector()) throw new IllegalArgumentException("root entry needs a sector");
		}
		root = replacement;
		if (!active) return;
		for (int index = 0; index < stack.size(); index++) {
			MenuState state = stack.get(index);
			SpatialMenu next = findMenu(replacement, state.menu.id());
			if (next == null && !state.external) {
				stack.subList(index, stack.size()).clear();
				pendingReturn = null;
				freshTrail();
				break;
			}
			if (next != null) state.menu = next;
		}
		setFocus(nowMillis);
	}

	private static SpatialMenu findMenu(SpatialMenu menu, String id) {
		if (menu.id().equals(id)) return menu;
		for (SpatialMenu.Choice choice : menu.choices()) {
			if (choice.children() == null || choice.disabled()) continue;
			SpatialMenu found = findMenu(choice.children(), id);
			if (found != null) return found;
		}
		return null;
	}

	/** The renderer read model for the current frame. */
	public Snapshot snapshot() {
		if (!active) {
			return new Snapshot(false, pointer, List.of(), null, List.of(), 0.0, tuning.deadzone(),
				trailSequence, trailEpoch, trailAlignment, trailAlignmentSequence);
		}

		List<MenuView> menus = new ArrayList<>();

		for (MenuState state : stack) {
			String focusId = state.surface && state.externalBackFocus ? state.menu.id() + ":back"
				: state.focus == null ? null : state.focus.id();
			List<ChoiceView> choices = new ArrayList<>();

			for (Resolved choice : displayedChoices(state)) {
				choices.add(new ChoiceView(
					choice.id(),
					choice.label(),
					choice.action(),
					choice.back(),
					choice.disabled(),
					choice.reserved(),
					choice.branch(),
					choice.id().equals(focusId),
					choice.startDegrees(),
					choice.spanDegrees(),
					choice.outlineColor()));
			}

			menus.add(new MenuView(state.menu.id(), state.origin, state.parentOrigin, focusId, List.copyOf(choices),
				state.entryChoiceId));
		}

		String focusId = menus.get(menus.size() - 1).focusId();
		return new Snapshot(true, pointer, List.copyOf(trail), focusId, List.copyOf(menus), hoverProgress,
			tuning.deadzone(), trailSequence, trailEpoch, trailAlignment, trailAlignmentSequence);
	}

	private void rebaseInternal(Point point, long nowMillis) {
		pointer = confined(point);
		pendingReturn = null;
		freshTrail();
		MenuState menu = activeMenu();
		menu.candidate = null;
		menu.candidateId = null;
		menu.lastMovement = nowMillis;
		setFocus(nowMillis);
		if (!menu.surface) updateHover(nowMillis);
	}

	/** Explicit discontinuity plus one seed; never inferred from coincident coordinates. */
	private void freshTrail() {
		trailEpoch++;
		trail.clear();
		appendTrail();
	}

	private void appendTrail() {
		trailSequence++;
		trail.add(pointer);
		if (trail.size() > tuning.trailLength()) trail.remove(0);
	}

	/**
	 * The requested circumference point, corrected inward only by the minimal
	 * representable steps needed to satisfy the inclusive deadzone under
	 * floating error. The caller's intended radius is otherwise preserved; a
	 * zero deadzone admits only the origin.
	 */
	private Point inclusiveDeadzonePoint(Point origin, Point desired) {
		double deadzone = tuning.deadzone();
		if (!(deadzone > 0.0)) {
			return new Point(origin.x(), origin.y());
		}
		double radius = deadzone;
		double dx = desired.x() - origin.x();
		double dy = desired.y() - origin.y();
		double distance = Math.hypot(dx, dy);
		for (int step = 0; step < 8 && distance > radius; step++) {
			radius = Math.nextDown(radius);
			double scale = radius / distance;
			dx *= scale;
			dy *= scale;
			distance = Math.hypot(dx, dy);
		}
		double x = origin.x() + dx;
		double y = origin.y() + dy;
		// focusAt and release test the reconstructed absolute point, so walk its
		// representable coordinates inward until that same predicate admits it.
		while (Math.hypot(x - origin.x(), y - origin.y()) > deadzone) {
			x = Math.nextAfter(x, origin.x());
			y = Math.nextAfter(y, origin.y());
		}
		return new Point(x, y);
	}

	/** Projection-only return placement: rebases trail and focus without travel or timers. */
	private void applyReturn(Point point, long nowMillis) {
		pointer = point;
		freshTrail();
		MenuState menu = activeMenu();
		menu.candidate = null;
		menu.candidateId = null;
		menu.traveled = 0.0;
		menu.enteredAt = nowMillis;
		menu.lastMovement = nowMillis;
		setFocus(nowMillis);
		updateHover(nowMillis);
	}

	private void endSession() {
		active = false;
		stack.clear();
		trail.clear();
		pendingReturn = null;
		pointer = confined(new Point(0.0, 0.0));
		hoverProgress = 0.0;
		hover.end();
	}

	private MenuState activeMenu() {
		return stack.get(stack.size() - 1);
	}

	private void setFocus(long nowMillis) {
		MenuState menu = activeMenu();
		Resolved next = focusAt(menu);
		String currentId = menu.focus == null ? null : menu.focus.id();
		String nextId = next == null ? null : next.id();

		if (!Objects.equals(currentId, nextId)) {
			menu.enteredAt = nowMillis;
			menu.candidate = null;
			menu.candidateId = null;
		}
		menu.focus = next;
	}

	private Resolved focusAt(MenuState menu) {
		if (menu.surface) return null;
		double dx = pointer.x() - menu.origin.x();
		double dy = pointer.y() - menu.origin.y();

		if (Math.hypot(dx, dy) <= tuning.deadzone()) {
			return null;
		}

		double bearing = Math.round(normalize(Math.toDegrees(Math.atan2(dx, -dy))) * 1.0e8) / 1.0e8;

		for (Resolved choice : displayedChoices(menu)) {
			if (containsBearing(bearing, choice.startDegrees(), choice.spanDegrees())) {
				return choice;
			}
		}

		return null;
	}

	private boolean updateHover(long nowMillis) {
		return updateHover(nowMillis, false);
	}

	private boolean updateHover(long nowMillis, boolean physicalMovement) {
		MenuState menu = activeMenu();
		boolean backFocused = menu.surface ? menu.externalBackFocus : menu.focus != null && menu.focus.back();
		Result result = hover.update(menu.menu.id() + ":alignment:" + hoverAlignment, backFocused, nowMillis, physicalMovement);

		if (result.triggerPop()) {
			hoverProgress = 0.0;
			pop(nowMillis);
			return true;
		}

		hoverProgress = result.progress();
		return false;
	}

	private void maybeDwell(long nowMillis) {
		MenuState menu = activeMenu();
		Resolved choice = menu.focus;

		if (choice == null || choice.disabled() || choice.reserved()) {
			return;
		}

		if (choice.back()) {
			if (tuning.hoverEnabled()) {
				return;
			}
		} else if (!choice.branch()) {
			return;
		}

		if (menu.freshStrokeRequired && menu.traveled < tuning.stroke()) {
			return;
		}

		if (distance(menu.origin, pointer) < tuning.stroke()) {
			return;
		}

		if (menu.traveled < DWELL_MIN_TRAVEL) {
			return;
		}

		if (nowMillis - menu.lastMovement < tuning.dwellMillis()) {
			return;
		}

		if (nowMillis - menu.enteredAt < tuning.dwellMillis()) {
			return;
		}

		if (choice.back()) {
			pop(nowMillis);
		} else {
			enter(choice, nowMillis);
		}
	}

	private void enter(Resolved choice, long nowMillis) {
		enterAt(choice, pointer, nowMillis);
	}

	private void enterAt(Resolved choice, Point origin, long nowMillis) {
		enterAt(choice, origin, nowMillis, false);
	}

	private void enterAt(Resolved choice, Point origin, long nowMillis, boolean physicalMovement) {
		MenuState parent = activeMenu();
		pendingReturn = null;
		MenuState child = new MenuState(choice.children(), origin, parent.origin);
		child.entryChoiceId = choice.id();
		child.enteredAt = nowMillis;
		child.lastMovement = nowMillis;
		stack.add(child);
		freshTrail();
		setFocus(nowMillis);
		updateHover(nowMillis, physicalMovement);
	}

	private boolean pop(long nowMillis) {
		return pop(nowMillis, true);
	}

	private boolean pop(long nowMillis, boolean userReturn) {
		if (stack.size() < 2) {
			return false;
		}

		stack.remove(stack.size() - 1);
		pendingReturn = null;
		MenuState parent = activeMenu();
		if (userReturn) {
			Point returnedOrigin = parent.origin;
			Point delta = new Point(pointer.x() - returnedOrigin.x(), pointer.y() - returnedOrigin.y());
			for (MenuState retained : stack) {
				retained.origin = translateForReturn(retained.origin, returnedOrigin, delta);
				retained.parentOrigin = translateForReturn(retained.parentOrigin, returnedOrigin, delta);
				retained.candidate = null;
				retained.candidateId = null;
			}
			Point total = userReturnPlacement.totalTranslation();
			userReturnPlacement = new UserReturnPlacement(userReturnPlacement.sequence() + 1L,
				new Point(total.x() + delta.x(), total.y() + delta.y()));
			parent.externalBackFocus = false;
			hoverAlignment++;
			hoverProgress = 0.0;
		}
		freshTrail();
		parent.focus = focusAt(parent);
		parent.enteredAt = nowMillis;
		parent.lastMovement = nowMillis;
		parent.traveled = 0.0;
		parent.freshStrokeRequired = true;
		parent.candidate = null;
		parent.candidateId = null;
		return true;
	}

	private Point translateForReturn(Point point, Point returnedOrigin, Point delta) {
		// Keep coincident anchors exactly coincident even when subtraction/addition rounds differently.
		if (point.equals(returnedOrigin)) return pointer;
		return new Point(point.x() + delta.x(), point.y() + delta.y());
	}

	private List<Resolved> displayedChoices(MenuState menu) {
		List<Resolved> resolved = new ArrayList<>();
		if (menu.surface) return resolved;

		if (menu == stack.get(0)) {
			for (SpatialMenu.Choice choice : menu.menu.choices()) {
				resolved.add(resolve(choice, normalize(choice.bearing() - choice.span() / 2.0), choice.span()));
			}

			return resolved;
		}

		List<SpatialMenu.Choice> choices = new ArrayList<>(menu.menu.choices());
		int backIndex = -1;

		for (int i = 0; i < choices.size(); i++) {
			if (choices.get(i).back()) {
				backIndex = i;
				break;
			}
		}

		if (backIndex < 0) {
			backIndex = choices.size();
			choices.add(SpatialMenu.Choice.back(menu.menu.id() + ":back"));
		}
		int count = choices.size();
		double span = 360.0 / count;
		double parentBearing = bearing(menu.origin, menu.parentOrigin);
		// The Back sector (explicit, wherever the caller placed it, or appended
		// last) stays centred on the parent bearing. A preference can reorder
		// non-Back choices, but never moves these equal geometry slots.
		double start = normalize(parentBearing - (backIndex + 0.5) * span);
		preferBearing(choices, menu.menu.preferredBearing(), start, span);
		int index = 0;

		for (SpatialMenu.Choice choice : choices) {
			resolved.add(resolve(choice, normalize(start + index * span), span));
			index++;
		}

		return resolved;
	}

	private static void preferBearing(List<SpatialMenu.Choice> choices, SpatialMenu.PreferredBearing preference,
		double start, double span) {
		if (preference == null) return;
		int source = -1, target = -1;
		double closest = Double.POSITIVE_INFINITY;
		for (int index = 0; index < choices.size(); index++) {
			SpatialMenu.Choice choice = choices.get(index);
			if (choice.back()) continue;
			if (choice.id().equals(preference.choiceId())) source = index;
			double slotStart = normalize(start + index * span);
			// Use the hit-test convention at an equal-distance boundary: the
			// start-inclusive slot owns the exact preferred direction.
			double distance = containsBearing(preference.degrees(), slotStart, span) ? -1.0
				: turnDegrees(normalize(preference.degrees()), normalize(slotStart + span / 2.0));
			if (distance < closest) {
				closest = distance;
				target = index;
			}
		}
		if (source < 0 || source == target) return;
		SpatialMenu.Choice preferred = choices.get(source);
		int step = Integer.compare(target, source);
		for (int index = source; index != target;) {
			int next = index + step;
			while (choices.get(next).back()) next += step;
			choices.set(index, choices.get(next));
			index = next;
		}
		choices.set(target, preferred);
	}

	private static boolean containsBearing(double bearing, double start, double span) {
		return normalize(bearing - start) < span;
	}

	private static Resolved resolve(SpatialMenu.Choice choice, double startDegrees, double spanDegrees) {
		return new Resolved(
			choice.id(),
			choice.label(),
			choice.action(),
			choice.children(),
			choice.back(),
			choice.disabled(),
			choice.reserved(),
			startDegrees,
			spanDegrees,
			choice.outlineColor());
	}

	private static double bearing(Point from, Point to) {
		return normalize(Math.toDegrees(Math.atan2(to.x() - from.x(), from.y() - to.y())));
	}

	private boolean retracing(MenuState menu, Point previous) {
		if (tuning.hoverEnabled() || menu.rowAnchored || stack.size() < 2) {
			return false;
		}

		double length = distance(menu.origin, menu.parentOrigin);

		if (length <= 0.0) {
			return false;
		}

		double directionX = menu.parentOrigin.x() - menu.origin.x();
		double directionY = menu.parentOrigin.y() - menu.origin.y();
		double offsetX = pointer.x() - menu.origin.x();
		double offsetY = pointer.y() - menu.origin.y();
		double previousX = previous.x() - menu.origin.x();
		double previousY = previous.y() - menu.origin.y();
		double toward = (offsetX * directionX + offsetY * directionY) / length;
		double previousToward = (previousX * directionX + previousY * directionY) / length;
		double sideways = Math.abs(offsetX * directionY - offsetY * directionX) / length;

		// Any strictly parent-directed progress qualifies, so incremental samples
		// accumulate to the same net distance as one large reverse stroke; zero,
		// tangential and outward samples add no progress.
		return toward >= Math.min(RETRACE_MIN_DISTANCE, length * RETRACE_RATIO)
			&& toward > previousToward
			&& sideways <= RETRACE_LATERAL_LIMIT;
	}

	private static double turnDegrees(double outbound, double inbound) {
		return Math.abs(((outbound - inbound + 540.0) % 360.0) - 180.0);
	}

	private static double normalize(double degrees) {
		return ((degrees % 360.0) + 360.0) % 360.0;
	}

	private static double distance(Point a, Point b) {
		return Math.hypot(a.x() - b.x(), a.y() - b.y());
	}

	/** Mutable per-menu session state; never exposed outside the controller. */
	private static final class MenuState {

		SpatialMenu menu;
		Point origin;
		Point parentOrigin;
		Resolved focus;
		long enteredAt;
		long lastMovement;
		double traveled;
		boolean freshStrokeRequired;
		Point candidate;
		String candidateId;
		boolean rowAnchored;
		boolean external;
		boolean surface;
		boolean externalBackFocus;
		/** Parent-menu choice that opened this menu; null for the root and external pushes. */
		String entryChoiceId;

		MenuState(SpatialMenu menu, Point origin, Point parentOrigin) {
			this.menu = menu;
			this.origin = origin;
			this.parentOrigin = parentOrigin;
		}
	}

	/** One entry with resolved sector geometry. */
	private record Resolved(
		String id,
		String label,
		String action,
		SpatialMenu children,
		boolean back,
		boolean disabled,
		boolean reserved,
		double startDegrees,
		double spanDegrees,
		Integer outlineColor
	) {
		boolean branch() {
			return children != null && !children.choices().isEmpty();
		}
	}
}
