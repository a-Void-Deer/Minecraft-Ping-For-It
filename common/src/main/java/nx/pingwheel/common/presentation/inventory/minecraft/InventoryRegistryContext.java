package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.DefaultedMappedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

/**
 * Separate audited encode owners and an on-demand external-trusted native
 * context. Auditing proves captured containers/delegates without provider calls;
 * only the explicit external path may consult arbitrary provider internals.
 */
final class InventoryRegistryContext implements RegistryOps.RegistryInfoLookup {
	/** Exact raw identity lookup, not a bucket traversal or a registry callback. */
	static long lookupWork(Map<?, ?> map) {
		InventoryComponentCertificate.exact(map, java.util.IdentityHashMap.class);
		return InventoryItemBudget.add(12, InventoryItemBudget.multiply(8, map.size() + 1L));
	}
	static long registrationWork(Registry<?> registry, InventoryResidentAccess raw) {
		return lookupWork(raw.registrationInfos(mapped(registry)));
	}
	static long valueWork(Registry<?> registry, InventoryResidentAccess raw) {
		return lookupWork(raw.registryValues(mapped(registry)));
	}
	private static final Class<?> ROOT = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).getClass();
	private static final Class<?> FROZEN = RegistryAccess.EMPTY.getClass();
	private final ResourceKey<?>[] keys;
	private final RegistryOps.RegistryInfo<?>[] infos;
	private final Optional<? extends RegistryOps.RegistryInfo<?>>[] results;
	private final InventoryItemBudget budget;
	private final HolderLookup.Provider provider;
	private final InventoryResidentAccess raw;
	private final InventoryComponentBounds bounds;
	private RegistryOps<net.minecraft.nbt.Tag> externalOps;
	private boolean audited;
	private int size;

	@SuppressWarnings("unchecked")
	private InventoryRegistryContext(HolderLookup.Provider provider, InventoryResidentAccess raw,
		InventoryComponentBounds bounds, InventoryItemBudget budget) {
		this.budget = budget; this.provider = provider; this.raw = raw; this.bounds = bounds;
		// Only the seven selected encode-owner registries are retained. The
		// native provider/root map pass remains fully admitted independently.
		int capacity = 7;
		budget.allocate(0, 0, 3L * capacity, 3);
		keys = new ResourceKey<?>[capacity];
		infos = new RegistryOps.RegistryInfo<?>[capacity];
		results = (Optional<? extends RegistryOps.RegistryInfo<?>>[]) new Optional<?>[capacity];
	}

	static InventoryRegistryContext capture(HolderLookup.Provider provider, InventoryResidentAccess raw,
		InventoryComponentBounds bounds, InventoryItemBudget budget) {
		var context = prepare(provider, raw, bounds, budget);
		context.requireAudited();
		return context;
	}

	static InventoryRegistryContext prepare(HolderLookup.Provider provider, InventoryResidentAccess raw,
		InventoryComponentBounds bounds, InventoryItemBudget budget) {
		budget.work(128);
		// Admit this context and its fixed arrays BEFORE inspecting any supplied
		// root map. No builder/provider callback or context cache is constructed.
		budget.allocate(5, 0, 8, 1);
		return new InventoryRegistryContext(provider, raw, bounds, budget);
	}

	void requireAudited() {
		if (audited) return;
		budget.work(32);
		Class<?> type = provider.getClass();
		if (type == InventoryRegistryShapes.PROVIDER) {
			Map<?, ?> entries = raw.builderEntries(provider);
			int count = bounds.mapPass(entries);
			if (count != 0) for (var entry : entries.entrySet()) {
				budget.work(128);
				InventoryComponentCertificate.exact(entry.getKey(), ResourceKey.class);
				if (!needed((ResourceKey<?>) entry.getKey())) continue;
				if (entry.getValue() == null || entry.getValue().getClass() != InventoryRegistryShapes.ENTRY)
					InventoryComponentCertificate.refuse();
				RegistryOps.RegistryInfo<?> info = raw.builderInfo(entry.getValue());
				InventoryComponentCertificate.exact(info, RegistryOps.RegistryInfo.class);
				if (info.owner() != null && info.owner().getClass() == InventoryRegistryShapes.OWNER) {
					Object lookup = info.getter();
					if (lookup == null || lookup.getClass() != InventoryRegistryShapes.LOOKUP
						|| raw.builderKey(lookup) != entry.getKey() || raw.builderOwner(lookup) != info.owner()
						|| raw.builderLifecycle(lookup) != info.elementsLifecycle()) InventoryComponentCertificate.refuse();
					// Encode-only use never calls the getter. Still certify its captured
					// map layout, without traversing holder values or lazy suppliers.
					bounds.mapPass(raw.builderElements(lookup));
					addInfo((ResourceKey<?>) entry.getKey(), info, budget);
				} else {
					Object lookup = info.owner();
					if (lookup == null || lookup.getClass() != InventoryRegistryShapes.MAPPED_LOOKUP || info.getter() != lookup)
						InventoryComponentCertificate.refuse();
					MappedRegistry<?> registry = mapped(raw.lookupRegistry(lookup));
					if (raw.registryKey(registry) != entry.getKey() || raw.registryLookup(registry) != lookup
						|| raw.registryLifecycle(registry) != info.elementsLifecycle()) InventoryComponentCertificate.refuse();
					add(registry, raw, budget);
				}
			}
			audited = true; return;
		}
		if (type == ROOT) {
			Registry<?> delegate = raw.registryDelegate(provider);
			MappedRegistry<?> root = mapped(delegate);
			InventoryComponentCertificate.exact(raw.registryKey(root), ResourceKey.class);
			Map<ResourceKey<?>, Holder.Reference<?>> entries = raw.registryEntries(root);
			int count = bounds.mapPass(entries);
			if (count != 0) for (var entry : entries.entrySet()) {
				budget.work(128);
				InventoryComponentCertificate.exact(entry.getKey(), ResourceKey.class);
				if (!needed(entry.getKey())) continue;
				InventoryComponentCertificate.exact(entry.getValue(), Holder.Reference.class);
				if (raw.holderKey(entry.getValue()) != entry.getKey() || raw.holderValue(entry.getValue()) == null)
					InventoryComponentCertificate.refuse();
				MappedRegistry<?> registry = mapped(raw.holderValue(entry.getValue()));
				if (raw.registryKey(registry) != entry.getKey()) InventoryComponentCertificate.refuse();
				add(registry, raw, budget);
			}
			audited = true; return;
		}
		if (type != RegistryAccess.ImmutableRegistryAccess.class && type != FROZEN) InventoryComponentCertificate.refuse();
		Map<? extends ResourceKey<?>, ? extends Registry<?>> entries = raw.registries((RegistryAccess.ImmutableRegistryAccess) provider);
		int count = bounds.mapPass(entries);
		if (count != 0) for (var entry : entries.entrySet()) {
			budget.work(128);
			InventoryComponentCertificate.exact(entry.getKey(), ResourceKey.class);
			if (!needed(entry.getKey())) continue;
			MappedRegistry<?> registry = mapped(entry.getValue());
			if (raw.registryKey(registry) != entry.getKey()) InventoryComponentCertificate.refuse();
			add(registry, raw, budget);
		}
		audited = true;
	}

	/**
	 * Native adapter/cache and the provider's lookup/lifecycle/getter internals are
	 * external-trusted here. No registry enumeration or audited-owner substitution.
	 * The one Ping-owned RegistryOps wrapper is still admitted before construction.
	 */
	RegistryOps<net.minecraft.nbt.Tag> externalOps() {
		if (externalOps == null) {
			budget.work(32); budget.allocate(0, 0, 2, 1);
			externalOps = RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, provider);
		}
		return externalOps;
	}

	private static boolean needed(ResourceKey<?> key) {
		return key == Registries.BLOCK || key == Registries.ENCHANTMENT || key == Registries.INSTRUMENT
			|| key == Registries.TRIM_MATERIAL || key == Registries.TRIM_PATTERN
			|| key == Registries.BANNER_PATTERN || key == Registries.JUKEBOX_SONG;
	}

	private static MappedRegistry<?> mapped(Object registry) {
		if (registry == null || (registry.getClass() != MappedRegistry.class && registry.getClass() != DefaultedMappedRegistry.class))
			InventoryComponentCertificate.refuse();
		return (MappedRegistry<?>) registry;
	}

	private void add(MappedRegistry<?> registry, InventoryResidentAccess raw, InventoryItemBudget budget) {
		budget.work(64); // exact owner/delegate/lifecycle field checks
		ResourceKey<?> key = raw.registryKey(registry);
		InventoryComponentCertificate.exact(key, ResourceKey.class);
		HolderLookup.RegistryLookup<?> lookup = raw.registryLookup(registry);
		// MappedRegistry constructs this final owner itself. Its inherited
		// HolderOwner.canSerializeIn is identity; no captured third-party delegate.
		if (lookup == null || lookup.getClass() != InventoryRegistryShapes.MAPPED_LOOKUP || raw.lookupRegistry(lookup) != registry)
			InventoryComponentCertificate.refuse();
		Lifecycle lifecycle = raw.registryLifecycle(registry);
		if (lifecycle != Lifecycle.stable() && lifecycle != Lifecycle.experimental()) InventoryComponentCertificate.refuse();
		budget.allocate(0, 0, 3, 1);
		addInfo(key, info(lookup, lifecycle), budget);
	}

	private void addInfo(ResourceKey<?> key, RegistryOps.RegistryInfo<?> info, InventoryItemBudget budget) {
		if (size == keys.length) InventoryComponentCertificate.refuse();
		if (info.elementsLifecycle() != Lifecycle.stable() && info.elementsLifecycle() != Lifecycle.experimental()) InventoryComponentCertificate.refuse();
		for (int index = 0; index < size; index++) { budget.work(8); if (keys[index] == key) InventoryComponentCertificate.refuse(); }
		budget.allocate(0, 0, 1, 1);
		keys[size] = key; infos[size] = info; results[size++] = Optional.of(info);
	}

	private static <T> RegistryOps.RegistryInfo<T> info(HolderLookup.RegistryLookup<T> owner, Lifecycle lifecycle) {
		return new RegistryOps.RegistryInfo<>(owner, owner, lifecycle);
	}

	HolderOwner<?> owner(ResourceKey<?> key) {
		requireAudited();
		for (int index = 0; index < size; index++) {
			budget.work(8);
			if (keys[index] == key) return infos[index].owner();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
		requireAudited();
		for (int index = 0; index < size; index++) {
			budget.work(8);
			if (keys[index] == key) {
				return (Optional<RegistryOps.RegistryInfo<T>>) (Optional<?>) results[index];
			}
		}
		return Optional.empty();
	}

	static void initialize() { /* fixed ROOT/FROZEN identities before begin */ }
}
