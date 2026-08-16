package nx.pingwheel.common.name;

import java.util.Objects;

/**
 * The server-authoritative JSON payload of a target's display name.
 *
 * <p>The value is a serialized Minecraft text component in JSON form
 * (for example {@code {"translate":"minecraft.zombie"}}). It is always
 * produced by the server; there is no client input path, and the client
 * never sends a name.
 *
 * <p>The value is immutable and validated: non-null, non-blank, and at most
 * {@link #MAX_LENGTH} characters. {@code MAX_LENGTH} matches the hard cap of
 * the network UTF encoding (see {@code MarkerPacketCodec}), so every valid
 * instance can travel the wire unchanged.
 *
 * <p>Only JDK types are used here; there are no {@code net.minecraft}
 * references, so this value can be constructed and validated without a game
 * client.
 */
public record TargetNameJson(String value) {

	/**
	 * The maximum accepted string length in characters, matching the hard cap
	 * of the network UTF string encoding (32767).
	 */
	public static final int MAX_LENGTH = 32767;

	public TargetNameJson {
		Objects.requireNonNull(value, "value");

		if (value.isBlank()) {
			throw new IllegalArgumentException("target name JSON must not be blank");
		}

		if (value.length() > MAX_LENGTH) {
			throw new IllegalArgumentException(
				"target name JSON exceeds " + MAX_LENGTH + " characters: " + value.length());
		}
	}
}
