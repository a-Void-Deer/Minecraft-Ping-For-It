package nx.pingwheel.forge;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.HeadlessEventChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ForgeClientPacketDirectionTest {

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void genericClientReceiverDecodesOnlyClientboundPayloads() {
		var channel = HeadlessEventChannel.wrap(ForgeMain.RATE_LIMIT_POLICY_CHANNEL_S2C);
		var decoded = new ArrayList<String>();
		var delivered = new ArrayList<String>();
		ForgeClient.registerPacketHandler(channel,
			body -> {
				decoded.add(body.readUtf());
				return decoded.getLast();
			},
			delivered::add);

		var serverbound = new HeldContext(new Connection(PacketFlow.SERVERBOUND));
		var serverboundBody = message("serverbound");
		try {
			receive(channel, serverbound, serverboundBody);
			assertTrue(serverboundBody.isReadable(), "a serverbound payload must not be decoded by the client receiver");
			assertTrue(decoded.isEmpty());
			assertTrue(serverbound.tasks.isEmpty(), "a serverbound payload must not be queued for client execution");
			assertTrue(delivered.isEmpty());
			assertTrue(serverbound.getPacketHandled());
		} finally {
			serverboundBody.release();
		}

		var clientbound = new HeldContext(new Connection(PacketFlow.CLIENTBOUND));
		var clientboundBody = message("clientbound");
		try {
			receive(channel, clientbound, clientboundBody);
			assertEquals(1, decoded.size());
			assertEquals("clientbound", decoded.getFirst());
			assertEquals(1, clientbound.tasks.size());
			assertTrue(delivered.isEmpty(), "the client callback must wait for queued client work");
			clientbound.tasks.removeFirst().run();
			assertEquals(1, delivered.size());
			assertEquals("clientbound", delivered.getFirst());
			assertFalse(clientboundBody.isReadable());
			assertTrue(clientbound.getPacketHandled());
		} finally {
			clientboundBody.release();
		}
	}

	private static FriendlyByteBuf message(String value) {
		var body = new FriendlyByteBuf(Unpooled.buffer());
		body.writeUtf(value);
		return body;
	}

	private static void receive(HeadlessEventChannel channel, HeldContext context, FriendlyByteBuf body) {
		channel.receive(new CustomPayloadEvent(channel.getName(), ForgePayload.create(channel.getName(), body), context, 0));
	}

	@NotNullByDefault
	private static final class HeldContext extends CustomPayloadEvent.Context {
		private final List<Runnable> tasks = new ArrayList<>();

		private HeldContext(Connection connection) {
			super(connection);
		}

		@Override public CompletableFuture<Void> enqueueWork(Runnable action) {
			tasks.add(action);
			return CompletableFuture.completedFuture(null);
		}
	}
}
