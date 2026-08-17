package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.name.TargetNameJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarkerPacketCodecTest {

	private static final String UNICODE_DIMENSION = "pingforit:维度\\u00df\u00e9";
	private static final String UNICODE_BLOCK = "pingforit:方块\u00e9";
	private static final String UNICODE_TARGET_TYPE = "目标.type";
	private static final String UNICODE_PING_TYPE = "注意.type";
	private static final UUID ENTITY_ID = UUID.randomUUID();

	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	@Test
	void targetRoundTripsForEveryKind() {
		Target[] targets = {
			new Target.EntityTarget("minecraft:overworld", ENTITY_ID),
			new Target.EntityTarget("minecraft:overworld", EntityLocator.runtimeId(17)),
			new Target.BlockTarget("minecraft:overworld", -5, 64, 200, "minecraft:chest"),
			new Target.LocationTarget("minecraft:the_end", 12.5, -64.0, 0.25)
		};

		for (Target target : targets) {
			var buf = buffer();
			MarkerPacketCodec.writeTarget(buf, target);
			assertEquals(target, MarkerPacketCodec.readTarget(buf));
		}
	}

	@Test
	void targetPreservesUnicodeDimensionAndRegistryIds() {
		var blockTarget = new Target.BlockTarget(UNICODE_DIMENSION, 1, 2, 3, UNICODE_BLOCK);
		var entityTarget = new Target.EntityTarget(UNICODE_DIMENSION, ENTITY_ID);

		var buf = buffer();
		MarkerPacketCodec.writeTarget(buf, blockTarget);
		MarkerPacketCodec.writeTarget(buf, entityTarget);

		assertEquals(blockTarget, MarkerPacketCodec.readTarget(buf));
		assertEquals(entityTarget, MarkerPacketCodec.readTarget(buf));
	}

	@Test
	void targetKeyRoundTripsForEveryKind() {
		TargetKey[] keys = {
			new TargetKey.EntityKey("minecraft:overworld", ENTITY_ID),
			new TargetKey.EntityKey("minecraft:overworld", EntityLocator.runtimeId(17)),
			new TargetKey.BlockKey("minecraft:overworld", -5, 64, 200, "minecraft:chest"),
			new TargetKey.LocationKey("minecraft:the_end", 12.5, -64.0, 0.25)
		};

		for (TargetKey key : keys) {
			var buf = buffer();
			MarkerPacketCodec.writeTargetKey(buf, key);
			assertEquals(key, MarkerPacketCodec.readTargetKey(buf));
		}
	}

	@Test
	void enumsRoundTripByStableName() {
		for (TargetKind value : TargetKind.values()) {
			var buf = buffer();
			MarkerPacketCodec.writeEnum(buf, value);
			assertEquals(value, MarkerPacketCodec.readEnum(buf, TargetKind.class));
		}

		for (MarkerRequestKind value : MarkerRequestKind.values()) {
			var buf = buffer();
			MarkerPacketCodec.writeEnum(buf, value);
			assertEquals(value, MarkerPacketCodec.readEnum(buf, MarkerRequestKind.class));
		}

		for (MarkerRejectReason value : MarkerRejectReason.values()) {
			var buf = buffer();
			MarkerPacketCodec.writeEnum(buf, value);
			assertEquals(value, MarkerPacketCodec.readEnum(buf, MarkerRejectReason.class));
		}

		for (MarkerRemovalReason value : MarkerRemovalReason.values()) {
			var buf = buffer();
			MarkerPacketCodec.writeEnum(buf, value);
			assertEquals(value, MarkerPacketCodec.readEnum(buf, MarkerRemovalReason.class));
		}
	}

	@Test
	void markerIdRoundTripsIncludingBounds() {
		for (long value : new long[] {0L, 1L, Long.MAX_VALUE}) {
			var buf = buffer();
			MarkerPacketCodec.writeMarkerId(buf, new MarkerId(value));
			assertEquals(new MarkerId(value), MarkerPacketCodec.readMarkerId(buf));
		}
	}

	@Test
	void optionalMarkerIdRoundTripsPresentAndEmpty() {
		var present = new MarkerId(123L);

		var buf = buffer();
		MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.of(present));
		MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.empty());

		assertEquals(Optional.of(present), MarkerPacketCodec.readOptionalMarkerId(buf));
		assertEquals(Optional.empty(), MarkerPacketCodec.readOptionalMarkerId(buf));
	}

	@Test
	void anchorRoundTrips() {
		var anchor = new MarkerAnchor(0.5, -1.25, 100_000.75);

		var buf = buffer();
		MarkerPacketCodec.writeMarkerAnchor(buf, anchor);
		assertEquals(anchor, MarkerPacketCodec.readMarkerAnchor(buf));
	}

	@Test
	void targetNameJsonRoundTrips() {
		var name = new TargetNameJson("{\"translate\":\"僵尸.类型\"}");

		var buf = buffer();
		MarkerPacketCodec.writeTargetNameJson(buf, name);

		assertEquals(name, MarkerPacketCodec.readTargetNameJson(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void targetNameJsonAcceptsExactlyMaxLengthOnWriteAndRead() {
		// {"text":" ... "} wrapper is exactly 11 characters.
		String json = "{\"text\":\"" + "x".repeat(MarkerPacketCodec.MAX_NAME_LENGTH - 11) + "\"}";

		var buf = buffer();
		MarkerPacketCodec.writeTargetNameJson(buf, new TargetNameJson(json));

		assertEquals(json, MarkerPacketCodec.readTargetNameJson(buf).value());
	}

	@Test
	void targetNameJsonRejectsBlankOnRead() {
		var buf = buffer();
		buf.writeUtf(" ");

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTargetNameJson(buf));
	}

	@Test
	void targetNameJsonRejectsOverlongOnRead() {
		var buf = buffer();
		String tooLong = "x".repeat(MarkerPacketCodec.MAX_NAME_LENGTH + 1);
		buf.writeVarInt(tooLong.length());
		buf.writeBytes(tooLong.getBytes(StandardCharsets.UTF_8));

		assertThrows(RuntimeException.class, () -> MarkerPacketCodec.readTargetNameJson(buf));
	}

	@Test
	void snapshotRoundTrips() {
		var snapshot = new MarkerSnapshot(
			new MarkerId(99L),
			UUID.randomUUID(),
			new Target.BlockTarget(UNICODE_DIMENSION, 1, 2, 3, UNICODE_BLOCK),
			UNICODE_TARGET_TYPE,
			UNICODE_PING_TYPE,
			new MarkerAnchor(0.5, 64.0, -8.25),
			10L,
			1000L
		);

		var buf = buffer();
		MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
		assertEquals(snapshot, MarkerPacketCodec.readMarkerSnapshot(buf));
	}

	@Test
	void rejectsUnknownTargetTag() {
		var buf = buffer();
		buf.writeUtf("NOPE", MarkerPacketCodec.MAX_ID_LENGTH);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTarget(buf));
	}

	@Test
	void rejectsUnknownTargetKeyTag() {
		var buf = buffer();
		buf.writeUtf("NOPE", MarkerPacketCodec.MAX_ID_LENGTH);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTargetKey(buf));
	}

	@Test
	void rejectsUnknownEntityLocatorTag() {
		var buf = buffer();
		MarkerPacketCodec.writeEnum(buf, TargetKind.ENTITY);
		MarkerPacketCodec.writeIdString(buf, "minecraft:overworld");
		buf.writeVarInt(99);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTarget(buf));
	}

	@Test
	void rejectsUnknownEntityLocatorTagForTargetKey() {
		var buf = buffer();
		MarkerPacketCodec.writeEnum(buf, TargetKind.ENTITY);
		MarkerPacketCodec.writeIdString(buf, "minecraft:overworld");
		buf.writeVarInt(99);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTargetKey(buf));
	}

	@Test
	void rejectsNegativeRuntimeEntityLocatorId() {
		var buf = buffer();
		MarkerPacketCodec.writeEnum(buf, TargetKind.ENTITY);
		MarkerPacketCodec.writeIdString(buf, "minecraft:overworld");
		buf.writeVarInt(MarkerPacketCodec.ENTITY_LOCATOR_RUNTIME_ID_TAG);
		buf.writeVarInt(-1);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTarget(buf));
	}

	@Test
	void rejectsNegativeRuntimeEntityLocatorIdForTargetKey() {
		var buf = buffer();
		MarkerPacketCodec.writeEnum(buf, TargetKind.ENTITY);
		MarkerPacketCodec.writeIdString(buf, "minecraft:overworld");
		buf.writeVarInt(MarkerPacketCodec.ENTITY_LOCATOR_RUNTIME_ID_TAG);
		buf.writeVarInt(-1);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTargetKey(buf));
	}

	@Test
	void rejectsTruncatedRuntimeEntityLocator() {
		var buf = buffer();
		MarkerPacketCodec.writeEnum(buf, TargetKind.ENTITY);
		MarkerPacketCodec.writeIdString(buf, "minecraft:overworld");
		buf.writeVarInt(MarkerPacketCodec.ENTITY_LOCATOR_RUNTIME_ID_TAG);

		assertThrows(RuntimeException.class, () -> MarkerPacketCodec.readTarget(buf));
	}

	@Test
	void rejectsUnknownEnumName() {
		var buf = buffer();
		buf.writeUtf("NOPE", MarkerPacketCodec.MAX_ID_LENGTH);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readEnum(buf, MarkerRejectReason.class));
	}

	@Test
	void rejectsOverlongIdStringOnRead() {
		var buf = buffer();
		buf.writeUtf("x".repeat(MarkerPacketCodec.MAX_ID_LENGTH * 40));

		assertThrows(Exception.class, () -> MarkerPacketCodec.readIdString(buf));
	}

	@Test
	void idStringAcceptsExactlyMaxLengthOnWriteAndRead() {
		String value = "x".repeat(MarkerPacketCodec.MAX_ID_LENGTH);

		var buf = buffer();
		MarkerPacketCodec.writeIdString(buf, value);

		assertEquals(value, MarkerPacketCodec.readIdString(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void idStringRejectsOneOverMaxLengthOnWriteAndRead() {
		String tooLong = "x".repeat(MarkerPacketCodec.MAX_ID_LENGTH + 1);

		assertThrows(RuntimeException.class,
			() -> MarkerPacketCodec.writeIdString(buffer(), tooLong));

		var buf = buffer();
		buf.writeUtf(tooLong);

		assertThrows(RuntimeException.class, () -> MarkerPacketCodec.readIdString(buf));
	}

	@Test
	void rejectsOverlongIdStringOnWrite() {
		var buf = buffer();

		assertThrows(RuntimeException.class,
			() -> MarkerPacketCodec.writeIdString(buf, "x".repeat(MarkerPacketCodec.MAX_ID_LENGTH + 1)));
	}

	@Test
	void rejectsNegativeMarkerIdOnRead() {
		var buf = buffer();
		buf.writeLong(-1L);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readMarkerId(buf));
	}

	@Test
	void rejectsBlankDimensionIdOnRead() {
		var buf = buffer();
		MarkerPacketCodec.writeEnum(buf, TargetKind.ENTITY);
		MarkerPacketCodec.writeIdString(buf, " ");
		// The uuid must be present so the record constructor reaches the
		// dimension validation instead of failing on a truncated buffer.
		buf.writeUUID(ENTITY_ID);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readTarget(buf));
	}

	@Test
	void rejectsNonFiniteAnchorCoordinatesOnRead() {
		var buf = buffer();
		buf.writeDouble(Double.NaN);
		buf.writeDouble(0.0);
		buf.writeDouble(0.0);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readMarkerAnchor(buf));
	}

	@Test
	void rejectsBlankSnapshotTypeIdsOnRead() {
		var buf = buffer();
		MarkerPacketCodec.writeMarkerId(buf, new MarkerId(1L));
		buf.writeUUID(UUID.randomUUID());
		MarkerPacketCodec.writeTarget(buf, new Target.LocationTarget("minecraft:overworld", 0, 0, 0));
		MarkerPacketCodec.writeIdString(buf, " ");
		MarkerPacketCodec.writeIdString(buf, "attention");
		MarkerPacketCodec.writeMarkerAnchor(buf, new MarkerAnchor(0, 0, 0));
		buf.writeLong(1L);
		buf.writeLong(100L);

		assertThrows(IllegalArgumentException.class, () -> MarkerPacketCodec.readMarkerSnapshot(buf));
	}
}
