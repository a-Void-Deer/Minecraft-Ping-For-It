package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationCodec;
import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/**
 * Versioned presentation negotiation and marker intents. The client advertises
 * only its manifest schemas; there is no client subscription and no
 * client-provided field set. A create intent carries at most one bounded
 * property observation per property ref.
 */
public record PresentationC2SPacket(Kind kind, int protocol, long epoch,
	Map<String, Integer> schemas, long requestId, Target target, String pingType, MarkerId markerId,
	List<PresentationPropertyIntent> properties) implements IPacket {
	public enum Kind { HELLO, CREATE, REMOVE }
	public static final int VERSION = 5;
	public static final int MAX_ADAPTERS = 32;
	public static final int MAX_FIELDS = 256;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(C2S_NAMESPACE, "presentation-v5");
	public static final Type<PresentationC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public PresentationC2SPacket {
		schemas = Map.copyOf(schemas);
		properties = List.copyOf(properties);
		if (schemas.size() > MAX_ADAPTERS || properties.size() > PresentationCodec.MAX_PROPERTIES)
			throw new IllegalArgumentException("presentation capabilities");
		Set<PresentationPropertyRef> refs = new HashSet<>();
		for (PresentationPropertyIntent intent : properties) {
			if (intent == null || !refs.add(intent.ref()))
				throw new IllegalArgumentException("duplicate presentation property");
		}
	}
	public PresentationC2SPacket() { this(null, 0, 0, Map.of(), 0, null, null, null, List.of()); }
	public PresentationC2SPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private PresentationC2SPacket(PresentationC2SPacket p) {
		this(p.kind, p.protocol, p.epoch, p.schemas, p.requestId, p.target, p.pingType, p.markerId, p.properties);
	}
	public static PresentationC2SPacket hello(Map<String, Integer> schemas) {
		return new PresentationC2SPacket(Kind.HELLO, VERSION, 0, schemas, 0, null, null, null, List.of());
	}
	public static PresentationC2SPacket create(long epoch, long requestId, Target target, String pingType) {
		return create(epoch, requestId, target, pingType, List.of());
	}
	public static PresentationC2SPacket create(long epoch, long requestId, Target target, String pingType,
		List<PresentationPropertyIntent> properties) {
		return new PresentationC2SPacket(Kind.CREATE, VERSION, epoch, Map.of(), requestId, target, pingType, null, properties);
	}
	public static PresentationC2SPacket remove(long epoch, MarkerId id) {
		return new PresentationC2SPacket(Kind.REMOVE, VERSION, epoch, Map.of(), 0, null, null, id, List.of());
	}
	@Override public void write(FriendlyByteBuf buf) {
		buf.writeEnum(kind); buf.writeVarInt(protocol); buf.writeLong(epoch);
		switch (kind) {
			case HELLO -> {
				buf.writeVarInt(schemas.size());
				schemas.forEach((id, schema) -> { buf.writeUtf(id, 193); buf.writeVarInt(schema); });
			}
			case CREATE -> {
				buf.writeLong(requestId); MarkerPacketCodec.writeTarget(buf, target); buf.writeUtf(pingType, 256);
				buf.writeVarInt(properties.size());
				properties.forEach(intent -> PresentationCodec.writePropertyIntent(buf, intent));
			}
			case REMOVE -> MarkerPacketCodec.writeMarkerId(buf, markerId);
		}
	}
	private static PresentationC2SPacket decode(FriendlyByteBuf buf) {
		Kind kind = buf.readEnum(Kind.class);
		int protocol = buf.readVarInt(); long epoch = buf.readLong();
		Map<String, Integer> schemas = new LinkedHashMap<>();
		long request = 0; Target target = null; String ping = null; MarkerId marker = null;
		List<PresentationPropertyIntent> properties = new ArrayList<>();
		switch (kind) {
			case HELLO -> {
				int count = bounded(buf.readVarInt(), MAX_ADAPTERS);
				for (int i = 0; i < count; i++) {
					String id = buf.readUtf(193); PresentationIds.validate(id);
					int schema = bounded(buf.readVarInt(), 255);
					if (schema == 0 || schemas.putIfAbsent(id, schema) != null) throw new IllegalArgumentException("adapter schema");
				}
			}
			case CREATE -> {
				request = buf.readLong(); target = MarkerPacketCodec.readTarget(buf); ping = buf.readUtf(256);
				int count = bounded(buf.readVarInt(), PresentationCodec.MAX_PROPERTIES);
				for (int i = 0; i < count; i++) properties.add(PresentationCodec.readPropertyIntent(buf));
			}
			case REMOVE -> marker = MarkerPacketCodec.readMarkerId(buf);
		}
		if (buf.isReadable()) throw new IllegalArgumentException("trailing presentation request");
		return new PresentationC2SPacket(kind, protocol, epoch, schemas, request, target, ping, marker, properties);
	}
	static int bounded(int value, int maximum) {
		if (value < 0 || value > maximum) throw new IllegalArgumentException("presentation count");
		return value;
	}
	@Override public boolean isCorrupt() {
		return kind == null || protocol != VERSION || schemas == null || properties == null
			|| (kind != Kind.HELLO && epoch == 0)
			|| (kind == Kind.CREATE && (requestId < 0 || target == null || pingType == null || pingType.isBlank()
				|| pingType.length() > 256))
			|| (kind == Kind.REMOVE && markerId == null);
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public @NotNull Type<PresentationC2SPacket> type() { return PACKET_TYPE; }
	public static PresentationC2SPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, PresentationC2SPacket.class); }
}
