package nx.pingwheel.common.network;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.network.FriendlyByteBuf;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.name.TargetNameJson;

/**
 * Centralized, symmetric wire encoding for the marker network model.
 *
	 * <p>Every write has an exact matching read in the same order. String ids
	 * (dimension, block registry, provider, stable target, ping/target type, enum
	 * tags) are capped UTF and preserved verbatim; the opaque external provider
	 * locator has its own bounded UTF cap. Enums are written by stable
	 * {@link Enum#name()} (never ordinal) and unknown names are rejected by throwing, which lets
 * {@link PacketHandler#readSafe} fall back to a corrupt packet instead of
 * accepting fabricated values.
 *
 * <p>Only {@code net.minecraft.network.FriendlyByteBuf} and plain JDK types
 * are used: no optional-mod classes, no client-only imports, and no loader
 * specific types appear here.
 */
public final class MarkerPacketCodec {

	public static final int MAX_ID_LENGTH = 256;
	public static final int MAX_OWNER_NAME_LENGTH = MAX_ID_LENGTH;
	public static final int MAX_EXTERNAL_PROVIDER_LOCATOR_LENGTH =
		Target.ExternalBlockTarget.MAX_PROVIDER_LOCATOR_LENGTH;

	/** Explicit block target variant tag for the ordinary vanilla block form. */
	public static final int BLOCK_TARGET_STANDARD_TAG = 0;

	/** Explicit block target variant tag for the external provider form. */
	public static final int BLOCK_TARGET_EXTERNAL_TAG = 1;

	private MarkerPacketCodec() {}

	// --- primitive ids ---

	public static void writeIdString(FriendlyByteBuf buf, String value) {
		buf.writeUtf(value, MAX_ID_LENGTH);
	}

	public static String readIdString(FriendlyByteBuf buf) {
		return buf.readUtf(MAX_ID_LENGTH);
	}

	// --- authoritative owner profile name ---

	public static void writeOwnerName(FriendlyByteBuf buf, String ownerName) {
		buf.writeUtf(Objects.requireNonNull(ownerName, "ownerName"), MAX_OWNER_NAME_LENGTH);
	}

	public static String readOwnerName(FriendlyByteBuf buf) {
		return buf.readUtf(MAX_OWNER_NAME_LENGTH);
	}

	// --- enums (by stable name, never ordinal) ---

	public static <E extends Enum<E>> void writeEnum(FriendlyByteBuf buf, E value) {
		buf.writeUtf(value.name(), MAX_ID_LENGTH);
	}

