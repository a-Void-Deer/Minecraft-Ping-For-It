package nx.pingwheel.forge.platform;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.EventNetworkChannel;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.HeadlessEventChannel;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import nx.pingwheel.forge.ForgeClient;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.minecraft.InventoryCreationReceiptTransport.EncodedTransport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class EncodedCreatedTransportTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void publicServiceConstructorExposesTheProductionEncodedHandoff() {
		assertInstanceOf(EncodedTransport.class, new PlatformNetworkServiceImpl());
	}

	@Test void actualForgeProtocolQueuesOwnedEncoderWithExactlyExistingEventChannelBody() {
		HeadlessEventChannel channel = channel(); var queued = new ArrayList<Packet<?>>();
		var transport = new PlatformNetworkServiceImpl((current, player) -> { assertSame(channel, current); return true; },
			(player, packet) -> queued.add(packet));
		assertInstanceOf(EncodedTransport.class, transport); ServerPlayer recipient = recipient();
		for (InventoryResourceKind lane : InventoryResourceKind.values()) {
			for (boolean unwritable : new boolean[] {false, true}) {
				FriendlyByteBuf caller = buffer(); caller.writeInt(1); packet(lane, 5).write(caller); caller.readerIndex(4);
				try {
					PresentationS2CPacket supplied = packet(lane, 99, unwritable ? 0 : 7);
					assertEquals(unwritable, supplied.isCorrupt());
					assertTrue(transport.sendEncodedCreated(supplied, recipient, caller));
					assertEquals(4, caller.readerIndex()); assertEquals(1, caller.refCnt()); caller.setZero(4, caller.readableBytes());
				} finally { caller.release(); }
			}
		}
		var received = new ArrayList<PresentationS2CPacket>();
		ForgeClient.registerPacketHandler(channel, PresentationS2CPacket::readSafe, received::add);
		CompletableFuture.runAsync(() -> {
			for (int i = queued.size() - 1; i >= 0; i--) {
				var packet = assertInstanceOf(ClientboundCustomPayloadPacket.class, queued.get(i));
				var payload = assertInstanceOf(ForgePayload.class, packet.payload());
				assertEquals(channel.getName(), payload.id()); assertNull(payload.data());
				FriendlyByteBuf output = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
				FriendlyByteBuf body = buffer(); FriendlyByteBuf expected = buffer();
				try {
					InventoryResourceKind lane = InventoryResourceKind.values()[i / 2]; payload.write(output);
					packet(lane, 5).write(body); channel.encode(expected, body);
					assertEquals(expected, output, "Forge protocol encoder must not add a discriminator or a second length");
					var context = new ImmediateContext();
					var event = new CustomPayloadEvent(channel.getName(), ForgePayload.create(channel.getName(), output), context, 0);
					channel.receive(event); assertTrue(context.getPacketHandled());
					PresentationS2CPacket decoded = received.getLast();
					assertFalse(decoded.isCorrupt()); assertEquals(lane, decoded.content().inventorySnapshot().resourceKind());
					assertEquals(5, decoded.content().inventorySnapshot().amount()); assertFalse(output.isReadable());
				} finally { output.release(); body.release(); expected.release(); }
			}
		}).join();
	}

	@Test void missingOrUnnegotiatedChannelHasNoHandoffAndSendThrowDoesNotRetainCallerBuffer() {
		ServerPlayer recipient = recipient(); FriendlyByteBuf caller = buffer(); packet(InventoryResourceKind.ITEM, 5).write(caller);
		EventNetworkChannel channel = channel();
		try {
			PlatformNetworkServiceImpl.CHANNEL_MAP.remove(PresentationS2CPacket.PACKET_ID);
			var missing = new PlatformNetworkServiceImpl((current, player) -> fail("lookup must stop"), (player, packet) -> fail("enqueue"));
			assertFalse(missing.sendEncodedCreated(packet(InventoryResourceKind.ITEM, 99), recipient, caller));
			PlatformNetworkServiceImpl.CHANNEL_MAP.put(PresentationS2CPacket.PACKET_ID, channel);
			assertFalse(new PlatformNetworkServiceImpl((current, player) -> false, (player, packet) -> fail("enqueue"))
				.sendEncodedCreated(packet(InventoryResourceKind.ITEM, 99), recipient, caller));
			var failure = new IllegalStateException("not enqueued");
			var throwing = new PlatformNetworkServiceImpl((current, player) -> true, (player, packet) -> { throw failure; });
			int reader = caller.readerIndex(), writer = caller.writerIndex();
			assertSame(failure, assertThrows(IllegalStateException.class,
				() -> throwing.sendEncodedCreated(packet(InventoryResourceKind.ITEM, 99), recipient, caller)));
			assertEquals(reader, caller.readerIndex()); assertEquals(writer, caller.writerIndex()); assertEquals(1, caller.refCnt());
			var queued = new ArrayList<Packet<?>>();
			assertTrue(new PlatformNetworkServiceImpl((current, player) -> true, (player, packet) -> queued.add(packet))
				.sendEncodedCreated(packet(InventoryResourceKind.ITEM, 99), recipient, caller)); assertEquals(1, queued.size());
		} finally { assertTrue(caller.release()); }
	}

	@Test void unchangedEventChannelEncodesNormalBasicAndOtherPacketsWithoutNewFraming() {
		var channel = channel(); var p = packet(InventoryResourceKind.ITEM, 5);
		for (var content : java.util.List.of(PresentationReceiptContent.whole(), PresentationReceiptContent.suppressed())) {
			var basic = PresentationS2CPacket.created(p.epoch(), p.view(), p.revision(), p.snapshot(), p.ownerName(), p.defaultRef(), content,
				new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false));
			FriendlyByteBuf encoded = buffer(); FriendlyByteBuf wire = buffer();
			try {
				basic.write(encoded); channel.encode(wire, encoded); assertEquals(encoded, wire);
				var decoded = PresentationS2CPacket.readSafe(wire); assertFalse(decoded.isCorrupt()); assertEquals(content, decoded.content());
			} finally { encoded.release(); wire.release(); }
		}
		FriendlyByteBuf encoded = buffer(); FriendlyByteBuf wire = buffer();
		try {
			var policy = new nx.pingwheel.common.network.RateLimitPolicyS2CPacket(3, 1000);
			policy.write(encoded); channel.encode(wire, encoded);
			assertEquals(policy, nx.pingwheel.common.network.RateLimitPolicyS2CPacket.readSafe(wire));
			encoded.clear(); wire.clear(); var hello = nx.pingwheel.common.network.PresentationC2SPacket.hello(Map.of("minecraft:basic", 1));
			hello.write(encoded); channel.encode(wire, encoded); assertEquals(hello, nx.pingwheel.common.network.PresentationC2SPacket.readSafe(wire));
			wire.clear(); channel.encode(wire, encoded); wire.writeByte(0);
			assertTrue(nx.pingwheel.common.network.PresentationC2SPacket.readSafe(wire).isCorrupt()); assertFalse(wire.isReadable());
		} finally { encoded.release(); wire.release(); }
	}

	private static HeadlessEventChannel channel() {
		return (HeadlessEventChannel) PlatformNetworkServiceImpl.CHANNEL_MAP.computeIfAbsent(PresentationS2CPacket.PACKET_ID,
			HeadlessEventChannel::create);
	}
	@NotNullByDefault
	private static final class ImmediateContext extends CustomPayloadEvent.Context {
		ImmediateContext() { super(new Connection(PacketFlow.CLIENTBOUND)); }
		@Override public CompletableFuture<Void> enqueueWork(Runnable action) {
			action.run(); return CompletableFuture.completedFuture(null);
		}
	}
	private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }
	private static ServerPlayer recipient() {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull((ServerPlayer) unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), ServerPlayer.class));
		} catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
	}
	private static PresentationS2CPacket packet(InventoryResourceKind lane, long amount) {
		return packet(lane, amount, 7);
	}
	private static PresentationS2CPacket packet(InventoryResourceKind lane, long amount, long epoch) {
		Target target = new Target.BlockTarget("minecraft:overworld", 1, 64, 2, "minecraft:chest");
		var marker = new MarkerSnapshot(new MarkerId(91), new UUID(1, 2), target, "block", "attention", new MarkerAnchor(1, 64, 2), 1, 100);
		return PresentationS2CPacket.created(epoch, 3, 5, marker, "Owner", PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME),
			PresentationReceiptContent.inventory(new InventoryCreationSnapshot(lane, "row", lane == InventoryResourceKind.ITEM ? "minecraft:stone" : "minecraft:water",
				"Resource", null, amount, false, "request", false, true)),
			new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false));
	}
}
