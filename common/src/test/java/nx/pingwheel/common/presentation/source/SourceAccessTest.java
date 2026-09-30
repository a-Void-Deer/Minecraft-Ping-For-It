package nx.pingwheel.common.presentation.source;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.source.SourceAccess.Descriptor;
import nx.pingwheel.common.presentation.source.SourceAccess.OpenResult;
import nx.pingwheel.common.presentation.source.SourceAccess.ReadScope;
import nx.pingwheel.common.presentation.source.SourceAccess.ResolveResult;
import nx.pingwheel.common.presentation.source.SourceAccess.StepOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceAccessTest {

	private static final CostLedger.Counter WORK = new CostLedger.Counter("source-a", CostLedger.Unit.WORK);

	@Test
	void resolveReturnsAvailableDescriptorAndNeverThrowsForOrdinaryOutcomes() {
		var source = new FakeSource(descriptor(), 1);
		var scope = new ReadScope("visible", Set.of("test:count"));

		var available = source.resolve(target(), scope, new CostLedger(Map.of(WORK, 4L)));

		assertTrue(available instanceof SourceAccess.ResolveResult.Available);
		var resolved = ((SourceAccess.ResolveResult.Available) available).descriptor();
		assertEquals(descriptor(), resolved);
		assertFalse(resolved.capabilities().oneShot());
		assertTrue(resolved.capabilities().stableCursor());

		var incompatible = source.resolve(target(), new ReadScope("hidden", Set.of("test:count")),
			new CostLedger(Map.of(WORK, 4L)));
		assertEquals(SourceAccess.ResolveResult.Unresolved.UNAVAILABLE, incompatible);

		var unsupported = source.resolve(
			new PresentationAdapter.DetachedTarget("minecraft:overworld", "item", "minecraft:chest", 0, 64, 0, null),
			scope, new CostLedger(Map.of(WORK, 4L)));
		assertEquals(SourceAccess.ResolveResult.Unresolved.UNSUPPORTED, unsupported);
	}

	@Test
	void budgetDenialDefersOpenWithoutAnyRead() {
		var source = new FakeSource(descriptor(), 1);
		var ledger = new CostLedger(Map.of(WORK, 0L));
		var scope = new ReadScope("visible", Set.of("test:count"));

		var denied = source.open(descriptor(), scope, ledger);

		assertEquals(SourceAccess.OpenResult.Unstarted.DEFERRED, denied);
		assertFalse(denied instanceof SourceAccess.OpenResult.Started);
		assertFalse(source.log.contains("read"), "a denied admission must not read the source");
		assertEquals(0L, ledger.used(WORK));
	}

	@Test
	void stepCapturesCompletedNonAtomicResultThenDefersWithoutMoreReads() {
		var source = new FakeSource(descriptor(), 1);
		var ledger = new CostLedger(Map.of(WORK, 1L));
		var scope = new ReadScope("visible", Set.of("test:count"));
		var handle = ((SourceAccess.OpenResult.Started) source.open(descriptor(), scope, ledger)).handle();

		assertEquals(descriptor(), handle.descriptor());
		assertEquals(Set.of("test:count"), handle.demand());

		var captured = handle.step(ledger);

		assertTrue(captured instanceof SourceAccess.StepOutcome.Captured);
		var result = ((SourceAccess.StepOutcome.Captured) captured).result();
		assertEquals(CaptureResult.Availability.READABLE, result.availability());
		assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
		assertEquals(CaptureResult.Consistency.EVENTUAL, result.consistency(), "a non-atomic completed sweep is valid");
		assertTrue(result.payload().isPresent());
		assertTrue(result.nextCursor().isEmpty());

		var deferred = handle.step(ledger);

		assertTrue(deferred instanceof SourceAccess.StepOutcome.Deferred);
		assertFalse(deferred instanceof SourceAccess.StepOutcome.Captured);
		assertEquals(1L, ledger.used(WORK));
		assertEquals(1, source.log.stream().filter("read"::equals).count());

		handle.close();
		handle.close();
		assertEquals(1, source.log.stream().filter("close"::equals).count(), "close must be idempotent");
	}

	@Test
	void readScopeCopiesAndFreezesAuthorizedDemand() {
		var demand = new LinkedHashSet<String>();
		demand.add("test:count");
		var scope = new ReadScope("visible", demand);

		demand.add("test:late");

		assertEquals(Set.of("test:count"), scope.demand());
		assertNotSame(demand, scope.demand());
		assertThrows(UnsupportedOperationException.class, () -> scope.demand().add("test:extra"));
		assertThrows(IllegalArgumentException.class, () -> new ReadScope(" ", Set.of("test:count")));
		assertThrows(IllegalArgumentException.class, () -> new ReadScope("visible", Set.of(" ")));
		assertThrows(NullPointerException.class, () -> new ReadScope("visible", null));
		assertTrue(scope.compatibleWith(descriptor()));
		assertFalse(new ReadScope("hidden", Set.of("test:count")).compatibleWith(descriptor()));
	}

	@Test
	void capabilitiesAreIndependent() {
		var oneShotAtomic = new SourceKey("test", "container", "overworld:0:64:0/minecraft:chest", "visible");
		var atomicSource = new FakeSource(new SourceAccess.Descriptor(oneShotAtomic, 1,
			new SourceAccess.Capabilities(true, false, true)), 1);
		var cursorSource = new FakeSource(new SourceAccess.Descriptor(oneShotAtomic, 1,
			new SourceAccess.Capabilities(false, true, false)), 1);
		var scope = new ReadScope("visible", Set.of("test:count"));
		var ledger = new CostLedger(Map.of(WORK, 4L));

		var atomic = ((SourceAccess.ResolveResult.Available) atomicSource.resolve(target(), scope, ledger))
			.descriptor().capabilities();
		var cursor = ((SourceAccess.ResolveResult.Available) cursorSource.resolve(target(), scope, ledger))
			.descriptor().capabilities();

		assertTrue(atomic.oneShot());
		assertFalse(atomic.stableCursor(), "a one-shot read is not automatically resumable");
		assertTrue(atomic.stableVersion(), "a one-shot read may still promise a stable version");
		assertFalse(cursor.oneShot());
		assertTrue(cursor.stableCursor());
		assertFalse(cursor.stableVersion(), "a stable cursor does not imply a stable version");
	}

	private static SourceAccess.Descriptor descriptor() {
		return new SourceAccess.Descriptor(
			new SourceKey("test", "container", "overworld:0:64:0/minecraft:chest", "visible"), 1,
			new SourceAccess.Capabilities(false, true, false));
	}

	private static PresentationAdapter.DetachedTarget target() {
		return new PresentationAdapter.DetachedTarget("minecraft:overworld", "container", "minecraft:chest", 0, 64, 0, null);
	}

	private static CaptureResult completedEventualResult() {
		return new CaptureResult(
			Optional.of(new CaptureResult.KeyedFragment(Map.of("test:item/0", new PresentationValue.NumberValue(0.0)))),
			new CaptureResult.Coverage("demand-1", 1, 1, OptionalLong.of(1)),
			CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE,
			CaptureResult.Consistency.EVENTUAL,
			Optional.empty(),
			Optional.empty());
	}

	private static final class FakeSource implements SourceAccess {

		final List<String> log = new ArrayList<>();
		private final Descriptor descriptor;
		private final CaptureResult result;
		private int readsRemaining;

		FakeSource(Descriptor descriptor, int readsRemaining) {
			this.descriptor = descriptor;
			this.readsRemaining = readsRemaining;
			this.result = completedEventualResult();
		}

		@Override
		public ResolveResult resolve(PresentationAdapter.DetachedTarget dispatchTarget, ReadScope readScope, CostLedger ledger) {
			log.add("resolve");
			if (!"container".equals(dispatchTarget.kind())) return ResolveResult.Unresolved.UNSUPPORTED;
			if (!readScope.compatibleWith(descriptor)) return ResolveResult.Unresolved.UNAVAILABLE;
			return new ResolveResult.Available(descriptor);
		}

		@Override
		public OpenResult open(Descriptor descriptor, ReadScope readScope, CostLedger ledger) {
			if (!readScope.compatibleWith(descriptor)) return OpenResult.Unstarted.UNAVAILABLE;
			if (ledger.remaining(WORK) < 1L) {
				log.add("open-deferred");
				return OpenResult.Unstarted.DEFERRED;
			}
			log.add("open");
			return new OpenResult.Started(new FakeHandle(descriptor, Set.copyOf(readScope.demand()), this));
		}
	}

	private static final class FakeHandle implements SourceAccess.Handle {

		private final Descriptor descriptor;
		private final Set<String> demand;
		private final FakeSource source;
		private boolean closed;

		FakeHandle(Descriptor descriptor, Set<String> demand, FakeSource source) {
			this.descriptor = descriptor;
			this.demand = demand;
			this.source = source;
		}

		@Override
		public Descriptor descriptor() {
			return descriptor;
		}

		@Override
		public Set<String> demand() {
			return demand;
		}

		@Override
		public StepOutcome step(CostLedger ledger) {
			if (source.readsRemaining <= 0) return new StepOutcome.Deferred();
			var ticket = ledger.tryReserve(Map.of(WORK, 1L));
			if (ticket.isEmpty()) return new StepOutcome.Deferred();
			ticket.get().commit(Map.of(WORK, 1L));
			source.readsRemaining--;
			source.log.add("read");
			return new StepOutcome.Captured(source.result);
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			source.log.add("close");
		}
	}
}
