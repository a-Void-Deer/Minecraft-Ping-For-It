package nx.pingwheel.common.presentation.minecraft;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.AuthoritativeTargetValidation;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ValidatedMarkerTarget;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.presentation.preview.PresentationPreviewLimits;
import nx.pingwheel.common.presentation.preview.PresentationPreviewServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationServerPreviewTest {
	private static final UUID PLAYER = new UUID(0, 12);
	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone");
	private static final class Basic implements PresentationAdapter {
		public String adapterId() { return PresentationBasic.ID; }
		public String modId() { return "minecraft"; }
		public int schema() { return 1; }
		public int minUpdateIntervalTicks() { return 5; }
		public List<PresentationField> fields() { return PresentationBasic.fields(); }
		public PresentationSection collect(DetachedTarget target, Set<String> fields, CaptureBudget budget) { fail("Basic must use the real Basic reader, never manifest.collect"); return null; }
	}
	private static PresentationServer.Session session(PresentationRegistry adapters, Set<String> fields) {
		var schemas = new java.util.LinkedHashMap<String, Integer>(); var manifest = new java.util.LinkedHashMap<String, List<PresentationField>>();
		adapters.all().forEach(adapter -> { schemas.put(adapter.adapterId(), adapter.schema()); manifest.put(adapter.adapterId(), adapter.fields()); });
		var session = new PresentationServer.Session(23, schemas, manifest); session.ready = true; session.view = 1;
		session.mask = Map.of("block", Map.of(PresentationBasic.ID, fields, InventoryPresentation.ADAPTER_ID, Set.of(InventoryPresentation.ITEMS)));
		return session;
	}
	private static PresentationRegistry registry() { var adapters = new PresentationRegistry(); adapters.register(new Basic()); adapters.register(InventoryPresentation.INSTANCE); return adapters; }
	private static PresentationPreviewC2SPacket request(long id, String adapter, Set<String> demand) {
		return PresentationPreviewC2SPacket.read(23, 1, id, TARGET, "block", adapter, demand);
	}
	private static AuthoritativeTargetValidation valid(Target target, TargetMatchContext context) {
		return AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(target, context, new MarkerAnchor(1.5, 2.5, 3.5), TargetNameJsonCodec.UNKNOWN));
	}
	private static PresentationSection result(Set<String> fields) {
		return new PresentationSection(PresentationBasic.ID, 1,
			fields.contains(PresentationBasic.BLOCK_STATE) ? Map.of(PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(Map.of())) : Map.of(), false);
	}
	@Test void advertisedMaskAndFreshPermissionBothRequiredAndPromotionWaitsForReset() {
		var adapters = registry(); var session = session(adapters, Set.of(PresentationBasic.BLOCK_STATE));
		var policy = PresentationSettings.serverDefaults();
		var initial = PresentationServer.previewAccess(session, adapters, "block", adapter -> PresentationServer.allowedFields(policy, PLAYER, 0, adapter, "block")).orElseThrow();
		assertEquals(Set.of(PresentationBasic.BLOCK_STATE), initial.fields(PresentationBasic.ID));
		assertFalse(initial.allows(PresentationBasic.ID, PresentationBasic.NAME), "fresh default alone cannot promote an unadvertised field");
		assertFalse(initial.adapters().containsKey(InventoryPresentation.ADAPTER_ID));
		policy.setPermissionLevels(Map.of(PresentationBasic.BLOCK_STATE, 3));
		assertTrue(PresentationServer.previewAccess(session, adapters, "block", adapter -> PresentationServer.allowedFields(policy, PLAYER, 0, adapter, "block")).orElseThrow().adapters().isEmpty());
		assertTrue(PresentationServer.previewAccess(session, adapters, "entity", adapter -> Set.of(PresentationBasic.BLOCK_STATE)).orElseThrow().adapters().isEmpty());
		session.mask = Map.of("block", Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)));
		assertFalse(initial.allows(PresentationBasic.ID, PresentationBasic.NAME), "detached old view is not mutated by RESET");
		session.ready = false;
		assertTrue(PresentationServer.previewAccess(session, adapters, "block", adapter -> fail("unready must not authorize")).isEmpty());
	}
	@Test void bridgeDebitsBeforeValidationAndReadsOnlyAdmittedDemandWithoutPublishingPlaceholder() {
		var adapters = registry(); var session = session(adapters, Set.of(PresentationBasic.BLOCK_STATE));
		var access = PresentationServer.previewAccess(session, adapters, "block", adapter -> Set.of(PresentationBasic.BLOCK_STATE)).orElseThrow();
		List<String> events = new ArrayList<>(); var budget = new PresentationAdapter.CaptureBudget(3);
		var capture = MinecraftPresentationPreview.capture(request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), access,
			adapters.get(PresentationBasic.ID), budget, () -> { assertEquals(2, budget.remaining()); events.add("validate"); return valid(TARGET, TargetMatchContext.blockEntityBlock(false)); },
			(target, demand) -> { assertEquals(1, budget.remaining()); assertEquals(TARGET, target); assertEquals(Set.of(PresentationBasic.BLOCK_STATE), demand); events.add("basic"); return result(demand); });
		assertEquals(List.of("validate", "basic"), events); assertEquals(PresentationPreviewS2CPacket.Status.RESULT, capture.status());
		assertFalse(capture.section().fields().containsKey(PresentationBasic.NAME)); assertTrue(capture.section().annotations().isEmpty());
		assertTrue(session.sent.isEmpty()); assertEquals(0, session.revision, "preview never becomes an initial marker");
	}
	@Test void deniedZeroWorkChangedIdentityTypeAndRejectedValidationStopBeforeCapture() {
		var adapters = registry(); var session = session(adapters, Set.of(PresentationBasic.BLOCK_STATE));
		var access = PresentationServer.previewAccess(session, adapters, "block", adapter -> Set.of(PresentationBasic.BLOCK_STATE)).orElseThrow();
		var request = request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE));
		var denied = MinecraftPresentationPreview.capture(request(2, PresentationBasic.ID, Set.of(PresentationBasic.NAME)), access, adapters.get(PresentationBasic.ID),
			new PresentationAdapter.CaptureBudget(10), () -> fail("denied before lookup"), (t, d) -> fail("denied source"));
		assertEquals(PresentationPreviewS2CPacket.Status.REJECTED, denied.status());
		assertEquals(PresentationPreviewS2CPacket.Status.DEFERRED, MinecraftPresentationPreview.capture(request, access, adapters.get(PresentationBasic.ID),
			new PresentationAdapter.CaptureBudget(0), () -> fail("zero budget before lookup"), (t, d) -> fail("zero budget source")).status());
		for (var verdict : List.of(valid(new Target.BlockTarget(TARGET.dimensionId(), 1, 2, 3, "minecraft:dirt"), TargetMatchContext.blockEntityBlock(false)),
			valid(TARGET, TargetMatchContext.blockEntityBlock(true)), AuthoritativeTargetValidation.rejected(MarkerRejectReason.OUT_OF_RANGE),
			AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE))) {
			assertEquals(PresentationPreviewS2CPacket.Status.REJECTED, MinecraftPresentationPreview.capture(request, access, adapters.get(PresentationBasic.ID),
				new PresentationAdapter.CaptureBudget(4), () -> verdict, (t, d) -> fail("invalid target/type must not retarget or capture")).status());
		}
	}
	@Test void candidatePreviewValidatesBindingAndActualTypeBeforeSafeSourceCapture() {
		var adapters = registry(); var session = session(adapters, Set.of(PresentationBasic.BLOCK_STATE));
		var access = PresentationServer.previewAccess(session, adapters, "block", adapter -> Set.of(PresentationBasic.BLOCK_STATE)).orElseThrow();
		var adapter = adapters.get(PresentationBasic.ID);
		var candidate = Target.ExternalBlockTarget.candidate(TARGET.dimensionId(), "test:provider", "test:block", "opaque", false);
		var request = PresentationPreviewC2SPacket.read(23, 1, 3, candidate, "block", PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE));

		// Identity drift including the opaque locator rejects before any provider source read.
		var drifted = Target.ExternalBlockTarget.candidate(TARGET.dimensionId(), "test:provider", "test:block", "other", false);
		assertEquals(PresentationPreviewS2CPacket.Status.REJECTED, MinecraftPresentationPreview.capture(request, access, adapter,
			new PresentationAdapter.CaptureBudget(10), () -> valid(drifted, TargetMatchContext.blockEntityBlock(false)),
			(t, d) -> fail("committed Basic must not serve a candidate"), (t, d) -> fail("drifted binding must not capture")).status());
		// The actual server-resolved target type still governs the preview.
		assertEquals(PresentationPreviewS2CPacket.Status.REJECTED, MinecraftPresentationPreview.capture(request, access, adapter,
			new PresentationAdapter.CaptureBudget(10), () -> valid(candidate, TargetMatchContext.blockEntityBlock(true)),
			(t, d) -> fail("committed Basic must not serve a candidate"), (t, d) -> fail("wrong type must not capture")).status());
		// An unavailable safe source is unavailable, never approximated or materialized.
		assertEquals(PresentationPreviewS2CPacket.Status.UNAVAILABLE, MinecraftPresentationPreview.capture(request, access, adapter,
			new PresentationAdapter.CaptureBudget(10), () -> valid(candidate, TargetMatchContext.blockEntityBlock(false)),
			(t, d) -> fail("committed Basic must not serve a candidate"), (t, d) -> null).status());
		// The admitted candidate is observed through the safe capture port only.
		List<String> events = new ArrayList<>();
		var capture = MinecraftPresentationPreview.capture(request, access, adapter, new PresentationAdapter.CaptureBudget(3),
			() -> { events.add("validate"); return valid(candidate, TargetMatchContext.blockEntityBlock(false)); },
			(t, d) -> fail("committed Basic must not serve a candidate"),
			(t, d) -> { events.add("candidate"); assertEquals(candidate, t); assertEquals(Set.of(PresentationBasic.BLOCK_STATE), d); return result(d); });
		assertEquals(List.of("validate", "candidate"), events);
		assertEquals(PresentationPreviewS2CPacket.Status.RESULT, capture.status());
		assertFalse(capture.section().fields().containsKey(PresentationBasic.NAME));
		assertTrue(session.sent.isEmpty()); assertEquals(0, session.revision, "candidate preview never becomes a marker");
		// Budget admission precedes the safe source read.
		assertEquals(PresentationPreviewS2CPacket.Status.DEFERRED, MinecraftPresentationPreview.capture(request, access, adapter,
			new PresentationAdapter.CaptureBudget(1), () -> valid(candidate, TargetMatchContext.blockEntityBlock(false)),
			(t, d) -> fail("committed Basic must not serve a candidate"), (t, d) -> fail("budget denial must not capture")).status());
	}
	@Test void optionalAdapterPreviewUsesCandidateAwareCollectInsteadOfCommittedCollect() {
		List<String> calls = new ArrayList<>();
		PresentationAdapter optional = new PresentationAdapter() {
			public String adapterId() { return "test:optional"; }
			public String modId() { return "test"; }
			public int schema() { return 1; }
			public int minUpdateIntervalTicks() { return 5; }
			public List<PresentationField> fields() { return List.of(new PresentationField("test:value", PresentationField.Kind.FLAG, true, 0, "value")); }
			public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
				calls.add("collect"); return null;
			}
			@Override public PresentationSection collectPreview(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
				calls.add("preview"); return new PresentationSection("test:optional", 1,
					Map.of("test:value", new PresentationValue.Flag(true)), false);
			}
		};
		var adapters = registry(); adapters.register(optional);
		var session = new PresentationServer.Session(23, Map.of(PresentationBasic.ID, 1, optional.adapterId(), 1),
			Map.of(PresentationBasic.ID, PresentationBasic.fields(), optional.adapterId(), optional.fields()));
		session.ready = true; session.view = 1;
		session.mask = Map.of("block", Map.of(optional.adapterId(), Set.of("test:value")));
		var candidate = Target.ExternalBlockTarget.candidate(TARGET.dimensionId(), "test:provider", "test:block", "opaque", false);
		var request = PresentationPreviewC2SPacket.read(23, 1, 1, candidate, "block", optional.adapterId(), Set.of("test:value"));
		var access = PresentationServer.previewAccess(session, adapters, "block",
			adapter -> Set.of("test:value")).orElseThrow();

		assertEquals(PresentationPreviewS2CPacket.Status.RESULT, MinecraftPresentationPreview.capture(request, access, optional,
			new PresentationAdapter.CaptureBudget(4), () -> valid(candidate, TargetMatchContext.blockEntityBlock(false)),
			(t, d) -> fail("optional adapters never use the Basic port")).status());
		assertEquals(List.of("preview"), calls);
	}
	private static final class QueueHost implements PresentationPreviewServer.Host {
		final PresentationRegistry adapters = registry(); final PresentationServer.Session session = session(adapters, Set.of(PresentationBasic.BLOCK_STATE));
		final PresentationSettings settings = PresentationSettings.serverDefaults(); List<String> events = new ArrayList<>();
		List<PresentationPreviewS2CPacket> sent = new ArrayList<>(); boolean revokeDuringRead; int reads;
		public Optional<PresentationPreviewAccess> access(UUID player, String type) {
			return PresentationServer.previewAccess(session, adapters, type, adapter -> PresentationServer.allowedFields(settings, player, 0, adapter, type));
		}
		public int intervalTicks(UUID player, String adapter) { return settings.interval(adapter, adapters.get(adapter).minUpdateIntervalTicks()); }
		public int scanBudget() { return settings.scanBudget(); }
		public PresentationPreviewServer.Capture capture(UUID player, PresentationPreviewC2SPacket request, PresentationPreviewAccess authorized, PresentationAdapter.CaptureBudget budget) {
			return MinecraftPresentationPreview.capture(request, authorized, adapters.get(request.adapterId()), budget,
				() -> { events.add("validate"); return valid(TARGET, TargetMatchContext.blockEntityBlock(false)); },
				(target, demand) -> { reads++; events.add("basic"); if (revokeDuringRead) settings.setPermissionLevels(Map.of(PresentationBasic.BLOCK_STATE, 3)); return result(demand); });
		}
		public boolean send(UUID player, PresentationPreviewS2CPacket packet) { events.add("send"); sent.add(packet); return true; }
	}
	@Test void queueIngressNeverReadsAndFreshRevocationBeforeDrainOrPublicationNeverLeaks() {
		var host = new QueueHost(); var queue = new PresentationPreviewServer(host);
		assertTrue(queue.handle(PLAYER, request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), 0)); assertTrue(host.events.isEmpty());
		host.settings.setPermissionLevels(Map.of(PresentationBasic.BLOCK_STATE, 3)); queue.drain(0, new PresentationAdapter.CaptureBudget(20), 4);
		assertEquals(0, host.reads); assertTrue(host.sent.isEmpty());
		host.settings.setPermissionLevels(Map.of()); host.revokeDuringRead = true;
		assertTrue(queue.handle(PLAYER, request(2, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), PresentationPreviewLimits.MIN_REQUEST_TICKS));
		queue.drain(PresentationPreviewLimits.MIN_REQUEST_TICKS, new PresentationAdapter.CaptureBudget(20), 4);
		assertEquals(1, host.reads); assertTrue(host.sent.isEmpty());
		queue.drain(1000, new PresentationAdapter.CaptureBudget(20), 4); assertEquals(1, host.reads); assertTrue(host.sent.isEmpty());
	}
	@Test void actualLeaseSamplerRunsBeforePreviewUsingSameResidualWorkAndCaptureQuota() {
		for (int captures : new int[] {1, 2}) {
			var host = new QueueHost(); var queue = new PresentationPreviewServer(host);
			assertTrue(queue.handle(PLAYER, request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), 0));
			var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
			var lease = new PresentationServer.Lease(new ServerMarker(new MarkerId(12), PLAYER, TARGET, type, type.defaultPingType(), new MarkerAnchor(1.5, 2.5, 3.5), 0, 500, List.of(PLAYER)), "Owner");
			var work = new PresentationAdapter.CaptureBudget(5);
			PresentationServer.sampleTick(List.of(lease), 0, 0, work, captures,
				(current, shared, remaining) -> PresentationServer.captureSources(current, host.adapters, host.settings, 0, shared, remaining, false,
					adapter -> Set.of(PresentationBasic.BLOCK_STATE), demand -> { host.events.add("lease"); return result(demand); }),
				current -> host.events.add("publish-lease"), queue);
			if (captures == 1) { assertEquals(List.of("lease", "publish-lease"), host.events); assertEquals(1, queue.queuedCount()); assertEquals(4, work.remaining()); }
			else { assertEquals(List.of("lease", "publish-lease", "validate", "basic", "send"), host.events); assertEquals(0, queue.queuedCount()); assertEquals(1, work.remaining()); }
			assertTrue(host.session.sent.isEmpty(), "preview may not backfill marker-known state");
		}
	}
	@Test void noLeaseTickStillDrainsZeroConfiguredBudgetNeverReadsAndExpiryIsDeferred() {
		var host = new QueueHost(); host.settings.setScanBudget(0); var queue = new PresentationPreviewServer(host);
		queue.handle(PLAYER, request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), 0);
		var work = new PresentationAdapter.CaptureBudget(10);
		PresentationServer.sampleTick(List.of(), 0, 0, work, 3, (l, b, c) -> fail("no leases"), l -> fail("no lease publish"), queue);
		assertTrue(host.events.isEmpty()); assertEquals(10, work.remaining());
		PresentationServer.sampleTick(List.of(), 0, PresentationPreviewLimits.REQUEST_TICKS, work, 3, (l, b, c) -> fail("no leases"), l -> {}, queue);
		assertEquals(0, host.reads); assertEquals(PresentationPreviewS2CPacket.Status.DEFERRED, host.sent.getFirst().status());
		host.settings.setScanBudget(10);
		queue.handle(PLAYER, request(2, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), PresentationPreviewLimits.REQUEST_TICKS);
		PresentationServer.sampleTick(List.of(), 0, PresentationPreviewLimits.REQUEST_TICKS, work, 1, (l, b, c) -> fail("no leases"), l -> {}, queue);
		assertEquals(1, host.reads); assertEquals(PresentationPreviewS2CPacket.Status.RESULT, host.sent.getLast().status());
	}
	@Test void fullWireRequestAndResponseUseProductionAccessAndCaptureAndConfiguredCadence() {
		var host = new QueueHost(); host.settings.setUpdateIntervals(Map.of(PresentationBasic.ID, 40)); var queue = new PresentationPreviewServer(host);
		FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
		try {
			request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)).write(wire);
			assertTrue(queue.handle(PLAYER, PresentationPreviewC2SPacket.readSafe(wire), 0));
			queue.drain(0, new PresentationAdapter.CaptureBudget(10), 2); host.sent.getFirst().write(wire);
			var decoded = PresentationPreviewS2CPacket.readSafe(wire); assertFalse(decoded.isCorrupt());
			assertEquals(result(Set.of(PresentationBasic.BLOCK_STATE)), decoded.decodeSection(host.access(PLAYER, "block").orElseThrow()));
			assertTrue(queue.handle(PLAYER, request(2, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), PresentationPreviewLimits.MIN_REQUEST_TICKS));
			queue.drain(PresentationPreviewLimits.MIN_REQUEST_TICKS, new PresentationAdapter.CaptureBudget(10), 2); assertEquals(1, host.reads);
			queue.drain(40, new PresentationAdapter.CaptureBudget(10), 2); assertEquals(2, host.reads);
			assertTrue(host.session.sent.isEmpty()); assertEquals(0, host.session.revision);
		} finally { wire.release(); }
	}
	@Test void optionalFailureStillChargesResidualWorkAndDoesNotBlockBasicPreview() {
		var host = new QueueHost();
		PresentationAdapter optional = new PresentationAdapter() {
			public String adapterId() { return "test:optional"; }
			public String modId() { return "test"; }
			public int schema() { return 1; }
			public int minUpdateIntervalTicks() { return 5; }
			public List<PresentationField> fields() { return List.of(new PresentationField("test:value", PresentationField.Kind.FLAG, true, 0, "value")); }
			public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
				host.events.add("optional"); assertTrue(budget.scan()); throw new IllegalStateException("optional failure");
			}
		};
		var adapters = new PresentationRegistry(); adapters.register(optional);
		var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		var lease = new PresentationServer.Lease(new ServerMarker(new MarkerId(12), PLAYER, TARGET, type, type.defaultPingType(), new MarkerAnchor(1.5, 2.5, 3.5), 0, 500, List.of(PLAYER)), "Owner");
		var queue = new PresentationPreviewServer(host); queue.handle(PLAYER, request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), 0);
		var work = new PresentationAdapter.CaptureBudget(5);
		PresentationServer.sampleTick(List.of(lease), 0, 0, work, 2, (current, shared, captures) ->
			PresentationServer.captureSources(current, adapters, host.settings, 0, shared, captures, false,
				adapter -> Set.of("test:value"), fields -> fail("no Basic lease")), current -> {}, queue);
		assertEquals(List.of("optional", "validate", "basic", "send"), host.events);
		assertEquals(0, work.remaining(), "failed optional work remains charged before preview admission");
		assertEquals(PresentationPreviewS2CPacket.Status.RESULT, host.sent.getFirst().status());
	}
	@Test void disconnectAndResetCannotCaptureAgainOrPolluteMarkerState() {
		var host = new QueueHost(); var queue = new PresentationPreviewServer(host);
		queue.handle(PLAYER, request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), 0); queue.disconnect(PLAYER);
		queue.drain(0, new PresentationAdapter.CaptureBudget(10), 1); assertEquals(0, host.reads);
		queue.handle(PLAYER, request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), 0); queue.reset();
		queue.drain(0, new PresentationAdapter.CaptureBudget(10), 1); assertEquals(0, host.reads); assertTrue(host.session.sent.isEmpty());
	}
	@Test void exhaustedLeaseWorkDefersPreviewWithoutValidationAndRevokedExpirySendsNothing() {
		var host = new QueueHost(); var queue = new PresentationPreviewServer(host);
		queue.handle(PLAYER, request(1, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), 0);
		var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		var lease = new PresentationServer.Lease(new ServerMarker(new MarkerId(12), PLAYER, TARGET, type, type.defaultPingType(), new MarkerAnchor(1.5, 2.5, 3.5), 0, 500, List.of(PLAYER)), "Owner");
		var work = new PresentationAdapter.CaptureBudget(2);
		PresentationServer.sampleTick(List.of(lease), 0, 0, work, 2, (current, shared, captures) ->
			PresentationServer.captureSources(current, host.adapters, host.settings, 0, shared, captures, false,
				adapter -> Set.of(PresentationBasic.BLOCK_STATE), fields -> { host.events.add("lease"); return result(fields); }), current -> {}, queue);
		assertEquals(List.of("lease"), host.events); assertEquals(1, queue.queuedCount()); assertEquals(1, work.remaining());
		host.settings.setPermissionLevels(Map.of(PresentationBasic.BLOCK_STATE, 3));
		PresentationServer.sampleTick(List.of(), 0, PresentationPreviewLimits.REQUEST_TICKS, work, 2, (l, b, c) -> fail("no lease"), l -> {}, queue);
		assertEquals(0, host.reads); assertTrue(host.sent.isEmpty(), "control publication also requires still-authorized roots/schema");
	}
	@Test void optionalPreviewCollectorUsesOnlyBoundedResidualAndFailureLeavesFollowingBasicUsable() {
		var host = new QueueHost();
		var optional = new PresentationAdapter() {
			public String adapterId() { return "test:optional"; }
			public String modId() { return "test"; }
			public int schema() { return 1; }
			public int minUpdateIntervalTicks() { return 5; }
			public List<PresentationField> fields() { return List.of(new PresentationField("test:value", PresentationField.Kind.FLAG, true, 0, "value")); }
			public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
				assertEquals(Set.of("test:value"), demand); assertEquals(2, budget.remaining());
				assertTrue(budget.scan()); host.events.add("optional-preview"); throw new LinkageError("optional missing");
			}
		};
		var adapters = registry(); adapters.register(optional);
		var session = new PresentationServer.Session(23, Map.of(PresentationBasic.ID, 1, optional.adapterId(), 1),
			Map.of(PresentationBasic.ID, PresentationBasic.fields(), optional.adapterId(), optional.fields()));
		session.ready = true; session.view = 1;
		session.mask = Map.of("block", Map.of(PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE), optional.adapterId(), Set.of("test:value")));
		var queue = new PresentationPreviewServer(new PresentationPreviewServer.Host() {
			public Optional<PresentationPreviewAccess> access(UUID player, String type) { return PresentationServer.previewAccess(session, adapters, type,
				adapter -> PresentationServer.allowedFields(host.settings, player, 0, adapter, type)); }
			public int intervalTicks(UUID player, String adapter) { return 5; }
			public int scanBudget() { return 50; }
			public PresentationPreviewServer.Capture capture(UUID player, PresentationPreviewC2SPacket request, PresentationPreviewAccess access, PresentationAdapter.CaptureBudget budget) {
				return MinecraftPresentationPreview.capture(request, access, adapters.get(request.adapterId()), budget,
					() -> valid(TARGET, TargetMatchContext.blockEntityBlock(false)), (t, demand) -> result(demand));
			}
			public boolean send(UUID player, PresentationPreviewS2CPacket packet) { return host.send(player, packet); }
		});
		assertTrue(queue.handle(PLAYER, request(1, optional.adapterId(), Set.of("test:value")), 0));
		var work = new PresentationAdapter.CaptureBudget(4); assertEquals(1, queue.drain(0, work, 1));
		assertEquals(1, work.remaining()); assertEquals(PresentationPreviewS2CPacket.Status.UNAVAILABLE, host.sent.getFirst().status());
		assertTrue(queue.handle(PLAYER, request(2, PresentationBasic.ID, Set.of(PresentationBasic.BLOCK_STATE)), PresentationPreviewLimits.MIN_REQUEST_TICKS));
		assertEquals(1, queue.drain(PresentationPreviewLimits.MIN_REQUEST_TICKS, new PresentationAdapter.CaptureBudget(4), 1));
		assertEquals(PresentationPreviewS2CPacket.Status.RESULT, host.sent.getLast().status());
		assertTrue(session.sent.isEmpty()); assertEquals(0, session.revision);
	}
}
