package nx.pingwheel.common.presentation.inventory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.SourceAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryResourceCodecTest {
	private static InventoryResourceValue value(InventoryResourceKind kind) {
		return InventoryResourceCodec.value(new InventoryScanner.Key(kind, "minecraft:water", "native"),
			9007199254740993L, "water", null, false);
	}
	private static CaptureResult metadata(int count) {
		return new CaptureResult(Optional.empty(), new CaptureResult.Coverage("scope", count, count, OptionalLong.of(count)),
			CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL,
			Optional.empty(), Optional.empty());
	}
	@Test void matchingIdsAndAmountsNeverMakeCrossLaneObservationsInterchangeable() {
		for (var kind : InventoryResourceKind.values()) {
			var original = value(kind); var encoded = InventoryResourceCodec.encode(kind, original);
			assertEquals(original, InventoryResourceCodec.decode(kind, encoded));
			var other = kind == InventoryResourceKind.ITEM ? InventoryResourceKind.FLUID : InventoryResourceKind.ITEM;
			assertNotEquals(original.key(), value(other).key());
			assertThrows(IllegalArgumentException.class, () -> InventoryResourceCodec.encode(other, original));
			assertThrows(IllegalArgumentException.class, () -> InventoryResourceCodec.decode(other, encoded));
			assertEquals(kind, InventoryResourceCodec.kind(InventoryResourceCodec.id(kind)));
		}
	}
	@Test void typedFluidPageAndObservedEmptiesRoundTripWithoutItemCodecOrInventedSweepEvidence() {
		List<@Nullable InventoryResourceValue> values = Arrays.asList(null, value(InventoryResourceKind.FLUID), null);
		var page = new InventoryStep(metadata(3), 0, values, InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP);
		var legacy = page.toLegacy(InventoryResourceKind.FLUID);
		assertEquals(InventoryFluidCodec.CODEC.id(), ((CaptureResult.OpaqueKeyedFragment) legacy.payload().orElseThrow()).codecId());
		var roundtrip = ((InventoryStep.Outcome.Captured) InventoryStep.fromLegacy(new SourceAccess.StepOutcome.Captured(legacy))).step();
		assertEquals(values, roundtrip.slots()); assertEquals(InventoryStep.SweepEvidence.NONE, roundtrip.evidence());
		assertThrows(UnsupportedOperationException.class, () -> roundtrip.slots().clear());
		var empty = new InventoryStep(metadata(1), 0, Arrays.asList((@Nullable InventoryResourceValue) null), InventoryStep.SweepEvidence.NONE);
		var encodedEmpty = (CaptureResult.OpaqueKeyedFragment) empty.toLegacy(InventoryResourceKind.FLUID).payload().orElseThrow();
		assertEquals(InventoryFluidCodec.CODEC, encodedEmpty.entries().get("0").codec());
		assertNull(InventoryFluidCodec.decode(encodedEmpty.entries().get("0")));
	}
	@Test void mixedTypedPagesAndNoncontiguousFragmentsAreRejectedBeforeReplay() {
		assertThrows(IllegalArgumentException.class, () -> new InventoryStep(metadata(2), 0,
			List.of(value(InventoryResourceKind.ITEM), value(InventoryResourceKind.FLUID)), InventoryStep.SweepEvidence.NONE));
		var encoded = InventoryResourceCodec.encode(InventoryResourceKind.FLUID, value(InventoryResourceKind.FLUID));
		var invalid = new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryFluidCodec.CODEC.id(), Map.of("1", encoded))),
			metadata(1).coverage(), CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE,
			CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
		assertThrows(IllegalArgumentException.class, () -> InventoryStep.fromLegacy(new SourceAccess.StepOutcome.Captured(invalid)));
	}
}
