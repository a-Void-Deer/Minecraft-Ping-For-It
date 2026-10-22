package nx.pingwheel.common.presentation.source;

import nx.pingwheel.common.presentation.PresentationLimits;
import nx.pingwheel.common.presentation.PresentationValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceKeyCaptureResultTest {

	@Test
	void sameSourceIdentityAndReadScopeAreEqual() {
		var first = new SourceKey("test", "container", "overworld:0:64:0/minecraft:chest", "visible");
		var second = new SourceKey("test", "container", "overworld:0:64:0/minecraft:chest", "visible");

		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
	}

	@Test
	void oneDifferentReadScopeMakesADifferentSource() {
		var visible = new SourceKey("test", "container", "overworld:0:64:0/minecraft:chest", "visible");
		var hidden = new SourceKey("test", "container", "overworld:0:64:0/minecraft:chest", "hidden");

		assertNotEquals(visible, hidden);
	}

	@Test
	void identityTokensAreNonBlankAndBounded() {
		assertThrows(NullPointerException.class, () -> new SourceKey(null, "block", "id", "scope"));
		assertThrows(IllegalArgumentException.class, () -> new SourceKey(" ", "block", "id", "scope"));
		assertThrows(IllegalArgumentException.class, () -> new SourceKey("test", "\t", "id", "scope"));
		assertThrows(IllegalArgumentException.class, () -> new SourceKey("test", "block", "", "scope"));
		assertThrows(IllegalArgumentException.class, () -> new SourceKey("test", "block", "id", "  "));

		var atBound = "a".repeat(SourceKey.MAX_TOKEN_BYTES);
		assertEquals(atBound, new SourceKey(atBound, "block", "id", "scope").providerId());
		var overBound = "a".repeat(SourceKey.MAX_TOKEN_BYTES + 1);
		assertThrows(IllegalArgumentException.class, () -> new SourceKey(overBound, "block", "id", "scope"));
	}

	@Test
	void readableSnapshotCopyIsImmutable() {
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		fields.put("test:count", new PresentationValue.NumberValue(3.0));
		var result = readable(new CaptureResult.SnapshotRecord(fields));

		fields.put("test:late", new PresentationValue.Flag(true));

		var snapshot = (CaptureResult.SnapshotRecord) result.payload().orElseThrow();
		assertEquals(1, snapshot.fields().size());
		assertFalse(snapshot.fields().containsKey("test:late"), "construction must copy the caller map");
		assertThrows(UnsupportedOperationException.class,
			() -> snapshot.fields().put("test:extra", new PresentationValue.Flag(false)));
	}

	@Test
	void keyedFragmentCopyKeepsExplicitZeroAndOmitsUnknownKeys() {
		Map<String, PresentationValue> entries = new LinkedHashMap<>();
		entries.put("test:item/0", new PresentationValue.NumberValue(0.0));
		var fragment = new CaptureResult.KeyedFragment(entries);

		entries.put("test:item/1", new PresentationValue.NumberValue(7.0));

		assertEquals(1, fragment.entries().size());
		assertFalse(fragment.entries().containsKey("test:item/1"), "construction must copy the caller map");
		assertFalse(fragment.entries().containsKey("test:item/unknown"),
			"a missing key is absent, never a synthesized zero");
		assertEquals(new PresentationValue.NumberValue(0.0), fragment.entries().get("test:item/0"),
			"a directly observed zero stays explicit");
		assertThrows(UnsupportedOperationException.class,
			() -> fragment.entries().put("test:item/2", new PresentationValue.NumberValue(1.0)));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.KeyedFragment(Map.of(" ", new PresentationValue.Flag(true))));
	}

	@Test
	void emptySnapshotIsNotAbsentPayload() {
		var absent = new CaptureResult(Optional.empty(), coverage(0, 0), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.CONTINUE, CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty());
		var empty = new CaptureResult(Optional.of(new CaptureResult.SnapshotRecord(Map.of())), coverage(0, 0),
			CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL,
			Optional.empty(), Optional.empty());

		assertTrue(absent.payload().isEmpty());
		assertTrue(empty.payload().isPresent());
		assertTrue(((CaptureResult.SnapshotRecord) empty.payload().orElseThrow()).fields().isEmpty());
		assertNotEquals(absent, empty);
	}

	@Test
	void unavailableAndInvalidResultsRejectPayload() {
		Optional<CaptureResult.CapturePayload> payload =
			Optional.of(new CaptureResult.SnapshotRecord(Map.of("test:count", new PresentationValue.NumberValue(1.0))));

		for (var availability : new CaptureResult.Availability[] {
			CaptureResult.Availability.UNAVAILABLE, CaptureResult.Availability.INVALID }) {
			assertThrows(IllegalArgumentException.class,
				() -> new CaptureResult(payload, coverage(0, 0), availability, CaptureResult.Completeness.INCOMPLETE,
					CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty()));

			var detached = new CaptureResult(Optional.empty(), coverage(0, 0), availability,
				CaptureResult.Completeness.INCOMPLETE, CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty());
			assertTrue(detached.payload().isEmpty());
		}
	}

	@Test
	void completeEventualSweepMayCarryADirectlyObservedZero() {
		var snapshot = new CaptureResult.SnapshotRecord(Map.of("test:count", new PresentationValue.NumberValue(0.0)));
		var result = new CaptureResult(Optional.of(snapshot), coverage(4, 4), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());

		assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
		assertEquals(CaptureResult.Consistency.EVENTUAL, result.consistency());
		assertEquals(new PresentationValue.NumberValue(0.0),
			((CaptureResult.SnapshotRecord) result.payload().orElseThrow()).fields().get("test:count"));
	}

	@Test
	void coverageRejectsBlankStampAndNegativeCounts() {
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.Coverage(" ", 0, 0, OptionalLong.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.Coverage("demand-1", -1, 0, OptionalLong.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.Coverage("demand-1", 0, -1, OptionalLong.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.Coverage("demand-1", 0, 0, OptionalLong.of(-1)));
	}

	@Test
	void knownZeroExpectedIsDistinctFromUnknownExpected() {
		var unknown = new CaptureResult.Coverage("demand-1", 0, 0, OptionalLong.empty());
		var knownZero = new CaptureResult.Coverage("demand-1", 0, 0, OptionalLong.of(0));

		assertTrue(unknown.expected().isEmpty());
		assertEquals(0L, knownZero.expected().orElseThrow());
		assertNotEquals(unknown, knownZero);
		assertEquals(4L, new CaptureResult.Coverage("demand-1", 4, 3, OptionalLong.of(4)).expected().orElseThrow());
	}

	@Test
	void keyedFragmentBoundIsIndependentOfRecordValueBound() {
		Map<String, PresentationValue> overRecordBound = new LinkedHashMap<>();
		for (int i = 0; i <= PresentationLimits.MAX_ENTRIES; i++)
			overRecordBound.put("test:item" + i, new PresentationValue.NumberValue(i));
		var fragment = new CaptureResult.KeyedFragment(overRecordBound);
		assertEquals(PresentationLimits.MAX_ENTRIES + 1, fragment.entries().size(),
			"a fragment page is not capped by the record-value entry bound");

		Map<String, PresentationValue> overFragmentBound = new LinkedHashMap<>(overRecordBound);
		for (int i = overRecordBound.size(); i <= CaptureResult.KeyedFragment.MAX_ENTRIES; i++)
			overFragmentBound.put("test:item" + i, new PresentationValue.NumberValue(i));
		assertThrows(IllegalArgumentException.class, () -> new CaptureResult.KeyedFragment(overFragmentBound));
	}

	@Test
	void snapshotRecordReusesExistingPresentationBounds() {
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.SnapshotRecord(Map.of("not an id", new PresentationValue.Flag(true))));
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult.SnapshotRecord(Map.of("test:count",
				new PresentationValue.Text("x".repeat(PresentationLimits.MAX_TEXT_BYTES + 1)))));

		Map<String, PresentationValue> tooMany = new LinkedHashMap<>();
		for (int i = 0; i <= PresentationLimits.MAX_FIELDS; i++)
			tooMany.put("test:f" + i, new PresentationValue.Flag(true));
		assertThrows(IllegalArgumentException.class, () -> new CaptureResult.SnapshotRecord(tooMany));
	}

	@Test
	void evidenceTokensAreBoundedAndDetached() {
		var result = new CaptureResult(Optional.empty(), coverage(1, 4), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.CONTINUE, CaptureResult.Consistency.UNKNOWN,
			Optional.of("observation-1"), Optional.of(new CaptureResult.BoundedCursor("page-2")));

		assertEquals("observation-1", result.sourceVersion().orElseThrow());
		assertEquals(new CaptureResult.BoundedCursor("page-2"), result.nextCursor().orElseThrow());
		assertThrows(IllegalArgumentException.class,
			() -> new CaptureResult(Optional.empty(), coverage(0, 0), CaptureResult.Availability.READABLE,
				CaptureResult.Completeness.CONTINUE, CaptureResult.Consistency.UNKNOWN, Optional.of(" "), Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new CaptureResult.BoundedCursor(" "));
		assertThrows(IllegalArgumentException.class, () -> new CaptureResult.BoundedCursor(
			"c".repeat(CaptureResult.BoundedCursor.MAX_TOKEN_BYTES + 1)));
	}

	private static CaptureResult readable(CaptureResult.CapturePayload payload) {
		return new CaptureResult(Optional.of(payload), coverage(1, 1), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.VERIFIED, Optional.empty(), Optional.empty());
	}

	private static CaptureResult.Coverage coverage(long scanned, long expected) {
		var expectedCount = expected < 0L ? OptionalLong.empty() : OptionalLong.of(expected);
		return new CaptureResult.Coverage("demand-1", scanned, scanned, expectedCount);
	}
}
