package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.EntityLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetKeyTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	@Test
	void entityKeyIdentityIsByUuidAndDimension() {
		UUID uuid = UUID.randomUUID();

		assertEquals(new TargetKey.EntityKey(OVERWORLD, uuid), new TargetKey.EntityKey(OVERWORLD, uuid));
		assertNotEquals(new TargetKey.EntityKey(OVERWORLD, uuid), new TargetKey.EntityKey(OVERWORLD, UUID.randomUUID()));
		assertNotEquals(new TargetKey.EntityKey(OVERWORLD, uuid), new TargetKey.EntityKey(NETHER, uuid));
	}

	@Test
	void entityKeyIdentityIncludesLocatorKind() {
		UUID uuid = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
		TargetKey.EntityKey uuidKey = new TargetKey.EntityKey(OVERWORLD, EntityLocator.uuid(uuid));
		TargetKey.EntityKey runtimeKey = new TargetKey.EntityKey(OVERWORLD, EntityLocator.runtimeId(42));

		assertNotEquals(uuidKey, runtimeKey);
		assertEquals(EntityLocator.runtimeId(42), runtimeKey.locator());
	}

	@Test
	void blockKeyIdentityIncludesBlockType() {
		assertEquals(
			new TargetKey.BlockKey(OVERWORLD, 1, 2, 3, "minecraft:stone"),
			new TargetKey.BlockKey(OVERWORLD, 1, 2, 3, "minecraft:stone"));
		assertNotEquals(
			new TargetKey.BlockKey(OVERWORLD, 1, 2, 3, "minecraft:stone"),
			new TargetKey.BlockKey(OVERWORLD, 1, 2, 3, "minecraft:dirt"));
	}

	@Test
	void blockKeyIdentityIncludesExactPositionAndDimension() {
		TargetKey.BlockKey a = new TargetKey.BlockKey(OVERWORLD, 1, 2, 3, "minecraft:stone");

		assertNotEquals(a, new TargetKey.BlockKey(OVERWORLD, 1, 2, 4, "minecraft:stone"));
		assertNotEquals(a, new TargetKey.BlockKey(OVERWORLD, 1, 3, 3, "minecraft:stone"));
		assertNotEquals(a, new TargetKey.BlockKey(OVERWORLD, 2, 2, 3, "minecraft:stone"));
		assertNotEquals(a, new TargetKey.BlockKey(NETHER, 1, 2, 3, "minecraft:stone"));
	}

	@Test
	void externalBlockKeyExcludesLocatorAndBlockEntityClassification() {
		Target.ExternalBlockTarget first = new Target.ExternalBlockTarget(
			OVERWORLD, "provider:test", "target-1", "minecraft:chest", "locator-a", true);
		Target.ExternalBlockTarget second = new Target.ExternalBlockTarget(
			OVERWORLD, "provider:test", "target-1", "minecraft:chest", "locator-b", false);

		TargetKey firstKey = TargetKey.from(first);
		TargetKey secondKey = TargetKey.from(second);

		assertEquals(firstKey, secondKey);
		assertEquals(
			new TargetKey.ExternalBlockKey(OVERWORLD, "provider:test", "target-1", "minecraft:chest"),
			firstKey);
	}

	@Test
	void externalBlockKeyIdentityIncludesStableProviderFields() {
		TargetKey base = new TargetKey.ExternalBlockKey(
			OVERWORLD, "provider:test", "target-1", "minecraft:chest");

		assertNotEquals(base, new TargetKey.ExternalBlockKey(
			NETHER, "provider:test", "target-1", "minecraft:chest"));
		assertNotEquals(base, new TargetKey.ExternalBlockKey(
			OVERWORLD, "provider:other", "target-1", "minecraft:chest"));
		assertNotEquals(base, new TargetKey.ExternalBlockKey(
			OVERWORLD, "provider:test", "target-2", "minecraft:chest"));
		assertNotEquals(base, new TargetKey.ExternalBlockKey(
			OVERWORLD, "provider:test", "target-1", "minecraft:stone"));
	}

	@Test
	void uncommittedExternalTargetHasNoTargetKey() {
		Target.ExternalBlockTarget candidate = Target.ExternalBlockTarget.candidate(
			OVERWORLD, "provider:test", "minecraft:chest", "locator", true);

		assertThrows(IllegalArgumentException.class, () -> TargetKey.from(candidate));
	}

	@Test
	void locationKeyIdentityUsesExactCoordinates() {
		assertEquals(
			new TargetKey.LocationKey(OVERWORLD, 1.5, 2.5, 3.5),
			new TargetKey.LocationKey(OVERWORLD, 1.5, 2.5, 3.5));
		assertNotEquals(
			new TargetKey.LocationKey(OVERWORLD, 1.5, 2.5, 3.5),
			new TargetKey.LocationKey(OVERWORLD, 1.5, 2.5, 3.6));
		assertNotEquals(
			new TargetKey.LocationKey(OVERWORLD, 1.5, 2.5, 3.5),
			new TargetKey.LocationKey(NETHER, 1.5, 2.5, 3.5));
	}

	@Test
	void fromEntityTargetProducesEntityKey() {
		UUID uuid = UUID.randomUUID();
		Target.EntityTarget target = new Target.EntityTarget(OVERWORLD, uuid);

		TargetKey key = TargetKey.from(target);

		assertInstanceOf(TargetKey.EntityKey.class, key);
		assertEquals(new TargetKey.EntityKey(OVERWORLD, uuid), key);
	}

	@Test
	void fromBlockTargetProducesBlockKeyWithBlockType() {
		Target.BlockTarget target = new Target.BlockTarget(OVERWORLD, 5, 6, 7, "minecraft:chest");

		TargetKey key = TargetKey.from(target);

		assertInstanceOf(TargetKey.BlockKey.class, key);
		assertEquals(new TargetKey.BlockKey(OVERWORLD, 5, 6, 7, "minecraft:chest"), key);
	}

	@Test
	void fromLocationTargetProducesLocationKey() {
		Target.LocationTarget target = new Target.LocationTarget(OVERWORLD, 0.5, 64.0, -8.25);

		TargetKey key = TargetKey.from(target);

		assertInstanceOf(TargetKey.LocationKey.class, key);
		assertEquals(new TargetKey.LocationKey(OVERWORLD, 0.5, 64.0, -8.25), key);
	}

	@Test
	void fromRejectsNull() {
		assertThrows(NullPointerException.class, () -> TargetKey.from(null));
	}

	@Test
	void entityKeyValidatesDimensionAndUuid() {
		assertThrows(NullPointerException.class, () -> new TargetKey.EntityKey(null, UUID.randomUUID()));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.EntityKey(" ", UUID.randomUUID()));
		assertThrows(NullPointerException.class, () -> new TargetKey.EntityKey(OVERWORLD, (EntityLocator)null));
	}

	@Test
	void blockKeyValidatesDimensionAndBlockRegistryId() {
		assertThrows(NullPointerException.class, () -> new TargetKey.BlockKey(null, 0, 0, 0, "minecraft:stone"));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.BlockKey(" ", 0, 0, 0, "minecraft:stone"));
		assertThrows(NullPointerException.class, () -> new TargetKey.BlockKey(OVERWORLD, 0, 0, 0, null));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.BlockKey(OVERWORLD, 0, 0, 0, " "));
	}

	@Test
	void externalBlockKeyValidatesCommittedIdentityFields() {
		assertThrows(NullPointerException.class,
			() -> new TargetKey.ExternalBlockKey(null, "provider:test", "target", "minecraft:stone"));
		assertThrows(IllegalArgumentException.class,
			() -> new TargetKey.ExternalBlockKey(OVERWORLD, " ", "target", "minecraft:stone"));
		assertThrows(IllegalArgumentException.class,
			() -> new TargetKey.ExternalBlockKey(OVERWORLD, "provider:test", " ", "minecraft:stone"));
		assertThrows(IllegalArgumentException.class,
			() -> new TargetKey.ExternalBlockKey(OVERWORLD, "provider:test", "target", " "));
	}

	@Test
	void locationKeyValidatesDimensionAndCoordinates() {
		assertThrows(NullPointerException.class, () -> new TargetKey.LocationKey(null, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.LocationKey(" ", 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.LocationKey(OVERWORLD, Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.LocationKey(OVERWORLD, 0, Double.POSITIVE_INFINITY, 0));
		assertThrows(IllegalArgumentException.class, () -> new TargetKey.LocationKey(OVERWORLD, 0, 0, Double.NEGATIVE_INFINITY));
	}
}
