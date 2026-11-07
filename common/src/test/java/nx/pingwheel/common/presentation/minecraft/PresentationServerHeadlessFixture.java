package nx.pingwheel.common.presentation.minecraft;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryServer;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Headless production activation for focused presentation seams. Constructor
 * bypass is the existing test-fixture pattern; the registry, policy
 * fingerprint, previews and budget sink all come from the real
 * {@link PresentationServer#activate(net.minecraft.server.MinecraftServer)} path.
 */
@NotNullByDefault
final class PresentationServerHeadlessFixture {
	private PresentationServerHeadlessFixture() {}

	static void activate() {
		InventoryServer.reset();
		PresentationServer.reset();
		PresentationServer.activate(DoubleChestFixture.allocate(Server.class));
	}

	static void reset() {
		InventoryServer.reset();
		PresentationServer.reset();
	}

	@NotNullByDefault
	public static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public DedicatedPlayerList getPlayerList() {
			return players == null ? players = DoubleChestFixture.allocate(Players.class) : players;
		}
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
		@Override public int getTickCount() { return 0; }
	}

	@NotNullByDefault
	public static final class Players extends DedicatedPlayerList {
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return null; }
		@Override public @Unmodifiable List<ServerPlayer> getPlayers() { return List.of(); }
	}
}
