package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The semantic five-summary default, sparse persistence, closed IDs and detached preference snapshots. */
@NotNullByDefault
class MonitorMetricSelectionsTest {
	@Test
	void firstUseSelectsExactlyTheConfirmedFiveSummariesAndNoDetailedMetric() {
		var selections = new MonitorMetricSelections();
		@Unmodifiable Set<String> expected = Set.of("summary.processing", "summary.requests_queue", "summary.budget",
			"summary.memory", "summary.sync_rate");
		assertEquals(expected, selections.snapshot().entrySet().stream().filter(Map.Entry::getValue)
			.map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
		for (Metric metric : Metric.all()) assertEquals(expected.contains(metric.id()), selections.isSelected(metric.id()));
	}

	@Test
	void immutableSnapshotAndIndependentInstancesDoNotFollowLaterEdits() {
		var selections = new MonitorMetricSelections();
		var other = new MonitorMetricSelections();
		@Unmodifiable Map<String, Boolean> snapshot = selections.snapshot();
		selections.setSelected("summary.processing", false);
		selections.setSelected("local.preview.timing.apply.max", true);
		assertTrue(snapshot.get("summary.processing"));
		assertFalse(snapshot.get("local.preview.timing.apply.max"));
		assertThrows(UnsupportedOperationException.class, () -> snapshot.put("summary.processing", false));
		assertTrue(other.isSelected("summary.processing"));
		assertFalse(other.isSelected("local.preview.timing.apply.max"));
		assertNotEquals(other, selections);
	}

	@Test
	void sparseRoundTripHasContentEqualityAndRevertingDefaultsRemovesOverrides() {
		var gson = new Gson();
		var selections = new MonitorMetricSelections();
		selections.setSelected("summary.processing", false);
		selections.setSelected("local.preview.timing.apply.max", true);
		var saved = gson.toJsonTree(selections).getAsJsonObject();
		assertEquals(JsonParser.parseString("{\"overrides\":{\"summary.processing\":false,"
			+ "\"local.preview.timing.apply.max\":true}}"), saved);
		var copy = gson.fromJson(saved, MonitorMetricSelections.class);
		assertEquals(selections, copy);
		assertEquals(selections.hashCode(), copy.hashCode());
		copy.setSelected("summary.processing", true);
		copy.setSelected("local.preview.timing.apply.max", false);
		assertEquals(new MonitorMetricSelections(), copy);
		assertEquals(JsonParser.parseString("{\"overrides\":{}}"), gson.toJsonTree(copy));
		var explicitDefaults = gson.fromJson("{\"overrides\":{\"summary.processing\":true,"
			+ "\"local.preview.timing.apply.max\":false}}", MonitorMetricSelections.class);
		assertEquals(copy, explicitDefaults);
		assertEquals(copy.hashCode(), explicitDefaults.hashCode());
	}

	@Test
	void unknownIdsNeverBecomeSelectionsAndUnknownJsonIsNotTypedState() {
		var selections = new Gson().fromJson("{\"future\":{\"opaque\":null},\"overrides\":{"
			+ "\"future.metric\":{\"enabled\":true},\"summary.processing\":false}}", MonitorMetricSelections.class);
		assertFalse(selections.isSelected("future.metric"));
		assertFalse(selections.snapshot().containsKey("future.metric"));
		assertThrows(IllegalArgumentException.class, () -> selections.setSelected("future.metric", true));
		assertFalse(selections.isSelected("summary.processing"));
		assertFalse(new Gson().toJsonTree(selections).getAsJsonObject().has("future"));
	}

	@Test
	void missingAndNullOverridesUseDefaultsWhileKnownMistypedValuesAreRejected() {
		var gson = new Gson();
		for (String raw : List.of("{}", "{\"overrides\":null}", "{\"overrides\":{\"summary.processing\":null}}")) {
			assertEquals(new MonitorMetricSelections(), gson.fromJson(raw, MonitorMetricSelections.class));
		}
		for (String raw : List.of("[]", "{\"overrides\":[]}", "{\"overrides\":{\"summary.processing\":1}}",
			"{\"overrides\":{\"summary.processing\":\"true\"}}")) {
			assertThrows(JsonParseException.class, () -> gson.fromJson(raw, MonitorMetricSelections.class));
		}
	}
}
