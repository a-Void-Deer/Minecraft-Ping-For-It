package nx.pingwheel.common.presentation;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure receipt projection rules: exact complete explicit refs, no default-ref
 * selection, conservative suppression, and the code-defined mandatory
 * formatting dependencies.
 */
class PresentationReceiptProjectorTest {
	private static final PresentationPropertyRef NAME =
		PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME);
	private static final PresentationPropertyRef HEALTH =
		PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.HEALTH);
	private static final PresentationPropertyRef MAX_HEALTH =
		PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.MAX_HEALTH);
	private static final PresentationPropertyRef ITEM_ID =
		PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.ITEM_ID);
	private static final PresentationPropertyRef ITEM_COUNT =
		PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.ITEM_COUNT);
	private static final PresentationPropertyRef STRESS =
		PresentationPropertyRef.root("create:kinetic", "create:kinetic.stress");
	private static final PresentationPropertyRef CAPACITY =
		PresentationPropertyRef.root("create:kinetic", "create:kinetic.capacity");

	private static final Predicate<PresentationPropertyRef> ALL = ref -> true;

	private static PresentationPropertySelection selection(PresentationPropertyRef ref) {
		return PresentationPropertySelection.of(ref, "attention");
	}

	private static PresentationPropertySelection nullable(PresentationPropertyRef ref) {
		return PresentationPropertySelection.of(ref);
	}

	@Test void defaultOnlyCreateProjectsTheWholeReceipt() {
		// The default display reference is not a selection and never a content receipt.
		assertEquals(PresentationReceiptContent.whole(),
			PresentationReceiptProjector.project(List.of(), false, false, true, ALL));
		assertEquals(PresentationReceiptContent.whole(),
			PresentationReceiptProjector.project(null, false, false, true, ALL));
	}

	@Test void nullableOnlySelectionsProjectTheWholeReceiptAndNeverSuppress() {
		// A null ping type is not an explicit property Ping: the ordinary receipt
		// stays immediate, and a denied name or unauthorized field cannot drop it.
		assertEquals(PresentationReceiptContent.whole(),
			PresentationReceiptProjector.project(List.of(nullable(NAME)), false, false, true, ALL));
		assertEquals(PresentationReceiptContent.whole(),
			PresentationReceiptProjector.project(List.of(nullable(NAME), nullable(HEALTH)), false, false, false, ALL));
		assertEquals(PresentationReceiptContent.whole(),
			PresentationReceiptProjector.project(List.of(nullable(NAME)), false, false, true, ref -> false));
	}

	@Test void nullableOnlySelectionsFallThroughToTheInventoryKindWithoutRefs() {
		var content = PresentationReceiptProjector.project(List.of(nullable(NAME)), true, true, true, ALL);
		assertEquals(PresentationReceiptContent.Kind.INVENTORY, content.kind());
		assertTrue(content.selectedRefs().isEmpty());
		assertEquals(PresentationReceiptContent.suppressed(),
			PresentationReceiptProjector.project(List.of(nullable(NAME)), true, false, true, ALL),
			"the dedicated inventory gate still suppresses an unauthorized inventory receipt");
	}

	@Test void nullableFirstOrLaterSelectionsLeaveOnlyTheExplicitPingedRefs() {
		var nullableFirst = PresentationReceiptProjector.project(
			List.of(nullable(NAME), selection(HEALTH), nullable(ITEM_COUNT)), false, false, true,
			ref -> ref.equals(HEALTH) || ref.equals(MAX_HEALTH));
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, nullableFirst.kind());
		assertEquals(List.of(HEALTH), nullableFirst.selectedRefs());

		var nullableLater = PresentationReceiptProjector.project(
			List.of(selection(ITEM_ID), nullable(NAME)), false, false, true, ALL);
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, nullableLater.kind());
		assertEquals(List.of(ITEM_ID), nullableLater.selectedRefs());
	}

	@Test void sameRefNullableAndExplicitKeepsTheExplicitRefOnce() {
		// Production rejects duplicate refs, but the pure projection normalizes
		// to the valid unique set instead of throwing or double-listing the ref.
		var nullableFirst = PresentationReceiptProjector.project(
			List.of(nullable(NAME), selection(NAME)), false, false, true, ALL);
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, nullableFirst.kind());
		assertEquals(List.of(NAME), nullableFirst.selectedRefs());
		var nullableLater = PresentationReceiptProjector.project(
			List.of(selection(NAME), nullable(NAME)), false, false, true, ALL);
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, nullableLater.kind());
		assertEquals(List.of(NAME), nullableLater.selectedRefs());
	}

	@Test void explicitSelectionsProjectExactlyTheCompleteSortedRefs() {
		var content = PresentationReceiptProjector.project(
			List.of(selection(ITEM_COUNT), selection(NAME), selection(STRESS)), false, false, true, ALL);
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, content.kind());
		assertEquals(List.of(NAME, ITEM_COUNT, STRESS).stream().sorted().toList(), content.selectedRefs(),
			"the complete explicit selection set is carried in deterministic order");
	}

	@Test void multiRefSelectionWithOneDeniedRefSuppressesWithoutDisclosingRefs() {
		var content = PresentationReceiptProjector.project(
			List.of(selection(NAME), selection(HEALTH)), false, false, true, ref -> !ref.equals(HEALTH));
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, content.kind());
		assertTrue(content.selectedRefs().isEmpty(), "a suppressed receipt never leaks a selected ref");
	}

	@Test void deniedTargetNameSuppressesEveryContentReceipt() {
		assertEquals(PresentationReceiptContent.suppressed(),
			PresentationReceiptProjector.project(List.of(selection(ITEM_ID)), false, false, false, ALL));
		assertEquals(PresentationReceiptContent.suppressed(),
			PresentationReceiptProjector.project(List.of(), true, true, false, ALL));
	}

	@Test void healthSelectionRequiresTheAuthorizedMaximumHealthDependency() {
		var withoutMax = PresentationReceiptProjector.project(
			List.of(selection(HEALTH)), false, false, true, ref -> ref.equals(HEALTH));
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, withoutMax.kind());
		var withMax = PresentationReceiptProjector.project(
			List.of(selection(HEALTH)), false, false, true, ref -> ref.equals(HEALTH) || ref.equals(MAX_HEALTH));
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, withMax.kind());
		assertEquals(List.of(HEALTH), withMax.selectedRefs());
	}

	@Test void optionalItemCountAndKineticCapacityAreNeverMandatoryDependencies() {
		// An item id formats without its count; a kinetic stress value omits its
		// percentage when capacity is unavailable.
		var item = PresentationReceiptProjector.project(
			List.of(selection(ITEM_ID)), false, false, true, ref -> ref.equals(ITEM_ID));
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, item.kind());
		var stress = PresentationReceiptProjector.project(
			List.of(selection(STRESS)), false, false, true, ref -> ref.equals(STRESS));
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, stress.kind());
		assertEquals(List.of(STRESS), stress.selectedRefs());
		assertEquals(List.of(), PresentationReceiptProjector.formatDependencies(STRESS));
		assertEquals(List.of(), PresentationReceiptProjector.formatDependencies(ITEM_ID));
		assertEquals(List.of(MAX_HEALTH), PresentationReceiptProjector.formatDependencies(HEALTH));
	}

	@Test void inventoryReceiptNeedsTheDedicatedRouteAndAnAuthorizedName() {
		var content = PresentationReceiptProjector.project(List.of(), true, true, true, ALL);
		assertEquals(PresentationReceiptContent.Kind.INVENTORY, content.kind());
		assertTrue(content.selectedRefs().isEmpty());
		assertEquals(PresentationReceiptContent.suppressed(),
			PresentationReceiptProjector.project(List.of(), true, false, true, ALL),
			"a recipient without the dedicated inventory route cannot complete the content");
	}

	@Test void suppressedReceiptCarriesNoSelectionCountOrType() {
		var suppressed = PresentationReceiptProjector.project(
			List.of(selection(ITEM_COUNT)), false, false, true, ref -> false);
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, suppressed.kind());
		assertEquals(List.of(), suppressed.selectedRefs());
	}
}
