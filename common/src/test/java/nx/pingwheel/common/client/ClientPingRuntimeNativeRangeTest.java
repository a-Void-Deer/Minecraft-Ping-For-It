package nx.pingwheel.common.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.spatial.PreciseCaptureRefresh;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.CandidateBlockCapture;
import nx.pingwheel.common.interaction.candidate.CandidateCollector;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.math.Raycast;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises native clipping and the actual ordinary/Precise runtime adapters on a headless level. */
@NotNullByDefault
class ClientPingRuntimeNativeRangeTest {

	private static final int RANGE = 1024;
	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, false);

	private @Nullable Minecraft previousGame;
	private boolean previousSable, previousDh;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach void disableOptionalRoutes() {
		previousGame = CommonClient.Game;
		previousSable = ModContext.HasSable;
		previousDh = ModContext.HasDistantHorizons;
		ModContext.HasSable = false;
		ModContext.HasDistantHorizons = false;
	}

	@AfterEach void restoreClient() {
		CommonClient.Game = previousGame;
		ModContext.HasSable = previousSable;
		ModContext.HasDistantHorizons = previousDh;
	}

	@Test
	void minecraftMinusFiveDegreeMissAgreesAcrossOrdinarySyntheticAndPreciseDistantFallbacks() throws Exception {
		var level = DoubleChestFixture.allocate(TestLevel.class);
		var camera = new Camera(level);
		var game = DoubleChestFixture.allocate(TestGame.class);
		var callbacks = new LinkedBlockingQueue<Runnable>();
		game.callbacks = callbacks;
		game.level = level;
		game.cameraEntity = camera;
		CommonClient.Game = game;

		Vec3 eye = new Vec3(23.125, 72.5, -31.75);
		camera.setXRot(0.0f);
		camera.setYRot(-5.0f);
		Vec3 direction = camera.getViewVector(1.0f);
		assertTrue(eye.distanceToSqr(eye.add(direction.scale(RANGE))) > (double) RANGE * RANGE,
			"the real lookup-table vector would put the uncorrected miss outside equal server range");
		var ray = new CapturedRay(world(eye), world(direction));
		var runtime = ClientPingRuntime.create((key, color) -> fail(key), packet -> fail("unexpected packet"));
		var activeField = ClientPingRuntime.class.getDeclaredField("activeInteraction");
		activeField.setAccessible(true);
		var token = ((ActiveInteraction) Objects.requireNonNull(activeField.get(runtime))).begin();
		set(runtime, "baselineToken", token);
		set(runtime, "selectorNativeDistance", (double) RANGE);
		set(runtime, "selectorPingDistance", (double) RANGE);
		set(runtime, "selectorPolicy", POLICY);

		Target.LocationTarget ordinary = ordinaryMiss(runtime, token, ray);
		assertBounded(eye, direction, point(ordinary), RANGE);
		game.cameraEntity = new Camera(null); // No level on the camera: native trace cannot supply a MISS.
		assertEquals(ordinary, ordinaryMiss(runtime, token, ray));
		game.cameraEntity = camera;

		var request = new PreciseCaptureRefresh.Request(1,
			new PreciseCaptureRefresh.Inputs(token, level, ray, POLICY, RANGE, RANGE));
		@Nullable IDhApiWorldProxy previousWorld = DhApi.Delayed.worldProxy;
		@Nullable IDhApiTerrainDataRepo previousRepo = DhApi.Delayed.terrainRepo;
		try {
			ModContext.HasDistantHorizons = true;
			var nativeCapture = ClientPingRuntime.class.getDeclaredMethod("capturePreciseNative", PreciseCaptureRefresh.Request.class);
			nativeCapture.setAccessible(true);
			var scan = (PreciseCaptureRefresh.Scan) Objects.requireNonNull(nativeCapture.invoke(runtime, request));
			assertTrue(scan.needsDistant());
			assertEquals(ordinary, scan.location().candidate().orElseThrow().resolvedTarget().target());

			var wrapper = proxy(IDhApiLevelWrapper.class, (ignored, method, args) -> {
				if (method.getName().equals("getWrappedMcObject")) return level;
				throw new AssertionError(method);
			});
			DhApi.Delayed.worldProxy = proxy(IDhApiWorldProxy.class, (ignored, method, args) -> switch (method.getName()) {
				case "worldLoaded" -> true;
				case "getAllLoadedLevelWrappers" -> List.of(wrapper);
				default -> throw new AssertionError(method);
			});
			var dhRayVerified = new AtomicReference<Boolean>();
			DhApi.Delayed.terrainRepo = proxy(IDhApiTerrainDataRepo.class, (ignored, method, args) -> switch (method.getName()) {
				case "createSoftCache" -> proxy(IDhApiTerrainDataCache.class, (cache, call, parameters) -> {
					if (call.getName().equals("clear")) return null;
					throw new AssertionError(call);
				});
				case "raycast" -> {
					@Nullable Object[] parameters = Objects.requireNonNull(args);
					dhRayVerified.set(new Vec3((double) parameters[1], (double) parameters[2], (double) parameters[3]).equals(eye)
						&& new Vec3((float) parameters[4], (float) parameters[5], (float) parameters[6]).equals(direction)
						&& (int) parameters[7] > RANGE);
					yield DhApiResult.createSuccess(null);
				}
				default -> throw new AssertionError(method);
			});
			var startDistant = ClientPingRuntime.class.getDeclaredMethod("startPreciseDistant",
				PreciseCaptureRefresh.Request.class, Consumer.class);
			startDistant.setAccessible(true);
			var finalLocation = new AtomicReference<PreciseCaptureRefresh.Outcome>();
			assertEquals(Boolean.TRUE, startDistant.invoke(runtime, request,
				(Consumer<PreciseCaptureRefresh.Outcome>) finalLocation::set));
			camera.setYRot(90.0f); // The callback must not resample the current view.
			@Nullable Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
			assertNotNull(callback, "the real async adapter must deliver its no-hit callback");
			callback.run();
			assertEquals(Boolean.TRUE, dhRayVerified.get(), "DH still receives the raw captured ray and its own longer range");
			assertEquals(ordinary, Objects.requireNonNull(finalLocation.get()).candidate().orElseThrow().resolvedTarget().target());
		} finally {
			DhApi.Delayed.worldProxy = previousWorld;
			DhApi.Delayed.terrainRepo = previousRepo;
		}
	}

	@Test
	void worldCoordinateRoundingCannotExtendTheNativeOrSupplementalSegment() {
		var level = DoubleChestFixture.allocate(TestLevel.class);
		var camera = new Camera(level);
		Vec3 eye = new Vec3(29_990_000.25, 72.5, -29_990_000.5);
		Vec3 direction = camera.calculateViewVector(24.0f, -31.0f);
		Vec3 unit = direction.normalize();
		assertTrue(eye.distanceToSqr(eye.add(unit.scale(RANGE))) > (double) RANGE * RANGE,
			"normalizing alone still puts the rounded endpoint outside the cap");
		int supplementalRange = RANGE * 2;
		assertTrue(eye.distanceToSqr(eye.add(unit.scale(supplementalRange))) > (double) supplementalRange * supplementalRange,
			"the same rounding case exercises the separately bounded supplement");

		var supplementalEnd = new AtomicReference<Vec3>();
		CandidateBlockCapture provider = new CandidateBlockCapture() {
			@Override public Result capture(Level captured, BlockHitResult hit, Vec3 from, Vec3 to) {
				throw new AssertionError("the native fixture is empty");
			}
			@Override public boolean collectSupplemental(Level captured, Vec3 from, Vec3 to, RaycastPolicy policy,
				CollisionContext context, Vec3 cameraFeet, CandidateCollector collector) {
				assertEquals(eye, from);
				supplementalEnd.set(to);
				return true;
			}
		};
		var ray = new CapturedRay(world(eye), world(direction));
		var trace = Raycast.traceDirectionalCandidates(camera, new ActiveInteraction().begin(), ray, RANGE,
			supplementalRange, POLICY, provider).orElseThrow();
		assertEquals(HitResult.Type.MISS, trace.ordinarySelection().hitResult().getType());
		assertBounded(eye, direction, trace.ordinarySelection().worldPoint().orElseThrow(), RANGE);
		assertBounded(eye, direction, Objects.requireNonNull(supplementalEnd.get()), supplementalRange);
	}

	@Test
	void exactlyRepresentableBoundaryMissDoesNotShrink() {
		var level = DoubleChestFixture.allocate(TestLevel.class);
		var camera = new Camera(level);
		Vec3 eye = new Vec3(23.125, 72.5, -31.75);
		var ray = new CapturedRay(world(eye), new WorldVector(1.0, 0.0, 0.0));

		var trace = Raycast.traceDirectionalCandidates(camera, new ActiveInteraction().begin(), ray, RANGE, RANGE,
			POLICY, CandidateBlockCapture.nativeBlocks()).orElseThrow();
		assertEquals(HitResult.Type.MISS, trace.ordinarySelection().hitResult().getType());
		assertEquals(new Vec3(eye.x + RANGE, eye.y, eye.z), trace.ordinarySelection().hitResult().getLocation());
	}

	@Test
	void aNativeBlockHitKeepsItsContactAndFaceInsteadOfBecomingARangeEndpoint() {
		var level = DoubleChestFixture.allocate(TestLevel.class);
		var camera = new Camera(level);
		var stone = new BlockPos(0, 64, 4);
		level.stone = stone;
		Vec3 eye = new Vec3(0.25, 64.5, 0.25);
		var ray = new CapturedRay(world(eye), world(camera.calculateViewVector(0.0f, -5.0f)));

		var trace = Raycast.traceDirectionalCandidates(camera, new ActiveInteraction().begin(), ray, RANGE, RANGE,
			POLICY, CandidateBlockCapture.nativeBlocks()).orElseThrow();
		var hit = assertInstanceOf(BlockHitResult.class, trace.ordinarySelection().hitResult());
		assertEquals(HitResult.Type.BLOCK, hit.getType());
		assertEquals(stone, hit.getBlockPos());
		assertEquals(Direction.NORTH, hit.getDirection());
		assertEquals(stone.getZ(), hit.getLocation().z, 1e-9);
		assertEquals(hit.getLocation(), trace.ordinarySelection().worldPoint().orElseThrow());
	}

	@Test
	void ordinaryShortPressStaysAtTheNativeSegmentWhilePreciseStillReachesThePingRange() throws Exception {
		var level = DoubleChestFixture.allocate(TestLevel.class);
		level.stone = new BlockPos(0, 64, 20);
		Vec3 eye = new Vec3(0.25, 64.5, 0.25);
		var ray = new CapturedRay(world(eye), new WorldVector(0.0, 0.0, 1.0));
		var runtime = pressRuntime(level, 8, 1024);
		var token = freshToken(runtime);

		TargetSnapshot ordinary = ordinaryCapture(runtime, token, ray);
		Target.LocationTarget location = assertInstanceOf(Target.LocationTarget.class, ordinary.target());
		assertEquals(eye.z + 8.0, location.z(), "the ordinary capture stops at the frozen native segment");
		assertTrue(level.farthestBlockReadZ <= eye.z + 8.0 + 1.0,
			"ordinary capture must not read block cells across the full Ping range");
		assertTrue(level.farthestLoadedZ <= eye.z + 8.0 + 1.0,
			"ordinary capture must not run the supplemental loaded-cell scan");
		assertTrue(level.loadedViews <= 1, "ordinary capture must not add the supplemental loaded-entity walk");

		var scan = preciseScan(runtime, token, level, ray, 8, 1024);
		var block = scan.nativeSlots().get(PreciseTargetType.BLOCK).candidate().orElseThrow();
		assertEquals(new Target.BlockTarget("minecraft:overworld", 0, 64, 20, "minecraft:stone"),
			block.resolvedTarget().target(), "the live Precise scan still uses the hold-frozen Ping range");
	}

	@Test
	void ordinaryShortPressKeepsTheNativeBlockFaceInsideTheEffectiveSegment() throws Exception {
		var level = DoubleChestFixture.allocate(TestLevel.class);
		var stone = new BlockPos(0, 64, 4);
		level.stone = stone;
		Vec3 eye = new Vec3(0.25, 64.5, 0.25);
		var ray = new CapturedRay(world(eye), new WorldVector(0.0, 0.0, 1.0));
		var runtime = pressRuntime(level, 8, 1024);
		var token = freshToken(runtime);

		TargetSnapshot ordinary = ordinaryCapture(runtime, token, ray);
		assertEquals(new Target.BlockTarget("minecraft:overworld", 0, 64, 4, "minecraft:stone"), ordinary.target());
		assertEquals(BlockFace.NORTH, ordinary.blockHitFace().orElseThrow());
		assertTrue(level.farthestBlockReadZ <= stone.getZ() + 1.0,
			"no block read may reach into the supplemental Ping range");
		assertTrue(level.farthestLoadedZ <= stone.getZ() + 1.0,
			"no loaded-cell check may reach into the supplemental Ping range");
	}

	private static ClientPingRuntime pressRuntime(TestLevel level, int nativeDistance, int pingDistance)
		throws ReflectiveOperationException {
		var game = DoubleChestFixture.allocate(TestGame.class);
		game.level = level;
		game.cameraEntity = new Camera(level);
		CommonClient.Game = game;
		var runtime = ClientPingRuntime.create((key, color) -> fail(key), packet -> fail("unexpected packet"));
		set(runtime, "selectorNativeDistance", (double) nativeDistance);
		set(runtime, "selectorPingDistance", (double) pingDistance);
		set(runtime, "selectorPolicy", POLICY);
		return runtime;
	}

	private static InteractionToken freshToken(ClientPingRuntime runtime) throws ReflectiveOperationException {
		var activeField = ClientPingRuntime.class.getDeclaredField("activeInteraction");
		activeField.setAccessible(true);
		var active = (ActiveInteraction) Objects.requireNonNull(activeField.get(runtime));
		var token = active.begin();
		set(runtime, "baselineToken", token);
		return token;
	}

	private static PreciseCaptureRefresh.Scan preciseScan(ClientPingRuntime runtime, InteractionToken token,
		TestLevel level, CapturedRay ray, int nativeDistance, int pingDistance) throws ReflectiveOperationException {
		var capture = ClientPingRuntime.class.getDeclaredMethod("capturePreciseNative", PreciseCaptureRefresh.Request.class);
		capture.setAccessible(true);
		var request = new PreciseCaptureRefresh.Request(1,
			new PreciseCaptureRefresh.Inputs(token, level, ray, POLICY, nativeDistance, pingDistance));
		return (PreciseCaptureRefresh.Scan) Objects.requireNonNull(capture.invoke(runtime, request));
	}

	private static void assertBounded(Vec3 eye, Vec3 direction, Vec3 endpoint, int range) {
		assertTrue(eye.distanceToSqr(endpoint) <= (double) range * range, "the strict squared limit accepts the represented point");
		assertTrue(eye.distanceTo(endpoint) >= range - 1e-6, "capture should still reach the boundary to world-coordinate precision");
		assertTrue(endpoint.subtract(eye).cross(direction).length() < 1e-6, "the endpoint stays along the captured view ray");
	}

	private static Target.LocationTarget ordinaryMiss(ClientPingRuntime runtime, InteractionToken token, CapturedRay ray)
		throws ReflectiveOperationException {
		return assertInstanceOf(Target.LocationTarget.class, ordinaryCapture(runtime, token, ray).target());
	}

	private static TargetSnapshot ordinaryCapture(ClientPingRuntime runtime, InteractionToken token, CapturedRay ray)
		throws ReflectiveOperationException {
		var capture = ClientPingRuntime.class.getDeclaredMethod("captureImmediately", InteractionToken.class, CapturedRay.class,
			ClientPingRuntime.CaptureCompletion.class);
		capture.setAccessible(true);
		var result = new AtomicReference<TargetSnapshot>();
		capture.invoke(runtime, token, ray, (ClientPingRuntime.CaptureCompletion) (snapshot, candidates) -> {
			assertTrue(candidates.isEmpty(), "an ordinary press must not freeze selector supplements");
			result.set(snapshot);
		});
		return Objects.requireNonNull(result.get());
	}

	private static void set(ClientPingRuntime runtime, String name, Object value) throws ReflectiveOperationException {
		var field = ClientPingRuntime.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(runtime, value);
	}

	private static WorldVector world(Vec3 v) { return new WorldVector(v.x, v.y, v.z); }
	private static Vec3 point(Target.LocationTarget v) { return new Vec3(v.x(), v.y(), v.z()); }

	private static <T> T proxy(Class<T> type, InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
	}

	@NotNullByDefault
	private static final class TestGame extends Minecraft {
		private @Nullable BlockingQueue<Runnable> callbacks;
		private TestGame() { super(null); }
		@Override public void execute(Runnable task) { Objects.requireNonNull(callbacks).add(task); }
	}

	@NotNullByDefault
	private static final class TestLevel extends ClientLevel {
		private @Nullable BlockPos stone;
		private int loadedViews;
		private double farthestBlockReadZ;
		private double farthestLoadedZ;

		private TestLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
		@Override public ResourceKey<Level> dimension() { return OVERWORLD; }
		@Override public @Unmodifiable List<Entity> getEntities(@Nullable Entity except, AABB bounds,
			Predicate<? super Entity> filter) { return List.of(); }
		@Override public @Unmodifiable Iterable<Entity> entitiesForRendering() {
			loadedViews++;
			return List.of();
		}
		@Override public boolean isLoaded(BlockPos pos) {
			farthestLoadedZ = Math.max(farthestLoadedZ, pos.getZ());
			return true;
		}
		@Override public BlockState getBlockState(BlockPos pos) {
			farthestBlockReadZ = Math.max(farthestBlockReadZ, pos.getZ());
			return (pos.equals(stone) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
		}
		@Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
	}

	@NotNullByDefault
	private static final class Camera extends Entity {
		Camera(@Nullable Level level) { super(EntityType.COW, level); }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
