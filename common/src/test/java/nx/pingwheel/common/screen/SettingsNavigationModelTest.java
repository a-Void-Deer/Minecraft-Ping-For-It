package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import nx.pingwheel.common.screen.SettingsNavigationModel.Page;
import nx.pingwheel.common.screen.SettingsNavigationModel.Scope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsNavigationModelTest {
	private static final ServerConfigSnapshot EDITABLE = new ServerConfigSnapshot(
		true,
		ChannelMode.AUTO,
		true,
		1000,
		5);

	@Test
	void initialPageIsTheClientOverview() {
		var navigation = new SettingsNavigationModel();

		assertEquals(Page.CLIENT_OVERVIEW, navigation.current());
		assertEquals(Scope.CLIENT, navigation.scope());
		assertTrue(navigation.isOverview());
		assertFalse(navigation.isLeaf());
		assertEquals(0, navigation.scrollAmount());
		assertNull(navigation.focusKey());
	}

	@Test
	void eachScopeSelectsItsOwnOverview() {
		var navigation = new SettingsNavigationModel();

		assertEquals(Page.SERVER_OVERVIEW, navigation.selectScope(Scope.SERVER));
		assertEquals(Scope.SERVER, navigation.scope());
		assertTrue(navigation.isOverview());

		assertEquals(Page.CLIENT_OVERVIEW, navigation.selectScope(Scope.CLIENT));
		assertEquals(Scope.CLIENT, navigation.scope());
		assertTrue(navigation.isOverview());
	}

	@Test
	void everyCategoryOpensItsOwnLeafPageWithinItsScope() {
		var navigation = new SettingsNavigationModel();

		for (var scope : Scope.values()) {
			assertEquals(Page.overview(scope), navigation.selectScope(scope));

			for (var category : SettingsNavigationModel.categories(scope)) {
				assertEquals(scope, category.scope());
				assertTrue(navigation.openCategory(category));
				assertEquals(category.page(), navigation.current());
				assertEquals(scope, navigation.scope());
				assertTrue(navigation.isLeaf());
				assertEquals(category, navigation.current().category().orElseThrow());
			}
		}
	}

	@Test
	void categoriesAreImmutableAndMatchTheDocumentedScopeTrees() {
		var client = SettingsNavigationModel.categories(Scope.CLIENT);
		var server = SettingsNavigationModel.categories(Scope.SERVER);

		assertEquals(6, client.size());
		assertEquals(3, server.size());
		assertEquals(
			List.of("display", "selection", "wheel_appearance", "input", "channel_notices", "geometry_config"),
			client.stream().map(Category::id).toList());
		assertEquals(
			List.of("channel_players", "send_rate", "marker_duration"),
			server.stream().map(Category::id).toList());
		assertThrows(UnsupportedOperationException.class, () -> client.add(Category.MARKER_DISPLAY));

		for (var category : client) {
			assertEquals(Scope.CLIENT, category.scope());
			assertEquals(category, category.page().category().orElseThrow());
			assertTrue(category.page().isLeaf());
		}
		for (var category : server) {
			assertEquals(Scope.SERVER, category.scope());
			assertEquals(category, category.page().category().orElseThrow());
			assertTrue(category.page().isLeaf());
		}

		assertEquals(
			client.size(),
			Arrays.stream(Page.values())
				.filter(page -> page.scope() == Scope.CLIENT && page.isLeaf())
				.count());
		assertEquals(
			server.size(),
			Arrays.stream(Page.values())
				.filter(page -> page.scope() == Scope.SERVER && page.isLeaf())
				.count());
		assertEquals(Page.CLIENT_OVERVIEW, Page.overview(Scope.CLIENT));
		assertEquals(Page.SERVER_OVERVIEW, Page.overview(Scope.SERVER));
	}

	@Test
	void foreignScopeCategoryIsRejectedWithoutNavigating() {
		var navigation = new SettingsNavigationModel();
		assertEquals(Page.CLIENT_OVERVIEW, navigation.current());

		assertFalse(navigation.openCategory(Category.CHANNEL_PLAYERS));
		assertEquals(Page.CLIENT_OVERVIEW, navigation.current());

		navigation.selectScope(Scope.SERVER);
		navigation.openCategory(Category.MARKER_DURATION);
		assertFalse(navigation.openCategory(Category.MARKER_DISPLAY));
		assertEquals(Page.SERVER_MARKER_DURATION, navigation.current());
	}

	@Test
	void backReturnsToTheOwningOverviewAndReportsTheRootAsNotClosable() {
		var navigation = new SettingsNavigationModel();

		assertFalse(navigation.back());
		assertEquals(Page.CLIENT_OVERVIEW, navigation.current());

		navigation.openCategory(Category.TARGET_SELECTION);
		assertTrue(navigation.back());
		assertEquals(Page.CLIENT_OVERVIEW, navigation.current());
		assertFalse(navigation.back());
		assertEquals(Page.CLIENT_OVERVIEW, navigation.current());

		navigation.selectScope(Scope.SERVER);
		navigation.openCategory(Category.SEND_RATE);
		assertTrue(navigation.back());
		assertEquals(Page.SERVER_OVERVIEW, navigation.current());
	}

	@Test
	void viewStateIsRememberedPerPageAcrossBackScopeAndForcedRouting() {
		var navigation = new SettingsNavigationModel();

		navigation.saveViewStateBeforeNavigation(40, "reset_all");
		navigation.openCategory(Category.WHEEL_APPEARANCE);
		navigation.saveViewStateBeforeNavigation(12, "wheel_inner_radius");
		assertTrue(navigation.back());
		assertEquals(40, navigation.scrollAmount());
		assertEquals("reset_all", navigation.focusKey());

		navigation.selectScope(Scope.SERVER);
		navigation.saveViewStateBeforeNavigation(7, "server_settings");
		navigation.forcePage(Page.SERVER_MARKER_DURATION);
		assertEquals(0, navigation.scrollAmount());
		assertNull(navigation.focusKey());
		navigation.saveViewStateBeforeNavigation(3, "sync_duration");
		assertTrue(navigation.back());
		assertEquals(7, navigation.scrollAmount());
		assertEquals("server_settings", navigation.focusKey());

		navigation.forcePage(Page.CLIENT_WHEEL_APPEARANCE);
		assertEquals(12, navigation.scrollAmount());
		assertEquals("wheel_inner_radius", navigation.focusKey());
		navigation.forcePage(Page.CLIENT_OVERVIEW);
		assertEquals(40, navigation.scrollAmount());
		assertEquals("reset_all", navigation.focusKey());
	}

	@Test
	void negativeScrollIsClampedToZeroAndFocusKeyStaysNullable() {
		var navigation = new SettingsNavigationModel();
		navigation.openCategory(Category.INPUT_INTERACTION);
		assertNull(navigation.focusKey());

		navigation.saveViewStateBeforeNavigation(-5, null);
		assertEquals(0, navigation.scrollAmount());
		assertNull(navigation.focusKey());

		navigation.saveViewStateBeforeNavigation(9, "wheel_hold_millis");
		assertEquals(9, navigation.scrollAmount());
		assertEquals("wheel_hold_millis", navigation.focusKey());
	}

	@Test
	void invalidDraftMaskRoutesToTheServerCategoryThatOwnsTheFirstInvalidField() {
		var navigation = new SettingsNavigationModel();

		assertFalse(navigation.routeToInvalidServerDraft(0));
		assertEquals(Page.CLIENT_OVERVIEW, navigation.current());

		assertTrue(navigation.routeToInvalidServerDraft(ServerConfigUpdate.MS_TO_REGENERATE));
		assertEquals(Page.SERVER_SEND_RATE, navigation.current());

		navigation.forcePage(Page.CLIENT_MARKER_DISPLAY);
		assertTrue(navigation.routeToInvalidServerDraft(ServerConfigUpdate.RATE_LIMIT));
		assertEquals(Page.SERVER_SEND_RATE, navigation.current());

		navigation.forcePage(Page.CLIENT_MARKER_DISPLAY);
		assertTrue(navigation.routeToInvalidServerDraft(ServerConfigUpdate.SYNC_DURATION));
		assertEquals(Page.SERVER_MARKER_DURATION, navigation.current());

		navigation.forcePage(Page.SERVER_MARKER_DURATION);
		assertTrue(navigation.routeToInvalidServerDraft(
			ServerConfigUpdate.MS_TO_REGENERATE | ServerConfigUpdate.SYNC_DURATION));
		assertEquals(Page.SERVER_SEND_RATE, navigation.current());

		navigation.forcePage(Page.SERVER_MARKER_DURATION);
		assertFalse(navigation.routeToInvalidServerDraft(
			ServerConfigUpdate.DEFAULT_CHANNEL_MODE | ServerConfigUpdate.PLAYER_TRACKING_ENABLED));
		assertEquals(Page.SERVER_MARKER_DURATION, navigation.current());
	}

	@Test
	void navigationKeepsTheSharedServerSessionDraftAndRoutesItsInvalidMask() {
		var serverSettings = new ServerSettingsModel(true);
		var navigation = new SettingsNavigationModel();

		long requestId = serverSettings.beginExpansion();
		assertTrue(serverSettings.applySnapshot(requestId, EDITABLE));
		serverSettings.setRateLimitText("99");

		navigation.selectScope(Scope.SERVER);
		navigation.openCategory(Category.CHANNEL_PLAYERS);
		navigation.saveViewStateBeforeNavigation(18, "player_tracking_enabled");
		assertTrue(navigation.back());
		navigation.selectScope(Scope.CLIENT);
		navigation.openCategory(Category.MARKER_DISPLAY);

		assertTrue(serverSettings.dirty());
		assertEquals(ServerConfigUpdate.RATE_LIMIT, serverSettings.updatePlan().orElseThrow().changedFields());
		assertEquals("99", serverSettings.rateLimitText());

		serverSettings.setRateLimitText("");
		assertTrue(navigation.routeToInvalidServerDraft(serverSettings.invalidFieldMask()));
		assertEquals(Page.SERVER_SEND_RATE, navigation.current());
	}
}
