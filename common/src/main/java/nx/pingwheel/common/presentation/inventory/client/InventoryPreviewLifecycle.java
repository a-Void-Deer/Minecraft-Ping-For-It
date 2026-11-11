package nx.pingwheel.common.presentation.inventory.client;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** One held, frozen Preview identity, independent of its replaceable wire attempts. */
@NotNullByDefault
public final class InventoryPreviewLifecycle {
	public static final int MAX_RETRIES = 3;
	public static final long RETRY_DELAY_MILLIS = 5_000L;
	public static final long MIN_INACTIVITY_MILLIS = 15_000L;

	private final ClientInventory owner;
	private final long localId;
	final Target target;
	final BlockFace face;
	final String targetType;
	long wireRequest = ClientInventory.NO_REQUEST;
	long lastProgressMillis;
	long retryDeadlineMillis;
	int attempts;
	int retriesUsed;
	boolean waiting;
	boolean stopped;
	boolean cancelled;
	boolean released;
	@Nullable InventoryFailureReason lastReason;

	InventoryPreviewLifecycle(ClientInventory owner, long localId, Target target, BlockFace face,
		String targetType, long now) {
		this.owner = owner;
		this.localId = localId;
		this.target = target;
		this.face = face;
		this.targetType = targetType;
		lastProgressMillis = now;
	}

	public ClientInventory owner() { return owner; }
	/** Stable selector-content fence; never use this as a selection reference. */
	public long localId() { return localId; }
	public long requestId() { return wireRequest; }
	public int attempts() { return attempts; }
	public int retriesUsed() { return retriesUsed; }
	public boolean waiting() { return waiting; }
	public boolean stopped() { return stopped; }
	public boolean cancelled() { return cancelled; }
	public @Nullable InventoryFailureReason failureReason() { return lastReason; }
	/** Retry text remains visible while the replacement attempt is running. */
	public boolean retrying() { return lastReason != null && !stopped && !cancelled && !released; }
	public void close() { owner.closePreview(this); }
}
