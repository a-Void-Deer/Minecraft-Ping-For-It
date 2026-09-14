package nx.pingwheel.neoforge.integration.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;
import nx.pingwheel.common.math.LocalGeometryKind;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.neoforge.NeoClient;

class CreateContraptionRaycastAdapterTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void shellClaimsExactlyFourPinnedCreateEntityIds() {
		assertEquals(Set.of(
			"create:stationary_contraption",
			"create:contraption",
			"create:carriage_contraption",
			"create:gantry_contraption"),
			CreateContraptionRaycastAdapter.claimedEntityTypeIds().stream()
				.map(Object::toString)
				.collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void neoClientKeepsTheCreateFreeShellBehindStringReflection() throws Exception {
		Class.forName(NeoClient.class.getName(), true, NeoClient.class.getClassLoader());
		try (InputStream bytes = NeoClient.class.getResourceAsStream("NeoClient.class")) {
			assertTrue(bytes != null);
			String classFile = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(classFile.contains("nx.pingwheel.neoforge.integration.create.CreateContraptionRaycastAdapter"));
			assertFalse(classFile.contains(
				"nx/pingwheel/neoforge/integration/create/CreateContraptionRaycastAdapter"));
			assertFalse(classFile.contains("com/simibubi/create"));
			assertFalse(classFile.contains("dev/engine_room/flywheel"));
		}
	}

	@Test
	void createFreeShellLoadsDelegateOnlyOnFirstTraceAndDoesNotRetryFailure() throws IOException {
		AtomicInteger loads = new AtomicInteger();
		CreateContraptionRaycastAdapter adapter = new CreateContraptionRaycastAdapter(() -> {
			loads.incrementAndGet();
			throw new ReflectiveOperationException("missing optional delegate");
		}, (message, failure) -> {});

		assertEquals(0, loads.get());
		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, adapter.trace(null, request()).outcome());
		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE,
			adapter.trace(null, request()).outcome());
		assertEquals(1, loads.get());

