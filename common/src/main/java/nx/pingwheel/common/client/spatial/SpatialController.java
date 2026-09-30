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
 * <p>Entering a focused branch is currently done by the stationary dwell tick
 * ({@link #tick(long)}); the qualified-turn entry path from the prototype is
 * intentionally not implemented in this unit. Return is delegated to the
 * existing {@link BackHoverController} when hover is enabled and otherwise by a
 * Back dwell, and a pop always arms {@code freshStrokeRequired} so a stationary
 * pointer cannot re-enter immediately.
 *
 * <p>Nothing here touches Minecraft, a renderer, configuration, or an input
 * callback; {@link Snapshot} is the read model a rectangular renderer consumes.
 */
public final class SpatialController {

	/** Prototype runtime policy constants, not player-facing settings. */
	private static final double DWELL_MIN_TRAVEL = 8.0;
	private static final int TRAIL_LIMIT = 65;

	/** A point in controller space (GUI pixels for the renderer). */
	public record Point(double x, double y) {}

	/** Frozen session parameters supplied by the configuration owner. */
	public record Tuning(double deadzone, double stroke, long dwellMillis, boolean hoverEnabled, long hoverMillis) {

		public Tuning {
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

	/** One entry as resolved for rendering or hit-testing. */
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
		double spanDegrees
	) {}

	/** One menu of the current ancestor path. */
	public record MenuView(
		String menuId,
		Point origin,
		Point parentOrigin,
		String focusId,
		List<ChoiceView> choices
	) {}

	/** Immutable read model for the renderer. */
	public record Snapshot(
		boolean active,
		Point pointer,
		List<Point> trail,
		String focusId,
		List<MenuView> menus,
		double hoverProgress
	) {}

	private final SpatialMenu root;
	private final Tuning tuning;
	private final BackHoverController hover = new BackHoverController();

	private boolean active;
	private Point pointer = new Point(0.0, 0.0);
	private final List<Point> trail = new ArrayList<>();
	private final List<MenuState> stack = new ArrayList<>();
	private double hoverProgress;

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
		pointer = new Point(0.0, 0.0);
		trail.clear();
		trail.add(pointer);
		stack.clear();

		MenuState rootState = new MenuState(root, pointer, pointer);
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

	/** Adds a physical delta to the virtual pointer and refreshes the focus. */
	public void movePhysical(double deltaX, double deltaY, long nowMillis) {
		if (!active) {
			return;
		}

		MenuState menu = activeMenu();
		pointer = new Point(pointer.x() + deltaX, pointer.y() + deltaY);
		menu.traveled += Math.hypot(deltaX, deltaY);
		trail.add(pointer);

		if (trail.size() > TRAIL_LIMIT) {
			trail.remove(0);
		}

		menu.lastMovement = nowMillis;
		setFocus(nowMillis);
		updateHover(nowMillis);
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

		MenuState parent = activeMenu();
		Point origin = new Point(originX, originY);
		MenuState state = new MenuState(menu, origin, parent.origin);
		state.enteredAt = nowMillis;
		state.lastMovement = nowMillis;
		stack.add(state);
		rebaseInternal(origin, nowMillis);
	}

	/** Moves the virtual pointer without physical travel (external rebase). */
	public void rebase(double x, double y, long nowMillis) {
		if (!active) {
			return;
		}

		rebaseInternal(new Point(x, y), nowMillis);
	}

	/** The renderer read model for the current frame. */
	public Snapshot snapshot() {
		if (!active) {
			return new Snapshot(false, pointer, List.of(), null, List.of(), 0.0);
		}

		List<MenuView> menus = new ArrayList<>();

		for (MenuState state : stack) {
			String focusId = state.focus == null ? null : state.focus.id();
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
					choice.spanDegrees()));
			}

			menus.add(new MenuView(state.menu.id(), state.origin, state.parentOrigin, focusId, List.copyOf(choices)));
		}

		String focusId = activeMenu().focus == null ? null : activeMenu().focus.id();
		return new Snapshot(true, pointer, List.copyOf(trail), focusId, List.copyOf(menus), hoverProgress);
	}

	private void rebaseInternal(Point point, long nowMillis) {
		pointer = point;
		trail.clear();
		trail.add(point);
		setFocus(nowMillis);
		updateHover(nowMillis);
	}

	private void endSession() {
		active = false;
		stack.clear();
		trail.clear();
		pointer = new Point(0.0, 0.0);
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
			menu.focus = next;
			menu.enteredAt = nowMillis;
		}
	}

	private Resolved focusAt(MenuState menu) {
		double dx = pointer.x() - menu.origin.x();
		double dy = pointer.y() - menu.origin.y();

		if (Math.hypot(dx, dy) <= tuning.deadzone()) {
			return null;
		}

		double bearing = Math.round(normalize(Math.toDegrees(Math.atan2(dx, -dy))) * 1.0e8) / 1.0e8;

		for (Resolved choice : displayedChoices(menu)) {
			if (normalize(bearing - choice.startDegrees()) < choice.spanDegrees()) {
				return choice;
			}
		}

		return null;
	}

	private boolean updateHover(long nowMillis) {
		MenuState menu = activeMenu();
		boolean backFocused = menu.focus != null && menu.focus.back();
		Result result = hover.update(menu.menu.id(), backFocused, nowMillis);

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
		MenuState parent = activeMenu();
		MenuState child = new MenuState(choice.children(), pointer, parent.origin);
		child.enteredAt = nowMillis;
		child.lastMovement = nowMillis;
		stack.add(child);
		setFocus(nowMillis);
		updateHover(nowMillis);
	}

	private boolean pop(long nowMillis) {
		if (stack.size() < 2) {
			return false;
		}

		stack.remove(stack.size() - 1);
		MenuState parent = activeMenu();
		parent.focus = focusAt(parent);
		parent.enteredAt = nowMillis;
		parent.lastMovement = nowMillis;
		parent.traveled = 0.0;
		parent.freshStrokeRequired = true;
		return true;
	}

	private List<Resolved> displayedChoices(MenuState menu) {
		List<Resolved> resolved = new ArrayList<>();

		if (menu == stack.get(0)) {
			for (SpatialMenu.Choice choice : menu.menu.choices()) {
				resolved.add(resolve(choice, normalize(choice.bearing() - choice.span() / 2.0), choice.span()));
			}

			return resolved;
		}

		int backIndex = -1;

		for (int i = 0; i < menu.menu.choices().size(); i++) {
			if (menu.menu.choices().get(i).back()) {
				backIndex = i;
				break;
			}
		}

		boolean hasBack = backIndex >= 0;
		int count = menu.menu.choices().size() + (hasBack ? 0 : 1);
		double span = 360.0 / count;
		double parentBearing = bearing(menu.origin, menu.parentOrigin);
		// The Back sector (explicit, wherever the caller placed it, or appended
		// last) stays centred on the parent bearing; every sibling keeps its
		// order and equal span.
		int anchorIndex = hasBack ? backIndex : count - 1;
		double start = normalize(parentBearing - (anchorIndex + 0.5) * span);
		int index = 0;

		for (SpatialMenu.Choice choice : menu.menu.choices()) {
			resolved.add(resolve(choice, normalize(start + index * span), span));
			index++;
		}

		if (!hasBack) {
			resolved.add(new Resolved(
				menu.menu.id() + ":back",
				null,
				null,
				null,
				true,
				false,
				false,
				normalize(start + index * span),
				span));
		}

		return resolved;
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
			spanDegrees);
	}

	private static double bearing(Point from, Point to) {
		return normalize(Math.toDegrees(Math.atan2(to.x() - from.x(), from.y() - to.y())));
	}

	private static double normalize(double degrees) {
		return ((degrees % 360.0) + 360.0) % 360.0;
	}

	private static double distance(Point a, Point b) {
		return Math.hypot(a.x() - b.x(), a.y() - b.y());
	}

	/** Mutable per-menu session state; never exposed outside the controller. */
	private static final class MenuState {

		final SpatialMenu menu;
		final Point origin;
		final Point parentOrigin;
		Resolved focus;
		long enteredAt;
		long lastMovement;
		double traveled;
		boolean freshStrokeRequired;

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
		double spanDegrees
	) {
		boolean branch() {
			return children != null && !children.choices().isEmpty();
		}
	}
}
