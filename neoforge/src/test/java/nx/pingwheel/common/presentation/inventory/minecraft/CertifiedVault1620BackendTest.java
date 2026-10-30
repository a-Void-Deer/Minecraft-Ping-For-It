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
		final InventorySettings settings = Profile.INCIDENT.settings();
		final InventoryRuntime runtime = new InventoryRuntime(world, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
		final InventoryBackend backend = new InventoryBackend(this, runtime, 1);
		final ServerMarkerStore markers = new ServerMarkerStore(new MarkerIdSource());
		final ClientInventory client = new ClientInventory(packet -> ingress((InventoryC2SPacket) packet));
		long tick, visits, frames, measurements, completeFrames;
		Fixture() {
			client.presentationReset(100, 1); ingress(InventoryC2SPacket.hello()); assertTrue(client.ready());
		}
		void ingress(InventoryC2SPacket packet) {
			var buffer = new FriendlyByteBuf(Unpooled.buffer());
			try { packet.write(buffer); var decoded = InventoryC2SPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); backend.handle(OWNER, decoded, tick, settings); }
			finally { buffer.release(); }
		}
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) { return pingType.equals("danger"); }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
			var group = new TargetKey.VaultKey("minecraft:overworld", 0, 0, 0, "create:item_vault");
			@Nullable var rejection = admission.prepare(frozen.target(), group, type.id(), List.of(OWNER));
			if (rejection != null) return new InventoryBackend.Created(null, rejection);
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
	@ParameterizedTest @CsvSource({"false,false,false", "true,false,false", "true,true,false", "true,true,true"})
	void sequentialDifferentItemsPreserveIndependentCountsLifetimesAndFourReplacementCycles(boolean differentMember, boolean completeFirstPreview, boolean completeFirstTracking) {
		try (var f = new Fixture()) {
			long firstRequest = f.open(BlockPos.ZERO, BlockFace.NORTH); f.awaitRow(firstRequest, "minecraft:stone");
			if (completeFirstPreview) { for (int i = 0; i < 180 && !f.client.preview(firstRequest).completeScan(); i++) f.next(); assertTrue(f.client.preview(firstRequest).completeScan()); }
			else assertFalse(f.client.preview(firstRequest).completeScan());
			MarkerId first = f.select(firstRequest, "minecraft:stone"); f.client.close(firstRequest);
			for (int i = 0; i < 8 && get(f.consumer(first), "round") == null; i++) f.next();
			if (completeFirstTracking) {
				for (int i = 0; i < 120 && !f.client.tracking(first).complete(); i++) f.next();
				assertTrue(f.client.tracking(first).complete(), "completed-round control must start with usable client evidence");
			}
			f.stage("first-selected-tracking-started");
			long secondRequest = f.open(differentMember ? new BlockPos(8, 2, 2) : BlockPos.ZERO, BlockFace.SOUTH);
			f.awaitRow(secondRequest, "minecraft:dirt"); f.stage("second-preview-visible");
			MarkerId second = f.select(secondRequest, "minecraft:dirt"); f.client.close(secondRequest);
			f.stage("two-selections"); f.next(); f.stage("second-tracking-started");
			var firstRound = java.util.Objects.requireNonNull(get(f.consumer(first), "round"));
			var secondRound = java.util.Objects.requireNonNull(get(f.consumer(second), "round"));
			var firstSweep = java.util.Objects.requireNonNull(get(firstRound, "sweep"));
			var secondSweep = java.util.Objects.requireNonNull(get(secondRound, "sweep"));
			assertEquals(get(firstSweep, "proof"), get(secondSweep, "proof"), "independently resolved exact bindings must prove the same physical graph");
			CertifiedVault1620BaselineTest.emit(Map.of("kind", "sequentialReuse", "tick", f.tick, "sameRound", firstRound == secondRound,
				"firstSequence", java.util.Objects.requireNonNull(get(firstRound, "sequence")), "secondSequence", java.util.Objects.requireNonNull(get(secondRound, "sequence")),
				"note", "diagnostic only; no assertion of old no-join behavior; authorized join owner supplies stronger oracle later"));
			long firstExpiry = f.markers.find(first).orElseThrow().expiresAtTick();
			long firstRevision = 0, secondRevision = 0; int firstCuts = 0, secondCuts = 0;
			for (int step = 0; step < 800 && (firstCuts < 5 || secondCuts < 5); step++) {
				f.next();
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
			assertTrue(f.tick < firstExpiry); assertEquals(firstExpiry, f.markers.find(first).orElseThrow().expiresAtTick());
			assertEquals(2, f.markers.size()); assertEquals(f.markers.find(first).orElseThrow().targetKey(), f.markers.find(second).orElseThrow().targetKey());
			assertEquals(0, f.client.stats().invalidPackets()); assertTrue(f.measurements > 0);
		}
	}
}
