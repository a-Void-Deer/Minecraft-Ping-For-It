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
		// Leave room for either size leaf's replacement without changing its unselected partner.
		var current = InventoryConfigValues.defaults()
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("2")))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, new BigDecimal("2")));
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
		assertEquals(InventoryLimits.INTERNAL_PENDING_MEMORY_MIB_GUARD * 1024L * 1024L,
			applied.inventory().toSettings().pendingMemoryBytes(),
			"unlimited pending memory uses the finite internal guard instead of the retained 65 MiB");
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
	void masksPreserveLegacyLeavesAndMonitoringWhileAppendingIndependentBooleanBits() {
		int mask = 0;
		for (Field field : Field.values()) {
			assertEquals(0, mask & field.mask());
			mask |= field.mask();
		}
		assertEquals(23, Field.values().length);
		assertEquals((((1 << 26) - 1) & ~31) | (1 << 27) | (1 << 28), mask);
		assertEquals((1 << 29) - 1, ServerConfigUpdate.ALL_FIELDS);
		for (Field field : Field.values()) {
			if (!field.isBoolean()) assertEquals(1 << (field.ordinal() + 5), field.mask());
		}
		assertEquals(1 << 26, ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED);
		assertEquals(1 << 27, Field.TRACKING_DISTINGUISH_FACES.mask());
		assertEquals(1 << 28, Field.TRACKING_DISTINGUISH_PLAYERS.mask());
		assertEquals(Field.DISPLAY_BYTE_MULTIPLIER.mask(), ServerConfigUpdate.INVENTORY_DISPLAY_BYTE_MULTIPLIER);
		assertEquals(Field.FRAME_BYTE_MULTIPLIER.mask(), ServerConfigUpdate.INVENTORY_FRAME_BYTE_MULTIPLIER);
	}

	@Test
	void sizeScaleLeavesAreFiniteExactScalarsAndUnlimitedIsRejected() {
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
		var legalPair = InventoryConfigValues.defaults()
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("2")))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, new BigDecimal("4.25")));
		assertTrue(legalPair.isSizePairCompatible());
		assertFalse(legalPair.with(Field.DISPLAY_BYTE_MULTIPLIER, null).isSizePairCompatible());
		assertFalse(legalPair.with(Field.FRAME_BYTE_MULTIPLIER, null).isSizePairCompatible());

		// A 32x display budget is 128 KiB; the 2 KiB metadata reserve does not fit
		// in a 4x (128 KiB) frame, while the next quarter-multiplier step does.
		var largestDisplay = legalPair
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("32")))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, new BigDecimal("4")));
		assertFalse(largestDisplay.isSizePairCompatible());
		assertTrue(largestDisplay.with(Field.FRAME_BYTE_MULTIPLIER,
			new Value(false, new BigDecimal("4.25"))).isSizePairCompatible());
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

	@ParameterizedTest
	@EnumSource(value = Field.class, names = {"TRACKING_DISTINGUISH_FACES", "TRACKING_DISTINGUISH_PLAYERS"})
	void booleanLeavesRejectNumericDraftsUnlimitedAndNonBooleanInternalValues(Field field) {
		assertEquals(InventoryConfigValues.Kind.BOOLEAN, field.kind());
		assertFalse(field.supportsUnlimited());
		assertTrue(field.parse("true", false).orElseThrow().booleanValue());
		assertFalse(field.parse("false", false).orElseThrow().booleanValue());
		for (String invalid : java.util.List.of("0", "1", "2", "TRUE", "", "0.5", "NaN")) {
			assertTrue(field.parse(invalid, false).isEmpty(), invalid);
		}
		assertTrue(field.parse("true", true).isEmpty());
		for (Value invalid : java.util.List.of(Value.scalar(-1), Value.scalar(2), Value.limit(true, 1),
			new Value(false, new BigDecimal("0.5")), new Value(false, null))) {
			assertFalse(field.isSafe(invalid));
			assertThrows(IllegalStateException.class, invalid::booleanValue);
		}
		assertThrows(UnsupportedOperationException.class, () -> InventoryConfigValues.defaults().fields().clear());
	}

	@Test
	void unselectedTrueFlagsSurviveNumericReconstructionAndSingleFlagMergePreservesConcurrentChanges() {
		var current = InventoryConfigValues.defaults()
			.with(Field.TRACKING_DISTINGUISH_FACES, Value.bool(true))
			.with(Field.TRACKING_DISTINGUISH_PLAYERS, Value.bool(true))
			.with(Field.PENDING_MEMORY_MIB, Value.limit(true, 65));
		var authoritative = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 19, 23, current);
		var stale = InventoryConfigValues.defaults().with(Field.TRACKING_PERIOD_TICKS, Value.scalar(7));
		var numeric = new ServerConfigUpdate(Field.TRACKING_PERIOD_TICKS.mask(), ChannelMode.AUTO, true, 1, 2, 3, stale);
		var merged = numeric.appliedTo(authoritative).orElseThrow();
		var persisted = merged.inventory().toSettings();
		assertTrue(persisted.getTracking().isDistinguishFaces());
		assertTrue(persisted.getTracking().isDistinguishPlayers());
		assertEquals(7, persisted.getTracking().getPeriodTicks());
		assertEquals(65, persisted.getPendingMemoryMiB().getValue());
		assertEquals(19, merged.rateLimit());

		var faceOnly = new ServerConfigUpdate(Field.TRACKING_DISTINGUISH_FACES.mask(),
			ChannelMode.DISABLED, false, 0, 0, 7, InventoryConfigValues.defaults());
		var faceMerged = faceOnly.appliedTo(merged).orElseThrow();
		assertFalse(faceMerged.inventory().toSettings().getTracking().isDistinguishFaces());
		assertTrue(faceMerged.inventory().toSettings().getTracking().isDistinguishPlayers());
		assertEquals(Value.scalar(7), faceMerged.inventory().value(Field.TRACKING_PERIOD_TICKS));
		assertEquals(Value.limit(true, 65), faceMerged.inventory().value(Field.PENDING_MEMORY_MIB));
		assertEquals(19, faceMerged.rateLimit());
	}

	private static Value different(InventoryConfigValues values, Field field) {
		Value current = values.value(field);
		if (field.isBoolean()) return Value.bool(!current.booleanValue());
		if (field.isSizeScale()) return new Value(false, field.sizeScale().next(current.value()));
		return field.isMultiplier() ? new Value(true, field.grid().next(current.value()))
			: new Value(field.supportsUnlimited(), current.value().intValueExact() == field.maximum()
				? BigDecimal.valueOf(field.minimum()) : current.value().add(BigDecimal.ONE));
	}
}
