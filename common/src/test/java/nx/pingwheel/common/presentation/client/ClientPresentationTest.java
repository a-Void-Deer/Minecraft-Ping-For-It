package nx.pingwheel.common.presentation.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
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
}
