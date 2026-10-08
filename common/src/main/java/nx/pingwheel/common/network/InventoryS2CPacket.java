package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerRejectReason;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/**
 * Versioned inventory preview and tracking responses. {@code OFFER} carries the
 * server-selected periods and nothing else; {@code PREVIEW}, {@code SNAPSHOT}
 * and {@code STREAM} carry one immutable entry fragment under an independent
 * epoch/request/marker/baseline identity; {@code STATUS} carries the
 * authoritative inventory state; {@code HEARTBEAT} carries only checksum and
 * watermark progress. Entry text is hard-bounded (256-byte key and item id,
 * 1024-byte label, 4096-byte display JSON), the whole encoded frame is capped
 * at {@link #MAX_FRAME_BYTES}, and decode rejects unknown enums, invalid part
 * ranges, duplicate keys, negative counts, trailing bytes, and oversized frames
 * before any large allocation.
 */
public record InventoryS2CPacket(Kind kind, int protocol, long epoch, long requestId, MarkerId markerId,
	long baselineId, long statusRevision, long watermark, int partIndex, int partCount, boolean completeScan,
	Status status, long checksum, List<Entry> entries, Offer offer, long presentationEpoch, long view,
	Set<String> allowedTypes, long commitId, MarkerRejectReason rejection) implements IPacket {
	public enum Kind { OFFER, POLICY, SELECTED, REJECT, PREVIEW, SNAPSHOT, STREAM, STATUS, HEARTBEAT }
	public enum Status { UPDATING, READY, UNCERTAIN, INCOMPLETE, UNAVAILABLE, INVALID, EXPIRED, COMPONENT_TOO_LONG }

	public static final int VERSION = 2;
	public static final int MAX_ENTRIES = 128;
	public static final int MAX_FRAME_BYTES = 32768;
	public static final int MAX_ENTRY_BYTES = 8192;
	public static final int MAX_KEY_BYTES = 256;
	public static final int MAX_ITEM_ID_BYTES = 256;
	public static final int MAX_LABEL_BYTES = 1024;
	public static final int MAX_DISPLAY_BYTES = 4096;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "inventory-v2");
	public static final Type<InventoryS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/**
	 * Server-selected periods within the shared inventory settings ranges. An
	 * explicit heartbeat zero disables the periodic heartbeat; preview and
	 * tracking use the shared period range, and resynchronization stays within
	 * its positive minimum.
	 */
	public record Offer(int previewPeriodTicks, int trackingPeriodTicks, int resyncMinPeriods, int heartbeatPeriods) {
		public Offer {
			if (periodOutOfRange(previewPeriodTicks) || periodOutOfRange(trackingPeriodTicks)
				|| resyncMinPeriods < InventoryLimits.MIN_RESYNC_PERIODS
				|| heartbeatPeriods < InventoryLimits.MIN_HEARTBEAT_PERIODS
				|| heartbeatPeriods > InventoryLimits.MAX_HEARTBEAT_PERIODS) {
				throw new IllegalArgumentException("inventory offer periods");
			}
		}
	}

	/**
	 * One immutable inventory entry. {@code displayJson} may be absent; the
	 * optional {@code quality} preserves the per-entry component-too-long grey
	 * state against a packet-level uncertain or incomplete status.
	 */
	public record Entry(String key, String itemId, String label, String displayJson, long count, long itemRevision,
		boolean fallback, Status quality, long groupRevision, boolean replaceGroup, String itemPingType) {
		public Entry(String key, String itemId, String label, String displayJson, long count, long itemRevision, boolean fallback, Status quality) {
			this(key, itemId, label, displayJson, count, itemRevision, fallback, quality, 0, false, null);
		}
		public Entry {
			key = requireBoundedText(key, MAX_KEY_BYTES, "inventory entry key");
			itemId = requireBoundedText(itemId, MAX_ITEM_ID_BYTES, "inventory item id");
			label = requireBoundedText(label, MAX_LABEL_BYTES, "inventory entry label");
			if (key.isBlank() || itemId.isBlank()) {
				throw new IllegalArgumentException("blank inventory entry identity");
			}
			if (displayJson != null && utf8Length(displayJson) > MAX_DISPLAY_BYTES) {
				throw new IllegalArgumentException("inventory display json");
			}
			if (count < 0 || itemRevision < 0 || groupRevision < 0 || (replaceGroup && !fallback)
				|| (itemPingType != null && (itemPingType.isBlank() || utf8Length(itemPingType) > 256))) {
				throw new IllegalArgumentException("negative inventory entry count");
			}
		}
	}

	public InventoryS2CPacket {
		allowedTypes = allowedTypes == null ? Set.of() : Set.copyOf(allowedTypes);
		if (allowedTypes.size() > 5 || !nx.pingwheel.common.presentation.PresentationSettings.TARGET_TYPE_IDS.containsAll(allowedTypes))
			throw new IllegalArgumentException("inventory policy types");
		entries = entries == null ? List.of() : List.copyOf(entries);
		if (entries.size() > MAX_ENTRIES) {
			throw new IllegalArgumentException("inventory entry count");
		}
	}
	public InventoryS2CPacket() {
		this(null, 0, 0, 0, null, 0, 0, 0, 0, 1, false, null, 0, List.of(), null);
	}
	public InventoryS2CPacket(Kind kind, int protocol, long epoch, long requestId, MarkerId markerId,
		long baselineId, long statusRevision, long watermark, int partIndex, int partCount, boolean completeScan,
		Status status, long checksum, List<Entry> entries, Offer offer) {
		this(kind, protocol, epoch, requestId, markerId, baselineId, statusRevision, watermark, partIndex, partCount,
			completeScan, status, checksum, entries, offer, 0, 0, Set.of(), 0, null);
	}
	public InventoryS2CPacket(FriendlyByteBuf buf) { this(decode(buf)); }
	private InventoryS2CPacket(InventoryS2CPacket packet) {
		this(packet.kind, packet.protocol, packet.epoch, packet.requestId, packet.markerId, packet.baselineId,
			packet.statusRevision, packet.watermark, packet.partIndex, packet.partCount, packet.completeScan,
			packet.status, packet.checksum, packet.entries, packet.offer, packet.presentationEpoch, packet.view,
			packet.allowedTypes, packet.commitId, packet.rejection);
	}
	public InventoryS2CPacket stamp(long presentationEpoch, long view) {
		return new InventoryS2CPacket(kind, protocol, epoch, requestId, markerId, baselineId, statusRevision, watermark, partIndex, partCount,
			completeScan, status, checksum, entries, offer, presentationEpoch, view, allowedTypes, commitId, rejection);
	}
	public static InventoryS2CPacket policy(long epoch, long presentationEpoch, long view, Set<String> allowedTypes) {
		return new InventoryS2CPacket(Kind.POLICY, VERSION, epoch, 0, null, 0, 0, 0, 0, 1, false, null, 0, List.of(), null,
			presentationEpoch, view, allowedTypes, 0, null);
	}
	public static InventoryS2CPacket selected(long epoch, long requestId, long commitId, MarkerId markerId, MarkerRejectReason rejection) {
		return new InventoryS2CPacket(rejection == null ? Kind.SELECTED : Kind.REJECT, VERSION, epoch, requestId, markerId, 0, 0, 0, 0, 1,
			false, null, 0, List.of(), null, 0, 0, Set.of(), commitId, rejection);
	}

	public static InventoryS2CPacket offer(long epoch, Offer offer) {
		return new InventoryS2CPacket(Kind.OFFER, VERSION, epoch, 0, null, 0, 0, 0, 0, 1, false, null, 0, List.of(), offer);
	}

	/** One fragment of preview, snapshot or stream data. */
	public static InventoryS2CPacket data(Kind kind, long epoch, long requestId, MarkerId markerId, long baselineId,
		long statusRevision, long watermark, int partIndex, int partCount, boolean completeScan, Status status,
		long checksum, List<Entry> entries) {
		if (kind != Kind.PREVIEW && kind != Kind.SNAPSHOT && kind != Kind.STREAM) {
			throw new IllegalArgumentException("inventory data kind");
		}
		return new InventoryS2CPacket(kind, VERSION, epoch, requestId, markerId, baselineId, statusRevision, watermark,
			partIndex, partCount, completeScan, status, checksum, entries, null);
	}

	/** Authoritative status control without item data. */
	public static InventoryS2CPacket status(long epoch, long requestId, MarkerId markerId, long baselineId,
		long statusRevision, long watermark, Status status, long checksum) {
		return new InventoryS2CPacket(Kind.STATUS, VERSION, epoch, requestId, markerId, baselineId, statusRevision,
			watermark, 0, 1, false, status, checksum, List.of(), null);
	}

	/** Progress-only heartbeat; it never resends state. */
	public static InventoryS2CPacket heartbeat(long epoch, long requestId, MarkerId markerId, long baselineId,
		long statusRevision, long watermark, long checksum) {
		return new InventoryS2CPacket(Kind.HEARTBEAT, VERSION, epoch, requestId, markerId, baselineId, statusRevision,
			watermark, 0, 1, false, null, checksum, List.of(), null);
	}

	@Override public void write(FriendlyByteBuf buf) {
		FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer(256, MAX_FRAME_BYTES + MAX_ENTRY_BYTES));
		try {
			writeFrame(frame);
			if (frame.readableBytes() > MAX_FRAME_BYTES) {
				throw new IllegalArgumentException("Inventory frame exceeds budget");
			}
			buf.writeBytes(frame);
		} finally {
			frame.release();
		}
	}

	private void writeFrame(FriendlyByteBuf buf) {
		MarkerPacketCodec.writeEnum(buf, kind);
		buf.writeVarInt(protocol);
		buf.writeLong(epoch);
		buf.writeLong(requestId);
		buf.writeLong(presentationEpoch);
		buf.writeLong(view);
		switch (kind) {
			case POLICY -> { buf.writeVarInt(allowedTypes.size()); for (String type : allowedTypes.stream().sorted().toList()) buf.writeUtf(type, 256); }
			case SELECTED, REJECT -> {
				buf.writeLong(commitId); MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.ofNullable(markerId));
				if (kind == Kind.REJECT) MarkerPacketCodec.writeEnum(buf, rejection);
			}
			case OFFER -> {
				buf.writeVarInt(offer.previewPeriodTicks());
				buf.writeVarInt(offer.trackingPeriodTicks());
				buf.writeVarInt(offer.resyncMinPeriods());
				buf.writeVarInt(offer.heartbeatPeriods());
			}
			case PREVIEW, SNAPSHOT, STREAM -> {
				MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.ofNullable(markerId));
				writeData(buf);
			}
			case STATUS -> {
				MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.ofNullable(markerId));
				buf.writeLong(baselineId);
				buf.writeLong(statusRevision);
				buf.writeLong(watermark);
				MarkerPacketCodec.writeEnum(buf, status);
				buf.writeLong(checksum);
			}
			case HEARTBEAT -> {
				MarkerPacketCodec.writeOptionalMarkerId(buf, Optional.ofNullable(markerId));
				buf.writeLong(baselineId);
				buf.writeLong(statusRevision);
				buf.writeLong(watermark);
				buf.writeLong(checksum);
			}
		}
	}

	private void writeData(FriendlyByteBuf buf) {
		buf.writeLong(baselineId);
		buf.writeLong(statusRevision);
		buf.writeLong(watermark);
		buf.writeVarInt(partIndex);
		buf.writeVarInt(partCount);
		buf.writeBoolean(completeScan);
		MarkerPacketCodec.writeEnum(buf, status);
		buf.writeLong(checksum);
		writeEntries(buf, entries);
	}

	private static InventoryS2CPacket decode(FriendlyByteBuf buf) {
		if (buf.readableBytes() > MAX_FRAME_BYTES) {
			throw new IllegalArgumentException("Inventory frame exceeds budget");
		}
		Kind kind = StrictPacketCodec.readEnum(buf, Kind.class);
		int protocol = StrictPacketCodec.readVarInt(buf);
		if (protocol != VERSION) {
			throw new IllegalArgumentException("Unsupported inventory protocol");
		}
		long epoch = buf.readLong();
		long requestId = buf.readLong();
		long presentationEpoch = buf.readLong(), view = buf.readLong(), commitId = 0;
		Set<String> allowedTypes = Set.of();
		MarkerRejectReason rejection = null;
		if (requestId < 0) {
			throw new IllegalArgumentException("Negative inventory request id");
		}
		MarkerId markerId = null;
		long baselineId = 0;
		long statusRevision = 0;
		long watermark = 0;
		long checksum = 0;
		int partIndex = 0;
		int partCount = 1;
		boolean completeScan = false;
		Status status = null;
		List<Entry> entries = List.of();
		Offer offer = null;
		switch (kind) {
			case POLICY -> {
				int size = StrictPacketCodec.readVarInt(buf); if (size < 0 || size > 5) throw new IllegalArgumentException("inventory policy size");
				Set<String> types = new HashSet<>();
				for (int i = 0; i < size; i++) if (!types.add(StrictPacketCodec.readUtf(buf, 256))) throw new IllegalArgumentException("duplicate policy type");
				allowedTypes = Set.copyOf(types);
			}
			case SELECTED, REJECT -> {
				commitId = buf.readLong(); markerId = readMarkerId(buf);
				if (kind == Kind.REJECT) rejection = StrictPacketCodec.readEnum(buf, MarkerRejectReason.class);
			}
			case OFFER -> offer = readOffer(buf);
			case PREVIEW, SNAPSHOT, STREAM -> {
				markerId = readMarkerId(buf);
				baselineId = buf.readLong();
				statusRevision = buf.readLong();
				watermark = buf.readLong();
				partIndex = StrictPacketCodec.readVarInt(buf);
				partCount = StrictPacketCodec.readVarInt(buf);
				if (partIndex < 0 || partCount < 1 || partIndex >= partCount) {
					throw new IllegalArgumentException("Invalid inventory part range");
				}
				completeScan = StrictPacketCodec.readBoolean(buf);
				status = StrictPacketCodec.readEnum(buf, Status.class);
				checksum = buf.readLong();
				entries = readEntries(buf);
			}
			case STATUS -> {
				markerId = readMarkerId(buf);
				baselineId = buf.readLong();
				statusRevision = buf.readLong();
				watermark = buf.readLong();
				status = StrictPacketCodec.readEnum(buf, Status.class);
				checksum = buf.readLong();
			}
			case HEARTBEAT -> {
				markerId = readMarkerId(buf);
				baselineId = buf.readLong();
				statusRevision = buf.readLong();
				watermark = buf.readLong();
				checksum = buf.readLong();
			}
		}
		if (baselineId < 0 || statusRevision < 0 || watermark < 0) {
			throw new IllegalArgumentException("Negative inventory state");
		}
		if (buf.isReadable()) {
			throw new IllegalArgumentException("Trailing inventory response");
		}
		return new InventoryS2CPacket(kind, protocol, epoch, requestId, markerId, baselineId, statusRevision, watermark,
			partIndex, partCount, completeScan, status, checksum, entries, offer, presentationEpoch, view, allowedTypes, commitId, rejection);
	}

	private static Offer readOffer(FriendlyByteBuf buf) {
		return new Offer(StrictPacketCodec.readVarInt(buf), StrictPacketCodec.readVarInt(buf), StrictPacketCodec.readVarInt(buf), StrictPacketCodec.readVarInt(buf));
	}
	private static MarkerId readMarkerId(FriendlyByteBuf buf) {
		return StrictPacketCodec.readBoolean(buf) ? new MarkerId(buf.readLong()) : null;
	}

	private static void writeEntries(FriendlyByteBuf buf, List<Entry> entries) {
		FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer(256, MAX_FRAME_BYTES + MAX_ENTRY_BYTES));
		try {
			payload.writeVarInt(entries.size());
			for (Entry entry : entries) {
				writeEntry(payload, entry);
				if (payload.readableBytes() > MAX_FRAME_BYTES) {
					throw new IllegalArgumentException("Inventory entries exceed frame budget");
				}
			}
			buf.writeVarInt(payload.readableBytes());
			buf.writeBytes(payload);
		} finally {
			payload.release();
		}
	}

	private static void writeEntry(FriendlyByteBuf buf, Entry entry) {
		buf.writeUtf(entry.key(), MAX_KEY_BYTES);
		buf.writeUtf(entry.itemId(), MAX_ITEM_ID_BYTES);
		buf.writeUtf(entry.label(), MAX_LABEL_BYTES);
		buf.writeBoolean(entry.displayJson() != null);
		if (entry.displayJson() != null) {
			buf.writeUtf(entry.displayJson(), MAX_DISPLAY_BYTES);
		}
		buf.writeLong(entry.count());
		buf.writeLong(entry.itemRevision());
		buf.writeBoolean(entry.fallback());
		buf.writeBoolean(entry.quality() != null);
		if (entry.quality() != null) {
			MarkerPacketCodec.writeEnum(buf, entry.quality());
		}
		buf.writeLong(entry.groupRevision());
		buf.writeBoolean(entry.replaceGroup());
		buf.writeBoolean(entry.itemPingType() != null);
		if (entry.itemPingType() != null) buf.writeUtf(entry.itemPingType(), 256);
	}

	private static List<Entry> readEntries(FriendlyByteBuf buf) {
		int length = StrictPacketCodec.readVarInt(buf);
		if (length < 0 || length > MAX_FRAME_BYTES || length > buf.readableBytes()) {
			throw new IllegalArgumentException("Inventory entries payload length");
		}
		byte[] payload = new byte[length];
		buf.readBytes(payload);
		FriendlyByteBuf entriesBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
		try {
			int count = StrictPacketCodec.readVarInt(entriesBuf);
			if (count < 0 || count > MAX_ENTRIES) {
				throw new IllegalArgumentException("Inventory entry count");
			}
			List<Entry> entries = new ArrayList<>(count);
			Set<String> keys = new HashSet<>();
			for (int i = 0; i < count; i++) {
				Entry entry = readEntry(entriesBuf);
				if (!keys.add(entry.key())) {
					throw new IllegalArgumentException("Duplicate inventory entry key");
				}
				entries.add(entry);
			}
			if (entriesBuf.isReadable()) {
				throw new IllegalArgumentException("Trailing inventory entries");
			}
			return entries;
		} finally {
			entriesBuf.release();
		}
	}

	private static Entry readEntry(FriendlyByteBuf buf) {
		String key = StrictPacketCodec.readUtf(buf, MAX_KEY_BYTES);
		String itemId = StrictPacketCodec.readUtf(buf, MAX_ITEM_ID_BYTES);
		String label = StrictPacketCodec.readUtf(buf, MAX_LABEL_BYTES);
		String displayJson = StrictPacketCodec.readBoolean(buf) ? StrictPacketCodec.readUtf(buf, MAX_DISPLAY_BYTES) : null;
		long count = buf.readLong();
		long itemRevision = buf.readLong();
		boolean fallback = StrictPacketCodec.readBoolean(buf);
		Status quality = StrictPacketCodec.readBoolean(buf) ? StrictPacketCodec.readEnum(buf, Status.class) : null;
		if (count < 0 || itemRevision < 0) {
			throw new IllegalArgumentException("Negative inventory entry count");
		}
		long groupRevision = buf.readLong(); boolean replaceGroup = StrictPacketCodec.readBoolean(buf);
		String itemPingType = StrictPacketCodec.readBoolean(buf) ? StrictPacketCodec.readUtf(buf, 256) : null;
		return new Entry(key, itemId, label, displayJson, count, itemRevision, fallback, quality, groupRevision, replaceGroup, itemPingType);
	}

	private static String requireBoundedText(String value, int maxBytes, String name) {
		if (value == null || utf8Length(value) > maxBytes) {
			throw new IllegalArgumentException(name);
		}
		return value;
	}

	private static int utf8Length(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static boolean periodOutOfRange(int ticks) {
		return ticks < InventoryLimits.MIN_PERIOD_TICKS || ticks > InventoryLimits.MAX_PERIOD_TICKS;
	}

	private boolean validParts() {
		return partIndex >= 0 && partCount >= 1 && partIndex < partCount;
	}

	private boolean hasDuplicateKeys() {
		if (entries.size() < 2) {
			return false;
		}
		Set<String> keys = new HashSet<>();
		for (Entry entry : entries) {
			if (!keys.add(entry.key())) {
				return true;
			}
		}
		return false;
	}

	@Override public boolean isCorrupt() {
		if (kind == null || protocol != VERSION) {
			return true;
		}
		if (epoch == 0 || requestId < 0 || baselineId < 0 || statusRevision < 0 || watermark < 0 || view < 0 || commitId < 0) {
			return true;
		}
		if (kind != Kind.OFFER && offer != null) {
			return true;
		}
		return switch (kind) {
			case POLICY -> markerId != null || !entries.isEmpty();
			case SELECTED -> markerId == null || commitId <= 0 || rejection != null || !entries.isEmpty();
			case REJECT -> markerId != null || commitId <= 0 || rejection == null || !entries.isEmpty();
			case OFFER -> offer == null || markerId != null || !entries.isEmpty();
			case PREVIEW -> markerId != null || status == null || !validParts() || hasDuplicateKeys();
			case SNAPSHOT, STREAM -> markerId == null || status == null || !validParts() || hasDuplicateKeys();
			case STATUS -> markerId == null || status == null || !entries.isEmpty();
			case HEARTBEAT -> markerId == null || !entries.isEmpty();
		};
	}

	@Override public ResourceLocation getId() { return PACKET_ID; }
	@Override public @NotNull Type<InventoryS2CPacket> type() { return PACKET_TYPE; }
	public static InventoryS2CPacket readSafe(FriendlyByteBuf buf) { return PacketHandler.readSafe(buf, InventoryS2CPacket.class); }
}
