package nx.pingwheel.common.client.outline;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ExternalBlockOutlineSelectionTest {

	@Test
	void retainsExternalLocatorForRenderTimeProviderRefresh() {
		Target.ExternalBlockTarget target = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-id", "minecraft:chest", "sublevel/1,2,3", true);
		MarkerId markerId = new MarkerId(4L);
		ClientMarker marker = ClientMarker.from(
			new MarkerSnapshot(
				markerId,
				new UUID(0L, 1L),
				target,
				"entity_block",
				"attention",
				new MarkerAnchor(1.0, 2.0, 3.0),
				1L,
				20L),
			0L,
			0L);
		TargetKey key = TargetKey.from(target);

		Map<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> selected =
			BlockOutlineSelection.selectExternal(
				Map.of(key, marker),
				nx.pingwheel.common.domain.PingTypeCatalog.builtIn());

		assertEquals(target, selected.get(key).target());
		assertEquals("sublevel/1,2,3", selected.get(key).target().providerLocator());
	}

	@Test
	void locatorMigrationChangesTheRenderSpecEvenThoughMarkerIdentityIsStable() {
		Target.ExternalBlockTarget first = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-id", "minecraft:chest", "sublevel/1,2,3", true);
		Target.ExternalBlockTarget second = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-id", "minecraft:chest", "sublevel/4,5,6", true);
		MarkerId markerId = new MarkerId(4L);
		TargetKey key = TargetKey.from(first);
		ClientMarker firstMarker = marker(markerId, first);
		ClientMarker secondMarker = marker(markerId, second);

		ExternalBlockOutlineSpec firstSpec = BlockOutlineSelection.selectExternal(
			Map.of(key, firstMarker), nx.pingwheel.common.domain.PingTypeCatalog.builtIn()).get(key);
		ExternalBlockOutlineSpec secondSpec = BlockOutlineSelection.selectExternal(
			Map.of(key, secondMarker), nx.pingwheel.common.domain.PingTypeCatalog.builtIn()).get(key);

		assertEquals(firstSpec.markerId(), secondSpec.markerId());
		assertNotEquals(firstSpec, secondSpec);
	}

	private static ClientMarker marker(MarkerId id, Target.ExternalBlockTarget target) {
		return ClientMarker.from(
			new MarkerSnapshot(
				id,
				new UUID(0L, 1L),
				target,
				"entity_block",
				"attention",
				new MarkerAnchor(1.0, 2.0, 3.0),
				1L,
				20L),
			0L,
			0L);
	}
}
