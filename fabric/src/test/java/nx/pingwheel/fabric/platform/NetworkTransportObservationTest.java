package nx.pingwheel.fabric.platform;

import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.RouteSupport;
import nx.pingwheel.common.platform.TransportHandoff;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class NetworkTransportObservationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void observedAndVoidUseExactlyOneRouteCheckAndOneDeferredEncoding(boolean observed) {
		var packet = new CountingPacket();
		var recipient = recipient();
		var checks = new AtomicInteger();
		var sends = new AtomicInteger();
		var service = new PlatformNetworkServiceImpl((player, payload) -> {
			assertSame(recipient, player); assertSame(packet, payload);
			checks.incrementAndGet(); return true;
		}, (player, payload) -> {
			assertSame(recipient, player); assertSame(packet, payload);
			assertEquals(0, packet.writes, "Fabric owns encoding; observation must not pre-encode");
			var output = new FriendlyByteBuf(Unpooled.buffer());
			try { ((IPacket) payload).write(output); assertEquals(17, output.readInt()); }
			finally { output.release(); }
			sends.incrementAndGet();
		});

		if (observed) assertEquals(TransportHandoff.HANDED_OFF, service.sendToClientObserved(packet, recipient));
		else service.sendToClient(packet, recipient);
		assertEquals(1, checks.get()); assertEquals(1, sends.get()); assertEquals(1, packet.writes);
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void unavailableRouteReturnsWithoutEncodingSendingOrFallback(boolean observed) {
		var packet = new CountingPacket();
		var checks = new AtomicInteger();
		var service = new PlatformNetworkServiceImpl((player, payload) -> { checks.incrementAndGet(); return false; },
			(player, payload) -> fail("missing route must not send or retry"));

		if (observed) assertEquals(TransportHandoff.NOT_HANDED_OFF, service.sendToClientObserved(packet, recipient()));
		else service.sendToClient(packet, recipient());
		assertEquals(1, checks.get()); assertEquals(0, packet.writes);
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void sendAndCapabilityFailuresPropagateWithoutSecondAttempt(boolean observed) {
		var packet = new CountingPacket();
		var checks = new AtomicInteger();
		var sends = new AtomicInteger();
		var failure = new IllegalStateException("dispatch failed");
		var service = new PlatformNetworkServiceImpl((player, payload) -> { checks.incrementAndGet(); return true; },
			(player, payload) -> { sends.incrementAndGet(); throw failure; });
		assertSame(failure, assertThrows(IllegalStateException.class, () -> attempt(service, packet, observed)));
		assertEquals(1, checks.get()); assertEquals(1, sends.get()); assertEquals(0, packet.writes);

		var rejecting = new PlatformNetworkServiceImpl((player, payload) -> { checks.incrementAndGet(); throw failure; },
			(player, payload) -> fail("failed capability must not send"));
		assertSame(failure, assertThrows(IllegalStateException.class, () -> attempt(rejecting, packet, observed)));
		assertEquals(2, checks.get()); assertEquals(1, sends.get());
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void transportEncodingFailureEscapesWithoutAnotherEncodingOrSend(boolean observed) {
		var packet = new CountingPacket();
		var failure = new IllegalStateException("encoding failed");
		packet.writeFailure = failure;
		var checks = new AtomicInteger();
		var sends = new AtomicInteger();
		var service = new PlatformNetworkServiceImpl((player, payload) -> { checks.incrementAndGet(); return true; },
			(player, payload) -> {
				sends.incrementAndGet();
				var output = new FriendlyByteBuf(Unpooled.buffer());
				try { ((IPacket) payload).write(output); }
				finally { output.release(); }
			});
		assertSame(failure, assertThrows(IllegalStateException.class, () -> attempt(service, packet, observed)));
		assertEquals(1, checks.get()); assertEquals(1, sends.get()); assertEquals(1, packet.writes);
	}

	@Test void capabilityUsesOnlyTheExactDirectionAdvertisementAndPreservesUnknownRegistration() {
		var c2s = ResourceLocation.fromNamespaceAndPath("pingforit-c2s", "capability-test");
		var s2c = ResourceLocation.fromNamespaceAndPath("pingforit-s2c", "capability-test");
		assertEquals(RouteSupport.UNKNOWN, PlatformNetworkServiceImpl.routeSupport(c2s, null));
		assertEquals(RouteSupport.UNKNOWN, PlatformNetworkServiceImpl.routeSupport(c2s, Set.of()));
		assertEquals(RouteSupport.AVAILABLE, PlatformNetworkServiceImpl.routeSupport(c2s, Set.of(c2s)));
		assertEquals(RouteSupport.UNAVAILABLE, PlatformNetworkServiceImpl.routeSupport(s2c, Set.of(c2s)));
		assertEquals(RouteSupport.AVAILABLE, PlatformNetworkServiceImpl.routeSupport(s2c, Set.of(s2c)));
		assertEquals(RouteSupport.UNKNOWN, new PlatformNetworkServiceImpl().serverboundSupport(c2s));
		assertEquals(RouteSupport.UNKNOWN, new PlatformNetworkServiceImpl().clientboundSupport(recipient(), s2c));
	}

	private static void attempt(PlatformNetworkServiceImpl service, IPacket packet, boolean observed) {
		if (observed) service.sendToClientObserved(packet, recipient());
		else service.sendToClient(packet, recipient());
	}

	@NotNullByDefault
	private static final class CountingPacket implements IPacket {
		private int writes;
		private @Nullable IllegalStateException writeFailure;
		@Override public void write(FriendlyByteBuf buf) {
			writes++;
			if (writeFailure != null) throw writeFailure;
			buf.writeInt(17);
		}
		@Override public boolean isCorrupt() { return false; }
		@Override public ResourceLocation getId() { return ResourceLocation.fromNamespaceAndPath("pingforit-s2c", "observation-test"); }
		@Override public Type<CountingPacket> type() { return new Type<>(getId()); }
	}

	private static ServerPlayer recipient() {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull((ServerPlayer) unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), ServerPlayer.class));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
