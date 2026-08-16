package nx.pingwheel.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.resource.ResourceConstants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the fork's external identity in common code and resources.
 *
 * <p>Global's identity constants are compile-time String constants, so
 * referencing them here never triggers Global's platform-context static
 * initialization (which resolves a ServiceLoader provider and must never run
 * from unit tests).
 */
class ModIdentityTest {

	private static final List<String> LANG_FILES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "uk_ua", "zh_cn", "zh_tw");

	@Test
	void globalIdentityUsesForkId() {
		assertEquals("pingforit", Global.MOD_ID);
		assertNotEquals("pingwheel", Global.MOD_ID);
		assertEquals("[PingForIt] ", Global.MOD_PREFIX);
	}

	@Test
	void derivedNamespacesAndCommandRootsFollowModId() {
		assertEquals("pingforit-c2s", Global.C2S_NAMESPACE);
		assertEquals("pingforit-s2c", Global.S2C_NAMESPACE);
		assertEquals("pingforit", Global.CLIENT_COMMAND_ROOT);
		assertEquals("pingforit:server", Global.SERVER_COMMAND_ROOT);
	}

	@Test
	void allNinePacketIdsUseNewNamespacesWithUnchangedPaths() {
		assertEquals(Set.of(
			"pingforit-c2s:marker-create",
			"pingforit-c2s:marker-remove",
			"pingforit-c2s:ping-location",
			"pingforit-c2s:update-channel",
			"pingforit-s2c:marker-created",
			"pingforit-s2c:marker-removed",
			"pingforit-s2c:marker-rejected",
			"pingforit-s2c:marker-winner-changed",
			"pingforit-s2c:rate-limit-policy",
			"pingforit-s2c:ping-location"
		), Set.of(
			MarkerCreateC2SPacket.PACKET_ID.toString(),
			MarkerRemoveC2SPacket.PACKET_ID.toString(),
			PingLocationC2SPacket.PACKET_ID.toString(),
			UpdateChannelC2SPacket.PACKET_ID.toString(),
			MarkerCreatedS2CPacket.PACKET_ID.toString(),
			MarkerRemovedS2CPacket.PACKET_ID.toString(),
			MarkerRejectedS2CPacket.PACKET_ID.toString(),
			MarkerWinnerChangedS2CPacket.PACKET_ID.toString(),
			RateLimitPolicyS2CPacket.PACKET_ID.toString(),
			PingLocationS2CPacket.PACKET_ID.toString()
		));
	}

	@Test
	void resourceConstantsUseNewNamespace() {
		assertEquals("pingforit:ping", ResourceConstants.PING_SOUND_ID.toString());
		assertEquals("pingforit:textures/ping.png", ResourceConstants.PING_TEXTURE_ID.toString());
		assertEquals("pingforit:textures/arrow.png", ResourceConstants.ARROW_TEXTURE_ID.toString());
	}

	@Test
	void newClasspathResourcesExistAndOldOnesAreAbsent() {
		assertNotNull(resource("/assets/pingforit/lang/en_us.json"));
		assertNotNull(resource("/assets/pingforit/sounds.json"));
		assertNotNull(resource("/assets/pingforit/sounds/ping.ogg"));
		assertNotNull(resource("/assets/pingforit/textures/ping.png"));
		assertNotNull(resource("/assets/pingforit/textures/arrow.png"));
		assertNotNull(resource("/pingforit.mixins.json"));

		assertNull(resource("/assets/pingwheel/lang/en_us.json"));
		assertNull(resource("/assets/pingwheel/sounds.json"));
		assertNull(resource("/assets/pingwheel/sounds/ping.ogg"));
		assertNull(resource("/assets/pingwheel/textures/ping.png"));
		assertNull(resource("/assets/pingwheel/textures/arrow.png"));
		assertNull(resource("/pingwheel.mixins.json"));
	}

	@Test
	void mixinConfigKeepsInternalPackage() throws IOException {
		String content = readResource("/pingforit.mixins.json");

		assertTrue(content.contains("nx.pingwheel.common.mixin"),
			"mixins json must keep the internal mixin package");

		assertNoStaleExternalIdentityInMixin();
	}

	@Test
	void langAndSoundResourcesContainNoStaleExternalIdentity() throws IOException {
		for (String lang : LANG_FILES) {
			assertNoStaleIdentity("/assets/pingforit/lang/" + lang + ".json");
		}

		assertNoStaleIdentity("/assets/pingforit/sounds.json");
	}

	private static void assertNoStaleIdentity(String path) throws IOException {
		String content = readResource(path).toLowerCase(Locale.ROOT);

		assertFalse(content.contains("pingwheel"), path + " contains stale pingwheel token");
		assertFalse(content.contains("ping-wheel"), path + " contains stale ping-wheel token");
		assertFalse(content.contains("ping wheel"), path + " contains stale 'ping wheel' token");
	}

	private static void assertNoStaleExternalIdentityInMixin() throws IOException {
		String content = readResource("/pingforit.mixins.json").toLowerCase(Locale.ROOT);
		String external = content.replace("nx.pingwheel", "");

		assertFalse(external.contains("pingwheel"), "mixins json contains stale external pingwheel token");
		assertFalse(external.contains("ping-wheel"), "mixins json contains stale ping-wheel token");
		assertFalse(external.contains("ping wheel"), "mixins json contains stale 'ping wheel' token");
	}

	private static String readResource(String path) throws IOException {
		try (InputStream stream = ModIdentityTest.class.getResourceAsStream(path)) {
			assertNotNull(stream, "missing classpath resource " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static InputStream resource(String path) {
		return ModIdentityTest.class.getResourceAsStream(path);
	}
}
