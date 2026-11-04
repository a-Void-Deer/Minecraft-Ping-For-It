package nx.pingwheel.common.presentation.inventory.minecraft;

import com.mojang.serialization.DataResult;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Failure detail only: never walks, formats or retains a resident item graph. */
@NotNullByDefault
public final class InventoryItemDiagnostics {
	private static final nx.pingwheel.common.diagnostics.DiagnosticChannel IDENTITY = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-item-identity", java.time.Duration.ofSeconds(10));
	private static final nx.pingwheel.common.diagnostics.DiagnosticChannel DISPLAY = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-item-display", java.time.Duration.ofSeconds(10));
	private static final nx.pingwheel.common.diagnostics.DiagnosticChannel CLIENT_CODEC = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-client-display-codec", java.time.Duration.ofSeconds(10));
	private static final nx.pingwheel.common.diagnostics.DiagnosticChannel CLIENT_LEGACY = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-client-display-legacy", java.time.Duration.ofSeconds(10));
	@NotNullByDefault
	private enum DisplayFailure {
		RESOLVE, DECODE, NAME, CACHE, CODEC, REGISTRY;
		final nx.pingwheel.common.diagnostics.DiagnosticChannel channel = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-client-display-" + name(), java.time.Duration.ofSeconds(10));
	}
	private static final String CODEC_REJECTION = "codec produced no complete result; native error detail not evaluated";

	private InventoryItemDiagnostics() {}

	static <T> T complete(DataResult<T> result, String stage) {
		// Error suppliers can walk NBT before producing their first character.
		// Keep only the trusted stage; neither retain the result nor use a partial.
		return result.result().orElseThrow(() -> new CodecRefusal(stage));
	}

	static void refusal(String operation, String itemId, RuntimeException refusal) {
		var logger = Diagnostics.server().atDetailed().rateLimited(operation.equals("inventory/item-identity") ? IDENTITY : DISPLAY);
		if (!logger.isEnabled(Level.WARN)) return;
		if (refusal instanceof CodecRefusal codec) {
			logger.event(Level.WARN, operation, "native-codec-rejected",
				() -> "item=" + itemId + " stage=" + codec.getMessage() + " detail=" + CODEC_REJECTION);
		} else {
			logger.failure(Level.WARN, operation, "root-refused", () -> "item=" + itemId, refusal);
		}
	}

	/** Native error suppliers may traverse payloads; report only the caller-owned failure stage. */
	public static void codecRejected(nx.pingwheel.common.diagnostics.DiagnosticLogger consumer, String operation, String stage) {
		consumer.atDetailed().rateLimited(operation.equals("inventory/client-display-legacy-codec") ? CLIENT_LEGACY : CLIENT_CODEC).event(Level.WARN, operation, "native-codec-rejected",
			() -> "stage=" + stage + " detail=" + CODEC_REJECTION);
	}

	public static void displayFailure(nx.pingwheel.common.diagnostics.DiagnosticLogger consumer, String operation, @Nullable String itemId, Throwable failure) {
		@Nullable DisplayFailure site = switch (operation) {
			case "inventory/client-display-resolve" -> DisplayFailure.RESOLVE;
			case "inventory/client-display-decode" -> DisplayFailure.DECODE;
			case "inventory/client-display-name" -> DisplayFailure.NAME;
			case "inventory/client-display-cache" -> DisplayFailure.CACHE;
			case "inventory/client-display-codec" -> DisplayFailure.CODEC;
			case "inventory/client-display-registry" -> DisplayFailure.REGISTRY;
			default -> null;
		};
		var logger = consumer.atDetailed();
		if (site != null) logger = logger.rateLimited(site.channel);
		logger.failure(Level.WARN, operation, "display-fallback", () -> "item=" + itemId, failure);
	}

	@NotNullByDefault
	private static final class CodecRefusal extends InventoryItemBudget.RootLimitException {
		CodecRefusal(String stage) { super(stage); }
	}
}
