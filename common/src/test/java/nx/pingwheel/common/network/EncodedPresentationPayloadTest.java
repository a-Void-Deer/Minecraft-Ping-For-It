package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class EncodedPresentationPayloadTest {

	@Test void frozenBodyDoesNotReserializeRecordAndSurvivesSourceReleaseAndRepeatedWrites() {
		PresentationS2CPacket source = packet(5);
		FriendlyByteBuf input = buffer();
		input.writeInt(123); source.write(input); input.readerIndex(4);
		byte @Unmodifiable [] expected = new byte[input.readableBytes()];
		input.getBytes(input.readerIndex(), expected);
		PresentationS2CPacket unwritable = packet(99, 0);
		assertTrue(unwritable.isCorrupt(), "any attempted second record serialization must throw");
		assertThrows(IllegalArgumentException.class, () -> unwritable.write(input));
		EncodedPresentationPayload frozen = EncodedPresentationPayload.copyCreated(unwritable, input);
		assertEquals(4, input.readerIndex());
		assertEquals(1, input.refCnt());
		input.setZero(input.readerIndex(), input.readableBytes());
		input.release();
		for (int i = 0; i < 2; i++) {
			FriendlyByteBuf output = buffer();
			try {
				EncodedPresentationPayload.STREAM_CODEC.encode(output, frozen);
				byte[] actual = new byte[output.readableBytes()]; output.getBytes(0, actual);
				assertArrayEquals(expected, actual);
				PresentationS2CPacket decoded = (PresentationS2CPacket) EncodedPresentationPayload.STREAM_CODEC.decode(output);
				assertFalse(decoded.isCorrupt()); assertEquals(5, decoded.content().inventorySnapshot().amount());
				assertEquals(0, output.readableBytes());
			} finally { output.release(); }
		}
	}

	@Test void ordinaryRecordStillWritesAndReaderRejectsTrailingBytesAndOversize() {
		FriendlyByteBuf output = buffer(); FriendlyByteBuf reference = buffer();
		try {
			PresentationS2CPacket original = packet(17);
			EncodedPresentationPayload.STREAM_CODEC.encode(output, original); original.write(reference);
			assertEquals(reference, output);
			output.writeByte(0);
			assertTrue(((PresentationS2CPacket) EncodedPresentationPayload.STREAM_CODEC.decode(output)).isCorrupt());
			assertEquals(0, output.readableBytes());
			output.clear().writeZero(PresentationS2CPacket.MAX_S2C_BODY_BYTES + 1);
			assertThrows(IllegalArgumentException.class, () -> EncodedPresentationPayload.copyCreated(original, output));
		} finally { output.release(); reference.release(); }
	}

	@Test void copyIsBoundedAtExactBodyCeilingAndRejectsEmptyOrNonCreatedBeforeAllocation() {
		FriendlyByteBuf source = new FriendlyByteBuf(Unpooled.buffer(PresentationS2CPacket.MAX_S2C_BODY_BYTES,
			PresentationS2CPacket.MAX_S2C_BODY_BYTES));
		FriendlyByteBuf target = new FriendlyByteBuf(Unpooled.buffer(PresentationS2CPacket.MAX_S2C_BODY_BYTES,
			PresentationS2CPacket.MAX_S2C_BODY_BYTES));
		try {
			source.writeZero(source.capacity()); source.setByte(0, 42); source.setByte(source.writerIndex() - 1, 81);
			var copied = EncodedPresentationPayload.copyCreated(packet(1), source);
			copied.writeTo(target); assertEquals(source, target); assertEquals(0, source.readerIndex());
			source.clear(); assertThrows(IllegalArgumentException.class, () -> EncodedPresentationPayload.copyCreated(packet(1), source));
			source.writeByte(1);
			assertThrows(IllegalArgumentException.class, () -> EncodedPresentationPayload.copyCreated(
				PresentationS2CPacket.offer(7, Map.of(), Map.of()), source));
		} finally { source.release(); target.release(); }
	}

	@Test void sameRouteArbitraryPayloadCannotEscapeTheClosedOutboundEncoderBranches() {
		FriendlyByteBuf output = buffer();
		CustomPacketPayload unrelated = () -> EncodedPresentationPayload.PACKET_TYPE;
		try {
			assertThrows(IllegalArgumentException.class, () -> EncodedPresentationPayload.STREAM_CODEC.encode(output, unrelated));
			assertEquals(0, output.writerIndex());
		} finally { output.release(); }
	}

	private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }

	private static PresentationS2CPacket packet(long amount) {
		return packet(amount, 7);
	}

	private static PresentationS2CPacket packet(long amount, long epoch) {
		Target target = new Target.BlockTarget("minecraft:overworld", 1, 64, 2, "minecraft:chest");
		MarkerSnapshot marker = new MarkerSnapshot(new MarkerId(91), new UUID(1, 2), target, "block", "attention",
			new MarkerAnchor(1, 64, 2), 1, 100);
		return PresentationS2CPacket.created(epoch, 3, 5, marker, "Owner",
			PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME),
			PresentationReceiptContent.inventory(new InventoryCreationSnapshot(InventoryResourceKind.ITEM,
				"row", "minecraft:stone", "Stone", null, amount, false, "request", false, true)),
			new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false));
	}
}
