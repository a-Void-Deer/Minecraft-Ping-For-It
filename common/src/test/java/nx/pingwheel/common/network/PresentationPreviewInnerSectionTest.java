package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.PresentationPreviewAccess;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Raw inner frames exercise the actual preview packet and coordinator, not wrapper-only admission. */
class PresentationPreviewInnerSectionTest {
	private static final String ADAPTER = "test:source", FIELD = "test:value", DENIED = "test:denied";
	private static final Target TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone");
	private enum Site {
		SECTION_LENGTH, ADAPTER_LENGTH, SCHEMA, FIELD_COUNT, FIELD_ID_LENGTH, FIELD_LENGTH,
		TEXT_LENGTH, SEQUENCE_COUNT, RECORD_COUNT, RECORD_KEY_LENGTH
	}
	private enum Encoding { CANONICAL, OVERFLOW, NONCANONICAL, NEGATIVE_WIDTH, OVERLONG }
	private static void integer(FriendlyByteBuf buf, int value, Site site, Site selected, Encoding encoding) {
		if (site != selected || encoding == Encoding.CANONICAL) { buf.writeVarInt(value); return; }
		switch (encoding) {
			case OVERFLOW -> {
				for (int shift = 0; shift < 28; shift += 7) buf.writeByte(((value >>> shift) & 0x7f) | 0x80);
				buf.writeByte((value >>> 28) | 0x10);
			}
			case NONCANONICAL -> { // Same positive value, with a redundant terminal zero group.
				while (value >= 0x80) { buf.writeByte((value & 0x7f) | 0x80); value >>>= 7; }
				buf.writeByte(value | 0x80); buf.writeByte(0);
			}
			case NEGATIVE_WIDTH -> buf.writeBytes(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f});
			case OVERLONG -> buf.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0});
			default -> throw new AssertionError(encoding);
		}
	}
	private static void text(FriendlyByteBuf buf, String value, Site site, Site selected, Encoding encoding) {
		byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		integer(buf, bytes.length, site, selected, encoding); buf.writeBytes(bytes);
	}
	private static byte[] inner(Site site, Encoding encoding, PresentationField.Kind kind, boolean denied) {
		FriendlyByteBuf field = new FriendlyByteBuf(Unpooled.buffer());
		FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
		FriendlyByteBuf section = new FriendlyByteBuf(Unpooled.buffer());
		try {
			switch (kind) {
				case NUMBER -> { field.writeByte(2); field.writeDouble(7); }
				case TEXT -> { field.writeByte(1); text(field, "observed", Site.TEXT_LENGTH, site, encoding); }
				case FLAG -> { field.writeByte(3); field.writeBoolean(false); }
				case SEQUENCE -> {
					field.writeByte(4); integer(field, 1, Site.SEQUENCE_COUNT, site, encoding);
					field.writeByte(2); field.writeDouble(7); // Sequence children have no annotation flag.
				}
				case RECORD -> {
					field.writeByte(5); integer(field, 1, Site.RECORD_COUNT, site, encoding);
					text(field, "key", Site.RECORD_KEY_LENGTH, site, encoding);
					field.writeByte(2); field.writeDouble(7); field.writeBoolean(false);
				}
			}
			field.writeBoolean(false);
			text(frame, ADAPTER, Site.ADAPTER_LENGTH, site, encoding);
			integer(frame, 1, Site.SCHEMA, site, encoding); frame.writeBoolean(false);
			integer(frame, 1, Site.FIELD_COUNT, site, encoding);
			text(frame, denied ? DENIED : FIELD, Site.FIELD_ID_LENGTH, site, encoding);
			integer(frame, field.readableBytes(), Site.FIELD_LENGTH, site, encoding); frame.writeBytes(field);
			integer(section, frame.readableBytes(), Site.SECTION_LENGTH, site, encoding); section.writeBytes(frame);
			byte[] bytes = new byte[section.readableBytes()]; section.readBytes(bytes); return bytes;
		} finally { field.release(); frame.release(); section.release(); }
	}
	private static PresentationPreviewAccess access(PresentationField.Kind kind) {
		return new PresentationPreviewAccess(31, 1, "block", Map.of(ADAPTER, new PresentationPreviewAccess.Adapter(1,
			Map.of(FIELD, new PresentationField(FIELD, kind, true, 0, "value")))));
	}
	private static PresentationPreviewS2CPacket wire(PresentationPreviewC2SPacket request, byte[] inner) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			// The wrapper is entirely canonical and correct; only the inner SECTION is malformed.
			buf.writeVarInt(1); buf.writeLong(request.epoch()); buf.writeLong(request.view()); buf.writeLong(request.requestId());
			buf.writeUtf(ADAPTER, 193); buf.writeVarInt(1); buf.writeEnum(PresentationPreviewS2CPacket.Status.RESULT); buf.writeByteArray(inner);
			var packet = PresentationPreviewS2CPacket.readSafe(buf); assertFalse(packet.isCorrupt()); assertFalse(buf.isReadable()); return packet;
		} finally { buf.release(); }
	}
	private static PresentationPreviewS2CPacket roundTrip(PresentationPreviewS2CPacket packet) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buf);
			var decoded = PresentationPreviewS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt()); assertFalse(buf.isReadable()); return decoded;
		} finally { buf.release(); }
	}
	private static PresentationPreviewC2SPacket request() { return PresentationPreviewC2SPacket.read(31, 1, 1, TARGET, "block", ADAPTER, Set.of(FIELD)); }
	private static final class Context implements PreviewFieldAccess.ReadContext {
		final Object level = new Object();
		public Object levelIdentity() { return level; }
		public String dimensionId() { return TARGET.dimensionId(); }
		public long tick() { return 0; }
	}
	private record Probe(Site site, PresentationField.Kind kind) {
		@Override public String toString() { return site.toString(); }
	}
	private static Stream<Probe> sites() {
		return Stream.of(Site.values()).map(site -> new Probe(site, switch (site) {
			case TEXT_LENGTH -> PresentationField.Kind.TEXT;
			case SEQUENCE_COUNT -> PresentationField.Kind.SEQUENCE;
			case RECORD_COUNT, RECORD_KEY_LENGTH -> PresentationField.Kind.RECORD;
			default -> PresentationField.Kind.NUMBER;
		}));
	}
	@ParameterizedTest(name = "{0}") @MethodSource("sites")
	void everyInnerMetadataIntegerRejectsMalformedAliasesAtPacketAndClientAcceptance(Probe probe) {
		assertNotNull(wire(request(), inner(probe.site(), Encoding.CANONICAL, probe.kind(), false)).decodeSection(access(probe.kind())));
		for (Encoding encoding : List.of(Encoding.OVERFLOW, Encoding.NONCANONICAL, Encoding.NEGATIVE_WIDTH, Encoding.OVERLONG)) {
			var packet = wire(request(), inner(probe.site(), encoding, probe.kind(), false));
			assertNull(packet.decodeSection(access(probe.kind())), probe.site() + " " + encoding);
			Context context = new Context(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
			var client = new ClientPresentationPreview(type -> Optional.of(access(probe.kind())), () -> context,
				List.of(), (target, type) -> Optional.empty(), sent::add);
			client.begin(new ClientPresentationPreview.Binding(new Object(), TARGET, "block", context.level));
			assertEquals(request(), sent.getFirst()); assertFalse(client.accept(packet), "raw inner alias must not become SERVER_PREVIEW");
			assertTrue(client.projection().orElseThrow().property(PresentationPropertyRef.root(ADAPTER, FIELD)).isEmpty());
			var canonical = wire(sent.getFirst(), inner(probe.site(), Encoding.CANONICAL, probe.kind(), false));
			assertTrue(client.accept(canonical), "malformed reply does not consume the pending request");
		}
	}
	@Test void exactOverflowingInnerSchemaCannotPublishSevenButLegacyGrammarIsUnchanged() {
		byte[] raw = inner(Site.SCHEMA, Encoding.OVERFLOW, PresentationField.Kind.NUMBER, false);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
		try {
			// Legacy compatibility is intentional: opting in to preview strictness cannot change the old route.
			assertEquals(new PresentationValue.NumberValue(7), PresentationCodec.read(buf, FIELD::equals).fields().get(FIELD));
		} finally { buf.release(); }
		assertNull(wire(request(), raw).decodeSection(access(PresentationField.Kind.NUMBER)), "81 80 80 80 10 is not inner schema 1");
	}
	@Test void actualClientAcceptanceRejectsRawOverflowingSchemaAndKeepsThePendingRequest() {
		Context context = new Context(); Object token = new Object(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		var client = new ClientPresentationPreview(type -> Optional.of(access(PresentationField.Kind.NUMBER)), () -> context,
			List.of(), (target, type) -> Optional.empty(), sent::add);
		client.begin(new ClientPresentationPreview.Binding(token, TARGET, "block", context.level));
		var malformed = wire(sent.getFirst(), inner(Site.SCHEMA, Encoding.OVERFLOW, PresentationField.Kind.NUMBER, false));
		assertFalse(client.accept(malformed), "raw 81 80 80 80 10 must never be accepted as SERVER_PREVIEW 7.0");
		assertTrue(client.intent(token, PresentationPropertyRef.root(ADAPTER, FIELD), null).isEmpty());
		assertEquals(1, sent.size(), "rejecting malformed replies adds no fallback polling");
		assertTrue(client.accept(wire(sent.getFirst(), inner(Site.SCHEMA, Encoding.CANONICAL, PresentationField.Kind.NUMBER, false))));
		assertEquals(new PresentationValue.NumberValue(7), client.intent(token, PresentationPropertyRef.root(ADAPTER, FIELD), null).orElseThrow().observedValue());
	}
	@Test void unacceptedFieldRejectsTheWholeResultWithoutConsumingThePendingRequest() {
		Context context = new Context(); Object token = new Object(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		var client = new ClientPresentationPreview(type -> Optional.of(access(PresentationField.Kind.NUMBER)), () -> context,
			List.of(), (target, type) -> Optional.empty(), sent::add);
		client.begin(new ClientPresentationPreview.Binding(token, TARGET, "block", context.level));
		var request = sent.getFirst();
		var ref = PresentationPropertyRef.root(ADAPTER, FIELD);
		// A correlated RESULT carrying only an unaccepted field must not become an accepted empty section.
		assertFalse(client.accept(roundTrip(PresentationPreviewS2CPacket.result(request,
			new PresentationSection(ADAPTER, 1, Map.of(DENIED, new PresentationValue.NumberValue(1)), false)))));
		assertTrue(client.projection().orElseThrow().property(ref).isEmpty());
		// Mixing an authorized field with an unaccepted one rejects the whole response, not just the extra field.
		assertFalse(client.accept(roundTrip(PresentationPreviewS2CPacket.result(request, new PresentationSection(ADAPTER, 1,
			Map.of(FIELD, new PresentationValue.NumberValue(9), DENIED, new PresentationValue.NumberValue(1)), false)))));
		assertTrue(client.projection().orElseThrow().property(ref).isEmpty());
		assertEquals(1, sent.size(), "rejected responses do not consume the pending request");
		// The still-pending request accepts the permitted zero value as real data.
		assertTrue(client.accept(roundTrip(PresentationPreviewS2CPacket.result(request,
			new PresentationSection(ADAPTER, 1, Map.of(FIELD, new PresentationValue.NumberValue(0)), false)))));
		assertEquals(new PresentationValue.NumberValue(0), client.intent(token, ref, null).orElseThrow().observedValue());
	}
	@Test void permittedFalseFlagStillBecomesAServerPreviewObservationAfterARejectedResult() {
		Context context = new Context(); Object token = new Object(); List<PresentationPreviewC2SPacket> sent = new ArrayList<>();
		var client = new ClientPresentationPreview(type -> Optional.of(access(PresentationField.Kind.FLAG)), () -> context,
			List.of(), (target, type) -> Optional.empty(), sent::add);
		client.begin(new ClientPresentationPreview.Binding(token, TARGET, "block", context.level));
		var request = sent.getFirst();
		assertFalse(client.accept(roundTrip(PresentationPreviewS2CPacket.result(request,
			new PresentationSection(ADAPTER, 1, Map.of(DENIED, new PresentationValue.Flag(false)), false)))));
		assertTrue(client.accept(roundTrip(PresentationPreviewS2CPacket.result(request,
			new PresentationSection(ADAPTER, 1, Map.of(FIELD, new PresentationValue.Flag(false)), false)))));
		assertEquals(new PresentationValue.Flag(false),
			client.intent(token, PresentationPropertyRef.root(ADAPTER, FIELD), null).orElseThrow().observedValue());
	}
	@Test void canonicalNestedValuesAndGlobalTextBoundaryStillUseTheExistingSectionGrammar() {
		var values = List.of(new PresentationValue.Text("x".repeat(PresentationCodec.MAX_TEXT_BYTES)),
			new PresentationValue.Flag(false), new PresentationValue.Sequence(List.of(new PresentationValue.RecordValue(Map.of("", new PresentationValue.NumberValue(0))))),
			new PresentationValue.RecordValue(Map.of("nested", new PresentationValue.RecordValue(Map.of("value", new PresentationValue.Flag(false))))));
		for (PresentationValue value : values) {
			var section = new PresentationSection(ADAPTER, 1, Map.of(FIELD, value), false);
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			try {
				PresentationCodec.write(buf, section); byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes);
				PresentationField.Kind kind = switch (value) {
					case PresentationValue.Text ignored -> PresentationField.Kind.TEXT;
					case PresentationValue.NumberValue ignored -> PresentationField.Kind.NUMBER;
					case PresentationValue.Flag ignored -> PresentationField.Kind.FLAG;
					case PresentationValue.Sequence ignored -> PresentationField.Kind.SEQUENCE;
					case PresentationValue.RecordValue ignored -> PresentationField.Kind.RECORD;
				};
				assertEquals(section, wire(request(), bytes).decodeSection(access(kind)));
			} finally { buf.release(); }
		}
		assertThrows(IllegalArgumentException.class, () -> new PresentationSection(ADAPTER, 1,
			Map.of(FIELD, new PresentationValue.Text("x".repeat(PresentationCodec.MAX_TEXT_BYTES + 1))), false));
	}
	@Test void deniedMalformedNestedMetadataRejectsTheWholeResultWithoutTypedDecodingButItsFrameLengthIsStrict() {
		for (Probe probe : sites().filter(value -> Set.of(Site.TEXT_LENGTH, Site.SEQUENCE_COUNT, Site.RECORD_COUNT, Site.RECORD_KEY_LENGTH).contains(value.site())).toList()) {
			// The denied payload is skipped before typed decoding, then rejects the whole result.
			assertNull(wire(request(), inner(probe.site(), Encoding.OVERFLOW, probe.kind(), true)).decodeSection(access(probe.kind())));
		}
		assertNull(wire(request(), inner(Site.FIELD_LENGTH, Encoding.OVERFLOW, PresentationField.Kind.NUMBER, true)).decodeSection(access(PresentationField.Kind.NUMBER)));
	}
	@Test void truncatedInnerFrameIsRejectedWithoutAllocatingObservedValues() {
		byte[] complete = inner(Site.SCHEMA, Encoding.CANONICAL, PresentationField.Kind.NUMBER, false);
		for (int length = 1; length < complete.length; length++)
			assertNull(wire(request(), java.util.Arrays.copyOf(complete, length)).decodeSection(access(PresentationField.Kind.NUMBER)));
	}
	@Test void noncanonicalFalseFlagsAndStaleAliasCannotTurnMalformedFramesIntoObservations() {
		for (boolean staleHeader : List.of(true, false)) {
			byte[] bytes = inner(Site.SCHEMA, Encoding.CANONICAL, PresentationField.Kind.NUMBER, false);
			FriendlyByteBuf cursor = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
			try {
				cursor.readVarInt(); cursor.readUtf(193); cursor.readVarInt();
				int offset = staleHeader ? cursor.readerIndex() : bytes.length - 1;
				bytes[offset] = 2; // Neither canonical false nor canonical true.
				assertNull(wire(request(), bytes).decodeSection(access(PresentationField.Kind.NUMBER)));
			} finally { cursor.release(); }
		}
		byte[] flag = inner(Site.SCHEMA, Encoding.CANONICAL, PresentationField.Kind.FLAG, false);
		flag[flag.length - 2] = 2;
		assertNull(wire(request(), flag).decodeSection(access(PresentationField.Kind.FLAG)));
	}
}
