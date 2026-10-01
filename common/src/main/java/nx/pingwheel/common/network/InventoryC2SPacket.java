package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/**
 * Versioned inventory preview and tracking requests. {@code HELLO} is the
 * epoch-zero handshake; every other kind runs under the negotiated nonzero
 * epoch and carries a non-negative request id. {@code OPEN} binds that request
 * to one bounded server target for preview without exposing a player UUID,
 * {@code CLOSE} releases the preview request, {@code RESYNC} repairs tracking
 * for an optional marker id, and {@code SELECT} turns one inventory entry key
 * into a bounded query whose authoritative target is derived server-side from
 * the request. No trusted item count or player identity is part of this family.
 *
 * <p>All three text fields use the shared 256-cap UTF id encoding, so a request
 * frame is bounded by construction; a wrong protocol, an unknown enum name, a
 * negative request id, or trailing bytes is rejected by decode and falls back
 * to the corrupt no-arg instance through {@link #readSafe}.
 */
public record InventoryC2SPacket(Kind kind, int protocol, long epoch, long requestId, Target target,
	MarkerId markerId, String entryKey, String itemId, String pingType) implements IPacket {
	public enum Kind { HELLO, OPEN, CLOSE, RESYNC, SELECT }

	public static final int VERSION = 1;
	public static final int MAX_ID_BYTES = MarkerPacketCodec.MAX_ID_LENGTH;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(C2S_NAMESPACE, "inventory-v1");
	public static final Type<InventoryC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public InventoryC2SPacket {
		validateBoundedText(entryKey, "inventory entry key");
		validateBoundedText(itemId, "inventory item id");
		validateBoundedText(pingType, "inventory ping type");
	}

	public InventoryC2SPacket() { this(null, 0, 0, 0, null, null, null, null, null); }
	public InventoryC2SPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private InventoryC2SPacket(InventoryC2SPacket packet) {
		this(packet.kind, packet.protocol, packet.epoch, packet.requestId, packet.target, packet.markerId,
			packet.entryKey, packet.itemId, packet.pingType);
	}

	public static InventoryC2SPacket hello() {
		return new InventoryC2SPacket(Kind.HELLO, VERSION, 0, 0, null, null, null, null, null);
	}

	public static InventoryC2SPacket open(long epoch, long requestId, Target target) {
		return new InventoryC2SPacket(Kind.OPEN, VERSION, epoch, requestId, target, null, null, null, null);
	}

	public static InventoryC2SPacket close(long epoch, long requestId) {
		return new InventoryC2SPacket(Kind.CLOSE, VERSION, epoch, requestId, null, null, null, null, null);
	}

	public static InventoryC2SPacket resync(long epoch, long requestId) {
		return resync(epoch, requestId, null);
	}

	/** {@code markerId} selects one tracked Ping; {@code null} resynchronizes the request session. */
	public static InventoryC2SPacket resync(long epoch, long requestId, MarkerId markerId) {
		return new InventoryC2SPacket(Kind.RESYNC, VERSION, epoch, requestId, null, markerId, null, null, null);
	}

	/** The server resolves the authoritative target and count from the request-bound entry key. */
	public static InventoryC2SPacket select(long epoch, long requestId, String entryKey, String itemId, String pingType) {
		return new InventoryC2SPacket(Kind.SELECT, VERSION, epoch, requestId, null, null, entryKey, itemId, pingType);
	}

	@Override public void write(FriendlyByteBuf buf) {
		MarkerPacketCodec.writeEnum(buf, kind);
		buf.writeVarInt(protocol);
		buf.writeLong(epoch);
		switch (kind) {
			case HELLO -> {}
			case OPEN -> { buf.writeLong(requestId); MarkerPacketCodec.writeTarget(buf, target); }
			case CLOSE -> buf.writeLong(requestId);
			case RESYNC -> {
				buf.writeLong(requestId);
				MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.ofNullable(markerId));
			}
			case SELECT -> {
				buf.writeLong(requestId);
				buf.writeUtf(entryKey, MAX_ID_BYTES);
				buf.writeUtf(itemId, MAX_ID_BYTES);
				buf.writeUtf(pingType, MAX_ID_BYTES);
			}
		}
	}

	private static InventoryC2SPacket decode(FriendlyByteBuf buf) {
		Kind kind = MarkerPacketCodec.readEnum(buf, Kind.class);
		int protocol = buf.readVarInt();
		if (protocol != VERSION) {
			throw new IllegalArgumentException("Unsupported inventory protocol");
		}
		long epoch = buf.readLong();
		long requestId = 0;
		Target target = null;
		MarkerId markerId = null;
		String entryKey = null;
		String itemId = null;
		String pingType = null;
		switch (kind) {
			case HELLO -> {}
			case OPEN -> { requestId = readRequestId(buf); target = MarkerPacketCodec.readTarget(buf); }
			case CLOSE -> requestId = readRequestId(buf);
			case RESYNC -> { requestId = readRequestId(buf); markerId = MarkerPacketCodec.readOptionalMarkerId(buf).orElse(null); }
			case SELECT -> {
				requestId = readRequestId(buf);
				entryKey = buf.readUtf(MAX_ID_BYTES);
				itemId = buf.readUtf(MAX_ID_BYTES);
				pingType = buf.readUtf(MAX_ID_BYTES);
			}
		}
		if (buf.isReadable()) {
			throw new IllegalArgumentException("Trailing inventory request");
		}
		return new InventoryC2SPacket(kind, protocol, epoch, requestId, target, markerId, entryKey, itemId, pingType);
	}

	private static long readRequestId(FriendlyByteBuf buf) {
		long value = buf.readLong();
		if (value < 0) {
			throw new IllegalArgumentException("Negative inventory request id");
		}
		return value;
	}

	private static void validateBoundedText(String value, String name) {
		if (value == null) {
			return;
		}
		if (value.length() > MAX_ID_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
			throw new IllegalArgumentException(name);
		}
	}

	@Override public boolean isCorrupt() {
		if (kind == null || protocol != VERSION) {
			return true;
		}
		if (kind == Kind.HELLO) {
			return epoch != 0 || requestId != 0 || target != null || markerId != null
				|| entryKey != null || itemId != null || pingType != null;
		}
		if (epoch == 0 || requestId < 0) {
			return true;
		}
		return switch (kind) {
			case OPEN -> target == null || markerId != null || entryKey != null || itemId != null || pingType != null;
			case CLOSE -> target != null || markerId != null || entryKey != null || itemId != null || pingType != null;
			case RESYNC -> target != null || entryKey != null || itemId != null || pingType != null;
			case SELECT -> target != null || markerId != null || entryKey == null || entryKey.isBlank()
				|| itemId == null || itemId.isBlank() || pingType == null || pingType.isBlank();
			case HELLO -> true;
		};
	}

	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public @NotNull Type<InventoryC2SPacket> type() { return PACKET_TYPE; }
	public static InventoryC2SPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, InventoryC2SPacket.class); }
}
