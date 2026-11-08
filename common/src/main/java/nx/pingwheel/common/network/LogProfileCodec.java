package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Stable logging-profile wire tags shared by both server-configuration payloads. */
@NotNullByDefault
final class LogProfileCodec {
	private LogProfileCodec() {}

	static LoggingProfile read(FriendlyByteBuf buf) {
		return switch (ServerConfigVarNumbers.readInt(buf)) {
			case 0 -> LoggingProfile.NORMAL;
			case 1 -> LoggingProfile.DEEP;
			case 2 -> LoggingProfile.DEBUG;
			default -> throw new IllegalArgumentException("unknown server configuration logging profile");
		};
	}

	static void write(FriendlyByteBuf buf, @Nullable LoggingProfile profile) {
		if (profile == null) {
			throw new IllegalArgumentException("missing server configuration logging profile");
		}
		buf.writeVarInt(switch (profile) {
			case NORMAL -> 0;
			case DEEP -> 1;
			case DEBUG -> 2;
		});
	}
}
