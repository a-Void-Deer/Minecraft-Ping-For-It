package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreatePresentationExternalBlockTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void externalCaptureAcceptsOnlyAuthoritativeDetachedMetadata() {
		Target.ExternalBlockTarget external = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "stable-id", "minecraft:chest", "opaque-locator", true);
		PresentationAdapter.DetachedTarget detached = new PresentationAdapter.DetachedTarget(
			external.dimensionId(), "block", external.expectedBlockRegistryId(), 0, 0, 0, "", external);

		assertTrue(CreatePresentationAdapters.validExternalMetadata(detached, external));
	}

	@Test
	void externalCaptureRejectsOpaqueLocatorOrMismatchedDetachedMetadata() {
		Target.ExternalBlockTarget external = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "stable-id", "minecraft:chest", "opaque-locator", true);
		PresentationAdapter.DetachedTarget opaque = new PresentationAdapter.DetachedTarget(
			external.dimensionId(), "block", external.expectedBlockRegistryId(), 0, 0, 0, "opaque", external);
		PresentationAdapter.DetachedTarget mismatched = new PresentationAdapter.DetachedTarget(
			external.dimensionId(), "block", "minecraft:furnace", 0, 0, 0, "", external);

		assertFalse(CreatePresentationAdapters.validExternalMetadata(opaque, external));
		assertFalse(CreatePresentationAdapters.validExternalMetadata(mismatched, external));
	}

	@Test
	void observeSourceUsesProviderLocalPositionAndForwardsDemandAndBudget() {
		Target.ExternalBlockTarget external = committedTarget();
		PresentationAdapter.DetachedTarget detached = detached(external);
		RecordingSourceAccess access = new RecordingSourceAccess();
		Set<String> demand = Set.of(CreatePresentationAdapter.SPEED, CreatePresentationAdapter.INVENTORY,
			CreatePresentationAdapter.FLUID);
		PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(4);

		CreatePresentationAdapter.Observation observed = CreatePresentationAdapters.observeSource(
			detached, demand, budget, access);

		assertSame(access.observation, observed);
		assertEquals(new BlockPos(18, 64, -4), access.collectedPosition);
		assertEquals(demand, access.collectedDemand);
		assertSame(budget, access.collectedBudget);
		assertEquals(3, budget.remaining(), "the source lookup consumes one scan before collection");
		assertEquals(1, access.externalObservations);
		assertEquals(0, access.ordinaryMatchCalls);
		assertEquals("minecraft:overworld", access.resolvedDimension);
	}

	@Test
	void observeSourceUsesDetachedCoordinatesForOrdinaryBlocksWithoutExternalObservation() {
		PresentationAdapter.DetachedTarget ordinary = new PresentationAdapter.DetachedTarget(
			"minecraft:overworld", "block", "minecraft:chest", 7, 65, -2, "", null);
		RecordingSourceAccess access = new RecordingSourceAccess();
		PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(3);

		CreatePresentationAdapters.observeSource(ordinary, Set.of(CreatePresentationAdapter.SPEED), budget, access);

		assertEquals(new BlockPos(7, 65, -2), access.collectedPosition);
		assertEquals(0, access.externalObservations);
		assertEquals(1, access.ordinaryMatchCalls);
		assertEquals(2, budget.remaining());
	}

	@Test
	void observeSourceRejectsUnavailableOrMismatchedInputsBeforeDownstreamSampling() {
		Target.ExternalBlockTarget external = committedTarget();
		PresentationAdapter.DetachedTarget detached = detached(external);

		RecordingSourceAccess unavailable = new RecordingSourceAccess();
		unavailable.available = false;
		PresentationAdapter.CaptureBudget unavailableBudget = new PresentationAdapter.CaptureBudget(3);
		assertNull(CreatePresentationAdapters.observeSource(detached, Set.of(CreatePresentationAdapter.SPEED),
			unavailableBudget, unavailable));
		assertEquals(0, unavailable.externalObservations);
		assertNull(unavailable.collectedPosition);
		assertEquals(3, unavailableBudget.remaining());

		RecordingSourceAccess denied = new RecordingSourceAccess();
		denied.externalResult = new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();
		PresentationAdapter.CaptureBudget deniedBudget = new PresentationAdapter.CaptureBudget(3);
		assertNull(CreatePresentationAdapters.observeSource(detached, Set.of(CreatePresentationAdapter.SPEED),
			deniedBudget, denied));
		assertEquals(1, denied.externalObservations);
		assertNull(denied.collectedPosition);
		assertEquals(2, deniedBudget.remaining());

		RecordingSourceAccess ordinaryMismatch = new RecordingSourceAccess();
		ordinaryMismatch.ordinaryMatchResult = false;
		PresentationAdapter.CaptureBudget ordinaryBudget = new PresentationAdapter.CaptureBudget(3);
		PresentationAdapter.DetachedTarget ordinary = new PresentationAdapter.DetachedTarget(
			"minecraft:overworld", "block", "minecraft:chest", 7, 65, -2, "", null);
		assertNull(CreatePresentationAdapters.observeSource(ordinary, Set.of(CreatePresentationAdapter.SPEED),
			ordinaryBudget, ordinaryMismatch));
		assertEquals(1, ordinaryMismatch.ordinaryMatchCalls);
		assertNull(ordinaryMismatch.collectedPosition);
		assertEquals(2, ordinaryBudget.remaining());
	}

	private static Target.ExternalBlockTarget committedTarget() {
		return Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "stable-id", "minecraft:chest", "opaque-locator", true);
	}

	private static PresentationAdapter.DetachedTarget detached(Target.ExternalBlockTarget external) {
		return new PresentationAdapter.DetachedTarget(
			external.dimensionId(), "block", external.expectedBlockRegistryId(), 0, 0, 0, "", external);
	}

	private static final class RecordingSourceAccess implements CreatePresentationAdapters.SourceAccess {
		private final CreatePresentationAdapters.SourceWorld world =
			new CreatePresentationAdapters.SourceWorld(new Object(), "minecraft:overworld");
		private final CreatePresentationAdapter.Observation observation =
			new CreatePresentationAdapter.Observation(null, null, null, null, null, null, null);
		private ExternalBlockServerProvider.ObservationResult externalResult =
			new ExternalBlockServerProvider.ObservationResult.Available(
				new ExternalBlockServerProvider.BlockObservation(new Object(), new BlockPos(18, 64, -4),
					Blocks.STONE.defaultBlockState()));
		private boolean available = true;
		private boolean ordinaryMatchResult = true;
		private String resolvedDimension;
		private BlockPos collectedPosition;
		private Set<String> collectedDemand;
		private PresentationAdapter.CaptureBudget collectedBudget;
		private int externalObservations;
		private int ordinaryMatchCalls;

		@Override
		public boolean serverThread() { return true; }

		@Override
		public boolean available() { return available; }

		@Override
		public CreatePresentationAdapters.SourceWorld resolveWorld(String dimensionId) {
			resolvedDimension = dimensionId;
			return world;
		}

		@Override
		public ExternalBlockServerProvider.ObservationResult observeExternal(
			CreatePresentationAdapters.SourceWorld world, Target.ExternalBlockTarget target) {
			externalObservations++;
			return externalResult;
		}

		@Override
		public boolean matchesOrdinary(CreatePresentationAdapters.SourceWorld world, BlockPos position,
			String expectedRegistryId) {
			ordinaryMatchCalls++;
			return ordinaryMatchResult;
		}

		@Override
		public CreatePresentationAdapter.Observation collect(CreatePresentationAdapters.SourceWorld world,
			BlockPos position, Set<String> demand, PresentationAdapter.CaptureBudget budget) {
			collectedPosition = position;
			collectedDemand = demand;
			collectedBudget = budget;
			return observation;
		}
	}
}
