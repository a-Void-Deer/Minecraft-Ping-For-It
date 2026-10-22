package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentEntry;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;

/**
 * Segmented detached observations. No resident survives a read callback. Budget
 * formulas depend on A1's public certificate, whose repair/integration is pending;
 * this staged implementation is NOT a claim that native allocation is certified.
 */
public final class InventoryObservationSnapshot implements InventorySourceAccess.InventorySnapshot {
	private record Observation(InventoryDetachedItem item, long amount) {}
	private record Segment(InventorySnapshotLayout.Segment topology, Observation[] slots) {}
	private record Member(InventorySnapshotLayout.Member topology, List<Segment> segments) {}
	private InventorySnapshotLayout layout;
	private BlockPos originalTarget;
	private HolderLookup.Provider registries;
	private List<Member> members;
	private InventoryItemCodec.Catalog catalog;
	private InventoryReprojectionSummary frozenReprojection;
	private int displayBytes;
	private final int slots;
	private long accounted;
	private boolean closed;

	public static int visibleSlots(InventorySnapshotLayout layout) {
		int count = 0;
		for (var member : layout.members()) for (var segment : member.segments()) count = Math.addExact(count, segment.visibleSlots().size());
		return count;
	}
	/** E + fixed validation state + peak validation workspace + item W + retained R + lazy K. */
	public static long memoryUpperBound(InventorySnapshotLayout layout, int displayBytes) {
		return memoryUpperBound(layout, displayBytes, false, false, layout.members().size());
	}
	public static long memoryUpperBound(InventorySnapshotLayout layout, int displayBytes, boolean provider, boolean vault, long locks) {
		long validation = InventoryValidationBudget.capture(layout, provider, vault, locks).workspaceBytes();
		return add(envelope(layout), add(validation, add(InventoryItemBudget.workspaceUpperBound(displayBytes),
			add(retentionLimit(layout, displayBytes), catalogReserve()))));
	}
	/**
	 * Fixed capture-owned validation passes beside the per-slot authority pass:
	 * plan discovery and capture entry reacquire the view, the capture prechecks
	 * members before and after, the boundary and final authority calls each
	 * recheck, and one pass of slack covers a provider revalidation. These are
	 * explicit call counts, not a measured allowance.
	 */
	static final int FIXED_VALIDATION_PASSES = 8;
	/**
	 * Capture-owned finite validation structure: one reusable classification
	 * scratch, the frozen authority closure with its captured references, four
	 * witness arrays per member and at most one frozen partner position per
	 * member. Symbolic in members and built from the existing layout weights.
	 */
	public static long validationStructureBytes(InventorySnapshotLayout layout) {
		return InventoryValidationBudget.fixed(layout).allocationBytes();
	}
	/** Ordinary owned validation work; native plans expose their concrete provider profile. */
	public static long validationWorkUpperBound(InventorySnapshotLayout layout) {
		return InventoryValidationBudget.capture(layout, false, false, 0).workUnits();
	}
	/** Nonempty lock matching multiplicity at A1's single-call root certificate. */
	public static long lockValidationWorkUpperBound(InventorySnapshotLayout layout, long lockableMembers) {
		if (lockableMembers <= 0) return 0L;
		long passes = add(Math.max(1, visibleSlots(layout)), FIXED_VALIDATION_PASSES);
		return multiply(passes, multiply(lockableMembers, InventoryItemBudget.freezeWorkUpperBound(InventoryItemCodec.minimumDisplayBytes())));
	}
	private static long retentionLimit(InventorySnapshotLayout layout, int displayBytes) {
		return Math.min(multiply(visibleSlots(layout), InventoryItemBudget.retainedUpperBound(displayBytes)), InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES);
	}
	private static long catalogReserve() {
		// Catalog's byte cap bounds canonical/display UTF-8 payload. UTF-16 display
		// ownership may cost twice UTF-8, plus a canonical clone: three payload caps.
		// Per entry: key, digest String+array, map nodes/table slots, conflict marker,
		// display record: 16 modeled objects (40 bytes), 32 references (8 bytes).
		return add(multiply(3, InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES),
			add(multiply(InventoryItemCodec.DEFAULT_MAX_ENTRIES, 16L * 40 + 32L * 8), 16L * 40));
	}
	private static long envelope(InventorySnapshotLayout layout) {
		// Retained layout strings/records plus capture and final reference arrays,
		// empty observation records, member/segment wrappers and fixed catalogue.
		long bytes = add(InventoryReprojectionSummary.METADATA_BYTES, add(24L * 40, add(text(layout.layoutId()), text(layout.layoutData()))));
		for (var member : layout.members()) {
			bytes = add(bytes, add(12L * 40 + 16L * 8 + 16L * member.segments().size(), add(text(member.blockId()), add(text(member.blockEntityId()), text(member.role())))));
			for (var segment : member.segments()) bytes = add(bytes, add(8L * 40 + 4L * 8,
				add(text(segment.fieldPath()), add(text(segment.schemaId()), multiply(segment.visibleSlots().size(), 2L * 40 + 4L * 8 + 4)))));
		}
		return bytes;
	}

