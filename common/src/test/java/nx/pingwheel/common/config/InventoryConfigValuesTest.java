package nx.pingwheel.common.config;

import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.*;

class InventoryConfigValuesTest {
	@ParameterizedTest
	@EnumSource(Field.class)
	void eachLeafMergePreservesEveryUnselectedCurrentValue(Field selected) {
		var current = InventoryConfigValues.defaults();
		var replacement = current;
		for (Field field : Field.values()) replacement = replacement.with(field, different(current, field));
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23, current);
		var update = new ServerConfigUpdate(selected.mask(), ChannelMode.DISABLED, false, 99, 88, 44, replacement);

		var result = ServerConfigUpdateService.apply(true, snapshot, update);
		assertTrue(result.applied());
		for (Field field : Field.values()) {
			assertEquals((field == selected ? replacement : current).value(field), result.snapshot().inventory().value(field), field.name());
		}
		assertEquals(snapshot.defaultChannelMode(), result.snapshot().defaultChannelMode());
		assertEquals(snapshot.syncDuration(), result.snapshot().syncDuration());
		assertEquals(current, snapshot.inventory());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void invalidLeafRejectsEntireMixedUpdateAndUnsafeSnapshots(Field invalid) {
		var inventory = InventoryConfigValues.defaults().with(invalid, new Value(true, BigDecimal.valueOf(-1)));
		assertFalse(inventory.isSafe());
		var update = new ServerConfigUpdate(ServerConfigUpdate.RATE_LIMIT | invalid.mask(), ChannelMode.AUTO, true, 0, 99, 7, inventory);
		assertFalse(update.isValid());
		var current = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23);
		var result = ServerConfigUpdateService.apply(true, current, update);
		assertFalse(result.applied());
		assertEquals(current, result.snapshot());
		assertFalse(new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 0, 0, 7, inventory).isSafe());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void eachLeafConvertsToAndFromItsOwnPersistedMember(Field field) {
		var defaults = InventoryConfigValues.defaults();
		var values = defaults.with(field, different(defaults, field));
		assertEquals(values, InventoryConfigValues.from(values.toSettings()));
		assertEquals(values, new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 0, 0, 7, values)
			.withCanEdit(false).inventory());
	}

	@Test
	void copiedValuesAndConversionsDoNotAliasMutableInputsOrOutputs() {
		var persisted = InventorySettings.serverDefaults();
		persisted.getTracking().getSnapshotByteMultiplier().setValue(new BigDecimal("0.5"));
		persisted.getTracking().getSnapshotByteMultiplier().setUnlimited(true);
		var values = InventoryConfigValues.from(persisted);
		var map = new EnumMap<Field, Value>(Field.class);
		map.putAll(values.fields());
		var copied = new InventoryConfigValues(map);
		map.clear();
		persisted.getTracking().getSnapshotByteMultiplier().setValue(BigDecimal.TEN);
		persisted.getPhysicalSlotsPerTick().setUnlimited(true);
		assertEquals(values, copied);
		assertThrows(UnsupportedOperationException.class, () -> copied.fields().clear());
		var first = values.toSettings();
		var second = values.toSettings();
		first.getPreview().setPeriodTicks(99);
		first.getTracking().getSnapshotByteMultiplier().setValue(BigDecimal.TEN);
		assertEquals(values, InventoryConfigValues.from(second));
		assertNotSame(first.getPreview(), second.getPreview());
		assertTrue(second.getTracking().getSnapshotByteMultiplier().isUnlimited());
		assertEquals(0, new BigDecimal("0.5").compareTo(second.getTracking().getSnapshotByteMultiplier().getValue()));
	}

	@Test
	void currentConcurrentNestedEditsSurviveAnOlderDraftAndPermissionDenial() {
		var baseline = InventoryConfigValues.defaults();
		var current = baseline.with(Field.PREVIEW_MAX_SLOTS_SERVER, Value.limit(true, 513))
			.with(Field.PENDING_MEMORY_MIB, Value.limit(true, 65));
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23, current);
		var update = new ServerConfigUpdate(Field.PREVIEW_PERIOD_TICKS.mask() | ServerConfigUpdate.RATE_LIMIT,
			ChannelMode.GLOBAL, false, 0, 9, 7, baseline.with(Field.PREVIEW_PERIOD_TICKS, Value.scalar(7)));
		var denied = ServerConfigUpdateService.apply(false, snapshot, update);
		assertFalse(denied.applied());
		assertEquals(current, denied.snapshot().inventory());
		var applied = ServerConfigUpdateService.apply(true, snapshot, update).snapshot();
		assertEquals(Value.limit(true, 513), applied.inventory().value(Field.PREVIEW_MAX_SLOTS_SERVER));
		assertEquals(Value.limit(true, 65), applied.inventory().value(Field.PENDING_MEMORY_MIB));
		assertEquals(4294967296L, applied.inventory().toSettings().pendingMemoryBytes());
		assertEquals(Value.scalar(7), applied.inventory().value(Field.PREVIEW_PERIOD_TICKS));
		assertEquals(9, applied.rateLimit());
	}

	@Test
	void missingNullOffGridAndUnexpectedUnlimitedAreUnsafeWithoutDefaulting() {
		assertFalse(new InventoryConfigValues(null).isSafe());
		assertFalse(InventoryConfigValues.defaults().with(Field.PHYSICAL_SLOTS_PER_TICK, null).isSafe());
		assertNull(InventoryConfigValues.from(null));
		var persisted = InventorySettings.serverDefaults();
		persisted.setPreview(null);
		assertNull(InventoryConfigValues.from(persisted));
		assertFalse(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER.isSafe(new Value(false, new BigDecimal("4.25"))));
		assertTrue(Field.PENDING_MEMORY_MIB.isSafe(Value.limit(true, 17)));
		assertFalse(Field.PREVIEW_PERIOD_TICKS.isSafe(Value.limit(true, 7)));
		assertTrue(Field.TRACKING_HEARTBEAT_PERIODS.isSafe(Value.scalar(0)));
		assertFalse(Field.TRACKING_GRACE_PERIODS.isSafe(Value.scalar(0)));
		assertTrue(Field.TRACKING_RESYNC_MIN_PERIODS.isSafe(Value.scalar(0)));
		assertFalse(Field.TRACKING_RESYNC_MIN_PERIODS.isSafe(Value.scalar(Integer.MAX_VALUE)));
		assertFalse(new ServerConfigSnapshot(true, null, true, 0, 0, 7).isSafe());
	}

	@Test
	void maskIsExactlyTwentyOneNewLeavesAndFiveOriginalFields() {
		int mask = 0;
		for (Field field : Field.values()) {
			assertEquals(0, mask & field.mask());
			mask |= field.mask();
		}
		assertEquals(21, Field.values().length);
		assertEquals(((1 << 26) - 1) & ~31, mask);
		assertEquals((1 << 27) - 1, ServerConfigUpdate.ALL_FIELDS);
		assertEquals(Field.DISPLAY_BYTE_MULTIPLIER.mask(), ServerConfigUpdate.INVENTORY_DISPLAY_BYTE_MULTIPLIER);
		assertEquals(Field.FRAME_BYTE_MULTIPLIER.mask(), ServerConfigUpdate.INVENTORY_FRAME_BYTE_MULTIPLIER);
	}

	@Test
	void sizeScaleLeavesAreFiniteExactScalarsAndUnlimitedIsRejected() {
		var defaults = InventoryConfigValues.defaults();
		assertEquals(new Value(false, BigDecimal.ONE), defaults.value(Field.DISPLAY_BYTE_MULTIPLIER));
		assertEquals(new Value(false, BigDecimal.ONE), defaults.value(Field.FRAME_BYTE_MULTIPLIER));
		assertFalse(Field.DISPLAY_BYTE_MULTIPLIER.supportsUnlimited());
		assertFalse(Field.FRAME_BYTE_MULTIPLIER.supportsUnlimited());
		assertFalse(Field.DISPLAY_BYTE_MULTIPLIER.isSafe(new Value(true, BigDecimal.ONE)));
		assertFalse(Field.FRAME_BYTE_MULTIPLIER.isSafe(new Value(true, BigDecimal.ONE)));
		assertFalse(Field.DISPLAY_BYTE_MULTIPLIER.isSafe(new Value(false, null)));
		assertTrue(Field.DISPLAY_BYTE_MULTIPLIER.isSafe(new Value(false, new BigDecimal("32"))));
		assertFalse(Field.DISPLAY_BYTE_MULTIPLIER.isSafe(new Value(false, new BigDecimal("4.25"))));
		assertFalse(Field.DISPLAY_BYTE_MULTIPLIER.isSafe(new Value(false, new BigDecimal("0.125"))));
		assertTrue(Field.FRAME_BYTE_MULTIPLIER.isSafe(new Value(false, new BigDecimal("16"))));
		assertFalse(Field.FRAME_BYTE_MULTIPLIER.isSafe(new Value(false, new BigDecimal("16.25"))));
	}

	@Test
	void pairCompatibilityRequiresBothLeavesAndTheFrameMetadataReserve() {
		assertTrue(InventoryConfigValues.defaults().isSizePairCompatible());
		var largeDisplay = InventoryConfigValues.defaults()
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("32")));
		assertFalse(largeDisplay.isSizePairCompatible());
		assertTrue(largeDisplay.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, new BigDecimal("4.25")))
			.isSizePairCompatible());
	}

	@Test
	void invalidMergedSizePairIsRejectedAtomicallyWithoutTouchingUnselectedLeaves() {
		var current = InventoryConfigValues.defaults()
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("2")))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, new BigDecimal("2")))
			.with(Field.TRACKING_HEARTBEAT_PERIODS, Value.scalar(7));
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23, current);
		// Selecting an 8 KiB frame leaves the current 8 KiB display one reserve over.
		var replacement = current.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, new BigDecimal("0.25")));
		var update = new ServerConfigUpdate(
			Field.FRAME_BYTE_MULTIPLIER.mask(), ChannelMode.DISABLED, false, 1, 2, 3, replacement);

		var result = ServerConfigUpdateService.apply(true, snapshot, update);
		assertFalse(result.applied());
		assertEquals(snapshot, result.snapshot());
		assertEquals(new Value(false, new BigDecimal("2")),
			result.snapshot().inventory().value(Field.DISPLAY_BYTE_MULTIPLIER));
		assertEquals(new Value(false, new BigDecimal("2")),
			result.snapshot().inventory().value(Field.FRAME_BYTE_MULTIPLIER));
		assertEquals(Value.scalar(7), result.snapshot().inventory().value(Field.TRACKING_HEARTBEAT_PERIODS));
		assertSame(snapshot, update.applyTo(snapshot));
	}

	@Test
	void selectedBothSizeLeavesWithAValidPairAreAccepted() {
		var current = InventoryConfigValues.defaults();
		var snapshot = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23, current);
		var replacement = current
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("32")))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, new BigDecimal("4.25")));
		var update = new ServerConfigUpdate(
			Field.DISPLAY_BYTE_MULTIPLIER.mask() | Field.FRAME_BYTE_MULTIPLIER.mask(),
			ChannelMode.AUTO, true, 1000, 5, 23, replacement);

		var result = ServerConfigUpdateService.apply(true, snapshot, update);
		assertTrue(result.applied());
		assertEquals(new Value(false, new BigDecimal("32")),
			result.snapshot().inventory().value(Field.DISPLAY_BYTE_MULTIPLIER));
		assertEquals(new Value(false, new BigDecimal("4.25")),
			result.snapshot().inventory().value(Field.FRAME_BYTE_MULTIPLIER));
	}

	@Test
	void unsafeCurrentAndMissingUpdateCannotReportAnAppliedTransaction() {
		var valid = new ServerConfigUpdate(ServerConfigUpdate.RATE_LIMIT, ChannelMode.AUTO, true, 0, 99, 7);
		assertFalse(ServerConfigUpdateService.apply(true, null, valid).applied());
		var unsafe = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 0, 0, 7, null);
		assertFalse(ServerConfigUpdateService.apply(true, unsafe, valid).applied());
		assertSame(unsafe, valid.applyTo(unsafe));
		var current = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 0, 0, 7);
		assertFalse(ServerConfigUpdateService.apply(true, current, null).applied());
		assertNotSame(current.inventory(), new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 0, 0, 7).inventory());
	}

	private static Value different(InventoryConfigValues values, Field field) {
		Value current = values.value(field);
		if (field.isSizeScale()) return new Value(false, field.sizeScale().next(current.value()));
		return field.isMultiplier() ? new Value(true, field.grid().next(current.value()))
			: new Value(field.supportsUnlimited(), current.value().intValueExact() == field.maximum()
				? BigDecimal.valueOf(field.minimum()) : current.value().add(BigDecimal.ONE));
	}
}
