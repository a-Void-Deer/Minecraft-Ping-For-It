package nx.pingwheel.common.name;

import java.util.Objects;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;

/**
 * Wire-ready JSON (de)serialization of authoritative target display names.
 *
 * <p>{@link #encode} serializes a server-derived {@link Component} into the
 * compact JSON text carried by {@link TargetNameJson}, using the 1.21.1
 * {@code Component.Serializer} together with the server registry access. The
 * reverse operation, {@link #decode}, parses that JSON back into a component.
 * Both directions use the exact same vanilla codec, so a name round-trips
 * losslessly.
 *
 * <p>Malformed or invalid JSON makes {@link #decode} throw a controlled Gson
 * {@code JsonParseException}; callers catch it and fall back to the
 * {@link TargetNameComposer#unknown() unknown} display name. The raw JSON is
 * never logged.
 *
 * <p>{@link #UNKNOWN} is the single source of truth for the fail-safe unknown
 * name payload shared with the authoritative validator.
 */
public final class TargetNameJsonCodec {

	/**
	 * The fail-safe unknown-name payload:
	 * {@code {"translate":"pingforit.target.unknown"}}.
	 */
	public static final TargetNameJson UNKNOWN =
		new TargetNameJson("{\"translate\":\"pingforit.target.unknown\"}");

	private TargetNameJsonCodec() {}

	/**
	 * Serializes a trusted server-derived component into its compact JSON
	 * form.
	 *
	 * @throws NullPointerException     on null arguments
	 * @throws RuntimeException         when the component cannot be serialized
	 *                                  (contract failure; callers fall back to
	 *                                  the unknown name)
	 */
	public static TargetNameJson encode(Component component, HolderLookup.Provider registryAccess) {
		Objects.requireNonNull(component, "component");
		Objects.requireNonNull(registryAccess, "registryAccess");

		return new TargetNameJson(Component.Serializer.toJson(component, registryAccess));
	}

	/**
	 * Parses an authoritative name JSON payload back into a component.
	 *
	 * @throws NullPointerException     on null arguments
	 * @throws com.google.gson.JsonParseException
	 *                                  when the payload is not valid JSON or
	 *                                  is not a text component; this is the
	 *                                  controlled failure mode and callers
	 *                                  fall back to the unknown name
	 */
	public static Component decode(TargetNameJson json, HolderLookup.Provider registryAccess) {
		Objects.requireNonNull(json, "json");
		Objects.requireNonNull(registryAccess, "registryAccess");

		return Component.Serializer.fromJson(json.value(), registryAccess);
	}
}
