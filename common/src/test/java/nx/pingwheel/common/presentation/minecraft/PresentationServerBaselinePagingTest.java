package nx.pingwheel.common.presentation.minecraft;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationPropertySelection;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationServerBaselinePagingTest {
	private static final String BASIC = PresentationBasic.ID, NAME = PresentationBasic.NAME;
	private static final UUID VIEWER = PresentationServerFairRotationTest.VIEWER, OTHER = new UUID(0, 3);
	private static final class ReadFreeAdapter extends PresentationServerFairRotationTest.Adapter {
		ReadFreeAdapter(String id) { super(id, 1); }
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			fail("baseline and metadata projection must never reach a collector"); return null;
		}
	}
	private static PresentationRegistry registry() {
		var registry = new PresentationRegistry();
		registry.register(new ReadFreeAdapter(BASIC));
		registry.register(new ReadFreeAdapter(PresentationServerFairRotationTest.OPTIONAL));
		return registry;
	}
	private static PresentationServer.Session session(PresentationRegistry registry) {
		Map<String, Integer> schemas = new LinkedHashMap<>(); Map<String, List<PresentationField>> manifest = new LinkedHashMap<>();
		registry.all().forEach(adapter -> { schemas.put(adapter.adapterId(), adapter.schema()); manifest.put(adapter.adapterId(), adapter.fields()); });
		var session = new PresentationServer.Session(51, schemas, manifest); session.view = 1; session.ready = true;
		Map<String, Set<String>> mask = new LinkedHashMap<>();
		registry.sectionAdapters().forEach(adapter -> mask.put(adapter.adapterId(), PresentationServerFairRotationTest.demand(adapter)));
		session.mask = Map.of("block", Map.copyOf(mask));
		return session;
	}
	private static PresentationServer.Lease cached(ServerMarker marker) {
		var lease = new PresentationServer.Lease(marker, "Owner");
		for (String id : List.of(BASIC, PresentationServerFairRotationTest.OPTIONAL)) {
			var source = new PresentationServer.Source(id, 1); source.value = PresentationServerFairRotationTest.value(id);
			source.observation = PresentationServer.Source.Observation.SUCCESS; source.successfulSerial = 1;
			source.nextSample = 300; lease.sources.put(id, source);
		}
		return lease;
	}
	private static PresentationSection decode(PresentationS2CPacket packet) {
		var bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.sectionBytes()));
		try { return PresentationCodec.read(bytes, field -> true); } finally { bytes.release(); }
	}
	private static void deliver(PresentationServer.Session session, PresentationServer.Lease lease,
		PresentationRegistry adapters, List<PresentationS2CPacket> packets) {
		PresentationServer.sendBaseline(session, lease, adapters, PresentationServerFairRotationTest::demand, packets::add, false, Set.of());
	}

	@Test void delayedFrozenAudienceBaselineIncludesOverflowAndResetUsesOneSharedRoundRobinAllowanceWithoutReads() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var adapters = registry();
		var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		int population = 1119;
		for (int id = 0; id < population; id++) {
			ServerMarker marker = PresentationServerFairRotationTest.create(store, id, 0, 10000);
			PresentationServer.rememberLease(cache, cached(marker));
		}
		assertEquals(1024, cache.size());
		var first = session(adapters); first.ready = false;
		var second = session(adapters); second.ready = false;
		var sessions = new HashMap<UUID, PresentationServer.Session>(); sessions.put(VIEWER, first); sessions.put(OTHER, second);
		var packets = new ArrayList<PresentationS2CPacket>();
		first.ready = true; // frozen-audience readiness arrives only after all overflow creates
		PresentationServer.startBaseline(first, store);
		UUID cursor = null;
		for (int tick = 100; first.baselinePending && tick < 120; tick++) {
			int before = first.sent.size();
			cursor = PresentationServer.baselineTick(store, sessions, cursor, tick, 256, recipient -> true, (recipient, marker) ->
				deliver(first, cache.getOrDefault(marker.id().value(), new PresentationServer.Lease(marker, "Owner")), adapters, packets));
			assertTrue(first.sent.size() - before <= 256);
		}
		assertFalse(first.baselinePending); assertEquals(population, first.sent.size());
		assertTrue(second.sent.isEmpty());
		assertEquals(population, packets.stream().filter(packet -> packet.kind() == PresentationS2CPacket.Kind.CREATED).count());
		var overflow = first.sent.get(1110L).sections.get(BASIC);
		assertTrue(overflow.fields().isEmpty()); assertTrue(overflow.stale(), "a genuinely unobserved baseline is explicitly empty stale Basic");
		assertTrue(PresentationServer.inventoryKnows(first, new MarkerId(1110)));
		assertEquals(1024, cache.size(), "baseline never admits temporary leases to the heavy cache");
		assertTrue(cache.values().stream().allMatch(lease -> lease.sources.values().stream().allMatch(source -> source.nextSample == 300)));
		assertBasicFirst(packets);
		long revision = first.revision;
		first.view++; first.authorizationCut = 1;
		PresentationServer.pruneSession(first, store, adapters::get, (marker, adapter) -> PresentationServerFairRotationTest.demand(adapter));
		PresentationServer.startBaseline(first, store);
		assertFalse(PresentationServer.inventoryKnows(first, new MarkerId(1110)), "retained prior-view state cannot authorize inventory before CREATED replay");
		packets.clear();
		for (int tick = 150; first.baselinePending && tick < 170; tick++) cursor = PresentationServer.baselineTick(store,
			sessions, cursor, tick, 256, recipient -> true, (recipient, marker) ->
				deliver(first, cache.getOrDefault(marker.id().value(), new PresentationServer.Lease(marker, "Owner")), adapters, packets));
		assertFalse(first.baselinePending); assertEquals(population, packets.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).count());
		assertTrue(first.revision > revision); assertTrue(packets.stream().allMatch(p -> p.revision() > revision));
		assertTrue(PresentationServer.inventoryKnows(first, new MarkerId(1110))); assertBasicFirst(packets);
	}

	@Test void baselineSharesDiscoveryBetweenReadySessionsAndSkipsRemovedExpiredAndAudienceHoles() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var adapters = registry();
		for (int id = 0; id < 11; id++) PresentationServerFairRotationTest.create(store, id, 0, id == 4 ? 5 : 10000);
		var first = session(adapters); var second = session(adapters);
		PresentationServer.startBaseline(first, store); PresentationServer.startBaseline(second, store);
		// Only first is in the frozen audience, but both session cursors consume canonical discovery.
		var sessions = Map.of(VIEWER, first, OTHER, second); List<Long> firstIds = new ArrayList<>();
		List<UUID> turns = new ArrayList<>(); UUID cursor = null;
		cursor = PresentationServer.baselineTick(store, sessions, cursor, 2, 3, recipient -> true, (recipient, marker) -> {
			turns.add(recipient); firstIds.add(marker.id().value());
		});
		assertEquals(List.of(0L, 1L), firstIds); assertEquals(1, first.baselineAfter); assertEquals(0, second.baselineAfter);
		store.removeByServer(new MarkerId(2), MarkerRemovalReason.CANCELLED);
		var newMarker = PresentationServerFairRotationTest.create(store, 99, 3, 10000);
		for (int i = 0; i < 20 && (first.baselinePending || second.baselinePending); i++)
			cursor = PresentationServer.baselineTick(store, sessions, cursor, 5, 3, recipient -> true,
				(recipient, marker) -> { turns.add(recipient); firstIds.add(marker.id().value()); });
		assertEquals(List.of(0L, 1L, 3L, 5L, 6L, 7L, 8L, 9L, 10L), firstIds);
		assertTrue(turns.stream().allMatch(VIEWER::equals)); assertFalse(first.baselinePending); assertFalse(second.baselinePending);
		assertFalse(firstIds.contains(newMarker.id().value()), "new creates do not extend a captured baseline");
	}

	@Test void readyAudiencesTakeAlternatingBaselineTurnsUnderOneAllowance() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var adapters = registry();
		var type = nx.pingwheel.common.domain.TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		for (int id = 0; id < 9; id++) store.create(PresentationServerFairRotationTest.OWNER,
			new nx.pingwheel.common.domain.Target.BlockTarget("minecraft:overworld", id, 0, 0, "minecraft:stone"),
			type, type.defaultPingType(), new nx.pingwheel.common.marker.MarkerAnchor(id, 0, 0), 0, 10000, List.of(VIEWER, OTHER));
		var first = session(adapters); var second = session(adapters);
		PresentationServer.startBaseline(first, store); PresentationServer.startBaseline(second, store);
		List<UUID> turns = new ArrayList<>();
		UUID cursor = PresentationServer.baselineTick(store, Map.of(VIEWER, first, OTHER, second), null, 5, 5,
			recipient -> true, (recipient, marker) -> turns.add(recipient));
		assertEquals(List.of(VIEWER, OTHER, VIEWER, OTHER, VIEWER), turns);
		assertEquals(2, first.baselineAfter); assertEquals(1, second.baselineAfter);
		turns.clear();
		PresentationServer.baselineTick(store, Map.of(VIEWER, first, OTHER, second), cursor, 6, 3,
			recipient -> true, (recipient, marker) -> turns.add(recipient));
		assertEquals(List.of(OTHER, VIEWER, OTHER), turns, "next tick resumes after the last recipient instead of rewarding map order");
	}

	@Test void failedReentryRetainsOnlyEachRecipientsOwnAuthorizedPriorAndBudgetDeferDoesNotFabricateStale() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var marker = PresentationServerFairRotationTest.create(store, 0, 0, 10000);
		var adapters = registry(); var first = session(adapters); var second = session(adapters);
		var firstLease = cached(marker); firstLease.sources.get(BASIC).value = new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text("first")), false);
		var secondLease = cached(marker); secondLease.sources.get(BASIC).value = new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text("second")), false);
		deliver(first, firstLease, adapters, new ArrayList<>()); deliver(second, secondLease, adapters, new ArrayList<>());
		var reentry = new PresentationServer.Lease(marker, "Owner");
		var basic = adapters.get(BASIC); var policy = PresentationSettings.serverDefaults();
		PresentationServer.captureSources(reentry, adapters, policy, 100, new PresentationAdapter.CaptureBudget(0), 1, false,
			adapter -> adapter.adapterId().equals(BASIC) ? Set.of(NAME) : Set.of(), fields -> fail("deferred before capture"));
		assertFalse(PresentationServer.recipientProjection(first, marker, basic, reentry.sources.get(BASIC), Set.of(NAME)).stale());
		assertEquals(new PresentationValue.Text("first"), PresentationServer.recipientProjection(first, marker, basic,
			reentry.sources.get(BASIC), Set.of(NAME)).fields().get(NAME));
		PresentationServer.captureSource(reentry, basic, policy, 100, new PresentationAdapter.CaptureBudget(10), Set.of(NAME), fields -> null);
		assertTrue(reentry.sources.get(BASIC).value.fields().isEmpty(), "recipient projections are never merged into neutral cache");
		var retainedFirst = PresentationServer.recipientProjection(first, marker, basic, reentry.sources.get(BASIC), Set.of(NAME));
		var retainedSecond = PresentationServer.recipientProjection(second, marker, basic, reentry.sources.get(BASIC), Set.of(NAME));
		assertTrue(retainedFirst.stale()); assertTrue(retainedSecond.stale());
		assertEquals(new PresentationValue.Text("first"), retainedFirst.fields().get(NAME));
		assertEquals(new PresentationValue.Text("second"), retainedSecond.fields().get(NAME));
	}

	@Test void restrictiveMetadataBeforeResetAdvancesCutAndLooseningCannotResurrectOldFieldsOrAnnotations() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		var ref = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("selected"));
		var marker = PresentationServerFairRotationTest.create(store, 0, 0, 10000,
			List.of(PresentationPropertySelection.of(ref, "attention")));
		var adapters = registry(); var session = session(adapters); var basic = adapters.get(BASIC);
		Set<String> fields = Set.of(NAME, PresentationBasic.BLOCK_STATE);
		session.mask = Map.of("block", Map.of(BASIC, fields));
		var lease = new PresentationServer.Lease(marker, "Owner");
		var policy = PresentationSettings.serverDefaults();
		PresentationServer.captureSource(lease, basic, policy, 10, new PresentationAdapter.CaptureBudget(10), fields,
			demand -> new PresentationSection(BASIC, 1, Map.of(NAME, new PresentationValue.Text("name"), PresentationBasic.BLOCK_STATE,
				new PresentationValue.RecordValue(Map.of("selected", new PresentationValue.Flag(true)))), false));
		var packets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendInitial(session, lease, basic, fields, packets::add);
		assertEquals("attention", decode(packets.getLast()).annotations().get(ref));
		long serial = lease.sources.get(BASIC).successfulSerial;
		// This production metadata refresh sees revocation before ordinary RESET.
		PresentationServer.refreshMarker(marker, Map.of(marker.id().value(), lease), basic, 11,
			Map.of(VIEWER, session), recipient -> true, recipient -> Set.of(), (recipient, packet) -> packets.add(packet));
		assertTrue(decode(packets.getLast()).fields().isEmpty()); assertTrue(session.authorizationCut >= serial);
		PresentationServer.sendInitial(session, lease, basic, fields, packets::add);
		assertTrue(decode(packets.getLast()).fields().isEmpty(), "fresh permission loosening cannot restore an older neutral capture");
		PresentationServer.captureSource(lease, basic, policy, 30, new PresentationAdapter.CaptureBudget(10), fields,
			demand -> new PresentationSection(BASIC, 1, Map.of(NAME, new PresentationValue.Text("new"), PresentationBasic.BLOCK_STATE,
				new PresentationValue.RecordValue(Map.of("selected", new PresentationValue.Flag(false)))), false));
		PresentationServer.sendInitial(session, lease, basic, fields, packets::add);
		assertEquals(new PresentationValue.Text("new"), decode(packets.getLast()).fields().get(NAME));
		assertEquals("attention", decode(packets.getLast()).annotations().get(ref));
		// Child-only pruning retains the entire authorized root record, but the
		// receipt must also remain acceptable before SECTION/inventory is enabled.
		String optionalId = PresentationServerFairRotationTest.OPTIONAL;
		session.mask = Map.of("block", Map.of(BASIC, fields, optionalId, Set.of(PresentationServerFairRotationTest.FIELD)));
		var optional = new PresentationServer.Source(optionalId, 1);
		optional.value = PresentationServerFairRotationTest.value(optionalId);
		optional.observation = PresentationServer.Source.Observation.SUCCESS;
		lease.sources.put(optionalId, optional);
		var client = acceptingClient(session, adapters);
		assertEquals(PresentationReceiptContent.properties(List.of(ref)), packets.getLast().content());
		assertTrue(client.initial(wire(packets.getLast())));
		var unaffected = session(adapters); unaffected.mask = session.mask;
		var unaffectedPackets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendInitial(unaffected, lease, basic, fields, unaffectedPackets::add);
		assertEquals(PresentationReceiptContent.properties(List.of(ref)), unaffectedPackets.getLast().content());
		PresentationSection neutral = lease.sources.get(BASIC).value;
		long nextSample = lease.sources.get(BASIC).nextSample;
		long beforeDenialSerial = lease.sources.get(BASIC).successfulSerial;
		session.childBlack = Map.of("block", List.of(ref)); session.view++;
		session.authorizationCut = beforeDenialSerial;
		PresentationServer.pruneSession(session, store, adapters::get, (m, a) -> a == basic ? fields : PresentationServerFairRotationTest.demand(a));
		resetClient(client, session);
		replayAcceptedBaseline(store, session, lease, adapters, fields, client, packets, PresentationReceiptContent.suppressed());
		assertTrue(client.view(marker.id()).sections().get(BASIC).annotations().isEmpty());
		assertEquals(neutral.fields().get(PresentationBasic.BLOCK_STATE), client.view(marker.id()).field(BASIC, PresentationBasic.BLOCK_STATE));
		assertEquals(neutral.fields().get(NAME), client.view(marker.id()).field(BASIC, NAME));
		assertEquals("attention", unaffected.sent.get(marker.id().value()).sections.get(BASIC).annotations().get(ref),
			"pruning one recipient does not remove another recipient's annotation");
		session.childBlack = Map.of(); session.view++;
		PresentationServer.pruneSession(session, store, adapters::get, (m, a) -> a == basic ? fields : PresentationServerFairRotationTest.demand(a));
		resetClient(client, session);
		replayAcceptedBaseline(store, session, lease, adapters, fields, client, packets, PresentationReceiptContent.suppressed());
		assertTrue(client.view(marker.id()).sections().get(BASIC).annotations().isEmpty(),
			"loosening cannot regenerate a pruned selection annotation");
		assertEquals(new PresentationValue.Flag(false), client.view(marker.id()).property(ref),
			"the root's selected child value remains present even though its annotation was removed");
		assertEquals(neutral, lease.sources.get(BASIC).value, "recipient replay leaves the authoritative neutral data unchanged");
		assertEquals(beforeDenialSerial, lease.sources.get(BASIC).successfulSerial, "deny/reallow replay makes no successful observation");
		assertEquals(nextSample, lease.sources.get(BASIC).nextSample, "read-free reset replay never changes capture cadence");
		PresentationServer.captureSource(lease, basic, policy, 50, new PresentationAdapter.CaptureBudget(10), fields,
			demand -> new PresentationSection(BASIC, 1, Map.of(NAME, new PresentationValue.Text("fresh"), PresentationBasic.BLOCK_STATE,
				new PresentationValue.RecordValue(Map.of("selected", new PresentationValue.Flag(true)))), false));
		assertTrue(lease.sources.get(BASIC).successfulSerial > session.authorizationCut);
		PresentationServer.sendInitial(session, lease, basic, fields, packet -> {
			assertEquals(PresentationReceiptContent.properties(List.of(ref)), packet.content(), "fresh capture restores the complete property receipt");
			assertTrue(client.initial(wire(packet)));
			packets.add(packet);
		});
		assertEquals("attention", client.view(marker.id()).sections().get(BASIC).annotations().get(ref));
		assertEquals(new PresentationValue.Flag(true), client.view(marker.id()).property(ref));
		assertTrue(PresentationServer.inventoryKnows(session, marker.id()));
		assertTrue(packets.getLast().revision() > packets.getFirst().revision());
	}

	@Test void absentSelectedBasicValueStillCarriesWaitingPropertyReceiptOnFirstAndKnownReplay() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var adapters = registry(); var session = session(adapters);
		var ref = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("facing"));
		var marker = PresentationServerFairRotationTest.create(store, 0, 0, 10000,
			List.of(PresentationPropertySelection.of(ref, "attention")));
		Set<String> fields = Set.of(NAME, PresentationBasic.BLOCK_STATE);
		session.mask = Map.of("block", Map.of(BASIC, fields));
		var lease = new PresentationServer.Lease(marker, "Owner");
		var source = new PresentationServer.Source(BASIC, 1);
		source.value = new PresentationSection(BASIC, 1, Map.of(NAME, new PresentationValue.Text("name")), false);
		source.observation = PresentationServer.Source.Observation.SUCCESS; source.successfulSerial = 1; source.nextSample = 300;
		lease.sources.put(BASIC, source);
		var client = acceptingClient(session, adapters); var packets = new ArrayList<PresentationS2CPacket>();
		replayAcceptedBaseline(store, session, lease, adapters, fields, client, packets, PresentationReceiptContent.properties(List.of(ref)));
		assertNull(client.view(marker.id()).property(ref));
		session.view++; session.authorizationCut = 1;
		resetClient(client, session);
		replayAcceptedBaseline(store, session, lease, adapters, fields, client, packets, PresentationReceiptContent.properties(List.of(ref)));
		assertNull(client.view(marker.id()).property(ref));
		assertEquals(300, source.nextSample);
	}

	private static ClientPresentation acceptingClient(PresentationServer.Session session, PresentationRegistry adapters) {
		var client = new ClientPresentation(packet -> {}, adapters.sectionAdapters().stream()
			.filter(adapter -> !adapter.adapterId().equals(BASIC)).toList());
		client.tick(true);
		assertTrue(client.offer(wire(PresentationS2CPacket.offer(session.epoch, session.manifest, session.schemas))));
		resetClient(client, session);
		return client;
	}
	private static void resetClient(ClientPresentation client, PresentationServer.Session session) {
		Map<String, List<PresentationPropertyRef>> childBlack = new LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack());
		childBlack.putAll(session.childBlack);
		assertTrue(client.reset(wire(PresentationS2CPacket.reset(session.epoch, session.view, session.mask, childBlack))));
	}
	private static PresentationS2CPacket wire(PresentationS2CPacket packet) {
		var bytes = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(bytes);
			var decoded = PresentationS2CPacket.readSafe(bytes);
			assertFalse(decoded.isCorrupt()); assertFalse(bytes.isReadable());
			return decoded;
		} finally { bytes.release(); }
	}
	private static void replayAcceptedBaseline(ServerMarkerStore store, PresentationServer.Session session,
		PresentationServer.Lease lease, PresentationRegistry adapters, Set<String> basicFields, ClientPresentation client,
		List<PresentationS2CPacket> packets, PresentationReceiptContent expected) {
		assertFalse(PresentationServer.inventoryKnows(session, lease.marker.id()), "new view cannot publish inventory before its Basic baseline");
		PresentationServer.startBaseline(session, store);
		int[] initials = {0};
		int[] sections = {0};
		PresentationServer.baselineTick(store, Map.of(VIEWER, session), null, 40, 1, recipient -> true, (recipient, marker) ->
			PresentationServer.sendBaseline(session, lease, adapters,
				adapter -> adapter.adapterId().equals(BASIC) ? basicFields : PresentationServerFairRotationTest.demand(adapter), packet -> {
					if (packet.kind() == PresentationS2CPacket.Kind.CREATED) {
						assertFalse(PresentationServer.inventoryKnows(session, marker.id()), "delivered view changes only after atomic handoff");
						assertEquals(expected, packet.content(), "receipt kind and complete ref set have an independent expectation");
						assertTrue(client.initial(wire(packet)), "server-generated replay must be an acceptable atomic Basic initial");
						initials[0]++;
					} else {
						assertEquals(PresentationS2CPacket.Kind.SECTION, packet.kind());
						assertEquals(1, initials[0]);
						assertTrue(PresentationServer.inventoryKnows(session, marker.id()), "optional SECTION follows accepted Basic handoff");
						assertTrue(client.section(wire(packet)));
						sections[0]++;
					}
					packets.add(packet);
				}, false, Set.of()));
		assertEquals(1, initials[0]); assertFalse(session.baselinePending);
		assertEquals(lease.sources.containsKey(PresentationServerFairRotationTest.OPTIONAL) ? 1 : 0, sections[0]);
		assertTrue(PresentationServer.inventoryKnows(session, lease.marker.id()));
	}

	@Test void fieldPolicyResetPrunesInPlaceAndOverflowPriorCannotReturnOnLooseningBeforeFreshCapture() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var adapters = registry();
		var marker = PresentationServerFairRotationTest.create(store, 0, 0, 10000); var session = session(adapters);
		var packets = new ArrayList<PresentationS2CPacket>(); var lease = cached(marker);
		deliver(session, lease, adapters, packets);
		var sameDeliveredState = session.sent.get(marker.id().value());
		assertFalse(sameDeliveredState.sections.get(BASIC).fields().isEmpty());
		session.view++; session.authorizationCut = 1;
		session.mask = Map.of("block", Map.of());
		PresentationServer.pruneSession(session, store, adapters::get, (m, a) -> Set.of());
		assertSame(sameDeliveredState, session.sent.get(marker.id().value()), "reset retains and prunes the existing recipient owner; no duplicate prior-sent map");
		assertTrue(sameDeliveredState.sections.get(BASIC).fields().isEmpty());
		assertFalse(PresentationServer.inventoryKnows(session, marker.id()));
		deliver(session, new PresentationServer.Lease(marker, "Different owner"), adapters, packets); // evicted overflow replay
		assertEquals("Owner", packets.stream().filter(p -> p.kind() == PresentationS2CPacket.Kind.CREATED).reduce((a, b) -> b).orElseThrow().ownerName());
		session.view++; session.mask = Map.of("block", Map.of(BASIC, Set.of(NAME),
			PresentationServerFairRotationTest.OPTIONAL, Set.of(PresentationServerFairRotationTest.FIELD)));
		PresentationServer.pruneSession(session, store, adapters::get, (m, a) -> PresentationServerFairRotationTest.demand(a));
		deliver(session, lease, adapters, packets); // old neutral cache still contains the deleted fields
		assertTrue(session.sent.get(marker.id().value()).sections.get(BASIC).fields().isEmpty());
		assertTrue(session.sent.get(marker.id().value()).sections.get(PresentationServerFairRotationTest.OPTIONAL).fields().isEmpty());
		assertSame(sameDeliveredState, session.sent.get(marker.id().value()));
		assertFalse(lease.sources.get(BASIC).value.fields().isEmpty(), "the neutral cache was not pruned or repopulated from recipient data");
		long revision = session.revision;
		store.removeByServer(marker.id(), MarkerRemovalReason.EXPIRED);
		PresentationServer.startBaseline(session, store);
		PresentationServer.baselineTick(store, Map.of(VIEWER, session), null, 10000, 256, r -> true,
			(r, m) -> fail("expired marker cannot rebaseline"));
		assertFalse(session.baselinePending); assertEquals(revision, session.revision);
	}

	private static void assertBasicFirst(List<PresentationS2CPacket> packets) {
		Set<MarkerId> created = new java.util.HashSet<>();
		for (var packet : packets) {
			if (packet.kind() == PresentationS2CPacket.Kind.CREATED) created.add(packet.markerId());
			else if (packet.kind() == PresentationS2CPacket.Kind.SECTION) assertTrue(created.contains(packet.markerId()));
		}
	}
}
