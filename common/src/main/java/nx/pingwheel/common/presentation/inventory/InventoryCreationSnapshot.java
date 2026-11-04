package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;

import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.PresentationPropertyPingTypes;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryDisplayWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * One immutable, server-selected Preview row frozen for an atomic CREATED receipt.
 * The opaque key is request-local metadata, never native canonical identity bytes.
 * This is provisional display history, not a Tracking baseline or source evidence.
 *
 * <p>previewComplete means the server handed off the complete Preview cut, not a
 * client acknowledgement. An incomplete cut always has uncertain quantity; a
 * completed cut can independently be uncertain (for example cursorless reads).
 * Target-specific annotation permission remains the server admission owner's job.
 */
@NotNullByDefault
public record InventoryCreationSnapshot(InventoryResourceKind resourceKind, String opaqueKey,
	String resourceId, String label, @Nullable String displayJson, long amount, boolean aggregate,
	String annotationPingType, boolean previewComplete, boolean quantityUncertain) {
	public InventoryCreationSnapshot {
		Objects.requireNonNull(resourceKind, "resourceKind");
		requireText(opaqueKey, InventoryS2CPacket.MAX_KEY_BYTES);
		if (opaqueKey.isBlank()) throw new IllegalArgumentException("blank creation snapshot key");
		requireId(resourceId);
		requireText(label, InventoryS2CPacket.MAX_LABEL_BYTES);
		InventoryQuantities.require(amount);
		if (!PresentationPropertyPingTypes.isKnownPingTypeId(annotationPingType))
			throw new IllegalArgumentException("creation snapshot annotation");
		if (!previewComplete && !quantityUncertain)
			throw new IllegalArgumentException("incomplete creation quantity must be uncertain");
		if (displayJson != null) {
			requireText(displayJson, InventoryS2CPacket.MAX_DISPLAY_BYTES);
			if (resourceKind == InventoryResourceKind.FLUID) {
				if (!InventoryFluidDisplayCodec.decodeStrict(displayJson).fluidId().equals(resourceId))
					throw new IllegalArgumentException("creation snapshot fluid display identity");
			} else if (!InventoryDisplayWriter.validProjection(resourceId, displayJson)) {
				throw new IllegalArgumentException("creation snapshot item display");
			}
		}
	}

	/** Same inventory Unicode/byte rules without temporary UTF encoder arrays. */
	private static void requireText(String text, int bound) {
		Objects.requireNonNull(text);
		if (text.length() > bound) throw new IllegalArgumentException("creation text bounds");
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
					throw new IllegalArgumentException("creation UTF");
			} else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("creation UTF");
		}
		if (io.netty.buffer.ByteBufUtil.utf8Bytes(text) > bound)
			throw new IllegalArgumentException("creation text bounds");
	}

	private static void requireId(String id) {
		requireText(id, InventoryS2CPacket.MAX_ITEM_ID_BYTES);
		int colon = id.indexOf(':');
		if (colon <= 0 || colon == id.length() - 1) throw new IllegalArgumentException("creation registry id");
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (i == colon || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
				|| c == '_' || c == '-' || c == '.' || i > colon && c == '/') continue;
			throw new IllegalArgumentException("creation registry id");
		}
	}

	/**
	 * Freezes the exact currently retained row after source/token/annotation admission.
	 * A missing row is never synthesized; mismatched lane, key or aggregate mode fails.
	 * The chosen annotation comes from the admitted selection, not the Preview row.
	 */
	public static InventoryCreationSnapshot fromRetained(String opaqueKey, InventorySelection selection,
		InventoryResourceValue retained, boolean previewComplete, boolean quantityUncertain) {
		Objects.requireNonNull(selection, "selection");
		Objects.requireNonNull(retained, "retained");
		if (selection.kind() != retained.resourceKind() || !selection.resourceId().equals(retained.resourceId())
			|| selection.aggregate() != retained.aggregateRequired()
			|| (selection.exact() != null && !selection.exact().equals(retained.key())))
			throw new IllegalArgumentException("creation snapshot retained selection mismatch");
		return new InventoryCreationSnapshot(selection.kind(), opaqueKey, retained.resourceId(), retained.label(),
			retained.displayJson(), retained.amount(), selection.aggregate(), selection.itemPingType(),
			previewComplete, !previewComplete || quantityUncertain);
	}

	/** Owned UTF-16 strings and fixed snapshot bookkeeping; no source/catalogue ownership. */
	public long retainedBytes() {
		return 512L + 2L * (opaqueKey.length() + resourceId.length() + label.length()
			+ annotationPingType.length() + (displayJson == null ? 0 : displayJson.length()));
	}

	/** Admission before construction/strict display validation, excluding the caller's existing strings. */
	public static long validationWorkspace(int displayBytes) {
		if (displayBytes < 0 || displayBytes > InventoryS2CPacket.MAX_DISPLAY_BYTES)
			throw new IllegalArgumentException("creation display bytes");
		return 4096L + 16L * displayBytes;
	}
}
