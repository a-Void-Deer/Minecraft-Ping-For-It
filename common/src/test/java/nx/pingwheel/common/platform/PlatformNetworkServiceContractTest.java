package nx.pingwheel.common.platform;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.network.IPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PlatformNetworkServiceContractTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void legacyObservedSendCallsTheOriginalOnceWithoutClaimingHandoffOrEncoding() {
		var packet = new Packet();
		var player = recipient();
		var sends = new AtomicInteger();
		IPlatformNetworkService service = legacy(packet, player, sends, () -> {});

		assertEquals(TransportHandoff.UNOBSERVED, service.sendToClientObserved(packet, player));
		assertEquals(1, sends.get());
		service.sendToClient(packet, player);
		assertEquals(2, sends.get());
	}

	@Test void legacyThrowIsNotConvertedToAnObservedOutcomeOrRetried() {
		var packet = new Packet();
		var player = recipient();
		var sends = new AtomicInteger();
		var failure = new IllegalStateException("send failed");
		var service = legacy(packet, player, sends, () -> { throw failure; });

		assertSame(failure, assertThrows(IllegalStateException.class, () -> service.sendToClientObserved(packet, player)));
		assertEquals(1, sends.get());
	}

	@Test void defaultCapabilitiesAreUnknownAndNeverProbeBySending() {
		var packet = new Packet();
		var player = recipient();
		var sends = new AtomicInteger();
		var service = legacy(packet, player, sends, () -> fail("capability queries must not send"));

		assertEquals(RouteSupport.UNKNOWN, service.serverboundSupport(packet.getId()));
		assertEquals(RouteSupport.UNKNOWN, service.clientboundSupport(player, packet.getId()));
		assertEquals(0, sends.get());
	}

	private static IPlatformNetworkService legacy(IPacket expected, ServerPlayer recipient,
		AtomicInteger sends, Runnable action) {
		return new IPlatformNetworkService() {
			@Override public void sendToServer(IPacket packet) { fail("unexpected C2S send"); }
			@Override public void sendToClient(IPacket packet, ServerPlayer player) {
				assertSame(expected, packet);
				assertSame(recipient, player);
				sends.incrementAndGet();
				action.run();
			}
		};
	}

	@NotNullByDefault
	private static final class Packet implements IPacket {
		@Override public void write(FriendlyByteBuf buf) { fail("default observation must not encode"); }
		@Override public boolean isCorrupt() { return false; }
		@Override public ResourceLocation getId() { return ResourceLocation.fromNamespaceAndPath("pingforit-s2c", "observation-test"); }
		@Override public Type<Packet> type() { return new Type<>(getId()); }
	}

	private static ServerPlayer recipient() {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe");
			var field = unsafe.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return Objects.requireNonNull((ServerPlayer) unsafe.getMethod("allocateInstance", Class.class)
				.invoke(field.get(null), ServerPlayer.class));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
