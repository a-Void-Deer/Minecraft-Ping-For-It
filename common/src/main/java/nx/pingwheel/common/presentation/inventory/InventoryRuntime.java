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
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;

/** One server-lifetime owner of physical rounds, logical coverage and retained admission. */
public final class InventoryRuntime implements AutoCloseable {
	private static final int MAX_CONSUMERS = 256, MAX_ROUNDS = 32;
	private static final long CONSUMER_MEMORY = 4096;
	private static final long ROUND_MEMORY = 4096 + 262144 + 32768; // provider catalog and bounded logical subject history
	public sealed interface Subject permits PreviewSubject, TrackingSubject {}
	public record PreviewSubject(UUID client) implements Subject {}
	public record TrackingSubject(TargetKey target) implements Subject {}
	public record Observation(CaptureResult result, List<InventoryDomainCodec.Item> slots) {
		public Observation { slots = java.util.Collections.unmodifiableList(new ArrayList<>(slots)); }
	}
	public final class Consumer implements AutoCloseable {
		private final InventorySourceInput input;
		private final Subject subject;
		private final RetainedMemoryLedger.Ticket memory;
		private Round round;
		private Observation invalidObservation;
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
		public void restart() {
			if (round != null) rounds.remove(round.key, round); // new observation evidence, not source identity
			release(this); invalidObservation = null; cursor = 0; observationFloor = tick; firstRound = 0;
		}
		@Override public void close() {
			if (!closed) {
				closed = true;
				invalidObservation = null;
				try { release(this); } finally { consumers.remove(this); memory.close(); }
			}
		}
	}
	private static final class Round {
		final SourceKey key;
		final SourceAccess.Handle handle;
		final RetainedMemoryLedger.Ticket memory;
		final long startedTick;
		final long sequence;
		boolean trackingObservation;
		final List<InventoryDomainCodec.Item> slots = new ArrayList<>();
		final Map<Subject, Integer> paid = new LinkedHashMap<>();
		final List<RetainedMemoryLedger.Ticket> pages = new ArrayList<>();
		CaptureResult last;
		int references;
		Round(SourceKey key, SourceAccess.Handle handle, RetainedMemoryLedger.Ticket memory, long tick, long sequence, boolean tracking) {
			this.key = key; this.handle = handle; this.memory = memory; startedTick = tick; this.sequence = sequence; trackingObservation = tracking;
		}
	}
	private final Function<InventorySourceInput, Optional<InventorySourceAccess.Source>> resolver;
	private final List<Consumer> consumers = new ArrayList<>();
	private final Map<SourceKey, Round> rounds = new LinkedHashMap<>();
	private int retainedRounds;
	private long roundSequence;
	private final RetainedMemoryLedger memory;
	private CostLedger physical;
	private InventorySettings settings;
	private long tick = -1;
	private final InventoryPeriodClock previewClock = new InventoryPeriodClock(), trackingClock = new InventoryPeriodClock();
	private final Map<Subject, Integer> logicalUsed = new LinkedHashMap<>();
	private final Map<Subject, RetainedMemoryLedger.Ticket> logicalMemory = new LinkedHashMap<>();
	private int previewGlobal, trackingGlobal;

