package nx.pingwheel.common.presentation;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.MarkerPacketCodec;
import nx.pingwheel.common.network.PresentationS2CPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationReceiptContentTest {
	private static final String BASIC = PresentationBasic.ID;

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static PresentationPropertyRef name() {
		return PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
	}

	private static PresentationPropertyRef state() {
		return PresentationPropertyRef.root(BASIC, PresentationBasic.BLOCK_STATE);
	}

	private static PresentationPropertyRef counts() {
		return new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("#counts"));
	}

	@Test
	void propertiesRequireANonEmptySortedUniqueBoundedRefList() {
		var content = PresentationReceiptContent.properties(List.of(name(), counts(), state()));

		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, content.kind());
		// Deterministic order: field lexically, then path depth.
		assertEquals(List.of(state(), counts(), name()), content.selectedRefs());
		assertThrows(UnsupportedOperationException.class, () -> content.selectedRefs().add(name()));

		assertThrows(IllegalArgumentException.class, () -> PresentationReceiptContent.properties(List.of()));
		assertThrows(IllegalArgumentException.class,
			() -> PresentationReceiptContent.properties(List.of(name(), name())));

		List<PresentationPropertyRef> overCapacity = new ArrayList<>();
		for (int i = 0; i <= PresentationCodec.MAX_PROPERTIES; i++)
			overCapacity.add(PresentationPropertyRef.root(BASIC, "minecraft:field_" + i));
		assertThrows(IllegalArgumentException.class, () -> PresentationReceiptContent.properties(overCapacity));
		overCapacity.remove(overCapacity.size() - 1);
		assertEquals(PresentationCodec.MAX_PROPERTIES,
			PresentationReceiptContent.properties(overCapacity).selectedRefs().size());
	}

	@Test
	void nonPropertiesKindsCarryNoRefs() {
		assertEquals(List.of(), PresentationReceiptContent.whole().selectedRefs());
		assertEquals(List.of(), PresentationReceiptContent.inventory().selectedRefs());
		assertEquals(List.of(), PresentationReceiptContent.suppressed().selectedRefs());
		assertEquals(PresentationReceiptContent.Kind.WHOLE, PresentationReceiptContent.whole().kind());
		assertEquals(PresentationReceiptContent.Kind.INVENTORY, PresentationReceiptContent.inventory().kind());
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, PresentationReceiptContent.suppressed().kind());

		assertThrows(IllegalArgumentException.class,
			() -> new PresentationReceiptContent(PresentationReceiptContent.Kind.WHOLE, List.of(name())));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationReceiptContent(PresentationReceiptContent.Kind.INVENTORY, List.of(name())));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationReceiptContent(PresentationReceiptContent.Kind.SUPPRESSED, List.of(name())));
		assertThrows(IllegalArgumentException.class,
			() -> new PresentationReceiptContent(PresentationReceiptContent.Kind.PROPERTIES, List.of()));
	}

	@Test
	void receiptContentRoundTripsAllFourKinds() {
		List<PresentationReceiptContent> contents = List.of(
			PresentationReceiptContent.whole(),
			PresentationReceiptContent.properties(List.of(name(), counts())),
			PresentationReceiptContent.inventory(),
			PresentationReceiptContent.suppressed());

		for (PresentationReceiptContent content : contents) {
			FriendlyByteBuf buf = buffer();
			try {
				PresentationCodec.writeReceiptContent(buf, content);
				assertEquals(content, PresentationCodec.readReceiptContent(buf));
				assertEquals(0, buf.readableBytes());
			} finally {
				buf.release();
			}
		}
	}

	@Test
	void strictReaderRejectsUnknownKindAndOverCapacityCount() {
		FriendlyByteBuf kind = buffer();
		try {
			kind.writeVarInt(PresentationReceiptContent.Kind.values().length);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(kind));
		} finally {
			kind.release();
		}

		FriendlyByteBuf count = buffer();
		try {
			count.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
			count.writeVarInt(PresentationCodec.MAX_PROPERTIES + 1);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(count));
		} finally {
			count.release();
		}
	}

	@Test
	void strictReaderRejectsIllegalListShape() {
		FriendlyByteBuf nonEmptyWhole = buffer();
		try {
			nonEmptyWhole.writeVarInt(PresentationReceiptContent.Kind.WHOLE.ordinal());
			nonEmptyWhole.writeVarInt(1);
			PresentationCodec.writePropertyRef(nonEmptyWhole, name());
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(nonEmptyWhole));
		} finally {
			nonEmptyWhole.release();
		}

		FriendlyByteBuf emptyProperties = buffer();
		try {
			emptyProperties.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
			emptyProperties.writeVarInt(0);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(emptyProperties));
		} finally {
			emptyProperties.release();
		}
	}

	@Test
	void strictReaderRejectsDuplicateAndOutOfOrderRefs() {
		FriendlyByteBuf duplicate = buffer();
		try {
			duplicate.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
			duplicate.writeVarInt(2);
			PresentationCodec.writePropertyRef(duplicate, state());
			PresentationCodec.writePropertyRef(duplicate, state());
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(duplicate));
		} finally {
			duplicate.release();
		}

		FriendlyByteBuf outOfOrder = buffer();
		try {
			outOfOrder.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
			outOfOrder.writeVarInt(2);
			PresentationCodec.writePropertyRef(outOfOrder, name());
			PresentationCodec.writePropertyRef(outOfOrder, state());
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(outOfOrder));
		} finally {
			outOfOrder.release();
		}
	}

	@Test
	void strictReaderRejectsNonCanonicalNumbers() {
		FriendlyByteBuf overlongKind = buffer();
		try {
			overlongKind.writeByte(0x80);
			overlongKind.writeByte(0x00);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(overlongKind));
		} finally {
			overlongKind.release();
		}

		FriendlyByteBuf overlongCount = buffer();
		try {
			overlongCount.writeVarInt(PresentationReceiptContent.Kind.WHOLE.ordinal());
			overlongCount.writeByte(0x80);
			overlongCount.writeByte(0x00);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(overlongCount));
		} finally {
			overlongCount.release();
		}
	}

	@Test
	void strictReaderRejectsInvalidIdAndPathGrammar() {
		FriendlyByteBuf invalidId = buffer();
		try {
			invalidId.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
			invalidId.writeVarInt(1);
			invalidId.writeUtf("Bad Id", 193);
			invalidId.writeUtf(PresentationBasic.NAME, 193);
			invalidId.writeVarInt(0);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(invalidId));
		} finally {
			invalidId.release();
		}

		FriendlyByteBuf tooDeep = buffer();
		try {
			tooDeep.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
			tooDeep.writeVarInt(1);
			tooDeep.writeUtf(BASIC, 193);
			tooDeep.writeUtf(PresentationBasic.BLOCK_STATE, 193);
			tooDeep.writeVarInt(PresentationLimits.MAX_DEPTH + 1);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(tooDeep));
		} finally {
			tooDeep.release();
		}

		FriendlyByteBuf blankKey = buffer();
		try {
			blankKey.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
			blankKey.writeVarInt(1);
			blankKey.writeUtf(BASIC, 193);
			blankKey.writeUtf(PresentationBasic.BLOCK_STATE, 193);
			blankKey.writeVarInt(1);
			blankKey.writeUtf(" ", PresentationPropertyRef.MAX_KEY_BYTES);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(blankKey));
		} finally {
			blankKey.release();
		}
	}

	@Test
	void strictReaderRejectsMalformedUtf8Metadata() {
		byte[][] malformed = {
			{(byte) 0xFF},                                              // bare invalid byte
			{(byte) 0xC0, (byte) 0xAF},                                 // overlong solidus
			{(byte) 0xE2, (byte) 0x82},                                 // truncated three-byte sequence
			{(byte) 0xE2, (byte) 0x82, 0x41},                           // bad continuation
			{(byte) 0xED, (byte) 0xA0, (byte) 0x80},                    // CESU-8 surrogate half
			{(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80},       // beyond U+10FFFF
		};
		for (byte[] bytes : malformed) {
			for (String slot : List.of("adapter", "field", "path")) {
				FriendlyByteBuf buf = receiptWithRawSlot(slot, bytes);
				try {
					assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(buf));
				} finally {
					buf.release();
				}
			}
		}
	}

	@Test
	void strictReaderAcceptsLiteralReplacementCharacterAndSupplementaryCodePoints() {
		// Valid canonical UTF-8 for U+FFFD and U+1F600 decodes exactly; neither is
		// the malformed-byte replacement path rejected above.
		assertReceiptKey(new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD}, "\uFFFD");
		assertReceiptKey(new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}, "\uD83D\uDE00");

		var content = PresentationReceiptContent.properties(List.of(
			new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("\uFFFD")),
			new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("\uD83D\uDE00"))));
		FriendlyByteBuf buf = buffer();
		try {
			PresentationCodec.writeReceiptContent(buf, content);
			assertEquals(content, PresentationCodec.readReceiptContent(buf));
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void strictReaderKeepsTheExistingUtfLengthAndCharacterBounds() {
		FriendlyByteBuf declaredLength = buffer();
		try {
			writePropertiesReceiptHeader(declaredLength);
			declaredLength.writeVarInt(PresentationPropertyRef.MAX_KEY_BYTES * 3 + 1);
			declaredLength.writeBytes(new byte[4]);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(declaredLength));
		} finally {
			declaredLength.release();
		}

		FriendlyByteBuf characters = buffer();
		try {
			writePropertiesReceiptHeader(characters);
			characters.writeUtf("x".repeat(PresentationPropertyRef.MAX_KEY_BYTES + 1),
				PresentationPropertyRef.MAX_KEY_BYTES + 1);
			assertThrows(IllegalArgumentException.class, () -> PresentationCodec.readReceiptContent(characters));
		} finally {
			characters.release();
		}
	}

	@Test
	void createdPacketReadSafeTreatsMalformedReceiptUtf8AsCorruptAndDrains() {
		var snapshot = new MarkerSnapshot(new MarkerId(9L), UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1.5, 64.0, -2.5), "block", "attention",
			new MarkerAnchor(1.5, 64.0, -2.5), 1L, 100L);
		var section = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false);

		FriendlyByteBuf buf = createdWithMalformedRecordPathKey(snapshot, section);
		try {
			var decoded = PresentationS2CPacket.readSafe(buf);

			assertTrue(decoded.isCorrupt());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void createdPacketCarriesTheExplicitDescriptorBesideTheAtomicInitial() {
		var snapshot = new MarkerSnapshot(new MarkerId(9L), UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1.5, 64.0, -2.5), "block", "attention",
			new MarkerAnchor(1.5, 64.0, -2.5), 1L, 100L);
		var ref = name();
		var section = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false);
		var content = PresentationReceiptContent.properties(List.of(counts()));

		FriendlyByteBuf buf = buffer();
		try {
			var packet = PresentationS2CPacket.created(41L, 2L, 5L, snapshot, "Owner", ref, content, section);
			packet.write(buf);
			var decoded = PresentationS2CPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(content, decoded.content());
			assertEquals(ref, decoded.defaultRef());
			assertEquals(snapshot, decoded.snapshot());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void createdPacketRejectsTrailingBytes() {
		var snapshot = new MarkerSnapshot(new MarkerId(9L), UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1.5, 64.0, -2.5), "block", "attention",
			new MarkerAnchor(1.5, 64.0, -2.5), 1L, 100L);
		var section = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false);

		FriendlyByteBuf buf = buffer();
		try {
			PresentationS2CPacket.created(41L, 2L, 5L, snapshot, "Owner", name(),
				PresentationReceiptContent.whole(), section).write(buf);
			buf.writeByte(0);
			assertThrows(IllegalArgumentException.class, () -> new PresentationS2CPacket(buf));
		} finally {
			buf.release();
		}
	}

	@Test
	void preDescriptorVersionThreeCreatedShapeIsRejectedWithoutShim() {
		var snapshot = new MarkerSnapshot(new MarkerId(9L), UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1.5, 64.0, -2.5), "block", "attention",
			new MarkerAnchor(1.5, 64.0, -2.5), 1L, 100L);
		var section = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("Chest")), false);

		FriendlyByteBuf direct = versionThreeCreated(snapshot, section);
		try {
			assertThrows(IllegalArgumentException.class, () -> new PresentationS2CPacket(direct));
		} finally {
			direct.release();
		}

		FriendlyByteBuf safe = versionThreeCreated(snapshot, section);
		try {
			assertTrue(PresentationS2CPacket.readSafe(safe).isCorrupt());
			assertEquals(0, safe.readableBytes());
		} finally {
			safe.release();
		}
	}

	/** The pre-descriptor wire shape: protocol three and the section bytes where the descriptor now lives. */
	private static FriendlyByteBuf versionThreeCreated(MarkerSnapshot snapshot, PresentationSection section) {
		FriendlyByteBuf buf = buffer();
		buf.writeEnum(PresentationS2CPacket.Kind.CREATED);
		buf.writeVarInt(3);
		buf.writeLong(41L);
		buf.writeLong(2L);
		buf.writeLong(5L);
		MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
		MarkerPacketCodec.writeOwnerName(buf, "Owner");
		PresentationCodec.writePropertyRef(buf, name());
		PresentationCodec.write(buf, section);
		return buf;
	}

	/** A PROPERTIES receipt whose named slot carries raw, unvalidated UTF-8 bytes. */
	private static FriendlyByteBuf receiptWithRawSlot(String slot, byte[] bytes) {
		FriendlyByteBuf buf = buffer();
		buf.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
		buf.writeVarInt(1);
		if (slot.equals("adapter")) writeRawUtf(buf, bytes); else buf.writeUtf(BASIC, 193);
		if (slot.equals("field")) writeRawUtf(buf, bytes); else buf.writeUtf(PresentationBasic.BLOCK_STATE, 193);
		buf.writeVarInt(1);
		if (slot.equals("path")) writeRawUtf(buf, bytes); else buf.writeUtf("#counts", PresentationPropertyRef.MAX_KEY_BYTES);
		return buf;
	}

	/** A PROPERTIES receipt for one ref whose single record key is the raw encoded bytes. */
	private static void assertReceiptKey(byte[] encoded, String expected) {
		FriendlyByteBuf buf = buffer();
		try {
			writePropertiesReceiptHeader(buf);
			writeRawUtf(buf, encoded);
			var content = PresentationCodec.readReceiptContent(buf);

			assertEquals(0, buf.readableBytes());
			assertEquals(List.of(new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of(expected))),
				content.selectedRefs());
		} finally {
			buf.release();
		}
	}

	/** PROPERTIES kind, one ref, valid adapter and field, then depth one. */
	private static void writePropertiesReceiptHeader(FriendlyByteBuf buf) {
		buf.writeVarInt(PresentationReceiptContent.Kind.PROPERTIES.ordinal());
		buf.writeVarInt(1);
		buf.writeUtf(BASIC, 193);
		buf.writeUtf(PresentationBasic.BLOCK_STATE, 193);
		buf.writeVarInt(1);
	}

	private static void writeRawUtf(FriendlyByteBuf buf, byte[] bytes) {
		buf.writeVarInt(bytes.length);
		buf.writeBytes(bytes);
	}

	/**
	 * The v4 CREATED frame with a valid adapter and field but a one-byte invalid
	 * record-path key: the key's declared UTF length stays one and its single byte
	 * is 0xFF.
	 */
	private static FriendlyByteBuf createdWithMalformedRecordPathKey(MarkerSnapshot snapshot, PresentationSection section) {
		FriendlyByteBuf receipt = buffer();
		try {
			PresentationCodec.writeReceiptContent(receipt, PresentationReceiptContent.properties(
				List.of(new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("k")))));
			int keyByte = receipt.writerIndex() - 1;
			assertEquals(1, receipt.getUnsignedByte(keyByte - 1));
			assertEquals((byte) 0x6B, receipt.getByte(keyByte));
			receipt.setByte(keyByte, 0xFF);

			FriendlyByteBuf buf = buffer();
			buf.writeEnum(PresentationS2CPacket.Kind.CREATED);
			buf.writeVarInt(PresentationS2CPacket.VERSION);
			buf.writeLong(41L);
			buf.writeLong(2L);
			buf.writeLong(5L);
			MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
			MarkerPacketCodec.writeOwnerName(buf, "Owner");
			PresentationCodec.writePropertyRef(buf, name());
			buf.writeBytes(receipt);
			PresentationCodec.write(buf, section);
			return buf;
		} finally {
			receipt.release();
		}
	}
}
