package nx.pingwheel.common.integration.externalblock;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.marker.MarkerAnchor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalBlockRefreshTriageTest {

	private static final Target.ExternalBlockTarget TARGET = Target.ExternalBlockTarget.committed(
		"minecraft:overworld", "provider:test", "tracking-id", "minecraft:chest", "locator", true);
	private static final MarkerAnchor ANCHOR = new MarkerAnchor(1.0, 2.0, 3.0);

	@Test
	void availableRefreshUpdatesOnlyWhenIdentityIsPreserved() {
		ExternalBlockServerProvider.RefreshResult available =
			new ExternalBlockServerProvider.RefreshResult.Available(
				TARGET, TargetMatchContext.blockEntityBlock(true), ANCHOR);

		assertEquals(
			ExternalBlockRefreshTriage.Action.UPDATE,
			ExternalBlockRefreshTriage.action(available, true));
		assertEquals(
			ExternalBlockRefreshTriage.Action.REMOVE,
			ExternalBlockRefreshTriage.action(available, false));
	}

	@Test
	void temporarilyUnavailableRefreshRetainsTheMarker() {
		assertEquals(
			ExternalBlockRefreshTriage.Action.RETAIN,
			ExternalBlockRefreshTriage.action(
				new ExternalBlockServerProvider.RefreshResult.TemporarilyUnavailable(), false));
	}

	@Test
	void invalidRefreshRemovesTheMarker() {
		ExternalBlockServerProvider.RefreshResult invalid =
			new ExternalBlockServerProvider.RefreshResult.Invalid();

		assertEquals(
			ExternalBlockRefreshTriage.Action.REMOVE,
			ExternalBlockRefreshTriage.action(invalid, false));
		assertEquals(
			ExternalBlockRefreshTriage.Action.REMOVE,
			ExternalBlockRefreshTriage.action(invalid, true));
	}
}
