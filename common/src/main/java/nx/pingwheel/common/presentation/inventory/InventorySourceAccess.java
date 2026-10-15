package nx.pingwheel.common.presentation.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;

/**
 * Production source seam. Admission precedes resolution and every controlled
 * read. A snapshot-capable source may additionally offer one lazy detached
 * capture: preparing it is a fixed-work, memory-bounded freeze, while
 * consuming a captured snapshot keeps the existing slot and work accounting.
 */
public final class InventorySourceAccess implements SourceAccess {
	public static final CostLedger.Counter PROBES = new CostLedger.Counter("inventory/probes", CostLedger.Unit.WORK);
	public static final CostLedger.Counter PHYSICAL = new CostLedger.Counter("inventory/physical", CostLedger.Unit.SLOT);
	public static final CostLedger.Counter PROVIDER_WORK = new CostLedger.Counter("inventory/provider_work", CostLedger.Unit.WORK);
	/** One resolution/validation/read may inspect a bounded Vault of 81 members several times. */
	public static final long PROVIDER_CALL_WORK = 1024, MAX_PROVIDER_WORK_PER_TICK = 131072;
	public static final int MAX_STEP = 64;
	public static final int MAX_SLOTS = 4096;

	/**
	 * Lazy one-shot snapshot preparation outcome. {@code READY} fixes one route
	 * for the handle lifetime, a detached snapshot or the existing live route.
	 * {@code DEFERRED} is retryable memory pressure: no capture ran and a later
	 * preparation may still succeed. The remaining states are terminal and are
	 * delivered once as a captured result instead of deferring forever.
	 */
	public enum Preparation { READY, DEFERRED, INVALID, UNAVAILABLE, INCOMPLETE }

	/**
	 * Atomicity evidence of one detached snapshot. Only a complete capture made
	 * in one contiguous server-thread operation may claim
	 * {@code ATOMIC_DETACHED}; a snapshot declaring {@code UNKNOWN} keeps its
	 * eventual quality across every later step.
	 */
	public enum SnapshotEvidence { UNKNOWN, ATOMIC_DETACHED }

	/**
	 * Optional one-shot detached capture offered by a snapshot-capable source.
	 * The retained-memory upper bound is reserved before {@link #capture()}
	 * runs; the contiguous freeze and copy are bounded by that reservation and
	 * by the structure guard, and they are never charged as slot-scan work.
	 */
	public interface SnapshotPlan {
		long memoryUpperBoundBytes();
		default nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost validationCost() {
			return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost(0, 0, 0);
		}
		/** Cumulative allocation is independent of the peak/retained memory reservation. */
		default long validationAllocationUpperBoundBytes() { return 0; }
		default long attemptedValidationAllocationBytes() { return 0; }
		default long attemptedValidationWorkUnits() { return 0; }
		/** Metadata only; C admits capture work separately from retained memory. */
		default int physicalReadUpperBound() { return 0; }
		default long itemWorkUpperBoundUnits() { return 0; }
		/** Includes empty and throwing getters; never refunded by capture rollback. */
		default int attemptedPhysicalReads() { return 0; }
		Optional<InventorySnapshot> capture();
		/** Caller-supplied finite subledger; native plans debit it before callbacks. */
		default Optional<InventorySnapshot> capture(nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance allowance) { return capture(); }
	}

