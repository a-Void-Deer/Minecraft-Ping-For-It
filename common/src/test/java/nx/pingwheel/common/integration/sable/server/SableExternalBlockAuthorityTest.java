package nx.pingwheel.common.integration.sable.server;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.IntegrationLinkGuard;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider.MaterializationResult;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider.ObservationResult;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider.RefreshResult;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider.ValidationResult;
import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.marker.MarkerAnchor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the public provider operations with a stand-in native plot index. */
@NotNullByDefault
class SableExternalBlockAuthorityTest {

	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), STABLE = new UUID(0, 3);
	private static final BlockPos POSITION_A = new BlockPos(4097, 64, -17);
	private static final BlockPos POSITION_B = new BlockPos(8193, 64, -17);
	private static final BlockPos OUTSIDE = new BlockPos(3, 64, -17);
	private static final MarkerAnchor ANCHOR_A = new MarkerAnchor(7.5, 64.5, -16.5);
	private static final MarkerAnchor ANCHOR_B = new MarkerAnchor(13.5, 64.5, -16.5);

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void aLoadedBlockInAnotherPlotOrTheOrdinaryWorldCannotBeClaimed() throws ReflectiveOperationException {
		var fixture = new Fixture();
		for (BlockPos position : List.of(POSITION_B, OUTSIDE)) {
			var candidate = candidate(A, position);
			assertInstanceOf(ValidationResult.Invalid.class, fixture.provider.validate(fixture.level, candidate));
			assertInstanceOf(MaterializationResult.Invalid.class, fixture.provider.materialize(fixture.level, candidate));
			assertTrue(fixture.provider.resolveName(fixture.level, candidate).isEmpty());
		}
		fixture.assertNoAuthorityReads();
		assertEquals(0, fixture.plotChunkLookups, "nonmembers must not ask either plot for chunk data");
	}

	@Test void validMembershipSupportsBothPhasesAndRefreshWithoutTheContentGate() throws ReflectiveOperationException {
		var fixture = new Fixture();
		set(SableExternalBlockServerProvider.class, fixture.provider, "contentUnavailable", true);
		assertTrue(fixture.server.isSameThread());
		var candidate = candidate(A, POSITION_A);
		assertTrue(fixture.provider.resolvePreviewReadSource(fixture.level, candidate).isEmpty());
		var validation = assertInstanceOf(ValidationResult.Accepted.class,
			fixture.provider.validate(fixture.level, candidate)).target();
		assertEquals(ANCHOR_A, validation.anchor());
		assertTrue(validation.target().isCandidate());
		assertFalse(validation.target().hasBlockEntity(), "live state decides the classification");
		assertEquals(0, fixture.trackingDataReads, "validation must not touch saved tracking data");

		var materialized = assertInstanceOf(MaterializationResult.Materialized.class,
			fixture.provider.materialize(fixture.level, validation.target())).target();
		assertEquals(ANCHOR_A, materialized.anchor());
		assertEquals(STABLE.toString(), materialized.target().stableTargetId());
		assertEquals(1, fixture.trackingCreates);
		assertEquals(new TrackingPoint(A, new Vector3d(4097.5, 64.5, -16.5)), fixture.trackingPoint);

		var committed = materialized.target();
		assertInstanceOf(ObservationResult.Available.class, fixture.provider.observeBlock(fixture.level, committed));
		fixture.loaded = false;
		assertInstanceOf(RefreshResult.TemporarilyUnavailable.class, fixture.provider.refresh(fixture.level, committed));
		fixture.loaded = true;
		fixture.trackingPoint = point(A, POSITION_B);
		int reads = fixture.stateReads, poses = fixture.poseReads;
		assertInstanceOf(RefreshResult.Invalid.class, fixture.provider.refresh(fixture.level, committed));
		assertInstanceOf(ObservationResult.TemporarilyUnavailable.class, fixture.provider.observeBlock(fixture.level, committed));
		assertEquals(reads, fixture.stateReads);
		assertEquals(poses, fixture.poseReads);

		fixture.trackingPoint = point(B, POSITION_B);
		var moved = assertInstanceOf(RefreshResult.Available.class, fixture.provider.refresh(fixture.level, committed));
		assertEquals(ANCHOR_B, moved.anchor());
		assertEquals(committed.stableTargetId(), moved.target().stableTargetId());
		assertEquals(candidate(B, POSITION_B).providerLocator(), moved.target().providerLocator());
		assertEquals(1, fixture.trackingCreates, "refresh must not allocate a replacement tracking point");
		assertEquals(0, fixture.parentStateReads, "server authority reads from the matched plot, not the shared level");
		fixture.provider.release(fixture.server, moved.target());
		assertNull(fixture.trackingPoint);
	}

	@Test void materializationRechecksMembershipAfterValidation() throws ReflectiveOperationException {
		var fixture = new Fixture();
		var validation = assertInstanceOf(ValidationResult.Accepted.class,
			fixture.provider.validate(fixture.level, candidate(A, POSITION_A))).target();
		fixture.container.firstPositionReassigned = true;
		int reads = fixture.stateReads, poses = fixture.poseReads;
		assertInstanceOf(MaterializationResult.Invalid.class, fixture.provider.materialize(fixture.level, validation.target()));
		assertEquals(reads, fixture.stateReads);
		assertEquals(poses, fixture.poseReads);
		assertEquals(0, fixture.trackingDataReads);
		assertEquals(0, fixture.trackingCreates);
	}

	@Test void unresolvedRemovedAndUnloadedAreTemporaryButProbeFailuresKeepExistingFailurePolicy()
		throws ReflectiveOperationException {
		var fixture = new Fixture();
		var candidate = candidate(A, POSITION_A);
		fixture.containerAvailable = false;
		assertInstanceOf(ValidationResult.TemporarilyUnavailable.class, fixture.provider.validate(fixture.level, candidate));
		fixture.containerAvailable = true;
		assertInstanceOf(ValidationResult.TemporarilyUnavailable.class,
			fixture.provider.validate(fixture.level, candidate(STABLE, POSITION_A)));
		fixture.subLevelA.removed = true;
		assertInstanceOf(ValidationResult.TemporarilyUnavailable.class, fixture.provider.validate(fixture.level, candidate));
		fixture.subLevelA.removed = false;
		fixture.subLevelA.plot = null;
		assertInstanceOf(ValidationResult.TemporarilyUnavailable.class, fixture.provider.validate(fixture.level, candidate));
		fixture.subLevelA.plot = fixture.plotA;
		fixture.loaded = false;
		assertInstanceOf(ValidationResult.TemporarilyUnavailable.class, fixture.provider.validate(fixture.level, candidate));
		assertInstanceOf(MaterializationResult.TemporarilyUnavailable.class, fixture.provider.materialize(fixture.level, candidate));
		fixture.loaded = true;

		var failure = new IllegalStateException("cannot read the resident plot index");
		fixture.container.failure = failure;
		assertInstanceOf(ValidationResult.Invalid.class, fixture.provider.validate(fixture.level, candidate));
		assertTrue(fixture.diagnostics.events().stream().anyMatch(event -> event.throwable() == failure));
		fixture.assertNoAuthorityReads();
		fixture.container.failure = null;
		assertInstanceOf(ValidationResult.Accepted.class, fixture.provider.validate(fixture.level, candidate));
	}

	@Test void wrongNativeChunkIsNotReadEvenWithPositivePlotMembership() throws ReflectiveOperationException {
		var fixture = new Fixture();
		fixture.plotA.chunk.absolute = new ChunkPos(POSITION_B);
		assertInstanceOf(ValidationResult.Invalid.class, fixture.provider.validate(fixture.level, candidate(A, POSITION_A)));
		fixture.assertNoAuthorityReads();
	}

	private static Target.ExternalBlockTarget candidate(UUID id, BlockPos position) {
		return Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:stone",
			new SableExternalBlockLocator(id, position).encode(), true);
	}

	private static TrackingPoint point(UUID id, BlockPos position) {
		return new TrackingPoint(id, new Vector3d(position.getX() + .25, position.getY(), position.getZ() + .25));
	}

	@NotNullByDefault
	private static final class Fixture {
		final SableExternalBlockServerProvider provider = allocate(SableExternalBlockServerProvider.class);
		final SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		final MinecraftServer server = allocate(DedicatedServer.class);
		final AuthorityLevel level = allocate(AuthorityLevel.class);
		final Plot plotA = new Plot(this, POSITION_A), plotB = new Plot(this, POSITION_B);
		final SubLevel subLevelA = new SubLevel(this, plotA, -4090);
		final SubLevel subLevelB = new SubLevel(this, plotB, -8180);
		final Container container = new Container(this);
		final TrackingData trackingData = new TrackingData(this);
		@Nullable TrackingPoint trackingPoint;
		boolean containerAvailable = true, loaded = true;
		int stateReads, parentStateReads, blockEntityReads, plotChunkLookups, poseReads, trackingDataReads, trackingCreates;

		Fixture() throws ReflectiveOperationException {
			level.fixture = this;
			set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
			var providerType = SableExternalBlockServerProvider.class;
			set(providerType, provider, "diagnostics", diagnostics);
			set(providerType, provider, "linkGuard", new IntegrationLinkGuard("sable", Diagnostics.server()));
			set(providerType, provider, "servers", new java.util.IdentityHashMap<>());
			Class<?> api = Class.forName(providerType.getName() + "$ReflectiveApi");
			var constructor = api.getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			set(providerType, provider, "api", constructor.newInstance(
				method(Container.class, "getContainer", ServerLevel.class), method(Container.class, "getSubLevel", UUID.class),
				method(SubLevel.class, "getLevel"), method(SubLevel.class, "isRemoved"), method(SubLevel.class, "logicalPose"),
				method(TrackingData.class, "getOrLoad", ServerLevel.class),
				method(TrackingData.class, "generateTrackingPoint", Vec3.class, SubLevel.class),
				method(TrackingData.class, "getTrackingPoint", UUID.class), method(TrackingData.class, "removeTrackingPoint", UUID.class),
				method(TrackingPoint.class, "inSubLevel"), method(TrackingPoint.class, "subLevelID"), method(TrackingPoint.class, "point"),
				method(SubLevel.class, "getPlot"), method(Container.class, "getPlot", ChunkPos.class),
				method(Plot.class, "toLocal", ChunkPos.class), method(Plot.class, "getChunk", ChunkPos.class)));
		}

		void assertNoAuthorityReads() {
			assertEquals(0, stateReads, "no content from an unproven position");
			assertEquals(0, parentStateReads, "no fallback through the shared level");
			assertEquals(0, blockEntityReads, "name resolution must not read a block entity for an unproven position");
			assertEquals(0, poseReads, "no anchor from an unproven position");
			assertEquals(0, trackingDataReads, "no saved data lookup for an unproven position");
			assertEquals(0, trackingCreates);
		}
	}

	@NotNullByDefault
	private static final class AuthorityLevel extends ServerLevel {
		Fixture fixture;
		private AuthorityLevel() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public MinecraftServer getServer() { return fixture.server; }
		@Override public boolean isOutsideBuildHeight(BlockPos position) { return false; }
		@Override public boolean isLoaded(BlockPos position) { return true; }
		@Override public BlockState getBlockState(BlockPos position) {
			fixture.parentStateReads++;
			return Blocks.STONE.defaultBlockState();
		}
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos position) {
			fixture.blockEntityReads++;
			return null;
		}
	}

	@NotNullByDefault
	public static final class Container {
		private final Fixture fixture;
		private @Nullable RuntimeException failure;
		private boolean firstPositionReassigned;

		private Container(Fixture fixture) { this.fixture = fixture; }
		public static @Nullable Container getContainer(ServerLevel level) {
			var fixture = ((AuthorityLevel) level).fixture;
			return fixture.containerAvailable ? fixture.container : null;
		}
		public @Nullable SubLevel getSubLevel(UUID id) {
			return A.equals(id) ? fixture.subLevelA : B.equals(id) ? fixture.subLevelB : null;
		}
		public @Nullable Plot getPlot(ChunkPos chunk) {
			if (failure != null) throw failure;
			if (chunk.equals(new ChunkPos(POSITION_A))) return firstPositionReassigned ? fixture.plotB : fixture.plotA;
			if (chunk.equals(new ChunkPos(POSITION_B))) return firstPositionReassigned ? null : fixture.plotB;
			return null;
		}
	}

	@NotNullByDefault
	public static final class Plot {
		private final Fixture fixture;
		private final ChunkPos origin;
		private final AuthorityChunk chunk = allocate(AuthorityChunk.class);

		private Plot(Fixture fixture, BlockPos position) {
			this.fixture = fixture;
			origin = new ChunkPos(position);
			chunk.fixture = fixture;
			chunk.absolute = origin;
			chunk.position = position;
		}
		public ChunkPos toLocal(ChunkPos absolute) { return new ChunkPos(absolute.x - origin.x, absolute.z - origin.z); }
		public @Nullable LevelChunk getChunk(ChunkPos relative) {
			fixture.plotChunkLookups++;
			return fixture.loaded && relative.x == 0 && relative.z == 0 ? chunk : null;
		}
	}

	@NotNullByDefault
	private static final class AuthorityChunk extends LevelChunk {
		Fixture fixture;
		ChunkPos absolute;
		BlockPos position;
		private AuthorityChunk() { super(null, null); }
		@Override public Level getLevel() { return fixture.level; }
		@Override public ChunkPos getPos() { return absolute; }
		@Override public BlockState getBlockState(BlockPos position) {
			fixture.stateReads++;
			assertEquals(this.position, position, "state reads use absolute plot storage coordinates");
			return Blocks.STONE.defaultBlockState();
		}
	}

	@NotNullByDefault
	public static final class SubLevel {
		private final Fixture fixture;
		private final Pose3d pose = new Pose3d();
		private @Nullable Plot plot;
		private boolean removed;

		private SubLevel(Fixture fixture, Plot plot, double translateX) {
			this.fixture = fixture;
			this.plot = plot;
			pose.position().set(translateX, 0, 0);
		}
		public ServerLevel getLevel() { return fixture.level; }
		public boolean isRemoved() { return removed; }
		public @Nullable Plot getPlot() { return plot; }
		public Pose3dc logicalPose() { fixture.poseReads++; return pose; }
	}

	@NotNullByDefault
	public static final class TrackingData {
		private final Fixture fixture;

		private TrackingData(Fixture fixture) { this.fixture = fixture; }
		public static TrackingData getOrLoad(ServerLevel level) {
			var fixture = ((AuthorityLevel) level).fixture;
			fixture.trackingDataReads++;
			return fixture.trackingData;
		}
		public UUID generateTrackingPoint(Vec3 point, SubLevel subLevel) {
			fixture.trackingCreates++;
			assertSame(fixture.subLevelA, subLevel);
			fixture.trackingPoint = new TrackingPoint(A, new Vector3d(point.x, point.y, point.z));
			return STABLE;
		}
		public @Nullable TrackingPoint getTrackingPoint(UUID id) { return STABLE.equals(id) ? fixture.trackingPoint : null; }
		public void removeTrackingPoint(UUID id) { if (STABLE.equals(id)) fixture.trackingPoint = null; }
	}

	@NotNullByDefault
	public record TrackingPoint(UUID subLevelID, Vector3dc point) {
		public boolean inSubLevel() { return true; }
	}

	private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
		return type.getMethod(name, parameters);
	}

	private static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static <T> T allocate(Class<T> type) {
		try {
			var unsafe = Class.forName("sun.misc.Unsafe");
			var field = unsafe.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}
}
