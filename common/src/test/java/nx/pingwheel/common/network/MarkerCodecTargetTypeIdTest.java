package nx.pingwheel.common.network;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression: the resolved target type id — including the new
 * {@code entity_block} id — travels verbatim through the marker snapshot
 * codec, so the {@code entity_block} classification needs no protocol shape
 * change (the existing {@code targetTypeId} field already syncs).
 */
class MarkerCodecTargetTypeIdTest {

	@Test
	void entityBlockTargetTypeIdRoundTripsVerbatim() {
		MarkerSnapshot snapshot = new MarkerSnapshot(
			new MarkerId(42L),
			UUID.randomUUID(),
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			"entity_block",
			"attention",
			new MarkerAnchor(1.5, 2.5, 3.5),
			100L,
			200L);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
		MarkerSnapshot decoded = MarkerPacketCodec.readMarkerSnapshot(buf);

		assertEquals("entity_block", decoded.targetTypeId());
		assertEquals(snapshot, decoded);
	}

	@Test
	void everyBuiltInTargetTypeIdSurvivesTheWire() {
		for (String targetTypeId : List.of("dropped_item", "entity", "entity_block", "block", "location")) {
			MarkerSnapshot snapshot = new MarkerSnapshot(
				new MarkerId(7L),
				UUID.randomUUID(),
				new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:stone"),
				targetTypeId,
				"attention",
				new MarkerAnchor(1.5, 2.5, 3.5),
				10L,
				20L);

			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);

			assertEquals(targetTypeId, MarkerPacketCodec.readMarkerSnapshot(buf).targetTypeId());
		}
	}
}
