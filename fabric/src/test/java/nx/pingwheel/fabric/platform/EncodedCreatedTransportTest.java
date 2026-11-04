package nx.pingwheel.fabric.platform;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
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
import nx.pingwheel.fabric.PresentationPayloadRegistrationTest;
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

	@Test void actualAdapterQueuesOwnedBodiesForDeferredRegisteredCodecWithoutReserializingRecords() {
		var codec = PresentationPayloadRegistrationTest.registeredCodec();
		List<CustomPacketPayload> queued = new ArrayList<>();
		var transport = new PlatformNetworkServiceImpl((player, payload) -> true, (player, payload) -> queued.add(payload));
		assertInstanceOf(EncodedTransport.class, transport);
		ServerPlayer recipient = recipient();
		for (InventoryResourceKind lane : InventoryResourceKind.values()) {
			for (boolean unwritable : new boolean[] {false, true}) {
				FriendlyByteBuf caller = buffer(); caller.writeInt(1); packet(lane, 5).write(caller); caller.readerIndex(4);
				try {
					PresentationS2CPacket supplied = packet(lane, 99, unwritable ? 0 : 7);
					assertEquals(unwritable, supplied.isCorrupt());
					assertTrue(transport.sendEncodedCreated(supplied, recipient, caller));
					assertEquals(4, caller.readerIndex()); assertEquals(1, caller.refCnt());
					caller.setZero(4, caller.readableBytes());
				} finally { caller.release(); }
			}
		}
		CompletableFuture.runAsync(() -> {
			for (int i = queued.size() - 1; i >= 0; i--) {
				FriendlyByteBuf output = buffer(); FriendlyByteBuf expected = buffer();
				try {
					InventoryResourceKind lane = InventoryResourceKind.values()[i / 2];
					codec.encode(output, queued.get(i)); packet(lane, 5).write(expected);
					assertEquals(expected, output, "registered encoder must write the supplied body, not packet(99)");
					var decoded = assertInstanceOf(PresentationS2CPacket.class, codec.decode(output));
					assertFalse(decoded.isCorrupt()); assertEquals(lane, decoded.content().inventorySnapshot().resourceKind());
					assertEquals(5, decoded.content().inventorySnapshot().amount()); assertFalse(output.isReadable());
				} finally { output.release(); expected.release(); }
			}
		}).join();
	}

	@Test void unavailableChannelDoesNotCopyOrEnqueueAndThrowLeavesCallerOwnershipUnchanged() {
		ServerPlayer recipient = recipient(); FriendlyByteBuf caller = buffer(); packet(InventoryResourceKind.ITEM, 5).write(caller);
		try {
			int reader = caller.readerIndex(), writer = caller.writerIndex();
			var missing = new PlatformNetworkServiceImpl((player, payload) -> false, (player, payload) -> fail("enqueue"));
			assertFalse(missing.sendEncodedCreated(packet(InventoryResourceKind.ITEM, 99), recipient, caller));
			var failure = new IllegalStateException("not enqueued");
			var throwing = new PlatformNetworkServiceImpl((player, payload) -> true, (player, payload) -> { throw failure; });
			assertSame(failure, assertThrows(IllegalStateException.class,
				() -> throwing.sendEncodedCreated(packet(InventoryResourceKind.ITEM, 99), recipient, caller)));
			assertEquals(reader, caller.readerIndex()); assertEquals(writer, caller.writerIndex()); assertEquals(1, caller.refCnt());
			var sent = new ArrayList<CustomPacketPayload>();
			assertTrue(new PlatformNetworkServiceImpl((player, payload) -> true, (player, payload) -> sent.add(payload))
				.sendEncodedCreated(packet(InventoryResourceKind.ITEM, 99), recipient, caller));
			assertEquals(1, sent.size());
		} finally { assertTrue(caller.release()); }
	}

	@Test void ordinaryPresentationSendStillQueuesTheRecordAndStrictReaderRejectsTrailingBody() {
		var codec = PresentationPayloadRegistrationTest.registeredCodec(); var queued = new ArrayList<CustomPacketPayload>();
		var transport = new PlatformNetworkServiceImpl((player, payload) -> true, (player, payload) -> queued.add(payload));
		PresentationS2CPacket original = packet(InventoryResourceKind.ITEM, 31); transport.sendToClient(original, recipient());
		assertSame(original, queued.getFirst()); FriendlyByteBuf body = buffer();
		try {
			codec.encode(body, queued.getFirst()); var decoded = (PresentationS2CPacket) codec.decode(body);
			assertEquals(31, decoded.content().inventorySnapshot().amount());
			body.clear(); codec.encode(body, queued.getFirst()); body.writeByte(0);
			assertTrue(((PresentationS2CPacket) codec.decode(body)).isCorrupt()); assertFalse(body.isReadable());
		} finally { body.release(); }
	}

	@Test void normalWholeAndSuppressedBasicInitialStillUseTheOriginalRecordWire() {
		var codec = PresentationPayloadRegistrationTest.registeredCodec(); var queued = new ArrayList<CustomPacketPayload>();
		var transport = new PlatformNetworkServiceImpl((player, payload) -> true, (player, payload) -> queued.add(payload));
		for (var content : List.of(PresentationReceiptContent.whole(), PresentationReceiptContent.suppressed())) {
			var p = packet(InventoryResourceKind.ITEM, 5);
			var basic = PresentationS2CPacket.created(p.epoch(), p.view(), p.revision(), p.snapshot(), p.ownerName(), p.defaultRef(), content,
				new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false));
			transport.sendToClient(basic, recipient()); assertSame(basic, queued.getLast());
			FriendlyByteBuf body = buffer(); FriendlyByteBuf expected = buffer();
			try {
				codec.encode(body, queued.getLast()); basic.write(expected); assertEquals(expected, body);
				var decoded = (PresentationS2CPacket) codec.decode(body); assertFalse(decoded.isCorrupt()); assertEquals(content, decoded.content());
			} finally { body.release(); expected.release(); }
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
