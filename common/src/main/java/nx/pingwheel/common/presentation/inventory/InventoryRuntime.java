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
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;

/** One server-lifetime owner of physical rounds, logical coverage and retained admission. */
public final class InventoryRuntime implements AutoCloseable {
	private static final int MAX_CONSUMERS = 256, MAX_ROUNDS = 32;
	private static final int MAX_RETAINED_ROUNDS = MAX_ROUNDS * 2; // bounded replacement headroom for completed evidence leases
	private static final long CONSUMER_MEMORY = 4096;
	private static final long ROUND_MEMORY = 4096 + 262144 + 32768; // provider catalog and bounded logical subject history
	private static final int REPROJECT_BATCH = 64; // bounded retained-metadata walk per tightening call
	private static final long PAGE_FIXED_BYTES = 4096L, PAGE_WORKSPACE_FIXED = 8192L;
	/**
	 * Metadata above the display budget in one encoded observation, derived from
	 * the codec's own envelope so a measured page reservation never charges the
	 * absolute display bound to a small payload.
	 */
	private static final int ENTRY_METADATA_BYTES = InventoryDomainCodec.MAX_BYTES - InventoryDomainCodec.MAX_DISPLAY_BYTES;
	public sealed interface Subject permits PreviewSubject, TrackingSubject {}
	public record PreviewSubject(UUID client) implements Subject {}
	public record TrackingSubject(TargetKey target) implements Subject {}
	public record Observation(CaptureResult result, List<InventoryDomainCodec.Item> slots) {
		public Observation { slots = java.util.Collections.unmodifiableList(new ArrayList<>(slots)); }
	}
	/** Server-thread source resolver; every new observation receives the authoritative display ceiling. */
	@FunctionalInterface
	public interface SourceResolver {
		Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes);
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
	 * calls; {@code foldIds} names item ids whose retained display now carries
	 * complete component stripping, and the caller decides folding.
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
			observationFloor = tick;
			firstRound = roundSequence + 1;
		}
		public InventorySourceInput input() { return input; }
		public boolean invalidated() { return invalidObservation != null; }
		/** Pins the complete observation actually accepted for publication, not a rejected or overflowing sweep. */
		public void retainCompleted() {
			if (closed || invalidObservation != null || round == null || round.last == null
				|| round.last.completeness() != CaptureResult.Completeness.COMPLETE || cursor != round.slots.size())
				throw new IllegalStateException("no complete inventory observation");
			completed(this, round);
		}
		public void restart() {
			if (round != null) rounds.remove(round.key, round); // new observation evidence, not source identity
			releaseScanning(this); invalidObservation = null; pendingObservation = null; cursor = 0; observationFloor = tick; firstRound = 0;
			if (completedRound == null) releaseBindingEvidence(this);
		}
		/** Releases this consumer's invalid data/evidence without invalidating unrelated physical rounds. */
		public void discardObservation() {
			invalidObservation = unavailable(input.viewKey()); pendingObservation = null; cursor = 0;
			release(this);
		}
		@Override public void close() {
			if (!closed) {
				closed = true;
				invalidObservation = null; pendingObservation = null;
				try { release(this); } finally { consumers.remove(this); memory.close(); }
			}
		}
	}
	private static final class Round {
		final SourceKey key;
		final InventorySourceInput input;
		final InventorySourceAccess.InventoryHandle handle;
		final RetainedMemoryLedger.Ticket memory;
		final long startedTick;
		final long sequence;
		boolean trackingObservation;
		InventorySourceAccess.Preparation preparation;
		final List<InventoryDomainCodec.Item> slots = new ArrayList<>();
		final Map<Subject, Integer> paid = new LinkedHashMap<>();
		final List<RetainedMemoryLedger.Ticket> pages = new ArrayList<>();
		CaptureResult last;
		Observation failureObservation;
		int references;
		int scanners; // consumer scans plus bounded physical handoff leases
		int appliedBytes; // ceiling fully walked over this round's decoded slots
		int passCeiling; // target of the active tightening pass
		int reprojectCursor; // bounded tightening walk position over decoded slots
		int reprojectEnd; // slots.size() frozen at pass start; later slots are already tightened
		boolean passActive;
		int pageCount; // page commits ever appended, so a settled swap keeps their fixed overhead
		boolean pagesChanged;
		boolean pagesSettled;
		Set<InventoryScanner.Key> tightenedKeys;
		Set<String> tightenedFoldIds;
		Round successor;
		final Set<Round> predecessors = new java.util.LinkedHashSet<>();
		boolean closed;
		Round(SourceKey key, InventorySourceInput input, InventorySourceAccess.InventoryHandle handle, RetainedMemoryLedger.Ticket memory, long tick, long sequence, boolean tracking, int displayBytes) {
			this.key = key; this.input = input; this.handle = handle; this.memory = memory; startedTick = tick; this.sequence = sequence; trackingObservation = tracking;
			this.appliedBytes = displayBytes;
		}
	}
	private final SourceResolver resolver;
	private final DisplayReprojector reprojector;
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
		this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
		this.reprojector = java.util.Objects.requireNonNull(reprojector, "reprojector");
		memory = new RetainedMemoryLedger(memoryBytes);
	}
	public RetainedMemoryLedger memory() { return memory; }
	public boolean selectionPresent(InventorySourceInput input, InventorySelection selection, int witness) {
		int displayCap = effectiveDisplayCap();
		long slotWorkspace = slotWorkspaceBytes(displayCap);
		int bound = (int) Math.min(physicalRemaining(), Math.min(InventorySourceAccess.MAX_STEP, Math.max(0, (memory.remaining() - ROUND_MEMORY) / slotWorkspace)));
		if (bound == 0) return false;
		var workspace = memory.tryReserve(checkedAdd(ROUND_MEMORY, checkedMultiply(bound, slotWorkspace)));
		if (workspace.isEmpty()) return false;
		try (var held = workspace.get()) {
			return new InventorySourceAccess(input, i -> resolve(i, displayCap)).selectionPresent(selection, witness, bound, physical);
		}
	}
	public <T> Optional<T> preflight(java.util.function.Supplier<Optional<T>> work) {
		var ticket = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (ticket.isEmpty()) return Optional.empty();
		try (var grant = ticket.get()) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK)); return work.get();
		} catch (RuntimeException | LinkageError failure) { return Optional.empty(); }
	}
	public void advance(long now, InventorySettings settings) {
		if (now < tick) throw new IllegalArgumentException("inventory clock regressed");
		this.settings = settings;
		memory.setCap(settings.pendingMemoryBytes());
		if (now != tick) {
			tick = now;
			physical = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) settings.effectivePhysicalSlotsPerTick(),
				InventorySourceAccess.PROBES, 256L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK));
		}
		if (previewClock.advance(now, settings.getPreview().getPeriodTicks())) { previewGlobal = 0; clearLogical(true); }
		if (trackingClock.advance(now, settings.getTracking().getPeriodTicks())) { trackingGlobal = 0; clearLogical(false); }
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
	private Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input) {
		return resolve(input, effectiveDisplayCap());
	}
	private Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes) {
		return resolver.resolve(input, displayBytes);
	}
	/** One encoded observation's true upper bound at a validated display ceiling. */
	private static long slotWorkspaceBytes(int displayBytes) {
		long entry = Math.min(InventoryDomainCodec.MAX_BYTES, displayBytes + (long) ENTRY_METADATA_BYTES);
		return checkedAdd(checkedMultiply(entry, 2L), 512L);
	}
	private static int clampDisplayBytes(int displayBytes) {
		return Math.clamp(displayBytes, InventoryItemCodec.minimumDisplayBytes(), InventoryDomainCodec.MAX_DISPLAY_BYTES);
	}
	private static long utf8Bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
	private static long checkedAdd(long first, long second) {
		try { return Math.addExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	private static long checkedMultiply(long first, long second) {
		try { return Math.multiplyExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
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
		var workspace = memory.tryReserve(ROUND_MEMORY);
		if (workspace.isEmpty()) return Optional.empty();
		try (var reserved = workspace.get()) {
		var ticket = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (ticket.isEmpty()) return Optional.empty();
		try (var grant = ticket.get()) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			try (var source = resolve(input).orElse(null)) {
				boolean valid = source != null && source.valid();
				if (!valid) retireInvalid(input);
				return Optional.of(valid);
			}
		} catch (RuntimeException | LinkageError unavailable) { retireInvalid(input); return Optional.of(false); }
		}
	}
	/** Checks the actual observation being published, including the completed result retained during a new sweep. */
	public Optional<Boolean> probe(Consumer consumer) {
		if (consumer.closed || consumer.invalidObservation != null) return Optional.of(false);
		Round completed = consumer.completedRound, scanning = consumer.round;
		if (completed == null && scanning == null) return probe(consumer.input);
		int calls = (completed != null && scanning != null && completed != scanning ? 2 : 1) + (consumer.bindingEvidence == null ? 0 : 1);
		var workspace = memory.tryReserve(ROUND_MEMORY);
		if (workspace.isEmpty()) return Optional.empty();
		try (var reserved = workspace.get()) {
			var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, (long) calls,
				InventorySourceAccess.PROVIDER_WORK, calls * InventorySourceAccess.PROVIDER_CALL_WORK));
			if (admission.isEmpty()) return Optional.empty();
			int used = 0;
			try (var grant = admission.get()) {
				try {
					if (consumer.bindingEvidence != null) {
						used++;
						if (!bindingEvidenceValid(consumer)) { consumer.discardObservation(); return Optional.of(false); }
					}
					for (Round round : completed == null ? List.of(scanning) : scanning == null || completed == scanning ? List.of(completed) : List.of(completed, scanning)) {
						used++; // attempted validity calls remain charged, including exceptions and invalid retirement
						boolean valid;
						try { valid = !round.closed && round.handle.evidenceValid(); }
						catch (RuntimeException | LinkageError unavailable) { valid = false; }
						if (!valid) { retire(round, unavailable(consumer.input.viewKey())); return Optional.of(false); }
					}
					return Optional.of(true);
				} finally {
					grant.commit(Map.of(InventorySourceAccess.PROBES, (long) used,
						InventorySourceAccess.PROVIDER_WORK, used * InventorySourceAccess.PROVIDER_CALL_WORK));
				}
			}
		}
	}

	public Optional<Observation> step(Consumer consumer) {
		return step(consumer, InventorySourceAccess.MAX_STEP);
	}
	/** Fix the physical capture route before scan or logical allowance is considered. */
	public InventorySourceAccess.Preparation prepare(Consumer consumer) {
		if (consumer.closed) return InventorySourceAccess.Preparation.UNAVAILABLE;
		if (consumer.invalidObservation != null || consumer.pendingObservation != null)
			return InventorySourceAccess.Preparation.UNAVAILABLE;
		Round handoff = consumer.completedRound == null ? null : consumer.completedRound.successor;
		if (consumer.round == null && consumer.completedRound != null && !consumer.completedRound.predecessors.isEmpty())
			return InventorySourceAccess.Preparation.DEFERRED; // finish the physical handoff before another replacement forks
		var bindingCheck = checkBindingEvidence(consumer);
		if (bindingCheck != InventorySourceAccess.Preparation.READY) return bindingCheck;
		if (consumer.round == null && handoff != null && consumer.input.sameBinding(handoff.input)) return prepareHandoff(consumer, handoff);
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
				&& shareable(consumer, round))) return InventorySourceAccess.Preparation.DEFERRED;
			var retained = memory.tryReserve(ROUND_MEMORY);
			if (retained.isEmpty()) return InventorySourceAccess.Preparation.DEFERRED;
			boolean ownsReservation = true;
			SourceAccess.Handle openedHandle = null;
			try {
				int displayCap = effectiveDisplayCap();
				var access = new InventorySourceAccess(consumer.input, i -> resolve(i, displayCap));
				var scope = new SourceAccess.ReadScope(consumer.input.viewKey(), Set.of("pingforit:inventory.items"));
				var resolved = access.resolve(MinecraftBlockReadSources.detached(consumer.input.target()), scope, physical);
				if (!(resolved instanceof SourceAccess.ResolveResult.Available available)) {
					if (resolved == SourceAccess.ResolveResult.Unresolved.DEFERRED) return InventorySourceAccess.Preparation.DEFERRED;
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
					return prepareHandoff(consumer, handoff);
				}
				Round round = rounds.get(available.descriptor().key());
				// A newly selected Ping never inherits a completed preview count.
				if (round != null && !shareable(consumer, round)) round = null;
				if (round == null) {
					// Another hit position may resolve to a compatible canonical alias even at capacity.
					// Only an actual new physical round needs the normal/replacement admission slot.
					if (!canCreate) return InventorySourceAccess.Preparation.DEFERRED;
					if (consumer.completedRound != null && !consumer.completedRound.key.equals(available.descriptor().key())) {
						retire(consumer.completedRound, unavailable(scope.viewKey()));
						return InventorySourceAccess.Preparation.UNAVAILABLE; // canonical view topology changed, not admission pressure
					}
					retained.get().commit(ROUND_MEMORY);
					round = new Round(available.descriptor().key(), consumer.input, (InventorySourceAccess.InventoryHandle) started.handle(), retained.get(), tick, ++roundSequence, consumer.subject instanceof TrackingSubject, displayCap);
					retainedRounds++;
					scanningRounds++;
					rounds.put(round.key, round);
					ownsReservation = false; openedHandle = null;
				}
				else if (retainBindingEvidence(consumer, round, (InventorySourceAccess.InventoryHandle) openedHandle, retained.get())) {
					ownsReservation = false; openedHandle = null;
				}
				join(consumer, round);
				var admittedBinding = checkBindingEvidence(consumer);
				if (admittedBinding != InventorySourceAccess.Preparation.READY) return admittedBinding;
			} finally {
				try { if (openedHandle != null) openedHandle.close(); }
				finally { if (ownsReservation) retained.get().close(); }
			}
		}
		Round round = consumer.round;
		if (round.preparation != null) return round.preparation;
		var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		if (admission.isEmpty()) return InventorySourceAccess.Preparation.DEFERRED;
		try (var grant = admission.get()) {
			grant.commit(Map.of(InventorySourceAccess.PROBES, 1L,
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			InventorySourceAccess.Preparation outcome = round.handle.prepareSnapshot(memory);
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
	private boolean bindingEvidenceValid(Consumer consumer) {
		try { return consumer.bindingEvidence == null || consumer.bindingEvidence.evidenceValid(); }
		catch (RuntimeException | LinkageError unavailable) { return false; }
	}
	/** Cache consumption checks its own original binding, not only the peer whose physical sweep was shared. */
	private InventorySourceAccess.Preparation checkBindingEvidence(Consumer consumer) {
		if (consumer.bindingEvidence == null) return InventorySourceAccess.Preparation.READY;
		var workspace = memory.tryReserve(ROUND_MEMORY);
		if (workspace.isEmpty()) return InventorySourceAccess.Preparation.DEFERRED;
		try (var reserved = workspace.get()) {
			var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L,
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			if (admission.isEmpty()) return InventorySourceAccess.Preparation.DEFERRED;
			try (var grant = admission.get()) {
				grant.commit(Map.of(InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
				if (!bindingEvidenceValid(consumer)) { consumer.discardObservation(); return InventorySourceAccess.Preparation.UNAVAILABLE; }
				return InventorySourceAccess.Preparation.READY;
			}
		}
	}
	private InventorySourceAccess.Preparation prepareHandoff(Consumer consumer, Round handoff) {
		var bindingCheck = checkBindingEvidence(consumer);
		if (bindingCheck != InventorySourceAccess.Preparation.READY) return bindingCheck;
		// A cached successor may be consumed after its original scanner's tick. Validate its
		// own retained evidence, not just the slow peer's still-published predecessor.
		var workspace = memory.tryReserve(ROUND_MEMORY);
		if (workspace.isEmpty()) return InventorySourceAccess.Preparation.DEFERRED;
		try (var reserved = workspace.get()) {
			var admission = physical.tryReserve(Map.of(InventorySourceAccess.PROBES, 1L,
				InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
			if (admission.isEmpty()) return InventorySourceAccess.Preparation.DEFERRED;
			try (var grant = admission.get()) {
				grant.commit(Map.of(InventorySourceAccess.PROBES, 1L,
					InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
				boolean valid;
				try { valid = !handoff.closed && handoff.handle.evidenceValid(); }
				catch (RuntimeException | LinkageError unavailable) { valid = false; }
				if (!valid) {
					Observation invalid = unavailable(consumer.input.viewKey());
					retire(handoff, invalid); consumer.pendingObservation = invalid;
					return InventorySourceAccess.Preparation.UNAVAILABLE;
				}
			}
		}
		join(consumer, handoff);
		return handoff.preparation == null ? prepare(consumer) : handoff.preparation;
	}
	public Optional<Observation> step(Consumer consumer, int slotBound) {
		if (slotBound < 1 || slotBound > InventorySourceAccess.MAX_STEP) throw new IllegalArgumentException("inventory step bound");
		if (consumer.closed) return Optional.empty();
		if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
		if (consumer.pendingObservation != null) return Optional.of(consumer.pendingObservation);
		InventorySourceAccess.Preparation preparation = prepare(consumer);
		if (preparation == InventorySourceAccess.Preparation.DEFERRED) return Optional.empty();
		if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
		if (preparation != InventorySourceAccess.Preparation.READY) {
			Round failedRound = consumer.round;
			if (failedRound == null) return Optional.ofNullable(consumer.pendingObservation);
			if (failedRound.failureObservation == null) {
				var zero = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, 0L, InventorySourceAccess.PROBES, 0L,
					InventorySourceAccess.PROVIDER_WORK, 0L));
				SourceAccess.StepOutcome outcome = failedRound.handle.step(zero);
				if (outcome instanceof SourceAccess.StepOutcome.Deferred) return Optional.empty();
				CaptureResult failed = ((SourceAccess.StepOutcome.Captured) outcome).result();
				failedRound.last = failed;
				failedRound.failureObservation = new Observation(failed, List.of());
			}
			Observation terminal = failedRound.failureObservation;
			if (terminal.result().availability() != CaptureResult.Availability.READABLE) retire(failedRound, terminal);
			return Optional.of(terminal); // INCOMPLETE remains explicit, never pretend-complete.
		}
		if (!logicalUsed.containsKey(consumer.subject) && logicalUsed.size() >= MAX_CONSUMERS) return Optional.empty();
		if (!logicalMemory.containsKey(consumer.subject)) {
			var held = memory.tryReserve(1024);
			if (held.isEmpty()) return Optional.empty();
			held.get().commit(1024); logicalMemory.put(consumer.subject, held.get()); logicalUsed.put(consumer.subject, 0);
		}
		int cap = consumer.subject instanceof PreviewSubject ? settings.getPreview().effectiveMaxSlotsPerClient()
			: settings.getTracking().effectiveMaxSlotsPerTarget();
		int globalCap = consumer.subject instanceof PreviewSubject ? settings.getPreview().effectiveMaxSlotsServer()
			: settings.getTracking().effectiveMaxSlotsServer();
		int global = consumer.subject instanceof PreviewSubject ? previewGlobal : trackingGlobal;
		int allowance = Math.min(slotBound, Math.min(cap - logicalUsed.getOrDefault(consumer.subject, 0), globalCap - global));
		Round round = consumer.round;
		if (!round.paid.containsKey(consumer.subject) && round.paid.size() >= MAX_CONSUMERS) return Optional.empty();
		if (consumer.subject instanceof TrackingSubject) round.trackingObservation = true;
		int paid = round.paid.getOrDefault(consumer.subject, 0);
		int available = Math.min(slotBound, Math.max(0, paid - consumer.cursor) + Math.max(0, allowance));
		if (available == 0) {
			if (round.slots.isEmpty() && round.last != null && zeroCostEmptyCompletion(round.last)) {
				return Optional.of(new Observation(round.last, List.of()));
			}
			if (consumer.cursor < round.slots.size() || round.last != null && round.last.completeness() != CaptureResult.Completeness.CONTINUE) return Optional.empty();
			SourceAccess.StepOutcome outcome = zeroAllowanceStep(round);
			if (outcome instanceof SourceAccess.StepOutcome.Deferred) return Optional.empty();
			CaptureResult result = ((SourceAccess.StepOutcome.Captured) outcome).result();
			if (result.availability() != CaptureResult.Availability.READABLE) {
				Observation invalid = new Observation(result, List.of());
				retire(round, invalid);
				return Optional.of(invalid);
			}
			if (!zeroCostEmptyCompletion(result)) return Optional.empty();
			round.last = result;
			return Optional.of(new Observation(result, List.of()));
		}
		if (consumer.cursor >= round.slots.size() && (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE)) {
			long slotWorkspace = slotWorkspaceBytes(round.handle.displayBytes());
			int memoryBound = (int) Math.min(available, Math.max(0, (memory.remaining() - PAGE_WORKSPACE_FIXED) / slotWorkspace));
			if (memoryBound == 0) return Optional.empty();
			var workspace = memory.tryReserve(checkedAdd(checkedMultiply(memoryBound, slotWorkspace), PAGE_WORKSPACE_FIXED));
			if (workspace.isEmpty()) return Optional.empty();
			boolean retainedPage = false;
			try {
			int bounded = Math.min(memoryBound, (int) Math.min(physicalRemaining(),
				Math.max(0, physical.remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK - 1)));
			if (bounded == 0) {
				SourceAccess.StepOutcome outcome = zeroAllowanceStep(round);
				if (outcome instanceof SourceAccess.StepOutcome.Deferred) { workspace.get().close(); return Optional.empty(); }
				CaptureResult zeroResult = ((SourceAccess.StepOutcome.Captured) outcome).result();
				if (zeroResult.availability() != CaptureResult.Availability.READABLE) {
					Observation invalid = new Observation(zeroResult, List.of());
					workspace.get().close(); retire(round, invalid); return Optional.of(invalid);
				}
				if (!zeroCostEmptyCompletion(zeroResult)) { workspace.get().close(); return Optional.empty(); }
				round.last = zeroResult;
				workspace.get().commit(PAGE_FIXED_BYTES); round.pages.add(workspace.get()); round.pageCount++; retainedPage = true;
			}
			if (bounded == 0) {
				// Snapshot handles may report an empty COMPLETE observation without scan allowance;
				// live handles defer before touching their source at zero allowance.
			} else {
			long workBound = (bounded + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK;
			var grant = physical.tryReserve(Map.of(InventorySourceAccess.PHYSICAL, (long) bounded, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, workBound));
			if (grant.isEmpty()) { workspace.get().close(); return Optional.empty(); }
			SourceAccess.StepOutcome result;
			try (var physicalTicket = grant.get()) {
				CostLedger step = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) bounded, InventorySourceAccess.PROBES, 1L, InventorySourceAccess.PROVIDER_WORK, workBound));
				result = round.handle.step(step);
				physicalTicket.commit(Map.of(InventorySourceAccess.PHYSICAL, bounded - step.remaining(InventorySourceAccess.PHYSICAL),
					InventorySourceAccess.PROBES, 1L - step.remaining(InventorySourceAccess.PROBES), InventorySourceAccess.PROVIDER_WORK, workBound - step.remaining(InventorySourceAccess.PROVIDER_WORK)));
			}
			if (result instanceof SourceAccess.StepOutcome.Deferred) { workspace.get().close(); return Optional.empty(); }
			round.last = ((SourceAccess.StepOutcome.Captured) result).result();
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
				for (var value : page.entries().values())
					retainedBytes = checkedAdd(retainedBytes, checkedAdd(checkedMultiply(value.size(), 2L), 256L));
			workspace.get().commit(retainedBytes);
			round.pages.add(workspace.get());
			round.pageCount++;
			retainedPage = true;
			}
			} finally { if (!retainedPage) workspace.get().close(); }
		}
		if (round.last == null) return Optional.empty();
		if (round.last.availability() != CaptureResult.Availability.READABLE)
			return Optional.of(new Observation(round.last, List.of()));
		int end = Math.min(round.slots.size(), consumer.cursor + available);
		int charge = Math.max(0, end - paid);
		CostLedger logical = new CostLedger(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) Math.max(0, allowance)));
		var ticket = logical.tryReserve(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) charge)).orElseThrow();
		ticket.commit(Map.of(new CostLedger.Counter("inventory/logical", CostLedger.Unit.LOGICAL_PROGRESS), (long) charge));
		logicalUsed.merge(consumer.subject, charge, Integer::sum);
		if (consumer.subject instanceof PreviewSubject) previewGlobal += charge; else trackingGlobal += charge;
		round.paid.put(consumer.subject, Math.max(paid, end));
		List<InventoryDomainCodec.Item> page = new ArrayList<>(round.slots.subList(consumer.cursor, end));
		consumer.cursor = end;
		CaptureResult.Completeness completeness = end == round.slots.size() ? round.last.completeness() : CaptureResult.Completeness.CONTINUE;
		CaptureResult result = new CaptureResult(Optional.empty(), new CaptureResult.Coverage(round.last.coverage().demandStamp(), end, end,
			round.last.coverage().expected()), CaptureResult.Availability.READABLE, completeness, round.last.consistency(), round.last.sourceVersion(), Optional.empty());
		return Optional.of(new Observation(result, page));
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
	private SourceAccess.StepOutcome zeroAllowanceStep(Round round) {
		Map<CostLedger.Counter, Long> limits = Map.of(InventorySourceAccess.PHYSICAL, 0L, InventorySourceAccess.PROBES, 1L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK);
		var admission = physical.tryReserve(limits);
		if (admission.isEmpty()) return new SourceAccess.StepOutcome.Deferred();
		try (var grant = admission.get()) {
			CostLedger step = new CostLedger(limits);
			SourceAccess.StepOutcome outcome = round.handle.step(step);
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
		round.pages.forEach(RetainedMemoryLedger.Ticket::close); round.pages.clear();
		round.slots.clear(); round.paid.clear(); round.last = null;
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
			round.memory.close(); rounds.remove(round.key, round); retainedRounds--;
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
	 */
	public Reprojection reprojectDisplays(Consumer consumer, int displayBytes) {
		int ceiling = clampDisplayBytes(displayBytes);
		if (consumer == null || consumer.closed || consumer.invalidated()) return Reprojection.unchanged(ceiling);
		var targets = new ArrayList<Round>(2);
		if (consumer.round != null && !consumer.round.closed) targets.add(consumer.round);
		if (consumer.completedRound != null && consumer.completedRound != consumer.round && !consumer.completedRound.closed)
			targets.add(consumer.completedRound);
		boolean pending = false, worked = false;
		for (Round round : targets) {
			// Lower the catalogue ceiling first so slots appended by an in-progress
			// sweep are already tightened and never need this walk.
			if (round.passActive && ceiling < round.passCeiling) {
				// A lower ceiling restarts the pass: earlier items may still exceed it.
				round.handle.tightenDisplay(ceiling);
				round.passCeiling = ceiling;
				round.reprojectCursor = 0;
				round.reprojectEnd = round.slots.size();
				worked = true;
			} else if (!round.passActive && round.appliedBytes > ceiling) {
				round.handle.tightenDisplay(ceiling);
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
					if (!reprojectSlots(round, round.passCeiling, end)) { pending = true; continue; }
				}
				if (round.reprojectCursor < round.reprojectEnd) { pending = true; continue; }
				round.passActive = false;
				round.appliedBytes = round.passCeiling;
			}
			if (round.pagesChanged && !round.pagesSettled) {
				worked = true;
				if (!settlePages(round)) pending = true;
			}
		}
		if (pending) return new Reprojection(ReprojectionStatus.DEFERRED, ceiling, changedKeys(targets), foldIds(targets));
		return new Reprojection(worked ? ReprojectionStatus.COMPLETED : ReprojectionStatus.UNCHANGED, ceiling,
			changedKeys(targets), foldIds(targets));
	}

	/** Reprojects one measured batch; false defers before any allocation or cursor movement. */
	private boolean reprojectSlots(Round round, int ceiling, int end) {
		long workspaceBound = PAGE_WORKSPACE_FIXED;
		for (int index = round.reprojectCursor; index < end; index++) {
			InventoryDomainCodec.Item item = round.slots.get(index);
			if (item == null || item.displayJson() == null) continue;
			long bytes = utf8Bytes(item.displayJson());
			if (bytes > ceiling) workspaceBound = checkedAdd(workspaceBound, bytes);
		}
		if (workspaceBound == PAGE_WORKSPACE_FIXED) {
			round.reprojectCursor = end; // nothing in this batch exceeds the ceiling
			return true;
		}
		var workspace = memory.tryReserve(workspaceBound);
		if (workspace.isEmpty()) return false;
		try (var reserved = workspace.get()) {
			long measured = PAGE_WORKSPACE_FIXED;
			for (int index = round.reprojectCursor; index < end; index++) {
				InventoryDomainCodec.Item item = round.slots.get(index);
				if (item == null || item.displayJson() == null || utf8Bytes(item.displayJson()) <= ceiling) continue;
				var retained = new InventoryItemCodec.Display(item.key().itemId(), item.label(), item.displayJson(), item.stripped());
				var projected = reprojector.reproject(retained, ceiling);
				if (projected.equals(retained)) continue;
				round.slots.set(index, new InventoryDomainCodec.Item(item.key(), item.count(),
					projected.label(), projected.displayJson(), projected.componentsStripped()));
				round.pagesChanged = true;
				round.pagesSettled = false;
				if (round.tightenedKeys == null) {
					round.tightenedKeys = new java.util.LinkedHashSet<>();
					round.tightenedFoldIds = new java.util.LinkedHashSet<>();
				}
				round.tightenedKeys.add(item.key());
				if (projected.componentsStripped()) round.tightenedFoldIds.add(item.key().itemId());
				measured = checkedAdd(measured, utf8Bytes(projected.displayJson()));
			}
			reserved.commit(measured);
		}
		round.reprojectCursor = end;
		return true;
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
		var ids = new java.util.LinkedHashSet<String>();
		for (Round round : rounds) if (round.tightenedFoldIds != null) ids.addAll(round.tightenedFoldIds);
		return List.copyOf(ids);
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
