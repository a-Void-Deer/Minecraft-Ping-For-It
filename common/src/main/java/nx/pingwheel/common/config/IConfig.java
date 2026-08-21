package nx.pingwheel.common.config;

public interface IConfig {
	void validate();
	void onUpdate();

	/**
	 * Whether load failures for this config should be recovered by backing up
	 * the original file and writing a fresh default file. Server configuration
	 * deliberately keeps the legacy reset behavior; the client config opts in
	 * because it contains user-authored matcher lists.
	 */
	default boolean recoverInvalidOnLoad() {
		return false;
	}
}
