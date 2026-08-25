package nx.pingwheel.common.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The concrete object captured when the ping key is pressed.
 *
 * <p>A target identity is deliberately frozen at capture time:
 * <ul>
	 *   <li>an entity is identified by dimension id + an explicit
	 *       {@link EntityLocator} (it carries no
	 *       mutable position, so movement/teleport within a dimension never changes
	 *       identity);</li>
	 *   <li>a block is identified by dimension + position + block registry id and
	 *       deliberately excludes {@code BlockState};</li>
	 *   <li>an external block is identified by its provider-owned stable identity;
	 *       its opaque locator and block-entity classification are not identity;</li>
	 *   <li>a pure location is the lowest-priority fallback.</li>
 * </ul>
 *
 * <p>Dimension identity is a stable, non-blank {@link String} resource
 * identifier such as {@code minecraft:overworld}. Later adapters derive it via
 * {@code ResourceKey<Level>.location().toString()}. Using the string identifier
 * (rather than a bare numeric hash code) avoids a hash-collision behavior from
 * becoming the authoritative dimension identity.
 *
 * <p>Only JDK types are used here; there are no {@code net.minecraft}
 * references, so this hierarchy can be tested without a game client.
 */
public sealed interface Target permits Target.EntityTarget, Target.BlockTarget, Target.ExternalBlockTarget, Target.LocationTarget {

	String dimensionId();

	TargetKind kind();

	record EntityTarget(String dimensionId, EntityLocator locator) implements Target {

		public EntityTarget {
			Target.requireDimensionId(dimensionId);
			Objects.requireNonNull(locator, "locator");
		}

		/**
		 * UUID convenience constructor retained for callers that already have a
		 * UUID locator.
		 */
		public EntityTarget(String dimensionId, UUID entityId) {
			this(dimensionId, EntityLocator.uuid(entityId));
		}

		@Override
		public TargetKind kind() {
			return TargetKind.ENTITY;
		}
	}

	record BlockTarget(String dimensionId, int x, int y, int z, String blockRegistryId) implements Target {

		public BlockTarget {
			Target.requireDimensionId(dimensionId);
			Objects.requireNonNull(blockRegistryId, "blockRegistryId");

			if (blockRegistryId.isBlank()) {
				throw new IllegalArgumentException("blockRegistryId must not be blank");
			}
		}

		@Override
		public TargetKind kind() {
			return TargetKind.BLOCK;
		}
	}