		try (InputStream bytes = CreateContraptionRaycastAdapter.class.getResourceAsStream(
			"CreateContraptionRaycastAdapter.class")) {
			assertTrue(bytes != null);
			String classFile = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
			assertFalse(classFile.contains("com/simibubi/create"));
		assertFalse(classFile.contains("dev/engine_room/flywheel"));
		}
	}

	@Test
	void resetAllowsTheUnavailableShellToLoadAgainForTheNextClientSession() {
		AtomicInteger loads = new AtomicInteger();
		CreateContraptionRaycastAdapter adapter = new CreateContraptionRaycastAdapter(() -> {
			loads.incrementAndGet();
			throw new ReflectiveOperationException("missing optional delegate");
		}, (message, failure) -> {});

		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, adapter.trace(null, request()).outcome());
		adapter.reset();
		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, adapter.trace(null, request()).outcome());
		assertEquals(2, loads.get());
	}

	@Test
	void shellLoadFailureIsUnavailableAndLogsOneBoundedFullDiagnostic() {
		AtomicInteger loads = new AtomicInteger();
		List<String> messages = new ArrayList<>();
		List<Throwable> failures = new ArrayList<>();
		LinkageError failure = new LinkageError("missing optional Create linkage");
		CreateContraptionRaycastAdapter adapter = new CreateContraptionRaycastAdapter(() -> {
			loads.incrementAndGet();
			throw failure;
		}, (message, reportedFailure) -> {
			messages.add(message);
			failures.add(reportedFailure);
		});

		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, adapter.trace(null, request()).outcome());
		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, adapter.trace(null, request()).outcome());
		assertEquals(1, loads.get());
		assertEquals(1, messages.size(), "one entity/category key must be rate limited");
		assertEquals(1, failures.size());
		assertSame(failure, failures.get(0));

		String diagnostic = messages.get(0);
		assertTrue(diagnostic.contains("sourceId=pingforit:create_contraption_raycast"));
		assertTrue(diagnostic.contains("delegateClass=nx.pingwheel.neoforge.integration.create.CreateContraptionRaycastDelegate"));
		assertTrue(diagnostic.contains("category=delegate-load-linkage"));
		assertTrue(diagnostic.contains("entityRegistry=<null>"));
		assertTrue(diagnostic.contains("entityClass=<null>"));
		assertTrue(diagnostic.contains("entityUuid=<null>"));
		assertTrue(diagnostic.contains("entityPosition=<null>"));
		assertTrue(diagnostic.contains("requestStart=" + request().start()));
		assertTrue(diagnostic.contains("requestEnd=" + request().end()));
		assertTrue(diagnostic.contains("policy=" + request().policy()));
		assertTrue(diagnostic.contains("partialTick=" + request().partialTick()));
	}

	@Test
	void structuralDelegateFailureBecomesUnavailableWithoutRetryingTheQuarantinedDelegate() {
		AtomicInteger traces = new AtomicInteger();
		List<String> messages = new ArrayList<>();
		AssertionError failure = new AssertionError("incompatible optional delegate");
		CreateContraptionRaycastAdapter adapter = new CreateContraptionRaycastAdapter(
			() -> (candidate, raycastRequest) -> {
				traces.incrementAndGet();
				throw failure;
			},
			(message, reportedFailure) -> messages.add(message));

		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, adapter.trace(null, request()).outcome());
		assertEquals(EntityLocalGeometryResult.Outcome.UNAVAILABLE, adapter.trace(null, request()).outcome());
		assertEquals(1, traces.get());
		assertEquals(1, messages.size());
		assertTrue(messages.get(0).contains("category=delegate-trace-assertion"));
	}

	@Test
	void transientDelegateRuntimeFailurePropagatesWithoutShellDiagnosticOrQuarantine() {
		AtomicInteger traces = new AtomicInteger();
		List<String> messages = new ArrayList<>();
		CreateContraptionRaycastAdapter adapter = new CreateContraptionRaycastAdapter(
			() -> (candidate, raycastRequest) -> {
				traces.incrementAndGet();
				throw new IllegalStateException("transient native shape scan failure");
			},
			(message, failure) -> messages.add(message));

		IllegalStateException first = assertThrows(IllegalStateException.class,
			() -> adapter.trace(null, request()));
		IllegalStateException second = assertThrows(IllegalStateException.class,
			() -> adapter.trace(null, request()));

		assertEquals("transient native shape scan failure", first.getMessage());
		assertEquals("transient native shape scan failure", second.getMessage());
		assertEquals(2, traces.get(), "a transient scan failure must remain eligible for the common FAILED boundary");
		assertTrue(messages.isEmpty(), "the common registry owns the sole runtime callback diagnostic");
	}

	@Test
	void typedDelegateKeepsCreatePhysicalTypeChecksBehindTheShellBoundary() throws IOException {
		try (InputStream bytes = CreateContraptionRaycastAdapter.class.getResourceAsStream(
			"CreateContraptionRaycastDelegate.class")) {
			assertTrue(bytes != null);
			String classFile = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(classFile.contains("com/simibubi/create/content/contraptions/AbstractContraptionEntity"));
			assertTrue(classFile.contains("com/simibubi/create/content/contraptions/Contraption"));
			assertFalse(classFile.contains("dev/engine_room/flywheel"));
		}
	}

	@Test
	void registrationCanCloseAndRegisterAgainForTheNextClientSession() {
		CreateContraptionRaycastAdapter.close();
		assertEquals("not-registered", CreateContraptionRaycastAdapter.registrationState());

		CreateContraptionRaycastAdapter.register();
		assertEquals("registered", CreateContraptionRaycastAdapter.registrationState());

		CreateContraptionRaycastAdapter.close();
		assertEquals("not-registered", CreateContraptionRaycastAdapter.registrationState());

		CreateContraptionRaycastAdapter.register();
		assertEquals("registered", CreateContraptionRaycastAdapter.registrationState());
		CreateContraptionRaycastAdapter.close();
	}

	@Test
	void frozenLocalViewUsesOnlyCapturedStatesAndPreservesFluidStates() {
		BlockPos waterPosition = new BlockPos(2, 4, 6);
		CreateContraptionRaycastEngine.Snapshot snapshot = CreateContraptionRaycastEngine.snapshot(
			new CreateContraptionRaycastEngine.LocalRay(
				new Vec3(0.5, 4.5, 0.0), new Vec3(0.5, 4.5, 10.0), new Vec3(0.5, 4.0, 0.0)),
			Map.of(waterPosition, new CreateContraptionRaycastEngine.CapturedBlock(
				Blocks.WATER.defaultBlockState(), null)),
			Set.of(), -64, 384, false);

		assertSame(Blocks.WATER, snapshot.view().getBlockState(waterPosition).getBlock());
		assertFalse(snapshot.view().getFluidState(waterPosition).isEmpty());
		assertSame(Blocks.AIR, snapshot.view().getBlockState(waterPosition.relative(net.minecraft.core.Direction.EAST)).getBlock());
	}

	@Test
	void portalHiddenEntriesAreAirInTheFrozenViewAndCannotSupplyNeighborState() {
		BlockPos hidden = new BlockPos(0, 0, 0);
		CreateContraptionRaycastEngine.Snapshot snapshot = CreateContraptionRaycastEngine.snapshot(
			new CreateContraptionRaycastEngine.LocalRay(Vec3.ZERO, new Vec3(2.0, 0.0, 0.0), Vec3.ZERO),
			Map.of(hidden, new CreateContraptionRaycastEngine.CapturedBlock(Blocks.STONE.defaultBlockState(), null)),
			Set.of(hidden), -64, 384, false);

		assertSame(Blocks.AIR, snapshot.view().getBlockState(hidden).getBlock());
		assertTrue(snapshot.view().getFluidState(hidden).isEmpty());
		assertEquals(EntityLocalGeometryResult.Outcome.MISS,
			CreateContraptionRaycastEngine.trace(request(), snapshot).outcome());
	}

	@Test
	void nativeTraceHonorsTheFrozenBlockAndFluidPolicy() {
		BlockPos glass = new BlockPos(0, 0, 0);
		BlockPos stone = new BlockPos(1, 0, 0);
		CreateContraptionRaycastEngine.LocalRay ray = new CreateContraptionRaycastEngine.LocalRay(
			new Vec3(-1.0, 0.5, 0.5), new Vec3(3.0, 0.5, 0.5), Vec3.ZERO);
		CreateContraptionRaycastEngine.Snapshot solidSnapshot = CreateContraptionRaycastEngine.snapshot(
			ray,
			Map.of(
				glass, new CreateContraptionRaycastEngine.CapturedBlock(Blocks.GLASS.defaultBlockState(), null),
				stone, new CreateContraptionRaycastEngine.CapturedBlock(Blocks.STONE.defaultBlockState(), null)),
			Set.of(), -64, 384, false);

		EntityLocalGeometryResult outline = CreateContraptionRaycastEngine.trace(
			request(RaycastPolicy.BlockMode.OUTLINE, RaycastPolicy.FluidMode.NONE), solidSnapshot);
		EntityLocalGeometryResult visual = CreateContraptionRaycastEngine.trace(
			request(RaycastPolicy.BlockMode.VISUAL, RaycastPolicy.FluidMode.NONE), solidSnapshot);
		assertEquals(glass, outline.localHit().orElseThrow().localPos());
		assertEquals(stone, visual.localHit().orElseThrow().localPos());

		CreateContraptionRaycastEngine.Snapshot fluidSnapshot = CreateContraptionRaycastEngine.snapshot(
			ray,
			Map.of(glass, new CreateContraptionRaycastEngine.CapturedBlock(Blocks.WATER.defaultBlockState(), null)),
			Set.of(), -64, 384, false);
		assertEquals(EntityLocalGeometryResult.Outcome.MISS, CreateContraptionRaycastEngine.trace(
			request(RaycastPolicy.BlockMode.VISUAL, RaycastPolicy.FluidMode.NONE), fluidSnapshot).outcome());
		assertEquals(EntityLocalGeometryResult.Outcome.HIT, CreateContraptionRaycastEngine.trace(
			request(RaycastPolicy.BlockMode.VISUAL, RaycastPolicy.FluidMode.ANY), fluidSnapshot).outcome());
		assertEquals(EntityLocalGeometryResult.Outcome.HIT, CreateContraptionRaycastEngine.trace(
			request(RaycastPolicy.BlockMode.OUTLINE, RaycastPolicy.FluidMode.ANY), fluidSnapshot).outcome());
		assertEquals(LocalGeometryKind.FLUID, CreateContraptionRaycastEngine.trace(
			request(RaycastPolicy.BlockMode.VISUAL, RaycastPolicy.FluidMode.ANY), fluidSnapshot)
			.localHit().orElseThrow().kind());
	}

	@Test
	void malformedCapturedEntryInvalidatesTheWholeSnapshotBeforeAnyPartialTraceCanEscape() {
		CreateContraptionRaycastEngine.LocalRay ray = new CreateContraptionRaycastEngine.LocalRay(
			new Vec3(-1.0, 0.5, 0.5), new Vec3(3.0, 0.5, 0.5), Vec3.ZERO);
		CreateContraptionRaycastEngine.CaptureEntry valid = new CreateContraptionRaycastEngine.CaptureEntry(
			BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null, false);

		IllegalStateException nullPosition = assertThrows(IllegalStateException.class,
			() -> CreateContraptionRaycastEngine.snapshotCaptured(ray, List.of(
				valid,
				new CreateContraptionRaycastEngine.CaptureEntry(null, Blocks.STONE.defaultBlockState(), null, false)),
				-64, 384, false));
		IllegalStateException nullState = assertThrows(IllegalStateException.class,
			() -> CreateContraptionRaycastEngine.snapshotCaptured(ray, List.of(
				valid,
				new CreateContraptionRaycastEngine.CaptureEntry(BlockPos.ZERO.above(), null, null, false)),
				-64, 384, false));
		List<CreateContraptionRaycastEngine.CaptureEntry> nullInfo = new ArrayList<>();
		nullInfo.add(valid);
		nullInfo.add(null);
		IllegalStateException missingInfo = assertThrows(IllegalStateException.class,
			() -> CreateContraptionRaycastEngine.snapshotCaptured(ray, nullInfo, -64, 384, false));

		assertTrue(nullPosition.getMessage().contains("null local position"));
		assertTrue(nullState.getMessage().contains("null block info/state"));
		assertTrue(missingInfo.getMessage().contains("null entry"));
	}

	@Test
	void localCollisionContextUsesCameraFeetOnlyForNonEmptyContextsAndDelegatesTheOtherMethods() {
		CollisionContext original = nonEmptyCollisionContext();
		VoxelShape shape = Shapes.block();
		BlockPos position = new BlockPos(2, 4, 6);
		double threshold = position.getY() + shape.max(Direction.Axis.Y) - 1.0E-5F;

		CreateContraptionRaycastEngine.LocalCollisionContext below =
			new CreateContraptionRaycastEngine.LocalCollisionContext(
				original, new Vec3(0.0, threshold - 0.0001, 0.0), false);
		CreateContraptionRaycastEngine.LocalCollisionContext exact =
			new CreateContraptionRaycastEngine.LocalCollisionContext(
				original, new Vec3(0.0, threshold, 0.0), false);
		CreateContraptionRaycastEngine.LocalCollisionContext above =
			new CreateContraptionRaycastEngine.LocalCollisionContext(
				original, new Vec3(0.0, threshold + 0.0001, 0.0), false);
		CreateContraptionRaycastEngine.LocalCollisionContext fallback =
			new CreateContraptionRaycastEngine.LocalCollisionContext(
				original, new Vec3(0.0, threshold + 1.0, 0.0), true);

		assertFalse(below.isAbove(shape, position, true));
		assertFalse(exact.isAbove(shape, position, true));
		assertTrue(above.isAbove(shape, position, false));
		assertFalse(fallback.isAbove(shape, position, true), "the empty-context fallback must keep its original answer");
		assertTrue(above.isDescending());
		assertTrue(above.isHoldingItem(Items.STICK));
		assertFalse(above.isHoldingItem(Items.BONE));
		FluidState water = Blocks.WATER.defaultBlockState().getFluidState();
		assertTrue(above.canStandOnFluid(water, water));

		CollisionContext empty = CollisionContext.empty();
		CreateContraptionRaycastEngine.LocalCollisionContext emptyFallback =
			new CreateContraptionRaycastEngine.LocalCollisionContext(
				empty, new Vec3(0.0, threshold + 1.0, 0.0), true);
		assertEquals(empty.isAbove(shape, position, true), emptyFallback.isAbove(shape, position, true));
		assertEquals(empty.isDescending(), emptyFallback.isDescending());
		assertEquals(empty.isHoldingItem(Items.STICK), emptyFallback.isHoldingItem(Items.STICK));
		assertEquals(empty.canStandOnFluid(water, water), emptyFallback.canStandOnFluid(water, water));
	}

	@Test
	void frozenViewSuppliesOnlyCapturedSameFluidNeighborsToNativeFluidShapes() {
		BlockPos water = new BlockPos(0, 0, 0);
		BlockPos above = water.above();
		CreateContraptionRaycastEngine.Snapshot snapshot = CreateContraptionRaycastEngine.snapshot(
			new CreateContraptionRaycastEngine.LocalRay(
				new Vec3(-1.0, 0.75, 0.5), new Vec3(2.0, 0.75, 0.5), Vec3.ZERO),
			Map.of(
				water, new CreateContraptionRaycastEngine.CapturedBlock(
					Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4), null),
				above, new CreateContraptionRaycastEngine.CapturedBlock(Blocks.WATER.defaultBlockState(), null)),
			Set.of(), -64, 384, false);

		assertFalse(snapshot.view().getFluidState(above).isEmpty());
		assertEquals(1.0F, snapshot.view().getFluidState(water).getHeight(snapshot.view(), water));
		assertEquals(LocalGeometryKind.FLUID, CreateContraptionRaycastEngine.trace(
			request(RaycastPolicy.BlockMode.VISUAL, RaycastPolicy.FluidMode.ANY), snapshot)
			.localHit().orElseThrow().kind());
	}

	@Test
	void rigidTransformUsesTheSameNormalizedSegmentForTheNativeShapeResult() {
		EntityLocalRaycastRequest request = new EntityLocalRaycastRequest(
			new Vec3(0.5, 0.5, -2.0), new Vec3(0.5, 0.5, 2.0),
			new RaycastPolicy(RaycastPolicy.BlockMode.OUTLINE, RaycastPolicy.FluidMode.NONE, false),
			1.0F, CollisionContext.empty(), Vec3.ZERO);
		CreateContraptionRaycastEngine.LocalRay ray = CreateContraptionRaycastEngine.transform(
			request, position -> new Vec3(-position.z, position.y, position.x)).orElseThrow();
		CreateContraptionRaycastEngine.Snapshot snapshot = CreateContraptionRaycastEngine.snapshot(
			ray,
			Map.of(new BlockPos(0, 0, 0), new CreateContraptionRaycastEngine.CapturedBlock(
				Blocks.STONE.defaultBlockState(), null)),
			Set.of(), -64, 384, false);

		EntityLocalGeometryResult result = CreateContraptionRaycastEngine.trace(request, snapshot);
		assertEquals(EntityLocalGeometryResult.Outcome.HIT, result.outcome());
		assertEquals(0.25D, result.localHit().orElseThrow().t());
		assertEquals(new Vec3(0.5, 0.5, -1.0), request.pointAt(result.localHit().orElseThrow().t()));
	}

	private static EntityLocalRaycastRequest request() {
		return request(RaycastPolicy.BlockMode.OUTLINE, RaycastPolicy.FluidMode.NONE);
	}

	private static EntityLocalRaycastRequest request(
		RaycastPolicy.BlockMode blockMode,
		RaycastPolicy.FluidMode fluidMode
	) {
		return new EntityLocalRaycastRequest(
			new Vec3(-1.0, 0.5, 0.5),
			new Vec3(3.0, 0.5, 0.5),
			new RaycastPolicy(blockMode, fluidMode, false),
			1.0F,
			CollisionContext.empty(),
			new Vec3(-1.0, 0.0, 0.5));
	}

	private static CollisionContext nonEmptyCollisionContext() {
		return new CollisionContext() {
			@Override
			public boolean isDescending() {
				return true;
			}

			@Override
			public boolean isAbove(VoxelShape shape, BlockPos position, boolean defaultValue) {
				return false;
			}

			@Override
			public boolean isHoldingItem(Item item) {
				return item == Items.STICK;
			}

			@Override
			public boolean canStandOnFluid(FluidState fluidState, FluidState flowingFluidState) {
				return fluidState == flowingFluidState;
			}
		};
	}

}
