package nx.pingwheel.common.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingTypeCatalogTest {

	@Test
	void builtInCatalogContainsAllSevenPingTypesInDeclarationOrder() {
		PingTypeCatalog catalog = PingTypeCatalog.builtIn();

		List<PingType> entries = catalog.entries();

		assertEquals(7, entries.size());
		assertEquals(
			List.of("attention", "danger", "go_to", "loot", "destroy", "take", "request"),
			entries.stream().map(PingType::id).toList());
	}

	@Test
	void builtInPingTypesHaveConfirmedColors() {
		PingTypeCatalog catalog = PingTypeCatalog.builtIn();

		assertEquals(0xFFC247, catalog.findById("attention").orElseThrow().outlineColor());
		assertEquals(0xFFAA00, catalog.findById("attention").orElseThrow().textColor());

		assertEquals(0xFF4D4D, catalog.findById("danger").orElseThrow().outlineColor());
		assertEquals(0xFF5555, catalog.findById("danger").orElseThrow().textColor());

		assertEquals(0x4DB8FF, catalog.findById("go_to").orElseThrow().outlineColor());
		assertEquals(0x55FFFF, catalog.findById("go_to").orElseThrow().textColor());

		assertEquals(0x52D273, catalog.findById("loot").orElseThrow().outlineColor());
		assertEquals(0x55FF55, catalog.findById("loot").orElseThrow().textColor());

		assertEquals(0xE66BDD, catalog.findById("destroy").orElseThrow().outlineColor());
		assertEquals(0xF0A0EA, catalog.findById("destroy").orElseThrow().textColor());

		assertEquals(0x52D273, catalog.findById("take").orElseThrow().outlineColor());
		assertEquals(0x55FF55, catalog.findById("take").orElseThrow().textColor());

		assertEquals(0x8C8CFF, catalog.findById("request").orElseThrow().outlineColor());
		assertEquals(0xB8B8FF, catalog.findById("request").orElseThrow().textColor());
	}

	@Test
	void builtInPingTypesUsePingForItLocalizationKeys() {
		PingTypeCatalog catalog = PingTypeCatalog.builtIn();

		assertEquals("pingforit.ping_type.attention.phrase", catalog.findById("attention").orElseThrow().phraseKey());
		assertEquals("pingforit.ping_type.attention", catalog.findById("attention").orElseThrow().displayKey());
		assertEquals("pingforit.ping_type.danger.phrase", catalog.findById("danger").orElseThrow().phraseKey());
		assertEquals("pingforit.ping_type.go_to.phrase", catalog.findById("go_to").orElseThrow().phraseKey());
		assertEquals("pingforit.ping_type.loot.phrase", catalog.findById("loot").orElseThrow().phraseKey());

		assertEquals("pingforit.ping_type.destroy.phrase", catalog.findById("destroy").orElseThrow().phraseKey());
		assertEquals("pingforit.ping_type.destroy", catalog.findById("destroy").orElseThrow().displayKey());
		assertEquals("pingforit.ping_type.take.phrase", catalog.findById("take").orElseThrow().phraseKey());
		assertEquals("pingforit.ping_type.take", catalog.findById("take").orElseThrow().displayKey());
		assertEquals("pingforit.ping_type.request.phrase", catalog.findById("request").orElseThrow().phraseKey());
		assertEquals("pingforit.ping_type.request", catalog.findById("request").orElseThrow().displayKey());
	}

	@Test
	void builtInCatalogIsImmutable() {
		PingTypeCatalog catalog = PingTypeCatalog.builtIn();

		assertThrows(UnsupportedOperationException.class,
			() -> catalog.entries().add(catalog.entries().get(0)));
	}

	@Test
	void rejectsDuplicateIds() {
		PingTypeCatalog builtIn = PingTypeCatalog.builtIn();
		PingType attention = builtIn.findById("attention").orElseThrow();

		PingType duplicate = new PingType("attention", "x.phrase", "x", 0x000000, 0x000000, Optional.empty());

		assertThrows(IllegalArgumentException.class,
			() -> new PingTypeCatalog(List.of(attention, duplicate)));
	}

	@Test
	void rejectsInvalidColors() {
		assertThrows(IllegalArgumentException.class,
			() -> new PingType("attention", "x.phrase", "x", 0xFF000000, 0x000000, Optional.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> new PingType("attention", "x.phrase", "x", 0x000000, 0x1000000, Optional.empty()));
	}

	@Test
	void rejectsBlankIdentifiers() {
		assertThrows(IllegalArgumentException.class,
			() -> new PingType(" ", "x.phrase", "x", 0x000000, 0x000000, Optional.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> new PingType("attention", "", "x", 0x000000, 0x000000, Optional.empty()));
	}

	@Test
	void iconOptionalIsExplicitAndValidated() {
		// empty optional means "reuse and tint the default ping icon"
		PingType withEmpty = new PingType("x", "x.phrase", "x", 0x000000, 0x000000, Optional.empty());
		assertTrue(withEmpty.iconId().isEmpty());

		// present non-blank value names an explicit icon resource
		PingType withIcon = new PingType("x", "x.phrase", "x", 0x000000, 0x000000, Optional.of("pingforit:icon/attention"));
		assertEquals("pingforit:icon/attention", withIcon.iconId().orElseThrow());

		// a null Optional is rejected
		assertThrows(NullPointerException.class,
			() -> new PingType("x", "x.phrase", "x", 0x000000, 0x000000, null));

		// a present but blank value is rejected
		assertThrows(IllegalArgumentException.class,
			() -> new PingType("x", "x.phrase", "x", 0x000000, 0x000000, Optional.of(" ")));
	}
}
