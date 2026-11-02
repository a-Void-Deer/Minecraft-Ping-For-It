package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

/** One synchronous fluid operation tree backed by the caller's EXISTING Runtime ledgers. */
@NotNullByDefault
public final class InventoryFluidAdmission implements IPlatformFluidService.Admission, AutoCloseable {
	private static final int MAX_GRANTS = 65536;
	private static final InventoryValidationBudget.Cost BOOKKEEPING = new InventoryValidationBudget.Cost(2048, 2048, 128);
	private final CostLedger ledger;
	private final CostLedger.Counter counter;
	private final RetainedMemoryLedger memory;
	private final InventoryValidationBudget.Cost memberCost, authorityCost;
	private final InventoryValidationBudget.Allowance allowance;
	private final InventoryValidationBudget.FluidScope scope;
	// Constant-sized nodes: no hidden backing-array growth at a later admission boundary.
	private final List<Grant> grants = new LinkedList<>();
	private final List<RetainedMemoryLedger.Ticket> retained = new LinkedList<>();
	private long retainedBytes;
	private long activeScratch, scratchPeak, workspaceHeld;
	private boolean closed, transferred;
	public InventoryFluidAdmission(CostLedger ledger, CostLedger.Counter counter, RetainedMemoryLedger memory,
		InventoryValidationBudget.Cost memberCost, InventoryValidationBudget.Cost authorityCost) {
		this(ledger, counter, memory, memberCost, authorityCost, new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE));
	}
	public InventoryFluidAdmission(CostLedger ledger, CostLedger.Counter counter, RetainedMemoryLedger memory,
		InventoryValidationBudget.Cost memberCost, InventoryValidationBudget.Cost authorityCost, InventoryValidationBudget.Allowance allowance) {
		this.ledger = Objects.requireNonNull(ledger); this.counter = Objects.requireNonNull(counter);
		this.memory = Objects.requireNonNull(memory); this.memberCost = Objects.requireNonNull(memberCost);
		this.authorityCost = Objects.requireNonNull(authorityCost);
		this.allowance = Objects.requireNonNull(allowance); scope = allowance.beginFluid();
	}
	@Override public IPlatformFluidService.Operation enter(IPlatformFluidService.OperationProfile profile) {
		Objects.requireNonNull(profile); requireOpen();
		var cost = profile.cost(memberCost, authorityCost);
		long nextScratch = Math.addExact(activeScratch, cost.workspaceBytes());
		reserve(cost, profile.retainedBytes(), nextScratch);
		activeScratch = nextScratch;
		return new Operation(cost.workspaceBytes());
	}
	private void requireOpen() {
		if (closed) throw new IllegalStateException("fluid admission closed"); allowance.requireFluidScope(scope);
	}
	private void reserve(InventoryValidationBudget.Cost cost, long bytes, long scratch) {
		requireOpen();
		if (grants.size() == MAX_GRANTS || transferred) throw new IllegalStateException("fluid admission ownership bound");
		// Sequential callbacks reuse scratch, but every admitted node remains live until settlement.
		// Allocation/work are cumulative; reserving the whole lock scratch again for every gate would
		// turn a bounded sequential operation into an artificial sum of simultaneous workspaces.
		long nextPeak = Math.max(scratchPeak, scratch);
		long nextWorkspace = Math.addExact(nextPeak, Math.multiplyExact(grants.size() + 1L, BOOKKEEPING.workspaceBytes()));
		var total = new InventoryValidationBudget.Cost(Math.addExact(cost.allocationBytes(), BOOKKEEPING.allocationBytes()),
			nextWorkspace, Math.addExact(cost.workUnits(), BOOKKEEPING.workUnits()));
		// Retained tickets and their nodes remain live with the metadata, unlike operation work tickets.
		long chargedBytes = bytes == 0 ? 0 : Math.addExact(bytes, retained.isEmpty() ? 512 : 256);
		long nextRetained = Math.addExact(retainedBytes, chargedBytes);
		// Check arithmetic BEFORE acquiring tickets or enlarging the cumulative allowance.
		allowance.checkAdditional(total);
		var workspace = memory.tryReserve(nextWorkspace - workspaceHeld).orElseThrow(InventoryFluidAdmission::deferred);
		try {
			var ownership = memory.tryReserve(chargedBytes).orElseThrow(InventoryFluidAdmission::deferred);
			try {
				var work = ledger.tryReserve(Map.of(counter, total.workUnits())).orElseThrow(InventoryFluidAdmission::deferred);
				int index = grants.size();
				int retainedCount = retained.size();
				try {
					grants.add(new Grant(work, workspace, total.workUnits(), chargedBytes));
					if (chargedBytes == 0) ownership.close(); else retained.add(ownership);
					allowance.admitAdditional(total); allowance.debit(BOOKKEEPING); retainedBytes = nextRetained;
					scratchPeak = nextPeak; workspaceHeld = nextWorkspace;
				} catch (RuntimeException | Error failure) {
					if (grants.size() > index) grants.remove(index);
					if (retained.size() > retainedCount) retained.remove(retainedCount);
					work.close(); throw failure;
				}
			} catch (RuntimeException | Error failure) { ownership.close(); throw failure; }
		} catch (RuntimeException | Error failure) { workspace.close(); throw failure; }
	}
	/** Transfer the already-admitted metadata tickets into detached source/evidence ownership exactly once. */
	public Ownership retain() {
		requireOpen(); if (transferred) throw new IllegalStateException("fluid metadata already transferred");
		if (retained.isEmpty()) reserve(InventoryValidationBudget.NONE, 128, activeScratch);
		var result = new Ownership(retained, retainedBytes);
		var tickets = retained.iterator();
		for (var grant : grants) if (grant.retainedBytes() != 0) tickets.next().commit(grant.retainedBytes());
		transferred = true; return result;
	}
	@Override public void close() {
		if (closed) return; closed = true;
		long remaining = scope.attemptedWorkUnits();
		boolean ordered = allowance.settleFluid(scope);
		try {
			for (Grant grant : grants) {
				long used = Math.min(remaining, grant.workUnits()); remaining -= used;
				try { grant.work().commit(Map.of(counter, used)); }
				finally { try { grant.work().close(); } finally { grant.workspace().close(); } }
			}
			if (remaining != 0) throw new IllegalStateException("fluid work exceeds genuine grants");
			if (!ordered) throw new IllegalStateException("fluid admission settlement order violated");
		} finally {
			// Also drain later tickets if settling an earlier one unexpectedly fails.
			for (Grant grant : grants) try { grant.work().close(); } finally { grant.workspace().close(); }
			if (!transferred) { retained.forEach(RetainedMemoryLedger.Ticket::close); retained.clear(); }
			grants.clear();
		}
	}
	private static Deferred deferred() {
		return new Deferred();
	}
	@NotNullByDefault
	public static final class Deferred extends InventoryItemBudget.CaptureLimitException {
		private static final long serialVersionUID = 1L;
		private Deferred() { super("fluid operation admission deferred"); }
	}
	@NotNullByDefault
	private record Grant(CostLedger.Ticket work, RetainedMemoryLedger.Ticket workspace, long workUnits, long retainedBytes) {}
	@NotNullByDefault
	private final class Operation implements IPlatformFluidService.Operation {
		private boolean done;
		private final long scratch;
		private long additionalScratch;
		private Operation(long scratch) { this.scratch = scratch; }
		private void requireActive() { requireOpen(); if (done) throw new IllegalStateException("fluid operation closed"); }
		@Override public InventoryValidationBudget.Allowance allowance() { requireActive(); return allowance; }
		@Override public void requireAdditional(InventoryValidationBudget.Cost cost) {
			requireActive(); Objects.requireNonNull(cost);
			long nextAdditional = Math.max(additionalScratch, cost.workspaceBytes());
			long nextScratch = Math.addExact(activeScratch, nextAdditional - additionalScratch);
			reserve(cost, 0, nextScratch); activeScratch = nextScratch; additionalScratch = nextAdditional;
		}
		@Override public void close() {
			if (!done) { done = true; if (!closed) activeScratch -= scratch + additionalScratch; }
		}
	}
	@NotNullByDefault
	public static final class Ownership implements AutoCloseable {
		private final @UnmodifiableView List<RetainedMemoryLedger.Ticket> tickets;
		private final long bytes;
		private boolean closed;
		private Ownership(List<RetainedMemoryLedger.Ticket> tickets, long bytes) {
			this.tickets = java.util.Collections.unmodifiableList(tickets); this.bytes = bytes;
		}
		public long bytes() { return bytes; }
		@Override public void close() { if (!closed) { closed = true; tickets.forEach(RetainedMemoryLedger.Ticket::close); } }
	}
}
