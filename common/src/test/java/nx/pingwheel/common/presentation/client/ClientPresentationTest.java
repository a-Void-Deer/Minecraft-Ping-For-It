package nx.pingwheel.common.presentation.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientPresentationTest {
	private static final MarkerId MARKER = new MarkerId(42);
	private static final String BASIC = ClientPresentation.BASIC;
	private static final String NAME = ClientPresentation.NAME;
	private static final String HEALTH = "minecraft:entity.health";
	private static final String MAX_HEALTH = "minecraft:entity.max_health";
	private static final String CREATE_SPEED = "create:kinetic.speed";

	private final List<IPacket> sent = new ArrayList<>();
	private final PresentationSettings receive = PresentationSettings.clientDefaults();
	private final PresentationSettings display = PresentationSettings.clientDefaults();
	private final ClientPresentation client = new ClientPresentation(sent::add, () -> receive, () -> display);

	private void ready() {
		client.tick(true);
		assertEquals(PresentationC2SPacket.Kind.HELLO, ((PresentationC2SPacket) sent.get(0)).kind());
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
			new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"))),
			Map.of(BASIC, 1))));
		assertFalse(client.ready());
		var subscription = (PresentationC2SPacket) sent.get(1);
		assertEquals(Set.of(NAME, HEALTH), subscription.fields());
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, 1)));
		assertTrue(client.ready());
	}

	private void readyWithHealth() {
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
			new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"),
			new PresentationField(MAX_HEALTH, PresentationField.Kind.NUMBER, true, 0, "Maximum health"))),
			Map.of(BASIC, 1))));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, 1)));
		assertTrue(client.ready());
	}

	private PresentationSection healthSection(double health, double maximum) {
		return new PresentationSection(BASIC, 1, Map.of(
			NAME, new PresentationValue.Text("{\"text\":\"Server\"}"),
			HEALTH, new PresentationValue.NumberValue(health),
			MAX_HEALTH, new PresentationValue.NumberValue(maximum)), false);
	}

	@Test
	void defaultReceivePolicyAcceptsAdvertisedFieldsButKeepsLocalDenySelectorsEffective() {
		receive.setBlack(List.of(NAME));
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
			new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"))),
			Map.of(BASIC, 1))));
		assertEquals(Set.of(HEALTH), ((PresentationC2SPacket) sent.get(1)).fields());
	}

	private PresentationSection basic() {
		return new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text("{\"text\":\"Server\"}"),
				HEALTH, new PresentationValue.NumberValue(5)), false);
	}

	@Test
	void negotiationRequiresMatchingEpochGenerationAndReset() {
		ready();
		assertFalse(client.reset(PresentationS2CPacket.reset(81, 0, 2)));
		assertFalse(client.reset(PresentationS2CPacket.reset(82, 1, 2)));
		client.store().initial(81, 1, 1, MARKER.value(), basic());
		assertFalse(client.section(PresentationS2CPacket.section(81, 0, 1, 1, MARKER, basic())));
		assertFalse(client.section(PresentationS2CPacket.section(81, 1, 0, 1, MARKER, basic())));
		assertTrue(client.section(PresentationS2CPacket.section(81, 1, 1, 1, MARKER,
			new PresentationSection(BASIC, 1, Map.of(HEALTH, new PresentationValue.NumberValue(3)), false))));
		assertNull(client.view(MARKER).field(BASIC, NAME)); // whole-section replacement, not a delta
		assertEquals(new PresentationValue.NumberValue(3), client.view(MARKER).field(BASIC, HEALTH));
		client.close();
		assertFalse(client.ready());
		assertTrue(client.store().sections(MARKER.value()).isEmpty());
	}

	@Test
	void receiveTighteningPrunesFrozenValuesAndRequiresAnotherReset() {
		ready();
		client.store().initial(81, 1, 1, MARKER.value(), basic());
		client.removed(MARKER, 2, true);
		assertTrue(client.store().isFrozen(MARKER.value()));
		receive.setWhite(List.of(HEALTH));
		receive.setBlack(List.of("minecraft:*"));
		client.tick(true);
		assertFalse(client.ready());
		assertNull(client.view(MARKER).field(BASIC, NAME));
		assertEquals(new PresentationValue.NumberValue(5), client.view(MARKER).field(BASIC, HEALTH));
		assertEquals(Set.of(HEALTH), ((PresentationC2SPacket) sent.get(2)).fields());
		assertFalse(client.reset(PresentationS2CPacket.reset(81, 1, 2)));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 2, 2)));
		receive.setWhite(List.of("*:*"));
		receive.setBlack(List.of());
		client.tick(true);
		assertNull(client.view(MARKER).field(BASIC, NAME)); // expiry cannot recover deleted data
	}

	@Test
	void displayPolicyAndProviderSwitchNeverSendNetworkOrReadDeniedValues() {
		ready();
		client.store().initial(81, 1, 1, MARKER.value(), basic());
		int before = sent.size();
		var observed = new AtomicReference<PresentationView>();
		client.registerProvider("replacement", view -> {
			observed.set(view);
			return List.of("custom", "x".repeat(90));
		});
		display.setWhite(List.of(HEALTH));
		display.setBlack(List.of("minecraft:*"));
		assertTrue(client.selectProvider("replacement"));
		assertEquals(2, client.labels(client.view(MARKER)).size());
		assertEquals(64, client.labels(client.view(MARKER)).get(1).length());
		assertNull(observed.get().field(BASIC, NAME));
		assertEquals(new PresentationValue.NumberValue(5), observed.get().field(BASIC, HEALTH));
		assertEquals(basic().fields().get(NAME), client.store().sections(MARKER.value()).get(BASIC).section().fields().get(NAME));
		assertEquals(before, sent.size());
	}

	@Test
	void incompatibleFieldKindIsNeverSubscribedOrRetained() {
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(15, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.NUMBER, true, 0, "Wrong kind"))),
			Map.of(BASIC, 1))));
		assertTrue(((PresentationC2SPacket) sent.get(1)).fields().isEmpty());
		assertTrue(client.reset(PresentationS2CPacket.reset(15, 1, 0)));
		client.store().initial(15, 1, 0, MARKER.value(), basic());
		assertTrue(client.view(MARKER).sections().isEmpty());
	}

	@Test
	void defaultHealthFieldsAreSubscribedAndProjectedAsHpWithoutExplicitRules() {
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
			new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"),
			new PresentationField(MAX_HEALTH, PresentationField.Kind.NUMBER, true, 0, "Maximum health"))),
			Map.of(BASIC, 1))));
		assertEquals(Set.of(NAME, HEALTH, MAX_HEALTH), ((PresentationC2SPacket) sent.get(1)).fields());
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, 1)));
		client.store().initial(81, 1, 1, MARKER.value(), healthSection(5, 20));

		var labels = client.labels(client.view(MARKER));
		assertTrue(labels.contains("HP 5.0 / 20.0"), () -> "expected HP projection, got " + labels);
	}

	@Test
	void displayDenialOfOneHealthFieldRemovesTheHpLine() {
		readyWithHealth();
		client.store().initial(81, 1, 1, MARKER.value(), healthSection(5, 20));
		assertTrue(client.labels(client.view(MARKER)).contains("HP 5.0 / 20.0"));

		display.setBlack(List.of(MAX_HEALTH));
		assertTrue(client.labels(client.view(MARKER)).isEmpty());
	}

	@Test
	void missingMaximumHealthNeverProducesAPartialHpLine() {
		readyWithHealth();
		client.store().initial(81, 1, 1, MARKER.value(), new PresentationSection(BASIC, 1, Map.of(
			HEALTH, new PresentationValue.NumberValue(5)), false));

		assertTrue(client.labels(client.view(MARKER)).isEmpty());
	}

	@Test
	void defaultProviderProjectsCreateRpmFromTheFilteredView() {
		PresentationView view = new PresentationView(Map.of("create:presentation",
			new PresentationSection("create:presentation", 1, Map.of(CREATE_SPEED,
				new PresentationValue.RecordValue(Map.of(
					"effective_rpm", new PresentationValue.NumberValue(42.5)))),
				false)));

		assertEquals(List.of("42.5 RPM"), ClientPresentation.defaultLabels(view));
	}

	@Test
	void acceptedServerCatalogKeepsAdvertisedMetadataAndClearsOnClose() {
		client.tick(true);
		long before = client.catalogRevision();
		assertFalse(client.catalogOffered());
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, false, 0, "Server name"),
			new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "Server health"),
			new PresentationField("minecraft:entity.type", PresentationField.Kind.NUMBER, true, 0, "Wrong kind"))),
			Map.of(BASIC, 1))));

		PresentationFieldCatalog catalog = client.acceptedCatalog();
		PresentationFieldCatalog.Entry name = catalog.entries().stream()
			.filter(entry -> entry.field().id().equals(NAME)).findFirst().orElseThrow();
		assertFalse(name.field().enabledByDefault(),
			"the local default must not override the server-advertised default");
		assertEquals("Server name", name.field().label());
		assertTrue(catalog.contains(HEALTH));
		assertFalse(catalog.contains("minecraft:entity.type"), "an incompatible kind is excluded");
		assertTrue(client.catalogRevision() > before);
		assertTrue(client.catalogOffered());

		client.close();
		assertTrue(client.acceptedCatalog().isEmpty());
		assertFalse(client.catalogOffered());
		assertTrue(client.catalogRevision() > before + 1L);
	}

	@Test
	void acceptedOfferWithNoCompatibleFieldStillMarksTheCatalogKnown() {
		client.tick(true);
		long before = client.catalogRevision();
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.NUMBER, true, 0, "Wrong kind"))),
			Map.of(BASIC, 1))));

		assertTrue(client.catalogOffered());
		assertTrue(client.acceptedCatalog().isEmpty());
		assertTrue(client.catalogRevision() > before);
	}

	@Test
	void offlineLocalManifestIsAvailableWithoutSendingAnything() {
		assertTrue(PresentationSettings.clientDefaults().policy().allows(CREATE_SPEED, true));

		PresentationFieldCatalog catalog = PresentationFieldCatalog.ofAdapters(ClientPresentation.localManifest());

		assertTrue(catalog.contains(HEALTH));
		assertTrue(catalog.contains(NAME));
		assertTrue(sent.isEmpty(), "building the offline preview must not send packets");
	}

	@Test
	void malformedOfferWithDuplicateFieldIdsPublishesNothingAndALaterOfferStillWorks() {
		client.tick(true);
		long revisionBefore = client.catalogRevision();
		PresentationField name = new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name");
		PresentationField health = new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health");

		assertFalse(client.offer(PresentationS2CPacket.offer(81,
			Map.of(BASIC, List.of(name, health, name)), Map.of(BASIC, 1))));
		assertFalse(client.ready());
		assertFalse(client.catalogOffered());
		assertTrue(client.acceptedCatalog().isEmpty());
		assertEquals(revisionBefore, client.catalogRevision());
		assertEquals(1, sent.size(), "a rejected offer must not emit a subscription");

		assertTrue(client.offer(PresentationS2CPacket.offer(82,
			Map.of(BASIC, List.of(name, health)), Map.of(BASIC, 1))));
		assertTrue(client.catalogOffered());
		assertEquals(Set.of(NAME, HEALTH), ((PresentationC2SPacket) sent.get(1)).fields());
		assertTrue(client.reset(PresentationS2CPacket.reset(82, 1, 1)));
		assertTrue(client.ready());
	}

	@Test
	void offerWithManifestSchemaMismatchOrInvalidSchemaIsRejectedWithoutStateChange() {
		client.tick(true);
		PresentationField name = new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name");

		assertFalse(client.offer(PresentationS2CPacket.offer(81,
			Map.of(BASIC, List.of(name)), Map.of())));
		assertFalse(client.offer(PresentationS2CPacket.offer(81,
			Map.of(BASIC, List.of(name)), Map.of(BASIC, 0))));
		assertFalse(client.offer(PresentationS2CPacket.offer(81,
			Map.of(BASIC, List.of(name)), Map.of(BASIC, 256))));
		assertFalse(client.catalogOffered());
		assertTrue(client.acceptedCatalog().isEmpty());
		assertEquals(1, sent.size(), "rejected offers must not emit a subscription");

		assertTrue(client.offer(PresentationS2CPacket.offer(81,
			Map.of(BASIC, List.of(name)), Map.of(BASIC, 1))));
		assertTrue(client.catalogOffered());
	}

	@Test
	void offerExceedingAdapterOrFieldBoundsIsRejectedWithoutStateChange() {
		client.tick(true);
		PresentationField name = new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name");

		Map<String, List<PresentationField>> tooManyAdapters = new LinkedHashMap<>();
		Map<String, Integer> adapterSchemas = new LinkedHashMap<>();
		for (int i = 0; i <= PresentationC2SPacket.MAX_ADAPTERS; i++) {
			String id = "test:adapter" + i;
			tooManyAdapters.put(id, List.of(name));
			adapterSchemas.put(id, 1);
		}
		assertFalse(client.offer(PresentationS2CPacket.offer(81, tooManyAdapters, adapterSchemas)));

		Map<String, List<PresentationField>> tooManyFields = new LinkedHashMap<>();
		Map<String, Integer> fieldSchemas = new LinkedHashMap<>();
		for (int adapter = 0; adapter < 6; adapter++) {
			String id = "test:fields" + adapter;
			List<PresentationField> fields = new ArrayList<>();
			for (int i = 0; i < PresentationCodec.MAX_FIELDS; i++) {
				fields.add(new PresentationField("test:field" + adapter + "_" + i,
					PresentationField.Kind.TEXT, true, 0, "f"));
			}
			tooManyFields.put(id, fields);
			fieldSchemas.put(id, 1);
		}
		assertFalse(client.offer(PresentationS2CPacket.offer(81, tooManyFields, fieldSchemas)));

		assertFalse(client.catalogOffered());
		assertTrue(client.acceptedCatalog().isEmpty());
		assertEquals(1, sent.size(), "rejected offers must not emit a subscription");
	}

	@Test
	void unknownAdvertisedAdapterIsIgnoredAndNeverPublished() {
		client.tick(true);
		PresentationField name = new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name");
		PresentationField unknown = new PresentationField("other:secret", PresentationField.Kind.TEXT, true, 0, "Secret");

		assertTrue(client.offer(PresentationS2CPacket.offer(81,
			Map.of(BASIC, List.of(name), "other:adapter", List.of(unknown)),
			Map.of(BASIC, 1, "other:adapter", 1))));

		assertTrue(client.catalogOffered());
		assertEquals(Set.of(NAME), ((PresentationC2SPacket) sent.get(1)).fields());
		assertEquals(List.of(NAME),
			client.acceptedCatalog().entries().stream().map(entry -> entry.field().id()).toList());
	}

	@Test
	void structurallyValidOfferWithoutCompatibleBasicIsIgnoredAndNotPublished() {
		client.tick(true);
		PresentationField unknown = new PresentationField("other:secret", PresentationField.Kind.TEXT, true, 0, "Secret");

		assertFalse(client.offer(PresentationS2CPacket.offer(81,
			Map.of("other:adapter", List.of(unknown)), Map.of("other:adapter", 1))));
		assertFalse(client.catalogOffered());
		assertTrue(client.acceptedCatalog().isEmpty());
		assertEquals(1, sent.size());

		assertTrue(client.offer(PresentationS2CPacket.offer(82, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"))), Map.of(BASIC, 1))));
		assertTrue(client.catalogOffered());
	}
}
