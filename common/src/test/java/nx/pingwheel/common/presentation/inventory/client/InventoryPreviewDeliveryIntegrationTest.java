package nx.pingwheel.common.presentation.inventory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real backend publication feeds client ingress before OPEN/tick returns; no packet-order simulation. */
@NotNullByDefault
class InventoryPreviewDeliveryIntegrationTest {
	private static final UUID OWNER = new UUID(71, 3);
	private static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	@NotNullByDefault
	private static final class Host implements InventoryBackend.Host {
		final List<InventoryS2CPacket> received = new ArrayList<>();
		@Nullable ClientInventory client;
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); }
		@Override public Optional<InventoryBackend.SessionAccess> session(UUID player) {
			return Optional.of(new InventoryBackend.SessionAccess(100, 1, Set.of(InventoryResourceKind.ITEM, InventoryResourceKind.FLUID)));
		}
		@Override public Optional<ServerMarker> activeMarker(MarkerId marker) { return Optional.empty(); }
		@Override public @Unmodifiable Set<InventoryResourceKind> freezeInventoryPreviewLanes(UUID owner, String type) {
			return Set.of(InventoryResourceKind.ITEM, InventoryResourceKind.FLUID);
		}
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target target) { return Optional.of(new InventoryBackend.Opened(target, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) { return true; }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) { throw new AssertionError("Preview cannot create"); }
		@Override public boolean knows(UUID player, MarkerId marker) { return false; }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { return packet.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES); }
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			assertFalse(packet.isCorrupt()); received.add(packet);
			if (client != null) client.accept(packet);
		}
		@Override public TransportHandoff sendObserved(UUID recipient, InventoryS2CPacket packet) { send(recipient, packet); return TransportHandoff.HANDED_OFF; }
	}
	@NotNullByDefault
	private static final class Source implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		Source(InventorySourceInput input) { this.input = input; }
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "partial-delivery", input.viewKey()); }
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 2; }
		@Override public int displayBytes() { return 4096; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.Item read(int slot) {
			return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "variant-" + slot), slot + 1L, "Stone", null, false);
		}
	}
	@Test void firstReadableIncompletePrefixSurvivesUnsupportedFluidThroughRealBackendAndClient() {
		InventorySettings settings = new InventorySettings(); settings.setPendingMemoryMiB(64);
		settings.setPhysicalSlotsPerTick(IntLimit.finite(8)); settings.getPreview().setPeriodTicks(4);
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(8)); settings.getPreview().setMaxSlotsServer(IntLimit.finite(16));
		settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(1));
		settings.getPreview().getClientByteMultiplier().setUnlimited(true); settings.getPreview().getGlobalByteMultiplier().setUnlimited(true);
		Host host = new Host();
		InventoryRuntime runtime = new InventoryRuntime((input, displayBytes) -> {
			if (input.resourceKind() == InventoryResourceKind.FLUID)
				throw new nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException();
			return Optional.of(new Source(input));
		}, settings.pendingMemoryBytes());
		List<IPacket> sent = new ArrayList<>();
		try (InventoryBackend backend = new InventoryBackend(host, runtime, 1)) {
			ClientInventory client = new ClientInventory(packet -> {
				sent.add(packet); backend.handle(OWNER, (InventoryC2SPacket) packet, 0, settings);
			}, () -> 0);
			host.client = client; client.presentationReset(100, 1); client.tick(true);
			InventoryPreviewLifecycle handle = java.util.Objects.requireNonNull(client.openPreview(TARGET, BlockFace.NORTH, "entity_block"));
			for (int tick = 0; tick < 20 && client.selectable(handle.requestId()).isEmpty(); tick++) backend.tick(tick, settings);
			assertTrue(host.received.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE
				&& packet.resourceKind() == InventoryResourceKind.FLUID && packet.condition() == InventoryS2CPacket.PreviewCondition.UNSUPPORTED));
			assertTrue(host.received.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& !packet.entries().isEmpty() && packet.status() == InventoryS2CPacket.Status.INCOMPLETE));
			assertEquals(1, client.selectable(handle.requestId()).size());
			assertEquals(InventoryFailureReason.VARIANT_LIMIT, client.preview(handle.requestId()).failureReason());
			assertFalse(client.preview(handle.requestId()).completeScan()); assertFalse(handle.waiting());
			assertTrue(sent.stream().noneMatch(packet -> packet instanceof InventoryC2SPacket p && p.kind() == InventoryC2SPacket.Kind.CLOSE));
			assertEquals(1, handle.attempts()); handle.close();
		}
	}
}
