package nx.pingwheel.common.presentation.minecraft;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAuthorization;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryResourceCodec;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryResourceValue;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.InventorySyncPublisher;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryServer;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract: the cached inventory lane POLICY hint is a future-input hint.
 * An already-frozen OPEN Preview and a created Ping keep ticking without a new
 * permission pass; SECTION mask field checks stay separately counted. A
 * replaceable provider's internal flip is not observed, while a new OPEN/SELECT
 * and an explicit provider replacement resolve current authority and refresh
 * the queued hint only once.
 */
@NotNullByDefault
class InventoryPolicyFrozenDemandRegressionTest {

	private static final UUID OWNER = new UUID(704, 2);
	private static final Target.BlockTarget TARGET =
		new Target.BlockTarget("minecraft:overworld", 1, 64, 3, "minecraft:chest");

	@BeforeAll static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@BeforeEach void recordingPresentationTransport() {
		InventoryProductionPolicyTest.Network.receiver = (packet, recipient) -> {};
		InventoryProductionPolicyTest.Network.admitEncoded = null;
		InventoryProductionPolicyTest.Network.admitRecipient = null;
	}

	@AfterEach void restoreProvider() {
		PresentationAuthorization.setProvider(null);
	}

	@Test void frozenOpenAndCreatedLanesTickWithoutInventoryPermissionDemand() throws Exception {
		var provider = new CountingProvider();
		PresentationAuthorization.setProvider(provider);
		try (var f = new Fixture()) {
			long request = f.open();
			MarkerId marker = Objects.requireNonNull(f.select(request, InventoryResourceKind.ITEM));
			f.tick(1);
			assertTrue(f.client.hasPreview(request, InventoryResourceKind.ITEM));
			assertTrue(f.client.tracking(marker).complete());
			var frozenPreview = f.client.preview(request, InventoryResourceKind.ITEM);

			// The constructor negotiation and the explicit HELLO already performed
			// their legitimate hint passes; only steady-state increments matter.
			provider.inventoryCalls.set(0);
			provider.sectionCalls.set(0);
			for (int tick = 2; tick <= 7; tick++) f.tick(tick);

			assertEquals(0, provider.inventoryCalls.get(),
				"neither the already-OPEN request nor the created Ping polls lane permission per tick");
			assertTrue(provider.sectionCalls.get() > 0,
				"the independent SECTION mask keeps evaluating its own fields while inventory stays cached");
			assertTrue(f.client.hasPreview(request, InventoryResourceKind.ITEM));
			assertEquals(frozenPreview, f.client.preview(request, InventoryResourceKind.ITEM));
			assertTrue(f.client.tracking(marker).complete());

			// Source-owner health and quantity work remain live even though the lane
			// permission was frozen at OPEN/create.
			long reads = f.reads.get();
			f.source.amount = 7;
			for (int tick = 8; tick <= 15; tick++) f.tick(tick);
			assertTrue(f.reads.get() > reads, "steady ticks still run source-owner checks");
			assertTrue(f.client.tracking(marker).entries().stream().anyMatch(entry -> entry.count() == 7),
				"the frozen lane accepts the newer live quantity");
			assertEquals(0, provider.inventoryCalls.get(), "quantity refresh is source work, not a permission refresh");

			int sectionCalls = provider.sectionCalls.get();
			f.policy.setPermissionLevels(Map.of(PresentationBasic.NAME, 4));
			f.tick(16);
			assertTrue(provider.sectionCalls.get() > sectionCalls,
				"a SECTION mask change evaluates its own field descriptors");
			assertTrue(provider.inventoryCalls.get() > 0,
				"an explicit configuration change is a legitimate lane-hint trigger");
			int refreshed = provider.inventoryCalls.get();
			for (int tick = 17; tick <= 22; tick++) f.tick(tick);
			assertEquals(refreshed, provider.inventoryCalls.get(),
				"the configuration trigger refreshes once, not on every later tick");
			assertTrue(f.client.hasPreview(request, InventoryResourceKind.ITEM),
				"the SECTION mask change does not clear the frozen OPEN lane");
			assertTrue(f.client.tracking(marker).complete(),
				"the SECTION mask change does not clear the frozen created Ping");
		} finally {
			PresentationAuthorization.setProvider(null);
		}
	}

