package nx.pingwheel.common.interaction;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.EntityCaptureMetadata;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetSnapshotTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID ENTITY_ID = UUID.randomUUID();

	@Test
	void entitySnapshotCarriesUuidIdentityAndEntityTypeContextWithoutPosition() {
		TargetSnapshot snapshot = TargetSnapshotFactory.entity(OVERWORLD, ENTITY_ID, "minecraft:item");

		assertTrue(snapshot.target() instanceof Target.EntityTarget, "expected entity target");

		Target.EntityTarget target = (Target.EntityTarget) snapshot.target();

		assertEquals(OVERWORLD, target.dimensionId());
		assertEquals(EntityLocator.uuid(ENTITY_ID), target.locator());
		assertEquals(TargetKind.ENTITY, target.kind());
		assertEquals(Optional.of("minecraft:item"), snapshot.matchContext().entityTypeId());
	}

	@Test
	void entitySnapshotIdentityIsStableByDesignAgainstMovementAndSameDimensionTeleport() {
		// The snapshot carries no position, so a same-dimension move or teleport
		// of the entity cannot change its identity.
		TargetSnapshot original = TargetSnapshotFactory.entity(OVERWORLD, ENTITY_ID, "minecraft:zombie");
		TargetSnapshot moved = TargetSnapshotFactory.entity(OVERWORLD, ENTITY_ID, "minecraft:zombie");

		assertEquals(original, moved);
	}

	@Test
	void entitySnapshotWithoutTypeUsesNoneContext() {
		TargetSnapshot snapshot = TargetSnapshotFactory.entity(OVERWORLD, ENTITY_ID);

		assertEquals(TargetMatchContext.none(), snapshot.matchContext());
	}

	@Test
	void blockSnapshotCarriesDimensionPositionAndRegistryIdButNoBlockState() {
		TargetSnapshot snapshot = TargetSnapshotFactory.block(OVERWORLD, 1, 2, 3, "minecraft:stone");

		assertTrue(snapshot.target() instanceof Target.BlockTarget, "expected block target");

		Target.BlockTarget target = (Target.BlockTarget) snapshot.target();

		assertEquals(OVERWORLD, target.dimensionId());
		assertEquals(1, target.x());
		assertEquals(2, target.y());
		assertEquals(3, target.z());
		assertEquals("minecraft:stone", target.blockRegistryId());
		assertEquals(TargetKind.BLOCK, target.kind());
		assertEquals(TargetMatchContext.none(), snapshot.matchContext());
	}

	@Test
	void blockSnapshotSameTypeAndPositionAreEqual() {
		TargetSnapshot first = TargetSnapshotFactory.block(OVERWORLD, 5, 6, 7, "minecraft:stone");
		TargetSnapshot second = TargetSnapshotFactory.block(OVERWORLD, 5, 6, 7, "minecraft:stone");

		assertEquals(first, second);
	}

	@Test
	void blockSnapshotReplacementTypeDiffers() {
		TargetSnapshot stone = TargetSnapshotFactory.block(OVERWORLD, 5, 6, 7, "minecraft:stone");
		TargetSnapshot dirt = TargetSnapshotFactory.block(OVERWORLD, 5, 6, 7, "minecraft:dirt");

		assertNotEquals(stone, dirt);
	}

	@Test
	void blockSnapshotDifferentPositionDiffers() {
		TargetSnapshot first = TargetSnapshotFactory.block(OVERWORLD, 5, 6, 7, "minecraft:stone");
		TargetSnapshot second = TargetSnapshotFactory.block(OVERWORLD, 5, 6, 8, "minecraft:stone");

		assertNotEquals(first, second);
	}

	@Test
	void externalBlockSnapshotCarriesOpaqueLocatorAndClassification() {
		TargetSnapshot snapshot = TargetSnapshotFactory.externalBlockCandidate(
			OVERWORLD, "provider:test", "minecraft:chest", "opaque-locator", true);

		assertTrue(snapshot.target() instanceof Target.ExternalBlockTarget, "expected external block target");

		Target.ExternalBlockTarget target = (Target.ExternalBlockTarget) snapshot.target();

		assertEquals(TargetKind.BLOCK, target.kind());
		assertEquals(OVERWORLD, target.dimensionId());
		assertEquals("provider:test", target.providerId());
		assertEquals("", target.stableTargetId());
		assertEquals("minecraft:chest", target.expectedBlockRegistryId());
		assertEquals("opaque-locator", target.providerLocator());
		assertEquals(Optional.of(true), snapshot.matchContext().blockHasBlockEntity());
	}

	@Test
	void externalBlockCommittedSnapshotKeepsStableIdentityAcrossLocatorUpdates() {
		TargetSnapshot first = TargetSnapshotFactory.externalBlockCommitted(
			OVERWORLD, "provider:test", "target-1", "minecraft:chest", "locator-a", true);
		TargetSnapshot second = TargetSnapshotFactory.externalBlockCommitted(
			OVERWORLD, "provider:test", "target-1", "minecraft:chest", "locator-b", false);

		assertEquals(first.target(), second.target());
		assertEquals(TargetKind.BLOCK, first.target().kind());
	}

	@Test
	void locationSnapshotSemantics() {
		TargetSnapshot snapshot = TargetSnapshotFactory.location(OVERWORLD, 1.5, 2.5, 3.5);

		assertTrue(snapshot.target() instanceof Target.LocationTarget, "expected location target");

		Target.LocationTarget target = (Target.LocationTarget) snapshot.target();

		assertEquals(OVERWORLD, target.dimensionId());
		assertEquals(1.5, target.x());
		assertEquals(2.5, target.y());
		assertEquals(3.5, target.z());
		assertEquals(TargetKind.LOCATION, target.kind());
		assertEquals(TargetMatchContext.none(), snapshot.matchContext());
	}

	@Test
	void locationSnapshotRejectsNonFiniteCoordinates() {
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.location(OVERWORLD, Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.location(OVERWORLD, 0, Double.POSITIVE_INFINITY, 0));
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.location(OVERWORLD, 0, 0, Double.NEGATIVE_INFINITY));
	}

	@Test
	void targetSnapshotRejectsNullFields() {
		Target target = new Target.LocationTarget(OVERWORLD, 0, 0, 0);

		assertThrows(NullPointerException.class, () -> new TargetSnapshot(null, TargetMatchContext.none()));
		assertThrows(NullPointerException.class, () -> new TargetSnapshot(target, null));
	}

	@Test
	void capturedPingContextRejectsNullFields() {
		ResolvedTarget resolved = resolvedLocation(OVERWORLD);
		InteractionToken token = new ActiveInteraction().begin();

		assertThrows(NullPointerException.class, () -> new CapturedPingContext(null, resolved));
		assertThrows(NullPointerException.class, () -> new CapturedPingContext(token, null));
		assertThrows(NullPointerException.class, () -> new CapturedPingContext(token, resolved, null));
	}

	@Test
	void entitySnapshotCanCarryRuntimeLocatorAndCaptureMetadata() {
		EntityCaptureMetadata metadata = new EntityCaptureMetadata(
			EntityLocator.Kind.RUNTIME_ID, false);
		TargetSnapshot snapshot = TargetSnapshotFactory.entity(
			OVERWORLD, EntityLocator.runtimeId(23), "minecraft:experience_orb", metadata);

		assertEquals(new Target.EntityTarget(OVERWORLD, EntityLocator.runtimeId(23)), snapshot.target());
		assertEquals(Optional.of("minecraft:experience_orb"), snapshot.matchContext().entityTypeId());
		assertEquals(Optional.of(metadata), snapshot.entityCaptureMetadata());
	}

	@Test
	void capturedPingContextIsAnImmutableRecordOfTokenAndResolvedTarget() {
		ResolvedTarget resolved = resolvedLocation(OVERWORLD);
		InteractionToken token = new ActiveInteraction().begin();
		CapturedRay ray = new CapturedRay(
			new nx.pingwheel.common.interaction.cancel.WorldVector(1, 2, 3),
			new nx.pingwheel.common.interaction.cancel.WorldVector(0, 1, 0));

		CapturedPingContext context = new CapturedPingContext(token, resolved, ray);

		assertSame(token, context.token());
		assertSame(resolved, context.resolvedTarget());
		assertSame(ray, context.ray());
	}

	@Test
	void factoryRejectsBlankOrInvalidIdentityValues() {
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.entity("", ENTITY_ID, "minecraft:item"));
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.entity(OVERWORLD, ENTITY_ID, " "));
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.block(" ", 0, 0, 0, "minecraft:stone"));
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.block(OVERWORLD, 0, 0, 0, " "));
		assertThrows(IllegalArgumentException.class,
			() -> TargetSnapshotFactory.location("", 0, 0, 0));
		assertThrows(NullPointerException.class,
			() -> TargetSnapshotFactory.entity(OVERWORLD, null, "minecraft:item"));
	}

	@Test
	void entitySnapshotDoesNotCarryPosition() {
		TargetSnapshot snapshot = TargetSnapshotFactory.entity(OVERWORLD, ENTITY_ID, "minecraft:item");

		assertFalse(snapshot.target() instanceof Target.LocationTarget);
		assertFalse(snapshot.target() instanceof Target.BlockTarget);
	}

	private static ResolvedTarget resolvedLocation(String dimensionId) {
		return new ResolvedTarget(
			new Target.LocationTarget(dimensionId, 0, 0, 0),
			TargetTypeCatalog.builtIn().findById("location").orElseThrow());
	}
}
