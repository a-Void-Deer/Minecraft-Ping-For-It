package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative initial metadata for a marker's content receipt: the selection
 * kind and the explicit property references the client selected. It deliberately
 * carries no values, annotations, or item counts — those arrive only through the
 * existing authorized stores — so a client cannot treat an upload or a preview
 * observation as content authority.
 *
 * <p>A {@code PROPERTIES} receipt requires at least one explicit reference; every
 * other kind carries none. References stay immutable, are bounded by the existing
 * property intent limit, are unique, and are held in deterministic sorted order.
 */
public record PresentationReceiptContent(Kind kind, List<PresentationPropertyRef> selectedRefs) {
	public enum Kind { WHOLE, PROPERTIES, INVENTORY, SUPPRESSED }

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
	}

	/** The ordinary whole-marker receipt whose content is complete on arrival. */
	public static PresentationReceiptContent whole() {
		return new PresentationReceiptContent(Kind.WHOLE, List.of());
	}

	/** A receipt that waits for every explicit property selection's authorized value. */
	public static PresentationReceiptContent properties(List<PresentationPropertyRef> refs) {
		return new PresentationReceiptContent(Kind.PROPERTIES, refs);
	}

	/** A receipt that waits for the dedicated inventory route's complete authoritative count. */
	public static PresentationReceiptContent inventory() {
		return new PresentationReceiptContent(Kind.INVENTORY, List.of());
	}

	/** A denied selection suppresses the message instead of falling back to the whole receipt. */
	public static PresentationReceiptContent suppressed() {
		return new PresentationReceiptContent(Kind.SUPPRESSED, List.of());
	}
}
