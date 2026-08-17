package nx.pingwheel.common.domain;

import java.util.Objects;

/**
 * Stable locator for an entity target.
 *
 * <p>The locator kind is part of the value identity: a UUID locator and a
 * runtime-id locator with numerically related values are never interchangeable
 * or equal. Runtime ids are deliberately limited to the synchronized,
 * non-negative integer representation used by the network protocol; the
 * Minecraft adapters in this step resolve only UUID locators.
 */
public sealed interface EntityLocator permits EntityLocator.UUID, EntityLocator.RuntimeId {

	/** Stable semantic and wire metadata for each locator representation. */
	enum Kind {
		UUID(0, "uuid"),
		RUNTIME_ID(1, "runtime_id");

		private final int wireTag;
		private final String tag;

		Kind(int wireTag, String tag) {
			this.wireTag = wireTag;
			this.tag = tag;
		}

		/** Explicit stable integer tag; this is not an enum ordinal. */
		public int wireTag() {
			return wireTag;
		}

		/** Stable ASCII tag useful in diagnostics and human-readable state. */
		public String tag() {
			return tag;
		}

		static Kind fromWireTag(int wireTag) {
			for (Kind kind : values()) {
				if (kind.wireTag == wireTag) {
					return kind;
				}
			}

			throw new IllegalArgumentException("Unknown entity locator tag: " + wireTag);
		}
	}

	Kind kind();

	/** Returns the stable ASCII kind tag. */
	default String tag() {
		return kind().tag();
	}

	/** Returns the explicit stable wire tag for this locator kind. */
	default int wireTag() {
		return kind().wireTag();
	}

	/** A globally stable entity UUID. */
	record UUID(java.util.UUID value) implements EntityLocator {

		public UUID {
			Objects.requireNonNull(value, "value");
		}

		@Override
		public Kind kind() {
			return Kind.UUID;
		}

		/** Explicitly named UUID accessor for adapter code. */
		public java.util.UUID uuid() {
			return value;
		}
	}

	/** A synchronized, non-negative runtime entity id. */
	record RuntimeId(int value) implements EntityLocator {

		public RuntimeId {
			if (value < 0) {
				throw new IllegalArgumentException("value must be non-negative: " + value);
			}
		}

		@Override
		public Kind kind() {
			return Kind.RUNTIME_ID;
		}

		/** Explicitly named runtime-id accessor for adapter code. */
		public int id() {
			return value;
		}
	}

	/** Convenience factory for the UUID variant. */
	static UUID uuid(java.util.UUID value) {
		return new UUID(value);
	}

	/** Convenience factory for the runtime-id variant. */
	static RuntimeId runtimeId(int value) {
		return new RuntimeId(value);
	}

	/** Decodes the explicit wire tag into a locator kind. */
	static Kind kindFromWireTag(int wireTag) {
		return Kind.fromWireTag(wireTag);
	}
}
