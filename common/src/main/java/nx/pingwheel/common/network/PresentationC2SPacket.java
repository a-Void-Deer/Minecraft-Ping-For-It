package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationIds;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/** Versioned presentation negotiation and marker intents; old routes keep their old shape. */
public record PresentationC2SPacket(Kind kind, int protocol, long epoch, long subscription,
	Map<String, Integer> schemas, Set<String> fields, long requestId, Target target,
	String pingType, MarkerId markerId) implements IPacket {
	public enum Kind { HELLO, SUBSCRIBE, CREATE, REMOVE }
	public static final int VERSION = 2;
	public static final int MAX_ADAPTERS = 32;
	public static final int MAX_FIELDS = 256;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(C2S_NAMESPACE, "presentation-v2");
	public static final Type<PresentationC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public PresentationC2SPacket {
		schemas = Map.copyOf(schemas);
		fields = Set.copyOf(fields);
		if (schemas.size() > MAX_ADAPTERS || fields.size() > MAX_FIELDS) throw new IllegalArgumentException("presentation capabilities");
	}
	public PresentationC2SPacket() { this(null, 0, 0, 0, Map.of(), Set.of(), 0, null, null, null); }
	public PresentationC2SPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private PresentationC2SPacket(PresentationC2SPacket p) {
		this(p.kind, p.protocol, p.epoch, p.subscription, p.schemas, p.fields, p.requestId, p.target, p.pingType, p.markerId);
	}
	public static PresentationC2SPacket hello(Map<String, Integer> schemas) {
		return new PresentationC2SPacket(Kind.HELLO, VERSION, 0, 0, schemas, Set.of(), 0, null, null, null);
	}
	public static PresentationC2SPacket subscribe(long epoch, long generation, Set<String> fields) {
		return new PresentationC2SPacket(Kind.SUBSCRIBE, VERSION, epoch, generation, Map.of(), fields, 0, null, null, null);
	}
	public static PresentationC2SPacket create(long epoch, long requestId, Target target, String pingType) {
		return new PresentationC2SPacket(Kind.CREATE, VERSION, epoch, 0, Map.of(), Set.of(), requestId, target, pingType, null);
	}
	public static PresentationC2SPacket remove(long epoch, MarkerId id) {
		return new PresentationC2SPacket(Kind.REMOVE, VERSION, epoch, 0, Map.of(), Set.of(), 0, null, null, id);
	}
	@Override public void write(FriendlyByteBuf buf) {
		buf.writeEnum(kind); buf.writeVarInt(protocol); buf.writeLong(epoch); buf.writeLong(subscription);
		switch (kind) {
			case HELLO -> {
				buf.writeVarInt(schemas.size());
				schemas.forEach((id, schema) -> { buf.writeUtf(id, 193); buf.writeVarInt(schema); });
			}
			case SUBSCRIBE -> { buf.writeVarInt(fields.size()); fields.forEach(id -> buf.writeUtf(id, 193)); }
			case CREATE -> { buf.writeLong(requestId); MarkerPacketCodec.writeTarget(buf, target); buf.writeUtf(pingType, 256); }
			case REMOVE -> MarkerPacketCodec.writeMarkerId(buf, markerId);
		}
	}
	private static PresentationC2SPacket decode(FriendlyByteBuf buf) {
		Kind kind = buf.readEnum(Kind.class);
		int protocol = buf.readVarInt(); long epoch = buf.readLong(); long subscription = buf.readLong();
		Map<String, Integer> schemas = new LinkedHashMap<>(); Set<String> fields = new LinkedHashSet<>();
		long request = 0; Target target = null; String ping = null; MarkerId marker = null;
		switch (kind) {
			case HELLO -> {
				int count = bounded(buf.readVarInt(), MAX_ADAPTERS);
				for (int i = 0; i < count; i++) {
					String id = buf.readUtf(193); PresentationIds.validate(id);
					int schema = bounded(buf.readVarInt(), 255);
					if (schema == 0 || schemas.putIfAbsent(id, schema) != null) throw new IllegalArgumentException("adapter schema");
				}
			}
			case SUBSCRIBE -> {
				int count = bounded(buf.readVarInt(), MAX_FIELDS);
				for (int i = 0; i < count; i++) { String id = buf.readUtf(193); PresentationIds.validate(id); if (!fields.add(id)) throw new IllegalArgumentException("duplicate field"); }
			}
			case CREATE -> { request = buf.readLong(); target = MarkerPacketCodec.readTarget(buf); ping = buf.readUtf(256); }
			case REMOVE -> marker = MarkerPacketCodec.readMarkerId(buf);
		}
		if (buf.isReadable()) throw new IllegalArgumentException("trailing presentation request");
		return new PresentationC2SPacket(kind, protocol, epoch, subscription, schemas, fields, request, target, ping, marker);
	}
	static int bounded(int value, int maximum) {
		if (value < 0 || value > maximum) throw new IllegalArgumentException("presentation count");
		return value;
	}
	@Override public boolean isCorrupt() {
		return kind == null || protocol != VERSION || (kind != Kind.HELLO && epoch == 0)
			|| (kind == Kind.SUBSCRIBE && subscription < 1)
			|| (kind == Kind.CREATE && (requestId < 0 || target == null || pingType == null || pingType.isBlank()))
			|| (kind == Kind.REMOVE && markerId == null);
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public @NotNull Type<PresentationC2SPacket> type() { return PACKET_TYPE; }
	public static PresentationC2SPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, PresentationC2SPacket.class); }
}
