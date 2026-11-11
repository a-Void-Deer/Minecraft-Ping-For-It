package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;

/** Stable inventory diagnostic site. Channels are partitioned by this value. */
@NotNullByDefault
public enum InventoryDiagnosticSite {
	REQUEST("request"),
	SESSION("session"),
	CAPTURE("capture"),
	SNAPSHOT("snapshot"),
	PROVIDER("provider"),
	NATIVE("native"),
	VALIDATION("validation"),
	PUBLICATION("publication"),
	TRANSPORT("transport"),
	CLIENT("client"),
	DISPLAY("display");

	private final String code;

	InventoryDiagnosticSite(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
