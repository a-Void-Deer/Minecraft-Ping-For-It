package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.outline.BlockOutlineSelection;
import nx.pingwheel.common.domain.*;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.presentation.*;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CompositeTargetPacketsTest {
	private static final String DIM = "minecraft:overworld", BASIC = "minecraft:basic", NAME = "minecraft:target.name";
	private static final UUID OWNER = new UUID(1, 1);
	private static final PresentationPropertyRef REF = PresentationPropertyRef.root(BASIC, NAME);
	private static final PresentationSection SECTION = new PresentationSection(BASIC, 1, Map.of(NAME, new PresentationValue.Text("{\"text\":\"Container\"}")), false);
	private static final @Unmodifiable List<TargetKey> GROUPS = List.of(
		new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 2, 2, 3, "minecraft:chest"),
		new TargetKey.DoubleChestKey(DIM, 1, 2, 3, 1, 2, 4, "minecraft:trapped_chest"),
		new TargetKey.VaultKey(DIM, -10, 2, 3, "create:item_vault"));

	private static Target.BlockTarget hit(TargetKey key) {
		return switch (key) {
			case TargetKey.DoubleChestKey chest -> new Target.BlockTarget(DIM, chest.secondX(), chest.secondY(), chest.secondZ(), chest.blockRegistryId());
			case TargetKey.VaultKey vault -> new Target.BlockTarget(DIM, 2, 2, 3, vault.blockRegistryId());
			default -> throw new AssertionError(key);
		};
	}
	private static MarkerSnapshot snapshot(TargetKey key) {
		return new MarkerSnapshot(new MarkerId(9), OWNER, hit(key), key, "entity_block", "danger", new MarkerAnchor(2.5, 2.5, 3.5), 1, 20);
	}
	private static PresentationS2CPacket wire(PresentationS2CPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try { packet.write(buf); var read = PresentationS2CPacket.readSafe(buf); assertFalse(read.isCorrupt()); assertFalse(buf.isReadable()); return read; }
		finally { buf.release(); }
	}
	@Test void createdAndWinnerRealWireAcceptExplicitGroupWhileOutlineUsesOriginalHit() {
		for (TargetKey key : GROUPS) {
			var session = new ClientPresentation(packet -> {}); session.tick(true);
			assertTrue(session.offer(wire(PresentationS2CPacket.offer(41, Map.of(BASIC, List.of(new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"))), Map.of(BASIC, 1)))));
			assertTrue(session.reset(wire(PresentationS2CPacket.reset(41, 2, Map.of("entity_block", Map.of(BASIC, Set.of(NAME)))))));
			var expected = snapshot(key);
			var created = wire(PresentationS2CPacket.created(41, 2, 1, expected, "Owner", REF, SECTION));
			assertEquals(expected, created.snapshot()); assertTrue(session.initial(created));
			var store = new ClientMarkerStore(0);
			var winner = wire(PresentationS2CPacket.winner(41, 2, key, Optional.of(expected.id())));
			store.onWinnerChanged(winner.targetKey(), winner.winnerId());
			assertTrue(store.visibleWinnersInDimension(DIM).isEmpty(), "WINNER before CREATED cannot invent the record");
			store.onCreated(created.snapshot(), 0);
			assertEquals(key, store.winnerMarker(key).orElseThrow().targetKey());
			var physical = (TargetKey.BlockKey) TargetKey.from(expected.target());
			var outlines = BlockOutlineSelection.select(store.visibleWinnersInDimension(DIM), PingTypeCatalog.builtIn());
			assertEquals(Set.of(physical), outlines.keySet()); assertEquals(expected.id(), outlines.get(physical).markerId());
			assertEquals(expected.anchor(), store.winnerMarker(key).orElseThrow().anchor());
			assertTrue(BlockOutlineSelection.select(Map.of(physical, store.winnerMarker(key).orElseThrow()), PingTypeCatalog.builtIn()).isEmpty(), "only the explicit logical winner slot is valid");
		}
	}

	private static void header(FriendlyByteBuf buf, PresentationS2CPacket.Kind kind) {
		buf.writeEnum(kind); buf.writeVarInt(2); buf.writeLong(41); buf.writeLong(2); buf.writeLong(1);
	}
	private static void corrupt(Consumer<FriendlyByteBuf> writer) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try { writer.accept(buf); assertTrue(PresentationS2CPacket.readSafe(buf).isCorrupt()); assertFalse(buf.isReadable()); }
		finally { buf.release(); }
	}
	private static void rawChestKey(FriendlyByteBuf buf, int firstX, int secondX, String registry) {
		MarkerPacketCodec.writeEnum(buf, TargetKind.BLOCK); buf.writeUtf(DIM); buf.writeVarInt(2);
		buf.writeInt(firstX); buf.writeInt(2); buf.writeInt(3); buf.writeInt(secondX); buf.writeInt(2); buf.writeInt(3); buf.writeUtf(registry);
	}
	@Test void malformedNoncanonicalAndWrongMemberKeysAreCorruptNotNormalized() {
		for (int[] pair : List.of(new int[]{2, 1}, new int[]{1, 1}, new int[]{1, 3})) {
			corrupt(buf -> { header(buf, PresentationS2CPacket.Kind.WINNER); rawChestKey(buf, pair[0], pair[1], "minecraft:chest"); buf.writeByte(0); });
		}
		corrupt(buf -> { header(buf, PresentationS2CPacket.Kind.WINNER); rawChestKey(buf, 1, 2, "mod:chest"); buf.writeByte(0); });
		corrupt(buf -> { header(buf, PresentationS2CPacket.Kind.WINNER); MarkerPacketCodec.writeEnum(buf, TargetKind.BLOCK); buf.writeUtf(DIM); buf.writeVarInt(3); buf.writeInt(1); buf.writeInt(2); buf.writeInt(3); buf.writeUtf("minecraft:chest"); buf.writeByte(0); });
		for (Target.BlockTarget wrong : List.of(new Target.BlockTarget(DIM, 3, 2, 3, "minecraft:chest"), new Target.BlockTarget(DIM, 1, 2, 3, "minecraft:trapped_chest"), new Target.BlockTarget("minecraft:the_nether", 1, 2, 3, "minecraft:chest"))) {
			corrupt(buf -> { header(buf, PresentationS2CPacket.Kind.CREATED); buf.writeLong(9); buf.writeUUID(OWNER); MarkerPacketCodec.writeTarget(buf, wrong); rawChestKey(buf, 1, 2, "minecraft:chest"); snapshotTail(buf); });
		}
		corrupt(buf -> { header(buf, PresentationS2CPacket.Kind.WINNER); MarkerPacketCodec.writeEnum(buf, TargetKind.BLOCK); buf.writeUtf(DIM); buf.writeByte(0x82); buf.writeByte(0); buf.writeZero(24); buf.writeUtf("minecraft:chest"); buf.writeByte(0); });
		corrupt(buf -> { header(buf, PresentationS2CPacket.Kind.WINNER); MarkerPacketCodec.writeEnum(buf, TargetKind.BLOCK); buf.writeVarInt(1); buf.writeByte(0xff); buf.writeVarInt(2); buf.writeZero(24); buf.writeUtf("minecraft:chest"); buf.writeByte(0); });
	}
	private static void snapshotTail(FriendlyByteBuf buf) {
		buf.writeUtf("entity_block"); buf.writeUtf("attention"); MarkerPacketCodec.writeMarkerAnchor(buf, new MarkerAnchor(2.5, 2.5, 3.5)); buf.writeLong(1); buf.writeLong(20);
		buf.writeUtf("Owner"); PresentationCodec.writePropertyRef(buf, REF); PresentationCodec.writeReceiptContent(buf, PresentationReceiptContent.whole());
		var section = new FriendlyByteBuf(Unpooled.buffer());
		try { PresentationCodec.write(section, SECTION); buf.writeVarInt(section.readableBytes()); buf.writeBytes(section); }
		finally { section.release(); }
	}
	@Test void missingMandatoryKeyOldVersionEveryTruncationAndTrailingFramesAreRejected() {
		corrupt(buf -> { header(buf, PresentationS2CPacket.Kind.CREATED); buf.writeLong(9); buf.writeUUID(OWNER); MarkerPacketCodec.writeTarget(buf, hit(GROUPS.getFirst())); snapshotTail(buf); });
		for (TargetKey key : GROUPS) for (PresentationS2CPacket packet : List.of(PresentationS2CPacket.created(41, 2, 1, snapshot(key), "Owner", REF, SECTION), PresentationS2CPacket.winner(41, 2, key, Optional.of(new MarkerId(9))))) {
			var source = new FriendlyByteBuf(Unpooled.buffer());
			try {
				packet.write(source);
				for (int length = 0; length < source.readableBytes(); length++) {
					int prefix = length; corrupt(buf -> buf.writeBytes(source, 0, prefix));
				}
				corrupt(buf -> { buf.writeBytes(source, 0, source.readableBytes()); buf.writeByte(0); });
				corrupt(buf -> { buf.writeBytes(source, 0, source.readableBytes()); buf.setByte(1, 1); });
			} finally { source.release(); }
		}
	}
	@Test void c2sTargetCannotAssertEitherGroupedVariantAndOldHelloCannotNegotiate() {
		for (int tag : List.of(2, 3)) {
			var buf = new FriendlyByteBuf(Unpooled.buffer());
			try {
				buf.writeEnum(PresentationC2SPacket.Kind.CREATE); buf.writeVarInt(2); buf.writeLong(41); buf.writeLong(1);
				MarkerPacketCodec.writeEnum(buf, TargetKind.BLOCK); buf.writeUtf(DIM); buf.writeVarInt(tag); buf.writeZero(32);
				assertTrue(PresentationC2SPacket.readSafe(buf).isCorrupt()); assertFalse(buf.isReadable());
			} finally { buf.release(); }
		}
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			buf.writeEnum(PresentationC2SPacket.Kind.HELLO); buf.writeVarInt(1); buf.writeLong(0); buf.writeVarInt(0);
			assertTrue(PresentationC2SPacket.readSafe(buf).isCorrupt()); assertFalse(buf.isReadable());
		} finally { buf.release(); }
	}
}
