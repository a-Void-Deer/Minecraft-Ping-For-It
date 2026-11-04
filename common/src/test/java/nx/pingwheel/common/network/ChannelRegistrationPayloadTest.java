package nx.pingwheel.common.network;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the Forge 47.2.0 channel-list parser.
 *
 * <p>Fabric writes NUL separators without a trailing NUL. Forge only commits a
 * name when it sees a NUL, so the final channel is lost. The encoded payload
 * must terminate every channel without inventing one.</p>
 */
class ChannelRegistrationPayloadTest {

	private static final ResourceLocation FIRST =
		new ResourceLocation("pingforit", "c2s/ping-location");
	private static final ResourceLocation MIDDLE =
		new ResourceLocation("pingforit", "c2s/marker-create");
	private static final ResourceLocation LAST =
		new ResourceLocation("pingforit", "c2s/server-config-request");

	@Test
	void withoutTerminatorForgeDropsTheLastChannel() {
		FriendlyByteBuf buffer = payloadWithoutTerminator();

		Set<String> parsed = parseLikeForge(buffer);

		assertTrue(parsed.contains(FIRST.toString()));
		assertTrue(parsed.contains(MIDDLE.toString()));
		assertFalse(parsed.contains(LAST.toString()),
			"the reference parser must reproduce Forge's dropped final segment");
	}

	@Test
	void encodedPayloadKeepsEveryChannel() {
		FriendlyByteBuf buffer = ChannelRegistrationPayload.encode(
			List.of(FIRST, MIDDLE, LAST));

		Set<String> parsed = parseLikeForge(buffer);

		assertEquals(Set.of(FIRST.toString(), MIDDLE.toString(), LAST.toString()), parsed);
	}

	private static FriendlyByteBuf payloadWithoutTerminator() {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeBytes((FIRST + "\0" + MIDDLE + "\0" + LAST)
			.getBytes(StandardCharsets.US_ASCII));
		return buffer;
	}

	/** Mirrors Forge 47.2.0 MCRegisterPacketHandler.ChannelList#bytesToResLocation. */
	private static Set<String> parseLikeForge(FriendlyByteBuf buffer) {
		byte[] all = new byte[buffer.readableBytes()];
		buffer.readBytes(all);

		Set<String> channels = new HashSet<>();
		int last = 0;

		for (int current = 0; current < all.length; current++) {
			if (all[current] == 0) {
				channels.add(new String(all, last, current - last, StandardCharsets.US_ASCII));
				last = current + 1;
			}
		}

		return channels;
	}
}
