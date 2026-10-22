package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Type;

/**
 * Presence-aware deserialization for the persisted inventory cap objects.
 *
 * <p>The reflective adapter fills the primitive {@code value} with zero when
 * the member is absent or null, which the later clamp would turn into an
 * explicit minimum instead of the owning field's model default. This adapter
 * parses through an equivalent plain {@link Gson} so malformed-input behavior
 * stays identical, then records the missing-member state for
 * {@link InventorySettings#validate()}.
 */
final class IntLimitDeserializer implements JsonDeserializer<IntLimit> {

	private static final Gson REFLECTIVE = new Gson();

	@Override
	public IntLimit deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
		IntLimit limit = REFLECTIVE.fromJson(json, IntLimit.class);
		if (limit != null && lacksValueMember(json)) {
			limit.markValueMissing();
		}
		return limit;
	}

	private static boolean lacksValueMember(JsonElement json) {
		if (!json.isJsonObject()) return false;
		JsonElement value = json.getAsJsonObject().get("value");
		return value == null || value.isJsonNull();
	}
}
