package nx.pingwheel.common.presentation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure server-side projection of one marker's atomic-initial receipt content
 * descriptor. It consumes only server-authoritative state: the marker's
 * admitted explicit property selections, the dedicated inventory sidecar
 * discriminator, and per-reference authorization verdicts supplied by the
 * caller from the recipient's accepted manifest/schema, field mask and fresh
 * permission. It never samples a source, captures a value, or discloses a
 * denied reference, item count, or annotation.
 *
 * <p>Kind rules:
 * <ul>
 *   <li>No explicit non-null-ping selection and no inventory sidecar: {@code
 *       WHOLE}, the ordinary receipt whose content is complete on arrival. A
 *       default display reference is not a selection and never produces a
 *       content receipt. A nullable selection is retained and server-projected
 *       by its section, is not an explicit property Ping, and never gates or
 *       suppresses the ordinary receipt.</li>
 *   <li>Explicit non-null-ping selections: {@code PROPERTIES} carrying exactly
 *       the complete set of pinged references, in the record's deterministic
 *       order. A nullable selection beside them stays out of the receipt.</li>
 *   <li>An active inventory sidecar: {@code INVENTORY}, whose values and count
 *       arrive only through the existing dedicated inventory store.</li>
 *   <li>Any pinged reference that is denied or incompatible, any denied
 *       required formatting dependency, or a denied target name: {@code
 *       SUPPRESSED} with no references, so the whole content message is hidden
 *       instead of a partial list.</li>
 * </ul>
 */
public final class PresentationReceiptProjector {
	private PresentationReceiptProjector() {}

	/**
	 * The fields a selected property's content message truly requires to be
	 * authorized and present. The known formatter needs the maximum health to
	 * format a health value, so that pair is a mandatory dependency. An item id
	 * formats with its count only when that count is authorized and present, and
	 * a kinetic stress value omits its percentage when capacity is unavailable,
	 * so neither count nor optional stress capacity is ever a mandatory
	 * dependency.
	 */
	public static List<PresentationPropertyRef> formatDependencies(PresentationPropertyRef ref) {
		if (ref == null || !ref.isRoot() || !PresentationBasic.ID.equals(ref.adapterId())) return List.of();
		if (PresentationBasic.HEALTH.equals(ref.fieldId()))
			return List.of(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.MAX_HEALTH));
		return List.of();
	}

	/**
	 * Projects one recipient's receipt descriptor.
	 *
	 * <p>Only a selection carrying a non-null ping type is an explicit property
	 * Ping and only its reference enters a {@code PROPERTIES} receipt. A nullable
	 * selection never joins the receipt refs, never applies a formatting
	 * dependency, and never suppresses the marker: it is retained and projected
	 * by the section alone.
	 *
	 * @param selections          the marker's admitted explicit property selections;
	 *                            a null ping type is not an explicit property Ping
	 * @param inventory           whether an active inventory sidecar identifies the marker
	 * @param inventoryAuthorized whether the recipient's accepted dedicated inventory
	 *                            route and policy include this marker's target type
	 * @param nameAuthorized      whether the recipient's fresh, mask-authorized Basic
	 *                            name field is available
	 * @param fieldAuthorized     per-reference manifest/schema, mask and fresh-permission verdict
	 */
	public static PresentationReceiptContent project(List<PresentationPropertySelection> selections,
		boolean inventory, boolean inventoryAuthorized, boolean nameAuthorized,
		Predicate<PresentationPropertyRef> fieldAuthorized) {
		Objects.requireNonNull(fieldAuthorized, "fieldAuthorized");
		Set<PresentationPropertyRef> refs = new LinkedHashSet<>();
		if (selections != null) {
			for (PresentationPropertySelection selection : selections) {
				if (selection == null) return PresentationReceiptContent.suppressed();
				if (selection.pingTypeId() != null) refs.add(selection.ref());
			}
		}
		if (!refs.isEmpty()) {
			if (!nameAuthorized) return PresentationReceiptContent.suppressed();
			for (PresentationPropertyRef ref : refs) {
				if (!fieldAuthorized.test(ref)) return PresentationReceiptContent.suppressed();
				for (PresentationPropertyRef dependency : formatDependencies(ref))
					if (!fieldAuthorized.test(dependency)) return PresentationReceiptContent.suppressed();
			}
			return PresentationReceiptContent.properties(List.copyOf(refs));
		}
		if (inventory) return inventoryAuthorized && nameAuthorized
			? PresentationReceiptContent.inventory() : PresentationReceiptContent.suppressed();
		return PresentationReceiptContent.whole();
	}
}
