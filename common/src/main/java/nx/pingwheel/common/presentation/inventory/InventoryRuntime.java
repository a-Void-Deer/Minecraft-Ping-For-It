package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** One server-lifetime owner of physical rounds, logical coverage and retained admission. */
public final class InventoryRuntime implements AutoCloseable {
	private static final int MAX_CONSUMERS = 256, MAX_ROUNDS = 32;
	private static final int MAX_RETAINED_ROUNDS = MAX_ROUNDS * 2; // bounded replacement headroom for completed evidence leases
	private static final long CONSUMER_MEMORY = 4096;
	private static final long ROUND_MEMORY = 4096 + 262144 + 32768; // provider catalog and bounded logical subject history
	private static final int REPROJECT_BATCH = 64; // bounded retained-metadata walk per tightening call
	private static final long PAGE_FIXED_BYTES = 4096L, PAGE_WORKSPACE_FIXED = 8192L;
	// Bounded metadata/hash/index operations per copied entry, separate from the OLD parser.
	private static final long REPROJECT_ENTRY_WORK = 8192L;
	// Fixed capture admission is independent of the configurable consumption-slot cap.
	private static final CostLedger.Counter CAPTURE_READS = new CostLedger.Counter("inventory/capture_reads", CostLedger.Unit.WORK);
	private static final CostLedger.Counter ITEM_WORK = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
	/**
	 * Metadata above the display budget in one encoded observation, derived from
	 * the codec's own envelope so a measured page reservation never charges the
	 * absolute display bound to a small payload.
	 */
	private static final int ENTRY_METADATA_BYTES = InventoryDomainCodec.MAX_BYTES - InventoryDomainCodec.MAX_DISPLAY_BYTES;
	public sealed interface Subject permits PreviewSubject, TrackingSubject {}
	public record PreviewSubject(UUID client) implements Subject {}
	public record TrackingSubject(TargetKey target) implements Subject {}
	public record Observation(CaptureResult result, @Unmodifiable List<InventoryDomainCodec.@Nullable Item> slots, InventoryStep.SweepEvidence inventorySweepEvidence) {
		public Observation { slots = java.util.Collections.unmodifiableList(new ArrayList<>(slots)); }
		public Observation(CaptureResult result, List<InventoryDomainCodec.@Nullable Item> slots) { this(result, slots, InventoryStep.SweepEvidence.NONE); }
	}
	/** Server-thread source resolver; every new observation receives the authoritative display ceiling. */
	@FunctionalInterface
	public interface SourceResolver {
		Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes);
		default InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryValidationBudget.NONE; }
		default Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes, InventoryValidationBudget.Allowance allowance) {
			return resolve(input, displayBytes);
		}
	}
	/**
	 * Registry-aware detached display reprojection port. Production supplies the
	 * real item codec through the server host; a headless runtime without a
	 * registry port keeps retained slot metadata unchanged.
	 */
	@FunctionalInterface
	public interface DisplayReprojector {
		InventoryItemCodec.Display reproject(InventoryItemCodec.Display retained, int displayBytes);
		static DisplayReprojector none() { return (retained, displayBytes) -> retained; }
	}
	/** Coherent outcome of one bounded tightening request. */
	public enum ReprojectionStatus { UNCHANGED, COMPLETED, DEFERRED }
	/**
	 * Change summary of a bounded tightening operation. {@code changedKeys} and
	 * {@code foldIds} are cumulative for the consumer since the first tightening
	 * below its adopted ceiling, so a caller may union them across DEFERRED
	 * calls. Display-only tightening never introduces a fold, so {@code foldIds}
	 * remains empty for compatibility with existing callers.
	 */
	public record Reprojection(ReprojectionStatus status, int displayBytes,
		List<InventoryScanner.Key> changedKeys, List<String> foldIds) {
		public Reprojection {
			changedKeys = List.copyOf(changedKeys);
			foldIds = List.copyOf(foldIds);
		}
		static Reprojection unchanged(int displayBytes) {
			return new Reprojection(ReprojectionStatus.UNCHANGED, displayBytes, List.of(), List.of());
		}
	}
	public final class Consumer implements AutoCloseable {
		private final long diagnosticId = ++consumerSequence;
		private final InventoryBudgetDelayState admissionDelay, probeDelay, logicalDelay, projectionDelay;
		private boolean monitoringActive = true;
		private boolean monitoringScan = true;
		private boolean zeroAdmissionRefused;
		private final InventorySourceInput input;
		private final Subject subject;
		private final RetainedMemoryLedger.Ticket memory;
		private Round round;
		private Round completedRound;
		// A canonical source alias shares data, not the original input's lease or physical-binding authority.
		private InventorySourceAccess.InventoryHandle bindingEvidence;
		private RetainedMemoryLedger.Ticket bindingMemory;
		private Observation invalidObservation;
		private Observation pendingObservation;
		private int cursor;
		private boolean closed;
		private long observationFloor;
		private long firstRound;
		private Consumer(InventorySourceInput input, Subject subject, RetainedMemoryLedger.Ticket memory) {
			this.input = input; this.subject = subject; this.memory = memory;
			Module module = subject instanceof PreviewSubject ? Module.INVENTORY_PREVIEW : Module.INVENTORY_TRACKING;
			admissionDelay = new InventoryBudgetDelayState(module); probeDelay = new InventoryBudgetDelayState(module);
			logicalDelay = new InventoryBudgetDelayState(module); projectionDelay = new InventoryBudgetDelayState(module);
			observationFloor = tick;
			firstRound = roundSequence + 1;
		}
		public InventorySourceInput input() { return input; }
		public boolean invalidated() { return invalidObservation != null; }
		/** No source access: audience removal and terminal discard remove retryable scan work. */
		void monitoringActive(boolean active) {
			monitoringActive = active;
			if (!active) cancelDelays();
		}
		void cancelDelays() {
			admissionDelay.cancel(); probeDelay.cancel(); logicalDelay.cancel(); projectionDelay.cancel();
		}
		void stopMonitoringScan() { monitoringScan = false; admissionDelay.cancel(); logicalDelay.cancel(); }
		/** Pins the complete observation actually accepted for publication, not a rejected or overflowing sweep. */
		public void retainCompleted() {
			if (closed || invalidObservation != null || round == null || round.last == null
				|| round.last.completeness() != CaptureResult.Completeness.COMPLETE || cursor != round.size())
				throw new IllegalStateException("no complete inventory observation");
			completed(this, round);
		}
		public void restart() {
			cancelDelays();
			monitoringScan = true;
			if (round != null) rounds.remove(round.key, round); // new observation evidence, not source identity
			releaseScanning(this); invalidObservation = null; pendingObservation = null; cursor = 0; observationFloor = tick; firstRound = 0;
			if (completedRound == null) releaseBindingEvidence(this);
		}
		/** Releases this consumer's invalid data/evidence without invalidating unrelated physical rounds. */
		public void discardObservation() {
			cancelDelays();
			invalidObservation = unavailable(input.viewKey()); pendingObservation = null; cursor = 0;
			release(this);
		}
		@Override public void close() {
			if (!closed) {
				closed = true;
				cancelDelays();
				invalidObservation = null; pendingObservation = null;
				try { release(this); } finally { consumers.remove(this); memory.close(); }
			}
		}
	}
	private static final class Round {
		InventoryBudgetDelayState physicalDelay;
		Module physicalModule;
		final SourceKey key;
		final InventorySourceInput input;
		final InventorySourceAccess.InventoryHandle handle;
		final AdmittedSource source;
		final RetainedMemoryLedger.Ticket memory;
		final long startedTick;
		final long sequence;
		boolean trackingObservation;
		InventorySourceAccess.Preparation preparation;
		final List<InventoryDomainCodec.Item> slots = new ArrayList<>();
		@Nullable InventorySlotJournal journal;
		RetainedMemoryLedger.@Nullable Ticket nativeMemory;
		InventoryStep.SweepEvidence sweepEvidence = InventoryStep.SweepEvidence.NONE;
		final Map<Subject, Integer> paid = new LinkedHashMap<>();
		final List<RetainedMemoryLedger.Ticket> pages = new ArrayList<>();
		final List<RetainedMemoryLedger.Ticket> catalogMemory = new ArrayList<>();
		final List<RetainedMemoryLedger.Ticket> projectionMemory = new ArrayList<>();
		long catalogBytes;
		CaptureResult last;
		Observation failureObservation;
		int references;
		int scanners; // consumer scans plus bounded physical handoff leases
		int appliedBytes; // ceiling fully walked over this round's decoded slots
		int passCeiling; // target of the active tightening pass
		int reprojectCursor; // bounded tightening walk position over decoded slots
		int reprojectEnd; // slots.size() frozen at pass start; later slots are already tightened
		boolean passActive;
		boolean sourceProjectionUnsupported; // retained decoded pages remain usable; no further opaque decode/capture
		int pageCount; // page commits ever appended, so a settled swap keeps their fixed overhead
		boolean pagesChanged;
		boolean pagesSettled;
		Set<InventoryScanner.Key> tightenedKeys;
		Round successor;
		final Set<Round> predecessors = new java.util.LinkedHashSet<>();
		boolean closed;
		Round(SourceKey key, InventorySourceInput input, InventorySourceAccess.InventoryHandle handle, AdmittedSource source, RetainedMemoryLedger.Ticket memory, long tick, long sequence, boolean tracking, int displayBytes) {
			this.key = key; this.input = input; this.handle = handle; this.source = source; this.memory = memory; startedTick = tick; this.sequence = sequence; trackingObservation = tracking;
			this.appliedBytes = displayBytes;
			journal = handle.readMode() == InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL ? new InventorySlotJournal() : null;
		}
		int size() { return journal == null ? slots.size() : journal.size(); }
		boolean empty() { return size() == 0; }
		InventoryDomainCodec.@Nullable Item read(int slot) { return journal == null ? slots.get(slot) : journal.read(slot); }
	}
	private final SourceResolver resolver;
	private final DisplayReprojector reprojector;
	private final BudgetDelaySink sink;
	private long consumerSequence;
	private final List<Consumer> consumers = new ArrayList<>();
	private final Map<SourceKey, Round> rounds = new LinkedHashMap<>();
	private int retainedRounds;
	private int scanningRounds;
	private long roundSequence;
	private final RetainedMemoryLedger memory;
	private CostLedger physical;
	private InventorySettings settings;
	private long tick = -1;
	private final InventoryPeriodClock previewClock = new InventoryPeriodClock(), trackingClock = new InventoryPeriodClock();
	private final Map<Subject, Integer> logicalUsed = new LinkedHashMap<>();
	private final Map<Subject, RetainedMemoryLedger.Ticket> logicalMemory = new LinkedHashMap<>();
	private int previewGlobal, trackingGlobal;

	/** Existing resolver-only callers keep the default 4 KiB catalogue and a headless no-op reprojection port. */
	public InventoryRuntime(Function<InventorySourceInput, Optional<InventorySourceAccess.Source>> resolver, long memoryBytes) {
		this((input, displayBytes) -> resolver.apply(input), memoryBytes, DisplayReprojector.none());
	}
	public InventoryRuntime(SourceResolver resolver, long memoryBytes) {
		this(resolver, memoryBytes, DisplayReprojector.none());
	}
	public InventoryRuntime(SourceResolver resolver, long memoryBytes, DisplayReprojector reprojector) {
		this(resolver, memoryBytes, reprojector, BudgetDelaySink.NONE);
	}
	public InventoryRuntime(SourceResolver resolver, long memoryBytes, DisplayReprojector reprojector, BudgetDelaySink sink) {
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.reprojector = java.util.Objects.requireNonNull(reprojector, "reprojector");
		this.sink = sink == null ? BudgetDelaySink.NONE : sink;
		memory = new RetainedMemoryLedger(memoryBytes);
	}
	public RetainedMemoryLedger memory() { return memory; }
	/** Detached processing shares item work; no source probe or root workspace is needed. */
	boolean claimDetachedItemWork(long workUnits) {
		if (workUnits < 0) throw new IllegalArgumentException("inventory detached work bound");
		if (physical.remaining(ITEM_WORK) < workUnits) return false;
		if (workUnits == 0) return true;
		var amounts = Map.of(ITEM_WORK, workUnits);
		var admission = physical.tryReserve(amounts);
		if (admission.isEmpty()) return false;
		try (var grant = admission.get()) {
			grant.commit(amounts); // callers invoke the admitted operation immediately; attempts are never refunded
			return true;
		}
	}
	/** List/map traversal, bounded key hashing/equality and replacement metadata construction. */
	static long reprojectionCopyWork(long entries) {
		return reprojectionCopyWork(entries, entries);
	}
	private static long reprojectionCopyWork(long entries, long indexedEntries) {
		// Key has at most two 256-unit strings. Even a non-comparable HashMap
		// collision tree may visit every existing key: allow four local events
		// per compared unit rather than assuming constant-time insertion.
		return checkedAdd(4096L, checkedMultiply(entries,
			checkedAdd(REPROJECT_ENTRY_WORK, checkedMultiply(2048L, indexedEntries))));
	}
	/** Matches the callers' oversized-only parser predicate; fitting/null inputs do not parse. */
	static long reprojectionWork(String json, int ceiling) {
		long bytes = json == null ? 0 : utf8Bytes(json);
		return bytes <= ceiling ? 0 : reprojectionInputWork(Math.toIntExact(bytes));
	}
	private static long reprojectionInputWork(int oldBytes) {
		// Additional caller UTF counts, output validation/copy and Item construction
		// are linear in OLD bytes (the retained projection never expands).
		return InventoryReprojectionSummary.parserWork(oldBytes);
	}
	public long previewPeriodId() { return previewClock.period(); }
	public long trackingPeriodId() { return trackingClock.period(); }
	private Module module(Consumer consumer) { return consumer.subject instanceof PreviewSubject ? Module.INVENTORY_PREVIEW : Module.INVENTORY_TRACKING; }
	private long cycle(Module module) { return module == Module.INVENTORY_PREVIEW ? previewPeriodId() : trackingPeriodId(); }
	private long cycle(Consumer consumer) { return cycle(module(consumer)); }
	private Quantity logicalQuantity(Consumer consumer) {
		Round round = consumer.round;
		return round != null && round.last != null && round.last.coverage().expected().isPresent()
			? Quantity.of(Unit.LOGICAL_SLOT, Math.max(0, round.last.coverage().expected().getAsLong() - consumer.cursor)) : Quantity.unknown();
	}
	private void delay(Consumer consumer, InventoryBudgetDelayState stage, int reasons, String name) {
		stage.observe(sink, cycle(consumer), System.nanoTime(), reasons,
			() -> new WorkItem("consumer/" + consumer.diagnosticId + "/" + name, "Inventory consumer " + consumer.diagnosticId + " " + name),
			() -> stage == consumer.logicalDelay ? logicalQuantity(consumer) : Quantity.unknown());
	}
	private void physicalDelay(Consumer consumer, int reasons, long requestedSlots) {
		if (reasons == 0) return;
		Round round = consumer.round;
		if (round == null) { delay(consumer, consumer.admissionDelay, reasons, "admission"); return; }
		if (round.physicalDelay == null) {
			round.physicalModule = module(consumer);
			round.physicalDelay = new InventoryBudgetDelayState(round.physicalModule);
		}
		round.physicalDelay.observe(sink, cycle(round.physicalModule), System.nanoTime(), reasons,
			() -> new WorkItem("round/" + round.sequence + "/physical", "Inventory round " + round.sequence + " physical capture/decode"),
			() -> requestedSlots < 0 || round.last == null || round.last.coverage().expected().isEmpty()
				? Quantity.unknown() : Quantity.of(Unit.PHYSICAL_SLOT, Math.max(0, round.last.coverage().expected().getAsLong() - round.size())));
	}
	private void physicalProgress(Round round) {
		if (round.physicalDelay != null) round.physicalDelay.progress(cycle(round.physicalModule));
	}
	private void warn(String operation, Consumer consumer, Round round, Throwable failure) {
		InventoryCaptureDiagnostics.warn("runtime/" + operation, consumer.input, round == null ? null : round.key,
			() -> "consumer=" + consumer.diagnosticId + " subject=" + consumer.subject
				+ " round=" + (round == null ? "none" : round.sequence) + " tick=" + tick, failure);
	}
	private int providerReasons(long probes, long work) {
		return (physical.remaining(InventorySourceAccess.PROBES) < probes ? Reason.PROBE_WORK.bit() : 0)
			| (physical.remaining(InventorySourceAccess.PROVIDER_WORK) < work ? Reason.PROVIDER_WORK.bit() : 0);
	}
	boolean delayed(Module module) {
		for (Consumer consumer : consumers) if (consumer.monitoringActive && !consumer.invalidated()) {
			if (module(consumer) == module && (consumer.admissionDelay.outstanding() || consumer.probeDelay.outstanding()
				|| consumer.logicalDelay.outstanding() || consumer.projectionDelay.outstanding())) return true;
			Round round = consumer.round;
			if (consumer.monitoringScan && round != null && round.physicalModule == module && round.physicalDelay != null && round.physicalDelay.outstanding()) return true;
		}
		return false;
	}
	boolean eligible(Module module) {
		for (Consumer consumer : consumers) if (consumer.monitoringActive && !consumer.invalidated()) {
			if (module(consumer) == module && (consumer.admissionDelay.eligible() || consumer.probeDelay.eligible()
				|| consumer.logicalDelay.eligible() || consumer.projectionDelay.eligible())) return true;
			Round round = consumer.round;
			if (round != null && round.physicalModule == module && round.physicalDelay != null && round.physicalDelay.eligible()) return true;
		}
		return false;
	}
	public boolean selectionPresent(InventorySourceInput input, InventorySelection selection, int witness) {
		int displayCap = effectiveDisplayCap();
		long fixed = checkedAdd(InventoryCatalogAdmission.METADATA_BYTES, checkedAdd(authorityWorkspaceBytes(), liveCatalogUpperBound()));
		long result = InventoryItemBudget.retainedUpperBound(displayCap);
		int bound = (int) Math.min(physicalRemaining(), Math.min(InventorySourceAccess.MAX_STEP, Math.max(0, (memory.remaining() - fixed) / result)));
		if (bound == 0) return false;
		var workspace = memory.tryReserve(checkedAdd(fixed, checkedMultiply(bound, result)));
		if (workspace.isEmpty()) return false;
		try (var held = workspace.get()) {
			long work = checkedMultiply(bound, InventoryItemBudget.workUpperBound(displayCap));
			var workAdmission = physical.tryReserve(Map.of(ITEM_WORK, work));
			if (workAdmission.isEmpty()) return false;
			long before = physical.used(InventorySourceAccess.PHYSICAL);
			try (var grant = workAdmission.get()) {
				var catalog = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(displayCap),
					checkedMultiply(bound, InventoryCatalogAdmission.workUpperBound(displayCap)));
				try {
					var probe = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
					if (probe.isEmpty()) return false;
					try (var admittedProbe = probe.get()) {
					var resolution = validation(resolver.resolutionValidationCost(input), new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), authorityWorkspaceBytes());
					if (resolution.isEmpty()) return false;
					InventoryValidationBudget.Allowance allowance;
					InventorySourceAccess.@org.jetbrains.annotations.Nullable Source source;
					try (var operation = resolution.get()) {
						allowance = operation.allowance;
						admittedProbe.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
						source = resolver.resolve(input, displayCap, allowance).orElse(null);
					}
					if (source == null) return false;
					try (source) {
						var read = validation(source.evidenceValidationCost().plus(source.readValidationCost(source.stableCursor() ? 1 : bound)), allowance, authorityWorkspaceBytes());
						if (read.isEmpty()) return false;
						try (var readGrant = read.get()) {
							// The outer operation owns close even when slot/provider admission refuses before resolver invocation.
							var borrowed = new AdmittedSource(source, false);
							return new InventorySourceAccess(input, i -> Optional.of(borrowed)).selectionPresent(selection, witness, bound, physical, catalog, allowance);
						}
					}
					}
				} catch (RuntimeException | LinkageError failure) {
					InventoryCaptureDiagnostics.warn("runtime/selection", input, null, () -> "witness=" + witness, failure);
					return false;
				}
				finally { grant.commit(Map.of(ITEM_WORK, checkedAdd(
					checkedMultiply(physical.used(InventorySourceAccess.PHYSICAL) - before, InventoryItemBudget.freezeWorkUpperBound(displayCap)), catalog.attemptedWork()))); }
			}
		}
	}
	public <T> Optional<T> preflight(java.util.function.Supplier<Optional<T>> work) {
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) return Optional.empty();
		try (var held = workspace.get()) {
		var ticket = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (ticket.isEmpty()) return Optional.empty();
		try (var grant = ticket.get()) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK)); return work.get();
		} catch (RuntimeException | LinkageError failure) {
			if (!InventoryCaptureDiagnostics.expectedFailure(failure))
				Diagnostics.warn("inventory/runtime/preflight", work.getClass().getName(), () -> "tick=" + tick, failure);
			return Optional.empty();
		}
		}
	}
	public void advance(long now, InventorySettings settings) {
		if (now < tick) throw new IllegalArgumentException("inventory clock regressed");
		this.settings = settings;
		memory.setCap(settings.pendingMemoryBytes());
		if (previewClock.advance(now, settings.getPreview().getPeriodTicks())) { previewGlobal = 0; clearLogical(true); }
		if (trackingClock.advance(now, settings.getTracking().getPeriodTicks())) { trackingGlobal = 0; clearLogical(false); }
		if (now != tick) {
			tick = now;
			for (Consumer consumer : consumers) {
				consumer.admissionDelay.beginTick(cycle(consumer)); consumer.probeDelay.beginTick(cycle(consumer));
				consumer.logicalDelay.beginTick(cycle(consumer)); consumer.projectionDelay.beginTick(cycle(consumer));
				Round round = consumer.round;
				if (round != null && round.physicalDelay != null) round.physicalDelay.beginTick(cycle(round.physicalModule));
			}
			physical = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) settings.effectivePhysicalSlotsPerTick(),
				InventorySourceAccess.PROBES, 256L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK,
				CAPTURE_READS, (long) InventorySourceAccess.MAX_SLOTS,
				ITEM_WORK, checkedMultiply(InventorySourceAccess.MAX_SLOTS, InventoryItemBudget.workUpperBound(effectiveDisplayCap()))));
		}
	}
	private long physicalRemaining() {
		// A live candidate may be applied between ingress tasks in the same tick.
		// Lower caps take effect without rebuilding the ledger or refunding its spend;
		// increases remain conservatively bounded by this tick's admitted limit.
		return Math.max(0, Math.min(physical.remaining(InventorySourceAccess.PHYSICAL),
			settings.effectivePhysicalSlotsPerTick() - physical.used(InventorySourceAccess.PHYSICAL)));
	}
	/** Authoritative effective display ceiling for new observations; validated and clamped. */
	private int effectiveDisplayCap() {
		InventorySettings current = settings;
		if (current == null) return InventoryDomainCodec.MAX_DISPLAY_BYTES;
		return clampDisplayBytes((int) current.effectiveDisplayBytes());
	}
	/** One caller-owned synchronous allowance. No ticket or allowance survives the operation. */
	private final class ValidationOperation implements AutoCloseable {
		final InventoryValidationBudget.Allowance allowance;
		final RetainedMemoryLedger.Ticket workspace;
		final CostLedger.Ticket work;
		final long before;
		ValidationOperation(InventoryValidationBudget.Cost cost, RetainedMemoryLedger.Ticket workspace, CostLedger.Ticket work, InventoryValidationBudget.Allowance allowance) {
			this.allowance = allowance; before = allowance.attemptedWorkUnits(); allowance.admitAdditional(cost); this.workspace = workspace; this.work = work;
		}
		@Override public void close() {
			try { work.commit(Map.of(ITEM_WORK, allowance.attemptedWorkUnits() - before)); }
			finally { allowance.releaseUnusedAdmission(); try { work.close(); } finally { workspace.close(); } }
		}
	}
	private Optional<ValidationOperation> validation(InventoryValidationBudget.Cost cost) {
		return validation(cost, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE));
	}
	private Optional<ValidationOperation> validation(InventoryValidationBudget.Cost cost, InventoryValidationBudget.Allowance allowance) {
		return validation(cost, allowance, 0);
	}
	private Optional<ValidationOperation> validation(InventoryValidationBudget.Cost cost, InventoryValidationBudget.Allowance allowance, long coveredWorkspace) {
		var workspace = memory.tryReserve(Math.max(0, cost.workspaceBytes() - coveredWorkspace));
		if (workspace.isEmpty()) return Optional.empty();
		var work = physical.tryReserve(Map.of(ITEM_WORK, cost.workUnits()));
		if (work.isEmpty()) { workspace.get().close(); return Optional.empty(); }
		return Optional.of(new ValidationOperation(cost, workspace.get(), work.get(), allowance));
	}
	private InventorySourceAccess sourceAccess(InventorySourceInput input, int cap,
		java.util.function.UnaryOperator<InventorySourceAccess.Source> decorate) {
		return new InventorySourceAccess(input, new InventorySourceAccess.Resolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput value) { return resolver.resolutionValidationCost(value); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput value) { return resolver.resolve(value, cap).map(decorate); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput value, InventoryValidationBudget.Allowance allowance) {
				return resolver.resolve(value, cap, allowance).map(decorate);
			}
		});
	}
	/** Encoded copies, decoded UTF-16 strings, page/map/index and consumer-list overlaps. */
	private static long slotWorkspaceBytes(int displayBytes) {
		long entry = Math.min(InventoryDomainCodec.MAX_BYTES, displayBytes + (long) ENTRY_METADATA_BYTES);
		return checkedAdd(checkedMultiply(entry, 8L), 1024L);
	}
	/** W also covers bounded held-name matching; final A1 lock dominance proof is pending. */
	private long authorityWorkspaceBytes() { return checkedAdd(ROUND_MEMORY, InventoryItemBudget.workspaceUpperBound(effectiveDisplayCap())); }
	/**
	 * Two live catalogues may be held by the source (compatibility and resident).
	 * Each payload cap pays canonical bytes and UTF-16 display ownership. Each
	 * entry additionally pays bounded id/label/digest strings, map/set nodes,
	 * backing references and collision evidence. Capture's separate K is B-owned.
	 */
	private static long liveCatalogUpperBound() {
		return checkedMultiply(2L, checkedAdd(checkedMultiply(3L, InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES),
			checkedMultiply(InventoryItemCodec.DEFAULT_MAX_ENTRIES, 4096L)));
	}
	private static long liveCatalogGrowth(int roots, int displayBytes, long held) {
		long perRoot = checkedMultiply(2L, checkedAdd(checkedMultiply(3L, InventoryItemBudget.retainedUpperBound(displayBytes)), 4096L));
		return Math.min(Math.max(0, liveCatalogUpperBound() - held), checkedMultiply(roots, perRoot));
	}
	private static int clampDisplayBytes(int displayBytes) {
		return Math.clamp(displayBytes, InventoryItemCodec.minimumDisplayBytes(), InventoryDomainCodec.MAX_DISPLAY_BYTES);
	}
	private static long utf8Bytes(String value) {
		if (value == null) return 0;
		long bytes = 0;
		for (int index = 0; index < value.length(); index++) {
			char unit = value.charAt(index);
			if (unit < 0x80) bytes++;
			else if (unit < 0x800) bytes += 2;
			else if (Character.isHighSurrogate(unit) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) { bytes += 4; index++; }
			else bytes += Character.isSurrogate(unit) ? 1 : 3; // CharsetEncoder's replacement byte is '?'.
		}
		return bytes;
	}
	private static long checkedAdd(long first, long second) {
		try { return Math.addExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	private static long checkedMultiply(long first, long second) {
		try { return Math.multiplyExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	/** Capture storage stays with the handle; this wrapper owns only attempted fixed work. */
	private final class AdmittedSource implements InventorySourceAccess.Source {
		private final InventorySourceAccess.Source delegate;
		private final boolean ownsDelegate;
		private final InventoryDisplayProjection projection = new InventoryDisplayProjection();
		private InventorySourceAccess.SnapshotPlan plan;
		private boolean discovered, captured, captureAttempted;
		private long captureValidationWork;
		AdmittedSource(InventorySourceAccess.Source delegate) { this(delegate, true); }
		AdmittedSource(InventorySourceAccess.Source delegate, boolean ownsDelegate) { this.delegate = delegate; this.ownsDelegate = ownsDelegate; }
		@Override public SourceKey key() { return delegate.key(); }
		@Override public InventorySourceAccess.ReadMode readMode() { return delegate.readMode(); }
		@Override public InventoryItemCodec.@Nullable Catalog nativeCatalog() { return delegate.nativeCatalog(); }
		@Override public long nativeCatalogGrowthUpperBound() { return delegate.nativeCatalogGrowthUpperBound(); }
		@Override public boolean valid() { return delegate.valid(); }
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) { return delegate.valid(allowance); }
		@Override public InventoryValidationBudget.Cost discoveryValidationCost() { return delegate.discoveryValidationCost(); }
		@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return delegate.evidenceValidationCost(); }
		@Override public InventoryValidationBudget.Cost readValidationCost(int slots) { return delegate.readValidationCost(slots); }
		@Override public InventoryDomainCodec.@org.jetbrains.annotations.Nullable Item read(int slot, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.read(slot, admission, allowance); }
		@Override public InventorySourceAccess.Page enumerate(int limit, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.enumerate(limit, admission, allowance); }
		@Override public boolean matchesSelection(int slot, InventorySelection selection, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.matchesSelection(slot, selection, admission, allowance); }
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.observeSelection(limit, selection, admission, allowance); }
		@Override public boolean stableCursor() { return delegate.stableCursor(); }
		@Override public int slots() { return delegate.slots(); }
		@Override public InventoryDomainCodec.Item read(int slot) { return delegate.read(slot); }
		@Override public InventoryDomainCodec.Item read(int slot, InventoryCatalogAdmission admission) { return delegate.read(slot, admission); }
		@Override public InventorySourceAccess.Page enumerate(int limit) { return delegate.enumerate(limit); }
		@Override public InventorySourceAccess.Page enumerate(int limit, InventoryCatalogAdmission admission) { return delegate.enumerate(limit, admission); }
		@Override public boolean matchesSelection(int slot, InventorySelection selection) { return delegate.matchesSelection(slot, selection); }
		@Override public boolean matchesSelection(int slot, InventorySelection selection, InventoryCatalogAdmission admission) { return delegate.matchesSelection(slot, selection, admission); }
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection) { return delegate.observeSelection(limit, selection); }
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection, InventoryCatalogAdmission admission) { return delegate.observeSelection(limit, selection, admission); }
		@Override public OptionalLong version() { return delegate.version(); }
		@Override public int displayBytes() { return delegate.displayBytes(); }
		@Override public InventoryReprojectionSummary reprojectionSummary(int displayBytes) {
			return projection.summary(delegate, InventorySourceAccess.Source.class, delegate.reprojectionSummary(displayBytes));
		}
		@Override public void tightenDisplay(int displayBytes) { delegate.tightenDisplay(displayBytes); }
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
			if (!discovered) { plan = delegate.snapshotPlan().orElse(null); discovered = true; }
			return admittedPlan();
		}
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(InventoryValidationBudget.Allowance allowance) {
			if (!discovered) { plan = delegate.snapshotPlan(allowance).orElse(null); discovered = true; }
			return admittedPlan();
		}
		private Optional<InventorySourceAccess.SnapshotPlan> admittedPlan() {
			if (plan == null) return Optional.empty();
			return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return plan.memoryUpperBoundBytes(); }
				@Override public InventoryValidationBudget.Cost validationCost() { return plan.validationCost(); }
				@Override public long validationAllocationUpperBoundBytes() { return plan.validationAllocationUpperBoundBytes(); }
				@Override public long attemptedValidationAllocationBytes() { return plan.attemptedValidationAllocationBytes(); }
				@Override public long attemptedValidationWorkUnits() { return plan.attemptedValidationWorkUnits(); }
				@Override public int physicalReadUpperBound() { return plan.physicalReadUpperBound(); }
				@Override public long itemWorkUpperBoundUnits() { return plan.itemWorkUpperBoundUnits(); }
				@Override public int attemptedPhysicalReads() { return plan.attemptedPhysicalReads(); }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					return capture(new InventoryValidationBudget.Allowance(validationCost()));
				}
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture(InventoryValidationBudget.Allowance allowance) {
					// The handle has acquired the plan's E/W/R/K grant before this call.
					captureAttempted = true;
					long before = allowance.attemptedWorkUnits();
					try {
						var result = plan.capture(allowance); captured = result.isPresent(); return result;
					} finally { captureValidationWork = allowance.attemptedWorkUnits() - before; }
				}
			});
		}
		@Override public void close() { if (ownsDelegate) delegate.close(); }
	}
	private InventorySourceAccess.Preparation prepareSnapshot(Consumer consumer, Round round, InventoryValidationBudget.Allowance discovery) {
		// Pure Cost metadata may construct bounded records: admit the existing fixed
		// authority scratch first, then extend it only to the discovery peak, not
		// cumulative allocation. This same scratch also covers legacy lock checks.
		long fixed = authorityWorkspaceBytes();
		var workspace = memory.tryReserve(fixed);
		if (workspace.isEmpty()) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var held = workspace.get()) {
			var cost = round.handle.discoveryValidationCost();
			var peak = memory.tryReserve(Math.max(0, cost.workspaceBytes() - fixed));
			if (peak.isEmpty()) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return InventorySourceAccess.Preparation.DEFERRED; }
			try (var discoveryWorkspace = peak.get()) {
				var admission = physical.tryReserve(Map.of(ITEM_WORK, cost.workUnits()));
				if (admission.isEmpty()) { physicalDelay(consumer, Reason.PUBLICATION_WORK.bit(), -1); return InventorySourceAccess.Preparation.DEFERRED; }
				try (var grant = admission.get()) {
					long before = discovery.attemptedWorkUnits(); discovery.admitAdditional(cost);
					try { return capturePreparedSnapshot(consumer, round, discovery); }
					finally {
						try { grant.commit(Map.of(ITEM_WORK, discovery.attemptedWorkUnits() - before - round.source.captureValidationWork)); }
						finally { round.source.captureValidationWork = 0; discovery.releaseUnusedAdmission(); }
					}
				}
			}
		} catch (InventoryItemBudget.CaptureLimitException exceeded) {
			InventoryCaptureDiagnostics.boundExceeded(consumer.input, "snapshot-discovery", exceeded);
			if (round.physicalDelay != null) round.physicalDelay.cancel();
			return InventorySourceAccess.Preparation.INCOMPLETE;
		} catch (RuntimeException | LinkageError unavailable) {
			warn("snapshot-prepare", consumer, round, unavailable);
			if (round.physicalDelay != null) round.physicalDelay.cancel();
			return InventorySourceAccess.Preparation.UNAVAILABLE;
		}
	}
	private InventorySourceAccess.Preparation capturePreparedSnapshot(Consumer consumer, Round round, InventoryValidationBudget.Allowance discovery) {
		AdmittedSource source = round.source;
		if (source.readMode() == InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL) return round.handle.prepareSnapshot(memory, discovery);
		InventorySourceAccess.@org.jetbrains.annotations.Nullable SnapshotPlan plan = source.snapshotPlan(discovery).orElse(null);
		long reads = plan == null ? 0 : plan.physicalReadUpperBound();
		long work = plan == null ? 0 : plan.itemWorkUpperBoundUnits();
		if (reads < 0 || reads > InventorySourceAccess.MAX_SLOTS || work < 0) {
			InventoryCaptureDiagnostics.boundExceeded(consumer.input, "snapshot-plan-reads-or-work reads=" + reads + " work=" + work);
			return InventorySourceAccess.Preparation.INCOMPLETE;
		}
		var admission = physical.tryReserve(Map.of(CAPTURE_READS, reads, ITEM_WORK, work));
		if (admission.isEmpty()) {
			physicalDelay(consumer, (physical.remaining(CAPTURE_READS) < reads ? Reason.CAPTURE_COUNT.bit() : 0)
				| (physical.remaining(ITEM_WORK) < work ? Reason.PROVIDER_WORK.bit() : 0), -1);
			return InventorySourceAccess.Preparation.DEFERRED;
		}
		int before = plan == null ? 0 : plan.attemptedPhysicalReads();
		source.captureAttempted = false;
		try (var grant = admission.get()) {
			try {
				if (plan != null) discovery.admitAdditional(plan.validationCost());
				var outcome = round.handle.prepareSnapshot(memory, discovery);
				// The handle's documented DEFERRED means plan memory refused before capture.
				if (outcome == InventorySourceAccess.Preparation.DEFERRED) physicalDelay(consumer, Reason.MEMORY.bit(), -1);
				else if (outcome == InventorySourceAccess.Preparation.READY) physicalProgress(round);
				else if (round.physicalDelay != null) round.physicalDelay.cancel();
				return outcome;
			}
			finally {
				long attempted = plan == null ? 0 : plan.attemptedPhysicalReads() - (long) before;
				if (attempted < 0 || attempted > reads) throw new IllegalStateException("capture exceeded attempted read admission");
				// A throwing capture pays every attempted root, never only its successful output.
				long attemptedWork = source.captureAttempted ? Math.min(work, checkedAdd(
					checkedMultiply(attempted, InventoryItemBudget.freezeWorkUpperBound(round.handle.displayBytes())), source.captureValidationWork)) : 0;
				grant.commit(Map.of(CAPTURE_READS, attempted, ITEM_WORK, attemptedWork));
			}
		}
	}
	public Optional<Consumer> attach(InventorySourceInput input, Subject subject) {
		if (consumers.size() >= MAX_CONSUMERS) return Optional.empty();
		var admission = memory.tryReserve(CONSUMER_MEMORY);
		if (admission.isEmpty()) return Optional.empty();
		Consumer consumer = new Consumer(input, subject, admission.get());
		admission.get().commit(CONSUMER_MEMORY);
		consumers.add(consumer);
		return Optional.of(consumer);
	}

	/** Input-only authority for initial admission, recovery and live SELECT witnesses, not cached publication. */
	public boolean validate(InventorySourceInput input) {
		return probe(input).orElse(false);
	}
	/** Empty is admission defer, not source invalidity. */
	public Optional<Boolean> probe(InventorySourceInput input) {
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) return Optional.empty();
		try (var reserved = workspace.get()) {
		var ticket = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (ticket.isEmpty()) return Optional.empty();
		try (var grant = ticket.get()) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			var resolution = validation(resolver.resolutionValidationCost(input), new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), authorityWorkspaceBytes());
			if (resolution.isEmpty()) return Optional.empty();
			InventoryValidationBudget.Allowance allowance;
			InventorySourceAccess.@org.jetbrains.annotations.Nullable Source resolved;
			try (var operation = resolution.get()) {
				allowance = operation.allowance;
				resolved = resolver.resolve(input, effectiveDisplayCap(), allowance).orElse(null);
			}
			try (var source = resolved) {
				if (source == null) { retireInvalid(input); return Optional.of(false); }
				var evidence = validation(source.evidenceValidationCost(), allowance, authorityWorkspaceBytes());
				if (evidence.isEmpty()) return Optional.empty();
				boolean valid;
				try (var operation = evidence.get()) { valid = source.valid(allowance); }
				if (!valid) retireInvalid(input);
				return Optional.of(valid);
			}
		} catch (InventoryItemBudget.CaptureLimitException deferred) {
			return Optional.empty();
		} catch (RuntimeException | LinkageError unavailable) {
			InventoryCaptureDiagnostics.warn("runtime/input-probe", input, null, () -> "tick=" + tick, unavailable);
			retireInvalid(input); return Optional.of(false);
		}
		}
	}
	/** Checks the actual observation being published, including the completed result retained during a new sweep. */
	public Optional<Boolean> probe(Consumer consumer) {
		if (consumer.closed || consumer.invalidObservation != null) return Optional.of(false);
		Round completed = consumer.completedRound, scanning = consumer.round;
		if (completed == null && scanning == null) {
			// Input-only recovery has no readable-source evidence. Do not infer delay.
			var result = probe(consumer.input);
			if (result.isPresent()) consumer.probeDelay.cancel();
			return result;
		}
		int calls = (completed != null && scanning != null && completed != scanning ? 2 : 1) + (consumer.bindingEvidence == null ? 0 : 1);
		var validationCost = consumer.bindingEvidence == null ? InventoryValidationBudget.NONE : consumer.bindingEvidence.evidenceValidationCost();
		if (completed != null) validationCost = validationCost.plus(completed.handle.evidenceValidationCost());
		if (scanning != null && scanning != completed) validationCost = validationCost.plus(scanning.handle.evidenceValidationCost());
		var validation = validation(validationCost);
		if (validation.isEmpty()) { delay(consumer, consumer.probeDelay, Reason.PROVIDER_WORK.bit() | Reason.MEMORY.bit(), "probe"); return Optional.empty(); }
		try (var operation = validation.get()) {
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) { delay(consumer, consumer.probeDelay, Reason.MEMORY.bit(), "probe"); return Optional.empty(); }
		try (var reserved = workspace.get()) {
			var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, (long) calls,
				InventorySourceAccess.PROVIDER_WORK, calls * InventorySourceAccess.PROVIDER_CALL_WORK));
			if (admission.isEmpty()) { delay(consumer, consumer.probeDelay, providerReasons(calls, calls * InventorySourceAccess.PROVIDER_CALL_WORK), "probe"); return Optional.empty(); }
			int used = 0;
			try (var grant = admission.get()) {
				try {
					if (consumer.bindingEvidence != null) {
						used++;
						try {
							if (!bindingEvidenceValid(consumer, operation.allowance)) { consumer.discardObservation(); return Optional.of(false); }
						} catch (InventoryItemBudget.CaptureLimitException deferred) { return Optional.empty(); }
					}
					for (Round round : completed == null ? List.of(scanning) : scanning == null || completed == scanning ? List.of(completed) : List.of(completed, scanning)) {
						used++; // attempted validity calls remain charged, including exceptions and invalid retirement
						boolean valid;
						try { valid = !round.closed && round.handle.evidenceValid(operation.allowance); }
						catch (InventoryItemBudget.CaptureLimitException deferred) { return Optional.empty(); }
						catch (RuntimeException | LinkageError unavailable) { warn("evidence-probe", consumer, round, unavailable); valid = false; }
						if (!valid) { retire(round, unavailable(consumer.input.viewKey())); return Optional.of(false); }
					}
					if (consumer.probeDelay.outstanding()) consumer.probeDelay.progress(cycle(consumer));
					return Optional.of(true);
				} finally {
					grant.commit(Map.of(InventorySourceAccess.PROBES, (long) used,
						InventorySourceAccess.PROVIDER_WORK, used * InventorySourceAccess.PROVIDER_CALL_WORK));
				}
			}
		}
		}
	}

	public Optional<Observation> step(Consumer consumer) {
		return step(consumer, InventorySourceAccess.MAX_STEP);
	}
	/** Fix the physical capture route before scan or logical allowance is considered. */
	public InventorySourceAccess.Preparation prepare(Consumer consumer) {
		return prepare(consumer, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE));
	}
	private InventorySourceAccess.Preparation prepare(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		if (consumer.closed) return InventorySourceAccess.Preparation.UNAVAILABLE;
		if (consumer.invalidObservation != null || consumer.pendingObservation != null)
			return InventorySourceAccess.Preparation.UNAVAILABLE;
		Round handoff = consumer.completedRound == null ? null : consumer.completedRound.successor;
		if (consumer.round == null && consumer.completedRound != null && !consumer.completedRound.predecessors.isEmpty())
			return InventorySourceAccess.Preparation.DEFERRED; // finish the physical handoff before another replacement forks
		var bindingCheck = checkBindingEvidence(consumer, allowance);
		if (bindingCheck != InventorySourceAccess.Preparation.READY) return bindingCheck;
		if (consumer.round == null && handoff != null && consumer.input.sameBinding(handoff.input)) return prepareHandoff(consumer, handoff, allowance);
		if (consumer.round == null) {
			for (Consumer peer : consumers) if (handoff == null && peer != consumer && peer.round != null && peer.input.sameBinding(consumer.input)
				&& (!(consumer.input.target() instanceof nx.pingwheel.common.domain.Target.ExternalBlockTarget) || consumer.input.sameBinding(peer.round.input))
				&& shareable(consumer, peer.round)) {
				join(consumer, peer.round);
				break;
			}
		}
		if (consumer.round == null) {
			// A first observation cannot consume the completed-evidence replacement reserve.
			// Count physical rounds, not consumers: compatible scanners already share above.
			int retainedLimit = consumer.completedRound == null ? MAX_ROUNDS : MAX_RETAINED_ROUNDS;
			boolean canCreate = handoff == null && scanningRounds < MAX_ROUNDS && retainedRounds < retainedLimit;
			if (!canCreate && handoff == null && rounds.values().stream().noneMatch(round -> round.key.readScope().equals(consumer.input.viewKey())
				&& shareable(consumer, round))) { delay(consumer, consumer.admissionDelay, Reason.ROUND_CAPACITY.bit(), "admission"); return InventorySourceAccess.Preparation.DEFERRED; }
			var retained = memory.tryReserve(authorityWorkspaceBytes());
			if (retained.isEmpty()) { delay(consumer, consumer.admissionDelay, Reason.MEMORY.bit(), "admission"); return InventorySourceAccess.Preparation.DEFERRED; }
			boolean ownsReservation = true;
			SourceAccess.Handle openedHandle = null;
			try {
				int displayCap = effectiveDisplayCap();
				AdmittedSource[] resolvedSource = new AdmittedSource[1];
				var access = sourceAccess(consumer.input, displayCap, source -> resolvedSource[0] = new AdmittedSource(source));
				var scope = new SourceAccess.ReadScope(consumer.input.viewKey(), Set.of("pingforit:inventory.items"));
				var resolution = validation(resolver.resolutionValidationCost(consumer.input), allowance, authorityWorkspaceBytes());
				if (resolution.isEmpty()) { delay(consumer, consumer.admissionDelay, Reason.PROVIDER_WORK.bit() | Reason.MEMORY.bit(), "admission"); return InventorySourceAccess.Preparation.DEFERRED; }
				SourceAccess.ResolveResult resolved;
				try (var operation = resolution.get()) {
					resolved = access.resolve(MinecraftBlockReadSources.detached(consumer.input.target()), scope, physical, operation.allowance);
				}
				if (!(resolved instanceof SourceAccess.ResolveResult.Available available)) {
					if (resolved == SourceAccess.ResolveResult.Unresolved.DEFERRED) {
						delay(consumer, consumer.admissionDelay, providerReasons(1, InventorySourceAccess.PROVIDER_CALL_WORK), "admission");
						return InventorySourceAccess.Preparation.DEFERRED;
					}
					consumer.cancelDelays();
					consumer.pendingObservation = unavailable(scope.viewKey());
					return InventorySourceAccess.Preparation.UNAVAILABLE;
				}
				var opened = access.open(available.descriptor(), scope, physical);
				if (!(opened instanceof SourceAccess.OpenResult.Started started)) {
					if (opened == SourceAccess.OpenResult.Unstarted.DEFERRED) return InventorySourceAccess.Preparation.DEFERRED;
					consumer.pendingObservation = unavailable(scope.viewKey());
					return InventorySourceAccess.Preparation.UNAVAILABLE;
				}
				openedHandle = started.handle();
				// Aliased hit positions still resolve their canonical key before joining a pinned handoff.
				if (handoff != null && !handoff.key.equals(available.descriptor().key())) {
					retire(consumer.completedRound, unavailable(scope.viewKey()));
					return InventorySourceAccess.Preparation.UNAVAILABLE;
				}
				if (handoff != null) {
					if (retainBindingEvidence(consumer, handoff, (InventorySourceAccess.InventoryHandle) openedHandle, retained.get())) {
						ownsReservation = false; openedHandle = null;
					}
					if (ownsReservation) { retained.get().close(); ownsReservation = false; }
					return prepareHandoff(consumer, handoff, allowance);
				}
				Round round = rounds.get(available.descriptor().key());
				// A newly selected Ping never inherits a completed preview count.
				if (round != null && !shareable(consumer, round)) round = null;
				if (round == null) {
					// Another hit position may resolve to a compatible canonical alias even at capacity.
					// Only an actual new physical round needs the normal/replacement admission slot.
					if (!canCreate) { delay(consumer, consumer.admissionDelay, Reason.ROUND_CAPACITY.bit(), "admission"); return InventorySourceAccess.Preparation.DEFERRED; }
					if (consumer.completedRound != null && !consumer.completedRound.key.equals(available.descriptor().key())) {
						retire(consumer.completedRound, unavailable(scope.viewKey()));
						return InventorySourceAccess.Preparation.UNAVAILABLE; // canonical view topology changed, not admission pressure
					}
					retained.get().commit(ROUND_MEMORY);
					round = new Round(available.descriptor().key(), consumer.input, (InventorySourceAccess.InventoryHandle) started.handle(), resolvedSource[0], retained.get(), tick, ++roundSequence, consumer.subject instanceof TrackingSubject, displayCap);
					retainedRounds++;
					scanningRounds++;
					rounds.put(round.key, round);
					ownsReservation = false; openedHandle = null;
				}
				else if (retainBindingEvidence(consumer, round, (InventorySourceAccess.InventoryHandle) openedHandle, retained.get())) {
					ownsReservation = false; openedHandle = null;
				}
				join(consumer, round);
				var admittedBinding = checkBindingEvidence(consumer, allowance);
				if (admittedBinding != InventorySourceAccess.Preparation.READY) return admittedBinding;
			} finally {
				try { if (openedHandle != null) openedHandle.close(); }
				finally { if (ownsReservation) retained.get().close(); }
			}
		}
		Round round = consumer.round;
		consumer.admissionDelay.progress(cycle(consumer));
		if (round.preparation != null) return round.preparation;
		if (round.sourceProjectionUnsupported) return InventorySourceAccess.Preparation.READY;
		var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (admission.isEmpty()) { physicalDelay(consumer, providerReasons(1, InventorySourceAccess.PROVIDER_CALL_WORK), -1); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var grant = admission.get()) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L,
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			InventorySourceAccess.Preparation outcome = prepareSnapshot(consumer, round, allowance);
			if (outcome != InventorySourceAccess.Preparation.DEFERRED) round.preparation = outcome;
			return outcome;
		}
	}
	/** Keep the separately resolved alias input's evidence under the already admitted workspace ticket. */
	private boolean retainBindingEvidence(Consumer consumer, Round round, InventorySourceAccess.InventoryHandle handle,
		RetainedMemoryLedger.Ticket ticket) {
		if (!(consumer.input.target() instanceof nx.pingwheel.common.domain.Target.ExternalBlockTarget)
			|| consumer.bindingEvidence != null || consumer.input.sameBinding(round.input)) return false;
		ticket.commit(ROUND_MEMORY);
		consumer.bindingEvidence = handle; consumer.bindingMemory = ticket;
		return true;
	}
	private boolean bindingEvidenceValid(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		try { return consumer.bindingEvidence == null || consumer.bindingEvidence.evidenceValid(allowance); }
		catch (InventoryItemBudget.CaptureLimitException deferred) { throw deferred; }
		catch (RuntimeException | LinkageError unavailable) { warn("binding-probe", consumer, consumer.round, unavailable); return false; }
	}
	/** Cache consumption checks its own original binding, not only the peer whose physical sweep was shared. */
	private InventorySourceAccess.Preparation checkBindingEvidence(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		if (consumer.bindingEvidence == null) return InventorySourceAccess.Preparation.READY;
		var validation = validation(consumer.bindingEvidence.evidenceValidationCost(), allowance);
		if (validation.isEmpty()) { delay(consumer, consumer.probeDelay, Reason.PROVIDER_WORK.bit() | Reason.MEMORY.bit(), "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var operation = validation.get()) {
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) { delay(consumer, consumer.probeDelay, Reason.MEMORY.bit(), "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var reserved = workspace.get()) {
			var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L,
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			if (admission.isEmpty()) { delay(consumer, consumer.probeDelay, providerReasons(1, InventorySourceAccess.PROVIDER_CALL_WORK), "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
			try (var grant = admission.get()) {
				grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
				try {
					if (!bindingEvidenceValid(consumer, operation.allowance)) { consumer.discardObservation(); return InventorySourceAccess.Preparation.UNAVAILABLE; }
				} catch (InventoryItemBudget.CaptureLimitException deferred) { return InventorySourceAccess.Preparation.DEFERRED; }
				consumer.probeDelay.progress(cycle(consumer));
				return InventorySourceAccess.Preparation.READY;
			}
		}
		}
	}
	private InventorySourceAccess.Preparation prepareHandoff(Consumer consumer, Round handoff, InventoryValidationBudget.Allowance allowance) {
		var bindingCheck = checkBindingEvidence(consumer, allowance);
		if (bindingCheck != InventorySourceAccess.Preparation.READY) return bindingCheck;
		var validation = validation(handoff.handle.evidenceValidationCost(), allowance);
		if (validation.isEmpty()) { delay(consumer, consumer.probeDelay, Reason.PROVIDER_WORK.bit() | Reason.MEMORY.bit(), "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var operation = validation.get()) {
		// A cached successor may be consumed after its original scanner's tick. Validate its
		// own retained evidence, not just the slow peer's still-published predecessor.
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) { delay(consumer, consumer.probeDelay, Reason.MEMORY.bit(), "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var reserved = workspace.get()) {
			var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L,
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			if (admission.isEmpty()) { delay(consumer, consumer.probeDelay, providerReasons(1, InventorySourceAccess.PROVIDER_CALL_WORK), "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
			try (var grant = admission.get()) {
				grant.commit(Map.of(InventorySourceAccess.PROBES, 1L,
					InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
				boolean valid;
				try { valid = !handoff.closed && handoff.handle.evidenceValid(operation.allowance); }
				catch (InventoryItemBudget.CaptureLimitException deferred) { return InventorySourceAccess.Preparation.DEFERRED; }
				catch (RuntimeException | LinkageError unavailable) { warn("handoff-probe", consumer, handoff, unavailable); valid = false; }
				if (!valid) {
					Observation invalid = unavailable(consumer.input.viewKey());
					retire(handoff, invalid); consumer.pendingObservation = invalid;
					return InventorySourceAccess.Preparation.UNAVAILABLE;
				}
			}
		}
		}
		join(consumer, handoff);
		consumer.probeDelay.progress(cycle(consumer));
		return handoff.preparation == null ? prepare(consumer, allowance) : handoff.preparation;
	}
	public Optional<Observation> step(Consumer consumer, int slotBound) {
		if (slotBound < 1 || slotBound > InventorySourceAccess.MAX_STEP) throw new IllegalArgumentException("inventory step bound");
		if (consumer.closed) return Optional.empty();
		if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
		if (consumer.pendingObservation != null) return Optional.of(consumer.pendingObservation);
		var operationAllowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
		InventorySourceAccess.Preparation preparation = prepare(consumer, operationAllowance);
		if (preparation == InventorySourceAccess.Preparation.DEFERRED) return Optional.empty();
		if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
		if (preparation != InventorySourceAccess.Preparation.READY) {
			Round failedRound = consumer.round;
			if (failedRound == null) return Optional.ofNullable(consumer.pendingObservation);
			consumer.admissionDelay.cancel(); consumer.logicalDelay.cancel();
			if (failedRound.physicalDelay != null) failedRound.physicalDelay.cancel();
			if (failedRound.failureObservation == null) {
				var zero = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, 0L, InventorySourceAccess.PROBES, 0L,
					InventorySourceAccess.PROVIDER_WORK, 0L));
				SourceAccess.StepOutcome outcome = failedRound.handle.step(zero);
				CaptureResult failed = outcome instanceof SourceAccess.StepOutcome.Captured captured ? captured.result()
					: new CaptureResult(Optional.empty(), new CaptureResult.Coverage(consumer.input.viewKey(), 0, 0, OptionalLong.empty()),
						preparation == InventorySourceAccess.Preparation.INCOMPLETE ? CaptureResult.Availability.READABLE
							: preparation == InventorySourceAccess.Preparation.INVALID ? CaptureResult.Availability.INVALID : CaptureResult.Availability.UNAVAILABLE,
						CaptureResult.Completeness.INCOMPLETE, CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty());
				failedRound.last = failed;
				failedRound.failureObservation = new Observation(failed, List.of());
			}
			Observation terminal = failedRound.failureObservation;
			if (terminal.result().availability() != CaptureResult.Availability.READABLE) retire(failedRound, terminal);
			return Optional.of(terminal); // INCOMPLETE remains explicit, never pretend-complete.
		}
		Round retained = consumer.round;
		if (retained.sourceProjectionUnsupported && consumer.cursor == retained.size()
			&& retained.last.completeness() == CaptureResult.Completeness.INCOMPLETE) {
			consumer.logicalDelay.cancel();
			return Optional.of(new Observation(new CaptureResult(Optional.empty(), retained.last.coverage(),
				retained.last.availability(), retained.last.completeness(), retained.last.consistency(),
				retained.last.sourceVersion(), Optional.empty()), List.of()));
		}
		if (!logicalUsed.containsKey(consumer.subject) && logicalUsed.size() >= MAX_CONSUMERS) { delay(consumer, consumer.logicalDelay, Reason.SUBJECT_CAPACITY.bit(), "logical"); return Optional.empty(); }
		if (!logicalMemory.containsKey(consumer.subject)) {
			var held = memory.tryReserve(1024);
			if (held.isEmpty()) { delay(consumer, consumer.logicalDelay, Reason.MEMORY.bit(), "logical"); return Optional.empty(); }
			held.get().commit(1024); logicalMemory.put(consumer.subject, held.get()); logicalUsed.put(consumer.subject, 0);
		}
		int cap = consumer.subject instanceof PreviewSubject ? settings.getPreview().effectiveMaxSlotsPerClient()
			: settings.getTracking().effectiveMaxSlotsPerTarget();
		int globalCap = consumer.subject instanceof PreviewSubject ? settings.getPreview().effectiveMaxSlotsServer()
			: settings.getTracking().effectiveMaxSlotsServer();
		int global = consumer.subject instanceof PreviewSubject ? previewGlobal : trackingGlobal;
		int allowance = Math.min(slotBound, Math.min(cap - logicalUsed.getOrDefault(consumer.subject, 0), globalCap - global));
		Round round = consumer.round;
		if (!round.paid.containsKey(consumer.subject) && round.paid.size() >= MAX_CONSUMERS) { delay(consumer, consumer.logicalDelay, Reason.SUBJECT_CAPACITY.bit(), "logical"); return Optional.empty(); }
		if (consumer.subject instanceof TrackingSubject) round.trackingObservation = true;
		int paid = round.paid.getOrDefault(consumer.subject, 0);
		int available = Math.min(slotBound, Math.max(0, paid - consumer.cursor) + Math.max(0, allowance));
		if (available == 0) {
			if (round.empty() && round.last != null && zeroCostEmptyCompletion(round.last)) {
				consumer.logicalDelay.progress(cycle(consumer));
				return Optional.of(new Observation(round.last, List.of(), round.sweepEvidence));
			}
			if (consumer.cursor < round.size() || round.last != null && round.last.completeness() != CaptureResult.Completeness.CONTINUE) {
				if (consumer.cursor < round.size()) logicalRefusal(consumer, cap, globalCap, global);
				return Optional.empty();
			}
			SourceAccess.StepOutcome outcome = zeroAllowanceStep(consumer, operationAllowance);
			if (outcome instanceof SourceAccess.StepOutcome.Deferred) {
				if (!consumer.zeroAdmissionRefused) logicalRefusal(consumer, cap, globalCap, global);
				return Optional.empty();
			}
			CaptureResult result = ((SourceAccess.StepOutcome.Captured) outcome).result();
			if (result.availability() != CaptureResult.Availability.READABLE) {
				Observation invalid = new Observation(result, List.of());
				retire(round, invalid);
				return Optional.of(invalid);
			}
			if (!zeroCostEmptyCompletion(result)) { logicalRefusal(consumer, cap, globalCap, global); return Optional.empty(); }
			round.last = result;
			if (round.journal != null) round.sweepEvidence = InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP;
			consumer.logicalDelay.progress(cycle(consumer));
			return Optional.of(new Observation(result, List.of(), round.sweepEvidence));
		}
		if (round.journal != null && consumer.cursor >= round.size() && (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE)) {
			if (!readNative(consumer, available, operationAllowance)) return Optional.empty();
			if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
		}
		if (round.journal == null && consumer.cursor >= round.size() && (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE)) {
			int readDisplayCap = clampDisplayBytes(round.handle.displayBytes());
			long readWork = InventoryItemBudget.workUpperBound(readDisplayCap);
			long slotWorkspace = slotWorkspaceBytes(readDisplayCap);
			long rootWorkspace = InventoryItemBudget.workspaceUpperBound(Math.max(effectiveDisplayCap(), readDisplayCap));
			long fixed = checkedAdd(InventoryCatalogAdmission.METADATA_BYTES, checkedAdd(PAGE_WORKSPACE_FIXED, rootWorkspace));
			int physicalBound = (int) Math.min(physicalRemaining(), Math.max(0, physical.remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK - 1));
			int limitingReasons = scanReasons(available, readDisplayCap);
			int memoryBound = Math.min(available, Math.max(1, physicalBound));
			while (memoryBound > 0 && checkedAdd(fixed, checkedAdd(checkedMultiply(memoryBound, slotWorkspace),
				round.source.captured ? 0 : liveCatalogGrowth(memoryBound, round.handle.displayBytes(), round.catalogBytes))) > memory.remaining()) memoryBound--;
			if (memoryBound == 0) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return Optional.empty(); }
			long catalogGrowth = round.source.captured ? 0 : liveCatalogGrowth(memoryBound, round.handle.displayBytes(), round.catalogBytes);
			var catalog = memory.tryReserve(catalogGrowth);
			if (catalog.isEmpty()) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return Optional.empty(); }
			var workspace = memory.tryReserve(checkedAdd(checkedMultiply(memoryBound, slotWorkspace), fixed));
			if (workspace.isEmpty()) { catalog.get().close(); physicalDelay(consumer, Reason.MEMORY.bit(), -1); return Optional.empty(); }
			long readsBefore = physical.used(InventorySourceAccess.PHYSICAL);
			boolean retainedPage = false;
			try {
			int bounded = Math.min(memoryBound, (int) Math.min(physicalRemaining(),
				Math.max(0, physical.remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK - 1)));
			if (bounded == 0) {
				SourceAccess.StepOutcome outcome = zeroAllowanceAdmitted(consumer, operationAllowance);
				if (outcome instanceof SourceAccess.StepOutcome.Deferred) {
					physicalDelay(consumer, scanReasons(available, readDisplayCap), physicalRemaining() == 0 ? available : -1);
					workspace.get().close(); return Optional.empty();
				}
				CaptureResult zeroResult = ((SourceAccess.StepOutcome.Captured) outcome).result();
				if (zeroResult.availability() != CaptureResult.Availability.READABLE) {
					Observation invalid = new Observation(zeroResult, List.of());
					workspace.get().close(); retire(round, invalid); return Optional.of(invalid);
				}
				if (!zeroCostEmptyCompletion(zeroResult)) { physicalDelay(consumer, scanReasons(available, readDisplayCap), physicalRemaining() == 0 ? available : -1); workspace.get().close(); return Optional.empty(); }
				round.last = zeroResult;
				physicalProgress(round);
				workspace.get().commit(PAGE_FIXED_BYTES); round.pages.add(workspace.get()); round.pageCount++; retainedPage = true;
			}
			if (bounded == 0) {
				// Snapshot handles may report an empty COMPLETE observation without scan allowance;
				// live handles defer before touching their source at zero allowance.
			} else {
			long workBound = (bounded + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK;
			long itemWork = checkedMultiply(bounded, readWork);
			var grant = physical.tryReserve(Map.of(InventorySourceAccess.PHYSICAL, (long) bounded, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, workBound, ITEM_WORK, itemWork));
			if (grant.isEmpty()) { physicalDelay(consumer, scanReasons(bounded, readDisplayCap), -1); workspace.get().close(); return Optional.empty(); }
			SourceAccess.StepOutcome result;
			try (var physicalTicket = grant.get()) {
				CostLedger step = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) bounded, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, workBound));
				var catalogAdmission = new InventoryCatalogAdmission(rootWorkspace,
					checkedMultiply(bounded, InventoryCatalogAdmission.workUpperBound(readDisplayCap)));
				var validation = validation(round.handle.stepValidationCost(bounded), operationAllowance, rootWorkspace);
				if (validation.isEmpty()) { physicalDelay(consumer, Reason.PROVIDER_WORK.bit() | Reason.MEMORY.bit(), -1); return Optional.empty(); }
				try (var operation = validation.get()) { result = round.handle.step(step, catalogAdmission, operation.allowance); }
				finally { physicalTicket.commit(Map.of(InventorySourceAccess.PHYSICAL, bounded - step.remaining(InventorySourceAccess.PHYSICAL),
					InventorySourceAccess.PROBES, 1L - step.remaining(InventorySourceAccess.PROBES), InventorySourceAccess.PROVIDER_WORK, workBound - step.remaining(InventorySourceAccess.PROVIDER_WORK),
					// Keep the existing attempted-slot envelope for source authority,
					// returned-stack processing and page encoding, even on lazy reads.
					// Catalog pays its actual cumulative attempt ONCE inside U, not U+C.
					ITEM_WORK, checkedAdd(checkedMultiply(bounded - step.remaining(InventorySourceAccess.PHYSICAL),
						InventoryItemBudget.freezeWorkUpperBound(readDisplayCap)), catalogAdmission.attemptedWork()))); }
			}
			if (result instanceof SourceAccess.StepOutcome.Deferred) { workspace.get().close(); return Optional.empty(); }
			round.last = ((SourceAccess.StepOutcome.Captured) result).result();
			physicalProgress(round);
			if (round.last.availability() != CaptureResult.Availability.READABLE) {
				Observation invalid = new Observation(round.last, List.of());
				retire(round, invalid);
				return Optional.of(invalid);
			}
			if (round.last.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment page) {
				if (!InventoryDomainCodec.ID.equals(page.codecId())) throw new IllegalStateException("wrong inventory codec");
				page.entries().entrySet().stream().sorted(java.util.Comparator.comparingInt(e -> Integer.parseInt(e.getKey())))
					.forEach(e -> round.slots.add(InventoryDomainCodec.decode(e.getValue())));
			}
			long retainedBytes = PAGE_FIXED_BYTES;
			if (round.last.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment page)
				for (var entry : page.entries().entrySet()) {
					retainedBytes = checkedAdd(retainedBytes, checkedAdd(checkedMultiply(entry.getValue().size(), 2L), 256L));
					retainedBytes = checkedAdd(retainedBytes, itemRetainedBytes(round.slots.get(Integer.parseInt(entry.getKey()))));
				}
			workspace.get().commit(retainedBytes);
			round.pages.add(workspace.get());
			round.pageCount++;
			retainedPage = true;
			if (round.last.completeness() == CaptureResult.Completeness.CONTINUE && bounded < available) {
				int reasons = limitingReasons;
				if (memoryBound < Math.min(available, Math.max(1, physicalBound))) reasons |= Reason.MEMORY.bit();
				physicalDelay(consumer, reasons, (reasons & Reason.PHYSICAL_SLOTS.bit()) != 0 ? available : -1);
			}
			}
			} finally {
				long attempted = physical.used(InventorySourceAccess.PHYSICAL) - readsBefore;
				long retainedCatalog = round.source.captured ? 0 : liveCatalogGrowth(Math.toIntExact(attempted), round.handle.displayBytes(), round.catalogBytes);
				if (retainedCatalog > 0 && !round.closed) {
					catalog.get().commit(retainedCatalog); round.catalogMemory.add(catalog.get()); round.catalogBytes += retainedCatalog;
				} else catalog.get().close();
				if (!retainedPage) workspace.get().close();
			}
		}
		if (round.last == null) return Optional.empty();
		if (round.last.availability() != CaptureResult.Availability.READABLE)
			return Optional.of(new Observation(round.last, List.of()));
		int end = Math.min(round.size(), consumer.cursor + available);
		// Journal replay allocates bounded Item cells; slow consumers acquire their own scratch without repeating a getter.
		RetainedMemoryLedger.@Nullable Ticket replay = null;
		if (round.journal != null) {
			var admission = memory.tryReserve(PAGE_WORKSPACE_FIXED + 256L * (end - consumer.cursor));
			if (admission.isEmpty()) { delay(consumer, consumer.logicalDelay, Reason.MEMORY.bit(), "logical"); return Optional.empty(); }
			replay = admission.get();
		}
		try (var replayWorkspace = replay) {
		if (round.journal != null && !claimDetachedItemWork(4096L + 2048L * (end - consumer.cursor))) {
			delay(consumer, consumer.logicalDelay, Reason.PROVIDER_WORK.bit(), "logical"); return Optional.empty();
		}
		int charge = Math.max(0, end - paid);
		CostLedger logical = new CostLedger(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) Math.max(0, allowance)));
		var ticket = logical.tryReserve(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) charge)).orElseThrow();
		ticket.commit(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) charge));
		logicalUsed.merge(consumer.subject, charge, Integer::sum);
		if (consumer.subject instanceof PreviewSubject) previewGlobal += charge; else trackingGlobal += charge;
		round.paid.put(consumer.subject, Math.max(paid, end));
		List<InventoryDomainCodec.@Nullable Item> page = new ArrayList<>(end - consumer.cursor);
		for (int index = consumer.cursor; index < end; index++) page.add(round.read(index));
		consumer.cursor = end;
		consumer.logicalDelay.progress(cycle(consumer));
		if (round.paid.getOrDefault(consumer.subject, 0) <= end && round.last.coverage().expected().isPresent() && end < round.last.coverage().expected().getAsLong())
			logicalRefusal(consumer, cap, globalCap, consumer.subject instanceof PreviewSubject ? previewGlobal : trackingGlobal);
		CaptureResult.Completeness completeness = end == round.size() ? round.last.completeness() : CaptureResult.Completeness.CONTINUE;
		CaptureResult result = new CaptureResult(Optional.empty(), new CaptureResult.Coverage(round.last.coverage().demandStamp(), end, end,
			round.last.coverage().expected()), CaptureResult.Availability.READABLE, completeness, round.last.consistency(), round.last.sourceVersion(), Optional.empty());
		return Optional.of(new Observation(result, page, round.sweepEvidence));
		}
	}

	/** Peak admission is independent of per-slot retention: one root scratch, shared definitions and compact chunks. */
	public long stepMemoryUpperBound(Consumer consumer, int slots) {
		if (slots < 0 || slots > InventorySourceAccess.MAX_STEP) throw new IllegalArgumentException("inventory step memory bound");
		@Nullable Round round = consumer.round;
		if (round != null && round.journal != null) {
			int count = Math.min(slots, InventorySourceAccess.MAX_SLOTS - round.journal.endSlot());
			return checkedAdd(nativeWorkspace(round, slots), checkedAdd(nativeRetained(round), checkedAdd(
				round.journal.batchAllocationUpperBound(count), checkedMultiply(slots, round.handle.nativeCatalogGrowthUpperBound()))));
		}
		int cap = round == null ? effectiveDisplayCap() : round.handle.displayBytes();
		return checkedAdd(InventoryItemBudget.workspaceUpperBound(cap) + PAGE_WORKSPACE_FIXED + InventoryCatalogAdmission.METADATA_BYTES,
			checkedAdd(checkedMultiply(slots, slotWorkspaceBytes(cap)), liveCatalogGrowth(slots, cap, round == null ? 0 : round.catalogBytes)));
	}
	private long nativeWorkspace(Round round, int slots) {
		return checkedAdd(InventoryItemBudget.workspaceUpperBound(round.handle.displayBytes()), checkedAdd(
			round.handle.stepValidationCost(slots).workspaceBytes(), PAGE_WORKSPACE_FIXED + InventoryCatalogAdmission.METADATA_BYTES + slots * 256L));
	}
	private static long nativeRetained(Round round) {
		InventoryItemCodec.@Nullable Catalog catalog = round.handle.nativeCatalog();
		// The admitted fixed round slab pays the journal's initial directories; variable chunk/definition capacity is separate.
		return (catalog == null ? 0 : catalog.modeledRetainedBytes()) + (round.journal == null ? 0 : round.journal.modeledRetainedBytes() - InventorySlotJournal.initialRetainedBytes());
	}
	private boolean readNative(Consumer consumer, int available, InventoryValidationBudget.Allowance allowance) {
		Round round = consumer.round;
		InventorySlotJournal journal = java.util.Objects.requireNonNull(round.journal);
		int bound = (int) Math.min(Math.min(available, physicalRemaining()), Math.max(0, physical.remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK - 1));
		bound = Math.min(bound, InventorySourceAccess.MAX_SLOTS - journal.endSlot());
		if (bound == 0) {
			var outcome = zeroAllowanceStep(consumer, allowance);
			if (outcome instanceof SourceAccess.StepOutcome.Deferred) { physicalDelay(consumer, scanReasons(available, round.handle.displayBytes()), available); return false; }
			round.last = ((SourceAccess.StepOutcome.Captured) outcome).result();
			round.sweepEvidence = InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP;
			if (round.last.availability() != CaptureResult.Availability.READABLE) retire(round, new Observation(round.last, List.of()));
			return true;
		}
		while (bound > 0 && stepMemoryUpperBound(consumer, bound) > memory.remaining()) bound--;
		if (bound == 0) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return false; }
		long oldRetained = nativeRetained(round);
		long growth = journal.batchAllocationUpperBound(bound) + checkedMultiply(bound, round.handle.nativeCatalogGrowthUpperBound());
		var retained = memory.tryReserve(checkedAdd(oldRetained, growth));
		if (retained.isEmpty()) return false;
		boolean transferred = false;
		try {
			var workspace = memory.tryReserve(nativeWorkspace(round, bound));
			if (workspace.isEmpty()) return false;
			try (var held = workspace.get()) {
				int display = round.handle.displayBytes();
				long provider = (bound + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK;
				var limits = Map.of(InventorySourceAccess.PHYSICAL, (long) bound, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, provider);
				var work = new LinkedHashMap<>(limits); work.put(ITEM_WORK, checkedMultiply(bound, InventoryItemBudget.workUpperBound(display)));
				var admitted = physical.tryReserve(work);
				if (admitted.isEmpty()) { physicalDelay(consumer, scanReasons(bound, display), -1); return false; }
				try (var grant = admitted.get()) {
					var step = new CostLedger(limits);
					var catalogAdmission = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(display), checkedMultiply(bound, InventoryCatalogAdmission.workUpperBound(display)));
					var validationCost = round.handle.stepValidationCost(bound);
					var validation = validation(validationCost, allowance, validationCost.workspaceBytes());
					if (validation.isEmpty()) return false;
					InventoryStep.Outcome outcome;
					try (var operation = validation.get()) { outcome = round.handle.stepInventory(step, catalogAdmission, operation.allowance); }
					finally { grant.commit(Map.of(InventorySourceAccess.PHYSICAL, step.used(InventorySourceAccess.PHYSICAL), InventorySourceAccess.PROBES, step.used(InventorySourceAccess.PROBES),
						InventorySourceAccess.PROVIDER_WORK, step.used(InventorySourceAccess.PROVIDER_WORK), ITEM_WORK, checkedAdd(checkedMultiply(step.used(InventorySourceAccess.PHYSICAL), InventoryItemBudget.freezeWorkUpperBound(display)), catalogAdmission.attemptedWork()))); }
					if (outcome instanceof InventoryStep.Outcome.Deferred) return false;
					InventoryStep result = ((InventoryStep.Outcome.Captured) outcome).step();
					round.last = result.metadata(); round.sweepEvidence = result.evidence(); physicalProgress(round);
					if (round.last.availability() != CaptureResult.Availability.READABLE) {
						retire(round, new Observation(round.last, List.of())); return true;
					}
					if (result.firstSlot() != journal.endSlot()) throw new IllegalStateException("native journal prefix mismatch");
					InventoryItemCodec.@Nullable Catalog catalog = round.handle.nativeCatalog();
					for (InventoryDomainCodec.@Nullable Item item : result.slots()) {
						InventoryItemCodec.@Nullable Encoded encoded = item == null ? null : java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(catalog).retainedEncoded(item.key()));
						journal.append(journal.endSlot(), encoded, item == null ? 0 : item.count());
					}
				}
			}
			return true;
		} finally {
			// Failed freezes can retain collision evidence. Evidence-only leases keep paying their catalogue, even after scanner release.
			if (!round.closed) {
				long measured = nativeRetained(round);
				retained.get().commit(measured);
				if (round.nativeMemory != null) round.nativeMemory.close();
				round.nativeMemory = retained.get(); transferred = true;
			}
			if (!transferred) retained.get().close();
		}
	}
	private int scanReasons(int requested, int displayBytes) {
		return (physicalRemaining() < requested ? Reason.PHYSICAL_SLOTS.bit() : 0)
			| providerReasons(1, (requested + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK)
			| (physical.remaining(ITEM_WORK) < checkedMultiply(requested, InventoryItemBudget.workUpperBound(displayBytes)) ? Reason.PROVIDER_WORK.bit() : 0);
	}
	private void logicalRefusal(Consumer consumer, int cap, int globalCap, int global) {
		int reasons = (logicalUsed.getOrDefault(consumer.subject, 0) >= cap ? Reason.LOGICAL_CLIENT_OR_TARGET.bit() : 0)
			| (global >= globalCap ? Reason.LOGICAL_GLOBAL.bit() : 0);
		delay(consumer, consumer.logicalDelay, reasons, "logical");
	}
	private static Observation unavailable(String stamp) {
		return new Observation(new CaptureResult(Optional.empty(), new CaptureResult.Coverage(stamp, 0, 0, OptionalLong.empty()),
			CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE, CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty()), List.of());
	}
	private static boolean zeroCostEmptyCompletion(CaptureResult result) {
		return result.availability() == CaptureResult.Availability.READABLE
			&& result.completeness() == CaptureResult.Completeness.COMPLETE
			&& result.coverage().expected().isPresent() && result.coverage().expected().getAsLong() == 0
			&& result.payload().isEmpty();
	}
	private SourceAccess.StepOutcome zeroAllowanceStep(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		consumer.zeroAdmissionRefused = false;
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) {
			consumer.zeroAdmissionRefused = true; physicalDelay(consumer, Reason.MEMORY.bit(), -1);
			return new SourceAccess.StepOutcome.Deferred();
		}
		try (var held = workspace.get()) {
			return zeroAllowanceAdmitted(consumer, allowance);
		}
	}
	private SourceAccess.StepOutcome zeroAllowanceAdmitted(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		consumer.zeroAdmissionRefused = false;
		Map<CostLedger.Counter, Long> limits = Map.of(InventorySourceAccess.PHYSICAL, 0L, InventorySourceAccess.PROBES, 1L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK);
		var admission = physical.tryReserve(limits);
		if (admission.isEmpty()) {
			consumer.zeroAdmissionRefused = true;
			physicalDelay(consumer, providerReasons(1, InventorySourceAccess.PROVIDER_CALL_WORK), -1);
			return new SourceAccess.StepOutcome.Deferred();
		}
		try (var grant = admission.get()) {
			CostLedger step = new CostLedger(limits);
			var validation = validation(consumer.round.handle.stepValidationCost(0), allowance);
			if (validation.isEmpty()) { consumer.zeroAdmissionRefused = true; physicalDelay(consumer, Reason.PROVIDER_WORK.bit() | Reason.MEMORY.bit(), -1); return new SourceAccess.StepOutcome.Deferred(); }
			SourceAccess.StepOutcome outcome;
			try (var operation = validation.get()) {
				outcome = consumer.round.handle.step(step, new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(effectiveDisplayCap()), 0), operation.allowance);
			}
			grant.commit(Map.of(InventorySourceAccess.PHYSICAL, 0L,
				InventorySourceAccess.PROBES, 1L - step.remaining(InventorySourceAccess.PROBES),
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK - step.remaining(InventorySourceAccess.PROVIDER_WORK)));
			return outcome;
		}
	}
	private boolean shareable(Consumer consumer, Round round) {
		// Observation evidence is local bookkeeping, never physical-source identity.
		if (round.closed || round.scanners == 0) return false;
		if (consumer.completedRound != null && (round.sequence <= consumer.completedRound.sequence || round.successor != null
			|| !round.key.equals(consumer.completedRound.key))) return false;
		if (round.startedTick < consumer.observationFloor || round.sequence < consumer.firstRound) return false;
		if (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE) return true;
		return !(consumer.subject instanceof TrackingSubject) || round.trackingObservation && round.startedTick == tick;
	}
	private void join(Consumer consumer, Round round) {
		Round previous = consumer.completedRound;
		if (previous != null && previous != round && previous.successor == null) {
			// One successor per physical observation, not one replacement allowance per consumer.
			// The lease keeps its data serviceable after the fast peer restarts; the slow peer consumes it
			// as its next observation without losing either its previous publication evidence or progress.
			previous.successor = round; round.predecessors.add(previous); round.references++; round.scanners++;
		}
		round.scanners++;
		if (previous != round) round.references++;
		consumer.round = round;
	}
	private void completed(Consumer consumer, Round round) {
		Round previous = consumer.completedRound;
		consumer.completedRound = round; // the scanning reference already pays for this consumer
		if (previous != null && previous != round) releaseReference(previous);
	}
	private void releaseScanning(Consumer consumer) {
		Round round = consumer.round; consumer.round = null;
		if (round == null) return;
		releaseScanner(round, round != consumer.completedRound);
	}
	private void releaseScanner(Round round, boolean ownsReference) {
		round.scanners--;
		if (round.scanners == 0) scanningRounds--;
		if (ownsReference) releaseReference(round);
		if (round.closed) return;
		if (round.scanners == 0) {
			// Completed publication needs only bounded local evidence, not decoded pages or quota history.
			try { round.handle.releaseCapturedData(); }
			finally {
				releaseDecodedData(round);
				rounds.remove(round.key, round);
			}
		}
	}
	/**
	 * Drops a round's decoded pages and quota history while its cumulative
	 * tightening summary stays available to consumers. A round without decoded
	 * slots has no walk position to resume and no page ticket to settle, so an
	 * active pass is ended here; otherwise a later tightening call would index
	 * a released page with stale bounds.
	 */
	private static void releaseDecodedData(Round round) {
		if (round.physicalDelay != null) round.physicalDelay.cancel();
		round.pages.forEach(RetainedMemoryLedger.Ticket::close); round.pages.clear();
		round.projectionMemory.forEach(RetainedMemoryLedger.Ticket::close); round.projectionMemory.clear();
		round.slots.clear(); round.paid.clear(); round.last = null;
		if (round.journal != null) { round.journal.close(); round.journal = null; }
		round.passActive = false; round.reprojectCursor = 0; round.reprojectEnd = 0;
		round.pagesChanged = false; round.pagesSettled = true;
	}
	private void releaseReference(Round round) {
		if (--round.references == 0) { closeRound(round); return; }
		if (!round.predecessors.isEmpty() && round.references == round.predecessors.size()) {
			// No consumer accepted or still scans this successor. A rejected/abandoned sweep must
			// not pin its predecessors forever or prevent a later fresh replacement.
			releasePredecessors(round);
		}
	}
	private void releasePredecessors(Round round) {
		Error fatal = null;
		for (Round previous : List.copyOf(round.predecessors)) {
			try { releaseHandoff(previous); }
			catch (Error failure) {
				if (fatal == null) fatal = failure;
				else if (failure != fatal) fatal.addSuppressed(failure);
			}
		}
		if (fatal != null) throw fatal;
	}
	private void releaseHandoff(Round previous) {
		Round successor = previous.successor; previous.successor = null;
		if (successor == null) return;
		successor.predecessors.remove(previous);
		releaseScanner(successor, true);
	}
	private void release(Consumer consumer) {
		Error fatal = null;
		try { releaseScanning(consumer); } catch (Error failure) { fatal = failure; }
		try {
			Round completed = consumer.completedRound; consumer.completedRound = null;
			if (completed != null) releaseReference(completed);
		} catch (Error failure) {
			if (fatal == null) fatal = failure;
			else if (failure != fatal) fatal.addSuppressed(failure);
		}
		try { releaseBindingEvidence(consumer); }
		catch (Error failure) {
			if (fatal == null) fatal = failure;
			else if (failure != fatal) fatal.addSuppressed(failure);
		}
		if (fatal != null) throw fatal;
	}
	private void releaseBindingEvidence(Consumer consumer) {
		var handle = consumer.bindingEvidence; consumer.bindingEvidence = null;
		var ticket = consumer.bindingMemory; consumer.bindingMemory = null;
		try { if (handle != null) handle.close(); }
		finally { if (ticket != null) ticket.close(); }
	}
	private void closeRound(Round round) {
		if (round.closed) return;
		round.closed = true;
		Error fatal = null;
		try { round.handle.close(); } catch (Error failure) { fatal = failure; }
		finally {
			round.memory.close(); round.catalogMemory.forEach(RetainedMemoryLedger.Ticket::close); round.catalogMemory.clear();
			if (round.nativeMemory != null) { round.nativeMemory.close(); round.nativeMemory = null; }
			rounds.remove(round.key, round); retainedRounds--;
			releaseDecodedData(round);
		}
		try { releaseHandoff(round); }
		catch (Error failure) {
			if (fatal == null) fatal = failure;
			else if (failure != fatal) fatal.addSuppressed(failure);
		}
		if (fatal != null) throw fatal;
	}
	/** Retire physical state, not a marker or a quota subject. Recovery requires a fresh restart. */
	public void retireInvalid(InventorySourceInput input) {
		var invalidRounds = new java.util.HashSet<Round>();
		var invalidAliases = new ArrayList<Consumer>();
		boolean external = input.target() instanceof nx.pingwheel.common.domain.Target.ExternalBlockTarget;
		for (Consumer consumer : consumers) {
			// The origin may no longer have a consumer, but aliases can still retain its evidence.
			if (external) {
				if (consumer.round != null && consumer.round.input.sameBinding(input)) invalidRounds.add(consumer.round);
				if (consumer.completedRound != null && consumer.completedRound.input.sameBinding(input)) invalidRounds.add(consumer.completedRound);
				if (consumer.input.sameBinding(input) && (consumer.round != null || consumer.completedRound != null || consumer.bindingEvidence != null))
					invalidAliases.add(consumer);
			} else if (consumer.input.sameBinding(input)) {
				if (consumer.round != null) invalidRounds.add(consumer.round);
				if (consumer.completedRound != null) invalidRounds.add(consumer.completedRound);
			}
		}
		Error fatal = null;
		for (Round round : invalidRounds) {
			try { retire(round, unavailable(input.viewKey())); }
			catch (Error failure) {
				if (fatal == null) fatal = failure;
				else if (failure != fatal) fatal.addSuppressed(failure);
			}
		}
		// A failed input-only alias probe invalidates that binding, not a still-readable peer's sweep.
		for (Consumer consumer : invalidAliases) if (!consumer.invalidated()) {
			try { consumer.discardObservation(); }
			catch (Error failure) {
				if (fatal == null) fatal = failure;
				else if (failure != fatal) fatal.addSuppressed(failure);
			}
		}
		if (fatal != null) throw fatal;
	}
	private void retire(Round round, Observation invalid) {
		if (round.closed) return;
		Error fatal = null;
		try { releasePredecessors(round); } catch (Error failure) { fatal = failure; }
		for (Consumer consumer : consumers) if (consumer.round == round || consumer.completedRound == round) {
			consumer.cancelDelays();
			consumer.cursor = 0; consumer.invalidObservation = invalid; consumer.pendingObservation = null;
			try { release(consumer); } // also releases a different in-progress round without leaking shared references
			catch (Error failure) {
				if (fatal == null) fatal = failure;
				else if (failure != fatal) fatal.addSuppressed(failure);
			}
		}
		if (fatal != null) throw fatal;
	}
	/**
	 * Bounded monotone tightening of one consumer's already captured display
	 * metadata. It never restarts the consumer, re-reads a source, recomputes a
	 * variant key or changes a count: retained display values are reprojected at
	 * the tighter ceiling through the registry-aware detached port, and the lazy
	 * snapshot/live catalogue ceiling is lowered so a later decode cannot
	 * re-expand. Work is bounded by a measured retained workspace reservation
	 * before any allocation and a fixed slot batch per call; {@code DEFERRED}
	 * means work remains and the caller must block data until it completes.
	 * An opaque extension tightener without a cost summary is never invoked.
	 * Its unread continuation becomes explicitly incomplete, while already decoded
	 * pages still tighten independently without a recapture or cursor reset.
	 */
	public Reprojection reprojectDisplays(Consumer consumer, int displayBytes) {
		int ceiling = clampDisplayBytes(displayBytes);
		if (consumer == null || consumer.closed || consumer.invalidated()) return Reprojection.unchanged(ceiling);
		var targets = new ArrayList<Round>(2);
		if (consumer.round != null && !consumer.round.closed) targets.add(consumer.round);
		if (consumer.completedRound != null && consumer.completedRound != consumer.round && !consumer.completedRound.closed)
			targets.add(consumer.completedRound);
		boolean pending = false, worked = false, resourceBlocked = false;
		for (Round round : targets) {
			if (round.handle.readMode() == InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL) {
				if (round.appliedBytes <= ceiling) continue;
				int refusal = tightenNative(round, ceiling);
				if (refusal != 0) { delay(consumer, consumer.projectionDelay, refusal, "projection"); resourceBlocked = pending = true; continue; }
				round.appliedBytes = ceiling; worked = true; continue;
			}
			// Lower the catalogue ceiling first so slots appended by an in-progress
			// sweep are already tightened and never need this walk.
			if (round.passActive && ceiling < round.passCeiling) {
				// A lower ceiling restarts the pass: earlier items may still exceed it.
				int refusal = tightenSource(round, ceiling);
				if (refusal != 0) { delay(consumer, consumer.projectionDelay, refusal, "projection"); resourceBlocked = pending = true; continue; }
				round.passCeiling = ceiling;
				round.reprojectCursor = 0;
				round.reprojectEnd = round.slots.size();
				worked = true;
			} else if (!round.passActive && round.appliedBytes > ceiling) {
				int refusal = tightenSource(round, ceiling);
				if (refusal != 0) { delay(consumer, consumer.projectionDelay, refusal, "projection"); resourceBlocked = pending = true; continue; }
				round.passActive = true;
				round.passCeiling = ceiling;
				round.reprojectCursor = 0;
				round.reprojectEnd = round.slots.size();
				worked = true;
			}
			if (round.passActive) {
				int end = Math.min(round.reprojectEnd, round.reprojectCursor + REPROJECT_BATCH);
				if (round.reprojectCursor < end) {
					worked = true;
					int refusal = reprojectSlots(round, round.passCeiling, end);
					if (refusal != 0) { delay(consumer, consumer.projectionDelay, refusal, "projection"); resourceBlocked = pending = true; continue; }
				}
				if (round.reprojectCursor < round.reprojectEnd) { pending = true; continue; }
				round.passActive = false;
				round.appliedBytes = round.passCeiling;
			}
			if (round.pagesChanged && !round.pagesSettled) {
				worked = true;
				if (!settlePages(round)) { delay(consumer, consumer.projectionDelay, Reason.MEMORY.bit(), "projection"); resourceBlocked = pending = true; }
			}
		}
		if (!resourceBlocked && consumer.projectionDelay.outstanding()) consumer.projectionDelay.progress(cycle(consumer));
		if (pending) return new Reprojection(ReprojectionStatus.DEFERRED, ceiling, changedKeys(targets), foldIds(targets));
		return new Reprojection(worked ? ReprojectionStatus.COMPLETED : ReprojectionStatus.UNCHANGED, ceiling,
			changedKeys(targets), foldIds(targets));
	}
	/** Catalogue projections are unique; repeated journal slots only keep ordinals and borrowed display references. */
	private int tightenNative(Round round, int ceiling) {
		@Nullable InventorySlotJournal journal = round.journal;
		InventoryItemCodec.@Nullable Catalog catalog = round.handle.nativeCatalog();
		if (journal != null && !claimDetachedItemWork(reprojectionCopyWork(journal.definitionCount()))) return Reason.PUBLICATION_WORK.bit();
		int refusal = tightenSource(round, ceiling, () -> {
			if (journal != null && catalog != null) {
				// The fixed round slab owns <=256 reference/key-set bookkeeping; no second parser is run.
				if (round.tightenedKeys == null) round.tightenedKeys = new java.util.LinkedHashSet<>();
				for (int ordinal = 0; ordinal < journal.definitionCount(); ordinal++) {
					var definition = journal.definition(ordinal);
					if (!definition.display().equals(catalog.retainedDisplay(definition.key()))) round.tightenedKeys.add(definition.key());
				}
				journal.refreshDisplays(catalog);
			}
		});
		if (refusal != 0) return refusal;
		// Old projection charges survive until every journal reference is refreshed. A refused swap keeps the larger old charge.
		long measured = nativeRetained(round);
		var replacement = memory.tryReserve(measured);
		if (replacement.isPresent()) {
			replacement.get().commit(measured);
			if (round.nativeMemory != null) round.nativeMemory.close();
			round.nativeMemory = replacement.get();
		}
		return 0;
	}
	/** The source can retain live compatibility/resident and detached snapshot catalogues. */
	private int tightenSource(Round round, int ceiling) {
		return tightenSource(round, ceiling, () -> {});
	}
	private int tightenSource(Round round, int ceiling, Runnable refreshBorrowed) {
		if (round.sourceProjectionUnsupported) return 0;
		// The summary query itself only combines a fixed number of cached records.
		// Admit that bookkeeping before calling any extension; never scan resident
		// data, discover slots, parse JSON or infer occupancy from a capacity here.
		var metadata = memory.tryReserve(InventoryDisplayProjection.METADATA_BYTES);
		if (metadata.isEmpty()) return Reason.MEMORY.bit();
		try (var heldMetadata = metadata.get()) {
			if (!claimDetachedItemWork(InventoryDisplayProjection.QUERY_WORK)) return Reason.PUBLICATION_WORK.bit();
			var summary = round.handle.reprojectionSummary(ceiling);
			if (summary == null || !summary.known()) { unsupportedSourceProjection(round); return 0; }
			var admission = memory.tryReserve(summary.workspaceBytes());
			if (admission.isEmpty()) return Reason.MEMORY.bit();
			try (var held = admission.get()) {
				if (!claimDetachedItemWork(summary.workUnits())) return Reason.PUBLICATION_WORK.bit();
				round.handle.tightenDisplay(ceiling);
				refreshBorrowed.run(); // old/new projection overlap remains under the admitted summary workspace
			}
		} catch (RuntimeException | LinkageError unavailable) {
			InventoryCaptureDiagnostics.warn("runtime/source-projection", round.input, round.key,
				() -> "round=" + round.sequence + " ceiling=" + ceiling + " tick=" + tick, unavailable);
			unsupportedSourceProjection(round);
		}
		return 0;
	}
	/** No guessed cost, provider call or fake empty inventory; preserve all paid decoded evidence. */
	private void unsupportedSourceProjection(Round round) {
		round.sourceProjectionUnsupported = true;
		if (round.physicalDelay != null) round.physicalDelay.cancel();
		for (Consumer consumer : consumers) if (consumer.round == round || consumer.completedRound == round)
			consumer.projectionDelay.cancel();
		CaptureResult previous = round.last;
		if (previous != null && previous.completeness() != CaptureResult.Completeness.CONTINUE) return;
		round.last = new CaptureResult(previous == null ? Optional.empty() : previous.payload(),
			previous == null ? new CaptureResult.Coverage(round.input.viewKey(), round.size(), round.size(), OptionalLong.empty()) : previous.coverage(),
			CaptureResult.Availability.READABLE, CaptureResult.Completeness.INCOMPLETE,
			previous == null ? CaptureResult.Consistency.UNKNOWN : previous.consistency(),
			previous == null ? Optional.empty() : previous.sourceVersion(), Optional.empty());
	}

	/** Zero succeeds; a resource reason defers before any parser or cursor movement. */
	private int reprojectSlots(Round round, int ceiling, int end) {
		long parserBound = 0, replacementBound = 0;
		long work = reprojectionCopyWork(end - round.reprojectCursor, round.slots.size());
		for (int index = round.reprojectCursor; index < end; index++) {
			InventoryDomainCodec.Item item = round.slots.get(index);
			if (item == null || item.displayJson() == null) continue;
			long bytes = utf8Bytes(item.displayJson());
			if (bytes > ceiling) {
				work = checkedAdd(work, reprojectionInputWork(Math.toIntExact(bytes)));
				parserBound = Math.max(parserBound, InventoryItemBudget.reprojectionWorkspaceUpperBound(Math.toIntExact(bytes)));
				replacementBound = checkedAdd(replacementBound, checkedAdd(checkedMultiply(bytes, 2L), 1024L));
			}
		}
		if (parserBound == 0) {
			round.reprojectCursor = end; // nothing in this batch exceeds the ceiling
			return 0;
		}
		var workspace = memory.tryReserve(checkedAdd(PAGE_WORKSPACE_FIXED, checkedAdd(parserBound, replacementBound)));
		if (workspace.isEmpty()) return Reason.MEMORY.bit();
		boolean transferred = false;
		try {
			if (!claimDetachedItemWork(work)) return Reason.PUBLICATION_WORK.bit();
			long measured = 0;
			for (int index = round.reprojectCursor; index < end; index++) {
				InventoryDomainCodec.Item item = round.slots.get(index);
				if (item == null || item.displayJson() == null || utf8Bytes(item.displayJson()) <= ceiling) continue;
				var retained = new InventoryItemCodec.Display(item.key().itemId(), item.label(), item.displayJson(), item.stripped());
				var projected = reprojector.reproject(retained, ceiling);
				if (projected.equals(retained)) continue;
				round.slots.set(index, new InventoryDomainCodec.Item(item.key(), item.count(),
					projected.label(), projected.displayJson(), item.aggregateRequired()));
				round.pagesChanged = true;
				round.pagesSettled = false;
				if (round.tightenedKeys == null) {
					round.tightenedKeys = new java.util.LinkedHashSet<>();
				}
				round.tightenedKeys.add(item.key());
				measured = checkedAdd(measured, checkedAdd(checkedMultiply(utf8Bytes(projected.displayJson()), 2L), 1024L));
			}
			workspace.get().commit(measured);
			if (measured > 0) { round.projectionMemory.add(workspace.get()); transferred = true; }
		} finally { if (!transferred) workspace.get().close(); }
		round.reprojectCursor = end;
		return 0;
	}

	/** Replaces the round's page tickets at the measured tighter cost, holding the new grant before releasing the old. */
	private boolean settlePages(Round round) {
		if (round.pages.isEmpty()) { round.pagesSettled = true; return true; } // released data retains no ticket to swap
		long measured = checkedMultiply(round.pageCount, PAGE_FIXED_BYTES);
		for (InventoryDomainCodec.Item item : round.slots) measured = checkedAdd(measured, itemRetainedBytes(item));
		// The latest encoded page stays referenced by round.last as retained
		// completion evidence even after its decoded display is tightened, so its
		// measured cost stays charged until the page tickets are next settled or
		// the round is released.
		if (round.last != null && round.last.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment page)
			for (var value : page.entries().values())
				measured = checkedAdd(measured, checkedAdd(checkedMultiply(value.size(), 2L), 256L));
		var replacement = memory.tryReserve(measured);
		if (replacement.isEmpty()) return false;
		replacement.get().commit(measured);
		round.pages.forEach(RetainedMemoryLedger.Ticket::close);
		round.projectionMemory.forEach(RetainedMemoryLedger.Ticket::close); round.projectionMemory.clear();
		round.pages.clear();
		round.pages.add(replacement.get());
		round.pagesSettled = true;
		return true;
	}

	private static List<InventoryScanner.Key> changedKeys(List<Round> rounds) {
		var keys = new java.util.LinkedHashSet<InventoryScanner.Key>();
		for (Round round : rounds) if (round.tightenedKeys != null) keys.addAll(round.tightenedKeys);
		return List.copyOf(keys);
	}

	private static List<String> foldIds(List<Round> rounds) {
		return List.of();
	}

	/** Mirrors the page commit's measured formula over decoded metadata. */
	private static long itemRetainedBytes(InventoryDomainCodec.Item item) {
		if (item == null) return 256L;
		long encoded = 8L + (item.displayJson() == null ? 0L : 4L + utf8Bytes(item.displayJson()));
		encoded = checkedAdd(encoded, checkedAdd(2L, utf8Bytes(item.key().itemId())));
		encoded = checkedAdd(encoded, checkedAdd(2L, utf8Bytes(item.key().componentsKey())));
		encoded = checkedAdd(encoded, checkedAdd(2L, utf8Bytes(item.label())));
		encoded = checkedAdd(encoded, 2L); // display-present and stripped flags
		return checkedAdd(checkedMultiply(encoded, 2L), 256L);
	}

	private void clearLogical(boolean preview) {
		logicalUsed.keySet().removeIf(s -> (s instanceof PreviewSubject) == preview);
		var iterator = logicalMemory.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next(); if ((entry.getKey() instanceof PreviewSubject) == preview) { entry.getValue().close(); iterator.remove(); }
		}
	}
	@Override public void close() { for (Consumer consumer : List.copyOf(consumers)) consumer.close(); clearLogical(true); clearLogical(false); }
}
