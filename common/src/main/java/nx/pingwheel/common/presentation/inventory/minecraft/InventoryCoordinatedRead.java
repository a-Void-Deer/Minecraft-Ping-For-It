package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Objects;
import net.minecraft.core.HolderLookup;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * One common-owned, admitted synchronous read. No public constructor or validity
 * flag exists. All callbacks are bound to the creating port, proof, provider,
 * slot, thread and catalogue; a failed or completed invocation cannot be reused.
 */
@NotNullByDefault
public final class InventoryCoordinatedRead implements AutoCloseable {
	private static final int ENTRY = 0, LOCAL = 1, CHECKING = 2, BEFORE = 3, FREEZING = 4, DETACHED = 5, AFTER = 6, CLOSED = 7;
	private final int slot;
	private int state = ENTRY;
	private @Nullable InventoryCertifiedRead.NativePort port;
	private IPlatformInventoryService.@Nullable CoordinatedSharedAccess provider;
	private InventorySharedRead.@Nullable Proof proof;
	private InventoryItemCodec.@Nullable Catalog catalog;
	private HolderLookup.@Nullable Provider registries;
	private @Nullable InventoryCatalogAdmission admission;
	private @Nullable InventoryItemBudget budget;
	private @Nullable Thread thread;
	private InventoryDomainCodec.@Nullable Item result;

	InventoryCoordinatedRead(InventoryCertifiedRead.NativePort port, IPlatformInventoryService.CoordinatedSharedAccess provider,
		int slot, InventorySharedRead.Proof proof, InventoryItemCodec.Catalog catalog, HolderLookup.Provider registries,
		InventoryCatalogAdmission admission) {
		this.port = port; this.provider = provider; this.slot = slot; this.proof = proof;
		this.catalog = catalog; this.registries = registries; this.admission = admission;
		budget = InventoryItemBudget.root(catalog.displayBytes()); thread = Thread.currentThread();
	}
	/** Must precede local metadata acquisition, without another provider callback after the common entry check. */
	public void enter(IPlatformInventoryService.CoordinatedSharedAccess provider, int slot) {
		require(provider, slot, ENTRY); state = LOCAL;
	}
	/** Full common/current-provider check after the last local metadata/audit and all-member held gate. */
	public void beforeResident(IPlatformInventoryService.CoordinatedSharedAccess provider, int slot) {
		require(provider, slot, LOCAL); state = CHECKING;
		Objects.requireNonNull(port).checkCoordinated(this, Objects.requireNonNull(proof));
		// A callback attempting another operation method poisons this invocation even if it catches the refusal.
		require(provider, slot, CHECKING); state = BEFORE;
	}
	/** Immediate getter-time handoff. Only common freeze + successful catalogue interning can mark DETACHED. */
	public void detach(IPlatformInventoryService.CoordinatedSharedAccess provider, int slot, IPlatformInventoryService.ResidentEntry resident) {
		require(provider, slot, BEFORE); state = FREEZING;
		var currentCatalog = Objects.requireNonNull(catalog);
		@Nullable var detached = InventoryMinecraftSources.freezeItem(currentCatalog, Objects.requireNonNull(registries),
			currentCatalog.displayBytes(), resident, Objects.requireNonNull(budget), Objects.requireNonNull(admission));
		require(provider, slot, FREEZING); result = detached; state = DETACHED; // null is a successfully observed empty, not a missing handoff
	}
	/** Final full check; the audited provider must return immediately, with no subsequent callbacks. */
	public void afterResident(IPlatformInventoryService.CoordinatedSharedAccess provider, int slot) {
		require(provider, slot, DETACHED); state = CHECKING;
		Objects.requireNonNull(port).checkCoordinated(this, Objects.requireNonNull(proof));
		require(provider, slot, CHECKING); state = AFTER;
	}
	InventoryDomainCodec.@Nullable Item completed(InventoryCertifiedRead.NativePort owner) {
		if (owner != port) throw invalid();
		require(Objects.requireNonNull(provider), slot, AFTER);
		return result;
	}
	private void require(IPlatformInventoryService.CoordinatedSharedAccess candidate, int address, int expectedState) {
		if (state != expectedState || provider != candidate || slot != address || thread != Thread.currentThread()
			|| port == null || !port.ownsCoordinated(this)) throw invalid();
	}
	private InventorySharedRead.ReadFailure invalid() {
		close(); return new InventorySharedRead.ReadFailure(InventorySharedRead.Check.UNAVAILABLE);
	}
	@Override public void close() {
		state = CLOSED; port = null; provider = null; proof = null; catalog = null; registries = null;
		admission = null; budget = null; thread = null; result = null;
	}
}
