package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ServerConfigUpdate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure navigation state for the categorized settings screen: the client/server
 * scope tree, the current page, and per-page viewport state.  It owns no
 * Minecraft types and no server-settings state, so the screen controller can
 * route validation and permission consequences through it without coupling
 * navigation to the shared server session.
 */
public final class SettingsNavigationModel {
	public enum Scope {
		CLIENT,
		SERVER
	}

	/**
	 * A category entry of one scope overview.  {@link #id()} is the stable
	 * English resource suffix of the category, not a localized label.
	 */
	public enum Category {
		MARKER_DISPLAY(Scope.CLIENT, "display"),
		TARGET_SELECTION(Scope.CLIENT, "selection"),
		WHEEL_APPEARANCE(Scope.CLIENT, "wheel_appearance"),
		INPUT_INTERACTION(Scope.CLIENT, "input"),
		CHANNEL_NOTICES(Scope.CLIENT, "channel_notices"),
		GEOMETRY_CONFIG(Scope.CLIENT, "geometry_config"),
		CHANNEL_PLAYERS(Scope.SERVER, "channel_players"),
		SEND_RATE(Scope.SERVER, "send_rate"),
		MARKER_DURATION(Scope.SERVER, "marker_duration");

		private final Scope scope;
		private final String id;

		Category(Scope scope, String id) {
			this.scope = scope;
			this.id = id;
		}

		public Scope scope() {
			return scope;
		}

		public String id() {
			return id;
		}

		/** The leaf page that shows this category. */
		public Page page() {
			return Page.forCategory(this);
		}
	}

	/**
	 * One overview or category page.  The enum identity is stable across screen
	 * rebuilds, so it also identifies the page's retained viewport state.
	 */
	public enum Page {
		CLIENT_OVERVIEW(Scope.CLIENT, null),
		CLIENT_MARKER_DISPLAY(Scope.CLIENT, Category.MARKER_DISPLAY),
		CLIENT_TARGET_SELECTION(Scope.CLIENT, Category.TARGET_SELECTION),
		CLIENT_WHEEL_APPEARANCE(Scope.CLIENT, Category.WHEEL_APPEARANCE),
		CLIENT_INPUT_INTERACTION(Scope.CLIENT, Category.INPUT_INTERACTION),
		CLIENT_CHANNEL_NOTICES(Scope.CLIENT, Category.CHANNEL_NOTICES),
		CLIENT_GEOMETRY_CONFIG(Scope.CLIENT, Category.GEOMETRY_CONFIG),
		SERVER_OVERVIEW(Scope.SERVER, null),
		SERVER_CHANNEL_PLAYERS(Scope.SERVER, Category.CHANNEL_PLAYERS),
		SERVER_SEND_RATE(Scope.SERVER, Category.SEND_RATE),
		SERVER_MARKER_DURATION(Scope.SERVER, Category.MARKER_DURATION);

		private final Scope scope;
		private final Category category;

		Page(Scope scope, Category category) {
			this.scope = scope;
			this.category = category;
		}

		public Scope scope() {
			return scope;
		}

		/** The category of a leaf page, or empty for an overview. */
		public Optional<Category> category() {
			return Optional.ofNullable(category);
		}

		public boolean isOverview() {
			return category == null;
		}

		public boolean isLeaf() {
			return category != null;
		}

		/** The overview page of the scope. */
		public static Page overview(Scope scope) {
			return scope == Scope.CLIENT ? CLIENT_OVERVIEW : SERVER_OVERVIEW;
		}

		/** The leaf page that shows the category. */
		public static Page forCategory(Category category) {
			for (Page page : values()) {
				if (page.category == category) {
					return page;
				}
			}

			throw new IllegalArgumentException("category has no page: " + category);
		}
	}

	private static final Map<Scope, List<Category>> CATEGORIES = Map.of(
		Scope.CLIENT,
		List.of(
			Category.MARKER_DISPLAY,
			Category.TARGET_SELECTION,
			Category.WHEEL_APPEARANCE,
			Category.INPUT_INTERACTION,
			Category.CHANNEL_NOTICES,
			Category.GEOMETRY_CONFIG),
		Scope.SERVER,
		List.of(
			Category.CHANNEL_PLAYERS,
			Category.SEND_RATE,
			Category.MARKER_DURATION));

	private static final PageViewState EMPTY_VIEW_STATE = new PageViewState(0, null);

	private final Map<Page, PageViewState> viewStates = new EnumMap<>(Page.class);
	private Page current = Page.CLIENT_OVERVIEW;

	/** The immutable category catalogue of the scope, in overview order. */
	public static List<Category> categories(Scope scope) {
		return CATEGORIES.get(scope);
	}

	public Page current() {
		return current;
	}

	public Scope scope() {
		return current.scope();
	}

	public boolean isOverview() {
		return current.isOverview();
	}

	public boolean isLeaf() {
		return current.isLeaf();
	}

	/** The current page's retained scroll amount, or zero when none was recorded. */
	public int scrollAmount() {
		return viewState(current).scrollAmount();
	}

	/**
	 * The stable key of the control to focus when the current page is shown
	 * again, or {@code null} when no focus was recorded.  The key is
	 * caller-defined text, never a widget reference.
	 */
	public String focusKey() {
		return viewState(current).focusKey();
	}

	/**
	 * Records the viewport state of the current page.  Navigation never clears a
	 * page's recorded state, so the controller calls this with the departing
	 * scroll amount and focus key before it switches pages; a negative scroll
	 * amount is clamped to zero.
	 */
	public void saveViewStateBeforeNavigation(int scrollAmount, String focusKey) {
		viewStates.put(current, new PageViewState(Math.max(0, scrollAmount), focusKey));
	}

	/** Opens the selected scope's overview and returns it. */
	public Page selectScope(Scope scope) {
		current = Page.overview(scope);
		return current;
	}

	/**
	 * Opens the category's leaf page when the category belongs to the current
	 * scope.  A foreign-scope category is rejected without navigating.
	 */
	public boolean openCategory(Category category) {
		if (category.scope() != current.scope()) {
			return false;
		}

		current = Page.forCategory(category);
		return true;
	}

	/**
	 * Returns a leaf page to its scope overview and reports {@code true}.  At an
	 * overview this reports {@code false} and keeps the page, because closing the
	 * screen is the screen's decision, not the navigation session's.
	 */
	public boolean back() {
		if (!current.isLeaf()) {
			return false;
		}

		current = Page.overview(current.scope());
		return true;
	}

	/** Forces the page for a validation or permission consequence. */
	public void forcePage(Page page) {
		current = page;
	}

	/**
	 * Routes to the server category that owns the first invalid numeric field.
	 * The send-rate category is checked before marker duration so a mask that
	 * contains several invalid fields lands deterministically.  A mask with no
	 * invalid numeric field leaves the current page unchanged and reports
	 * {@code false}.
	 */
	public boolean routeToInvalidServerDraft(int invalidFieldMask) {
		if ((invalidFieldMask & (ServerConfigUpdate.MS_TO_REGENERATE | ServerConfigUpdate.RATE_LIMIT)) != 0) {
			current = Page.SERVER_SEND_RATE;
			return true;
		}

		if ((invalidFieldMask & ServerConfigUpdate.SYNC_DURATION) != 0) {
			current = Page.SERVER_MARKER_DURATION;
			return true;
		}

		return false;
	}

	private PageViewState viewState(Page page) {
		return viewStates.getOrDefault(page, EMPTY_VIEW_STATE);
	}

	private record PageViewState(int scrollAmount, String focusKey) {
	}
}
