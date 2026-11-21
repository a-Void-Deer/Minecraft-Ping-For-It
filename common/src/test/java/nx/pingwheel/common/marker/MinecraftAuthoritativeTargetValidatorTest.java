package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityParentFrame;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class MinecraftAuthoritativeTargetValidatorTest {

	private static final UUID OWNER = new UUID(1L, 1L);
	private static final UUID ENTITY_ID = new UUID(2L, 2L);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void distantNativePlotPositionIsAcceptedThroughItsNearbyLogicalWorldFrame() {
		Fixture fixture = new Fixture();
		fixture.entity.setPos(30_000_000.0, 1.0, 30_000_000.0);
		fixture.player.setEye(new Vec3(4.0, 65.0, 6.0));
		EntityParentFrame frame = translation(-29_999_996.0, 64.0, -29_999_994.0);

		AuthoritativeTargetValidation validation = fixture.validator(1,
			entity -> EntityFrameResolution.contained(frame, UUID.randomUUID()))
			.validate(OWNER, entityTarget());

		assertTrue(validation.isAccepted());
		assertEquals(new MarkerAnchor(4.0, 65.0, 6.0), validation.validatedTarget().orElseThrow().anchor());
	}

	@Test
	void nearRawNativePositionCannotMaskADistantLogicalWorldAnchor() {
		Fixture fixture = new Fixture();
		fixture.entity.setPos(1.0, 0.0, 0.0);
		fixture.player.setEye(new Vec3(1.0, 0.0, 0.0));
		EntityParentFrame frame = translation(100.0, 0.0, 0.0);

		AuthoritativeTargetValidation validation = fixture.validator(5,
			entity -> EntityFrameResolution.contained(frame, UUID.randomUUID()))
			.validate(OWNER, entityTarget());

		assertEquals(MarkerRejectReason.OUT_OF_RANGE, validation.rejectReason().orElseThrow());
	}

	@Test
	void remoteRotatedPivotAcceptsEqualityAndRejectsTheNextRepresentableOutsideDistance() {
		Fixture fixture = new Fixture();
		Vector3d nativePivot = new Vector3d(10_000_000, 1, 30_000_000);
		fixture.entity.setPos(nativePivot.x, nativePivot.y, nativePivot.z);
		fixture.player.setEye(new Vec3(1024.1, 64, .5));
		// The native base is at the pivot, so rotating it cannot move it off this exact world point.
		EntityParentFrame frame = EntityParentFrame.copyOfPivoted(nativePivot, new Vector3d(1152.1, 64, .5),
			new Quaterniond(.5, .5, .5, .5), new Vector3d(1));
		var validator = fixture.validator(128, entity -> EntityFrameResolution.contained(frame, UUID.randomUUID()));

		AuthoritativeTargetValidation equality = validator.validate(OWNER, entityTarget());
		assertTrue(equality.isAccepted());
		assertEquals(new MarkerAnchor(1152.1, 64, .5), equality.validatedTarget().orElseThrow().anchor());

		fixture.player.setEye(new Vec3(Math.nextDown(1024.1), 64, .5));
		assertEquals(MarkerRejectReason.OUT_OF_RANGE, validator.validate(OWNER, entityTarget()).rejectReason().orElseThrow());
	}

	@Test
	void locationRangeAcceptsEqualityButRejectsTheNextRepresentableOutsidePoint() {
		Fixture fixture = new Fixture();
		Vec3 eye = new Vec3(29_990_000.25, 72.5, -29_990_000.5);
		fixture.player.setEye(eye);
		int range = 1024;
		var validator = fixture.productionValidator(range);
		double edge = eye.x + range;

		assertTrue(validator.validate(OWNER, new Target.LocationTarget("minecraft:overworld", edge, eye.y, eye.z)).isAccepted());
		assertEquals(MarkerRejectReason.OUT_OF_RANGE, validator.validate(OWNER,
			new Target.LocationTarget("minecraft:overworld", Math.nextUp(edge), eye.y, eye.z)).rejectReason().orElseThrow());
	}

	@Test
	void entityAnchorRemainsTheBasePositionNotATopCenterOrContactDerivedPoint() {
		Fixture fixture = new Fixture();
		fixture.entity.setPos(0.0, 0.0, 0.0);
		fixture.player.setEye(Vec3.ZERO);
		EntityParentFrame frame = translation(5.0, 0.0, 0.0);

		AuthoritativeTargetValidation validation = fixture.validator(5,
			entity -> EntityFrameResolution.contained(frame, UUID.randomUUID()))
			.validate(OWNER, entityTarget());

		assertTrue(fixture.entity.getBoundingBox().getYsize() > 0.0);
		assertTrue(validation.isAccepted());
		assertEquals(new MarkerAnchor(5.0, 0.0, 0.0), validation.validatedTarget().orElseThrow().anchor());
	}

	@Test
	void unavailableLogicalFrameIsTargetGoneInsteadOfFallingBackToTheNativePosition() {
		Fixture fixture = new Fixture();
		fixture.entity.setPos(1.0, 2.0, 3.0);
		fixture.player.setEye(Vec3.ZERO);

		AuthoritativeTargetValidation validation = fixture.validator(100,
			entity -> EntityFrameResolution.unavailable())
			.validate(OWNER, entityTarget());

		assertEquals(MarkerRejectReason.TARGET_GONE, validation.rejectReason().orElseThrow());
	}

	@Test
	void ordinaryEntityKeepsItsRawAnchorThroughTheProductionFrameFacade() {
		Fixture fixture = new Fixture();
		fixture.entity.setPos(2.0, 3.0, 4.0);
		fixture.player.setEye(new Vec3(2.0, 3.0, 4.0));
		boolean previousSable = ModContext.HasSable;

		try {
			ModContext.HasSable = false;

			AuthoritativeTargetValidation validation = fixture.productionValidator(1)
				.validate(OWNER, entityTarget());

			assertTrue(validation.isAccepted());
			assertEquals(new MarkerAnchor(2.0, 3.0, 4.0), validation.validatedTarget().orElseThrow().anchor());
		} finally {
			ModContext.HasSable = previousSable;
		}
	}

	private static EntityParentFrame translation(double x, double y, double z) {
		return EntityParentFrame.copyOf(new Matrix4d().translation(x, y, z), new Quaterniond());
	}

	private static Target.EntityTarget entityTarget() {
		return new Target.EntityTarget("minecraft:overworld", ENTITY_ID);
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe");
			var field = unsafe.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return Objects.requireNonNull(type.cast(
				unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	@NotNullByDefault
	private static final class Fixture {
		// These ports need no running server, chunks, connection or player data.
		private final Server server = allocate(Server.class);
		private final Players players = allocate(Players.class);
		private final World level = allocate(World.class);
		private final Player player = allocate(Player.class);
		private final TestEntity entity = new TestEntity();

		private Fixture() {
			server.players = players;
			players.player = player;
			player.level = level;
			level.entity = entity;
			entity.setUUID(ENTITY_ID);
		}

		private MinecraftAuthoritativeTargetValidator validator(int range,
			MinecraftAuthoritativeTargetValidator.EntityFrameResolver frames) {
			return new MinecraftAuthoritativeTargetValidator(server, range, true,
				(owner, target) -> TargetNameJsonCodec.UNKNOWN, new ExternalBlockServerProviderRegistry(), frames);
		}

		private MinecraftAuthoritativeTargetValidator productionValidator(int range) {
			return new MinecraftAuthoritativeTargetValidator(server, range, true,
				(owner, target) -> TargetNameJsonCodec.UNKNOWN, new ExternalBlockServerProviderRegistry());
		}
	}

	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;

		private Server() {
			super(null, null, null, null, null, null, null, null);
		}

		@Override
		public DedicatedPlayerList getPlayerList() {
			return Objects.requireNonNull(players);
		}
	}

	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable Player player;

		private Players() {
			super(null, null, null);
		}

		@Override
		public @Nullable ServerPlayer getPlayer(UUID id) {
			return id.equals(OWNER) ? player : null;
		}
	}

	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private @Nullable World level;

		private Player() {
			super(null, null, null, null);
		}

		private void setEye(Vec3 eye) {
			try {
				var position = Entity.class.getDeclaredField("position");
				position.setAccessible(true);
				position.set(this, new Vec3(eye.x, eye.y - getEyeHeight(), eye.z));
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(failure);
			}
		}

		@Override
		public UUID getUUID() {
			return OWNER;
		}

		@Override
		public ServerLevel serverLevel() {
			return Objects.requireNonNull(level);
		}
	}

	@NotNullByDefault
	private static final class World extends ServerLevel {
		private @Nullable Entity entity;

		private World() {
			super(null, null, null, null, null, null, null, false, 0, List.of(), false, null);
		}

		@Override
		public ResourceKey<Level> dimension() {
			return Level.OVERWORLD;
		}

		@Override
		public @Nullable Entity getEntity(UUID id) {
			return entity != null && id.equals(entity.getUUID()) ? entity : null;
		}
	}

	@NotNullByDefault
	private static final class TestEntity extends Entity {
		private TestEntity() {
			super(EntityType.COW, null);
		}

		@Override
		public boolean isAlive() {
			return true;
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {}

		@Override
		protected void readAdditionalSaveData(CompoundTag tag) {}

		@Override
		protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
