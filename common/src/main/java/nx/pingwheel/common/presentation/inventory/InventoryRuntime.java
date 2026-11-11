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
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Unmodifiable;

/** One server-lifetime owner of physical rounds, logical coverage and retained admission. */
public final class InventoryRuntime implements AutoCloseable {
	private static final nx.pingwheel.common.diagnostics.DiagnosticChannel PREFLIGHT = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-preflight", java.time.Duration.ofSeconds(10));
	private static final int MAX_CONSUMERS = 1024, MAX_ROUNDS = 64;
	private static final int MAX_RETAINED_ROUNDS = MAX_ROUNDS * 2; // bounded replacement headroom for completed evidence leases
	private static final long CONSUMER_MEMORY = 4096;
	// Empty round state only. Subject/key indexes acquire tiered ownership before their first insertion.
	private static final long ROUND_MEMORY = 4096 + InventorySlotJournal.initialRetainedBytes();
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
	public record Observation(CaptureResult result, @Unmodifiable List<? extends @Nullable InventoryResourceValue> slots, InventoryStep.SweepEvidence inventorySweepEvidence) {
		public Observation { slots = java.util.Collections.unmodifiableList(new ArrayList<>(slots)); }
		public Observation(CaptureResult result, List<? extends @Nullable InventoryResourceValue> slots) { this(result, slots, InventoryStep.SweepEvidence.NONE); }
	}
	/** Server-thread source resolver; every new observation receives the authoritative display ceiling. */
	@FunctionalInterface
	public interface SourceResolver {
		Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes);
		default InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryValidationBudget.NONE; }
		/** Legacy resolvers keep their complete profile; staged production resolvers select only the paid root probe. */
		default InventoryValidationBudget.Cost resolutionProbeCost(InventorySourceInput input) { return resolutionValidationCost(input); }
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
		@Unmodifiable List<InventoryScanner.Key> changedKeys, @Unmodifiable List<String> foldIds) {
		public Reprojection {
			changedKeys = List.copyOf(changedKeys);
			foldIds = List.copyOf(foldIds);
		}
		static Reprojection unchanged(int displayBytes) {
			return new Reprojection(ReprojectionStatus.UNCHANGED, displayBytes, List.of(), List.of());
		}
	}
	public final class Consumer implements AutoCloseable {
		/** Diagnostic-only input recovery transition, bounded by this consumer's lifecycle. */
		private boolean inputRejectionReported;
		private @Nullable InventoryFailureReason currentReason;
		private final long diagnosticId = ++consumerSequence;
		private final InventoryBudgetDelayState admissionDelay, probeDelay, logicalDelay, projectionDelay;
		private boolean serviceableDemand = true;
		private boolean scanning = true;
		private boolean zeroAdmissionRefused;
		private boolean publicationRequired;
		private final InventorySourceInput input;
		private final Subject subject;
		private final RetainedMemoryLedger.Ticket memory;
		private @Nullable Round round;
		private @Nullable Round completedRound;
		private @Nullable Association scanningAssociation, completedAssociation;
		private @Nullable Round successorEntitlement;
		// A canonical source alias shares data, not the original input's lease or physical-binding authority.
		private InventorySourceAccess.@Nullable InventoryHandle bindingEvidence;
		private RetainedMemoryLedger.@Nullable Ticket bindingMemory;
		private @Nullable Observation invalidObservation;
		private @Nullable Observation pendingObservation;
		private int cursor;
		private boolean closed;
		private long observationFloor;
		private long firstRound;
		private long lastAssociatedSequence, recoveryFloor;
		private Consumer(InventorySourceInput input, Subject subject, RetainedMemoryLedger.Ticket memory) {
			this.input = input; this.subject = subject; this.memory = memory;
			Module module = subject instanceof PreviewSubject ? Module.INVENTORY_PREVIEW : Module.INVENTORY_TRACKING;
			admissionDelay = new InventoryBudgetDelayState(module); probeDelay = new InventoryBudgetDelayState(module);
			logicalDelay = new InventoryBudgetDelayState(module); projectionDelay = new InventoryBudgetDelayState(module);
			observationFloor = tick;
			firstRound = roundSequence + 1;
		}
		public InventorySourceInput input() { return input; }
		public @Nullable InventoryFailureReason currentReason() { return currentReason; }
		/** Existing paid progress only: no provider, world, source-count or content callback. */
		public Progress progress() { return new Progress(round == null ? 0 : round.sequence, cursor, round == null ? 0 : round.size()); }
		void requirePublicationEvidence() { publicationRequired = true; }
		public boolean invalidated() { return invalidObservation != null; }
		private boolean scopeTransition;
		public boolean scopeTransition() { return scopeTransition; }
		public void recomputeScope() {
			release(this); invalidObservation = null; pendingObservation = null; scopeTransition = false; cursor = 0;
			observationFloor = tick; firstRound = recoveryFloor = roundSequence + 1; scanning = true;
		}
		/** No source access: suspension preserves paid replay/evidence, but cannot reserve future observations. */
		void serviceableDemand(boolean active) {
			if (serviceableDemand != active) {
				removeScanDemand(this);
				serviceableDemand = active;
				addScanDemand(this);
			}
			if (!active) { releaseEntitlement(this); cancelDelays(); }
		}
		void cancelDelays() {
			admissionDelay.cancel(); probeDelay.cancel(); logicalDelay.cancel(); projectionDelay.cancel();
		}
		/** Terminal projection can retain a physically unfinished round without keeping it open to new consumers. */
		void stopScanning() { removeScanDemand(this); scanning = false; admissionDelay.cancel(); logicalDelay.cancel(); }
		/** Pins the complete observation actually accepted for publication, not a rejected or overflowing sweep. */
		public void retainCompleted() {
			if (closed || invalidObservation != null || round == null || round.last == null
				|| round.last.completeness() != CaptureResult.Completeness.COMPLETE || cursor != round.size())
				throw new IllegalStateException("no complete inventory observation");
			completed(this, round);
		}
		public void restart() {
			cancelDelays();
			if (invalidObservation != null || pendingObservation != null) recoveryFloor = roundSequence + 1;
			if (round != null && round.sweep == null) rounds.remove(round.lookupKey, round); // new observation evidence, not source identity
			releaseScanning(this); invalidObservation = null; pendingObservation = null; cursor = 0; observationFloor = tick; firstRound = 0;
			scanning = true;
			if (completedRound == null) releaseBindingEvidence(this);
		}
		/** Releases this consumer's invalid data/evidence without invalidating unrelated physical rounds. */
		public void discardObservation() {
			cancelDelays();
			recoveryFloor = roundSequence + 1;
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
	@NotNullByDefault
	public record Progress(long observationSequence, int consumedSlots, int capturedSlots) {}
	private static final class Round {
		InventoryBudgetDelayState physicalDelay;
		Module physicalModule;
		final SourceKey key;
		final LookupKey lookupKey;
		final InventorySharedRead.Policy policy;
		boolean sealed;
		long successorSequence; // provenance only: never pins a successor or predecessor's source/journal
		final @Nullable PooledSweep sweep;
		final InventorySourceInput input;
		final InventorySourceAccess.@Nullable InventoryHandle handle;
		final @Nullable AdmittedSource source;
		final RetainedMemoryLedger.Ticket memory;
		final long startedTick;
		final long sequence;
		boolean trackingObservation;
		InventorySourceAccess.@Nullable Preparation preparation;
		final List<@Nullable InventoryResourceValue> slots = new ArrayList<>();
		@Nullable InventorySlotJournal journal;
		@Nullable InventoryFailureReason terminalReason;
		RetainedMemoryLedger.@Nullable Ticket nativeMemory;
		InventoryStep.SweepEvidence sweepEvidence = InventoryStep.SweepEvidence.NONE;
		final Map<Subject, Integer> paid = new LinkedHashMap<>();
		RetainedMemoryLedger.@Nullable Ticket bookkeepingMemory;
		int paidCapacity, tightenedCapacity;
		final List<RetainedMemoryLedger.Ticket> pages = new ArrayList<>();
		final List<RetainedMemoryLedger.Ticket> catalogMemory = new ArrayList<>();
		final List<RetainedMemoryLedger.Ticket> projectionMemory = new ArrayList<>();
		long catalogBytes;
		@Nullable CaptureResult last;
		@Nullable Observation failureObservation;
		int references;
		int incomingEntitlements; // included in references/scanners, but neither an actual scan nor an accepted completion
		int scanners; // journal/replay owners plus bounded physical handoff leases, independent of live demand
		int activeScans, activeTracking; // live pooled scan demand, excluding terminal/audience-paused associations and handoff holds
		int appliedBytes; // ceiling fully walked over this round's decoded slots
		int passCeiling; // target of the active tightening pass
		int reprojectCursor; // bounded tightening walk position over decoded slots
		int reprojectEnd; // slots.size() frozen at pass start; later slots are already tightened
		boolean passActive;
		boolean sourceProjectionUnsupported; // retained decoded pages remain usable; no further opaque decode/capture
		int pageCount; // page commits ever appended, so a settled swap keeps their fixed overhead
		boolean pagesChanged;
		boolean pagesSettled;
		@Nullable Set<InventoryScanner.Key> tightenedKeys;
		@Nullable Round successor;
		final Set<Round> predecessors = new java.util.LinkedHashSet<>();
		boolean closed;
		Round(SourceKey key, InventorySourceInput input, InventorySourceAccess.InventoryHandle handle, AdmittedSource source, RetainedMemoryLedger.Ticket memory, long tick, long sequence, boolean tracking, int displayBytes, InventorySharedRead.Policy policy) {
			this.key = key; this.input = input; this.handle = handle; this.source = source; this.memory = memory; startedTick = tick; this.sequence = sequence; trackingObservation = tracking;
			this.policy = policy; lookupKey = new ExactKey(key, policy); sweep = null;
			this.appliedBytes = displayBytes;
			journal = handle.readMode() == InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL ? new InventorySlotJournal() : null;
		}
		Round(SourceKey key, InventorySourceInput input, PooledKey lookupKey, PooledSweep sweep,
			RetainedMemoryLedger.Ticket memory, long tick, long sequence, int displayBytes) {
			this.key = key; this.input = input; this.lookupKey = lookupKey; this.sweep = sweep; policy = lookupKey.policy();
			handle = null; source = null; this.memory = memory; startedTick = tick; this.sequence = sequence;
			appliedBytes = displayBytes; journal = new InventorySlotJournal();
		}
		int size() { return journal == null ? slots.size() : journal.size(); }
		boolean empty() { return size() == 0; }
		@Nullable InventoryResourceValue read(int slot) { return journal == null ? slots.get(slot) : journal.readResource(slot); }
	}
	@NotNullByDefault
	private sealed interface LookupKey permits ExactKey, PooledKey {}
	@NotNullByDefault
	private record ExactKey(SourceKey source, InventorySharedRead.Policy policy) implements LookupKey {}
	@NotNullByDefault
	private record PooledKey(InventorySharedRead.Graph graph, InventorySharedRead.Policy policy,
		@Nullable UUID owner, @Nullable nx.pingwheel.common.domain.BlockFace face) implements LookupKey {}
	/** No source, owner supplier or private cursor belongs to a pooled observation. */
	@NotNullByDefault
	private static final class PooledSweep {
		final InventorySharedRead.Proof proof;
		InventoryItemCodec.@Nullable Catalog catalog;
		PooledSweep(InventorySharedRead.Proof proof) { this.proof = proof; }
	}
	// Association records, lease, <=65 complete page profiles, map/key/proof reference overlap and entitlement bookkeeping.
	private static final long LEASE_MEMORY = 16384, EVIDENCE_MEMORY = 256;
	/** Two searches over actual admitted graphs/participants, not maximum-sized global slabs. */
	private InventoryValidationBudget.Cost poolBookkeepingCost(InventorySharedRead.Port port) {
		var proof = port.proof();
		long shapeInspection = 4096 + proof.members().size() * 8192L;
		for (Round round : liveRounds) if (round.sweep != null) shapeInspection = checkedAdd(shapeInspection, round.sweep.proof.members().size() * 8192L);
		if (!claimDetachedItemWork(shapeInspection)) throw InventoryValidationBudget.itemResolutionDeferred(InventoryFailureReason.ITEM_WORK_LIMIT);
		long incoming = graphComparisons(proof);
		long work = checkedAdd(16384, checkedMultiply(incoming, 128));
		for (Round round : liveRounds) if (round.sweep != null) {
			long existing = graphComparisons(round.sweep.proof);
			work = checkedAdd(work, checkedMultiply(64, checkedAdd(incoming, existing)));
			work = checkedAdd(work, checkedMultiply(consumers.size(), 4096L));
		}
		return new InventoryValidationBudget.Cost(16384, 16384, work);
	}
	private static long graphComparisons(InventorySharedRead.Proof proof) {
		long units = proof.members().size() + proof.graph().alias().length() + proof.graph().dimension().length() + proof.graph().provider().length()
			+ proof.graph().layout().layoutId().length() + proof.graph().layout().layoutData().length();
		for (var member : proof.graph().layout().members()) {
			units += member.blockId().length() + member.blockEntityId().length() + member.role().length() + 16L;
			for (var segment : member.segments()) units += segment.visibleSlots().size() + segment.fieldPath().length() + segment.schemaId().length() + 16L;
		}
		return units;
	}
	private static int bookkeepingCapacity(int required) {
		if (required == 0) return 0;
		int capacity = 4;
		while (capacity < required) capacity *= 2;
		return capacity;
	}
	/** Replacement cost includes full new allocation, initialization/copy and old/new overlap. */
	private boolean growBookkeeping(Consumer consumer, Round round, int paidRequired, int keysRequired) {
		if (paidRequired < 0 || paidRequired > MAX_CONSUMERS || keysRequired < 0 || keysRequired > InventorySourceAccess.MAX_SLOTS)
			throw new IllegalArgumentException("round bookkeeping shape bound");
		int paid = bookkeepingCapacity(paidRequired), keys = bookkeepingCapacity(keysRequired);
		if (paid <= round.paidCapacity && keys <= round.tightenedCapacity) return true;
		paid = Math.max(paid, round.paidCapacity); keys = Math.max(keys, round.tightenedCapacity);
		long bytes = checkedAdd(256, checkedAdd(checkedMultiply(paid, 160L), checkedMultiply(keys, 128L)));
		var retained = memory.tryReserve(bytes);
		if (retained.isEmpty()) { consumer.currentReason = InventoryFailureReason.MEMORY_LIMIT; return false; }
		try {
			if (!claimDetachedItemWork(checkedAdd(4096, checkedMultiply(paid + (long) keys + round.paid.size()
				+ (round.tightenedKeys == null ? 0 : round.tightenedKeys.size()), 4096L)))) {
				consumer.currentReason = InventoryFailureReason.ITEM_WORK_LIMIT; return false;
			}
			retained.get().commit(bytes);
			if (round.bookkeepingMemory != null) round.bookkeepingMemory.close();
			round.bookkeepingMemory = retained.get(); round.paidCapacity = paid; round.tightenedCapacity = keys;
			return true;
		} finally { if (round.bookkeepingMemory != retained.get()) retained.get().close(); }
	}
	/** Exact input authority and metadata ownership, independently retained by scans, completions and publication cuts. */
	@NotNullByDefault
	private static final class BindingLease {
		final InventorySourceInput input;
		final InventorySourceAccess.InventoryHandle handle;
		final InventorySharedRead.Port port;
		final RetainedMemoryLedger.Ticket memory;
		final InventoryValidationBudget.Cost @Unmodifiable [] pageCosts;
		int references = 1;
		BindingLease(InventorySourceInput input, InventorySourceAccess.InventoryHandle handle, InventorySharedRead.Port port, RetainedMemoryLedger.Ticket memory) {
			this.input = input; this.handle = handle; this.port = port; this.memory = memory;
			pageCosts = new InventoryValidationBudget.Cost[InventorySourceAccess.MAX_STEP + 1];
			for (int slots = 0; slots < pageCosts.length; slots++) pageCosts[slots] = pooledValidationCost(port, slots);
		}
		void retain() { if (references == 0) throw new IllegalStateException("released inventory binding"); references++; }
		void release() { if (--references == 0) try { handle.close(); } finally { memory.close(); } }
	}
	@NotNullByDefault
	private record Association(Round round, BindingLease lease, InventorySharedRead.Proof expected) {
		Association retain() { lease.retain(); return new Association(round, lease, expected); }
		void close() { lease.release(); }
	}
	/** Exact historical proof/lease only; validation has no runtime retirement or journal side effects. Caller admits its cost. */
	@ApiStatus.Internal
	@NotNullByDefault
	public static final class Evidence implements AutoCloseable {
		private final BindingLease lease;
		private final InventorySharedRead.Proof expected;
		private final RetainedMemoryLedger.Ticket memory;
		private boolean closed;
		private Evidence(Association association, RetainedMemoryLedger.Ticket memory) {
			lease = association.lease; expected = association.expected; this.memory = memory; lease.retain();
		}
		public InventoryValidationBudget.Cost validationCost() { return lease.port.validationCost(); }
		public InventorySharedRead.Check validate(InventoryValidationBudget.Allowance allowance) {
			if (closed) return InventorySharedRead.Check.UNAVAILABLE;
			try { return lease.port.validate(expected, allowance); }
			catch (InventoryItemBudget.CaptureLimitException deferred) { return InventorySharedRead.Check.DEFERRED; }
			catch (RuntimeException | LinkageError unavailable) {
				@Nullable var providerLimit = InventoryCaptureDiagnostics.providerLimit(unavailable);
				if (providerLimit != null) throw providerLimit;
				InventoryCaptureDiagnostics.warn("runtime/publication-validate", "deny-publication", lease.input, null,
					() -> "stage=historical-metadata proofRoute=" + expected.graph().route() + " alias=" + expected.graph().alias(), unavailable);
				return InventorySharedRead.Check.UNAVAILABLE;
			}
		}
		@Override public void close() { if (!closed) { closed = true; try { lease.release(); } finally { memory.close(); } } }
	}
	private final SourceResolver resolver;
	private final DisplayReprojector reprojector;
	private final BudgetDelaySink sink;
	private final ServerInventoryMonitor monitor;
	private @Nullable SelectionReadScope selectionReadScope;
	/** Fixed-size enabled-only metadata, scoped to the actual witness call and its immutable ledger identity. */
	@NotNullByDefault
	private static final class SelectionReadScope {
		final @Nullable SelectionReadScope parent;
		final CostLedger ledger;
		final long generation, before;
		long nestedCharged;
		SelectionReadScope(@Nullable SelectionReadScope parent, CostLedger ledger, long generation) {
			this.parent = parent; this.ledger = ledger; this.generation = generation;
			before = ledger.used(InventorySourceAccess.PHYSICAL);
		}
	}
	private long consumerSequence;
	private final List<Consumer> consumers = new ArrayList<>();
	private final List<OperationGrant> operationGrants = new ArrayList<>(2);
	private final Map<LookupKey, Round> rounds = new LinkedHashMap<>();
	private final List<Round> liveRounds = new ArrayList<>();
	private final boolean certifiedPooling;
	private InventorySharedRead.Policy policy = new InventorySharedRead.Policy(true, true);
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
		this(resolver, memoryBytes, reprojector, sink, false);
	}
	/** The production Backend supplies final per-cut publication authority before using certified pooling. */
	@ApiStatus.Internal
	public InventoryRuntime(SourceResolver resolver, long memoryBytes, DisplayReprojector reprojector, BudgetDelaySink sink, boolean certifiedPooling) {
		this(resolver, memoryBytes, reprojector, sink, certifiedPooling, new ServerInventoryMonitor());
	}
	public InventoryRuntime(SourceResolver resolver, long memoryBytes, DisplayReprojector reprojector, BudgetDelaySink sink,
		boolean certifiedPooling, ServerInventoryMonitor monitor) {
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.reprojector = java.util.Objects.requireNonNull(reprojector, "reprojector");
		this.sink = sink == null ? BudgetDelaySink.NONE : sink;
		this.certifiedPooling = certifiedPooling;
		this.monitor = java.util.Objects.requireNonNull(monitor, "monitor");
		memory = new RetainedMemoryLedger(memoryBytes);
	}
	public RetainedMemoryLedger memory() { return memory; }
	public ServerInventoryMonitor monitor() { return monitor; }
	/** One bounded scheduling opportunity held out of the existing ledgers. */
	@NotNullByDefault
	public record OperationBudget(int physicalSlots, int probeCalls, long providerWork, long itemWork, long workspaceBytes) {
		public OperationBudget {
			if (physicalSlots < 0 || physicalSlots > InventorySourceAccess.MAX_STEP || probeCalls < 0 || probeCalls > 256
				|| providerWork < 0 || itemWork < 0 || workspaceBytes < 0) throw new IllegalArgumentException("inventory operation bound");
		}
	}
	@NotNullByDefault
	public final class OperationGrant implements AutoCloseable {
		private final CostLedger.Ticket work;
		private final RetainedMemoryLedger.Ticket workspace;
		private final long reservedTick = tick;
		private final Thread thread = Thread.currentThread();
		private boolean closed;
		private OperationGrant(CostLedger.Ticket work, RetainedMemoryLedger.Ticket workspace) { this.work = work; this.workspace = workspace; }
		/** Releases only unused protection; the synchronous operation pays through its normal admission path. */
		public <T> T run(java.util.function.Supplier<T> operation) {
			if (closed || thread != Thread.currentThread() || reservedTick != tick) throw new IllegalStateException("inventory operation opportunity is not current");
			close();
			return operation.get();
		}
		@Override public void close() { if (!closed) { closed = true; operationGrants.remove(this); try { work.close(); } finally { workspace.close(); } } }
	}
	public OperationBudget preflightBudget() { return new OperationBudget(0, 1, InventorySourceAccess.PROVIDER_CALL_WORK, 0, authorityWorkspaceBytes()); }
	public OperationBudget stepBudget(Consumer consumer, int slots) {
		if (slots < 1 || slots > InventorySourceAccess.MAX_STEP) throw new IllegalArgumentException("inventory operation step bound");
		@Nullable Round round = consumer.round;
		int cap = round == null ? effectiveDisplayCap() : displayBytes(round);
		InventoryValidationBudget.Cost validationCost = round == null ? resolver.resolutionProbeCost(consumer.input)
			: round.sweep == null ? round.handle.stepValidationCost(slots) : java.util.Objects.requireNonNull(consumer.scanningAssociation).lease.pageCosts[slots];
		long itemWork = checkedAdd(validationCost.workUnits(), checkedMultiply(slots,
			InventoryItemBudget.workUpperBound(cap, round == null ? slots - 1 : nativeEntries(round, slots))));
		if (round != null && round.journal != null) {
			if (round.sweep == null && round.handle.pendingNativeValidation()) itemWork = validationCost.workUnits();
			boolean reading = consumer.cursor >= round.size() && (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE)
				&& (round.sweep != null || !round.handle.pendingNativeValidation());
			int capacity = round.sweep == null ? InventorySourceAccess.MAX_SLOTS : round.sweep.proof.graph().slots();
			int appended = reading ? Math.min(slots, capacity - round.journal.endSlot()) : 0;
			reading &= appended > 0;
			itemWork = checkedAdd(itemWork, journalStepWorkUpperBound(consumer, slots, appended, reading));
		}
		return new OperationBudget(slots, 4, (slots + 4L) * InventorySourceAccess.PROVIDER_CALL_WORK,
			itemWork,
			Math.max(authorityWorkspaceBytes(), stepMemoryUpperBound(consumer, slots)));
	}
	public InventoryDecision<OperationGrant> reserveOperation(OperationBudget budget) {
		if (operationGrants.size() == 2) return deferred(InventoryFailureReason.REQUEST_CAPACITY);
		var workspace = memory.tryReserve(checkedAdd(1024, budget.workspaceBytes()));
		if (workspace.isEmpty()) return deferred(InventoryFailureReason.MEMORY_LIMIT);
		var admission = physical.tryReserveDecision(Map.of(InventorySourceAccess.PHYSICAL, (long) budget.physicalSlots(),
			InventorySourceAccess.PROBES, (long) budget.probeCalls(), InventorySourceAccess.PROVIDER_WORK, budget.providerWork(), ITEM_WORK, budget.itemWork()));
		if (!admission.accepted()) { workspace.get().close(); return deferred(counterReason(java.util.Objects.requireNonNull(admission.limitingCounter()))); }
		var grant = new OperationGrant(java.util.Objects.requireNonNull(admission.ticket()), workspace.get());
		operationGrants.add(grant); return InventoryDecision.accept(grant);
	}
	private void recordReadAttempts(long generation, CostLedger ledger, long attempted, long charged) {
		if (!monitor.enabled() || generation != monitor.collectionGeneration()) return;
		for (@Nullable SelectionReadScope scope = selectionReadScope; scope != null; scope = scope.parent)
			if (scope.ledger == ledger && scope.generation == generation) scope.nestedCharged += charged;
		monitor.sharedCount(Counter.READ_ATTEMPTS, attempted);
	}
	/** Callback-free fixed scalar reads; called after publication reservations have settled. */
	void sampleMonitor() {
		if (!monitor.enabled()) return;
		monitor.memory(memory);
		monitor.sharedGauge(Gauge.CONSUMERS, consumers.size());
		monitor.sharedGauge(Gauge.ACTIVE_OBSERVATIONS, scanningRounds);
		if (settings == null || physical == null) return;
		// This initialized Runtime observes the source-step ledger, including ticks with no new attempts.
		monitor.sharedCount(Counter.READ_ATTEMPTS, 0);
		if (monitor.enabled(Lane.PREVIEW)) monitor.budget(Lane.PREVIEW, Budget.LOGICAL_SLOTS, previewPeriodId(),
			settings.getPreview().effectiveMaxSlotsServer(), previewGlobal, (long) settings.getPreview().effectiveMaxSlotsServer() - previewGlobal);
		if (monitor.enabled(Lane.TRACKING)) monitor.budget(Lane.TRACKING, Budget.LOGICAL_SLOTS, trackingPeriodId(),
			settings.getTracking().effectiveMaxSlotsServer(), trackingGlobal, (long) settings.getTracking().effectiveMaxSlotsServer() - trackingGlobal);
		monitor.sharedBudget(Budget.PHYSICAL_SLOTS, tick, physical, InventorySourceAccess.PHYSICAL, physicalRemaining());
		monitor.sharedBudget(Budget.PROCESSING_CHARGE, tick, physical, ITEM_WORK, physical.remaining(ITEM_WORK));
		monitor.sharedBudget(Budget.CAPTURE_READS, tick, physical, CAPTURE_READS, physical.remaining(CAPTURE_READS));
		monitor.sharedBudget(Budget.PROBES, tick, physical, InventorySourceAccess.PROBES, physical.remaining(InventorySourceAccess.PROBES));
		monitor.sharedBudget(Budget.PROVIDER_WORK, tick, physical, InventorySourceAccess.PROVIDER_WORK, physical.remaining(InventorySourceAccess.PROVIDER_WORK));
	}
	@NotNullByDefault
	public enum EvidenceScope { SCANNING, COMPLETED }
	/** Pins the explicitly selected historical association, never an implicit latest-source fallback or a whole round. */
	@ApiStatus.Internal
	public Optional<Evidence> retainEvidence(Consumer consumer, EvidenceScope scope) {
		@Nullable Association association = scope == EvidenceScope.COMPLETED ? consumer.completedAssociation : consumer.scanningAssociation;
		if (consumer.closed || consumer.invalidated() || association == null) return Optional.empty();
		var admission = memory.tryReserve(EVIDENCE_MEMORY);
		if (admission.isEmpty()) { InventoryCaptureDiagnostics.limit(consumer.input, InventoryDiagnosticSite.PUBLICATION, InventoryOperation.VALIDATE,
			InventoryFailureReason.MEMORY_LIMIT, InventoryOutcome.DEFERRED, "publication-evidence", EVIDENCE_MEMORY, memory.retained(), memory.cap(), null); return Optional.empty(); }
		admission.get().commit(EVIDENCE_MEMORY);
		return Optional.of(new Evidence(association, admission.get()));
	}
	/** Explicit historical cut ownership for certified and exact legacy observations alike. */
	public Optional<InventoryPublicationEvidence> publicationEvidence(Consumer consumer, EvidenceScope scope) {
		if (consumer.closed || consumer.invalidated()) return Optional.empty();
		@Nullable Association association = scope == EvidenceScope.COMPLETED ? consumer.completedAssociation : consumer.scanningAssociation;
		if (association != null) return retainEvidence(consumer, scope).map(PublicationEvidence::new);
		@Nullable Round round = scope == EvidenceScope.COMPLETED ? consumer.completedRound : consumer.round;
		if (round == null || round.closed || round.handle == null) return Optional.empty();
		var primary = round.handle.publicationEvidence();
		if (primary.isEmpty() || consumer.bindingEvidence == null) return primary;
		var binding = consumer.bindingEvidence.publicationEvidence();
		if (binding.isEmpty()) { primary.get().close(); return Optional.empty(); }
		return Optional.of(new CombinedEvidence(primary.get(), binding.get()));
	}
	@NotNullByDefault
	private static final class PublicationEvidence implements InventoryPublicationEvidence {
		final Evidence evidence;
		int references = 1;
		PublicationEvidence(Evidence evidence) { this.evidence = evidence; }
		@Override public InventoryValidationBudget.Cost validationCost() { return evidence.validationCost(); }
		@Override public int probeCalls() { return 1; }
		@Override public InventorySharedRead.Check validate(InventoryValidationBudget.Allowance allowance) { return evidence.validate(allowance); }
		@Override public InventoryPublicationEvidence retain() { if (references == 0) throw new IllegalStateException("released publication evidence"); references++; return this; }
		@Override public InventorySharedRead.Proof proof() { return evidence.expected; }
		@Override public void close() { if (references != 0 && --references == 0) evidence.close(); }
	}
	@NotNullByDefault
	private static final class CombinedEvidence implements InventoryPublicationEvidence {
		final InventoryPublicationEvidence primary, binding;
		final InventoryValidationBudget.Cost cost;
		int references = 1;
		CombinedEvidence(InventoryPublicationEvidence primary, InventoryPublicationEvidence binding) {
			this.primary = primary; this.binding = binding; cost = primary.validationCost().plus(binding.validationCost());
		}
		@Override public InventoryValidationBudget.Cost validationCost() { return cost; }
		@Override public boolean scopeTransition() { return primary.scopeTransition() || binding.scopeTransition(); }
		@Override public int probeCalls() { return primary.probeCalls() + binding.probeCalls(); }
		@Override public InventorySharedRead.@Nullable Proof proof() { return primary.proof(); }
		@Override public boolean sameProof(InventoryPublicationEvidence other) { return primary.sameProof(other instanceof CombinedEvidence combined ? combined.primary : other); }
		@Override public InventorySharedRead.Check validate(InventoryValidationBudget.Allowance allowance) {
			var check = primary.validate(allowance); return check == InventorySharedRead.Check.VALID ? binding.validate(allowance) : check;
		}
		@Override public InventoryPublicationEvidence retain() { if (references == 0) throw new IllegalStateException("released publication evidence"); references++; return this; }
		@Override public void close() { if (references != 0 && --references == 0) try { primary.close(); } finally { binding.close(); } }
	}
	/** Applies a latched failure only outside publication traversal. */
	void publicationFailed(Consumer consumer, InventoryPublicationEvidence evidence, InventorySharedRead.Check check) {
		if (check == InventorySharedRead.Check.DEFERRED || check == InventorySharedRead.Check.VALID) return;
		if (check == InventorySharedRead.Check.AUTHORITY_DENIED) {
			// Permission belongs to the exact owner binding, not to one historical physical proof.
			for (Consumer peer : consumers) if (!peer.closed && peer.input.sameBinding(consumer.input)) peer.discardObservation();
			return;
		}
		if (evidence.proof() != null)
			for (Round round : List.copyOf(liveRounds)) {
				InventorySharedRead.@Nullable Port port = round.handle == null ? null : round.handle.sharedRead();
				InventorySharedRead.@Nullable Proof proof = round.sweep != null ? round.sweep.proof : port == null ? null : port.proof();
				if (evidence.proof().equals(proof)) retirePublicationRound(round);
			}
		@Nullable InventoryPublicationEvidence primary = evidence instanceof CombinedEvidence combined ? combined.primary : evidence;
		if (primary instanceof LegacyPublicationEvidence legacy)
			for (Round round : List.copyOf(liveRounds)) if (round.source != null && round.source.metadata == legacy.owner)
				retirePublicationRound(round);
	}
	/** A failed historical completion cannot retire an independently resolved newer scanning association. */
	private void retirePublicationRound(Round round) {
		if (round.closed) return;
		releasePredecessors(round);
		for (Consumer peer : consumers) {
			if (peer.round == round || peer.round == null && peer.completedRound == round) { peer.discardObservation(); continue; }
			if (peer.completedRound == round) {
				if (peer.completedAssociation != null) { peer.completedAssociation.close(); peer.completedAssociation = null; }
				peer.completedRound = null; releaseReference(round);
			}
			if (peer.successorEntitlement == round) releaseEntitlement(peer);
		}
	}
	/** Reserved opportunity, not a cached validation result. No source callbacks run during admission. */
	@NotNullByDefault
	public final class PublicationGrant implements AutoCloseable {
		private final InventoryPublicationEvidence evidence;
		private final InventoryValidationBudget.Cost cost;
		private final int calls;
		private final RetainedMemoryLedger.Ticket workspace;
		private final CostLedger.Ticket work;
		private boolean attempted, closed;
		private PublicationGrant(InventoryPublicationEvidence evidence, RetainedMemoryLedger.Ticket workspace, CostLedger.Ticket work) {
			this.evidence = evidence.retain(); cost = evidence.validationCost(); calls = evidence.probeCalls(); this.workspace = workspace; this.work = work;
		}
		public boolean forEvidence(InventoryPublicationEvidence value) {
			var requested = value.validationCost();
			return !closed && !attempted && value.probeCalls() <= calls && requested.allocationBytes() <= cost.allocationBytes()
				&& requested.workspaceBytes() <= cost.workspaceBytes() && requested.workUnits() <= cost.workUnits();
		}
		public InventorySharedRead.Check validate() { return validateResult().check(); }
		public PublicationValidation validateResult() { return validateResult(evidence); }
		/** A newer cut may use this context's opportunity only if its entire cached envelope fits; its own proof is still checked. */
		public InventorySharedRead.Check validate(InventoryPublicationEvidence value) { return validateResult(value).check(); }
		public PublicationValidation validateResult(InventoryPublicationEvidence value) {
			if (closed || attempted) throw new IllegalStateException("settled publication validation");
			if (!forEvidence(value)) throw new IllegalArgumentException("publication evidence exceeds reserved opportunity");
			attempted = true;
			var allowance = new InventoryValidationBudget.Allowance(cost);
			installFluidGrants(allowance);
			try { return new PublicationValidation(value.validate(allowance), null); }
			catch (InventoryItemBudget.CaptureLimitException deferred) { return new PublicationValidation(InventorySharedRead.Check.DEFERRED, deferReason(deferred)); }
			catch (RuntimeException | LinkageError unavailable) {
				@Nullable var providerLimit = InventoryCaptureDiagnostics.providerLimit(unavailable);
				if (providerLimit != null) return new PublicationValidation(InventorySharedRead.Check.UNAVAILABLE, providerLimit.reason());
				InventoryCaptureDiagnostics.publicationFailed(
					() -> "stage=handoff-metadata probes=" + calls + " admittedWork=" + cost.workUnits(), unavailable);
				return new PublicationValidation(InventorySharedRead.Check.UNAVAILABLE, null);
			}
			finally { work.commit(Map.of(ITEM_WORK, allowance.attemptedWorkUnits() - allowance.independentlyChargedWork(), InventorySourceAccess.PROBES, (long) value.probeCalls(),
				InventorySourceAccess.PROVIDER_WORK, value.probeCalls() * InventorySourceAccess.PROVIDER_CALL_WORK)); }
		}
		@Override public void close() {
			if (!closed) { closed = true; try { work.close(); } finally { try { workspace.close(); } finally { evidence.close(); } } }
		}
	}
	public Optional<PublicationGrant> reservePublication(InventoryPublicationEvidence evidence) {
		var cost = evidence.validationCost();
		var workspace = memory.tryReserve(Math.max(authorityWorkspaceBytes(), cost.workspaceBytes()));
		if (workspace.isEmpty()) return Optional.empty();
		var work = physical.tryReserve(Map.of(ITEM_WORK, cost.workUnits(), InventorySourceAccess.PROBES, (long) evidence.probeCalls(),
			InventorySourceAccess.PROVIDER_WORK, evidence.probeCalls() * InventorySourceAccess.PROVIDER_CALL_WORK));
		if (work.isEmpty()) { workspace.get().close(); return Optional.empty(); }
		return Optional.of(new PublicationGrant(evidence, workspace.get(), work.get()));
	}
	@NotNullByDefault
	public record PublicationValidation(InventorySharedRead.Check check, @Nullable InventoryFailureReason reason) {}
	@NotNullByDefault
	public record BindingEvidenceResult(InventorySharedRead.Check check, @Nullable InventoryPublicationEvidence evidence,
		@Nullable InventoryFailureReason reason) {}
	/** A seed acquires only its own committed-input metadata on a normal budgeted tick, never preview capture. */
	public BindingEvidenceResult acquirePublicationBinding(Consumer consumer) {
		if (consumer.closed || consumer.invalidated()) return new BindingEvidenceResult(InventorySharedRead.Check.UNAVAILABLE, null, null);
		InventoryValidationBudget.Cost resolutionCost;
		try { resolutionCost = resolver.resolutionProbeCost(consumer.input); }
		catch (InventoryItemBudget.CaptureLimitException deferred) {
			return new BindingEvidenceResult(InventorySharedRead.Check.DEFERRED, null, deferReason(deferred));
		}
		catch (RuntimeException | LinkageError unavailable) {
			return new BindingEvidenceResult(InventorySharedRead.Check.UNAVAILABLE, null, sourceReason(unavailable));
		}
		long upper = Math.max(authorityWorkspaceBytes(), resolutionCost.workspaceBytes());
		var retained = memory.tryReserve(checkedAdd(upper, EVIDENCE_MEMORY));
		if (retained.isEmpty()) return new BindingEvidenceResult(InventorySharedRead.Check.DEFERRED, null, InventoryFailureReason.MEMORY_LIMIT);
		InventorySourceAccess.@Nullable Source source = null;
		boolean transferred = false;
		try {
			var resolution = validationDecision(resolutionCost, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), upper);
			if (!resolution.accepted()) return new BindingEvidenceResult(InventorySharedRead.Check.DEFERRED, null, resolution.reason());
			try (var operation = java.util.Objects.requireNonNull(resolution.value())) {
				var probe = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
				if (!probe.accepted()) return new BindingEvidenceResult(InventorySharedRead.Check.DEFERRED, null,
					counterReason(java.util.Objects.requireNonNull(probe.limitingCounter())));
				try (var credit = java.util.Objects.requireNonNull(probe.ticket())) {
					credit.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
					source = resolver.resolve(consumer.input, effectiveDisplayCap(), operation.allowance).orElse(null);
				}
			}
			if (source == null || consumer.closed || consumer.invalidated()) return new BindingEvidenceResult(InventorySharedRead.Check.UNAVAILABLE, null, null);
			if (source.sourceRetainedBytes() < 0 || source.sourceRetainedBytes() > upper) throw new IllegalStateException("binding metadata exceeds resolution grant");
			var evidence = new LegacyPublicationEvidence(new MetadataOwner(source, true), retained.get());
			var admission = reservePublication(evidence);
			if (admission.isEmpty()) return new BindingEvidenceResult(InventorySharedRead.Check.DEFERRED, null, null);
			PublicationValidation validationResult;
			try (var grant = admission.get()) { validationResult = grant.validateResult(); }
			if (validationResult.check() != InventorySharedRead.Check.VALID) return new BindingEvidenceResult(validationResult.check(), null, validationResult.reason());
			retained.get().commit(checkedAdd(EVIDENCE_MEMORY, source.sourceRetainedBytes()));
			transferred = true;
			return new BindingEvidenceResult(validationResult.check(), evidence, null);
		} catch (InventoryItemBudget.CaptureLimitException deferred) { return new BindingEvidenceResult(InventorySharedRead.Check.DEFERRED, null, deferReason(deferred)); }
		catch (RuntimeException | LinkageError unavailable) {
			return new BindingEvidenceResult(InventorySharedRead.Check.UNAVAILABLE, null, sourceReason(unavailable));
		}
		finally { if (!transferred) try { if (source != null) source.close(); } finally { retained.get().close(); } }
	}
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
	private static Lane lane(Consumer consumer) { return consumer.subject instanceof PreviewSubject ? Lane.PREVIEW : Lane.TRACKING; }
	private Quantity logicalQuantity(Consumer consumer) {
		Round round = consumer.round;
		return round != null && round.last != null && round.last.coverage().expected().isPresent()
			? Quantity.of(Unit.LOGICAL_SLOT, Math.max(0, round.last.coverage().expected().getAsLong() - consumer.cursor)) : Quantity.unknown();
	}
	private void delay(Consumer consumer, InventoryBudgetDelayState stage, int reasons, String name) {
		reportDelay(consumer, reasons, name);
		stage.observe(sink, cycle(consumer), tick, reasons,
			() -> new WorkItem("consumer/" + consumer.diagnosticId + "/" + name, "Inventory consumer " + consumer.diagnosticId + " " + name),
			() -> stage == consumer.logicalDelay ? logicalQuantity(consumer) : Quantity.unknown());
	}
	private void physicalDelay(Consumer consumer, int reasons, long requestedSlots) {
		if (reasons == 0) return;
		reportDelay(consumer, reasons, "physical");
		Round round = consumer.round;
		if (round == null) { delay(consumer, consumer.admissionDelay, reasons, "admission"); return; }
		if (round.physicalDelay == null) {
			round.physicalModule = module(consumer);
			round.physicalDelay = new InventoryBudgetDelayState(round.physicalModule);
		}
		round.physicalDelay.observe(sink, cycle(round.physicalModule), tick, reasons,
			() -> new WorkItem("round/" + round.sequence + "/physical", "Inventory round " + round.sequence + " physical capture/decode"),
			() -> requestedSlots < 0 ? Quantity.unknown() : round.sweep != null
				? Quantity.of(Unit.PHYSICAL_SLOT, Math.max(0, round.sweep.proof.graph().slots() - round.size()))
				: round.last == null || round.last.coverage().expected().isEmpty() ? Quantity.unknown()
				: Quantity.of(Unit.PHYSICAL_SLOT, Math.max(0, round.last.coverage().expected().getAsLong() - round.size())));
	}
	private void reportDelay(Consumer consumer, int reasons, String stage) {
		if (reasons == 0) return;
		for (Reason reason : Reason.values()) if ((reasons & reason.bit()) != 0) {
			@Nullable InventoryFailureReason failure = switch (reason) {
				case MEMORY -> InventoryFailureReason.MEMORY_LIMIT;
				case PROBE_WORK -> InventoryFailureReason.PROBE_LIMIT;
				case PROVIDER_WORK -> InventoryFailureReason.PROVIDER_WORK_LIMIT;
				case PUBLICATION_WORK -> InventoryFailureReason.PUBLICATION_WORK_LIMIT;
				case PHYSICAL_SLOTS -> InventoryFailureReason.PHYSICAL_SLOT_LIMIT;
				case LOGICAL_CLIENT_OR_TARGET, LOGICAL_GLOBAL -> InventoryFailureReason.LOGICAL_SLOT_LIMIT;
				case ROUND_CAPACITY -> InventoryFailureReason.ROUND_CAPACITY;
				case SUBJECT_CAPACITY -> InventoryFailureReason.CONSUMER_CAPACITY;
				case CAPTURE_COUNT -> InventoryFailureReason.CAPTURE_READ_LIMIT;
				default -> null;
			};
			if (failure == null) continue;
			consumer.currentReason = failure;
			InventoryCaptureDiagnostics.limit(consumer.input, InventoryDiagnosticSite.CAPTURE, InventoryOperation.READ,
				failure, InventoryOutcome.DEFERRED, stage, -1, -1, -1, null);
		}
	}
	private void validationDelay(Consumer consumer, InventoryBudgetDelayState state, InventoryDecision<?> decision, String stage) {
		var reason = java.util.Objects.requireNonNull(decision.reason());
		observeTypedDelay(consumer, state, reason, stage);
	}
	/** One request report, followed by monitoring without a second bit-derived report. */
	private void requestDelay(Consumer consumer, InventoryBudgetDelayState state, InventoryFailureReason reason, String stage,
		long requested, long used, long limit) {
		InventoryCaptureDiagnostics.limit(consumer.input, InventoryDiagnosticSite.CAPTURE, InventoryOperation.READ,
			reason, InventoryOutcome.DEFERRED, stage, requested, used, limit, null);
		observeTypedDelay(consumer, state, reason, stage);
	}
	private void observeTypedDelay(Consumer consumer, InventoryBudgetDelayState state, InventoryFailureReason reason, String stage) {
		int bits = reason == InventoryFailureReason.MEMORY_LIMIT ? Reason.MEMORY.bit()
			: reason == InventoryFailureReason.PROBE_LIMIT ? Reason.PROBE_WORK.bit()
			: reason == InventoryFailureReason.PROVIDER_WORK_LIMIT ? Reason.PROVIDER_WORK.bit()
			: reason == InventoryFailureReason.PHYSICAL_SLOT_LIMIT ? Reason.PHYSICAL_SLOTS.bit()
			: reason == InventoryFailureReason.CAPTURE_READ_LIMIT ? Reason.CAPTURE_COUNT.bit() : Reason.PUBLICATION_WORK.bit();
		// Validation already reported its exact typed decision. The monitor's
		// coarser work bucket must neither replace that reason nor report it again.
		consumer.currentReason = reason;
		state.observe(sink, cycle(consumer), tick, bits,
			() -> new WorkItem("consumer/" + consumer.diagnosticId + "/" + stage, "Inventory consumer " + consumer.diagnosticId + " " + stage),
			Quantity::unknown);
	}
	private void physicalProgress(Round round) {
		if (round.physicalDelay != null) round.physicalDelay.progress(cycle(round.physicalModule));
	}
	private void warn(String operation, Consumer consumer, @Nullable Round round, Throwable failure) {
		InventoryCaptureDiagnostics.warn("runtime/" + operation, consumer.input, round == null ? null : round.key,
			() -> "consumer=" + consumer.diagnosticId + " subject=" + consumer.subject
				+ " round=" + (round == null ? "none" : round.sequence) + " tick=" + tick, failure);
	}
	private void warnPooled(String operation, Consumer consumer, @Nullable Round round, InventorySharedRead.Proof proof,
		String stage, Throwable failure) {
		InventoryCaptureDiagnostics.warn("runtime/" + operation, "invalidate-observation", consumer.input, round == null ? null : round.key,
			() -> "consumer=" + consumer.diagnosticId + " subject=" + consumer.subject + " stage=" + stage
				+ " round=" + (round == null ? "none" : round.sequence) + " tick=" + tick
				+ " proofRoute=" + proof.graph().route() + " alias=" + proof.graph().alias()
				+ " readPage=" + (round == null ? 0 : round.size())
				+ (failure instanceof InventorySharedRead.ReadFailure typed ? " check=" + typed.check() : " check=UNAVAILABLE"), failure);
	}
	private int providerReasons(long probes, long work) {
		return (physical.remaining(InventorySourceAccess.PROBES) < probes ? Reason.PROBE_WORK.bit() : 0)
			| (physical.remaining(InventorySourceAccess.PROVIDER_WORK) < work ? Reason.PROVIDER_WORK.bit() : 0);
	}
	boolean delayed(Module module) {
		for (Consumer consumer : consumers) if (consumer.serviceableDemand && !consumer.invalidated()) {
			if (module(consumer) == module && (consumer.admissionDelay.outstanding() || consumer.probeDelay.outstanding()
				|| consumer.logicalDelay.outstanding() || consumer.projectionDelay.outstanding())) return true;
			Round round = consumer.round;
			if (consumer.scanning && round != null && round.physicalModule == module && round.physicalDelay != null && round.physicalDelay.outstanding()) return true;
		}
		return false;
	}
	boolean eligible(Module module) {
		for (Consumer consumer : consumers) if (consumer.serviceableDemand && !consumer.invalidated()) {
			if (module(consumer) == module && (consumer.admissionDelay.eligible() || consumer.probeDelay.eligible()
				|| consumer.logicalDelay.eligible() || consumer.projectionDelay.eligible())) return true;
			Round round = consumer.round;
			if (consumer.scanning && round != null && round.physicalModule == module && round.physicalDelay != null && round.physicalDelay.eligible()) return true;
		}
		return false;
	}
	public boolean selectionPresent(InventorySourceInput input, InventorySelection selection, int witness) {
		long readGeneration = monitor.enabled() ? monitor.collectionGeneration() : -1;
		int displayCap = effectiveDisplayCap();
		long fixed = checkedAdd(InventoryCatalogAdmission.METADATA_BYTES, authorityWorkspaceBytes());
		long result = InventoryItemBudget.retainedUpperBound(displayCap);
		int bound = (int) Math.min(physicalRemaining(), InventorySourceAccess.MAX_STEP);
		if (bound == 0) return false;
		var workspace = memory.tryReserve(fixed);
		if (workspace.isEmpty()) return false;
		try (var held = workspace.get()) {
			long work = checkedMultiply(bound, InventoryItemBudget.workUpperBound(displayCap, Math.min(InventoryItemCodec.DEFAULT_MAX_ENTRIES, bound - 1)));
			var workAdmission = physical.tryReserve(Map.of(ITEM_WORK, work));
			if (workAdmission.isEmpty()) return false;
			long before = physical.used(InventorySourceAccess.PHYSICAL);
			try (var grant = workAdmission.get()) {
				var catalog = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(displayCap),
					checkedMultiply(bound, InventoryCatalogAdmission.workUpperBound(displayCap, Math.min(InventoryItemCodec.DEFAULT_MAX_ENTRIES, bound - 1))));
				try {
					var probe = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
					if (probe.isEmpty()) return false;
					try (var admittedProbe = probe.get()) {
					var resolution = validation(resolver.resolutionProbeCost(input), new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), authorityWorkspaceBytes());
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
						int affordable = source.stableCursor() ? 1 : bound;
						while (affordable > 0 && checkedAdd(liveCatalogGrowth(affordable, displayCap, 0), checkedMultiply(affordable, result)) > memory.remaining()) affordable--;
						if (affordable == 0) return false;
						final int observationBound = affordable;
						var growth = memory.tryReserve(checkedAdd(liveCatalogGrowth(observationBound, displayCap, 0), checkedMultiply(observationBound, result)));
						if (growth.isEmpty()) return false;
						try (var growthGrant = growth.get()) {
						var read = validation(source.evidenceValidationCost().plus(source.readValidationCost(observationBound)), allowance, authorityWorkspaceBytes());
						if (read.isEmpty()) return false;
						try (var readGrant = read.get()) {
							// The outer operation owns close even when slot/provider admission refuses before resolver invocation.
							var borrowed = new AdmittedSource(source, false, memory, monitor, Lane.TRACKING);
							var access = new InventorySourceAccess(input, i -> Optional.of(borrowed));
							CostLedger readLedger = physical;
							@Nullable SelectionReadScope scope = readGeneration >= 0 && monitor.enabled() && readGeneration == monitor.collectionGeneration()
								? new SelectionReadScope(selectionReadScope, readLedger, readGeneration) : null;
							if (scope != null) selectionReadScope = scope;
							try { return access.selectionPresent(selection, witness, observationBound, readLedger, catalog, allowance); }
							finally {
								if (scope != null) {
									selectionReadScope = scope.parent;
									// Only committed descendants on this exact ledger are included in its used delta.
									long own = readLedger.used(InventorySourceAccess.PHYSICAL) - scope.before - scope.nestedCharged;
									recordReadAttempts(readGeneration, readLedger, own, own);
								}
							}
						}
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
	public <T> Optional<T> preflight(java.util.function.Supplier<@Nullable Optional<T>> work) {
		return Optional.ofNullable(preflightDecision(work).value());
	}
	private static InventoryFailureReason counterReason(CostLedger.Counter counter) {
		if (counter.equals(InventorySourceAccess.PROBES)) return InventoryFailureReason.PROBE_LIMIT;
		if (counter.equals(InventorySourceAccess.PROVIDER_WORK)) return InventoryFailureReason.PROVIDER_WORK_LIMIT;
		if (counter.equals(InventorySourceAccess.PHYSICAL)) return InventoryFailureReason.PHYSICAL_SLOT_LIMIT;
		if (counter.equals(CAPTURE_READS)) return InventoryFailureReason.CAPTURE_READ_LIMIT;
		return InventoryFailureReason.ITEM_WORK_LIMIT;
	}
	private static <T> InventoryDecision<T> deferred(InventoryFailureReason reason) {
		InventoryCaptureDiagnostics.limit(null, InventoryDiagnosticSite.VALIDATION, InventoryOperation.VALIDATE, reason,
			InventoryOutcome.DEFERRED, "runtime-admission", -1, -1, -1, null);
		return InventoryDecision.defer(reason, InventoryImpact.DEGRADED);
	}
	private static InventoryFailureReason sourceReason(Throwable failure) {
		@Nullable var provider = InventoryCaptureDiagnostics.providerLimit(failure);
		if (provider != null) return provider.reason();
		if (failure instanceof InventorySharedRead.ReadFailure typed) return switch (typed.check()) {
			case AUTHORITY_DENIED -> InventoryFailureReason.SOURCE_AUTHORITY_DENIED;
			case STRUCTURE_INVALID -> InventoryFailureReason.SOURCE_CHANGED;
			default -> InventoryFailureReason.SOURCE_UNAVAILABLE;
		};
		return InventoryCaptureDiagnostics.sourceReason(failure);
	}
	private static InventoryFailureReason deferReason(InventoryItemBudget.CaptureLimitException failure) {
		if (failure instanceof InventoryValidationBudget.ItemResolutionDeferred item) return item.reason();
		if (failure instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission.Deferred fluid) return fluid.reason();
		return failure.reason();
	}
	public <T> InventoryDecision<T> preflightDecision(java.util.function.Supplier<@Nullable Optional<T>> work) {
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) return deferred(InventoryFailureReason.MEMORY_LIMIT);
		try (var held = workspace.get()) {
		var ticket = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (!ticket.accepted()) return deferred(counterReason(java.util.Objects.requireNonNull(ticket.limitingCounter())));
		try (var grant = java.util.Objects.requireNonNull(ticket.ticket())) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			@Nullable Optional<T> result = work.get();
			return result == null || result.isEmpty() ? InventoryDecision.reject(InventoryFailureReason.SOURCE_UNAVAILABLE, InventoryImpact.DEGRADED)
				: InventoryDecision.accept(result.get());
		} catch (RuntimeException | LinkageError failure) {
			if (failure instanceof InventoryItemBudget.CaptureLimitException bounded) return deferred(deferReason(bounded));
			if (failure instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException)
				return InventoryDecision.unsupported(InventoryFailureReason.UNSUPPORTED_LANE, InventoryImpact.DEGRADED);
			if (!InventoryCaptureDiagnostics.expectedFailure(failure))
				Diagnostics.server().atNormal().rateLimited(PREFLIGHT).warn("inventory/runtime/preflight", "reject-selection", failure, () -> "tick=" + tick);
			return InventoryDecision.reject(sourceReason(failure), InventoryImpact.DEGRADED);
		}
		}
	}
	public void advance(long now, InventorySettings settings) {
		if (now < tick) throw new IllegalArgumentException("inventory clock regressed");
		var nextPolicy = certifiedPooling ? new InventorySharedRead.Policy(settings.getTracking().isDistinguishFaces(), settings.getTracking().isDistinguishPlayers())
			: new InventorySharedRead.Policy(true, true);
		if (!policy.equals(nextPolicy)) {
			for (Round round : liveRounds) round.sealed = true;
			policy = nextPolicy;
		}
		this.settings = settings;
		memory.setCap(settings.pendingMemoryBytes());
		if (previewClock.advance(now, settings.getPreview().getPeriodTicks())) { previewGlobal = 0; clearLogical(true); }
		if (trackingClock.advance(now, settings.getTracking().getPeriodTicks())) { trackingGlobal = 0; clearLogical(false); }
		if (now != tick) {
			for (OperationGrant grant : List.copyOf(operationGrants)) grant.close();
			tick = now;
			for (Consumer consumer : consumers) {
				consumer.admissionDelay.beginTick(cycle(consumer)); consumer.probeDelay.beginTick(cycle(consumer));
				consumer.logicalDelay.beginTick(cycle(consumer)); consumer.projectionDelay.beginTick(cycle(consumer));
				Round round = consumer.round;
				if (round != null && round.physicalDelay != null) round.physicalDelay.beginTick(cycle(round.physicalModule));
			}
			physical = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) settings.effectivePhysicalSlotsPerTick(),
				InventorySourceAccess.PROBES, 256L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK,
				CAPTURE_READS, (long) InventorySourceAccess.CAPTURE_MAX_SLOTS,
				ITEM_WORK, checkedMultiply(InventorySourceAccess.CAPTURE_MAX_SLOTS, InventoryItemBudget.workUpperBound(effectiveDisplayCap()))));
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
	/** One caller-owned synchronous allowance. Only a settled source-metadata ticket may escape the operation. */
	private final class ValidationOperation implements AutoCloseable {
		final InventoryValidationBudget.Allowance allowance;
		final RetainedMemoryLedger.Ticket workspace;
		final CostLedger.Ticket work;
		final long before;
		final long workUpper;
		private @Nullable ItemStageGrant first, second, third;
		private boolean closed;
		ValidationOperation(InventoryValidationBudget.Cost cost, RetainedMemoryLedger.Ticket workspace, CostLedger.Ticket work, InventoryValidationBudget.Allowance allowance) {
			this.allowance = allowance; before = allowance.attemptedOuterWorkUnits();
			installFluidGrants(allowance);
			allowance.admitAdditional(cost); this.workspace = workspace; this.work = work; workUpper = cost.workUnits();
			allowance.itemResolutionGrants(this::openItemStage);
		}
		private InventoryValidationBudget.ItemResolutionStage openItemStage(InventoryValidationBudget.Cost cost) {
			if (closed || third != null) throw new IllegalStateException("item resolution operation is not active");
			allowance.checkItemResolutionAdditional(cost); // arithmetic/scope preflight before either real ledger moves
			var scratch = memory.tryReserve(cost.workspaceBytes()).orElseThrow(() -> InventoryValidationBudget.itemResolutionDeferred(InventoryFailureReason.MEMORY_LIMIT));
			try {
				var ticket = physical.tryReserve(Map.of(ITEM_WORK, cost.workUnits())).orElseThrow(() -> InventoryValidationBudget.itemResolutionDeferred(InventoryFailureReason.ITEM_WORK_LIMIT));
				try {
					var stage = new InventoryValidationBudget.ItemResolutionStage(scratch, cost.workspaceBytes());
					var grant = new ItemStageGrant(ticket, cost.workUnits(), stage);
					allowance.admitAdditional(cost);
					if (first == null) first = grant; else if (second == null) second = grant; else third = grant;
					return stage;
				} catch (RuntimeException | Error failure) { ticket.close(); throw failure; }
			} catch (RuntimeException | Error failure) { scratch.close(); throw failure; }
		}
		@Override public void close() {
			if (closed) return; closed = true; allowance.itemResolutionGrants(null);
			long remaining = allowance.attemptedOuterWorkUnits() - before;
			try {
				long used = Math.min(remaining, workUpper); remaining -= used; work.commit(Map.of(ITEM_WORK, used));
				if (first != null) remaining = first.settle(remaining);
				if (second != null) remaining = second.settle(remaining);
				if (third != null) remaining = third.settle(remaining);
				if (remaining != 0) throw new IllegalStateException("item validation work exceeds genuine grants");
			} finally {
				try { allowance.releaseUnusedAdmission(); }
				finally {
					try { try { if (first != null) first.close(); } finally { try { if (second != null) second.close(); } finally { if (third != null) third.close(); } } }
					finally { try { work.close(); } finally { workspace.close(); } }
				}
			}
		}
	}
	@NotNullByDefault
	private record ItemStageGrant(CostLedger.Ticket work, long upperBound, InventoryValidationBudget.ItemResolutionStage stage) implements AutoCloseable {
		long settle(long remaining) { long used = Math.min(remaining, upperBound); work.commit(Map.of(ITEM_WORK, used)); return remaining - used; }
		@Override public void close() { try { work.close(); } finally { stage.close(); } }
	}
	private Optional<ValidationOperation> validation(InventoryValidationBudget.Cost cost) {
		return validation(cost, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE));
	}
	/** Installs the capability only; each fluid operation still admits and settles its own actual ledger costs. */
	private void installFluidGrants(InventoryValidationBudget.Allowance allowance) {
		allowance.fluidGrants((caller, member, authority) -> new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission(
			physical, ITEM_WORK, memory, member, authority, caller));
	}
	/** Staged FLUID port helper; uses this Runtime's current global work and retained-memory ledgers. */
	public nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission fluidAdmission(
		InventoryValidationBudget.Cost memberGateCost, InventoryValidationBudget.Cost currentAuthorityCost) {
		return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission(physical, ITEM_WORK, memory,
			memberGateCost, currentAuthorityCost);
	}
	private Optional<ValidationOperation> validation(InventoryValidationBudget.Cost cost, InventoryValidationBudget.Allowance allowance) {
		return validation(cost, allowance, 0);
	}
	private Optional<ValidationOperation> validation(InventoryValidationBudget.Cost cost, InventoryValidationBudget.Allowance allowance, long coveredWorkspace) {
		return Optional.ofNullable(validationDecision(cost, allowance, coveredWorkspace).value());
	}
	private InventoryDecision<ValidationOperation> validationDecision(InventoryValidationBudget.Cost cost, InventoryValidationBudget.Allowance allowance, long coveredWorkspace) {
		var workspace = memory.tryReserve(Math.max(0, cost.workspaceBytes() - coveredWorkspace));
		if (workspace.isEmpty()) return deferred(InventoryFailureReason.MEMORY_LIMIT);
		var work = physical.tryReserve(Map.of(ITEM_WORK, cost.workUnits()));
		if (work.isEmpty()) { workspace.get().close(); return deferred(InventoryFailureReason.ITEM_WORK_LIMIT); }
		return InventoryDecision.accept(new ValidationOperation(cost, workspace.get(), work.get(), allowance));
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
		return Math.min(Math.max(0, liveCatalogUpperBound() - held), checkedMultiply(2L,
			checkedAdd(InventoryItemCodec.Catalog.initialModeledBytes(), InventoryItemCodec.Catalog.firstReadGrowthUpperBound(displayBytes, roots))));
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
	private static final class AdmittedSource implements InventorySourceAccess.Source {
		@Override public boolean scopeTransition() { return delegate.scopeTransition(); }
		private final InventorySourceAccess.Source delegate;
		private final RetainedMemoryLedger memory;
		private final ServerInventoryMonitor monitor;
		private Lane lane;
		private final MetadataOwner metadata;
		private final InventoryDisplayProjection projection = new InventoryDisplayProjection();
		private InventorySourceAccess.@Nullable SnapshotPlan plan;
		private boolean discovered, captured, captureAttempted;
		private long captureValidationWork;
		private boolean ownerClosed;
		AdmittedSource(InventorySourceAccess.Source delegate, boolean ownsDelegate, RetainedMemoryLedger memory,
			ServerInventoryMonitor monitor, Lane lane) {
			this.delegate = delegate; this.memory = memory; metadata = new MetadataOwner(delegate, ownsDelegate);
			this.monitor = monitor; this.lane = lane;
		}
		@Override public SourceKey key() { return delegate.key(); }
		@Override public InventorySharedRead.@Nullable Port sharedRead() { return delegate.sharedRead(); }
		@Override public Optional<InventoryPublicationEvidence> publicationEvidence() {
			if (ownerClosed) return Optional.empty();
			var ticket = memory.tryReserve(EVIDENCE_MEMORY);
			if (ticket.isEmpty()) return Optional.empty();
			ticket.get().commit(EVIDENCE_MEMORY); metadata.references++;
			return Optional.of(new LegacyPublicationEvidence(metadata, ticket.get()));
		}
		@Override public void releaseReadData() { delegate.releaseReadData(); }
		@Override public long sourceRetainedBytes() { return delegate.sourceRetainedBytes(); }
		@Override public InventorySourceAccess.ReadMode readMode() { return delegate.readMode(); }
		@Override public InventoryItemCodec.@Nullable Catalog nativeCatalog() { return delegate.nativeCatalog(); }
		@Override public long nativeCatalogGrowthUpperBound() { return delegate.nativeCatalogGrowthUpperBound(); }
		@Override public long nativeCatalogGrowthUpperBound(int attempts) { return delegate.nativeCatalogGrowthUpperBound(attempts); }
		@Override public int nativeCatalogEntriesUpperBound(int attempts) { return delegate.nativeCatalogEntriesUpperBound(attempts); }
		@Override public long nativeResourceRetainedBytes() { return delegate.nativeResourceRetainedBytes(); }
		@Override public boolean certifiedResourceSharing() { return delegate.certifiedResourceSharing(); }
		@Override public @Nullable InventoryResourceValue readResource(int slot) { return delegate.readResource(slot); }
		@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.readResource(slot, admission, allowance); }
		@Override public InventorySourceAccess.ResourcePage enumerateResources(int limit, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.enumerateResources(limit, admission, allowance); }
		@Override public boolean valid() { return delegate.valid(); }
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) { return delegate.valid(allowance); }
		@Override public InventoryDecision<Boolean> validationDecision(InventoryValidationBudget.Allowance allowance) { return delegate.validationDecision(allowance); }
		@Override public InventoryValidationBudget.Cost discoveryValidationCost() { return delegate.discoveryValidationCost(); }
		@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return delegate.evidenceValidationCost(); }
		@Override public InventoryValidationBudget.Cost readValidationCost(int slots) { return delegate.readValidationCost(slots); }
		@Override public InventoryDomainCodec.@org.jetbrains.annotations.Nullable Item read(int slot, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.read(slot, admission, allowance); }
		@Override public InventorySourceAccess.Page enumerate(int limit, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.enumerate(limit, admission, allowance); }
		@Override public boolean matchesSelection(int slot, InventorySelection selection, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.matchesSelection(slot, selection, admission, allowance); }
		@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection, @org.jetbrains.annotations.Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) { return delegate.observeSelection(limit, selection, admission, allowance); }
		@Override public boolean stableCursor() { return delegate.stableCursor(); }
		@Override public int slots() { return delegate.slots(); }
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) { return delegate.read(slot); }
		@Override public InventoryDomainCodec.@Nullable Item read(int slot, @Nullable InventoryCatalogAdmission admission) { return delegate.read(slot, admission); }
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
					long timing = monitor.enabled() ? monitor.beginPhase(lane, Phase.CAPTURE) : 0;
					try {
						var result = plan.capture(allowance); captured = result.isPresent(); return result;
					} finally {
						captureValidationWork = allowance.attemptedWorkUnits() - before;
						monitor.end(timing);
					}
				}
			});
		}
		@Override public void close() { if (!ownerClosed) { ownerClosed = true; try { delegate.releaseReadData(); } finally { metadata.release(); } } }
	}
	/** Static metadata owner: a queued cut cannot reach the runtime's rounds through an inner wrapper. */
	@NotNullByDefault
	private static final class MetadataOwner {
		final InventorySourceAccess.Source source;
		final boolean ownsSource;
		int references = 1;
		RetainedMemoryLedger.@Nullable Ticket memory;
		MetadataOwner(InventorySourceAccess.Source source, boolean ownsSource) { this.source = source; this.ownsSource = ownsSource; }
		void release() { if (--references == 0) try { if (ownsSource) source.close(); } finally { if (memory != null) memory.close(); } }
	}
	/** Split the already-held source envelope from round state so metadata has exactly one independent lifetime charge. */
	private void retainMetadata(AdmittedSource source, RetainedMemoryLedger.Ticket original) {
		original.commit(ROUND_MEMORY);
		long bytes = source.sourceRetainedBytes();
		var metadata = memory.tryReserve(bytes).orElseThrow(() -> new IllegalStateException("source metadata transfer exceeds held envelope"));
		metadata.commit(bytes); source.metadata.memory = metadata;
	}
	@NotNullByDefault
	private static final class LegacyPublicationEvidence implements InventoryPublicationEvidence {
		final MetadataOwner owner;
		final RetainedMemoryLedger.Ticket memory;
		final InventoryValidationBudget.Cost cost;
		final InventorySharedRead.@Nullable Port port;
		int references = 1;
		LegacyPublicationEvidence(MetadataOwner owner, RetainedMemoryLedger.Ticket memory) {
			this.owner = owner; this.memory = memory; port = owner.source.certifiedResourceSharing() ? owner.source.sharedRead() : null;
			cost = port == null ? owner.source.evidenceValidationCost() : port.validationCost();
		}
		@Override public InventorySharedRead.@Nullable Proof proof() { return port == null ? null : port.proof(); }
		@Override public boolean sameProof(InventoryPublicationEvidence other) {
			return other instanceof LegacyPublicationEvidence legacy && owner == legacy.owner || InventoryPublicationEvidence.super.sameProof(other);
		}
		@Override public InventoryValidationBudget.Cost validationCost() { return cost; }
		@Override public int probeCalls() { return 1; }
		@Override public boolean scopeTransition() { return owner.source.scopeTransition(); }
		@Override public InventorySharedRead.Check validate(InventoryValidationBudget.Allowance allowance) {
			return port != null ? port.validate(port.proof(), allowance) : owner.source.valid(allowance) ? InventorySharedRead.Check.VALID : InventorySharedRead.Check.UNAVAILABLE;
		}
		@Override public InventoryPublicationEvidence retain() { if (references == 0) throw new IllegalStateException("released publication evidence"); references++; return this; }
		@Override public void close() { if (references != 0 && --references == 0) try { owner.release(); } finally { memory.close(); } }
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
					long before = discovery.attemptedWorkUnits(), independent = discovery.independentlyChargedWork(); discovery.admitAdditional(cost);
					boolean measuring = monitor.enabled();
					Lane previousLane = round.source.lane;
					if (measuring) round.source.lane = lane(consumer);
					try { return capturePreparedSnapshot(consumer, round, discovery); }
					finally {
						if (measuring) round.source.lane = previousLane;
						try { grant.commit(Map.of(ITEM_WORK, discovery.attemptedWorkUnits() - before - round.source.captureValidationWork
							- (discovery.independentlyChargedWork() - independent))); }
						finally { round.source.captureValidationWork = 0; discovery.releaseUnusedAdmission(); }
					}
				}
			}
		} catch (InventoryItemBudget.CaptureLimitException exceeded) {
			consumer.currentReason = round.terminalReason = InventoryCaptureDiagnostics.boundReason(exceeded);
			InventoryCaptureDiagnostics.boundExceeded(consumer.input, "snapshot-discovery", exceeded);
			if (round.physicalDelay != null) round.physicalDelay.cancel();
			return InventorySourceAccess.Preparation.INCOMPLETE;
		} catch (RuntimeException | LinkageError unavailable) {
			if (InventoryCaptureDiagnostics.providerLimit(unavailable) != null) consumer.currentReason = round.terminalReason = sourceReason(unavailable);
			if (InventoryCaptureDiagnostics.expectedSourceUnavailable(unavailable))
				InventoryCaptureDiagnostics.sourceUnavailable("runtime/snapshot-prepare", consumer.input,
					() -> round.key.toString(), "snapshot-prepare", unavailable);
			else warn("snapshot-prepare", consumer, round, unavailable);
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
		if (reads < 0 || reads > InventorySourceAccess.CAPTURE_MAX_SLOTS) {
			consumer.currentReason = round.terminalReason = InventoryFailureReason.CAPTURE_READ_LIMIT;
			InventoryCaptureDiagnostics.limit(consumer.input, InventoryDiagnosticSite.SNAPSHOT, InventoryOperation.SNAPSHOT,
				consumer.currentReason, InventoryOutcome.TRUNCATED, "snapshot-plan-reads", reads, physical.used(CAPTURE_READS), physical.limit(CAPTURE_READS), null);
			return InventorySourceAccess.Preparation.INCOMPLETE;
		}
		if (work < 0 || work > physical.limit(ITEM_WORK)) {
			consumer.currentReason = round.terminalReason = InventoryFailureReason.ITEM_WORK_LIMIT;
			InventoryCaptureDiagnostics.limit(consumer.input, InventoryDiagnosticSite.SNAPSHOT, InventoryOperation.SNAPSHOT,
				consumer.currentReason, InventoryOutcome.TRUNCATED, "snapshot-plan-work", work, physical.used(ITEM_WORK), physical.limit(ITEM_WORK), null);
			return InventorySourceAccess.Preparation.INCOMPLETE;
		}
		var admission = physical.tryReserveDecision(Map.of(CAPTURE_READS, reads, ITEM_WORK, work));
		if (!admission.accepted()) {
			var counter = java.util.Objects.requireNonNull(admission.limitingCounter());
			requestDelay(consumer, consumer.admissionDelay, counterReason(counter), "snapshot-admission",
				counter.equals(CAPTURE_READS) ? reads : work, physical.used(counter), physical.limit(counter));
			return InventorySourceAccess.Preparation.DEFERRED;
		}
		int before = plan == null ? 0 : plan.attemptedPhysicalReads();
		source.captureAttempted = false;
		try (var grant = java.util.Objects.requireNonNull(admission.ticket())) {
			try {
				if (plan != null) discovery.admitAdditional(plan.validationCost());
				var outcome = round.handle.prepareSnapshot(memory, discovery);
				if (round.handle.terminalReason() != null) consumer.currentReason = round.terminalReason = round.handle.terminalReason();
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
		return Optional.ofNullable(attachDecision(input, subject).value());
	}
	private void limitRound(Consumer consumer, InventoryFailureReason reason, String stage) {
		consumer.currentReason = reason;
		if (consumer.round != null) consumer.round.terminalReason = reason;
		InventoryCaptureDiagnostics.limit(consumer.input, InventoryDiagnosticSite.CAPTURE, InventoryOperation.READ, reason,
			InventoryOutcome.TRUNCATED, stage, -1, -1, -1, null);
	}
	public InventoryDecision<Consumer> attachDecision(InventorySourceInput input, Subject subject) {
		if (consumers.size() >= MAX_CONSUMERS) return deferred(InventoryFailureReason.CONSUMER_CAPACITY);
		var admission = memory.tryReserve(CONSUMER_MEMORY);
		if (admission.isEmpty()) return deferred(InventoryFailureReason.MEMORY_LIMIT);
		Consumer consumer = new Consumer(input, subject, admission.get());
		admission.get().commit(CONSUMER_MEMORY);
		consumers.add(consumer);
		return InventoryDecision.accept(consumer);
	}

	/** Input-only authority for initial admission, recovery and live SELECT witnesses, not cached publication. */
	public boolean validate(InventorySourceInput input) {
		return probe(input).orElse(false);
	}
	/** Empty is admission defer, not source invalidity. */
	public Optional<Boolean> probe(InventorySourceInput input) {
		return legacyProbe(probeDecision(input));
	}
	private Optional<Boolean> probe(InventorySourceInput input, boolean reportRejection) {
		return legacyProbe(probeDecision(input, reportRejection));
	}
	private static Optional<Boolean> legacyProbe(InventoryDecision<Boolean> decision) {
		return decision.accepted() ? Optional.of(java.util.Objects.requireNonNull(decision.value())) : decision.deferred() ? Optional.empty() : Optional.of(false);
	}
	private static <T> InventoryDecision<T> failureDecision(InventoryDecision<?> decision) {
		var reason = java.util.Objects.requireNonNull(decision.reason());
		var impact = java.util.Objects.requireNonNull(decision.impact());
		return switch (decision.kind()) {
			case DEFER -> InventoryDecision.defer(reason, impact);
			case UNSUPPORTED -> InventoryDecision.unsupported(reason, impact);
			case REJECT -> InventoryDecision.reject(reason, impact);
			case ACCEPT -> throw new IllegalArgumentException("accepted decision has no failure");
		};
	}
	public InventoryDecision<Boolean> probeDecision(InventorySourceInput input) { return probeDecision(input, true); }
	private InventoryDecision<Boolean> probeDecision(InventorySourceInput input, boolean reportRejection) {
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) return deferred(InventoryFailureReason.MEMORY_LIMIT);
		try (var reserved = workspace.get()) {
		var ticket = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (!ticket.accepted()) return deferred(counterReason(java.util.Objects.requireNonNull(ticket.limitingCounter())));
		InventorySourceAccess.@org.jetbrains.annotations.Nullable Source resolved = null;
		try (var grant = java.util.Objects.requireNonNull(ticket.ticket())) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			var resolution = validationDecision(resolver.resolutionProbeCost(input), new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), authorityWorkspaceBytes());
			if (!resolution.accepted()) return failureDecision(resolution);
			InventoryValidationBudget.Allowance allowance;
			try (var operation = java.util.Objects.requireNonNull(resolution.value())) {
				allowance = operation.allowance;
				resolved = resolver.resolve(input, effectiveDisplayCap(), allowance).orElse(null);
			}
			try (var source = resolved) {
				if (source == null) {
					if (reportRejection) InventoryCaptureDiagnostics.sourceUnavailable("runtime/input-probe", input, null, "resolve-null", null);
					retireInvalid(input); return InventoryDecision.reject(InventoryFailureReason.SOURCE_UNAVAILABLE, InventoryImpact.DEGRADED);
				}
				var evidence = validationDecision(source.evidenceValidationCost(), allowance, authorityWorkspaceBytes());
				if (!evidence.accepted()) return failureDecision(evidence);
				InventoryDecision<Boolean> sourceValidation;
				try (var operation = java.util.Objects.requireNonNull(evidence.value())) { sourceValidation = source.validationDecision(allowance); }
				if (sourceValidation.deferred()) return sourceValidation;
				boolean valid = sourceValidation.accepted() && Boolean.TRUE.equals(sourceValidation.value());
				if (!valid) {
					var probed = source;
					if (reportRejection) InventoryCaptureDiagnostics.sourceUnavailable("runtime/input-probe", input, () -> probed.key().toString(), "valid-false", null);
					retireInvalid(input);
				}
				return valid ? InventoryDecision.accept(true) : sourceValidation.accepted()
					? InventoryDecision.reject(InventoryFailureReason.SOURCE_UNAVAILABLE, InventoryImpact.DEGRADED) : sourceValidation;
			}
		} catch (InventoryItemBudget.CaptureLimitException deferred) {
			return deferred(deferReason(deferred));
		} catch (RuntimeException | LinkageError unavailable) {
			@org.jetbrains.annotations.Nullable InventorySourceAccess.Source failedSource = resolved;
			if (InventoryCaptureDiagnostics.expectedSourceUnavailable(unavailable)) {
				if (reportRejection) InventoryCaptureDiagnostics.sourceUnavailable("runtime/input-probe", input,
					failedSource == null ? null : () -> failedSource.key().toString(), "input-valid", unavailable);
			}
			else InventoryCaptureDiagnostics.warn("runtime/input-probe", input, null, () -> "tick=" + tick, unavailable);
			retireInvalid(input);
			return unavailable instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException
				? InventoryDecision.unsupported(InventoryFailureReason.UNSUPPORTED_LANE, InventoryImpact.DEGRADED)
				: InventoryDecision.reject(sourceReason(unavailable), InventoryImpact.DEGRADED);
		}
		}
	}
	/** Recovery still performs the same input-only probe; only repeated diagnostic output is coalesced. */
	public Optional<Boolean> probeRecovery(Consumer consumer) {
		return legacyProbe(probeRecoveryDecision(consumer));
	}
	public InventoryDecision<Boolean> probeRecoveryDecision(Consumer consumer) {
		var decision = probeDecision(consumer.input, !consumer.inputRejectionReported);
		var result = legacyProbe(decision);
		if (result.isPresent()) consumer.inputRejectionReported = !result.get();
		consumer.currentReason = decision.reason();
		return decision;
	}
	/** Checks the actual observation being published, including the completed result retained during a new sweep. */
	public Optional<Boolean> probe(Consumer consumer) {
		return legacyProbe(probeDecision(consumer));
	}
	public InventoryDecision<Boolean> probeDecision(Consumer consumer) {
		try (var attempt = beginProbe(consumer, true, false, true)) { return attempt.decision(); }
	}
	public InventoryDecision<InventorySourceAccess.Preparation> prepareDecision(Consumer consumer) {
		return preparationDecision(consumer, prepare(consumer));
	}
	public InventoryDecision<InventorySourceAccess.Preparation> prepareDecision(Consumer consumer, ProbeAttempt attempt) {
		return preparationDecision(consumer, prepare(consumer, attempt));
	}
	private InventoryDecision<InventorySourceAccess.Preparation> preparationDecision(Consumer consumer, InventorySourceAccess.Preparation result) {
		if (result == InventorySourceAccess.Preparation.READY) return InventoryDecision.accept(result);
		@Nullable InventoryFailureReason reason = consumer.currentReason;
		if (reason == null) reason = result == InventorySourceAccess.Preparation.INCOMPLETE ? InventoryFailureReason.SNAPSHOT_LIMIT
			: result == InventorySourceAccess.Preparation.DEFERRED ? InventoryFailureReason.ROUND_CAPACITY : InventoryFailureReason.SOURCE_UNAVAILABLE;
		return result == InventorySourceAccess.Preparation.DEFERRED ? InventoryDecision.defer(reason, InventoryImpact.DEGRADED)
			: InventoryDecision.reject(reason, InventoryImpact.DEGRADED);
	}
	/**
	 * One synchronous probe that may keep its resolved source for a later
	 * same-iteration prepare/step handoff. The attempt is strictly bound to this
	 * consumer, input, tick and display ceiling, and is single-use.
	 */
	public ProbeAttempt beginProbe(Consumer consumer) {
		return beginProbe(consumer, true, true, true);
	}
	/** Recovery keeps the original input-only probe cadence and diagnostic coalescing. */
	public ProbeAttempt beginProbeRecovery(Consumer consumer) {
		var attempt = inputProbe(consumer, !consumer.inputRejectionReported, true);
		attempt.result().ifPresent(value -> consumer.inputRejectionReported = !value);
		return attempt;
	}
	private ProbeAttempt beginProbe(Consumer consumer, boolean reportRejection, boolean retainCandidate, boolean cancelInputDelay) {
		consumer.currentReason = null;
		if (consumer.closed || consumer.invalidObservation != null)
			return new ProbeAttempt(consumer, effectiveDisplayCap(), Optional.of(false), null, null, 0);
		if (consumer.scanningAssociation != null || consumer.completedAssociation != null) {
			var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
			var checked = checkAssociations(consumer, allowance);
			return new ProbeAttempt(consumer, effectiveDisplayCap(), checked == InventorySourceAccess.Preparation.DEFERRED
				? Optional.empty() : Optional.of(checked == InventorySourceAccess.Preparation.READY), null, null, 0);
		}
		Round completed = consumer.completedRound, scanning = consumer.round;
		if (completed == null && scanning == null) {
			// Input-only recovery has no readable-source evidence. Do not infer delay.
			var attempt = inputProbe(consumer, reportRejection, retainCandidate);
			attempt.result().ifPresent(value -> {
				consumer.inputRejectionReported = !value;
				if (cancelInputDelay) consumer.probeDelay.cancel();
			});
			return attempt;
		}
		return new ProbeAttempt(consumer, effectiveDisplayCap(), probeRounds(consumer, completed, scanning), null, null, 0);
	}
	private Optional<Boolean> probeRounds(Consumer consumer, @Nullable Round completed, @Nullable Round scanning) {
		int calls = (completed != null && scanning != null && completed != scanning ? 2 : 1) + (consumer.bindingEvidence == null ? 0 : 1);
		var validationCost = consumer.bindingEvidence == null ? InventoryValidationBudget.NONE : consumer.bindingEvidence.evidenceValidationCost();
		if (completed != null) validationCost = validationCost.plus(completed.handle.evidenceValidationCost());
		if (scanning != null && scanning != completed) validationCost = validationCost.plus(scanning.handle.evidenceValidationCost());
		var validation = validationDecision(validationCost, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), 0);
		if (!validation.accepted()) { validationDelay(consumer, consumer.probeDelay, validation, "probe"); return Optional.empty(); }
		try (var operation = java.util.Objects.requireNonNull(validation.value())) {
		var workspace = memory.tryReserve(authorityWorkspaceBytes());
		if (workspace.isEmpty()) { delay(consumer, consumer.probeDelay, Reason.MEMORY.bit(), "probe"); return Optional.empty(); }
		try (var reserved = workspace.get()) {
			var admission = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, (long) calls,
				InventorySourceAccess.PROVIDER_WORK, calls * InventorySourceAccess.PROVIDER_CALL_WORK));
			if (!admission.accepted()) { consumer.currentReason = counterReason(java.util.Objects.requireNonNull(admission.limitingCounter())); delay(consumer, consumer.probeDelay, providerReasons(calls, calls * InventorySourceAccess.PROVIDER_CALL_WORK), "probe"); return Optional.empty(); }
			int used = 0;
			try (var grant = java.util.Objects.requireNonNull(admission.ticket())) {
				try {
					if (consumer.bindingEvidence != null) {
						used++;
						try {
							if (!bindingEvidenceValid(consumer, operation.allowance)) { consumer.discardObservation(); return Optional.of(false); }
						} catch (InventoryItemBudget.CaptureLimitException deferred) { consumer.currentReason = deferReason(deferred); return Optional.empty(); }
					}
					for (Round round : completed == null ? List.of(scanning) : scanning == null || completed == scanning ? List.of(completed) : List.of(completed, scanning)) {
						used++; // attempted validity calls remain charged, including exceptions and invalid retirement
						boolean valid;
						boolean reported = false;
						try { valid = !round.closed && round.handle.evidenceValid(operation.allowance); }
						catch (InventoryItemBudget.CaptureLimitException deferred) { consumer.currentReason = deferReason(deferred); return Optional.empty(); }
						catch (RuntimeException | LinkageError unavailable) {
							if (InventoryCaptureDiagnostics.providerLimit(unavailable) != null) consumer.currentReason = sourceReason(unavailable);
							if (InventoryCaptureDiagnostics.expectedSourceUnavailable(unavailable)) {
								InventoryCaptureDiagnostics.sourceUnavailable("runtime/evidence-probe", consumer.input,
									() -> round.key.toString(), "evidence-valid", unavailable);
								reported = true;
							} else {
								warn("evidence-probe", consumer, round, unavailable);
								reported = true;
							}
							valid = false;
						}
						if (!valid) {
							if (!reported) InventoryCaptureDiagnostics.sourceUnavailable("runtime/evidence-probe", consumer.input,
								() -> round.key.toString(), "valid-false", null);
							retire(round, unavailable(consumer.input.viewKey())); return Optional.of(false);
						}
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
	private ProbeAttempt inputProbe(Consumer consumer, boolean reportRejection, boolean retainCandidate) {
		consumer.currentReason = null;
		InventorySourceInput input = consumer.input;
		long probeTick = tick;
		int displayCap = effectiveDisplayCap();
		var resolutionCost = resolver.resolutionProbeCost(input);
		long resolutionWorkspace = Math.max(InventoryItemBudget.workspaceUpperBound(displayCap), resolutionCost.workspaceBytes());
		long envelope = retainCandidate ? checkedAdd(ROUND_MEMORY, resolutionWorkspace) : authorityWorkspaceBytes();
		var workspaceAdmission = memory.tryReserve(envelope);
		if (workspaceAdmission.isEmpty()) return failedProbe(consumer, displayCap, InventoryFailureReason.MEMORY_LIMIT, true);
		RetainedMemoryLedger.Ticket workspace = workspaceAdmission.get();
		var ticket = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (!ticket.accepted()) { workspace.close(); return failedProbe(consumer, displayCap, counterReason(java.util.Objects.requireNonNull(ticket.limitingCounter())), true); }
		InventorySourceAccess.@Nullable Source resolved = null;
		RetainedMemoryLedger.@Nullable Ticket retained = null;
		@Nullable Throwable primary = null;
		boolean transferred = false;
		try (var grant = java.util.Objects.requireNonNull(ticket.ticket())) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			var resolution = validationDecision(resolutionCost, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), envelope);
			if (!resolution.accepted()) { validationDelay(consumer, consumer.probeDelay, resolution, "probe"); return failedProbe(consumer, displayCap, java.util.Objects.requireNonNull(resolution.reason()), true); }
			InventoryValidationBudget.Allowance allowance;
			try (var operation = java.util.Objects.requireNonNull(resolution.value())) {
				allowance = operation.allowance;
				resolved = resolver.resolve(input, displayCap, allowance).orElse(null);
			}
			if (resolved == null) {
				if (reportRejection) InventoryCaptureDiagnostics.sourceUnavailable("runtime/input-probe", input, null, "resolve-null", null);
				retireInvalid(input); return failedProbe(consumer, displayCap, InventoryFailureReason.SOURCE_UNAVAILABLE, false);
			}
			var evidence = validationDecision(resolved.evidenceValidationCost(), allowance, envelope);
			if (!evidence.accepted()) { validationDelay(consumer, consumer.probeDelay, evidence, "probe"); return failedProbe(consumer, displayCap, java.util.Objects.requireNonNull(evidence.reason()), true); }
			InventoryDecision<Boolean> sourceValidation;
			try (var operation = java.util.Objects.requireNonNull(evidence.value())) { sourceValidation = resolved.validationDecision(allowance); }
			if (sourceValidation.deferred()) return failedProbe(consumer, displayCap, java.util.Objects.requireNonNull(sourceValidation.reason()), true);
			boolean valid = sourceValidation.accepted() && Boolean.TRUE.equals(sourceValidation.value());
			if (!valid) {
				var probed = resolved;
				if (reportRejection) InventoryCaptureDiagnostics.sourceUnavailable("runtime/input-probe", input, () -> probed.key().toString(), "valid-false", null);
				retireInvalid(input); return failedProbe(consumer, displayCap, sourceValidation.reason() == null ? InventoryFailureReason.SOURCE_UNAVAILABLE : sourceValidation.reason(), false);
			}
			if (retainCandidate && (tick != probeTick || effectiveDisplayCap() != displayCap))
				return new ProbeAttempt(consumer, displayCap, Optional.empty(), null, null, 0);
			if (!retainCandidate) {
				InventorySourceAccess.Source closing = resolved; resolved = null;
				closing.close();
				return new ProbeAttempt(consumer, displayCap, Optional.of(true), null, null, 0);
			}
			retained = workspace;
			var attempt = new ProbeAttempt(consumer, displayCap, Optional.of(true), resolved, retained, resolutionWorkspace);
			resolved = null; retained = null; transferred = true;
			return attempt;
		} catch (InventoryItemBudget.CaptureLimitException deferred) {
			primary = deferred;
			return failedProbe(consumer, displayCap, deferReason(deferred), true);
		} catch (RuntimeException | LinkageError unavailable) {
			primary = unavailable;
			@org.jetbrains.annotations.Nullable InventorySourceAccess.Source failedSource = resolved;
			if (InventoryCaptureDiagnostics.expectedSourceUnavailable(unavailable)) {
				if (reportRejection) InventoryCaptureDiagnostics.sourceUnavailable("runtime/input-probe", input,
					failedSource == null ? null : () -> failedSource.key().toString(), "input-valid", unavailable);
			}
			else InventoryCaptureDiagnostics.warn("runtime/input-probe", input, null, () -> "tick=" + tick, unavailable);
			retireInvalid(input);
			if (InventoryCaptureDiagnostics.providerLimit(unavailable) != null) {
				consumer.currentReason = sourceReason(unavailable);
				return new ProbeAttempt(consumer, displayCap, Optional.of(false), null, null, 0);
			}
			return failedProbe(consumer, displayCap,
				unavailable instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException ? InventoryFailureReason.UNSUPPORTED_LANE : sourceReason(unavailable), false);
		} catch (Error fatal) {
			primary = fatal;
			throw fatal;
		} finally {
			if (!transferred) {
				RetainedMemoryLedger.Ticket release = retained == null ? workspace : retained;
				if (resolved != null) {
					try { closeProbeCandidate(consumer, input, resolved, release); }
					catch (Error cleanup) {
						if (primary instanceof Error && !(primary instanceof LinkageError)) {
							if (primary != cleanup) primary.addSuppressed(cleanup);
						} else {
							if (primary != null && primary != cleanup) cleanup.addSuppressed(primary);
							throw cleanup;
						}
					}
				}
				else release.close();
			}
		}
	}
	private ProbeAttempt failedProbe(Consumer consumer, int cap, InventoryFailureReason reason, boolean defer) {
		consumer.currentReason = reason;
		// The source-unavailability boundary already owns its lazy cause/context
		// report and consumer transition suppression. Scheduling limits have no
		// source-failure report and use the shared limit terminal here.
		if (defer || reason != InventoryFailureReason.SOURCE_UNAVAILABLE)
			InventoryCaptureDiagnostics.limit(consumer.input, InventoryDiagnosticSite.VALIDATION, InventoryOperation.VALIDATE,
				reason, defer ? InventoryOutcome.DEFERRED : InventoryOutcome.REJECTED, "input-probe", -1, -1, -1, null);
		return new ProbeAttempt(consumer, cap, defer ? Optional.empty() : Optional.of(false), null, null, 0);
	}
	/** Releases an untransferred candidate exactly once; a failed close is an input-probe failure. */
	private void closeProbeCandidate(Consumer owner, InventorySourceInput input, InventorySourceAccess.Source candidate, RetainedMemoryLedger.@Nullable Ticket ticket) {
		try {
			try { candidate.close(); }
			catch (RuntimeException | LinkageError unavailable) {
				if (InventoryCaptureDiagnostics.expectedSourceUnavailable(unavailable))
					InventoryCaptureDiagnostics.sourceUnavailable("runtime/input-probe", input,
						() -> candidate.key().toString(), "input-close", unavailable);
				else InventoryCaptureDiagnostics.warn("runtime/input-probe", input, null, () -> "tick=" + tick, unavailable);
				retireInvalid(input);
				if (!owner.closed && !owner.invalidated()) owner.discardObservation();
			}
		} finally {
			if (ticket != null) ticket.close();
		}
	}
	/**
	 * One synchronous single-consumer probe. A successful input check may retain
	 * one resolved source and its resolution/metadata envelope until the same
	 * iteration's prepare or step takes it over. The attempt is bound to the
	 * exact consumer, input, tick and display ceiling, is single-use, and closes
	 * any untransferred candidate on every exit.
	 */
	@NotNullByDefault
	public final class ProbeAttempt implements AutoCloseable {
		private final Consumer consumer;
		private final InventorySourceInput input;
		private final long tick;
		private final Thread thread = Thread.currentThread();
		private final int displayCeiling;
		private final Optional<Boolean> result;
		private final InventoryDecision<Boolean> decision;
		private final long metadataUpperBound;
		private @Nullable InventorySourceAccess.Source source;
		private @Nullable RetainedMemoryLedger.Ticket ticket;
		private boolean transferred, closed;
		private ProbeAttempt(Consumer consumer, int displayCeiling, Optional<Boolean> result,
			@Nullable InventorySourceAccess.Source source, @Nullable RetainedMemoryLedger.Ticket ticket, long metadataUpperBound) {
			this.consumer = consumer; this.input = consumer.input; this.tick = InventoryRuntime.this.tick;
			this.displayCeiling = displayCeiling; this.result = result; this.source = source; this.ticket = ticket;
			decision = consumer.currentReason == InventoryFailureReason.UNSUPPORTED_LANE ? InventoryDecision.unsupported(InventoryFailureReason.UNSUPPORTED_LANE, InventoryImpact.DEGRADED)
				: result.isEmpty() ? InventoryDecision.defer(consumer.currentReason == null ? InventoryFailureReason.ITEM_WORK_LIMIT : consumer.currentReason, InventoryImpact.DEGRADED)
				: result.get() ? InventoryDecision.accept(true) : InventoryDecision.reject(consumer.currentReason == null ? InventoryFailureReason.SOURCE_UNAVAILABLE : consumer.currentReason, InventoryImpact.DEGRADED);
			this.metadataUpperBound = metadataUpperBound;
		}
		public Optional<Boolean> result() { return result; }
		public InventoryDecision<Boolean> decision() { return decision; }
		private Adoption take(Consumer expectedConsumer, long expectedTick, int expectedDisplayCeiling) {
			if (closed) throw new IllegalStateException("closed inventory probe attempt");
			if (transferred) throw new IllegalStateException("inventory probe attempt was already transferred");
			if (expectedConsumer != consumer || !expectedConsumer.input.equals(input))
				throw new IllegalStateException("inventory probe attempt consumer or input mismatch");
			if (thread != Thread.currentThread()) throw new IllegalStateException("inventory probe attempt thread mismatch");
			if (expectedTick != tick || expectedDisplayCeiling != displayCeiling) {
				close();
				return new Adoption(null, true);
			}
			InventorySourceAccess.@Nullable Source candidate = source;
			RetainedMemoryLedger.@Nullable Ticket held = ticket;
			if (candidate == null || held == null) {
				if (held != null) { ticket = null; held.close(); }
				transferred = true;
				return new Adoption(null, false);
			}
			var adoption = new Adoption(new Candidate(consumer, input, candidate, held, metadataUpperBound), false);
			source = null; ticket = null; transferred = true;
			return adoption;
		}
		private boolean belongsTo(InventoryRuntime runtime) { return InventoryRuntime.this == runtime; }
		@Override public void close() {
			if (closed || transferred) return;
			closed = true;
			InventorySourceAccess.@Nullable Source candidate = source; source = null;
			RetainedMemoryLedger.@Nullable Ticket held = ticket; ticket = null;
			if (candidate == null) { if (held != null) held.close(); return; }
			closeProbeCandidate(consumer, input, candidate, held);
		}
	}
	@NotNullByDefault
	private record Adoption(@Nullable Candidate candidate, boolean deferred) {}
	@NotNullByDefault
	private final class Candidate implements AutoCloseable {
		private final Consumer consumer;
		private final InventorySourceInput input;
		private final InventorySourceAccess.Source source;
		private final RetainedMemoryLedger.Ticket ticket;
		private final long metadataUpperBound;
		private boolean transferred, closed;
		private Candidate(Consumer consumer, InventorySourceInput input, InventorySourceAccess.Source source, RetainedMemoryLedger.Ticket ticket, long metadataUpperBound) {
			this.consumer = consumer; this.input = input; this.source = source; this.ticket = ticket; this.metadataUpperBound = metadataUpperBound;
		}
		private InventorySourceAccess.Source source() {
			if (closed || transferred) throw new IllegalStateException("inventory probe candidate is no longer owned");
			return source;
		}
		private RetainedMemoryLedger.Ticket ticket() {
			if (closed || transferred) throw new IllegalStateException("inventory probe candidate is no longer owned");
			return ticket;
		}
		private long metadataUpperBound() { return metadataUpperBound; }
		private void transfer() {
			if (closed || transferred) throw new IllegalStateException("inventory probe candidate is no longer owned");
			transferred = true;
		}
		@Override public void close() {
			if (closed || transferred) return;
			closed = true;
			closeProbeCandidate(consumer, input, source, ticket);
		}
	}

	public Optional<Observation> step(Consumer consumer) {
		return step(consumer, InventorySourceAccess.MAX_STEP);
	}
	/** One step may consume the same-iteration probe candidate before its first resolve position. */
	public Optional<Observation> step(Consumer consumer, ProbeAttempt attempt) {
		return step(consumer, InventorySourceAccess.MAX_STEP, attempt);
	}
	public Optional<Observation> step(Consumer consumer, int slotBound) {
		return step(consumer, slotBound, null);
	}
	/** Fix the physical capture route before scan or logical allowance is considered. */
	public InventorySourceAccess.Preparation prepare(Consumer consumer) {
		return prepare(consumer, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE));
	}
	/** Consumes one same-iteration probe attempt; an unusable attempt defers instead of resolving again. */
	public InventorySourceAccess.Preparation prepare(Consumer consumer, ProbeAttempt attempt) {
		java.util.Objects.requireNonNull(attempt, "attempt");
		return prepare(consumer, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), false, attempt);
	}
	@NotNullByDefault
	public enum JoinResult { JOINED, NO_MATCH, DEFERRED, INVALID }
	/** Metadata-only admission: never starts a round, prepares a catalogue, captures or visits a slot. */
	public JoinResult tryJoinInProgress(Consumer consumer) {
		var outcome = prepare(consumer, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE), true);
		if (outcome == InventorySourceAccess.Preparation.DEFERRED) return JoinResult.DEFERRED;
		if (outcome != InventorySourceAccess.Preparation.READY) return JoinResult.INVALID;
		return consumer.scanningAssociation == null ? JoinResult.NO_MATCH : JoinResult.JOINED;
	}
	private InventorySourceAccess.Preparation prepare(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		return prepare(consumer, allowance, false);
	}
	private InventorySourceAccess.Preparation prepare(Consumer consumer, InventoryValidationBudget.Allowance allowance, boolean joinOnly) {
		return prepare(consumer, allowance, joinOnly, null);
	}
	private InventorySourceAccess.Preparation prepare(Consumer consumer, InventoryValidationBudget.Allowance allowance,
		boolean joinOnly, @Nullable ProbeAttempt attempt) {
		@Nullable Candidate candidate = null;
		if (attempt != null) {
			if (!attempt.belongsTo(this)) throw new IllegalStateException("inventory probe attempt runtime mismatch");
			var adoption = attempt.take(consumer, tick, effectiveDisplayCap());
			if (adoption.deferred() || attempt.result().isEmpty()) return InventorySourceAccess.Preparation.DEFERRED;
			if (!attempt.result().get()) return InventorySourceAccess.Preparation.UNAVAILABLE;
			candidate = adoption.candidate();
		}
		try { return prepareWithCandidate(consumer, allowance, joinOnly, candidate); }
		finally { if (candidate != null) candidate.close(); }
	}
	private InventorySourceAccess.Preparation prepareWithCandidate(Consumer consumer, InventoryValidationBudget.Allowance allowance,
		boolean joinOnly, @Nullable Candidate candidate) {
		consumer.currentReason = null;
		boolean pooled = certifiedPooling && consumer.input.resourceKind() == InventoryResourceKind.ITEM;
		if (consumer.closed) return InventorySourceAccess.Preparation.UNAVAILABLE;
		if (consumer.invalidObservation != null || consumer.pendingObservation != null)
			return InventorySourceAccess.Preparation.UNAVAILABLE;
		// Retained unprepared rounds skip resolution (and its ValidationOperation) on a later attempt.
		installFluidGrants(allowance);
		@Nullable Round handoff = pooled ? consumer.successorEntitlement
			: consumer.subject instanceof TrackingSubject && consumer.completedRound != null ? consumer.completedRound.successor : null;
		if (consumer.round == null && consumer.completedRound != null && !consumer.completedRound.predecessors.isEmpty()) {
			delay(consumer, consumer.admissionDelay, Reason.ROUND_CAPACITY.bit(), "handoff");
			return InventorySourceAccess.Preparation.DEFERRED; // finish the physical handoff before another replacement forks
		}
		if (pooled && consumer.round == null && consumer.completedRound != null && consumer.completedRound.incomingEntitlements != 0) {
			delay(consumer, consumer.admissionDelay, Reason.ROUND_CAPACITY.bit(), "handoff"); return InventorySourceAccess.Preparation.DEFERRED;
		}
		var bindingCheck = checkBindingEvidence(consumer, allowance);
		if (bindingCheck != InventorySourceAccess.Preparation.READY) return bindingCheck;
		if (consumer.round != null && consumer.round.sweep != null) return joinOnly ? InventorySourceAccess.Preparation.READY : preparePooledCatalog(consumer, allowance);
		if (joinOnly && (!pooled || consumer.round != null || handoff != null && handoff.sweep == null)) return InventorySourceAccess.Preparation.READY;
		boolean legacyWidened = consumer.subject instanceof TrackingSubject && pooled
			&& !(handoff == null ? policy : handoff.policy).equals(new InventorySharedRead.Policy(true, true));
		if (consumer.round == null && handoff != null && handoff.sweep == null && consumer.input.sameBinding(handoff.input)) return prepareHandoff(consumer, handoff, allowance);
		if (consumer.round == null) {
			for (Consumer peer : consumers) if (!joinOnly && !legacyWidened && handoff == null && peer != consumer && peer.round != null && peer.round.sweep == null && peer.input.sameBinding(consumer.input)
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
			boolean canCreate = !joinOnly && handoff == null && scanningRounds < MAX_ROUNDS && retainedRounds < retainedLimit;
			if (joinOnly && handoff == null && scanningRounds == 0) return InventorySourceAccess.Preparation.READY;
			if (!pooled && !canCreate && handoff == null && liveRounds.stream().noneMatch(round -> round.key.readScope().equals(consumer.input.viewKey())
				&& shareable(consumer, round))) {
				if (joinOnly) return InventorySourceAccess.Preparation.READY;
				delay(consumer, consumer.admissionDelay, Reason.ROUND_CAPACITY.bit(), "admission"); return InventorySourceAccess.Preparation.DEFERRED;
			}
			var resolutionCost = resolver.resolutionProbeCost(consumer.input);
			long resolutionWorkspace = Math.max(InventoryItemBudget.workspaceUpperBound(effectiveDisplayCap()), resolutionCost.workspaceBytes());
			var retainedAdmission = candidate == null
				? memory.tryReserve(checkedAdd(ROUND_MEMORY, resolutionWorkspace))
				: Optional.of(candidate.ticket());
			if (retainedAdmission.isEmpty()) { delay(consumer, consumer.admissionDelay, Reason.MEMORY.bit(), "admission"); return InventorySourceAccess.Preparation.DEFERRED; }
			RetainedMemoryLedger.Ticket retained = retainedAdmission.get();
			if (candidate != null) resolutionWorkspace = candidate.metadataUpperBound();
			boolean ownsReservation = true;
			SourceAccess.@Nullable Handle openedHandle = null;
			try {
				int displayCap = effectiveDisplayCap();
				AdmittedSource[] resolvedSource = new AdmittedSource[1];
				var access = sourceAccess(consumer.input, displayCap, source -> resolvedSource[0] = new AdmittedSource(source, true, memory, monitor, lane(consumer)));
				var scope = new SourceAccess.ReadScope(consumer.input.viewKey(), Set.of(consumer.input.resourceKind().fieldId()));
				if (candidate != null) {
					var admitted = new AdmittedSource(candidate.source(), true, memory, monitor, lane(consumer));
					resolvedSource[0] = admitted;
					candidate.transfer();
					var opened = access.adopt(admitted, scope);
					if (!(opened instanceof SourceAccess.OpenResult.Started started)) {
						if (opened == SourceAccess.OpenResult.Unstarted.DEFERRED) return InventorySourceAccess.Preparation.DEFERRED;
						consumer.pendingObservation = unavailable(scope.viewKey());
						InventoryCaptureDiagnostics.sourceUnavailable("runtime/prepare", consumer.input, null, "open-unavailable", null);
						return InventorySourceAccess.Preparation.UNAVAILABLE;
					}
					openedHandle = started.handle();
				} else {
					var resolution = validationDecision(resolutionCost, allowance, resolutionWorkspace);
					if (!resolution.accepted()) { validationDelay(consumer, consumer.admissionDelay, resolution, "admission"); return InventorySourceAccess.Preparation.DEFERRED; }
					InventoryDecision<SourceAccess.Descriptor> resolved;
					try (var operation = java.util.Objects.requireNonNull(resolution.value())) {
						resolved = access.resolveDecision(MinecraftBlockReadSources.detached(consumer.input.target()), scope, physical, operation.allowance);
					}
					if (!resolved.accepted()) {
						consumer.currentReason = resolved.reason();
						if (resolved.deferred()) {
							validationDelay(consumer, consumer.admissionDelay, resolved, "admission");
							return InventorySourceAccess.Preparation.DEFERRED;
						}
						consumer.cancelDelays();
						consumer.pendingObservation = unavailable(scope.viewKey());
						InventoryCaptureDiagnostics.sourceUnavailable("runtime/prepare", consumer.input, null,
							resolved.unsupported() ? "resolve-unsupported" : "resolve-unavailable", null);
						return InventorySourceAccess.Preparation.UNAVAILABLE;
					}
					var opened = access.open(java.util.Objects.requireNonNull(resolved.value()), scope, physical);
					if (!(opened instanceof SourceAccess.OpenResult.Started started)) {
						if (opened == SourceAccess.OpenResult.Unstarted.DEFERRED) return InventorySourceAccess.Preparation.DEFERRED;
						consumer.pendingObservation = unavailable(scope.viewKey());
						InventoryCaptureDiagnostics.sourceUnavailable("runtime/prepare", consumer.input, null, "open-unavailable", null);
						return InventorySourceAccess.Preparation.UNAVAILABLE;
					}
					openedHandle = started.handle();
				}
				if (consumer.closed || consumer.invalidated()) return InventorySourceAccess.Preparation.UNAVAILABLE;
				SourceKey resolvedKey = resolvedSource[0].key();
				long sourceBytes = resolvedSource[0].sourceRetainedBytes();
				// The outer resolution reservation is still held: transfer its owned metadata
				// into the round/binding ticket before releasing scratch. Catalogue growth is separate.
				if (sourceBytes < 0 || sourceBytes > resolutionWorkspace)
					throw new IllegalStateException("source retention exceeds resolution admission");
				InventorySharedRead.@Nullable Port port = pooled ? ((InventorySourceAccess.InventoryHandle) openedHandle).sharedRead() : null;
				if (pooled && ((InventorySourceAccess.InventoryHandle) openedHandle).certifiedResourceSharing() && port != null) {
					var outcome = admitPooled(consumer, handoff, resolvedKey, (InventorySourceAccess.InventoryHandle) openedHandle,
						port, retained, canCreate, allowance, joinOnly);
					if (consumer.scanningAssociation != null) { ownsReservation = false; openedHandle = null; }
					return outcome;
				}
				if (joinOnly) return InventorySourceAccess.Preparation.READY;
				if (handoff != null && handoff.sweep != null) {
					retire(handoff, unavailable(scope.viewKey())); consumer.discardObservation();
					return InventorySourceAccess.Preparation.UNAVAILABLE;
				}
				// Aliased hit positions still resolve their canonical key before joining a pinned handoff.
				if (handoff != null && !handoff.key.equals(resolvedKey)) {
					retire(consumer.completedRound, unavailable(scope.viewKey()));
					return InventorySourceAccess.Preparation.UNAVAILABLE;
				}
				if (handoff != null) {
					if (retainBindingEvidence(consumer, handoff, (InventorySourceAccess.InventoryHandle) openedHandle, resolvedSource[0], retained)) {
						ownsReservation = false; openedHandle = null;
					}
					if (ownsReservation) { retained.close(); ownsReservation = false; }
					return prepareHandoff(consumer, handoff, allowance);
				}
				Round round = rounds.get(new ExactKey(resolvedKey, policy));
				if (consumer.subject instanceof PreviewSubject) for (Round previewRound : liveRounds) {
					if (previewRound.sweep == null && previewRound.key.equals(resolvedKey) && shareable(consumer, previewRound)) { round = previewRound; break; }
				}
				// A newly selected Ping never inherits a completed preview count.
				if (round != null && !shareable(consumer, round)) round = null;
				if (round == null) {
					// Another hit position may resolve to a compatible canonical alias even at capacity.
					// Only an actual new physical round needs the normal/replacement admission slot.
					if (!canCreate) { delay(consumer, consumer.admissionDelay, Reason.ROUND_CAPACITY.bit(), "admission"); return InventorySourceAccess.Preparation.DEFERRED; }
					if (consumer.completedRound != null && consumer.completedRound.sweep == null && consumer.completedRound.policy.equals(policy)
						&& !consumer.completedRound.key.equals(resolvedKey)) {
						retire(consumer.completedRound, unavailable(scope.viewKey()));
						return InventorySourceAccess.Preparation.UNAVAILABLE; // canonical view topology changed, not admission pressure
					}
					retainMetadata(resolvedSource[0], retained);
					round = new Round(resolvedKey, consumer.input, (InventorySourceAccess.InventoryHandle) openedHandle, resolvedSource[0], retained, tick, ++roundSequence, consumer.subject instanceof TrackingSubject, displayCap, policy);
					retainedRounds++;
					scanningRounds++;
					rounds.put(round.lookupKey, round); liveRounds.add(round);
					ownsReservation = false; openedHandle = null;
				}
				else if (retainBindingEvidence(consumer, round, (InventorySourceAccess.InventoryHandle) openedHandle, resolvedSource[0], retained)) {
					ownsReservation = false; openedHandle = null;
				}
				join(consumer, round);
				var admittedBinding = checkBindingEvidence(consumer, allowance);
				if (admittedBinding != InventorySourceAccess.Preparation.READY) return admittedBinding;
			} finally {
				try { if (openedHandle != null) openedHandle.close(); }
				finally { if (ownsReservation) retained.close(); }
			}
		}
		Round round = consumer.round;
		consumer.admissionDelay.progress(cycle(consumer));
		if (round.preparation != null) {
			if ((round.preparation == InventorySourceAccess.Preparation.INCOMPLETE || round.preparation == InventorySourceAccess.Preparation.UNAVAILABLE)
				&& round.terminalReason != null) consumer.currentReason = round.terminalReason;
			if (round.preparation == InventorySourceAccess.Preparation.INCOMPLETE && consumer.currentReason == null) consumer.currentReason = InventoryFailureReason.SNAPSHOT_LIMIT;
			return round.preparation;
		}
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
	private InventorySourceAccess.Preparation admitPooled(Consumer consumer, @Nullable Round entitlement, SourceKey key,
		InventorySourceAccess.InventoryHandle handle, InventorySharedRead.Port port, RetainedMemoryLedger.Ticket leaseMemory,
		boolean canCreate, InventoryValidationBudget.Allowance allowance, boolean joinOnly) {
		// The pure cost-directory walk is itself bounded by actual retained rounds and members.
		long directoryWork = 4096L + liveRounds.size() * 128L;
		if (!claimDetachedItemWork(directoryWork)) { requestDelay(consumer, consumer.admissionDelay, InventoryFailureReason.ITEM_WORK_LIMIT,
			"pool-directory", directoryWork, physical.used(ITEM_WORK), physical.limit(ITEM_WORK)); return InventorySourceAccess.Preparation.DEFERRED; }
		InventoryValidationBudget.Cost bookkeepingCost;
		try { bookkeepingCost = poolBookkeepingCost(port); }
		catch (InventoryValidationBudget.ItemResolutionDeferred deferred) { requestDelay(consumer, consumer.admissionDelay, deferred.reason(),
			"pool-bookkeeping", -1, -1, -1); return InventorySourceAccess.Preparation.DEFERRED; }
		var bookkeeping = memory.tryReserve(bookkeepingCost.workspaceBytes());
		if (bookkeeping.isEmpty()) { delay(consumer, consumer.admissionDelay, Reason.MEMORY.bit(), "pool-bookkeeping"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var bookkeepingOperation = bookkeeping.get()) {
		if (!claimDetachedItemWork(bookkeepingCost.workUnits())) { requestDelay(consumer, consumer.admissionDelay, InventoryFailureReason.ITEM_WORK_LIMIT,
			"pool-bookkeeping", bookkeepingCost.workUnits(), physical.used(ITEM_WORK), physical.limit(ITEM_WORK)); return InventorySourceAccess.Preparation.DEFERRED; }
		var frozen = entitlement == null ? policy : entitlement.policy;
		var lookup = new PooledKey(port.proof().graph(), frozen, frozen.distinguishPlayers() ? consumer.input.readOwner() : null,
			frozen.distinguishFaces() ? consumer.input.face() : null);
		@Nullable Round selected = entitlement == null ? oldestPooledCandidate(consumer, lookup, port.proof(), key) : entitlement;
		if (selected != null && (selected.sweep == null || !selected.lookupKey.equals(lookup) || !selected.sweep.proof.equals(port.proof()))) {
			consumer.discardObservation(); return InventorySourceAccess.Preparation.UNAVAILABLE;
		}
		var expected = selected == null ? port.proof() : java.util.Objects.requireNonNull(selected.sweep).proof;
		var validation = validationDecision(port.validationCost(), allowance, 0);
		if (!validation.accepted()) { validationDelay(consumer, consumer.admissionDelay, validation, "pool-validation"); return InventorySourceAccess.Preparation.DEFERRED; }
		InventorySharedRead.Check check;
		try (var operation = java.util.Objects.requireNonNull(validation.value())) {
			var probe = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			if (!probe.accepted()) { var counter = java.util.Objects.requireNonNull(probe.limitingCounter()); requestDelay(consumer, consumer.admissionDelay,
				counterReason(counter), "pool-probe", counter.equals(InventorySourceAccess.PROBES) ? 1 : InventorySourceAccess.PROVIDER_CALL_WORK,
				physical.used(counter), physical.limit(counter)); return InventorySourceAccess.Preparation.DEFERRED; }
			try (var grant = java.util.Objects.requireNonNull(probe.ticket())) {
				grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
				check = port.validate(expected, operation.allowance);
			}
		}
		catch (InventoryItemBudget.CaptureLimitException deferred) { requestDelay(consumer, consumer.admissionDelay, deferred.reason(),
			"pool-validation", -1, -1, -1); return InventorySourceAccess.Preparation.DEFERRED; }
		catch (RuntimeException | LinkageError failure) {
			warnPooled("metadata-validate", consumer, selected, expected, "pool-admission", failure);
			if (InventoryCaptureDiagnostics.providerLimit(failure) != null) {
				consumer.currentReason = sourceReason(failure);
				if (selected != null) retire(selected, unavailable(consumer.input.viewKey()));
				consumer.discardObservation(); return InventorySourceAccess.Preparation.UNAVAILABLE;
			}
			check = InventorySharedRead.Check.UNAVAILABLE;
		}
		if (check == InventorySharedRead.Check.DEFERRED) { requestDelay(consumer, consumer.admissionDelay, InventoryFailureReason.ITEM_WORK_LIMIT,
			"pool-validation", -1, -1, -1); return InventorySourceAccess.Preparation.DEFERRED; }
		if (check != InventorySharedRead.Check.VALID) {
			consumer.currentReason = check == InventorySharedRead.Check.AUTHORITY_DENIED ? InventoryFailureReason.SOURCE_AUTHORITY_DENIED
				: check == InventorySharedRead.Check.STRUCTURE_INVALID ? InventoryFailureReason.SOURCE_CHANGED : InventoryFailureReason.SOURCE_UNAVAILABLE;
			if (selected != null && check != InventorySharedRead.Check.AUTHORITY_DENIED) retire(selected, unavailable(consumer.input.viewKey()));
			consumer.discardObservation(); return InventorySourceAccess.Preparation.UNAVAILABLE;
		}
		// Metadata callbacks can finish/retire a candidate, revoke its ownership or change policy.
		// No observation entitlement exists until the independent association is installed.
		if (consumer.closed || consumer.invalidated()) return InventorySourceAccess.Preparation.UNAVAILABLE;
		if (entitlement == null && !frozen.equals(policy)) return InventorySourceAccess.Preparation.DEFERRED;
		if (entitlement == null) {
			@Nullable Round current = oldestPooledCandidate(consumer, lookup, port.proof(), key);
			if (selected != null && current == null) return InventorySourceAccess.Preparation.DEFERRED;
			selected = current;
		} else if (consumer.successorEntitlement != selected || selected.closed || selected.journal == null || selected.scanners == 0)
			return InventorySourceAccess.Preparation.DEFERRED;
		if (selected == null) {
			if (joinOnly) return InventorySourceAccess.Preparation.READY;
			if (!canCreate) { delay(consumer, consumer.admissionDelay, Reason.ROUND_CAPACITY.bit(), "pool-round"); return InventorySourceAccess.Preparation.DEFERRED; }
			if (scanningRounds >= MAX_ROUNDS || retainedRounds >= (consumer.completedRound == null ? MAX_ROUNDS : MAX_RETAINED_ROUNDS))
				return InventorySourceAccess.Preparation.DEFERRED;
			// The kept proof remains owned after its original sampler departs. Charge its complete source-profile envelope independently.
			var roundMemory = memory.tryReserve(checkedAdd(ROUND_MEMORY, port.retainedBytes()));
			if (roundMemory.isEmpty()) { delay(consumer, consumer.admissionDelay, Reason.MEMORY.bit(), "pool-round"); return InventorySourceAccess.Preparation.DEFERRED; }
			roundMemory.get().commit(checkedAdd(ROUND_MEMORY, port.retainedBytes()));
			selected = new Round(key, consumer.input, lookup, new PooledSweep(expected), roundMemory.get(), tick, ++roundSequence, effectiveDisplayCap());
			retainedRounds++; scanningRounds++; liveRounds.add(selected); rounds.put(lookup, selected);
		}
		// sourceRetainedBytes already includes this exact port/proof. Never charge that port twice to the lease.
		leaseMemory.commit(checkedAdd(LEASE_MEMORY, handle.sourceRetainedBytes()));
		// Equal proofs keep this exact lease's own witnesses. The round's proof may have been supplied by a departed creator.
		consumer.scanningAssociation = new Association(selected, new BindingLease(consumer.input, handle, port, leaseMemory), port.proof());
		join(consumer, selected);
		return joinOnly ? InventorySourceAccess.Preparation.READY : preparePooledCatalog(consumer, allowance);
		}
	}
	private @Nullable Round oldestPooledCandidate(Consumer consumer, PooledKey lookup, InventorySharedRead.Proof proof, SourceKey key) {
		for (Round candidate : liveRounds) if (pooledCandidate(consumer, candidate, lookup, proof, key)) return candidate;
		return null;
	}
	/** Full certificate and Preview's active-participant rule, shared by normal and JOIN_ONLY admission. */
	private boolean pooledCandidate(Consumer consumer, Round round, PooledKey lookup, InventorySharedRead.Proof proof, SourceKey key) {
		if (round.sweep == null || !shareable(consumer, round) || !round.lookupKey.equals(lookup) || !round.sweep.proof.equals(proof)) return false;
		if (consumer.subject instanceof TrackingSubject || round.activeTracking != 0) return true;
		for (Consumer peer : consumers) if (!peer.closed && peer.scanning && peer.serviceableDemand && peer.round == round && peer.scanningAssociation != null
			&& peer.scanningAssociation.lease.handle.descriptor().key().equals(key)) return true;
		return false;
	}
	private InventorySourceAccess.Preparation preparePooledCatalog(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		Round round = consumer.round;
		PooledSweep sweep = java.util.Objects.requireNonNull(round.sweep);
		if (sweep.catalog != null) return InventorySourceAccess.Preparation.READY;
		Association association = java.util.Objects.requireNonNull(consumer.scanningAssociation);
		var retained = memory.tryReserve(InventoryItemCodec.Catalog.initialModeledBytes());
		if (retained.isEmpty()) { delay(consumer, consumer.admissionDelay, Reason.MEMORY.bit(), "pool-catalog"); return InventorySourceAccess.Preparation.DEFERRED; }
		boolean transferred = false;
		try {
			var validation = validationDecision(association.lease.port.catalogCreationCost(), allowance, 0);
			if (!validation.accepted()) { validationDelay(consumer, consumer.admissionDelay, validation, "pool-catalog"); return InventorySourceAccess.Preparation.DEFERRED; }
			try (var operation = java.util.Objects.requireNonNull(validation.value())) {
				var probe = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
				if (!probe.accepted()) { var counter = java.util.Objects.requireNonNull(probe.limitingCounter()); requestDelay(consumer, consumer.admissionDelay,
					counterReason(counter), "pool-catalog-probe", counter.equals(InventorySourceAccess.PROBES) ? 1 : InventorySourceAccess.PROVIDER_CALL_WORK,
					physical.used(counter), physical.limit(counter)); return InventorySourceAccess.Preparation.DEFERRED; }
				try (var grant = java.util.Objects.requireNonNull(probe.ticket())) {
					grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
					sweep.catalog = association.lease.port.createCatalog(round.appliedBytes, operation.allowance);
				}
			}
			retained.get().commit(sweep.catalog.modeledRetainedBytes()); round.nativeMemory = retained.get(); transferred = true;
			round.preparation = InventorySourceAccess.Preparation.READY;
			return round.preparation;
		} catch (InventorySharedRead.ReadFailure failure) {
			warnPooled("catalog-create", consumer, round, association.expected, "catalog-create", failure);
			if (providerFailure(consumer, association, failure)) return InventorySourceAccess.Preparation.UNAVAILABLE;
			pooledFailure(consumer, association, failure.check()); return failure.check() == InventorySharedRead.Check.DEFERRED
				? InventorySourceAccess.Preparation.DEFERRED : InventorySourceAccess.Preparation.UNAVAILABLE;
		} catch (InventoryItemBudget.CaptureLimitException capacity) {
			requestDelay(consumer, consumer.admissionDelay, capacity.reason(), "pool-catalog", -1, -1, -1);
			return InventorySourceAccess.Preparation.DEFERRED;
		} catch (RuntimeException | LinkageError failure) {
			warnPooled("catalog-create", consumer, round, association.expected, "catalog-create", failure);
			if (providerFailure(consumer, association, failure)) return InventorySourceAccess.Preparation.UNAVAILABLE;
			pooledFailure(consumer, association, InventorySharedRead.Check.UNAVAILABLE); return InventorySourceAccess.Preparation.UNAVAILABLE;
		} finally { if (!transferred) retained.get().close(); }
	}
	private boolean providerFailure(Consumer consumer, Association association, Throwable failure) {
		@Nullable var limit = InventoryCaptureDiagnostics.providerLimit(failure);
		if (limit == null) return false;
		pooledFailure(consumer, association, InventorySharedRead.Check.UNAVAILABLE);
		consumer.currentReason = limit.reason();
		return true;
	}
	private void pooledFailure(Consumer consumer, Association association, InventorySharedRead.Check check) {
		if (check == InventorySharedRead.Check.DEFERRED) {
			requestDelay(consumer, consumer.probeDelay, InventoryFailureReason.ITEM_WORK_LIMIT, "pool-check", -1, -1, -1);
			return;
		}
		consumer.currentReason = switch (check) {
			case AUTHORITY_DENIED -> InventoryFailureReason.SOURCE_AUTHORITY_DENIED;
			case STRUCTURE_INVALID -> InventoryFailureReason.SOURCE_CHANGED;
			case DEFERRED -> InventoryFailureReason.ITEM_WORK_LIMIT;
			case UNAVAILABLE -> InventoryFailureReason.SOURCE_UNAVAILABLE;
			case VALID -> null;
		};
		if (check == InventorySharedRead.Check.AUTHORITY_DENIED) consumer.discardObservation();
		else if (check != InventorySharedRead.Check.DEFERRED) {
			for (Round affected : List.copyOf(liveRounds)) if (affected.sweep != null && affected.sweep.proof.equals(association.expected))
				retire(affected, unavailable(consumer.input.viewKey()));
		}
	}
	/** Keep the separately resolved alias input's evidence under the already admitted workspace ticket. */
	private boolean retainBindingEvidence(Consumer consumer, Round round, InventorySourceAccess.InventoryHandle handle,
		AdmittedSource source, RetainedMemoryLedger.Ticket ticket) {
		if (!(consumer.input.target() instanceof nx.pingwheel.common.domain.Target.ExternalBlockTarget) && !consumer.publicationRequired
			|| consumer.bindingEvidence != null || consumer.input.sameBinding(round.input)) return false;
		retainMetadata(source, ticket);
		consumer.bindingEvidence = handle; consumer.bindingMemory = ticket;
		return true;
	}
	private boolean bindingEvidenceValid(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		try { return consumer.bindingEvidence == null || consumer.bindingEvidence.evidenceValid(allowance); }
		catch (InventoryItemBudget.CaptureLimitException deferred) { throw deferred; }
		catch (RuntimeException | LinkageError unavailable) { if (InventoryCaptureDiagnostics.providerLimit(unavailable) != null) consumer.currentReason = sourceReason(unavailable);
			warn("binding-probe", consumer, consumer.round, unavailable); return false; }
	}
	/** Cache consumption checks its own original binding, not only the peer whose physical sweep was shared. */
	private InventorySourceAccess.Preparation checkBindingEvidence(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		if (consumer.scanningAssociation != null || consumer.completedAssociation != null) return checkAssociations(consumer, allowance);
		if (consumer.bindingEvidence == null) return InventorySourceAccess.Preparation.READY;
		var validation = validationDecision(consumer.bindingEvidence.evidenceValidationCost(), allowance, 0);
		if (!validation.accepted()) { validationDelay(consumer, consumer.probeDelay, validation, "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var operation = java.util.Objects.requireNonNull(validation.value())) {
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
				} catch (InventoryItemBudget.CaptureLimitException deferred) { consumer.currentReason = deferReason(deferred); return InventorySourceAccess.Preparation.DEFERRED; }
				consumer.probeDelay.progress(cycle(consumer));
				return InventorySourceAccess.Preparation.READY;
			}
		}
		}
	}
	private InventorySourceAccess.Preparation checkAssociations(Consumer consumer, InventoryValidationBudget.Allowance allowance) {
		var cost = InventoryValidationBudget.NONE;
		int calls = 0;
		for (int index = 0; index < 2; index++) {
			@Nullable Association association = index == 0 ? consumer.completedAssociation : consumer.scanningAssociation;
			@Nullable Round round = index == 0 ? consumer.completedRound : consumer.round;
			if (index == 1 && round == consumer.completedRound) continue;
			if (association != null) { cost = cost.plus(association.lease.port.validationCost()); calls++; }
			else if (round != null) { cost = cost.plus(round.handle.evidenceValidationCost()); calls++; }
		}
		var validation = validationDecision(cost, allowance, 0);
		if (!validation.accepted()) { validationDelay(consumer, consumer.probeDelay, validation, "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var operation = java.util.Objects.requireNonNull(validation.value())) {
			var probe = physical.tryReserveDecision(Map.of(InventorySourceAccess.PROBES, (long) calls, InventorySourceAccess.PROVIDER_WORK, calls * InventorySourceAccess.PROVIDER_CALL_WORK));
			if (!probe.accepted()) { consumer.currentReason = counterReason(java.util.Objects.requireNonNull(probe.limitingCounter())); return InventorySourceAccess.Preparation.DEFERRED; }
			int attempted = 0;
			try (var grant = java.util.Objects.requireNonNull(probe.ticket())) {
				try {
					for (int index = 0; index < 2; index++) {
						@Nullable Round round = index == 0 ? consumer.completedRound : consumer.round;
						if (round == null || index == 1 && round == consumer.completedRound) continue;
						@Nullable Association association = index == 0 ? consumer.completedAssociation : consumer.scanningAssociation;
						attempted++;
						if (association != null) {
							InventorySharedRead.Check check;
							try { check = association.lease.port.validate(association.expected, operation.allowance); }
							catch (InventoryItemBudget.CaptureLimitException deferred) { consumer.currentReason = deferReason(deferred); return InventorySourceAccess.Preparation.DEFERRED; }
							catch (RuntimeException | LinkageError failure) {
								warnPooled("metadata-validate", consumer, round, association.expected, "association-validate", failure);
								if (providerFailure(consumer, association, failure)) return InventorySourceAccess.Preparation.UNAVAILABLE;
								check = InventorySharedRead.Check.UNAVAILABLE;
							}
							if (check == InventorySharedRead.Check.DEFERRED) { consumer.currentReason = InventoryFailureReason.ITEM_WORK_LIMIT; return InventorySourceAccess.Preparation.DEFERRED; }
							if (check != InventorySharedRead.Check.VALID) {
								consumer.currentReason = check == InventorySharedRead.Check.AUTHORITY_DENIED ? InventoryFailureReason.SOURCE_AUTHORITY_DENIED
									: check == InventorySharedRead.Check.STRUCTURE_INVALID ? InventoryFailureReason.SOURCE_CHANGED : InventoryFailureReason.SOURCE_UNAVAILABLE;
								pooledFailure(consumer, association, check); return InventorySourceAccess.Preparation.UNAVAILABLE;
							}
						} else {
							boolean valid;
							try { valid = round.handle.evidenceValid(operation.allowance); }
							catch (InventoryItemBudget.CaptureLimitException deferred) { consumer.currentReason = deferReason(deferred); return InventorySourceAccess.Preparation.DEFERRED; }
							catch (RuntimeException | LinkageError failure) { consumer.currentReason = sourceReason(failure); valid = false; }
							if (!valid) { retire(round, unavailable(consumer.input.viewKey())); return InventorySourceAccess.Preparation.UNAVAILABLE; }
						}
					}
				} finally { grant.commit(Map.of(InventorySourceAccess.PROBES, (long) attempted, InventorySourceAccess.PROVIDER_WORK, attempted * InventorySourceAccess.PROVIDER_CALL_WORK)); }
			}
		}
		consumer.probeDelay.progress(cycle(consumer));
		return InventorySourceAccess.Preparation.READY;
	}
	private InventorySourceAccess.Preparation prepareHandoff(Consumer consumer, Round handoff, InventoryValidationBudget.Allowance allowance) {
		var bindingCheck = checkBindingEvidence(consumer, allowance);
		if (bindingCheck != InventorySourceAccess.Preparation.READY) return bindingCheck;
		var validation = validationDecision(handoff.handle.evidenceValidationCost(), allowance, 0);
		if (!validation.accepted()) { validationDelay(consumer, consumer.probeDelay, validation, "probe"); return InventorySourceAccess.Preparation.DEFERRED; }
		try (var operation = java.util.Objects.requireNonNull(validation.value())) {
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
				catch (InventoryItemBudget.CaptureLimitException deferred) { consumer.currentReason = deferReason(deferred); return InventorySourceAccess.Preparation.DEFERRED; }
				catch (RuntimeException | LinkageError unavailable) { if (InventoryCaptureDiagnostics.providerLimit(unavailable) != null) consumer.currentReason = sourceReason(unavailable);
					warn("handoff-probe", consumer, handoff, unavailable); valid = false; }
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
	private Optional<Observation> step(Consumer consumer, int slotBound, @Nullable ProbeAttempt attempt) {
		var outcome = stepOwned(consumer, slotBound, attempt);
		@Nullable Round round = consumer.round;
		if (round != null && outcome.isPresent()) {
			if (round.terminalReason == null && round.handle != null) round.terminalReason = round.handle.terminalReason();
			var result = outcome.get().result();
			if (result.availability() == CaptureResult.Availability.READABLE) {
				if (result.completeness() == CaptureResult.Completeness.INCOMPLETE && round.terminalReason != null)
					consumer.currentReason = round.terminalReason;
				else if (consumer.currentReason == round.terminalReason) consumer.currentReason = null;
			}
		}
		return outcome;
	}
	private Optional<Observation> stepOwned(Consumer consumer, int slotBound, @Nullable ProbeAttempt attempt) {
		consumer.currentReason = null;
		long readGeneration = monitor.enabled() ? monitor.collectionGeneration() : -1;
		if (slotBound < 1 || slotBound > InventorySourceAccess.MAX_STEP) throw new IllegalArgumentException("inventory step bound");
		if (consumer.closed) return Optional.empty();
		if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
		if (consumer.pendingObservation != null) return Optional.of(consumer.pendingObservation);
		var operationAllowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
		InventorySourceAccess.Preparation preparation = prepare(consumer, operationAllowance, false, attempt);
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
			if (round.journal != null && round.sweep == null && consumer.cursor == round.size() && round.handle.pendingNativeValidation()
				&& (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE)) {
				if (!readNative(consumer, 0, operationAllowance, readGeneration)) return Optional.empty();
				if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
				consumer.logicalDelay.progress(cycle(consumer));
				return Optional.of(new Observation(java.util.Objects.requireNonNull(round.last), List.of(), round.sweepEvidence));
			}
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
			if (!readNative(consumer, available, operationAllowance, readGeneration)) return Optional.empty();
			if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
		}
		if (round.journal == null && consumer.cursor >= round.size() && (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE)) {
			int readDisplayCap = clampDisplayBytes(round.handle.displayBytes());
			long slotWorkspace = slotWorkspaceBytes(readDisplayCap);
			long rootWorkspace = InventoryItemBudget.workspaceUpperBound(Math.max(effectiveDisplayCap(), readDisplayCap));
			long fixed = checkedAdd(InventoryCatalogAdmission.METADATA_BYTES, checkedAdd(PAGE_WORKSPACE_FIXED, rootWorkspace));
			int physicalBound = (int) Math.min(physicalRemaining(), Math.max(0, physical.remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK - 1));
			int limitingReasons = scanReasons(available, readDisplayCap, nativeEntries(round, available));
			int memoryBound = Math.min(available, Math.max(1, physicalBound));
			if (round.input.resourceKind() == InventoryResourceKind.FLUID)
				while (memoryBound > 0 && checkedAdd(round.handle.stepValidationCost(memoryBound).workUnits(), checkedMultiply(memoryBound,
					InventoryItemBudget.workUpperBound(readDisplayCap, nativeEntries(round, memoryBound)))) > physical.remaining(ITEM_WORK)) memoryBound--;
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
					if (!observeHandleDeferral(consumer, round)) physicalDelay(consumer, scanReasons(available, readDisplayCap, nativeEntries(round, available)), physicalRemaining() == 0 ? available : -1);
					workspace.get().close(); return Optional.empty();
				}
				CaptureResult zeroResult = ((SourceAccess.StepOutcome.Captured) outcome).result();
				if (zeroResult.availability() != CaptureResult.Availability.READABLE) {
					Observation invalid = new Observation(zeroResult, List.of());
					workspace.get().close(); retire(round, invalid); return Optional.of(invalid);
				}
				if (!zeroCostEmptyCompletion(zeroResult)) { physicalDelay(consumer, scanReasons(available, readDisplayCap, nativeEntries(round, available)), physicalRemaining() == 0 ? available : -1); workspace.get().close(); return Optional.empty(); }
				round.last = zeroResult;
				physicalProgress(round);
				workspace.get().commit(PAGE_FIXED_BYTES); round.pages.add(workspace.get()); round.pageCount++; retainedPage = true;
			}
			if (bounded == 0) {
				// Snapshot handles may report an empty COMPLETE observation without scan allowance;
				// live handles defer before touching their source at zero allowance.
			} else {
			long workBound = (bounded + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK;
			long itemWork = checkedMultiply(bounded, InventoryItemBudget.workUpperBound(readDisplayCap, nativeEntries(round, bounded)));
			CostLedger readLedger = physical;
			var grant = readLedger.tryReserve(Map.of(InventorySourceAccess.PHYSICAL, (long) bounded, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, workBound, ITEM_WORK, itemWork));
			if (grant.isEmpty()) { physicalDelay(consumer, scanReasons(bounded, readDisplayCap, nativeEntries(round, bounded)), -1); workspace.get().close(); return Optional.empty(); }
			SourceAccess.StepOutcome result;
			try (var physicalTicket = grant.get()) {
				CostLedger step = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) bounded, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, workBound));
				var catalogAdmission = new InventoryCatalogAdmission(rootWorkspace,
					checkedMultiply(bounded, InventoryCatalogAdmission.workUpperBound(readDisplayCap, nativeEntries(round, bounded))));
				var validation = validationDecision(round.handle.stepValidationCost(bounded), operationAllowance, rootWorkspace);
				if (!validation.accepted()) { validationDelay(consumer, consumer.probeDelay, validation, "step-validation"); return Optional.empty(); }
				try (var operation = java.util.Objects.requireNonNull(validation.value())) { result = readStep(consumer, step, catalogAdmission, operation.allowance); }
				finally {
					long attempted = bounded - step.remaining(InventorySourceAccess.PHYSICAL);
					boolean committed = false;
					try { physicalTicket.commit(Map.of(InventorySourceAccess.PHYSICAL, attempted,
					InventorySourceAccess.PROBES, 1L - step.remaining(InventorySourceAccess.PROBES), InventorySourceAccess.PROVIDER_WORK, workBound - step.remaining(InventorySourceAccess.PROVIDER_WORK),
					// Keep the existing attempted-slot envelope for source authority,
					// returned-stack processing and page encoding, even on lazy reads.
					// Catalog pays its actual cumulative attempt ONCE inside U, not U+C.
					ITEM_WORK, checkedAdd(checkedMultiply(attempted,
						InventoryItemBudget.freezeWorkUpperBound(readDisplayCap)), catalogAdmission.attemptedWork()))); committed = true; }
					finally { if (readGeneration >= 0) recordReadAttempts(readGeneration, readLedger, attempted, committed ? attempted : 0); }
				}
			}
			if (result instanceof SourceAccess.StepOutcome.Deferred) { observeHandleDeferral(consumer, round); workspace.get().close(); return Optional.empty(); }
			round.last = ((SourceAccess.StepOutcome.Captured) result).result();
			physicalProgress(round);
			if (round.last.availability() != CaptureResult.Availability.READABLE) {
				Observation invalid = new Observation(round.last, List.of());
				retire(round, invalid);
				return Optional.of(invalid);
			}
			if (round.last.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment page) {
				if (!InventoryResourceCodec.id(round.input.resourceKind()).equals(page.codecId())) throw new IllegalStateException("wrong inventory codec");
				page.entries().entrySet().stream().sorted(java.util.Comparator.comparingInt(e -> Integer.parseInt(e.getKey())))
					.forEach(e -> round.slots.add(InventoryResourceCodec.decode(round.input.resourceKind(), e.getValue())));
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
		if (round.journal != null && !claimDetachedItemWork(journalReplayWork(end - consumer.cursor))) {
			requestDelay(consumer, consumer.logicalDelay, InventoryFailureReason.ITEM_WORK_LIMIT, "journal-replay",
				journalReplayWork(end - consumer.cursor), physical.used(ITEM_WORK), physical.limit(ITEM_WORK)); return Optional.empty();
		}
		int charge = Math.max(0, end - paid);
		if (!growBookkeeping(consumer, round, round.paid.size() + (round.paid.containsKey(consumer.subject) ? 0 : 1),
			round.tightenedKeys == null ? 0 : round.tightenedKeys.size())) {
			delay(consumer, consumer.logicalDelay, consumer.currentReason == InventoryFailureReason.MEMORY_LIMIT ? Reason.MEMORY.bit()
				: Reason.PUBLICATION_WORK.bit(), "bookkeeping-growth"); return Optional.empty();
		}
		CostLedger logical = new CostLedger(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) Math.max(0, allowance)));
		var ticket = logical.tryReserve(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) charge)).orElseThrow();
		ticket.commit(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) charge));
		logicalUsed.merge(consumer.subject, charge, Integer::sum);
		if (consumer.subject instanceof PreviewSubject) previewGlobal += charge; else trackingGlobal += charge;
		if (monitor.enabled(lane(consumer))) monitor.count(lane(consumer), Counter.LOGICAL_SLOTS, charge);
		round.paid.put(consumer.subject, Math.max(paid, end));
		List<@Nullable InventoryResourceValue> page = new ArrayList<>(end - consumer.cursor);
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
			long workspace = round.sweep == null ? nativeWorkspace(round, slots) : pooledWorkspace(java.util.Objects.requireNonNull(consumer.scanningAssociation).lease, round, slots);
			return checkedAdd(workspace, checkedAdd(nativeRetained(round), checkedAdd(
				round.journal.batchAllocationUpperBound(count), nativeGrowth(round, slots))));
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
		InventoryItemCodec.@Nullable Catalog catalog = nativeCatalog(round);
		// The admitted fixed round slab pays the journal's initial directories; variable chunk/definition capacity is separate.
		return (round.sweep == null ? round.handle.nativeResourceRetainedBytes() : catalog == null ? 0 : catalog.modeledRetainedBytes())
			+ (round.journal == null ? 0 : round.journal.modeledRetainedBytes() - InventorySlotJournal.initialRetainedBytes());
	}
	private static InventoryItemCodec.@Nullable Catalog nativeCatalog(Round round) { return round.sweep == null ? round.handle.nativeCatalog() : round.sweep.catalog; }
	private static int displayBytes(Round round) { return round.sweep == null ? round.handle.displayBytes() : round.sweep.catalog == null ? round.appliedBytes : round.sweep.catalog.displayBytes(); }
	private static long nativeGrowth(Round round, int attempts) {
		if (round.sweep == null) {
			long growth = round.handle.nativeCatalogGrowthUpperBound(attempts);
			// Extension-native ITEM values can own their detached payload directly.
			// Admit that payload even when no catalogue exists to own its strings.
			return round.input.resourceKind() == InventoryResourceKind.ITEM && round.handle.nativeCatalog() == null
				? Math.max(growth, InventoryItemCodec.Catalog.firstReadGrowthUpperBound(displayBytes(round), attempts)) : growth;
		}
		return round.sweep.catalog == null
			? InventoryItemCodec.Catalog.firstReadGrowthUpperBound(displayBytes(round), attempts) : round.sweep.catalog.retainedGrowthUpperBound(attempts);
	}
	private static int nativeEntries(Round round, int attempts) {
		return round.sweep == null ? round.handle.nativeCatalogEntriesUpperBound(attempts) : round.sweep.catalog == null
			? Math.min(InventoryItemCodec.DEFAULT_MAX_ENTRIES, Math.max(0, attempts - 1)) : round.sweep.catalog.entriesUpperBound(Math.max(1, attempts));
	}
	private static long journalReplayWork(int slots) {
		return slots == 0 ? 0 : checkedAdd(4096L, checkedMultiply(2048L, slots));
	}
	/** Pure journal shape and consumer cursor only; no provider callback or speculative slot read. */
	private static long journalStepWorkUpperBound(Consumer consumer, int available, int appended, boolean reading) {
		InventorySlotJournal journal = java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(consumer.round).journal);
		int end = Math.min(journal.endSlot() + appended, consumer.cursor + available);
		return checkedAdd(reading ? journal.batchWorkUpperBound(appended) : 0,
			journalReplayWork(Math.max(0, end - consumer.cursor)));
	}
	private static long nativeReadWorkUpperBound(Consumer consumer, int slots) {
		Round round = java.util.Objects.requireNonNull(consumer.round);
		long validation = round.sweep == null ? round.handle.stepValidationCost(slots).workUnits()
			: java.util.Objects.requireNonNull(consumer.scanningAssociation).lease.pageCosts[slots].workUnits();
		return checkedAdd(checkedAdd(validation, checkedMultiply(slots,
			InventoryItemBudget.workUpperBound(displayBytes(round), nativeEntries(round, slots)))),
			journalStepWorkUpperBound(consumer, slots, slots, true));
	}
	private boolean readNative(Consumer consumer, int available, InventoryValidationBudget.Allowance allowance, long readGeneration) {
		Round round = consumer.round;
		if (round.sweep != null) return readPooled(consumer, available, allowance, readGeneration);
		InventorySlotJournal journal = java.util.Objects.requireNonNull(round.journal);
		int bound = (int) Math.min(Math.min(available, physicalRemaining()), Math.max(0, physical.remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK - 1));
		bound = round.handle.pendingNativeValidation() ? 0
			: Math.min(bound, Math.min(round.handle.remainingSlots(), InventorySourceAccess.MAX_SLOTS - journal.endSlot()));
		if (bound == 0) {
			var outcome = zeroAllowanceStep(consumer, allowance);
			if (outcome instanceof SourceAccess.StepOutcome.Deferred) { if (!observeHandleDeferral(consumer, round)) physicalDelay(consumer, scanReasons(available, round.handle.displayBytes(), nativeEntries(round, available)), available); return false; }
			round.last = ((SourceAccess.StepOutcome.Captured) outcome).result();
			round.sweepEvidence = InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP;
			if (round.last.availability() != CaptureResult.Availability.READABLE) retire(round, new Observation(round.last, List.of()));
			return true;
		}
		while (bound > 0 && (stepMemoryUpperBound(consumer, bound) > memory.remaining()
				|| nativeReadWorkUpperBound(consumer, bound) > physical.remaining(ITEM_WORK))) bound--;
		if (bound == 0) {
			if (stepMemoryUpperBound(consumer, 1) > memory.remaining()) physicalDelay(consumer, Reason.MEMORY.bit(), -1);
			long required = nativeReadWorkUpperBound(consumer, 1);
			if (required > physical.remaining(ITEM_WORK)) requestDelay(consumer, consumer.probeDelay,
				InventoryFailureReason.ITEM_WORK_LIMIT, "native-read-work", required, physical.used(ITEM_WORK), physical.limit(ITEM_WORK));
			return false;
		}
		long oldRetained = nativeRetained(round);
		if (bound > 0 && !claimDetachedItemWork(journal.batchWorkUpperBound(bound))) { physicalDelay(consumer, Reason.PUBLICATION_WORK.bit(), -1); return false; }
		long growth = journal.batchAllocationUpperBound(bound) + nativeGrowth(round, bound);
		var retained = memory.tryReserve(checkedAdd(oldRetained, growth));
		if (retained.isEmpty()) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return false; }
		boolean transferred = false;
		try {
			var workspace = memory.tryReserve(nativeWorkspace(round, bound));
			if (workspace.isEmpty()) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return false; }
			try (var held = workspace.get()) {
				int display = round.handle.displayBytes();
				long provider = (bound + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK;
				var limits = Map.of(InventorySourceAccess.PHYSICAL, (long) bound, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, provider);
				var work = new LinkedHashMap<>(limits); work.put(ITEM_WORK, checkedMultiply(bound, InventoryItemBudget.workUpperBound(display, nativeEntries(round, bound))));
				CostLedger readLedger = physical;
				var admitted = readLedger.tryReserve(work);
				if (admitted.isEmpty()) { physicalDelay(consumer, scanReasons(bound, display, nativeEntries(round, bound)), -1); return false; }
				try (var grant = admitted.get()) {
					var step = new CostLedger(limits);
					var catalogAdmission = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(display), checkedMultiply(bound, InventoryCatalogAdmission.workUpperBound(display, nativeEntries(round, bound))));
					var validationCost = round.handle.stepValidationCost(bound);
					var validation = validationDecision(validationCost, allowance, validationCost.workspaceBytes());
					if (!validation.accepted()) { validationDelay(consumer, consumer.probeDelay, validation, "native-validation"); return false; }
					InventoryStep.Outcome outcome;
					long timing = monitor.enabled() ? monitor.beginPhase(lane(consumer), Phase.READ) : 0;
					try (var operation = java.util.Objects.requireNonNull(validation.value())) { outcome = round.handle.stepInventory(step, catalogAdmission, operation.allowance); }
					finally {
						long attempted = step.used(InventorySourceAccess.PHYSICAL);
						boolean committed = false;
						try { grant.commit(Map.of(InventorySourceAccess.PHYSICAL, attempted, InventorySourceAccess.PROBES, step.used(InventorySourceAccess.PROBES),
							InventorySourceAccess.PROVIDER_WORK, step.used(InventorySourceAccess.PROVIDER_WORK), ITEM_WORK, checkedAdd(checkedMultiply(attempted, InventoryItemBudget.freezeWorkUpperBound(display)), catalogAdmission.attemptedWork()))); committed = true; }
						finally {
							if (readGeneration >= 0) recordReadAttempts(readGeneration, readLedger, attempted, committed ? attempted : 0);
							monitor.end(timing);
						}
					}
					if (round.handle.terminalReason() != null) round.terminalReason = round.handle.terminalReason();
					if (outcome instanceof InventoryStep.Outcome.Deferred) { observeHandleDeferral(consumer, round); return false; }
					InventoryStep result = ((InventoryStep.Outcome.Captured) outcome).step();
					round.last = result.metadata(); round.sweepEvidence = result.evidence(); physicalProgress(round);
					if (round.last.availability() != CaptureResult.Availability.READABLE) {
						consumer.currentReason = round.terminalReason;
						retire(round, new Observation(round.last, List.of())); return true;
					}
					if (result.firstSlot() != journal.endSlot()) throw new IllegalStateException("native journal prefix mismatch");
					InventoryItemCodec.@Nullable Catalog catalog = round.handle.nativeCatalog();
					try {
						for (@Nullable InventoryResourceValue item : result.slots()) {
							InventoryItemCodec.@Nullable Encoded encoded = item == null || item.resourceKind() != InventoryResourceKind.ITEM || catalog == null
								? null : java.util.Objects.requireNonNull(catalog.retainedEncoded(item.key()));
							journal.appendResource(journal.endSlot(), encoded, item == null ? 0 : item.amount(), item);
						}
					} catch (InventorySlotJournal.CapacityException exhausted) {
						limitRound(consumer, InventoryFailureReason.JOURNAL_LIMIT, "native-journal-capacity");
						// Extension sources may not use the native catalogue's complete-key guard.
						// Keep the append-only validated prefix and all paid attempts, never retry
						// the source cursor or certify the unretained tail as a complete total.
						CaptureResult prior = round.last;
						round.last = new CaptureResult(Optional.empty(), new CaptureResult.Coverage(prior.coverage().demandStamp(),
							journal.endSlot(), journal.endSlot(), prior.coverage().expected()), prior.availability(),
							CaptureResult.Completeness.INCOMPLETE, prior.consistency(), prior.sourceVersion(), Optional.empty());
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
	private int scanReasons(int requested, int displayBytes, int entries) {
		return (physicalRemaining() < requested ? Reason.PHYSICAL_SLOTS.bit() : 0)
			| providerReasons(1, (requested + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK)
			| (physical.remaining(ITEM_WORK) < checkedMultiply(requested, InventoryItemBudget.workUpperBound(displayBytes, entries)) ? Reason.PUBLICATION_WORK.bit() : 0);
	}
	/** The real source step (including its native checks/freeze), not paid journal replay. */
	private SourceAccess.StepOutcome readStep(Consumer consumer, CostLedger step, InventoryCatalogAdmission catalog,
		InventoryValidationBudget.Allowance allowance) {
		return readStep(consumer, step, catalog, allowance, false);
	}
	private SourceAccess.StepOutcome readStep(Consumer consumer, CostLedger step, InventoryCatalogAdmission catalog,
		InventoryValidationBudget.Allowance allowance, boolean completionOnly) {
		Round round = consumer.round;
		long timing = monitor.enabled() ? monitor.beginPhase(lane(consumer), Phase.READ) : 0;
		try { return completionOnly ? round.handle.stepCompletion(step, catalog, allowance) : round.handle.step(step, catalog, allowance); }
		finally {
			if (round.handle.terminalReason() != null) {
				round.terminalReason = round.handle.terminalReason();
				consumer.currentReason = round.terminalReason;
			}
			monitor.end(timing);
		}
	}
	private boolean observeHandleDeferral(Consumer consumer, Round round) {
		@Nullable InventoryFailureReason reason = round.handle.stepDeferralReason();
		if (reason == null) return false;
		// The handle already reported this exact attempted admission.
		observeTypedDelay(consumer, consumer.probeDelay, reason, "source-read");
		return true;
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
		if (consumer.round.sweep != null) {
			Association association = java.util.Objects.requireNonNull(consumer.scanningAssociation);
			// An empty certified graph cannot attempt a content read; sampling observes its zero.
			if (association.expected.graph().slots() != 0 || !readPooled(consumer, 0, allowance, -1) || consumer.invalidated()) return new SourceAccess.StepOutcome.Deferred();
			return new SourceAccess.StepOutcome.Captured(java.util.Objects.requireNonNull(consumer.round.last));
		}
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
			var validation = validationDecision(consumer.round.handle.stepValidationCost(0), allowance, 0);
			if (!validation.accepted()) { consumer.zeroAdmissionRefused = true; validationDelay(consumer, consumer.probeDelay, validation, "zero-validation"); return new SourceAccess.StepOutcome.Deferred(); }
			SourceAccess.StepOutcome outcome;
			try (var operation = java.util.Objects.requireNonNull(validation.value())) {
				outcome = readStep(consumer, step, new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(effectiveDisplayCap()), 0), operation.allowance, true);
			}
			if (outcome instanceof SourceAccess.StepOutcome.Deferred && observeHandleDeferral(consumer, consumer.round)) consumer.zeroAdmissionRefused = true;
			grant.commit(Map.of(InventorySourceAccess.PHYSICAL, 0L,
				InventorySourceAccess.PROBES, 1L - step.remaining(InventorySourceAccess.PROBES),
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK - step.remaining(InventorySourceAccess.PROVIDER_WORK)));
			return outcome;
		}
	}
	private boolean shareable(Consumer consumer, Round round) {
		// Observation evidence is local bookkeeping, never physical-source identity.
		if (consumer.input.resourceKind() != round.input.resourceKind()) return false;
		if (round.closed || round.scanners == 0) return false;
		if (round.sweep != null) return !round.sealed && round.policy.equals(policy) && activePooledRound(round)
			&& round.sequence > consumer.lastAssociatedSequence && round.sequence >= consumer.recoveryFloor;
		if (certifiedPooling && consumer.input.resourceKind() == InventoryResourceKind.ITEM && consumer.subject instanceof TrackingSubject && (round.sealed || !round.policy.equals(policy))) return false;
		if (consumer.completedRound != null && (round.sequence <= consumer.completedRound.sequence || round.successor != null || round.successorSequence != 0
			|| round.sweep == null && !round.key.equals(consumer.completedRound.key))) return false;
		if (round.startedTick < consumer.observationFloor || round.sequence < consumer.firstRound) return false;
		if (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE) return true;
		return !(consumer.subject instanceof TrackingSubject) || round.trackingObservation && round.startedTick == tick;
	}
	private static boolean activePooledRound(Round round) {
		return !round.closed && round.journal != null && round.activeScans > 0
			&& round.failureObservation == null && !round.sourceProjectionUnsupported
			&& (round.preparation == null || round.preparation == InventorySourceAccess.Preparation.READY)
			&& (round.last == null || round.last.availability() == CaptureResult.Availability.READABLE
				&& round.last.completeness() == CaptureResult.Completeness.CONTINUE);
	}
	private void join(Consumer consumer, Round round) {
		boolean pooled = certifiedPooling && consumer.input.resourceKind() == InventoryResourceKind.ITEM;
		@Nullable Round previous = consumer.completedRound;
		if (pooled && previous != null && previous != round && !previous.sealed && previous.successorSequence == 0 && previous.policy.equals(round.policy)
			&& (round.sweep == null || !round.sealed && (round.references == 0 || activePooledRound(round)))) {
			previous.successorSequence = round.sequence;
			// Recurring peers remain demand during cadence waits, but a suspended audience cannot consume a successor.
			for (Consumer peer : consumers) if (peer.subject instanceof TrackingSubject
				&& peer.serviceableDemand && (peer.completedRound == previous || peer.round == previous) && peer.successorEntitlement == null && !peer.closed
				&& round.sequence > peer.lastAssociatedSequence && round.sequence >= peer.recoveryFloor) {
				peer.successorEntitlement = round;
				round.references++; round.scanners++; round.incomingEntitlements++;
			}
		}
		if (!pooled && previous != null && previous != round && previous.successor == null && hasTrackingPeer(previous, consumer)) {
			// One successor per physical observation, not one replacement allowance per consumer.
			// The lease keeps its data serviceable after the fast peer restarts; the slow peer consumes it
			// as its next observation without losing either its previous publication evidence or progress.
			previous.successor = round; round.predecessors.add(previous); round.references++; round.scanners++;
		}
		round.scanners++;
		if (previous != round) round.references++;
		consumer.round = round;
		addScanDemand(consumer);
		consumer.lastAssociatedSequence = Math.max(consumer.lastAssociatedSequence, round.sequence);
		if (consumer.successorEntitlement == round) releaseEntitlement(consumer);
	}
	/** Demand is independent of the scanner reference that keeps a journal and its exact publication evidence serviceable. */
	private static void addScanDemand(Consumer consumer) { changeScanDemand(consumer, 1); }
	private static void removeScanDemand(Consumer consumer) { changeScanDemand(consumer, -1); }
	private static void changeScanDemand(Consumer consumer, int delta) {
		@Nullable Round round = consumer.round;
		if (round == null || round.sweep == null || !consumer.scanning || !consumer.serviceableDemand) return;
		round.activeScans += delta;
		if (consumer.subject instanceof TrackingSubject) round.activeTracking += delta;
	}
	/** Legacy shared handoffs are future Tracking demand, not one-shot Preview ownership. */
	private boolean hasTrackingPeer(Round round, @Nullable Consumer excluded) {
		for (Consumer peer : consumers) if (peer != excluded && !peer.closed && peer.subject instanceof TrackingSubject
			&& (peer.round == round || peer.completedRound == round)) return true;
		return false;
	}
	private boolean readPooled(Consumer consumer, int available, InventoryValidationBudget.Allowance allowance, long readGeneration) {
		Round round = consumer.round;
		Association association = java.util.Objects.requireNonNull(consumer.scanningAssociation);
		InventorySharedRead.Port port = association.lease.port;
		InventorySlotJournal journal = java.util.Objects.requireNonNull(round.journal);
		InventoryItemCodec.Catalog catalog = java.util.Objects.requireNonNull(nativeCatalog(round));
		int bound = (int) Math.min(available, Math.min(physicalRemaining(), association.expected.graph().slots() - journal.endSlot()));
		bound = Math.min(bound, InventorySourceAccess.MAX_STEP);
		int limitingReasons = physicalRemaining() == 0 ? Reason.PHYSICAL_SLOTS.bit() : 0;
		boolean itemWorkLimited = false;
		// Admission shrinks to a serviceable page; do not wait for a page whose metadata cost exceeds a fresh tick.
		while (bound > 0) {
			itemWorkLimited = nativeReadWorkUpperBound(consumer, bound) > physical.remaining(ITEM_WORK);
			int reasons = (stepMemoryUpperBound(consumer, bound) > memory.remaining() ? Reason.MEMORY.bit() : 0)
				| (itemWorkLimited ? Reason.PUBLICATION_WORK.bit() : 0)
				| ((bound + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK > physical.remaining(InventorySourceAccess.PROVIDER_WORK) ? Reason.PROVIDER_WORK.bit() : 0);
			if (reasons == 0) break;
			limitingReasons = reasons; bound--;
		}
		if (bound == 0 && journal.endSlot() != association.expected.graph().slots()) {
			if (itemWorkLimited) requestDelay(consumer, consumer.probeDelay, InventoryFailureReason.ITEM_WORK_LIMIT,
				"pooled-read-work", nativeReadWorkUpperBound(consumer, 1),
				physical.used(ITEM_WORK), physical.limit(ITEM_WORK));
			int otherReasons = limitingReasons & ~Reason.PUBLICATION_WORK.bit();
			if (otherReasons != 0) physicalDelay(consumer, otherReasons, available);
			return false;
		}
		if (bound > 0 && !claimDetachedItemWork(journal.batchWorkUpperBound(bound))) { physicalDelay(consumer, Reason.PUBLICATION_WORK.bit(), -1); return false; }
		var retained = memory.tryReserve(checkedAdd(nativeRetained(round), journal.batchAllocationUpperBound(bound) + nativeGrowth(round, bound)));
		if (retained.isEmpty()) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return false; }
		boolean transferred = false;
		try {
			var workspace = memory.tryReserve(pooledWorkspace(association.lease, round, bound));
			if (workspace.isEmpty()) { physicalDelay(consumer, Reason.MEMORY.bit(), -1); return false; }
			try (var held = workspace.get()) {
				var cost = association.lease.pageCosts[bound];
				var validation = validationDecision(cost, allowance, cost.workspaceBytes());
				if (!validation.accepted()) { validationDelay(consumer, consumer.probeDelay, validation, "pooled-read"); return false; }
				try (var operation = java.util.Objects.requireNonNull(validation.value())) {
					var limits = Map.of(InventorySourceAccess.PHYSICAL, (long) bound, InventorySourceAccess.PROBES, 1L,
						InventorySourceAccess.PROVIDER_WORK, (bound + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK,
					ITEM_WORK, checkedMultiply(bound, InventoryItemBudget.workUpperBound(catalog.displayBytes(), catalog.entriesUpperBound(Math.max(1, bound)))));
					CostLedger readLedger = physical;
					var admission = readLedger.tryReserveDecision(limits);
					if (!admission.accepted()) { var counter = java.util.Objects.requireNonNull(admission.limitingCounter());
						requestDelay(consumer, consumer.probeDelay, counterReason(counter), "pooled-read-admission", java.util.Objects.requireNonNull(limits.get(counter)),
							readLedger.used(counter), readLedger.limit(counter)); return false; }
					int attempted = 0;
					var catalogAdmission = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(catalog.displayBytes()),
						checkedMultiply(bound, InventoryCatalogAdmission.workUpperBound(catalog.displayBytes(), catalog.entriesUpperBound(Math.max(1, bound)))));
					try (var grant = java.util.Objects.requireNonNull(admission.ticket())) {
						try {
							InventorySharedRead.Check check = port.validate(association.expected, operation.allowance);
							if (check != InventorySharedRead.Check.VALID) { pooledFailure(consumer, association, check); return check != InventorySharedRead.Check.DEFERRED; }
							var page = new ArrayList<InventoryDomainCodec.@Nullable Item>(bound);
							boolean incomplete = false;
							long timing = monitor.enabled() ? monitor.beginPhase(lane(consumer), Phase.READ) : 0;
							try {
								while (page.size() < bound) {
									attempted++;
									page.add(port.read(journal.endSlot() + page.size(), association.expected, catalog, catalogAdmission, operation.allowance));
								}
							} catch (InventoryItemBudget.CaptureLimitException | InventoryItemCodec.CapacityExceededException capacity) {
								limitRound(consumer, InventoryCaptureDiagnostics.boundReason(capacity), "pooled-read-capacity"); incomplete = true;
							}
							finally { monitor.end(timing); }
							check = port.validate(association.expected, operation.allowance);
							if (check != InventorySharedRead.Check.VALID) { pooledFailure(consumer, association, check); return check != InventorySharedRead.Check.DEFERRED; }
							// Only this validated transaction advances the physical cursor. Failed attempts and collision history remain paid.
							for (InventoryDomainCodec.@Nullable Item item : page) {
								InventoryItemCodec.@Nullable Encoded encoded = item == null ? null : java.util.Objects.requireNonNull(catalog.retainedEncoded(item.key()));
								journal.append(journal.endSlot(), encoded, item == null ? 0 : item.count(), item);
							}
							int end = journal.endSlot(), expected = association.expected.graph().slots();
							round.last = new CaptureResult(Optional.empty(), new CaptureResult.Coverage(consumer.input.viewKey(), end, end, OptionalLong.of(expected)),
								CaptureResult.Availability.READABLE, incomplete ? CaptureResult.Completeness.INCOMPLETE : end == expected ? CaptureResult.Completeness.COMPLETE : CaptureResult.Completeness.CONTINUE,
								CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
							round.sweepEvidence = InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP; physicalProgress(round);
							return true;
						} catch (InventorySharedRead.ReadFailure failure) {
							warnPooled("pooled-read", consumer, round, association.expected, "pooled-read", failure);
							if (providerFailure(consumer, association, failure)) return true;
							pooledFailure(consumer, association, failure.check()); return true;
						} catch (RuntimeException | LinkageError failure) {
							warnPooled("pooled-read", consumer, round, association.expected, "pooled-read", failure);
							if (providerFailure(consumer, association, failure)) return true;
							pooledFailure(consumer, association, InventorySharedRead.Check.UNAVAILABLE); return true;
						} finally {
							boolean committed = false;
							try { grant.commit(Map.of(InventorySourceAccess.PHYSICAL, (long) attempted, InventorySourceAccess.PROBES, 1L,
								InventorySourceAccess.PROVIDER_WORK, (attempted + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK,
								ITEM_WORK, checkedAdd(checkedMultiply(attempted, InventoryItemBudget.freezeWorkUpperBound(catalog.displayBytes())), catalogAdmission.attemptedWork()))); committed = true; }
							finally { if (readGeneration >= 0) recordReadAttempts(readGeneration, readLedger, attempted, committed ? attempted : 0); }
						}
					}
				}
			}
		} finally {
			if (!round.closed) { retained.get().commit(nativeRetained(round)); if (round.nativeMemory != null) round.nativeMemory.close(); round.nativeMemory = retained.get(); transferred = true; }
			if (!transferred) retained.get().close();
		}
	}
	private static InventoryValidationBudget.Cost pooledValidationCost(InventorySharedRead.Port port, int slots) { return port.validationCost().times(2).plus(port.readValidationCost(slots)); }
	private static long pooledWorkspace(BindingLease lease, Round round, int slots) {
		return checkedAdd(InventoryItemBudget.workspaceUpperBound(displayBytes(round)), checkedAdd(lease.pageCosts[slots].workspaceBytes(),
			PAGE_WORKSPACE_FIXED + InventoryCatalogAdmission.METADATA_BYTES + slots * 256L));
	}
	private void completed(Consumer consumer, Round round) {
		Round previous = consumer.completedRound;
		if (consumer.completedAssociation != null) consumer.completedAssociation.close();
		consumer.completedAssociation = consumer.scanningAssociation == null ? null : consumer.scanningAssociation.retain();
		consumer.completedRound = round; // the scanning reference already pays for this consumer
		if (previous != null && previous != round) releaseReference(previous);
	}
	private void releaseScanning(Consumer consumer) {
		removeScanDemand(consumer);
		@Nullable Round round = consumer.round; consumer.round = null;
		if (consumer.scanningAssociation != null) { consumer.scanningAssociation.close(); consumer.scanningAssociation = null; }
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
			try { if (round.handle != null) round.handle.releaseCapturedData(); }
			finally {
				releaseDecodedData(round);
				rounds.remove(round.lookupKey, round);
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
		if (round.sweep != null) {
			round.sweep.catalog = null;
			if (round.nativeMemory != null) { round.nativeMemory.close(); round.nativeMemory = null; }
		}
		if (round.input.resourceKind() == InventoryResourceKind.FLUID && round.nativeMemory != null) {
			round.nativeMemory.close(); round.nativeMemory = null;
		}
		round.passActive = false; round.reprojectCursor = 0; round.reprojectEnd = 0;
		round.pagesChanged = false; round.pagesSettled = true;
	}
	private void releaseReference(Round round) {
		if (--round.references == 0) { closeRound(round); return; }
		if (round.successor != null && !hasTrackingPeer(round, null)) releaseHandoff(round);
		if (round.incomingEntitlements != 0 && round.references == round.incomingEntitlements) {
			// Only handoff holds remain: no actual scanner or accepted-completion owner needs this replacement.
			releaseAbandonedEntitlements(round);
			return;
		}
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
			releaseEntitlement(consumer);
			if (consumer.completedAssociation != null) { consumer.completedAssociation.close(); consumer.completedAssociation = null; }
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
	private void releaseAbandonedEntitlements(Round round) {
		int holds = round.incomingEntitlements;
		clearSuccessorProvenance(round);
		// Detach the complete bounded cohort before releasing references: close/release recursion must not see stale holds.
		for (Consumer consumer : consumers) if (consumer.successorEntitlement == round) {
			consumer.successorEntitlement = null;
		}
		round.incomingEntitlements = 0;
		for (int hold = 0; hold < holds; hold++) releaseScanner(round, true);
	}
	private void releaseEntitlement(Consumer consumer) {
		@Nullable Round entitled = consumer.successorEntitlement; consumer.successorEntitlement = null;
		if (entitled != null) { entitled.incomingEntitlements--; releaseScanner(entitled, true); }
	}
	/** No actual scanner or accepted-completion reference remains. Adoption itself must never clear this provenance. */
	private void clearSuccessorProvenance(Round successor) {
		// At most MAX_RETAINED_ROUNDS scalar comparisons; no graph records or strong predecessor links escape round admission.
		for (Round previous : liveRounds) if (previous.successorSequence == successor.sequence) previous.successorSequence = 0;
		// A sealed predecessor stays sealed: clearing its dead successor does not authorize new old-policy entitlements.
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
		clearSuccessorProvenance(round); // includes the all-adopted, zero-entitlement final release
		Error fatal = null;
		try { if (round.handle != null) round.handle.close(); } catch (Error failure) { fatal = failure; }
		finally {
			round.memory.close(); round.catalogMemory.forEach(RetainedMemoryLedger.Ticket::close); round.catalogMemory.clear();
			if (round.bookkeepingMemory != null) { round.bookkeepingMemory.close(); round.bookkeepingMemory = null; }
			if (round.nativeMemory != null) { round.nativeMemory.close(); round.nativeMemory = null; }
			rounds.remove(round.lookupKey, round); liveRounds.remove(round); retainedRounds--;
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
			} else if (consumer.input.sameBinding(input) && (consumer.scanningAssociation != null || consumer.completedAssociation != null)) {
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
		for (Consumer consumer : consumers) if (consumer.round == round || consumer.completedRound == round || consumer.successorEntitlement == round) {
			consumer.scopeTransition |= round.handle != null && round.handle.scopeTransition();
			consumer.cancelDelays();
			consumer.recoveryFloor = roundSequence + 1;
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
			if (round.sweep != null || round.handle.readMode() == InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL) {
				if (round.appliedBytes <= ceiling) continue;
				int refusal = tightenNative(consumer, round, ceiling);
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
					int refusal = reprojectSlots(consumer, round, round.passCeiling, end);
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
	private int tightenNative(Consumer consumer, Round round, int ceiling) {
		@Nullable InventorySlotJournal journal = round.journal;
		InventoryItemCodec.@Nullable Catalog catalog = nativeCatalog(round);
		if (journal != null && !growBookkeeping(consumer, round, round.paid.size(), journal.definitionCount()))
			return consumer.currentReason == InventoryFailureReason.MEMORY_LIMIT ? Reason.MEMORY.bit() : Reason.PUBLICATION_WORK.bit();
		if (journal != null && !claimDetachedItemWork(reprojectionCopyWork(journal.definitionCount()))) return Reason.PUBLICATION_WORK.bit();
		// A changed definition is certified once after reprojection, never on replay.
		// Account its validation scan/UTF8 scratch independently of the catalogue parser.
		var refresh = memory.tryReserve(journal == null ? 0 : journal.refreshWorkspaceUpperBound());
		if (refresh.isEmpty()) return Reason.MEMORY.bit();
		try (var held = refresh.get()) {
		if (journal != null && !claimDetachedItemWork(journal.refreshWorkUpperBound())) return Reason.PUBLICATION_WORK.bit();
		int refusal = tightenSource(round, ceiling, () -> {
			if (journal != null && catalog != null) {
			// The admitted key-index tier owns reference/set bookkeeping; no second parser is run.
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
			var summary = round.sweep == null ? round.handle.reprojectionSummary(ceiling) : round.sweep.catalog == null ? InventoryReprojectionSummary.EMPTY : round.sweep.catalog.reprojectionSummary(ceiling);
			if (summary == null || !summary.known()) { unsupportedSourceProjection(round); return 0; }
			var admission = memory.tryReserve(summary.workspaceBytes());
			if (admission.isEmpty()) return Reason.MEMORY.bit();
			try (var held = admission.get()) {
				if (!claimDetachedItemWork(summary.workUnits())) return Reason.PUBLICATION_WORK.bit();
				if (round.sweep == null) round.handle.tightenDisplay(ceiling);
				else if (round.sweep.catalog != null) round.sweep.catalog.tightenDisplay(ceiling);
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
	private int reprojectSlots(Consumer consumer, Round round, int ceiling, int end) {
		long parserBound = 0, replacementBound = 0;
		long work = reprojectionCopyWork(end - round.reprojectCursor, round.slots.size());
		for (int index = round.reprojectCursor; index < end; index++) {
			@Nullable InventoryResourceValue item = round.slots.get(index);
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
		if (!growBookkeeping(consumer, round, round.paid.size(), Math.min(InventorySourceAccess.MAX_SLOTS,
			(round.tightenedKeys == null ? 0 : round.tightenedKeys.size()) + end - round.reprojectCursor)))
			return consumer.currentReason == InventoryFailureReason.MEMORY_LIMIT ? Reason.MEMORY.bit() : Reason.PUBLICATION_WORK.bit();
		var workspace = memory.tryReserve(checkedAdd(PAGE_WORKSPACE_FIXED, checkedAdd(parserBound, replacementBound)));
		if (workspace.isEmpty()) return Reason.MEMORY.bit();
		boolean transferred = false;
		try {
			if (!claimDetachedItemWork(work)) return Reason.PUBLICATION_WORK.bit();
			long measured = 0;
			for (int index = round.reprojectCursor; index < end; index++) {
				@Nullable InventoryResourceValue item = round.slots.get(index);
				if (item == null || item.displayJson() == null || utf8Bytes(item.displayJson()) <= ceiling) continue;
				@Nullable String projectedJson;
				if (item.resourceKind() == InventoryResourceKind.ITEM) {
					var retained = new InventoryItemCodec.Display(item.resourceId(), item.label(), item.displayJson(), item.aggregateRequired());
					projectedJson = reprojector.reproject(retained, ceiling).displayJson();
				} else projectedJson = null; // display-only refusal never alters native fluid identity
				if (java.util.Objects.equals(projectedJson, item.displayJson())) continue;
				round.slots.set(index, InventoryResourceCodec.value(item.key(), item.amount(), item.label(), projectedJson, item.aggregateRequired()));
				round.pagesChanged = true;
				round.pagesSettled = false;
				if (round.tightenedKeys == null) {
					round.tightenedKeys = new java.util.LinkedHashSet<>();
				}
				round.tightenedKeys.add(item.key());
				measured = checkedAdd(measured, checkedAdd(checkedMultiply(utf8Bytes(projectedJson), 2L), 1024L));
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
		for (@Nullable InventoryResourceValue item : round.slots) measured = checkedAdd(measured, itemRetainedBytes(item));
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
	private static long itemRetainedBytes(@Nullable InventoryResourceValue item) {
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
	@Override public void close() { for (OperationGrant grant : List.copyOf(operationGrants)) grant.close(); for (Consumer consumer : List.copyOf(consumers)) consumer.close(); clearLogical(true); clearLogical(false); }
}
