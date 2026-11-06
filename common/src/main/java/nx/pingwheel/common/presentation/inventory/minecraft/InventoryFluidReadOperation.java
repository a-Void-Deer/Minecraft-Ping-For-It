package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Objects;

import net.minecraft.core.HolderLookup;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * One common-owned, admitted synchronous coordinated fluid read. No public
 * constructor or validity flag exists. All callbacks are bound to the creating
 * source/input proof, guarded wrapper, audited provider, slot, thread, catalogue
 * and the one caller allowance; a failed, reordered or completed invocation
 * cannot be reused. The operation is dropped at the end of the call and never
 * crosses a call, tick or publication cache.
 */
@NotNullByDefault
public final class InventoryFluidReadOperation implements AutoCloseable {
	private static final int ENTER = 0, BEFORE = 1, FREEZING = 2, DETACHED = 3, AFTER = 4, CLOSED = 5;
	/**
	 * Operation state/refusal bookkeeping only. The two common full validations
	 * keep their own complete profiles; the provider's resident profile also keeps
	 * the full pre-getter current fence. No retained bytes are owned here.
	 */
	private static final IPlatformFluidService.OperationProfile PROFILE = new IPlatformFluidService.OperationProfile(
		new InventoryValidationBudget.Cost(2048, 2048, 128), 0, 0, 0);

	/** Package-private source seam. Every accessor is pure identity/bookkeeping. */
	interface Port {
		Object proof();
		IPlatformFluidService.Access wrapper();
		InventoryValidationBudget.Allowance allowance();
		boolean active(InventoryFluidReadOperation operation);
		boolean validate();
	}

	private final int slot;
	private final int displayBytes;
	private int state = ENTER;
	private boolean bound, busy, completed;
	private @Nullable Port port;
	private @Nullable Object proof;
	private IPlatformFluidService.@Nullable Access wrapper;
	private IPlatformFluidService.@Nullable CoordinatedAccess provider;
	private @Nullable Thread thread;
	private @Nullable InventoryFluidCodec.Catalog catalog;
	private HolderLookup.@Nullable Provider registries;
	private @Nullable InventoryCatalogAdmission admission;
	private InventoryValidationBudget.@Nullable Allowance allowance;
	private @Nullable InventoryFluidValue result;

	InventoryFluidReadOperation(Port port, Object proof, IPlatformFluidService.Access wrapper,
		IPlatformFluidService.CoordinatedAccess provider, int slot, Thread thread, InventoryFluidCodec.Catalog catalog,
		HolderLookup.Provider registries, InventoryCatalogAdmission admission, int displayBytes,
		InventoryValidationBudget.Allowance allowance) {
		this.port = Objects.requireNonNull(port); this.proof = Objects.requireNonNull(proof);
		this.wrapper = Objects.requireNonNull(wrapper); this.provider = Objects.requireNonNull(provider);
		this.slot = slot; this.thread = Objects.requireNonNull(thread); this.catalog = Objects.requireNonNull(catalog);
		this.registries = Objects.requireNonNull(registries); this.admission = Objects.requireNonNull(admission);
		this.displayBytes = displayBytes; this.allowance = Objects.requireNonNull(allowance);
	}

	public static IPlatformFluidService.OperationProfile profile() { return PROFILE; }

	/** Must be the first operation method; it binds this exact provider and address before any local metadata. */
	public void enter(IPlatformFluidService.CoordinatedAccess provider, int slot) {
		if (busy || state != ENTER || bound || provider != this.provider || slot != this.slot
			|| thread != Thread.currentThread() || !owns()) throw invalid();
		bound = true;
	}

	/** First complete common validation, immediately before the callback-free native getter handoff. */
	public void beforeResident(IPlatformFluidService.CoordinatedAccess provider, int slot) {
		require(provider, slot, ENTER);
		if (!bound) throw invalid();
		busy = true;
		try {
			var current = port;
			if (current == null) throw invalid();
			if (!current.validate()) throw invalid();
			if (state != ENTER) throw invalid();
			state = BEFORE;
		} catch (RuntimeException | Error failure) {
			close(); throw failure;
		} finally { busy = false; }
	}

	/** Callback-free ownership fence after the provider's admitted pre-getter current checks. */
	public void requireGetter(IPlatformFluidService.CoordinatedAccess provider, int slot) {
		require(provider, slot, BEFORE);
	}

	/** Immediate getter-time handoff. Freeze/intern is the only transition into DETACHED. */
	public void detach(IPlatformFluidService.CoordinatedAccess provider, int slot, IPlatformFluidService.Entry entry) {
		require(provider, slot, BEFORE);
		busy = true;
		try {
			state = FREEZING;
			var currentCatalog = catalog; var currentRegistries = registries; var currentAdmission = admission;
			if (currentCatalog == null || currentRegistries == null || currentAdmission == null) throw invalid();
			@Nullable var value = InventoryFluidFreezer.freeze(Objects.requireNonNull(entry), currentRegistries,
				displayBytes, currentCatalog, currentAdmission);
			// A callback that closed or re-entered leaves a poisoned state; never publish the provisional value.
			if (state != FREEZING) throw invalid();
			result = value;
			state = DETACHED;
		} catch (RuntimeException | Error failure) {
			close(); throw failure;
		} finally { busy = false; }
	}

	/** Second complete common validation. The provider must return immediately after this call. */
	public void afterResident(IPlatformFluidService.CoordinatedAccess provider, int slot) {
		require(provider, slot, DETACHED);
		busy = true;
		try {
			var current = port;
			if (current == null) throw invalid();
			if (!current.validate()) throw invalid();
			if (state != DETACHED) throw invalid();
			state = AFTER;
		} catch (RuntimeException | Error failure) {
			close(); throw failure;
		} finally { busy = false; }
	}

	/** Single accepted completion. Null is a successfully observed empty value; it is not an unfinished read. */
	@Nullable InventoryFluidValue completed(Port owner) {
		if (busy || state != AFTER || completed || owner != port || !owns()) throw invalid();
		completed = true;
		return result;
	}

	private void require(IPlatformFluidService.CoordinatedAccess candidate, int address, int expectedState) {
		if (busy || state != expectedState || candidate != provider || address != slot || thread != Thread.currentThread()
			|| !owns()) throw invalid();
	}

	private boolean owns() {
		var current = port;
		return current != null && proof != null && wrapper != null && provider != null && allowance != null
			&& current.proof() == proof && current.wrapper() == wrapper && current.allowance() == allowance && current.active(this);
	}

	private InventorySourceUnavailableException invalid() {
		close();
		return new InventorySourceUnavailableException("fluid coordinated read invalid");
	}

	/** Idempotent release. No provider, authority, admission or getter callback runs here. */
	@Override public void close() {
		state = CLOSED; bound = false; busy = false; completed = false;
		port = null; proof = null; wrapper = null; provider = null; thread = null; catalog = null;
		registries = null; admission = null; allowance = null; result = null;
	}
}
