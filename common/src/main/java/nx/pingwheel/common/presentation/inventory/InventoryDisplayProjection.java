package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentReads;
import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentReads.MethodOwnership;

/** Per-owner evidence for the legacy inherited no-op, not a registry of trusted parsers. */
final class InventoryDisplayProjection {

	/**
	 * One handle query can classify its source wrapper, that wrapper's delegate,
	 * and its snapshot. Each classification has at most 32 declared lookups and
	 * one public lookup over a bounded class/interface graph, with ResidentReads'
	 * bounded type/member/signature names. Class.getInterfaces can return at most
	 * the class file's u2 interface count (65535 references); admit that array
	 * before querying, then refuse graphs above 32 edges without traversing them.
	 * The fixed grant covers that sequential array plus Method/argument/failed-
	 * signature scratch, cached enum evidence and combined records. VM metadata caches,
	 * class loading and VM backtraces have the existing reflection-model boundary.
	 */
	static final long METADATA_BYTES = 65535L * Long.BYTES + 32768L;
	static final long QUERY_WORK = InventoryReprojectionSummary.FIXED_WORK + 3L * (33L * 8192L + 65536L);
	private MethodOwnership ownership;

	/** Called only inside the handle's admitted fixed metadata query. */
	InventoryReprojectionSummary summary(Object value, Class<?> defaultOwner, InventoryReprojectionSummary declared) {
		if (declared != null && declared.known()) return declared;
		if (ownership == null) ownership = classify(value, defaultOwner);
		return ownership == MethodOwnership.PROVEN_INHERITED ? InventoryReprojectionSummary.EMPTY : InventoryReprojectionSummary.UNKNOWN;
	}

	private static MethodOwnership classify(Object value, Class<?> defaultOwner) {
		// The declared-method helper deliberately does not infer interface defaults.
		// Only a bounded complete class chain may use the public resolution fallback;
		// its actual declaring interface must be the literal no-op's owner, not an
		// overriding subinterface. No candidate method is invoked or made accessible.
		try {
			MethodOwnership declared = ResidentReads.methodOwnership(value, defaultOwner, "tightenDisplay", int.class);
			if (declared != MethodOwnership.UNCLASSIFIED) return declared;
			if (!boundedInterfaces(value.getClass())) return MethodOwnership.UNCLASSIFIED;
			return value.getClass().getMethod("tightenDisplay", int.class).getDeclaringClass() == defaultOwner
				? MethodOwnership.PROVEN_INHERITED : MethodOwnership.PROVEN_OVERRIDE;
		} catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {
			return MethodOwnership.UNCLASSIFIED;
		}
	}

	private static boolean boundedInterfaces(Class<?> implementation) {
		Class<?>[] pending = new Class<?>[32];
		int size = 0, edges = 0;
		Class<?> type = implementation;
		for (int depth = 0; type != null && depth < 32; depth++, type = type.getSuperclass()) {
			if (!ResidentReads.reflectionShape(type, "tightenDisplay", int.class)) return false;
			Class<?>[] interfaces = type.getInterfaces();
			if (interfaces.length > pending.length - edges) return false;
			edges += interfaces.length;
			for (Class<?> candidate : interfaces) pending[size++] = candidate;
		}
		if (type != null) return false;
		while (size > 0) {
			Class<?> candidate = pending[--size]; pending[size] = null;
			if (!ResidentReads.reflectionShape(candidate, "tightenDisplay", int.class)) return false;
			Class<?>[] parents = candidate.getInterfaces();
			if (parents.length > pending.length - edges) return false;
			edges += parents.length;
			for (Class<?> parent : parents) pending[size++] = parent;
		}
		return true;
	}
}
