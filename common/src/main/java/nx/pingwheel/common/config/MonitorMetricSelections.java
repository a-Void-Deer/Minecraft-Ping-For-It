package nx.pingwheel.common.config;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.JsonAdapter;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client-local selection overrides; the closed metric catalogue belongs to MonitorSchema. */
@NotNullByDefault
@JsonAdapter(MonitorMetricSelections.Adapter.class)
public final class MonitorMetricSelections {
	private final Map<String, Boolean> overrides = new HashMap<>();

	/** Unknown persisted IDs have no display or demand meaning. */
	public boolean isSelected(String id) {
		if (Metric.byId(id) == null) return false;
		return overrides.getOrDefault(id, Metric.defaultIds().contains(id));
	}

	/** Rejects invented metric IDs and stores only choices differing from the schema defaults. */
	public void setSelected(String id, boolean selected) {
		if (Metric.byId(id) == null) throw new IllegalArgumentException("unknown monitor metric");
		if (selected == Metric.defaultIds().contains(id)) overrides.remove(id);
		else overrides.put(id, selected);
	}

	/** Detached effective choices, including unselected details, for the known closed catalogue. */
	public @Unmodifiable Map<String, Boolean> snapshot() {
		var choices = new LinkedHashMap<String, Boolean>();
		for (Metric metric : Metric.all()) choices.put(metric.id(), isSelected(metric.id()));
		return Map.copyOf(choices);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof MonitorMetricSelections selections && overrides.equals(selections.overrides);
	}

	@Override
	public int hashCode() {
		return overrides.hashCode();
	}

	/** Unknown JSON remains raw handler-owned preservation data, never typed selector state. */
	@NotNullByDefault
	static final class Adapter implements JsonDeserializer<MonitorMetricSelections>, JsonSerializer<MonitorMetricSelections> {
		@Override
		public MonitorMetricSelections deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
			if (!json.isJsonObject()) throw new JsonParseException("monitorMetrics must be an object");
			var selections = new MonitorMetricSelections();
			@Nullable JsonElement overrides = json.getAsJsonObject().get("overrides");
			if (overrides == null || overrides.isJsonNull()) return selections;
			if (!overrides.isJsonObject()) throw new JsonParseException("monitor metric overrides must be an object");
			for (var entry : overrides.getAsJsonObject().entrySet()) {
				if (Metric.byId(entry.getKey()) == null || entry.getValue().isJsonNull()) continue;
				if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
					throw new JsonParseException("monitor metric selection must be a boolean");
				}
				selections.setSelected(entry.getKey(), entry.getValue().getAsBoolean());
			}
			return selections;
		}

		@Override
		public JsonElement serialize(MonitorMetricSelections selections, Type type, JsonSerializationContext context) {
			var overrides = new JsonObject();
			for (Metric metric : Metric.all()) {
				@Nullable Boolean selected = selections.overrides.get(metric.id());
				if (selected != null) overrides.addProperty(metric.id(), selected);
			}
			var root = new JsonObject();
			root.add("overrides", overrides);
			return root;
		}
	}
}
