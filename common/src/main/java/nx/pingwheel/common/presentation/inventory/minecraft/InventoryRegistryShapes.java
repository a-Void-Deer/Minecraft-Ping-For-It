package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

/**
 * Native class identities from a fixed empty construction, independent of runtime
 * mapping names. Process initialization only: no supplied provider is queried.
 * Exact identities do not license arbitrary implementations of the raw ports.
 */
final class InventoryRegistryShapes {
	static final Class<?> PROVIDER, ENTRY, LOOKUP, OWNER;
	static final Class<?> MAPPED_LOOKUP = BuiltInRegistries.BLOCK.asLookup().getClass();
	static {
		InventoryResidentAccess raw = InventoryResidentAccess.load();
		var provider = new RegistrySetBuilder().add(Registries.BLOCK, bootstrap -> {}).build(RegistryAccess.EMPTY);
		// This fixed native construction has exactly one entry, not an input map.
		var entries = raw.builderEntries(provider);
		if (entries.getClass() != java.util.HashMap.class || entries.size() != 1)
			throw new ExceptionInInitializerError("native registry construction drift");
		Object entry = entries.values().iterator().next(); var info = raw.builderInfo(entry);
		PROVIDER = provider.getClass(); ENTRY = entry.getClass();
		LOOKUP = info.getter().getClass(); OWNER = info.owner().getClass();
	}
	private InventoryRegistryShapes() {}
	static void initialize() { /* force fixed native class identity initialization before capture */ }
}
