package nx.pingwheel.common.presentation.inventory.minecraft;

import com.mojang.serialization.DataResult;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Failure detail only: never walks, formats or retains a resident item graph. */
@NotNullByDefault
public final class InventoryItemDiagnostics {
	private static final String CODEC_REJECTION = "codec produced no complete result; native error detail not evaluated";

	private InventoryItemDiagnostics() {}

	static <T> T complete(DataResult<T> result, String stage) {
		// Error suppliers can walk NBT before producing their first character.
		// Keep only the trusted stage; neither retain the result nor use a partial.
		return result.result().orElseThrow(() -> new CodecRefusal(stage));
	}

	static void refusal(String operation, String itemId, RuntimeException refusal) {
		if (refusal instanceof CodecRefusal codec) {
			Diagnostics.event(Level.WARN, operation, "minecraft", "native-codec-rejected",
				() -> "item=" + itemId + " stage=" + codec.getMessage() + " detail=" + CODEC_REJECTION);
		} else if (refusal.getCause() == null) {
			Diagnostics.event(Level.WARN, operation, "minecraft", "root-refused",
				() -> "item=" + itemId + " detail=" + refusal.getMessage());
		} else {
			Diagnostics.warn(operation, "minecraft", () -> "item=" + itemId, refusal);
		}
	}

	/** Native error suppliers may traverse payloads; report only the caller-owned failure stage. */
	public static void codecRejected(String operation, String stage) {
		Diagnostics.event(Level.WARN, operation, "minecraft", "native-codec-rejected",
			() -> "stage=" + stage + " detail=" + CODEC_REJECTION);
	}

	public static void displayFailure(String operation, @Nullable String itemId, Throwable failure) {
		Diagnostics.warn(operation, "minecraft", () -> "item=" + itemId, failure);
	}

	@NotNullByDefault
	private static final class CodecRefusal extends InventoryItemBudget.RootLimitException {
		CodecRefusal(String stage) { super(stage); }
	}
}
