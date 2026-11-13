package nx.pingwheel.common.integration.sable.client;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityParentFrame;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class SableClientEntityFramesTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void renderRoleForwardsInterpolationWithoutCachingAndUnavailableNeverBecomesIdentity() {
		Entity entity = new TestEntity(); UUID id = UUID.randomUUID();
		SableClientEntityFrames.Access source = (value, partialTick, work) -> {
			assertSame(entity, value);
			return EntityFrameResolution.contained(EntityParentFrame.copyOf(new Matrix4d().translation(partialTick, 0, 0), new Quaterniond()), id);
		};
		assertEquals(new Vec3(.25, 0, 0), SableClientEntityFrames.resolve(entity, .25F, source).frame().orElseThrow().toWorld(Vec3.ZERO));
		assertEquals(new Vec3(.75, 0, 0), SableClientEntityFrames.resolve(entity, .75F, source).frame().orElseThrow().toWorld(Vec3.ZERO));
		assertEquals(EntityFrameResolution.Status.UNAVAILABLE, SableClientEntityFrames.resolve(entity, .5F,
			(value, partialTick, work) -> { throw new NoClassDefFoundError(); }).status());
		assertEquals(EntityFrameResolution.Status.UNAVAILABLE, SableClientEntityFrames.resolve(entity, Float.NaN,
			(value, partialTick, work) -> { throw new AssertionError("nonfinite tick must not enter provider"); }).status());
	}
	@NotNullByDefault
	private static final class TestEntity extends Entity {
		TestEntity() { super(EntityType.COW, null); }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
