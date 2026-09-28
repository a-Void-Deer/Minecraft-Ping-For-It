package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationPacketsV3Test {
	private static final String BASIC = "minecraft:basic";

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	@Test
	void versionThreeHasNoSubscribeKindAndUsesNewRouteIds() {
		assertEquals(3, PresentationC2SPacket.VERSION);
		assertEquals(3, PresentationS2CPacket.VERSION);
		assertEquals("pingforit-c2s:presentation-v3", PresentationC2SPacket.PACKET_ID.toString());
		assertEquals("pingforit-s2c:presentation-v3", PresentationS2CPacket.PACKET_ID.toString());
		assertEquals(List.of(PresentationC2SPacket.Kind.HELLO, PresentationC2SPacket.Kind.CREATE,
			PresentationC2SPacket.Kind.REMOVE), Arrays.asList(PresentationC2SPacket.Kind.values()));
	}

	@Test
	void helloAdvertisesSchemasOnlyAndRoundTrips() {
		FriendlyByteBuf buf = buffer();
		try {
			var packet = PresentationC2SPacket.hello(Map.of(BASIC, 1));
			packet.write(buf);
			var decoded = PresentationC2SPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(PresentationC2SPacket.Kind.HELLO, decoded.kind());
			assertEquals(Map.of(BASIC, 1), decoded.schemas());
			assertEquals(List.of(), decoded.properties());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void createUploadsBoundedTypedPropertyObservationsAndRoundTrips() {
		var ref = PresentationPropertyRef.root(BASIC, "minecraft:block.state");
		var nested = new PresentationPropertyRef(BASIC, "minecraft:block.state", List.of("#counts"));
		var icon = PresentationPropertyRef.root(BASIC, "minecraft:item.icon");
		var properties = List.of(
			PresentationPropertyIntent.observed(ref, new PresentationValue.RecordValue(
				Map.of("facing", new PresentationValue.Text("north")))),
			PresentationPropertyIntent.of(nested, new PresentationValue.NumberValue(3), "request"),
			PresentationPropertyIntent.observed(icon, new PresentationValue.Flag(true)));
		var target = new Target.LocationTarget("minecraft:overworld", 1.5, 64.0, -2.5);

		FriendlyByteBuf buf = buffer();
		try {
			var packet = PresentationC2SPacket.create(41L, 7L, target, "attention", properties);
			packet.write(buf);
			var decoded = PresentationC2SPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(PresentationC2SPacket.Kind.CREATE, decoded.kind());
			assertEquals(41L, decoded.epoch());
			assertEquals(7L, decoded.requestId());
			assertEquals(target, decoded.target());
			assertEquals("attention", decoded.pingType());
			assertEquals(properties, decoded.properties());
			assertTrue(decoded.properties().get(0).observedValue() instanceof PresentationValue.RecordValue);
			assertEquals(new PresentationValue.NumberValue(3), decoded.properties().get(1).observedValue());
			assertEquals(new PresentationValue.Flag(true), decoded.properties().get(2).observedValue());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void legacyCreateOverloadHasNoPropertiesAndDuplicateRefsAreRejected() {
		var target = new Target.LocationTarget("minecraft:overworld", 0.0, 0.0, 0.0);
		assertEquals(List.of(), PresentationC2SPacket.create(41L, 7L, target, "attention").properties());

		var ref = PresentationPropertyRef.root(BASIC, "minecraft:block.state");
		var intent = PresentationPropertyIntent.observed(ref, new PresentationValue.Flag(true));
		assertThrows(IllegalArgumentException.class,
			() -> PresentationC2SPacket.create(41L, 7L, target, "attention", List.of(intent, intent)));
	}

	@Test
	void removeRoundTripsAndCreateWithoutTargetIsCorrupt() {
		FriendlyByteBuf buf = buffer();
		try {
			var packet = PresentationC2SPacket.remove(41L, new MarkerId(9L));
			packet.write(buf);
			var decoded = PresentationC2SPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt());
			assertEquals(new MarkerId(9L), decoded.markerId());
		} finally {
			buf.release();
		}

		assertTrue(PresentationC2SPacket.create(0L, 1L, null, "attention").isCorrupt());
		assertTrue(PresentationC2SPacket.create(1L, 1L, new Target.LocationTarget("minecraft:overworld", 0, 0, 0), null).isCorrupt());
	}

	@Test
	void offerCarriesManifestAndRoundTrips() {
		var field = new nx.pingwheel.common.presentation.PresentationField("minecraft:target.name",
			nx.pingwheel.common.presentation.PresentationField.Kind.TEXT, true, 0, "name");

		FriendlyByteBuf buf = buffer();
		try {
			var packet = PresentationS2CPacket.offer(41L, Map.of(BASIC, List.of(field)), Map.of(BASIC, 1));
			packet.write(buf);
			var decoded = PresentationS2CPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(PresentationS2CPacket.Kind.OFFER, decoded.kind());
			assertEquals(Map.of(BASIC, List.of(field)), decoded.manifest());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void resetCarriesTheAuthoritativeMaskAndAnEmptyMaskDeniesEverything() {
		Map<String, Map<String, Set<String>>> mask = Map.of(
			"block", Map.of(BASIC, Set.of("minecraft:target.name")));

		FriendlyByteBuf buf = buffer();
		try {
			var packet = PresentationS2CPacket.reset(41L, 3L, mask);
			packet.write(buf);
			var decoded = PresentationS2CPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(mask, decoded.mask());
			assertEquals(3L, decoded.view());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}

		assertTrue(PresentationS2CPacket.reset(41L, 3L, Map.of()).mask().isEmpty());
		assertThrows(IllegalArgumentException.class,
			() -> PresentationS2CPacket.reset(41L, 3L, Map.of("unknown", Map.of())));
	}

	@Test
	void createdCarriesTheDefaultRefBesideTheAnnotatedSectionAndRoundTrips() {
		var snapshot = new MarkerSnapshot(new MarkerId(9L), UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1.5, 64.0, -2.5), "block", "attention",
			new MarkerAnchor(1.5, 64.0, -2.5), 1L, 100L);
		var ref = PresentationPropertyRef.root(BASIC, "minecraft:target.name");
		var section = new PresentationSection(BASIC, 1,
			Map.of("minecraft:target.name", new PresentationValue.Text("Chest")), false, Map.of(ref, "attention"));
		var defaultRef = PresentationPropertyRef.root(BASIC, "minecraft:target.name");

		FriendlyByteBuf buf = buffer();
		try {
			var packet = PresentationS2CPacket.created(41L, 2L, 5L, snapshot, "Owner", defaultRef, section);
			packet.write(buf);
			var decoded = PresentationS2CPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(PresentationS2CPacket.Kind.CREATED, decoded.kind());
			assertEquals(defaultRef, decoded.defaultRef());
			assertEquals(snapshot, decoded.snapshot());
			assertEquals("Owner", decoded.ownerName());

			FriendlyByteBuf sectionBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decoded.sectionBytes()));
			try {
				assertEquals(section, nx.pingwheel.common.presentation.PresentationCodec.read(sectionBuf, id -> true));
			} finally {
				sectionBuf.release();
			}
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void createdWithoutADefaultRefIsCorrupt() {
		var snapshot = new MarkerSnapshot(new MarkerId(9L), UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1.5, 64.0, -2.5), "block", "attention",
			new MarkerAnchor(1.5, 64.0, -2.5), 1L, 100L);
		var section = new PresentationSection(BASIC, 1,
			Map.of("minecraft:target.name", new PresentationValue.Text("Chest")), false);

		assertTrue(new PresentationS2CPacket(PresentationS2CPacket.Kind.CREATED, PresentationS2CPacket.VERSION,
			41L, 2L, 5L, Map.of(), Map.of(), Map.of(), snapshot, "Owner", null,
			new byte[] {1}, snapshot.id(), null, null, java.util.Optional.empty(), 0, null, null).isCorrupt());
	}

	@Test
	void removedAndRejectedRoundTrip() {
		FriendlyByteBuf buf = buffer();
		try {
			PresentationS2CPacket.removed(41L, 2L, new MarkerId(9L), MarkerRemovalReason.EXPIRED).write(buf);
			var removed = PresentationS2CPacket.readSafe(buf);
			assertFalse(removed.isCorrupt());
			assertEquals(MarkerRemovalReason.EXPIRED, removed.removalReason());

			buf.clear();
			PresentationS2CPacket.rejected(41L, 2L, 7L, MarkerRequestKind.CREATE, MarkerRejectReason.RATE_LIMITED).write(buf);
			var rejected = PresentationS2CPacket.readSafe(buf);
			assertFalse(rejected.isCorrupt());
			assertEquals(MarkerRejectReason.RATE_LIMITED, rejected.rejectReason());
		} finally {
			buf.release();
		}
	}
}