	public static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, Class<E> enumClass) {
		return Enum.valueOf(enumClass, buf.readUtf(MAX_ID_LENGTH));
	}

	// --- target ---

	/** Explicit, stable wire tag for the UUID entity locator variant. */
	public static final int ENTITY_LOCATOR_UUID_TAG = EntityLocator.Kind.UUID.wireTag();

	/** Explicit, stable wire tag for the runtime-id entity locator variant. */
	public static final int ENTITY_LOCATOR_RUNTIME_ID_TAG = EntityLocator.Kind.RUNTIME_ID.wireTag();

	public static void writeTarget(FriendlyByteBuf buf, Target target) {
		Objects.requireNonNull(target, "target");
		writeEnum(buf, target.kind());
		writeIdString(buf, target.dimensionId());

		switch (target) {
			case Target.EntityTarget entity -> writeEntityLocator(buf, entity.locator());
			case Target.BlockTarget block -> {
				buf.writeVarInt(BLOCK_TARGET_STANDARD_TAG);
				buf.writeInt(block.x());
				buf.writeInt(block.y());
				buf.writeInt(block.z());
				writeIdString(buf, block.blockRegistryId());
			}
			case Target.ExternalBlockTarget external -> {
				buf.writeVarInt(BLOCK_TARGET_EXTERNAL_TAG);
				writeIdString(buf, external.providerId());
				writeIdString(buf, external.stableTargetId());
				writeIdString(buf, external.expectedBlockRegistryId());
				buf.writeUtf(external.providerLocator(), MAX_EXTERNAL_PROVIDER_LOCATOR_LENGTH);
				writeStrictBoolean(buf, external.hasBlockEntity());
			}
			case Target.LocationTarget location -> {
				buf.writeDouble(location.x());
				buf.writeDouble(location.y());
				buf.writeDouble(location.z());
			}
		}
	}

	public static Target readTarget(FriendlyByteBuf buf) {
		TargetKind kind = readEnum(buf, TargetKind.class);
		String dimensionId = readIdString(buf);

		return switch (kind) {
			case ENTITY -> new Target.EntityTarget(dimensionId, readEntityLocator(buf));
			case BLOCK -> readBlockTarget(buf, dimensionId);
			case LOCATION -> new Target.LocationTarget(
				dimensionId, buf.readDouble(), buf.readDouble(), buf.readDouble());
		};
	}

	// --- target key ---

	public static void writeTargetKey(FriendlyByteBuf buf, TargetKey targetKey) {
		Objects.requireNonNull(targetKey, "targetKey");
		writeEnum(buf, kindOf(targetKey));
		writeIdString(buf, targetKey.dimensionId());

		switch (targetKey) {
			case TargetKey.EntityKey entity -> writeEntityLocator(buf, entity.locator());
			case TargetKey.BlockKey block -> {
				buf.writeVarInt(BLOCK_TARGET_STANDARD_TAG);
				buf.writeInt(block.x());
				buf.writeInt(block.y());
				buf.writeInt(block.z());
				writeIdString(buf, block.blockRegistryId());
			}
			case TargetKey.ExternalBlockKey external -> {
				buf.writeVarInt(BLOCK_TARGET_EXTERNAL_TAG);
				writeIdString(buf, external.providerId());
				writeIdString(buf, external.stableTargetId());
				writeIdString(buf, external.expectedBlockRegistryId());
			}
			case TargetKey.LocationKey location -> {
				buf.writeDouble(location.x());
				buf.writeDouble(location.y());
				buf.writeDouble(location.z());
			}
		}
	}

	public static TargetKey readTargetKey(FriendlyByteBuf buf) {
		TargetKind kind = readEnum(buf, TargetKind.class);
		String dimensionId = readIdString(buf);

		return switch (kind) {
			case ENTITY -> new TargetKey.EntityKey(dimensionId, readEntityLocator(buf));
			case BLOCK -> readBlockTargetKey(buf, dimensionId);
			case LOCATION -> new TargetKey.LocationKey(
				dimensionId, buf.readDouble(), buf.readDouble(), buf.readDouble());
		};
	}

	/**
	 * Encodes an entity locator as its explicit stable tag followed by the
	 * representation selected by that tag.
	 */
	public static void writeEntityLocator(FriendlyByteBuf buf, EntityLocator locator) {
		Objects.requireNonNull(locator, "locator");
		buf.writeVarInt(locator.wireTag());

		switch (locator) {
			case EntityLocator.UUID uuid -> buf.writeUUID(uuid.value());
			case EntityLocator.RuntimeId runtimeId -> buf.writeVarInt(runtimeId.value());
		}
	}

	/**
	 * Decodes an entity locator and rejects unknown or negative values. Truncated
	 * reads retain FriendlyByteBuf's existing exception behavior so packet
	 * read-safe wrappers can mark the packet corrupt.
	 */
	public static EntityLocator readEntityLocator(FriendlyByteBuf buf) {
		EntityLocator.Kind kind = EntityLocator.kindFromWireTag(buf.readVarInt());

		return switch (kind) {
			case UUID -> EntityLocator.uuid(buf.readUUID());
			case RUNTIME_ID -> {
				int value = buf.readVarInt();

				if (value < 0) {
					throw new IllegalArgumentException("Entity runtime id must be non-negative: " + value);
				}

				yield EntityLocator.runtimeId(value);
			}
		};
	}

	private static TargetKind kindOf(TargetKey targetKey) {
		return switch (targetKey) {
			case TargetKey.EntityKey ignored -> TargetKind.ENTITY;
			case TargetKey.BlockKey ignored -> TargetKind.BLOCK;
			case TargetKey.ExternalBlockKey ignored -> TargetKind.BLOCK;
			case TargetKey.LocationKey ignored -> TargetKind.LOCATION;
		};
	}

	private static Target readBlockTarget(FriendlyByteBuf buf, String dimensionId) {
		return switch (readBlockVariantTag(buf)) {
			case BLOCK_TARGET_STANDARD_TAG -> new Target.BlockTarget(
				dimensionId, buf.readInt(), buf.readInt(), buf.readInt(), readIdString(buf));
			case BLOCK_TARGET_EXTERNAL_TAG -> new Target.ExternalBlockTarget(
				dimensionId,
				readIdString(buf),
				readIdString(buf),
				readIdString(buf),
				buf.readUtf(MAX_EXTERNAL_PROVIDER_LOCATOR_LENGTH),
				readStrictBoolean(buf));
			default -> throw new IllegalArgumentException("Unknown block target variant tag");
		};
	}

	private static TargetKey readBlockTargetKey(FriendlyByteBuf buf, String dimensionId) {
		return switch (readBlockVariantTag(buf)) {
			case BLOCK_TARGET_STANDARD_TAG -> new TargetKey.BlockKey(
				dimensionId, buf.readInt(), buf.readInt(), buf.readInt(), readIdString(buf));
			case BLOCK_TARGET_EXTERNAL_TAG -> new TargetKey.ExternalBlockKey(
				dimensionId, readIdString(buf), readIdString(buf), readIdString(buf));
			default -> throw new IllegalArgumentException("Unknown block target variant tag");
		};
	}

	private static int readBlockVariantTag(FriendlyByteBuf buf) {
		return buf.readVarInt();
	}

	private static void writeStrictBoolean(FriendlyByteBuf buf, boolean value) {
		buf.writeByte(value ? 1 : 0);
	}

	private static boolean readStrictBoolean(FriendlyByteBuf buf) {
		return switch (buf.readUnsignedByte()) {
			case 0 -> false;
			case 1 -> true;
			default -> throw new IllegalArgumentException("Invalid boolean value");
		};
	}

	// --- marker id ---

	public static void writeMarkerId(FriendlyByteBuf buf, MarkerId markerId) {
		buf.writeLong(markerId.value());
	}

	public static MarkerId readMarkerId(FriendlyByteBuf buf) {
		return new MarkerId(buf.readLong());
	}

	public static void writeOptionalMarkerId(FriendlyByteBuf buf, Optional<MarkerId> markerId) {
		buf.writeBoolean(markerId.isPresent());
		markerId.ifPresent(id -> writeMarkerId(buf, id));
	}

	public static Optional<MarkerId> readOptionalMarkerId(FriendlyByteBuf buf) {
		if (!buf.readBoolean()) {
			return Optional.empty();
		}

		return Optional.of(readMarkerId(buf));
	}

	// --- anchor ---

	public static void writeMarkerAnchor(FriendlyByteBuf buf, MarkerAnchor anchor) {
		buf.writeDouble(anchor.x());
		buf.writeDouble(anchor.y());
		buf.writeDouble(anchor.z());
	}

	public static MarkerAnchor readMarkerAnchor(FriendlyByteBuf buf) {
		return new MarkerAnchor(buf.readDouble(), buf.readDouble(), buf.readDouble());
	}

	// --- target name json ---

	/**
	 * The symmetric cap for the authoritative target name JSON, matching
	 * {@link TargetNameJson#MAX_LENGTH} and the hard cap of the network UTF
	 * string encoding.
	 */
	public static final int MAX_NAME_LENGTH = TargetNameJson.MAX_LENGTH;

	public static void writeTargetNameJson(FriendlyByteBuf buf, TargetNameJson name) {
		buf.writeUtf(name.value(), MAX_NAME_LENGTH);
	}

	public static TargetNameJson readTargetNameJson(FriendlyByteBuf buf) {
		return new TargetNameJson(buf.readUtf(MAX_NAME_LENGTH));
	}

	// --- snapshot ---

	public static void writeMarkerSnapshot(FriendlyByteBuf buf, MarkerSnapshot snapshot) {
		writeMarkerId(buf, snapshot.id());
		buf.writeUUID(snapshot.owner());
		writeTarget(buf, snapshot.target());
		writeIdString(buf, snapshot.targetTypeId());
		writeIdString(buf, snapshot.pingTypeId());
		writeMarkerAnchor(buf, snapshot.anchor());
		buf.writeLong(snapshot.arrivalTick());
		buf.writeLong(snapshot.expiresAtTick());
	}

	public static MarkerSnapshot readMarkerSnapshot(FriendlyByteBuf buf) {
		return new MarkerSnapshot(
			readMarkerId(buf),
			buf.readUUID(),
			readTarget(buf),
			readIdString(buf),
			readIdString(buf),
			readMarkerAnchor(buf),
			buf.readLong(),
			buf.readLong()
		);
	}
}
