package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/**
 * Version-two inventory preview and tracking requests. {@code HELLO} is the
 * epoch-zero handshake; every other kind runs under the negotiated nonzero
 * epoch and carries a non-negative request id. {@code OPEN} binds that request
 * to one bounded server target for preview without exposing a player UUID: a
 * native block target or an uncommitted external provider candidate carrying
 * its provider locator and block-entity classification, never a committed
 * external target, entity, or location. {@code CLOSE} releases the preview
 * request, {@code RESYNC} repairs tracking for a required marker id, and
 * {@code SELECT} turns one inventory entry key into a bounded query whose
 * authoritative target is derived server-side from the request. No trusted
 * item count or player identity is part of this family.
 *
 * <p>The request frame grammar and its 4096-byte bound are the current
 * version-two grammar. SELECT and RESYNC carry an explicit resource lane; OPEN/CLOSE
 * remain shared request operations. The {@code SELECT} text fields use the shared 256-cap
 * UTF id encoding, so a request frame is bounded by construction; a wrong
 * protocol, an unknown enum name, a negative request id, an overlong target
 * field, an invalid boolean, a non-canonical number, or trailing bytes is
 * rejected by decode and falls back to the corrupt no-arg instance through
 * {@link #readSafe}.
 */
@NotNullByDefault
public record InventoryC2SPacket(@Nullable Kind kind, int protocol, long epoch, long requestId, @Nullable Target target,
	@Nullable MarkerId markerId, @Nullable String entryKey, @Nullable String itemId, @Nullable String pingType, long presentationEpoch, long view,
	@Nullable BlockFace face, long commitId, long baselineId, long stateRevision, InventoryResourceKind resourceKind) implements IPacket {
	public enum Kind { HELLO, OPEN, CLOSE, RESYNC, SELECT }

	public static final int VERSION = 2;
	public static final int MAX_ID_BYTES = MarkerPacketCodec.MAX_ID_LENGTH;
	public static final int MAX_FRAME_BYTES = 4096;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(C2S_NAMESPACE, "inventory-v2");
	public static final Type<InventoryC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public InventoryC2SPacket {
		Objects.requireNonNull(resourceKind, "resourceKind");
		validateBoundedText(entryKey, "inventory entry key");
		validateBoundedText(itemId, "inventory item id");
		validateBoundedText(pingType, "inventory ping type");
	}
	/** Java source bridge only: the old full constructor is always ITEM, never a v1 wire fallback. */
	public InventoryC2SPacket(@Nullable Kind kind, int protocol, long epoch, long requestId, @Nullable Target target,
		@Nullable MarkerId markerId, @Nullable String entryKey, @Nullable String itemId, @Nullable String pingType,
		long presentationEpoch, long view, @Nullable BlockFace face, long commitId, long baselineId, long stateRevision) {
		this(kind, protocol, epoch, requestId, target, markerId, entryKey, itemId, pingType,
			presentationEpoch, view, face, commitId, baselineId, stateRevision, InventoryResourceKind.ITEM);
	}

	public InventoryC2SPacket() { this(null, 0, 0, 0, null, null, null, null, null); }
	/** Kept for constructing corrupt/legacy-shaped fixtures; runtime OPEN always requires a face and an openable target. */
	public InventoryC2SPacket(@Nullable Kind kind, int protocol, long epoch, long requestId, @Nullable Target target,
		@Nullable MarkerId markerId, @Nullable String entryKey, @Nullable String itemId, @Nullable String pingType) {
		this(kind, protocol, epoch, requestId, target, markerId, entryKey, itemId, pingType, 0, 0, null, 0, 0, 0);
	}
	public InventoryC2SPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private InventoryC2SPacket(InventoryC2SPacket packet) {
		this(packet.kind, packet.protocol, packet.epoch, packet.requestId, packet.target, packet.markerId,
			packet.entryKey, packet.itemId, packet.pingType, packet.presentationEpoch, packet.view, packet.face,
			packet.commitId, packet.baselineId, packet.stateRevision, packet.resourceKind);
	}

	public static InventoryC2SPacket hello() {
		return new InventoryC2SPacket(Kind.HELLO, VERSION, 0, 0, null, null, null, null, null);
	}

	public static InventoryC2SPacket open(long epoch, long requestId, Target target) {
		return new InventoryC2SPacket(Kind.OPEN, VERSION, epoch, requestId, target, null, null, null, null);
	}
	public static InventoryC2SPacket open(long epoch, long presentationEpoch, long view, long requestId, Target target, BlockFace face) {
		return new InventoryC2SPacket(Kind.OPEN, VERSION, epoch, requestId, target, null, null, null, null,
			presentationEpoch, view, face, 0, 0, 0);
	}
	public static InventoryC2SPacket select(long epoch, long presentationEpoch, long view, long commitId, long requestId,
		long baselineId, long stateRevision, String entryKey, String itemPingType) {
		return select(InventoryResourceKind.ITEM, epoch, presentationEpoch, view, commitId, requestId, baselineId, stateRevision, entryKey, itemPingType);
	}
	public static InventoryC2SPacket select(InventoryResourceKind resourceKind, long epoch, long presentationEpoch, long view, long commitId, long requestId,
		long baselineId, long stateRevision, String entryKey, String itemPingType) {
		return new InventoryC2SPacket(Kind.SELECT, VERSION, epoch, requestId, null, null, entryKey, null, itemPingType,
			presentationEpoch, view, null, commitId, baselineId, stateRevision, resourceKind);
	}
	public InventoryC2SPacket stamp(long presentationEpoch, long view) {
		return new InventoryC2SPacket(kind, protocol, epoch, requestId, target, markerId, entryKey, itemId, pingType,
			presentationEpoch, view, face, commitId, baselineId, stateRevision, resourceKind);
	}

	public static InventoryC2SPacket close(long epoch, long requestId) {
		return new InventoryC2SPacket(Kind.CLOSE, VERSION, epoch, requestId, null, null, null, null, null);
	}

	/** {@code markerId} selects one tracked Ping. */
	public static InventoryC2SPacket resync(long epoch, long requestId, @NotNull MarkerId markerId) {
		return resync(InventoryResourceKind.ITEM, epoch, requestId, markerId);
	}
	public static InventoryC2SPacket resync(InventoryResourceKind resourceKind, long epoch, long requestId, MarkerId markerId) {
		return new InventoryC2SPacket(Kind.RESYNC, VERSION, epoch, requestId, null,
			Objects.requireNonNull(markerId, "inventory resync marker"), null, null, null, 0, 0, null, 0, 0, 0, resourceKind);
	}

	/** The server resolves the authoritative target and count from the request-bound entry key. */
	public static InventoryC2SPacket select(long epoch, long requestId, String entryKey, String itemId, String pingType) {
		return new InventoryC2SPacket(Kind.SELECT, VERSION, epoch, requestId, null, null, entryKey, itemId, pingType);
	}

	@Override public void write(FriendlyByteBuf buf) {
		if (kind == Kind.RESYNC && markerId == null) throw new IllegalArgumentException("Missing inventory resync marker");
		MarkerPacketCodec.writeEnum(buf, kind);
		buf.writeVarInt(protocol);
		buf.writeLong(epoch);
		buf.writeLong(presentationEpoch);
		buf.writeLong(view);
		if (kind == Kind.SELECT || kind == Kind.RESYNC) buf.writeByte(resourceKind.wireId());
		switch (kind) {
			case HELLO -> {}
			case OPEN -> { buf.writeLong(requestId); MarkerPacketCodec.writeTarget(buf, target); MarkerPacketCodec.writeEnum(buf, face); }
			case CLOSE -> buf.writeLong(requestId);
			case RESYNC -> {
				buf.writeLong(requestId);
				buf.writeBoolean(true);
				MarkerPacketCodec.writeMarkerId(buf, markerId);
			}
			case SELECT -> {
				buf.writeLong(requestId);
				buf.writeLong(commitId);
				buf.writeLong(baselineId);
				buf.writeLong(stateRevision);
				buf.writeUtf(entryKey, MAX_ID_BYTES);
				buf.writeUtf(pingType, MAX_ID_BYTES);
			}
		}
	}

	private static InventoryC2SPacket decode(FriendlyByteBuf buf) {
		if (buf.readableBytes() > MAX_FRAME_BYTES) throw new IllegalArgumentException("inventory request frame");
		Kind kind = InventoryWireCodec.readEnum(buf, Kind.class);
		int protocol = StrictPacketCodec.readVarInt(buf);
		if (protocol != VERSION) {
			throw new IllegalArgumentException("Unsupported inventory protocol");
		}
		long epoch = buf.readLong();
		long presentationEpoch = buf.readLong(), view = buf.readLong(), commitId = 0, baselineId = 0, stateRevision = 0;
		InventoryResourceKind resourceKind = kind == Kind.SELECT || kind == Kind.RESYNC
			? InventoryResourceKind.fromWireId(buf.readUnsignedByte()) : InventoryResourceKind.ITEM;
		@Nullable BlockFace face = null;
		long requestId = 0;
		@Nullable Target target = null;
		@Nullable MarkerId markerId = null;
		@Nullable String entryKey = null;
		@Nullable String itemId = null;
		@Nullable String pingType = null;
		switch (kind) {
			case HELLO -> {}
			case OPEN -> { requestId = readRequestId(buf); target = readOpenTarget(buf); face = InventoryWireCodec.readEnum(buf, BlockFace.class); }
			case CLOSE -> requestId = readRequestId(buf);
			case RESYNC -> {
				requestId = readRequestId(buf);
				if (!StrictPacketCodec.readBoolean(buf)) throw new IllegalArgumentException("Missing inventory resync marker");
				markerId = new MarkerId(buf.readLong());
			}
			case SELECT -> {
				requestId = readRequestId(buf);
				commitId = readRequestId(buf);
				baselineId = readRequestId(buf);
				stateRevision = readRequestId(buf);
				entryKey = InventoryWireCodec.readUtf(buf, MAX_ID_BYTES);
				pingType = InventoryWireCodec.readUtf(buf, MAX_ID_BYTES);
			}
		}
		if (buf.isReadable()) {
			throw new IllegalArgumentException("Trailing inventory request");
		}
		return new InventoryC2SPacket(kind, protocol, epoch, requestId, target, markerId, entryKey, itemId, pingType,
			presentationEpoch, view, face, commitId, baselineId, stateRevision, resourceKind);
	}

	private static long readRequestId(FriendlyByteBuf buf) {
		long value = buf.readLong();
		if (value < 0) {
			throw new IllegalArgumentException("Negative inventory request id");
		}
		return value;
	}
	/**
	 * Strict version-two {@code OPEN} target grammar: one native block or one
	 * uncommitted external provider candidate with bounded provider, expected
	 * registry, opaque locator, and strict classification. Entity, location,
	 * committed external, unknown variant, overlong, and non-canonical fields
	 * are rejected instead of being reinterpreted through the permissive
	 * marker target codec.
	 */
	private static Target readOpenTarget(FriendlyByteBuf buf) {
		if (InventoryWireCodec.readEnum(buf, TargetKind.class) != TargetKind.BLOCK)
			throw new IllegalArgumentException("inventory requires a block target");
		String dimension = InventoryWireCodec.readUtf(buf, MAX_ID_BYTES);
		int variant = StrictPacketCodec.readVarInt(buf);
		if (variant == MarkerPacketCodec.BLOCK_TARGET_STANDARD_TAG) {
			return new Target.BlockTarget(dimension, buf.readInt(), buf.readInt(), buf.readInt(), InventoryWireCodec.readUtf(buf, MAX_ID_BYTES));
		}
		if (variant == MarkerPacketCodec.BLOCK_TARGET_EXTERNAL_TAG) {
			String providerId = InventoryWireCodec.readUtf(buf, MAX_ID_BYTES);
			String stableTargetId = InventoryWireCodec.readUtf(buf, MAX_ID_BYTES);
			if (!stableTargetId.isEmpty())
				throw new IllegalArgumentException("inventory external target must be an uncommitted candidate");
			String expectedBlockRegistryId = InventoryWireCodec.readUtf(buf, MAX_ID_BYTES);
			String providerLocator = InventoryWireCodec.readUtf(buf, MarkerPacketCodec.MAX_EXTERNAL_PROVIDER_LOCATOR_LENGTH);
			boolean hasBlockEntity = StrictPacketCodec.readBoolean(buf);
			return Target.ExternalBlockTarget.candidate(dimension, providerId, expectedBlockRegistryId, providerLocator, hasBlockEntity);
		}
		throw new IllegalArgumentException("Unknown inventory block target variant");
	}

	/** A version-two OPEN target is one native block or one uncommitted external candidate. */
	private static boolean openTarget(@Nullable Target target) {
		return target instanceof Target.BlockTarget
			|| (target instanceof Target.ExternalBlockTarget external && external.isCandidate());
	}

	private static void validateBoundedText(@Nullable String value, String name) {
		if (value == null) {
			return;
		}
		if (value.length() > MAX_ID_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
			throw new IllegalArgumentException(name);
		}
	}

	@Override public boolean isCorrupt() {
		if (kind != Kind.SELECT && kind != Kind.RESYNC && resourceKind != InventoryResourceKind.ITEM) return true;
		if (kind == null || protocol != VERSION) {
			return true;
		}
		if (kind == Kind.HELLO) {
			return epoch != 0 || requestId != 0 || target != null || markerId != null
				|| entryKey != null || itemId != null || pingType != null || face != null || presentationEpoch != 0 || view != 0
				|| commitId != 0 || baselineId != 0 || stateRevision != 0;
		}
		if (epoch == 0 || requestId < 0 || view < 0 || commitId < 0 || baselineId < 0 || stateRevision < 0) {
			return true;
		}
		return switch (kind) {
			case OPEN -> !openTarget(target) || face == null || markerId != null || entryKey != null || itemId != null || pingType != null || commitId != 0 || baselineId != 0 || stateRevision != 0;
			case CLOSE -> target != null || markerId != null || entryKey != null || itemId != null || pingType != null || face != null || commitId != 0 || baselineId != 0 || stateRevision != 0;
			case RESYNC -> markerId == null || target != null || entryKey != null || itemId != null || pingType != null || face != null || commitId != 0 || baselineId != 0 || stateRevision != 0;
			case SELECT -> target != null || markerId != null || entryKey == null || entryKey.isBlank()
				|| itemId != null || face != null || commitId <= 0 || baselineId <= 0 || stateRevision <= 0 || pingType == null || pingType.isBlank();
			case HELLO -> true;
		};
	}

	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public @NotNull Type<InventoryC2SPacket> type() { return PACKET_TYPE; }
	public static InventoryC2SPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, InventoryC2SPacket.class); }
}
