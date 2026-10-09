package nx.pingwheel.common.presentation.client;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationFieldCatalogTest {
	private static final List<String> CREATE_FIELD_IDS = List.of(
		"create:kinetic.speed",
		"create:kinetic.has_network",
		"create:kinetic.overstressed",
		"create:kinetic.stress",
		"create:kinetic.capacity",
		"create:inventory.summary",
		"create:fluid.summary");

	@Test
	void localManifestIsGroupedByNamespaceWithExactFieldIds() {
		PresentationFieldCatalog catalog = PresentationFieldCatalog.ofAdapters(
			List.of(basicAdapter(), createAdapter()));

		assertEquals(List.of("minecraft", "create"),
			catalog.namespaces().stream().map(PresentationFieldCatalog.Namespace::id).toList());
		assertEquals(PresentationBasic.fields().stream().map(PresentationField::id).sorted().toList(),
			catalog.namespaces().get(0).entries().stream()
				.map(entry -> entry.field().id()).toList());
		assertEquals(CREATE_FIELD_IDS.stream().sorted().toList(),
			catalog.namespaces().get(1).entries().stream()
				.map(entry -> entry.field().id()).toList());
		Set<String> basicIds = Set.of("minecraft:target.name", "minecraft:target.custom_name", "minecraft:entity.type",
			"minecraft:entity.health", "minecraft:entity.max_health", "minecraft:item.id", "minecraft:item.count",
			"minecraft:item.icon", "minecraft:block.state");
		assertEquals(basicIds, catalog.namespaces().get(0).entries().stream()
			.map(entry -> entry.field().id()).collect(java.util.stream.Collectors.toSet()));
		assertEquals(basicIds.size() + CREATE_FIELD_IDS.size(), catalog.entries().size());
		var custom = catalog.entries().stream().filter(entry -> entry.field().id().equals("minecraft:target.custom_name"))
			.findFirst().orElseThrow();
		assertEquals(PresentationBasic.ID, custom.adapterId());
		assertEquals(PresentationField.Kind.TEXT, custom.field().kind());
		assertTrue(custom.field().enabledByDefault());
		assertEquals(0, custom.field().permissionLevel());
		assertTrue(catalog.contains("create:kinetic.speed"));
		assertFalse(catalog.contains("create:unknown.field"));
	}

	@Test
	void acceptedCatalogKeepsServerMetadataAndDeduplicatesIds() {
		Map<String, List<PresentationField>> accepted = new LinkedHashMap<>();
		accepted.put("minecraft:basic", List.of(
			new PresentationField(PresentationBasic.NAME, PresentationField.Kind.TEXT, false, 0, "Server name"),
			new PresentationField(PresentationBasic.HEALTH, PresentationField.Kind.NUMBER, true, 0, "Server health"),
			new PresentationField(PresentationBasic.HEALTH, PresentationField.Kind.NUMBER, true, 0, "Duplicate")));
		accepted.put("create:presentation", List.of(
			new PresentationField("create:kinetic.speed", PresentationField.Kind.RECORD, true, 0, "Effective RPM")));

		PresentationFieldCatalog catalog = PresentationFieldCatalog.ofAccepted(accepted);

		assertEquals(List.of("minecraft", "create"),
			catalog.namespaces().stream().map(PresentationFieldCatalog.Namespace::id).toList());
		assertEquals(List.of(
			PresentationBasic.HEALTH,
			PresentationBasic.NAME,
			"create:kinetic.speed"),
			catalog.entries().stream().map(entry -> entry.field().id()).toList());
		PresentationFieldCatalog.Entry name = catalog.entries().stream()
			.filter(entry -> entry.field().id().equals(PresentationBasic.NAME))
			.findFirst().orElseThrow();
		assertFalse(name.field().enabledByDefault(),
			"server-advertised default must never be replaced by the local default");
		assertEquals("Server name", name.field().label());
		assertEquals("minecraft:basic", name.adapterId());
		assertEquals("minecraft_entity_health", catalog.entries().stream()
			.filter(entry -> entry.field().id().equals(PresentationBasic.HEALTH))
			.findFirst().orElseThrow().translationSuffix());
	}

	@Test
	void catalogMetadataIsImmutable() {
		PresentationFieldCatalog catalog = PresentationFieldCatalog.ofAdapters(
			List.of(basicAdapter(), createAdapter()));

		assertThrows(UnsupportedOperationException.class, () -> catalog.entries().clear());
		assertThrows(UnsupportedOperationException.class,
			() -> catalog.namespaces().get(0).entries().clear());
	}

	@Test
	void groupingFollowsTheFieldIdNamespaceNotTheAdapterOwner() {
		PresentationAdapter wrapper = adapter("bridge:presentation", "wrapper", List.of(
			new PresentationField("minecraft:entity.health", PresentationField.Kind.NUMBER, true, 0, "health"),
			new PresentationField("create:kinetic.speed", PresentationField.Kind.RECORD, true, 0, "speed"),
			new PresentationField("other:thing", PresentationField.Kind.TEXT, true, 0, "thing")));

		PresentationFieldCatalog catalog = PresentationFieldCatalog.ofAdapters(List.of(wrapper));

		assertEquals(List.of("minecraft", "create", "other"),
			catalog.namespaces().stream().map(PresentationFieldCatalog.Namespace::id).toList());
		for (PresentationFieldCatalog.Entry entry : catalog.entries()) {
			assertEquals("bridge:presentation", entry.adapterId(), "the owning adapter stays available for lookup");
			assertEquals(entry.field().id().substring(0, entry.field().id().indexOf(':')), entry.namespace());
		}
	}

	@Test
	void acceptedGroupingAlsoFollowsTheFieldIdNamespace() {
		Map<String, List<PresentationField>> accepted = new LinkedHashMap<>();
		accepted.put("bridge:presentation", List.of(
			new PresentationField("minecraft:entity.health", PresentationField.Kind.NUMBER, true, 0, "health"),
			new PresentationField("create:kinetic.speed", PresentationField.Kind.RECORD, true, 0, "speed")));

		PresentationFieldCatalog catalog = PresentationFieldCatalog.ofAccepted(accepted);

		assertEquals(List.of("minecraft", "create"),
			catalog.namespaces().stream().map(PresentationFieldCatalog.Namespace::id).toList());
		assertEquals("bridge:presentation", catalog.entries().get(0).adapterId());
		assertEquals("minecraft", catalog.entries().get(0).namespace());
	}

	@Test
	void namespaceHeadingsResolveThroughTheBundledKeyPattern() {
		assertEquals("settings.pingforit.presentation.namespace.minecraft",
			new PresentationFieldCatalog.Namespace("minecraft", List.of()).translationKey());
		assertEquals("settings.pingforit.presentation.namespace.create",
			new PresentationFieldCatalog.Namespace("create", List.of()).translationKey());
	}

	private static PresentationAdapter basicAdapter() {
		return adapter(PresentationBasic.ID, "minecraft", PresentationBasic.fields());
	}

	private static PresentationAdapter createAdapter() {
		List<PresentationField> fields = new ArrayList<>();
		for (String id : CREATE_FIELD_IDS) {
			fields.add(new PresentationField(id, PresentationField.Kind.RECORD, true, 0, id));
		}
		return adapter("create:presentation", "create", fields);
	}

	private static PresentationAdapter adapter(String id, String modId, List<PresentationField> fields) {
		return new PresentationAdapter() {
			@Override
			public String adapterId() {
				return id;
			}

			@Override
			public String modId() {
				return modId;
			}

			@Override
			public int schema() {
				return 1;
			}

			@Override
			public int minUpdateIntervalTicks() {
				return 1;
			}

			@Override
			public List<PresentationField> fields() {
				return fields;
			}

			@Override
			public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
				return null;
			}
		};
	}
}
