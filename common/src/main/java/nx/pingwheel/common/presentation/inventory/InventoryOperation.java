package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;

/** Stable inventory operation. Channels are partitioned by this value. */
@NotNullByDefault
public enum InventoryOperation {
	OPEN("open"),
	PREVIEW("preview"),
	TRACKING("tracking"),
	CREATE("create"),
	REMOVE("remove"),
	SELECT("select"),
	UPDATE("update"),
	CLOSE("close"),
	SNAPSHOT("snapshot"),
	READ("read"),
	VALIDATE("validate"),
	ENCODE("encode"),
	DECODE("decode"),
	PUBLISH("publish"),
	ROUTE("route"),
	DISPLAY("display");

	private final String code;

	InventoryOperation(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
