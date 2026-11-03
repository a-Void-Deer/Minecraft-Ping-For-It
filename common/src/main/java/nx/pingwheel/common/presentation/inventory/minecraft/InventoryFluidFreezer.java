package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.FluidDescriptions;
import nx.pingwheel.common.platform.FluidRegistries;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidDisplayCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Synchronous borrowed-fluid transaction. Only complete bounded canonical bytes and detached display escape. */
@NotNullByDefault
public final class InventoryFluidFreezer {
	private InventoryFluidFreezer() {}
	private static final InventoryResidentAccess RAW = InventoryResidentAccess.load();
	private static final InventoryItemNativeAccess NATIVE = InventoryItemNativeAccess.load();
	private static final IPlatformFluidDescriptionService DESCRIPTION = FluidDescriptions::describeBase;

	public static @Nullable InventoryFluidValue freeze(IPlatformFluidService.Entry entry, HolderLookup.Provider registries,
		int displayBytes, InventoryFluidCodec.Catalog catalog, InventoryCatalogAdmission admission) {
		return freeze(entry, registries, displayBytes, catalog, admission, DESCRIPTION);
	}
	static @Nullable InventoryFluidValue freeze(IPlatformFluidService.Entry entry, HolderLookup.Provider registries,
		int displayBytes, InventoryFluidCodec.Catalog catalog, InventoryCatalogAdmission admission, IPlatformFluidDescriptionService descriptions) {
		Objects.requireNonNull(entry); Objects.requireNonNull(registries); Objects.requireNonNull(catalog); Objects.requireNonNull(admission);
		if (entry.amount() == 0) return null;
		IPlatformFluidService.Resident resident = Objects.requireNonNull(entry.resident());
		var budget = InventoryItemBudget.root(displayBytes);
		InventoryRegistryShapes.initialize(); InventoryItemFreezer.initialize(); InventoryComponentCertificate.initialize();
		budget.begin(displayBytes);
		try {
			// Reliable registration and amount precede the identity-refusal boundary.
			budget.allocate(0, 0, 1, 1); // root-bound admission callback; it cannot escape the synchronous lookup
			@Nullable var location = FluidRegistries.registeredKey(resident.fluid(), (work, allocation) -> {
				budget.work(work); budget.allocate(allocation, 0, 0, 0);
			});
			if (location == null || resident.fluid() == Fluids.EMPTY)
				throw new InventoryItemBudget.InvalidInputException("unregistered or empty fluid");
			int length = location.getNamespace().length() + location.getPath().length() + 1;
			if (length > 256) throw new InventoryItemBudget.InvalidInputException("fluid registry id bound");
			budget.allocate(1024 + 16L * length, 0, 16, 16); budget.work(8192 + 64L * length);
			String id = location.toString();
			InventoryFluidCodec.Identity identity;
			try {
				byte @Unmodifiable [] canonical;
				if (resident instanceof IPlatformFluidService.Components components) {
					canonical = InventoryItemFreezer.fluidPatch(components.patch(), registries, displayBytes, budget, RAW, NATIVE);
				} else {
					var nbt = (IPlatformFluidService.Nbt) resident;
					if (nbt.tag() == null) canonical = new byte[] {0}; // native absent tag remains distinct from an empty compound
					else {
						var bounds = new InventoryComponentBounds(budget, RAW);
						canonical = InventoryCanonicalWriter.writeFluid(bounds.ownedPersistentTag(nbt.tag(), 0), budget);
					}
				}
				budget.allocate(canonical.length, 0, 1, 2); budget.work(canonical.length);
				identity = InventoryFluidCodec.Identity.exact(canonical);
			} catch (InventoryItemBudget.CaptureLimitException exhausted) {
				throw exhausted;
			} catch (RuntimeException refused) {
				// Only this identity transaction is inside the fallback boundary. Reliable
				// registry/amount and later display/catalogue/admission failures are not.
				identity = InventoryFluidCodec.Identity.refused();
			}
			budget.beginDisplay();
			// Prepay one external base-name call plus the closed projection's bounded key/plain-text
			// checks and DTO/fallback storage, inside the existing admitted display phase. Native
			// callback internals remain external-trusted, not an unbounded Ping component traversal.
			budget.allocate(16384, 0, 32, 32); budget.work(32768);
			@Nullable var base = FluidDescriptions.describeBase(resident.fluid(), descriptions);
			String label = base instanceof IPlatformFluidDescriptionService.Translation translation ? translation.key() : id;
			// A native base literal is not a custom name. Keep the ID-only projection and wire grammar.
			budget.allocate(8192, 0, 24, 24); budget.work(16384);
			String json = InventoryFluidDisplayCodec.encode(new InventoryFluidDisplayCodec.Projection(id));
			var display = new InventoryFluidValue.Display(id, label, json);
			return catalog.intern(id, entry.amount(), identity, display, admission);
		} finally { budget.end(); }
	}
}