	/**
	 * A block-like target supplied by an external provider.
	 *
	 * <p>The stable identity is the dimension, provider, non-empty committed
	 * target id, and expected block registry id. An empty {@code stableTargetId}
	 * is reserved for a candidate carried by a create request; such a candidate
	 * cannot be converted to a {@link nx.pingwheel.common.marker.TargetKey} or
	 * stored in a committed marker. {@code providerLocator} is an opaque,
	 * bounded provider value used to locate the live object and is deliberately
	 * not part of target-key identity. The block-entity flag is likewise
	 * classification data rather than identity.
	 *
	 * <p>This type contains no provider or optional-mod classes. The provider
	 * locator is kept as a String so the common model never has to interpret
	 * provider-specific data.
	 */
	record ExternalBlockTarget(
		String dimensionId,
		String providerId,
		String stableTargetId,
		String expectedBlockRegistryId,
		String providerLocator,
		boolean hasBlockEntity
	) implements Target {

		/** Maximum length of each external identity identifier. */
		public static final int MAX_IDENTIFIER_LENGTH = 256;

		/** Maximum length of the opaque provider locator payload. */
		public static final int MAX_PROVIDER_LOCATOR_LENGTH = 32767;

		public ExternalBlockTarget {
			Target.requireDimensionId(dimensionId);
			requireIdentifier("providerId", providerId);
			requireStableTargetId(stableTargetId);
			requireIdentifier("expectedBlockRegistryId", expectedBlockRegistryId);
			requireProviderLocator(providerLocator);
		}

		/**
		 * Creates an external target with an unknown/negative block-entity
		 * classification. The provider locator remains opaque to the core.
		 */
		public ExternalBlockTarget(
			String dimensionId,
			String providerId,
			String stableTargetId,
			String expectedBlockRegistryId,
			String providerLocator
		) {
			this(dimensionId, providerId, stableTargetId, expectedBlockRegistryId, providerLocator, false);
		}

		/** Creates an uncommitted candidate with an empty stable target id. */
		public static ExternalBlockTarget candidate(
			String dimensionId,
			String providerId,
			String expectedBlockRegistryId,
			String providerLocator,
			boolean hasBlockEntity
		) {
			return new ExternalBlockTarget(
				dimensionId, providerId, "", expectedBlockRegistryId, providerLocator, hasBlockEntity);
		}

		/** Creates a committed external target with a stable target id. */
		public static ExternalBlockTarget committed(
			String dimensionId,
			String providerId,
			String stableTargetId,
			String expectedBlockRegistryId,
			String providerLocator,
			boolean hasBlockEntity
		) {
			return new ExternalBlockTarget(
				dimensionId, providerId, stableTargetId, expectedBlockRegistryId, providerLocator,
				hasBlockEntity);
		}

		@Override
		public TargetKind kind() {
			return TargetKind.BLOCK;
		}

		/** Whether this target is an uncommitted C2S candidate. */
		public boolean isCandidate() {
			return stableTargetId.isEmpty();
		}

		/** Whether this target has the stable identity required by a marker. */
		public boolean isCommitted() {
			return !isCandidate();
		}

		/** Descriptive alias for callers that call the opaque value a locator. */
		public String locator() {
			return providerLocator;
		}

		/** Descriptive alias for generic provider-payload adapters. */
		public String providerPayload() {
			return providerLocator;
		}

		/** Descriptive alias for the block-entity classification. */
		public boolean blockEntity() {
			return hasBlockEntity;
		}

		/** Descriptive alias for the block-entity classification. */
		public boolean isBlockEntity() {
			return hasBlockEntity;
		}

		/**
		 * External target equality is stable identity equality: locator changes and
		 * classification changes do not create a different captured object.
		 */
		@Override
		public boolean equals(Object object) {
			if (this == object) {
				return true;
			}

			if (!(object instanceof ExternalBlockTarget other)) {
				return false;
			}

			return dimensionId.equals(other.dimensionId)
				&& providerId.equals(other.providerId)
				&& stableTargetId.equals(other.stableTargetId)
				&& expectedBlockRegistryId.equals(other.expectedBlockRegistryId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(dimensionId, providerId, stableTargetId, expectedBlockRegistryId);
		}

		private static String requireIdentifier(String name, String value) {
			Objects.requireNonNull(value, name);

			if (value.isBlank()) {
				throw new IllegalArgumentException(name + " must not be blank");
			}

			if (value.length() > MAX_IDENTIFIER_LENGTH) {
				throw new IllegalArgumentException(name + " exceeds the maximum length");
			}

			return value;
		}

		private static String requireStableTargetId(String value) {
			Objects.requireNonNull(value, "stableTargetId");

			if (!value.isEmpty() && value.isBlank()) {
				throw new IllegalArgumentException("stableTargetId must be empty or non-blank");
			}

			if (value.length() > MAX_IDENTIFIER_LENGTH) {
				throw new IllegalArgumentException("stableTargetId exceeds the maximum length");
			}

			return value;
		}

		private static String requireProviderLocator(String value) {
			Objects.requireNonNull(value, "providerLocator");

			if (value.length() > MAX_PROVIDER_LOCATOR_LENGTH) {
				throw new IllegalArgumentException("providerLocator exceeds the maximum length");
			}

			return value;
		}
	}

	record LocationTarget(String dimensionId, double x, double y, double z) implements Target {

		public LocationTarget {
			Target.requireDimensionId(dimensionId);
			Target.requireFinite("x", x);
			Target.requireFinite("y", y);
			Target.requireFinite("z", z);
		}

		@Override
		public TargetKind kind() {
			return TargetKind.LOCATION;
		}
	}

	/**
	 * Validates a dimension resource identifier: non-null and non-blank.
	 */
	static String requireDimensionId(String dimensionId) {
		Objects.requireNonNull(dimensionId, "dimensionId");

		if (dimensionId.isBlank()) {
			throw new IllegalArgumentException("dimensionId must not be blank");
		}

		return dimensionId;
	}

	/**
	 * Validates a coordinate value: must be finite (not NaN or +/-Infinity).
	 */
	static double requireFinite(String name, double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite, got " + value);
		}

		return value;
	}
}
