package nx.pingwheel.common.domain;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class EntityCaptureMetadataTest {

	@Test
	void ordinaryUuidCaptureIsNotSpecial() {
		EntityCaptureMetadata metadata = new EntityCaptureMetadata(
			EntityLocator.Kind.UUID, false);

		assertEquals("uuid", metadata.locatorStrategy());
		assertFalse(metadata.isSpecialIdentity());
	}

	@Test
	void runtimeIdCaptureIsSpecialWithoutMultipartCanonicalization() {
		EntityCaptureMetadata metadata = new EntityCaptureMetadata(
			EntityLocator.Kind.RUNTIME_ID, false);

		assertEquals("runtime_id", metadata.locatorStrategy());
		assertTrue(metadata.isSpecialIdentity());
		assertFalse(metadata.canonicalizedMultipart());
	}

	@Test
	void multipartCaptureMetadataMarksCanonicalParentIdentity() {
		EntityCaptureMetadata firstPart = new EntityCaptureMetadata(
			EntityLocator.Kind.UUID, true);
		EntityCaptureMetadata secondPart = new EntityCaptureMetadata(
			EntityLocator.Kind.UUID, true);

		assertEquals(firstPart, secondPart);
		assertTrue(firstPart.isSpecialIdentity());
		assertTrue(firstPart.canonicalizedMultipart());
	}
}