	@Test void internalProviderFlipLeavesHintStaleButNewOpenAndCreateResolveCurrentAuthority() throws Exception {
		var provider = new CountingProvider();
		PresentationAuthorization.setProvider(provider);
		try (var f = new Fixture()) {
			long request = f.open();
			long secondRequest = f.open();
			MarkerId marker = Objects.requireNonNull(f.select(secondRequest, InventoryResourceKind.ITEM));
			f.tick(1);
			var frozenPreview = f.client.preview(request, InventoryResourceKind.ITEM);
			assertTrue(f.client.tracking(marker).complete());

			provider.inventoryCalls.set(0);
			int policyPackets = f.policyPackets(InventoryResourceKind.ITEM);
			provider.allowItem = false;
			provider.allowFluid = false;
			for (int tick = 2; tick <= 4; tick++) f.tick(tick);

			assertEquals(0, provider.inventoryCalls.get(),
				"an internal provider flip without replacement is not polled");
			assertEquals(policyPackets, f.policyPackets(InventoryResourceKind.ITEM),
				"no new POLICY is generated for the retained stale hint");
			assertTrue(PresentationServer.inventoryPolicy(OWNER).orElseThrow().types().contains("entity_block"),
				"the cached hint may still advertise the old view");
			assertTrue(f.client.hasPreview(request, InventoryResourceKind.ITEM),
				"the stale hint never revokes the OPEN-frozen lane");
			assertEquals(frozenPreview, f.client.preview(request, InventoryResourceKind.ITEM));
			assertTrue(f.client.tracking(marker).complete(),
				"the stale hint never clears the created Ping's tracking");

			int beforeOpen = provider.inventoryCalls.get();
			// The released create request still occupies a server preview-target
			// slot. Close it to make room for the genuinely new OPEN; its created
			// marker tracking is independent and must survive.
			f.close(secondRequest);
			assertEquals(beforeOpen, provider.inventoryCalls.get(),
				"closing a released request is not a new input authority check");
			long deniedRequest = f.open();
			assertTrue(provider.inventoryCalls.get() > beforeOpen,
				"a new OPEN resolves its actual target type from current authority");
			assertFalse(f.client.hasPreview(deniedRequest, InventoryResourceKind.ITEM),
				"the new OPEN does not inherit the stale preview hint");
			assertFalse(PresentationServer.inventoryPolicy(OWNER).orElseThrow().types().contains("entity_block"),
				"the fresh OPEN folds its actual denied lane into the hint");
			assertTrue(f.client.hasPreview(request, InventoryResourceKind.ITEM),
				"the older request lane is not changed by the fresher OPEN");

			int markers = f.store.allMarkers().size();
			assertNull(f.select(request, InventoryResourceKind.ITEM),
				"create resolves its audience fresh and does not inherit the earlier preview freeze");
			assertEquals(markers, f.store.allMarkers().size());
			assertTrue(f.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.REJECT
				&& packet.requestId() == request), "the denied create is a result, not an inherited grant");
			assertTrue(f.client.hasPreview(request, InventoryResourceKind.ITEM));
			assertEquals(frozenPreview, f.client.preview(request, InventoryResourceKind.ITEM));

			f.close(deniedRequest);
			provider.allowItem = true;
			provider.allowFluid = true;
			long freshRequest = f.open();
			int beforeSelect = provider.inventoryCalls.get();
			MarkerId fresh = Objects.requireNonNull(f.select(freshRequest, InventoryResourceKind.ITEM),
				"a later create resolves the current internal allow without a notification");
			assertTrue(provider.inventoryCalls.get() > beforeSelect,
				"the new create evaluates its own audience instead of reusing the old preview");
			assertEquals(markers + 1, f.store.allMarkers().size());
			assertTrue(f.client.tracking(marker).complete(), "the older created Ping remains tracked");
			assertTrue(PresentationServer.inventoryPolicy(OWNER).orElseThrow().types().contains("entity_block"),
				"the successful create folds its actual lane into the hint without a permission recheck");
			assertFalse(fresh.equals(marker));
			assertEquals(frozenPreview, f.client.preview(request, InventoryResourceKind.ITEM));
		} finally {
			PresentationAuthorization.setProvider(null);
		}
	}

	@Test void providerReplacementRefreshesQueuedHintOnceAndBudgetRetriesDoNotReevaluate() throws Exception {
		var allow = new CountingProvider();
		PresentationAuthorization.setProvider(allow);
		try (var f = new Fixture()) {
			// Initialize the real period clock first so the repeated explicit HELLO
			// requests spend the actual current preview window instead of the
			// pre-first-tick state.
			f.tick(0);
			// Repeated explicit HELLO requests are a legitimate shared-window spend.
			// They leave the current allow hint queued behind the exhausted preview
			// byte budget without changing any production behavior for this test.
			f.wirePressure();
			for (int request = 0; request < 512; request++)
				f.backend.handle(OWNER, InventoryC2SPacket.hello(), 0, f.settings);
			assertTrue(f.queuedPolicyCount() > 0, "the preview byte budget keeps the hint queued");

			var deny = new CountingProvider();
			deny.allowItem = false;
			deny.allowFluid = false;
			PresentationAuthorization.setProvider(deny);

			f.tick(1);
			assertTrue(deny.inventoryCalls.get() > 0,
				"an explicit provider replacement refreshes the cached hint");
			int refreshedCalls = deny.inventoryCalls.get();
			assertNotNull(f.queuedPolicy(InventoryResourceKind.ITEM));
			assertTrue(f.queuedPolicy(InventoryResourceKind.ITEM).allowedTypes().isEmpty(),
				"the newly generated hint supersedes the queued old allow packet");

			int sentBefore = f.packets.size();
			for (int tick = 2; tick <= 4; tick++) {
				f.tick(tick);
				f.publisher().drainControls();
			}
			f.restoreWireBudget();
			assertEquals(refreshedCalls, deny.inventoryCalls.get(),
				"budget retries never re-evaluate permission");
			assertNotNull(f.queuedPolicy(InventoryResourceKind.ITEM),
				"a failed handoff is not successful completion");

			for (int tick = 5; tick <= 60 && f.queuedPolicy(InventoryResourceKind.ITEM) != null; tick++) {
				f.tick(tick);
				f.publisher().drainControls();
			}
			assertNull(f.queuedPolicy(InventoryResourceKind.ITEM));
			var delivered = f.packets.subList(sentBefore, f.packets.size()).stream()
				.filter(packet -> packet.kind() == InventoryS2CPacket.Kind.POLICY
					&& packet.resourceKind() == InventoryResourceKind.ITEM)
				.toList();
			assertEquals(1, delivered.size(), "only the replacement hint reaches the wire");
			assertTrue(delivered.getFirst().allowedTypes().isEmpty(),
				"the superseded allow packet is never delivered after replacement");
			assertEquals(refreshedCalls, deny.inventoryCalls.get(),
				"the complete wait-and-retry cycle performs no per-retry permission pass");
		} finally {
			PresentationAuthorization.setProvider(null);
		}
	}

	@NotNullByDefault
	private static final class CountingProvider implements PresentationAuthorization {
		final AtomicInteger inventoryCalls = new AtomicInteger();
		final AtomicInteger sectionCalls = new AtomicInteger();
		volatile boolean allowItem = true;
		volatile boolean allowFluid = true;

		@Override public boolean allows(UUID recipient, PresentationField field, int actualLevel, int requiredLevel) {
			if (field.id().equals(InventoryPresentation.ITEMS)) {
				inventoryCalls.incrementAndGet();
				return allowItem;
			}
			if (field.id().equals(InventoryPresentation.FLUIDS)) {
				inventoryCalls.incrementAndGet();
				return allowFluid;
			}
			if (field.id().equals(PresentationBasic.NAME)) sectionCalls.incrementAndGet();
			return actualLevel >= requiredLevel;
		}
	}

	@NotNullByDefault
	private static final class SourceState {
		long amount = 81;
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final Server server = DoubleChestFixture.allocate(Server.class);
		final World level = DoubleChestFixture.allocate(World.class);
		final Player player = DoubleChestFixture.allocate(Player.class);
		final Players players = DoubleChestFixture.allocate(Players.class);
		final PresentationSettings policy = ServerConfig.HANDLER.getConfig().getPresentation();
		final PresentationSettings savedPolicy = policy.deepCopy();
		final InventorySettings settings = InventorySettings.serverDefaults();
		final ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());
		final List<InventoryS2CPacket> packets = new ArrayList<>();
		final List<InventoryC2SPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(packet -> requests.add((InventoryC2SPacket) packet));
		final SourceState source = new SourceState();
		final AtomicInteger reads = new AtomicInteger();
		final InventoryRuntime runtime;
		final InventoryBackend backend;

		Fixture() throws Exception {
			server.players = players;
			server.world = level;
			players.player = player;
			players.others = Map.of();
			player.level = level;
			player.permission = 2;
			policy.copyFrom(PresentationSettings.serverDefaults());
			PresentationServer.reset();
			PresentationServer.activate(server);
			// The real presentation RESET/CREATED path drives the client view stamp;
			// without it the inventory client correctly fences every inventory frame.
			InventoryProductionPolicyTest.Network.receiver = (packet, recipient) -> {
				if (!recipient.getUUID().equals(OWNER) || !(packet instanceof PresentationS2CPacket presentation)) return;
				if (presentation.kind() == PresentationS2CPacket.Kind.RESET)
					client.presentationReset(presentation.epoch(), presentation.view());
				if (presentation.kind() == PresentationS2CPacket.Kind.CREATED)
					client.markerCreated(presentation.snapshot(), presentation.markerGrant());
			};
			PresentationServer.negotiate(server, player, PresentationC2SPacket.hello(Map.of(
				PresentationBasic.ID, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA)), store);

			InventoryRuntime.SourceResolver resolver = (input, display) -> Optional.of(new InventorySourceAccess.Source() {
				@Override public SourceKey key() {
					return new SourceKey("test", "frozen-demand", "chest", input.viewKey());
				}
				@Override public boolean valid() { return true; }
				@Override public OptionalLong version() { return OptionalLong.empty(); }
				@Override public boolean stableCursor() { return true; }
				@Override public int slots() { return 1; }
				@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) {
					return InventoryReprojectionSummary.EMPTY;
				}
				@Override public InventoryDomainCodec.@Nullable Item read(int slot) {
					throw new AssertionError("resource reader required");
				}
				@Override public @Nullable InventoryResourceValue readResource(int slot,
					@Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
					reads.incrementAndGet();
					String id = input.resourceKind() == InventoryResourceKind.ITEM ? "minecraft:stone" : "minecraft:water";
					return InventoryResourceCodec.value(new InventoryScanner.Key(input.resourceKind(), id, "native"),
						source.amount, id, null, false);
				}
			});
			runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes());
			backend = new InventoryBackend(new Host(), runtime, 1);
			field(InventoryServer.class, "active").set(null, server);
			field(InventoryServer.class, "backend").set(null, backend);
			backend.handle(OWNER, InventoryC2SPacket.hello(), 0, settings);
		}

		long open() {
			long request = client.open(TARGET, BlockFace.NORTH, "entity_block");
			backend.handle(OWNER, requests.getLast(), server.now, settings);
			tick(server.now);
			return request;
		}

		void tick(int tick) {
			server.now = tick;
			PresentationServer.tick(server, store);
			backend.tick(tick, settings);
		}
		void wirePressure() throws ReflectiveOperationException {
			Object publisher = value(backend, "publisher");
			var wire = publisher.getClass().getDeclaredMethod("wireBudgetFixture", long.class, long.class); wire.setAccessible(true); wire.invoke(publisher, 4096L, 4096L);
			var global = publisher.getClass().getDeclaredMethod("previewGlobalFixture", long.class); global.setAccessible(true); global.invoke(publisher, 4096L);
			var queue = publisher.getClass().getDeclaredMethod("previewQueueFixture", long.class); queue.setAccessible(true); queue.invoke(publisher, 32 * 1024L);
		}
		void restoreWireBudget() throws ReflectiveOperationException {
			Object publisher = value(backend, "publisher");
			var wire = publisher.getClass().getDeclaredMethod("wireBudgetFixture", long.class, long.class); wire.setAccessible(true);
			wire.invoke(publisher, settings.previewClientPeriodBytes(), settings.trackingSnapshotClientPeriodBytes());
			var global = publisher.getClass().getDeclaredMethod("previewGlobalFixture", long.class); global.setAccessible(true);
			global.invoke(publisher, settings.previewGlobalPeriodBytes());
			var queue = publisher.getClass().getDeclaredMethod("previewQueueFixture", long.class); queue.setAccessible(true);
			queue.invoke(publisher, settings.previewQueueBytes());
		}

		void close(long request) {
			int queued = requests.size();
			client.close(request);
			assertEquals(queued + 1, requests.size(), "the fixture close must dispatch one CLOSE");
			backend.handle(OWNER, requests.getLast(), server.now, settings);
		}

		/**
		 * Returns the new marker, or null when the fresh create authority rejected
		 * the selection. The fixture still exercises the real Backend create path.
		 */
		@Nullable MarkerId select(long request, InventoryResourceKind kind) {
			assertEquals(ClientInventory.DispatchOutcome.SENT, client.select(client.selectable(request, kind).getFirst(),
				"attention", packet -> { requests.add(packet); return ClientInventory.DispatchOutcome.SENT; }));
			int markers = store.allMarkers().size();
			backend.handle(OWNER, requests.getLast(), server.now, settings);
			return store.allMarkers().size() > markers ? store.allMarkers().getLast().id() : null;
		}

		int policyPackets(InventoryResourceKind kind) {
			return (int) packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.POLICY
				&& packet.resourceKind() == kind).count();
		}

		InventorySyncPublisher publisher() throws ReflectiveOperationException {
			return (InventorySyncPublisher) value(backend, "publisher");
		}

		int queuedPolicyCount() throws ReflectiveOperationException {
			Map<?, ?> controls = (Map<?, ?>) value(publisher(), "controls");
			return (int) controls.keySet().stream()
				.filter(key -> key.toString().startsWith(OWNER + "/POLICY/")).count();
		}

		@Nullable InventoryS2CPacket queuedPolicy(InventoryResourceKind kind) throws ReflectiveOperationException {
			Map<?, ?> controls = (Map<?, ?>) value(publisher(), "controls");
			@Nullable Object control = controls.get(OWNER + "/POLICY/" + kind + "/0");
			return control == null ? null : (InventoryS2CPacket) value(control, "packet");
		}

		@Override public void close() {
			InventoryServer.reset();
			PresentationServer.reset();
			InventoryProductionPolicyTest.Network.receiver = null;
			InventoryProductionPolicyTest.Network.admitEncoded = null;
			InventoryProductionPolicyTest.Network.admitRecipient = null;
			policy.copyFrom(savedPolicy);
		}

		@NotNullByDefault
		private final class Host implements InventoryBackend.Host {
			@Override public Optional<InventoryBackend.Policy> policy(UUID player) {
				return PresentationServer.inventoryPolicy(player)
					.map(policy -> new InventoryBackend.Policy(policy.epoch(), policy.view(), policy.types(),
						policy.fluidTypes(), policy.revision()));
			}
			@Override public void policyDemand(UUID player) { PresentationServer.refreshInventoryPolicy(player); }
			@Override public void adoptPreviewPolicy(UUID player, String targetTypeId,
				@Unmodifiable Set<InventoryResourceKind> lanes) {
				PresentationServer.adoptInventoryPolicyLanes(player, targetTypeId, lanes);
			}
			@Override public void adoptCreatePolicy(UUID player, String targetTypeId, InventoryResourceKind kind,
				boolean allowed) {
				PresentationServer.adoptInventoryPolicyLane(player, targetTypeId, kind, allowed);
			}
			@Override public Optional<InventoryBackend.SessionAccess> session(UUID player) {
				return PresentationServer.sessionAccess(player)
					.map(access -> new InventoryBackend.SessionAccess(access.epoch(), access.view(),
						access.compatibleInventoryKinds()));
			}
			@Override public Optional<nx.pingwheel.common.marker.ServerMarker> activeMarker(MarkerId marker) {
				return store.find(marker);
			}
			@Override public @Unmodifiable Set<InventoryResourceKind> freezeInventoryPreviewLanes(
				UUID owner, String targetTypeId) {
				return PresentationServer.freezeInventoryPreviewLanes(owner, targetTypeId);
			}
			@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) {
				return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention"));
			}
			@Override public boolean annotationAllowed(InventorySourceInput input, String annotation) {
				return annotation.equals("attention");
			}
			@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen,
				InventoryBackend.Admission admission) {
				var type = TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
				List<UUID> audience = List.of(OWNER);
				@Nullable MarkerRejectReason rejection = admission.prepare(frozen.target(),
					TargetKey.from(frozen.target()), type.id(), audience);
				if (rejection != null) return new InventoryBackend.Created(null, rejection);
				var authorization = PresentationServer.freezeForCreate(server, frozen.target(), type.id(),
					player, audience);
				if (!authorization.forRecipient(player).allowsField(InventoryPresentation.ADAPTER_ID,
					InventoryResourceKind.ITEM.fieldId()))
					return new InventoryBackend.Created(null, MarkerRejectReason.INVALID_REQUEST);
				rejection = admission.finishAuthorization(authorization);
				if (rejection != null) return new InventoryBackend.Created(null, rejection);
				var marker = store.create(player, frozen.target(), TargetKey.from(frozen.target()), type,
					type.defaultPingType(), new MarkerAnchor(1, 64, 3), 0, 10000, audience, List.of(),
					authorization).marker();
				return new InventoryBackend.Created(marker, null);
			}
			@Override public void publishCreated(InventoryBackend.Created created) {
				PresentationServer.created(server, created.marker(),
					nx.pingwheel.common.name.TargetNameJsonCodec.UNKNOWN, "owner", Map.of(
						PresentationBasic.ID, new PresentationSection(PresentationBasic.ID, 1, Map.of(
							PresentationBasic.NAME, new PresentationValue.Text("\"chest\"")), false)));
			}
			@Override public boolean knows(UUID player, MarkerId marker) {
				return PresentationServer.inventoryKnows(player, marker);
			}
			@Override public boolean authorized(SyncPublisher.Context context) { return true; }
			@Override public int encodedBytes(InventoryS2CPacket packet) {
				return packet.encodedSize(InventoryDomainCodec.MAX_DISPLAY_BYTES,
					InventoryS2CPacket.MAX_FRAME_BYTES);
			}
			@Override public void send(UUID player, InventoryS2CPacket packet) {
				packets.add(packet);
				if (player.equals(OWNER)) client.accept(packet);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Object value(Object object, String name) throws ReflectiveOperationException {
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return Objects.requireNonNull(field.get(object));
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private @Nullable World world;
		private int now;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public int getTickCount() { return now; }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
		@Override public @Nullable ServerLevel getLevel(ResourceKey<Level> dimension) {
			return dimension.equals(Level.OVERWORLD) ? world : null;
		}
	}

	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable Player player;
		private Map<UUID, Player> others = Map.of();
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) {
			return id.equals(OWNER) ? player : others.get(id);
		}
		@Override public @Unmodifiable List<ServerPlayer> getPlayers() {
			return List.of(Objects.requireNonNull(player));
		}
	}

	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private @Nullable UUID id;
		private @Nullable World level;
		private int permission;
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return id == null ? OWNER : id; }
		@Override public GameProfile getGameProfile() { return new GameProfile(getUUID(), "owner"); }
		@Override public ServerLevel serverLevel() { return Objects.requireNonNull(level); }
		@Override public boolean hasPermissions(int required) { return permission >= required; }
	}

	@NotNullByDefault
	private static final class World extends ServerLevel {
		private @Nullable Server server;
		private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public MinecraftServer getServer() { return Objects.requireNonNull(server); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public boolean isLoaded(BlockPos pos) { return true; }
		@Override public boolean hasChunkAt(BlockPos pos) { return true; }
		@Override public BlockState getBlockState(BlockPos pos) { return Blocks.CHEST.defaultBlockState(); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
	}
}
