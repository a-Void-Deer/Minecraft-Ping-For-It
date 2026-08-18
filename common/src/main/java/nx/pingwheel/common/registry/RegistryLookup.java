package nx.pingwheel.common.registry;

/**
 * A stable, indirect lookup for optional registry content.
 *
 * <p>This functional interface deliberately uses only stable {@link String}
 * identifiers (a registry id plus an entry id) and no optional-mod classes, so
 * a missing optional mod can never cause class-loading or linkage failures.
 * The concrete lookup implementation is injected by the caller and only queried
 * on demand.
 */
@FunctionalInterface
public interface RegistryLookup {

	/**
	 * Whether {@code entryId} exists within {@code registryId}.
	 */
	boolean contains(String registryId, String entryId);
}