	/** Caller holds the plan's reservation; every member is gated before invocation. */
	static InventoryObservationSnapshot capture(InventorySnapshotLayout layout, BlockPos target,
		HolderLookup.Provider registries, int displayBytes, long admittedBytes,
		IntFunction<ResidentEntry> read, BooleanSupplier valid) {
		var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.capture(layout, false, false, layout.members().size()));
		allowance.debit(InventoryValidationBudget.bookkeeping());
		allowance.debit(InventoryValidationBudget.fixed(layout));
		return capture(layout, target, registries, displayBytes, admittedBytes, read, valid, allowance);
	}
	static InventoryObservationSnapshot capture(InventorySnapshotLayout layout, BlockPos target,
		HolderLookup.Provider registries, int displayBytes, long admittedBytes,
		IntFunction<ResidentEntry> read, BooleanSupplier valid, InventoryValidationBudget.Allowance validation) {
		Objects.requireNonNull(read); Objects.requireNonNull(valid);
		boolean containsTarget = false;
		for (int index = 0; index < layout.members().size(); index++) if (layout.members().get(index).position().equals(target)) containsTarget = true;
		if (!containsTarget) throw new IllegalArgumentException("target outside capture layout");
		long upper = add(envelope(layout), add(validationStructureBytes(layout), add(InventoryItemBudget.workspaceUpperBound(displayBytes),
			add(retentionLimit(layout, displayBytes), catalogReserve()))));
		if (upper > admittedBytes) throw new LimitException("observation reservation is too small");
		int count = visibleSlots(layout);
		long visited = count + layout.members().size();
		for (var member : layout.members()) visited = add(visited, member.segments().size());
		long retainedLimit = retentionLimit(layout, displayBytes);
		InventoryItemBudget budget = InventoryItemBudget.capture(displayBytes, Math.max(1, count), Math.max(1, retainedLimit),
			multiply(Math.max(1, count), InventoryItemBudget.freezeWorkUpperBound(displayBytes)));
		Envelope structure = new Envelope(envelope(layout));
		// Fixed validation state was consumed by the dedicated allowance before
		// scratch/witness construction; it is not spare snapshot envelope capacity.
		structure.charge(40L + 8L * layout.members().size());
		List<Member> members = new ArrayList<>(layout.members().size());
		// The per-root U grant includes its explicit 32768 + 16*displayBytes
		// caller allowance beyond the two phase limits. The bounded UTF length
		// scan and constant-size summary arithmetic consume that allowance, not
		// another resident/native traversal. Envelope pays builder + cached record.
		structure.charge(InventoryReprojectionSummary.METADATA_BYTES);
		var summary = new InventoryReprojectionSummary.Builder().entries(visited);
		long retained = 0; int index = 0;
		for (var member : layout.members()) {
			structure.charge(40L + 8L * member.segments().size());
			List<Segment> segments = new ArrayList<>(member.segments().size());
			for (var topology : member.segments()) {
				structure.charge(40L + 8L * topology.visibleSlots().size());
				Observation[] values = new Observation[topology.visibleSlots().size()];
				for (int local = 0; local < values.length; local++) {
					if (!valid.getAsBoolean()) throw new IllegalStateException("capture authority or topology revoked");
					structure.charge(40L + 2L * 8);
					ResidentEntry resident = Objects.requireNonNull(read.apply(index++), "resident observation");
					InventoryDetachedItem item = resident.amount() == 0 ? null
						: InventoryItemCodec.freeze(resident.item(), registries, displayBytes, budget);
					if (item != null) retained = add(retained, item.retainedBytes());
					if (item != null) summary.input(item.display().displayJson());
					if (retained > retainedLimit) throw new LimitException("capture retention exhausted");
					values[local] = new Observation(item, resident.amount());
				}
				structure.charge(40L + 2L * 8); segments.add(new Segment(topology, values));
			}
			structure.charge(3L * 40 + 16L * segments.size());
			members.add(new Member(member, List.copyOf(segments)));
		}
		if (!valid.getAsBoolean()) throw new IllegalStateException("capture final authority or topology revoked");
		structure.charge(3L * 40 + 16L * members.size());
		return new InventoryObservationSnapshot(layout, target, List.copyOf(members), registries,
			displayBytes, count, add(envelope(layout), add(retained, catalogReserve())), summary.build());
	}
	private InventoryObservationSnapshot(InventorySnapshotLayout layout, BlockPos target, List<Member> members,
		HolderLookup.Provider registries, int displayBytes, int slots, long accounted, InventoryReprojectionSummary summary) {
		this.layout = layout; this.originalTarget = target.immutable(); this.members = members;
		this.registries = Objects.requireNonNull(registries, "registries");
		this.catalog = new InventoryItemCodec.Catalog(registries, displayBytes); this.displayBytes = displayBytes;
		this.slots = slots; this.accounted = accounted;
		this.frozenReprojection = summary;
	}
	public InventorySnapshotLayout layout() { requireOpen(); return layout; }
	public BlockPos originalTarget() { requireOpen(); return originalTarget; }
	@Override public int slots() { requireOpen(); return slots; }
	@Override public long retainedBytes() { return closed ? 0 : accounted; }
	@Override public int displayBytes() { requireOpen(); return displayBytes; }
	@Override public InventoryReprojectionSummary reprojectionSummary(int displayBytes) {
		requireOpen(); InventoryMinecraftSources.requireDisplayBytes(displayBytes);
		return displayBytes >= this.displayBytes ? InventoryReprojectionSummary.EMPTY
			: frozenReprojection.plus(catalog.reprojectionSummary(displayBytes));
	}
	@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
	@Override public void tightenDisplay(int displayBytes) {
		requireOpen(); InventoryMinecraftSources.requireDisplayBytes(displayBytes);
		if (displayBytes >= this.displayBytes) return;
		int ceiling = displayBytes;
		long refund = 0L;
		var summary = new InventoryReprojectionSummary.Builder().entries(frozenReprojection.entries());
		// Unread frozen displays are reprojected inside this admitted tightening
		// call. The Runtime tightening grant already reserves the OLD-input parser
		// workspace and the old/new replacement overlap, so a later lazy intern
		// never parses an input larger than the reported ceiling after the grant.
		for (var member : members) for (var segment : member.segments()) {
			Observation[] values = segment.slots();
			for (int index = 0; index < values.length; index++) {
				Observation observation = values[index];
				InventoryDetachedItem item = observation.item();
				if (item == null) continue;
				var tightened = InventoryItemCodec.reproject(item.display(), registries, ceiling);
				summary.input(tightened.displayJson());
				if (tightened.equals(item.display())) continue;
				InventoryDetachedItem replaced = item.withDisplay(tightened);
				refund += item.retainedBytes() - replaced.retainedBytes();
				values[index] = new Observation(replaced, observation.amount());
			}
		}
		catalog.tightenDisplay(ceiling); this.displayBytes = ceiling;
		frozenReprojection = summary.build();
		if (refund > accounted) throw new LimitException("capture retention accounting");
		accounted -= refund;
	}
	@Override public InventoryDomainCodec.Item read(int index) {
		return readOwned(index, null); // Direct legacy callers own their admission.
	}
	@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission admission) {
		return readOwned(index, Objects.requireNonNull(admission));
	}
	private InventoryDomainCodec.Item readOwned(int index, InventoryCatalogAdmission admission) {
		requireOpen(); Objects.checkIndex(index, slots);
		for (var member : members) for (var segment : member.segments()) {
			if (index >= segment.slots().length) { index -= segment.slots().length; continue; }
			Observation value = segment.slots()[index]; if (value.item() == null) return null;
			var encoded = admission == null ? catalog.intern(value.item()) : catalog.intern(value.item(), admission);
			var display = encoded.display();
			return new InventoryDomainCodec.Item(encoded.key(), value.amount(), display.label(), display.displayJson(), display.aggregateRequired());
		}
		throw new IllegalStateException("capture segment index");
	}
	@Override public void close() { if (!closed) { closed = true; layout = null; originalTarget = null; registries = null; members = null; catalog = null; frozenReprojection = null; } }
	private void requireOpen() { if (closed) throw new IllegalStateException("closed observation snapshot"); }
	private static final class Envelope {
		private long remaining;
		Envelope(long remaining) { this.remaining = remaining; }
		void charge(long bytes) { if (bytes < 0 || bytes > remaining) throw new LimitException("capture structure exhausted"); remaining -= bytes; }
	}
	private static long text(String value) { return add(2L * 40, multiply(2, value.length())); }
	private static long add(long a, long b) { try { return Math.addExact(a, b); } catch (ArithmeticException failure) { throw new LimitException("capture accounting overflow"); } }
	private static long multiply(long a, long b) { try { return Math.multiplyExact(a, b); } catch (ArithmeticException failure) { throw new LimitException("capture accounting overflow"); } }
	public static final class LimitException extends IllegalStateException { public LimitException(String message) { super(message); } }
}
