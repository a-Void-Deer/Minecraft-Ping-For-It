package nx.pingwheel.common.presentation.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationPropertySelection;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresentationServerRefreshTest {
	private static final UUID OWNER = new UUID(0, 1);
	private static final UUID VIEWER = new UUID(0, 2);
	private static final UUID OTHER = new UUID(0, 3);
	private static final String BASIC = PresentationBasic.ID;
	private static final String NAME = PresentationBasic.NAME;

	private static ServerMarker marker(long id, String locator, MarkerAnchor anchor, List<UUID> viewers) {
		var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		return new ServerMarker(new MarkerId(id), OWNER,
			Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "tracking-id", "minecraft:chest", locator, true),
			type, type.defaultPingType(), anchor, 2, 500, viewers);
	}

	private static PresentationSection cached() {
		return new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text("{\"text\":\"cached\"}")), false);
	}

	private static PresentationServer.Session readySession() {
		var session = new PresentationServer.Session(37, Map.of(BASIC, 1), Map.of());
		session.ready = true;
		session.view = 1;
		return session;
	}

	private static PresentationServer.SentMarker sentMarker(String ownerName, PresentationSection basic) {
		var sent = new PresentationServer.SentMarker(ownerName);
		sent.sections.put(BASIC, basic);
		return sent;
	}

	private static PresentationSection decodedBasic(PresentationS2CPacket packet) {
		var bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.sectionBytes()));
		try {
			return PresentationCodec.read(bytes, field -> true);
		} finally {
			bytes.release();
		}
	}

	@Test void changedLocatorAndAnchorReachOnlyKnownOnlineAudienceWithoutSamplingOrNewLifetime() {
		ServerMarker first = marker(10, "locator-before", new MarkerAnchor(1, 2, 3), List.of(VIEWER, OTHER));
		ServerMarker refreshed = marker(10, "locator-after", new MarkerAnchor(4, 5, 6), first.recipients());
		assertEquals(first.target(), refreshed.target(), "stable identity deliberately ignores locator");
		var lease = new PresentationServer.Lease(first, "Cached owner");
		var source = new PresentationServer.Source(BASIC, 1);
		source.value = cached();
		source.nextSample = 250;
		lease.sources.put(BASIC, source);
		var known = readySession();
		known.sent.put(first.id().value(), sentMarker("Cached owner", cached()));
		var notSent = readySession();
		var sessions = Map.of(VIEWER, known, OTHER, notSent);
		var sent = new ArrayList<PresentationS2CPacket>();
		var basicAdapter = new BasicAdapter();
		PresentationServer.updateLease(lease, refreshed, 100, sessions,
			viewer -> true, viewer -> PresentationServer.sendInitial(sessions.get(viewer), lease,
				basicAdapter, Set.of(NAME), sent::add));
		assertEquals(1, sent.size(), "an unbaselined viewer is not backfilled by a refresh");
		var packet = sent.get(0);
		assertEquals(PresentationS2CPacket.Kind.CREATED, packet.kind());
		assertEquals("locator-after", ((Target.ExternalBlockTarget) packet.snapshot().target()).providerLocator());
		assertEquals(refreshed.anchor(), packet.snapshot().anchor());
		assertEquals(first.id(), packet.snapshot().id());
		assertEquals(first.arrivalTick(), packet.snapshot().arrivalTick());
		assertEquals(first.expiresAtTick(), packet.snapshot().expiresAtTick());
		assertEquals("Cached owner", packet.ownerName(), "the owner need not be online or re-resolved");
		assertEquals(cached(), known.sent.get(first.id().value()).sections.get(BASIC));
		assertTrue(packet.sectionBytes().length > 0);
		assertEquals(250, source.nextSample, "metadata delivery must not force the next source observation");
		assertEquals(0, notSent.sent.size());
		PresentationServer.updateLease(lease, refreshed, 500, sessions, viewer -> true,
			viewer -> fail("expired leases must not be delivered"));
		assertEquals(1, sent.size());
		PresentationServer.updateLease(lease, refreshed, 101, sessions, viewer -> false,
			viewer -> fail("offline viewers must not be delivered"));
	}

	@Test void cachedRefreshProjectsAgainstCurrentAllowedFieldsRatherThanLeakingPriorSessionFields() {
		var lease = new PresentationServer.Lease(marker(11, "before", new MarkerAnchor(1, 2, 3), List.of(VIEWER)), "Owner");
		var source = new PresentationServer.Source(BASIC, 1);
		source.value = cached();
		lease.sources.put(BASIC, source);
		var session = readySession();
		session.sent.put(lease.marker.id().value(), sentMarker("Owner", cached()));
		var packets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.updateLease(lease, marker(11, "after", new MarkerAnchor(1, 2, 3), List.of(VIEWER)),
			100, Map.of(VIEWER, session), viewer -> true,
			viewer -> PresentationServer.sendInitial(session, lease, new BasicAdapter(), Set.of(), packets::add));
		assertEquals(1, packets.size());
		assertTrue(session.sent.get(lease.marker.id().value()).sections.get(BASIC).fields().isEmpty(),
			"a tight server mask must remove cached name on same-id delivery");
		assertEquals(cached(), source.value, "server cache remains neutral; projection is recipient scoped");
	}

	@Test void markerBeyondSamplingCapRefreshesKnownRecipientWithoutLeaseOrWorldRead() {
		var leases = new java.util.LinkedHashMap<Long, PresentationServer.Lease>();
		ServerMarker admitted = marker(1, "lease", new MarkerAnchor(1, 2, 3), List.of(VIEWER));
		for (long id = 1; ; id++) {
			ServerMarker candidate = marker(id, "lease", new MarkerAnchor(1, 2, 3), List.of(VIEWER));
			if (!PresentationServer.rememberLease(leases, new PresentationServer.Lease(candidate, "Owner"))) break;
		}
		assertNotNull(leases.get(admitted.id().value()));
		long beyond = leases.size() + 1L;
		var previous = marker(beyond, "locator-before", new MarkerAnchor(1, 2, 3), List.of(VIEWER, OTHER));
		var refreshed = marker(beyond, "locator-after", new MarkerAnchor(4, 5, 6), previous.recipients());
		assertFalse(PresentationServer.rememberLease(leases, new PresentationServer.Lease(previous, "Owner")));
		assertFalse(leases.containsKey(beyond));
		var ready = readySession();
		var unknown = readySession();
		var basicAdapter = new BasicAdapter();
		var deliveries = new ArrayList<PresentationS2CPacket>();
		// This is the production initial-sending method used even if the lease was
		// not admitted to the persistent sampling cache.
		var ephemeral = new PresentationServer.Lease(previous, "Original owner");
		var source = new PresentationServer.Source(BASIC, 1);
		source.value = cached();
		ephemeral.sources.put(BASIC, source);
		PresentationServer.sendInitial(ready, ephemeral, basicAdapter, Set.of(NAME), deliveries::add);
		assertEquals(1, deliveries.size());
		assertFalse(leases.containsKey(beyond));
		var sessions = Map.of(VIEWER, ready, OTHER, unknown);
		PresentationServer.refreshMarker(refreshed, leases, basicAdapter, 100, sessions,
			recipient -> recipient.equals(VIEWER), recipient -> Set.of(NAME), (recipient, packet) -> deliveries.add(packet));
		assertEquals(2, deliveries.size());
		var packet = deliveries.get(1);
		assertEquals("locator-after", ((Target.ExternalBlockTarget) packet.snapshot().target()).providerLocator());
		assertEquals(refreshed.anchor(), packet.snapshot().anchor());
		assertEquals(previous.id(), packet.snapshot().id());
		assertEquals(previous.arrivalTick(), packet.snapshot().arrivalTick());
		assertEquals(previous.expiresAtTick(), packet.snapshot().expiresAtTick());
		assertEquals("Original owner", packet.ownerName(), "owner profile is not needed for an offline refresh");
		assertEquals(cached(), decodedBasic(packet));
		assertTrue(packet.revision() > deliveries.get(0).revision());
		assertEquals(cached(), source.value, "metadata refresh reuses the already-delivered Basic section");
		assertFalse(leases.containsKey(beyond), "metadata delivery cannot allocate sampling capacity");
		assertTrue(unknown.sent.isEmpty(), "an unbaselined member of the audience must not be backfilled");
		PresentationServer.refreshMarker(refreshed, leases, basicAdapter, 500, sessions,
			recipient -> true, recipient -> fail("expired marker must not be projected"),
			(recipient, response) -> fail("expired marker must not be sent"));
		assertEquals(2, deliveries.size());
	}

	@Test void cappedMarkerRefreshRevokesCachedValuesAndAnnotationsPerRecipient() {
		var previous = marker(40, "before", new MarkerAnchor(1, 2, 3), List.of(VIEWER, OTHER));
		var annotated = new ServerMarker(previous.id(), previous.owner(), previous.target(), previous.targetType(),
			previous.pingType(), previous.anchor(), previous.arrivalTick(), previous.expiresAtTick(),
			previous.recipients(), List.of(PresentationPropertySelection.of(
				PresentationPropertyRef.root(BASIC, NAME), "danger")));
		var next = new ServerMarker(annotated.id(), annotated.owner(),
			marker(40, "after", new MarkerAnchor(4, 5, 6), annotated.recipients()).target(),
			annotated.targetType(), annotated.pingType(), new MarkerAnchor(4, 5, 6),
			annotated.arrivalTick(), annotated.expiresAtTick(), annotated.recipients(), annotated.properties());
		var first = readySession();
		var second = readySession();
		first.sent.put(annotated.id().value(), sentMarker("Owner", cached()));
		second.sent.put(annotated.id().value(), sentMarker("Owner", cached()));
		var delivered = new java.util.LinkedHashMap<UUID, PresentationS2CPacket>();
		PresentationServer.refreshMarker(next, Map.of(), new BasicAdapter(), 100,
			Map.of(VIEWER, first, OTHER, second), recipient -> true,
			recipient -> recipient.equals(VIEWER) ? Set.of() : Set.of(NAME), delivered::put);
		assertEquals(Set.of(VIEWER, OTHER), delivered.keySet());
		assertTrue(decodedBasic(delivered.get(VIEWER)).fields().isEmpty());
		assertTrue(decodedBasic(delivered.get(VIEWER)).annotations().isEmpty());
		assertEquals(cached().fields(), decodedBasic(delivered.get(OTHER)).fields());
		assertEquals("danger", decodedBasic(delivered.get(OTHER)).annotations().get(
			PresentationPropertyRef.root(BASIC, NAME)));
		assertTrue(first.sent.get(next.id().value()).sections.get(BASIC).fields().isEmpty());
		assertEquals(cached().fields(), second.sent.get(next.id().value()).sections.get(BASIC).fields());
	}

	@Test void negotiatedBaselinesAcrossDueLeasesReplayCachedValuesWithoutSourceReads() {
		var leases = new ArrayList<PresentationServer.Lease>();
		for (int id = 1; id <= 40; id++) {
			var lease = new PresentationServer.Lease(marker(id, "locator", new MarkerAnchor(1, 2, 3),
				id == 40 ? List.of(OTHER) : List.of(VIEWER)), "Owner");
			var basic = new PresentationServer.Source(BASIC, 1);
			basic.value = cached();
			basic.nextSample = 0; // Every source is due; HELLO must still replay only the cache.
			lease.sources.put(BASIC, basic);
			leases.add(lease);
		}
		var session = readySession();
		var packets = new ArrayList<PresentationS2CPacket>();
		var basicAdapter = new BasicAdapter();
		var sourceReads = new AtomicInteger();
		var adapters = new PresentationRegistry();
		adapters.register(new BasicAdapter());
		var settings = PresentationSettings.serverDefaults();
		// Use the same production sampler to establish the observable reader count
		// before replaying a large, entirely due, cached baseline.
		PresentationServer.captureSources(leases.get(0), adapters, settings, 1,
			new PresentationAdapter.CaptureBudget(10), 1, false,
			adapter -> Set.of(NAME), fields -> { sourceReads.incrementAndGet(); return cached(); });
		leases.get(0).sources.get(BASIC).nextSample = 0;
		int readsBeforeBaseline = sourceReads.get();
		for (int repeat = 0; repeat < 2; repeat++) {
			PresentationServer.cachedBaseline(leases, 100, VIEWER, lease -> {
				PresentationServer.sendInitial(session, lease, basicAdapter, Set.of(NAME), packets::add);
			});
		}
		assertEquals(78, packets.size());
		assertEquals(readsBeforeBaseline, sourceReads.get(), "a cached baseline does not re-enter source capture");
		assertTrue(packets.stream().allMatch(packet -> packet.kind() == PresentationS2CPacket.Kind.CREATED));
		assertEquals(0, leases.get(0).sources.get(BASIC).nextSample);
	}

	@Test void refreshedMetadataDoesNotBypassEffectiveSamplingInterval() {
		var lease = new PresentationServer.Lease(marker(1, "before", new MarkerAnchor(1, 2, 3), List.of(VIEWER)), "Owner");
		var settings = PresentationSettings.serverDefaults();
		settings.setMinUpdateIntervalTicks(30);
		var adapters = new PresentationRegistry();
		adapters.register(new BasicAdapter());
		var reads = new AtomicInteger();
		var demand = Set.of(NAME);
		var work = new PresentationAdapter.CaptureBudget(100);
		assertEquals(1, PresentationServer.captureSources(lease, adapters, settings, 100, work, 10, false,
			adapter -> demand, fields -> { reads.incrementAndGet(); return cached(); }));
		long due = lease.sources.get(BASIC).nextSample;
		assertTrue(due >= 130);
		var session = readySession();
		session.sent.put(lease.marker.id().value(), sentMarker("Owner", cached()));
		var basicAdapter = new BasicAdapter();
		for (long now = 101; now < due; now++) {
			ServerMarker refreshed = marker(1, "moved-" + now, new MarkerAnchor(now, 2, 3), List.of(VIEWER));
			PresentationServer.updateLease(lease, refreshed, now, Map.of(VIEWER, session), viewer -> true,
				viewer -> PresentationServer.sendInitial(session, lease, basicAdapter, Set.of(NAME), ignored -> {}));
			assertEquals(0, PresentationServer.captureSources(lease, adapters, settings, now, work, 10, false,
				adapter -> demand, fields -> { reads.incrementAndGet(); return cached(); }));
		}
		assertEquals(1, reads.get());
		assertEquals(due, lease.sources.get(BASIC).nextSample);
		assertEquals(1, PresentationServer.captureSources(lease, adapters, settings, due, work, 10, false,
			adapter -> demand, fields -> { reads.incrementAndGet(); return cached(); }));
		assertEquals(2, reads.get());
	}

	@Test void zeroBudgetDefersBasicAndOptionalObservationsWithoutMutatingCachedStaleness() {
		var lease = new PresentationServer.Lease(marker(1, "initial", new MarkerAnchor(1, 2, 3), List.of(VIEWER)), "Owner");
		var adapters = new PresentationRegistry();
		adapters.register(new BasicAdapter());
		var optional = new CountingAdapter();
		adapters.register(optional);
		var settings = PresentationSettings.serverDefaults();
		settings.setScanBudget(0);
		var basic = new PresentationServer.Source(BASIC, 1);
		basic.value = cached();
		lease.sources.put(BASIC, basic);
		var work = new PresentationAdapter.CaptureBudget(100);
		var reads = new AtomicInteger();
		assertEquals(0, PresentationServer.captureSources(lease, adapters, settings, 100, work, 10, false,
			adapter -> Set.of(adapter.adapterId().equals(BASIC) ? NAME : CountingAdapter.FIELD),
			fields -> { reads.incrementAndGet(); return cached(); }));
		assertEquals(0, reads.get());
		assertEquals(0, optional.reads);
		assertEquals(cached(), basic.value);
		assertEquals(0, basic.nextSample);
		assertEquals(100, work.remaining());
		settings.setScanBudget(1);
		assertEquals(2, PresentationServer.captureSources(lease, adapters, settings, 100, work, 10, false,
			adapter -> Set.of(adapter.adapterId().equals(BASIC) ? NAME : CountingAdapter.FIELD),
			fields -> { reads.incrementAndGet(); return cached(); }));
		assertEquals(1, reads.get());
		assertEquals(1, optional.reads);
		assertEquals(0, PresentationServer.captureSources(lease, adapters, settings, 101, work, 10, false,
			adapter -> adapter.adapterId().equals(BASIC) ? Set.of(NAME) : Set.of(),
			fields -> { reads.incrementAndGet(); return cached(); }));
		assertEquals(1, reads.get());
		assertTrue(lease.sources.get(CountingAdapter.ID).value.fields().isEmpty(),
			"empty demand still clears a previously sampled optional source");
	}

	@Test void exhaustedSharedWorkDefersOptionalWithoutFakeFailureAndPositivePerCallAllowanceRemainsSeparate() {
		var lease = new PresentationServer.Lease(marker(1, "initial", new MarkerAnchor(1, 2, 3), List.of(VIEWER)), "Owner");
		var adapters = new PresentationRegistry();
		var optional = new CountingAdapter();
		adapters.register(optional);
		var settings = PresentationSettings.serverDefaults();
		settings.setScanBudget(4);
		var source = new PresentationServer.Source(CountingAdapter.ID, 1);
		source.value = new PresentationSection(CountingAdapter.ID, 1,
			Map.of(CountingAdapter.FIELD, new PresentationValue.Flag(true)), false);
		lease.sources.put(CountingAdapter.ID, source);
		assertEquals(0, PresentationServer.captureSources(lease, adapters, settings, 100,
			new PresentationAdapter.CaptureBudget(1), 10, false,
			adapter -> Set.of(CountingAdapter.FIELD), ignored -> fail("Basic not registered")));
		assertEquals(0, optional.reads);
		assertEquals(0, source.nextSample);
		assertFalse(source.value.stale());
		var work = new PresentationAdapter.CaptureBudget(20);
		assertEquals(1, PresentationServer.captureSources(lease, adapters, settings, 100, work, 10, false,
			adapter -> Set.of(CountingAdapter.FIELD), ignored -> fail("Basic not registered")));
		assertEquals(4, optional.lastAllowance);
		assertEquals(1, optional.reads);
		assertEquals(19, work.remaining(), "unused per-call allowance does not drain shared tick work");
	}

	@Test void positivePerCaptureAllowanceCannotResetSharedTickQuotaAcrossLeases() {
		var adapters = new PresentationRegistry();
		var optional = new CountingAdapter();
		adapters.register(optional);
		var settings = PresentationSettings.serverDefaults();
		settings.setScanBudget(1);
		var work = new PresentationAdapter.CaptureBudget(8);
		int remainingCaptures = 2;
		for (int id = 1; id <= 12; id++) {
			var lease = new PresentationServer.Lease(marker(id, "loc", new MarkerAnchor(1, 2, 3), List.of(VIEWER)), "Owner");
			remainingCaptures -= PresentationServer.captureSources(lease, adapters, settings, 100,
				work, remainingCaptures, false, adapter -> Set.of(CountingAdapter.FIELD),
				fields -> fail("Basic not registered"));
		}
		assertEquals(2, optional.reads, "a new lease must not reset the common capture quota");
		assertEquals(0, remainingCaptures);
		assertEquals(6, work.remaining());
	}

	private static final class BasicAdapter implements PresentationAdapter {
		@Override public String adapterId() { return BASIC; }
		@Override public String modId() { return "minecraft"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 5; }
		@Override public List<PresentationField> fields() { return PresentationBasic.fields(); }
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			throw new AssertionError("server Basic uses its world reader, not the adapter collector");
		}
	}

	private static final class CountingAdapter implements PresentationAdapter {
		static final String ID = "test:observer", FIELD = "test:observation";
		int reads;
		int lastAllowance;
		@Override public String adapterId() { return ID; }
		@Override public String modId() { return "test"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 5; }
		@Override public List<PresentationField> fields() {
			return List.of(new PresentationField(FIELD, PresentationField.Kind.FLAG, true, 0, "Observation"));
		}
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			reads++;
			lastAllowance = budget.remaining();
			return new PresentationSection(ID, 1, Map.of(FIELD, new PresentationValue.Flag(true)), false);
		}
	}
}
