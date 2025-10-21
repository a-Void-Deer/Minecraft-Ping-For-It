package nx.pingwheel.common.integration;

public enum TeamContext {
	NONE, VANILLA_TEAM, VOICE_CHAT;

	@Override
	public String toString() {
		return super.toString().toLowerCase();
	}
}