	public InventoryRuntime(Function<InventorySourceInput, Optional<InventorySourceAccess.Source>> resolver, long memoryBytes) {
		this.resolver = resolver; memory = new RetainedMemoryLedger(memoryBytes);
	}
	public RetainedMemoryLedger memory() { return memory; }
	public boolean selectionPresent(InventorySourceInput input, InventorySelection selection, int witness) {
		int bound = (int) Math.min(physicalRemaining(), Math.min(InventorySourceAccess.MAX_STEP, Math.max(0, (memory.remaining() - ROUND_MEMORY) / InventoryDomainCodec.MAX_BYTES)));
		if (bound == 0) return false;
		var workspace = memory.tryReserve(ROUND_MEMORY + bound * (long) InventoryDomainCodec.MAX_BYTES);
		if (workspace.isEmpty()) return false;
		try (var held = workspace.get()) { return new InventorySourceAccess(input, resolver).selectionPresent(selection, witness, bound, physical); }
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
	public Optional<Consumer> attach(InventorySourceInput input, Subject subject) {
		if (consumers.size() >= MAX_CONSUMERS) return Optional.empty();
		var admission = memory.tryReserve(CONSUMER_MEMORY);
		if (admission.isEmpty()) return Optional.empty();
		Consumer consumer = new Consumer(input, subject, admission.get());
		admission.get().commit(CONSUMER_MEMORY);
		consumers.add(consumer);
		return Optional.of(consumer);
	}

	/** A publication gate also invokes this on completed cached observations before sending. */
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
			try (var source = resolver.apply(input).orElse(null)) {
				boolean valid = source != null && source.valid();
				if (!valid) retireInvalid(input);
				return Optional.of(valid);
			}
		} catch (RuntimeException | LinkageError unavailable) { retireInvalid(input); return Optional.of(false); }
		}
	}

	public Optional<Observation> step(Consumer consumer) {
		return step(consumer, InventorySourceAccess.MAX_STEP);
	}
	public Optional<Observation> step(Consumer consumer, int slotBound) {
		if (slotBound < 1 || slotBound > InventorySourceAccess.MAX_STEP) throw new IllegalArgumentException("inventory step bound");
		if (consumer.closed) return Optional.empty();
		if (consumer.invalidObservation != null) return Optional.of(consumer.invalidObservation);
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
		if (consumer.round == null) {
			for (Consumer peer : consumers) if (peer != consumer && peer.round != null && peer.input.equals(consumer.input)
				&& shareable(consumer, peer.round)) {
				consumer.round = peer.round; consumer.round.references++; break;
			}
		}
		if (consumer.round == null) {
			if (allowance <= 0 || physicalRemaining() == 0 || retainedRounds >= MAX_ROUNDS) return Optional.empty();
			var retained = memory.tryReserve(ROUND_MEMORY);
			if (retained.isEmpty()) return Optional.empty();
			boolean ownsReservation = true;
			SourceAccess.Handle openedHandle = null;
			try {
			var access = new InventorySourceAccess(consumer.input, resolver);
			var target = consumer.input.target();
			var scope = new SourceAccess.ReadScope(consumer.input.viewKey(), Set.of("pingforit:inventory.items"));
			var resolved = access.resolve(new PresentationAdapter.DetachedTarget(target.dimensionId(), "block", target.blockRegistryId(),
				target.x(), target.y(), target.z(), ""), scope, physical);
			if (!(resolved instanceof SourceAccess.ResolveResult.Available available)) {
				return resolved == SourceAccess.ResolveResult.Unresolved.DEFERRED ? Optional.empty() : Optional.of(unavailable(scope.viewKey()));
			}
			var opened = access.open(available.descriptor(), scope, physical);
			if (!(opened instanceof SourceAccess.OpenResult.Started started)) return Optional.empty();
			openedHandle = started.handle();
			Round round = rounds.get(available.descriptor().key());
			// A newly selected Ping never inherits a completed preview count.
			if (round != null && !shareable(consumer, round)) round = null;
			if (round == null) {
				retained.get().commit(ROUND_MEMORY);
				round = new Round(available.descriptor().key(), started.handle(), retained.get(), tick, ++roundSequence, consumer.subject instanceof TrackingSubject);
				retainedRounds++;
				rounds.put(round.key, round);
				ownsReservation = false; openedHandle = null;
			}
			round.references++; consumer.round = round;
			} finally {
				try { if (openedHandle != null) openedHandle.close(); }
				finally { if (ownsReservation) retained.get().close(); }
			}
		}
		Round round = consumer.round;
		if (!round.paid.containsKey(consumer.subject) && round.paid.size() >= MAX_CONSUMERS) return Optional.empty();
		if (consumer.subject instanceof TrackingSubject) round.trackingObservation = true;
		int paid = round.paid.getOrDefault(consumer.subject, 0);
		int available = Math.min(slotBound, Math.max(0, paid - consumer.cursor) + Math.max(0, allowance));
		if (available == 0) return Optional.empty();
		if (consumer.cursor >= round.slots.size() && (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE)) {
			int memoryBound = (int) Math.min(available, Math.max(0, (memory.remaining() - 8192) / (InventoryDomainCodec.MAX_BYTES * 2L)));
			if (memoryBound == 0) return Optional.empty();
			var workspace = memory.tryReserve((memoryBound * (long) InventoryDomainCodec.MAX_BYTES * 2) + 8192);
			if (workspace.isEmpty()) return Optional.empty();
			boolean retainedPage = false;
			try {
			int bounded = Math.min(memoryBound, (int) Math.min(physicalRemaining(),
				Math.max(0, physical.remaining(InventorySourceAccess.PROVIDER_WORK) / InventorySourceAccess.PROVIDER_CALL_WORK - 1)));
			if (bounded == 0) { workspace.get().close(); return Optional.empty(); }
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
			long retainedBytes = 4096;
			if (round.last.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment page)
				for (var value : page.entries().values()) retainedBytes += value.bytes().length * 2L + 256;
			workspace.get().commit(retainedBytes);
			round.pages.add(workspace.get());
			retainedPage = true;
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
	private boolean shareable(Consumer consumer, Round round) {
		// Observation evidence is local bookkeeping, never physical-source identity.
		if (round.startedTick < consumer.observationFloor || round.sequence < consumer.firstRound) return false;
		if (round.last == null || round.last.completeness() == CaptureResult.Completeness.CONTINUE) return true;
		return !(consumer.subject instanceof TrackingSubject) || round.trackingObservation && round.startedTick == tick;
	}
	private void release(Consumer consumer) {
		Round round = consumer.round; consumer.round = null;
		if (round != null && --round.references == 0) {
			try { round.handle.close(); }
			finally { round.pages.forEach(RetainedMemoryLedger.Ticket::close); round.memory.close(); rounds.remove(round.key, round); retainedRounds--; }
		}
	}
	/** Retire physical state, not a marker or a quota subject. Recovery requires a fresh restart. */
	public void retireInvalid(InventorySourceInput input) {
		var invalidRounds = new java.util.HashSet<Round>();
		for (Consumer consumer : consumers) if (consumer.input.equals(input) && consumer.round != null) invalidRounds.add(consumer.round);
		for (Round round : invalidRounds) retire(round, unavailable(input.viewKey()));
	}
	private void retire(Round round, Observation invalid) {
		for (Consumer consumer : consumers) if (consumer.round == round) {
			consumer.round = null; consumer.cursor = 0; consumer.invalidObservation = invalid;
		}
		round.references = 0;
		try { round.handle.close(); }
		finally {
			round.pages.forEach(RetainedMemoryLedger.Ticket::close); round.memory.close(); rounds.remove(round.key, round); retainedRounds--;
			round.pages.clear(); round.slots.clear(); round.paid.clear(); round.last = null;
		}
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
