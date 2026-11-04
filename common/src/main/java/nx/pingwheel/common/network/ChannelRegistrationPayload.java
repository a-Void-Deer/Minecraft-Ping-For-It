package nx.pingwheel.common.network;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Wire-format helper for {@code minecraft:register} channel announcements.
 *
 * <p>Forge 47.2.0 parses the payload by collecting a channel name only when it
 * encounters a NUL byte, and it does not flush the final unterminated segment.
 * Fabric writes separators before every channel after the first but no trailing
 * NUL, so a Forge client silently drops the last Fabric channel. Writing a
 * terminator after every channel (the format Forge itself emits) makes the
 * whole list visible to both parsers.</p>
 */
public final class ChannelRegistrationPayload {

	private ChannelRegistrationPayload() {}

	/**
	 * Encodes a complete registration payload with a NUL terminator after every
	 * channel, including the last.
	 */
	public static FriendlyByteBuf encode(Collection<ResourceLocation> channels) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		for (ResourceLocation channel : channels) {
			buffer.writeBytes(channel.toString().getBytes(StandardCharsets.US_ASCII));
			buffer.writeByte(0);
		}

		return buffer;
	}
}
