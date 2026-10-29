package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.network.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPooledVaultBackendTest {
	@NotNullByDefault
	static final class Fixture implements InventoryBackend.Host, AutoCloseable {
		static final UUID A = new UUID(3, 1), B = new UUID(3, 2);
		final InventorySettings settings;
		final InventoryRuntime runtime;
		final InventoryBackend backend;
		final ServerMarkerStore markers = new ServerMarkerStore(new MarkerIdSource());
		final Map<UUID, ClientInventory> clients = new HashMap<>();
		final Map<UUID, Long> epochs = new HashMap<>();
		final List<InventoryS2CPacket> frames = new ArrayList<>();
		final List<UUID> recipients = new ArrayList<>();
		int effectiveMeasurements;
		Fixture(InventoryRuntime.SourceResolver resolver, InventorySettings settings) {
			this.settings = settings; runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
			backend = new InventoryBackend(this, runtime, 1);
			for (UUID owner : List.of(A, B)) { var client = new ClientInventory(packet -> {}); client.presentationReset(100, 1); clients.put(owner, client); backend.handle(owner, InventoryC2SPacket.hello(), 0, settings); }
		}
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) { return pingType.equals("danger"); }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow(); var group = new TargetKey.VaultKey("minecraft:overworld", 0, 0, 0, "create:item_vault");
			@Nullable var rejection = admission.prepare(frozen.target(), group, type.id(), List.of(A, B));
			return rejection != null ? new InventoryBackend.Created(null, rejection) : new InventoryBackend.Created(markers.create(player, frozen.target(), group, type, type.defaultPingType(), new MarkerAnchor(0, 0, 0), 0, 10000, List.of(A, B)).marker(), null);
		}
		@Override public void publishCreated(InventoryBackend.Created created) { for (var client : clients.values()) client.markerCreated(MarkerSnapshot.from(created.marker())); }
		@Override public boolean knows(UUID player, MarkerId marker) { return markers.allMarkers().stream().anyMatch(value -> value.id().equals(marker)); }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { throw new AssertionError("legacy measurement"); }
		@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) { effectiveMeasurements++; return packet.encodedSize(display, frame); }
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			var buffer = new FriendlyByteBuf(Unpooled.buffer()); try { packet.write(buffer); var decoded = InventoryS2CPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); frames.add(decoded); recipients.add(recipient); clients.get(recipient).accept(decoded);
				if (packet.kind() == InventoryS2CPacket.Kind.OFFER) epochs.put(recipient, packet.epoch()); } finally { buffer.release(); }
		}
		void open(UUID owner, Target target, BlockFace face, long request) { backend.handle(owner, InventoryC2SPacket.open(epochs.get(owner), 100, 1, request, target, face), 0, settings); }
		MarkerId select(UUID owner, long request) {
			@Nullable InventoryS2CPacket preview = null;
			for (int index = 0; index < frames.size(); index++) if (recipients.get(index).equals(owner) && frames.get(index).kind() == InventoryS2CPacket.Kind.PREVIEW && frames.get(index).requestId() == request && !frames.get(index).entries().isEmpty()) preview = frames.get(index);
			assertNotNull(preview); backend.handle(owner, InventoryC2SPacket.select(epochs.get(owner), 100, 1, request, request, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 120, settings);
			return markers.allMarkers().getLast().id();
		}
		void closePreview(UUID owner, long request) { backend.handle(owner, InventoryC2SPacket.close(epochs.get(owner), request).stamp(100, 1), 120, settings); }
		@Override public void close() { backend.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved()); }
	}
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }
	@ParameterizedTest @CsvSource({"true,true,3600", "true,false,1800", "false,true,1800", "false,false,900"})
	void backendSelectionsForTwoRealOwnersShareOnlyApproved900SlotPolicyCohortsAndReachEncodedClients(boolean faces, boolean players, int expected) throws ReflectiveOperationException {
		var world = new InventoryPooledVaultRuntimeTest.World(); var settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(256); settings.setPhysicalSlotsPerTick(IntLimit.finite(64)); settings.getPreview().setPeriodTicks(1);
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(4096)); settings.getPreview().setMaxSlotsServer(IntLimit.finite(4096));
		settings.getTracking().setPeriodTicks(10); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(4096)); settings.getTracking().setMaxSlotsServer(IntLimit.finite(4096));
		settings.getTracking().setDistinguishFaces(faces); settings.getTracking().setDistinguishPlayers(players);
		var target = new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault");
		try (var f = new Fixture(world, settings)) {
			for (UUID owner : List.of(Fixture.A, Fixture.B)) { f.open(owner, target, BlockFace.NORTH, 1); f.open(owner, target, BlockFace.SOUTH, 2); }
			for (int tick = 0; tick < 120; tick++) f.backend.tick(tick, settings);
			var ids = new ArrayList<MarkerId>();
			for (UUID owner : List.of(Fixture.A, Fixture.B)) for (long request = 1; request <= 2; request++) { ids.add(f.select(owner, request)); f.closePreview(owner, request); }
			long visits = 0;
			for (int tick = 121; tick < 400; tick++) {
				f.backend.tick(tick, settings); visits += InventoryPooledVaultRuntimeTest.physical(f.runtime);
				if (f.clients.values().stream().allMatch(client -> ids.stream().allMatch(id -> client.tracking(id).complete()))) break;
			}
			assertEquals(expected, visits);
			for (var client : f.clients.values()) for (MarkerId id : ids) { assertTrue(client.tracking(id).complete()); assertEquals(2_700_407_250L, client.tracking(id).entries().getFirst().count()); }
			assertEquals(4, f.markers.size()); assertTrue(f.effectiveMeasurements > 0);
		}
	}
}
