package nx.pingwheel.common.network;

import java.util.Optional;

import net.minecraft.network.FriendlyByteBuf;

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
 * (dimension, block registry, ping/target type, enum tags) are capped UTF and
 * preserved verbatim. Enums are written by stable {@link Enum#name()} (never
 * ordinal) and unknown names are rejected by throwing, which lets
 * {@link PacketHandler#readSafe} fall back to a corrupt packet instead of
 * accepting fabricated values.
 *
 * <p>Only {@code net.minecraft.network.FriendlyByteBuf} and plain JDK types
 * are used: no optional-mod classes, no client-only imports, and no loader
 * specific types appear here.
 */
public final class MarkerPacketCodec {

	public static final int MAX_ID_LENGTH = 256;

	private MarkerPacketCodec() {}

	// --- primitive ids ---

	public static void writeIdString(FriendlyByteBuf buf, String value) {
		buf.writeUtf(value, MAX_ID_LENGTH);
	}

	public static String readIdString(FriendlyByteBuf buf) {
		return buf.readUtf(MAX_ID_LENGTH);
	}

	// --- enums (by stable name, never ordinal) ---

	public static <E extends Enum<E>> void writeEnum(FriendlyByteBuf buf, E value) {
		buf.writeUtf(value.name(), MAX_ID_LENGTH);
	}

	public static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, Class<E> enumClass) {
		return Enum.valueOf(enumClass, buf.readUtf(MAX_ID_LENGTH));
	}

	// --- target ---

	public static void writeTarget(FriendlyByteBuf buf, Target target) {
		writeEnum(buf, target.kind());
		writeIdString(buf, target.dimensionId());

		switch (target) {
			case Target.EntityTarget entity -> buf.writeUUID(entity.entityId());
			case Target.BlockTarget block -> {
				buf.writeInt(block.x());
				buf.writeInt(block.y());
				buf.writeInt(block.z());
				writeIdString(buf, block.blockRegistryId());
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
			case ENTITY -> new Target.EntityTarget(dimensionId, buf.readUUID());
			case BLOCK -> new Target.BlockTarget(
				dimensionId, buf.readInt(), buf.readInt(), buf.readInt(), readIdString(buf));
			case LOCATION -> new Target.LocationTarget(
				dimensionId, buf.readDouble(), buf.readDouble(), buf.readDouble());
		};
	}

	// --- target key ---

	public static void writeTargetKey(FriendlyByteBuf buf, TargetKey targetKey) {
		writeEnum(buf, kindOf(targetKey));
		writeIdString(buf, targetKey.dimensionId());

		switch (targetKey) {
			case TargetKey.EntityKey entity -> buf.writeUUID(entity.entityId());
			case TargetKey.BlockKey block -> {
				buf.writeInt(block.x());
				buf.writeInt(block.y());
				buf.writeInt(block.z());
				writeIdString(buf, block.blockRegistryId());
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
			case ENTITY -> new TargetKey.EntityKey(dimensionId, buf.readUUID());
			case BLOCK -> new TargetKey.BlockKey(
				dimensionId, buf.readInt(), buf.readInt(), buf.readInt(), readIdString(buf));
			case LOCATION -> new TargetKey.LocationKey(
				dimensionId, buf.readDouble(), buf.readDouble(), buf.readDouble());
		};
	}

	private static TargetKind kindOf(TargetKey targetKey) {
		return switch (targetKey) {
			case TargetKey.EntityKey ignored -> TargetKind.ENTITY;
			case TargetKey.BlockKey ignored -> TargetKind.BLOCK;
			case TargetKey.LocationKey ignored -> TargetKind.LOCATION;
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
