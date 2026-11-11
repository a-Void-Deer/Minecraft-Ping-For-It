package nx.pingwheel.common.presentation.inventory.minecraft;

import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.ByteMultiplierGrid;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.client.InventoryPreviewLifecycle;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.neoforge.integration.create.presentation.PooledVaultFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import static nx.pingwheel.common.presentation.inventory.minecraft.CertifiedVault1620Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Three disjoint native stores in ONE SourceWorld; only provider metadata coordinates are translated. */
@NotNullByDefault
final class MultiVaultFixture implements InventoryBackend.Host, InventoryRuntime.SourceResolver, AutoCloseable {

	@NotNullByDefault
	enum Budgets {
		DEFAULTS, MINIMUM_BYTES;
		InventorySettings settings() {
			var settings = InventorySettings.serverDefaults();
			if (this != DEFAULTS) {
				settings.setPendingMemoryMiB(InventoryLimits.MIN_PENDING_MEMORY_MIB);
				settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.CLIENT.minimum()));
				settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.GLOBAL.minimum()));
				settings.getTracking().setStreamByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.CLIENT.minimum()));
				settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.CLIENT.minimum()));
				settings.getTracking().setGlobalByteMultiplier(ByteMultiplier.finite(ByteMultiplierGrid.GLOBAL.minimum()));
				settings.setDisplayByteMultiplier(InventorySizeScale.DISPLAY.minimumMultiplier());
				settings.setFrameByteMultiplier(InventorySizeScale.FRAME.minimumMultiplier());
			}
			String before = settings.toString();
			settings.validate();
			assertEquals(before, settings.toString(), "scenario must use legal inputs, never silently clamped tuning");
			var values = Objects.requireNonNull(InventoryConfigValues.from(settings));
			assertTrue(values.isSafe()); assertTrue(values.isSizePairCompatible());
			return settings;
		}
	}

	final InventorySettings settings;
	final World world;
	final InventoryRuntime runtime;
	final InventoryBackend backend;
	final ServerMarkerStore markers = new ServerMarkerStore(new MarkerIdSource());
	final ClientInventory client;
	final List<InventoryS2CPacket> received = new ArrayList<>();
	final List<InventoryC2SPacket> sent = new ArrayList<>();
	final Map<MarkerId, Long> originalExpiry = new HashMap<>();
	long tick, millis, visits, measurements;
	int createCalls, createdNotifications;
	@Nullable InventoryC2SPacket lastSelection;

	MultiVaultFixture(Budgets budgets) { this(budgets, -1, ItemStack.EMPTY); }
	/** singletonVault keeps 1620 physical addresses, with one populated native address. */
	MultiVaultFixture(Budgets budgets, int singletonVault, ItemStack singleton) {
		settings = budgets.settings(); world = new World(singletonVault, singleton);
		runtime = new InventoryRuntime(this, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
		backend = new InventoryBackend(this, runtime, 1);
		client = new ClientInventory(packet -> ingress((InventoryC2SPacket) packet), () -> millis);
		client.presentationReset(100, 1); ingress(InventoryC2SPacket.hello()); assertTrue(client.ready());
	}
	void ingress(InventoryC2SPacket packet) {
		sent.add(packet);
		if (packet.kind() == InventoryC2SPacket.Kind.SELECT) lastSelection = packet;
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer); var decoded = InventoryC2SPacket.readSafe(buffer);
			assertFalse(decoded.isCorrupt()); assertFalse(buffer.isReadable()); backend.handle(OWNER, decoded, tick, settings);
		} finally { buffer.release(); }
	}
	@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); }
	@Override public Optional<InventoryBackend.SessionAccess> session(UUID player) { return Optional.of(new InventoryBackend.SessionAccess(100, 1, Set.of(InventoryResourceKind.ITEM))); }
	@Override public Optional<ServerMarker> activeMarker(MarkerId marker) { return markers.find(marker); }
	@Override public @Unmodifiable Set<InventoryResourceKind> freezeInventoryPreviewLanes(UUID owner, String type) { return Set.of(InventoryResourceKind.ITEM); }
	@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
	@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) { return pingType.equals("danger"); }
	@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
	@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted source resolution"); }
	@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
		allowance.debit(resolutionValidationCost(input));
		var view = InventoryMinecraftSources.view(world, input, world::find);
		var source = new InventoryMinecraftSources.Source(input, view, world::onServerThread,
			() -> InventoryMinecraftSources.view(world, input, world::find),
			entry -> { throw new AssertionError("certified native route required"); }, world, null, cap, ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY);
		var proof = Objects.requireNonNull(source.sharedRead()).proof();
		assertEquals(InventorySharedRead.Route.CREATE_VAULT_LOCAL, proof.graph().route());
		assertEquals(SLOTS, proof.graph().slots()); assertEquals(MEMBERS, proof.members().size());
		world.proofs.put(world.index(input.ordinaryTarget().orElseThrow()), proof);
		world.sourceKeys.put(world.index(input.ordinaryTarget().orElseThrow()), source.key());
		return Optional.of(source);
	}
	@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
		createCalls++;
		var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		var hit = (Target.BlockTarget) frozen.target();
		var group = new TargetKey.VaultKey(hit.dimensionId(), hit.x(), hit.y(), hit.z(), hit.blockRegistryId());
		var audience = List.of(OWNER);
		@Nullable var rejection = admission.prepare(hit, group, type.id(), audience);
		if (rejection != null) return new InventoryBackend.Created(null, rejection);
		var authorization = InventoryTestGrants.allowAll(audience);
		rejection = admission.finishAuthorization(authorization);
		if (rejection != null) return new InventoryBackend.Created(null, rejection);
		var marker = markers.create(player, hit, group, type, type.defaultPingType(),
			new MarkerAnchor(hit.x() + .5, hit.y() + .5, hit.z() + .5), tick, tick + 40_000, audience, List.of(), authorization).marker();
		originalExpiry.put(marker.id(), marker.expiresAtTick());
		return new InventoryBackend.Created(marker, null);
	}
	@Override public void publishCreated(InventoryBackend.Created created) {
		createdNotifications++;
		var marker = Objects.requireNonNull(created.marker());
		client.markerCreated(MarkerSnapshot.from(marker), InventoryTestGrants.content(marker, OWNER, InventoryResourceKind.ITEM));
	}
	@Override public boolean knows(UUID player, MarkerId marker) { return markers.find(marker).isPresent(); }
	@Override public boolean authorized(SyncPublisher.Context context) { return true; }
	@Override public int encodedBytes(InventoryS2CPacket packet) { throw new AssertionError("effective wire caps required"); }
	@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) { measurements++; return packet.encodedSize(display, frame); }
	@Override public void send(UUID recipient, InventoryS2CPacket packet) {
		assertEquals(OWNER, recipient);
		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer, (int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes());
			assertEquals(packet.encodedSize((int) settings.effectiveDisplayBytes(), (int) settings.effectiveFrameBytes()), buffer.readableBytes());
			var decoded = InventoryS2CPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); assertFalse(buffer.isReadable());
			received.add(decoded); client.accept(decoded);
		} finally { buffer.release(); }
	}
	void next() {
		millis += 50; backend.tick(++tick, settings); visits += physical(runtime); client.tick(true);
		assertTrue(physical(runtime) <= settings.effectivePhysicalSlotsPerTick(), this::evidence);
		assertEquals(0, runtime.memory().reserved(), "all synchronous workspace must be released");
		assertTrue(runtime.memory().retained() <= settings.pendingMemoryBytes(), this::evidence);
		for (var marker : markers.allMarkers()) assertEquals(originalExpiry.get(marker.id()).longValue(), marker.expiresAtTick(), "inventory never extends marker lifetime");
	}
	void await(BooleanSupplier condition, int ticks, String failure) {
		for (int step = 0; step < ticks && !condition.getAsBoolean(); step++) next();
		assertTrue(condition.getAsBoolean(), () -> failure + " " + evidence());
	}
	InventoryPreviewLifecycle open(int vault) { return Objects.requireNonNull(client.openPreview(target(World.origin(vault)), BlockFace.NORTH, "entity_block")); }
	void awaitRow(InventoryPreviewLifecycle handle) {
		await(() -> !client.selectable(handle.requestId()).isEmpty(), 128, "stable due Preview must produce a selectable paid prefix within 6.4 s");
		assertEquals(1, handle.attempts(), "healthy sources must not need a retry to escape Tracking pressure");
		assertTrue(client.preview(handle.requestId()).entries().getFirst().count() > 0, "unknown/partial state cannot invent zero");
	}
	MarkerId select(InventoryPreviewLifecycle handle) {
		int before = markers.size(), calls = createCalls, notifications = createdNotifications;
		long reads = physical(runtime);
		var reference = client.selectable(handle.requestId()).getFirst();
		long retainedCount = retainedCount(handle.requestId(), reference.entryKey());
		assertEquals(ClientInventory.DispatchOutcome.SENT, client.select(reference, "danger", packet -> { ingress(packet); return ClientInventory.DispatchOutcome.SENT; }));
		assertEquals(before + 1, markers.size(), this::evidence);
		assertEquals(calls + 1, createCalls); assertEquals(notifications + 1, createdNotifications);
		assertEquals(reads, physical(runtime), "SELECT is metadata-only and may not call another native getter");
		var marker = markers.allMarkers().getLast().id();
		var receipt = backend.creationSnapshot(marker).orElseThrow();
		assertEquals(retainedCount, receipt.amount(), "creation uses the exact server-retained selected count");
		assertTrue(receipt.amount() > 0, "missing retained count must reject, never become zero");
		ingress(Objects.requireNonNull(lastSelection));
		assertEquals(before + 1, markers.size()); assertEquals(calls + 1, createCalls); assertEquals(notifications + 1, createdNotifications);
		assertEquals(reads, physical(runtime), "replayed SELECT cannot read or create again");
		handle.close(); return marker;
	}
	@SuppressWarnings("unchecked") @UnmodifiableView Map<MarkerId, ?> leases() { return (Map<MarkerId, ?>) Objects.requireNonNull(get(backend, "tracking")); }
	Object preview(long request) {
		var session = Objects.requireNonNull(((Map<?, ?>) Objects.requireNonNull(get(backend, "sessions"))).get(OWNER));
		return ((Map<?, ?>) Objects.requireNonNull(get(session, "previews"))).values().stream()
			.filter(value -> Long.valueOf(request).equals(get(Objects.requireNonNull(value), "request"))).findFirst().orElseThrow();
	}
	InventoryRuntime.Consumer previewConsumer(long request) { return (InventoryRuntime.Consumer) Objects.requireNonNull(get(preview(request), "consumer")); }
	long retainedCount(long request, String token) {
		var preview = preview(request);
		var selection = (InventorySelection) Objects.requireNonNull(((Map<?, ?>) Objects.requireNonNull(get(preview, "references"))).get(token));
		var counts = (Map<?, ?>) Objects.requireNonNull(get(preview, "counts"));
		return ((InventoryResourceValue) Objects.requireNonNull(counts.get(selection.exact()))).amount();
	}
	InventoryRuntime.Consumer consumer(MarkerId marker) { return (InventoryRuntime.Consumer) Objects.requireNonNull(get(Objects.requireNonNull(leases().get(marker)), "consumer")); }
	long revision(MarkerId marker) { return (Long) Objects.requireNonNull(get(Objects.requireNonNull(leases().get(marker)), "revision")); }
	void assertCount(MarkerId marker, long expected) {
		var projection = client.tracking(marker);
		assertTrue(projection.complete(), this::evidence); assertFalse(projection.grey(), this::evidence);
		assertEquals(InventoryS2CPacket.Status.READY, projection.status()); assertEquals(1, projection.entries().size());
		assertEquals(expected, projection.entries().getFirst().count(), this::evidence);
	}
	void assertDistinctPhysicalSources() {
		assertEquals(3, world.proofs.size()); assertEquals(3, new java.util.HashSet<>(world.sourceKeys.values()).size());
		for (int a = 0; a < 3; a++) for (int b = a + 1; b < 3; b++) {
			var x = Objects.requireNonNull(world.proofs.get(a)); var y = Objects.requireNonNull(world.proofs.get(b));
			assertEquals(x.world(), y.world(), "all three Vaults inhabit the same physical world");
			assertNotEquals(x.graph().alias(), y.graph().alias()); assertNotEquals(x.graph(), y.graph()); assertNotEquals(x.members(), y.members());
			var positions = x.graph().layout().members().stream().map(InventorySnapshotLayout.Member::position).toList();
			assertTrue(y.graph().layout().members().stream().noneMatch(member -> positions.contains(member.position())));
		}
		var consumers = markers.allMarkers().stream().filter(marker -> leases().containsKey(marker.id())).map(marker -> consumer(marker.id())).toList();
		for (int a = 0; a < consumers.size(); a++) for (int b = a + 1; b < consumers.size(); b++) {
			@Nullable var x = get(consumers.get(a), "round"); @Nullable var y = get(consumers.get(b), "round");
			if (x != null && y != null) assertNotSame(x, y, "different Vaults may never share a physical round");
		}
	}
	String evidence() {
		return "tick=" + tick + " visits=" + visits + " memory=" + runtime.memory().retained() + "/" + runtime.memory().cap()
			+ " tracking=" + markers.allMarkers().stream().filter(marker -> leases().containsKey(marker.id())).map(marker -> marker.id() + ":" + consumer(marker.id()).progress() + ":" + client.tracking(marker.id())).toList()
			+ " controls=" + received.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED || packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE)
				.skip(Math.max(0, received.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED || packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE).count() - 8))
				.map(packet -> packet.kind() + "/" + packet.requestId() + "/" + packet.condition() + "/" + packet.failureReason() + "/progress=" + packet.progressRevision()).toList();
	}
	@Override public void close() { backend.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved()); }

	@NotNullByDefault
	static final class World implements InventoryMinecraftSources.SourceWorld {
		final @Unmodifiable List<PlacedVault> vaults;
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		final Map<Integer, InventorySharedRead.Proof> proofs = new HashMap<>();
		final Map<Integer, nx.pingwheel.common.presentation.source.SourceKey> sourceKeys = new HashMap<>();
		long metadataCalls;
		World(int singletonVault, ItemStack singleton) {
			var placed = new ArrayList<PlacedVault>();
			for (int index = 0; index < 3; index++) {
				final int vault = index;
				IntFunction<ItemStack> contents = index == singletonVault ? address -> address == 0 ? singleton.copy() : ItemStack.EMPTY
					: address -> { var stack = CertifiedVault1620Fixture.stack(Contents.REPEATED, address); stack.setCount(stack.getCount() + vault * 10_000); return stack; };
				var value = new PlacedVault(origin(index), new PooledVaultFixture(9, contents)); placed.add(value);
				for (BlockPos local : value.nativeVault.members.keySet()) {
					BlockPos pos = origin(index).offset(local); entities.put(pos, new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState()));
				}
			}
			vaults = List.copyOf(placed);
		}
		static BlockPos origin(int index) { return new BlockPos(index * 32, 0, 0); }
		int index(Target.BlockTarget target) { return target.x() / 32; }
		Optional<IPlatformInventoryService.Access> find(BlockPos pos, Direction face) { return vaults.get(pos.getX() / 32).find(pos, face); }
		@Override public boolean isLoaded(BlockPos pos) { metadataCalls++; return entities.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { metadataCalls++; return Objects.requireNonNull(entities.get(pos)).getBlockState(); }
		@Override public BlockEntity blockEntity(BlockPos pos) { metadataCalls++; return Objects.requireNonNull(entities.get(pos)); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
	}

	/** Adapter to the existing package-private provider test port, without copying its topology/read algorithm. */
	@NotNullByDefault
	static final class PlacedVault {
		private static final Class<?> WORLD = nested("World"), MEMBER = nested("Member");
		private static final Method FIND = findMethod();
		final BlockPos origin;
		final PooledVaultFixture nativeVault;
		final Object metadataWorld;
		final Map<BlockPos, Object> metadataMembers = new HashMap<>();
		PlacedVault(BlockPos origin, PooledVaultFixture nativeVault) {
			this.origin = origin; this.nativeVault = nativeVault;
			for (var entry : nativeVault.members.entrySet()) {
				Object delegate = entry.getValue();
				Object metadata = Proxy.newProxyInstance(MEMBER.getClassLoader(), new Class<?>[] { MEMBER }, (proxy, method, arguments) -> {
					if (method.getName().equals("controller")) return origin;
					method.setAccessible(true);
					try { return method.invoke(delegate, arguments); }
					catch (InvocationTargetException failure) { throw Objects.requireNonNull(failure.getCause()); }
				});
				metadataMembers.put(origin.offset(entry.getKey()), metadata);
			}
			metadataWorld = Proxy.newProxyInstance(WORLD.getClassLoader(), new Class<?>[] { WORLD }, (proxy, method, arguments) -> switch (method.getName()) {
				case "dimension" -> nativeVault.dimension();
				case "currentRoute" -> nativeVault.currentRoute();
				case "loaded" -> Objects.requireNonNull(metadataMembers.get((BlockPos) Objects.requireNonNull(arguments)[0]));
				default -> throw new AssertionError("unexpected provider metadata method " + method);
			});
		}
		@SuppressWarnings("unchecked") Optional<IPlatformInventoryService.Access> find(BlockPos pos, Direction face) {
			try { return (Optional<IPlatformInventoryService.Access>) FIND.invoke(null, metadataWorld, pos, face); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		private static Class<?> nested(String name) {
			try { return Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess$" + name); }
			catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
		}
		private static Method findMethod() {
			try {
				var method = Class.forName("nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess").getDeclaredMethod("find", WORLD, BlockPos.class, Direction.class);
				method.setAccessible(true); return method;
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
	}
}
