package nx.pingwheel.common.presentation.client;

import nx.pingwheel.common.network.ServerPresentationPolicyC2SPacket;
import nx.pingwheel.common.presentation.PresentationPolicy;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Connection-scoped client mirror of the server presentation policy rule view.
 *
 * <p>The state starts unknown on every connection and only becomes known after
 * a matching, valid {@code OK} snapshot. An error snapshot never marks an empty
 * rule view as ready. Correlated responses are accepted only for the currently
 * pending request id; unsolicited revision broadcasts are accepted only after
 * the same connection already holds a known view and only when the revision
 * strictly advances. A disconnect clears every field, so late packets from the
 * previous connection are ignored.
 *
 * <p>At most one correlated request is outstanding at a time. A mutation may
 * only start while the known view grants edit and no request is in flight; a
 * read retry may replace another read but never an in-flight mutation. Every
 * request carries the monotonic timestamp of its start; {@link #tick()} expires
 * a request that outlives the bounded timeout. An expired mutation leaves the
 * last known view in place, marks the outcome as uncertain, and requires a
 * successful read before another mutation may start, so an ambiguous write is
 * never blindly repeated. The view status exposes the exact reason the UI may
 * not show authoritative values.
 *
 * <p>The type is deliberately free of Minecraft and platform types so the
 * settings UI can consume it and focused unit tests can drive it.
 */
public final class ServerPresentationPolicyState {
	/** Returned when no correlated request may be started on the current connection. */
	public static final long NO_PENDING_REQUEST = -1L;

	/** Bounded lifetime of one correlated request before it expires as timed out. */
	public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 5_000L;

	/**
	 * The precedence-ordered reason the current rule view may or may not be
	 * shown: connection loss first, then an in-flight request, then the last
	 * completed request's outcome, and only then a retained known view.
	 */
	public enum ViewStatus {
		/** No server connection; nothing may be sent. */
		DISCONNECTED,
		/** Connected, but the server never answered any read; not a confirmed empty view. */
		UNAVAILABLE,
		/** One correlated request is in flight. */
		PENDING,
		/** The last request expired without any response. */
		TIMED_OUT,
		/** The last correlated response was an error; a retained view is not "ready". */
		FAILED,
		/** A valid authoritative snapshot is known for this connection. */
		READY
	}

	/** Receives a callback after every visible state transition. */
	@FunctionalInterface
	public interface ChangeListener {
		void onServerPresentationPolicyChanged(ServerPresentationPolicyState state);
	}

	private static long requestIdSequence;

	private final List<ChangeListener> listeners = new ArrayList<>();
	private final LongSupplier clock;
	private final long requestTimeoutMillis;

	private boolean connected;
	private boolean known;
	private boolean canEdit;
	private long revision = -1L;
	private List<String> white = List.of();
	private List<String> black = List.of();
	private boolean whitelistOnly;
	private long pendingRequestId = NO_PENDING_REQUEST;
	private Operation pendingOperation;
	private long pendingSinceMillis = -1L;
	private boolean lastRequestTimedOut;
	private boolean mutationOutcomeUncertain;
	private Status lastStatus;

	public ServerPresentationPolicyState() {
		this(() -> System.nanoTime() / 1_000_000L, DEFAULT_REQUEST_TIMEOUT_MILLIS);
	}

	/**
	 * Creates a state driven by an explicit monotonic clock, so focused tests can
	 * advance time without sleeping.
	 */
	public ServerPresentationPolicyState(LongSupplier clock, long requestTimeoutMillis) {
		this.clock = clock;
		this.requestTimeoutMillis = Math.max(1L, requestTimeoutMillis);
	}
	/**
	 * Marks a fresh connection, clears every previous connection's view, and
	 * allocates the positive request id for the initial read request. The caller
	 * sends {@link ServerPresentationPolicyC2SPacket#read(long)} with the
	 * returned id; when the route is absent the state simply stays unknown.
	 */
	public long beginConnection() {
		connected = true;
		known = false;
		canEdit = false;
		revision = -1L;
		white = List.of();
		black = List.of();
		whitelistOnly = false;
		lastStatus = null;
		pendingOperation = null;
		pendingRequestId = nextRequestId();
		pendingSinceMillis = clock.getAsLong();
		lastRequestTimedOut = false;
		mutationOutcomeUncertain = false;
		notifyListeners();
		return pendingRequestId;
	}

	/**
	 * Allocates a fresh correlated read request id, or
	 * {@link #NO_PENDING_REQUEST} while disconnected. Replaces another pending
	 * read; an in-flight mutation is never replaced, because its outcome would
	 * become ambiguous.
	 */
	public long beginReadRequest() {
		if (!connected || pendingOperation != null) {
			return NO_PENDING_REQUEST;
		}

		pendingRequestId = nextRequestId();
		pendingSinceMillis = clock.getAsLong();
		lastRequestTimedOut = false;
		return pendingRequestId;
	}

	/**
	 * Allocates a correlated mutation request only when the connection is known,
	 * the server granted edit, no request is outstanding, and no earlier mutation
	 * timed out without a confirming read. The server still authorizes the
	 * operation independently for every request.
	 */
	public Optional<ServerPresentationPolicyC2SPacket> beginMutation(
		Operation operation,
		String selector,
		boolean whitelistOnly
	) {
		if (!canMutate() || operation == null || operation == Operation.READ) {
			return Optional.empty();
		}

		if (operation.requiresSelector() && !ServerPresentationPolicyService.isValidSelector(selector)) {
			return Optional.empty();
		}

		long requestId = nextRequestId();
		pendingRequestId = requestId;
		pendingOperation = operation;
		pendingSinceMillis = clock.getAsLong();
		lastRequestTimedOut = false;
		return Optional.of(new ServerPresentationPolicyC2SPacket(
			requestId, operation, selector == null ? "" : selector, whitelistOnly));
	}

	/**
	 * Expires the pending request after the bounded timeout. An expired mutation
	 * keeps the last known view and requires a fresh successful read before
	 * another mutation may start; an expired read simply becomes retryable.
	 * Returns whether a request expired.
	 */
	public boolean tick() {
		if (!connected || pendingRequestId == NO_PENDING_REQUEST) {
			return false;
		}
		if (clock.getAsLong() - pendingSinceMillis < requestTimeoutMillis) {
			return false;
		}

		final boolean expiredMutation = pendingOperation != null;
		pendingRequestId = NO_PENDING_REQUEST;
		pendingOperation = null;
		pendingSinceMillis = -1L;
		lastRequestTimedOut = true;
		if (expiredMutation) {
			mutationOutcomeUncertain = true;
		}
		notifyListeners();
		return true;
	}

	/**
	 * Applies one decoded snapshot. Returns whether the known rule view changed.
	 *
	 * <p>A correlated response is accepted only for the pending request id and
	 * always completes that request, even when a newer unsolicited broadcast has
	 * already published a later revision; the retained values never regress. A
	 * non-{@code OK} status is recorded as {@link #lastStatus()} without touching
	 * the known rule view; a {@code DENIED} status additionally clears the client
	 * edit hint. A response to an expired or replaced request id is ignored.
	 */
	public boolean applySnapshot(
		long requestId,
		long revision,
		Status status,
		boolean canEdit,
		List<String> white,
		List<String> black,
		boolean whitelistOnly
	) {
		if (!connected || status == null || revision < 0L) {
			return false;
		}

		if (requestId == 0L) {
			if (!known || revision <= this.revision || status != Status.OK) {
				return false;
			}

			return applyKnown(status, revision, canEdit, white, black, whitelistOnly);
		}

		if (requestId < 0L || requestId != pendingRequestId) {
			return false;
		}

		pendingRequestId = NO_PENDING_REQUEST;
		pendingOperation = null;
		pendingSinceMillis = -1L;
		lastRequestTimedOut = false;

		if (status != Status.OK) {
			lastStatus = status;
			if (status == Status.DENIED) {
				this.canEdit = false;
			}
			notifyListeners();
			return false;
		}

		mutationOutcomeUncertain = false;
		lastStatus = status;

		if (known && revision < this.revision) {
			// A newer broadcast already published a later revision. Keep those
			// values, but the correlated request is still complete, so the UI
			// must be told that no request is in flight any more.
			notifyListeners();
			return false;
		}

		return applyKnown(status, revision, canEdit, white, black, whitelistOnly);
	}
	private boolean applyKnown(
		Status status,
		long revision,
		boolean canEdit,
		List<String> white,
		List<String> black,
		boolean whitelistOnly
	) {
		final PresentationPolicy candidate;

		try {
			candidate = new PresentationPolicy(white, black, whitelistOnly);
		} catch (RuntimeException ex) {
			return false;
		}

		this.known = true;
		this.revision = revision;
		this.canEdit = canEdit;
		this.white = candidate.white();
		this.black = candidate.black();
		this.whitelistOnly = candidate.whitelistOnly();
		this.lastStatus = status;
		notifyListeners();
		return true;
	}

	/** Clears every connection-scoped field after leaving the server. */
	public void resetForDisconnect() {
		boolean hadState = connected
			|| known
			|| lastStatus != null
			|| lastRequestTimedOut
			|| mutationOutcomeUncertain;

		connected = false;
		known = false;
		canEdit = false;
		revision = -1L;
		white = List.of();
		black = List.of();
		whitelistOnly = false;
		pendingRequestId = NO_PENDING_REQUEST;
		pendingOperation = null;
		pendingSinceMillis = -1L;
		lastRequestTimedOut = false;
		mutationOutcomeUncertain = false;
		lastStatus = null;

		if (hadState) {
			notifyListeners();
		}
	}

	public boolean isConnected() {
		return connected;
	}

	/** True only after a valid OK snapshot for this connection. */
	public boolean isKnown() {
		return connected && known;
	}

	/** Explicit readiness alias for UI call sites. */
	public boolean isReady() {
		return isKnown();
	}

	/** The authoritative edit hint; false while unknown. */
	public boolean canEdit() {
		return connected && known && canEdit;
	}

	/**
	 * True while this connection may start an edit: the rule view is known, the
	 * server granted edit, no correlated request is in flight, and no earlier
	 * mutation timed out without a confirming read. UI edit controls gate on
	 * exactly this predicate, so a pending, revoked, or ambiguous state cannot
	 * present an editable control.
	 */
	public boolean canMutate() {
		return canEdit() && pendingRequestId == NO_PENDING_REQUEST && !mutationOutcomeUncertain;
	}

	/**
	 * The precedence-ordered view status the UI must render: a lost connection
	 * or a never-answered route is never presented as a ready (possibly empty)
	 * rule view, a retained view is not ready while a request is pending or the
	 * last response failed, and a timed-out request is distinct from both.
	 */
	public ViewStatus viewStatus() {
		if (!connected) {
			return ViewStatus.DISCONNECTED;
		}
		if (pendingRequestId != NO_PENDING_REQUEST) {
			return ViewStatus.PENDING;
		}
		if (lastRequestTimedOut) {
			return ViewStatus.TIMED_OUT;
		}
		if (lastStatus != null && lastStatus != Status.OK) {
			return ViewStatus.FAILED;
		}
		if (known) {
			return ViewStatus.READY;
		}
		return ViewStatus.UNAVAILABLE;
	}

	/** Current server revision, or -1 while unknown. */
	public long revision() {
		return known ? revision : -1L;
	}

	/** The known allow selectors; empty while unknown, authoritative when known. */
	public List<String> white() {
		return white;
	}

	/** The known deny selectors; empty while unknown, authoritative when known. */
	public List<String> black() {
		return black;
	}

	public boolean whitelistOnly() {
		return whitelistOnly;
	}

	/**
	 * The compiled known rule view, or {@code null} while unknown. UI consumers
	 * use this read-only policy for truthful rule explanations; it never
	 * replaces the raw correlated lists for mutation requests.
	 */
	public PresentationPolicy policy() {
		if (!isKnown()) {
			return null;
		}
		try {
			return new PresentationPolicy(white, black, whitelistOnly);
		} catch (RuntimeException invalid) {
			return null;
		}
	}

	/** The last correlated status, or null before any response. */
	public Status lastStatus() {
		return lastStatus;
	}

	/** True when the last request expired without any response. */
	public boolean lastRequestTimedOut() {
		return lastRequestTimedOut;
	}

	/**
	 * True when a mutation expired without a response, so the server may or may
	 * not have applied it; a successful read is required before another write.
	 */
	public boolean mutationOutcomeUncertain() {
		return mutationOutcomeUncertain;
	}

	public long pendingRequestId() {
		return pendingRequestId;
	}

	public void addListener(ChangeListener listener) {
		if (listener != null && !listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public void removeListener(ChangeListener listener) {
		listeners.remove(listener);
	}

	private void notifyListeners() {
		if (listeners.isEmpty()) {
			return;
		}

		for (ChangeListener listener : List.copyOf(listeners)) {
			listener.onServerPresentationPolicyChanged(this);
		}
	}

	private static synchronized long nextRequestId() {
		if (requestIdSequence == Long.MAX_VALUE) {
			requestIdSequence = 1L;
		} else {
			requestIdSequence++;
		}

		return requestIdSequence;
	}

	static synchronized void setRequestIdSequenceForTesting(long sequence) {
		requestIdSequence = sequence;
	}
}
