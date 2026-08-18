package nx.pingwheel.common.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
