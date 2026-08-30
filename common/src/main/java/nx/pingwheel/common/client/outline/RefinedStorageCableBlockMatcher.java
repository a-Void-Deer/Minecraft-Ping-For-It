package nx.pingwheel.common.client.outline;

import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/**
 * Pure identity matcher for Refined Storage 2 cable-like blocks.
 *
 * <p>The optional integration supplies the live block class (or its already
 * resolved superclass names) to this helper.  No optional class is loaded by
 * this common code, which keeps the same matcher reusable by every loader.</p>
 */
public final class RefinedStorageCableBlockMatcher {
	public static final String NAMESPACE = "refinedstorage";
	public static final String CABLE_BLOCK_CLASS =
		"com.refinedmods.refinedstorage.common.networking.CableBlock";
	public static final String DIRECTIONAL_CABLE_BLOCK_CLASS =
		"com.refinedmods.refinedstorage.common.support.AbstractDirectionalCableBlock";

	private static final Set<String> CABLE_CLASS_NAMES = Set.of(
		CABLE_BLOCK_CLASS,
		DIRECTIONAL_CABLE_BLOCK_CLASS);

	private RefinedStorageCableBlockMatcher() {
	}

	/**
	 * Matches a live block class without loading or naming any optional class.
	 * The superclass walk is performed by the JVM for the already loaded block.
	 */
	public static boolean matches(ResourceLocation blockId, Class<?> blockClass) {
		if (blockClass == null) {
			return false;
		}

		for (Class<?> current = blockClass; current != null; current = current.getSuperclass()) {
			if (matchesClassName(blockId, current.getName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Headless seam for tests and loader bridges that have already resolved a
	 * block's class hierarchy.  The supplied names are checked in order, but
	 * ordering does not affect the result.
	 */
	public static boolean matches(ResourceLocation blockId, Iterable<String> classHierarchyNames) {
		if (blockId == null || !NAMESPACE.equals(blockId.getNamespace()) || classHierarchyNames == null) {
			return false;
		}

		for (String className : classHierarchyNames) {
			if (matchesClassName(blockId, className)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matchesClassName(ResourceLocation blockId, String className) {
		return blockId != null
			&& NAMESPACE.equals(blockId.getNamespace())
			&& CABLE_CLASS_NAMES.contains(className);
	}

	/** Returns whether a name is one of the two supported RS2 cable classes. */
	public static boolean isCableClassName(String className) {
		return CABLE_CLASS_NAMES.contains(className);
	}
}