	/**
	 * One complete detached inventory snapshot. Only the capture freezes world
	 * state; reading it is pure retained data, so decoding it across steps or
	 * periods keeps the capture's own evidence and never re-reads the source.
	 */
	public interface InventorySnapshot extends AutoCloseable {
		int slots();
		InventoryDomainCodec.Item read(int index);
		/** Caller holds NEW read scratch and prepaid work; compatibility callbacks remain trusted. */
		default InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission admission) { return read(index); }
		long retainedBytes();
		/** Effective display ceiling of this snapshot's lazy decode catalogue. */
		default int displayBytes() { return InventoryDomainCodec.MAX_DISPLAY_BYTES; }
		/** O(1) cached detached cost; the handle separately proves an inherited no-op when this is unknown. */
		default InventoryReprojectionSummary reprojectionSummary(int displayBytes) { return InventoryReprojectionSummary.UNKNOWN; }
		/** Lowers the lazy decode ceiling so a later decode never re-expands; an increase is ignored. */
		default void tightenDisplay(int displayBytes) {}
		/** Atomicity evidence of this completed capture; unknown stays uncertain. */
		default SnapshotEvidence evidence() { return SnapshotEvidence.UNKNOWN; }
		@Override default void close() {}
	}

	/**
	 * Handle that may prepare one lazy snapshot before consuming it. The
	 * prepared route is fixed: a retained snapshot, or the live fallback when
	 * the source offers no usable snapshot support.
	 */
	public interface InventoryHandle extends SourceAccess.Handle {
		/** One cumulative allowance for every item consumed by this step, never retained by the handle. */
		default StepOutcome step(CostLedger ledger, InventoryCatalogAdmission admission) { return step(ledger); }
		/** Admit before prepareSnapshot, including its initial/final source.valid calls. */
		default nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost discoveryValidationCost() {
			return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost(0, 0, 0);
		}
		/**
		 * Resolves the route once. A successful snapshot keeps its measured
		 * reservation charged until its captured data is released or the handle
		 * closes; a retryable memory defer leaves the route unresolved without capture.
		 */
		Preparation prepareSnapshot(RetainedMemoryLedger memory);
		default Preparation prepareSnapshot(RetainedMemoryLedger memory,
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance discovery) { return prepareSnapshot(memory); }
		/** Validates this observation's retained source evidence, even after its terminal step; never reads items. */
		boolean evidenceValid();
		/** Drops detached capture data when no scanner needs it; keeps source evidence until close. */
		void releaseCapturedData();
		/** Effective display ceiling of this handle's live or captured catalogue; the absolute bound when unknown. */
		default int displayBytes() { return InventoryDomainCodec.MAX_DISPLAY_BYTES; }
		/** Cached detached cost of the exact callbacks invoked by tightenDisplay, queried under the fixed metadata grant. */
		default InventoryReprojectionSummary reprojectionSummary(int displayBytes) { return InventoryReprojectionSummary.UNKNOWN; }
		/**
		 * Lowers the retained display ceiling of this handle's already captured or
		 * live source metadata, so later decode cannot re-expand; an increase is
		 * ignored. The detached cost summary covers all callbacks invoked here.
		 */
		default void tightenDisplay(int displayBytes) {}
	}

	public interface Source extends AutoCloseable {
		/** Pure metadata, available before any validation/discovery callback. */
		default nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost discoveryValidationCost() {
			return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost(0, 0, 0);
		}
		SourceKey key();
		boolean valid();
		default boolean valid(nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance allowance) { return valid(); }
		boolean stableCursor();
		int slots();
		InventoryDomainCodec.Item read(int slot);
		/** Additive admitted route; existing external callbacks keep their original contract. */
		default InventoryDomainCodec.Item read(int slot, InventoryCatalogAdmission admission) { return read(slot); }
		default boolean matchesSelection(int slot, InventorySelection selection, InventoryCatalogAdmission admission) {
			return matchesSelection(slot, selection);
		}
		default boolean matchesSelection(int slot, InventorySelection selection) {
			return InventorySourceAccess.matchesSelection(read(slot), selection);
		}
		default SelectionWitness observeSelection(int limit, InventorySelection selection) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			Page page = enumerate(limit);
			if (page.slots().size() > limit) throw new IllegalStateException("provider exceeded selection allowance");
			return new SelectionWitness(page.slots().stream().anyMatch(item -> InventorySourceAccess.matchesSelection(item, selection)), page.slots().size());
		}
		default SelectionWitness observeSelection(int limit, InventorySelection selection, InventoryCatalogAdmission admission) {
			return observeSelection(limit, selection);
		}
		default OptionalLong version() { return OptionalLong.empty(); }
		/** Empty keeps the source on its existing live route. */
		default Optional<SnapshotPlan> snapshotPlan() { return Optional.empty(); }
		default Optional<SnapshotPlan> snapshotPlan(nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance allowance) { return snapshotPlan(); }
		/** Effective display ceiling of this source's retained catalogue; the absolute bound when unknown. */
		default int displayBytes() { return InventoryDomainCodec.MAX_DISPLAY_BYTES; }
		/**
		 * Pure cached metadata; no world/provider/slot reads or JSON parse may run
		 * here. UNKNOWN does not label an overriding tightener free: the handle
		 * can only recover EMPTY by proving the unchanged inherited no-op.
		 */
		default InventoryReprojectionSummary reprojectionSummary(int displayBytes) { return InventoryReprojectionSummary.UNKNOWN; }
		/** Lowers the retained display ceiling of this source's catalogue; an increase is ignored. */
		default void tightenDisplay(int displayBytes) {}
		/** Cursorless sources must count every visited view, including empty views. */
		default Page enumerate(int limit) { throw new UnsupportedOperationException("no enumeration"); }
		default Page enumerate(int limit, InventoryCatalogAdmission admission) { return enumerate(limit); }
		@Override default void close() {}
	}
	public record SelectionWitness(boolean present, int visited) {
		public SelectionWitness { if (visited < 0) throw new IllegalArgumentException("negative selection visits"); }
	}
	/**
	 * Owned synchronous cursorless failure, including the failing visit. Only an
	 * observer that knows the physical prefix may supply this evidence; opaque
	 * provider failures keep the conservative whole-grant settlement. Stackless,
	 * with one scalar beyond Throwable's fields (88 modeled bytes), allocated at
	 * most once per failed operation under the caller's fixed read workspace.
	 */
	public static final class CursorlessReadFailure extends RuntimeException {
		private final int attemptedVisits;
		public CursorlessReadFailure(int attemptedVisits, Throwable failure) {
			super(null, Objects.requireNonNull(failure), false, false);
			if (attemptedVisits < 0 || attemptedVisits > MAX_SLOTS) throw new IllegalArgumentException("cursorless attempted visits");
			this.attemptedVisits = attemptedVisits;
		}
		public int attemptedVisits() { return attemptedVisits; }
	}
	public record Page(java.util.List<InventoryDomainCodec.Item> slots, boolean complete) {
		public Page { slots = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(slots)); }
	}
	private final Function<InventorySourceInput, Optional<Source>> resolver;
	private final InventorySourceInput input;
	private Source prepared;

	public InventorySourceAccess(InventorySourceInput input, Function<InventorySourceInput, Optional<Source>> resolver) {
		this.input = Objects.requireNonNull(input);
		this.resolver = Objects.requireNonNull(resolver);
	}
	/** Immediate authority check at a retained slot witness, or one bounded cursorless observation. */
	public boolean selectionPresent(InventorySelection selection, int witness, int observationBound, CostLedger ledger) {
		return selectionPresentOwned(selection, witness, observationBound, ledger, null);
	}
	/** The same caller-backed allowance follows the entire live witness. */
	public boolean selectionPresent(InventorySelection selection, int witness, int observationBound, CostLedger ledger,
		InventoryCatalogAdmission admission) {
		return selectionPresentOwned(selection, witness, observationBound, ledger, Objects.requireNonNull(admission));
	}
	private boolean selectionPresentOwned(InventorySelection selection, int witness, int observationBound, CostLedger ledger,
		InventoryCatalogAdmission catalog) {
		int limit = (int) Math.min(observationBound, Math.min(ledger.remaining(PHYSICAL), Math.max(0, ledger.remaining(PROVIDER_WORK) / PROVIDER_CALL_WORK - 1)));
		if (limit == 0 || witness < 0 || witness >= MAX_SLOTS) return false;
		var admission = ledger.tryReserve(Map.of(PHYSICAL, (long) limit, PROBES, 1L, PROVIDER_WORK, (limit + 1L) * PROVIDER_CALL_WORK));
		if (admission.isEmpty()) return false;
		int used = 0;
		try (var ticket = admission.get()) {
			try (var source = resolver.apply(input).orElse(null)) {
				if (source == null || !source.valid()) return false;
				if (source.stableCursor()) {
					if (witness >= source.slots()) return false;
					used = 1;
					return catalog == null ? source.matchesSelection(witness, selection) : source.matchesSelection(witness, selection, catalog);
				}
				used = limit;
				SelectionWitness observed = catalog == null ? source.observeSelection(limit, selection) : source.observeSelection(limit, selection, catalog);
				if (observed.visited() > limit) return false;
				used = observed.visited();
				return observed.present();
			} catch (CursorlessReadFailure failure) {
				used = Math.min(limit, failure.attemptedVisits());
				return false;
			} catch (RuntimeException | LinkageError unavailable) { return false; }
			finally { ticket.commit(Map.of(PHYSICAL, (long) used, PROBES, 1L, PROVIDER_WORK, (used + 1L) * PROVIDER_CALL_WORK)); }
		}
	}
	private static boolean matchesSelection(InventoryDomainCodec.Item item, InventorySelection selection) {
		return item != null && item.count() > 0 && (selection.aggregate() ? item.key().itemId().equals(selection.itemId())
			: !item.aggregateRequired() && item.key().equals(selection.exact()));
	}
	@Override public ResolveResult resolve(PresentationAdapter.DetachedTarget target, ReadScope scope, CostLedger ledger) {
		if (!scope.viewKey().equals(input.viewKey()) || !matches(target)) return ResolveResult.Unresolved.UNSUPPORTED;
		var ticket = ledger.tryReserve(Map.of(PROBES, 1L, PROVIDER_WORK, PROVIDER_CALL_WORK));
		if (ticket.isEmpty()) return ResolveResult.Unresolved.DEFERRED;
		try (var grant = ticket.get()) {
			grant.commit(Map.of(PROBES, 1L, PROVIDER_WORK, PROVIDER_CALL_WORK));
			if (prepared != null) { prepared.close(); prepared = null; }
			prepared = resolver.apply(input).orElse(null);
			if (prepared == null) return ResolveResult.Unresolved.UNAVAILABLE;
			if (!prepared.key().readScope().equals(scope.viewKey())) { prepared.close(); prepared = null; return ResolveResult.Unresolved.UNAVAILABLE; }
			return new ResolveResult.Available(new Descriptor(prepared.key(), 1,
				new Capabilities(!prepared.stableCursor(), prepared.stableCursor(), prepared.version().isPresent())));
		} catch (RuntimeException | LinkageError unavailable) {
			try { if (prepared != null) prepared.close(); } catch (RuntimeException | LinkageError cleanup) { unavailable.addSuppressed(cleanup); }
			finally { prepared = null; }
			return ResolveResult.Unresolved.UNAVAILABLE;
		}
	}
	@Override public OpenResult open(Descriptor descriptor, ReadScope scope, CostLedger ledger) {
		if (!scope.compatibleWith(descriptor) || prepared == null || !descriptor.key().equals(prepared.key()))
		{
			try { if (prepared != null) prepared.close(); } finally { prepared = null; }
			return OpenResult.Unstarted.UNAVAILABLE;
		}
		Source source = prepared;
		prepared = null;
		return new OpenResult.Started(new CaptureHandle(input, descriptor, scope, source));
	}
	private boolean matches(PresentationAdapter.DetachedTarget target) {
		return input.matchesDetached(target);
	}

	private static final class CaptureHandle implements InventoryHandle {
		private final InventorySourceInput input;
		private final Descriptor descriptor;
		private final ReadScope scope;
		private final Source source;
		private final InventoryDisplayProjection sourceProjection = new InventoryDisplayProjection();
		private final InventoryDisplayProjection snapshotProjection = new InventoryDisplayProjection();
		private int cursor, expected = -1;
		private boolean closed, terminal, versionStable = true;
		private OptionalLong firstVersion = OptionalLong.empty();
		private Preparation preparation;
		private boolean planDeferred;
		private InventorySnapshot snapshot;
		private RetainedMemoryLedger.Ticket snapshotMemory;
		private SnapshotEvidence snapshotEvidence = SnapshotEvidence.UNKNOWN;
		private OptionalLong snapshotVersion = OptionalLong.empty();
		private int snapshotSlots = -1;
		CaptureHandle(InventorySourceInput input, Descriptor descriptor, ReadScope scope, Source source) {
			this.input = input; this.descriptor = descriptor; this.scope = scope; this.source = source;
		}
		@Override public Descriptor descriptor() { return descriptor; }
		@Override public nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost discoveryValidationCost() { return source.discoveryValidationCost(); }
		@Override public java.util.Set<String> demand() { return scope.demand(); }
		@Override public boolean evidenceValid() { return !closed && source.valid(); }
		@Override public void releaseCapturedData() { releaseRetained(); }
		@Override public int displayBytes() {
			InventorySnapshot captured = snapshot;
			return captured != null ? captured.displayBytes() : source.displayBytes();
		}
		@Override public InventoryReprojectionSummary reprojectionSummary(int displayBytes) {
			if (closed) return InventoryReprojectionSummary.EMPTY;
			InventorySnapshot captured = snapshot;
			InventoryReprojectionSummary live = sourceProjection.summary(source, Source.class, source.reprojectionSummary(displayBytes));
			return captured == null ? live : snapshotProjection.summary(captured, InventorySnapshot.class, captured.reprojectionSummary(displayBytes)).plus(live);
		}
		@Override public void tightenDisplay(int displayBytes) {
			if (closed) return;
			InventorySnapshot captured = snapshot;
			if (captured != null) captured.tightenDisplay(displayBytes);
			// The live catalogue is kept consistent even when a snapshot route is
			// already fixed; tightening an unused catalogue is a bounded no-op.
			source.tightenDisplay(displayBytes);
		}

		/**
		 * One lazy, memory-bounded detached capture. The plan's upper bound is
		 * reserved before any capture, and the measured retained cost settles
		 * that reservation only when it is a valid subset of the bound.
		 */
		@Override public Preparation prepareSnapshot(RetainedMemoryLedger memory) {
			return prepareSnapshot(memory, new nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance(discoveryValidationCost()));
		}
		@Override public Preparation prepareSnapshot(RetainedMemoryLedger memory,
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance discovery) {
			Objects.requireNonNull(memory, "memory");
			Objects.requireNonNull(discovery, "discovery");
			if (closed) return Preparation.UNAVAILABLE;
			if (preparation != null) return preparation;
			InventorySnapshot captured = null;
			RetainedMemoryLedger.Ticket reserved = null;
			try {
				if (!source.valid(discovery)) return settle(Preparation.INVALID);
				SnapshotPlan plan = source.snapshotPlan(discovery).orElse(null);
				if (plan == null) return settleLiveRoute();
				long upperBound = plan.memoryUpperBoundBytes();
				if (upperBound < 0L) {
					InventoryCaptureDiagnostics.boundExceeded(input, "negative-memory-bound");
					return settle(Preparation.INCOMPLETE);
				}
				var admission = memory.tryReserve(upperBound);
				if (admission.isEmpty()) {
					planDeferred = true;
					InventoryCaptureDiagnostics.memoryDeferred(input);
					return Preparation.DEFERRED;
				}
				reserved = admission.get();
				captured = plan.capture().orElse(null);
				if (captured == null) return settleLiveRoute();
				if (!source.valid(discovery)) return settle(Preparation.INVALID);
				int slots = captured.slots();
				long retained = captured.retainedBytes();
				if (slots < 0 || slots > MAX_SLOTS || retained < 0L || retained > upperBound) {
					InventoryCaptureDiagnostics.boundExceeded(input, slots < 0 || slots > MAX_SLOTS ? "snapshot-slots" : "retained-bytes");
					return settle(Preparation.INCOMPLETE);
				}
				SnapshotEvidence evidence = captured.evidence();
				OptionalLong version = source.version();
				reserved.commit(retained);
				this.snapshot = captured;
				this.snapshotMemory = reserved;
				captured = null;
				reserved = null;
				this.snapshotSlots = slots;
				this.snapshotEvidence = evidence == null ? SnapshotEvidence.UNKNOWN : evidence;
				this.snapshotVersion = version;
				this.preparation = Preparation.READY;
				this.planDeferred = false;
				return Preparation.READY;
			} catch (nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.CaptureLimitException
				| nx.pingwheel.common.presentation.inventory.minecraft.InventoryObservationSnapshot.LimitException failure) {
				InventoryCaptureDiagnostics.boundExceeded(input, "capture-work-or-structure");
				return settle(Preparation.INCOMPLETE);
			} catch (RuntimeException | LinkageError failure) {
				InventoryCaptureDiagnostics.captureFailed(input, failure);
				return settle(Preparation.UNAVAILABLE);
			} finally {
				// Fatal Errors propagate, but never strand a captured object or its
				// provisional retained-memory reservation.
				releaseUntransferred(captured, reserved);
			}
		}

		private Preparation settleLiveRoute() {
			this.preparation = Preparation.READY;
			this.planDeferred = false;
			return Preparation.READY;
		}

		private Preparation settle(Preparation state) {
			this.snapshot = null;
			this.snapshotMemory = null;
			this.planDeferred = false;
			this.preparation = state;
			return state;
		}

		@Override public StepOutcome step(CostLedger ledger) {
			return stepOwned(ledger, null);
		}
		@Override public StepOutcome step(CostLedger ledger, InventoryCatalogAdmission admission) {
			return stepOwned(ledger, Objects.requireNonNull(admission));
		}
		private StepOutcome stepOwned(CostLedger ledger, InventoryCatalogAdmission catalog) {
			if (closed || terminal) return new StepOutcome.Deferred();
			if (preparation != null && preparation != Preparation.READY) {
				terminal = true;
				return terminalFailure(preparation);
			}
			if (snapshot != null) return snapshotStep(ledger, catalog);
			if (planDeferred) return new StepOutcome.Deferred();
			return liveStep(ledger, catalog);
		}

		/** Terminal preparation failures become one explicit captured result, never a forever defer. */
		private StepOutcome terminalFailure(Preparation failure) {
			CaptureResult.Availability availability = switch (failure) {
				case INVALID -> CaptureResult.Availability.INVALID;
				case INCOMPLETE -> CaptureResult.Availability.READABLE;
				default -> CaptureResult.Availability.UNAVAILABLE;
			};
			return new StepOutcome.Captured(new CaptureResult(Optional.empty(),
				new CaptureResult.Coverage(scope.viewKey(), cursor, cursor, OptionalLong.empty()),
				availability, CaptureResult.Completeness.INCOMPLETE, CaptureResult.Consistency.UNKNOWN,
				Optional.empty(), Optional.empty()));
		}

		/** Consumes retained snapshot data with the existing admission, revalidation and attempted-read charge. */
		private StepOutcome snapshotStep(CostLedger ledger, InventoryCatalogAdmission catalog) {
			try {
				int slots = snapshotSlots;
				int allowance = slots == 0 ? 0
					: (int) Math.min(MAX_STEP, Math.min(ledger.remaining(PHYSICAL), Math.max(0, ledger.remaining(PROVIDER_WORK) / PROVIDER_CALL_WORK - 1)));
				// A non-empty snapshot with no slot grant must not touch the live
				// source. Empty snapshots still need one fixed validation call.
				if (slots != 0 && allowance == 0) return new StepOutcome.Deferred();
				var admission = ledger.tryReserve(Map.of(PHYSICAL, (long) allowance, PROBES, 1L, PROVIDER_WORK, (allowance + 1L) * PROVIDER_CALL_WORK));
				if (admission.isEmpty()) return new StepOutcome.Deferred();
				int used = 0;
				try (var ticket = admission.get()) {
					try {
						if (!source.valid()) {
							terminal = true;
							releaseRetained();
							return snapshotCaptured(null, CaptureResult.Availability.INVALID, CaptureResult.Completeness.INCOMPLETE);
						}
						if (slots == 0) {
							terminal = true;
							return snapshotCaptured(null, CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE);
						}
						Map<String, CaptureResult.OpaqueValue> page = new LinkedHashMap<>();
						while (cursor < Math.min(slots, MAX_SLOTS) && used < allowance) {
							used++; // An attempted snapshot read still consumes its admission.
							var item = catalog == null ? snapshot.read(cursor) : snapshot.read(cursor, catalog);
							page.put(Integer.toString(cursor++), InventoryDomainCodec.encode(item));
						}
						boolean complete = cursor == slots;
						terminal = complete || cursor == MAX_SLOTS;
						return snapshotCaptured(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, page), CaptureResult.Availability.READABLE,
							complete ? CaptureResult.Completeness.COMPLETE : terminal ? CaptureResult.Completeness.INCOMPLETE : CaptureResult.Completeness.CONTINUE);
					} catch (InventoryItemBudget.CaptureLimitException exhausted) {
						InventoryCaptureDiagnostics.boundExceeded(input, "catalog-work-or-workspace");
						terminal = true;
						releaseRetained();
						return snapshotCaptured(null, CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE);
					} catch (RuntimeException | LinkageError unavailable) {
						InventoryCaptureDiagnostics.snapshotStepFailed(input, unavailable);
						terminal = true;
						releaseRetained();
						return snapshotCaptured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
					} finally { ticket.commit(Map.of(PHYSICAL, (long) used, PROBES, 1L, PROVIDER_WORK, (used + 1L) * PROVIDER_CALL_WORK)); }
				}
			} catch (RuntimeException | LinkageError unavailable) {
				InventoryCaptureDiagnostics.snapshotStepFailed(input, unavailable);
				terminal = true;
				releaseRetained();
				return snapshotCaptured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
			} catch (Error fatal) {
				terminal = true;
				try { releaseRetained(); }
				catch (Error cleanup) { if (cleanup != fatal) fatal.addSuppressed(cleanup); }
				throw fatal;
			}
		}

		private StepOutcome snapshotCaptured(CaptureResult.CapturePayload payload, CaptureResult.Availability available, CaptureResult.Completeness completeness) {
			return new StepOutcome.Captured(new CaptureResult(Optional.ofNullable(payload),
				new CaptureResult.Coverage(scope.viewKey(), cursor, cursor, snapshotSlots < 0 ? OptionalLong.empty() : OptionalLong.of(snapshotSlots)),
				available, completeness,
				snapshotEvidence == SnapshotEvidence.ATOMIC_DETACHED ? CaptureResult.Consistency.VERIFIED : CaptureResult.Consistency.EVENTUAL,
				snapshotVersion.isPresent() ? Optional.of(Long.toString(snapshotVersion.getAsLong())) : Optional.empty(),
				Optional.empty()));
		}

		private StepOutcome liveStep(CostLedger ledger, InventoryCatalogAdmission catalog) {
			int allowance = (int) Math.min(MAX_STEP, Math.min(ledger.remaining(PHYSICAL), Math.max(0, ledger.remaining(PROVIDER_WORK) / PROVIDER_CALL_WORK - 1)));
			if (allowance == 0) return new StepOutcome.Deferred();
			var admission = ledger.tryReserve(Map.of(PHYSICAL, (long) allowance, PROBES, 1L, PROVIDER_WORK, (allowance + 1L) * PROVIDER_CALL_WORK));
			if (admission.isEmpty()) return new StepOutcome.Deferred();
			int used = 0;
			try (var ticket = admission.get()) {
				try {
					if (!source.valid()) { terminal = true; return captured(null, CaptureResult.Availability.INVALID, CaptureResult.Completeness.INCOMPLETE); }
					if (expected < 0) { firstVersion = source.version(); expected = source.stableCursor() ? source.slots() : -1; }
					Map<String, CaptureResult.OpaqueValue> page = new LinkedHashMap<>();
					boolean complete;
					if (!source.stableCursor()) {
						used = allowance; // throwing enumeration may have traversed its whole grant
						Page enumerated = catalog == null ? source.enumerate(allowance) : source.enumerate(allowance, catalog);
						if (enumerated.slots().size() > allowance) throw new IllegalStateException("provider exceeded admitted enumeration");
						used = enumerated.slots().size();
						for (var item : enumerated.slots()) page.put(Integer.toString(cursor++), InventoryDomainCodec.encode(item));
						complete = enumerated.complete();
						expected = complete ? cursor : -1;
						terminal = true;
					} else {
						if (expected < 0 || source.slots() != expected) throw new IllegalStateException("slot view changed");
						while (cursor < Math.min(expected, MAX_SLOTS) && used < allowance) {
							used++; // An attempted throwing read still consumes its admission.
							var item = catalog == null ? source.read(cursor) : source.read(cursor, catalog);
							page.put(Integer.toString(cursor++), InventoryDomainCodec.encode(item));
						}
						complete = cursor == expected;
						terminal = complete || cursor == MAX_SLOTS;
					}
					versionStable &= firstVersion.isPresent() && source.version().equals(firstVersion);
					return captured(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, page), CaptureResult.Availability.READABLE,
						complete ? CaptureResult.Completeness.COMPLETE : terminal ? CaptureResult.Completeness.INCOMPLETE : CaptureResult.Completeness.CONTINUE);
				} catch (CursorlessReadFailure failure) {
					used = Math.min(allowance, failure.attemptedVisits());
					terminal = true;
					if (failure.getCause() instanceof InventoryItemBudget.CaptureLimitException) {
						InventoryCaptureDiagnostics.boundExceeded(input, "catalog-work-or-workspace");
						return captured(null, CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE);
					}
					return captured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
				} catch (InventoryItemBudget.CaptureLimitException exhausted) {
					InventoryCaptureDiagnostics.boundExceeded(input, "catalog-work-or-workspace");
					terminal = true;
					return captured(null, CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE);
				} catch (RuntimeException | LinkageError unavailable) {
					terminal = true;
					return captured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
				} finally { ticket.commit(Map.of(PHYSICAL, (long) used, PROBES, 1L, PROVIDER_WORK, (used + 1L) * PROVIDER_CALL_WORK)); }
			}
		}

		private StepOutcome captured(CaptureResult.CapturePayload payload, CaptureResult.Availability available, CaptureResult.Completeness completeness) {
			return new StepOutcome.Captured(new CaptureResult(Optional.ofNullable(payload),
				new CaptureResult.Coverage(scope.viewKey(), cursor, cursor, expected < 0 ? OptionalLong.empty() : OptionalLong.of(expected)),
				available, completeness, versionStable && firstVersion.isPresent() ? CaptureResult.Consistency.VERIFIED : CaptureResult.Consistency.EVENTUAL,
				firstVersion.isPresent() ? Optional.of(Long.toString(firstVersion.getAsLong())) : Optional.empty(), Optional.empty()));
		}

		private void releaseRetained() {
			InventorySnapshot held = snapshot;
			snapshot = null;
			RetainedMemoryLedger.Ticket heldTicket = snapshotMemory;
			snapshotMemory = null;
			Error fatal = null;
			try { closeRecoverable(held); } catch (Error failure) { fatal = failure; }
			try { closeRecoverable(heldTicket); }
			catch (Error failure) {
				if (fatal == null) fatal = failure;
				else if (failure != fatal) fatal.addSuppressed(failure);
			}
			if (fatal != null) throw fatal;
		}
		@Override public void close() {
			if (!closed) {
				closed = true;
				Error fatal = null;
				try { releaseRetained(); } catch (Error failure) { fatal = failure; }
				try { source.close(); }
				catch (RuntimeException | LinkageError unavailable) { /* local provider cleanup must not strand other consumers */ }
				catch (Error failure) {
					if (fatal == null) fatal = failure;
					else if (failure != fatal) fatal.addSuppressed(failure);
				}
				if (fatal != null) throw fatal;
			}
		}
	}

	private static void releaseUntransferred(InventorySnapshot snapshot, RetainedMemoryLedger.Ticket ticket) {
		Error fatal = null;
		try { closeRecoverable(snapshot); } catch (Error failure) { fatal = failure; }
		try { closeRecoverable(ticket); }
		catch (Error failure) {
			if (fatal == null) fatal = failure;
			else if (failure != fatal) fatal.addSuppressed(failure);
		}
		if (fatal != null) throw fatal;
	}

	private static void closeRecoverable(InventorySnapshot snapshot) throws Error {
		if (snapshot == null) return;
		try { snapshot.close(); } catch (RuntimeException | LinkageError ignored) { /* cleanup must not strand the retained ticket */ }
	}

	private static void closeRecoverable(RetainedMemoryLedger.Ticket ticket) throws Error {
		if (ticket == null) return;
		try { ticket.close(); } catch (RuntimeException | LinkageError ignored) { /* ledger cleanup is best-effort but never fatal */ }
	}
}
