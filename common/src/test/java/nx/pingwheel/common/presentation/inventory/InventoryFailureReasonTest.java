package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.marker.MarkerRejectReason;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class InventoryFailureReasonTest {

	@Test
	void wireIdsAreExplicitUniqueAndUnknownIdsFailStrictly() {
		Map<Integer, InventoryFailureReason> byId = new HashMap<>();
		for (InventoryFailureReason reason : InventoryFailureReason.values()) {
			assertTrue(reason.wireId() > 0, reason.name());
			assertNull(byId.put(reason.wireId(), reason), () -> "Duplicate wire id " + reason.wireId());
			assertEquals(reason, InventoryFailureReason.fromWireId(reason.wireId()));
		}
		assertEquals(1, InventoryFailureReason.TARGET_GONE.wireId());
		assertEquals(50, InventoryFailureReason.NOT_FOUND.wireId());
		assertEquals(51, InventoryFailureReason.INVALID_PING_TYPE.wireId());
		assertEquals(52, InventoryFailureReason.PUBLICATION_WORK_LIMIT.wireId());
		assertEquals(53, InventoryFailureReason.CAPTURE_READ_LIMIT.wireId());
		for (int unknown : new int[] {-1, 0, Integer.MAX_VALUE}) {
			assertThrows(IllegalArgumentException.class, () -> InventoryFailureReason.fromWireId(unknown));
		}
	}

	@Test
	void codesAndTranslationKeysAreStableAndDistinct() {
		Set<String> codes = new HashSet<>();
		Set<String> keys = new HashSet<>();
		for (InventoryFailureReason reason : InventoryFailureReason.values()) {
			assertTrue(reason.code().matches("[a-z0-9-]+"), reason.name());
			assertTrue(codes.add(reason.code()), () -> "Duplicate code " + reason.code());
			assertTrue(keys.add(reason.translationKey()), () -> "Duplicate key " + reason.translationKey());
			assertEquals("pingforit.inventory.reason." + reason.code().replace('-', '_'), reason.translationKey());
		}
	}

	@Test
	void genericMarkerRejectionsMapWithoutChangingTheGenericEnum() {
		assertEquals(InventoryFailureReason.TARGET_GONE, InventoryFailureReason.from(MarkerRejectReason.TARGET_GONE));
		assertEquals(InventoryFailureReason.OUT_OF_RANGE, InventoryFailureReason.from(MarkerRejectReason.OUT_OF_RANGE));
		assertEquals(InventoryFailureReason.CHANNEL_DISABLED, InventoryFailureReason.from(MarkerRejectReason.CHANNEL_DISABLED));
		assertEquals(InventoryFailureReason.RATE_LIMITED, InventoryFailureReason.from(MarkerRejectReason.RATE_LIMITED));
		assertEquals(InventoryFailureReason.INVALID_REQUEST, InventoryFailureReason.from(MarkerRejectReason.INVALID_REQUEST));
		assertEquals(InventoryFailureReason.NOT_OWNER, InventoryFailureReason.from(MarkerRejectReason.NOT_OWNER));
		assertEquals(InventoryFailureReason.NOT_FOUND, InventoryFailureReason.from(MarkerRejectReason.NOT_FOUND));
		assertEquals(InventoryFailureReason.INVALID_PING_TYPE, InventoryFailureReason.from(MarkerRejectReason.INVALID_PING_TYPE));
	}
}
