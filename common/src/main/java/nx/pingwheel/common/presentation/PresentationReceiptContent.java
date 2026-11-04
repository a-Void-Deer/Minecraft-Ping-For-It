package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Authoritative initial metadata for a marker's content receipt: the selection
 * kind and explicit property references, or one server-frozen inventory creation
 * snapshot. Only the server's selected retained row supplies inventory content.
 *
 * <p>A {@code PROPERTIES} receipt requires at least one explicit reference; every
 * other kind carries none. References stay immutable, are bounded by the existing
 * property intent limit, are unique, and are held in deterministic sorted order.
 */
@NotNullByDefault
public record PresentationReceiptContent(Kind kind, @Unmodifiable List<PresentationPropertyRef> selectedRefs,
	@Nullable InventoryCreationSnapshot inventorySnapshot) {
	@NotNullByDefault
	public enum Kind { WHOLE, PROPERTIES, INVENTORY, SUPPRESSED }

	/** Source bridge for non-inventory receipts; INVENTORY requires its snapshot. */
	public PresentationReceiptContent(Kind kind, List<PresentationPropertyRef> selectedRefs) {
		this(kind, selectedRefs, null);
	}

	public PresentationReceiptContent {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(selectedRefs, "selectedRefs");
		if (selectedRefs.size() > PresentationCodec.MAX_PROPERTIES)
			throw new IllegalArgumentException("too many receipt property refs");
		Set<PresentationPropertyRef> unique = new HashSet<>();
		List<PresentationPropertyRef> sorted = new ArrayList<>(selectedRefs.size());
		for (PresentationPropertyRef ref : selectedRefs) {
			Objects.requireNonNull(ref, "selectedRef");
			if (!unique.add(ref)) throw new IllegalArgumentException("duplicate receipt property ref");
			sorted.add(ref);
		}
		Collections.sort(sorted);
		selectedRefs = List.copyOf(sorted);
		if (kind == Kind.PROPERTIES && selectedRefs.isEmpty())
			throw new IllegalArgumentException("properties receipt without refs");
		if (kind != Kind.PROPERTIES && !selectedRefs.isEmpty())
			throw new IllegalArgumentException("unexpected receipt property refs");
		if ((kind == Kind.INVENTORY) != (inventorySnapshot != null))
			throw new IllegalArgumentException("receipt inventory snapshot kind mismatch");
	}

	/** The ordinary whole-marker receipt whose content is complete on arrival. */
	public static PresentationReceiptContent whole() {
		return new PresentationReceiptContent(Kind.WHOLE, List.of());
	}

	/** A receipt that waits for every explicit property selection's authorized value. */
	public static PresentationReceiptContent properties(List<PresentationPropertyRef> refs) {
		return new PresentationReceiptContent(Kind.PROPERTIES, refs);
	}

	/** Incomplete source migration is a hard failure, never the old wait-for-Tracking path. */
	public static PresentationReceiptContent inventory() {
		throw new IllegalArgumentException("inventory receipt requires a creation snapshot");
	}

	/** Inventory content is complete on receipt; its provisional quality is explicit. */
	public static PresentationReceiptContent inventory(InventoryCreationSnapshot snapshot) {
		return new PresentationReceiptContent(Kind.INVENTORY, List.of(), Objects.requireNonNull(snapshot, "snapshot"));
	}

	/** A denied selection suppresses the message instead of falling back to the whole receipt. */
	public static PresentationReceiptContent suppressed() {
		return new PresentationReceiptContent(Kind.SUPPRESSED, List.of());
	}
}
