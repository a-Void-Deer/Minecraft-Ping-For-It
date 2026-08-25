package nx.pingwheel.common.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetIdentityTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	@Test
	void entityIdentityIsStableByUuidAndDimension() {
		UUID uuid = UUID.randomUUID();

		Target.EntityTarget a = new Target.EntityTarget(OVERWORLD, uuid);
		Target.EntityTarget b = new Target.EntityTarget(OVERWORLD, uuid);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	void entityIdentityIsIndependentOfPositionByDesign() {
		UUID uuid = UUID.randomUUID();

		Target.EntityTarget a = new Target.EntityTarget(OVERWORLD, uuid);

		// EntityTarget has no position field, so identity can only vary by
		// dimension id or UUID; movement cannot produce a different identity.
		assertEquals(new Target.EntityTarget(OVERWORLD, uuid), a);
		assertNotEquals(new Target.EntityTarget(NETHER, uuid), a);
	}

	@Test
	void entityIdentityDiffersByUuidOrDimension() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		assertNotEquals(new Target.EntityTarget(OVERWORLD, first), new Target.EntityTarget(OVERWORLD, second));
		assertNotEquals(new Target.EntityTarget(OVERWORLD, first), new Target.EntityTarget(NETHER, first));
	}

	@Test
	void entityIdentityIncludesLocatorKind() {
		UUID uuid = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
		Target.EntityTarget uuidTarget = new Target.EntityTarget(OVERWORLD, EntityLocator.uuid(uuid));
		Target.EntityTarget runtimeTarget = new Target.EntityTarget(OVERWORLD, EntityLocator.runtimeId(42));

		assertNotEquals(uuidTarget, runtimeTarget);
		assertEquals(EntityLocator.runtimeId(42), runtimeTarget.locator());
	}

	@Test
	void blockIdentityEqualsForSameTypeAndPosition() {
		Target.BlockTarget a = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target.BlockTarget b = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	void blockIdentityDiffersByBlockRegistryId() {
		Target.BlockTarget stone = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");
		Target.BlockTarget dirt = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:dirt");

		assertNotEquals(stone, dirt);
	}

	@Test
	void blockIdentityDiffersByPositionOrDimension() {
		Target.BlockTarget a = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");

		assertNotEquals(a, new Target.BlockTarget(OVERWORLD, 1, 2, 4, "minecraft:stone"));
		assertNotEquals(a, new Target.BlockTarget(NETHER, 1, 2, 3, "minecraft:stone"));
	}

	@Test
	void externalBlockUsesBlockKindAndAllowsAnUncommittedCandidate() {
		Target.ExternalBlockTarget candidate = Target.ExternalBlockTarget.candidate(
			OVERWORLD, "provider:test", "minecraft:chest", "opaque-candidate", true);

		assertEquals(TargetKind.BLOCK, candidate.kind());
		assertTrue(candidate.isCandidate());
		assertEquals("provider:test", candidate.providerId());
		assertEquals("", candidate.stableTargetId());
		assertEquals("minecraft:chest", candidate.expectedBlockRegistryId());
		assertEquals("opaque-candidate", candidate.providerLocator());
		assertTrue(candidate.hasBlockEntity());
	}

	@Test
	void externalBlockEqualityUsesStableIdentityNotLocatorOrClassification() {
		Target.ExternalBlockTarget first = new Target.ExternalBlockTarget(
			OVERWORLD, "provider:test", "target-1", "minecraft:chest", "locator-a", true);
		Target.ExternalBlockTarget second = new Target.ExternalBlockTarget(
			OVERWORLD, "provider:test", "target-1", "minecraft:chest", "locator-b", false);

		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertTrue(first.isCommitted());
	}

	@Test
	void externalBlockValidatesOpaqueAndStableValues() {
		assertThrows(NullPointerException.class,
			() -> new Target.ExternalBlockTarget(OVERWORLD, null, "id", "minecraft:stone", "locator", false));
		assertThrows(IllegalArgumentException.class,
			() -> new Target.ExternalBlockTarget(OVERWORLD, " ", "id", "minecraft:stone", "locator", false));
		assertThrows(IllegalArgumentException.class,
			() -> new Target.ExternalBlockTarget(OVERWORLD, "provider:test", " ", "minecraft:stone", "locator", false));
		assertThrows(IllegalArgumentException.class,
			() -> new Target.ExternalBlockTarget(
				OVERWORLD, "provider:test", "id", "minecraft:stone",
				"x".repeat(Target.ExternalBlockTarget.MAX_PROVIDER_LOCATOR_LENGTH + 1), false));
	}

	@Test
	void locationIdentityUsesDimensionAndCoordinates() {
		Target.LocationTarget a = new Target.LocationTarget(OVERWORLD, 1.5, 2.5, 3.5);

		assertEquals(a, new Target.LocationTarget(OVERWORLD, 1.5, 2.5, 3.5));
		assertNotEquals(a, new Target.LocationTarget(OVERWORLD, 1.5, 2.5, 3.6));
		assertNotEquals(a, new Target.LocationTarget(NETHER, 1.5, 2.5, 3.5));
	}

	@Test
	void targetKindMatchesVariant() {
		assertEquals(TargetKind.ENTITY, new Target.EntityTarget(OVERWORLD, UUID.randomUUID()).kind());
		assertEquals(TargetKind.BLOCK, new Target.BlockTarget(OVERWORLD, 0, 0, 0, "minecraft:stone").kind());
		assertEquals(TargetKind.LOCATION, new Target.LocationTarget(OVERWORLD, 0, 0, 0).kind());
	}

	@Test
	void blockRegistryIdMustNotBeBlank() {
		assertThrows(IllegalArgumentException.class,
			() -> new Target.BlockTarget(OVERWORLD, 0, 0, 0, " "));
	}

	@Test
	void entityUuidMustNotBeNull() {
		assertThrows(NullPointerException.class,
			() -> new Target.EntityTarget(OVERWORLD, (EntityLocator)null));
	}

	@Test
	void dimensionIdIsExposedAsStableString() {
		Target.EntityTarget entity = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());
		Target.BlockTarget block = new Target.BlockTarget(NETHER, 0, 0, 0, "minecraft:stone");
		Target.LocationTarget location = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertEquals(OVERWORLD, entity.dimensionId());
		assertEquals(NETHER, block.dimensionId());
		assertEquals(OVERWORLD, location.dimensionId());
	}

	@Test
	void dimensionIdMustNotBeNull() {
		assertThrows(NullPointerException.class,
			() -> new Target.EntityTarget(null, UUID.randomUUID()));
		assertThrows(NullPointerException.class,
			() -> new Target.BlockTarget(null, 0, 0, 0, "minecraft:stone"));
		assertThrows(NullPointerException.class,
			() -> new Target.LocationTarget(null, 0, 0, 0));
	}

	@Test
	void dimensionIdMustNotBeBlank() {
		assertThrows(IllegalArgumentException.class,
			() -> new Target.EntityTarget(" ", UUID.randomUUID()));
		assertThrows(IllegalArgumentException.class,
			() -> new Target.BlockTarget(" ", 0, 0, 0, "minecraft:stone"));
		assertThrows(IllegalArgumentException.class,
			() -> new Target.LocationTarget(" ", 0, 0, 0));
	}

	@Test
	void locationCoordinatesMustBeFinite() {
		assertThrows(IllegalArgumentException.class,
			() -> new Target.LocationTarget(OVERWORLD, Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class,
			() -> new Target.LocationTarget(OVERWORLD, 0, Double.POSITIVE_INFINITY, 0));
		assertThrows(IllegalArgumentException.class,
			() -> new Target.LocationTarget(OVERWORLD, 0, 0, Double.NEGATIVE_INFINITY));
	}
}
