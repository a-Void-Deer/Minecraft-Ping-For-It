package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.*;
import nx.pingwheel.common.presentation.inventory.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryCreationSnapshotPacketsTest {
	private static final String ID = "minecraft:water";
	private static final String DISPLAY = "{\"version\":1,\"id\":\"minecraft:water\",\"name\":\"冷水\"}";
	private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME);
	private static final MarkerSnapshot MARKER = marker(new Target.BlockTarget("minecraft:overworld", 1, 64, 2, "minecraft:chest"));
	private static final PresentationSection BASIC = new PresentationSection(PresentationBasic.ID, 1,
		Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false);

	private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }
	private static MarkerSnapshot marker(Target target) {
		return new MarkerSnapshot(new MarkerId(91), new UUID(1, 2), target, "block", "attention", new MarkerAnchor(1, 64, 2), 1, 100);
	}
	private static InventoryCreationSnapshot snapshot(InventoryResourceKind kind, long amount, boolean aggregate,
		boolean complete, boolean uncertain, @Nullable String display) {
		return new InventoryCreationSnapshot(kind, "opaque-row", kind == InventoryResourceKind.FLUID ? ID : "minecraft:stone",
			"自定义 fallback", display, amount, aggregate, "request", complete, uncertain);
	}
	private static PresentationS2CPacket packet(MarkerSnapshot marker, InventoryCreationSnapshot snapshot) {
		return PresentationS2CPacket.created(7, 3, 5, marker, "Owner", NAME, PresentationReceiptContent.inventory(snapshot), BASIC);
	}

	/** Literal wire oracle independent of the production snapshot writer and DTO factories. */
	@Test void literalInventoryCreatedCarriesPreviewAmountImmediately() {
		FriendlyByteBuf payload = raw(1, ID, DISPLAY, Long.MAX_VALUE, 0, 0, 1);
		FriendlyByteBuf wire = created(payload, 2, 0, 0);
		try {
			PresentationS2CPacket decoded = PresentationS2CPacket.readSafe(wire);
			assertFalse(decoded.isCorrupt(), "a bounded server-selected Preview row must travel in atomic CREATED");
			assertEquals(new InventoryCreationSnapshot(InventoryResourceKind.FLUID, "opaque-row", ID, "label", DISPLAY,
				Long.MAX_VALUE, false, "request", false, true), decoded.content().inventorySnapshot());
			assertEquals(MARKER, decoded.snapshot());
			assertEquals(0, wire.readableBytes());
		} finally { wire.release(); payload.release(); }
	}

	@Test void realCreatedRoundTripsBothLanesExactAggregateZeroAndFullLongWithQuality() {
		for (InventoryResourceKind kind : InventoryResourceKind.values()) {
			for (long amount : new long[] {0, Long.MAX_VALUE}) {
				for (boolean aggregate : new boolean[] {false, true}) {
					for (int quality = 0; quality < 3; quality++) {
						InventoryCreationSnapshot snapshot = snapshot(kind, amount, aggregate, quality != 0, quality != 1,
							kind == InventoryResourceKind.FLUID ? DISPLAY
								: "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":\"{\\\"text\\\":\\\"石头\\\"}\",\"!minecraft:lore\":{}}}");
						assertRoundTrip(packet(MARKER, snapshot));
					}
				}
			}
		}
		assertRoundTrip(packet(MARKER, snapshot(InventoryResourceKind.ITEM, 5, false, false, true, null)));
	}

	@Test void creationMeasurementMatchesActualMarkerShapesAndMultibyteRefsWithoutSerializing() {
		@Unmodifiable List<Target> targets = List.of(MARKER.target(),
			new Target.EntityTarget("minecraft:overworld", EntityLocator.uuid(new UUID(2, 3))),
			new Target.EntityTarget("minecraft:overworld", EntityLocator.runtimeId(Integer.MAX_VALUE)),
			new Target.LocationTarget("minecraft:overworld", 1.25, 64, 2),
			new Target.ExternalBlockTarget("minecraft:overworld", "provider:test", "stable", "minecraft:chest", "位置", true));
		for (Target target : targets) assertRoundTrip(packet(marker(target), snapshot(InventoryResourceKind.FLUID, 81, false, true, false, DISPLAY)));
		@Unmodifiable List<TargetKey> groups = List.of(
			new TargetKey.DoubleChestKey("minecraft:overworld", 1, 64, 2, 2, 64, 2, "minecraft:chest"),
			new TargetKey.VaultKey("minecraft:overworld", 1, 64, 2, "create:item_vault"));
		for (TargetKey key : groups) {
			Target.BlockTarget target = new Target.BlockTarget("minecraft:overworld", 1, 64, 2,
				key instanceof TargetKey.VaultKey ? "create:item_vault" : "minecraft:chest");
			MarkerSnapshot grouped = new MarkerSnapshot(MARKER.id(), MARKER.owner(), target, key, "block", "attention", MARKER.anchor(), 1, 100);
			assertRoundTrip(packet(grouped, snapshot(InventoryResourceKind.ITEM, 0, true, true, true, null)));
		}
		Target tankTarget = new Target.BlockTarget("minecraft:overworld", 1, 64, 2, "create:fluid_tank");
		TargetKey tankKey = new TargetKey.CreateTankKey("minecraft:overworld", 1, 64, 2, "create:fluid_tank");
		assertRoundTrip(packet(new MarkerSnapshot(MARKER.id(), MARKER.owner(), tankTarget, tankKey, "block", "attention", MARKER.anchor(), 1, 100),
			snapshot(InventoryResourceKind.FLUID, 100, false, true, false, null)));
		PresentationReceiptContent properties = PresentationReceiptContent.properties(List.of(
			new PresentationPropertyRef(PresentationBasic.ID, PresentationBasic.BLOCK_STATE, List.of("方位😀"))));
		assertRoundTrip(PresentationS2CPacket.created(7, 3, 5, MARKER, "所有者😀", NAME, properties, BASIC));
	}

	@Test void strictWireRejectsUnknownLaneNegativeAmountBooleanBytesAndContradictoryQuality() {
		for (int kind : new int[] {2, 255}) reject(raw(kind, ID, null, 4, 0, 1, 0));
		reject(raw(1, ID, null, -1, 0, 1, 0));
		reject(raw(1, ID, null, 4, 0, 0, 0));
		for (int nonBoolean : new int[] {2, 128, 255}) {
			reject(raw(1, ID, null, 4, nonBoolean, 1, 0));
			reject(raw(1, ID, null, 4, 0, nonBoolean, 0));
			reject(raw(1, ID, null, 4, 0, 1, nonBoolean));
			FriendlyByteBuf payload = raw(1, ID, null, 4, 0, 1, 0);
			try {
				payload.readUnsignedByte(); payload.readUtf(); payload.readUtf(); payload.readUtf();
				payload.setByte(payload.readerIndex(), nonBoolean); payload.readerIndex(0);
				reject(new FriendlyByteBuf(payload.retainedDuplicate()));
			} finally { payload.release(); }
		}
	}

	@Test void strictWireRejectsMalformedIdsDisplayMismatchUnknownAnnotationAndInvalidProjection() {
		for (String id : List.of("Bad Id", "water", "minecraft:", "minecraft:a:b", "a/b:water")) reject(raw(1, id, null, 1, 0, 1, 0));
		for (String display : List.of("{}", "{\"version\":1,\"id\":\"minecraft:lava\"}",
			"{\"version\":1,\"id\":\"minecraft:water\",\"components\":{}}", DISPLAY + " true")) reject(raw(1, ID, display, 1, 0, 1, 0));
		for (String display : List.of("{}", "{\"id\":\"minecraft:dirt\"}",
			"{\"id\":\"minecraft:stone\",\"unknown\":true}", "{\"id\":\"minecraft:stone\"}[]"))
			reject(raw(0, "minecraft:stone", display, 1, 0, 1, 0));
		FriendlyByteBuf payload = raw(1, ID, null, 1, 0, 1, 0);
		try {
			payload.writerIndex(payload.writerIndex() - 10);
			payload.writeUtf("disabled"); payload.writeBoolean(true); payload.writeBoolean(false);
			reject(new FriendlyByteBuf(payload.retainedDuplicate()));
		} finally { payload.release(); }
	}

	@Test void inventoryRequiresExactlyOnePayloadNoRefsAndOtherKindsRejectPayloadInAtomicPacket() {
		FriendlyByteBuf payload = raw(1, ID, DISPLAY, 81, 0, 1, 0);
		try {
			for (int kind : new int[] {0, 1, 3}) rejectWire(created(payload, kind, kind == 1 ? 1 : 0, 0));
			rejectWire(created(payload, 2, 1, 0));
			rejectWire(created(payload, 2, 0, 1)); // two snapshot payloads
			FriendlyByteBuf missing = createdPrefix(2, 0);
			PresentationCodec.write(missing, BASIC); rejectWire(missing);
			InventoryCreationSnapshot snapshot = snapshot(InventoryResourceKind.FLUID, 81, false, true, false, DISPLAY);
			for (PresentationReceiptContent.Kind kind : List.of(PresentationReceiptContent.Kind.WHOLE,
				PresentationReceiptContent.Kind.PROPERTIES, PresentationReceiptContent.Kind.SUPPRESSED)) {
				assertThrows(IllegalArgumentException.class, () -> new PresentationReceiptContent(kind,
					kind == PresentationReceiptContent.Kind.PROPERTIES ? List.of(NAME) : List.of(), snapshot));
			}
			assertThrows(IllegalArgumentException.class, PresentationReceiptContent::inventory);
			assertThrows(IllegalArgumentException.class, () -> new PresentationReceiptContent(PresentationReceiptContent.Kind.INVENTORY, List.of()));
		} finally { payload.release(); }
	}

	@Test void lengthBoundsCanonicalIntegersMalformedUtfTruncationAndTrailingBytesFailClosed() {
		FriendlyByteBuf payload = raw(1, ID, DISPLAY, 81, 0, 1, 0);
		try {
			for (int length : new int[] {InventoryS2CPacket.MAX_ENTRY_BYTES + 1, Integer.MAX_VALUE}) {
				FriendlyByteBuf wire = createdPrefix(2, 0); wire.writeVarInt(length); wire.writeByte(0); rejectWire(wire);
			}
			FriendlyByteBuf overlong = createdPrefix(2, 0); overlong.writeByte(0x81); overlong.writeByte(0); rejectWire(overlong);
			FriendlyByteBuf overflow = createdPrefix(2, 0);
			overflow.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10}); rejectWire(overflow);
			for (int cut = 0; cut < payload.readableBytes(); cut++) {
				FriendlyByteBuf truncated = new FriendlyByteBuf(payload.copy(0, cut));
				reject(truncated);
			}
			FriendlyByteBuf trailing = new FriendlyByteBuf(payload.copy());
			trailing.writeByte(0); reject(trailing);
			FriendlyByteBuf wire = created(payload, 2, 0, 0); wire.writeByte(0); rejectWire(wire);
			for (byte[] utf : new byte[][] {{(byte) 0xff}, {(byte) 0xc0, (byte) 0xaf}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}}) {
				FriendlyByteBuf bad = buffer(); bad.writeByte(1); bad.writeVarInt(utf.length); bad.writeBytes(utf);
				bad.writeBytes(payload, 1 + 1 + "opaque-row".length(), payload.readableBytes() - 1 - 1 - "opaque-row".length()); reject(bad);
			}
			FriendlyByteBuf displayLength = buffer(); displayLength.writeByte(1); displayLength.writeUtf("key");
			displayLength.writeUtf(ID); displayLength.writeUtf("label"); displayLength.writeBoolean(true);
			displayLength.writeVarInt(InventoryS2CPacket.MAX_DISPLAY_BYTES + 1); reject(displayLength);
		} finally { payload.release(); }
	}

	@Test void displayAndUtf8BoundsAreSharedAndServerEncodePreservesPayloadAtTheBoundary() {
		String base = "{\"id\":\"minecraft:stone\"}";
		String atBound = base + " ".repeat(InventoryS2CPacket.MAX_DISPLAY_BYTES - base.length());
		InventoryCreationSnapshot snapshot = snapshot(InventoryResourceKind.ITEM, Long.MAX_VALUE, false, true, false, atBound);
		assertRoundTrip(packet(MARKER, snapshot));
		assertThrows(IllegalArgumentException.class, () -> snapshot(InventoryResourceKind.ITEM, 1, false, true, false, atBound + " "));
		String label = "界".repeat(341) + "x";
		assertRoundTrip(packet(MARKER, new InventoryCreationSnapshot(InventoryResourceKind.FLUID, "k".repeat(256), ID, label,
			null, 0, true, "attention", true, true)));
		assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.FLUID, "key", ID,
			label + "x", null, 0, false, "attention", true, false));
		assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.ITEM, "\uD800", "minecraft:stone",
			"label", null, 0, false, "attention", true, false));
		assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.ITEM, "key", "minecraft:stone",
			"label", null, 0, false, "unknown", true, false));
	}

	@Test void snapshotUtfLengthsRejectNoncanonicalOverflowAndEveryMalformedTextSlot() {
		for (int slot = 0; slot < 5; slot++) {
			for (int invalid = 0; invalid < 3; invalid++) {
				FriendlyByteBuf payload = buffer(); payload.writeByte(1);
				for (int field = 0; field < 5; field++) {
					if (field == 3) payload.writeBoolean(true);
					if (field == 4) { payload.writeLong(81); payload.writeBoolean(false); }
					if (field == slot) {
						if (invalid == 0) { payload.writeByte(0x81); payload.writeByte(0); payload.writeByte('x'); }
						else if (invalid == 1) payload.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10});
						else { payload.writeVarInt(1); payload.writeByte(0xff); }
					} else payload.writeUtf(switch (field) { case 0 -> "key"; case 1 -> ID; case 2 -> "label"; case 3 -> DISPLAY; default -> "request"; });
				}
				payload.writeBoolean(true); payload.writeBoolean(false); reject(payload);
			}
		}
		FriendlyByteBuf overlongKey = raw(1, ID, null, 81, 0, 1, 0);
		try {
			FriendlyByteBuf payload = buffer(); payload.writeByte(1); payload.writeUtf("k".repeat(257));
			payload.writeBytes(overlongKey, 12, overlongKey.writerIndex() - 12); reject(payload);
		} finally { overlongKey.release(); }
		FriendlyByteBuf oversizedCreated = buffer();
		oversizedCreated.writeZero(PresentationS2CPacket.MAX_S2C_BODY_BYTES + 1); rejectWire(oversizedCreated);
	}

	@Test void retainedFactoryKeepsExactRowAndRejectsCrossLaneOrAggregateSubstitution() {
		InventoryScanner.Key key = new InventoryScanner.Key(InventoryResourceKind.FLUID, ID, "digest");
		InventoryResourceValue retained = new InventoryFluidValue(key, Long.MAX_VALUE, new InventoryFluidValue.Display(ID, "label", DISPLAY), false);
		InventorySelection selection = new InventorySelection(InventoryResourceKind.FLUID, key, ID, "older label", null, false, "request");
		InventoryCreationSnapshot frozen = InventoryCreationSnapshot.fromRetained("token", selection, retained, false, false);
		assertEquals(Long.MAX_VALUE, frozen.amount()); assertEquals(DISPLAY, frozen.displayJson()); assertEquals("label", frozen.label());
		assertTrue(frozen.quantityUncertain()); assertFalse(frozen.previewComplete());
		assertTrue(InventoryCreationSnapshot.fromRetained("token", selection, retained, true, true).quantityUncertain());
		assertThrows(IllegalArgumentException.class, () -> InventoryCreationSnapshot.fromRetained("token",
			new InventorySelection(InventoryResourceKind.FLUID, null, ID, "label", null, true, "request"), retained, true, false));
		assertThrows(IllegalArgumentException.class, () -> InventoryCreationSnapshot.fromRetained("token",
			new InventorySelection(InventoryResourceKind.ITEM, new InventoryScanner.Key(ID, "digest"), ID, "label", null, false, "request"), retained, true, false));
		assertThrows(IllegalArgumentException.class, () -> InventoryCreationSnapshot.fromRetained("token",
			new InventorySelection(InventoryResourceKind.FLUID, new InventoryScanner.Key(InventoryResourceKind.FLUID, ID, "other"), ID, "label", null, false, "request"), retained, true, false));
	}

	@Test void nullAndSmallDisplayMetadataKeepExactUtf8BoundsAndRejectUnpairedSurrogates() {
		String key = "k".repeat(254) + "\u0100";
		String id = "a:" + "x".repeat(254);
		String label = "l".repeat(1022) + "\u0100";
		for (@Nullable String display : new String[] {null, "{\"id\":\"" + id + "\"}"}) {
			InventoryCreationSnapshot frozen = new InventoryCreationSnapshot(InventoryResourceKind.ITEM, key, id,
				label, display, Long.MAX_VALUE, false, "attention", true, false);
			assertRoundTrip(packet(MARKER, frozen));
			InventoryScanner.Key exact = new InventoryScanner.Key(id, "digest");
			InventoryCreationSnapshot retained = InventoryCreationSnapshot.fromRetained(key,
				new InventorySelection(exact, id, label, display, false, "attention"),
				new InventoryDomainCodec.Item(exact, Long.MAX_VALUE, label, display, false), true, false);
			assertEquals(frozen, retained);
			assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.ITEM,
				key + "x", id, label, display, 1, false, "attention", true, false));
			assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.ITEM,
				key, id, label + "x", display, 1, false, "attention", true, false));
		}
		for (String malformed : List.of("\uD800", "\uDC00", "\uD800x", "x\uDC00")) {
			assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.ITEM,
				malformed, id, label, null, 1, false, "attention", true, false));
			assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.ITEM,
				key, id, malformed, null, 1, false, "attention", true, false));
			assertThrows(IllegalArgumentException.class, () -> new InventoryCreationSnapshot(InventoryResourceKind.ITEM,
				key, malformed, label, null, 1, false, "attention", true, false));
		}
	}

	@Test void createdPreflightRejectsWriterCharacterOverflowBeforeDestinationMutation() {
		String tooLong = "x".repeat(257);
		@Unmodifiable List<PresentationS2CPacket> invalid = List.of(
			PresentationS2CPacket.created(7, 3, 5, MARKER, tooLong, NAME, PresentationReceiptContent.whole(), BASIC),
			packet(marker(new Target.BlockTarget(tooLong, 1, 64, 2, "minecraft:chest")), snapshot(InventoryResourceKind.ITEM, 1, false, true, false, null)),
			packet(new MarkerSnapshot(MARKER.id(), MARKER.owner(), MARKER.target(), tooLong, "attention", MARKER.anchor(), 1, 100),
				snapshot(InventoryResourceKind.ITEM, 1, false, true, false, null)),
			packet(new MarkerSnapshot(MARKER.id(), MARKER.owner(), MARKER.target(), "block", tooLong, MARKER.anchor(), 1, 100),
				snapshot(InventoryResourceKind.ITEM, 1, false, true, false, null)),
			packet(marker(new Target.BlockTarget("minecraft:overworld", 1, 64, 2, "a:" + "x".repeat(255))),
				snapshot(InventoryResourceKind.ITEM, 1, false, true, false, null)));
		for (PresentationS2CPacket invalidPacket : invalid) {
			FriendlyByteBuf wire = buffer();
			try {
				wire.writeByte(42);
				assertThrows(RuntimeException.class, () -> invalidPacket.write(wire));
				assertEquals(1, wire.writerIndex(), "invalid CREATED preflight must precede any destination write");
				assertThrows(IllegalArgumentException.class, invalidPacket::encodedCreatedBodyBytes);
				assertThrows(IllegalArgumentException.class, invalidPacket::createdEncodingWorkspace);
			} finally { wire.release(); }
		}
	}

	@Test void createdWriterCharacterLimitsAcceptMultibyteBoundaryAndEveryReceiptKind() {
		String chars = "界".repeat(256);
		MarkerSnapshot marker = new MarkerSnapshot(MARKER.id(), MARKER.owner(),
			new Target.BlockTarget(chars, 1, 64, 2, chars), chars, chars, MARKER.anchor(), 1, 100);
		PresentationPropertyRef boundary = new PresentationPropertyRef("a:" + "x".repeat(128),
			"b:" + "y".repeat(128), List.of("界".repeat(42) + "xx"));
		for (PresentationReceiptContent content : List.of(PresentationReceiptContent.whole(),
			PresentationReceiptContent.suppressed(), PresentationReceiptContent.properties(List.of(boundary)),
			PresentationReceiptContent.inventory(snapshot(InventoryResourceKind.ITEM, 7, false, true, false, null)))) {
			assertRoundTrip(PresentationS2CPacket.created(7, 3, 5, marker, chars, boundary, content, BASIC));
		}
		Target external = new Target.ExternalBlockTarget(chars, chars, chars, chars,
			"界".repeat(MarkerPacketCodec.MAX_EXTERNAL_PROVIDER_LOCATOR_LENGTH), true);
		assertRoundTrip(PresentationS2CPacket.created(7, 3, 5, marker(external), chars, NAME, PresentationReceiptContent.whole(), BASIC));
	}

	private static void assertRoundTrip(PresentationS2CPacket packet) {
		FriendlyByteBuf wire = buffer();
		try {
			int measured = packet.encodedCreatedBodyBytes();
			packet.write(wire);
			assertEquals(measured, wire.readableBytes(), "actual wire bytes include marker, receipt and framed Basic");
			assertTrue(packet.createdEncodingWorkspace() >= measured);
			PresentationS2CPacket decoded = new PresentationS2CPacket(wire);
			assertFalse(decoded.isCorrupt()); assertEquals(packet.content(), decoded.content());
			assertArrayEquals(packet.sectionBytes(), decoded.sectionBytes()); assertEquals(packet.snapshot(), decoded.snapshot());
			assertEquals(0, wire.readableBytes());
		} finally { wire.release(); }
	}

	private static FriendlyByteBuf raw(int lane, String id, @Nullable String display, long amount, int aggregate, int complete, int uncertain) {
		FriendlyByteBuf payload = buffer();
		payload.writeByte(lane); payload.writeUtf("opaque-row"); payload.writeUtf(id); payload.writeUtf("label");
		payload.writeBoolean(display != null); if (display != null) payload.writeUtf(display);
		payload.writeLong(amount); payload.writeByte(aggregate); payload.writeUtf("request"); payload.writeByte(complete); payload.writeByte(uncertain);
		return payload;
	}
	private static FriendlyByteBuf createdPrefix(int receiptKind, int refs) {
		FriendlyByteBuf wire = buffer(); wire.writeVarInt(2); wire.writeVarInt(2);
		wire.writeLong(7); wire.writeLong(3); wire.writeLong(5);
		MarkerPacketCodec.writeMarkerSnapshot(wire, MARKER); MarkerPacketCodec.writeOwnerName(wire, "Owner");
		PresentationCodec.writePropertyRef(wire, NAME); wire.writeVarInt(receiptKind); wire.writeVarInt(refs);
		if (refs != 0) PresentationCodec.writePropertyRef(wire, NAME);
		return wire;
	}
	private static FriendlyByteBuf created(FriendlyByteBuf payload, int kind, int refs, int extraPayloads) {
		FriendlyByteBuf wire = createdPrefix(kind, refs);
		for (int i = 0; i <= extraPayloads; i++) { wire.writeVarInt(payload.readableBytes()); wire.writeBytes(payload, payload.readerIndex(), payload.readableBytes()); }
		FriendlyByteBuf section = buffer();
		try { PresentationCodec.write(section, BASIC); wire.writeByteArray(readBytes(section)); }
		finally { section.release(); }
		return wire;
	}
	private static byte[] readBytes(FriendlyByteBuf buf) { byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); return bytes; }
	private static void reject(FriendlyByteBuf payload) {
		try { rejectWire(created(payload, 2, 0, 0)); } finally { payload.release(); }
	}
	private static void rejectWire(FriendlyByteBuf wire) {
		try { assertTrue(PresentationS2CPacket.readSafe(wire).isCorrupt()); assertEquals(0, wire.readableBytes()); }
		finally { wire.release(); }
	}
}
