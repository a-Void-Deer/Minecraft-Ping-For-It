package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.*;

/** Real backend ingress and encoded client endpoints shared by the chest and loader-native Vault oracles. */
@NotNullByDefault
public final class InventoryPublicationBackendFixture implements AutoCloseable, InventoryBackend.Host {
	public static final UUID A = new UUID(3, 1), B = new UUID(3, 2);
	public final InventorySettings settings;
	public final InventoryRuntime runtime;
	public final InventoryBackend backend;
	public final ServerMarkerStore markers = new ServerMarkerStore(new MarkerIdSource());
	public final Map<UUID, ClientInventory> clients = new HashMap<>();
	public final Map<UUID, Long> epochs = new HashMap<>();
	public final Map<UUID, Long> requests = new HashMap<>(), commits = new HashMap<>();
	public final List<InventoryS2CPacket> frames = new ArrayList<>();
	public final List<UUID> recipients = new ArrayList<>();
	public final TargetKey group;
	public java.util.function.Consumer<InventoryS2CPacket> measured = packet -> {};
	public java.util.function.Consumer<InventoryS2CPacket> handed = packet -> {};
	public java.util.function.Function<UUID, Optional<InventoryBackend.Policy>> currentPolicy = player -> Optional.of(new InventoryBackend.Policy(100, 1, Set.of("entity_block")));
	public java.util.function.BiPredicate<UUID, MarkerId> known = (player, marker) -> true;
	public java.util.function.BiConsumer<SyncPublisher.Context, InventoryS2CPacket> beforeValidation = (context, packet) -> {};
	public java.util.function.BiPredicate<SyncPublisher.Context, InventoryS2CPacket> blocked = (context, packet) -> false;
	public boolean holdData;
	public int effectiveMeasurements;
	public InventoryPublicationBackendFixture(InventoryRuntime.SourceResolver resolver, InventorySettings settings, TargetKey group) {
		this.settings = settings; this.group = group;
		runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
		backend = new InventoryBackend(this, runtime, 1);
		for (UUID owner : List.of(A, B)) { var client = new ClientInventory(packet -> {}); client.presentationReset(100, 1); clients.put(owner, client); backend.handle(owner, InventoryC2SPacket.hello(), 0, settings); }
	}
	@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return currentPolicy.apply(player); }
	@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
	@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) { return pingType.equals("danger"); }
	@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
		var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		@Nullable var rejection = admission.prepare(frozen.target(), group, type.id(), List.of(A, B));
		if (rejection != null) return new InventoryBackend.Created(null, rejection);
		return new InventoryBackend.Created(markers.create(player, frozen.target(), group, type, type.defaultPingType(), new MarkerAnchor(0, 0, 0), 0, 10000, List.of(A, B)).marker(), null);
	}
	@Override public void publishCreated(InventoryBackend.Created created) { for (var client : clients.values()) client.markerCreated(MarkerSnapshot.from(created.marker())); }
	@Override public boolean knows(UUID player, MarkerId marker) { return known.test(player, marker) && markers.allMarkers().stream().anyMatch(value -> value.id().equals(marker) && value.recipients().contains(player)); }
	@Override public boolean authorized(SyncPublisher.Context context) { return true; }
	@Override public int encodedBytes(InventoryS2CPacket packet) { throw new AssertionError("production adapter must forward effective-budget measurement"); }
	@Override public int encodedBytes(InventoryS2CPacket packet, int displayBytes, int frameBytes) { effectiveMeasurements++; measured.accept(packet); return packet.encodedSize(displayBytes, frameBytes); }
	@Override public InventorySyncPublisher.SendDecision beforeSend(SyncPublisher.Context context, InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) {
		beforeValidation.accept(context, packet);
		return (holdData && InventorySyncPublisher.requiresSource(packet)) || blocked.test(context, packet) ? InventorySyncPublisher.SendDecision.DEFER : InventorySyncPublisher.SendDecision.ALLOW;
	}
	@Override public void send(UUID recipient, InventoryS2CPacket packet) {
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try { packet.write(buffer); var decoded = InventoryS2CPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); assertFalse(buffer.isReadable());
			frames.add(decoded); recipients.add(recipient); clients.get(recipient).accept(decoded);
			if (packet.kind() == InventoryS2CPacket.Kind.OFFER) epochs.put(recipient, packet.epoch());
		} finally { buffer.release(); }
		handed.accept(packet);
	}
	public long open(UUID owner, Target target, BlockFace face, long tick) {
		long request = requests.merge(owner, 1L, Long::sum);
		backend.handle(owner, InventoryC2SPacket.open(epochs.get(owner), 100, 1, request, target, face), tick, settings); return request;
	}
	public MarkerId select(UUID owner, long request, long tick) {
		@Nullable InventoryS2CPacket preview = null;
		for (int index = 0; index < frames.size(); index++) if (recipients.get(index).equals(owner) && frames.get(index).kind() == InventoryS2CPacket.Kind.PREVIEW
			&& frames.get(index).requestId() == request && !frames.get(index).entries().isEmpty()) preview = frames.get(index);
		assertNotNull(preview);
		long commit = commits.merge(owner, 1L, Long::sum);
		backend.handle(owner, InventoryC2SPacket.select(epochs.get(owner), 100, 1, commit, request, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), tick, settings);
		return markers.allMarkers().getLast().id();
	}
	public void closePreview(UUID owner, long request, long tick) { backend.handle(owner, InventoryC2SPacket.close(epochs.get(owner), request).stamp(100, 1), tick, settings); }
	@Override public void close() { backend.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved()); }
}
