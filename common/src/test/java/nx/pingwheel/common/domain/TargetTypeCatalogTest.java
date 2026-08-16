package nx.pingwheel.common.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetTypeCatalogTest {

	private static final TargetTypeCatalog BUILT_IN = TargetTypeCatalog.builtIn();

	@Test
	void builtInCatalogHasConfirmedDeclarationOrder() {
		assertEquals(
			List.of("dropped_item", "entity", "entity_block", "block", "location"),
			BUILT_IN.entries().stream().map(TargetType::id).toList());
	}

	@Test
	void builtInCatalogHasConfirmedPriorities() {
		assertEquals(100, target("dropped_item").priority());
		assertEquals(200, target("entity").priority());
		assertEquals(250, target("entity_block").priority());
		assertEquals(300, target("block").priority());
		assertEquals(Integer.MAX_VALUE, target("location").priority());
	}

	@Test
	void builtInCatalogHasConfirmedKindsAndDefaultPingTypes() {
		assertEquals(TargetKind.ENTITY, target("dropped_item").kind());
		assertEquals("loot", target("dropped_item").defaultPingType().id());

		assertEquals(TargetKind.ENTITY, target("entity").kind());
		assertEquals("attention", target("entity").defaultPingType().id());

		assertEquals(TargetKind.BLOCK, target("entity_block").kind());
		assertEquals("attention", target("entity_block").defaultPingType().id());

		assertEquals(TargetKind.BLOCK, target("block").kind());
		assertEquals("attention", target("block").defaultPingType().id());

		assertEquals(TargetKind.LOCATION, target("location").kind());
		assertEquals("go_to", target("location").defaultPingType().id());
	}

	@Test
	void builtInCatalogHasConfirmedPingTypeLists() {
		assertEquals(
			List.of("loot", "attention", "danger"),
			target("dropped_item").pingTypes().stream().map(PingType::id).toList());
		assertEquals(
			List.of("attention", "danger", "go_to"),
			target("entity").pingTypes().stream().map(PingType::id).toList());
		assertEquals(
			List.of("attention", "destroy", "take", "request"),
			target("entity_block").pingTypes().stream().map(PingType::id).toList());
		assertEquals(
			List.of("attention", "go_to", "danger"),
			target("block").pingTypes().stream().map(PingType::id).toList());
		assertEquals(
			List.of("go_to", "attention", "danger"),
			target("location").pingTypes().stream().map(PingType::id).toList());
	}

	@Test
	void locationIsResolvedLast() {
		List<TargetType> order = BUILT_IN.resolutionOrder();

		assertEquals("location", order.get(order.size() - 1).id());
	}

	@Test
	void lowerNumericPriorityWins() {
		TargetType high = new TargetType("high", 200, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));
		TargetType low = new TargetType("low", 100, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(high, low));

		assertEquals(List.of("low", "high"),
			catalog.resolutionOrder().stream().map(TargetType::id).toList());
	}

	@Test
	void equalPriorityResolvesByDeclarationOrder() {
		TargetType first = new TargetType("first", 100, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));
		TargetType second = new TargetType("second", 100, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));
		TargetType third = new TargetType("third", 100, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(second, first, third));

		assertEquals(List.of("second", "first", "third"),
			catalog.resolutionOrder().stream().map(TargetType::id).toList());
	}

	@Test
	void resolutionOrderIsDeterministicAcrossRepeatedCalls() {
		List<TargetType> first = BUILT_IN.resolutionOrder();
		List<TargetType> second = BUILT_IN.resolutionOrder();
		List<TargetType> third = BUILT_IN.resolutionOrder();

		assertEquals(first, second);
		assertEquals(first, third);
	}

	@Test
	void interleavedPrioritiesAreSortedDeterministically() {
		TargetType a = new TargetType("a", 300, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));
		TargetType b = new TargetType("b", 100, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));
		TargetType c = new TargetType("c", 200, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(a, b, c));

		assertEquals(List.of("b", "c", "a"),
			catalog.resolutionOrder().stream().map(TargetType::id).toList());
	}

	@Test
	void locationDeclaredFirstStillResolvesLast() {
		PingType goTo = ping("go_to");

		TargetType location = new TargetType("location", Integer.MAX_VALUE, TargetKind.LOCATION, List.of(goTo), goTo);
		TargetType entity = new TargetType("entity", 200, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));

		TargetTypeCatalog catalog = new TargetTypeCatalog(List.of(location, entity));

		assertEquals(List.of("entity", "location"),
			catalog.resolutionOrder().stream().map(TargetType::id).toList());
	}

	@Test
	void rejectsDuplicateIds() {
		TargetType a = new TargetType("dup", 100, TargetKind.ENTITY, List.of(ping("attention")), ping("attention"));
		TargetType b = new TargetType("dup", 200, TargetKind.BLOCK, List.of(ping("attention")), ping("attention"));

		assertThrows(IllegalArgumentException.class,
			() -> new TargetTypeCatalog(List.of(a, b)));
	}

	@Test
	void rejectsDefaultPingTypeNotInList() {
		PingTypeCatalog pingTypes = PingTypeCatalog.builtIn();

		assertThrows(IllegalArgumentException.class,
			() -> new TargetType(
				"bad",
				100,
				TargetKind.ENTITY,
				List.of(pingTypes.findById("attention").orElseThrow()),
				pingTypes.findById("danger").orElseThrow()));
	}

	private static TargetType target(String id) {
		return BUILT_IN.findById(id).orElseThrow();
	}

	private static PingType ping(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}
}
