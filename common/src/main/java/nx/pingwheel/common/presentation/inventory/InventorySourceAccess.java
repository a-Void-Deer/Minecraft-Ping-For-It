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
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;

/** Production source seam. Admission precedes resolution and every controlled read. */
public final class InventorySourceAccess implements SourceAccess {
	public static final CostLedger.Counter PROBES = new CostLedger.Counter("inventory/probes", CostLedger.Unit.WORK);
	public static final CostLedger.Counter PHYSICAL = new CostLedger.Counter("inventory/physical", CostLedger.Unit.SLOT);
	public static final CostLedger.Counter PROVIDER_WORK = new CostLedger.Counter("inventory/provider_work", CostLedger.Unit.WORK);
	/** One resolution/validation/read may inspect a bounded Vault of 81 members several times. */
	public static final long PROVIDER_CALL_WORK = 1024, MAX_PROVIDER_WORK_PER_TICK = 131072;
	public static final int MAX_STEP = 64;
	public static final int MAX_SLOTS = 4096;

	public interface Source extends AutoCloseable {
		SourceKey key();
		boolean valid();
		boolean stableCursor();
		int slots();
		InventoryDomainCodec.Item read(int slot);
		default OptionalLong version() { return OptionalLong.empty(); }
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
		return new OpenResult.Started(new CaptureHandle(descriptor, scope, source));
	}
	private boolean matches(PresentationAdapter.DetachedTarget target) {
		var block = input.target();
		return target != null && block.dimensionId().equals(target.dimension()) && block.blockRegistryId().equals(target.registryId())
			&& block.x() == target.x() && block.y() == target.y() && block.z() == target.z();
	}

	private static final class CaptureHandle implements Handle {
		private final Descriptor descriptor;
		private final ReadScope scope;
		private final Source source;
		private int cursor, expected = -1;
		private boolean closed, terminal, versionStable = true;
		private OptionalLong firstVersion = OptionalLong.empty();
		CaptureHandle(Descriptor descriptor, ReadScope scope, Source source) {
			this.descriptor = descriptor; this.scope = scope; this.source = source;
		}
		@Override public Descriptor descriptor() { return descriptor; }
		@Override public java.util.Set<String> demand() { return scope.demand(); }
		@Override public StepOutcome step(CostLedger ledger) {
			if (closed || terminal) return new StepOutcome.Deferred();
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
		@Override public void close() {
			if (!closed) {
				closed = true;
				try { source.close(); } catch (RuntimeException | LinkageError unavailable) { /* local provider cleanup must not strand other consumers */ }
			}
		}
	}
}
