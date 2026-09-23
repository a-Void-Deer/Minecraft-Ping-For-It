package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPresentationPolicyPacketsTest {
	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	@Test
	void c2sRoundTripsEveryOperation() {
		for (Operation operation : Operation.values()) {
			var packet = new ServerPresentationPolicyC2SPacket(
				42L,
				operation,
				operation.requiresSelector() ? "minecraft:*" : "",
				operation == Operation.SET_WHITELIST_ONLY);
			FriendlyByteBuf buf = buffer();
			packet.write(buf);

			var decoded = ServerPresentationPolicyC2SPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(42L, decoded.requestId());
			assertEquals(operation, decoded.operation());
			assertEquals(packet.selector(), decoded.selector());
			assertEquals(packet.whitelistOnly(), decoded.whitelistOnly());
			assertEquals(0, buf.readableBytes());
		}
	}

	@Test
	void c2sZeroOrNegativeRequestIdIsCorrupt() {
		assertTrue(new ServerPresentationPolicyC2SPacket(0L, Operation.READ, "", false).isCorrupt());
		assertTrue(new ServerPresentationPolicyC2SPacket(-1L, Operation.READ, "", false).isCorrupt());
	}

	@Test
	void c2sMutationWithoutSelectorIsCorrupt() {
		assertTrue(new ServerPresentationPolicyC2SPacket(1L, Operation.ADD_WHITE, "", false).isCorrupt());
		assertTrue(new ServerPresentationPolicyC2SPacket(1L, Operation.ADD_WHITE, null, false).isCorrupt());
	}

	@Test
	void c2sMutationWithInvalidSelectorGrammarIsCorrupt() {
		assertTrue(new ServerPresentationPolicyC2SPacket(1L, Operation.ADD_WHITE, "Not A Selector", false).isCorrupt());
		assertTrue(new ServerPresentationPolicyC2SPacket(1L, Operation.ADD_WHITE, "missingcolon", false).isCorrupt());
	}

	@Test
	void c2sSafeDecodeRejectsUnknownOperationAndDrains() {
		FriendlyByteBuf buf = buffer();
		buf.writeVarLong(7L);
		buf.writeVarInt(Operation.values().length + 5);
		buf.writeUtf("minecraft:*");
		buf.writeBoolean(false);
		buf.writeByte(0x7F);

		var decoded = ServerPresentationPolicyC2SPacket.readSafe(buf);

		assertTrue(decoded.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void c2sSafeDecodeRejectsOversizedSelectorAndDrains() {
		FriendlyByteBuf buf = buffer();
		buf.writeVarLong(7L);
		buf.writeVarInt(Operation.ADD_WHITE.ordinal());
		buf.writeUtf("a".repeat(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH + 1));
		buf.writeBoolean(false);

		var decoded = ServerPresentationPolicyC2SPacket.readSafe(buf);

		assertTrue(decoded.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void s2cRoundTripsRuleView() {
		var packet = new ServerPresentationPolicyS2CPacket(
			5L,
			3L,
			Status.OK,
			true,
			List.of("minecraft:basic", "create:*"),
			List.of("minecraft:entity.health"),
			true);
		FriendlyByteBuf buf = buffer();
		packet.write(buf);

		var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf);

		assertFalse(decoded.isCorrupt());
		assertEquals(packet, decoded);
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void s2cAllowsUnsolicitedZeroRequestId() {
		var packet = new ServerPresentationPolicyS2CPacket(
			0L, 1L, Status.OK, false, List.of(), List.of(), false);

		assertFalse(packet.isCorrupt());
	}

	@Test
	void s2cSafeDecodeRejectsOversizedSelectorCountAndDrains() {
		FriendlyByteBuf buf = buffer();
		buf.writeVarLong(1L);
		buf.writeVarLong(1L);
		buf.writeVarInt(Status.OK.ordinal());
		buf.writeBoolean(false);
		buf.writeVarInt(ServerPresentationPolicyService.MAX_SELECTORS + 1);
		buf.writeByte(0x7F);

		var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf);

		assertTrue(decoded.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void s2cSafeDecodeRejectsOversizedSelectorStringAndDrains() {
		FriendlyByteBuf buf = buffer();
		buf.writeVarLong(1L);
		buf.writeVarLong(1L);
		buf.writeVarInt(Status.OK.ordinal());
		buf.writeBoolean(false);
		buf.writeVarInt(1);
		buf.writeUtf("a".repeat(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH + 1));

		var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf);

		assertTrue(decoded.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void s2cInvalidSelectorFailsClosed() {
		var packet = new ServerPresentationPolicyS2CPacket(
			1L, 1L, Status.OK, true, List.of("NOT VALID"), List.of(), false);

		assertTrue(packet.isCorrupt());
	}

	@Test
	void s2cCanonicalConstructorCopiesListsDefensively() {
		List<String> white = new ArrayList<>(List.of("minecraft:basic"));
		List<String> black = new ArrayList<>(List.of("create:*"));
		var packet = new ServerPresentationPolicyS2CPacket(
			1L, 1L, Status.OK, false, white, black, false);

		white.add("create:*");
		black.clear();

		assertEquals(List.of("minecraft:basic"), packet.white());
		assertEquals(List.of("create:*"), packet.black());
	}

	@Test
	void s2cRejectsOversizedSelectorCountOnEncode() {
		List<String> tooMany = IntStream.range(0, ServerPresentationPolicyService.MAX_SELECTORS + 1)
			.mapToObj(index -> "test:field" + index)
			.toList();
		var packet = new ServerPresentationPolicyS2CPacket(
			1L, 1L, Status.OK, false, tooMany, List.of(), false);

		assertTrue(packet.isCorrupt());
		assertThrows(IllegalArgumentException.class, () -> packet.write(buffer()));
	}

	@Test
	void s2cRejectsOversizedSelectorLengthOnEncode() {
		String tooLong = "a:" + "b".repeat(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH);
		var packet = new ServerPresentationPolicyS2CPacket(
			1L, 1L, Status.OK, false, List.of(tooLong), List.of(), false);

		assertTrue(packet.isCorrupt());
		assertThrows(IllegalArgumentException.class, () -> packet.write(buffer()));
	}

	@Test
	void s2cRejectsSelectorPayloadOverEncodedByteBoundOnEncode() {
		String multibyte = "é".repeat(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH);
		List<String> overBound = IntStream.range(0, ServerPresentationPolicyService.MAX_SELECTORS)
			.mapToObj(index -> multibyte)
			.toList();
		var packet = new ServerPresentationPolicyS2CPacket(
			1L, 1L, Status.OK, false, overBound, overBound, false);

		assertTrue(packet.isCorrupt());
		assertThrows(IllegalArgumentException.class, () -> packet.write(buffer()));
	}

	@Test
	void s2cMaximumValidRuleViewFitsTheEncodedByteBound() {
		String selector = "a:" + "b".repeat(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH - 2);
		List<String> full = IntStream.range(0, ServerPresentationPolicyService.MAX_SELECTORS)
			.mapToObj(index -> selector)
			.toList();
		var packet = new ServerPresentationPolicyS2CPacket(
			1L, 1L, Status.OK, false, full, full, false);

		assertFalse(packet.isCorrupt());

		FriendlyByteBuf buf = buffer();
		packet.write(buf);

		assertTrue(buf.readableBytes() <= ServerPresentationPolicyS2CPacket.MAX_ENCODED_PAYLOAD_BYTES);
	}

	@Test
	void s2cEncodeRejectsCorruptSentinel() {
		assertThrows(IllegalArgumentException.class, () -> new ServerPresentationPolicyS2CPacket().write(buffer()));
	}

	@Test
	void s2cUnknownStatusFallsBackToCorrupt() {
		FriendlyByteBuf buf = buffer();
		buf.writeVarLong(1L);
		buf.writeVarLong(1L);
		buf.writeVarInt(Status.values().length + 5);
		buf.writeBoolean(false);
		buf.writeVarInt(0);
		buf.writeVarInt(0);
		buf.writeBoolean(false);

		var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf);

		assertTrue(decoded.isCorrupt());
		assertEquals(0, buf.readableBytes());
	}
}
