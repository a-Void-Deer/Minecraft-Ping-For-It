package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
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
class InventoryStepTest {
	private static CaptureResult metadata(int end, CaptureResult.Consistency consistency) {
		return new CaptureResult(Optional.empty(), new CaptureResult.Coverage("view", end, end, OptionalLong.of(100)),
			CaptureResult.Availability.READABLE, CaptureResult.Completeness.CONTINUE, consistency, Optional.empty(), Optional.empty());
	}
	private static InventoryDomainCodec.Item item(long amount) {
		return new InventoryDomainCodec.Item(new InventoryScanner.Key("test:value", "exact"), amount, "item.test.value", null, false);
	}

	@Test void contiguousAbsolutePagePreservesLongsEmptySlotsAndDetachedList() {
		var source = new ArrayList<InventoryDomainCodec.@Nullable Item>(Arrays.asList(item((1L << 53) + 1), null, item(Long.MAX_VALUE)));
		var page = new InventoryStep(metadata(35, CaptureResult.Consistency.EVENTUAL), 32, source, InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP);
		source.clear();
		assertEquals(3, page.slots().size()); assertNull(page.slots().get(1));
		assertThrows(UnsupportedOperationException.class, () -> page.slots().clear());
		assertTrue(page.metadata().payload().isEmpty());
		var encoded = page.toLegacy();
		var entries = ((CaptureResult.OpaqueKeyedFragment) encoded.payload().orElseThrow()).entries();
		assertEquals((1L << 53) + 1, InventoryDomainCodec.decode(entries.get("32")).count());
		assertNull(InventoryDomainCodec.decode(entries.get("33")));
		assertEquals(Long.MAX_VALUE, InventoryDomainCodec.decode(entries.get("34")).count());
		var decoded = ((InventoryStep.Outcome.Captured) InventoryStep.fromLegacy(new SourceAccess.StepOutcome.Captured(encoded))).step();
		assertEquals(32, decoded.firstSlot()); assertEquals(page.slots(), decoded.slots());
		assertEquals(InventoryStep.SweepEvidence.NONE, decoded.evidence(), "generic results cannot carry native sweep acceptance");
		assertEquals(CaptureResult.Consistency.EVENTUAL, decoded.metadata().consistency());
	}

	@Test void rejectsPayloadMixSparseOrOversizedPagesAndPreservesDeferral() {
		var payload = new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("0", InventoryDomainCodec.encode(item(1))));
		var mixed = new CaptureResult(Optional.of(payload), metadata(1, CaptureResult.Consistency.VERIFIED).coverage(),
			CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.VERIFIED, Optional.empty(), Optional.empty());
		assertThrows(IllegalArgumentException.class, () -> new InventoryStep(mixed, 0, List.of(item(1)), InventoryStep.SweepEvidence.NONE));
		assertThrows(IllegalArgumentException.class, () -> InventoryStep.fromLegacy(new SourceAccess.StepOutcome.Captured(
			new CaptureResult(Optional.of(payload), metadata(2, CaptureResult.Consistency.EVENTUAL).coverage(), CaptureResult.Availability.READABLE,
				CaptureResult.Completeness.CONTINUE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty()))));
		assertThrows(IllegalArgumentException.class, () -> new InventoryStep(metadata(65, CaptureResult.Consistency.EVENTUAL), 0,
			java.util.Collections.nCopies(65, null), InventoryStep.SweepEvidence.NONE));
		assertInstanceOf(InventoryStep.Outcome.Deferred.class, InventoryStep.fromLegacy(new SourceAccess.StepOutcome.Deferred()));
	}
}
