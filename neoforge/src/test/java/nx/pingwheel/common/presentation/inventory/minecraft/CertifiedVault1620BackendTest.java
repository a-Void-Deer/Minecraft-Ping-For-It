package nx.pingwheel.common.presentation.inventory.minecraft;

import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.*;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static nx.pingwheel.common.presentation.inventory.minecraft.CertifiedVault1620Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sequential, not simultaneous tuned admission. Real Backend/Publisher/C2S/S2C/ClientInventory. */
@NotNullByDefault
class CertifiedVault1620BackendTest {
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }
	@NotNullByDefault
	static final class Fixture implements InventoryBackend.Host, AutoCloseable {
		final World world = new World(Contents.MIXED);
		final InventorySettings settings;
		final InventoryRuntime runtime;
		final InventoryBackend backend;
		final ServerMarkerStore markers = new ServerMarkerStore(new MarkerIdSource());
		final ClientInventory client = new ClientInventory(packet -> ingress((InventoryC2SPacket) packet));
		long tick, visits, frames, measurements, completeFrames;
		boolean rejectAfterAdmission;
		boolean deferJoin;
		@Nullable InventoryC2SPacket lastSelection;
		Fixture() { this(Profile.INCIDENT); }
		Fixture(Profile profile) {
			settings = profile.settings();
			runtime = new InventoryRuntime(world, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
			backend = new InventoryBackend(this, runtime, 1);
			client.presentationReset(100, 1); ingress(InventoryC2SPacket.hello()); assertTrue(client.ready());
		}
		void ingress(InventoryC2SPacket packet) {
			if (packet.kind() == InventoryC2SPacket.Kind.SELECT) lastSelection = packet;
			var buffer = new FriendlyByteBuf(Unpooled.buffer());
			try { packet.write(buffer); var decoded = InventoryC2SPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); backend.handle(OWNER, decoded, tick, settings); }
			finally { buffer.release(); }
		}
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) {
			if (deferJoin) {
				var ledger = (nx.pingwheel.common.presentation.source.CostLedger) java.util.Objects.requireNonNull(get(runtime, "physical"));
				var work = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
				var rest = Map.of(work, ledger.remaining(work)); ledger.tryReserve(rest).orElseThrow().commit(rest);
			}
			return pingType.equals("danger");
		}
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
			var group = new TargetKey.VaultKey("minecraft:overworld", 0, 0, 0, "create:item_vault");
			@Nullable var rejection = admission.prepare(frozen.target(), group, type.id(), List.of(OWNER));
			if (rejection != null) return new InventoryBackend.Created(null, rejection);
			if (rejectAfterAdmission) return new InventoryBackend.Created(null, MarkerRejectReason.INVALID_REQUEST);
			var hit = (Target.BlockTarget) frozen.target();
			return new InventoryBackend.Created(markers.create(player, hit, group, type, type.defaultPingType(),
				new MarkerAnchor(hit.x() + .5, hit.y() + .5, hit.z() + .5), tick, tick + 1000, List.of(OWNER)).marker(), null);
		}
		@Override public void publishCreated(InventoryBackend.Created created) { client.markerCreated(MarkerSnapshot.from(created.marker())); }
		@Override public boolean knows(UUID player, MarkerId marker) { return markers.find(marker).isPresent(); }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { throw new AssertionError("effective budgets required"); }
		@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) { measurements++; return packet.encodedSize(display, frame); }
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			var buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				packet.write(buffer, (int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes());
				var decoded = InventoryS2CPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); frames++;
				if (decoded.completeScan()) completeFrames++; client.accept(decoded);
			} finally { buffer.release(); }
		}
		void next() {
			backend.tick(++tick, settings); visits += physical(runtime);
			assertTrue(physical(runtime) <= settings.effectivePhysicalSlotsPerTick()); assertEquals(0, runtime.memory().reserved());
		}
		long open(BlockPos hit, BlockFace face) { long request = client.open(target(hit), face, "entity_block"); assertTrue(request > 0); return request; }
		void awaitRow(long request, String item) {
			for (int tries = 0; tries < 180 && client.preview(request).entries().stream().noneMatch(e -> e.itemId().equals(item)); tries++) next();
			assertTrue(client.preview(request).entries().stream().anyMatch(e -> e.itemId().equals(item)), "preview row not serviceable at 16 MiB and unchanged logical caps");
		}
		MarkerId select(long request, String item) {
			// Two stone patches exist. Select the undecorated one explicitly; UI order is not an identity oracle.
			var row = client.preview(request).entries().stream().filter(e -> e.itemId().equals(item)
				&& (e.displayJson() == null || !e.displayJson().contains("custom_name"))).findFirst().orElseThrow();
			var reference = client.selectable(request).stream().filter(r -> r.entryKey().equals(row.key())).findFirst().orElseThrow();
			long before = physical(runtime);
			assertEquals(ClientInventory.DispatchOutcome.SENT, client.select(reference, "danger", packet -> { ingress(packet); return ClientInventory.DispatchOutcome.SENT; }));
			assertEquals(before, physical(runtime), "selection validates metadata, never rechecks item presence");
			assertEquals(request, markers.size()); return markers.allMarkers().getLast().id();
		}
		@SuppressWarnings("unchecked") @UnmodifiableView Map<MarkerId, ?> leases() { return (Map<MarkerId, ?>) java.util.Objects.requireNonNull(get(backend, "tracking")); }
		InventoryRuntime.Consumer consumer(MarkerId marker) { return (InventoryRuntime.Consumer) java.util.Objects.requireNonNull(get(java.util.Objects.requireNonNull(leases().get(marker)), "consumer")); }
		long revision(MarkerId marker) { return (Long) java.util.Objects.requireNonNull(get(java.util.Objects.requireNonNull(leases().get(marker)), "revision")); }
		InventoryRuntime.Consumer previewConsumer(long request) {
			var session = java.util.Objects.requireNonNull(((Map<?, ?>) get(backend, "sessions")).get(OWNER));
			var preview = java.util.Objects.requireNonNull(((Map<?, ?>) get(session, "previews")).get(request));
			return (InventoryRuntime.Consumer) java.util.Objects.requireNonNull(get(preview, "consumer"));
		}
		void stage(String label) {
			var record = new LinkedHashMap<String, Object>(); record.put("kind", "sequential"); record.put("stage", label); record.put("tick", tick);
			record.put("physicalVisits", visits); record.put("frames", frames); record.put("completeFrames", completeFrames); record.put("retained", runtime.memory().retained());
			var observations = new java.util.ArrayList<Map<String, @Nullable Object>>();
			for (var marker : markers.allMarkers()) {
				var consumer = consumer(marker.id()); @Nullable var round = get(consumer, "round");
				var values = new LinkedHashMap<String, @Nullable Object>(); values.put("marker", marker.id().value()); values.put("target", marker.target()); values.put("group", marker.targetKey());
				values.put("cursor", get(consumer, "cursor")); values.put("revision", revision(marker.id())); values.put("client", client.tracking(marker.id()));
				if (round != null) {
					values.put("sequence", get(round, "sequence")); values.put("startedTick", get(round, "startedTick"));
					@Nullable var sweep = get(round, "sweep"); values.put("pooled", sweep != null);
					if (sweep != null) { var proof = (InventorySharedRead.Proof) java.util.Objects.requireNonNull(get(sweep, "proof")); values.put("graphHashDiagnosticOnly", proof.graph().hashCode()); values.put("certifiedSlots", proof.graph().slots()); }
				}
				observations.add(values);
			} record.put("observations", observations); CertifiedVault1620BaselineTest.emit(record);
		}
		@Override public void close() { backend.close(); assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained()); }
	}
	static long expected(String item) {
		long sum = 0;
		for (int address = 0; address < SLOTS; address++) if (item.equals(address % 3 == 2 ? "minecraft:dirt" : "minecraft:stone") && address % 3 != 1) sum += 3_000_003L + address;
		return sum;
	}
	@ParameterizedTest @CsvSource({"INCIDENT,false,false,false", "INCIDENT,true,false,false", "INCIDENT,true,true,false", "INCIDENT,true,true,true", "DEFAULTS,true,false,false"})
	void sequentialDifferentItemsPreserveIndependentCountsLifetimesAndFourReplacementCycles(Profile profile, boolean differentMember, boolean completeFirstPreview, boolean completeFirstTracking) {
		try (var f = new Fixture(profile)) {
			long firstRequest = f.open(BlockPos.ZERO, BlockFace.NORTH); f.awaitRow(firstRequest, "minecraft:stone");
			if (completeFirstPreview) { for (int i = 0; i < 180 && !f.client.preview(firstRequest).completeScan(); i++) f.next(); assertTrue(f.client.preview(firstRequest).completeScan()); }
			else assertFalse(f.client.preview(firstRequest).completeScan());
			Object previewRound = java.util.Objects.requireNonNull(get(f.previewConsumer(firstRequest), "round"));
			MarkerId first = f.select(firstRequest, "minecraft:stone");
			if (!completeFirstPreview) assertSame(previewRound, get(f.consumer(first), "round"));
			else assertNull(get(f.consumer(first), "round"), "completed Preview SELECT must start fresh, not pin a completed journal");
			f.client.close(firstRequest);
			for (int i = 0; i < 8 && get(f.consumer(first), "round") == null; i++) f.next();
			if (completeFirstTracking) {
				for (int i = 0; i < 120 && !f.client.tracking(first).complete(); i++) f.next();
				assertTrue(f.client.tracking(first).complete(), "completed-round control must start with usable client evidence");
			}
			if (!completeFirstTracking) f.next(); // actual partial Tracking progress before the second OPEN
			f.stage("first-selected-tracking-started");
			long secondRequest = f.open(differentMember ? new BlockPos(8, 2, 2) : BlockPos.ZERO, BlockFace.SOUTH);
			f.awaitRow(secondRequest, "minecraft:dirt"); f.stage("second-preview-visible");
			if (!completeFirstPreview) assertSame(get(f.consumer(first), "round"), get(f.previewConsumer(secondRequest), "round"));
			MarkerId second = f.select(secondRequest, "minecraft:dirt"); f.client.close(secondRequest);
			f.stage("two-selections");
			var firstRound = java.util.Objects.requireNonNull(get(f.consumer(first), "round"));
			var secondRound = java.util.Objects.requireNonNull(get(f.consumer(second), "round"));
			var firstSweep = java.util.Objects.requireNonNull(get(firstRound, "sweep"));
			var secondSweep = java.util.Objects.requireNonNull(get(secondRound, "sweep"));
			assertEquals(get(firstSweep, "proof"), get(secondSweep, "proof"), "independently resolved exact bindings must prove the same physical graph");
			if (!completeFirstPreview) assertSame(firstRound, secondRound, "SELECT pins its own association before normal Preview CLOSE");
			CertifiedVault1620BaselineTest.emit(Map.of("kind", "sequentialReuse", "profile", profile.name(), "tick", f.tick, "sameRound", firstRound == secondRound,
				"firstSequence", java.util.Objects.requireNonNull(get(firstRound, "sequence")), "secondSequence", java.util.Objects.requireNonNull(get(secondRound, "sequence")),
				"note", "new association asserted at successful partial SELECT; completed observations never form a new entitlement"));
			f.next(); f.stage("second-tracking-started");
			long firstExpiry = f.markers.find(first).orElseThrow().expiresAtTick();
			long firstRevision = 0, secondRevision = 0; int firstCuts = 0, secondCuts = 0;
			boolean initialPhysicalAsserted = false;
			for (int step = 0; step < 800 && (firstCuts < 5 || secondCuts < 5); step++) {
				f.next();
				if (!completeFirstPreview && !initialPhysicalAsserted && get(firstRound, "last") instanceof nx.pingwheel.common.presentation.source.CaptureResult cut
					&& cut.completeness() == nx.pingwheel.common.presentation.source.CaptureResult.Completeness.COMPLETE) {
					assertEquals(SLOTS, f.visits, "the entire initial shared round reads 1620 native addresses, not two sweeps");
					initialPhysicalAsserted = true;
				}
				for (var id : List.of(first, second)) {
					var projection = f.client.tracking(id);
					if (!projection.complete()) continue;
					assertFalse(projection.grey()); assertEquals(InventoryS2CPacket.Status.READY, projection.status()); assertEquals(1, projection.entries().size());
					String item = id.equals(first) ? "minecraft:stone" : "minecraft:dirt";
					assertEquals(item, projection.entries().getFirst().itemId()); assertEquals(expected(item), projection.entries().getFirst().count());
				}
				long a = f.revision(first), b = f.revision(second);
				// Count actual backend-completed observations, not heartbeat frames or repeated client projections.
				if (Boolean.FALSE.equals(get(f.leases().get(first), "scanning")) && a != firstRevision) { firstRevision = a; firstCuts++; }
				if (Boolean.FALSE.equals(get(f.leases().get(second), "scanning")) && b != secondRevision) { secondRevision = b; secondCuts++; }
			}
			f.stage("four-replacements"); assertTrue(firstCuts >= 5 && secondCuts >= 5, "must finish initial and four replacements before 1000-tick expiry");
			if (!completeFirstPreview) assertTrue(initialPhysicalAsserted);
			assertTrue(f.tick < firstExpiry); assertEquals(firstExpiry, f.markers.find(first).orElseThrow().expiresAtTick());
			assertEquals(2, f.markers.size()); assertEquals(f.markers.find(first).orElseThrow().targetKey(), f.markers.find(second).orElseThrow().targetKey());
			assertEquals(0, f.client.stats().invalidPackets()); assertTrue(f.measurements > 0);
		}
	}
	@ParameterizedTest @CsvSource({"INCIDENT", "DEFAULTS"})
	void preAttachmentReadSlotKeepsOldAmountWhileUnreadSlotContributesNewAmountAndSeedIsNotAdded(Profile profile) {
		try (var f = new Fixture(profile)) {
			long firstRequest = f.open(BlockPos.ZERO, BlockFace.NORTH); f.awaitRow(firstRequest, "minecraft:stone");
			var preview = f.previewConsumer(firstRequest); Object round = java.util.Objects.requireNonNull(get(preview, "round"));
			var journal = (InventorySlotJournal) java.util.Objects.requireNonNull(get(round, "journal"));
			assertTrue(journal.endSlot() > 2 && journal.endSlot() < SLOTS - 1);
			long oldStone = journal.amount(0), oldDirt = journal.amount(2);
			// Mutate two already-read slots and two never-read slots without moving identities.
			for (int address : List.of(0, 2, 1617, 1619)) {
				var replacement = stack(Contents.MIXED, address); replacement.setCount(replacement.getCount() + 1000);
				f.world.vault.replaceStack(address, replacement);
			}
			MarkerId first = f.select(firstRequest, "minecraft:stone"); f.client.close(firstRequest);
			assertSame(round, get(f.consumer(first), "round"));
			f.next(); long secondRequest = f.open(new BlockPos(8, 2, 2), BlockFace.SOUTH);
			long beforeReplay = f.visits; f.awaitRow(secondRequest, "minecraft:dirt");
			assertSame(round, get(f.previewConsumer(secondRequest), "round"));
			assertTrue(f.visits - beforeReplay < SLOTS, "new Preview replays paid prefix, not a fresh native sweep");
			MarkerId second = f.select(secondRequest, "minecraft:dirt"); f.client.close(secondRequest);
			assertSame(round, get(f.consumer(second), "round"));
			assertEquals(oldStone, journal.amount(0)); assertEquals(oldDirt, journal.amount(2));
			for (int tick = 0; tick < 200 && !(f.client.tracking(first).complete() && f.client.tracking(second).complete()); tick++) f.next();
			for (var marker : List.of(first, second)) {
				var result = f.client.tracking(marker); assertTrue(result.complete()); assertFalse(result.grey());
				assertEquals(expected(marker.equals(first) ? "minecraft:stone" : "minecraft:dirt") + 1000, result.entries().getFirst().count());
			}
			assertEquals(3_000_003L + 1617 + 1000, journal.amount(1617)); assertEquals(3_000_003L + 1619 + 1000, journal.amount(1619));
			assertEquals(SLOTS, f.visits, "both full mixed-time selected totals came from exactly one native round");
			CertifiedVault1620BaselineTest.emit(Map.of("kind", "activeJoinMutation", "profile", profile.name(), "physicalVisits", f.visits,
				"stone", f.client.tracking(first).entries().getFirst().count(), "dirt", f.client.tracking(second).entries().getFirst().count()));
		}
	}
	@org.junit.jupiter.api.Test void duplicateSelectAndStoreFailureCloseOnlyTentativeJoinedOwnership() {
		try (var f = new Fixture()) {
			long request = f.open(BlockPos.ZERO, BlockFace.NORTH); f.awaitRow(request, "minecraft:stone");
			Object round = java.util.Objects.requireNonNull(get(f.previewConsumer(request), "round"));
			MarkerId marker = f.select(request, "minecraft:stone"); long reads = physical(f.runtime);
			Object references = get(round, "references"); f.ingress(java.util.Objects.requireNonNull(f.lastSelection));
			assertEquals(1, f.markers.size()); assertEquals(references, get(round, "references")); assertEquals(reads, physical(f.runtime));
			f.client.close(request); assertSame(round, get(f.consumer(marker), "round"));
		}
		try (var f = new Fixture()) {
			long request = f.open(BlockPos.ZERO, BlockFace.NORTH); f.awaitRow(request, "minecraft:stone");
			Object round = java.util.Objects.requireNonNull(get(f.previewConsumer(request), "round"));
			var selection = f.client.selectable(request).getFirst(); f.rejectAfterAdmission = true;
			long reads = physical(f.runtime); Object references = get(round, "references");
			assertEquals(ClientInventory.DispatchOutcome.SENT, f.client.select(selection, "danger", packet -> { f.ingress(packet); return ClientInventory.DispatchOutcome.SENT; }));
			assertEquals(0, f.markers.size()); assertTrue(f.leases().isEmpty()); assertEquals(reads, physical(f.runtime));
			assertEquals(references, get(round, "references")); assertEquals(0, get(round, "activeTracking"));
			assertSame(round, get(f.previewConsumer(request), "round")); assertEquals(0, f.runtime.memory().reserved());
		}
	}
	@org.junit.jupiter.api.Test void metadataBudgetDeferDoesNotRejectOtherwiseValidSynchronousSelectionOrGrantOldPreviewOwnership() {
		try (var f = new Fixture()) {
			long request = f.open(BlockPos.ZERO, BlockFace.NORTH); f.awaitRow(request, "minecraft:stone");
			Object old = java.util.Objects.requireNonNull(get(f.previewConsumer(request), "round")); f.deferJoin = true;
			MarkerId marker = f.select(request, "minecraft:stone");
			assertNull(get(f.consumer(marker), "round")); assertEquals(1, get(old, "references"));
			f.client.close(request); assertEquals(true, get(old, "closed"));
			f.deferJoin = false;
			for (int tick = 0; tick < 180 && !f.client.tracking(marker).complete(); tick++) f.next();
			assertTrue(f.client.tracking(marker).complete()); assertEquals(expected("minecraft:stone"), f.client.tracking(marker).entries().getFirst().count());
			assertNotSame(old, get(f.consumer(marker), "round"));
		}
	}
}
