package nx.pingwheel.common.platform;

import org.jetbrains.annotations.NotNullByDefault;

/** Connection-local transport capability, not domain negotiation or authorization. */
@NotNullByDefault
public enum RouteSupport {
	/** No connection or insufficient evidence that route negotiation is ready. */
	UNKNOWN,
	/** The queried direction currently permits this exact route. */
	AVAILABLE,
	/** The ready connection or local transport explicitly lacks this exact route. */
	UNAVAILABLE
}
