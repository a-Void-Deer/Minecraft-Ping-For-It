package nx.pingwheel.common.presentation.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

import nx.pingwheel.common.presentation.PresentationAdapter;
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
		Optional<InventorySnapshot> capture();
	}

	/**
	 * One complete detached inventory snapshot. Only the capture freezes world
	 * state; reading it is pure retained data, so decoding it across steps or
	 * periods keeps the capture's own evidence and never re-reads the source.
	 */
	public interface InventorySnapshot extends AutoCloseable {
		int slots();
		InventoryDomainCodec.Item read(int index);
		long retainedBytes();
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
		/**
		 * Resolves the route once. A successful snapshot keeps its measured
		 * reservation charged until close; a retryable memory defer performs no
		 * capture and leaves the route unresolved.
		 */
		Preparation prepareSnapshot(RetainedMemoryLedger memory);
	}

	public interface Source extends AutoCloseable {
		SourceKey key();
		boolean valid();
		boolean stableCursor();
		int slots();
		InventoryDomainCodec.Item read(int slot);
		default OptionalLong version() { return OptionalLong.empty(); }
		/** Empty keeps the source on its existing live route. */
		default Optional<SnapshotPlan> snapshotPlan() { return Optional.empty(); }
		/** Cursorless sources must count every visited view, including empty views. */
		default Page enumerate(int limit) { throw new UnsupportedOperationException("no enumeration"); }
		@Override default void close() {}
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
					return matchesSelection(source.read(witness), selection);
				}
				used = limit;
				Page page = source.enumerate(limit);
				if (page.slots().size() > limit) return false;
				used = page.slots().size();
				return page.slots().stream().anyMatch(item -> matchesSelection(item, selection));
			} catch (RuntimeException | LinkageError unavailable) { return false; }
			finally { ticket.commit(Map.of(PHYSICAL, (long) used, PROBES, 1L, PROVIDER_WORK, (used + 1L) * PROVIDER_CALL_WORK)); }
		}
	}
	private static boolean matchesSelection(InventoryDomainCodec.Item item, InventorySelection selection) {
		return item != null && item.count() > 0 && (selection.aggregate() ? item.key().itemId().equals(selection.itemId()) : item.key().equals(selection.exact()));
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
		var block = input.target();
		return target != null && block.dimensionId().equals(target.dimension()) && block.blockRegistryId().equals(target.registryId())
			&& block.x() == target.x() && block.y() == target.y() && block.z() == target.z();
	}

	private static final class CaptureHandle implements InventoryHandle {
		private final InventorySourceInput input;
		private final Descriptor descriptor;
		private final ReadScope scope;
		private final Source source;
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
		@Override public java.util.Set<String> demand() { return scope.demand(); }

		/**
		 * One lazy, memory-bounded detached capture. The plan's upper bound is
		 * reserved before any capture, and the measured retained cost settles
		 * that reservation only when it is a valid subset of the bound.
		 */
		@Override public Preparation prepareSnapshot(RetainedMemoryLedger memory) {
			Objects.requireNonNull(memory, "memory");
			if (closed) return Preparation.UNAVAILABLE;
			if (preparation != null) return preparation;
			InventorySnapshot captured = null;
			RetainedMemoryLedger.Ticket reserved = null;
			try {
				if (!source.valid()) return settle(Preparation.INVALID);
				SnapshotPlan plan = source.snapshotPlan().orElse(null);
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
				if (!source.valid()) return settle(Preparation.INVALID);
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
			if (closed || terminal) return new StepOutcome.Deferred();
			if (preparation != null && preparation != Preparation.READY) {
				terminal = true;
				return terminalFailure(preparation);
			}
			if (snapshot != null) return snapshotStep(ledger);
			if (planDeferred) return new StepOutcome.Deferred();
			return liveStep(ledger);
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
		private StepOutcome snapshotStep(CostLedger ledger) {
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
							var item = snapshot.read(cursor);
							page.put(Integer.toString(cursor++), InventoryDomainCodec.encode(item));
						}
						boolean complete = cursor == slots;
						terminal = complete || cursor == MAX_SLOTS;
						return snapshotCaptured(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, page), CaptureResult.Availability.READABLE,
							complete ? CaptureResult.Completeness.COMPLETE : terminal ? CaptureResult.Completeness.INCOMPLETE : CaptureResult.Completeness.CONTINUE);
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

		private StepOutcome liveStep(CostLedger ledger) {
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
						Page enumerated = source.enumerate(allowance);
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
							var item = source.read(cursor);
							page.put(Integer.toString(cursor++), InventoryDomainCodec.encode(item));
						}
						complete = cursor == expected;
						terminal = complete || cursor == MAX_SLOTS;
					}
					versionStable &= firstVersion.isPresent() && source.version().equals(firstVersion);
					return captured(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, page), CaptureResult.Availability.READABLE,
						complete ? CaptureResult.Completeness.COMPLETE : terminal ? CaptureResult.Completeness.INCOMPLETE : CaptureResult.Completeness.CONTINUE);
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
