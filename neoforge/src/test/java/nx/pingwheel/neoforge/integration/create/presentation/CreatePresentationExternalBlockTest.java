package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
		assertSame(external, access.resolvedCommittedTarget, "the detached target is not replaced by physical coordinates");
		assertSame(access.committedBinding.memberGate(), access.collectedGate);
		assertEquals(3, budget.remaining(), "the source lookup consumes one scan before collection");
		assertEquals(1, access.committedResolutions);
		assertEquals(1, access.scopedCollects);
		assertEquals(0, access.unscopedCollects, "committed external collection cannot drop the member gate");
		assertEquals(0, access.externalObservations, "the legacy observation route is not a scoped source");
		assertEquals(0, access.previewResolutions);
		assertEquals(0, access.previewCollects);
		assertEquals(1, access.ordinaryMatchCalls);
		assertEquals("minecraft:overworld", access.resolvedDimension);
	}

	@Test
	void observeSourceUsesCurrentCommittedPositionRatherThanPlaceholderOrLegacyObservation() {
		Target.ExternalBlockTarget staleLocator = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "stable-id", "minecraft:chest", "stale-opaque-locator", true);
		RecordingSourceAccess access = new RecordingSourceAccess();
		BlockPos current = new BlockPos(90, 72, -30);
		access.committedBinding = new CreatePresentationAdapters.CommittedBinding(current, current::equals);
		access.externalResult = new ExternalBlockServerProvider.ObservationResult.TemporarilyUnavailable();

		assertSame(access.observation, CreatePresentationAdapters.observeSource(detached(staleLocator),
			Set.of(CreatePresentationAdapter.INVENTORY), new PresentationAdapter.CaptureBudget(4), access));
		assertSame(staleLocator, access.resolvedCommittedTarget);
		assertEquals(current, access.collectedPosition);
		assertEquals(0, access.externalObservations);
		assertEquals(0, access.previewResolutions);
	}

	@Test
	void observeSourceRejectsCommittedRootScopeBeforeStateMatchOrCollection() {
		for (boolean throwing : new boolean[] { false, true }) {
			RecordingSourceAccess access = new RecordingSourceAccess();
			access.committedBinding = new CreatePresentationAdapters.CommittedBinding(
				new BlockPos(18, 64, -4), position -> {
					if (throwing) throw new IllegalStateException("scope");
					return false;
				});
			PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(4);

			assertNull(CreatePresentationAdapters.observeSource(detached(committedTarget()),
				Set.of(CreatePresentationAdapter.INVENTORY), budget, access));
			assertEquals(1, access.committedResolutions);
			assertEquals(0, access.ordinaryMatchCalls, "root membership precedes the state match");
			assertEquals(0, access.scopedCollects);
			assertEquals(0, access.unscopedCollects);
			assertEquals(0, access.externalObservations);
			assertEquals(3, budget.remaining());
		}
	}

	@Test
	void observeSourceForwardsGateThatRejectsControllerAndOtherMembersBeforeReads() {
		RecordingSourceAccess access = new RecordingSourceAccess();
		BlockPos root = new BlockPos(18, 64, -4);
		BlockPos controller = root.offset(-1, 0, 0);
		BlockPos foreignMember = root.offset(1, 0, 0);
		access.committedBinding = new CreatePresentationAdapters.CommittedBinding(root, root::equals);
		assertSame(access.observation, CreatePresentationAdapters.observeSource(detached(committedTarget()),
			Set.of(CreatePresentationAdapter.INVENTORY, CreatePresentationAdapter.FLUID),
			new PresentationAdapter.CaptureBudget(4), access));

		assertSame(access.committedBinding.memberGate(), access.collectedGate);
		assertTrue(CreateSamplingWork.member(access.collectedGate, root));
		assertFalse(CreateSamplingWork.member(access.collectedGate, controller));
		AtomicInteger memberReads = new AtomicInteger();
		CreateSamplingWork work = new CreateSamplingWork(2);
		assertFalse(work.verifyShape(root, 2, 1, 1,
			new CreateSamplingLimits(128, 128, 16, 16, 256, 2048), access.collectedGate, part -> {
				memberReads.incrementAndGet();
				return true;
			}));
		assertFalse(access.collectedGate.test(foreignMember));
		assertEquals(1, memberReads.get(), "the production verification helper never reads the rejected member");
		assertEquals(2, work.used(), "the rejected member is still paid");
	}

	@Test
	void compatibleSourceAccessDefaultsNeverFallBackToLegacyObservationOrDropAScope() {
		RecordingSourceAccess recording = new RecordingSourceAccess();
		CreatePresentationAdapters.SourceAccess legacy = new CreatePresentationAdapters.SourceAccess() {
			@Override public boolean serverThread() { return recording.serverThread(); }
			@Override public boolean available() { return recording.available(); }
			@Override public CreatePresentationAdapters.SourceWorld resolveWorld(String dimensionId) {
				return recording.resolveWorld(dimensionId);
			}
			@Override public ExternalBlockServerProvider.ObservationResult observeExternal(
				CreatePresentationAdapters.SourceWorld world, Target.ExternalBlockTarget target) {
				return recording.observeExternal(world, target);
			}
			@Override public CreatePresentationAdapters.PreviewBinding resolvePreviewExternal(
				CreatePresentationAdapters.SourceWorld world, Target.ExternalBlockTarget candidate) {
				return recording.resolvePreviewExternal(world, candidate);
			}
			@Override public boolean matchesOrdinary(CreatePresentationAdapters.SourceWorld world,
				BlockPos position, String expectedRegistryId) {
				return recording.matchesOrdinary(world, position, expectedRegistryId);
			}
			@Override public CreatePresentationAdapter.Observation collect(CreatePresentationAdapters.SourceWorld world,
				BlockPos position, Set<String> demand, PresentationAdapter.CaptureBudget budget) {
				return recording.collect(world, position, demand, budget);
			}
			@Override public CreatePresentationAdapter.Observation collectPreview(CreatePresentationAdapters.SourceWorld world,
				BlockPos position, Set<String> demand, PresentationAdapter.CaptureBudget budget,
				java.util.function.Predicate<BlockPos> memberGate) {
				return recording.collectPreview(world, position, demand, budget, memberGate);
			}
		};
		Set<String> demand = Set.of(CreatePresentationAdapter.INVENTORY);
		PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(4);
		assertNull(CreatePresentationAdapters.observeSource(detached(committedTarget()), demand, budget, legacy));
		assertEquals(0, recording.externalObservations, "a missing scoped source cannot use legacy observation");
		assertEquals(0, recording.unscopedCollects);
		assertNull(legacy.collect(recording.world, new BlockPos(18, 64, -4), demand, budget, position -> true));
		assertEquals(0, recording.unscopedCollects, "a legacy collector cannot silently discard a member gate");
		assertSame(recording.observation, legacy.collect(recording.world,
			new BlockPos(7, 65, -2), demand, budget, null));
		assertEquals(1, recording.unscopedCollects, "the unscoped ordinary overload remains compatible");
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
		assertEquals(0, access.committedResolutions);
		assertEquals(0, access.previewResolutions);
		assertEquals(0, access.scopedCollects);
		assertEquals(1, access.unscopedCollects);
		assertNull(access.collectedGate);
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
		assertEquals(0, unavailable.committedResolutions);
		assertNull(unavailable.collectedPosition);
		assertEquals(3, unavailableBudget.remaining());

		RecordingSourceAccess denied = new RecordingSourceAccess();
		denied.committedBinding = null;
		PresentationAdapter.CaptureBudget deniedBudget = new PresentationAdapter.CaptureBudget(3);
		assertNull(CreatePresentationAdapters.observeSource(detached, Set.of(CreatePresentationAdapter.SPEED),
			deniedBudget, denied));
		assertEquals(1, denied.committedResolutions);
		assertEquals(0, denied.externalObservations, "an unavailable source never falls back to legacy observation");
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

		RecordingSourceAccess externalMismatch = new RecordingSourceAccess();
		externalMismatch.ordinaryMatchResult = false;
		assertNull(CreatePresentationAdapters.observeSource(detached, Set.of(CreatePresentationAdapter.SPEED),
			new PresentationAdapter.CaptureBudget(3), externalMismatch));
		assertEquals(1, externalMismatch.committedResolutions);
		assertEquals(1, externalMismatch.ordinaryMatchCalls);
		assertEquals(0, externalMismatch.scopedCollects);

		RecordingSourceAccess candidate = new RecordingSourceAccess();
		assertNull(CreatePresentationAdapters.observeSource(detached(candidateTarget()),
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(4), candidate));
		assertEquals(0, candidate.committedResolutions);
		assertEquals(0, candidate.previewResolutions, "committed capture cannot resolve a candidate source");

		for (int allowance : new int[] { 0, 1 }) {
			RecordingSourceAccess exhausted = new RecordingSourceAccess();
			assertNull(CreatePresentationAdapters.observeSource(detached, Set.of(CreatePresentationAdapter.SPEED),
				new PresentationAdapter.CaptureBudget(allowance), exhausted));
			assertEquals(0, exhausted.committedResolutions, "admission precedes committed source resolution");
		}
	}

	@Test
	void previewSourceUsesProviderResolvedLocalPositionAndOriginalDetachedMetadata() {
		Target.ExternalBlockTarget candidate = candidateTarget();
		RecordingSourceAccess access = new RecordingSourceAccess();
		PresentationAdapter.CaptureBudget budget = new PresentationAdapter.CaptureBudget(4);

		CreatePresentationAdapter.Observation observed = CreatePresentationAdapters.observePreviewSource(
			detached(candidate), Set.of(CreatePresentationAdapter.SPEED), budget, access);

		assertSame(access.previewObservation, observed);
		assertEquals(new BlockPos(18, 64, -4), access.collectedPreviewPosition);
		assertSame(access.previewBinding.memberGate(), access.collectedPreviewGate);
		assertEquals(1, access.previewResolutions);
		assertEquals(1, access.previewCollects);
		assertEquals(3, budget.remaining(), "the source lookup consumes one scan before collection");
		assertEquals(0, access.externalObservations);
		assertEquals(0, access.committedResolutions);
		assertEquals(0, access.scopedCollects);
		assertEquals(1, access.ordinaryMatchCalls);
		assertEquals("minecraft:overworld", access.resolvedDimension);
	}

	@Test
	void previewSourceRejectsForeignMetadataOrMemberScopeBeforeCollection() {
		RecordingSourceAccess nonCandidate = new RecordingSourceAccess();
		assertNull(CreatePresentationAdapters.observePreviewSource(detached(committedTarget()),
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(4), nonCandidate));
		assertEquals(0, nonCandidate.previewResolutions);

		Target.ExternalBlockTarget candidate = candidateTarget();
		assertTrue(CreatePresentationAdapters.validPreviewExternalMetadata(detached(candidate), candidate),
			"the opaque provider locator stays on the external target; only the detached placeholder is empty");
		PresentationAdapter.DetachedTarget placeholder = new PresentationAdapter.DetachedTarget(
			candidate.dimensionId(), "block", candidate.expectedBlockRegistryId(), 5, 6, 7, "", candidate);
		assertFalse(CreatePresentationAdapters.validPreviewExternalMetadata(placeholder, candidate));
		PresentationAdapter.DetachedTarget opaquePlaceholder = new PresentationAdapter.DetachedTarget(
			candidate.dimensionId(), "block", candidate.expectedBlockRegistryId(), 0, 0, 0, "opaque", candidate);
		assertFalse(CreatePresentationAdapters.validPreviewExternalMetadata(opaquePlaceholder, candidate));
		RecordingSourceAccess placeholderAccess = new RecordingSourceAccess();
		assertNull(CreatePresentationAdapters.observePreviewSource(placeholder,
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(4), placeholderAccess));
		assertEquals(0, placeholderAccess.previewResolutions);

		RecordingSourceAccess foreignScope = new RecordingSourceAccess();
		foreignScope.previewBinding = new CreatePresentationAdapters.PreviewBinding(
			new BlockPos(18, 64, -4), position -> false);
		assertNull(CreatePresentationAdapters.observePreviewSource(detached(candidate),
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(4), foreignScope));
		assertEquals(1, foreignScope.previewResolutions);
		assertEquals(0, foreignScope.previewCollects, "member scope crossing is rejected before any member read");
		assertEquals(0, foreignScope.ordinaryMatchCalls);

		RecordingSourceAccess throwingScope = new RecordingSourceAccess();
		throwingScope.previewBinding = new CreatePresentationAdapters.PreviewBinding(
			new BlockPos(18, 64, -4), position -> { throw new IllegalStateException("scope"); });
		assertNull(CreatePresentationAdapters.observePreviewSource(detached(candidate),
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(4), throwingScope));
		assertEquals(0, throwingScope.previewCollects, "a failing member gate stays fail-closed before any read");

		RecordingSourceAccess missing = new RecordingSourceAccess();
		missing.previewBinding = null;
		assertNull(CreatePresentationAdapters.observePreviewSource(detached(candidate),
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(4), missing));
		assertEquals(0, missing.previewCollects);

		RecordingSourceAccess exhausted = new RecordingSourceAccess();
		assertNull(CreatePresentationAdapters.observePreviewSource(detached(candidate),
			Set.of(CreatePresentationAdapter.SPEED), new PresentationAdapter.CaptureBudget(1), exhausted));
		assertEquals(0, exhausted.previewResolutions, "provider resolution is admitted before any source lookup");
	}

	private static Target.ExternalBlockTarget candidateTarget() {
		return Target.ExternalBlockTarget.candidate(
			"minecraft:overworld", "sable", "minecraft:chest", "opaque-locator", true);
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
		private final CreatePresentationAdapter.Observation previewObservation =
			new CreatePresentationAdapter.Observation(null, null, null, null, null, null, null);
		private ExternalBlockServerProvider.ObservationResult externalResult =
			new ExternalBlockServerProvider.ObservationResult.Available(
				new ExternalBlockServerProvider.BlockObservation(new Object(), new BlockPos(18, 64, -4),
					Blocks.STONE.defaultBlockState()));
		private CreatePresentationAdapters.PreviewBinding previewBinding =
			new CreatePresentationAdapters.PreviewBinding(new BlockPos(18, 64, -4), position -> true);
		private CreatePresentationAdapters.CommittedBinding committedBinding =
			new CreatePresentationAdapters.CommittedBinding(new BlockPos(18, 64, -4), position -> true);
		private boolean available = true;
		private boolean ordinaryMatchResult = true;
		private String resolvedDimension;
		private BlockPos collectedPosition;
		private Set<String> collectedDemand;
		private PresentationAdapter.CaptureBudget collectedBudget;
		private java.util.function.Predicate<BlockPos> collectedGate;
		private Target.ExternalBlockTarget resolvedCommittedTarget;
		private BlockPos collectedPreviewPosition;
		private java.util.function.Predicate<BlockPos> collectedPreviewGate;
		private int externalObservations;
		private int committedResolutions;
		private int scopedCollects;
		private int unscopedCollects;
		private int ordinaryMatchCalls;
		private int previewResolutions;
		private int previewCollects;

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
		public CreatePresentationAdapters.CommittedBinding resolveCommittedExternal(
			CreatePresentationAdapters.SourceWorld world, Target.ExternalBlockTarget target) {
			committedResolutions++;
			resolvedCommittedTarget = target;
			return committedBinding;
		}

		@Override
		public CreatePresentationAdapters.PreviewBinding resolvePreviewExternal(
			CreatePresentationAdapters.SourceWorld world, Target.ExternalBlockTarget candidate) {
			previewResolutions++;
			return previewBinding;
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
			unscopedCollects++;
			collectedPosition = position;
			collectedDemand = demand;
			collectedBudget = budget;
			return observation;
		}

		@Override
		public CreatePresentationAdapter.Observation collect(CreatePresentationAdapters.SourceWorld world,
			BlockPos position, Set<String> demand, PresentationAdapter.CaptureBudget budget,
			java.util.function.Predicate<BlockPos> memberGate) {
			scopedCollects++;
			collectedPosition = position;
			collectedDemand = demand;
			collectedBudget = budget;
			collectedGate = memberGate;
			return observation;
		}

		@Override
		public CreatePresentationAdapter.Observation collectPreview(CreatePresentationAdapters.SourceWorld world,
			BlockPos position, Set<String> demand, PresentationAdapter.CaptureBudget budget,
			java.util.function.Predicate<BlockPos> memberGate) {
			previewCollects++;
			collectedPreviewPosition = position;
			collectedPreviewGate = memberGate;
			return previewObservation;
		}
	}
}
