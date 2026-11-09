package nx.pingwheel.forge.platform;

import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.EventNetworkChannel;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.HeadlessEventChannel;
import nx.pingwheel.common.CommonClient;
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
	private static final ResourceLocation S2C = ResourceLocation.fromNamespaceAndPath("pingforit-s2c", "observation-test");
	private static final ResourceLocation C2S = ResourceLocation.fromNamespaceAndPath("pingforit-c2s", "observation-test");
	private static final Map<ResourceLocation, EventNetworkChannel> CHANNELS = new HashMap<>();
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void observedAndVoidCheckSerializeAndSendOnceWithTheSameEventChannelBody(boolean observed) {
		try (var channel = new ChannelScope(S2C); var packet = new CountingPacket()) {
			var recipient = recipient();
			var checks = new AtomicInteger();
			var sends = new AtomicInteger();
			var service = new PlatformNetworkServiceImpl((current, player) -> {
				assertSame(channel.channel, current); assertSame(recipient, player);
				checks.incrementAndGet(); return true;
			}, (player, outgoing) -> {
				assertSame(recipient, player); assertEquals(1, packet.writes);
				var payload = assertInstanceOf(ForgePayload.class,
					assertInstanceOf(ClientboundCustomPayloadPacket.class, outgoing).payload());
				assertEquals(S2C, payload.id());
				var output = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
				try { payload.write(output); assertEquals(17, output.readInt()); assertFalse(output.isReadable()); }
				finally { output.release(); }
				sends.incrementAndGet();
			});

			if (observed) assertEquals(TransportHandoff.HANDED_OFF, service.sendToClientObserved(packet, recipient));
			else service.sendToClient(packet, recipient);
			assertEquals(1, checks.get()); assertEquals(1, sends.get()); assertEquals(1, packet.writes);
		}
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void missingOrUnnegotiatedRouteNeverSerializesSendsOrFallsBack(boolean observed) {
		try (var channel = new ChannelScope(S2C); var packet = new CountingPacket()) {
			PlatformNetworkServiceImpl.CHANNEL_MAP.remove(S2C);
			var missing = new PlatformNetworkServiceImpl((current, player) -> fail("missing local channel"),
				(player, outgoing) -> fail("missing route must not send"));
			assertMissing(missing, packet, observed);

			PlatformNetworkServiceImpl.CHANNEL_MAP.put(S2C, channel.channel);
			var checks = new AtomicInteger();
			var unnegotiated = new PlatformNetworkServiceImpl((current, player) -> { checks.incrementAndGet(); return false; },
				(player, outgoing) -> fail("unnegotiated route must not send or retry"));
			assertMissing(unnegotiated, packet, observed);
			assertEquals(1, checks.get()); assertEquals(0, packet.writes);
		}
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void capabilityEncodingAndDispatchFailuresPropagateWithoutRetry(boolean observed) {
		try (var channel = new ChannelScope(S2C); var packet = new CountingPacket()) {
			var failure = new IllegalStateException("failed attempt");
			var checks = new AtomicInteger();
			var sends = new AtomicInteger();
			var rejecting = new PlatformNetworkServiceImpl((current, player) -> { checks.incrementAndGet(); throw failure; },
				(player, outgoing) -> fail("failed capability must not send"));
			assertSame(failure, assertThrows(IllegalStateException.class, () -> attempt(rejecting, packet, observed)));
			assertEquals(1, checks.get()); assertEquals(0, packet.writes);

			var service = new PlatformNetworkServiceImpl((current, player) -> { checks.incrementAndGet(); return true; },
				(player, outgoing) -> { sends.incrementAndGet(); throw failure; });
			packet.writeFailure = failure;
			assertSame(failure, assertThrows(IllegalStateException.class, () -> attempt(service, packet, observed)));
			assertEquals(2, checks.get()); assertEquals(0, sends.get()); assertEquals(1, packet.writes);
			packet.writeFailure = null;
			assertSame(failure, assertThrows(IllegalStateException.class, () -> attempt(service, packet, observed)));
			assertEquals(3, checks.get()); assertEquals(1, sends.get()); assertEquals(2, packet.writes);
		}
	}

	@Test void capabilitiesDistinguishUnreadyMissingAndNegotiatedExactRoutes() {
		try (var clientbound = new ChannelScope(S2C); var serverbound = new ChannelScope(C2S)) {
			var checks = new AtomicInteger();
			assertEquals(RouteSupport.UNKNOWN, PlatformNetworkServiceImpl.routeSupport(C2S, false, current -> fail("not ready")));
			assertEquals(RouteSupport.AVAILABLE, PlatformNetworkServiceImpl.routeSupport(C2S, true,
				current -> { checks.incrementAndGet(); return current == serverbound.channel; }));
			assertEquals(RouteSupport.UNAVAILABLE, PlatformNetworkServiceImpl.routeSupport(S2C, true,
				current -> { checks.incrementAndGet(); return current == serverbound.channel; }));
			assertEquals(RouteSupport.AVAILABLE, PlatformNetworkServiceImpl.routeSupport(S2C, true,
				current -> { checks.incrementAndGet(); return current == clientbound.channel; }));
			assertEquals(3, checks.get());
			PlatformNetworkServiceImpl.CHANNEL_MAP.remove(S2C);
			assertEquals(RouteSupport.UNAVAILABLE, PlatformNetworkServiceImpl.routeSupport(S2C, true, current -> fail("no local route")));
			assertEquals(RouteSupport.UNKNOWN, new PlatformNetworkServiceImpl().clientboundSupport(recipient(), S2C));
			var failure = new IllegalStateException("capability failed");
			assertSame(failure, assertThrows(IllegalStateException.class,
				() -> PlatformNetworkServiceImpl.routeSupport(C2S, true, current -> { throw failure; })));
		}
	}

	@Test void absentClientOrPlayListenerDoesNotBecomeUnsupported() {
		@Nullable Minecraft previous = CommonClient.Game;
		try {
			var service = new PlatformNetworkServiceImpl();
			CommonClient.Game = null;
			assertEquals(RouteSupport.UNKNOWN, service.serverboundSupport(C2S));
			CommonClient.Game = allocate(Minecraft.class);
			assertEquals(RouteSupport.UNKNOWN, service.serverboundSupport(C2S));
		} finally { CommonClient.Game = previous; }
	}

	private static void assertMissing(PlatformNetworkServiceImpl service, IPacket packet, boolean observed) {
		if (observed) assertEquals(TransportHandoff.NOT_HANDED_OFF, service.sendToClientObserved(packet, recipient()));
		else service.sendToClient(packet, recipient());
	}

	private static void attempt(PlatformNetworkServiceImpl service, IPacket packet, boolean observed) {
		if (observed) service.sendToClientObserved(packet, recipient());
		else service.sendToClient(packet, recipient());
	}

	@NotNullByDefault
	private static final class CountingPacket implements IPacket, AutoCloseable {
		private int writes;
		private @Nullable FriendlyByteBuf encoded;
		private @Nullable IllegalStateException writeFailure;
		@Override public void write(FriendlyByteBuf buf) {
			if (encoded != null) encoded.release();
			encoded = buf; writes++;
			if (writeFailure != null) throw writeFailure;
			buf.writeInt(17);
		}
		@Override public boolean isCorrupt() { return false; }
		@Override public ResourceLocation getId() { return S2C; }
		@Override public Type<CountingPacket> type() { return new Type<>(getId()); }
		@Override public void close() { if (encoded != null) encoded.release(); }
	}

	@NotNullByDefault
	private static final class ChannelScope implements AutoCloseable {
		private final ResourceLocation id;
		private final EventNetworkChannel channel;
		private final @Nullable EventNetworkChannel previous;
		private ChannelScope(ResourceLocation id) {
			this.id = id;
			@Nullable EventNetworkChannel existing = PlatformNetworkServiceImpl.CHANNEL_MAP.get(id);
			channel = existing == null ? CHANNELS.computeIfAbsent(id, HeadlessEventChannel::create) : existing;
			previous = PlatformNetworkServiceImpl.CHANNEL_MAP.put(id, channel);
		}
		@Override public void close() {
			if (previous == null) PlatformNetworkServiceImpl.CHANNEL_MAP.remove(id);
			else PlatformNetworkServiceImpl.CHANNEL_MAP.put(id, previous);
		}
	}

	private static ServerPlayer recipient() {
		return allocate(ServerPlayer.class);
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
