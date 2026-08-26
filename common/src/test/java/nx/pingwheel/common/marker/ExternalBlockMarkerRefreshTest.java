package nx.pingwheel.common.marker;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockMarkerRefreshTest {

	private static final UUID OWNER = new UUID(0L, 1L);
	private static final UUID RECIPIENT = new UUID(0L, 2L);
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();
	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();

	@Test
	void locatorRefreshPreservesMarkerIdentityWinnerLifetimeAndAudience() {
		Target.ExternalBlockTarget original = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-1", "minecraft:chest", "locator-a", true);
		Target.ExternalBlockTarget migrated = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-1", "minecraft:chest", "locator-b", true);
		ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());

		ServerMarker created = store.create(
			OWNER,
			original,
			TARGET_TYPES.findById("entity_block").orElseThrow(),
			PING_TYPES.findById("attention").orElseThrow(),
			new MarkerAnchor(1.0, 2.0, 3.0),
			10L,
			110L,
			List.of(OWNER, RECIPIENT)).marker();

		TargetKey key = created.targetKey();
		ServerMarker updated = store.updateExternalTarget(
			created.id(), migrated, new MarkerAnchor(40.0, 50.0, 60.0)).orElseThrow();

		assertEquals(created.id(), updated.id());
		assertEquals(created.owner(), updated.owner());
		assertEquals(10L, updated.arrivalTick());
		assertEquals(110L, updated.expiresAtTick());
		assertEquals(created.recipients(), updated.recipients());
		assertEquals(key, updated.targetKey());
		assertEquals(migrated, updated.target());
		assertEquals(new MarkerAnchor(40.0, 50.0, 60.0), updated.anchor());
		assertEquals(created.id(), store.winnerFor(key, RECIPIENT).orElseThrow().id());
		assertTrue(store.updateExternalTarget(
			created.id(), Target.ExternalBlockTarget.committed(
				"minecraft:overworld", "sable", "other", "minecraft:chest", "locator-c", true),
			new MarkerAnchor(0, 0, 0)).isEmpty());
	}
}
