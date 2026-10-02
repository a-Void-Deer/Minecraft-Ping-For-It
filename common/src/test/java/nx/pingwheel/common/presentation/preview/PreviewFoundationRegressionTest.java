package nx.pingwheel.common.presentation.preview;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewFoundationRegressionTest {
	private static final String ADAPTER = "test:source", A = "test:a", B = "test:b", DENIED = "test:denied";
	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 4, 5, 6, "minecraft:stone");
	private static final class Context implements PreviewFieldAccess.ReadContext {
		Object level = new Object(); long tick;
		public Object levelIdentity() { return level; }
		public String dimensionId() { return TARGET.dimensionId(); }
		public long tick() { return tick; }
	}
	private static PresentationPreviewAccess access(long view, String... allowed) {
		var fields = new java.util.LinkedHashMap<String, PresentationField>();
		for (String field : allowed) fields.put(field, new PresentationField(field, PresentationField.Kind.NUMBER, true, 0, field));
		return new PresentationPreviewAccess(31, view, "block", Map.of(ADAPTER, new PresentationPreviewAccess.Adapter(1, fields)));
	}
	private static PresentationPropertyRef ref(String field) { return PresentationPropertyRef.root(ADAPTER, field); }
	private static PreviewFieldAccess.Observed local(double value) {
		return new PreviewFieldAccess.Observed(new PreviewObservation(new PresentationValue.NumberValue(value), PreviewObservation.Origin.CLIENT_SYNCED, 0, false));
	}
	// Traverse the publicly accessible value graph, not private implementation fields.
	private static boolean exposes(Object object, Object sentinel, Set<Object> seen) throws ReflectiveOperationException {
		if (object == sentinel) return true;
		if (object == null || !seen.add(object)) return false;
		if (object instanceof Map<?, ?> map) {
			for (var entry : map.entrySet()) if (exposes(entry.getKey(), sentinel, seen) || exposes(entry.getValue(), sentinel, seen)) return true;
		} else if (object instanceof Iterable<?> values) {
			for (Object value : values) if (exposes(value, sentinel, seen)) return true;
		} else if (object instanceof Optional<?> value) {
			return exposes(value.orElse(null), sentinel, seen);
		} else if (object.getClass().isRecord()) {
			for (RecordComponent component : object.getClass().getRecordComponents())
				if (exposes(component.getAccessor().invoke(object), sentinel, seen)) return true;
		}
		return false;
	}
	@Test void publicProjectionAndPropertyEntriesCannotReachWorldOrCallerToken() throws ReflectiveOperationException {
		Context context = new Context(); Object world = context.level;
		// Even a caller using the world itself as token cannot export it through the opaque ID.
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return ADAPTER; }
			public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) { return Map.of(A, local(0)); }
		};
		var preview = new ClientPresentationPreview(type -> Optional.of(access(1, A)), () -> context,
			List.of(reader), (target, type) -> Optional.empty(), request -> fail("all-local evidence sends nothing"));
		preview.begin(new ClientPresentationPreview.Binding(world, TARGET, "block", world));
		var projection = preview.projection().orElseThrow();
		assertFalse(exposes(projection, world, Collections.newSetFromMap(new IdentityHashMap<>())), "WORLD_HANDLE_ESCAPE");
		assertFalse(exposes(PreviewPropertyEntries.of(projection), world, Collections.newSetFromMap(new IdentityHashMap<>())));
		assertEquals(TARGET, projection.target()); assertEquals("block", projection.targetTypeId());
		assertEquals(projection.interactionId(), preview.projection().orElseThrow().interactionId());
		assertEquals(new PresentationValue.NumberValue(0), preview.intent(world, ref(A), null).orElseThrow().observedValue());
		assertTrue(preview.intent(new Object(), ref(A), null).isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> projection.fields().clear());
		preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", world));
		assertNotEquals(projection.interactionId(), preview.projection().orElseThrow().interactionId());
		preview.abort(); assertTrue(preview.projection().isEmpty());
		assertEquals(new PresentationValue.NumberValue(0), projection.property(ref(A)).orElseThrow().value(), "old detached snapshot stays inert");
	}
	@Test void expiredUnadmittedServerQueuePreservesDeferredWithoutSourceReadOrRetry() {
		Context context = new Context(); List<PresentationPreviewC2SPacket> requests = new ArrayList<>();
		List<PresentationPreviewS2CPacket> responses = new ArrayList<>(); int[] captures = {0};
		var preview = new ClientPresentationPreview(type -> Optional.of(access(1, A)), () -> context,
			List.of(), (target, type) -> Optional.empty(), requests::add);
		preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
		var server = new PresentationPreviewServer(new PresentationPreviewServer.Host() {
			public Optional<PresentationPreviewAccess> access(UUID player, String type) { return Optional.of(PreviewFoundationRegressionTest.access(1, A)); }
			public int intervalTicks(UUID player, String adapter) { return 1; }
			public int scanBudget() { return 3; }
			public PresentationPreviewServer.Capture capture(UUID player, PresentationPreviewC2SPacket request, PresentationPreviewAccess authorized, PresentationAdapter.CaptureBudget budget) {
				captures[0]++; fail("unadmitted queue expiry must not inspect the source"); return null;
			}
			public boolean send(UUID player, PresentationPreviewS2CPacket packet) { responses.add(packet); return true; }
		});
		assertTrue(server.handle(UUID.randomUUID(), requests.getFirst(), 0));
		assertEquals(0, server.drain(0, new PresentationAdapter.CaptureBudget(0), 0));
		assertEquals(0, server.drain(PresentationPreviewLimits.REQUEST_TICKS, new PresentationAdapter.CaptureBudget(0), 0));
		assertEquals(0, captures[0]); assertEquals(0, server.queuedCount());
		assertEquals(PresentationPreviewS2CPacket.Status.DEFERRED, responses.getFirst().status());
		// Client and server tick origins are independent; this response is still locally pending.
		assertTrue(preview.accept(responses.getFirst()));
		assertEquals(PreviewFieldAccess.Missing.DEFERRED, preview.projection().orElseThrow().fields().get(ref(A)));
		assertTrue(preview.projection().orElseThrow().property(ref(A)).isEmpty());
		context.tick = PresentationPreviewLimits.REQUEST_TICKS * 3; preview.tick(); preview.tick();
		assertEquals(1, requests.size(), "defer is terminal for this one-shot, not a polling trigger");
	}
	@Test void disappearingLocalRootStillGetsItsOwnOneShotAfterSiblingResult() {
		Context context = new Context(); List<PresentationPreviewC2SPacket> requests = new ArrayList<>(); boolean[] present = {true};
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return ADAPTER; }
			public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext world) {
				assertEquals(Set.of(A, B), demand); // DENIED is never read or requested.
				return present[0] ? Map.of(A, local(0)) : Map.of();
			}
		};
		var preview = new ClientPresentationPreview(type -> Optional.of(access(1, A, B)), () -> context,
			List.of(reader), (target, type) -> Optional.empty(), requests::add);
		preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
		assertEquals(Set.of(B), requests.getFirst().fields());
		assertTrue(preview.accept(PresentationPreviewS2CPacket.result(requests.getFirst(), new PresentationSection(ADAPTER, 1, Map.of(B, new PresentationValue.NumberValue(7)), false))));
		present[0] = false; preview.tick(); assertEquals(1, requests.size(), "cadence still applies");
		context.tick = PresentationPreviewLimits.MIN_REQUEST_TICKS; preview.tick();
		assertEquals(List.of(Set.of(B), Set.of(A)), requests.stream().map(PresentationPreviewC2SPacket::fields).toList());
		assertTrue(preview.accept(PresentationPreviewS2CPacket.control(requests.getLast(), 1, PresentationPreviewS2CPacket.Status.UNAVAILABLE)));
		context.tick += PresentationPreviewLimits.REQUEST_TICKS * 3; preview.tick(); preview.tick();
		assertEquals(2, requests.size(), "neither the succeeded B nor failed A may be polled again");
		assertFalse(requests.stream().anyMatch(request -> request.fields().contains(DENIED)));
	}
	@Test void nonStaleRetainedMarkerIsHistoricalAndCannotSuppressFallback() {
		Context context = new Context(); List<PresentationPreviewC2SPacket> requests = new ArrayList<>();
		var section = new PresentationSection(ADAPTER, 1, Map.of(A, new PresentationValue.NumberValue(0)), false,
			Map.of(ref(A), "danger"));
		var retained = new ClientPresentationPreview.RetainedSnapshot(TARGET, "block", 0, Map.of(ADAPTER, section));
		var preview = new ClientPresentationPreview(type -> Optional.of(access(1, A)), () -> context,
			List.of(), (target, type) -> Optional.of(retained), requests::add);
		Object token = new Object(); preview.begin(new ClientPresentationPreview.Binding(token, TARGET, "block", context.level));
		var observed = preview.projection().orElseThrow().property(ref(A)).orElseThrow();
		assertEquals(PreviewObservation.Origin.RETAINED_MARKER, observed.origin());
		assertTrue(observed.stale(), "retained evidence is historical even when the producer's section wasn't stale");
		assertEquals(Set.of(A), requests.getFirst().fields());
		assertNull(preview.intent(token, ref(A), null).orElseThrow().pingTypeId(), "original annotation is never inherited");
	}
	@Test void siblingFailureDoesNotConsumeLocalRootsAndResetClearsOnlyAttemptHistoryNotCadence() {
		Context context = new Context(); List<PresentationPreviewC2SPacket> requests = new ArrayList<>(); boolean[] present = {true};
		var authorization = new java.util.concurrent.atomic.AtomicReference<>(access(1, A, B));
		List<Set<String>> demands = new ArrayList<>();
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return ADAPTER; }
			public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) {
				demands.add(demand); return present[0] && demand.contains(A) ? Map.of(A, local(0)) : Map.of();
			}
		};
		var preview = new ClientPresentationPreview(type -> Optional.of(authorization.get()), () -> context,
			List.of(reader), (target, type) -> Optional.empty(), requests::add);
		preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
		var first = requests.getFirst(); assertEquals(Set.of(B), first.fields());
		assertTrue(preview.accept(PresentationPreviewS2CPacket.control(first, 1, PresentationPreviewS2CPacket.Status.UNAVAILABLE)));
		present[0] = false; context.tick = PresentationPreviewLimits.MIN_REQUEST_TICKS; preview.tick();
		assertEquals(List.of(Set.of(B), Set.of(A)), requests.stream().map(PresentationPreviewC2SPacket::fields).toList());
		var second = requests.getLast();
		preview.tick(); assertEquals(2, requests.size(), "one pending request blocks further sends");
		assertTrue(preview.accept(PresentationPreviewS2CPacket.control(second, 1, PresentationPreviewS2CPacket.Status.DEFERRED)));
		authorization.set(access(2, A)); preview.tick();
		assertEquals(2, requests.size(), "RESET cannot bypass the last send cadence");
		context.tick += PresentationPreviewLimits.MIN_REQUEST_TICKS; preview.tick();
		assertEquals(Set.of(A), requests.getLast().fields()); assertEquals(2, requests.getLast().view());
		assertEquals(Set.of(A), demands.getLast(), "revoked B is not locally read on the new view");
		assertFalse(preview.accept(PresentationPreviewS2CPacket.result(second, new PresentationSection(ADAPTER, 1, Map.of(A, new PresentationValue.NumberValue(55)), false))));
		assertTrue(preview.accept(PresentationPreviewS2CPacket.control(requests.getLast(), 1, PresentationPreviewS2CPacket.Status.UNAVAILABLE)));
		context.tick += PresentationPreviewLimits.REQUEST_TICKS; preview.tick();
		assertEquals(3, requests.size()); assertFalse(preview.projection().orElseThrow().fields().containsKey(ref(B)));
	}
	@Test void retainedBoundaryChecksExactTargetTypeSchemaAndCurrentMaskEvenAfterReset() {
		Context context = new Context(); List<PresentationPreviewC2SPacket> requests = new ArrayList<>();
		var section = new PresentationSection(ADAPTER, 1, Map.of(A, new PresentationValue.NumberValue(4), DENIED, new PresentationValue.NumberValue(9)), false,
			Map.of(ref(A), "danger", ref(DENIED), "attention"));
		var retained = new java.util.concurrent.atomic.AtomicReference<>(new ClientPresentationPreview.RetainedSnapshot(
			new Target.BlockTarget(TARGET.dimensionId(), 4, 5, 7, "minecraft:stone"), "block", 0, Map.of(ADAPTER, section)));
		var authorization = new java.util.concurrent.atomic.AtomicReference<>(access(1, A));
		var preview = new ClientPresentationPreview(type -> Optional.of(authorization.get()), () -> context,
			List.of(), (target, type) -> Optional.of(retained.get()), requests::add);
		preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
		assertTrue(preview.projection().orElseThrow().property(ref(A)).isEmpty(), "nearby block is not the captured target");
		retained.set(new ClientPresentationPreview.RetainedSnapshot(TARGET, "entity_block", 0, Map.of(ADAPTER, section))); preview.tick();
		assertTrue(preview.projection().orElseThrow().property(ref(A)).isEmpty());
		retained.set(new ClientPresentationPreview.RetainedSnapshot(TARGET, "block", 0,
			Map.of(ADAPTER, new PresentationSection(ADAPTER, 2, section.fields(), false)))); preview.tick();
		assertTrue(preview.projection().orElseThrow().property(ref(A)).isEmpty(), "cache schema must match current accepted schema");
		retained.set(new ClientPresentationPreview.RetainedSnapshot(TARGET, "block", 0, Map.of(ADAPTER, section))); preview.tick();
		assertTrue(preview.projection().orElseThrow().property(ref(A)).orElseThrow().stale());
		assertFalse(preview.projection().orElseThrow().fields().containsKey(ref(DENIED)));
		assertEquals(1, requests.size(), "cached value does not cause a second poll");
		authorization.set(access(2)); preview.tick();
		assertTrue(preview.projection().orElseThrow().fields().isEmpty(), "old cached fields never bypass RESET pruning");
	}
	@Test void uncommittedExternalCandidateCannotBorrowAnotherCandidateMarkerCache() {
		Context context = new Context();
		var captured = Target.ExternalBlockTarget.candidate(TARGET.dimensionId(), "test:provider", "minecraft:stone", "locator-a", true);
		var other = Target.ExternalBlockTarget.candidate(TARGET.dimensionId(), "test:provider", "minecraft:stone", "locator-b", true);
		assertEquals(captured, other, "domain candidate equality does not prove exact provider identity");
		var retained = new ClientPresentationPreview.RetainedSnapshot(other, "block", 0,
			Map.of(ADAPTER, new PresentationSection(ADAPTER, 1, Map.of(A, new PresentationValue.NumberValue(1)), false)));
		var preview = new ClientPresentationPreview(type -> Optional.of(access(1, A)), () -> context,
			List.of(), (target, type) -> Optional.of(retained), request -> {});
		preview.begin(new ClientPresentationPreview.Binding(new Object(), captured, "block", context.level));
		assertTrue(preview.projection().orElseThrow().property(ref(A)).isEmpty());
	}
	@Test void clientSyncedZeroAndFalseStayUsableAndRetainedNeverReplacesThem() {
		Context context = new Context(); Object token = new Object();
		var authorization = new PresentationPreviewAccess(31, 1, "block", Map.of(ADAPTER,
			new PresentationPreviewAccess.Adapter(1, Map.of(A, new PresentationField(A, PresentationField.Kind.NUMBER, true, 0, A),
				B, new PresentationField(B, PresentationField.Kind.FLAG, true, 0, B)))));
		PreviewFieldAccess reader = new PreviewFieldAccess() {
			public String adapterId() { return ADAPTER; }
			public Map<String, Outcome> observe(Target target, Set<String> demand, ReadContext context) {
				return Map.of(A, local(0), B, new Observed(new PreviewObservation(new PresentationValue.Flag(false), PreviewObservation.Origin.CLIENT_SYNCED, 0, false)));
			}
		};
		var retained = new ClientPresentationPreview.RetainedSnapshot(TARGET, "block", 0,
			Map.of(ADAPTER, new PresentationSection(ADAPTER, 1, Map.of(A, new PresentationValue.NumberValue(8), B, new PresentationValue.Flag(true)), false)));
		var preview = new ClientPresentationPreview(type -> Optional.of(authorization), () -> context,
			List.of(reader), (target, type) -> Optional.of(retained), request -> fail("synced zero/false requires no fallback"));
		preview.begin(new ClientPresentationPreview.Binding(token, TARGET, "block", context.level)); preview.tick();
		assertEquals(new PresentationValue.NumberValue(0), preview.intent(token, ref(A), null).orElseThrow().observedValue());
		assertEquals(new PresentationValue.Flag(false), preview.intent(token, ref(B), null).orElseThrow().observedValue());
		assertEquals(PreviewObservation.Origin.CLIENT_SYNCED, preview.projection().orElseThrow().property(ref(B)).orElseThrow().origin());
		assertFalse(preview.projection().orElseThrow().property(ref(B)).orElseThrow().stale());
	}
	@Test void deferredAttemptRemainsDeferredWhenHistoricalCacheDisappears() {
		Context context = new Context(); List<PresentationPreviewC2SPacket> requests = new ArrayList<>();
		var retained = new java.util.concurrent.atomic.AtomicReference<>(Optional.of(new ClientPresentationPreview.RetainedSnapshot(TARGET, "block", 0,
			Map.of(ADAPTER, new PresentationSection(ADAPTER, 1, Map.of(A, new PresentationValue.NumberValue(0)), false)))));
		var preview = new ClientPresentationPreview(type -> Optional.of(access(1, A)), () -> context,
			List.of(), (target, type) -> retained.get(), requests::add);
		preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
		assertTrue(preview.accept(PresentationPreviewS2CPacket.control(requests.getFirst(), 1, PresentationPreviewS2CPacket.Status.DEFERRED)));
		assertTrue(preview.projection().orElseThrow().property(ref(A)).orElseThrow().stale());
		retained.set(Optional.empty()); preview.tick();
		assertEquals(PreviewFieldAccess.Missing.DEFERRED, preview.projection().orElseThrow().fields().get(ref(A)));
		context.tick += PresentationPreviewLimits.REQUEST_TICKS; preview.tick(); assertEquals(1, requests.size());
	}
	@Test void resetAndAbortRevokePublicProjectionEvenIfCancelTransportThrows() {
		Context context = new Context(); var authorization = new java.util.concurrent.atomic.AtomicReference<>(access(1, A));
		var preview = new ClientPresentationPreview(type -> Optional.of(authorization.get()), () -> context,
			List.of(), (target, type) -> Optional.empty(), request -> {
				if (request.kind() == PresentationPreviewC2SPacket.Kind.CANCEL) throw new IllegalStateException("transport");
			});
		preview.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
		authorization.set(access(2)); assertThrows(IllegalStateException.class, preview::tick);
		assertTrue(preview.projection().orElseThrow().fields().isEmpty());
		authorization.set(access(3, A)); context.tick = PresentationPreviewLimits.MIN_REQUEST_TICKS; preview.tick();
		assertThrows(IllegalStateException.class, preview::abort); assertTrue(preview.projection().isEmpty());
	}
}
