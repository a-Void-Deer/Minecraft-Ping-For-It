package nx.pingwheel.common.network;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.preview.PresentationPreviewLimits;

/** A bounded one-shot optimization hint, not subscription or authority. */
public record PresentationPreviewC2SPacket(Kind kind, int protocol, long epoch, long view, long requestId,
	Target target, String targetTypeId, String adapterId, Set<String> fields) implements IPacket {
	public enum Kind { READ, CANCEL }
	public static final int VERSION = 1;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath("pingforit-c2s", "presentation-preview-v1");
	public static final Type<PresentationPreviewC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);
	public PresentationPreviewC2SPacket {
		fields = Set.copyOf(fields);
		if (fields.size() > PresentationPreviewLimits.MAX_FIELDS) throw new IllegalArgumentException("preview fields");
		fields.forEach(PresentationIds::validate);
	}
	public PresentationPreviewC2SPacket() { this(null, 0, 0, 0, 0, null, null, null, Set.of()); }
	public PresentationPreviewC2SPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private PresentationPreviewC2SPacket(PresentationPreviewC2SPacket packet) {
		this(packet.kind, packet.protocol, packet.epoch, packet.view, packet.requestId, packet.target,
			packet.targetTypeId, packet.adapterId, packet.fields);
	}
	public static PresentationPreviewC2SPacket read(long epoch, long view, long id, Target target,
		String type, String adapter, Set<String> fields) {
		return new PresentationPreviewC2SPacket(Kind.READ, VERSION, epoch, view, id, target, type, adapter, fields);
	}
	public static PresentationPreviewC2SPacket cancel(long epoch, long view, long id) {
		return new PresentationPreviewC2SPacket(Kind.CANCEL, VERSION, epoch, view, id, null, null, null, Set.of());
	}
	@Override public void write(FriendlyByteBuf buf) {
		if (isCorrupt()) throw new IllegalArgumentException("corrupt preview request");
		int start = buf.writerIndex();
		buf.writeEnum(kind); buf.writeVarInt(protocol); buf.writeLong(epoch); buf.writeLong(view); buf.writeLong(requestId);
		if (kind == Kind.READ) {
			MarkerPacketCodec.writeTarget(buf, target); buf.writeUtf(targetTypeId, 193); buf.writeUtf(adapterId, 193);
			buf.writeVarInt(fields.size()); fields.stream().sorted().forEach(id -> buf.writeUtf(id, 193));
		}
		if (buf.writerIndex() - start > PresentationPreviewLimits.MAX_REQUEST_BYTES)
			throw new IllegalArgumentException("preview request frame");
	}
	private static PresentationPreviewC2SPacket decode(FriendlyByteBuf buf) {
		if (buf.readableBytes() > PresentationPreviewLimits.MAX_REQUEST_BYTES) throw new IllegalArgumentException("preview request frame");
		int kindId = ServerConfigVarNumbers.readInt(buf);
		if (kindId >= Kind.values().length) throw new IllegalArgumentException("preview request kind");
		Kind kind = Kind.values()[kindId]; int version = ServerConfigVarNumbers.readInt(buf);
		if (version != VERSION) throw new IllegalArgumentException("preview protocol");
		long epoch = buf.readLong(), view = buf.readLong(), id = buf.readLong();
		Target target = null; String type = null, adapter = null; Set<String> fields = new LinkedHashSet<>();
		if (kind == Kind.READ) {
			target = readTarget(buf); type = StrictPacketCodec.readUtf(buf, 193); adapter = StrictPacketCodec.readUtf(buf, 193);
			int count = ServerConfigVarNumbers.readInt(buf);
			if (count < 1 || count > PresentationPreviewLimits.MAX_FIELDS) throw new IllegalArgumentException("preview fields");
			for (int i = 0; i < count; i++) if (!fields.add(StrictPacketCodec.readUtf(buf, 193))) throw new IllegalArgumentException("duplicate preview field");
		}
		if (buf.isReadable()) throw new IllegalArgumentException("trailing preview request");
		return new PresentationPreviewC2SPacket(kind, version, epoch, view, id, target, type, adapter, fields);
	}
	/** Same target grammar as MarkerPacketCodec, with strict numbers only on this route. */
	private static Target readTarget(FriendlyByteBuf buf) {
		TargetKind kind = StrictPacketCodec.readEnum(buf, TargetKind.class);
		String dimension = StrictPacketCodec.readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH);
		return switch (kind) {
			case ENTITY -> {
				EntityLocator.Kind locator = EntityLocator.kindFromWireTag(ServerConfigVarNumbers.readInt(buf));
				yield new Target.EntityTarget(dimension, switch (locator) {
					case UUID -> EntityLocator.uuid(buf.readUUID());
					case RUNTIME_ID -> EntityLocator.runtimeId(ServerConfigVarNumbers.readInt(buf));
				});
			}
			case BLOCK -> switch (ServerConfigVarNumbers.readInt(buf)) {
				case MarkerPacketCodec.BLOCK_TARGET_STANDARD_TAG -> new Target.BlockTarget(dimension,
					buf.readInt(), buf.readInt(), buf.readInt(), StrictPacketCodec.readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH));
				case MarkerPacketCodec.BLOCK_TARGET_EXTERNAL_TAG -> new Target.ExternalBlockTarget(dimension,
					StrictPacketCodec.readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH), StrictPacketCodec.readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH),
					StrictPacketCodec.readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH),
					StrictPacketCodec.readUtf(buf, MarkerPacketCodec.MAX_EXTERNAL_PROVIDER_LOCATOR_LENGTH), StrictPacketCodec.readBoolean(buf));
				default -> throw new IllegalArgumentException("preview block variant");
			};
			case LOCATION -> new Target.LocationTarget(dimension, buf.readDouble(), buf.readDouble(), buf.readDouble());
		};
	}
	@Override public boolean isCorrupt() {
		if (kind == null || protocol != VERSION || epoch == 0 || view < 1 || requestId < 1) return true;
		if (kind == Kind.CANCEL) return target != null || adapterId != null || targetTypeId != null || !fields.isEmpty();
		try { PresentationIds.validate(adapterId); }
		catch (RuntimeException invalid) { return true; }
		return target == null || !PresentationSettings.isKnownTargetType(targetTypeId) || fields.isEmpty();
	}
	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public Type<PresentationPreviewC2SPacket> type() { return PACKET_TYPE; }
	public static PresentationPreviewC2SPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, PresentationPreviewC2SPacket.class); }
}
