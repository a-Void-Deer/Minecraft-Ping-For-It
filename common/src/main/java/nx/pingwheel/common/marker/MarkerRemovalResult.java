package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The immutable outcome of a single-marker removal attempt against a
 * {@link ServerMarkerStore}.
 *
 * <p>The {@link Status} is authoritative:
 * <ul>
 *   <li>{@link Status#REMOVED}: the marker was removed; {@link #removal()}
 *       carries the removed marker and reason and {@link #winnerChanges()}
 *       carries the resulting winner transitions;</li>
 *   <li>{@link Status#NOT_FOUND}: no marker with the requested id exists;
 *       nothing changed;</li>
 *   <li>{@link Status#NOT_OWNER}: the requester does not own the marker;
 *       nothing changed.</li>
 * </ul>
 *
 * <p>Construction is restricted to the static factories so an invalid
 * combination (for example a present removal on a {@code NOT_FOUND} result)
 * cannot be built.
 */
public final class MarkerRemovalResult {

	public enum Status {
		REMOVED,
		NOT_FOUND,
		NOT_OWNER
	}

	private static final MarkerRemovalResult NOT_FOUND = new MarkerRemovalResult(Status.NOT_FOUND, null, List.of());
	private static final MarkerRemovalResult NOT_OWNER = new MarkerRemovalResult(Status.NOT_OWNER, null, List.of());

	private final Status status;
	private final Optional<MarkerRemoval> removal;
	private final List<MarkerWinnerChange> winnerChanges;

	private MarkerRemovalResult(Status status, MarkerRemoval removal, List<MarkerWinnerChange> winnerChanges) {
		this.status = status;
		this.removal = Optional.ofNullable(removal);
		this.winnerChanges = winnerChanges;
	}

	/**
	 * A successful removal: the marker was removed with the given reason.
	 */
	public static MarkerRemovalResult removed(MarkerRemoval removal, List<MarkerWinnerChange> winnerChanges) {
		Objects.requireNonNull(removal, "removal");

		return new MarkerRemovalResult(
			Status.REMOVED, removal, List.copyOf(Objects.requireNonNull(winnerChanges, "winnerChanges")));
	}

	/**
	 * No marker with the requested id exists; the store was not modified.
	 */
	public static MarkerRemovalResult notFound() {
		return NOT_FOUND;
	}

	/**
	 * The requester does not own the marker; the store was not modified.
	 */
	public static MarkerRemovalResult notOwner() {
		return NOT_OWNER;
	}

	public Status status() {
		return status;
	}

	/**
	 * The removed marker and reason; present only for {@link Status#REMOVED}.
	 */
	public Optional<MarkerRemoval> removal() {
		return removal;
	}

	/**
	 * The winner transitions caused by the removal; empty for rejected results.
	 */
	public List<MarkerWinnerChange> winnerChanges() {
		return winnerChanges;
	}
}
