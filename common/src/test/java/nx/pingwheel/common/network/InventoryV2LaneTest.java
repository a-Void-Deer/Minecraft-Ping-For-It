package nx.pingwheel.common.network;

import java.util.List;
import java.util.Set;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.presentation.inventory.InventoryFluidDisplayCodec;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryV2LaneTest {
	private static FriendlyByteBuf frame(IPacket packet) { var buf = new FriendlyByteBuf(Unpooled.buffer()); packet.write(buf); return buf; }
	@Test void fluidSelectAndResyncCarryLaneBeforeRequestAndRetainAllOpaqueFences() {
		for (var packet : List.of(
			InventoryC2SPacket.select(InventoryResourceKind.FLUID, 11, 12, 13, 14, 15, 16, 17, "opaque-not-a-fluid-id", "attention"),
			InventoryC2SPacket.resync(InventoryResourceKind.FLUID, 11, 15, new MarkerId(18)).stamp(12, 13))) {
			var buf = frame(packet);
			try {
				assertEquals(packet.kind().name(), buf.readUtf()); assertEquals(2, buf.readVarInt());
				assertEquals(11, buf.readLong()); assertEquals(12, buf.readLong()); assertEquals(13, buf.readLong());
				assertEquals(1, buf.readUnsignedByte()); assertEquals(15, buf.readLong());
				buf.readerIndex(0); assertEquals(packet, InventoryC2SPacket.readSafe(buf)); assertFalse(buf.isReadable());
			} finally { buf.release(); }
		}
		assertEquals(InventoryResourceKind.ITEM, InventoryC2SPacket.select(11, 12, 13, 14, 15, 16, 17, "opaque", "attention").resourceKind());
	}
	@Test void everyFluidResponseIncludingEmptyCutsAndPolicyIsDomainHomogeneous() {
		var entry = new InventoryS2CPacket.Entry("opaque", "minecraft:water", "block.minecraft.water", "{\"version\":1,\"id\":\"minecraft:water\"}",
			9007199254740993L, 1, false, null);
		for (var packet : List.of(
			InventoryS2CPacket.policy(InventoryResourceKind.FLUID, 1, 2, 3, Set.of("block")),
			InventoryS2CPacket.data(InventoryResourceKind.FLUID, InventoryS2CPacket.Kind.PREVIEW, 1, 2, null, 3, 4, 5, 0, 1, false, InventoryS2CPacket.Status.UPDATING, 6, List.of(entry)),
			InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, 1, 2, new MarkerId(3), 4, 5, 6, 0, 1, true, InventoryS2CPacket.Status.READY, 7, List.of()).withResourceKind(InventoryResourceKind.FLUID),
			InventoryS2CPacket.data(InventoryS2CPacket.Kind.STREAM, 1, 2, new MarkerId(3), 4, 5, 6, 0, 1, true, InventoryS2CPacket.Status.READY, 7, List.of(entry)).withResourceKind(InventoryResourceKind.FLUID),
			InventoryS2CPacket.status(InventoryResourceKind.FLUID, 1, 2, new MarkerId(3), 4, 5, 6, InventoryS2CPacket.Status.INVALID, 7),
			InventoryS2CPacket.heartbeat(InventoryResourceKind.FLUID, 1, 2, new MarkerId(3), 4, 5, 6, 7),
			InventoryS2CPacket.selected(InventoryResourceKind.FLUID, 1, 2, 3, new MarkerId(4), null))) {
			var buf = frame(packet);
			try {
				assertEquals(packet.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES), buf.readableBytes());
				assertEquals(packet, InventoryS2CPacket.readSafe(buf)); assertFalse(buf.isReadable());
			} finally { buf.release(); }
		}
	}
	@Test void fluidDisplayDecodesAfterWireRoundTripWhileBadDisplayCannotEraseAuthoritativeEntryMetadata() {
		String namedDisplay = InventoryFluidDisplayCodec.encode(new InventoryFluidDisplayCodec.Projection("minecraft:water", "Spring water"));
		for (var entry : List.of(
			new InventoryS2CPacket.Entry("opaque", "minecraft:water", "block.minecraft.water",
				namedDisplay,
				Long.MAX_VALUE, 7, false, null),
			new InventoryS2CPacket.Entry("opaque", "minecraft:water", "block.minecraft.water", null, Long.MAX_VALUE, 7, false, null),
			new InventoryS2CPacket.Entry("opaque", "minecraft:water", "block.minecraft.water", "{\"id\":\"minecraft:water\"}", Long.MAX_VALUE, 7, false, null),
			new InventoryS2CPacket.Entry("opaque", "minecraft:water", "block.minecraft.water", "{\"version\":1,\"id\":\"minecraft:lava\"}", Long.MAX_VALUE, 7, false, null))) {
			var packet = InventoryS2CPacket.data(InventoryResourceKind.FLUID, InventoryS2CPacket.Kind.SNAPSHOT, 1, 2, new MarkerId(3),
				4, 5, 6, 0, 1, true, InventoryS2CPacket.Status.READY, 8, List.of(entry)).stamp(9, 10);
			var buf = frame(packet);
			try {
				var decoded = InventoryS2CPacket.readSafe(buf);
				assertEquals(packet, decoded); assertFalse(buf.isReadable());
				var received = decoded.entries().getFirst();
				var display = InventoryFluidDisplayCodec.decode(received.resourceId(), received.displayJson());
				assertEquals(namedDisplay.equals(entry.displayJson()), display.isPresent());
				if (display.isPresent()) assertEquals("Spring water", display.get().customName());
				assertEquals("opaque", received.key()); assertEquals(Long.MAX_VALUE, received.amount());
				assertEquals(7, received.resourceRevision()); assertFalse(received.fallback()); assertNull(received.quality());
				assertEquals("block.minecraft.water", received.label());
			} finally { buf.release(); }
		}
	}
	@Test void fullValidFramesRejectOldVersionUnknownLaneAndTrailingRatherThanReinterpret() {
		var request = InventoryC2SPacket.select(InventoryResourceKind.FLUID, 11, 12, 13, 14, 15, 16, 17, "opaque", "attention");
		var response = InventoryS2CPacket.status(1, 2, new MarkerId(3), 4, 5, 6, InventoryS2CPacket.Status.INVALID, 7).withResourceKind(InventoryResourceKind.FLUID);
		for (int mutation = 0; mutation < 3; mutation++) {
			var req = frame(request); var res = frame(response);
			try {
				req.readUtf(); int reqVersion = req.readerIndex(); req.readVarInt(); int reqLane = req.readerIndex() + 24;
				res.readUtf(); int resVersion = res.readerIndex(); res.readVarInt(); int resLane = res.readerIndex() + 32;
				if (mutation == 0) { req.setByte(reqVersion, 1); res.setByte(resVersion, 1); }
				else if (mutation == 1) { req.setByte(reqLane, 2); res.setByte(resLane, 255); }
				else { req.writeByte(0); res.writeByte(0); }
				req.readerIndex(0); res.readerIndex(0);
				assertTrue(InventoryC2SPacket.readSafe(req).isCorrupt()); assertTrue(InventoryS2CPacket.readSafe(res).isCorrupt());
				assertFalse(req.isReadable()); assertFalse(res.isReadable());
			} finally { req.release(); res.release(); }
		}
	}
	@Test void malformedUtfCannotBecomeAReplacementCharacterInsideAnOpaqueSelectionOrDisplay() {
		var request = InventoryC2SPacket.select(InventoryResourceKind.FLUID, 11, 12, 13, 14, 15, 16, 17, "ab", "attention");
		var response = InventoryS2CPacket.data(InventoryResourceKind.FLUID, InventoryS2CPacket.Kind.PREVIEW, 1, 2, null,
			3, 4, 5, 0, 1, false, InventoryS2CPacket.Status.UPDATING, 6,
			List.of(new InventoryS2CPacket.Entry("key", "minecraft:water", "water", "ab", 81, 1, false, null)));
		var req = frame(request); var res = frame(response);
		try {
			req.readUtf(); req.readVarInt(); req.skipBytes(24 + 1 + 32); assertEquals(2, req.readVarInt());
			req.setByte(req.readerIndex(), 0xc0); req.setByte(req.readerIndex() + 1, 0xaf);
			res.readUtf(); res.readVarInt(); res.skipBytes(32 + 1 + 1 + 24);
			res.readVarInt(); res.readVarInt(); res.readBoolean(); res.readUtf(); res.readLong();
			res.readVarInt(); res.readVarInt(); res.readUtf(); res.readUtf(); res.readUtf(); assertTrue(res.readBoolean()); assertEquals(2, res.readVarInt());
			res.setByte(res.readerIndex(), 0xc0); res.setByte(res.readerIndex() + 1, 0xaf);
			req.readerIndex(0); res.readerIndex(0);
			assertTrue(InventoryC2SPacket.readSafe(req).isCorrupt()); assertTrue(InventoryS2CPacket.readSafe(res).isCorrupt());
			assertFalse(req.isReadable()); assertFalse(res.isReadable());
		} finally { req.release(); res.release(); }
	}
}
