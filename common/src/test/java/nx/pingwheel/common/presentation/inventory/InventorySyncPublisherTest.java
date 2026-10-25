package nx.pingwheel.common.presentation.inventory;

import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.*;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jetbrains.annotations.Unmodifiable;
import org.apache.logging.log4j.Level;
import static org.junit.jupiter.api.Assertions.*;

class InventorySyncPublisherTest {
	static class Transport implements InventorySyncPublisher.Transport {
		final List<InventoryS2CPacket> sent = new ArrayList<>(); boolean authorized = true; int encoded;
		boolean failNextSend;
		final List<int[]> measuredCaps = new ArrayList<>();
		@Override public boolean authorized(SyncPublisher.Context context) { return authorized; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { encoded++; return bytes(packet); }
		@Override public int encodedBytes(InventoryS2CPacket packet, int displayBytes, int frameBytes) {
			measuredCaps.add(new int[] { displayBytes, frameBytes });
			return InventorySyncPublisher.Transport.super.encodedBytes(packet, displayBytes, frameBytes);
		}
		@Override public void send(UUID player, InventoryS2CPacket packet) {
			if (failNextSend) { failNextSend = false; throw new IllegalStateException("application send seam backpressure"); }
			sent.add(packet);
		}
	}
	static int bytes(InventoryS2CPacket packet) { var buf = new FriendlyByteBuf(Unpooled.buffer()); try { packet.write(buf); return buf.readableBytes(); } finally { buf.release(); } }
	static SyncPublisher.Context context(String id, long baseline) { return new SyncPublisher.Context(id, new UUID(1, 1), "100/1", 1, baseline); }
	static InventorySyncPublisher.Route route(MarkerId marker, String target) { return new InventorySyncPublisher.Route(7, 100, 1, 0, marker, target, "danger"); }
	static CaptureResult capture(long revision, int entries, int display) {
		Map<String, CaptureResult.OpaqueValue> values = new LinkedHashMap<>();
		for (int i = 0; i < entries; i++) values.put("k" + i, InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "v" + i), i,
			"stone", "d".repeat(display), false)));
		return new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, values)),
			new CaptureResult.Coverage("inventory", revision, entries, OptionalLong.of(entries)), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
	}
	static void publish(InventorySyncPublisher publisher, SyncPublisher.Context context, CaptureResult result) {
		var payload = (CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow();
		assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(result,
			new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, payload.entries().keySet()), context,
			new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
	}
	@Test void scopedSweepEvidenceControlsBothEntryQualityAndCompletionWithoutChangingGenericPublication() {
		for (var evidence : InventoryStep.SweepEvidence.values()) for (var consistency : CaptureResult.Consistency.values())
			for (var completeness : CaptureResult.Completeness.values()) for (boolean knownCoverage : new boolean[] {false, true})
				for (int entries : new int[] {0, 1}) {
			var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
			try (var publisher = new InventorySyncPublisher(memory, transport)) {
				publisher.advance(0, settings); var context = context("preview/1", 1);
				assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
				var source = capture(1, entries, 0);
				var result = new CaptureResult(source.payload(), new CaptureResult.Coverage("inventory", 1, entries,
					knownCoverage ? OptionalLong.of(entries) : OptionalLong.empty()), source.availability(), completeness, consistency, Optional.empty(), Optional.empty());
				var projection = new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE,
					((CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow()).entries().keySet());
				var ledger = new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L));
				assertInstanceOf(SyncPublisher.Outcome.Accepted.class, evidence == InventoryStep.SweepEvidence.NONE
					? publisher.publish(result, projection, context, ledger) : publisher.publish(result, evidence, projection, context, ledger));
				publisher.drain();
				boolean normal = consistency == CaptureResult.Consistency.VERIFIED || consistency == CaptureResult.Consistency.EVENTUAL
					&& evidence == InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP && completeness == CaptureResult.Completeness.COMPLETE && knownCoverage;
				var quality = normal ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UNCERTAIN;
				assertTrue(transport.sent.stream().flatMap(packet -> packet.entries().stream()).allMatch(entry -> entry.quality() == quality));
				assertEquals(completeness == CaptureResult.Completeness.COMPLETE ? quality : completeness == CaptureResult.Completeness.INCOMPLETE
					? InventoryS2CPacket.Status.INCOMPLETE : InventoryS2CPacket.Status.UPDATING, transport.sent.getLast().status());
				assertEquals(completeness == CaptureResult.Completeness.COMPLETE, transport.sent.getLast().completeScan());
			}
			assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	@Test void evidenceCannotPromoteShortCoverageOrStickToLaterGenericVersionsAndAggregateQualityWins() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/1", 1);
			publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null));
			for (int revision = 1; revision <= 4; revision++) {
				var source = capture(revision, 1, 0);
				var payload = revision == 4 ? Optional.<CaptureResult.CapturePayload>of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("k0",
					InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 9, "stone", null, true))))) : source.payload();
				var result = new CaptureResult(payload, new CaptureResult.Coverage("inventory", revision, 1, OptionalLong.of(revision == 1 ? 2 : 1)),
					source.availability(), source.completeness(), source.consistency(), source.sourceVersion(), source.nextCursor());
				var projection = new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("k0"));
				var ledger = new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L));
				assertInstanceOf(SyncPublisher.Outcome.Accepted.class, revision == 3 ? publisher.publish(result, projection, context, ledger)
					: publisher.publish(result, InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, projection, context, ledger));
				publisher.drain(); var sent = transport.sent.getLast();
				assertEquals(revision == 2 || revision == 4 ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UNCERTAIN, sent.status());
				var entry = transport.sent.stream().flatMap(packet -> packet.entries().stream()).reduce((a, b) -> b).orElseThrow();
				assertEquals(revision == 4 ? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : revision == 2 ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UNCERTAIN, entry.quality());
			}
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void immutableBaselineSurvivesMoreThanFivePeriodsAndRecipientRepairIsIsolated() {
		InventorySettings settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		Transport transport = new Transport(); RetainedMemoryLedger memory = new RetainedMemoryLedger(16000000);
		try (InventorySyncPublisher publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var a = context("tracking/1", 1); var b = context("tracking/2", 2);
			assertTrue(publisher.register(a, route(new MarkerId(1), "target-a"))); assertTrue(publisher.register(b, route(new MarkerId(2), "target-b")));
			publish(publisher, a, capture(1, 12, 2500)); publish(publisher, b, capture(1, 1, 2500));
			for (int period = 0; period < 20; period++) {
				publisher.advance(period * 3L, settings); int start = transport.sent.size(); publisher.drain();
				long snapshots = transport.sent.subList(start, transport.sent.size()).stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).mapToLong(InventorySyncPublisherTest::bytes).sum();
				assertTrue(snapshots <= settings.trackingSnapshotClientPeriodBytes(), "period sum covers all targets, not each part");
				if (period < 5) assertTrue(publisher.pending(a));
			}
			assertFalse(publisher.pending(a)); assertFalse(publisher.pending(b));
			assertEquals(12, transport.sent.stream().filter(p -> p.markerId() != null && p.markerId().value() == 1 && p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).count());
			publisher.cancel(a); var update = capture(2, 1, 550); publish(publisher, b, update); publisher.advance(63, settings); publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.STREAM && p.markerId().value() == 2 && p.baselineId() == 2));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void revokeAndMemoryDeferDoNotEncodeOrSendAndAccountingReleasesPartialBatches() {
		InventorySettings settings = InventorySettings.serverDefaults(); Transport transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); assertTrue(publisher.register(context, route(new MarkerId(1), "target")));
			publish(publisher, context, capture(1, 3, 100)); memory.setCap(memory.retained()); publisher.drain(); assertEquals(0, transport.encoded);
			transport.authorized = false; memory.setCap(16000000); publisher.drain(); assertTrue(transport.sent.isEmpty()); assertEquals(0, memory.retained());
		}
	}
	@Test void changingPeriodAndWindowCannotRefundCurrentSpend() {
		InventoryPeriodClock clock = new InventoryPeriodClock(); assertTrue(clock.advance(0, 3)); assertFalse(clock.advance(1, 1));
		assertFalse(clock.advance(2, 1)); assertTrue(clock.advance(3, 1));
		InventoryWireWindow window = new InventoryWireWindow(100, 4); assertTrue(window.trySpend(450));
		window.reconfigure(100, 4); assertEquals(50, window.remaining());
		window.reconfigure(50, 2); assertEquals(0, window.remaining()); assertEquals(450, window.spent());
	}
	@Test void newerOfferedCutCannotRewriteAnAdmittedBaselineAndHeartbeatUsesClosedDelivery() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		settings.getTracking().setHeartbeatPeriods(1); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); publisher.register(context, route(new MarkerId(1), "target"));
			var first = capture(1, 3, 2500); publish(publisher, context, first); publisher.drain();
			assertEquals(1, transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).count());
			var latest = capture(2, 3, 550); publish(publisher, context, latest);
			for (int tick = 3; tick <= 18; tick += 3) { publisher.advance(tick, settings); publisher.drain(); }
			var baseline = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).toList();
			assertEquals(3, baseline.size()); assertTrue(baseline.stream().allMatch(p -> p.watermark() == 1 && p.entries().getFirst().displayJson().length() == 2500));
			var heartbeat = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.HEARTBEAT).reduce((a, b) -> b).orElseThrow();
			var updates = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM).flatMap(p -> p.entries().stream()).collect(java.util.stream.Collectors.toMap(InventoryS2CPacket.Entry::key, e -> e, (a, b) -> b));
			assertEquals(2, heartbeat.watermark()); assertEquals(InventoryChecksums.checksumEntries(updates.values()), heartbeat.checksum());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void invalidationCancelsPartialBatchAndHasPriorityOverAnotherConsumersItems() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var a = context("tracking/1", 1); var b = context("tracking/2", 2);
			publisher.register(a, route(new MarkerId(1), "target-a")); publisher.register(b, route(new MarkerId(2), "target-b"));
			publish(publisher, a, capture(1, 3, 2500)); publish(publisher, b, capture(1, 3, 2500)); publisher.drain();
			int before = transport.sent.size(); publisher.status(b, InventoryS2CPacket.Status.INVALID); publisher.advance(3, settings); publisher.drain();
			assertEquals(InventoryS2CPacket.Kind.STATUS, transport.sent.get(before).kind()); assertEquals(new MarkerId(2), transport.sent.get(before).markerId());
			assertTrue(transport.sent.subList(before, transport.sent.size()).stream().noneMatch(p -> p.markerId().equals(new MarkerId(2)) && !p.entries().isEmpty()));
			publisher.cancel(a); publisher.cancel(b);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void controlNegotiationAndReplayAreByteChargedAndCannotResetClientPeriodAllowance() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); UUID recipient = new UUID(1, 1);
			var offer = InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1);
			for (int i = 0; i < 1000; i++) { publisher.control(recipient, offer); publisher.drainControls(); }
			long bytes = transport.sent.stream().mapToLong(InventorySyncPublisherTest::bytes).sum();
			assertTrue(bytes > 0 && bytes <= settings.previewClientPeriodBytes());
			int count = transport.sent.size(); publisher.advance(1, settings); publisher.drainControls(); assertEquals(count, transport.sent.size());
			publisher.advance(5, settings); publisher.drainControls(); assertEquals(count + 1, transport.sent.size());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void indivisibleOversizedPreviewBatchTerminatesIncompleteInsteadOfWaitingForever() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/1", 1);
			publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null));
			publish(publisher, context, capture(1, 1, 4096)); publisher.drain();
			assertFalse(publisher.pending(context));
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, transport.sent.getLast().status());
			assertFalse(transport.sent.getLast().completeScan()); assertTrue(transport.sent.getLast().entries().isEmpty());
		}
	}
	private static InventoryDomainCodec.Item nativeNamedItem(int nameLength) {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("x".repeat(nameLength)));
		var encoded = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Catalog(net.minecraft.core.RegistryAccess.EMPTY).encode(stack);
		assertFalse(encoded.display().aggregateRequired());
		return new InventoryDomainCodec.Item(encoded.key(), 1, encoded.display().label(), encoded.display().displayJson(), false);
	}
	private static InventoryS2CPacket previewFrame(String key, InventoryDomainCodec.Item item, int index, int parts) {
		return previewFrame(key, item, index, parts, InventoryS2CPacket.Status.READY);
	}
	private static InventoryS2CPacket previewFrame(String key, InventoryDomainCodec.Item item, int index, int parts, InventoryS2CPacket.Status status) {
		var entry = new InventoryS2CPacket.Entry(key, item.key().itemId(), item.label(), item.displayJson(), item.count(), 1,
			false, InventoryS2CPacket.Status.READY, 0, false, null);
		return InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, 1, null, 1, 1, 1, index, parts, true,
			status, 0, List.of(entry)).stamp(100, 1);
	}
	private static InventoryDomainCodec.Item nativeAtFrameBytes(String key, int index, int parts, int target) {
		return nativeAtFrameBytes(key, index, parts, target, InventoryS2CPacket.Status.READY);
	}
	private static InventoryDomainCodec.Item nativeAtFrameBytes(String key, int index, int parts, int target, InventoryS2CPacket.Status status) {
		int nameLength = 3000;
		for (int attempt = 0; attempt < 4; attempt++) {
			var item = nativeNamedItem(nameLength); int size = bytes(previewFrame(key, item, index, parts, status));
			if (size == target) return item;
			nameLength += target - size;
		}
		throw new AssertionError("could not calibrate actual native display frame");
	}
	private static @Unmodifiable List<InventoryS2CPacket> drainPreview(InventorySyncPublisher publisher, Transport transport,
		SyncPublisher.Context context, InventorySettings settings, int from) {
		int start = transport.sent.size();
		for (int tick = from; tick < from + 1500 && (publisher.pending(context) || publisher.controlPending(context)); tick += settings.getPreview().getPeriodTicks()) {
			publisher.advance(tick, settings); publisher.drain();
		}
		assertFalse(publisher.pending(context), "no indivisible immutable part may remain pending forever");
		assertFalse(publisher.controlPending(context));
		return List.copyOf(transport.sent.subList(start, transport.sent.size()));
	}
	@ParameterizedTest @ValueSource(ints = {127, 128, 256})
	void finalMultipartFramingAdmitsExactCapacityAndDropsOneByteOverWholeAtEarlyAndLateIndexes(int parts) {
		for (int index : new int[] {0, parts - 1}) for (int over : new int[] {0, 1}) {
			var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
			long allowance = settings.previewClientPeriodBytes(); int capacity = Math.toIntExact(allowance - Math.min(allowance, 128 + allowance / 16));
			Map<String, InventoryDomainCodec.Item> items = new LinkedHashMap<>();
			for (int i = 0; i < parts; i++) items.put("k%03d".formatted(i),
				new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "v" + i), 1, "stone", "{\"id\":\"minecraft:stone\"}", false));
			// Opaque fragments deliberately do not promise insertion order. Calibrate
			// against their actual traversal, not the caller's LinkedHashMap order.
			@Unmodifiable List<String> order = ((CaptureResult.OpaqueKeyedFragment) entries(1, items).payload().orElseThrow()).entries().keySet().stream().toList();
			String largeKey = order.get(index); var large = nativeAtFrameBytes(largeKey, index, parts, capacity + over);
			items.put(largeKey, large);
			var result = entries(1, items);
			assertEquals(order, ((CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow()).entries().keySet().stream().toList());
			try (var publisher = new InventorySyncPublisher(memory, transport)) {
				publisher.advance(0, settings); var context = context("preview/framing", 1);
				assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
				publish(publisher, context, result);
				@Unmodifiable List<InventoryS2CPacket> frames = drainPreview(publisher, transport, context, settings, 0);
				@Unmodifiable List<InventoryS2CPacket> data = frames.stream().filter(packet -> !packet.entries().isEmpty()).toList();
				assertEquals(parts - over, data.size());
				for (int part = 0; part < data.size(); part++) {
					var packet = data.get(part); assertEquals(part, packet.partIndex()); assertEquals(data.size(), packet.partCount());
					assertTrue(bytes(packet) <= capacity); assertEquals(bytes(packet), packet.encodedSize(publisher.effectiveDisplayBytes(), publisher.effectiveFrameBytes()));
					assertEquals(over == 0, packet.completeScan()); assertEquals(over == 0 ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.INCOMPLETE, packet.status());
				}
				@Unmodifiable List<InventoryS2CPacket.Entry> sentLarge = data.stream().flatMap(packet -> packet.entries().stream()).filter(entry -> entry.key().equals(largeKey)).toList();
				assertEquals(over == 0 ? 1 : 0, sentLarge.size());
				if (over == 0) assertEquals(large.displayJson(), sentLarge.getFirst().displayJson(), "admission never strips legitimate display metadata");
				else assertTrue(frames.stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INCOMPLETE));
				publisher.cancel(context); publisher.advance(1505, settings); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
			}
			assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	@ParameterizedTest @ValueSource(ints = {128, 256})
	void provisionalSinglePartCapacityCannotStrandRealMultipartOrCorrectiveReplay(int parts) {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		long allowance = settings.previewClientPeriodBytes(); int capacity = Math.toIntExact(allowance - Math.min(allowance, 128 + allowance / 16));
		Map<String, InventoryDomainCodec.Item> items = new LinkedHashMap<>();
		for (int i = 0; i < parts; i++) items.put("k%03d".formatted(i),
			new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "v" + i), 1, "stone", null, false));
		String key = ((CaptureResult.OpaqueKeyedFragment) entries(1, items).payload().orElseThrow()).entries().keySet().stream().toList().get(parts - 1);
		var large = nativeAtFrameBytes(key, 0, 1, capacity); items.put(key, large);
		assertTrue(bytes(previewFrame(key, large, parts - 1, parts)) > capacity);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/provisional", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			for (int replay = 0; replay < 2; replay++) {
				if (replay != 0) publisher.rebase(context, context.stateFence());
				publish(publisher, context, entries(replay + 1, items));
				@Unmodifiable List<InventoryS2CPacket> frames = drainPreview(publisher, transport, context, settings, replay * 1505);
				assertEquals(parts - 1, frames.stream().flatMap(packet -> packet.entries().stream()).count());
				assertTrue(frames.stream().allMatch(packet -> !packet.completeScan() && packet.status() == InventoryS2CPacket.Status.INCOMPLETE));
				assertTrue(frames.stream().flatMap(packet -> packet.entries().stream()).noneMatch(entry -> entry.key().equals(key)));
				assertTrue(frames.stream().filter(packet -> !packet.entries().isEmpty()).allMatch(packet -> packet.partCount() == parts - 1));
				assertEquals(0, memory.reserved());
			}
			publisher.cancel(context); publisher.advance(3010, settings); assertEquals(0, memory.retained());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@ParameterizedTest @ValueSource(ints = {128, 129, 256})
	void droppingAFrameRechecksLongerIncompleteStatusAndReindexesAcrossVarIntWidthChanges(int parts) {
		for (boolean cascadingDrop : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
			long allowance = settings.previewClientPeriodBytes(); int capacity = Math.toIntExact(allowance - Math.min(allowance, 128 + allowance / 16));
			Map<String, InventoryDomainCodec.Item> items = new LinkedHashMap<>();
			for (int i = 0; i < parts; i++) items.put("k%03d".formatted(i),
				new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "v" + i), 1, "stone", null, false));
			@Unmodifiable List<String> order = ((CaptureResult.OpaqueKeyedFragment) entries(1, items).payload().orElseThrow()).entries().keySet().stream().toList();
			String boundaryKey = order.get(cascadingDrop ? 0 : parts - 1), droppedKey = order.get(cascadingDrop ? parts - 1 : 0);
			var boundary = cascadingDrop ? nativeAtFrameBytes(boundaryKey, 0, parts, capacity)
				: nativeAtFrameBytes(boundaryKey, parts - 2, parts - 1, capacity, InventoryS2CPacket.Status.INCOMPLETE);
			items.put(boundaryKey, boundary); items.put(droppedKey, nativeAtFrameBytes(droppedKey, 0, 1, capacity + 20));
			try (var publisher = new InventorySyncPublisher(memory, transport)) {
				publisher.advance(0, settings); var context = context("preview/reindex", 1);
				publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null));
				publish(publisher, context, entries(1, items));
				@Unmodifiable List<InventoryS2CPacket> frames = drainPreview(publisher, transport, context, settings, 0);
				@Unmodifiable List<InventoryS2CPacket> data = frames.stream().filter(packet -> !packet.entries().isEmpty()).toList();
				assertEquals(parts - (cascadingDrop ? 2 : 1), data.size());
				for (int i = 0; i < data.size(); i++) {
					assertEquals(i, data.get(i).partIndex()); assertEquals(data.size(), data.get(i).partCount());
					assertTrue(bytes(data.get(i)) <= capacity); assertFalse(data.get(i).completeScan()); assertEquals(InventoryS2CPacket.Status.INCOMPLETE, data.get(i).status());
				}
				assertTrue(data.stream().flatMap(packet -> packet.entries().stream()).noneMatch(entry -> entry.key().equals(droppedKey)));
				assertEquals(cascadingDrop ? 0 : 1, data.stream().flatMap(packet -> packet.entries().stream()).filter(entry -> entry.key().equals(boundaryKey)).count());
				if (!cascadingDrop) assertEquals(capacity, bytes(data.stream().filter(packet -> packet.entries().getFirst().key().equals(boundaryKey)).findFirst().orElseThrow()));
				publisher.cancel(context); publisher.advance(1505, settings); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
			}
		}
	}
	@Test void cumulativeCompletionRequeuesUnchangedEntriesIncludingEarlierSparseKeysAndPaysWireAgain() throws Exception {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/cumulative", 1);
			publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null));
			var aggregate = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 7, "stone", null, true);
			var exact = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "plain"), 9, "dirt", null, false);
			var source = entries(1, Map.of("fold", aggregate, "exact", exact));
			var partial = new CaptureResult(source.payload(), source.coverage(), source.availability(), CaptureResult.Completeness.CONTINUE,
				source.consistency(), source.sourceVersion(), source.nextCursor());
			publish(publisher, context, partial); publisher.drain();
			assertFalse(publisher.pending(context)); int start = transport.sent.size();
			var before = ((Number) ((Map<?, ?>) InventoryBackendTest.field(publisher, "clientBytes")).get("preview/" + context.recipient())).longValue();
			var complete = entries(2, Map.of("fold", aggregate)); // omitted sparse exact key still belongs to the retained projection
			publisher.gateData(context.recipient()); publish(publisher, context, complete);
			assertTrue(publisher.pending(context)); assertTrue(((Number) InventoryBackendTest.field(publisher, "previewQueued")).longValue() > 0);
			publisher.drain(); assertEquals(start, transport.sent.size(), "no empty COMPLETE control may bypass gated cumulative data");
			publisher.releaseData(context.recipient()); publisher.drain();
			@Unmodifiable List<InventoryS2CPacket> frames = transport.sent.subList(start, transport.sent.size()).stream().toList();
			assertEquals(2, frames.size()); assertTrue(frames.stream().allMatch(packet -> packet.completeScan() && packet.partCount() == 2));
			assertEquals(Set.of("fold", "exact"), frames.stream().flatMap(packet -> packet.entries().stream()).map(InventoryS2CPacket.Entry::key).collect(java.util.stream.Collectors.toSet()));
			long after = ((Number) ((Map<?, ?>) InventoryBackendTest.field(publisher, "clientBytes")).get("preview/" + context.recipient())).longValue();
			assertEquals(frames.stream().mapToLong(InventorySyncPublisherTest::bytes).sum(), after - before);
			assertEquals(0L, InventoryBackendTest.field(publisher, "previewQueued")); assertFalse(publisher.pending(context));
			publisher.cancel(context); publisher.advance(5, settings); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	@ParameterizedTest @ValueSource(ints = {127, 128, 256})
	void finalCumulativeCutUsesItsOwnMultipartWidthEvenWhenEveryEntryIsUnchanged(int parts) {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		long allowance = settings.previewClientPeriodBytes(); int capacity = Math.toIntExact(allowance - Math.min(allowance, 128 + allowance / 16));
		Map<String, InventoryDomainCodec.Item> items = new LinkedHashMap<>();
		for (int i = 0; i < parts; i++) items.put("k%03d".formatted(i),
			new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "v" + i), 1, "stone", null, false));
		String boundaryKey = ((CaptureResult.OpaqueKeyedFragment) entries(1, items).payload().orElseThrow()).entries().keySet().stream().toList().get(parts - 1);
		items.put(boundaryKey, nativeAtFrameBytes(boundaryKey, 0, 1, capacity));
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/final-width", 1);
			publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null));
			publisher.gateData(context.recipient());
			// Earlier sparse offers retain their entries while delivery is gated. The
			// final cut must use cumulative framing even when sameValue suppresses all DTO changes.
			long watermark = 0;
			for (var entry : items.entrySet()) {
				var result = entries(++watermark, Map.of(entry.getKey(), entry.getValue()));
				publish(publisher, context, new CaptureResult(result.payload(), result.coverage(), result.availability(), CaptureResult.Completeness.CONTINUE,
					result.consistency(), result.sourceVersion(), result.nextCursor()));
				publisher.drain(); assertTrue(transport.sent.isEmpty());
			}
			publish(publisher, context, entries(++watermark, Map.of()));
			publisher.releaseData(context.recipient());
			@Unmodifiable List<InventoryS2CPacket> frames = drainPreview(publisher, transport, context, settings, (int) watermark * 5);
			int expected = parts == 127 ? parts : parts - 1;
			assertEquals(expected, frames.stream().flatMap(packet -> packet.entries().stream()).count());
			assertTrue(frames.stream().allMatch(packet -> packet.partCount() == expected && packet.completeScan() == (parts == 127)));
			assertTrue(frames.stream().allMatch(packet -> packet.status() == (parts == 127 ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.INCOMPLETE)));
			assertTrue(frames.stream().allMatch(packet -> bytes(packet) <= capacity));
			publisher.cancel(context); publisher.advance(5000, settings); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	@Test void controlOfferAndSmallDataFlowUnderOneMiBAndAllWorkspacesRelease() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(1024 * 1024);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); UUID recipient = new UUID(1, 1);
			assertEquals(4096, publisher.effectiveDisplayBytes()); assertEquals(32768, publisher.effectiveFrameBytes());
			assertTrue(publisher.control(recipient, InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 16)).stamp(100, 1)));
			publisher.drainControls();
			assertEquals(InventoryS2CPacket.Kind.OFFER, transport.sent.getFirst().kind());
			var context = context("tracking/1", 1);
			assertTrue(publisher.register(context, route(new MarkerId(1), "target")));
			publish(publisher, context, capture(1, 1, 2500)); publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && p.entries().size() == 1),
				"a default small cut still sends under the minimum finite pending memory");
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void retainedOverCapEntryIsFencedAndAnExactEffectiveCapEntryIsAccepted() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(0.25));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings);
			assertEquals(1024, publisher.effectiveDisplayBytes()); assertEquals(32768, publisher.effectiveFrameBytes());
			var context = context("tracking/1", 1);
			assertTrue(publisher.register(context, route(new MarkerId(1), "target")));
			publish(publisher, context, capture(1, 1, 4096));
			assertTrue(publisher.oversized(context), "a retained entry beyond the effective display cap is fenced");
			assertEquals(List.of("k0"), publisher.oversizedKeys(context));
			publisher.drain();
			assertTrue(transport.sent.stream().noneMatch(p -> !p.entries().isEmpty()), "an over-cap retained entry is never handed off");
			publisher.cancel(context);
			var replacement = context("tracking/1", 2);
			assertTrue(publisher.register(replacement, route(new MarkerId(1), "target")));
			publish(publisher, replacement, capture(1, 1, 1024));
			assertFalse(publisher.oversized(replacement));
			publisher.drain();
			var sent = transport.sent.stream().filter(p -> !p.entries().isEmpty()).findFirst().orElseThrow();
			assertEquals(1024, sent.entries().getFirst().displayJson().length());
			assertTrue(sent.encodedSize(publisher.effectiveDisplayBytes(), publisher.effectiveFrameBytes()) <= publisher.effectiveFrameBytes(),
				"an exact-cap frame is measured under the configured pair");
			publisher.cancel(replacement);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void measurementUsesTheAdvertisedEffectiveCapsForControlsAndData() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(2));
		settings.setFrameByteMultiplier(java.math.BigDecimal.valueOf(2));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings);
			assertEquals(8192, publisher.effectiveDisplayBytes()); assertEquals(65536, publisher.effectiveFrameBytes());
			UUID recipient = new UUID(1, 1);
			var advertised = publisher.offer(5, 3, 5, 16);
			assertEquals(8192, advertised.effectiveDisplayBytes()); assertEquals(65536, advertised.effectiveFrameBytes());
			assertTrue(publisher.control(recipient, InventoryS2CPacket.offer(7, advertised).stamp(100, 1)));
			publisher.drainControls();
			var context = context("tracking/1", 1);
			assertTrue(publisher.register(context, route(new MarkerId(1), "target")));
			publish(publisher, context, capture(1, 1, 2500)); publisher.drain();
			assertFalse(transport.measuredCaps.isEmpty());
			assertTrue(transport.measuredCaps.stream().allMatch(caps -> caps[0] == 8192 && caps[1] == 65536),
				"every control and data handoff is measured under the advertised pair");
			assertTrue(transport.sent.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.OFFER && p.offer().effectiveDisplayBytes() == 8192));
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void controlFramesAreNotHandedOffWhenTheyFailTheConfiguredMeasurement() {
		var settings = InventorySettings.serverDefaults(); var memory = new RetainedMemoryLedger(16000000);
		class StrictTransport extends Transport {
			boolean strict = true;
			@Override public int encodedBytes(InventoryS2CPacket packet, int displayBytes, int frameBytes) {
				if (strict && packet.kind() == InventoryS2CPacket.Kind.OFFER) throw new IllegalArgumentException("control frame exceeds the configured budget");
				return super.encodedBytes(packet, displayBytes, frameBytes);
			}
		}
		var transport = new StrictTransport();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); UUID recipient = new UUID(1, 1);
			assertTrue(publisher.control(recipient, InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 16)).stamp(100, 1)));
			publisher.drainControls();
			assertTrue(transport.sent.isEmpty(), "a control frame is subject to the same configured measurement");
			transport.strict = false; publisher.drainControls();
			assertEquals(InventoryS2CPacket.Kind.OFFER, transport.sent.getFirst().kind());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void failedOfferHandoffKeepsTheDataGateAndSuccessfulHandoffRunsTheCallbackOnce() {
		var settings = InventorySettings.serverDefaults(); var memory = new RetainedMemoryLedger(16000000);
		class GatedTransport extends Transport {
			boolean blockOffer;
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				if (blockOffer && packet.kind() == InventoryS2CPacket.Kind.OFFER) throw new IllegalStateException("offer seam backpressure");
				super.send(recipient, packet);
			}
		}
		var transport = new GatedTransport();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); UUID recipient = new UUID(1, 1);
			int[] handoffs = {0};
			publisher.setControlHandoff((player, packet) -> { handoffs[0]++; return true; });
			publisher.gateData(recipient);
			var context = context("preview/1", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			publish(publisher, context, capture(1, 1, 2500));
			publisher.drain();
			assertTrue(transport.sent.isEmpty(), "gated item data is never handed off");
			transport.blockOffer = true;
			assertTrue(publisher.control(recipient, InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 16)).stamp(100, 1)));
			publisher.drainControls();
			assertEquals(0, handoffs[0], "a failed transport handoff never invokes the callback");
			assertTrue(publisher.dataGated(recipient));
			publisher.drain();
			assertTrue(transport.sent.stream().noneMatch(p -> !p.entries().isEmpty()));
			transport.blockOffer = false; publisher.drainControls();
			assertEquals(1, handoffs[0]); assertFalse(publisher.dataGated(recipient));
			publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(p -> !p.entries().isEmpty()), "an authorized handoff releases the data gate");
			publisher.drain(); publisher.drainControls();
			assertEquals(1, handoffs[0], "the callback runs once per successful control handoff");
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void perFrameWorkspacesAreReleasedOnDeferFailureRetryAndCancel() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings);
			var context = context("preview/1", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			publish(publisher, context, capture(1, 1, 2500));
			memory.setCap(memory.retained()); publisher.drain();
			assertTrue(transport.sent.isEmpty(), "no handoff without workspace room");
			assertEquals(0, memory.reserved(), "a deferred frame releases its whole workspace");
			memory.setCap(16000000);
			transport.failNextSend = true; publisher.drain();
			assertEquals(0, memory.reserved(), "a throwing transport releases its workspace; the same immutable part retries");
			assertTrue(publisher.pending(context));
			publisher.advance(5, settings); publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(p -> !p.entries().isEmpty()));
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void throwingTransportRetainsOriginalReportAndChargesEachRetryWithoutChangingTheImmutablePacket() throws Exception {
		var settings = InventorySettings.serverDefaults(); var memory = new RetainedMemoryLedger(16000000);
		var failure = new ClassCastException("transport payload cannot be cast to test.Connection");
		class ThrowingTransport extends Transport {
			final List<InventoryS2CPacket> attempts = new ArrayList<>(); boolean failing = true;
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				attempts.add(packet); if (failing) throw failure; super.send(recipient, packet);
			}
		}
		var transport = new ThrowingTransport();
		try (var logs = new InventoryCaptureDiagnosticsTest.Capture(); var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/transport-diagnostic", 43);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 41, null, "target", null)));
			publish(publisher, context, capture(1, 1, 10));
			publisher.drain(); publisher.drain();
			assertEquals(2, transport.attempts.size()); assertSame(transport.attempts.getFirst(), transport.attempts.getLast());
			assertTrue(transport.sent.isEmpty()); assertTrue(publisher.pending(context)); assertEquals(0, memory.reserved());
			var charges = (Map<?, ?>) InventoryBackendTest.field(publisher, "clientBytes");
			assertEquals(2L * bytes(transport.attempts.getFirst()), charges.get("preview/" + context.recipient()), "a throwing handoff stays charged");
			logs.report("publisher/transport-send", Level.WARN, failure.getMessage(), "java.lang.ClassCastException",
				"at " + getClass().getName() + ".throwingTransportRetainsOriginalReport", "kind=PREVIEW", "request=41",
				"recipient=" + context.recipient(), "marker=null", "epoch=7", "baseline=43", "part=0/1");
			assertEquals(1, logs.events.size(), "the repeated same failure is rate-limited without suppressing the retry");
			transport.failing = false; publisher.advance(5, settings); publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(p -> !p.entries().isEmpty())); publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void largeAllowedFrameIsRefusedWhenTheIndependentFragmentBudgetIsTooSmall() {
		var settings = InventorySettings.serverDefaults();
		settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(0.25)));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings);
			var context = context("tracking/1", 1);
			assertTrue(publisher.register(context, route(new MarkerId(1), "target")));
			publish(publisher, context, capture(1, 1, 4096));
			publisher.drain();
			assertTrue(transport.sent.stream().noneMatch(p -> !p.entries().isEmpty()), "a frame beyond the independent fragment budget is not handed off");
			assertTrue(publisher.pending(context));
			settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.one());
			publisher.advance(3, settings); publisher.drain();
			assertTrue(transport.sent.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && !p.entries().isEmpty()),
				"the same retained cut sends once its independent budget allows");
			assertEquals("d".repeat(4096), transport.sent.stream().filter(p -> !p.entries().isEmpty()).findFirst().orElseThrow()
				.entries().getFirst().displayJson(), "period/fragment pressure queues the already projected payload, never strips it");
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void withdrawDropsAQueuedCutAndControlPendingTracksTheHandoff() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings);
			var context = context("preview/1", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			publish(publisher, context, capture(1, 1, 2500));
			assertTrue(publisher.controlPending(context), "a published preview control waits for its handoff");
			publisher.drain();
			assertFalse(publisher.controlPending(context), "the handoff clears the pending control");
			assertTrue(transport.sent.stream().anyMatch(p -> !p.entries().isEmpty()));
			publish(publisher, context, capture(2, 1, 2501));
			assertTrue(publisher.pending(context));
			int before = transport.sent.size();
			publisher.withdraw(context);
			assertFalse(publisher.pending(context), "withdraw refunds the queued cut");
			publisher.drain();
			assertTrue(transport.sent.subList(before, transport.sent.size()).stream().noneMatch(p -> !p.entries().isEmpty()),
				"a withdrawn cut is never handed off");
			assertTrue(transport.sent.subList(before, transport.sent.size()).stream().noneMatch(InventoryS2CPacket::completeScan),
				"a withdrawn cumulative barrier cannot be replaced by an empty completion control");
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void deliveredVersionStaysChargedWhileItsReplacementIsGatedAndWithdrawn() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1);
			assertTrue(publisher.register(context, route(new MarkerId(1), "target")));
			publish(publisher, context, capture(1, 1, 2500)); publisher.drain();
			long delivered = memory.retained();
			publisher.gateData(context.recipient());
			publish(publisher, context, capture(2, 1, 10));
			assertTrue(memory.retained() > delivered, "both the old delivered JSON and the new offered entry are retained");
			long both = memory.retained();
			publisher.drain(); publisher.withdraw(context);
			assertEquals(both, memory.retained(), "withdraw removes queued work, not either retained version");
			publish(publisher, context, capture(3, 1, 11));
			assertEquals(both + 3, memory.retained(), "an unreferenced intermediate offer releases its ticket");
			publisher.releaseData(context.recipient()); publisher.advance(3, settings); publisher.drain();
			assertTrue(memory.retained() < delivered, "the old delivered version releases only after handoff commits");
			long latest = memory.retained();
			for (int revision = 4; revision < 24; revision++) {
				publish(publisher, context, capture(revision, 1, revision % 2 == 0 ? 10 : 11));
				publisher.advance(revision * 3L, settings); publisher.drain();
			}
			assertEquals(latest, memory.retained(), "historical versions cannot accumulate tickets");
			publisher.rebase(context, 2);
			assertEquals(registrationCost() + 1024, memory.retained(), "rebase releases every entry version and retains the prepaid state slab and current wire scope");
			publisher.cancel(new SyncPublisher.Context(context.consumerId(), context.recipient(), context.sessionView(), 2, context.baseline()));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void groupReplacementKeepsDeliveredVariantsChargedUntilTheGroupCommits() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/1", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			publish(publisher, context, capture(1, 2, 100)); publisher.drain();
			long variants = memory.retained(); publisher.gateData(context.recipient());
			var item = InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 1, "stone", null, true));
			var result = new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("fold", item))),
				new CaptureResult.Coverage("inventory", 2, 2, OptionalLong.of(2)), CaptureResult.Availability.READABLE,
				CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
			publish(publisher, context, result);
			assertTrue(memory.retained() > variants, "removing offered variants cannot release still-delivered variants");
			publisher.releaseData(context.recipient()); publisher.advance(5, settings); publisher.drain();
			assertTrue(memory.retained() < variants, "a committed replacement retires the old delivered group");
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void admittedVersionsRemainChargedUntilWithdrawAndOfferedVersionsSurvive() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); publisher.register(context, route(new MarkerId(1), "target"));
			publish(publisher, context, capture(1, 3, 2500)); publisher.drain();
			long admitted = memory.retained();
			publish(publisher, context, capture(2, 3, 10));
			assertTrue(memory.retained() > admitted, "immutable admitted parts still own the old entries");
			publisher.withdraw(context);
			assertTrue(memory.retained() < admitted, "withdraw releases admitted-only versions, not latest offers");
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void tinyControlRefusesInsufficientEncodingMemoryBeforeMeasurement() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var recipient = context("control", 1).recipient();
			assertTrue(publisher.control(recipient, InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 0)).stamp(100, 1)));
			memory.setCap(memory.retained() + 128); publisher.drainControls();
			assertTrue(transport.measuredCaps.isEmpty(), "128 bytes cannot admit the actual encoding workspace, even for OFFER");
			assertTrue(transport.sent.isEmpty()); assertEquals(0, memory.reserved());
			memory.setCap(1024 * 1024); publisher.drainControls(); assertEquals(1, transport.sent.size());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void refusedOfferReplacementWithdrawsTheOldOfferAndExactCancellationCannotRemoveANewerResult() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var recipient = context("control", 1).recipient(); publisher.gateData(recipient);
			var old = InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 0)).stamp(100, 1);
			var latest = InventoryS2CPacket.offer(7, publisher.offer(5, 9, 5, 0)).stamp(100, 1);
			assertTrue(publisher.control(recipient, old));
			memory.setCap(0); assertFalse(publisher.control(recipient, latest));
			assertEquals(0, memory.retained(), "a refused refresh leaves no superseded queued OFFER");
			memory.setCap(16000000); publisher.drainControls();
			assertTrue(transport.sent.isEmpty()); assertTrue(publisher.dataGated(recipient));
			assertTrue(publisher.control(recipient, latest)); publisher.drainControls();
			assertEquals(List.of(latest), transport.sent);
			var retired = InventoryS2CPacket.selected(7, 1, 1, null, nx.pingwheel.common.marker.MarkerRejectReason.INVALID_REQUEST).stamp(100, 1);
			var replacement = retired.stamp(100, 2);
			assertTrue(publisher.control(recipient, retired)); assertTrue(publisher.control(recipient, replacement));
			publisher.cancelControl(recipient, retired); publisher.drainControls();
			assertEquals(List.of(latest, replacement), transport.sent, "cancellation uses the exact bounded result owner");
			assertTrue(publisher.control(recipient, retired)); publisher.cancelControl(recipient, retired); publisher.drainControls();
			assertEquals(2, transport.sent.size()); assertEquals(0, memory.reserved());
		}
		assertEquals(0, memory.retained());
	}
	@Test void reentrantControlCancellationAndCloseDoNotSendCancelledWork() {
		for (boolean close : List.of(false, true)) {
			var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
			try (var publisher = new InventorySyncPublisher(memory, transport)) {
				publisher.advance(0, settings); var recipient = context("control", 1).recipient();
				assertTrue(publisher.control(recipient, InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 0)).stamp(100, 1)));
				assertTrue(publisher.control(recipient, InventoryS2CPacket.policy(7, 100, 1, Set.of("block"))));
				int[] callbacks = {0};
				publisher.setControlHandoff((player, packet) -> {
					callbacks[0]++; if (close) publisher.close(); else publisher.cancelControls(player); return false;
				});
				assertDoesNotThrow(publisher::drainControls);
				assertEquals(1, transport.sent.size()); assertEquals(1, callbacks[0]);
				assertEquals(close ? 0 : 1024, memory.retained(), "accepted/cancelled controls refund exactly once");
			}
			assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	@Test void callbackAddedControlsWaitForNextDrainAndCallbackFailureDoesNotRetryAcceptedWork() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var logs = new InventoryCaptureDiagnosticsTest.Capture(); var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var recipient = context("control", 1).recipient(); publisher.gateData(recipient);
			var offer = InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 0)).stamp(100, 1);
			var policy = InventoryS2CPacket.policy(7, 100, 1, Set.of("block"));
			assertTrue(publisher.control(recipient, offer)); assertTrue(publisher.control(recipient, policy));
			int[] callbacks = {0};
			publisher.setControlHandoff((player, packet) -> {
				callbacks[0]++; if (packet.kind() == InventoryS2CPacket.Kind.OFFER) assertTrue(publisher.control(player, offer));
				throw new IllegalStateException("callback failed after transport accepted");
			});
			assertDoesNotThrow(publisher::drainControls);
			assertEquals(List.of(offer, policy), transport.sent, "new callback work cannot extend the current drain");
			assertEquals(2, callbacks[0]); assertTrue(publisher.dataGated(recipient));
			var failures = logs.events.stream().filter(e -> e.getMessage().getFormattedMessage().contains("operation=inventory/publisher/control-handoff ")).toList();
			assertEquals(2, failures.size(), "OFFER and POLICY are independently scoped failures");
			for (var event : failures) {
				assertEquals(Level.WARN, event.getLevel()); var message = event.getMessage().getFormattedMessage();
				assertTrue(message.contains("callback failed after transport accepted")); assertTrue(message.contains("recipient=" + recipient));
				assertTrue(message.contains("at " + getClass().getName() + ".lambda$callbackAddedControls"));
			}
			publisher.setControlHandoff((player, packet) -> true); publisher.drainControls();
			assertEquals(List.of(offer, policy, offer), transport.sent); assertFalse(publisher.dataGated(recipient));
			publisher.drainControls(); assertEquals(3, transport.sent.size());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void callbackReplacedControlsAndRecursiveDrainsDeferNewWork() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var recipient = context("control", 1).recipient();
			var offer = InventoryS2CPacket.offer(7, publisher.offer(5, 3, 5, 0)).stamp(100, 1);
			var policy = InventoryS2CPacket.policy(7, 100, 1, Set.of("block"));
			var replacement = InventoryS2CPacket.policy(7, 100, 2, Set.of("entity"));
			assertTrue(publisher.control(recipient, offer)); assertTrue(publisher.control(recipient, policy));
			publisher.setControlHandoff((player, packet) -> {
				assertEquals(1024 + 4096, memory.retained(), "accepted control was refunded before callback");
				assertTrue(publisher.control(player, replacement)); publisher.drainControls(); return false;
			});
			publisher.drainControls(); assertEquals(List.of(offer), transport.sent, "replaced/callback-added controls wait even under recursion");
			publisher.setControlHandoff(null); publisher.drainControls();
			assertEquals(List.of(offer, replacement), transport.sent); assertEquals(1024, memory.retained());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void admittedGroupRetirementRequiresFreshTrackingBaselineAndReleasesAdmittedOnlyVersions() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25)));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(16000000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); publisher.register(context, route(new MarkerId(1), "target"));
			publish(publisher, context, capture(1, 3, 2500)); publisher.drain();
			long variants = memory.retained();
			assertTrue(publisher.retireGroup(context, "minecraft:stone"));
			assertTrue(memory.retained() < variants, "retirement cancels admitted-only versions immediately");
			long retired = memory.retained();
			var item = InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 3, "stone", null, true));
			var result = new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("fold", item))),
				new CaptureResult.Coverage("inventory", 2, 3, OptionalLong.of(3)), CaptureResult.Availability.READABLE,
				CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, publisher.publish(result,
				new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("fold")), context,
				new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
			assertEquals(retired, memory.retained(), "a stranded tracking cut must be fenced by the Backend before replacement");
			publisher.withdraw(context);
			assertFalse(publisher.pending(context)); publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}

	static CaptureResult entries(long watermark, Map<String, InventoryDomainCodec.Item> items) {
		Map<String, CaptureResult.OpaqueValue> payload = new LinkedHashMap<>();
		items.forEach((key, item) -> payload.put(key, InventoryDomainCodec.encode(item)));
		return new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, payload)),
			new CaptureResult.Coverage("inventory", watermark, items.size(), OptionalLong.of(items.size())), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.VERIFIED, Optional.empty(), Optional.empty());
	}
	static long registrationCost() {
		var memory = new RetainedMemoryLedger(16_000_000);
		try (var publisher = new InventorySyncPublisher(memory, new Transport())) {
			assertTrue(publisher.register(context("measure", 1), route(null, "measure")));
			return memory.retained();
		}
	}

	@Test void retirementSlabIsPaidAtRegistrationAndCannotGrowBeyondItsAdmittedOwner() {
		var settings = InventorySettings.serverDefaults(); var memory = new RetainedMemoryLedger(16_000_000); var transport = new Transport();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/metadata", 1);
			assertTrue(publisher.register(context, route(null, "preview"))); long owner = memory.retained();
			assertTrue(owner < settings.pendingMemoryBytes(), "finite metadata leaves default room for real item work");
			memory.setCap(owner);
			// Request-lifetime retirement allowance, independent of 128-row wire frames.
			for (int i = 0; i < 256; i++) assertFalse(publisher.retireGroup(context, "test:item_" + i));
			assertEquals(owner, memory.retained(), "all retirement indexes were paid BEFORE the cap became full");
			assertEquals(0, memory.reserved());
			assertThrows(IllegalStateException.class, () -> publisher.retireGroup(context, "test:beyond_lifetime_bound"));
			publisher.status(context, InventoryS2CPacket.Status.READY); publisher.drain();
			assertTrue(transport.sent.isEmpty(), "unreplaced retirement cannot become a completed digest");
			publisher.cancel(context); assertEquals(0, memory.retained());
		}
	}

	@Test void restageAdmissionPaysForPeakOverlapAndRefusedPersistentTicketsLeaveLatestOffersRetryable() throws Exception {
		var settings = InventorySettings.serverDefaults(); var memory = new RetainedMemoryLedger(16_000_000); var transport = new Transport();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("preview/overlap", 1);
			assertTrue(publisher.register(context, route(null, "preview")));
			var stone = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "exact"), 5, "stone", null, false);
			var dirt = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "exact"), 1, "dirt", null, false);
			publish(publisher, context, entries(1, Map.of("stone", stone, "dirt", dirt))); publisher.drain();
			var latest = new InventoryDomainCodec.Item(dirt.key(), 99, "dirt", null, false);
			publish(publisher, context, entries(2, Map.of("dirt", latest)));
			transport.failNextSend = true; publisher.drain(); // immutable latest dirt cut remains admitted
			assertFalse(publisher.retireGroup(context, "minecraft:stone"));
			var statesField = InventorySyncPublisher.class.getDeclaredField("states"); statesField.setAccessible(true);
			Object state = ((Map<?, ?>) statesField.get(publisher)).values().iterator().next();
			var entryField = state.getClass().getDeclaredField("entryMemory"); entryField.setAccessible(true);
			var ownership = (Map<?, ?>) entryField.get(state); int versions = ownership.size();
			long held = memory.retained(); int start = transport.sent.size();
			var aggregate = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 12, "stone", null, true);
			var replacement = entries(3, Map.of("aggregate", aggregate));
			// Admit decoding/index workspace but leave no room for persistent entry
			// tickets. Invoke the model helper, rather than copying its coefficients.
			var workspace = InventorySyncPublisher.class.getDeclaredMethod("publicationWorkspace", state.getClass(), CaptureResult.OpaqueKeyedFragment.class); workspace.setAccessible(true);
			long temporary = (long) workspace.invoke(null, state, replacement.payload().orElseThrow());
			for (int retry = 0; retry < 2; retry++) {
				memory.setCap(held + temporary);
				assertInstanceOf(SyncPublisher.Outcome.Deferred.class, publisher.publish(replacement,
					new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("aggregate")), context,
					new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
				assertEquals(versions, ownership.size()); assertEquals(held, memory.retained()); assertEquals(0, memory.reserved());
			}
			memory.setCap(16_000_000); publish(publisher, context, replacement);
			assertTrue(memory.retained() > held, "old delivered versions and accepted replacements overlap");
			memory.setCap(16_000_000); publisher.advance(5, settings); publisher.drain();
			var sent = transport.sent.subList(start, transport.sent.size()).stream().flatMap(packet -> packet.entries().stream()).toList();
			assertTrue(sent.stream().anyMatch(entry -> entry.itemId().equals("minecraft:dirt") && entry.count() == 99));
			assertTrue(sent.stream().anyMatch(entry -> entry.fallback() && entry.count() == 12));
			assertTrue(sent.stream().noneMatch(entry -> entry.itemId().equals("minecraft:stone") && !entry.fallback()));
			publisher.cancel(context);
			assertTrue(((Map<?, ?>) statesField.get(publisher)).isEmpty(), "cancel releases the context");
			assertTrue(ownership.isEmpty(), "cancel releases retained entry versions");
			var batchField = state.getClass().getDeclaredField("batchMemory"); batchField.setAccessible(true);
			assertNull(batchField.get(state), "cancel releases the immutable batch owner");
			assertEquals(1024, memory.retained(), "recipient wire accounting survives context cancellation for its period");
			assertEquals(0, memory.reserved());
			publisher.advance(10, settings);
			assertEquals(0, memory.retained(), "the next preview accounting period releases its wire subject");
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}

	@Test void incompleteControlDoesNotClearRetirementOrAuthorizeOldExactAndCompletionTraffic() {
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); publisher.register(context, route(new MarkerId(1), "target"));
			publish(publisher, context, capture(1, 2, 10)); transport.failNextSend = true; publisher.drain();
			assertTrue(publisher.retireGroup(context, "minecraft:stone"));
			publisher.status(context, InventoryS2CPacket.Status.INCOMPLETE); int start = transport.sent.size(); publisher.advance(3, settings); publisher.drain();
			var failure = transport.sent.subList(start, transport.sent.size()); assertEquals(2, failure.size(), "initial fence must not consume the waiting INCOMPLETE status");
			assertEquals(InventoryS2CPacket.Status.UPDATING, failure.getFirst().status()); assertEquals(InventoryS2CPacket.Status.INCOMPLETE, failure.getLast().status());
			assertTrue(failure.stream().allMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.entries().isEmpty() && packet.watermark() == 0 && packet.checksum() == 0));
			assertTrue(publisher.retireGroup(context, "minecraft:stone"), "failure control does not clear the required fresh baseline");
			publisher.status(context, InventoryS2CPacket.Status.READY); start = transport.sent.size(); publisher.advance(6, settings); publisher.drain();
			assertEquals(start, transport.sent.size(), "success control and heartbeats remain fenced until valid replacement lifecycle");
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}

	@Test void retirementFencesPendingAndAdmittedExactBeforeReplacementMemoryAdmissionAndRestagesLatestUnrelatedValues() {
		for (boolean admitted : List.of(false, true)) {
			var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
			try (var publisher = new InventorySyncPublisher(memory, transport)) {
				publisher.advance(0, settings); var context = context("preview/1", 1);
				publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "preview", null));
				var stone = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "exact"), 5, "stone", null, false);
				var dirt = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "exact"), 1, "dirt", null, false);
				publish(publisher, context, entries(1, Map.of("stone", stone, "dirt", dirt)));
				if (admitted) { transport.failNextSend = true; publisher.drain(); }
				var latest = new InventoryDomainCodec.Item(dirt.key(), 99, "dirt", null, false);
				publish(publisher, context, entries(2, Map.of("dirt", latest)));
				assertFalse(publisher.retireGroup(context, "minecraft:stone"));
				int start = transport.sent.size();
				var aggregate = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 12, "stone", null, true);
				var replacement = entries(3, Map.of("aggregate", aggregate));
				memory.setCap(memory.retained());
				assertInstanceOf(SyncPublisher.Outcome.Deferred.class, publisher.publish(replacement,
					new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("aggregate")), context,
					new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
				memory.setCap(16_000_000); publisher.advance(5, settings); publisher.drain();
				assertEquals(start, transport.sent.size(), "retirement alone cannot send the obsolete cut or its completion");
				publish(publisher, context, replacement); publisher.drain();
				var sent = transport.sent.subList(start, transport.sent.size());
				assertTrue(sent.stream().flatMap(packet -> packet.entries().stream()).noneMatch(entry -> entry.itemId().equals("minecraft:stone") && !entry.fallback()));
				assertTrue(sent.stream().flatMap(packet -> packet.entries().stream()).anyMatch(entry -> entry.itemId().equals("minecraft:dirt") && entry.count() == 99));
				assertTrue(sent.stream().flatMap(packet -> packet.entries().stream()).anyMatch(entry -> entry.fallback() && entry.count() == 12));
			}
			assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	@Test void largeDataWorkspaceRefusesBeforeHostEncodingAndRetriesWithoutChangingItsDisplay() {
		var settings = InventorySettings.serverDefaults(); settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(2));
		settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(2)));
		var transport = new Transport(); var memory = new RetainedMemoryLedger(1024 * 1024);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = context("tracking/1", 1); publisher.register(context, route(new MarkerId(1), "target"));
			publish(publisher, context, capture(1, 1, 8192));
			long paddingBytes = memory.remaining() - 12000;
			var padding = memory.tryReserve(paddingBytes).orElseThrow(); padding.commit(paddingBytes);
			publisher.drain();
			assertTrue(transport.sent.stream().allMatch(p -> p.entries().isEmpty()));
			assertEquals(1, transport.measuredCaps.size(), "only the small fence reached measurement, never data");
			assertEquals(0, memory.reserved()); padding.close();
			publisher.advance(3, settings); publisher.drain();
			assertEquals(8192, transport.sent.stream().filter(p -> !p.entries().isEmpty()).findFirst().orElseThrow().entries().getFirst().displayJson().length());
			publisher.cancel(context);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
}
