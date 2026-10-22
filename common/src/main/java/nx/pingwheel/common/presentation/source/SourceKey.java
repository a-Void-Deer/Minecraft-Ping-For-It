package nx.pingwheel.common.presentation.source;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Detached, server-owned identity of one physical source together with the
 * compatible read context used to observe it.
 *
 * <p>The key never contains a Ping ID, a consumer identity, a session, or a
 * source-instance generation counter: a later Ping, preview, or tracking
 * consumer observing the same provider identity and context produces the same
 * key. {@code stableId} is a provider-confirmed stable target or source
 * identity, never a refreshable locator; {@code readScope} is the canonical
 * compatible visibility/facing/context token. All components are non-blank,
 * bounded, server-owned strings, so the key is fully detached from world
 * objects.
 *
 * <p>Identity alone does not authorize a read; authorized demand is carried
 * separately by the server read scope. Two keys with different read scopes are
 * never the same source, even when the provider identity matches.
 */
public record SourceKey(String providerId, String kind, String stableId, String readScope) {

	/** Engineering bound for server-owned identity tokens; not a product value. */
	static final int MAX_TOKEN_BYTES = 256;

	public SourceKey {
		providerId = requireToken(providerId, "providerId");
		kind = requireToken(kind, "kind");
		stableId = requireToken(stableId, "stableId");
		readScope = requireToken(readScope, "readScope");
	}

	static String requireToken(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES)
			throw new IllegalArgumentException(name + " exceeds the identity token bound");
		return value;
	}
}
