package nx.pingwheel.common.presentation.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryProductionCreateTest {
	private static final UUID OWNER = new UUID(700, 1);
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void productionHostAndServerCoreAdmitFluidOnlyPolicyAndRejectItemBeforeAdmission() throws Exception {
		var server = DoubleChestFixture.allocate(Server.class); var level = DoubleChestFixture.allocate(World.class);
		var player = DoubleChestFixture.allocate(Player.class); var players = DoubleChestFixture.allocate(Players.class);
		server.players = players; players.player = player; player.level = level; level.server = server;
		var position = net.minecraft.world.entity.Entity.class.getDeclaredField("position"); position.setAccessible(true); position.set(player, new Vec3(1.5, 64.5, 3.5));
		var config = ServerConfig.HANDLER.getConfig(); int oldRate = config.getRateLimit(); ChannelMode oldMode = config.getDefaultChannelMode();
		var active = field(ServerCore.class, "ACTIVE_SERVER"); var storeField = field(ServerCore.class, "MARKER_STORE");
		var presentationActive = field(PresentationServer.class, "activeServer");
		@Nullable Object oldActive = active.get(null), oldStore = storeField.get(null), oldPresentation = presentationActive.get(null);
		@SuppressWarnings("unchecked") Map<UUID, PresentationServer.Session> sessions = (Map<UUID, PresentationServer.Session>) field(PresentationServer.class, "SESSIONS").get(null);
		@Nullable PresentationServer.Session oldSession = sessions.get(OWNER);
		var store = new ServerMarkerStore(new MarkerIdSource());
		try {
			config.setRateLimit(0); config.setDefaultChannelMode(ChannelMode.GLOBAL); active.set(null, server); storeField.set(null, store); presentationActive.set(null, server);
			var session = new PresentationServer.Session(100, Map.of(InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA), Map.of());
			session.ready = true; session.view = 1; session.inventoryTypes = Set.of(); session.fluidTypes = Set.of("entity_block"); sessions.put(OWNER, session);
			assertEquals(Set.of("entity_block"), PresentationServer.inventoryPolicy(OWNER).orElseThrow().fluidTypes());
			Class<?> hostClass = Class.forName("nx.pingwheel.common.presentation.inventory.minecraft.InventoryServer$Host");
			var ctor = hostClass.getDeclaredConstructors()[0]; ctor.setAccessible(true);
			try (var runtime = new nx.pingwheel.common.presentation.inventory.InventoryRuntime(input -> Optional.empty(), 16_000_000)) {
				var host = (InventoryBackend.Host) ctor.newInstance(server, runtime, nx.pingwheel.common.presentation.inventory.InventoryRuntime.DisplayReprojector.none());
				var frozen = new InventoryBackend.Opened(new Target.BlockTarget("minecraft:overworld", 1, 64, 3, "minecraft:chest"), "entity_block", "attention");
				var admissions = new AtomicInteger();
				var item = host.create(OWNER, frozen, InventoryResourceKind.ITEM, (t,k,type,a) -> { admissions.incrementAndGet(); return null; });
				assertNull(item.marker()); assertEquals(0, admissions.get());
				var fluid = host.create(OWNER, frozen, InventoryResourceKind.FLUID, (t,k,type,a) -> { admissions.incrementAndGet(); return null; });
				assertNotNull(fluid.marker()); assertEquals(1, admissions.get()); assertEquals(1, store.size());
			}
		} finally {
			config.setRateLimit(oldRate); config.setDefaultChannelMode(oldMode); active.set(null, oldActive); storeField.set(null, oldStore); presentationActive.set(null, oldPresentation);
			if (oldSession == null) sessions.remove(OWNER); else sessions.put(OWNER, oldSession);
		}
	}
	private static java.lang.reflect.Field field(Class<?> owner, String name) throws Exception { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public DedicatedPlayerList getPlayerList() { return java.util.Objects.requireNonNull(players); }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
	}
	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable Player player;
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return id.equals(OWNER) ? player : null; }
		@Override public List<ServerPlayer> getPlayers() { return List.of(java.util.Objects.requireNonNull(player)); }
	}
	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private @Nullable World level;
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return OWNER; }
		@Override public ServerLevel serverLevel() { return java.util.Objects.requireNonNull(level); }
		@Override public boolean hasPermissions(int level) { return false; }
	}
	@NotNullByDefault
	private static final class World extends ServerLevel {
		private @Nullable Server server;
		private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public MinecraftServer getServer() { return java.util.Objects.requireNonNull(server); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public boolean isLoaded(BlockPos pos) { return true; }
		@Override public BlockState getBlockState(BlockPos pos) { return Blocks.CHEST.defaultBlockState(); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
	}
}
