package nx.pingwheel.common.marker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nx.pingwheel.common.presentation.PresentationGrant;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The immutable creation-time presentation authorization of one marker.
 *
 * <p>The map contains exactly one entry for every canonical audience member,
 * including a deny-all entry. A missing recipient is denial rather than an
 * accidental allow. Audience reduction filters the same map; it never
 * broadens a surviving entry.
 */
@NotNullByDefault
public record PingAuthorization(@Unmodifiable Map<UUID, PresentationGrant> recipients) {

	public PingAuthorization {
		Map<UUID, PresentationGrant> copy = new LinkedHashMap<>();
		Objects.requireNonNull(recipients, "recipients").forEach((id, grant) -> {
			copy.put(Objects.requireNonNull(id, "recipient"), Objects.requireNonNull(grant, "grant"));
		});
		recipients = Map.copyOf(copy);
	}

	public PresentationGrant forRecipient(UUID recipient) {
		PresentationGrant grant = recipient == null ? null : recipients.get(recipient);
		return grant == null ? PresentationGrant.denied() : grant;
	}

	public PingAuthorization retaining(@Unmodifiable List<UUID> audience) {
		Objects.requireNonNull(audience, "audience");
		Map<UUID, PresentationGrant> retained = new LinkedHashMap<>();
		for (UUID recipient : audience) {
			PresentationGrant grant = recipients.get(recipient);
			if (grant != null) retained.put(recipient, grant);
		}
		return new PingAuthorization(retained);
	}

	public static PingAuthorization denied(@Unmodifiable List<UUID> audience) {
		Objects.requireNonNull(audience, "audience");
		Map<UUID, PresentationGrant> denied = new LinkedHashMap<>();
		for (UUID recipient : audience) denied.put(Objects.requireNonNull(recipient, "recipient"), PresentationGrant.denied());
		return new PingAuthorization(denied);
	}
}
