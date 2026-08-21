package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingForItVersionTest {
	@Test
	void normalizesOnlyThePingForItNamespaceMarker() {
		PingForItVersion version = new PingForItVersion("0.2.0-pfi-beta1");

		assertEquals("0.2.0-pfi-beta1", version.toString());
		assertEquals("0.2.0-pfi-beta1", version.originalVersion());
		assertEquals("0.2.0-beta1", version.normalizedVersion());
	}

	@Test
	void comparesNumericAndQualifierPartsThroughTheNormalizedMavenValue() {
		assertTrue(new PingForItVersion("0.2.0-pfi-beta1")
			.compareTo(new PingForItVersion("0.2.0-pfi-beta2")) < 0);
		assertTrue(new PingForItVersion("0.2.0-pfi-beta2")
			.compareTo(new PingForItVersion("0.2.0-pfi-beta10")) < 0);
		assertTrue(new PingForItVersion("0.1.0-pfi-beta1")
			.compareTo(new PingForItVersion("0.2.0-pfi-beta1")) < 0);
		assertTrue(new PingForItVersion("0.2.0-pfi-rc1")
			.compareTo(new PingForItVersion("0.2.0-pfi-final")) < 0);

		assertEquals(
			new PingForItVersion("0.2.0-pfi-final"),
			new PingForItVersion("0.2.0-pfi-release"));
		assertEquals(
			new PingForItVersion("0.2.0-pfi-final").hashCode(),
			new PingForItVersion("0.2.0-pfi-release").hashCode());
		assertEquals(
			new PingForItVersion("01.002.000-pfi-beta1"),
			new PingForItVersion("1.2.0-pfi-beta1"));
		assertEquals(
			new PingForItVersion("01.002.000-pfi-beta1").hashCode(),
			new PingForItVersion("1.2.0-pfi-beta1").hashCode());
		assertTrue(new PingForItVersion("999999999999999999999.0.0-pfi-beta1")
			.compareTo(new PingForItVersion("1000000000000000000000.0.0-pfi-beta1")) < 0);
	}

	@Test
	void pfiIsRemovedBeforeMavenSeesTheQualifier() {
		PingForItVersion beta = new PingForItVersion("0.2.0-pfi-beta1");

		assertEquals("0.2.0-beta1", beta.normalizedVersion());
		assertTrue(beta.compareTo(new PingForItVersion("0.2.0-pfi-final")) < 0);
	}

	@Test
	void rejectsVersionsOutsideTheStrictThreePartPfiFormat() {
		String[] invalidVersions = {
			"0.2-pfi-beta1",
			"0.2.0.1-pfi-beta1",
			"0.2.0-PFI-beta1",
			"0.2.0-pfi-",
			"0.2.0-pfi-beta-foo",
			"0.2.0-pfi-beta.foo",
			"0.2.0-pfi-beta/foo",
			"0.2.0-pfi-beta\u00A0foo",
			"0.2.0-pfi-beta\u2028foo",
			"0.2.0-pfi-βeta1",
			"0.2.0-pfi-beta foo",
			"0.2.0-beta1",
			"-1.2.0-pfi-beta1",
			"0.-2.0-pfi-beta1",
			"0.2.-1-pfi-beta1"
		};

		for (String invalidVersion : invalidVersions) {
			assertThrows(PingForItVersion.InvalidVersionException.class,
				() -> new PingForItVersion(invalidVersion));
		}

		PingForItVersion.InvalidVersionException nullVersion = assertThrows(
			PingForItVersion.InvalidVersionException.class,
			() -> new PingForItVersion(null));
		assertTrue(nullVersion.getMessage().contains("null"));
	}
}
