package nx.pingwheel.common.presentation.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.Nullable;
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
	/** Pure route metadata; native indexed reads must never discover an eager snapshot plan. */
	public enum ReadMode { ADAPTER, NATIVE_INCREMENTAL }

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
		/** Optional borrowed certification port; does not advance this handle's legacy cursor. */
		default InventorySharedRead.@Nullable Port sharedRead() { return null; }
		/** Cached source-owned metadata, excluding catalogues and detached captures; no callbacks or lazy allocation. */
		default long sourceRetainedBytes() { return 0; }
		default ReadMode readMode() { return ReadMode.ADAPTER; }
		default InventoryItemCodec.@Nullable Catalog nativeCatalog() { return null; }
		default long nativeCatalogGrowthUpperBound() { return 0; }
		default InventoryValidationBudget.Cost evidenceValidationCost() { return InventoryValidationBudget.NONE; }
		default InventoryValidationBudget.Cost stepValidationCost(int slots) { return InventoryValidationBudget.NONE; }
		default boolean evidenceValid(InventoryValidationBudget.Allowance allowance) { return evidenceValid(); }
		default StepOutcome step(CostLedger ledger, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return admission == null ? step(ledger) : step(ledger, admission); }
		default InventoryStep.Outcome stepInventory(CostLedger ledger, InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
			return InventoryStep.fromLegacy(step(ledger, admission, allowance));
		}
		/** Typed native observations; adapters retain their existing encoded conversion and evidence. */
		default InventoryStep.Outcome stepInventory(CostLedger ledger, InventoryCatalogAdmission admission) {
			return InventoryStep.fromLegacy(step(ledger, admission));
		}
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
		/** Optional inventory-local sharing capability. Unknown sources retain exact isolation. */
		default InventorySharedRead.@Nullable Port sharedRead() { return null; }
		/** Pure cached ownership cost, bounded by the admitted resolution workspace and held until close. */
		default long sourceRetainedBytes() { return 0; }
		default ReadMode readMode() { return ReadMode.ADAPTER; }
		/** Borrowed metadata only: never creates a catalogue or performs a provider callback. */
		default InventoryItemCodec.@Nullable Catalog nativeCatalog() { return null; }
		default long nativeCatalogGrowthUpperBound() { return 0; }
		default InventoryValidationBudget.Cost evidenceValidationCost() { return InventoryValidationBudget.NONE; }
		/** Includes nested validity, member, mapping and provider metadata callbacks for the whole operation. */
		default InventoryValidationBudget.Cost readValidationCost(int slots) { return InventoryValidationBudget.NONE; }
		default InventoryDomainCodec.@Nullable Item read(int slot, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return admission == null ? read(slot) : read(slot, admission); }
		default Page enumerate(int limit, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return admission == null ? enumerate(limit) : enumerate(limit, admission); }
		default boolean matchesSelection(int slot, InventorySelection selection, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
			return admission == null ? matchesSelection(slot, selection) : matchesSelection(slot, selection, admission);
		}
		default SelectionWitness observeSelection(int limit, InventorySelection selection, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
			return admission == null ? observeSelection(limit, selection) : observeSelection(limit, selection, admission);
		}
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
	public interface Resolver {
		Optional<Source> resolve(InventorySourceInput input);
		InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input);
		default Optional<Source> resolve(InventorySourceInput input, InventoryValidationBudget.Allowance allowance) { return resolve(input); }
	}
	private final Resolver resolver;
	private final InventorySourceInput input;
	private Source prepared;

	public InventorySourceAccess(InventorySourceInput input, Function<InventorySourceInput, Optional<Source>> resolver) {
		this(input, new Resolver() {
			@Override public Optional<Source> resolve(InventorySourceInput value) { return resolver.apply(value); }
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput value) { return InventoryValidationBudget.NONE; }
		});
	}
	public InventorySourceAccess(InventorySourceInput input, Resolver resolver) {
		this.input = Objects.requireNonNull(input);
		this.resolver = Objects.requireNonNull(resolver);
	}
	/** Immediate authority check at a retained slot witness, or one bounded cursorless observation. */
	public boolean selectionPresent(InventorySelection selection, int witness, int observationBound, CostLedger ledger) {
		return selectionPresentOwned(selection, witness, observationBound, ledger, null, null);
	}
	/** The same caller-backed allowance follows the entire live witness. */
	public boolean selectionPresent(InventorySelection selection, int witness, int observationBound, CostLedger ledger,
		InventoryCatalogAdmission admission) {
		return selectionPresentOwned(selection, witness, observationBound, ledger, Objects.requireNonNull(admission), null);
	}
	public boolean selectionPresent(InventorySelection selection, int witness, int observationBound, CostLedger ledger,
		InventoryCatalogAdmission catalog, InventoryValidationBudget.Allowance allowance) {
		return selectionPresentOwned(selection, witness, observationBound, ledger, catalog, Objects.requireNonNull(allowance));
	}
	private boolean selectionPresentOwned(InventorySelection selection, int witness, int observationBound, CostLedger ledger,
		@Nullable InventoryCatalogAdmission catalog, InventoryValidationBudget.@Nullable Allowance validation) {
		int limit = (int) Math.min(observationBound, Math.min(ledger.remaining(PHYSICAL), Math.max(0, ledger.remaining(PROVIDER_WORK) / PROVIDER_CALL_WORK - 1)));
		if (limit == 0 || witness < 0 || witness >= MAX_SLOTS) return false;
		var admission = ledger.tryReserve(Map.of(PHYSICAL, (long) limit, PROBES, 1L, PROVIDER_WORK, (limit + 1L) * PROVIDER_CALL_WORK));
		if (admission.isEmpty()) return false;
		int used = 0;
		try (var ticket = admission.get()) {
			String sourceClass = "unresolved";
			try {
				@Nullable Source source = (validation == null ? resolver.resolve(input) : resolver.resolve(input, validation)).orElse(null);
				Throwable primary = null;
				try {
					if (source != null) sourceClass = source.getClass().getName();
					if (source == null || !(validation == null ? source.valid() : source.valid(validation))) return false;
					if (source.stableCursor()) {
						if (witness >= source.slots()) return false;
						used = 1;
						return validation != null ? source.matchesSelection(witness, selection, catalog, validation)
							: catalog == null ? source.matchesSelection(witness, selection) : source.matchesSelection(witness, selection, catalog);
					}
					used = limit;
					SelectionWitness observed = validation != null ? source.observeSelection(limit, selection, catalog, validation)
						: catalog == null ? source.observeSelection(limit, selection) : source.observeSelection(limit, selection, catalog);
					if (observed.visited() > limit) return false;
					used = observed.visited();
					return observed.present();
				} catch (RuntimeException | Error failure) {
					primary = failure;
					if (failure instanceof CursorlessReadFailure cursorless) used = Math.min(limit, cursorless.attemptedVisits());
					throw failure;
				} finally {
					closeAttemptSource("selection-witness-close", source, primary, () -> "witness=" + witness + " limit=" + limit);
				}
			} catch (CursorlessReadFailure failure) {
				used = Math.min(limit, failure.attemptedVisits());
				String failedClass = sourceClass;
				InventoryCaptureDiagnostics.warn("selection-witness", input, null, () -> "sourceClass=" + failedClass + " witness=" + witness + " limit=" + limit, failure);
				return false;
			} catch (RuntimeException | LinkageError unavailable) {
				String failedClass = sourceClass;
				InventoryCaptureDiagnostics.warn("selection-witness", input, null, () -> "sourceClass=" + failedClass + " witness=" + witness + " limit=" + limit, unavailable);
				return false;
			}
			finally { ticket.commit(Map.of(PHYSICAL, (long) used, PROBES, 1L, PROVIDER_WORK, (used + 1L) * PROVIDER_CALL_WORK)); }
		}
	}
	private static boolean matchesSelection(InventoryDomainCodec.Item item, InventorySelection selection) {
		return item != null && item.count() > 0 && (selection.aggregate() ? item.key().itemId().equals(selection.itemId())
			: !item.aggregateRequired() && item.key().equals(selection.exact()));
	}
	/** Close once while both failures are available, before expected outcomes are filtered. */
	private void closeAttemptSource(String operation, Source source, Throwable primary, Supplier<String> detail) {
		if (source == null) return;
		try { source.close(); }
		catch (RuntimeException | LinkageError cleanup) {
			if (primary == null) throw cleanup;
			// The owned cursorless wrapper disables suppression. Keep its original
			// cause as the report carrier without replacing the business exception.
			Throwable reason = primary instanceof CursorlessReadFailure ? primary.getCause() : primary;
			if (cleanup != primary && cleanup != reason) reason.addSuppressed(cleanup);
			if (InventoryCaptureDiagnostics.expectedFailure(primary))
				InventoryCaptureDiagnostics.warn(operation, input, null,
					() -> "sourceClass=" + source.getClass().getName() + " primaryClass=" + primary.getClass().getName()
						+ " primaryReasonClass=" + reason.getClass().getName() + " primaryMessage=" + reason.getMessage() + " " + detail.get(), cleanup);
		} catch (Error cleanup) {
			if (primary instanceof Error && !(primary instanceof LinkageError)) {
				if (cleanup != primary) primary.addSuppressed(cleanup);
			} else {
				// A fatal cleanup must never become suppressed under a recoverable
				// failure which the caller will convert to false/unavailable.
				if (primary != null && cleanup != primary) cleanup.addSuppressed(primary);
				throw cleanup;
			}
		}
	}
	@Override public ResolveResult resolve(PresentationAdapter.DetachedTarget target, ReadScope scope, CostLedger ledger) {
		return resolve(target, scope, ledger, null);
	}
	public ResolveResult resolve(PresentationAdapter.DetachedTarget target, ReadScope scope, CostLedger ledger, InventoryValidationBudget.@Nullable Allowance validation) {
		if (!scope.viewKey().equals(input.viewKey()) || !matches(target)) return ResolveResult.Unresolved.UNSUPPORTED;
		var ticket = ledger.tryReserve(Map.of(PROBES, 1L, PROVIDER_WORK, PROVIDER_CALL_WORK));
		if (ticket.isEmpty()) return ResolveResult.Unresolved.DEFERRED;
		String sourceClass = "unresolved";
		try (var grant = ticket.get()) {
			grant.commit(Map.of(PROBES, 1L, PROVIDER_WORK, PROVIDER_CALL_WORK));
			if (prepared != null) {
				Source previous = prepared; prepared = null;
				sourceClass = previous.getClass().getName(); previous.close();
			}
			sourceClass = "unresolved";
			prepared = (validation == null ? resolver.resolve(input) : resolver.resolve(input, validation)).orElse(null);
			if (prepared == null) return ResolveResult.Unresolved.UNAVAILABLE;
			sourceClass = prepared.getClass().getName();
			if (!prepared.key().readScope().equals(scope.viewKey())) {
				Source denied = prepared; prepared = null; denied.close();
				return ResolveResult.Unresolved.UNAVAILABLE;
			}
			return new ResolveResult.Available(new Descriptor(prepared.key(), 1,
				new Capabilities(!prepared.stableCursor(), prepared.stableCursor(), prepared.version().isPresent())));
		} catch (InventoryItemBudget.CaptureLimitException deferred) {
			@Nullable Source failed = prepared; prepared = null;
			closeAttemptSource("resolve-close", failed, deferred, () -> "stage=resolve-admission");
			return ResolveResult.Unresolved.DEFERRED;
		} catch (RuntimeException | LinkageError unavailable) {
			Source failed = prepared; prepared = null;
			closeAttemptSource("resolve-close", failed, unavailable, () -> "stage=resolve");
			String failedClass = sourceClass;
			InventoryCaptureDiagnostics.warn("resolve", input, null, () -> "sourceClass=" + failedClass, unavailable);
			return ResolveResult.Unresolved.UNAVAILABLE;
		} catch (Error fatal) {
			Source failed = prepared; prepared = null;
			closeAttemptSource("resolve-close", failed, fatal, () -> "stage=resolve");
			throw fatal;
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
		@Override public InventorySharedRead.@Nullable Port sharedRead() { return closed ? null : source.sharedRead(); }
		@Override public long sourceRetainedBytes() { return source.sourceRetainedBytes(); }
		@Override public ReadMode readMode() { return source.readMode(); }
		@Override public InventoryItemCodec.@Nullable Catalog nativeCatalog() { return source.nativeCatalog(); }
		@Override public long nativeCatalogGrowthUpperBound() { return source.nativeCatalogGrowthUpperBound(); }
		@Override public InventoryValidationBudget.Cost discoveryValidationCost() {
			return readMode() == ReadMode.NATIVE_INCREMENTAL ? source.evidenceValidationCost() : source.discoveryValidationCost();
		}
		@Override public java.util.Set<String> demand() { return scope.demand(); }
		@Override public boolean evidenceValid() { return !closed && source.valid(); }
		@Override public boolean evidenceValid(InventoryValidationBudget.Allowance allowance) { return !closed && source.valid(allowance); }
		@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return source.evidenceValidationCost(); }
		@Override public InventoryValidationBudget.Cost stepValidationCost(int slots) {
			return snapshot != null ? source.evidenceValidationCost() : source.evidenceValidationCost().times(readMode() == ReadMode.NATIVE_INCREMENTAL ? 2 : 1).plus(source.readValidationCost(slots));
		}
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
			return prepareSnapshot(memory, new InventoryValidationBudget.Allowance(discoveryValidationCost()), true);
		}
		@Override public Preparation prepareSnapshot(RetainedMemoryLedger memory,
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance discovery) {
			return prepareSnapshot(memory, discovery, false);
		}
		private Preparation prepareSnapshot(RetainedMemoryLedger memory, InventoryValidationBudget.Allowance discovery, boolean legacy) {
			Objects.requireNonNull(memory, "memory");
			Objects.requireNonNull(discovery, "discovery");
			if (closed) return Preparation.UNAVAILABLE;
			if (preparation != null) return preparation;
			InventorySnapshot captured = null;
			RetainedMemoryLedger.Ticket reserved = null;
			Throwable preparationFailure = null;
			try {
				if (!source.valid(discovery)) return settle(Preparation.INVALID);
				if (readMode() == ReadMode.NATIVE_INCREMENTAL) return settleLiveRoute();
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
				captured = (legacy ? plan.capture() : plan.capture(discovery)).orElse(null);
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
				preparationFailure = failure;
				InventoryCaptureDiagnostics.boundExceeded(input, "capture-work-or-structure", failure);
				return settle(Preparation.INCOMPLETE);
			} catch (RuntimeException | LinkageError failure) {
				preparationFailure = failure;
				return settle(Preparation.UNAVAILABLE);
			} finally {
				// Fatal Errors propagate, but never strand a captured object or its
				// provisional retained-memory reservation.
				releaseUntransferred(input, descriptor.key(), captured, reserved, preparationFailure);
				if (preparationFailure != null)
					InventoryCaptureDiagnostics.warn("capture-failed", input, descriptor.key(), () -> "stage=snapshot-prepare sourceClass=" + source.getClass().getName(), preparationFailure);
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
			return stepOwned(ledger, null, null);
		}
		@Override public StepOutcome step(CostLedger ledger, InventoryCatalogAdmission admission) {
			return stepOwned(ledger, Objects.requireNonNull(admission), null);
		}
		@Override public StepOutcome step(CostLedger ledger, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance validation) {
			return stepOwned(ledger, admission, Objects.requireNonNull(validation));
		}
		@Override public InventoryStep.Outcome stepInventory(CostLedger ledger, InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance validation) {
			if (readMode() != ReadMode.NATIVE_INCREMENTAL) return InventoryStep.fromLegacy(stepOwned(ledger, admission, validation));
			return nativeStep(ledger, admission, validation);
		}
		@Override public InventoryStep.Outcome stepInventory(CostLedger ledger, InventoryCatalogAdmission admission) {
			return stepInventory(ledger, admission, new InventoryValidationBudget.Allowance(stepValidationCost(MAX_STEP)));
		}
		private StepOutcome stepOwned(CostLedger ledger, @Nullable InventoryCatalogAdmission catalog, InventoryValidationBudget.@Nullable Allowance validation) {
			if (readMode() == ReadMode.NATIVE_INCREMENTAL) {
				// Generic callers request encoding explicitly; the typed route advances the cursor only once.
				var outcome = nativeStep(ledger, catalog, validation);
				return outcome instanceof InventoryStep.Outcome.Captured captured ? new StepOutcome.Captured(captured.step().toLegacy()) : new StepOutcome.Deferred();
			}
			if (closed || terminal) return new StepOutcome.Deferred();
			if (preparation != null && preparation != Preparation.READY) {
				terminal = true;
				return terminalFailure(preparation);
			}
			if (snapshot != null) return snapshotStep(ledger, catalog, validation);
			if (planDeferred) return new StepOutcome.Deferred();
			return liveStep(ledger, catalog, validation);
		}

		private InventoryStep.Outcome nativeStep(CostLedger ledger, @Nullable InventoryCatalogAdmission catalog, InventoryValidationBudget.@Nullable Allowance validation) {
			if (closed || terminal || planDeferred) return new InventoryStep.Outcome.Deferred();
			if (preparation != null && preparation != Preparation.READY) {
				terminal = true; return InventoryStep.fromLegacy(terminalFailure(preparation));
			}
			// Native source slots() is frozen resolution metadata. Generic extension routes keep their legacy semantics.
			int slots = source.slots();
			int bound = slots == 0 ? 0 : (int) Math.min(MAX_STEP, Math.min(ledger.remaining(PHYSICAL), Math.max(0, ledger.remaining(PROVIDER_WORK) / PROVIDER_CALL_WORK - 1)));
			if (slots != 0 && bound == 0) return new InventoryStep.Outcome.Deferred();
			var admission = ledger.tryReserve(Map.of(PHYSICAL, (long) bound, PROBES, 1L, PROVIDER_WORK, (bound + 1L) * PROVIDER_CALL_WORK));
			if (admission.isEmpty()) return new InventoryStep.Outcome.Deferred();
			int used = 0, first = cursor;
			var items = new java.util.ArrayList<InventoryDomainCodec.@Nullable Item>(bound);
			try (var ticket = admission.get()) {
				try {
					if (!(validation == null ? source.valid() : source.valid(validation))) {
						terminal = true; return nativeCaptured(cursor, java.util.List.of(), CaptureResult.Availability.INVALID, CaptureResult.Completeness.INCOMPLETE);
					}
					if (expected < 0) expected = slots;
					if (slots < 0 || slots != expected) {
						terminal = true; return nativeCaptured(cursor, java.util.List.of(), CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
					}
					while (cursor < Math.min(expected, MAX_SLOTS) && used < bound) {
						used++; // Getter exceptions are attempts, but never commit a journal slot.
						items.add(validation == null ? catalog == null ? source.read(cursor) : source.read(cursor, catalog) : source.read(cursor, catalog, validation));
						cursor++;
					}
					terminal = cursor == expected || cursor == MAX_SLOTS;
					return nativeCaptured(first, items, CaptureResult.Availability.READABLE,
						cursor == expected ? CaptureResult.Completeness.COMPLETE : terminal ? CaptureResult.Completeness.INCOMPLETE : CaptureResult.Completeness.CONTINUE);
				} catch (InventoryItemCodec.CapacityExceededException | InventoryItemBudget.CaptureLimitException exhausted) {
					terminal = true;
					InventoryCaptureDiagnostics.boundExceeded(input, "native-catalog-capacity-or-work", exhausted);
					// Capacity/work refusal is not a certificate that a provider's reentrant callback kept authority.
					if (!(validation == null ? source.valid() : source.valid(validation)))
						return nativeCaptured(cursor, java.util.List.of(), CaptureResult.Availability.INVALID, CaptureResult.Completeness.INCOMPLETE);
					return nativeCaptured(first, items, CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE);
				} catch (RuntimeException | LinkageError unavailable) {
					terminal = true;
					InventoryCaptureDiagnostics.warn("native-step", input, descriptor.key(), () -> "cursor=" + cursor + " expected=" + expected, unavailable);
					return nativeCaptured(cursor, java.util.List.of(), CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
				} finally { ticket.commit(Map.of(PHYSICAL, (long) used, PROBES, 1L, PROVIDER_WORK, (used + 1L) * PROVIDER_CALL_WORK)); }
			}
		}
		private InventoryStep.Outcome nativeCaptured(int first, java.util.List<InventoryDomainCodec.@Nullable Item> items,
			CaptureResult.Availability availability, CaptureResult.Completeness completeness) {
			var metadata = new CaptureResult(Optional.empty(), new CaptureResult.Coverage(scope.viewKey(), cursor, cursor,
				expected < 0 ? OptionalLong.empty() : OptionalLong.of(expected)), availability, completeness, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
			return new InventoryStep.Outcome.Captured(new InventoryStep(metadata, first, items, InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP));
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
		private StepOutcome snapshotStep(CostLedger ledger, @Nullable InventoryCatalogAdmission catalog, InventoryValidationBudget.@Nullable Allowance validation) {
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
						if (!(validation == null ? source.valid() : source.valid(validation))) {
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
						InventoryCaptureDiagnostics.boundExceeded(input, "catalog-work-or-workspace", exhausted);
						terminal = true;
						releaseRetained(exhausted);
						return snapshotCaptured(null, CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE);
					} catch (RuntimeException | LinkageError unavailable) {
						terminal = true;
						releaseRetained(unavailable);
						InventoryCaptureDiagnostics.warn("snapshot-step-failed", input, descriptor.key(), () -> "cursor=" + cursor + " slots=" + snapshotSlots, unavailable);
						return snapshotCaptured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
					} finally { ticket.commit(Map.of(PHYSICAL, (long) used, PROBES, 1L, PROVIDER_WORK, (used + 1L) * PROVIDER_CALL_WORK)); }
				}
			} catch (RuntimeException | LinkageError unavailable) {
				terminal = true;
				releaseRetained(unavailable);
				InventoryCaptureDiagnostics.warn("snapshot-step-failed", input, descriptor.key(), () -> "cursor=" + cursor + " slots=" + snapshotSlots, unavailable);
				return snapshotCaptured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
			} catch (Error fatal) {
				terminal = true;
				try { releaseRetained(fatal); }
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

		private StepOutcome liveStep(CostLedger ledger, @Nullable InventoryCatalogAdmission catalog, InventoryValidationBudget.@Nullable Allowance validation) {
			int allowance = (int) Math.min(MAX_STEP, Math.min(ledger.remaining(PHYSICAL), Math.max(0, ledger.remaining(PROVIDER_WORK) / PROVIDER_CALL_WORK - 1)));
			if (allowance == 0) return new StepOutcome.Deferred();
			var admission = ledger.tryReserve(Map.of(PHYSICAL, (long) allowance, PROBES, 1L, PROVIDER_WORK, (allowance + 1L) * PROVIDER_CALL_WORK));
			if (admission.isEmpty()) return new StepOutcome.Deferred();
			int used = 0;
			try (var ticket = admission.get()) {
				try {
					if (!(validation == null ? source.valid() : source.valid(validation))) { terminal = true; return captured(null, CaptureResult.Availability.INVALID, CaptureResult.Completeness.INCOMPLETE); }
					if (expected < 0) { firstVersion = source.version(); expected = source.stableCursor() ? source.slots() : -1; }
					Map<String, CaptureResult.OpaqueValue> page = new LinkedHashMap<>();
					boolean complete;
					if (!source.stableCursor()) {
						used = allowance; // throwing enumeration may have traversed its whole grant
						Page enumerated = validation != null ? source.enumerate(allowance, catalog, validation)
							: catalog == null ? source.enumerate(allowance) : source.enumerate(allowance, catalog);
						if (enumerated.slots().size() > allowance) throw new IllegalStateException("provider exceeded admitted enumeration");
						used = enumerated.slots().size();
						for (var item : enumerated.slots()) page.put(Integer.toString(cursor++), InventoryDomainCodec.encode(item));
						complete = enumerated.complete();
						expected = complete ? cursor : -1;
						terminal = true;
					} else {
						if (expected < 0 || source.slots() != expected) {
							// An ordinary topology change is unavailability, not a provider exception.
							terminal = true;
							return captured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
						}
						while (cursor < Math.min(expected, MAX_SLOTS) && used < allowance) {
							used++; // An attempted throwing read still consumes its admission.
							var item = validation != null ? source.read(cursor, catalog, validation)
								: catalog == null ? source.read(cursor) : source.read(cursor, catalog);
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
					if (failure.getCause() instanceof InventoryItemBudget.CaptureLimitException || failure.getCause() instanceof InventoryItemCodec.CapacityExceededException) {
						InventoryCaptureDiagnostics.boundExceeded(input, "catalog-work-or-workspace", failure);
						return captured(null, CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE);
					}
					InventoryCaptureDiagnostics.warn("live-step", input, descriptor.key(), () -> "cursor=" + cursor + " expected=" + expected, failure);
					return captured(null, CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE);
				} catch (InventoryItemBudget.CaptureLimitException | InventoryItemCodec.CapacityExceededException exhausted) {
					InventoryCaptureDiagnostics.boundExceeded(input, "catalog-work-or-workspace", exhausted);
					terminal = true;
					return captured(null, CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE);
				} catch (RuntimeException | LinkageError unavailable) {
					InventoryCaptureDiagnostics.warn("live-step", input, descriptor.key(), () -> "cursor=" + cursor + " expected=" + expected, unavailable);
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
			releaseRetained(null);
		}
		private void releaseRetained(Throwable primary) {
			InventorySnapshot held = snapshot;
			snapshot = null;
			RetainedMemoryLedger.Ticket heldTicket = snapshotMemory;
			snapshotMemory = null;
			Error fatal = null;
			try { closeRecoverable(input, descriptor.key(), held, primary); } catch (Error failure) { fatal = failure; }
			try { closeRecoverable(input, descriptor.key(), heldTicket, primary); }
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
				catch (RuntimeException | LinkageError unavailable) {
					if (fatal == null) InventoryCaptureDiagnostics.warn("source-close", input, descriptor.key(), () -> "sourceClass=" + source.getClass().getName(), unavailable);
				}
				catch (Error failure) {
					if (fatal == null) fatal = failure;
					else if (failure != fatal) fatal.addSuppressed(failure);
				}
				if (fatal != null) throw fatal;
			}
		}
	}

	private static void releaseUntransferred(InventorySourceInput input, SourceKey key, InventorySnapshot snapshot, RetainedMemoryLedger.Ticket ticket, Throwable primary) {
		Error fatal = null;
		try { closeRecoverable(input, key, snapshot, primary); } catch (Error failure) { fatal = failure; }
		try { closeRecoverable(input, key, ticket, primary); }
		catch (Error failure) {
			if (fatal == null) fatal = failure;
			else if (failure != fatal) fatal.addSuppressed(failure);
		}
		if (fatal != null) throw fatal;
	}

	private static void closeRecoverable(InventorySourceInput input, SourceKey key, InventorySnapshot snapshot, Throwable primary) throws Error {
		if (snapshot == null) return;
		try { snapshot.close(); } catch (RuntimeException | LinkageError failure) {
			if (primary != null && primary != failure) primary.addSuppressed(failure);
			// Expected primary outcomes have no exception report of their own; a
			// failing cleanup still needs its separate, explicitly associated report.
			if (primary == null || InventoryCaptureDiagnostics.expectedFailure(primary))
				InventoryCaptureDiagnostics.warn("snapshot-close", input, key,
					() -> "snapshotClass=" + snapshot.getClass().getName()
						+ (primary == null ? "" : " primaryClass=" + primary.getClass().getName() + " primaryMessage=" + primary.getMessage()), failure);
		}
	}

	private static void closeRecoverable(InventorySourceInput input, SourceKey key, RetainedMemoryLedger.Ticket ticket, Throwable primary) throws Error {
		if (ticket == null) return;
		try { ticket.close(); } catch (RuntimeException | LinkageError failure) {
			if (primary != null && primary != failure) primary.addSuppressed(failure);
			if (primary == null || InventoryCaptureDiagnostics.expectedFailure(primary))
				InventoryCaptureDiagnostics.warn("snapshot-memory-close", input, key,
					() -> "stage=retained-ticket-close"
						+ (primary == null ? "" : " primaryClass=" + primary.getClass().getName() + " primaryMessage=" + primary.getMessage()), failure);
		}
	}
}
