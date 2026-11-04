package nx.pingwheel.common.presentation.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.marker.ServerMarker;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryInitialRetryPagingTest {
	@Test void newDeferredCreatesCannotMoveTheCurrentRetryPassBoundaryForever() throws ReflectiveOperationException {
		UUID a = new UUID(900, 1), b = new UUID(900, 2), owner = new UUID(900, 3);
		var store = new ServerMarkerStore(new MarkerIdSource());
		var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		Supplier<ServerMarker> create = () -> store.create(owner,
			new Target.BlockTarget("minecraft:overworld", 1, 64, 3, "minecraft:chest"), type, type.defaultPingType(),
			new MarkerAnchor(1, 64, 3), 0, 100000, List.of(a, b)).marker();
		var first = create.get(); var second = create.get();
		var sessions = Map.of(a, session(), b, session());
		var ordinary = new java.util.ArrayDeque<MarkerId>();
		for (int index = 0; index < 512; index++) ordinary.add(create.get().id());
		for (var session : sessions.values()) PresentationServer.startBaseline(session, store);
		int[] firstAttempts = {0}, inspections = {0}, recoveredTick = {-1}, now = {1}; boolean[] recover = {false};
		BiConsumer<UUID, ServerMarker> deliver = (recipient, marker) -> {
			inspections[0]++;
			var session = sessions.get(recipient);
			if (marker.id().equals(first.id()) && recipient.equals(a)) {
				firstAttempts[0]++;
				if (!recover[0]) { defer(session, marker); return; }
				recoveredTick[0] = now[0];
			}
			if (marker.id().equals(second.id()) && recipient.equals(b)) { defer(session, marker); return; }
			var sent = new PresentationServer.SentMarker("owner"); sent.deliveredView = 1;
			session.sent.put(marker.id().value(), sent);
		};
		@Nullable UUID cursor = PresentationServer.baselineTick(store, sessions, null, 1, 256, id -> true, deliver);
		recover[0] = true;
		@Nullable MarkerId previous = null;
		for (int tick = 2; tick <= 25; tick++) {
			if (previous != null) store.removeByServer(previous, MarkerRemovalReason.CANCELLED);
			for (int index = 0; index < 128; index++) {
				var plain = create.get(); ordinary.add(plain.id());
				for (var session : sessions.values()) {
					var sent = new PresentationServer.SentMarker("owner"); sent.deliveredView = 1;
					session.sent.put(plain.id().value(), sent);
				}
			}
			while (ordinary.size() > 512) {
				var old = ordinary.remove(); store.removeByServer(old, MarkerRemovalReason.CANCELLED);
				for (var session : sessions.values()) session.sent.remove(old.value());
			}
			var newest = create.get(); previous = newest.id();
			long passEnd = sessions.get(a).baselineHighWater;
			for (var session : sessions.values()) defer(session, newest);
			assertEquals(passEnd, sessions.get(a).baselineHighWater, "new arrivals do not move the running pass boundary");
			@Unmodifiable List<long @Unmodifiable []> before = sessions.values().stream()
				.map(session -> new long @Unmodifiable [] {session.baselineAfter, session.baselineHighWater}).toList();
			inspections[0] = 0;
			now[0] = tick;
			cursor = PresentationServer.baselineTick(store, sessions, cursor, tick, 256, id -> true, deliver);
			assertTrue(inspections[0] <= 256, "all recipients/retry work share the existing discovery cap");
			long discovered = 0; int sessionIndex = 0;
			for (var session : sessions.values()) {
				long[] boundary = before.get(sessionIndex++);
				boolean finished = !session.baselinePending || session.baselineHighWater != boundary[1] || session.baselineAfter < boundary[0];
				long through = finished ? boundary[1] : session.baselineAfter;
				discovered += store.allMarkers().stream().filter(marker -> marker.id().value() > boundary[0] && marker.id().value() <= through).count();
			}
			assertTrue(discovered <= 256, "including known skips, canonical inspections share one cap across both sessions");
			assertEquals(515, store.size());
		}
		assertTrue(sessions.get(a).sent.containsKey(first.id().value()),
			"a recovered early marker must get another turn despite continually arriving deferred creates");
		assertEquals(2, firstAttempts[0], "only the failed and successful turns are delivered");
		assertTrue(recoveredTick[0] <= 6, "finite first population ends before the old recovered record's next turn");
	}
	@Test void mixedRetryPassesKeepOneSharedDiscoveryCapAndDoNotRepeatKnownRecords() {
		UUID a = new UUID(803,1), b = new UUID(803,2), owner = new UUID(803,3);
		var store = new ServerMarkerStore(new MarkerIdSource());
		var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		for (int i=0;i<620;i++) store.create(owner,new Target.BlockTarget("minecraft:overworld",i,1,1,"minecraft:stone"),
			type,type.defaultPingType(),new MarkerAnchor(i,1,1),0,i==4?2:10000,i==5?List.of(owner):List.of(a,b));
		var first = session(); var second = session(); var sessions = Map.of(a,first,b,second);
		PresentationServer.startBaseline(first,store); PresentationServer.startBaseline(second,store);
		var attempts = new HashMap<String,Integer>(); var delivered = new ArrayList<String>();
		@Nullable UUID cursor = null;
		for (int tick=0;tick<30 && (first.baselinePending||second.baselinePending);tick++) {
			int before = delivered.size(), turn = tick; int[] inspections={0};
			cursor=PresentationServer.baselineTick(store,sessions,cursor,3,256,id->true,(recipient,marker)->{
				inspections[0]++;
				var session=sessions.get(recipient); String key=recipient+"/"+marker.id().value();
				attempts.merge(key,1,Integer::sum);
				if (turn<8 && (recipient.equals(a)&&marker.id().value()==1 || recipient.equals(b)&&marker.id().value()==2)) {
					session.blockedInitial=session.blockedInitial<0?marker.id().value():Math.min(session.blockedInitial,marker.id().value());
					return;
				}
				var sent=new PresentationServer.SentMarker("owner"); sent.deliveredView=session.view;
				session.sent.put(marker.id().value(),sent); delivered.add(key);
			});
			assertTrue(delivered.size()-before<=256);
			assertTrue(inspections[0]<=256, "one shared discovery allowance across all recipients and retry passes");
		}
		assertFalse(first.baselinePending); assertFalse(second.baselinePending);
		assertEquals(618,first.sent.size()); assertEquals(618,second.sent.size());
		assertEquals(delivered.size(),new java.util.HashSet<>(delivered).size());
		assertTrue(attempts.get(a+"/1")>1); assertEquals(1,attempts.get(a+"/3"));
	}
	@Test void removedExpiredAudienceAndViewReplacedWaitsClearWithoutExtendingLifetime() {
		UUID recipient=new UUID(804,1), owner=new UUID(804,2);
		var store=new ServerMarkerStore(new MarkerIdSource()); var type=TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		for(int i=0;i<3;i++) store.create(owner,new Target.BlockTarget("minecraft:overworld",i,1,1,"minecraft:stone"),type,type.defaultPingType(),
			new MarkerAnchor(i,1,1),0,i==1?2:10000,i==2?List.of(owner):List.of(recipient));
		var session=session(); PresentationServer.startBaseline(session,store); session.blockedInitial=0;
		store.removeByServer(new MarkerId(0),MarkerRemovalReason.CANCELLED);
		PresentationServer.baselineTick(store,Map.of(recipient,session),null,3,256,id->true,(id,marker)->fail("all remaining records are expired or outside audience"));
		assertFalse(session.baselinePending); assertEquals(-1,session.blockedInitial);
		session.blockedInitial=1; session.view++; PresentationServer.startBaseline(session,store);
		assertEquals(-1,session.blockedInitial); assertEquals(-1,session.baselineAfter);
	}
	@Test void behindCursorDeferredRefreshWaitsForFinitePassThenUsesCanonicalRecord() {
		UUID recipient = new UUID(901, 1), owner = new UUID(901, 2);
		var store = new ServerMarkerStore(new MarkerIdSource());
		var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		for (int index = 0; index < 4; index++) store.create(owner,
			new Target.BlockTarget("minecraft:overworld", index, 1, 1, "minecraft:stone"), type, type.defaultPingType(),
			new MarkerAnchor(index, 1, 1), 0, 10000, List.of(recipient));
		var session = session(); PresentationServer.startBaseline(session, store);
		var sent = new PresentationServer.SentMarker("owner"); sent.deliveredView = session.view;
		session.sent.put(0L, sent);
		PresentationServer.baselineTick(store, Map.of(recipient, session), null, 1, 1, id -> true, (id, marker) -> fail("already known"));
		sent.metadataRevision++;
		defer(session, store.find(new MarkerId(0)).orElseThrow());
		assertEquals(0, session.baselineAfter, "a behind-cursor refresh never rewinds a running pass");
		var delivered = new ArrayList<Long>();
		PresentationServer.baselineTick(store, Map.of(recipient, session), null, 2, 256, id -> true, (id, marker) -> {
			delivered.add(marker.id().value());
			var known = new PresentationServer.SentMarker("owner"); known.deliveredView = session.view;
			session.sent.put(marker.id().value(), known);
		});
		assertEquals(List.of(1L, 2L, 3L), delivered);
		PresentationServer.baselineTick(store, Map.of(recipient, session), null, 3, 256, id -> true, (id, marker) -> {
			delivered.add(marker.id().value()); sent.deliveredMetadataRevision = sent.metadataRevision;
		});
		assertEquals(List.of(1L, 2L, 3L, 0L), delivered); assertFalse(session.baselinePending);
	}
	@Test void directSuccessOfEarliestFailureDoesNotDiscardLaterFailedWork() throws ReflectiveOperationException {
		UUID recipient = new UUID(902, 1), owner = new UUID(902, 2);
		var store = new ServerMarkerStore(new MarkerIdSource()); var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		for (int index = 0; index < 4; index++) store.create(owner,
			new Target.BlockTarget("minecraft:overworld", index, 1, 1, "minecraft:stone"), type, type.defaultPingType(),
			new MarkerAnchor(index, 1, 1), 0, 10000, List.of(recipient));
		var session = session(); PresentationServer.startBaseline(session, store);
		PresentationServer.baselineTick(store, Map.of(recipient, session), null, 1, 2, id -> true,
			(id, marker) -> defer(session, marker));
		var sent = new PresentationServer.SentMarker("owner"); sent.deliveredView = session.view; session.sent.put(0L, sent);
		var complete = PresentationServer.class.getDeclaredMethod("completeInitial", PresentationServer.Session.class,
			ServerMarker.class, PresentationServer.SentMarker.class);
		complete.setAccessible(true); complete.invoke(null, session, store.find(new MarkerId(0)).orElseThrow(), sent);
		var delivered = new ArrayList<Long>();
		for (int tick = 2; tick <= 4; tick++) PresentationServer.baselineTick(store, Map.of(recipient, session), null, tick, 256,
			id -> true, (id, marker) -> {
				delivered.add(marker.id().value()); var known = new PresentationServer.SentMarker("owner"); known.deliveredView = session.view;
				session.sent.put(marker.id().value(), known);
			});
		assertTrue(delivered.contains(1L)); assertFalse(delivered.contains(0L)); assertFalse(session.baselinePending);
	}
	private static PresentationServer.Session session() {
		var session=new PresentationServer.Session(1,Map.of(),Map.of()); session.ready=true; session.view=1; return session;
	}
	private static void defer(PresentationServer.Session session, ServerMarker marker) {
		try {
			var method = PresentationServer.class.getDeclaredMethod("deferInitial", PresentationServer.Session.class, ServerMarker.class);
			method.setAccessible(true); method.invoke(null, session, marker);
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
