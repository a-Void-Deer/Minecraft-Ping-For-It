package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.RulesView;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/**
 * Carries the complete per-target-type presentation selector rule view, the
 * server in-memory revision, and the status of the correlated request. Every
 * existing target type is present; a missing or unknown type is rejected so a
 * partial map can never fabricate an empty allow view. Only the three
 * presentation selector values travel per type; no other persisted server
 * setting is disclosed. A {@code requestId} of zero marks an unsolicited
 * broadcast.
 *
 * <p>The no-argument fallback carries all existing target types with empty
 * allow views; it is only a decoder fallback and remains corrupt until a
 * handler validates a real status and revision.
 */
public record ServerPresentationPolicyS2CPacket(
	long requestId,
	long revision,
	Status status,
	boolean canEdit,
	Map<String, RulesView> rules
) implements IPacket {
	public static final int VERSION = 2;
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		S2C_NAMESPACE,
		"server-presentation-policy-v2");
	public static final Type<ServerPresentationPolicyS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/** Maximum encoded size of one selector; the accepted grammar is ASCII. */
	public static final int MAX_ENCODED_SELECTOR_BYTES = ServerPresentationPolicyService.MAX_SELECTOR_LENGTH;

	/** Upper bound of one encoded rule-view payload including every target type id and both lists. */
	public static final int MAX_ENCODED_PAYLOAD_BYTES =
		PresentationSettings.TARGET_TYPE_IDS.size() * (MAX_ENCODED_SELECTOR_BYTES + 8)
			+ 2 * PresentationSettings.TARGET_TYPE_IDS.size()
				* ServerPresentationPolicyService.MAX_SELECTORS * (MAX_ENCODED_SELECTOR_BYTES + 3)
			+ 64;

	/** All existing target types with empty allow views; a safe-decoding fallback only. */
	public static Map<String, RulesView> defaultRules() {
		Map<String, RulesView> views = new LinkedHashMap<>();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) views.put(id, new RulesView(List.of(), List.of(), false));
		return Collections.unmodifiableMap(views);
	}

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerPresentationPolicyS2CPacket() {
		this(-1L, -1L, null, false, defaultRules());
	}

	public ServerPresentationPolicyS2CPacket {
		rules = validateRules(rules);
	}

	private ServerPresentationPolicyS2CPacket(ServerPresentationPolicyS2CPacket packet) {
		this(packet.requestId, packet.revision, packet.status, packet.canEdit, packet.rules);
	}

	public ServerPresentationPolicyS2CPacket(FriendlyByteBuf buf) {
		this(decode(buf));
	}

	private static ServerPresentationPolicyS2CPacket decode(FriendlyByteBuf buf) {
		ServerPresentationPolicyS2CPacket packet = new ServerPresentationPolicyS2CPacket(
			buf.readVarLong(),
			buf.readVarLong(),
			readStatus(buf),
			buf.readBoolean(),
			readRules(buf));
		if (buf.isReadable()) throw new IllegalArgumentException("trailing presentation policy response");
		return packet;
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		if (isCorrupt()) {
			throw new IllegalArgumentException("refusing to encode an invalid presentation policy snapshot");
		}

		buf.writeVarLong(requestId);
		buf.writeVarLong(revision);
		buf.writeVarInt(status.ordinal());
		buf.writeBoolean(canEdit);
		writeRules(buf, rules);
	}

	@Override
	public boolean isCorrupt() {
		if (requestId < 0L || revision < 0L || status == null) {
			return true;
		}

		if (!validRules(rules)) {
			return true;
		}

		return encodedRulesBytes() > MAX_ENCODED_PAYLOAD_BYTES;
	}

	/** Exactly every existing target type, no missing and no unknown entry. */
	static boolean validRules(Map<String, RulesView> rules) {
		if (rules == null || rules.size() != PresentationSettings.TARGET_TYPE_IDS.size()) {
			return false;
		}

		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			RulesView view = rules.get(id);
			if (view == null || view.white() == null || view.black() == null) {
				return false;
			}
			if (!validSelectorList(view.white()) || !validSelectorList(view.black())) {
				return false;
			}
		}

		return rules.keySet().size() == PresentationSettings.TARGET_TYPE_IDS.size();
	}

	static Map<String, RulesView> validateRules(Map<String, RulesView> rules) {
		if (!validRules(rules)) {
			throw new IllegalArgumentException("presentation policy rules must cover every target type");
		}

		Map<String, RulesView> copy = new LinkedHashMap<>();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) copy.put(id, rules.get(id));
		return Collections.unmodifiableMap(copy);
	}

	private static boolean validSelectorList(List<String> selectors) {
		if (selectors == null || selectors.size() > ServerPresentationPolicyService.MAX_SELECTORS) {
			return false;
		}

		for (String selector : selectors) {
			if (selector == null
				|| selector.isEmpty()
				|| selector.length() > MAX_ENCODED_SELECTOR_BYTES) {
				return false;
			}
		}

		return true;
	}

	private int encodedRulesBytes() {
		int size = varIntSize(rules.size());

		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			RulesView view = rules.get(id);
			size += 3 + id.getBytes(StandardCharsets.UTF_8).length; // id length prefix + id
			size += encodedListBytes(view.white());
			size += encodedListBytes(view.black());
			size += 1; // whitelistOnly
		}

		return size;
	}

	private static int encodedListBytes(List<String> selectors) {
		int size = varIntSize(selectors.size());

		for (String selector : selectors) {
			int bytes = selector.getBytes(StandardCharsets.UTF_8).length;
			size += varIntSize(bytes) + bytes;
		}

		return size;
	}

	/** Encoded size of a non-negative varint; mirrors {@code FriendlyByteBuf#writeVarInt}. */
	private static int varIntSize(int value) {
		return (value & -128) == 0 ? 1
			: (value & -16384) == 0 ? 2
			: (value & -2097152) == 0 ? 3
			: (value & -268435456) == 0 ? 4
			: 5;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static ServerPresentationPolicyS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, ServerPresentationPolicyS2CPacket.class);
	}

	@Override
	public @NotNull Type<ServerPresentationPolicyS2CPacket> type() {
		return PACKET_TYPE;
	}

	static List<String> readSelectors(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		if (count < 0 || count > ServerPresentationPolicyService.MAX_SELECTORS) {
			throw new IllegalArgumentException("invalid presentation selector count");
		}

		List<String> selectors = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			selectors.add(buf.readUtf(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH));
		}

		return List.copyOf(selectors);
	}

	static void writeSelectors(FriendlyByteBuf buf, List<String> selectors) {
		buf.writeVarInt(selectors.size());
		for (String selector : selectors) {
			buf.writeUtf(selector, ServerPresentationPolicyService.MAX_SELECTOR_LENGTH);
		}
	}

	static Map<String, RulesView> readRules(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		if (count != PresentationSettings.TARGET_TYPE_IDS.size()) {
			throw new IllegalArgumentException("invalid presentation policy rule count");
		}

		Map<String, RulesView> rules = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			String type = buf.readUtf(193);
			if (!PresentationSettings.isKnownTargetType(type) || rules.containsKey(type)) {
				throw new IllegalArgumentException("invalid presentation policy target type");
			}
			rules.put(type, new RulesView(readSelectors(buf), readSelectors(buf), buf.readBoolean()));
		}

		return validateRules(rules);
	}

	static void writeRules(FriendlyByteBuf buf, Map<String, RulesView> rules) {
		buf.writeVarInt(PresentationSettings.TARGET_TYPE_IDS.size());
		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			RulesView view = rules.get(id);
			buf.writeUtf(id, 193);
			writeSelectors(buf, view.white());
			writeSelectors(buf, view.black());
			buf.writeBoolean(view.whitelistOnly());
		}
	}

	static Status readStatus(FriendlyByteBuf buf) {
		int ordinal = buf.readVarInt();
		Status[] values = Status.values();
		if (ordinal < 0 || ordinal >= values.length) {
			throw new IllegalArgumentException("invalid presentation policy status");
		}
		return values[ordinal];
	}
}
