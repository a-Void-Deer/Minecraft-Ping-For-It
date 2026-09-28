package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.RulesView;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPresentationPolicyPacketsTest {
	private static FriendlyByteBuf buffer() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	private static Map<String, RulesView> fullRules() {
		Map<String, RulesView> rules = new LinkedHashMap<>();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) rules.put(id, new RulesView(List.of(), List.of(), false));
		rules.put("block", new RulesView(List.of("minecraft:basic"), List.of("create:*"), true));
		return rules;
	}

	@Test
	void routeIsVersionTwo() {
		assertEquals(2, ServerPresentationPolicyC2SPacket.VERSION);
		assertEquals(2, ServerPresentationPolicyS2CPacket.VERSION);
		assertEquals("pingforit-c2s:server-presentation-policy-v2", ServerPresentationPolicyC2SPacket.PACKET_ID.toString());
		assertEquals("pingforit-s2c:server-presentation-policy-v2", ServerPresentationPolicyS2CPacket.PACKET_ID.toString());
	}

	@Test
	void c2sReadNeedsNoTargetTypeAndRoundTrips() {
		FriendlyByteBuf buf = buffer();
		try {
			ServerPresentationPolicyC2SPacket.read(42L).write(buf);
			var decoded = ServerPresentationPolicyC2SPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(Operation.READ, decoded.operation());
			assertEquals("", decoded.targetTypeId());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void c2sMutationsRoundTripWithASelectedTargetType() {
		for (Operation operation : Operation.values()) {
			if (operation == Operation.READ) continue;
			FriendlyByteBuf buf = buffer();
			try {
				var packet = ServerPresentationPolicyC2SPacket.mutation(42L, "block", operation,
					operation.requiresSelector() ? "minecraft:*" : "",
					operation == Operation.SET_WHITELIST_ONLY);
				packet.write(buf);
				var decoded = ServerPresentationPolicyC2SPacket.readSafe(buf);

				assertFalse(decoded.isCorrupt());
				assertEquals(operation, decoded.operation());
				assertEquals("block", decoded.targetTypeId());
				assertEquals(packet.selector(), decoded.selector());
				assertEquals(0, buf.readableBytes());
			} finally {
				buf.release();
			}
		}
	}

	@Test
	void c2sMutationWithoutKnownTargetTypeOrSelectorIsCorrupt() {
		assertTrue(ServerPresentationPolicyC2SPacket.mutation(1L, "", Operation.ADD_WHITE, "minecraft:*", false).isCorrupt());
		assertTrue(ServerPresentationPolicyC2SPacket.mutation(1L, "unknown", Operation.ADD_WHITE, "minecraft:*", false).isCorrupt());
		assertTrue(ServerPresentationPolicyC2SPacket.mutation(1L, "block", Operation.ADD_WHITE, "", false).isCorrupt());
		assertTrue(new ServerPresentationPolicyC2SPacket(0L, Operation.READ, "", "", false).isCorrupt());
	}

	@Test
	void s2cCarriesEveryTargetTypeAndRoundTrips() {
		FriendlyByteBuf buf = buffer();
		try {
			var packet = new ServerPresentationPolicyS2CPacket(42L, 7L, Status.OK, true, fullRules());
			packet.write(buf);
			var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf);

			assertFalse(decoded.isCorrupt());
			assertEquals(42L, decoded.requestId());
			assertEquals(7L, decoded.revision());
			assertEquals(Status.OK, decoded.status());
			assertTrue(decoded.canEdit());
			assertEquals(fullRules(), decoded.rules());
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
	}

	@Test
	void s2cDefaultFallbackCarriesAllFiveEmptyAllowViewsButIsCorrupt() {
		var fallback = new ServerPresentationPolicyS2CPacket();

		assertEquals(PresentationSettings.TARGET_TYPE_IDS, List.copyOf(fallback.rules().keySet()));
		assertTrue(fallback.rules().values().stream()
			.allMatch(view -> view.white().isEmpty() && view.black().isEmpty() && !view.whitelistOnly()));
		assertTrue(fallback.isCorrupt());
		assertThrows(IllegalArgumentException.class, () -> fallback.write(buffer()));
	}

	@Test
	void s2cPartialOrUnknownRuleMapIsRejected() {
		Map<String, RulesView> partial = Map.of("block", new RulesView(List.of(), List.of(), false));
		assertThrows(IllegalArgumentException.class,
			() -> new ServerPresentationPolicyS2CPacket(42L, 7L, Status.OK, true, partial));

		Map<String, RulesView> unknown = new LinkedHashMap<>(fullRules());
		unknown.remove("location");
		unknown.put("something_else", new RulesView(List.of(), List.of(), false));
		assertThrows(IllegalArgumentException.class,
			() -> new ServerPresentationPolicyS2CPacket(42L, 7L, Status.OK, true, unknown));
	}

	@Test
	void s2cDecodeOfAMissingTargetTypeYieldsAFailClosedFallback() {
		FriendlyByteBuf buf = buffer();
		try {
			buf.writeVarLong(1L);
			buf.writeVarLong(1L);
			buf.writeVarInt(Status.OK.ordinal());
			buf.writeBoolean(false);
			buf.writeVarInt(PresentationSettings.TARGET_TYPE_IDS.size() - 1);

			var decoded = ServerPresentationPolicyS2CPacket.readSafe(buf);

			assertTrue(decoded.isCorrupt());
		} finally {
			buf.release();
		}
	}

	@Test
	void trailingBytesYieldTheCorruptFallbackInsteadOfAnException() {
		FriendlyByteBuf readBuf = buffer();
		try {
			ServerPresentationPolicyC2SPacket.read(42L).write(readBuf);
			readBuf.writeByte(1);
			assertTrue(ServerPresentationPolicyC2SPacket.readSafe(readBuf).isCorrupt());
			assertEquals(0, readBuf.readableBytes());
		} finally {
			readBuf.release();
		}

		FriendlyByteBuf mutationBuf = buffer();
		try {
			ServerPresentationPolicyC2SPacket.mutation(42L, "block", Operation.ADD_WHITE, "minecraft:*", false).write(mutationBuf);
			mutationBuf.writeByte(1);
			assertTrue(ServerPresentationPolicyC2SPacket.readSafe(mutationBuf).isCorrupt());
		} finally {
			mutationBuf.release();
		}

		FriendlyByteBuf responseBuf = buffer();
		try {
			new ServerPresentationPolicyS2CPacket(42L, 7L, Status.OK, true, fullRules()).write(responseBuf);
			responseBuf.writeByte(1);
			assertTrue(ServerPresentationPolicyS2CPacket.readSafe(responseBuf).isCorrupt());
		} finally {
			responseBuf.release();
		}
	}
}
