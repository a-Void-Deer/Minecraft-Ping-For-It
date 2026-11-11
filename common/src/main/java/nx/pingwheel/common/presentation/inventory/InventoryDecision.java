package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One mutually exclusive inventory decision. The failure reason carries no
 * impact: the deciding operation supplies impact explicitly because the same
 * cause can be a hard rejection, a defer or detail-only loss at different sites.
 */
@NotNullByDefault
public final class InventoryDecision<T> {

	@NotNullByDefault
	public enum Kind {
		ACCEPT,
		DEFER,
		UNSUPPORTED,
		REJECT
	}

	private final Kind kind;
	private final @Nullable T value;
	private final @Nullable InventoryFailureReason reason;
	private final @Nullable InventoryImpact impact;

	private InventoryDecision(Kind kind, @Nullable T value, @Nullable InventoryFailureReason reason,
		@Nullable InventoryImpact impact) {
		this.kind = kind;
		this.value = value;
		this.reason = reason;
		this.impact = impact;
	}

	public static <T> InventoryDecision<T> accept(T value) {
		return new InventoryDecision<>(Kind.ACCEPT, Objects.requireNonNull(value, "value"), null, null);
	}

	public static <T> InventoryDecision<T> defer(InventoryFailureReason reason, InventoryImpact impact) {
		return failure(Kind.DEFER, reason, impact);
	}

	public static <T> InventoryDecision<T> unsupported(InventoryFailureReason reason, InventoryImpact impact) {
		return failure(Kind.UNSUPPORTED, reason, impact);
	}

	public static <T> InventoryDecision<T> reject(InventoryFailureReason reason, InventoryImpact impact) {
		return failure(Kind.REJECT, reason, impact);
	}

	private static <T> InventoryDecision<T> failure(Kind kind, InventoryFailureReason reason, InventoryImpact impact) {
		return new InventoryDecision<>(kind, null,
			Objects.requireNonNull(reason, "reason"),
			Objects.requireNonNull(impact, "impact"));
	}

	public Kind kind() {
		return kind;
	}

	public boolean accepted() {
		return kind == Kind.ACCEPT;
	}

	public boolean deferred() {
		return kind == Kind.DEFER;
	}

	public boolean unsupported() {
		return kind == Kind.UNSUPPORTED;
	}

	public boolean rejected() {
		return kind == Kind.REJECT;
	}

	/** Present only for an accepted decision. */
	public @Nullable T value() {
		return value;
	}

	/** Present only for a non-accepted decision. */
	public @Nullable InventoryFailureReason reason() {
		return reason;
	}

	/** Present only for a non-accepted decision; never derived from {@link #reason()}. */
	public @Nullable InventoryImpact impact() {
		return impact;
	}

	public InventoryOutcome outcome() {
		return switch (kind) {
			case ACCEPT -> InventoryOutcome.ACCEPTED;
			case DEFER -> InventoryOutcome.DEFERRED;
			case UNSUPPORTED -> InventoryOutcome.UNSUPPORTED;
			case REJECT -> InventoryOutcome.REJECTED;
		};
	}
}
