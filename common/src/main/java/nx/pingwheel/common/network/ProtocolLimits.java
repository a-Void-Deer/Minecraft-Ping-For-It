package nx.pingwheel.common.network;

/**
 * Wire-format limits shared by both source sets.
 *
 * <p>{@code MAX_CHANNEL_LENGTH} previously lived on the client-only
 * {@code ClientConfig}, which made the shared packet codecs depend on a client
 * class. Moved here so the shared source set stays free of client references.</p>
 */
public final class ProtocolLimits {

	private ProtocolLimits() {}

	/** Maximum {@code readUtf}/{@code writeUtf} length for the channel field. */
	public static final int MAX_CHANNEL_LENGTH = 128;
}
